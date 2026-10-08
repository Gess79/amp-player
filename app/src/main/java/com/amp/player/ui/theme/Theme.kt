package com.amp.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFD97B4A)
private val Ink = Color(0xFF1B2A2F)
private val Cream = Color(0xFFF2EDE6)
private val Slate = Color(0xFF2F3E44)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Cream,
    background = Ink,
    surface = Slate,
    onBackground = Cream,
    onSurface = Cream,
)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Cream,
    secondary = Ink,
    background = Cream,
    surface = Color(0xFFE7E1D8),
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun AmpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
