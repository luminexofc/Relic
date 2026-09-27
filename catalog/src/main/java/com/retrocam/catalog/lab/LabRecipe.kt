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
) {
    /** The template's 4x5 matrix, or null when there is no template. */
    fun templateMatrix(): FloatArray? = templateId?.let { LabTemplates.byId[it]?.matrix }

    /** True when any grading or any effect is actually doing something. */
    val isIdentity: Boolean
        get() = templateId == null && adjustments.isNeutral && !hasEffects

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
    }

    companion object {
        /**
         * Duotone defaults match FilterEngine.applyDuotone's own fallbacks, so a
         * recipe that enables duotone without picking colours looks the same here
         * as it does upstream.
         */
        const val DEFAULT_DUO_SHADOW = 0xFF141450.toInt()  // rgb(20, 20, 80)
        const val DEFAULT_DUO_HIGHLIGHT = 0xFFFF6E50.toInt()  // rgb(255, 110, 80)

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
        )
    }
}

/**
 * The synthetic [com.retrocam.catalog.FilterSpec] for the lab's grade stage.
 *
 * It is never in the filter strip and never selected on its own — the renderer
 * appends it as the second link when a spec carries a [LabRecipe]. Keeping it a
 * normal spec means it reuses the existing program cache, uniform upload and
 * safe-mode fallback with no special cases.
 */
object LabShaderSpec {
    val spec = com.retrocam.catalog.FilterSpec(
        id = "lab_grade",
        displayName = "LAB",
        family = com.retrocam.catalog.FilterFamily.LAB,
        fragmentBody = com.retrocam.catalog.Shaders.LAB_GRADE,
        param1 = 0f,
        param2 = 0f,
        defaultIntensity = 1f,
        context = "filter lab colour grade stage",
    )
}
