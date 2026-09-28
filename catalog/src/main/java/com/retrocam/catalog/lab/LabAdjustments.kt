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
        val LIGHT = LabKnobGroup(
            "LIGHT",
            listOf(
                // -2..1.5 EV is what gamma 0.2..3 can actually represent
                // (2^-2.32 = 0.2, 2^1.58 = 3). A wider slider would promise an
                // exposure the recipe cannot store.
                Knob("EXPOSURE", -2f, 1.5f, 0f),
                Knob("CONTRAST", 0f, 3f, 1f),
                Knob("HIGHLIGHTS", -1f, 1f, 0f),
                Knob("SHADOWS", -1f, 1f, 0f),
                Knob("WHITES", -1f, 1f, 0f),
                Knob("BLACKS", -1f, 1f, 0f),
            ),
        )
        val CURVES = LabKnobGroup(
            "CURVES (PARAMETRIC)",
            listOf(
                Knob("PAR SHADOWS", -1f, 1f, 0f),
                Knob("PAR DARKS", -1f, 1f, 0f),
                Knob("PAR LIGHTS", -1f, 1f, 0f),
                Knob("PAR HIGHLIGHTS", -1f, 1f, 0f),
            ),
        )
        val COLOR = LabKnobGroup(
            "COLOR",
            listOf(
                Knob("WARMTH", -1f, 1f, 0f),
                Knob("TINT", -1f, 1f, 0f),
                Knob("VIBRANCE", -1f, 1f, 0f),
                Knob("SATURATION", 0f, 3f, 1f),
                Knob("GRAYSCALE", 0f, 1f, 0f),
            ),
        )
        val EFFECTS = LabKnobGroup(
            "EFFECTS",
            listOf(
                Knob("TEXTURE", -1f, 1f, 0f),
                Knob("CLARITY", -1f, 1f, 0f),
                Knob("DEHAZE", -1f, 1f, 0f),
                Knob("VIGNETTE", 0f, 1f, 0f),
                Knob("VIG MIDPOINT", 0f, 1f, 0.5f),
                Knob("VIG FEATHER", 0f, 1f, 0.5f),
                Knob("VIG ROUND", 0f, 1f, 0.5f),
                Knob("VIG ASPECT", 0f, 1f, 0.5f),
                Knob("GRAIN", 0f, 1f, 0f),
                Knob("GRAIN SIZE", 0.5f, 3f, 1f),
                Knob("GRAIN ROUGH", 0f, 1f, 0.5f),
            ),
        )
        val DETAIL = LabKnobGroup(
            "DETAIL",
            listOf(
                Knob("SHARPENING", 0f, 1f, 0f),
                Knob("RADIUS", 0.5f, 3f, 1f),
                Knob("DETAIL", 0f, 1f, 0f),
                Knob("MASKING", 0f, 1f, 0f),
                Knob("NOISE LUM", 0f, 1f, 0f),
                Knob("NOISE COLOR", 0f, 1f, 0f),
            ),
        )
        val OPTICS = LabKnobGroup(
            "OPTICS",
            listOf(
                Knob("REMOVE CA", 0f, 1f, 0f),
                Knob("LENS CORRECT", 0f, 1f, 0f),
                Knob("LENS DISTORT", -1f, 1f, 0f),
                Knob("LENS BLUR", 0f, 1f, 0f),
                Knob("LENS FOCUS", 0f, 1f, 0.5f),
            ),
        )
        val GEOMETRY = LabKnobGroup(
            "GEOMETRY",
            listOf(
                Knob("GEO VERTICAL", -1f, 1f, 0f),
                Knob("GEO HORIZONTAL", -1f, 1f, 0f),
                Knob("GEO ROTATE", -1f, 1f, 0f),
                Knob("GEO ASPECT", -1f, 1f, 0f),
                Knob("GEO SCALE", 0f, 1f, 0f),
                Knob("GEO X", -1f, 1f, 0f),
                Knob("GEO Y", -1f, 1f, 0f),
            ),
        )
    }
}

/**
 * Every group, in panel order.
 *
 * CURVES is deliberately absent: its knobs are parametric amounts with no
 * LabKnob entries, and the panel renders them in CurvesBlock instead. GEOMETRY
 * is absent for the same reason in reverse: GeoBlock renders the mode buttons
 * plus the same 7 sliders, so listing them here as well showed two near-
 * identical geometry sections back to back.
 */
