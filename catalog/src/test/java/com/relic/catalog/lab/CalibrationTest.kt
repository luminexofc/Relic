package com.relic.catalog.lab

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adobe's Calibration panel: six primary trims into one 3x3.
 *
 * The point of a calibration is to move one primary without disturbing the
 * others, so almost every test here is about a channel that must NOT move.
 */
class CalibrationTest {

    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        m[0] * r + m[1] * g + m[2] * b,
        m[5] * r + m[6] * g + m[7] * b,
        m[10] * r + m[11] * g + m[12] * b,
    )

    private fun none() = floatArrayOf(0f, 0f, 0f)
    private fun hue(vararg v: Float) = floatArrayOf(*v, *FloatArray(3 - v.size))
    private fun sat(vararg v: Float) = floatArrayOf(*v, *FloatArray(3 - v.size))

    private fun close(a: Float, b: Float, tol: Float = 1e-3f) =
        assertTrue(abs(a - b) <= tol, "expected $b, got $a")

    @Test
    fun `no adjustment is the identity`() {
        val m = Calibration.build(hue(), sat())
        for (i in 0 until 3) for (j in 0 until 3) {
            close(m[i * 5 + j], if (i == j) 1f else 0f)
        }
        assertFalse(Calibration.isActive(m))
    }

    /**
     * The defining property: a saturation trim on one primary must leave the
     * other two alone. This is what a calibration is for and what a diagonal
     * warmth scale cannot do.
     */
    @Test
    fun `a saturation trim touches only its own primary`() {
        val m = Calibration.build(hue(), sat(0.5f, 0f, 0f))
        // Pure green: red and blue primaries have no magnitude to scale, so it
        // must pass through unchanged.
        val g = apply(m, 0f, 1f, 0f)
        close(g[0], 0f); close(g[1], 1f); close(g[2], 0f)
        // Pure red is the one that moves, and only in red.
        val r = apply(m, 1f, 0f, 0f)
        assertTrue(r[0] > 1f, "red should have been lifted, was ${r[0]}")
        close(r[1], 0f); close(r[2], 0f)
    }

    /**
     * And the hue half, which is why this is a matrix and not a scale: a
     * rotation must move a primary AWAY from the others, so a red rotation has
     * to put some green or blue into a pure red.
     */
    @Test
    fun `a hue rotation moves a primary into its neighbour`() {
        val m = Calibration.build(hue(0.5f, 0f, 0f), sat())
        // Red's plane is itself and green, so a red rotation puts green into a
        // pure red. If the plane indices were off by one this would land in blue
        // instead, and the matrix would still look valid.
        val r = apply(m, 1f, 0f, 0f)
        assertTrue(abs(r[1]) > 0.01f, "red should have pulled in green, got $r")
        close(r[2], 0f, 1e-3f)
    }

    /**
     * Each primary rotates in its own plane, and the pair is (self, one other):
     * red with green, green with blue, blue with red. A test that only checks
     * "something moved" cannot catch a wrong pairing, because a rotation in the
     * wrong plane is still a rotation.
     */
    @Test
    fun `each primary rotates against the right neighbour`() {
        // Red against green, so blue must be untouched.
        close(apply(Calibration.build(hue(0.5f, 0f, 0f), sat()), 1f, 0f, 0f)[2], 0f)
        // Green against blue, so red must be untouched.
        close(apply(Calibration.build(hue(0f, 0.5f, 0f), sat()), 0f, 1f, 0f)[0], 0f)
        // Blue against red, so green must be untouched.
        close(apply(Calibration.build(hue(0f, 0f, 0.5f), sat()), 0f, 0f, 1f)[1], 0f)
    }

    @Test
    fun `the rotation is reversible`() {
        val forward = Calibration.build(hue(0.6f, 0f, 0f), sat())
        val back = Calibration.build(hue(-0.6f, 0f, 0f), sat())
        // The red primary's own channel must be unchanged by a round trip.
        val a = apply(forward, 1f, 0.1f, 0.1f)
        val b = apply(back, a[0], a[1], a[2])
        close(b[0], 1f, 1e-2f)
        close(b[1], 0.1f, 1e-2f)
        close(b[2], 0.1f, 1e-2f)
    }

    @Test
    fun `a full scale rotation is the stated angle`() {
        val m = Calibration.build(hue(1f, 0f, 0f), sat())
        // Rotating the red axis by 60 degrees, so green picks up sin(60).
        val r = apply(m, 1f, 0f, 0f)
        close(r[0], kotlin.math.cos(Math.toRadians(Calibration.DEGREES.toDouble())).toFloat(), 1e-3f)
    }

    @Test
    fun `the three primaries are independent`() {
        val m = Calibration.build(hue(0.3f, -0.4f, 0.5f), sat(0.2f, 0.3f, -0.4f))
        // A mid grey is all three primaries at once, so every one of them has to
        // contribute or one of the trims was silently dropped.
        val grey = apply(m, 0.5f, 0.5f, 0.5f)
        assertTrue(grey.any { abs(it - 0.5f) > 1e-3f }, "nothing happened: $grey")

        // The saturation trims on their own, with no rotation, so the diagonal
        // is exactly the scale. Checked on a hue-free matrix because a rotation
        // rewrites the diagonal as well (it becomes cos of the angle), which
        // would make this assert something false.
        val satOnly = Calibration.build(hue(), sat(0.2f, 0.3f, -0.4f))
        close(satOnly[0], 1f + 0.2f * Calibration.SAT_RANGE, 1e-3f)
        close(satOnly[6], 1f + 0.3f * Calibration.SAT_RANGE, 1e-3f)
        close(satOnly[12], 1f - 0.4f * Calibration.SAT_RANGE, 1e-3f)

        // With all three rotations, no row may be a plain diagonal, or one of
        // them was lost.
        val offDiag = listOf(m[1], m[2], m[5], m[7], m[10], m[11])
        assertTrue(offDiag.all { abs(it) > 1e-4f }, "a rotation was dropped: $offDiag")
    }

    // ---- the recipe field ----

    @Test
    fun `round trips through the recipe field`() {
        val h = hue(0.1f, -0.2f, 0.3f)
        val s = sat(0.4f, 0.5f, -0.6f)
        val enc = Calibration.encode(h, s)
        val (h2, s2) = assertNotNull(Calibration.parse(enc))
        for (i in 0 until 3) {
            close(h2[i], h[i], 5e-3f)
            close(s2[i], s[i], 5e-3f)
        }
    }

    @Test
    fun `neutral encodes to the dash placeholder`() {
        assertEquals(Calibration.NONE, Calibration.encode(hue(), sat()))
        assertNull(Calibration.parse(Calibration.NONE))
        assertNull(Calibration.parse(null))
        assertNull(Calibration.parse(""))
    }

    /**
     * A comma is the codec's field separator, so the packed calibration must
     * not contain one. Same trap as the colour mixer, and it would make every
     * recipe with a calibration unloadable.
     */
    @Test
    fun `the packed field contains no comma`() {
        val enc = Calibration.encode(hue(0.1f, 0.2f, 0.3f), sat(0.4f, 0.5f, 0.6f))
        assertFalse(enc.contains(','), "calibration must not contain a comma: $enc")
        val saved = SavedRecipe.create("C", "original", LabRecipe(calibration = enc))
        val payload = RecipeCodec.encode(saved)
        val plain = RecipeCodec.encode(SavedRecipe.create("C", "original", LabRecipe()))
        assertEquals(
            plain.split(',').size, payload.split(',').size,
            "the calibration changed the payload field count",
        )
        val back = RecipeCodec.decode(payload)
        assertNotNull(back, "a recipe with a calibration must decode")
        assertEquals(enc, back!!.lab.calibration)
    }

    @Test
    fun `an unreadable field is unusable rather than partly applied`() {
        assertNull(Calibration.parse("0.1 0.2"))
        assertNull(Calibration.parse("0.1 0.2 0.3 0.4 0.5 x"))
        assertNull(Calibration.parse("0.1,0.2,0.3,0.4,0.5,0.6,0.7"))
    }

    @Test
    fun `out of range values are clamped not rejected`() {
        val (h, s) = assertNotNull(Calibration.parse("99 -99 0 0 0 0"))
        for (v in h) assertTrue(v in -1f..1f, "hue $v out of range")
        for (v in s) assertTrue(v in -1f..1f, "sat $v out of range")
    }

    @Test
    fun `the shader declares and uses the calibration it was given`() {
        val h = com.relic.catalog.Shaders.HEADER
        assertTrue(h.contains("uniform float u_calActive;"), "u_calActive missing")
        for (n in listOf("u_calR0", "u_calR1", "u_calR2")) {
            assertTrue(h.contains("uniform vec3 $n;"), "$n missing")
        }
        val lab = com.relic.catalog.Shaders.LAB_GRADE
        assertTrue(lab.contains("u_calActive > 0.0"), "the skip guard is missing")
        assertTrue(lab.contains("dot(u_calR0, c)"), "the matrix is never applied")
    }
}
