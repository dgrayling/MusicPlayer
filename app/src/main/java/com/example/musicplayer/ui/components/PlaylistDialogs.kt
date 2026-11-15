package com.example.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.example.musicplayer.data.model.Playlist

@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onDismiss = onDismiss,
            onConfirm = onCreateNew,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("New playlist…") },
                        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                        modifier = Modifier.clickable { creating = true },
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = { Text(pluralize(playlist.songCount, "song")) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                        modifier = Modifier.clickable { onPick(playlist) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
