package com.retrocam.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The Adobe-order split.
 *
 * The point of these is that the scalars must be *numerically identical* to the
 * matrices they replaced, or every existing recipe silently changes look. The
 * order is not identical, and that is deliberate, so there is a test proving the
 * difference is real rather than assuming the split was free.
 */
class LabGradingSplitTest {

    /**
     * Apply a 4x5, scaling only the translation column into 0-1.
     *
     * The linear part is already unitless and the column is in 0-255 units, so
     * the shader's 12-float form divides the column by 255 and leaves the rest
     * alone. Dividing the whole result instead makes every channel 255x too
     * small and the comparisons fail for the wrong reason.
     */
    private fun apply(v: FloatArray, rgb: FloatArray): FloatArray = floatArrayOf(
        v[0] * rgb[0] + v[1] * rgb[1] + v[2] * rgb[2] + v[4] / 255f,
        v[5] * rgb[0] + v[6] * rgb[1] + v[7] * rgb[2] + v[9] / 255f,
        v[10] * rgb[0] + v[11] * rgb[1] + v[12] * rgb[2] + v[14] / 255f,
    )

    /** What the shader now does, in Adobe's order. */
    private fun applySplit(s: LabGrading.Split, rgb: FloatArray): FloatArray {
        var c = rgb.copyOf()
        // template
        c = floatArrayOf(
            s.template[0] * c[0] + s.template[1] * c[1] + s.template[2] * c[2] + s.template[9],
            s.template[3] * c[0] + s.template[4] * c[1] + s.template[5] * c[2] + s.template[10],
            s.template[6] * c[0] + s.template[7] * c[1] + s.template[8] * c[2] + s.template[11],
        )
        // contrast, then temp/tint, then saturation
        c = FloatArray(3) {
            (c[it] * s.contrast + 0.502f * (1f - s.contrast) + s.brightness)
        }
        c = floatArrayOf(c[0] * s.rScale, c[1] * s.gScale, c[2] * s.bScale)
        val lum = 0.213f * c[0] + 0.715f * c[1] + 0.072f * c[2]
        return FloatArray(3) { lum + (c[it] - lum) * s.saturation }
    }

    @Test
    fun `warmth and tint become the same three scales the matrix carried`() {
        for (warmth in listOf(-1f, -0.3f, 0f, 0.4f, 1f)) {
            for (tint in listOf(-1f, 0f, 0.7f)) {
                val m = LabGrading.warmthTintMatrix(warmth, tint)
                val s = LabGrading.split(null, LabAdjustments(warmth = warmth, tint = tint))
                assertEquals("warmth $warmth", m[0], s.rScale, 1e-6f)
                assertEquals("tint $tint", m[6], s.gScale, 1e-6f)
                assertEquals("warmth $warmth", m[12], s.bScale, 1e-6f)
            }
        }
    }

    @Test
    fun `contrast and brightness keep the old matrix's numbers`() {
        for (contrast in listOf(0f, 0.5f, 1f, 1.8f, 3f)) {
            for (brightness in listOf(-1f, 0f, 0.2f)) {
                val m = LabGrading.contrastBrightnessMatrix(contrast, brightness)
                val s = LabGrading.split(null, LabAdjustments(contrast = contrast, brightness = brightness))
                assertEquals(contrast, m[0], 1e-6f)
                assertEquals(contrast, m[6], 1e-6f)
                assertEquals(contrast, m[12], 1e-6f)
                // 128/255, which is the 0.502 in the shader.
                val offset = m[4] / 255f
                assertEquals(offset, 0.502f * (1f - contrast) + brightness, 1e-3f)
                assertEquals(brightness, s.brightness, 1e-6f)
            }
        }
    }

    @Test
    fun `the template is carried on its own and no longer absorbs the knobs`() {
        val t = LabTemplates.all.first().matrix
        val s = LabGrading.split(t, LabAdjustments(contrast = 2f, saturation = 0f))
        // The template matrix must be untouched by the knobs now. Before the
        // split, compose() multiplied the knobs into it.
        val tOnly = LabGrading.toUniforms(t)
        for (i in tOnly.indices) {
            assertEquals("template slot $i", tOnly[i], s.template[i], 1e-6f)
        }
        assertEquals(2f, s.contrast, 0f)
        assertEquals(0f, s.saturation, 0f)
    }

    @Test
    fun `no template gives the identity matrix`() {
        val s = LabGrading.split(null, LabAdjustments.NEUTRAL)
        for (i in 0 until 12) {
            val expect = if (i % 4 == 0) 1f else 0f
            assertEquals(expect, s.template[i], 1e-6f)
        }
    }

    @Test
    fun `neutral knobs give a neutral split`() {
        val s = LabGrading.split(null, LabAdjustments.NEUTRAL)
        assertEquals(1f, s.contrast, 0f)
        assertEquals(0f, s.brightness, 0f)
        assertEquals(1f, s.rScale, 1e-6f)
        assertEquals(1f, s.gScale, 1e-6f)
        assertEquals(1f, s.bScale, 1e-6f)
        assertEquals(1f, s.saturation, 0f)
    }

    /**
     * The reorder is a real behaviour change, not a refactor with no effect.
     *
     * Saturation is luminance-weighted and temp/tint is a per-channel scale, so
     * the two do not commute: Adobe warms the image first and then saturates it,
     * where the composed matrix saturated first. On a strongly coloured pixel the
     * two orders give measurably different results, and this is the test that
     * stops anyone later "simplifying" the split back into one matrix on the
     * grounds that the operations are equivalent.
     */
    @Test
    fun `saturation and temp no longer commute, which is why the split exists`() {
        val adj = LabAdjustments(warmth = 0.8f, saturation = 2.2f)
        val rgb = floatArrayOf(0.2f, 0.6f, 0.9f)

        val old = apply(LabGrading.compose(null, adj), rgb)
        val neu = applySplit(LabGrading.split(null, adj), rgb)

        fun q(v: FloatArray) = v.map { (it * 255f).roundToInt() }
        assertNotEquals(
            "the reorder should be observable, or the split is pointless",
            q(old).toString(), q(neu).toString(),
        )
        // Both are still in gamut and close, so this is a reordering and not a
        // change in overall strength.
        for (i in 0..2) {
            assertEquals("channel $i is far off", old[i], neu[i], 0.25f)
        }
    }

    @Test
    fun `with only pointwise knobs the two orders agree exactly`() {
        // Contrast and brightness commute with the diagonal channel scales, so
        // when only those are set the reorder must be a no-op. If this ever
        // fails, something other than the order is changing.
        val adj = LabAdjustments(contrast = 1.6f, brightness = 0.1f)
        val rgb = floatArrayOf(0.2f, 0.6f, 0.9f)
        val old = apply(LabGrading.compose(null, adj), rgb)
        val neu = applySplit(LabGrading.split(null, adj), rgb)
        for (i in 0..2) {
            // 1e-4 of full range is 0.026/255, far below anything visible. The
            // two paths round differently - one scales 0-255 then divides, the
            // other divides first - so they agree to float32, not to the bit.
            assertEquals("channel $i", old[i], neu[i], 1e-4f)
        }
    }
}
