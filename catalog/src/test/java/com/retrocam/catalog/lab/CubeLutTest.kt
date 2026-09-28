package com.retrocam.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {

    private fun ok(r: CubeLut.Result): CubeLut.Cube {
        assertTrue("expected a cube, got $r", r is CubeLut.Result.Ok)
        return (r as CubeLut.Result.Ok).cube
    }

    private fun bad(r: CubeLut.Result): String {
        assertTrue("expected a rejection, got $r", r is CubeLut.Result.Bad)
        return (r as CubeLut.Result.Bad).why
    }

    /** An identity cube: entry [i] has r, g, b from the red-fastest index. */
    private fun identity(size: Int) = "LUT_3D_SIZE $size\n" + (0 until size * size * size)
        .joinToString("\n") { i ->
            val r = i % size
            val g = (i / size) % size
            val b = i / (size * size)
            val d = (size - 1).toFloat()
            "${r / d} ${g / d} ${b / d}"
        }

    /** The colour the shader would read for an input of r, g, b in 0..255. */
    private fun channel(px: IntArray, side: Int, r: Int, g: Int, b: Int, cube: Int): Int {
        val maxC = cube - 1
        val ri = ((r * maxC + 127) / 255).coerceIn(0, maxC)
        val gi = ((g * maxC + 127) / 255).coerceIn(0, maxC)
        val bi = ((b * maxC + 127) / 255).coerceIn(0, maxC)
        val (x, y) = LutCatalog.indexTexel(ri, gi, bi, cube)
        return px[y * side + x]
    }

    // ---- parsing ----

    @Test
    fun `a minimal cube parses`() {
        val c = ok(CubeLut.parse(identity(2)))
        assertEquals(2, c.size)
        assertEquals(8, c.entryCount)
    }

    /**
     * Red varies fastest. If that order is wrong every colour is wrong and it
     * looks like a plausible LUT rather than a broken one, so it is pinned by
     * reading a known entry rather than by round-tripping.
     */
    @Test
    fun `entries are stored with red varying fastest`() {
        val c = ok(CubeLut.parse(identity(2)))
        // Entry 1 of a 2-cube is r=1, g=0, b=0.
        assertEquals(1f, c.data[3], 1e-4f)
        assertEquals(0f, c.data[4], 1e-4f)
        assertEquals(0f, c.data[5], 1e-4f)
        // Entry 2 is r=0, g=1, b=0.
        assertEquals(0f, c.data[6], 1e-4f)
        assertEquals(1f, c.data[7], 1e-4f)
    }

    @Test
    fun `comments, blanks, a title and domain lines are skipped`() {
        val text = "# a comment\nTITLE \"My LUT\"\n\n" +
            "DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n" + identity(2)
        assertEquals("My LUT", ok(CubeLut.parse(text)).title)
    }

    @Test
    fun `a 1D cube is rejected rather than half read`() {
        val why = bad(CubeLut.parse("LUT_1D_SIZE 4\n0 0 0\n0.3 0.3 0.3\n0.6 0.6 0.6\n1 1 1"))
        assertTrue(why.contains("1D"))
    }

    @Test
    fun `a truncated cube says so with both counts`() {
        assertTrue(bad(CubeLut.parse("LUT_3D_SIZE 4\n0 0 0\n1 1 1")).contains("expected 64"))
    }

    @Test
    fun `rubbish and empty files are rejected with a reason`() {
        for (t in listOf("", "hello", "TITLE \"x\"", "# only a comment")) {
            assertTrue("accepted '$t'", bad(CubeLut.parse(t)).isNotBlank())
        }
    }

    /** Refused on the header alone, so a bad size never allocates. */
    @Test
    fun `an absurd cube size is refused before allocating for it`() {
        assertTrue(bad(CubeLut.parse("LUT_3D_SIZE 4096")).contains("too large"))
    }

    // ---- conversion to the Hald layout the shader indexes ----

    /**
     * The whole reason no shader change was needed: `haldTexel` was already
     * written in terms of `gridFor(cube)`, and the cube is recoverable from the
     * side length, so imported LUTs stay plain images with no sidecar.
     */
    @Test
    fun `the cube is recoverable from the side length for every real size`() {
        for (size in listOf(2, 16, 25, 33, 64)) {
            val side = LutCatalog.gridFor(size) * size
            assertEquals("cube for side $side", size, CubeLut.cubeFromSide(side))
        }
    }

    @Test
    fun `an unrecognised side is not guessed at`() {
        assertNull(CubeLut.cubeFromSide(500))
        assertNull(CubeLut.cubeFromSide(3))
    }

    /**
     * An identity cube must survive the round trip unchanged. If the red-fastest
     * order or the tile layout were wrong, this is where it shows.
     */
    @Test
    fun `an identity cube round trips through the hald layout`() {
        for (size in listOf(2, 16, 33, 64)) {
            val side = LutCatalog.gridFor(size) * size
            val px = CubeLut.toHaldPixels(ok(CubeLut.parse(identity(size))))
            assertEquals(side * side, px.size)
            // A 2-cube has two levels, so only the eight corners are
            // representable; asking it for mid grey would be asking for something
            // the format cannot express.
            val probes = if (size == 2) {
                listOf(Triple(0, 0, 0), Triple(255, 0, 0), Triple(0, 255, 0),
                    Triple(255, 255, 0), Triple(0, 0, 255), Triple(255, 0, 255),
                    Triple(0, 255, 255), Triple(255, 255, 255))
            } else {
                listOf(Triple(0, 0, 0), Triple(255, 255, 255), Triple(255, 0, 0),
                    Triple(0, 255, 0), Triple(0, 0, 255), Triple(128, 128, 128),
                    Triple(255, 255, 0), Triple(12, 200, 77))
            }
            // Tolerance is the format's own resolution, 255/(size-1) 8-bit
            // levels. An arbitrary 8-bit colour is NOT exactly representable in
            // a size-n cube - 128 is not one of the 16 levels - so claiming
            // exactness here would be claiming something the format cannot do.
            val step = 255f / (size - 1)
            for (triple in probes) {
                val p = channel(px, side, triple.first, triple.second, triple.third, size)
                for ((want, got, ch) in listOf(
                    Triple(triple.first, (p shr 16) and 0xFF, "r"),
                    Triple(triple.second, (p shr 8) and 0xFF, "g"),
                    Triple(triple.third, p and 0xFF, "b"),
                )) {
                    assertEquals(
                        "$ch at $triple size $size", want.toDouble(), got.toDouble(), step.toDouble(),
                    )
                }
            }
        }
    }

    /** The two endpoints are exactly representable at every size, so pin them. */
    @Test
    fun `black and white are exact at every cube size`() {
        for (size in listOf(2, 16, 33, 64)) {
            val side = LutCatalog.gridFor(size) * size
            val px = CubeLut.toHaldPixels(ok(CubeLut.parse(identity(size))))
            var black = channel(px, side, 0, 0, 0, size)
            assertEquals("black r at $size", 0, black shr 16 and 0xFF)
            assertEquals("black g at $size", 0, black shr 8 and 0xFF)
            assertEquals("black b at $size", 0, black and 0xFF)
            val white = channel(px, side, 255, 255, 255, size)
            assertEquals("white r at $size", 255, white shr 16 and 0xFF)
            assertEquals("white g at $size", 255, white shr 8 and 0xFF)
            assertEquals("white b at $size", 255, white and 0xFF)
        }
    }

    /** 33 is the size real LUTs ship in, and it is not a power of two. */
    @Test
    fun `a 33 cube converts at the expected side`() {
        val side = LutCatalog.gridFor(33) * 33
        assertEquals(198, side)
        val px = CubeLut.toHaldPixels(ok(CubeLut.parse(identity(33))))
        assertEquals(198 * 198, px.size)
        val p = channel(px, 198, 255, 255, 255, 33)
        assertEquals(255, (p shr 16) and 0xFF)
        assertEquals(255, (p shr 8) and 0xFF)
        assertEquals(255, p and 0xFF)
    }

    @Test
    fun `values outside 0 to 1 are clamped rather than wrapping`() {
        // Index 0 is (0,0,0) and index 7 is (1,1,1) in the red-fastest order,
        // so the two extremes of the range are the ones to put them on.
        val text = "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { i ->
            when (i) {
                0 -> "-1 -1 -1"
                7 -> "5 5 5"
                else -> "0.5 0.5 0.5"
            }
        }
        val side = LutCatalog.gridFor(2) * 2
        val px = CubeLut.toHaldPixels(ok(CubeLut.parse(text)))
        assertEquals(0, px[0] shr 16 and 0xFF)
        val p = channel(px, side, 255, 255, 255, 2)
        assertEquals(255, p shr 16 and 0xFF)
    }

    @Test
    fun `every cube size a real LUT ships in parses`() {
        for (size in listOf(2, 4, 8, 16, 17, 25, 32, 33, 64, 65, 128, 129)) {
            assertNotNull("size $size", ok(CubeLut.parse(identity(size))).data.first())
        }
    }
}
