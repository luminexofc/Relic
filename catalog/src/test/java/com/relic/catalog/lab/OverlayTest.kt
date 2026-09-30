package com.relic.catalog.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Overlay geometry.
 *
 * The subtle part is the Y axis. In the renderer `quadPos` pairs clip y=-1 with
 * texcoord y=0, and clip -1 is the *bottom* of the viewport, so frame uv.y grows
 * upward. An Android Bitmap, on the other hand, has row 0 at the top. The shader
 * therefore flips v inside the rect. Only the rect is computed here, so if that
 * one line in the shader is ever wrong, these tests still pass - which is
 * exactly why they are written as "the rect is a pure function of the corner"
 * and nothing more.
 */
class OverlayPlacementTest {

    private fun area(r: FloatArray) = (r[2] - r[0]) * (r[3] - r[1])

    @Test
    fun `every position produces a rect inside the frame`() {
        for (pos in StampPosition.entries) {
            for (aspect in listOf(0.2f, 1f, 3f, 8f)) {
                val r = OverlayPlacement.rect(pos, aspect)
                assertTrue(r[0] >= -1e-4f, "$pos x0=${r[0]}")
                assertTrue(r[1] >= -1e-4f, "$pos y0=${r[1]}")
                assertTrue(r[2] <= 1f + 1e-4f, "$pos x1=${r[2]}")
                assertTrue(r[3] <= 1f + 1e-4f, "$pos y1=${r[3]}")
                assertTrue(r[2] > r[0] && r[3] > r[1], "$pos degenerate for aspect=$aspect")
            }
        }
    }

    @Test
    fun `corners sit at the expected edges with y measured from the bottom`() {
        val a = 1f
        // Bottom right: hugging the right edge and the BOTTOM of the frame, which
        // is y0 near 0 because frame y grows upward.
        val br = OverlayPlacement.rect(StampPosition.BOTTOM_RIGHT, a)
        val pad = OverlayPlacement.STAMP_PAD
        val w = br[2] - br[0]
        val h = br[3] - br[1]
        assertEquals(1f - pad - w, br[0], 1e-4f)
        assertEquals(pad, br[1], 1e-4f)
        // Top left is the mirror: y1 near 1.
        val tl = OverlayPlacement.rect(StampPosition.TOP_LEFT, a)
        assertEquals(pad, tl[0], 1e-4f)
        assertEquals(1f - pad - h, tl[1], 1e-4f)
    }

    @Test
    fun `opposite corners are exact mirrors of each other`() {
        for (aspect in listOf(0.3f, 1f, 4f)) {
            for ((a, b) in listOf(
                StampPosition.TOP_LEFT to StampPosition.BOTTOM_RIGHT,
                StampPosition.TOP_RIGHT to StampPosition.BOTTOM_LEFT,
            )) {
                val ra = OverlayPlacement.rect(a, aspect)
                val rb = OverlayPlacement.rect(b, aspect)
                assertEquals(ra[0], 1f - rb[2], 1e-4f, "$a/$b x")
                assertEquals(ra[3], 1f - rb[1], 1e-4f, "$a/$b y")
            }
        }
    }

    @Test
    fun `centre is centred`() {
        val r = OverlayPlacement.rect(StampPosition.CENTER, 2f)
        val w = r[2] - r[0]
        val h = r[3] - r[1]
        assertEquals((1f - w) / 2f, r[0], 1e-4f)
        assertEquals((1f - h) / 2f, r[1], 1e-4f)
    }

    @Test
    fun `a wide overlay is limited by width, a tall one by height`() {
        // Very wide: the width budget binds, so the height shrinks below the
        // available vertical space.
        val wide = OverlayPlacement.rect(StampPosition.CENTER, 20f)
        assertTrue(wide[2] - wide[0] <= 1f - 2 * OverlayPlacement.STAMP_PAD + 1e-4f)
        // Very tall: the height budget binds.
        val tall = OverlayPlacement.rect(StampPosition.CENTER, 0.02f)
        assertEquals(1f - 2 * OverlayPlacement.STAMP_PAD, tall[3] - tall[1], 1e-4f)
    }

    @Test
    fun `the rect keeps the overlay aspect ratio`() {
        for (aspect in listOf(0.25f, 1f, 2.5f, 6f)) {
            val r = OverlayPlacement.rect(StampPosition.CENTER, aspect)
            val got = (r[2] - r[0]) / (r[3] - r[1])
            assertEquals(aspect, got, 1e-3f, "aspect $aspect came out as $got")
        }
    }

    @Test
    fun `nonsense aspects do not produce a degenerate rect`() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val r = OverlayPlacement.rect(StampPosition.CENTER, bad)
            assertTrue(r[2] > r[0] && r[3] > r[1], "aspect $bad gave $r")
            assertTrue(area(r) > 0f)
        }
    }

    @Test
    fun `watermark padding is tighter than the stamp's, matching upstream`() {
        assertEquals(0.04f, OverlayPlacement.STAMP_PAD)
        assertEquals(0.03f, OverlayPlacement.MARK_PAD)
    }
}

/** The date stamp's own numbers. */
class DateStampTest {

    @Test
    fun `the default colour is upstream's retro LED amber`() {
        assertEquals(0xFFFF8C14.toInt(), DateStamp.DEFAULT_COLOR)
        assertEquals(255, (DateStamp.DEFAULT_COLOR shr 16) and 0xFF)
        assertEquals(140, (DateStamp.DEFAULT_COLOR shr 8) and 0xFF)
        assertEquals(20, DateStamp.DEFAULT_COLOR and 0xFF)
    }

    @Test
    fun `the glow is upstream's half-transparent orange`() {
        assertEquals(180, (DateStamp.GLOW_COLOR ushr 24) and 0xFF)
        assertEquals(255, (DateStamp.GLOW_COLOR shr 16) and 0xFF)
        assertEquals(100, (DateStamp.GLOW_COLOR shr 8) and 0xFF)
    }

    @Test
    fun `aspect grows with the character count`() {
        val short = DateStamp.aspectFor("'98")
        val long = DateStamp.aspectFor("'98 08 13 07")
        assertTrue(long > short, "'98 08 13 07 ($long) should be wider than '98 ($short)")
        // Every character adds the same amount: monospace.
        val one = DateStamp.aspectFor("a")
        val two = DateStamp.aspectFor("aa")
        assertEquals(two - one, DateStamp.aspectFor("aaa") - DateStamp.aspectFor("aa"), 1e-5f)
    }

    @Test
    fun `an empty stamp still has a usable aspect`() {
        assertTrue(DateStamp.aspectFor("") > 0f)
    }

    @Test
    fun `the default text uses the two digit year those cameras showed`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(1998, java.util.Calendar.AUGUST, 13)
        assertEquals("'98 08 13", DateStamp.defaultText(cal))
    }

    @Test
    fun `the stamp key changes with text and with colour`() {
        val a = DateStamp.hashStampText("'98 08 13", 0xFFFF8C14.toInt())
        assertEquals(a, DateStamp.hashStampText("'98 08 13", 0xFFFF8C14.toInt()))
        assertTrue(a != DateStamp.hashStampText("'98 08 14", 0xFFFF8C14.toInt()))
        assertTrue(a != DateStamp.hashStampText("'98 08 13", 0xFFFF3B30.toInt()))
    }

    @Test
    fun `the stamp key survives a comma in the text`() {
        // It ends up in a map key and a uniform lookup, not the wire format, but
        // an unbounded character is still worth pinning.
        assertTrue(DateStamp.hashStampText("a,b,c", 0).isNotEmpty())
    }
}
