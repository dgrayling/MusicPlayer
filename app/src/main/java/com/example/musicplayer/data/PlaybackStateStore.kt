package com.example.musicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore(name = "playback_state")

data class SavedPlaybackState(
    val songIds: List<Long>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/** Persists the queue and position so playback can resume after the app is killed. */
class PlaybackStateStore(private val context: Context) {

    suspend fun save(state: SavedPlaybackState) {
        context.playbackDataStore.edit {
            it[QUEUE] = state.songIds.joinToString(",")
            it[INDEX] = state.index
            it[POSITION] = state.positionMs
            it[SHUFFLE] = state.shuffle
            it[REPEAT] = state.repeatMode
        }
    }

    suspend fun load(): SavedPlaybackState? {
        val prefs = context.playbackDataStore.data.first()
        val ids = prefs[QUEUE]?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.isEmpty()) return null
        return SavedPlaybackState(
            songIds = ids,
            index = prefs[INDEX] ?: 0,
            positionMs = prefs[POSITION] ?: 0L,
            shuffle = prefs[SHUFFLE] ?: false,
            repeatMode = prefs[REPEAT] ?: Player.REPEAT_MODE_ONE,
        )
    }

    private companion object {
        val QUEUE = stringPreferencesKey("queue")
        val INDEX = intPreferencesKey("index")
        val POSITION = longPreferencesKey("position")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat_mode")
    }
}
