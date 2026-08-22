package com.example.inuit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InuitColors = darkColorScheme(
    primary = Indigo,
    onPrimary = Ink,
    primaryContainer = IndigoDim,
    onPrimaryContainer = TextPrimary,
    secondary = Teal,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF124B45),
    onSecondaryContainer = TextPrimary,
    tertiary = Amber,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkRaised,
    onSurfaceVariant = TextSecondary,
    outline = InkOutline,
    error = Rose,
    onError = Ink
)

@Composable
fun InuitTheme(content: @Composable () -> Unit) {
    // Dark-first: charts and the knowledge map are designed for dark canvases.
    MaterialTheme(
        colorScheme = InuitColors,
        typography = Typography,
        content = content
    )
}
