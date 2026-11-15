package com.example.musicplayer.data.enrich

/** What could be inferred from a file's path when its tags are missing. Every field is optional. */
data class ParsedPath(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
)

/**
 * Infers metadata from paths like
 *  - `Music/Artist/Album (1999)/03 - Title.mp3`
 *  - `Music/Artist - Album/03. Title.mp3`
 *  - `Download/03 - Artist - Title [320kbps].mp3`
 *  - `Download/Artist - Title.mp3`
 */
object FilenameParser {

    /** Folder names that say nothing about the music in them. */
    private val GENERIC_DIRS = setOf(
        "", "0", "music", "musics", "my music", "audio", "audios", "songs", "song", "mp3", "mp3s", "download",
        "downloads", "media", "sounds", "sound", "storage", "emulated", "sdcard", "external", "internal",
        "telegram", "telegram audio", "whatsapp", "whatsapp audio", "bluetooth", "documents", "files",
        "various", "various artists", "va", "unknown", "unknown artist", "unknown album", "new folder",
        "misc", "other", "others", "playlist", "playlists", "recordings", "voice recorder", "ringtones",
        "notifications", "alarms", "podcasts", "android", "data", "self", "primary", "snaptube",
    )

    // A number only counts as a track number when followed by a separator ("01 -", "1.", "03_") or
    // zero-padded ("07 "), so "50 Cent - In Da Club" and "7 Rings" keep their leading digits.
    private val TRACK_ARTIST_TITLE = Regex("^(?:(\\d{1,3})\\s*[-._)]|(0\\d)\\s)\\s*(.+?)\\s+[-–—]\\s+(.+)$")
    private val TRACK_TITLE = Regex("^(?:(\\d{1,3})\\s*[-._)]\\s*|(0\\d)\\s+)(.+)$")
    private val ARTIST_TITLE = Regex("^(.+?)\\s+[-–—]\\s+(.+)$")
    private val BARE_NUMBER = Regex("^(\\d{1,3})$")
    private val SEPARATOR = Regex("\\s+[-–—]\\s+")

    /** Uploader/channel tags that prefix "Tag - Artist - Title" names. */
    private val CHANNEL_TAGS = setOf(
        "mmv", "amv", "gmv", "ncs", "ncs release", "monstercat", "trap nation", "bass nation", "chill nation",
        "proximity", "mrsuicidesheep", "lyrics", "nightcore", "magic music", "audio library",
    )

    /** Descriptions that end "Artist - Title - Piano Tutorial" names; they belong with the title. */
    private val DESCRIPTOR = Regex(
        "^(?:(?:accurate |impossible |easy )?piano (?:tutorial|cover)|piano tutorial \\+ midi|cover|instrumental|" +
            "remix|extended(?: mix)?|original mix|lyrics?|ost|soundtrack|slowed(?: \\+ reverb)?|sped up|nightcore|" +
            "8d audio|bass boosted|full album|official audio)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Splits "Artist - Title" names, coping with three or more parts:
     *  - "MMV – STRLGHT - Clutch" → the channel tag is dropped;
     *  - "Resonance - Home - Piano Tutorial + MIDI" → the trailing description joins the title in parentheses;
     *  - otherwise the first part is the artist and the rest the title.
     * Returns null when there's no separator.
     */
    fun splitArtistTitle(name: String): Pair<String, String>? {
        var parts = name.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts.size >= 3 && parts.first().lowercase() in CHANNEL_TAGS) parts = parts.drop(1)
        if (parts.size >= 3 && DESCRIPTOR.matches(parts.last())) {
            parts = parts.dropLast(2) + "${parts[parts.size - 2]} (${parts.last()})"
        }
        return parts.first() to parts.drop(1).joinToString(" - ")
    }
    private val YEAR_IN_DIR = Regex("[\\[(]?\\b((?:19|20)\\d{2})\\b[\\])]?")
    private val DISC_DIR = Regex("^(?:cd|disc|disk)\\s*\\d+$", RegexOption.IGNORE_CASE)

