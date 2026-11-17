package com.example.musicplayer.ui.settings

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.data.FolderPaths
import com.example.musicplayer.data.dedupe.DuplicateKind
import com.example.musicplayer.data.dedupe.FolderRemoval
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.DuplicateScan
import com.example.musicplayer.ui.components.LocalSongActions
import com.example.musicplayer.ui.components.pluralize

/**
 * Finds folders whose songs all exist in another folder (identical copies, or a smaller folder contained in a
 * bigger one) and moves the redundant ones to the system trash, merging them into the folder that's kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFoldersScreen(vm: AppViewModel, onBack: () -> Unit) {
    val scan by vm.duplicateScan.collectAsStateWithLifecycle()
    val showMessage = LocalSongActions.current.showMessage
    val context = LocalContext.current
    var ignoreNames by rememberSaveable { mutableStateOf(false) }
    // Folders the user unticked; everything else found is selected.
    var unticked by remember(scan) { mutableStateOf(emptySet<String>()) }
    var pending by remember { mutableStateOf<List<FolderRemoval>?>(null) }

    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val removed = pending
        pending = null
        if (removed != null && result.resultCode == Activity.RESULT_OK) vm.onFoldersTrashed(removed, showMessage)
    }

    val found = (scan as? DuplicateScan.Done)?.removals.orEmpty()
    val chosen = found.filter { it.folder !in unticked }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Duplicate folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (chosen.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "${pluralize(chosen.sumOf { it.songIds.size }, "song")} · ${formatBytes(chosen.sumOf { it.bytes })}",
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        modifier = Modifier.padding(end = 16.dp),
                        onClick = {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                showMessage("Removing files needs Android 11 or newer")
                                return@Button
                            }
                            val valid = vm.prepareFolderRemoval(chosen, showMessage) ?: return@Button
                            pending = valid
                            val uris = vm.songUris(valid.flatMap { it.songIds })
                            val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                            trash.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        },
                    ) { Text("Move ${pluralize(chosen.size, "folder")} to trash") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    "Finds folders whose songs are all in another folder, either an identical copy or a smaller folder " +
                        "contained in a bigger one, and merges them by moving the redundant folder to the trash. " +
                        "Songs are compared by their file contents. Trashed files can be restored for 30 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                val enabled = scan !is DuplicateScan.Scanning
                ListItem(
                    headlineContent = { Text("Match renamed files") },
                    supportingContent = { Text("Also count a song as present when its file has a different name or sits in a different subfolder") },
                    leadingContent = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    trailingContent = { Switch(checked = ignoreNames, onCheckedChange = { ignoreNames = it }, enabled = enabled) },
                    modifier = Modifier.clickable(enabled = enabled) { ignoreNames = !ignoreNames },
                )
            }
            item { ScanControls(scan, onScan = { vm.scanDuplicateFolders(ignoreNames) }, onCancel = vm::cancelDuplicateScan) }

            if (scan is DuplicateScan.Done) {
                if (found.isEmpty()) {
                    item {
                        Text(
                            "No duplicate folders found.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                    items(found, key = { it.folder }) { r ->
                        val checked = r.folder !in unticked
                        val toggle = { unticked = if (checked) unticked + r.folder else unticked - r.folder }
                        ListItem(
                            headlineContent = { Text(displayPath(r.folder), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    Text(
                                        (if (r.kind == DuplicateKind.IDENTICAL) "Same as " else "All of it is in ") + displayPath(r.keptIn),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text("${pluralize(r.songIds.size, "song")} · ${formatBytes(r.bytes)}")
                                }
                            },
                            leadingContent = { Icon(Icons.Filled.FolderCopy, contentDescription = null) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
                            modifier = Modifier.clickable(onClick = toggle),
                        )
                    }
                    item {
                        Text(
                            "Only music files are moved. Anything else in these folders, such as cover images, stays where it is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanControls(scan: DuplicateScan, onScan: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (scan) {
            is DuplicateScan.Scanning -> {
                if (scan.total > 0) {
                    LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
                    Text("Comparing songs… ${scan.done} of ${scan.total}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading library…", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is DuplicateScan.Done -> FilledTonalButton(onClick = onScan) { Text("Scan again") }
            DuplicateScan.Idle -> Button(onClick = onScan) { Text("Find duplicate folders") }
        }
    }
}

/** "Music/Rock" for internal storage, "SD card · Music/Rock" for others. */
private fun displayPath(path: String): String {
    val rel = FolderPaths.relativeDir(path) ?: return path
    return if (path.startsWith("/storage/emulated/")) rel else "SD card · $rel"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
