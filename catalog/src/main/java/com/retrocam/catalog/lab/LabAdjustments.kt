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
            // Not matrix-representable, but they sit with the other colour knobs
            // in the UI, so the ranges live here too.
            Knob("GAMMA", 0.2f, 3f, 1f),
            Knob("SPLIT TONE", 0f, 1f, 0f),
        )
    }
}

/** One adjustable grading knob: label, slider range, neutral point. */
data class Knob(val label: String, val min: Float, val max: Float, val neutral: Float)

/**
 * The groups of controls the XMP import can populate, in the order Adobe's own
 * panel uses them.
 *
 * This exists because of a real gap, not tidiness: the importer can set about
 * thirty fields, and before this the Lab UI showed seven sliders, so a preset
 * that set its clarity, its colour mixer and its grain could be imported,
 * rendered correctly, and then looked at in the UI as if none of it had
 * happened. A setting you cannot see is a setting a user cannot trust, and the
 * one that set it has no way to tell what was applied.
 *
 * The grouping is the same four-mechanism split the shader and the reader use,
 * so the structure of the UI, the order of the shader and the families of XMP
 * keys are one thing rather than three that have to be kept in step.
 */
data class LabKnobGroup(
    val title: String,
    val knobs: List<Knob>,
) {
    companion object {
        /** Adobe's Tone panel: the four range controls. */
        val TONE = LabKnobGroup(
            "TONE",
            listOf(
                Knob("HIGHLIGHTS", -1f, 1f, 0f),
                Knob("SHADOWS", -1f, 1f, 0f),
                Knob("WHITES", -1f, 1f, 0f),
                Knob("BLACKS", -1f, 1f, 0f),
            ),
        )

        /** Adobe's Presence panel: local contrast. */
        val PRESENCE = LabKnobGroup(
            "PRESENCE",
            listOf(
                Knob("TEXTURE", -1f, 1f, 0f),
                Knob("CLARITY", -1f, 1f, 0f),
                Knob("DEHAZE", -1f, 1f, 0f),
            ),
        )

        /** Adobe's Color panel beyond temp/tint, plus Grayscale. */
        val COLOR = LabKnobGroup(
            "COLOR",
            listOf(
                Knob("GRAYSCALE", 0f, 1f, 0f),
            ),
        )

        /** Adobe's Detail panel: the three sharpening parameters. */
        val DETAIL = LabKnobGroup(
            "DETAIL",
            listOf(
                Knob("SHARP RADIUS", 0.5f, 3f, 1f),
                Knob("DETAIL", 0f, 1f, 0f),
                Knob("MASKING", 0f, 1f, 0f),
            ),
        )

        /** Grain distribution and the vignette falloff. */
        val GRAIN = LabKnobGroup(
            "GRAIN & VIGNETTE",
            listOf(
                Knob("GRAIN SIZE", 0.5f, 3f, 1f),
                Knob("GRAIN ROUGH", 0f, 1f, 0.5f),
                Knob("VIG MIDPOINT", 0f, 1f, 0.5f),
                Knob("VIG FEATHER", 0f, 1f, 0.5f),
            ),
        )
    }
}

/** Every group, in panel order. */
val LAB_KNOB_GROUPS = listOf(
    LabKnobGroup.TONE,
    LabKnobGroup.PRESENCE,
    LabKnobGroup.COLOR,
    LabKnobGroup.DETAIL,
    LabKnobGroup.GRAIN,
)

/**
 * A flat index over every XMP-settable scalar, so the ViewModel can take one
 * `Int` and the UI never has to agree with itself about what index 11 means.
 *
 * Written as an explicit list rather than a computed offset, because a computed
 * one is only correct until someone inserts a group, and then every saved knob
 * position silently means a different control. `LAB_KNOB_GROUPS` is the
 * definition of the display order; this is the definition of the storage order,
 * and a test asserts the two agree in count and label.
 */
enum class LabKnob(val label: String) {
    BRIGHTNESS("BRIGHTNESS"),
    CONTRAST("CONTRAST"),
    SATURATION("SATURATION"),
    WARMTH("WARMTH"),
    TINT("TINT"),
    GAMMA("GAMMA"),
    SPLIT_TONE("SPLIT TONE"),

    HIGHLIGHTS("HIGHLIGHTS"),
    SHADOWS("SHADOWS"),
    WHITES("WHITES"),
    BLACKS("BLACKS"),

