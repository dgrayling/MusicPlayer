package com.example.musicplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musicplayer.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onReviewSuggestions: () -> Unit) {
    val showAlbums by vm.showAlbumsTab.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text("Albums tab") },
                    supportingContent = { Text("Show an Albums tab for browsing music by album") },
                    leadingContent = { Icon(Icons.Filled.Album, contentDescription = null) },
                    trailingContent = { Switch(checked = showAlbums, onCheckedChange = vm::setShowAlbumsTab) },
                    modifier = Modifier.clickable { vm.setShowAlbumsTab(!showAlbums) },
                )
            }
            metadataSettings(vm, onReview = onReviewSuggestions, onReset = { confirmReset = true })
        }
    }
    if (confirmReset) ResetCorrectionsDialog(onConfirm = vm::resetCorrections, onDismiss = { confirmReset = false })
}
