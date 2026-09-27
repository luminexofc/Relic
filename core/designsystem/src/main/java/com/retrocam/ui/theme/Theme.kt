package com.retrocam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RetroOrange = Color(0xFFFF4B2E)
val AlertPink = Color(0xFFFF5C8A)
val AppBackground = Color(0xFF0E0E12)
val CardBackground = Color(0xFF17171D)
val TextPrimary = Color(0xFFF2F2F7)
val TextDim = Color(0x8CF2F2F7)

private val RetroCamColors = darkColorScheme(
    primary = RetroOrange,
    secondary = AlertPink,
    background = AppBackground,
    surface = AppBackground,
    surfaceVariant = CardBackground,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun RetroCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RetroCamColors, content = content)
}
