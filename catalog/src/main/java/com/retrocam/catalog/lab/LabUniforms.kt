package com.retrocam.catalog.lab

/**
 * Everything the lab shader needs, resolved from a recipe in one place.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan)
 * for the effect formulas; see LabGrading for the colour maths.
 *
 * Lives in :catalog rather than the renderer so the conversions (packed ARGB to
 * 0..1 floats, amount clamping, the grain scale factor, the overlay rects) are
 * unit-testable on a desktop JVM. The renderer only uploads what this produces.
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
    /** `[x0,y0,x1,y1]` in frame UV, or [NO_RECT] for no stamp. */
    val stampRect: FloatArray,
    val stampAlpha: Float,
    /** `[x0,y0,x1,y1]` in frame UV, or [NO_RECT] for no watermark. */
    val markRect: FloatArray,
    val markAlpha: Float,
    val contrast: Float,
    val brightness: Float,
    val rScale: Float,
    val gScale: Float,
    val bScale: Float,
    val saturation: Float,
    /** 256x1 RGBA, four tone curves. Null means no curve, which the shader skips. */
    /** `[highlights, shadows, whites, blacks]`, each -1..1. */
    val ranges: FloatArray,
    /** `[texture, clarity, dehaze]`, each -1..1. */
    val local: FloatArray,
    val curveTex: ByteArray?,
    /** Tone curve blend. Zero also means "no curve bound". */
    val curveAmount: Float,
    val gamma: Float,
    val splitAmount: Float,
    val shadowTint: FloatArray,
    val highlightTint: FloatArray,
) {
    companion object {
        /** A zero-width rect, which the shader treats as "nothing to draw". */
        val NO_RECT = floatArrayOf(0f, 0f, 0f, 0f)

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
         */
        const val BLUR_MAX_SPACING_PX = 6f

        /**
         * @param stampAspect width/height of the rasterised stamp bitmap. Derived
         *   from the text when not known, since a monospace face makes the
         *   advance predictable.
         * @param markAspect width/height of the watermark bitmap.
         */
        fun of(
            recipe: LabRecipe,
            stampAspect: Float = DateStamp.aspectFor(recipe.stampText.orEmpty()),
            markAspect: Float = 1f,
        ): LabUniforms {
            // Adobe's order needs the knobs apart: contrast is applied second
            // while temp/tint and saturation come after the range and local work.
            val g = LabGrading.split(recipe.templateMatrix(), recipe.adjustments)
                return LabUniforms(
                ccm = g.template,
                contrast = g.contrast,
                brightness = g.brightness,
                rScale = g.rScale,
                gScale = g.gScale,
                bScale = g.bScale,
                saturation = g.saturation,
                vignette = recipe.vignette.coerceIn(0f, 1f),
                grain = recipe.grain.coerceIn(0f, 1f),
                sharpen = recipe.sharpen.coerceIn(0f, 1f),
                blur = recipe.blur.coerceIn(0f, 1f),
                glitch = recipe.glitch.coerceIn(0f, 1f),
                duotoneShadow = unpackRgb(recipe.duotoneShadow),
                duotoneHighlight = unpackRgb(recipe.duotoneHighlight),
                duotone = recipe.duotone.coerceIn(0f, 1f),
                lutAmount = if (recipe.lutActive) recipe.lutAmount.coerceIn(0f, 1f) else 0f,
                stampRect = if (recipe.stampText.isNullOrBlank()) {
                    NO_RECT
                } else {
                    OverlayPlacement.rect(recipe.stampPosition, stampAspect)
                },
                stampAlpha = recipe.stampAlpha.coerceIn(0f, 1f),
                markRect = if (recipe.watermarkId == null) {
                    NO_RECT
                } else {
                    OverlayPlacement.rect(recipe.watermarkPosition, markAspect, OverlayPlacement.MARK_PAD, OverlayPlacement.MARK_PAD)
                },
                markAlpha = recipe.watermarkAlpha.coerceIn(0f, 1f),
                ranges = recipe.rangeArray(),
            local = recipe.localArray(),
            curveTex = if (recipe.toneCurveActive) {
                ToneCurve.toRgba(ToneCurve.parseGroup(recipe.toneCurves))
            } else {
                null
            },
            curveAmount = if (recipe.toneCurveActive) 1f else 0f,
            gamma = recipe.gamma.coerceIn(0.2f, 3f),
                splitAmount = recipe.splitAmount.coerceIn(0f, 1f),
                shadowTint = unpackRgb(recipe.shadowTint),
                highlightTint = unpackRgb(recipe.highlightTint),
            )
        }
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

/** The five stamp positions, for the position picker. */
val STAMP_POSITIONS = StampPosition.entries
