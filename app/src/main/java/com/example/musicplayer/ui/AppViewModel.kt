package com.example.musicplayer.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.musicplayer.AppContainer
import com.example.musicplayer.MusicApp
import com.example.musicplayer.data.db.SongOverrideEntity
import com.example.musicplayer.data.db.SongSuggestionEntity
import com.example.musicplayer.data.enrich.EnrichmentRunStats
import com.example.musicplayer.data.model.Playlist
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.ui.components.pluralize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Single app-wide view model: the library is small enough that per-screen view models add nothing. */
class AppViewModel(container: AppContainer) : ViewModel() {
    private val library = container.mediaRepository
    private val playlistRepo = container.playlistRepository
    private val settings = container.settings

    val player = container.playerConnection
    val playerState = player.state

    val songs = library.songs
    val albums = library.albums
    val artists = library.artists
    val folders = library.folders
    val libraryLoaded = library.loaded

    val playlists: StateFlow<List<Playlist>> =
        playlistRepo.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showAlbumsTab: StateFlow<Boolean> =
        container.settings.showAlbumsTab.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowAlbumsTab(show: Boolean) {
        viewModelScope.launch { settings.setShowAlbumsTab(show) }
    }

    /** Moves files (write access must already be granted) and reports a short summary. */
    fun moveSongs(songs: List<Song>, destDir: String, onDone: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return onDone("Moving files needs Android 11 or newer")
        viewModelScope.launch {
            val (moved, failed) = library.moveSongs(songs, destDir)
            val where = destDir.substringAfterLast('/')
            onDone(
                buildString {
                    append("Moved ${pluralize(moved, "song")} to \"$where\"")
                    if (failed > 0) append(" · ${pluralize(failed, "song")} couldn't be moved")
                },
            )
        }
    }

    fun refreshLibrary() = library.refresh()

    // --- metadata enrichment ---
    private val enrichment = container.enrichment
    private val scheduler = container.enrichmentScheduler

    val enrichEnabled: StateFlow<Boolean> = settings.enrichEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val enrichWifiOnly: StateFlow<Boolean> = settings.enrichWifiOnly.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val enrichStats: StateFlow<EnrichmentRunStats> = enrichment.stats
    val enrichStatusCounts: StateFlow<Map<String, Int>> =
        enrichment.statusCounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val enrichRunning: StateFlow<Boolean> = scheduler.isRunning.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val enrichWaiting: StateFlow<Boolean> = scheduler.isWaiting.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Suggestions joined with the song as currently shown. */
    val suggestions: StateFlow<List<Pair<Song, SongSuggestionEntity>>> =
        combine(enrichment.suggestions, songs) { sugs, all ->
            val byId = all.associateBy { it.id }
            sugs.mapNotNull { sug -> byId[sug.songId]?.let { it to sug } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnrichEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setEnrichEnabled(enabled) }
    }

    fun setEnrichWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { settings.setEnrichWifiOnly(wifiOnly) }
    }

    /** Runs the local clean-up now and queues an online lookup (which honours the Wi-Fi-only setting). */
    fun fixMetadataNow() {
        viewModelScope.launch {
            library.rawSongs.value?.let { enrichment.runLocalPass(it) }
            scheduler.runNow(enrichWifiOnly.value)
        }
    }

    private suspend fun relearn() {
        library.rawSongs.value?.let { enrichment.runLocalPass(it) }
    }

    fun resetCorrections() {
        viewModelScope.launch { enrichment.resetAutomatic(); relearn() }
    }

    fun acceptSuggestions(songs: List<Song>) {
        viewModelScope.launch { enrichment.acceptSuggestions(songs) }
    }

    fun rejectSuggestions(songs: List<Song>) {
        viewModelScope.launch { enrichment.rejectSuggestions(songs.map { it.id }) }
    }

    /** Uses [to] for every song whose artist is spelled like [from], now and in future. */
    fun teachArtistAlias(from: String, to: String) {
        viewModelScope.launch { enrichment.teachArtistAlias(from, to); relearn() }
    }

    fun originalSong(id: Long): Song? = library.rawSongs.value?.firstOrNull { it.id == id }

    fun songOverride(id: Long): Flow<SongOverrideEntity?> = enrichment.overrides.map { it[id] }

    fun editSongMetadata(song: Song, title: String, artist: String, album: String, year: Int?, genre: String) {
        val original = originalSong(song.id) ?: song
        viewModelScope.launch { enrichment.editSong(original, title, artist, album, year, genre) }
    }

    fun revertSongMetadata(song: Song) {
        val original = originalSong(song.id) ?: song
        viewModelScope.launch { enrichment.revertToOriginal(original) }
    }

    fun searchSongAgain(song: Song) {
        viewModelScope.launch {
            enrichment.searchAgain(song.id)
            relearn()
            scheduler.runNow(enrichWifiOnly.value)
        }
    }

    fun playlistName(id: Long): Flow<String?> = playlistRepo.playlistName(id)

    fun playlistSongs(id: Long): Flow<List<Song>> =
        combine(playlistRepo.songIds(id), songs) { ids, all ->
            val byId = all.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        viewModelScope.launch { playlistRepo.create(name, songs.map { it.id }) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { playlistRepo.rename(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistRepo.delete(id) }
    }

    fun addToPlaylist(id: Long, songs: List<Song>) {
        viewModelScope.launch { playlistRepo.addSongs(id, songs.map { it.id }) }
    }

    fun removeFromPlaylist(id: Long, song: Song) {
        viewModelScope.launch { playlistRepo.removeSong(id, song.id) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { AppViewModel((this[APPLICATION_KEY] as MusicApp).container) }
        }
    }
}
