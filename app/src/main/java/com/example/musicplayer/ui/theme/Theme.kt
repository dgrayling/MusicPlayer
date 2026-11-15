package com.example.musicplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF5A4AB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF170065),
    secondary = Color(0xFF5F5C71),
    secondaryContainer = Color(0xFFE5DFF9),
    tertiary = Color(0xFF7B5266),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2B1A80),
    primaryContainer = Color(0xFF423197),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFC9C3DC),
    secondaryContainer = Color(0xFF474459),
    tertiary = Color(0xFFECB8CF),
)

@Composable
fun MusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