    private val MONTH_OR_DATE = Regex(
        "^(?:(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\s*\\d{0,4}|\\d{4}\\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*|\\d{4}(?:[-_ ]?\\d{2}){0,2})$",
    )
    private val VOLUME_ID = Regex("^[0-9a-f]{4}-[0-9a-f]{4}$")

    fun isGenericDir(name: String): Boolean {
        val k = name.trim().lowercase()
        return k in GENERIC_DIRS || k.startsWith("storage") || VOLUME_ID.matches(k) ||
            // Backups, export dumps and date-named folders ("mp3backup-2026…", "feb2023", "2022july").
            "backup" in k || "processed" in k || "youtube" in k || MONTH_OR_DATE.matches(k)
    }

    /**
     * @param useFolders false when the song's folder is known to be a playlist rather than an album (see
     *   [LocalCleanup.playlistFolders]); then only the file name is used.
     */
    fun parse(path: String, useFolders: Boolean = true): ParsedPath {
        if (path.isBlank()) return ParsedPath()
        val key = path to useFolders
        synchronized(cache) { cache[key] }?.let { return it }
        return computeParse(path, useFolders).also { synchronized(cache) { cache[key] = it } }
    }

    /** Pure and called several times per song per pass; regexes are slow on Android, so remember results. */
    private val cache = object : LinkedHashMap<Pair<String, Boolean>, ParsedPath>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, ParsedPath>?) = size > 10_000
    }

    private fun computeParse(path: String, useFolders: Boolean): ParsedPath {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val base = TextCleaner.stripJunk(parts.last().substringBeforeLast('.'))
        // Skip "CD1"/"Disc 2" folders: the album is the folder above them.
        val dirs = parts.dropLast(1).filterNot { DISC_DIR.matches(it.trim()) }
        val parentDir = dirs.getOrNull(dirs.size - 1)?.takeUnless(::isGenericDir)
        val grandDir = dirs.getOrNull(dirs.size - 2)?.takeUnless(::isGenericDir)

        var title: String? = null
        var artist: String? = null
        var track: Int? = null
        TRACK_ARTIST_TITLE.matchEntire(base)?.let {
            track = it.groupValues[1].ifEmpty { it.groupValues[2] }.toInt()
            artist = it.groupValues[3]; title = it.groupValues[4]
        } ?: BARE_NUMBER.matchEntire(base)?.let {
            // "10.mp3": a track number and nothing else.
            track = it.groupValues[1].toInt()
        } ?: TRACK_TITLE.matchEntire(base)?.let {
            track = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(); title = it.groupValues[3]
        } ?: splitArtistTitle(base)?.let { (a, t) ->
            artist = a; title = t
        } ?: run { title = base }

        // Folder layout: ".../Artist/Album/file" or ".../Artist - Album/file".
        var album: String? = null
        var year: Int? = null
        if (parentDir != null && useFolders) {
            val m = ARTIST_TITLE.matchEntire(TextCleaner.stripJunk(parentDir))
            val albumRaw: String
            if (m != null && grandDir == null) {
                if (artist == null) artist = m.groupValues[1]
                albumRaw = m.groupValues[2]
            } else {
                albumRaw = parentDir
                if (artist == null && grandDir != null) artist = grandDir
            }
            year = YEAR_IN_DIR.find(albumRaw)?.groupValues?.get(1)?.toInt()
            album = TextCleaner.stripJunk(YEAR_IN_DIR.replace(albumRaw, " ")).ifBlank { null }
            // A folder named after the artist alone isn't an album.
            if (album != null && artist != null && TextCleaner.key(album) == TextCleaner.key(artist!!)) album = null
        }

        return ParsedPath(
            title = title?.trim()?.ifBlank { null },
            artist = artist?.trim()?.ifBlank { null },
            album = album,
            trackNumber = track?.takeIf { it in 1..999 },
            year = year,
        )
    }
}
