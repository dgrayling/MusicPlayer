package com.example.musicplayer.ui.components

import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.musicplayer.data.FolderPaths
import com.example.musicplayer.data.model.Folder
import com.example.musicplayer.data.model.Song
import com.example.musicplayer.ui.AppViewModel

/** Multi-selection of songs on one screen. Long-press a song to start; tap to toggle. */
@Stable
class SongSelection {
    var ids by mutableStateOf<Set<Long>>(emptySet())
        private set

    val active: Boolean get() = ids.isNotEmpty()

    fun toggle(song: Song) {
        ids = if (song.id in ids) ids - song.id else ids + song.id
    }

    fun selectAll(songs: List<Song>) {
        ids = songs.mapTo(HashSet()) { it.id }
    }

    fun clear() {
        ids = emptySet()
    }

    fun selectedFrom(songs: List<Song>): List<Song> = songs.filter { it.id in ids }

    /** Drops ids that are no longer in [songs] (e.g. after they were moved out of this folder). */
    fun retain(songs: List<Song>) {
        if (ids.isEmpty()) return
        val present = songs.mapTo(HashSet()) { it.id }
        if (!present.containsAll(ids)) ids = ids intersect present
    }
}

@Composable
fun rememberSongSelection(): SongSelection {
    val selection = remember { SongSelection() }
    BackHandler(enabled = selection.active) { selection.clear() }
    return selection
}

/** Contextual top bar shown while songs are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selection: SongSelection,
    songs: List<Song>,
    onMove: (List<Song>) -> Unit,
) {
    val actions = LocalSongActions.current
    val selected = selection.selectedFrom(songs)
    var menuOpen by remember { mutableStateOf(false) }
    fun done(block: (List<Song>) -> Unit) {
        menuOpen = false
        block(selected)
        selection.clear()
    }

    TopAppBar(
        title = { Text("${selected.size} selected") },
        navigationIcon = {
            IconButton(onClick = selection::clear) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
        },
        actions = {
            IconButton(onClick = { selection.selectAll(songs) }) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = { onMove(selected) }, enabled = selected.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to folder")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = selected.isNotEmpty()) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("Play next", Icons.Filled.SkipNext) { done(actions.playNext) }
                    MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { done(actions.addToQueue) }
                    MenuItem("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { done(actions.addToPlaylist) }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/**
 * Returns a function that starts the move flow for some songs: pick a destination folder, let Android ask the
 * user to allow modifying the files, then move them. Progress and results are reported via [SongActions.showMessage].
 */
@Composable
fun rememberMoveSongsFlow(vm: AppViewModel, folders: List<Folder>, onMoved: () -> Unit): (List<Song>) -> Unit {
    val context = LocalContext.current
    val actions = LocalSongActions.current
    var picking by remember { mutableStateOf<List<Song>?>(null) }
    var pendingMove by remember { mutableStateOf<Pair<List<Song>, String>?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val move = pendingMove
        pendingMove = null
        if (move != null && result.resultCode == android.app.Activity.RESULT_OK) {
            vm.moveSongs(move.first, move.second, actions.showMessage)
            onMoved()
        }
    }

    picking?.let { songs ->
        FolderPickerDialog(
            folders = folders,
            songCount = songs.size,
            startDir = songs.first().path.substringBeforeLast('/'),
            onDismiss = { picking = null },
            onPick = { dest ->
                picking = null
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    actions.showMessage("Moving files needs Android 11 or newer")
                } else {
                    pendingMove = songs to dest
                    val request = MediaStore.createWriteRequest(context.contentResolver, songs.map { it.uri })
                    permission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            },
        )
    }

    return { songs -> if (songs.isNotEmpty()) picking = songs }
}

/** Full-screen folder browser for choosing where to move songs; can create new folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerDialog(
    folders: List<Folder>,
    songCount: Int,
    startDir: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val created = remember { mutableStateListOf<String>() }
    val tree = remember(folders, created.size) { FolderPaths.destinationTree(folders, created) }
    var current by rememberSaveable { mutableStateOf(startDir) }
    var naming by remember { mutableStateOf(false) }
    val root = FolderPaths.volumeRoot(current)
    val children = remember(tree, current) { FolderPaths.children(tree, current) }
    val allowed = FolderPaths.canMoveInto(current)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(
                    title = { Text("Move ${pluralize(songCount, "song")} to…") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    },
                    actions = {
                        IconButton(onClick = { naming = true }, enabled = root != null) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                        }
                    },
                )
                Text(
                    readablePath(current),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (root != null && current != root) {
                        item {
                            ListItem(
                                headlineContent = { Text("Up") },
                                leadingContent = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                                modifier = Modifier.clickable { current = FolderPaths.parent(current) },
                            )
                        }
                    }
                    items(children, key = { it }) { dir ->
                        ListItem(
                            headlineContent = { Text(dir.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.clickable { current = dir },
                        )
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(16.dp)) {
                    if (!allowed) {
                        Text(
                            "Android only allows music inside: ${FolderPaths.AUDIO_ROOTS.take(4).joinToString(", ")}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = { onPick(current) }, enabled = allowed) { Text("Move here") }
                    }
                }
            }
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        val valid = FolderPaths.isValidFolderName(name) && "$current/${name.trim()}" !in tree
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                    isError = name.isNotEmpty() && !valid,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val dir = "$current/${name.trim()}"
                        created += dir
                        current = dir
                        naming = false
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/** "/storage/emulated/0/Music/Rock" -> "Internal storage/Music/Rock". */
fun readablePath(dir: String): String = dir
    .replaceFirst(Regex("^/storage/emulated/\\d+"), "Internal storage")
    .replaceFirst(Regex("^/storage/([^/]+)"), "SD card ($1)")
