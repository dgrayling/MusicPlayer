package com.example.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.musicplayer.MainActivity
import com.example.musicplayer.MusicApp
import com.example.musicplayer.data.SavedPlaybackState
import com.example.musicplayer.data.model.Song
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer and exposes it through a MediaSession, which gives us the media notification,
 * lock-screen controls, Bluetooth/headset buttons and background playback.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private val container get() = (application as MusicApp).container

    /** Don't persist anything until we've tried restoring, so we never overwrite saved state with an empty queue. */
    private var restoreAttempted = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Repeat the current song forever unless the user changes the repeat mode.
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(playerListener)
        TrimEnforcer(player, scope, container.trimRepository, ::findSong).start()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        restoreState()
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (player.isPlaying) saveState()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun restoreState() {
        scope.launch {
            try {
                val saved = container.playbackStateStore.load() ?: return@launch
                if (player.mediaItemCount > 0) return@launch
                val songs = container.mediaRepository.songsByIds(saved.songIds)
                if (songs.isEmpty() || player.mediaItemCount > 0) return@launch

                val savedCurrentId = saved.songIds.getOrNull(saved.index)
                val index = songs.indexOfFirst { it.id == savedCurrentId }
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeatMode
                player.setMediaItems(
                    songs.map { it.toMediaItem() },
                    index.coerceAtLeast(0),
                    if (index >= 0) saved.positionMs else 0L,
                )
                player.prepare()
            } finally {
                restoreAttempted = true
            }
        }
    }

    private suspend fun findSong(id: Long): Song? =
        container.mediaRepository.rawSongs.value?.firstOrNull { it.id == id }
            ?: container.mediaRepository.songsByIds(listOf(id)).firstOrNull()

    private fun saveState() {
        if (!restoreAttempted) return
        val snapshot = SavedPlaybackState(
            songIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).songId },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        // Application scope so the write survives this service being destroyed.
        container.appScope.launch { container.playbackStateStore.save(snapshot) }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) {
                saveState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = player.currentMediaItem?.mediaMetadata?.title ?: "track"
            Toast.makeText(this@PlaybackService, "Couldn't play $title", Toast.LENGTH_SHORT).show()
            // Skip unreadable/missing files rather than stopping playback entirely.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())
    }
}
