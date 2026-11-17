package com.example.musicplayer.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.ContentCut
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.musicplayer.data.trim.TrackTrim
import com.example.musicplayer.data.trim.TrimMarks
import com.example.musicplayer.playback.PlayerConnection
import com.example.musicplayer.playback.PlayerUiState
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.formatDuration
import kotlin.math.abs
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
            // Keep polling while paused too (more slowly), so seeks show up.
            delay(if (state.isPlaying) 250 else 500)
        }
    }
    return position
}

/**
 * A thin progress line that can be tapped or dragged to seek. [onScrub] reports the fraction under the
 * finger while dragging (null when the drag ends or is cancelled); [onSeek] commits a position.
 */
@Composable
private fun ScrubBar(
    fraction: Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val lineHeight by animateDpAsState(if (dragFraction != null) 6.dp else 3.dp, label = "scrubLine")
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val shown = (dragFraction ?: fraction).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = at(it.x).also(scrub) },
                    onDragEnd = {
                        dragFraction?.let(seek)
                        dragFraction = null
                        scrub(null)
                    },
                    onDragCancel = {
                        dragFraction = null
                        scrub(null)
                    },
                ) { change, _ ->
                    change.consume()
                    dragFraction = at(change.position.x).also(scrub)
                }
            }
            .drawBehind {
                val h = lineHeight.toPx()
                val top = (size.height - h) / 2
                drawRect(trackColor, Offset(0f, top), Size(size.width, h))
                drawRect(color, Offset(0f, top), Size(size.width * shown, h))
                if (dragFraction != null) drawCircle(color, radius = 6.dp.toPx(), center = Offset(size.width * shown, size.height / 2))
            }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f) },
    )
}

@Composable
fun MiniPlayer(connection: PlayerConnection, state: PlayerUiState, onOpen: () -> Unit) {
    val item = state.currentItem ?: return
    val position = rememberPlaybackPosition(connection, state)
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            ScrubBar(
                fraction = if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f,
                onScrub = { f -> scrubMs = f?.let { (it * state.durationMs).toLong() } },
                onSeek = { connection.seekTo((it * state.durationMs).toLong()) },
            )
            Row(
                Modifier
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
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
                        scrubMs?.let { "${formatDuration(it)} / ${formatDuration(state.durationMs)}" }
                            ?: item.mediaMetadata.artist?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scrubMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
fun NowPlayingScreen(
    connection: PlayerConnection,
    state: PlayerUiState,
    trim: TrimMarks,
    trimActions: TrimActions,
    onClose: () -> Unit,
) {
    val item = state.currentItem
    LaunchedEffect(item == null) { if (item == null) onClose() }
    if (item == null) return

    val position = rememberPlaybackPosition(connection, state)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showQueue by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
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
            TextButton(onClick = { showTrim = true }) {
                Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val effective = trim.effective
                Text(
                    if (effective == TrackTrim.NONE) {
                        "Set start & end"
                    } else {
                        "Plays ${formatDuration(effective.startMs)} – ${formatDuration(effective.endMs ?: state.durationMs)}"
                    },
                )
            }

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
    if (showTrim) {
        ModalBottomSheet(onDismissRequest = { showTrim = false }) {
            TrimSheetContent(connection, state.durationMs, position, trim, trimActions)
        }
    }
}

/** Saves a song's start/end marks; a null mark falls back to detected silence or the song's own start/end. */
class TrimActions(
    val setMarks: (startMs: Long?, endMs: Long?) -> Unit,
    val setStart: (Long) -> Unit,
    val setEnd: (Long) -> Unit,
)

@Composable
private fun TrimSheetContent(
    connection: PlayerConnection,
    durationMs: Long,
    positionMs: Long,
    marks: TrimMarks,
    actions: TrimActions,
) {
    val effective = marks.effective
    val effectiveEnd = effective.endMs ?: durationMs
    // Local while dragging; reset whenever the saved marks change.
    var range by remember(effective, durationMs) {
        mutableStateOf(effective.startMs.toFloat()..effectiveEnd.toFloat())
    }

    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start & end", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { actions.setMarks(null, null) },
                enabled = marks.userStartMs != null || marks.userEndMs != null,
            ) { Text("Reset") }
        }
        Text(
            "Every time this song plays it starts and stops at these marks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (durationMs > 0) {
            Spacer(Modifier.height(16.dp))
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = 0f..durationMs.toFloat(),
                onValueChangeFinished = {
                    // Only the handle that moved becomes a mark of the user's; the other keeps its source.
                    val start = range.start.toLong()
                    val end = range.endInclusive.toLong()
                    actions.setMarks(
                        if (abs(start - effective.startMs) > 20) start else marks.userStartMs,
                        if (abs(end - effectiveEnd) > 20) end else marks.userEndMs,
                    )
                },
            )
            Row(Modifier.fillMaxWidth()) {
                MarkLabel(
                    "Start",
                    range.start.toLong(),
                    source = when {
                        marks.userStartMs != null -> "Your mark"
                        effective.startMs > 0 -> "Silence skipped"
                        else -> "Song start"
                    },
                    alignment = Alignment.Start,
                )
                Spacer(Modifier.weight(1f))
                MarkLabel(
                    "End",
                    range.endInclusive.toLong(),
                    source = when {
                        marks.userEndMs != null -> "Your mark"
                        effective.endMs != null -> "Silence skipped"
                        else -> "Song end"
                    },
                    alignment = Alignment.End,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.setStart(positionMs) }, modifier = Modifier.weight(1f)) {
                Text("Start at ${formatPreciseDuration(positionMs)}")
            }
            OutlinedButton(onClick = { actions.setEnd(positionMs) }, modifier = Modifier.weight(1f)) {
                Text("End at ${formatPreciseDuration(positionMs)}")
            }
        }
        TextButton(onClick = { connection.seekTo(effective.startMs) }) { Text("Play from start mark") }

        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !marks.skipSilence -> "Skip silence is off in Settings, so only your own marks apply."
                marks.detectedStartMs != null || marks.detectedEndMs != null ->
                    "Sound detected from ${formatPreciseDuration(marks.detectedStartMs ?: 0)} to " +
                        formatPreciseDuration(marks.detectedEndMs ?: durationMs) + "."
                else -> "Silence at the start and end is skipped automatically; none was found in this song."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarkLabel(label: String, ms: Long, source: String, alignment: Alignment.Horizontal) {
    Column(horizontalAlignment = alignment) {
        Text("$label ${formatPreciseDuration(ms)}", style = MaterialTheme.typography.labelLarge)
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Like formatDuration, with tenths of a second: "1:23.4". */
private fun formatPreciseDuration(ms: Long): String =
    "${formatDuration(ms)}.${(ms.coerceAtLeast(0) % 1000) / 100}"

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
