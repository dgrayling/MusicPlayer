package com.example.musicplayer.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.musicplayer.data.model.Song

/** Actions available from any song's overflow menu, provided once at the top of the UI. */
class SongActions(
    val playNext: (List<Song>) -> Unit,
    val addToQueue: (List<Song>) -> Unit,
    val addToPlaylist: (List<Song>) -> Unit,
    val goToAlbum: (Song) -> Unit,
    val goToArtist: (Song) -> Unit,
    val showMessage: (String) -> Unit,
    /** Opens the song's metadata (original vs corrected) for review or editing. */
    val showInfo: (Song) -> Unit = {},
    /** Opens the Now Playing screen. */
    val openNowPlaying: () -> Unit = {},
)

val LocalSongActions = staticCompositionLocalOf<SongActions> { error("SongActions not provided") }

/** Opens the Settings screen; provided at the top of the UI. */
val LocalOpenSettings = staticCompositionLocalOf<() -> Unit> { {} }

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun pluralize(count: Int, word: String) = "$count $word${if (count == 1) "" else "s"}"

/** Album art with a music-note placeholder underneath for when there's no embedded art. */
@Composable
fun Artwork(
    uri: Uri?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    placeholder: ImageVector = Icons.Filled.MusicNote,
) {
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        modifier = sized
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxSize(0.45f),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    showAlbum: Boolean = true,
    trackNumber: Int? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove from playlist",
    /** Null when not selecting; otherwise whether this row is selected (taps then toggle selection). */
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val actions = LocalSongActions.current
    var menuOpen by remember { mutableStateOf(false) }
    val titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                // Tapping the track that's already loaded opens the player instead of restarting it.
                onClick = if (isCurrent && selected == null) actions.openNowPlaying else onClick,
                onLongClick = onLongClick,
            )
            .then(if (selected == true) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showArtwork -> Artwork(song.artworkUri, size = 48.dp)
            trackNumber != null -> Text(
                text = if (trackNumber > 0) trackNumber.toString() else "–",
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                modifier = Modifier.width(32.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showAlbum) listOfNotNull(song.artist, song.displayAlbum).joinToString(" · ") else song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            return@Row
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem("Play next", Icons.Filled.SkipNext) { menuOpen = false; actions.playNext(listOf(song)) }
                MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) {
                    menuOpen = false; actions.addToQueue(listOf(song))
                }
                MenuItem("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) {
                    menuOpen = false; actions.addToPlaylist(listOf(song))
                }
                MenuItem("Go to album", Icons.Filled.Album) { menuOpen = false; actions.goToAlbum(song) }
                MenuItem("Go to artist", Icons.Filled.Person) { menuOpen = false; actions.goToArtist(song) }
                MenuItem("Song info", Icons.Filled.Info) { menuOpen = false; actions.showInfo(song) }
                if (onRemove != null) {
                    MenuItem(removeLabel, Icons.Filled.Delete) { menuOpen = false; onRemove() }
                }
            }
        }
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** "Play" + "Shuffle" buttons shown at the top of album / artist / playlist / songs lists. */
@Composable
fun PlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle")
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
