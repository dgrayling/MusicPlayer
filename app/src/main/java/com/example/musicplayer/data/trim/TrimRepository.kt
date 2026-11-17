package com.example.musicplayer.data.trim

import android.content.Context
import android.util.Log
import com.example.musicplayer.data.SettingsStore
import com.example.musicplayer.data.db.TrackTrimEntity
import com.example.musicplayer.data.db.TrimDao
import com.example.musicplayer.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The part of a song to play, in ms from the start of the file; [endMs] null plays to the end. */
data class TrackTrim(val startMs: Long = 0, val endMs: Long? = null) {
    companion object {
        val NONE = TrackTrim()
    }
}

/** A song's stored marks, as the trim editor shows them. */
data class TrimMarks(
    val userStartMs: Long? = null,
    val userEndMs: Long? = null,
    val detectedStartMs: Long? = null,
    val detectedEndMs: Long? = null,
    val skipSilence: Boolean = true,
) {
    /** User marks win; detected silence applies only where the user hasn't set a mark, and only when enabled. */
    val effective: TrackTrim
        get() {
            val start = userStartMs ?: detectedStartMs.takeIf { skipSilence } ?: 0L
            val end = (userEndMs ?: detectedEndMs.takeIf { skipSilence })?.takeIf { it >= start + MIN_LENGTH_MS }
            return TrackTrim(start, end)
        }

    companion object {
        /** The shortest stretch a song can be trimmed to. */
        const val MIN_LENGTH_MS = 1_000L

        fun from(row: TrackTrimEntity?, skipSilence: Boolean) = TrimMarks(
            userStartMs = row?.startMs,
            userEndMs = row?.endMs,
            detectedStartMs = row?.soundStartMs,
            detectedEndMs = row?.soundEndMs,
            skipSilence = skipSilence,
        )
    }
}

/** Per-song start/end marks, from the user and from silence detection. */
class TrimRepository(
    private val context: Context,
    private val dao: TrimDao,
    private val settings: SettingsStore,
    private val detect: suspend (Song) -> SoundBounds = { SilenceDetector.detect(context, it.uri) },
) {
    /** One decode at a time; it's background work and shouldn't compete with playback. */
    private val analysisLock = Mutex()
    /** Serializes read-modify-write of rows, so an edit and a finished analysis can't overwrite each other. */
    private val writeLock = Mutex()
    private val edited = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Ids of songs whose trim may have changed; [ALL_SONGS] when it may have changed for every song. */
    val changes: Flow<Long> = merge(
        edited,
        settings.skipSilence.distinctUntilChanged().drop(1).map { ALL_SONGS },
    )

    fun observe(songId: Long): Flow<TrimMarks> =
        combine(dao.observe(songId), settings.skipSilence) { row, skip -> TrimMarks.from(row, skip) }

    /** The trim to play [song] with, first running silence detection on it if that's enabled and hasn't run on this file. */
    suspend fun trimFor(song: Song): TrackTrim {
        val skip = settings.skipSilence.first()
        var row = dao.get(song.id)
        if (skip && row?.analyzedFingerprint != song.fingerprint) row = analyze(song)
        return TrimMarks.from(row, skip).effective
    }

    private suspend fun analyze(song: Song): TrackTrimEntity = analysisLock.withLock {
        dao.get(song.id)?.takeIf { it.analyzedFingerprint == song.fingerprint }?.let { return it }
        val bounds = try {
            val started = System.currentTimeMillis()
            detect(song).also {
                Log.i(TAG, "Analysed \"${song.title}\" in ${System.currentTimeMillis() - started} ms: $it")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Remember the failure too, so an unreadable file isn't decoded again every time it plays.
            Log.w(TAG, "Couldn't analyse ${song.uri}", e)
            SoundBounds(null, null)
        }
        writeLock.withLock {
            // Re-read: the user may have set a mark while we were decoding.
            val row = (dao.get(song.id) ?: TrackTrimEntity(song.id)).copy(
                analyzedFingerprint = song.fingerprint,
                soundStartMs = bounds.startMs,
                soundEndMs = bounds.endMs,
            )
            dao.upsert(row)
            row
        }
    }

    /** Sets (or with null, clears) the user's start mark; an end mark that would no longer come after it is dropped. */
    suspend fun setStart(songId: Long, startMs: Long?) = edit(songId) { row ->
        val start = startMs?.coerceAtLeast(0)
        row.copy(
            startMs = start,
            endMs = row.endMs?.takeIf { start == null || it >= start + TrimMarks.MIN_LENGTH_MS },
        )
    }

    /** Sets (or with null, clears) the user's end mark; a start mark that would no longer come before it is dropped. */
    suspend fun setEnd(songId: Long, endMs: Long?) = edit(songId) { row ->
        row.copy(
            endMs = endMs,
            startMs = row.startMs?.takeIf { endMs == null || it <= endMs - TrimMarks.MIN_LENGTH_MS },
        )
    }

    suspend fun setMarks(songId: Long, startMs: Long?, endMs: Long?) = edit(songId) { row ->
        row.copy(startMs = startMs, endMs = endMs?.takeIf { it >= (startMs ?: 0) + TrimMarks.MIN_LENGTH_MS })
    }

    private suspend fun edit(songId: Long, change: (TrackTrimEntity) -> TrackTrimEntity) {
        writeLock.withLock { dao.upsert(change(dao.get(songId) ?: TrackTrimEntity(songId))) }
        edited.emit(songId)
    }

    companion object {
        const val ALL_SONGS = -1L
        private const val TAG = "TrackTrim"
    }
}
