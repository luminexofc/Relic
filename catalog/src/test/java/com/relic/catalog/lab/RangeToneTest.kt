package com.relic.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeToneTest {

    /**
     * The shader is a separate implementation of this arithmetic and is what
     * actually runs. These assertions read the band edges out of the generated
     * GLSL and check them against the Kotlin constants, which is the only cheap
     * way to stop the two drifting. They pin the exact expressions, so editing
     * one without the other fails here rather than showing up as a preview that
     * disagrees with the app's own maths.
     */
    @Test
    fun `the shader implements the same bands as this object`() {
        val h = com.relic.catalog.Shaders.HEADER
        for (f in listOf(
            "smooth01((l - 0.45) / 0.50)",   // highlights weight
            "1.0 - smooth01((l - 0.05) / 0.50)",   // shadows weight
        )) {
            assertTrue("shader is missing: $f", h.contains(f))
        }
    }

    @Test
    fun `the shader pulls highlights and shadows toward the blurred average`() {
        // u_ranges order (highlights, shadows, whites, blacks) is
        // LabRecipe.rangeArray and must stay in sync with it: x drives the
        // over-average pull, y the under-average pull. The uniform is declared
        // in HEADER but applied in LAB_GRADE, so both are checked.
        val h = com.relic.catalog.Shaders.HEADER
        assertTrue(h.contains("uniform vec4 u_ranges"))
        val body = com.relic.catalog.Shaders.LAB_GRADE
        assertTrue(body.contains("c += u_ranges.x * wH * max(c - bl, 0.0)"))
        assertTrue(body.contains("c += u_ranges.y * wS * max(bl - c, 0.0)"))
    }

    @Test
    fun `whites and blacks ride bounded endpoint curves`() {
        // smoothstep/6 peaks at 1.5/6 slope, so the global terms alone cannot
        // turn a ramp around either; the scan below proves the combination.
        val h = com.relic.catalog.Shaders.HEADER
        assertTrue(h.contains("vec3 whiteCurve(vec3 c)"))
        assertTrue(h.contains("vec3 blackCurve(vec3 c)"))
        assertTrue(h.contains("t * t * (3.0 - 2.0 * t) / 6.0"))
    }

    /**
     * A zero amount must be an exact no-op on every channel, or every recipe in
     * the app picks up a slight tone shift from a control nobody touched.
     */
    @Test
    fun `a zero amount changes nothing`() {
        for (v in listOf(0f, 0.1f, 0.5f, 1f)) {
            for (w in listOf(0f, 0.3f, 1f)) {
                assertEquals(v, RangeTone.adjust(v, w, 0f), 0f)
            }
        }
    }

    @Test
    fun `a zero weight changes nothing`() {
        for (v in listOf(0f, 0.4f, 1f)) {
            for (a in listOf(-1f, 0.5f, 1f)) {
                assertEquals(v, RangeTone.adjust(v, 0f, a), 0f)
            }
        }
    }

    @Test
    fun `a positive amount lifts and never leaves the range`() {
        for (c in listOf(0f, 0.2f, 0.6f, 1f)) {
            for (w in listOf(0f, 0.5f, 1f)) {
                val r = RangeTone.adjust(c, w, 0.8f)
                assertTrue("c=$c w=$w gave $r", r in c..1f)
            }
        }
    }

    @Test
    fun `a negative amount rolls off and never leaves the range`() {
        for (c in listOf(0f, 0.2f, 0.6f, 1f)) {
            for (w in listOf(0f, 0.5f, 1f)) {
                val r = RangeTone.adjust(c, w, -0.8f)
                assertTrue("c=$c w=$w gave $r", r in 0f..c)
            }
        }
    }

    @Test
    fun `the sign change is continuous through zero`() {
        // A discontinuity at a = 0 would show as a visible step when dragging the
        // slider through neutral.
        var prev = RangeTone.adjust(0.5f, 1f, -0.02f)
        for (i in -19..20) {
            val r = RangeTone.adjust(0.5f, 1f, i / 1000f)
            assertTrue("jump at $i", kotlin.math.abs(r - prev) < 0.01f)
            prev = r
        }
    }

    @Test
    fun `shadows are weighted to the dark end and highlights to the bright end`() {
        assertTrue(RangeTone.shadowWeight(0.05f) > 0.9f)
        assertEquals(0f, RangeTone.shadowWeight(0.7f), 0f)
        assertEquals(0f, RangeTone.highlightWeight(0.3f), 0f)
        assertTrue(RangeTone.highlightWeight(0.95f) > 0.9f)
    }

    @Test
    fun `blacks and whites are the narrow ends`() {
        assertTrue(RangeTone.blackWeight(0f) > 0.9f)
        assertEquals(0f, RangeTone.blackWeight(0.5f), 0f)
        assertEquals(0f, RangeTone.whiteWeight(0.5f), 0f)
        assertTrue(RangeTone.whiteWeight(1f) > 0.9f)
    }

    /** Bands have to overlap or the overlap region is a visible hard edge. */
    @Test
    fun `the bands overlap in the midtones`() {
        val l = 0.5f
        assertTrue("shadows", RangeTone.shadowWeight(l) > 0f)
        assertTrue("highlights", RangeTone.highlightWeight(l) > 0f)
    }

    @Test
    fun `weights are one at the extremes and bounded everywhere`() {
        assertEquals(1f, RangeTone.shadowWeight(0f), 0f)
        assertEquals(1f, RangeTone.blackWeight(0f), 0f)
        assertEquals(1f, RangeTone.whiteWeight(1f), 0f)
        assertEquals(1f, RangeTone.highlightWeight(1f), 0f)
        for (i in 0..100) {
            val l = i / 100f
            for (w in listOf(
                RangeTone.shadowWeight(l), RangeTone.blackWeight(l),
                RangeTone.highlightWeight(l), RangeTone.whiteWeight(l),
            )) {
                assertTrue("weight $w at l=$l", w in 0f..1f)
            }
        }
    }

    @Test
    fun `luminance beyond the ends clamps rather than extrapolating`() {
        assertEquals(1f, RangeTone.shadowWeight(-5f), 0f)
        assertEquals(0f, RangeTone.shadowWeight(5f), 0f)
        assertEquals(0f, RangeTone.whiteWeight(-1f), 0f)
        assertEquals(1f, RangeTone.whiteWeight(9f), 0f)
    }

    /**
     * The solarization regression test. The old global remap inverted the
     * tonal scale at combined extremes (Highlights -0.61 with Shadows +0.91
     * put a 0.10 input above a 0.90 one); the local formulation pulls toward
     * the neighbourhood average, which is the identity on a ramp, and the
     * global endpoint curves are slope-bounded. So the composite mapping must
     * be non-decreasing for EVERY combination, proven here by scanning the
     * whole grid rather than a handful of points.
     */
    @Test
    fun `the range stage never inverts the tonal scale`() {
        val ramp = (0..64).map { it / 64f }
        val steps = listOf(-1f, -0.75f, -0.5f, -0.25f, 0f, 0.25f, 0.5f, 0.75f, 1f)
        // The exact report that started this, plus every grid corner.
        val combos = mutableListOf(listOf(-0.61f, 0.91f, -0.79f, -0.48f))
        for (aH in steps) for (aS in steps) for (aW in steps) for (aB in steps) {
            combos.add(listOf(aH, aS, aW, aB))
        }
        for ((aH, aS, aW, aB) in combos) {
            // On a smooth ramp the blurred average equals the pixel, exactly
            // as the shader's tent of a gradient does away from the edges.
            val out = ramp.map { RangeTone.rangeStage(it, it, aH, aS, aW, aB) }
            for (i in 0 until out.lastIndex) {
                assertTrue(
                    "inversion at $i for amounts $aH,$aS,$aW,$aB",
                    out[i + 1] >= out[i] - 1e-6f,
                )
            }
            assertTrue(
                "brights must stay above darks for $aH,$aS,$aW,$aB",
                out[58] > out[6],
            )
        }
    }

    /**
     * Monotonicity alone could be satisfied by doing nothing, so this pins
     * that moderate values still move real content: a dark patch in bright
     * surroundings lifts, a bright patch in dark surroundings recovers, and
     * the global endpoints answer on flat fields where the local terms rest.
     */
    @Test
    fun `moderate values visibly recover and lift`() {
        val lifted = RangeTone.rangeStage(0.2f, 0.7f, 0f, 0.3f, 0f, 0f)
        assertTrue("shadow lift $lifted", lifted - 0.2f >= 0.05f)
        val recovered = RangeTone.rangeStage(0.85f, 0.3f, -0.3f, 0f, 0f, 0f)
        assertTrue("highlight recovery $recovered", 0.85f - recovered >= 0.05f)
        val whiter = RangeTone.rangeStage(0.8f, 0.8f, 0f, 0f, 1f, 0f)
        assertTrue("whites $whiter", whiter - 0.8f >= 0.05f)
        val blackLift = RangeTone.rangeStage(0.2f, 0.2f, 0f, 0f, 0f, -1f)
        assertTrue("blacks $blackLift", blackLift - 0.2f >= 0.02f)
    }

    @Test
    fun `zero amounts are an exact no-op in the stage`() {
        for (c in listOf(0f, 0.25f, 0.7f, 1f)) {
            assertEquals(c, RangeTone.rangeStage(c, 0.5f, 0f, 0f, 0f, 0f), 0f)
        }
    }
}
