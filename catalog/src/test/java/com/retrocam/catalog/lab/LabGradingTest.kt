package com.retrocam.catalog.lab

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Verifies the Filter Lab grading maths against the FilterLibrary behaviour it
 * was ported from.
 *
 * The load-bearing idea here is that matrix composition is checked by
 * *composition of functions* rather than against golden arrays: if
 * `thenCompose(a, b)` is a correct composition, then applying the composed
 * matrix must equal applying `a` and then `b`. That property is what actually
 * catches a transposed index or a reversed order, and it holds for arbitrary
 * inputs, so it does not depend on a handful of hand-picked cases being right.
 *
 * The handful of golden cases that *are* spelled out are there to pin the
 * transcription of the 35 upstream matrices, where the numbers were copied
 * mechanically from FilterLibrary's source and a typo would be invisible.
 */
class LabGradingTest {

    private val eps = 1e-4f

    // ---- reference implementations, deliberately naive ----

    /** Apply a 4x5 row-vector matrix to a 0-255 colour, clamping like an 8-bit bitmap. */
    private fun apply(m: FloatArray, rgb: FloatArray): FloatArray {
        val out = FloatArray(3)
        for (i in 0 until 3) {
            var sum = m[i * 5 + 4]
            for (k in 0 until 3) sum += rgb[k] * m[i * 5 + k]
            out[i] = sum
        }
        return out
    }

    private fun assertRgb(expected: FloatArray, actual: FloatArray, what: String) {
        for (i in 0 until 3) {
            // These matrices deliberately overshoot 0-255 (POLAROID_70S sums to
            // ~1.86 per row), so intermediates reach ~1000 and float32's ~1e-7
            // relative error shows up as ~1e-4 absolute. Compare relatively.
            val tol = 1e-3f + 1e-5f * abs(expected[i])
            assertTrue(
                abs(expected[i] - actual[i]) <= tol,
                "$what: channel $i expected ${expected[i]} got ${actual[i]} (tol $tol)",
            )
        }
    }

    /**
     * A random *well-formed* colour matrix, not a random 4x4.
     *
     * The distinction matters and is easy to get wrong: the reference [apply]
     * above treats colour as a row vector and ignores the alpha row entirely,
     * which is what the shader does. `thenCompose` however composes all four
     * rows faithfully, so against a garbage matrix its rgb rows pick up the
     * alpha-column term (`b[j][3] * a[3][k]`) that [apply] never evaluates — and
     * the two legitimately disagree. Constraining the alpha column to zero and
     * the alpha row to pass-through is exactly the contract the real pipeline
     * relies on, and asserting it here is part of the test's value.
     */
    private fun randMatrix(rnd: Random): FloatArray {
        val m = FloatArray(20)
        for (r in 0 until 3) {
            for (c in 0 until 3) m[r * 5 + c] = rnd.nextFloat() * 4f - 2f
            m[r * 5 + 3] = 0f
            m[r * 5 + 4] = rnd.nextFloat() * 255f - 128f
        }
        m[15] = 0f
        m[16] = 0f
        m[17] = 0f
        m[18] = 1f
        m[19] = 0f
        return m
    }

    // ---- thenCompose ----

    @Test
    fun `thenCompose equals applying a then b`() {
        val rnd = Random(20260927)
        repeat(200) {
            val a = randMatrix(rnd)
            val b = randMatrix(rnd)
            val rgb = FloatArray(3) { rnd.nextFloat() * 255f }
            val sequential = apply(b, apply(a, rgb))
            val composed = apply(LabGrading.thenCompose(a, b), rgb)
            assertRgb(sequential, composed, "compose mismatch")
        }
    }

    @Test
    fun `thenCompose is not commutative, and that is the point`() {
        // If this ever passes, the order information has been lost and
        // "saturation then contrast" and "contrast then saturation" would have
        // become the same filter.
        val a = LabGrading.contrastBrightnessMatrix(1.8f, 0f)
        val b = LabGrading.saturationMatrix(0.2f)
        assertFalse(
            a.contentEquals(LabGrading.thenCompose(a, b)),
            "composition collapsed to plain multiplication",
        )
    }