val LAB_KNOB_GROUPS = listOf(
    LabKnobGroup.LIGHT,
    LabKnobGroup.COLOR,
    LabKnobGroup.EFFECTS,
    LabKnobGroup.DETAIL,
    LabKnobGroup.OPTICS,
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

    EXPOSURE("EXPOSURE"),
    HIGHLIGHTS("HIGHLIGHTS"),
    SHADOWS("SHADOWS"),
    WHITES("WHITES"),
    BLACKS("BLACKS"),

    TEXTURE("TEXTURE"),
    CLARITY("CLARITY"),
    DEHAZE("DEHAZE"),
    VIGNETTE_AMT("VIGNETTE"),
    GRAIN_AMT("GRAIN"),
    SHARPEN_AMT("SHARPENING"),

    GRAYSCALE("GRAYSCALE"),
    VIBRANCE("VIBRANCE"),

    SHARP_RADIUS("RADIUS"),
    DETAIL("DETAIL"),
    MASKING("MASKING"),
    DENOISE_LUM("NOISE LUM"),
    DENOISE_COLOR("NOISE COLOR"),

    GRAIN_SIZE("GRAIN SIZE"),
    GRAIN_ROUGH("GRAIN ROUGH"),
    VIG_MIDPOINT("VIG MIDPOINT"),
    VIG_FEATHER("VIG FEATHER"),
    VIG_ROUND("VIG ROUND"),
    VIG_ASPECT("VIG ASPECT"),

    LENS_CA("REMOVE CA"),
    LENS_ENABLE("LENS CORRECT"),
    LENS_DISTORT("LENS DISTORT"),
    LENS_BLUR("LENS BLUR"),
    LENS_FOCUS("LENS FOCUS"),

    GEO_VERTICAL("GEO VERTICAL"),
    GEO_HORIZONTAL("GEO HORIZONTAL"),
    GEO_ROTATE("GEO ROTATE"),
    GEO_ASPECT("GEO ASPECT"),
    GEO_SCALE("GEO SCALE"),
    GEO_X("GEO X"),
    GEO_Y("GEO Y");

    companion object {
        fun byIndex(i: Int): LabKnob? = entries.getOrNull(i)
        fun byLabel(l: String): LabKnob? = entries.firstOrNull { it.label == l }
    }
}

/** Exposure EV (-5..5) to gamma, matching XmpImport's 2^stops. */
fun exposureToGamma(ev: Float): Float =
    Math.pow(2.0, ev.toDouble()).toFloat().coerceIn(0.2f, 3f)
fun gammaToExposure(g: Float): Float =
    (Math.log(g.toDouble()) / Math.log(2.0)).toFloat().coerceIn(-5f, 5f)

