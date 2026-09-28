package com.retrocam.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round trips + invariants for the full-Lightroom control set. */
class LRControlsTest {

    private fun <T : Any> nn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    @Test
    fun `color grade round trips and is inactive by default`() {
        assertEquals(ColorGrade.NONE, ColorGrade.encode(null))
        assertEquals(ColorGrade.NONE, ColorGrade.encode(ColorGrade.defaults()))
        val v = floatArrayOf(0.1f, 0.5f, 0.3f, 0.4f, 0.6f, 0.5f, 0.5f, 0.1f)
        val back: FloatArray = nn(ColorGrade.parse(ColorGrade.encode(v)))
        for (i in 0 until 8) assertEquals(v[i], back[i], 5e-3f)
        assertNull(ColorGrade.parse("0.1 0.2"))
        assertNull(ColorGrade.parse(null))
    }

    @Test
    fun `bw mix round trips with no comma`() {
        val v = FloatArray(8) { (it - 4) / 10f }
        val enc = BwMix.encode(v)
        assertFalse(enc.contains(','))
        val back: FloatArray = nn(BwMix.parse(enc))
        for (i in 0 until 8) assertEquals(v[i], back[i], 5e-3f)
        assertEquals(BwMix.NONE, BwMix.encode(FloatArray(8)))
    }

    @Test
    fun `defringe round trips with no comma`() {
        val v = floatArrayOf(0.5f, 0.75f, 0.92f, 0.4f, 0.25f, 0.42f)
        val enc = Defringe.encode(v)
        assertFalse(enc.contains(','))
        val back: FloatArray = nn(Defringe.parse(enc))
        for (i in 0 until 6) assertEquals(v[i], back[i], 5e-3f)
    }

    @Test
    fun `geometry round trips and mode names line up`() {
        assertEquals(6, Geometry.MODE_NAMES.size)
        val v = floatArrayOf(5f, 0.2f, -0.1f, 0.1f, 0f, 0.1f, 0f, 0f)
        val back: FloatArray = nn(Geometry.parse(Geometry.encode(v)))
        for (i in 0 until 8) assertEquals(v[i], back[i], 5e-3f)
        assertEquals(Geometry.NONE, Geometry.encode(Geometry.defaults()))
        assertTrue(Geometry.isActive(v))
        assertFalse(Geometry.isActive(Geometry.defaults()))
    }

    @Test
    fun `exposure and gamma are two views of one field`() {
        assertEquals(2f, exposureToGamma(1f), 1e-3f)
        assertEquals(1f, gammaToExposure(2f), 1e-3f)
        assertEquals(0f, gammaToExposure(1f), 1e-4f)
    }

    @Test
    fun `hsl band helpers round trip`() {
        val r = LabRecipe().withHsl(3, 1, -0.4f)
        assertEquals(-0.4f, r.hslBand(3, 1), 1e-4f)
        assertTrue(r.hslActive)
        assertEquals(Hsl.NONE, LabRecipe().withHsl(0, 0, 0f).hsl)
    }

    @Test
    fun `grade bw cal defringe helpers round trip`() {
        val g = LabRecipe().withGrade(1, 0.6f)
        assertEquals(0.6f, g.gradeArray()!![1], 1e-4f)
        val b = LabRecipe().withBw(2, 0.3f)
        assertEquals(0.3f, b.bwArray()!![2], 1e-4f)
        val c = LabRecipe().withCal(0, 0.2f)
        assertEquals(0.2f, c.calibrationParts()!!.first[0], 1e-4f)
        val d = LabRecipe().withDefringe(0, 0.5f)
        assertEquals(0.5f, d.defringeArray()!![0], 1e-4f)
    }

    @Test
    fun `new xmp keys land instead of dropping`() {
        val res = XmpImport.parse(
            """
            crs:Vibrance="+20" crs:ColorGradeShadowHue="30" crs:ColorGradeShadowSat="40"
            crs:ColorGradeMidtoneHue="200" crs:ColorGradeMidtoneSat="30"
            crs:ColorGradeHighlightHue="50" crs:ColorGradeHighlightSat="50"
            crs:ColorGradeBlending="60" crs:ColorGradeBalance="20"
            crs:GrayMixerRed="10" crs:GrayMixerGreen="-15" crs:ConvertToGrayscale="True"
            crs:LuminanceSmoothing="30" crs:ColorNoiseReduction="40"
            crs:DefringePurpleAmount="50" crs:DefringeGreenAmount="30"
            crs:LensProfileEnable="True" crs:LensManualDistortionAmount="15"
            crs:AutoLateralCA="True" crs:PerspectiveVertical="10"
            crs:PerspectiveHorizontal="-5" crs:PerspectiveRotate="2"
            crs:PerspectiveAspect="10" crs:PerspectiveScale="110"
            crs:PerspectiveX="5" crs:PerspectiveY="-5" crs:PerspectiveUpright="5"
            crs:PostCropVignetteRoundness="20" crs:PostCropVignetteAspect="-10"
            """.trimIndent(),
        )
        assertEquals("dropped: ${res.ignored.map { it.key }}", 0, res.ignored.size)
        assertEquals(0.2f, res.recipe.vibrance, 1e-3f)
        assertTrue(res.recipe.gradeActive)
        assertEquals(0.3f, res.recipe.denoiseLum, 1e-3f)
        assertEquals(0.4f, res.recipe.denoiseColor, 1e-3f)
        assertTrue(res.recipe.defringeActive)
        assertEquals(1f, res.recipe.lensEnable, 0f)
        assertEquals(1f, res.recipe.lensCA, 0f)
        assertTrue(res.recipe.geoActive)
        // B&W mixer only matters with grayscale on, but it must still be stored.
        assertTrue(res.recipe.bwActive)
    }

    @Test
    fun `vibrance is separate from saturation now`() {
        val res = XmpImport.parse("""crs:Saturation="-2" crs:Vibrance="+10"""")
        assertEquals(0.1f, res.recipe.vibrance, 1e-4f)
        assertEquals(0.98f, res.recipe.adjustments.saturation, 1e-3f)
    }

    @Test
    fun `shader declares every new uniform and uses it`() {
        val h = com.retrocam.catalog.Shaders.HEADER
        for (u in listOf("u_vibrance", "u_gradeA", "u_gradeB", "u_gradeActive",
            "u_bwMixA", "u_bwMixB", "u_bwActive", "u_vigRound", "u_vigAspect",
            "u_nrLum", "u_nrColor", "u_defringeA", "u_defringeB", "u_defringeActive",
            "u_lensCA", "u_lensEnable", "u_lensDistort", "u_lensBlur", "u_lensFocus",
            "u_geoA", "u_geoB", "u_geoActive")) {
            assertTrue("$u missing from HEADER", h.contains(u))
        }
        val lab = com.retrocam.catalog.Shaders.LAB_GRADE
        for (s in listOf("u_vibrance", "u_gradeActive", "u_bwActive", "u_nrLum",
            "u_defringeActive", "u_lensBlur", "u_geoActive", "u_vigRound")) {
            assertTrue("$s never used in LAB_GRADE", lab.contains(s))
        }
    }
}