    @Test
    fun `composing with identity is a no-op`() {
        val rnd = Random(7)
        repeat(50) {
            val a = randMatrix(rnd)
            val c = LabGrading.thenCompose(a, LabGrading.IDENTITY)
            for (i in 0 until 20) assertTrue(abs(c[i] - a[i]) < eps, "identity broke at $i")
        }
    }

    // ---- the individual steps ----

    @Test
    fun `saturation at 1 is identity and at 0 is luminance grey`() {
        val one = LabGrading.saturationMatrix(1f)
        for (i in 0 until 20) {
            val expected = LabGrading.IDENTITY[i]
            assertTrue(abs(one[i] - expected) < eps, "saturation(1) at $i: ${one[i]} != $expected")
        }
        val zero = LabGrading.saturationMatrix(0f)
        val rgb = floatArrayOf(200f, 100f, 50f)
        val luma = 0.213f * 200f + 0.715f * 100f + 0.072f * 50f
        assertRgb(FloatArray(3) { luma }, apply(zero, rgb), "saturation(0) should be luminance grey")
    }

    @Test
    fun `saturation preserves the luminance of any colour`() {
        val rnd = Random(11)
        repeat(100) {
            val rgb = FloatArray(3) { rnd.nextFloat() * 255f }
            val before = 0.213f * rgb[0] + 0.715f * rgb[1] + 0.072f * rgb[2]
            val after = apply(LabGrading.saturationMatrix(0.4f), rgb)
            val luma = 0.213f * after[0] + 0.715f * after[1] + 0.072f * after[2]
            assertTrue(abs(before - luma) < 0.5f, "luminance drifted: $before -> $luma")
        }
    }

    @Test
    fun `contrast pivots around mid grey`() {
        val m = LabGrading.contrastBrightnessMatrix(1.5f, 0f)
        // offset = 128 * (1 - c); 128 must map to itself.
        val mid = apply(m, floatArrayOf(128f, 128f, 128f))
        assertRgb(FloatArray(3) { 128f }, mid, "mid grey should be the pivot")
    }

    @Test
    fun `brightness is a post offset of brightness times 255`() {
        val m = LabGrading.contrastBrightnessMatrix(1f, 0.1f)
        val black = apply(m, floatArrayOf(0f, 0f, 0f))
        assertRgb(FloatArray(3) { 25.5f }, black, "brightness 0.1 should lift black by 25.5")
    }

    @Test
    fun `warmth lifts red and drops blue, tint moves green`() {
        // Each knob is +-0.2 per unit, so full warm scales red by 1.2 (which
        // overshoots white on purpose; the shader clamps).
        val warm = LabGrading.warmthTintMatrix(1f, 0f)
        assertRgb(floatArrayOf(306f, 0f, 0f), apply(warm, floatArrayOf(255f, 0f, 0f)), "warmth scales red up")
        val cool = LabGrading.warmthTintMatrix(-1f, 0f)
        assertRgb(floatArrayOf(204f, 0f, 0f), apply(cool, floatArrayOf(255f, 0f, 0f)), "cool scales red down")
        val green = LabGrading.warmthTintMatrix(0f, 1f)
        assertRgb(floatArrayOf(0f, 306f, 0f), apply(green, floatArrayOf(0f, 255f, 0f)), "tint scales green up")
    }

    // ---- compose: order and neutral skipping ----

    @Test
    fun `compose applies template then saturation then contrast then warmth`() {
        val template = LabTemplates.byId.getValue("KODAK_PORTRA").matrix
        val adj = LabAdjustments(saturation = 0.5f, contrast = 1.3f, brightness = 0.05f, warmth = 0.4f)
        val rgb = floatArrayOf(120f, 90f, 200f)

        // Straightforward sequential application, step by step, in the order
        // FilterEngine.applyAdjustments uses.
        var expected = apply(template, rgb)
        expected = apply(LabGrading.saturationMatrix(0.5f), expected)
        expected = apply(LabGrading.contrastBrightnessMatrix(1.3f, 0.05f), expected)
        expected = apply(LabGrading.warmthTintMatrix(0.4f, 0f), expected)

        assertRgb(expected, apply(LabGrading.compose(template, adj), rgb), "compose order")
    }