/** Reads the scalar [k] off a recipe. */
fun LabRecipe.knobValue(k: LabKnob): Float = when (k) {
    LabKnob.BRIGHTNESS -> adjustments.brightness
    LabKnob.CONTRAST -> adjustments.contrast
    LabKnob.SATURATION -> adjustments.saturation
    LabKnob.WARMTH -> adjustments.warmth
    LabKnob.TINT -> adjustments.tint
    LabKnob.GAMMA -> gamma
    LabKnob.SPLIT_TONE -> splitAmount
    LabKnob.EXPOSURE -> gammaToExposure(gamma)
    LabKnob.HIGHLIGHTS -> highlights
    LabKnob.SHADOWS -> shadows
    LabKnob.WHITES -> whites
    LabKnob.BLACKS -> blacks
    LabKnob.TEXTURE -> texture
    LabKnob.CLARITY -> clarity
    LabKnob.DEHAZE -> dehaze
    LabKnob.VIGNETTE_AMT -> vignette
    LabKnob.GRAIN_AMT -> grain
    LabKnob.SHARPEN_AMT -> sharpen
    LabKnob.GRAYSCALE -> grayscale
    LabKnob.VIBRANCE -> vibrance
    LabKnob.SHARP_RADIUS -> sharpRadius
    LabKnob.DETAIL -> detail
    LabKnob.MASKING -> masking
    LabKnob.DENOISE_LUM -> denoiseLum
    LabKnob.DENOISE_COLOR -> denoiseColor
    LabKnob.GRAIN_SIZE -> grainSize
    LabKnob.GRAIN_ROUGH -> grainRough
    LabKnob.VIG_MIDPOINT -> vigMidpoint
    LabKnob.VIG_FEATHER -> vigFeather
    LabKnob.VIG_ROUND -> vigRound
    LabKnob.VIG_ASPECT -> vigAspect
    LabKnob.LENS_CA -> lensCA
    LabKnob.LENS_ENABLE -> lensEnable
    LabKnob.LENS_DISTORT -> lensDistort
    LabKnob.LENS_BLUR -> lensBlur
    LabKnob.LENS_FOCUS -> lensFocus
    LabKnob.GEO_VERTICAL -> geoArray()?.get(1) ?: 0f
    LabKnob.GEO_HORIZONTAL -> geoArray()?.get(2) ?: 0f
    LabKnob.GEO_ROTATE -> geoArray()?.get(3) ?: 0f
    LabKnob.GEO_ASPECT -> geoArray()?.get(4) ?: 0f
    LabKnob.GEO_SCALE -> geoArray()?.get(5) ?: 0f
    LabKnob.GEO_X -> geoArray()?.get(6) ?: 0f
    LabKnob.GEO_Y -> geoArray()?.get(7) ?: 0f
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
        LabKnob.EXPOSURE -> copy(gamma = exposureToGamma(c(-5f, 5f)))
        LabKnob.SPLIT_TONE -> copy(splitAmount = c(0f, 1f))
        LabKnob.HIGHLIGHTS -> copy(highlights = c(-1f, 1f))
        LabKnob.SHADOWS -> copy(shadows = c(-1f, 1f))
        LabKnob.WHITES -> copy(whites = c(-1f, 1f))
        LabKnob.BLACKS -> copy(blacks = c(-1f, 1f))
        LabKnob.TEXTURE -> copy(texture = c(-1f, 1f))
        LabKnob.CLARITY -> copy(clarity = c(-1f, 1f))
        LabKnob.DEHAZE -> copy(dehaze = c(-1f, 1f))
        LabKnob.VIGNETTE_AMT -> copy(vignette = c(0f, 1f))
        LabKnob.GRAIN_AMT -> copy(grain = c(0f, 1f))
        LabKnob.SHARPEN_AMT -> copy(sharpen = c(0f, 1f))
        LabKnob.GRAYSCALE -> copy(grayscale = c(0f, 1f))
        LabKnob.VIBRANCE -> copy(vibrance = c(-1f, 1f))
        // Dependent sliders auto-arm their master amount: radius/masking with
        // sharpening at 0, grain size with grain at 0, vignette shape with
        // vignette at 0 are all dead otherwise, which is exactly the "sliders
        // do nothing from scratch" report. Adobe sidesteps this by defaulting
        // Sharpening to 40; here the default stays 0 (neutral is neutral) and
        // the first drag switches it on instead.
        LabKnob.SHARP_RADIUS -> copy(
            sharpRadius = c(0.5f, 3f),
            sharpen = if (sharpen == 0f && detail == 0f && c(0.5f, 3f) != 1f) 0.45f else sharpen,
        )
        // No master to arm: the shader runs the detail unsharp on its own
        // (`u_sharpen > 0.0 || u_detail > 0.0`), so this is a first-class control
        // and switching sharpening on behind the user's back would be wrong.
        LabKnob.DETAIL -> copy(detail = c(0f, 1f))
        LabKnob.MASKING -> copy(
            masking = c(0f, 1f),
            sharpen = if (sharpen == 0f && detail == 0f && c(0f, 1f) != 0f) 0.45f else sharpen,
        )
        LabKnob.DENOISE_LUM -> copy(denoiseLum = c(0f, 1f))
        LabKnob.DENOISE_COLOR -> copy(denoiseColor = c(0f, 1f))
        LabKnob.GRAIN_SIZE -> copy(
            grainSize = c(0.5f, 3f),
            grain = if (grain == 0f && c(0.5f, 3f) != 1f) 0.5f else grain,
        )
        LabKnob.GRAIN_ROUGH -> copy(
            grainRough = c(0f, 1f),
            grain = if (grain == 0f && c(0f, 1f) != 0.5f) 0.5f else grain,
        )
        LabKnob.VIG_MIDPOINT -> copy(
            vigMidpoint = c(0f, 1f),
            vignette = if (vignette == 0f && c(0f, 1f) != 0.5f) 0.5f else vignette,
        )
        LabKnob.VIG_FEATHER -> copy(
            vigFeather = c(0f, 1f),
            vignette = if (vignette == 0f && c(0f, 1f) != 0.5f) 0.5f else vignette,
        )
        LabKnob.VIG_ROUND -> copy(
            vigRound = c(0f, 1f),
            vignette = if (vignette == 0f && c(0f, 1f) != 0.5f) 0.5f else vignette,
        )
        LabKnob.VIG_ASPECT -> copy(
            vigAspect = c(0f, 1f),
            vignette = if (vignette == 0f && c(0f, 1f) != 0.5f) 0.5f else vignette,
        )
        LabKnob.LENS_CA -> copy(lensCA = if (c(0f, 1f) > 0.5f) 1f else 0f)
        LabKnob.LENS_ENABLE -> copy(lensEnable = if (c(0f, 1f) > 0.5f) 1f else 0f)
        LabKnob.LENS_DISTORT -> copy(lensDistort = c(-1f, 1f))
        LabKnob.LENS_BLUR -> copy(lensBlur = c(0f, 1f))
        LabKnob.LENS_FOCUS -> copy(
            lensFocus = c(0f, 1f),
            lensBlur = if (lensBlur == 0f && c(0f, 1f) != 0.5f) 0.5f else lensBlur,
        )
        LabKnob.GEO_VERTICAL -> withGeo(1, c(-1f, 1f))
        LabKnob.GEO_HORIZONTAL -> withGeo(2, c(-1f, 1f))
        LabKnob.GEO_ROTATE -> withGeo(3, c(-1f, 1f))
        LabKnob.GEO_ASPECT -> withGeo(4, c(-1f, 1f))
        LabKnob.GEO_SCALE -> withGeo(5, c(0f, 1f))
        LabKnob.GEO_X -> withGeo(6, c(-1f, 1f))
        LabKnob.GEO_Y -> withGeo(7, c(-1f, 1f))
    }
}

