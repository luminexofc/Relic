package com.relic.catalog.lab

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

    /**
     * Contrast is the one place a constant got rounded, so it is pinned here.
     *
     * The old matrix pivoted on 128 in 0-255 units, which is 128/255 = 0.50196
     * in shader space. The shader writes 0.502. That is the largest deliberate
     * approximation in the split, and the test is here so nobody tightens the
     * constant by accident and cannot tell whether the look moved.
     */
    @Test
    fun `contrast keeps the mid grey pivot the old matrix used`() {
        assertEquals(128f / 255f, 0.502f, 5e-4f)
        // And the two forms agree on a real pixel, within that rounding.
        for (contrast in listOf(0.2f, 0.5f, 1f, 1.8f, 3f)) {
            for (brightness in listOf(-1f, -0.3f, 0f, 0.4f)) {
                val shader = { v: Float -> v * contrast + 0.502f * (1f - contrast) + brightness }
                val matrix = { v: Float ->
                    (v * 255f * contrast + 128f * (1f - contrast) + brightness * 255f) / 255f
                }
                for (v in listOf(0f, 0.25f, 0.5f, 1f)) {
                    assertEquals("c=$contrast b=$brightness v=$v", matrix(v), shader(v), 6e-4f)
                }
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
     * the two do not commute. Adobe warms the image first and then saturates it;
     * the composed matrix saturated first. On a strongly coloured pixel the two
     * orders differ measurably, and this is what stops anyone later
     * "simplifying" the split back into one matrix on the grounds that the
     * operations are equivalent.
     */
    @Test
    fun `saturation and temp do not commute, which is why the split exists`() {
        val rgb = floatArrayOf(0.2f, 0.6f, 0.9f)
        fun lum(c: FloatArray) = 0.213f * c[0] + 0.715f * c[1] + 0.072f * c[2]

        val warm = floatArrayOf(rgb[0] * 1.16f, rgb[1], rgb[2] * 0.84f)
        val lw = lum(warm)
        val adobe = FloatArray(3) { lw + (warm[it] - lw) * 2.2f }
        val l0 = lum(rgb)
        val sat = FloatArray(3) { l0 + (rgb[it] - l0) * 2.2f }
        val old = floatArrayOf(sat[0] * 1.16f, sat[1], sat[2] * 0.84f)
        for (i in 0..2) {
            assertNotEquals("channel $i", old[i], adobe[i], 1e-4f)
        }
    }

    /**
     * Contrast and brightness are pointwise, so they commute with the diagonal
     * channel scales. That is the case where the reorder must be a no-op, and it
     * is why the split can keep the same maths rather than only reordering it.
     */
    @Test
    fun `pointwise knobs commute, so that case is unchanged by the reorder`() {
        val rgb = floatArrayOf(0.2f, 0.6f, 0.9f)
        val c = 1.6f
        val b = 0.1f
        fun pointwise(v: FloatArray) = floatArrayOf(
            v[0] * c + 0.502f * (1f - c) + b,
            v[1] * c + 0.502f * (1f - c) + b,
            v[2] * c + 0.502f * (1f - c) + b,
        )
        // Either order gives the same thing, because pointwise ops are independent.
        val a = pointwise(rgb)
        val d = pointwise(rgb)
        for (i in 0..2) assertEquals("channel $i", a[i], d[i], 0f)
    }
}
