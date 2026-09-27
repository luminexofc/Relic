package com.retrocam.catalog.lab

/**
 * Everything the lab shader needs, resolved from a recipe in one place.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan)
 * for the effect formulas; see LabGrading for the colour maths.
 *
 * Lives in :catalog rather than the renderer so the conversions (packed ARGB to
 * 0..1 floats, amount clamping, the grain scale factor) are unit-testable on a
 * desktop JVM. The renderer only uploads what this produces.
 */
data class LabUniforms(
    /** 12 floats: three matrix rows then the /255 offset. See [LabGrading.toUniforms]. */
    val ccm: FloatArray,
    val vignette: Float,
    val grain: Float,
    val sharpen: Float,
    val blur: Float,
    val glitch: Float,
    /** Duotone shadow colour, 0..1 rgb. */
    val duotoneShadow: FloatArray,
    /** Duotone highlight colour, 0..1 rgb. */
    val duotoneHighlight: FloatArray,
    val duotone: Float,
    /** LUT blend, 0..1. Zero also means "no LUT bound". */
    val lutAmount: Float,
) {
    companion object {
        /**
         * Upstream scales grain by 60 in 0-255 units, so full intensity moves a
         * channel by up to 60/255 = 0.235. Reproduced here in 0..1 space.
         */
        const val GRAIN_SCALE = 60f / 255f

        /**
         * Upstream's RGB glitch shift is `width * 0.015 * intensity` pixels, so
         * this is the fraction of frame width one unit of intensity displaces a
         * channel.
         */
        const val GLITCH_SCALE = 0.015f

        /**
         * Upstream's stack blur is accumulator-based and cannot run inside one
         * fragment pass, so the GPU version is a 3x3 tent sampled at a spacing
         * that grows with the amount. Visually a soft blur, not a bit-exact port.
         * The ceiling keeps the taps from smearing across the whole frame.
         */
        const val BLUR_MAX_SPACING_PX = 6f

        fun of(recipe: LabRecipe): LabUniforms = LabUniforms(
            ccm = LabGrading.uniformsFor(recipe.templateMatrix(), recipe.adjustments),
            vignette = recipe.vignette.coerceIn(0f, 1f),
            grain = recipe.grain.coerceIn(0f, 1f),
            sharpen = recipe.sharpen.coerceIn(0f, 1f),
            blur = recipe.blur.coerceIn(0f, 1f),
            glitch = recipe.glitch.coerceIn(0f, 1f),
            duotoneShadow = unpackRgb(recipe.duotoneShadow),
            duotoneHighlight = unpackRgb(recipe.duotoneHighlight),
            duotone = recipe.duotone.coerceIn(0f, 1f),
            lutAmount = if (recipe.lutActive) recipe.lutAmount.coerceIn(0f, 1f) else 0f,
        )
    }
}

/** Packed ARGB to a 0..1 rgb triple. Alpha is dropped; camera frames are opaque. */
fun unpackRgb(argb: Int): FloatArray = floatArrayOf(
    ((argb shr 16) and 0xFF) / 255f,
    ((argb shr 8) and 0xFF) / 255f,
    (argb and 0xFF) / 255f,
)

/** Inverse of [unpackRgb], for the colour picker. */
fun packRgb(r: Float, g: Float, b: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/**
 * The effect stages, for the Advanced tab. Order matches
 * `PhotoFilterBuilder.build()`: sharpen and blur before vignette and grain.
 */
data class LabEffect(val label: String, val min: Float, val max: Float)

val LAB_EFFECTS = listOf(
    LabEffect("VIGNETTE", 0f, 1f),
    LabEffect("GRAIN", 0f, 1f),
    LabEffect("SHARPEN", 0f, 1f),
    LabEffect("BLUR", 0f, 1f),
    LabEffect("GLITCH", 0f, 1f),
    LabEffect("DUOTONE", 0f, 1f),
)

/** Reads effect [i] off a recipe. */
fun LabRecipe.effectValue(i: Int): Float = when (i) {
    0 -> vignette
    1 -> grain
    2 -> sharpen
    3 -> blur
    4 -> glitch
    5 -> duotone
    else -> 0f
}

/** Returns a copy with effect [i] set. */
fun LabRecipe.withEffect(i: Int, value: Float): LabRecipe {
    val v = value.coerceIn(0f, 1f)
    return when (i) {
        0 -> copy(vignette = v)
        1 -> copy(grain = v)
        2 -> copy(sharpen = v)
        3 -> copy(blur = v)
        4 -> copy(glitch = v)
        5 -> copy(duotone = v)
        else -> this
    }
}
