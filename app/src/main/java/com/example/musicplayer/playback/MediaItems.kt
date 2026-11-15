package com.example.musicplayer.playback

import android.content.ContentUris
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.musicplayer.data.model.Song
import java.util.concurrent.atomic.AtomicLong

private val queueEntryCounter = AtomicLong()

/**
 * Builds a queue entry for [Song]. The media id is "<songId>#<n>" so the same song can appear in the
 * queue more than once while every entry still has a unique, stable key.
 */
fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId("$id#${queueEntryCounter.incrementAndGet()}")
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(displayAlbum)
                .setAlbumArtist(albumArtist.ifEmpty { null })
                .setGenre(genre.ifEmpty { null })
                .setReleaseYear(year.takeIf { it > 0 })
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

val MediaItem.songId: Long
    get() = mediaId.substringBefore('#').toLongOrNull() ?: -1L

/** Items sent through a MediaController lose their URI; restore it on the session side. */
fun MediaItem.withPlayableUri(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri
        ?: songId.takeIf { it >= 0 }?.let {
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it)
        }
        ?: return this
    return buildUpon().setUri(uri).build()
}