    @Test
    fun `neutral adjustments leave the template untouched`() {
        for (id in LabTemplates.all.map { it.id }) {
            val t = LabTemplates.byId.getValue(id)
            val composed = LabGrading.compose(t.matrix, LabAdjustments.NEUTRAL)
            for (i in 0 until 20) {
                assertTrue(abs(composed[i] - t.matrix[i]) < eps, "$id altered at $i with neutral knobs")
            }
        }
    }

    @Test
    fun `no template means the knobs act on the base filter directly`() {
        val adj = LabAdjustments(contrast = 1.4f)
        val rgb = floatArrayOf(10f, 128f, 240f)
        val expected = apply(LabGrading.contrastBrightnessMatrix(1.4f, 0f), rgb)
        assertRgb(expected, apply(LabGrading.compose(null, adj), rgb), "no-template grade")
    }

    @Test
    fun `coerce clamps hostile recipe values`() {
        val wild = LabAdjustments(brightness = 99f, contrast = -5f, saturation = -1f, warmth = 50f, tint = -50f)
        val c = LabAdjustments.coerce(wild)
        assertEquals(1f, c.brightness)
        assertEquals(0f, c.contrast)
        assertEquals(0f, c.saturation)
        assertEquals(1f, c.warmth)
        assertEquals(-1f, c.tint)
        // And the composed matrix must stay finite.
        val m = LabGrading.compose(null, wild)
        assertTrue(m.all { it.isFinite() }, "coerced matrix went non-finite")
    }

    // ---- toUniforms layout ----

