package com.example.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.musicplayer.ui.AppViewModel
import com.example.musicplayer.ui.navigation.MusicAppRoot
import com.example.musicplayer.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels { AppViewModel.Factory }

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var granted by remember { mutableStateOf(hasAudioPermission()) }
                    var askedOnce by remember { mutableStateOf(false) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        askedOnce = true
                        granted = hasAudioPermission()
                    }
                    // Pick up a grant made from system settings.
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasAudioPermission() }

                    if (granted) {
                        LaunchedEffect(Unit) { vm.refreshLibrary() }
                        MusicAppRoot(vm)
                    } else {
                        PermissionScreen(
                            showSettings = askedOnce,
                            onRequest = {
                                val perms = buildList {
                                    add(audioPermission)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                launcher.launch(perms.toTypedArray())
                            },
                            onOpenSettings = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", packageName, null)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.player.connect()
    }

    override fun onStop() {
        vm.player.release()
        super.onStop()
    }
}

@Composable
private fun PermissionScreen(showSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text("Let's find your music", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Music needs access to the audio files on this device to build your library.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow access") }
        if (showSettings) {
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}
