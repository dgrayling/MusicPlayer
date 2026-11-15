package com.example.musicplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.data.LibraryGrouping
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.EmptyState
import com.example.musicplayer.ui.components.LocalOpenSettings
import com.example.musicplayer.ui.components.SelectionTopBar
import com.example.musicplayer.ui.components.rememberMoveSongsFlow
import com.example.musicplayer.ui.components.rememberSongSelection
import com.example.musicplayer.ui.components.PlayShuffleButtons
import com.example.musicplayer.ui.components.SongRow
import com.example.musicplayer.ui.components.pluralize

/**
 * Folders tab: a flat list of every folder that directly holds songs, shown by its own name only. Parent
 * folders appear only if they hold songs themselves, and then with just those songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(vm: AppViewModel, onSearch: () -> Unit, onFolder: (String) -> Unit) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val openSettings = LocalOpenSettings.current
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (folders.isEmpty()) {
            if (!loaded) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.padding(top = 64.dp))
                }
            } else {
                EmptyState(Icons.Filled.Folder, "No music here", "Folders with music in them appear here.", Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(folders, key = { it.path }) { folder ->
                ListItem(
                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pluralize(folder.songs.size, "song")) },
                    leadingContent = { Artwork(null, size = 52.dp, placeholder = Icons.Filled.Folder) },
                    modifier = Modifier.clickable { onFolder(folder.path) },
                )
            }
        }
    }
}

@Composable
fun FolderDetailScreen(vm: AppViewModel, path: String, onBack: () -> Unit, onFolder: (String) -> Unit) {
    FolderBrowser(vm, title = path.substringAfterLast('/'), path = path, onFolder = onFolder, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBrowser(
    vm: AppViewModel,
    title: String,
    path: String,
    onFolder: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val contents = remember(folders, path) { LibraryGrouping.folderContents(folders, path) }
    val allSongs = contents.songs
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val openSettings = LocalOpenSettings.current
    val selection = rememberSongSelection()
    LaunchedEffect(contents.songs) { selection.retain(contents.songs) }
    val move = rememberMoveSongsFlow(vm, folders, onMoved = selection::clear)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selection.active) {
                SelectionTopBar(selection, contents.songs, move)
            } else TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (onSearch != null) {
                        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                        IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (allSongs.isEmpty()) {
            if (!loaded) {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.padding(top = 64.dp))
                }
            } else {
                EmptyState(Icons.Filled.Folder, "No music here", "Folders with music in them appear here.", Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    contents.folder.location + "/" + contents.folder.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PlayShuffleButtons(
                    onPlay = { vm.player.play(allSongs) },
                    onShuffle = { vm.player.play(allSongs, shuffle = true) },
                )
                Text(
                    pluralize(allSongs.size, "song"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            itemsIndexed(contents.songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == player.currentSongId,
                    onClick = { if (selection.active) selection.toggle(song) else vm.player.play(contents.songs, index) },
                    selected = if (selection.active) song.id in selection.ids else null,
                    onLongClick = { selection.toggle(song) },
                )
            }
        }
    }
}
