package com.example.musicplayer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.EmptyState
import com.example.musicplayer.ui.components.SectionHeader
import com.example.musicplayer.ui.components.SongRow

private const val MAX_PER_SECTION = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onAlbum: (Long) -> Unit,
    onArtist: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val q = query.trim()
    val songHits = remember(q, songs) {
        if (q.isEmpty()) emptyList()
        else songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }
    val albumHits = remember(q, albums) {
        if (q.isEmpty()) emptyList() else albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    val artistHits = remember(q, artists) {
        if (q.isEmpty()) emptyList() else artists.filter { it.name.contains(q, true) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Songs, albums, artists") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        if (q.isNotEmpty() && songHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()) {
            EmptyState(Icons.Filled.Search, "No results", "Nothing matches \"$q\".", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (artistHits.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(artistHits.take(MAX_PER_SECTION), key = { "artist-${it.id}" }) { artist ->
                    ListItem(
                        headlineContent = { Text(artist.name) },
                        leadingContent = {
                            Artwork(artist.artworkUri, size = 48.dp, shape = CircleShape, placeholder = Icons.Filled.Person)
                        },
                        modifier = Modifier.clickable { onArtist(artist.id) },
                    )
                }
            }
            if (albumHits.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(albumHits.take(MAX_PER_SECTION), key = { "album-${it.id}" }) { album ->
                    ListItem(
                        headlineContent = { Text(album.title) },
                        supportingContent = { Text(album.artist) },
                        leadingContent = { Artwork(album.artworkUri, size = 48.dp, placeholder = Icons.Filled.Album) },
                        modifier = Modifier.clickable { onAlbum(album.id) },
                    )
                }
            }
            if (songHits.isNotEmpty()) {
                item { SectionHeader("Songs") }
                itemsIndexed(songHits, key = { _, s -> "song-${s.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = song.id == player.currentSongId,
                        onClick = { vm.player.play(songHits, index) },
                    )
                }
            }
        }
    }
}