/** Copy with geometry slot [i] set, preserving mode. */
fun LabRecipe.withGeo(i: Int, v: Float): LabRecipe {
    val cur = geoArray() ?: Geometry.defaults()
    val next = cur.copyOf()
    next[i] = v
    return copy(geometry = Geometry.encode(next))
}

/** Copy with HSL band [band] channel [ch] (0 hue,1 sat,2 lum) set. */
fun LabRecipe.withHsl(band: Int, ch: Int, v: Float): LabRecipe {
    val cur = hslArray() ?: FloatArray(Hsl.VALUES)
    val next = cur.copyOf()
    next[band * 3 + ch] = v.coerceIn(-1f, 1f)
    return copy(hsl = Hsl.encode(next))
}
fun LabRecipe.hslBand(band: Int, ch: Int): Float =
    hslArray()?.getOrNull(band * 3 + ch) ?: 0f

/**
 * Copy with color-grade slot set.
 *
 * A hue with its saturation at 0 is invisible AND un-storable (the encoder
 * drops a sat-less grade as inactive), so setting a hue arms its saturation
 * to 0.5. Same auto-arm rule as the scalar dependents above.
 */
fun LabRecipe.withGrade(slot: Int, v: Float): LabRecipe {
    val cur = gradeArray() ?: ColorGrade.defaults()
    val next = cur.copyOf()
    next[slot] = when (slot) {
        0, 2, 4 -> v.coerceIn(0f, 1f)
        1, 3, 5 -> v.coerceIn(0f, 1f)
        6 -> v.coerceIn(0f, 1f)
        else -> v.coerceIn(-1f, 1f)
    }
    if (slot % 2 == 0 && slot < 6 && next[slot] != 0f && next[slot + 1] == 0f) {
        next[slot + 1] = 0.5f
    }
    return copy(colorGrade = ColorGrade.encode(next))
}

/** Copy with B&W mixer band set. */
fun LabRecipe.withBw(band: Int, v: Float): LabRecipe {
    val cur = bwArray() ?: FloatArray(BwMix.VALUES)
    val next = cur.copyOf()
    next[band] = v.coerceIn(-1f, 1f)
    return copy(bwMix = BwMix.encode(next))
}

/** Copy with calibration trim set (0 rh,1 rs,2 gh,3 gs,4 bh,5 bs). */
fun LabRecipe.withCal(slot: Int, v: Float): LabRecipe {
    val (h, s) = calibrationParts() ?: (FloatArray(3) to FloatArray(3))
    val nh = h.copyOf(); val ns = s.copyOf()
    if (slot % 2 == 0) nh[slot / 2] = v.coerceIn(-1f, 1f)
    else ns[slot / 2] = v.coerceIn(-1f, 1f)
    return copy(calibration = Calibration.encode(nh, ns))
}

/**
 * Copy with defringe slot set.
 *
 * Hue ranges with both amounts at 0 are invisible AND un-storable, so moving
 * a range arms its amount to 0.5. Slots 0/3 are the amounts themselves.
 */
fun LabRecipe.withDefringe(slot: Int, v: Float): LabRecipe {
    val cur = defringeArray() ?: Defringe.defaults()
    val next = cur.copyOf()
    next[slot] = v.coerceIn(0f, 1f)
    if (slot in 1..2 && next[0] == 0f && v != Defringe.defaults()[slot]) next[0] = 0.5f
    if (slot in 4..5 && next[3] == 0f && v != Defringe.defaults()[slot]) next[3] = 0.5f
    return copy(defringe = Defringe.encode(next))
}

/** The neutral value of [k], which is also its reset target. */
val LabKnob.neutral: Float
    get() = LabRecipe().knobValue(this)
