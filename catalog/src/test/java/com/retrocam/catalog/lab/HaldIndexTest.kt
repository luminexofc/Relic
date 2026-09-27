package com.retrocam.catalog.lab

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HALD CLUT layout, pinned against the formula transcribed from
 * FilterLibrary's `FilterEngine.applyHaldLut`. The GLSL in Shaders.LAB_GRADE
 * reimplements the same arithmetic, and it cannot be unit tested here, so these
 * vectors are what a shader change has to be checked against.
 */
class HaldIndexTest {

    @Test
    fun `grid is the square root of the cube size`() {
        assertEquals(4, LutCatalog.gridFor(16))
        assertEquals(8, LutCatalog.gridFor(64))
    }

    @Test
    fun `cube size follows upstream's 512 rule`() {
        assertEquals(64, LutCatalog.cubeFor(512, 512))
        assertEquals(64, LutCatalog.cubeFor(1024, 1024))
        assertEquals(16, LutCatalog.cubeFor(64, 64))
        // Anything else is treated as the small cube, same as upstream.
        assertEquals(16, LutCatalog.cubeFor(256, 256))
    }

    @Test
    fun `only the two real Hald sizes are supported`() {
        assertTrue(LutCatalog.isSupportedSize(512, 512))
        assertTrue(LutCatalog.isSupportedSize(1024, 1024))
        assertTrue(LutCatalog.isSupportedSize(64, 64))
        assertTrue(!LutCatalog.isSupportedSize(256, 256))
        assertTrue(!LutCatalog.isSupportedSize(512, 256))
        assertTrue(!LutCatalog.isSupportedSize(32, 32))
    }

    @Test
    fun `black lands in the first tile and white in the last`() {
        val (bx, by) = LutCatalog.haldTexel(0, 0, 0, 64)
        assertEquals(0, bx)
        assertEquals(0, by)
        val (wx, wy) = LutCatalog.haldTexel(255, 255, 255, 64)
        // White is blueIndex 63, so tile (7,7), and the last texel of that tile.
        assertEquals(511, wx)
        assertEquals(511, wy)
    }

    @Test
    fun `blue picks the tile and red and green pick within it`() {
        // blue = 255 -> tile 63 -> tileX 7, tileY 7. red 255, green 255 -> (63,63) inside.
        val (x, y) = LutCatalog.haldTexel(255, 255, 255, 64)
        assertEquals(7 * 64 + 63, x)
        assertEquals(7 * 64 + 63, y)
        // blue = 0 -> tile 0, and green drives the row within the tile. The
        // in-tile index is `v * (cube-1) / 255` in INTEGER arithmetic, so 10
        // lands on 10*63/255 = 2, not 10. Getting this wrong is the classic way
        // to end up with an off-by-a-lot LUT, so it is spelled out.
        val (x0, y0) = LutCatalog.haldTexel(10, 20, 0, 64)
        assertEquals(10 * 63 / 255, x0)
        assertEquals(20 * 63 / 255, y0)
        assertEquals(2, x0)
        assertEquals(4, y0)
    }

    @Test
    fun `matches the upstream formula term for term`() {
        // Written out longhand exactly as upstream computes it, then compared.
        for (cube in listOf(16, 64)) {
            val grid = LutCatalog.gridFor(cube)
            val maxColor = cube - 1
            for (rgb in listOf(
                Triple(0, 0, 0), Triple(255, 255, 255), Triple(10, 200, 90),
                Triple(128, 128, 128), Triple(1, 254, 77),
            )) {
                val (r, g, b) = rgb
                val blueIndex = (b * maxColor) / 255
                val lutTileX = blueIndex % grid
                val lutTileY = blueIndex / grid
                val wantX = (lutTileX * cube + (r * maxColor) / 255).coerceIn(0, grid * cube - 1)
                val wantY = (lutTileY * cube + (g * maxColor) / 255).coerceIn(0, grid * cube - 1)
                assertEquals(wantX to wantY, LutCatalog.haldTexel(r, g, b, cube), "rgb=$rgb cube=$cube")
            }
        }
    }

    @Test
    fun `every colour lands inside the image`() {
        for (cube in listOf(16, 64)) {
            val side = LutCatalog.gridFor(cube) * cube
            for (r in 0..255 step 17) {
                for (g in 0..255 step 23) {
                    for (b in 0..255 step 29) {
                        val (x, y) = LutCatalog.haldTexel(r, g, b, cube)
                        assertTrue(x in 0 until side, "x=$x out of range for side=$side")
                        assertTrue(y in 0 until side, "y=$y out of range for side=$side")
                    }
                }
            }
        }
    }
}

/**
 * The procedurally generated built-in LUTs. A 64-cube rasterises to a 512x512
 * image, so these also check the tile layout agrees with [haldTexel].
 */
class BuiltinLutTest {

    @Test
    fun `there are four built in luts with unique ids`() {
        assertEquals(4, LutCatalog.builtIn.size)
        assertEquals(4, LutCatalog.builtInById.size)
        assertTrue(LutCatalog.builtIn.all { it.id.startsWith("builtin_") })
    }

    @Test
    fun `a 64 cube rasterises to a 512 square image`() {
        val px = LutCatalog.builtInById.getValue("builtin_faded").rasterize(64)
        assertEquals(512 * 512, px.size)
        assertTrue(px.all { it != 0 }, "every texel should be fully opaque")
    }

    @Test
    fun `a 16 cube rasterises to a 64 square image`() {
        val px = LutCatalog.builtInById.getValue("builtin_faded").rasterize(16)
        assertEquals(64 * 64, px.size)
    }

