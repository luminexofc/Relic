package com.relic.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * shadcn's two type roles.
 *
 * shadcn uses the platform UI font stack plus a monospace for anything numeric.
 * On Android that is Roboto via [FontFamily.SansSerif] and the system mono via
 * [FontFamily.Monospace], so no font files ship at all - which is why
 * `res/font/` is gone and the three OFL attributions went with it.
 *
 * Weight carries the emphasis instead of the family, which is the other half of
 * moving off Press Start 2P.
 */
object AppType {
    /** shadcn's `font-sans`. All UI text. */
    val Sans: FontFamily = FontFamily.SansSerif

    /** shadcn's `font-mono`. Numeric and terminal readouts only. */
    val Mono: FontFamily = FontFamily.Monospace

    /** The weight shadcn uses for headings and strong labels. */
    val Strong: FontWeight = FontWeight.Bold
    val Normal: FontWeight = FontWeight.Normal
}
