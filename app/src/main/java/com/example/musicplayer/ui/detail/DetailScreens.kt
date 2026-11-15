package com.example.musicplayer.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.EmptyState
import com.example.musicplayer.ui.components.LocalSongActions
import com.example.musicplayer.ui.components.MenuItem
import com.example.musicplayer.ui.components.PlayShuffleButtons
import com.example.musicplayer.ui.components.PlaylistNameDialog
import com.example.musicplayer.ui.components.SectionHeader
import com.example.musicplayer.ui.components.SongRow
import com.example.musicplayer.ui.components.formatDuration
import com.example.musicplayer.ui.components.pluralize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
                scrollBehavior = scroll,
            )
        },
        content = content,
    )
}

/** Overflow actions that apply to a whole collection of songs. */
@Composable
private fun CollectionMenu(songs: List<Song>, extra: @Composable (dismiss: () -> Unit) -> Unit = {}) {
    val actions = LocalSongActions.current
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (songs.isNotEmpty()) {
            MenuItem("Play next", Icons.Filled.SkipNext) { open = false; actions.playNext(songs) }
            MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { open = false; actions.addToQueue(songs) }
            MenuItem("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) {
                open = false; actions.addToPlaylist(songs)
            }
        }
        extra { open = false }
    }
}

@Composable
private fun DetailHeader(
    artworkUri: android.net.Uri?,
    title: String,
    subtitle: String,
    meta: String,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Album,
) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artworkUri, size = 120.dp, placeholder = placeholder)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AlbumDetailScreen(vm: AppViewModel, albumId: Long, onBack: () -> Unit, onArtist: (Long) -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }

    DetailScaffold(album?.title ?: "", onBack, actions = { if (album != null) CollectionMenu(album.songs) }) { padding ->
        if (album == null) return@DetailScaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Column(Modifier.clickable { onArtist(album.artistId) }) {
                    DetailHeader(
                        album.artworkUri,
                        album.title,
                        album.artist,
                        listOfNotNull(
                            album.year.takeIf { it > 0 }?.toString(),
                            pluralize(album.songs.size, "song"),
                            formatDuration(album.durationMs),
                        ).joinToString(" · "),
                    )
                }
                PlayShuffleButtons(
                    onPlay = { vm.player.play(album.songs) },
                    onShuffle = { vm.player.play(album.songs, shuffle = true) },
                )
            }
            itemsIndexed(album.songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == player.currentSongId,
                    onClick = { vm.player.play(album.songs, index) },
                    showArtwork = false,
                    showAlbum = false,
                    trackNumber = song.displayTrackNumber,
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(vm: AppViewModel, artistId: Long, onBack: () -> Unit, onAlbum: (Long) -> Unit) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val artist = artists.firstOrNull { it.id == artistId }

    DetailScaffold(artist?.name ?: "", onBack, actions = { if (artist != null) CollectionMenu(artist.songs) }) { padding ->
        if (artist == null) return@DetailScaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    pluralize(artist.songs.size, "song"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PlayShuffleButtons(
                    onPlay = { vm.player.play(artist.songs) },
                    onShuffle = { vm.player.play(artist.songs, shuffle = true) },
                )
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(artist.albums, key = { it.id }) { album ->
                        Column(
                            Modifier
                                .width(140.dp)
                                .clickable { onAlbum(album.id) }
                                .padding(4.dp),
                        ) {
                            Artwork(album.artworkUri, size = 132.dp, placeholder = Icons.Filled.Album)
                            Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (album.year > 0) {
                                Text(album.year.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                SectionHeader("Songs")
            }
            itemsIndexed(artist.songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == player.currentSongId,
                    onClick = { vm.player.play(artist.songs, index) },
                )
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(vm: AppViewModel, playlistId: Long, onBack: () -> Unit) {
    val name by remember(playlistId) { vm.playlistName(playlistId) }.collectAsStateWithLifecycle(initialValue = "")
    val songs by remember(playlistId) { vm.playlistSongs(playlistId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val player by vm.playerState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    DetailScaffold(
        name.orEmpty(),
        onBack,
        actions = {
            CollectionMenu(songs) { dismiss ->
                MenuItem("Rename", Icons.Filled.Edit) { dismiss(); renaming = true }
                MenuItem("Delete playlist", Icons.Filled.Delete) { dismiss(); confirmDelete = true }
            }
        },
    ) { padding ->
        if (songs.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                "This playlist is empty",
                "Use \"Add to playlist\" from any song's menu.",
                Modifier.padding(padding),
            )
            return@DetailScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    "${pluralize(songs.size, "song")} · ${formatDuration(songs.sumOf { it.durationMs })}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PlayShuffleButtons(
                    onPlay = { vm.player.play(songs) },
                    onShuffle = { vm.player.play(songs, shuffle = true) },
                )
                Spacer(Modifier.height(4.dp).fillMaxWidth())
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == player.currentSongId,
                    onClick = { vm.player.play(songs, index) },
                    onRemove = { vm.removeFromPlaylist(playlistId, song) },
                )
            }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Rename",
            initialName = name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                vm.renamePlaylist(playlistId, it)
                renaming = false
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${name.orEmpty()}\" will be deleted. Your music files won't be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePlaylist(playlistId)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
