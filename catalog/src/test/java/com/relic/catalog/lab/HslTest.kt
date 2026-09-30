package com.relic.catalog.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Adobe's Color Mixer, and the Kotlin/GLSL pair behind it.
 *
 * The band weights and the HSL round trip exist twice - once here and once in
 * `Shaders.HEADER` - because the shader is what runs and this is what is
 * testable. The last test reads the expressions back out of the shader so the
 * two cannot drift, which is the same trick `RangeToneTest` uses for the range
 * bands and the reason §4.6 was found at all.
 */
class HslTest {

    private fun values(vararg pairs: Pair<Int, Float>): FloatArray {
        val v = FloatArray(Hsl.VALUES)
        for ((slot, value) in pairs) v[slot] = value
        return v
    }

    private fun close(a: Float, b: Float, tol: Float = 1e-3f) =
        assertTrue(
            kotlin.math.abs(a - b) <= tol,
            "expected $b, got $a (tolerance $tol)",
        )

    // ---- band weights ----

    /**
     * The whole design rests on this: at every hue the eight weights sum to 1.
     * If they do not, a pixel's colour gains or loses adjustment depending on
     * where on the wheel it sits, which reads as a colour cast that moves.
     */
    @Test
    fun `the eight band weights partition unity at every hue`() {
        for (deg in 0 until 360) {
            val w = Hsl.weights(deg.toFloat())
            close(w.sum(), 1f)
        }
    }

    /**
     * A pixel at a band centre belongs mostly to that band, and never entirely
     * unless its neighbours are all further away than the reach.
     *
     * Green is the only band where that holds, because its neighbours are 60
     * degrees away. Every other band bleeds slightly, which the weights
     * normalise; see the bleed test for the exact amount.
     */
    @Test
    fun `a hue at a band centre belongs mostly to that band`() {
        for (band in 0 until Hsl.BANDS) {
            val w = Hsl.weights(Hsl.CENTRES[band])
            assertTrue(
                w[band] > 0.9f,
                "band $band at its own centre kept only ${w[band]}",
            )
            close(w.sum(), 1f, 1e-4f)
        }
    }

    /** The one band with no neighbour in reach gets the whole pixel. */
    @Test
    fun `green is isolated so it is never diluted`() {
        val g = Hsl.weights(Hsl.CENTRES[3])
        close(g[3], 1f, 1e-4f)
        for (other in 0 until Hsl.BANDS) if (other != 3) close(g[other], 0f, 1e-4f)
    }

    /**
     * The centres must be Adobe's, not an even spread. Green is at 120 degrees
     * because that is where green is; an even 45-degree spacing would put it at
     * 135 and the mixer would miss the colour it is named for.
     */
    @Test
    fun `the band centres are Adobe's and not evenly spaced`() {
        assertEquals(0f, Hsl.CENTRES[0], 0f)     // red
        assertEquals(120f, Hsl.CENTRES[3], 0f)   // green, not 135
        assertEquals(180f, Hsl.CENTRES[4], 0f)   // aqua, not 180 by accident
        assertEquals(315f, Hsl.CENTRES[7], 0f)   // magenta
        // The gaps are deliberately uneven, which is what the normalisation in
        // weights() exists to absorb.
        val gaps = Hsl.CENTRES.indices.map {
            val a = Hsl.CENTRES[it]
            val b = Hsl.CENTRES[(it + 1) % Hsl.BANDS]
            var d = b - a
            if (d <= 0f) d += 360f
            d
        }
        assertTrue(gaps.containsAll(listOf(30f, 60f, 45f)), "gaps were $gaps")
    }

    /**
     * The reach has to strictly exceed half the largest gap, or a hue that lands
     * exactly between two centres gets no adjustment at all.
     *
     * At reach exactly 30, hue 90 is the failure: yellow's window ends and
     * green's begins, both at weight zero, so a pure green-yellow pixel was
     * passed through untouched while every other hue on the wheel was graded.
     * Silent, and only at one hue, which is the worst shape a bug can have.
     */
    @Test
    fun `the reach strictly exceeds half the largest gap between centres`() {
        assertTrue(
            Hsl.REACH > Hsl.requiredReach(),
            "reach ${Hsl.REACH} is not greater than half-gap ${Hsl.requiredReach()}, " +
                "so a hue between two centres gets no adjustment",
        )
        // The half-gap here is 30, and the reach has to clear it with room.
        close(Hsl.requiredReach(), 30f, 1e-4f)
    }

