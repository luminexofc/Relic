package com.retrocam.catalog.lab

/**
 * A user-built Filter Lab recipe: a starting template plus the five grading
 * knobs, applied on top of whichever base filter the recipe was saved against.
 *
 * Immutable on purpose. Editing a slider produces a new instance, and the
 * renderer uses that identity change to know when its uniforms need recomputing.
 *
 * Effect stages (vignette, grain, LUT, stamp) arrive in later phases as further
 * fields; the grade is the part that has to be right first, because everything
 * else sits on top of it.
 */
data class LabRecipe(
    /** [LabTemplates] id to start from, or null to grade the base filter as-is. */
    val templateId: String? = null,
    val adjustments: LabAdjustments = LabAdjustments.NEUTRAL,
    // ---- effects, all 0 = off ----
    /** Corner darkening, 0..1. */
    val vignette: Float = 0f,
    /** Monochrome film grain, 0..1. Animated on the GPU (upstream is static). */
    val grain: Float = 0f,
    /** 3x3 sharpen, 0..1. */
    val sharpen: Float = 0f,
    /** Soft blur, 0..1. */
    val blur: Float = 0f,
    /** Horizontal RGB channel split, 0..1. */
    val glitch: Float = 0f,
    /** Blend toward the two-colour ramp, 0..1. */
    val duotone: Float = 0f,
    /** Packed ARGB for the duotone shadow colour. */
    val duotoneShadow: Int = DEFAULT_DUO_SHADOW,
    /** Packed ARGB for the duotone highlight colour. */
    val duotoneHighlight: Int = DEFAULT_DUO_HIGHLIGHT,
    /**
     * Id of the LUT to apply, or null. A built-in id from [LutCatalog], or the
     * content hash of an imported Hald PNG.
     *
     * Deliberately an id and never the image: a recipe has to fit in a QR code,
     * and a LUT does not. The receiving app looks the id up and, if it has never
     * seen that LUT, the grade still works with the LUT simply skipped.
     */
    val lutId: String? = null,
    /** Blend toward the LUT, 0..1. */
    val lutAmount: Float = 0f,
    // ---- colour correction that a 4x5 matrix cannot express ----
    /**
     * Midtone power curve. 1 is neutral, below 1 lifts the midtones, above crushes
     * them. A power law is not affine, so this cannot live in the colour matrix and
     * runs as a shader step after the grade.
     */
    val gamma: Float = 1f,
    /**
     * How strongly the shadow and highlight tints are applied, 0..1. Split toning
     * is a function of luminance, which is also non-linear.
     */
    val splitAmount: Float = 0f,
    /** Packed ARGB tint pulled into the shadows. */
    val shadowTint: Int = DEFAULT_SHADOW_TINT,
    /** Packed ARGB tint pulled into the highlights. */
    val highlightTint: Int = DEFAULT_HIGHLIGHT_TINT,
    // ---- overlays ----
    /**
     * Date stamp text, or null for no stamp. The text is rasterised on the CPU
     * (only Canvas can lay out glyphs) and composited live by the shader.
     */
    val stampText: String? = null,
    val stampColor: Int = DateStamp.DEFAULT_COLOR,
    val stampPosition: StampPosition = StampPosition.BOTTOM_RIGHT,
    val stampAlpha: Float = 1f,
    /** Content hash of an imported watermark logo, or null. */
    val watermarkId: String? = null,
    val watermarkAlpha: Float = 0.8f,
    val watermarkPosition: StampPosition = StampPosition.BOTTOM_RIGHT,
    /**
     * Payload slot for the next version. Present so appending a field later does
     * not silently reinterpret every recipe already in the wild; it is always
     * written as "0" and never read.
     */
    val _reserved: String = "0",

    /**
     * Extra filter stages, applied in order after the colour grade.
     *
     * Capped at [MAX_STAGES] because every stage is a full-screen FBO pass on the
     * live viewfinder, and each one costs a trip to the GPU on every frame. Four
     * is the point past which a recipe is no longer something a person can look at
     * and reason about, and a fifth would start dropping frames on a mid-range
     * phone rather than just looking busy.
     */
    val stages: List<LabStage> = emptyList(),

    /**
     * Adobe's tone curve, as four 256-sample runs joined by `;`: composite, red,
     * green, blue. A run is empty when that curve is absent, and [ToneCurve.NONE]
     * when the preset had no curve at all.
     *
     * Stored sampled rather than as control points, because re-interpolating a
     * sampled run bends a curve that was already exact.
     */
    val toneCurves: String = ToneCurve.NONE,

    // Adobe's four range controls, -1..1. Positive lifts, negative rolls off;
    // see RangeTone for the band weights. XMP's values are -100..100.
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,

    // Adobe's local-contrast trio, -1..1.
    //
    // Clarity and Dehaze share a blur radius; Adobe gives them separate ones.
    // A second 9-tap pass on the live viewfinder is 9 more texture fetches per
    // pixel per frame, which is not worth the difference, and a preset's
    // texture/clarity balance still lands because the amount differs.
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,

    // Adobe's sharpening detail. `sharpen` above stays as the plain amount so
    // existing recipes are untouched; these are the XMP parameters around it.
    val sharpRadius: Float = 1f,
    val detail: Float = 0f,
    val masking: Float = 0f,

    // Adobe's grain distribution. The Lab's own `grain` stays the amount.
    val grainSize: Float = 1f,
    val grainRough: Float = 0.5f,

    // Adobe's vignette falloff. Roundness and Aspect are NOT implemented: they
    // change the shape of the falloff rather than its strength, and the visual
    // difference on a phone viewfinder is small next to the shader complexity.
    // The XMP import reports them as dropped rather than pretending.
    val vigMidpoint: Float = 0.5f,
    val vigFeather: Float = 0.5f,

    /**
     * Adobe's Color Mixer: eight hue bands x (hue rotation, saturation scale,
     * luminance scale), as [Hsl.VALUES] numbers, or [Hsl.NONE].
     *
     * One string field rather than twenty-four floats. Twenty-four constructor
     * parameters, twenty-four lines in `coerce` and twenty-four codec fields
     * would be twenty-four places to forget one, and §4.7 is exactly that
     * failure: a field count one off made every recipe in the app decode to
     * null. Packed, it is one field and the round-trip tests cover it whole.
     */
    val hsl: String = Hsl.NONE,

    /**
     * Adobe's Grayscale switch, 0..1.
     *
     * The only XMP colour key that removes colour rather than shifting it, and
     * the reason a black and white preset cannot be reproduced by turning
     * Saturation to -100: that is a luma-weighted desaturation and it does not
     * produce the neutral of a real channel mix.
     */
    val grayscale: Float = 0f,
) {
    /** True when any local-contrast control is off neutral. */
    val localActive: Boolean
        get() = texture != 0f || clarity != 0f || dehaze != 0f

    /** The parsed [Hsl.VALUES] adjustments, or null when the mixer is off. */
    fun hslArray(): FloatArray? = Hsl.parse(hsl)

    /** True when the color mixer would change something. */
    val hslActive: Boolean get() = Hsl.isActive(hslArray())

    /** `[texture, clarity, dehaze]`. */
    fun localArray(): FloatArray = floatArrayOf(texture, clarity, dehaze)
    /** True when any of the four range controls is off neutral. */
    val rangesActive: Boolean
        get() = highlights != 0f || shadows != 0f || whites != 0f || blacks != 0f

    /** `[highlights, shadows, whites, blacks]`, the order the shader wants. */
    fun rangeArray(): FloatArray = floatArrayOf(highlights, shadows, whites, blacks)
    /** True when at least one tone curve is present. */
    val toneCurveActive: Boolean get() = toneCurves != ToneCurve.NONE
    /** The stage list after clamping, which is what the renderer and codec both use. */
    fun stagesClamped(): List<LabStage> = stages.take(MAX_STAGES).map {
        it.copy(amount = it.amountClamped, mask = it.maskClamped)
    }

    /** The template's 4x5 matrix, or null when there is no template. */
    fun templateMatrix(): FloatArray? = templateId?.let { LabTemplates.byId[it]?.matrix }

    /** True when any grading or any effect is actually doing something. */
    val isIdentity: Boolean
        get() = templateId == null && adjustments.isNeutral && !hasEffects &&
            stages.isEmpty() && !toneCurveActive && !rangesActive && !localActive &&
            !hslActive && grayscale == 0f

    /** True when at least one effect stage is active. */
    val hasEffects: Boolean
        get() = vignette > 0f || grain > 0f || sharpen > 0f || blur > 0f ||
            glitch > 0f || duotone > 0f

    /** Names of the active effects, for the summary line in the UI. */
    fun activeEffects(): List<String> = buildList {
        if (vignette > 0f) add("vignette")
        if (grain > 0f) add("grain")
        if (sharpen > 0f) add("sharpen")
        if (blur > 0f) add("blur")
        if (glitch > 0f) add("glitch")
        if (duotone > 0f) add("duotone")
        if (lutId != null && lutAmount > 0f) add("lut")
        if (gamma != 1f) add("gamma")
        if (splitAmount > 0f) add("split tone")
        if (!stampText.isNullOrBlank()) add("date stamp")
        if (watermarkId != null) add("watermark")
        if (toneCurveActive) add("tone curve")
        if (highlights != 0f) add("highlights")
        if (shadows != 0f) add("shadows")
        if (whites != 0f) add("whites")
        if (blacks != 0f) add("blacks")
        if (texture != 0f) add("texture")
        if (clarity != 0f) add("clarity")
        if (dehaze != 0f) add("dehaze")
        if (detail != 0f) add("detail")
        if (masking != 0f) add("masking")
        if (vigMidpoint != 0.5f || vigFeather != 0.5f) add("vignette falloff")
        if (hslActive) add("color mixer")
        if (grayscale > 0f) add("grayscale")
        stages.take(MAX_STAGES).forEach { st ->
            add(LabPrimitives.byId(st.primitiveId)?.displayName?.lowercase() ?: st.primitiveId)
        }
    }

    /** True when a LUT is selected and switched on. */
    val lutActive: Boolean get() = lutId != null && lutAmount > 0f

    companion object {
        /**
         * Hard cap on chain length. Enforced in the recipe, the codec and the UI
         * rather than in one place, because a cap that only the UI respects is not
         * a cap — a shared recipe with nine stages would still have to render.
         */
        const val MAX_STAGES = 4

        /**
         * Duotone defaults match FilterEngine.applyDuotone's own fallbacks, so a
         * recipe that enables duotone without picking colours looks the same here
         * as it does upstream.
         */
        const val DEFAULT_DUO_SHADOW = 0xFF141450.toInt()  // rgb(20, 20, 80)
        const val DEFAULT_DUO_HIGHLIGHT = 0xFFFF6E50.toInt()  // rgb(255, 110, 80)

        /**
         * Split-tone defaults are deliberately mild: a cool shadow and a warm
         * highlight is the classic film look, but at full strength it flattens, so
         * the amount slider rather than the colour carries the weight.
         */
        const val DEFAULT_SHADOW_TINT = 0xFF2A3A5A.toInt()  // cool slate
        const val DEFAULT_HIGHLIGHT_TINT = 0xFF5A4632.toInt()  // warm amber

        /**
         * Clamp every field into range. Applied on decode, so a hand-edited or
         * scanned recipe can never drive a matrix or an effect somewhere absurd.
         */
        fun coerce(r: LabRecipe) = LabRecipe(
            templateId = r.templateId?.takeIf { LabTemplates.byId.containsKey(it) },
            adjustments = LabAdjustments.coerce(r.adjustments),
            vignette = r.vignette.coerceIn(0f, 1f),
            grain = r.grain.coerceIn(0f, 1f),
            sharpen = r.sharpen.coerceIn(0f, 1f),
            blur = r.blur.coerceIn(0f, 1f),
            glitch = r.glitch.coerceIn(0f, 1f),
            duotone = r.duotone.coerceIn(0f, 1f),
            duotoneShadow = r.duotoneShadow,
            duotoneHighlight = r.duotoneHighlight,
            lutId = r.lutId,
            lutAmount = r.lutAmount.coerceIn(0f, 1f),
            gamma = r.gamma.coerceIn(0.2f, 3f),
            splitAmount = r.splitAmount.coerceIn(0f, 1f),
            shadowTint = r.shadowTint,
            highlightTint = r.highlightTint,
            stages = r.stagesClamped(),
            toneCurves = r.toneCurves,
            highlights = r.highlights.coerceIn(-1f, 1f),
            shadows = r.shadows.coerceIn(-1f, 1f),
            whites = r.whites.coerceIn(-1f, 1f),
            blacks = r.blacks.coerceIn(-1f, 1f),
            texture = r.texture.coerceIn(-1f, 1f),
            clarity = r.clarity.coerceIn(-1f, 1f),
            dehaze = r.dehaze.coerceIn(-1f, 1f),
            sharpRadius = r.sharpRadius.coerceIn(0.5f, 3f),
            detail = r.detail.coerceIn(0f, 1f),
            masking = r.masking.coerceIn(0f, 1f),
            grainSize = r.grainSize.coerceIn(0.5f, 3f),
            grainRough = r.grainRough.coerceIn(0f, 1f),
            vigMidpoint = r.vigMidpoint.coerceIn(0f, 1f),
            vigFeather = r.vigFeather.coerceIn(0f, 1f),
            // Re-encoded rather than trusted: a hand-edited field is clamped and
            // quantised here, and an unreadable one becomes NONE, so the shader
            // can index the array blind.
            hsl = Hsl.encode(r.hslArray()),
            grayscale = r.grayscale.coerceIn(0f, 1f),
            stampText = r.stampText?.take(24)?.takeIf { it.isNotBlank() },
            stampColor = r.stampColor,
            stampPosition = r.stampPosition,
            stampAlpha = r.stampAlpha.coerceIn(0f, 1f),
            watermarkId = r.watermarkId?.takeIf { it.isNotBlank() },
            watermarkAlpha = r.watermarkAlpha.coerceIn(0f, 1f),
            watermarkPosition = r.watermarkPosition,
            _reserved = "0",
        )
    }
}
