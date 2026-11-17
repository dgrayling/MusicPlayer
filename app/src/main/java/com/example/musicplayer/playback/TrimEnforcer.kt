package com.example.musicplayer.playback

import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.data.trim.TrackTrim
import com.example.musicplayer.data.trim.TrimRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Makes the player honour each song's start and end marks (the user's, or detected silence).
 *
 * Rather than clipping media items, which would hide the trimmed parts from the seek bar, this jumps to
 * the start mark whenever a song begins (including repeats and "restart" via previous), and at the end
 * mark moves on as if the song had ended. Seeking manually into a trimmed part still works.
 */
@OptIn(UnstableApi::class)
class TrimEnforcer(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val trims: TrimRepository,
    private val findSong: suspend (Long) -> Song?,
) : Player.Listener {

    // All state is touched only on the main thread (the player's looper and a MainScope).
    private val cache = HashMap<Long, TrackTrim>()
    private val loading = HashMap<Long, Deferred<TrackTrim>>()
    /** Bumped on every invalidation, so a load that started before it doesn't cache a stale result. */
    private var generation = 0
    private var applyJob: Job? = null
    private var endMessage: PlayerMessage? = null

    fun start() {
        player.addListener(this)
        scope.launch {
            trims.changes.collect { songId ->
                generation++
                if (songId == TrimRepository.ALL_SONGS) {
                    cache.clear()
                    loading.clear()
                } else {
                    cache.remove(songId)
                    loading.remove(songId)
                }
                val current = player.currentMediaItem?.songId
                if (songId == TrimRepository.ALL_SONGS || songId == current) apply(snapToStart = false)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = apply(snapToStart = true)

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Back at the very top of the same song: a repeat, "previous" restarting it, or a seek to 0.
        val restarted = oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
            newPosition.positionMs < RESTART_WINDOW_MS &&
            (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || reason == Player.DISCONTINUITY_REASON_SEEK)
        if (restarted) apply(snapToStart = true)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        // The queue changed (moved/removed items): re-attach the end mark to the current item's new place.
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            val item = player.currentMediaItem ?: return cancelEnd()
            cache[item.songId]?.let { scheduleEnd(item.mediaId, it) }
        }
    }

    private fun apply(snapToStart: Boolean) {
        applyJob?.cancel()
        val item = player.currentMediaItem ?: return cancelEnd()
        val cached = cache[item.songId]
        if (cached != null) {
            enforce(item.mediaId, cached, snapToStart)
            prefetchNext()
            return
        }
        cancelEnd()
        val pending = load(item.songId)
        applyJob = scope.launch {
            val trim = pending.await()
            // Whatever silence played while we were detecting it is skipped from here, unless we're already past it.
            if (player.currentMediaItem?.mediaId == item.mediaId) enforce(item.mediaId, trim, snapToStart)
            prefetchNext()
        }
    }

    private fun enforce(mediaId: String, trim: TrackTrim, snapToStart: Boolean) {
        if (snapToStart && trim.startMs > 0 && player.currentPosition < trim.startMs - 50) player.seekTo(trim.startMs)
        scheduleEnd(mediaId, trim)
    }

    /** Detect silence in the next song ahead of time, so its start is skipped without a gap. */
    private fun prefetchNext() {
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET) load(player.getMediaItemAt(next).songId)
    }

    /** Not tied to the caller's job: skipping past a song mustn't throw away a half-finished detection. */
    private fun load(songId: Long): Deferred<TrackTrim> {
        cache[songId]?.let { return CompletableDeferred(it) }
        loading[songId]?.let { return it }
        val startedAt = generation
        val deferred = scope.async {
            val trim = try {
                findSong(songId)?.let { trims.trimFor(it) } ?: TrackTrim.NONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TrackTrim", "Couldn't load trim for song $songId", e)
                TrackTrim.NONE
            }
            if (generation == startedAt) {
                cache[songId] = trim
                loading.remove(songId)
            }
            trim
        }
        loading[songId] = deferred
        return deferred
    }

    private fun scheduleEnd(mediaId: String, trim: TrackTrim) {
        cancelEnd()
        val end = trim.endMs ?: return
        endMessage = player.createMessage { _, _ -> onEndReached(mediaId, trim) }
            .setLooper(Looper.getMainLooper())
            .setPosition(player.currentMediaItemIndex, end)
            .setDeleteAfterDelivery(false) // fire again on every pass, e.g. with repeat-one
            .send()
    }

    private fun cancelEnd() {
        endMessage?.cancel()
        endMessage = null
    }

    /** Does what the player would do at the natural end of the song. */
    private fun onEndReached(mediaId: String, trim: TrackTrim) {
        if (player.currentMediaItem?.mediaId != mediaId) return
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> player.seekTo(trim.startMs)
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            player.duration != C.TIME_UNSET -> player.seekTo(player.duration) // end of the queue
            else -> player.pause()
        }
    }

    private companion object {
        const val RESTART_WINDOW_MS = 300L
    }
}
