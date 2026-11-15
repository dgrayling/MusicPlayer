package com.example.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musicplayer.data.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val currentItem: MediaItem? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ONE,
    val queue: List<MediaItem> = emptyList(),
) {
    val currentSongId: Long get() = currentItem?.songId ?: -1L
}

/** UI-side handle to the playback service, via a MediaController. */
class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish(c, rebuildQueue = true)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player, rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    private fun publish(p: Player, rebuildQueue: Boolean) {
        val queue = if (rebuildQueue) {
            (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        } else {
            _state.value.queue
        }
        _state.value = PlayerUiState(
            currentItem = p.currentMediaItem,
            currentIndex = p.currentMediaItemIndex,
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            durationMs = p.duration.coerceAtLeast(0L),
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            queue = queue,
        )
    }

    // ---- Commands ----

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(songs.indices)
            c.setMediaItems(songs.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun playNext(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) play(songs) else c.addMediaItems(songs.map { it.toMediaItem() })
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun skipToQueueItem(index: Int) = withController {
        it.seekToDefaultPosition(index)
        it.play()
    }

    fun moveQueueItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }

    fun removeQueueItem(index: Int) = withController { it.removeMediaItem(index) }

    fun clearQueue() = withController { it.clearMediaItems() }
}
