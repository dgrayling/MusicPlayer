package com.example.musicplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.data.db.OnlineStatus
import com.example.musicplayer.data.db.OverrideSource
import com.example.musicplayer.data.db.SongOverrideEntity
import com.example.musicplayer.data.db.SongSuggestionEntity
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.components.Artwork
import com.example.musicplayer.ui.components.pluralize

/** "Song metadata" items for the Settings list. */
fun LazyListScope.metadataSettings(vm: AppViewModel, onReview: () -> Unit, onReset: () -> Unit) {
    item {
        Text(
            "Song metadata",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
        )
    }
    item {
        val enabled by vm.enrichEnabled.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text("Fix song metadata") },
            supportingContent = {
                Text("Tidy messy tags and fill in missing titles, artists, albums, years, genres and cover art from MusicBrainz. Your files aren't changed.")
            },
            leadingContent = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
            trailingContent = { Switch(checked = enabled, onCheckedChange = vm::setEnrichEnabled) },
            modifier = Modifier.clickable { vm.setEnrichEnabled(!enabled) },
        )
    }
    item {
        val enabled by vm.enrichEnabled.collectAsStateWithLifecycle()
        val wifiOnly by vm.enrichWifiOnly.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text("Look up on Wi-Fi only") },
            leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
            trailingContent = { Switch(checked = wifiOnly, onCheckedChange = vm::setEnrichWifiOnly, enabled = enabled) },
            modifier = Modifier.clickable(enabled = enabled) { vm.setEnrichWifiOnly(!wifiOnly) },
        )
    }
    item { MetadataStatus(vm) }
    item {
        val suggestions by vm.suggestions.collectAsStateWithLifecycle()
        if (suggestions.isNotEmpty()) {
            ListItem(
                headlineContent = { Text("Review suggestions") },
                supportingContent = { Text("${pluralize(suggestions.size, "song")} with a possible match that needs your OK") },
                leadingContent = { Icon(Icons.Filled.RateReview, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onReview),
            )
        }
    }
    item {
        ListItem(
            headlineContent = { Text("Reset automatic corrections") },
            supportingContent = { Text("Undo every automatic fix. Your own edits are kept.") },
            leadingContent = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onReset),
        )
    }
}

