package com.example.musicplayer.data.enrich

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.musicplayer.MusicApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Background online lookup. Each run works for at most [BUDGET_MS] (WorkManager stops workers after
 * about 10 minutes), then queues a follow-up run if songs are still waiting.
 */
class EnrichmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MusicApp).container
        if (!container.settings.enrichEnabled.first()) return Result.success()
        val songs = container.mediaRepository.awaitRawSongs()
        val engine = container.enrichment
        engine.runLocalPass(songs)
        return try {
            val result = engine.runOnlinePass(songs, deadline = System.currentTimeMillis() + BUDGET_MS, isStopped = { isStopped })
            engine.pruneHttpCache()
            if (result.remaining > 0 && !isStopped) {
                EnrichmentScheduler(applicationContext).continueLater(container.settings.enrichWifiOnly.first())
            }
            Result.success()
        } catch (e: TransientHttpException) {
            Log.w(TAG, "Lookup interrupted, will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EnrichmentWorker"
        val BUDGET_MS: Long = TimeUnit.MINUTES.toMillis(8)
    }
}

/** Schedules [EnrichmentWorker]: a daily sweep, plus one-off runs for new songs or "Fix now". */
class EnrichmentScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun oneTime(wifiOnly: Boolean) = OneTimeWorkRequestBuilder<EnrichmentWorker>()
        .setConstraints(constraints(wifiOnly))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(TAG)
        .build()

    fun schedulePeriodic(wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<EnrichmentWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(wifiOnly))
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Run soon; does nothing if a run is already queued or running. */
    fun runNow(wifiOnly: Boolean) {
        wm.enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, oneTime(wifiOnly))
    }

    /** Queue another run after the current one finishes. */
    fun continueLater(wifiOnly: Boolean) {
        wm.enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.APPEND_OR_REPLACE, oneTime(wifiOnly))
    }

    fun cancelAll() {
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(ONE_OFF)
    }

    /** True while a run is executing, false otherwise. */
    val isRunning: Flow<Boolean> = wm.getWorkInfosByTagFlow(TAG).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    /** True when a run is waiting for its constraints (e.g. Wi-Fi). */
    val isWaiting: Flow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(ONE_OFF)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }

    companion object {
        const val TAG = "metadata-enrichment"
        private const val PERIODIC = "metadata-enrichment-daily"
        private const val ONE_OFF = "metadata-enrichment-now"
    }
}
