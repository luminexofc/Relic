package com.retrocam.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.retrocam.R

/** Retro type system (all OFL-licensed for commercial use). */
object RetroType {
    /** UI labels, buttons, strip names. */
    val Mono = FontFamily(
        Font(R.font.space_mono),
        Font(R.font.space_mono_bold, FontWeight.Bold),
    )

    /** Terminal readouts (timer, zoom, hints). */
    val Terminal = FontFamily(Font(R.font.vt323))

    /** Display accents: logo, countdown, empty states. */
    val Display = FontFamily(Font(R.font.press_start_2p))
}
