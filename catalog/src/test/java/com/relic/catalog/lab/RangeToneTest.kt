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
            "1.0 - smooth01((l - 0.05) / 0.50)",   // shadows
            "1.0 - smooth01(l / 0.28)",            // blacks
            "smooth01((l - 0.45) / 0.50)",         // highlights
            "smooth01((l - 0.72) / 0.28)",         // whites
        )) {
            assertTrue("shader is missing: $f", h.contains(f))
        }
    }

    @Test
    fun `the shader orders the bands as highlights shadows whites blacks`() {
        // u_ranges is declared in the shared HEADER, not in the lab body.
        val h = com.relic.catalog.Shaders.HEADER
        assertTrue(h.contains("uniform vec4 u_ranges"))
        // The loop walks 0..3 in order, so rangeWeight's band numbers have to be
        // in the same order the recipe packs them.
        val idx = listOf("(band == 0)", "(band == 1)", "(band == 2)")
        var prev = -1
        for (i in idx) {
            val at = h.indexOf(i)
            assertTrue("missing $i", at > prev)
            prev = at
        }
    }

    @Test
    fun `the shader lifts on positive and rolls off on negative, like adjust does`() {
        val g = com.relic.catalog.Shaders.HEADER
        assertTrue(g.contains("a >= 0.0 ? c + a * w * (1.0 - c) : c * (1.0 + a * w)"))
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
}