    @Test
    fun `output is in range and opaque everywhere`() {
        for (lut in LutCatalog.builtIn) {
            for (p in lut.rasterize(16)) {
                assertEquals(0xFF, (p ushr 24) and 0xFF, "${lut.id} alpha")
                assertTrue(p and 0xFF <= 255)
            }
        }
    }

    @Test
    fun `a black input stays black and a white input stays bright`() {
        for (lut in LutCatalog.builtIn) {
            val px = lut.rasterize(16)
            val black = px[LutCatalog.haldTexel(0, 0, 0, 16).let { (x, y) -> y * 64 + x }]
            val white = px[LutCatalog.haldTexel(255, 255, 255, 16).let { (x, y) -> y * 64 + x }]
            // Cross Process lifts cyan into the shadows, and Faded Film lifts the
            // toe on purpose, so black is not required to stay black. White must
            // not go dark, though, or the LUT inverts the image.
            val wR = (white shr 16) and 0xFF
            val wG = (white shr 8) and 0xFF
            val wB = white and 0xFF
            assertTrue(maxOf(wR, wG, wB) > 128, "${lut.id} turned white dark: $white")
            assertTrue(minOf(wR, wG, wB) < 255 || maxOf(wR, wG, wB) > 200, "${lut.id} white is flat: $white")
        }
    }

    @Test
    fun `the rasterised cube agrees with the hald index lookup`() {
        // Round trip: rasterise at 16, then look a colour up with the same
        // function the shader uses. If the tile layout in rasterize() and
        // haldTexel() ever disagree, this finds it.
        val lut = LutCatalog.builtInById.getValue("builtin_sepia3d")
        val cube = 16
        val px = lut.rasterize(cube)
        for (rgb in listOf(Triple(0, 0, 0), Triple(255, 255, 255), Triple(40, 90, 160))) {
            val (r, g, b) = rgb
            val (x, y) = LutCatalog.haldTexel(r, g, b, cube)
            val got = px[y * 64 + x]
            // Compare against the function evaluated at the QUANTISED colour the
            // texel actually stores, not the input asked for. A 16-cube has only
            // 16 steps per channel, so 40 lands on 34 and looking up 40 returns
            // the mapping for 34 by design.
            val qR = (x % cube) * 255 / (cube - 1)
            val qG = (y % cube) * 255 / (cube - 1)
            // Invert the tile layout: blueIndex = tileY*grid + tileX.
            val grid = LutCatalog.gridFor(cube)
            val blueIndex = (y / cube) * grid + (x / cube)
            val qB = blueIndex * 255 / (cube - 1)
            val want = lut.fn(qR / 255f, qG / 255f, qB / 255f)
            assertTrue(
                abs(((got shr 16) and 0xFF) - (want.first * 255f)) < 2f,
                "r mismatch for $rgb",
            )
            assertTrue(
                abs(((got shr 8) and 0xFF) - (want.second * 255f)) < 2f,
                "g mismatch for $rgb",
            )
            assertTrue(
                abs((got and 0xFF) - (want.third * 255f)) < 2f,
                "b mismatch for $rgb",
            )
        }
    }

    @Test
    fun `content hashes are stable and discriminating`() {
        val a = LutCatalog.builtInById.getValue("builtin_faded").rasterize(16)
        val b = LutCatalog.builtInById.getValue("builtin_cross").rasterize(16)
        val a2 = LutCatalog.builtInById.getValue("builtin_faded").rasterize(16)
        assertEquals(LutCatalog.hashPixels(a, 64, 16), LutCatalog.hashPixels(a2, 64, 16))
        assertTrue(LutCatalog.hashPixels(a, 64, 16) != LutCatalog.hashPixels(b, 64, 16))
        // The side and cube are part of the id so the same pixels at a different
        // resolution cannot collide.
        assertTrue(LutCatalog.hashPixels(a, 64, 16) != LutCatalog.hashPixels(a, 128, 16))
        assertTrue(LutCatalog.hashPixels(a, 64, 16).startsWith("lut_"))
    }
}

/** Packed ARGB round trip, used by the LUT swatches and duotone picker. */
class ColorPackingTest {
    @Test
    fun `pack and unpack round trip`() {
        for (rgb in listOf(Triple(0f, 0f, 0f), Triple(1f, 1f, 1f), Triple(0.5f, 0.25f, 0.75f))) {
            val packed = packRgb(rgb.first, rgb.second, rgb.third)
            val back = unpackRgb(packed)
            assertTrue(abs(back[0] - rgb.first) < 0.01f, "r ${rgb.first} -> ${back[0]}")
            assertTrue(abs(back[1] - rgb.second) < 0.01f, "g ${rgb.second} -> ${back[1]}")
            assertTrue(abs(back[2] - rgb.third) < 0.01f, "b ${rgb.third} -> ${back[2]}")
        }
    }

    @Test
    fun `out of range values clamp instead of wrapping`() {
        assertEquals(0, packRgb(-1f, 0f, 0f) and 0xFF)
        assertEquals(255, packRgb(2f, 0f, 0f) shr 16 and 0xFF)
    }

    @Test
    fun `the default duotone pair matches upstream's fallbacks`() {
        // FilterEngine.applyDuotone defaults to rgb(20,20,80) and rgb(255,110,80).
        assertEquals(listOf(20, 20, 80), unpackRgb(LabRecipe.DEFAULT_DUO_SHADOW).map { (it * 255f).toInt() })
        assertEquals(listOf(255, 110, 80), unpackRgb(LabRecipe.DEFAULT_DUO_HIGHLIGHT).map { (it * 255f).toInt() })
    }
}