@Composable
private fun MetadataStatus(vm: AppViewModel) {
    val enabled by vm.enrichEnabled.collectAsStateWithLifecycle()
    val counts by vm.enrichStatusCounts.collectAsStateWithLifecycle()
    val stats by vm.enrichStats.collectAsStateWithLifecycle()
    val running by vm.enrichRunning.collectAsStateWithLifecycle()
    val waiting by vm.enrichWaiting.collectAsStateWithLifecycle()
    val wifiOnly by vm.enrichWifiOnly.collectAsStateWithLifecycle()
    val total = counts.values.sum()
    val matched = counts[OnlineStatus.MATCHED] ?: 0
    val pending = (counts[OnlineStatus.PENDING] ?: 0) + (counts[OnlineStatus.ERROR] ?: 0)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (total > 0) {
            Text(
                "$matched of $total songs matched online · ${counts[OnlineStatus.SUGGESTED] ?: 0} to review · " +
                    "${counts[OnlineStatus.UNMATCHED] ?: 0} not found · $pending waiting",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            running || stats.running -> {
                if (stats.total > 0) {
                    LinearProgressIndicator(progress = { stats.processed.toFloat() / stats.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    "Looking up ${stats.processed} / ${stats.total} · ${pluralize(stats.networkLookups, "network lookup")} · " +
                        "${stats.fromCache} from cache",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            waiting && pending > 0 -> Text(
                if (wifiOnly) "Waiting for Wi-Fi and enough battery" else "Waiting for a connection and enough battery",
                style = MaterialTheme.typography.bodySmall,
            )
            stats.lastError != null -> Text(
                "Last run stopped: ${stats.lastError}. It will retry automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        FilledTonalButton(onClick = vm::fixMetadataNow, enabled = enabled && !running) {
            Text("Fix metadata now")
        }
    }
}

@Composable
fun ResetCorrectionsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset automatic corrections?") },
        text = { Text("Songs go back to their original tags and are looked up again on the next run. Your own edits and artist spellings you chose are kept.") },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------------------------
// Review screen
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataReviewScreen(vm: AppViewModel, onBack: () -> Unit) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val byAlbum = remember(suggestions) {
        suggestions.groupBy { (_, sug) -> sug.mbReleaseId ?: "single:${sug.songId}" }.values.toList()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Review suggestions") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (suggestions.isEmpty()) {
            Text(
                "Nothing to review. Uncertain matches show up here.",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            byAlbum.forEach { group ->
                val first = group.first().second
                item(key = "h" + (first.mbReleaseId ?: first.songId.toString())) {
                    ReviewGroupHeader(first, group.size, onAcceptAll = { vm.acceptSuggestions(group.map { it.first }) },
                        onRejectAll = { vm.rejectSuggestions(group.map { it.first }) })
                }
                items(group, key = { it.second.songId }) { (song, sug) ->
                    ReviewRow(
                        song, sug,
                        onAccept = { vm.acceptSuggestions(listOf(song)) },
                        onReject = { vm.rejectSuggestions(listOf(song)) },
                        onAlwaysArtist = { vm.teachArtistAlias(song.artist, sug.artist!!); vm.acceptSuggestions(listOf(song)) },
                    )
                }
                item { HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun ReviewGroupHeader(sug: SongSuggestionEntity, count: Int, onAcceptAll: () -> Unit, onRejectAll: () -> Unit) {
    ListItem(
        leadingContent = { Artwork(sug.artworkUrl?.let(android.net.Uri::parse), size = 56.dp) },
        headlineContent = { Text(sug.album ?: sug.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(sug.albumArtist ?: sug.artist, sug.year?.toString(), sug.genre).joinToString(" · "))
        },
        trailingContent = {
            if (count > 1) {
                Row {
                    TextButton(onClick = onRejectAll) { Text("Reject all") }
                    TextButton(onClick = onAcceptAll) { Text("Accept all") }
                }
            }
        },
    )
}

@Composable
private fun ReviewRow(song: Song, sug: SongSuggestionEntity, onAccept: () -> Unit, onReject: () -> Unit, onAlwaysArtist: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${song.title} · ${song.artist}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "→ ${sug.title ?: song.title} · ${sug.artist ?: song.artist}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text("${(sug.confidence * 100).toInt()}% match", style = MaterialTheme.typography.labelSmall)
            if (sug.artist != null && sug.artist != song.artist) {
                TextButton(onClick = onAlwaysArtist, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Always use \"${sug.artist}\" for \"${song.artist}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconButton(onClick = onReject) { Icon(Icons.Filled.Close, contentDescription = "Reject") }
        IconButton(onClick = onAccept) { Icon(Icons.Filled.Check, contentDescription = "Accept") }
    }
}

// ------------------------------------------------------------------------------------------------
// Song info dialog
// ------------------------------------------------------------------------------------------------

@Composable
fun SongInfoDialog(vm: AppViewModel, song: Song, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val original = remember(song.id) { vm.originalSong(song.id) }
    val override by vm.songOverride(song.id).collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        EditMetadataDialog(song, onDismiss = { editing = false }) { title, artist, album, year, genre ->
            vm.editSongMetadata(song, title, artist, album, year, genre)
            onMessage("Saved")
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceLabel(override), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                InfoField("Title", song.title, original?.title)
                InfoField("Artist", song.artist, original?.artist)
                InfoField("Album", song.displayAlbum.orEmpty(), original?.displayAlbum.orEmpty())
                InfoField("Album artist", song.albumArtist, original?.albumArtist)
                InfoField("Year", song.year.takeIf { it > 0 }?.toString().orEmpty(), original?.year?.takeIf { it > 0 }?.toString().orEmpty())
                InfoField("Genre", song.genre, original?.genre)
                InfoField("Track", song.displayTrackNumber.takeIf { it > 0 }?.toString().orEmpty(),
                    original?.displayTrackNumber?.takeIf { it > 0 }?.toString().orEmpty())
                InfoField("File", song.path, null)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.revertSongMetadata(song); onMessage("Showing the file's own tags"); onDismiss() }) {
                        Text("Use original")
                    }
                    TextButton(onClick = { vm.searchSongAgain(song); onMessage("Looking it up again"); onDismiss() }) {
                        Text("Search again")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { editing = true }) { Text("Edit") } },
    )
}

private fun sourceLabel(o: SongOverrideEntity?): String = when {
    o == null -> "From the file's tags"
    o.locked && o.source == OverrideSource.USER && o.onlineStatus == OnlineStatus.REJECTED -> "Original tags (kept by you)"
    o.locked -> "Edited by you"
    o.source == OverrideSource.ONLINE -> "Matched on MusicBrainz (${(o.confidence * 100).toInt()}% confidence)"
    o.onlineStatus == OnlineStatus.SUGGESTED -> "Cleaned up · a suggestion is waiting for review"
    listOf(o.title, o.artist, o.album, o.year, o.trackNumber).any { it != null } -> "Cleaned up from tags and file name"
    else -> "From the file's tags"
}

@Composable
private fun InfoField(label: String, value: String, original: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
        if (original != null && original != value) {
            Text(
                "was: ${original.ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditMetadataDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, album: String, year: Int?, genre: String) -> Unit,
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.displayAlbum.orEmpty()) }
    var year by remember { mutableStateOf(song.year.takeIf { it > 0 }?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(song.genre) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit song info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(
                    year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true)
                Text(
                    "Saved in the app only; automatic fixes won't change this song again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, artist, album, year.toIntOrNull(), genre) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
