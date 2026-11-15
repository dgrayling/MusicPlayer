package com.example.musicplayer.data.enrich

import com.example.musicplayer.data.model.Song

/**
 * The offline half of enrichment: tidies junk and fills missing fields from the file path, and learns
 * one preferred spelling per artist/album across the whole library. Pure; no I/O.
 */
object LocalCleanup {

    /** Corrected values; null = same as MediaStore's. */
    data class Fix(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val confidence: Double = 0.9,
    )

    private data class Inferred(
        val title: String,
        val artist: String?,
        val album: String?,
        val albumMissing: Boolean,
        val year: Int?,
        val track: Int?,
        val usedPath: Boolean,
    )

    private fun dirOf(s: Song) = s.path.substringBeforeLast('/', "")

    /**
     * What a song's folder says about how to read its tags.
     * @param isAlbum false for playlist-like folders (see [folderHints]): the folder name isn't an album.
     * @param swappedTitle set when the folder's title and artist tags are swapped (see [folderHints]): the
     *   title tag every song shares (as a [TextCleaner.key]), which is really the collection name, while the
     *   artist tag is the song's name.
     */
    data class FolderHints(val isAlbum: Boolean = true, val swappedTitle: String? = null, val swappedTitleText: String? = null)

    /** [computeInfer] is pure in the song's fields; each song is inferred several times per pass. */
    private val inferCache = object : LinkedHashMap<Pair<Song, FolderHints>, Inferred>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Song, FolderHints>, Inferred>?) = size > 10_000
    }

    private fun infer(s: Song, hints: FolderHints = FolderHints()): Inferred {
        val key = s to hints
        synchronized(inferCache) { inferCache[key] }?.let { return it }
        return computeInfer(s, hints).also { synchronized(inferCache) { inferCache[key] = it } }
    }

    private fun computeInfer(s: Song, hints: FolderHints): Inferred {
        // Swapped folder: the artist tag holds the song's name, the shared title tag the collection's name.
        // Every song in the folder (tagged or not) then gets that collection as its album, so it stays one album.
        if (hints.swappedTitle != null) {
            val folderName = s.path.substringBeforeLast('/').substringAfterLast('/').trim()
            val albumIsFallback = TextCleaner.isPlaceholder(s.album) || s.album.trim() == folderName
            val isSwapped = TextCleaner.key(s.title) == hints.swappedTitle
            val collection = hints.swappedTitleText ?: s.title.trim()
            val album = if (albumIsFallback) collection else s.album
            val adjusted = if (isSwapped) {
                s.copy(title = s.artist.trim(), artist = MediaStoreUnknown, album = album)
            } else {
                s.copy(album = album)
            }
            return computeInfer(adjusted, hints.copy(swappedTitle = null)).copy(usedPath = true)
        }
        val folderIsAlbum = hints.isAlbum
        val parsed by lazy { FilenameParser.parse(s.path, useFolders = folderIsAlbum) }
        val base = s.path.substringAfterLast('/').substringBeforeLast('.').trim()
        // MediaStore falls back to the file name for the title and the folder name for the album.
        // Tags read with the wrong encoding are recovered ("扩牴梷杴띭畳楣" → "birth·gt·music").
        val tagTitle = TextCleaner.recoverMisdecoded(s.title) ?: s.title
        val tagArtist = TextCleaner.recoverMisdecoded(s.artist) ?: s.artist
        val titleFromFile = TextCleaner.isPlaceholder(tagTitle) || (base.isNotEmpty() && tagTitle.trim() == base)
        val artistMissing = TextCleaner.isPlaceholder(tagArtist)
        val parentDir = s.path.substringBeforeLast('/', "").substringAfterLast('/')
        // An album equal to the folder name is MediaStore's fallback for untagged files.
        val albumIsFolderName = parentDir.isNotEmpty() && s.album.trim() == parentDir.trim()
        val albumMissing = TextCleaner.isPlaceholder(s.album) || FilenameParser.isGenericDir(s.album) ||
            (albumIsFolderName && !folderIsAlbum)
        var usedPath = false

        var title = tagTitle
        var artist: String? = if (artistMissing) null else tagArtist
        if (titleFromFile && s.path.isNotEmpty()) {
            // "01.mp3" says nothing about the title.
            parsed.title?.takeUnless { t -> t.all { it.isDigit() || it.isWhitespace() } }?.let { title = it; usedPath = true }
            if (artist == null) parsed.artist?.let { artist = it; usedPath = true }
            // A file named only by its number ("16.mp3") reads like its tagged siblings: "Track 16".
            if (BARE_TRACK.matches(title.trim())) { title = "Track ${title.trim()}"; usedPath = true }
        } else if (artist == null) {
            // "Artist - Title" crammed into the title tag.
            FilenameParser.splitArtistTitle(TextCleaner.stripJunk(tagTitle))?.let { (a, t) ->
                artist = a; title = t; usedPath = true
            }
        }
        if (artist == null && s.path.isNotEmpty()) parsed.artist?.let { artist = it; usedPath = true }

        // Folder-name albums get parsed ("Album (1999)" → "Album", 1999).
        val albumFromFolder = !albumMissing && albumIsFolderName
        val album = when {
            albumMissing && s.path.isNotEmpty() -> parsed.album?.also { usedPath = true }
            albumMissing -> null
            // Tidy layouts name folders after albums too, so only a different parse counts as a guess.
            albumFromFolder && parsed.album != null && parsed.album != s.album -> parsed.album.also { usedPath = true }
            else -> s.album
        }
        val year = if (s.year > 0) null else if (s.path.isNotEmpty()) parsed.year else null
        val track = if (s.trackNumber > 0) null else if (s.path.isNotEmpty()) parsed.trackNumber else null
        // "1 No differences" where the track tag is 1: the number is the track, not part of the title.
        if (s.displayTrackNumber > 0) {
            val prefix = "${s.displayTrackNumber} "
            val padded = "%02d ".format(s.displayTrackNumber)
            listOf(padded, prefix).firstOrNull { title.startsWith(it) && title.length > it.length + 1 }
                ?.let { title = title.removePrefix(it) }
        }
        return Inferred(title, artist, album, albumMissing, year, track, usedPath || year != null || track != null)
    }

    /** The cleaned artist this song would get before any alias is applied (used to learn spellings). */
    fun cleanedArtist(s: Song, hints: FolderHints = FolderHints()): String? =
        infer(s, hints).artist?.let(TextCleaner::cleanArtist)

    fun cleanedAlbum(s: Song, hints: FolderHints = FolderHints()): String? =
        infer(s, hints).album?.let(TextCleaner::cleanAlbum)

    /**
     * Reads each folder as a whole, because one file alone can't tell you its tags are in the wrong fields:
     *  - **Swapped title/artist.** In a real album the title changes per song and the artist mostly doesn't.
     *    If (nearly) every tagged song shares one title but each has a different artist ("Track 10",
     *    "Track 13", …), the artist tag is the song's name and the title tag is the collection's.
     *  - **Playlists.** See [playlistFolders].
     * Only folders that need non-default handling are returned.
     */
    fun folderHints(songs: List<Song>): Map<String, FolderHints> {
        val swapped = songs.filter { it.path.isNotEmpty() }.groupBy(::dirOf).mapNotNull { (dir, members) ->
            val tagged = members.filter { !TextCleaner.isPlaceholder(it.title) && it.artist.isNotBlank() && it.artist != MediaStoreUnknown }
            if (tagged.size < 3) return@mapNotNull null
            // Keys, not raw text: old ID3v1 tags are space-padded ("JAK-X - Original Game Audio   ").
            val (sharedTitle, count) = tagged.groupingBy { TextCleaner.key(it.title) }.eachCount().maxBy { it.value }
            val withShared = tagged.filter { TextCleaner.key(it.title) == sharedTitle }
            val distinctArtists = withShared.map { TextCleaner.key(it.artist) }.distinct().size
            val ok = count >= 3 && count * 5 >= tagged.size * 3 && distinctArtists * 5 >= withShared.size * 4
            if (ok) dir to (sharedTitle to withShared.first().title.trim()) else null
        }.toMap()
        val playlists = playlistFolders(songs.filter { dirOf(it) !in swapped })
        return (swapped.keys + playlists).associateWith { dir ->
            FolderHints(isAlbum = dir !in playlists, swappedTitle = swapped[dir]?.first, swappedTitleText = swapped[dir]?.second)
        }
    }

    fun hintsFor(s: Song, hints: Map<String, FolderHints>): FolderHints = hints[dirOf(s)] ?: FolderHints()

    /**
     * Folders that are playlists, not albums: their songs carry no album tag of their own (MediaStore reports
     * the folder name) yet are by several different artists, e.g. "Download/music/synthwave".
     * Artists here come from tags and file names only, never from the folders being judged.
     */
    fun playlistFolders(songs: List<Song>): Set<String> =
        songs.filter { it.path.isNotEmpty() }.groupBy(::dirOf).filter { (dir, members) ->
            val folderName = dir.substringAfterLast('/')
            // "Untagged": no album of its own, or MediaStore's folder-name fallback on a file without an artist tag.
            val untagged = members.count { s ->
                TextCleaner.isPlaceholder(s.album) || FilenameParser.isGenericDir(s.album) ||
                    (s.album.trim() == folderName.trim() && TextCleaner.isPlaceholder(s.artist))
            }
            val artists = members.mapNotNull { cleanedArtist(it, FolderHints(isAlbum = false))?.let(TextCleaner::key) }.toSet()
            // Untagged album rips are track-numbered ("01 - Title"); grab-bag folders mostly aren't.
            val numbered = members.count { it.trackNumber > 0 || FilenameParser.parse(it.path, useFolders = false).trackNumber != null }
            untagged * 2 >= members.size && (artists.size >= 3 || numbered * 2 < members.size)
        }.keys

    /**
     * @param artistAlias canonical artist name for a [TextCleaner.key], if one is known (from MusicBrainz,
     *   the user, or [preferredSpellings]).
     * @param albumSpelling preferred album spelling for "artistKey|albumKey", if one is known.
     */
    fun fix(
        s: Song,
        artistAlias: (String) -> String? = { null },
        albumSpelling: (String) -> String? = { null },
        hints: FolderHints = FolderHints(),
    ): Fix {
        val inf = infer(s, hints)
        val artistClean = inf.artist?.let(TextCleaner::cleanArtist)
        val artist = artistClean?.let { artistAlias(TextCleaner.key(it)) ?: it }
        val title = TextCleaner.cleanTitle(inf.title, artist ?: artistClean)
        val albumClean = inf.album?.let(TextCleaner::cleanAlbum)
        val album = albumClean?.let { albumSpelling(albumKey(artist ?: s.artist, it)) ?: it }
            ?: if (inf.albumMissing) UNKNOWN_ALBUM else null

        // In a swapped folder the artist tag was the song's name; with no real artist known, say so explicitly
        // (a null here would mean "keep the tag", i.e. show "Track 10" as the artist again).
        val artistWasTitle = hints.swappedTitle != null && TextCleaner.key(s.title) == hints.swappedTitle
        return Fix(
            title = title.takeIf { it != s.title },
            artist = (artist ?: UNKNOWN_ARTIST.takeIf { artistWasTitle })?.takeIf { it != s.artist },
            album = album?.takeIf { it != s.album },
            year = inf.year,
            trackNumber = inf.track,
            confidence = if (inf.usedPath) 0.7 else 0.9,
        )
    }

    fun albumKey(artist: String, album: String) = TextCleaner.key(artist) + "|" + TextCleaner.key(album)

    /**
     * For every key with several spellings in the library ("Beatles", "The Beatles", "the beatles"), the one
     * to use everywhere: the most common, then the one with capitals, then "The …", then the longest.
     */
    fun preferredSpellings(values: List<String>): Map<String, String> =
        values.groupBy(TextCleaner::key)
            .filterKeys { it.isNotEmpty() }
            .filterValues { v -> v.distinct().size > 1 }
            .mapValues { (_, v) ->
                v.groupingBy { it }.eachCount().entries.maxWith(
                    compareBy<Map.Entry<String, Int>> { it.value }
                        .thenBy { e -> e.key.any { it.isUpperCase() } }
                        .thenBy { it.key.startsWith("The ") }
                        .thenBy { it.key.length },
                ).key
            }

    /** 1–3 digits only, so a real title like "1979" is never touched. */
    private val BARE_TRACK = Regex("^\\d{1,3}$")

    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_ARTIST = "Unknown artist"
    private const val MediaStoreUnknown = "<unknown>"
}
