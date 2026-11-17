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
import com.example.musicplayer.data.trim.TrimMarks
import com.example.musicplayer.ui.components.pluralize
import com.example.musicplayer.data.dedupe.DuplicateFolderFinder
import com.example.musicplayer.data.dedupe.FolderRemoval
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DuplicateScan {
    data object Idle : DuplicateScan
    data class Scanning(val done: Int, val total: Int) : DuplicateScan
    data class Done(val removals: List<FolderRemoval>, val ignoreNames: Boolean) : DuplicateScan
}

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

    // --- duplicate folders ---
    private val duplicateFinder = container.duplicateFolderFinder
    private val _duplicateScan = MutableStateFlow<DuplicateScan>(DuplicateScan.Idle)
    val duplicateScan: StateFlow<DuplicateScan> = _duplicateScan.asStateFlow()
    private var duplicateJob: Job? = null

    fun scanDuplicateFolders(ignoreNames: Boolean) {
        duplicateJob?.cancel()
        duplicateJob = viewModelScope.launch {
            _duplicateScan.value = DuplicateScan.Scanning(0, 0)
            val songs = library.awaitRawSongs()
            val found = duplicateFinder.find(songs, ignoreNames) { done, total ->
                _duplicateScan.value = DuplicateScan.Scanning(done, total)
            }
            _duplicateScan.value = DuplicateScan.Done(found, ignoreNames)
        }
    }

    fun cancelDuplicateScan() {
        duplicateJob?.cancel()
        _duplicateScan.value = DuplicateScan.Idle
    }

    /**
     * Re-checks the chosen removals against the library as it is now. Returns the songs to trash for the ones
     * that still hold, or null (with a message) when none do.
     */
    fun prepareFolderRemoval(chosen: List<FolderRemoval>, onMessage: (String) -> Unit): List<FolderRemoval>? {
        val current = library.rawSongs.value.orEmpty()
        val valid = chosen.filter { DuplicateFolderFinder.stillValid(it, current) }
        if (valid.size < chosen.size) {
            onMessage("${pluralize(chosen.size - valid.size, "folder")} changed since the scan and will be skipped")
        }
        return valid.takeIf { it.isNotEmpty() }
    }

    fun songUris(ids: List<Long>): List<android.net.Uri> {
        val byId = library.rawSongs.value.orEmpty().associateBy { it.id }
        return ids.mapNotNull { byId[it]?.uri }
    }

    /** Called after Android has moved the folders' songs to the trash. */
    fun onFoldersTrashed(removed: List<FolderRemoval>, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            playlistRepo.replaceSongs(removed.fold(emptyMap()) { acc, r -> acc + r.replacements })
            library.refresh()
            val songs = removed.sumOf { it.songIds.size }
            onMessage("Moved ${pluralize(removed.size, "folder")} (${pluralize(songs, "song")}) to the trash")
            (_duplicateScan.value as? DuplicateScan.Done)?.let { done ->
                val gone = removed.map { it.folder }.toSet()
                _duplicateScan.value = done.copy(removals = done.removals.filter { it.folder !in gone })
            }
        }
    }

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

    // ---- Track start/end marks ----

    private val trims = container.trimRepository

    val skipSilence: StateFlow<Boolean> = settings.skipSilence.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSkipSilence(skip: Boolean) {
        viewModelScope.launch { settings.setSkipSilence(skip) }
    }

    fun trimMarks(songId: Long): Flow<TrimMarks> = trims.observe(songId)

    /** Null marks fall back to detected silence (or the song's own start/end). */
    fun setTrim(songId: Long, startMs: Long?, endMs: Long?) {
        viewModelScope.launch { trims.setMarks(songId, startMs, endMs) }
    }

    fun setTrimStart(songId: Long, startMs: Long?) {
        viewModelScope.launch { trims.setStart(songId, startMs) }
    }

    fun setTrimEnd(songId: Long, endMs: Long?) {
        viewModelScope.launch { trims.setEnd(songId, endMs) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { AppViewModel((this[APPLICATION_KEY] as MusicApp).container) }
        }
    }
}
