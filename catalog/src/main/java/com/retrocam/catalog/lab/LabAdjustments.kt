package com.retrocam.catalog.lab

/**
 * The Filter Lab's five colour knobs. Ranges match
 * `FilterEngine.applyAdjustments`; the neutral value of each is its default.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan).
 * https://github.com/hgayan7/FilterLibrary
 */
data class LabAdjustments(
    /** -1 black .. 0 neutral .. +1 white. */
    val brightness: Float = 0f,
    /** 0 flat .. 1 neutral .. 3 harsh. */
    val contrast: Float = 1f,
    /** 0 greyscale .. 1 neutral .. 3 vivid. */
    val saturation: Float = 1f,
    /** -1 cool blue .. 0 neutral .. +1 warm amber. */
    val warmth: Float = 0f,
    /** -1 magenta .. 0 neutral .. +1 green. */
    val tint: Float = 0f,
) {
    /** True when every knob sits at neutral, so no grading maths is needed. */
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f &&
            warmth == 0f && tint == 0f

    companion object {
        val NEUTRAL = LabAdjustments()

        /**
         * Clamp every knob into the range the upstream implementation accepts,
         * so a hand-edited or scanned recipe can never push the matrix somewhere
         * absurd.
         */
        fun coerce(a: LabAdjustments) = LabAdjustments(
            brightness = a.brightness.coerceIn(-1f, 1f),
            contrast = a.contrast.coerceAtLeast(0f),
            saturation = a.saturation.coerceAtLeast(0f),
            warmth = a.warmth.coerceIn(-1f, 1f),
            tint = a.tint.coerceIn(-1f, 1f),
        )

        /** Slider ranges, for the Advanced tab. */
        val RANGES = listOf(
            Knob("BRIGHTNESS", -1f, 1f, 0f),
            Knob("CONTRAST", 0f, 3f, 1f),
            Knob("SATURATION", 0f, 3f, 1f),
            Knob("WARMTH", -1f, 1f, 0f),
            Knob("TINT", -1f, 1f, 0f),
        )
    }
}

/** One adjustable grading knob: label, slider range, neutral point. */
data class Knob(val label: String, val min: Float, val max: Float, val neutral: Float)
