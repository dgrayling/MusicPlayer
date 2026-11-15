package com.example.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.musicplayer.playback.PlayerConnection
import com.example.musicplayer.playback.PlayerUiState
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.formatDuration
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Polls the controller's position while composed (positions aren't pushed as events). */
@Composable
private fun rememberPlaybackPosition(connection: PlayerConnection, state: PlayerUiState): Long {
    var position by remember { mutableLongStateOf(connection.positionMs) }
    LaunchedEffect(state.isPlaying, state.currentItem?.mediaId) {
        while (true) {
            position = connection.positionMs
            if (!state.isPlaying) break
            delay(250)
        }
    }
    return position
}

@Composable
fun MiniPlayer(connection: PlayerConnection, state: PlayerUiState, onOpen: () -> Unit) {
    val item = state.currentItem ?: return
    val position = rememberPlaybackPosition(connection, state)
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(
                Modifier
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.mediaMetadata.artworkUri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.mediaMetadata.title?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.mediaMetadata.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = connection::togglePlayPause) {
                    Icon(
                        if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playWhenReady) "Pause" else "Play",
                    )
                }
                IconButton(onClick = connection::next) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(connection: PlayerConnection, state: PlayerUiState, onClose: () -> Unit) {
    val item = state.currentItem
    LaunchedEffect(item == null) { if (item == null) onClose() }
    if (item == null) return

    val position = rememberPlaybackPosition(connection, state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1L)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close")
                }
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(
                    item.mediaMetadata.artworkUri,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                item.mediaMetadata.title?.toString().orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                listOfNotNull(item.mediaMetadata.artist, item.mediaMetadata.albumTitle).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))
            Slider(
                value = if (dragging) dragValue else (position.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    connection.seekTo((dragValue * duration).toLong())
                    dragging = false
                },
            )
            Row(Modifier.fillMaxWidth()) {
                val shown = if (dragging) (dragValue * duration).toLong() else position
                Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val active = MaterialTheme.colorScheme.primary
                val inactive = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = connection::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                        tint = if (state.shuffle) active else inactive,
                    )
                }
                IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = connection::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playWhenReady) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = connection::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = connection::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repeat one"
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            else -> "Repeat off"
                        },
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheetContent(connection, state)
        }
    }
}

@Composable
private fun QueueSheetContent(connection: PlayerConnection, state: PlayerUiState) {
    // Local copy so drag-reordering is instant; resyncs whenever the real queue changes.
    var items by remember { mutableStateOf(state.queue) }
    LaunchedEffect(state.queue) { items = state.queue }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
        connection.moveQueueItem(from.index, to.index)
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            "${items.size} tracks",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = connection::clearQueue) { Text("Clear") }
    }
    HorizontalDivider()
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(items, key = { _, item -> item.mediaId }) { index, item ->
            ReorderableItem(reorderState, key = item.mediaId) { isDragging ->
                val isCurrent = item.mediaId == state.currentItem?.mediaId
                Surface(tonalElevation = if (isDragging) 8.dp else 0.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { connection.skipToQueueItem(index) }
                            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(Icons.Filled.DragHandle, contentDescription = "Reorder")
                        }
                        Artwork(item.mediaMetadata.artworkUri, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.mediaMetadata.title?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.mediaMetadata.artist?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { connection.removeQueueItem(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from queue")
                        }
                    }
                }
            }
        }
    }
}
