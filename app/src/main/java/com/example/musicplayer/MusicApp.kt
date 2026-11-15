package com.example.musicplayer

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.musicplayer.data.MediaStoreRepository
import com.example.musicplayer.data.PlaybackStateStore
import com.example.musicplayer.data.PlaylistRepository
import com.example.musicplayer.data.SettingsStore
import com.example.musicplayer.data.db.AppDatabase
import com.example.musicplayer.data.enrich.CachedHttp
import com.example.musicplayer.data.enrich.EnrichmentRepository
import com.example.musicplayer.data.enrich.EnrichmentScheduler
import com.example.musicplayer.data.enrich.ItunesClient
import com.example.musicplayer.data.enrich.MusicBrainzClient
import com.example.musicplayer.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MusicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startEnrichment()
    }
}

/** Simple manual dependency container shared by the UI and the playback service. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = AppDatabase.build(context)
    val settings = SettingsStore(context)

    private val http = CachedHttp(
        client = CachedHttp.defaultClient(),
        dao = database.enrichmentDao(),
        userAgent = "MusicPlayer/${BuildConfig.VERSION_NAME}" +
            BuildConfig.MUSICBRAINZ_CONTACT.takeIf { it.isNotBlank() }?.let { " ( $it )" }.orEmpty(),
        limiters = CachedHttp.defaultLimiters(),
    )
    val enrichment = EnrichmentRepository(
        dao = database.enrichmentDao(),
        mb = MusicBrainzClient(http),
        itunes = ItunesClient(http),
        networkCalls = { http.networkCalls.get() },
    )
    val enrichmentScheduler by lazy { EnrichmentScheduler(context) }

    val mediaRepository = MediaStoreRepository(context, appScope, enrichment.overrides)
    val playlistRepository = PlaylistRepository(database.playlistDao())
    val playbackStateStore = PlaybackStateStore(context)
    val playerConnection = PlayerConnection(context)

    /**
     * Keeps background enrichment in step with the settings, and on every library change runs the cheap
     * local pass right away, queueing an online run when songs are waiting for a lookup.
     */
    @OptIn(FlowPreview::class)
    fun startEnrichment() {
        appScope.launch {
            combine(settings.enrichEnabled, settings.enrichWifiOnly) { on, wifi -> on to wifi }
                .distinctUntilChanged()
                .collect { (on, wifi) ->
                    safely { if (on) enrichmentScheduler.schedulePeriodic(wifi) else enrichmentScheduler.cancelAll() }
                }
        }
        appScope.launch {
            mediaRepository.rawSongs.filterNotNull().debounce(2_000).collectLatest { songs ->
                if (!settings.enrichEnabled.first()) return@collectLatest
                safely {
                    val started = System.currentTimeMillis()
                    val changed = enrichment.runLocalPass(songs)
                    Log.i("Enrichment", "Local pass: ${songs.size} songs, $changed updated in ${System.currentTimeMillis() - started} ms")
                    if (enrichment.hasDueWork(songs)) enrichmentScheduler.runNow(settings.enrichWifiOnly.first())
                }
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Enrichment", "Background metadata step failed", e)
        }
    }
}
