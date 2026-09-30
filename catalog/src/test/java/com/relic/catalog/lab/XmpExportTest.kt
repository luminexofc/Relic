package com.relic.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export half of XMP: a curated preset built from scratch must leave the
 * app as a file Lightroom understands.
 *
 * Every test here is a round trip through our own importer, which is the
 * honest version of "Lightroom can read this": the importer reads the real
 * key names, so a file it reads back to the same values is a file in the
 * current dialect rather than ours.
 */
class XmpExportTest {

    private fun roundTrip(r: LabRecipe): LabRecipe = XmpImport.parse(XmpExport.export(r, "TEST")).recipe

    @Test
    fun `light controls round trip`() {
        val r = LabRecipe(
            gamma = 1.2746f,
            adjustments = LabAdjustments(contrast = 1.12f),
            highlights = -0.4f, shadows = 0.38f, whites = -0.2f, blacks = 0.1f,
        )
        val back = roundTrip(r)
        assertEquals(r.gamma, back.gamma, 1e-2f)
        assertEquals(1.12f, back.adjustments.contrast, 1e-3f)
        assertEquals(-0.4f, back.highlights, 5e-3f)
        assertEquals(0.38f, back.shadows, 5e-3f)
        assertEquals(-0.2f, back.whites, 5e-3f)
        assertEquals(0.1f, back.blacks, 5e-3f)
    }

    @Test
    fun `color controls round trip`() {
        val r = LabRecipe(
            adjustments = LabAdjustments(saturation = 0.9f, warmth = 0.3f, tint = 0.1f),
            vibrance = 0.2f, grayscale = 1f,
            hsl = Hsl.encode(FloatArray(Hsl.VALUES).also { it[Hsl.satAt(3)] = -0.4f }),
            colorGrade = ColorGrade.encode(floatArrayOf(0.6f, 0.5f, 0.3f, 0.4f, 0.1f, 0.5f, 0.5f, 0f)),
            bwMix = BwMix.encode(FloatArray(8).also { it[3] = -0.5f }),
            calibration = Calibration.encode(floatArrayOf(0.1f, 0f, 0f), floatArrayOf(0f, 0.2f, 0f)),
        )
        val back = roundTrip(r)
        assertEquals(0.9f, back.adjustments.saturation, 1e-2f)
        assertEquals(0.2f, back.vibrance, 5e-3f)
        assertEquals(1f, back.grayscale, 0f)
        assertEquals(-0.4f, back.hslArray()!![Hsl.satAt(3)], 5e-3f)
        assertEquals(0.5f, back.gradeArray()!![1], 5e-3f)
        assertEquals(-0.5f, back.bwArray()!![3], 5e-3f)
        assertEquals(0.1f, back.calibrationParts()!!.first[0], 5e-3f)
    }

    @Test
    fun `effects and detail round trip`() {
        val r = LabRecipe(
            texture = 0.15f, clarity = 0.08f, dehaze = 0.05f,
            vignette = 0.22f, vigMidpoint = 0.65f, vigFeather = 0.75f,
            vigRound = 0.6f, vigAspect = 0.4f,
            grain = 0.06f, grainSize = 2f, grainRough = 0.4f,
            sharpen = 0.45f, sharpRadius = 1.2f, detail = 0.25f, masking = 0.4f,
            denoiseLum = 0.3f, denoiseColor = 0.4f,
            defringe = Defringe.encode(floatArrayOf(0.5f, 0.75f, 0.92f, 0.4f, 0.25f, 0.42f)),
            lensCA = 1f, lensEnable = 1f, lensDistort = 0.15f,
            geometry = Geometry.encode(floatArrayOf(5f, 0.1f, 0f, 0f, 0f, 0.1f, 0f, 0f)),
        )
        val back = roundTrip(r)
        assertEquals(0.15f, back.texture, 5e-3f)
        assertEquals(0.08f, back.clarity, 5e-3f)
        assertEquals(0.22f, back.vignette, 5e-3f)
        assertEquals(0.65f, back.vigMidpoint, 5e-3f)
        assertEquals(0.6f, back.vigRound, 5e-3f)
        assertEquals(2f, back.grainSize, 1e-2f)
        assertEquals(0.45f, back.sharpen, 5e-3f)
        assertEquals(0.3f, back.denoiseLum, 5e-3f)
        assertEquals(0.5f, back.defringeArray()!![0], 5e-3f)
        assertEquals(1f, back.lensCA, 0f)
        assertEquals(0.1f, back.geoArray()!![1], 5e-3f)
    }

    @Test
    fun `a tone curve survives as control points`() {
        val r = LabRecipe(
            toneCurves = ToneCurve.encodeGroup(
                listOf(
                    FloatArray(ToneCurve.SIZE) { (it + (it - 128) * 0.1f) / 255f },
                    null, null, null,
                ),
            ),
        )
        val back = roundTrip(r)
        assertTrue(back.toneCurveActive)
        // Ten control points through a linear re-interpolation land close.
        val want = ToneCurve.parseGroup(r.toneCurves)[0]!!
        val got = ToneCurve.parseGroup(back.toneCurves)[0]!!
        for (i in listOf(0, 64, 128, 192, 255)) {
            assertEquals("at $i", want[i], got[i], 0.02f)
        }
    }

    @Test
    fun `an identity recipe exports almost nothing`() {
        val doc = XmpExport.export(LabRecipe(), "PLAIN")
        // Header keys only; no look settings at all.
        assertTrue(doc.contains("PresetType"))
        assertTrue(!doc.contains("Contrast2012"))
        assertTrue(!doc.contains("Sharpness"))
        assertTrue(!doc.contains("ToneCurvePV2012"))
        val back = XmpImport.parse(doc)
        assertTrue(back.recipe.isIdentity)
        // Nothing to cover and nothing failed: no look keys, no unsupported.
        assertEquals(0, back.lookKeys.size)
        assertEquals(0, back.ignored.size)
    }

    @Test
    fun `the document names the preset and escapes it`() {
        val doc = XmpExport.export(LabRecipe(adjustments = LabAdjustments(contrast = 1.2f)), "A&B \"Q\" <x>")
        // Names uppercase like saved recipes, and XML-escape.
        assertTrue(doc.contains("A&amp;B &quot;Q&quot; &lt;X&gt;"))
        assertTrue(doc.contains("xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\""))
    }
}
