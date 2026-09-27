package com.retrocam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's colour scheme, expressed as shadcn tokens.
 *
 * shadcn's dark theme is the Radix `neutral` scale, reproduced here faithfully.
 * The one departure is [ShadcnColor.Primary], which is a Radix orange rather
 * than shadcn's near-white, so the app keeps a colour identity; see there for
 * why.
 *
 * Legacy names are kept as aliases because 15-odd call sites still read them, and
 * a name that no longer describes the value is worse than a slightly stale one.
 * They will go once the screens have been migrated to the components.
 */
val RetroOrange = ShadcnColor.Primary
val AlertPink = ShadcnColor.Destructive
val AppBackground = ShadcnColor.Background
val CardBackground = ShadcnColor.Card
val TextPrimary = ShadcnColor.Foreground
val TextDim = ShadcnColor.MutedForeground

private val ShadcnColors = darkColorScheme(
    primary = ShadcnColor.Primary,
    onPrimary = ShadcnColor.PrimaryForeground,
    secondary = ShadcnColor.Secondary,
    onSecondary = ShadcnColor.SecondaryForeground,
    background = ShadcnColor.Background,
    onBackground = ShadcnColor.Foreground,
    surface = ShadcnColor.Card,
    onSurface = ShadcnColor.Foreground,
    surfaceVariant = ShadcnColor.Surface,
    onSurfaceVariant = ShadcnColor.MutedForeground,
    outline = ShadcnColor.Border,
    outlineVariant = ShadcnColor.Border,
    error = ShadcnColor.Destructive,
    onError = ShadcnColor.DestructiveForeground,
    scrim = Color.Black,
)

@Composable
fun RetroCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShadcnColors, content = content)
}
