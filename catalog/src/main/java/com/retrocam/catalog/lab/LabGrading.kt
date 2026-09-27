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
     * Android `ColorMatrix.setSaturation`, transcribed. Luminance-preserving:
     * the standard 0.213/0.715/0.072 weighting.
     */
    fun saturationMatrix(s: Float): FloatArray {
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return floatArrayOf(
            lr + s * (1f - lr), lg * (1f - s), lb * (1f - s), 0f, 0f,
            lr * (1f - s), lg + s * (1f - lg), lb * (1f - s), 0f, 0f,
            lr * (1f - s), lg * (1f - s), lb + s * (1f - lb), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Contrast pivots around mid-grey (128) with brightness as a post-offset, in
     * 0-255 units. Transcribed from `FilterEngine.applyAdjustments`.
     */
    fun contrastBrightnessMatrix(contrast: Float, brightness: Float): FloatArray {
        val c = contrast
        val offset = 128f * (1f - c) + brightness * 255f
        return floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

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
     * Compose two 4x5 row-vector matrices into the single matrix equivalent to
     * applying [a] and then [b]. This is Android `ColorMatrix.postConcat`'s
     * semantics: `b` is applied after `a`.
     *
     * With row vectors, `apply(a); apply(b)` is `in * (b * a)` on the linear
     * part, and `b_linear * a_offset + b_offset` on the translation column.
     */
    fun thenCompose(a: FloatArray, b: FloatArray): FloatArray {
        val c = FloatArray(20)
        for (j in 0 until 4) {
            for (k in 0 until 4) {
                var sum = 0f
                for (i in 0 until 4) sum += b[j * 5 + i] * a[i * 5 + k]
                c[j * 5 + k] = sum
            }
            var t = b[j * 5 + 4]
            for (i in 0 until 4) t += b[j * 5 + i] * a[i * 5 + 4]
            c[j * 5 + 4] = t
        }
        return c
    }

    /**
     * The full grade for a recipe: optional template matrix, then the five
     * knobs, in upstream's order. Neutral knobs are skipped, matching the
     * `if` guards in `FilterEngine.applyAdjustments` — skipping is not just an
     * optimisation, it is what keeps the result bit-comparable with upstream.
     *
     * Returns a fresh 4x5 in 0-255 units. Pass it to [toUniforms].
     */
    fun compose(template: FloatArray?, adjustments: LabAdjustments): FloatArray {
        val a = LabAdjustments.coerce(adjustments)
        var m = if (template != null && template.size == 20) template.copyOf() else IDENTITY.copyOf()
        if (a.saturation != 1f) m = thenCompose(m, saturationMatrix(a.saturation))
        if (a.contrast != 1f || a.brightness != 0f) {
            m = thenCompose(m, contrastBrightnessMatrix(a.contrast, a.brightness))
        }
        if (a.warmth != 0f || a.tint != 0f) {
            m = thenCompose(m, warmthTintMatrix(a.warmth, a.tint))
        }
        return m
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
    fun uniformsFor(template: FloatArray?, adjustments: LabAdjustments): FloatArray =
        toUniforms(compose(template, adjustments))
}
