package com.example.musicplayer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.example.musicplayer.data.db.SongOverrideEntity
import com.example.musicplayer.data.enrich.MetadataOverlay
import com.example.musicplayer.data.model.Album
import com.example.musicplayer.data.model.Artist
import com.example.musicplayer.data.model.Folder
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the device music library from MediaStore and keeps it up to date. [songs] has metadata corrections
 * ([overrides], from enrichment) applied on top, so every screen and the player see the fixed values.
 */
class MediaStoreRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    overrides: Flow<Map<Long, SongOverrideEntity>>,
) {
    /** Exactly what MediaStore reports; null until the first scan completes. */
    private val _rawSongs = MutableStateFlow<List<Song>?>(null)
    val rawSongs: StateFlow<List<Song>?> = _rawSongs.asStateFlow()

    private val overrideMap: StateFlow<Map<Long, SongOverrideEntity>?> =
        overrides.stateIn(scope, SharingStarted.Eagerly, null)

    private val overlaid: StateFlow<List<Song>?> =
        combine(_rawSongs.filterNotNull(), overrideMap.filterNotNull()) { raw, o ->
            LibraryGrouping.sortSongs(MetadataOverlay.applyAll(raw, o))
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, null)

    val songs: StateFlow<List<Song>> = overlaid
        .map { it.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True once at least one scan has completed (and corrections are applied). */
    val loaded: StateFlow<Boolean> = overlaid
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val albums: StateFlow<List<Album>> = songs
        .map { LibraryGrouping.albums(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = combine(songs, albums) { s, a -> LibraryGrouping.artists(s, a) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<Folder>> = songs
        .map { LibraryGrouping.folders(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private var observerRegistered = false
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // MediaStore fires many notifications during a scan; debounce them.
            refresh(debounceMs = 1_000)
        }
    }

    fun refresh(debounceMs: Long = 0) {
        if (!observerRegistered) {
            context.contentResolver.registerContentObserver(collection, true, observer)
            observerRegistered = true
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _rawSongs.value = querySongs()
        }
    }

    /** The uncorrected library, scanning it first if nothing has yet (e.g. in a background worker). */
    suspend fun awaitRawSongs(): List<Song> {
        if (_rawSongs.value == null) withContext(Dispatchers.Main) { if (refreshJob == null) refresh() }
        return _rawSongs.filterNotNull().first()
    }

    /**
     * Moves the songs' files into the absolute directory [destDir] (created if needed). The caller must already
     * hold write access to them (see MediaStore.createWriteRequest). Returns how many moved and how many failed.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun moveSongs(songs: List<Song>, destDir: String): MoveResult = withContext(Dispatchers.IO) {
        val destRoot = FolderPaths.volumeRoot(destDir)
        val relative = FolderPaths.relativeDir(destDir)
        var moved = 0
        var failed = 0
        for (song in songs) {
            when {
                song.path.substringBeforeLast('/') == destDir -> Unit // already there
                destRoot == null || relative.isNullOrEmpty() || FolderPaths.volumeRoot(song.path) != destRoot -> failed++
                else -> try {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$relative/") }
                    if (context.contentResolver.update(song.uri, values, null, null) > 0) moved++ else failed++
                } catch (_: Exception) {
                    // e.g. a file with the same name already exists there
                    failed++
                }
            }
        }
        refresh()
        MoveResult(moved, failed)
    }

    /** Looks up songs by id, preserving the given order (and duplicates) and dropping ids that no longer exist. */
    suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Song>()
        ids.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            querySongs(
                selection = "${MediaStore.Audio.Media._ID} IN ($placeholders)",
                args = chunk.map { it.toString() }.toTypedArray(),
            ).forEach { found[it.id] = it }
        }
        val current = songs.value.associateBy { it.id }
        val o = overrideMap.value.orEmpty()
        return ids.mapNotNull { id -> current[id] ?: found[id]?.let { MetadataOverlay.apply(it, o[id]) } }
    }

    private suspend fun querySongs(
        selection: String? = null,
        args: Array<String>? = null,
    ): List<Song> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            // Deprecated for file access, but still the only way to get the full folder path on every API level.
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(MediaStore.Audio.AudioColumns.GENRE, MediaStore.Audio.AudioColumns.ALBUM_ARTIST)
        } else {
            emptyArray()
        }
        val fullSelection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (selection != null) append(" AND ($selection)")
        }
        val result = ArrayList<Song>()
        try {
            context.contentResolver.query(collection, projection, fullSelection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                @Suppress("DEPRECATION")
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val genreCol = c.getColumnIndex("genre")
                val albumArtistCol = c.getColumnIndex("album_artist")
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += Song(
                        id = id,
                        title = c.getString(titleCol).orUnknown("Unknown title"),
                        artist = c.getString(artistCol).orUnknown("Unknown artist"),
                        artistId = c.getLong(artistIdCol),
                        album = c.getString(albumCol).orUnknown("Unknown album"),
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        dateAdded = c.getLong(dateCol),
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        path = if (dataCol >= 0) c.getString(dataCol).orEmpty() else "",
                        genre = if (genreCol >= 0) c.getString(genreCol).orEmpty() else "",
                        albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol).orEmpty() else "",
                        size = if (sizeCol >= 0) c.getLong(sizeCol) else 0,
                        dateModified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0,
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted (yet); the UI asks for it and refreshes afterwards.
        }
        result
    }

    private fun String?.orUnknown(fallback: String): String =
        if (isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this
}

data class MoveResult(val moved: Int, val failed: Int)
