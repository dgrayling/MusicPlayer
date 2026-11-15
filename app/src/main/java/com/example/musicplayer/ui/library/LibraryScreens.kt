package com.example.musicplayer.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.EmptyState
import com.example.musicplayer.ui.components.LocalOpenSettings
import com.example.musicplayer.ui.components.SelectionTopBar
import com.example.musicplayer.ui.components.rememberMoveSongsFlow
import com.example.musicplayer.ui.components.rememberSongSelection
import com.example.musicplayer.ui.components.PlayShuffleButtons
import com.example.musicplayer.ui.components.PlaylistNameDialog
import com.example.musicplayer.ui.components.SongRow
import com.example.musicplayer.ui.components.pluralize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScaffold(
    title: String,
    onSearch: () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    /** Replaces the normal top bar while songs are selected. */
    selectionBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val openSettings = LocalOpenSettings.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selectionBar != null) {
                selectionBar()
            } else {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                        IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    },
                    scrollBehavior = scroll,
                )
            }
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
private fun LoadingOrEmpty(loaded: Boolean, icon: ImageVector, title: String, body: String, padding: PaddingValues) {
    if (!loaded) {
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.padding(top = 64.dp))
        }
    } else {
        EmptyState(icon, title, body, Modifier.padding(padding))
    }
}

@Composable
fun SongsScreen(vm: AppViewModel, onSearch: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val selection = rememberSongSelection()
    LaunchedEffect(songs) { selection.retain(songs) }
    val move = rememberMoveSongsFlow(vm, folders, onMoved = selection::clear)

    LibraryScaffold(
        "Songs",
        onSearch,
        selectionBar = if (selection.active) ({ SelectionTopBar(selection, songs, move) }) else null,
    ) { padding ->
        if (songs.isEmpty()) {
            LoadingOrEmpty(loaded, Icons.Filled.LibraryMusic, "No music found", "Copy audio files to this device's Music folder.", padding)
            return@LibraryScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                PlayShuffleButtons(
                    onPlay = { vm.player.play(songs) },
                    onShuffle = { vm.player.play(songs, shuffle = true) },
                )
                Text(
                    pluralize(songs.size, "song"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == player.currentSongId,
                    onClick = { if (selection.active) selection.toggle(song) else vm.player.play(songs, index) },
                    selected = if (selection.active) song.id in selection.ids else null,
                    onLongClick = { selection.toggle(song) },
                )
            }
        }
    }
}

@Composable
fun AlbumsScreen(vm: AppViewModel, onSearch: () -> Unit, onAlbum: (Long) -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()

    LibraryScaffold("Albums", onSearch) { padding ->
        if (albums.isEmpty()) {
            LoadingOrEmpty(loaded, Icons.Filled.Album, "No albums", "Albums appear here once you have music on the device.", padding)
            return@LibraryScaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            items(albums, key = { it.id }) { album ->
                Column(
                    Modifier
                        .padding(4.dp)
                        .clickable { onAlbum(album.id) }
                        .padding(4.dp),
                ) {
                    Artwork(
                        album.artworkUri,
                        placeholder = Icons.Filled.Album,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(
                        album.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistsScreen(vm: AppViewModel, onSearch: () -> Unit, onArtist: (Long) -> Unit) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val loaded by vm.libraryLoaded.collectAsStateWithLifecycle()

    LibraryScaffold("Artists", onSearch) { padding ->
        if (artists.isEmpty()) {
            LoadingOrEmpty(loaded, Icons.Filled.Person, "No artists", "Artists appear here once you have music on the device.", padding)
            return@LibraryScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(artists, key = { it.id }) { artist ->
                ListItem(
                    headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralize(artist.songs.size, "song"))
                    },
                    leadingContent = {
                        Artwork(artist.artworkUri, size = 52.dp, shape = CircleShape, placeholder = Icons.Filled.Person)
                    },
                    modifier = Modifier.clickable { onArtist(artist.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(vm: AppViewModel, onSearch: () -> Unit, onPlaylist: (Long) -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LibraryScaffold(
        "Playlists",
        onSearch,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New playlist") },
            )
        },
    ) { padding ->
        if (playlists.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                "No playlists yet",
                "Create one, or use \"Add to playlist\" from any song's menu.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = { Text(pluralize(playlist.songCount, "song")) },
                        leadingContent = {
                            Artwork(null, size = 52.dp, placeholder = Icons.AutoMirrored.Filled.PlaylistPlay)
                        },
                        modifier = Modifier
                            .animateItem()
                            .clickable { onPlaylist(playlist.id) },
                    )
                }
            }
        }
    }

    if (creating) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = {
                vm.createPlaylist(it)
                creating = false
            },
        )
    }
}