    @Test
    fun `no hue falls in a gap between bands`() {
        for (tenths in 0 until 3600) {
            val w = Hsl.weights(tenths / 10f)
            close(w.sum(), 1f, 1e-3f)
        }
        // And specifically the hue that broke it.
        close(Hsl.weights(90f).sum(), 1f, 1e-3f)
        close(Hsl.weights(150f).sum(), 1f, 1e-3f)
    }

    @Test
    fun `a hue between two neighbouring centres is shared between them`() {
        // 15 degrees is exactly between red (0) and orange (30).
        val w = Hsl.weights(15f)
        close(w[0], 0.5f, 1e-3f)
        close(w[1], 0.5f, 1e-3f)
    }

    /** Hue 0 and hue 360 are the same colour, so they must weight the same. */
    @Test
    fun `the wheel wraps`() {
        close(Hsl.weight(350f, 7), Hsl.weight(10f, 7), 1e-4f)
        // And a hue just below red is still mostly red.
        assertTrue(Hsl.weights(350f)[0] > 0.9f)
    }

    @Test
    fun `a band has no reach past its 35 degrees`() {
        // Red's centre is 0, so 40 is outside.
        assertEquals(0f, Hsl.weight(40f, 0), 0f)
        // Green's centre is 120, so 80 is 40 away: outside.
        assertEquals(0f, Hsl.weight(80f, 3), 0f)
        // Just inside, it is nonzero.
        assertTrue(Hsl.weight(90f, 3) > 0f)
    }

    // ---- the operator ----

