package com.relic.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {

    /** JUnit's assertNotNull returns Unit, so it cannot hand the value back. */
    private fun <T : Any> nn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    private fun at(c: FloatArray?, x255: Int) = c!![x255.coerceIn(0, 255)]

    @Test
    fun `an identity curve maps every value to itself`() {
        val c = nn(ToneCurve.parse("0, 0, 255, 255"))
        for (i in 0 until 256) {
            assertEquals("at $i", i / 255f, at(c, i), 1e-3f)
        }
    }

    @Test
    fun `control points are hit exactly`() {
        val c = nn(ToneCurve.parse("0, 0, 255, 255, 128, 132, 64, 96, 192, 208"))
        assertEquals(132 / 255f, at(c, 128), 1e-4f)
        assertEquals(96 / 255f, at(c, 64), 1e-4f)
        assertEquals(208 / 255f, at(c, 192), 1e-4f)
    }

    @Test
    fun `spaces work as well as commas`() {
        val a = ToneCurve.parse("0, 0, 255, 255, 128, 132")
        val b = ToneCurve.parse("0 0 255 255 128 132")
        assertNotNull(b)
        for (i in 0 until 256) assertEquals("at $i", at(a, i), at(b, i), 1e-6f)
    }

    @Test
    fun `values outside the point range are clamped to the end points`() {
        // A curve that only spans 0..128 must hold its last value above 128
        // rather than reading off the end of the point list.
        val c = nn(ToneCurve.parse("0, 0, 128, 64"))
        assertEquals(64 / 255f, at(c, 200), 1e-4f)
        assertEquals(0f, at(c, 0), 1e-4f)
    }

    @Test
    fun `output is clamped to the displayable range`() {
        val c = nn(ToneCurve.parse("0, -40, 128, 90, 255, 300"))
        for (v in c) {
            assertTrue("value $v out of range", v in 0f..1f)
        }
    }

    /**
     * Unsorted points would make the segment search walk backwards and produce
     * a curve that doubles back on itself. A hand-edited file is the only way
     * to get one, which is exactly why it needs handling.
     */
    @Test
    fun `unsorted control points are sorted rather than producing a broken curve`() {
        val sorted = nn(ToneCurve.parse("0, 0, 128, 200, 255, 255"))
        val shuffled = nn(ToneCurve.parse("128, 200, 255, 255, 0, 0"))
        for (i in 0 until 256) assertEquals("at $i", at(sorted, i), at(shuffled, i), 1e-6f)
    }

    @Test
    fun `a degenerate curve with duplicate x does not divide by zero`() {
        val c = nn(ToneCurve.parse("10, 5, 10, 200, 255, 255"))
        for (v in c) assertTrue("value $v", v in 0f..1f)
    }

    @Test
    fun `malformed curves are rejected rather than silently becoming identity`() {
        // Null, not identity: the import report has to be able to say a curve
        // was unreadable, which it cannot if the failure is a silent identity.
        assertNull(ToneCurve.parse(null))
        assertNull(ToneCurve.parse(""))
        assertNull(ToneCurve.parse("   "))
        assertNull(ToneCurve.parse("0, 0, 255"))          // odd count
        assertNull(ToneCurve.parse("0, 0"))              // too few points
        assertNull(ToneCurve.parse("a, b, c, d"))         // not numbers
    }

    @Test
    fun `a group of four reads back in composite, red, green, blue order`() {
        val ramp = List(ToneCurve.SIZE) { it.toString() }
        val g = ToneCurve.parseGroup(
            ramp.joinToString(" ") + ";" + "" + ";" +
                ramp.joinToString(" ") + ";" + "",
        )
        assertEquals(4, g.size)
        assertNotNull(g[0]); assertNull(g[1]); assertNotNull(g[2]); assertNull(g[3])
        assertEquals(200 / 255f, at(g[0], 200), 1e-3f)
        assertEquals(100 / 255f, at(g[2], 100), 1e-3f)
    }

    /** A part that is not a full sample run is dropped, not half-read. */
    @Test
    fun `a short part is dropped rather than half-read`() {
        val g = ToneCurve.parseGroup("0 1 2 3")
        assertEquals(listOf(null, null, null, null), g)
    }

    @Test
    fun `an absent group is all nulls`() {
        assertEquals(4, ToneCurve.parseGroup(null).size)
        assertTrue(ToneCurve.parseGroup(ToneCurve.NONE).all { it == null })
        assertTrue(ToneCurve.parseGroup("").all { it == null })
    }

    @Test
    fun `encoding and decoding a group round trips`() {
        val src = listOf(
            ToneCurve.parse("0, 0, 255, 255, 128, 132"),
            null,
            ToneCurve.parse("0, 0, 255, 255, 64, 96"),
            ToneCurve.parse("0, 0, 255, 255, 192, 208"),
        )
        val enc = ToneCurve.encodeGroup(src)
        assertTrue("group separator must survive", enc.contains(';'))
        assertTrue("no commas: they are the codec field separator", !enc.contains(','))
        assertEquals(3, enc.count { it == ToneCurve.GROUP_SEP })
        val back = ToneCurve.parseGroup(enc)
        for (i in 0 until 4) {
            val a = src[i]
            val b = back[i]
            if (a == null) {
                assertNull("channel $i should stay absent", b)
            } else {
                assertNotNull("channel $i", b)
                // 8-bit storage: round-to-nearest can move a sample by up to half a level,
                // and that is the whole of the loss. A larger bound would hide
                // a real bug behind a loose tolerance.
                for (k in 0 until 256) {
                    assertEquals("channel $i at $k", a[k], b!![k], 0.5f / 255f + 1e-6f)
                }
            }
        }
    }

    @Test
    fun `encoding an empty group is the placeholder`() {
        assertEquals(ToneCurve.NONE, ToneCurve.encodeGroup(List(4) { null }))
    }

    @Test
    fun `re-encoding is stable`() {
        val g = ToneCurve.parseGroup("0 0 255 255 128 132;;0 0 255 255 64 96;")
        assertEquals(ToneCurve.encodeGroup(g), ToneCurve.encodeGroup(ToneCurve.parseGroup(ToneCurve.encodeGroup(g))))
    }

    /**
     * An absent curve must pack as the identity ramp, because the shader
     * applies all four channels unconditionally. If an absent channel packed as
     * black, every recipe with no tone curve would go black.
     */
    @Test
    fun `an absent curve packs as identity, not black`() {
        val rgba = ToneCurve.toRgba(listOf(ToneCurve.parse("0, 0, 255, 255"), null, null, null))
        assertEquals(ToneCurve.SIZE * 4, rgba.size)
        for (i in 0 until ToneCurve.SIZE) {
            val expected = i.toByte()
            assertEquals("composite at $i", expected, rgba[i * 4])
            assertEquals("red at $i", expected, rgba[i * 4 + 1])
            assertEquals("green at $i", expected, rgba[i * 4 + 2])
            assertEquals("blue at $i", expected, rgba[i * 4 + 3])
        }
    }
}
