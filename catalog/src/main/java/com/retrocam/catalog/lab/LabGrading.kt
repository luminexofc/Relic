package com.retrocam.catalog.lab

/**
 * Filter Lab colour grading: the four-step colour transform that turns a
 * template plus five knobs into a single matrix the shader can apply.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan).
 * https://github.com/hgayan7/FilterLibrary
 *
 * Specifically `FilterEngine.applyAdjustments` and the intensity interpolation
 * from `createColorFilter` / `applyColorMatrix`, with the Bitmap drawing
 * replaced by a GPU uniform upload. See THIRD_PARTY_NOTICES.md.
 *
 * ## Why this is Kotlin and not GLSL
 *
 * The composition order and the 0-255 offset units are easy to get subtly wrong,
 * and a shader is a bad place to find out. All of it happens here, once per
 * slider move rather than once per frame, and [toUniforms] hands the result
 * straight to the GPU. That also makes the whole path unit-testable on a plain
 * JVM — see LabGradingOracleTest.
 *
 * ## Conventions
 *
 * Matrices are 4x5 in **row-major, 0-255 units**, applied as a row vector,
 * which is upstream's (and Android `ColorMatrix`'s) convention. Composition
 * order matches upstream exactly: saturation, then contrast/brightness, then
 * warmth/tint.
 *
 * Two deliberate divergences, both documented at the use site:
 *  - the alpha row is ignored and alpha is passed through untouched, because
 *    camera frames are opaque. (Upstream `RETRO_WARM` has a non-identity alpha
 *    row, which would make opaque frames slightly transparent.)
 *  - per-channel clamping to 0-255, which `ColorMatrixColorFilter` does
 *    implicitly by rendering into an 8-bit Bitmap, is done in the shader.
 */
object LabGrading {

    /** 4x5 identity, 0-255 units. */
    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    

    

    /**
     * Warmth scales red up / blue down, tint scales green. +-0.2 per unit, so
     * the knob has a gentle usable range. Transcribed from
     * `FilterEngine.applyAdjustments`.
     */
    fun warmthTintMatrix(warmth: Float, tint: Float): FloatArray {
        val rScale = (1f + warmth * 0.2f).coerceAtLeast(0f)
        val bScale = (1f - warmth * 0.2f).coerceAtLeast(0f)
        val gScale = (1f + tint * 0.2f).coerceAtLeast(0f)
        return floatArrayOf(
            rScale, 0f, 0f, 0f, 0f,
            0f, gScale, 0f, 0f, 0f,
            0f, 0f, bScale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    

    /**
     * The full grade for a recipe: optional template matrix, then the five
     * knobs, in upstream's order. Neutral knobs are skipped, matching the
     * `if` guards in `FilterEngine.applyAdjustments` — skipping is not just an
     * optimisation, it is what keeps the result bit-comparable with upstream.
     *
     * Returns a fresh 4x5 in 0-255 units. Pass it to [toUniforms].
     */
    /**
     * The knobs in Adobe's application order, split out of the matrix.
     *
     * The four 4x5s above all turn out to be reproducible as scalars, which is
     * why this split costs six floats rather than two more matrices:
     *
     *  - contrast is `c * in + 128 * (1 - c) + brightness * 255`
     *  - warmth/tint is three diagonal scales with no translation column
     *  - saturation is `lum + s * (in - lum)`, which is exactly
     *    [saturationMatrix] since that is built from the same luminance weights
     *
     * They have to be separate because Adobe's order is not commutative:
     * contrast comes second, before the range and local-contrast work, while
     * temp/tint and saturation come after it. Composited into one matrix they
     * could not be placed at all, which is what made the single-matrix shortcut
     * incompatible with an XMP import.
     *
     * [template] stays a matrix and stays first. A template is a whole base look,
     * not a setting, so it belongs ahead of the adjustments rather than in the
     * middle of them.
     */
    data class Split(
        /** The template matrix alone, 12 floats ready for the shader. */
        val template: FloatArray,
        val contrast: Float,
        /** Post-contrast offset, 0-255 units, as the old matrix carried it. */
        val brightness: Float,
        val rScale: Float,
        val gScale: Float,
        val bScale: Float,
        val saturation: Float,
    )

    fun split(template: FloatArray?, adjustments: LabAdjustments): Split {
        val a = LabAdjustments.coerce(adjustments)
        val m = if (template != null && template.size == 20) template.copyOf() else IDENTITY.copyOf()
        val wt = warmthTintMatrix(a.warmth, a.tint)
        return Split(
            template = toUniforms(m),
            contrast = a.contrast,
            brightness = a.brightness,
            // The 4x5 is row-major with 5 columns, so the diagonal is at 0, 6
            // and 12 - NOT at 0, 5 and 10, which are the first entry of each row
            // and all zero. Reading those gives gScale = bScale = 0, and since
            // the shader multiplies by vec3(rScale, gScale, bScale) that would
            // have driven green and blue to black on every single frame.
            rScale = wt[0],          // warmth -> red
            gScale = wt[6],          // tint    -> green
            bScale = wt[12],         // warmth -> blue
            saturation = a.saturation,
        )
    }



    /**
     * Pack a composed 4x5 into the 12 floats the lab shader wants:
     *
     *   `[0..8]`  three rows of the linear part, unmodified
     *   `[9..11]` the translation column, divided by 255 into 0..1
     *
     * There is deliberately no intensity parameter. Every RetroCam shader ends
     * with `mix(src, applyFilter(src, uv), u_intensity)`, so the shared Filter
     * Lab intensity slider already scales this stage against its own input, and
     * blending here as well would apply the slider twice. (The two are
     * algebraically the same operation, so the only real decision is to have
     * exactly one of them.)
     *
     * Rows are uploaded as three `vec3` uniforms rather than a `mat3` so the
     * layout is unambiguous — GLSL matrices are column-major, and a transpose
     * here would be invisible until someone compared a screenshot.
     */
    fun toUniforms(m: FloatArray): FloatArray {
        val out = FloatArray(12)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                out[r * 3 + c] = m[r * 5 + c]
            }
            out[9 + r] = m[r * 5 + 4] / 255f
        }
        return out
    }

    /**
     * Convenience: template + knobs straight to shader uniforms.
     */
}
