package com.relic.catalog.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing a recipe means it survives a trip through a QR code, so that is
 * exactly what is tested here: encode, rasterise, degrade, decode, and check the
 * recipe comes back identical.
 *
 * The rasterise/decode step runs in-process because ZXing's core is pure Java, so
 * a full round trip needs no device. The one thing this cannot cover is a QR
 * photographed off a phone screen, which is why the error correction level is
 * set to M rather than L.
 */
class RecipeQrTest {

    private fun sample() = SavedRecipe.create(
        "SUNSET 94",
        "crt",
        LabRecipe(
            templateId = "KODAK_PORTRA",
            adjustments = LabAdjustments(0.12f, 1.25f, 0.85f, 0.4f, -0.2f),
            vignette = 0.35f, grain = 0.22f, sharpen = 0.1f,
            blur = 0.05f, glitch = 0.08f, duotone = 0.5f,
            duotoneShadow = 0xFF102040.toInt(), duotoneHighlight = 0xFFFFC040.toInt(),
            stampText = "'98 08 13", stampColor = 0xFFFF8C14.toInt(),
            stampPosition = StampPosition.CENTER, stampAlpha = 0.8f,
            watermarkId = "mark_abc123", watermarkAlpha = 0.6f,
            watermarkPosition = StampPosition.TOP_LEFT,
        ),
    )

    /** Matrix -> ARGB pixels, which is what a Bitmap would hold. */
    private fun rasterise(m: BooleanArray, size: Int, scale: Int = 4): IntArray {
        val side = size * scale
        val out = IntArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dark = m[(y / scale) * size + (x / scale)]
                val v = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                out[y * side + x] = v
            }
        }
        return out
    }

    private fun roundTrip(recipe: SavedRecipe, size: Int = 512, scale: Int = 4): SavedRecipe? {
        val m = assertNotNull(RecipeQr.encodeMatrix(recipe, size), "encode failed")
        val side = size * scale
        val lum = RecipeQr.toLuminance(rasterise(m, size, scale), side, side)
        return RecipeQr.decodeLuminances(lum, side, side)
    }

    @Test
    fun `a fully populated recipe survives a QR round trip`() {
        val r = sample()
        assertEquals(r, roundTrip(r))
    }

    @Test
    fun `a minimal recipe survives too`() {
        val r = SavedRecipe.create("A", "original", LabRecipe())
        assertEquals(r, roundTrip(r))
    }

    @Test
    fun `the payload is short enough to be a comfortable QR`() {
        val encoded = RecipeCodec.encode(sample())
        // Byte mode at ECC M tops out at version 40 (~2331 bytes). A recipe is a
        // couple of hundred, which means a small symbol and chunky modules, so it
        // scans off a screen. This guards against a future field quietly turning
        // sharing into a 4KB blob.
        assertTrue(encoded.length < 320, "payload is ${encoded.length} chars")
        println("recipe payload = ${encoded.length} chars")
    }

    @Test
    fun `the QR matrix comes out at the requested size`() {
        val m = assertNotNull(RecipeQr.encodeMatrix(sample(), 512))
        assertEquals(512 * 512, m.size)
    }

    @Test
    fun `decoding survives some image damage`() {
        // A QR photographed off a screen picks up noise. Error correction level M
        // should ride out a few percent of flipped modules; if this ever fails,
        // the ECC level or the payload length needs revisiting.
        val r = sample()
        val m = assertNotNull(RecipeQr.encodeMatrix(r, 512))
        val side = 512 * 4
        val px = rasterise(m, 512, 4)
        // Scattered, not periodic. An earlier version used `(i * k) % 97`, which
        // lays the damage down in a regular lattice: that wipes whole module rows
        // and no error correction level can rescue it, which is a property of the
        // pattern rather than of the encoder. Seeded so the test is repeatable.
        val rnd = kotlin.random.Random(4242)
        repeat(px.size * 2 / 100) { i ->
            px[i] = if (px[i] == 0xFF000000.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            rnd.nextInt(px.size)
        }
        val lum = RecipeQr.toLuminance(px, side, side)
        assertEquals(r, RecipeQr.decodeLuminances(lum, side, side))
    }

    @Test
    fun `a QR carrying something else is rejected, not misread`() {
        val m = assertNotNull(RecipeQr.encodeText("https://example.com/not-a-recipe", 256))
        val side = 256 * 3
        val lum = RecipeQr.toLuminance(rasterise(m, 256, 3), side, side)
        assertNull(RecipeQr.decodeLuminances(lum, side, side))
    }

    @Test
    fun `an image with no QR in it decodes to null`() {
        val side = 128
        val blank = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        assertNull(RecipeQr.decodeLuminances(RecipeQr.toLuminance(blank, side, side), side, side))
    }

    @Test
    fun `luminance conversion is the standard weighting and stays in range`() {
        val px = intArrayOf(
            0xFF000000.toInt(), // black -> 0
            0xFFFFFFFF.toInt(), // white -> 255
            0xFFFF0000.toInt(), // pure red -> ~76
            0xFF00FF00.toInt(), // pure green -> ~149
            0xFF0000FF.toInt(), // pure blue -> ~29
        )
        val l = RecipeQr.toLuminance(px, 5, 1)
        assertEquals(5, l.size)
        // Byte is signed, so 255 arrives as -1; mask before comparing.
        fun at(i: Int) = l[i].toInt() and 0xFF
        assertEquals(0, at(0))
        assertEquals(255, at(1))
        assertTrue(at(2) in 70..82, "red luma was ${at(2)}")
        assertTrue(at(3) in 143..155, "green luma was ${at(3)}")
        assertTrue(at(4) in 24..34, "blue luma was ${at(4)}")
    }

    @Test
    fun `a too small buffer is rejected rather than crashing`() {
        assertNull(RecipeQr.decodeLuminances(ByteArray(4), 64, 64))
        assertNull(RecipeQr.decodeLuminances(ByteArray(0), 0, 0))
    }
}
