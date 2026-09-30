package com.relic.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * shadcn/ui design tokens.
 *
 * shadcn is React, so there is nothing to depend on. What is portable is its token
 * set: it is built on **Radix Colors** (MIT) with a `--radius` of 8px, two type
 * roles, and Radix's motion timings. That vocabulary is reproduced here as plain
 * Compose, which is also shadcn's own philosophy — you own the code, there is no
 * runtime dependency.
 *
 * Values are the shadcn **dark** theme, whose palette is the Radix `neutral`
 * scale, with one deliberate departure: [Primary] is a Radix orange rather than
 * shadcn's near-white. Reproducing that faithfully is right for a web app and
 * wrong for a camera - with a near-white primary and no accent hue, every active
 * state, slider fill, selected filter and record button became the same grey as
 * the body text, and the app read as a wireframe of itself.
 *
 * The destructive slot stays reserved for destructive actions, so orange is the
 * only accent and it never has to mean "danger".
 */
object ShadcnColor {
    /** Radix neutral 12. */
    val Background = Color(0xFF09090B)

    /** Radix neutral 1. */
    val Foreground = Color(0xFFFAFAFA)

    /** Radix neutral 10. Raised surfaces: cards, inputs, popovers. */
    val Card = Color(0xFF18181B)

    /** Radix neutral 9. Also the border, input, muted, secondary and accent. */
    val Surface = Color(0xFF27272A)

    /** Radix neutral 6. */
    val Muted = Color(0xFF71717A)

    /**
     * Radix orange 9, which is shadcn's own primary for its orange theme.
     *
     * shadcn's stock dark theme makes Primary near-white, which is a faithful
     * reproduction and a lifeless one for a camera: every active state, slider
     * fill, selected filter and record button became the same grey as the text,
     * so the app lost the one thing that made it look like itself.
     *
     * The neutral surfaces stay Radix neutral, so the change is one colour
     * rather than a new palette. Set this back to #FAFAFA to return to stock
     * shadcn.
     */
    val Primary = Color(0xFFFF8A3D)

    /** Neutral 9. On orange 9 that is 6.3:1, so it clears AA for body text. */
    val PrimaryForeground = Color(0xFF27272A)

    val Secondary = Color(0xFF27272A)
    val SecondaryForeground = Color(0xFFFAFAFA)

    val MutedForeground = Color(0xFF71717A)

    val Accent = Color(0xFF27272A)
    val AccentForeground = Color(0xFFFAFAFA)

    /** Radix red 9. */
    val Destructive = Color(0xFFE5484D)
    val DestructiveForeground = Color(0xFFFAFAFA)

    val Border = Color(0xFF27272A)
    val Input = Color(0xFF27272A)
    /** Focus ring. Follows the accent, so keyboard focus is findable. */
    val Ring = Primary
}

/**
 * shadcn's radius scale. Note this is much tighter than Relic's previous
 * 16dp-everywhere, which is most of why the app will read as a different app
 * even before any component is swapped.
 */
object ShadcnRadius {
    val Sm: Dp = 4.dp
    val Md: Dp = 6.dp

    /** `--radius: 0.5rem`. The default. */
    val Lg: Dp = 8.dp
    val Xl: Dp = 12.dp

    /** `rounded-full`. Deliberate, not the default. */
    val Full: Dp = 9999.dp
}

/**
 * The same scale as shapes, which is what actually gets passed to `clip`.
 *
 * shadcn's `--radius` is a border radius, so a Dp alone is not enough - the two
 * are kept together to stop them drifting apart.
 */
object ShadcnShape {
    val sm = RoundedCornerShape(ShadcnRadius.Sm)
    val md = RoundedCornerShape(ShadcnRadius.Md)
    val lg = RoundedCornerShape(ShadcnRadius.Lg)
    val xl = RoundedCornerShape(ShadcnRadius.Xl)
    val full = CircleShape
}

/**
 * Radix motion timings, in ms. shadcn uses ~150ms for hover/press and ~200ms for
 * enter/exit, on a standard curve.
 */
object ShadcnMotion {
    const val HOVER = 150
    const val ENTER = 200

    /** `cubic-bezier(0.4, 0, 0.2, 1)`. */
    val Easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}
