package com.example.musicplayer.data.enrich

import java.text.Normalizer

/**
 * Pure text clean-up for messy tags. Two outputs per value:
 *  - a **display** value (junk removed, tidied, but otherwise as written), and
 *  - a **match key** (aggressively normalized) used for cache keys and fuzzy comparison, so
 *    "Beatles, The", "the beatles " and "The Beatles" all share one key.
 */
object TextCleaner {

    private val JUNK_WORDS = listOf(
        "official\\s+(?:music\\s+|lyrics?\\s+|hd\\s+|hq\\s+)?(?:video|audio|visuali[sz]er|clip)",
        "lyrics?(?:\\s+video)?", "audio(?:\\s+only)?", "music\\s+video", "video\\s+clip", "visuali[sz]er",
        "hd", "hq", "4k", "1080p", "720p", "explicit", "clean", "free\\s+download", "download",
        "\\d{2,3}\\s*kbps", "\\d{2,3}\\s*k", "mp3", "m4a", "flac", "wav", "ogg", "320", "256", "192", "128",
    ).joinToString("|")

    /**
     * These functions are pure and called on the same strings many times per pass (grouping, overlay, keys).
     * Android's ICU regex engine is slow per call, so results are memoized (bounded, thread-safe).
     */
    private class Memo(private val max: Int) {
        private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
        }
        @Synchronized fun get(k: String): String? = map[k]
        @Synchronized fun put(k: String, v: String) { map[k] = v }
        inline fun getOrPut(k: String, compute: () -> String): String = get(k) ?: compute().also { put(k, it) }
    }
    private val junkCache = Memo(20_000)
    private val keyCache = Memo(20_000)
    private val keyFeatCache = Memo(5_000)

    // NB: Android's regex engine (ICU) is stricter than the JVM's that unit tests use: always escape literal
    // brackets and braces, even where the JVM would accept them bare.

    /** A whole bracketed group whose content is junk, e.g. "[320 kbps]" or "(Official Video)". */
    private val BRACKETED_JUNK = Regex(
        "\\s*[\\[(【\\{]\\s*(?:$JUNK_WORDS)(?:\\s*[/|,&+\\-]\\s*(?:$JUNK_WORDS))*\\s*[\\])】\\}]",
        RegexOption.IGNORE_CASE,
    )

    /** Download-site watermarks: "www.site.com", "site.net", "[MP3Juices]", "- SongsPK". */
    private val DOMAIN = Regex(
        "\\s*[\\[(]?\\s*(?:https?://)?(?:www\\.)?[a-z0-9-]+\\.(?:com|net|org|ru|to|cc|info|in|me|io|co|pk|mobi|biz|xyz|club)(?:/\\S*)?\\s*[\\])]?",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_TAGS = Regex(
        "\\s*[\\[(]\\s*(?:mp3\\s*juices?|songs?\\s*pk|mr\\s*jatt|djpunjab|pagalworld|tubidy|y2mate|savefrom|zippy\\w*)\\s*[\\])]",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Leading track numbers. Only stripped with an explicit separator ("01 - ", "1. ", "03_") or when
     * zero-padded ("07 Song"), so real titles like "7 Rings", "99 Problems" or "1979" survive.
     */
    private val LEADING_TRACK = Regex("^\\s*(?:(?:cd|disc)\\s*\\d+\\s*[-._]?\\s*)?(?:\\d{1,3}\\s*[-._)]\\s*|0\\d\\s+)(?=\\S)", RegexOption.IGNORE_CASE)

    private val FEAT = Regex("(?<![^\\s(\\[])(?:ft\\.?|feat\\.?|featuring)(?=\\s)", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("\\s*[(\\[]?\\s*(?:ft\\.?|feat\\.?|featuring|with)\\s+.*$", RegexOption.IGNORE_CASE)
    private val TRAILING_THE = Regex("^(.+),\\s*(the|a|an)$", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    // Trailing dashes only go when detached ("Song -"), so stylized names like "No differences -a0v-" keep theirs.
    private val EDGE_JUNK = Regex("^[\\s\\-_.,|~·•:]+|\\s+[\\s\\-_|~·•:,]*$|[|~·•:,]+$")
    /** yt-dlp style video ids: "Title-dQw4w9WgXcQ" or "Title [dQw4w9WgXcQ]". */
    private val YOUTUBE_ID = Regex("(?:\\s*\\[([A-Za-z0-9_\\-]{11})\\]|\\s*-([A-Za-z0-9_\\-]{11})-?)$")
    /** Unbracketed quality/video junk at the end: "Halo 3 Theme - HD 1080p", "Song _ Music Visualization". */
    private val TRAILING_JUNK = Regex(
        "\\s+[-_|~]\\s*(?:hd|hq|4k|1080p|720p|official\\s+(?:music\\s+)?video|lyrics?(?:\\s+video)?|music\\s+visuali[sz]ation|visuali[sz]er|audio)(?:\\s+(?:hd|hq|4k|1080p|720p))*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    /** Emoji and their joiners/variation selectors (not other symbols like ℃ or ☆, which can be part of titles). */
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2700}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val ARTIST_SUFFIX = Regex("\\s*-\\s*Topic$|(?<=\\p{Ll})VEVO$", RegexOption.IGNORE_CASE)
    private val EMPTY_BRACKETS = Regex("\\s*(?:\\(\\s*\\)|\\[\\s*\\]|\\{\\s*\\})")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Strips junk shared by every field: watermarks, quality tags, empty brackets, stray separators. */
    fun stripJunk(raw: String): String = junkCache.getOrPut(raw) { computeStripJunk(raw) }

    private fun computeStripJunk(raw: String): String {
        // NFC only: compatibility forms (NFKC) would rewrite "℃" or "①" in what the user sees; keys fold them.
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        // Before the "_" → " " step below, which would break ids containing "_".
        s = stripYoutubeId(s)
        // Files ripped as "Song_Name_Here" have no spaces at all.
        if (' ' !in s.trim() && s.count { it == '_' } >= 1) s = s.replace('_', ' ')
        s = SITE_TAGS.replace(s, "")
        s = DOMAIN.replace(s, "")
        // Repeat: "(Official Video) [HD]" needs two passes once the first group is gone.
        repeat(3) { s = BRACKETED_JUNK.replace(s, "") }
        s = EMPTY_BRACKETS.replace(s, "")
        s = EMOJI.replace(s, "")
        s = WHITESPACE.replace(s, " ")
        s = EDGE_JUNK.replace(s, "").trim()
        val withoutTail = EDGE_JUNK.replace(TRAILING_JUNK.replace(s, ""), "").trim()
        return withoutTail.ifEmpty { s }
    }

    /** Removes a trailing YouTube video id, but only when it looks random (so "-Christmases" survives). */
    fun stripYoutubeId(s: String): String {
        val m = YOUTUBE_ID.find(s) ?: return s
        val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val looksRandom = id.any { it.isDigit() || it == '_' || it == '-' } || id.drop(1).any { it.isUpperCase() }
        return if (looksRandom && m.range.first > 0) s.substring(0, m.range.first).trimEnd() else s
    }

    /**
     * Tags written as Latin-1 but read as UTF-16 turn "birth·gt·music" into "扩牴梷杴띭畳楣". Re-reading the
     * bytes gets the text back; real CJK text never survives the round trip as mostly-ASCII, so it's left alone.
     * Returns the recovered text, or null if [s] doesn't look misdecoded.
     */
    fun recoverMisdecoded(s: String?): String? {
        if (s == null || s.length < 2) return null
        val wide = s.count { it.code >= 0x3400 }
        if (wide * 2 < s.length) return null
        val bytes = s.toByteArray(Charsets.UTF_16BE)
        if (bytes.any { val b = it.toInt() and 0xFF; b < 0x20 || b in 0x7F..0x9F }) return null
        val text = String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000')
        val asciiish = text.count { it.isLetterOrDigit() && it.code < 0x80 || it == ' ' }
        return text.takeIf { asciiish * 10 >= it.length * 7 }
    }

    fun stripLeadingTrackNumber(s: String): String {
        val stripped = LEADING_TRACK.replace(s, "")
        return if (stripped.isBlank()) s else stripped.trim()
    }

    /** "ft X" / "Featuring X" → "feat. X". */
    fun normalizeFeat(s: String): String = FEAT.replace(s, "feat.")

    /** "Beatles, The" → "The Beatles". */
    fun fixArticle(s: String): String =
        TRAILING_THE.matchEntire(s.trim())?.let { m ->
            val article = m.groupValues[2].replaceFirstChar { it.uppercaseChar() }
            "$article ${m.groupValues[1].trim()}"
        } ?: s

    /** Title-cases text typed in all lowercase; anything with capitals is assumed deliberate and kept. */
    fun fixCase(s: String): String {
        if (s.length < 3 || s.any { it.isUpperCase() } || s.none { it.isLetter() }) return s
        // Scripts without case (e.g. Japanese) mixed in: leave it alone.
        if (s.any { it.isLetter() && it.code >= 0x250 }) return s
        return s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecaseChar() } }
    }

    fun cleanTitle(raw: String, artist: String? = null): String {
        var s = stripLeadingTrackNumber(stripJunk(raw))
        // "Artist - Title" stuffed into the title field.
        if (!artist.isNullOrBlank()) {
            val a = artist.trim()
            if (s.length > a.length && s.startsWith(a, ignoreCase = true)) {
                val rest = s.substring(a.length).trimStart()
                val without = rest.removePrefixAny("-", "–", "—", ":")
                // Only "Artist - Title" / "Artist: Title"; never "Beckoning" or "Beck Song".
                if (without !== rest && without.isNotBlank()) s = without.trim()
            }
        }
        return fixCase(normalizeFeat(s)).ifBlank { raw.trim() }
    }

    fun cleanArtist(raw: String): String =
        fixCase(fixArticle(normalizeFeat(ARTIST_SUFFIX.replace(stripJunk(raw), "")))).ifBlank { raw.trim() }

    fun cleanAlbum(raw: String): String =
        fixCase(normalizeFeat(stripJunk(raw))).ifBlank { raw.trim() }

    /**
     * Match key: diacritics folded, lowercase, leading article and "feat." tail dropped, "&" → "and",
     * punctuation collapsed. Use for cache keys and similarity, never for display.
     */
    fun key(raw: String, dropFeat: Boolean = true): String =
        (if (dropFeat) keyCache else keyFeatCache).getOrPut(raw) { computeKey(raw, dropFeat) }

    private fun String.removePrefixAny(vararg prefixes: String): String =
        prefixes.firstOrNull { startsWith(it) }?.let { removePrefix(it) } ?: this

    private fun computeKey(raw: String, dropFeat: Boolean): String {
        var s = fixArticle(stripJunk(raw))
        if (dropFeat) s = FEAT_TAIL.replace(s, "")
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        s = DIACRITICS.replace(s, "").lowercase()
            .replace("&", " and ")
            .replace("+", " and ")
        s = NON_ALNUM.replace(s, " ").trim()
        if (s.startsWith("the ")) s = s.removePrefix("the ")
        return WHITESPACE.replace(s, " ")
    }

    /** Is this a placeholder rather than a real value ("Unknown artist", "<unknown>", "Track 01", "")? */
    fun isPlaceholder(s: String?): Boolean {
        if (s.isNullOrBlank()) return true
        val k = s.trim().lowercase()
        return k == "<unknown>" || k.startsWith("unknown") || k == "various" || k == "untitled" ||
            PLACEHOLDER.matches(k)
    }

    private val PLACEHOLDER = Regex("^(?:track|audio|untitled|new recording)\\s*\\d*$")
}