    @Test
    fun `all neutral is a no-op`() {
        val v = FloatArray(Hsl.VALUES)
        for (colour in listOf(
            floatArrayOf(0.8f, 0.2f, 0.1f),
            floatArrayOf(0.1f, 0.7f, 0.3f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
        )) {
            val out = Hsl.apply(colour[0], colour[1], colour[2], v)
            close(out[0], colour[0]); close(out[1], colour[1]); close(out[2], colour[2])
        }
    }

    /**
     * The reason the band exists. Turning the green band's saturation down must
     * not touch a red pixel, which is the whole point of a colour mixer and the
     * thing a global Saturation slider cannot do.
     */
    @Test
    fun `one band does not disturb another band`() {
        val v = values(Hsl.satAt(3) to -1f) // green, desaturated
        // A pure red pixel is entirely in band 0 and must be untouched.
        val red = Hsl.apply(0.9f, 0.1f, 0.1f, v)
        close(red[0], 0.9f, 1e-3f)
        close(red[1], 0.1f, 1e-3f)
        // A pure green pixel is entirely in band 3 and must lose its colour.
        val green = Hsl.apply(0.1f, 0.8f, 0.2f, v)
        close(green[0], green[1], 1e-3f)
        close(green[1], green[2], 1e-3f)
    }

    /**
     * Green is used for the exact rotation tests because it is the only band
     * with no neighbour inside reach: its neighbours sit 60 degrees away, and
     * the reach is 35. Every other band bleeds a little into the next, which is
     * deliberate and covered by the bleed test below.
     */
    @Test
    fun `a hue rotation moves a pixel around the wheel`() {
        val v = values(Hsl.hueAt(3) to 0.5f) // green, +50 degrees
        val out = Hsl.apply(0f, 1f, 0f, v)
        close(Hsl.rgbToHsl(out[0], out[1], out[2])[0], 120f + 50f, 1f)
    }

    /** A full-strength rotation has to move the hue by the stated amount. */
    @Test
    fun `full scale is a hundred degrees of hue`() {
        val v = values(Hsl.hueAt(3) to 1f)
        val out = Hsl.apply(0f, 1f, 0f, v)
        close(Hsl.rgbToHsl(out[0], out[1], out[2])[0], 120f + Hsl.HUE_DEGREES, 1f)
    }

    /**
     * Bands overlap, and a band-centre pixel keeps most but not all of its own
     * setting. This pins the bleed so a future reach change is a visible
     * decision rather than a silent one.
     */
    @Test
    fun `a band centre keeps most of its own setting`() {
        // Red at 0 degrees, orange's centre is 30 away and the reach is 35, so
        // orange contributes a little weight and normalisation dilutes red.
        val w = Hsl.weights(0f)
        assertTrue(w[0] > 0.9f, "red kept only ${w[0]} of its own band")
        assertTrue(w[1] > 0f, "orange should still contribute at red")
        // Green, 60 from both neighbours, is untouched by either.
        val g = Hsl.weights(120f)
        close(g[3], 1f, 1e-4f)
    }

    /**
     * The band comes from the ORIGINAL hue. If it came from the rotated one, a
     * large rotation would walk a pixel into the next band's settings and a
     * preset would drift as it got stronger.
     */
    @Test
    fun `the band is chosen before the rotation`() {
        // Green rotated 100 degrees lands at hue 220, which is in the blue band.
        // If the band were picked from the ROTATED hue, blue's own settings
        // would then be applied on top and a preset would drift further the
        // stronger it was set, which is a bug that grows with the slider.
        val v = values(Hsl.hueAt(3) to 1f, Hsl.lumAt(5) to -1f)
        val out = Hsl.apply(0f, 1f, 0f, v)
        // Pure green has lightness 0.5, and blue's -1 luminance must not reach it.
        close(Hsl.rgbToHsl(out[0], out[1], out[2])[2], 0.5f, 1e-2f)
    }

    @Test
    fun `output stays inside the unit cube`() {
        val v = values(
            Hsl.hueAt(0) to 1f, Hsl.satAt(1) to 1f, Hsl.lumAt(2) to 1f,
            Hsl.satAt(3) to -1f, Hsl.lumAt(4) to -1f, Hsl.hueAt(5) to -1f,
        )
        for (r in 0..10) for (g in 0..10) for (b in 0..10) {
            val out = Hsl.apply(r / 10f, g / 10f, b / 10f, v)
            for (c in out) assertTrue(c in 0f..1f, "channel $c out of range")
        }
    }

    // ---- the colour space round trip ----

    @Test
    fun `rgb to hsl and back is lossless`() {
        for (i in 0..20) for (j in 0..20) for (k in 0..20) {
            val r = i / 20f; val g = j / 20f; val b = k / 20f
            val hsl = Hsl.rgbToHsl(r, g, b)
            val back = Hsl.hslToRgb(hsl[0], hsl[1], hsl[2])
            close(back[0], r, 1e-3f); close(back[1], g, 1e-3f); close(back[2], b, 1e-3f)
        }
    }

    @Test
    fun `primitives land on the hue they are`() {
        close(Hsl.rgbToHsl(1f, 0f, 0f)[0], 0f, 1e-3f)     // red
        close(Hsl.rgbToHsl(0f, 1f, 0f)[0], 120f, 1e-3f)   // green
        close(Hsl.rgbToHsl(0f, 0f, 1f)[0], 240f, 1e-3f)   // blue
        // Grey has no hue, and must not divide by zero to find one.
        val grey = Hsl.rgbToHsl(0.5f, 0.5f, 0.5f)
        close(grey[0], 0f, 1e-6f)
        close(grey[1], 0f, 1e-6f)
        close(grey[2], 0.5f, 1e-3f)
    }

    @Test
    fun `white and black do not divide by zero`() {
        close(Hsl.rgbToHsl(1f, 1f, 1f)[2], 1f, 1e-6f)
        close(Hsl.rgbToHsl(0f, 0f, 0f)[2], 0f, 1e-6f)
        assertTrue(Hsl.rgbToHsl(1f, 1f, 1f)[1].isFinite())
        assertTrue(Hsl.rgbToHsl(0f, 0f, 0f)[1].isFinite())
    }

    // ---- the codec field ----

    @Test
    fun `round trips through the recipe field`() {
        val v = FloatArray(Hsl.VALUES) { (it - 12) / 20f }
        val enc = Hsl.encode(v)
        val back = assertNotNullArray(Hsl.parse(enc))
        for (i in 0 until Hsl.VALUES) close(back[i], v[i], 5e-3f)
    }

    @Test
    fun `neutral encodes to the dash placeholder`() {
        assertEquals(Hsl.NONE, Hsl.encode(FloatArray(Hsl.VALUES)))
        assertEquals(Hsl.NONE, Hsl.encode(null))
        assertFalse(Hsl.isActive(FloatArray(Hsl.VALUES)))
        assertFalse(Hsl.isActive(null))
    }

    /**
     * The packed field must not contain a comma, because a comma is the codec's
     * own field separator.
     *
     * A comma here splits 24 values into 24 codec fields, the payload gains 23
     * phantom fields, and every decode returns null. That made every recipe
     * carrying a colour mixer unloadable, and it is completely invisible in a
     * test that only looks at `Hsl` in isolation.
     */
    @Test
    fun `the packed field contains no comma and survives the codec`() {
        val v = FloatArray(Hsl.VALUES) { (it - 11) / 30f }
        val enc = Hsl.encode(v)
        assertTrue(!enc.contains(','), "packed colour mixer must not contain a comma: $enc")

        val saved = SavedRecipe.create("P", "original", LabRecipe(hsl = enc))
        val payload = RecipeCodec.encode(saved)
        // Same number of fields as a recipe with no mixer, or the split is wrong.
        val plain = RecipeCodec.encode(SavedRecipe.create("P", "original", LabRecipe()))
        assertEquals(
            plain.split(',').size,
            payload.split(',').size,
            "the colour mixer changed the payload field count",
        )
        val back = RecipeCodec.decode(payload)
        assertTrue(back != null, "a recipe carrying a colour mixer must decode")
        assertEquals(enc, back!!.lab.hsl)
    }

    @Test
    fun `an unreadable field is unusable rather than partly applied`() {
        // A half-applied colour mixer is a wrong picture, not a slightly wrong
        // one, so a short or non-numeric field is dropped whole.
        assertNull(Hsl.parse("0.1,0.2"))
        assertNull(Hsl.parse("0.1," + "0,".repeat(30)))
        assertNull(Hsl.parse("0.1,0.2,x"))
        assertNull(Hsl.parse(""))
        assertNull(Hsl.parse(null))
    }

    /**
     * The Kotlin copy is what the tests above exercise and the shader is what
     * actually runs, so the two band geometries have to be the same numbers.
     * These are the facts that make a pixel land in the band it is named for:
     * the eight centres, the reach, and the hue conversion.
     */
    @Test
    fun `the shader and this file agree on the band geometry`() {
        val h = com.relic.catalog.Shaders.HEADER
        for (band in 0 until Hsl.BANDS) {
            assertTrue(
                h.contains("return ${Hsl.CENTRES[band].clean()};"),
                "shader centre for band $band is not ${Hsl.CENTRES[band]}",
            )
        }
        assertTrue(
            h.contains("if (d >= ${Hsl.REACH.clean()}) return 0.0;"),
            "shader reach is not Hsl.REACH (${Hsl.REACH})",
        )
        assertTrue(
            h.contains("/ ${Hsl.REACH.clean()}"),
            "shader falloff denominator is not Hsl.REACH",
        )
        // The hue conversion, which was once 3.6 degrees instead of 100 and
        // turned the whole panel into a nudge.
        assertTrue(
            h.contains("adj.x * ${Hsl.HUE_DEGREES.clean()}"),
            "shader hue factor is not Hsl.HUE_DEGREES (${Hsl.HUE_DEGREES})",
        )
        // The normalisation, without which uneven spacing leaves dead hues.
        assertTrue(h.contains("if (total > 0.0) adj /= total;"), "shader lost the weight normalisation")
    }

    /** 30.0 stays "30.0"; this just keeps the string form predictable. */
    private fun Float.clean(): String =
        if (this == toInt().toFloat()) toInt().toString() + ".0" else toString()

    private fun assertNotNullArray(v: FloatArray?) = v!!
}