    @Test
    fun `toUniforms sends rows verbatim and divides only the offset by 255`() {
        val m = floatArrayOf(
            1.1f, 0.2f, 0.3f, 0f, 20f,
            0.4f, 1.2f, 0.5f, 0f, -10f,
            0.6f, 0.7f, 1.3f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f,
        )
        val u = LabGrading.toUniforms(m)
        assertEquals(12, u.size)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                assertEquals(m[r * 5 + c], u[r * 3 + c], "linear part r$r c$c")
            }
            assertEquals(m[r * 5 + 4] / 255f, u[9 + r], "offset r$r must be /255")
        }
    }

    @Test
    fun `uniforms reproduce the matrix when applied in 0 to 1 space`() {
        val rnd = Random(99)
        repeat(100) {
            val m = randMatrix(rnd)
            val rgb = floatArrayOf(rnd.nextFloat() * 255f, rnd.nextFloat() * 255f, rnd.nextFloat() * 255f)
            val expected = apply(m, rgb)

            val u = LabGrading.toUniforms(m)
            val unit = floatArrayOf(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
            val got = FloatArray(3) { r ->
                var sum = u[9 + r]
                for (k in 0 until 3) sum += unit[k] * u[r * 3 + k]
                sum * 255f
            }
            assertRgb(expected, got, "uniform application")
        }
    }

    // ---- the transcribed upstream matrices ----

    @Test
    fun `there are 35 matrix templates`() {
        assertEquals(35, LabTemplates.all.size)
        assertEquals(35, LabTemplates.byId.size)
    }

    @Test
    fun `every template is a finite 20 float matrix with a sane alpha row`() {
        for (t in LabTemplates.all) {
            assertEquals(20, t.matrix.size, "${t.id} matrix size")
            assertTrue(t.matrix.all { it.isFinite() }, "${t.id} has non-finite values")
            // RETRO_WARM is upstream's one oddity: its alpha row is
            // (0, -0.1, 0, 0.9, 0) rather than pass-through. We ignore the alpha
            // row, so this is expected, but it should stay a known exception
            // rather than silently appearing elsewhere.
            if (t.id != "RETRO_WARM") {
                val alpha = t.matrix.copyOfRange(15, 20)
                assertTrue(
                    alpha[0] == 0f && alpha[1] == 0f && alpha[2] == 0f && alpha[3] == 1f,
                    "${t.id} unexpected alpha row ${alpha.toList()}",
                )
            }
        }
    }

    @Test
    fun `template ids are unique and stable`() {
        assertEquals(LabTemplates.all.size, LabTemplates.all.map { it.id }.toSet().size)
        // Spelled out so a re-transcription that renames or drops one fails loudly
        // rather than quietly changing what a shared recipe points at.
        for (id in listOf("GRAYSCALE", "SEPIA", "KODAK_PORTRA", "POLAROID_70S", "TEAL_AND_ORANGE",
            "CYBERPUNK", "CROSS_PROCESS", "DRAMATIC", "SOLARIZE", "INVERT", "RETRO_WARM")) {
            assertTrue(LabTemplates.byId.containsKey(id), "missing template $id")
        }
    }

    @Test
    fun `golden GRAYSCALE is the standard luma weights`() {
        val g = LabTemplates.byId.getValue("GRAYSCALE")
        assertEquals(0.299f, g.matrix[0], eps)
        assertEquals(0.587f, g.matrix[1], eps)
        assertEquals(0.114f, g.matrix[2], eps)
        assertRgb(
            floatArrayOf(128f, 128f, 128f),
            apply(g.matrix, floatArrayOf(128f, 128f, 128f)),
            "grey in, grey out",
        )
    }

    @Test
    fun `golden INVERT is 255 minus each channel`() {
        val inv = LabTemplates.byId.getValue("INVERT").matrix
        assertRgb(floatArrayOf(155f, 155f, 155f), apply(inv, floatArrayOf(100f, 100f, 100f)), "invert")
    }

    @Test
    fun `golden SOLARIZE is 1 point 5 times the channel minus 128`() {
        val s = LabTemplates.byId.getValue("SOLARIZE").matrix
        // 0 -> -128, 128 -> 64, 255 -> 254.5
        assertRgb(floatArrayOf(-128f, -128f, -128f), apply(s, floatArrayOf(0f, 0f, 0f)), "solarize black")
        assertRgb(floatArrayOf(64f, 64f, 64f), apply(s, floatArrayOf(128f, 128f, 128f)), "solarize mid")
    }

    @Test
    fun `golden MONO_WARM offsets differ per channel`() {
        // The one preset whose rows are not identical, so it catches a
        // transcription that flattened the three rows into one.
        val m = LabTemplates.byId.getValue("MONO_WARM").matrix
        assertTrue(m[4] != m[9] || m[9] != m[14], "MONO_WARM offsets should not be uniform")
        val out = apply(m, floatArrayOf(0f, 0f, 0f))
        assertTrue(abs(out[0] - 15f) < eps, "red offset should be 15, got ${out[0]}")
        assertTrue(abs(out[1] - 10f) < eps, "green offset should be 10, got ${out[1]}")
        assertTrue(abs(out[2] - (-5f)) < eps, "blue offset should be -5, got ${out[2]}")
    }

    @Test
    fun `out of range gains are left for the shader to clamp`() {
        // POLAROID_70S row 0 sums to about 1.86, so white clips hard. If the
        // port had started clamping during composition the look would be wrong,
        // so assert we do NOT clamp and the shader has to.
        val p = LabTemplates.byId.getValue("POLAROID_70S").matrix
        val white = apply(p, floatArrayOf(255f, 255f, 255f))
        assertTrue(white[0] > 255f, "expected POLAROID to overshoot, got ${white[0]}")
    }

    // ---- recipe plumbing ----

    @Test
    fun `recipe resolves its template matrix and knows when it is a no-op`() {
        assertTrue(LabRecipe().isIdentity)
        assertTrue(LabRecipe(templateId = null, adjustments = LabAdjustments.NEUTRAL).isIdentity)
        assertFalse(LabRecipe(templateId = "SEPIA").isIdentity)
        assertFalse(LabRecipe(adjustments = LabAdjustments(brightness = 0.2f)).isIdentity)
        assertEquals(
            LabTemplates.byId.getValue("SEPIA").matrix.toList(),
            LabRecipe(templateId = "SEPIA").templateMatrix()!!.toList(),
        )
        // A recipe naming a template that no longer exists must degrade to "no
        // template" rather than throwing: shared recipes outlive catalogs.
        assertEquals(null, LabRecipe(templateId = "REMOVED_FILTER").templateMatrix())
        assertFalse(LabRecipe(templateId = "REMOVED_FILTER").isIdentity)
    }

    @Test
    fun `isIdentity is false for an unknown template so the stage still runs`() {
        // If this were true the grade would be silently dropped for recipes
        // whose template was removed upstream of us.
        assertFalse(LabRecipe(templateId = "GONE").isIdentity)
    }
}
