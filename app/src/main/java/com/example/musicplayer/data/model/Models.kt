package com.example.musicplayer.data.model

import android.content.ContentUris
import android.net.Uri
import com.example.musicplayer.data.enrich.TextCleaner

private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    /** MediaStore track number; may be encoded as disc * 1000 + track. */
    val trackNumber: Int,
    val year: Int,
    val dateAdded: Long,
    val uri: Uri,
    /** Absolute file path, or "" if MediaStore didn't report one. */
    val path: String = "",
    val genre: String = "",
    val albumArtist: String = "",
    val size: Long = 0,
    /** Seconds since the epoch, as MediaStore reports it. */
    val dateModified: Long = 0,
    /** Cover art found online by metadata enrichment; preferred over the embedded art. */
    val remoteArtworkUrl: String? = null,
) {
    val artworkUri: Uri get() = remoteArtworkUrl?.let(Uri::parse) ?: albumArtUri(albumId)
    val displayTrackNumber: Int get() = trackNumber % 1000
    /** The album name to show next to the song, or null when it's only a placeholder like "Unknown album". */
    val displayAlbum: String? get() = album.takeUnless(TextCleaner::isPlaceholder)
    /** Identifies this exact file version: when it changes, enrichment looks at the song again. */
    val fingerprint: String get() = "$path|$size|$dateModified"
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val year: Int,
    val songs: List<Song>,
) {
    val artworkUri: Uri get() = songs.firstOrNull { it.remoteArtworkUrl != null }?.artworkUri ?: albumArtUri(id)
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

data class Artist(
    val id: Long,
    val name: String,
    val albums: List<Album>,
    val songs: List<Song>,
) {
    val artworkUri: Uri? get() = albums.firstOrNull()?.artworkUri
}

data class Folder(
    /** Absolute directory path, e.g. /storage/emulated/0/Music/Rock. */
    val path: String,
    val songs: List<Song>,
) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { path }

    /** Parent location shown under the name, with the storage root made readable. */
    val location: String get() = path.substringBeforeLast('/', "")
        .replaceFirst(Regex("^/storage/emulated/\\d+"), "Internal storage")
        .replaceFirst(Regex("^/storage/([^/]+)"), "SD card ($1)")
        .ifEmpty { "/" }
}

data class FolderContents(
    /** The folder itself, with every song beneath it (direct songs first, then subfolders). */
    val folder: Folder,
    val subfolders: List<Folder>,
    val songs: List<Song>,
)

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
)