    TEXTURE("TEXTURE"),
    CLARITY("CLARITY"),
    DEHAZE("DEHAZE"),

    GRAYSCALE("GRAYSCALE"),

    SHARP_RADIUS("SHARP RADIUS"),
    DETAIL("DETAIL"),
    MASKING("MASKING"),

    GRAIN_SIZE("GRAIN SIZE"),
    GRAIN_ROUGH("GRAIN ROUGH"),
    VIG_MIDPOINT("VIG MIDPOINT"),
    VIG_FEATHER("VIG FEATHER");

    companion object {
        fun byIndex(i: Int): LabKnob? = entries.getOrNull(i)
    }
}

/** Reads the scalar [k] off a recipe. */
fun LabRecipe.knobValue(k: LabKnob): Float = when (k) {
    LabKnob.BRIGHTNESS -> adjustments.brightness
    LabKnob.CONTRAST -> adjustments.contrast
    LabKnob.SATURATION -> adjustments.saturation
    LabKnob.WARMTH -> adjustments.warmth
    LabKnob.TINT -> adjustments.tint
    LabKnob.GAMMA -> gamma
    LabKnob.SPLIT_TONE -> splitAmount
    LabKnob.HIGHLIGHTS -> highlights
    LabKnob.SHADOWS -> shadows
    LabKnob.WHITES -> whites
    LabKnob.BLACKS -> blacks
    LabKnob.TEXTURE -> texture
    LabKnob.CLARITY -> clarity
    LabKnob.DEHAZE -> dehaze
    LabKnob.GRAYSCALE -> grayscale
    LabKnob.SHARP_RADIUS -> sharpRadius
    LabKnob.DETAIL -> detail
    LabKnob.MASKING -> masking
    LabKnob.GRAIN_SIZE -> grainSize
    LabKnob.GRAIN_ROUGH -> grainRough
    LabKnob.VIG_MIDPOINT -> vigMidpoint
    LabKnob.VIG_FEATHER -> vigFeather
}

/** A copy of the recipe with the scalar [k] set to [value], clamped. */
fun LabRecipe.withKnob(k: LabKnob, value: Float): LabRecipe {
    fun c(lo: Float, hi: Float) = value.coerceIn(lo, hi)
    return when (k) {
        LabKnob.BRIGHTNESS -> copy(adjustments = adjustments.copy(brightness = c(-1f, 1f)))
        LabKnob.CONTRAST -> copy(adjustments = adjustments.copy(contrast = c(0f, 3f)))
        LabKnob.SATURATION -> copy(adjustments = adjustments.copy(saturation = c(0f, 3f)))
        LabKnob.WARMTH -> copy(adjustments = adjustments.copy(warmth = c(-1f, 1f)))
        LabKnob.TINT -> copy(adjustments = adjustments.copy(tint = c(-1f, 1f)))
        LabKnob.GAMMA -> copy(gamma = c(0.2f, 3f))
        LabKnob.SPLIT_TONE -> copy(splitAmount = c(0f, 1f))
        LabKnob.HIGHLIGHTS -> copy(highlights = c(-1f, 1f))
        LabKnob.SHADOWS -> copy(shadows = c(-1f, 1f))
        LabKnob.WHITES -> copy(whites = c(-1f, 1f))
        LabKnob.BLACKS -> copy(blacks = c(-1f, 1f))
        LabKnob.TEXTURE -> copy(texture = c(-1f, 1f))
        LabKnob.CLARITY -> copy(clarity = c(-1f, 1f))
        LabKnob.DEHAZE -> copy(dehaze = c(-1f, 1f))
        LabKnob.GRAYSCALE -> copy(grayscale = c(0f, 1f))
        LabKnob.SHARP_RADIUS -> copy(sharpRadius = c(0.5f, 3f))
        LabKnob.DETAIL -> copy(detail = c(0f, 1f))
        LabKnob.MASKING -> copy(masking = c(0f, 1f))
        LabKnob.GRAIN_SIZE -> copy(grainSize = c(0.5f, 3f))
        LabKnob.GRAIN_ROUGH -> copy(grainRough = c(0f, 1f))
        LabKnob.VIG_MIDPOINT -> copy(vigMidpoint = c(0f, 1f))
        LabKnob.VIG_FEATHER -> copy(vigFeather = c(0f, 1f))
    }
}

/** The neutral value of [k], which is also its reset target. */
val LabKnob.neutral: Float
    get() = LabRecipe().knobValue(this)
