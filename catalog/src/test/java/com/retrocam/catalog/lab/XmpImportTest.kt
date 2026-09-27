package com.retrocam.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpImportTest {

    /** A real Lightroom 11 preset, trimmed but structurally identical. */
    private val modern = """
        <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:xmp="http://ns.adobe.com/xap/1.0/"
            xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
            crs:Version="15.0"
            crs:ProcessVersion="11.0"
            crs:HasSettings="True"
            crs:Contrast2012="+10"
            crs:Exposure2012="+0.35"
            crs:Highlights2012="-42"
            crs:Shadows2012="+24"
            crs:Whites2012="+8"
            crs:Blacks2012="-11"
            crs:Texture="+15"
            crs:Clarity="+5"
            crs:Dehaze="+3"
            crs:Vibrance="+20"
            crs:Saturation="-5"
            crs:Sharpness="45"
            crs:ColorTemp="5200"
            crs:Tint="+6"
            crs:Look="Medium High Contrast"
            crs:ToneCurvePV2012="0, 0, 255, 255, 128, 132, 64, 96, 192, 208"
            crs:GrainAmount="18"
          />
         </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    // ---- reading ----

    @Test
    fun `reads every crs attribute across lines`() {
        val a = XmpImport.readAttributes(modern)
        assertEquals("+10", a["Contrast2012"])
        assertEquals("+0.35", a["Exposure2012"])
        assertEquals("Medium High Contrast", a["Look"])
    }

    @Test
    fun `a tone curve with spaces and commas survives`() {
        // The commas here would break a naive split, which is the whole reason
        // the scanner reads attributes rather than fields.
        val a = XmpImport.readAttributes(modern)
        assertTrue(a.getValue("ToneCurvePV2012").contains(','))
        assertEquals(10, a.getValue("ToneCurvePV2012").split(',').size)
    }

    @Test
    fun `non-crs attributes are ignored`() {
        val a = XmpImport.readAttributes("""xmp:Creator="me" crs:Contrast2012="+5"""")
        assertEquals(1, a.size)
        assertEquals("+5", a["Contrast2012"])
    }

    @Test
    fun `a file with no crs attributes reads as empty rather than throwing`() {
        assertTrue(XmpImport.readAttributes("<html></html>").isEmpty())
        assertTrue(XmpImport.parse("<html></html>").isEmpty)
    }

    // ---- mapping ----

    @Test
    fun `contrast and saturation land on their own knobs`() {
        val r = XmpImport.parse(modern).recipe.adjustments
        assertEquals(1.1f, r.contrast, 1e-3f)
        // Saturation -5 and Vibrance +20 multiply: 0.95 * 1.2 = 1.14
        assertEquals(1.14f, r.saturation, 1e-3f)
    }

    @Test
    fun `exposure becomes gamma rather than brightness`() {
        val rec = XmpImport.parse(modern).recipe
        // +0.35 EV: 2^0.35 = 1.2746
        assertEquals(1.2746f, rec.gamma, 1e-3f)
        // brightness must stay neutral, because it is an additive offset and an
        // exposure is multiplicative: moving it would drag blacks.
        assertEquals(0f, rec.adjustments.brightness, 0f)
    }

    @Test
    fun `a negative exposure darkens via gamma`() {
        val rec = XmpImport.parse("""crs:Exposure2012="-1.0"""").recipe
        assertEquals(0.5f, rec.gamma, 1e-3f)
    }

    @Test
    fun `sharpness becomes the sharpen effect`() {
        assertEquals(0.45f, XmpImport.parse(modern).recipe.sharpen, 1e-4f)
    }

    @Test
    fun `a cooler colour temp gives negative warmth and 5500 is neutral`() {
        assertTrue(XmpImport.parse(modern).recipe.adjustments.warmth < 0f)
        assertEquals(0f, XmpImport.parse("""crs:ColorTemp="5500"""").recipe.adjustments.warmth, 1e-4f)
        assertTrue(XmpImport.parse("""crs:ColorTemp="9000"""").recipe.adjustments.warmth > 0f)
    }

    @Test
    fun `tint maps straight across because both are minus one to one`() {
        assertEquals(0.06f, XmpImport.parse(modern).recipe.adjustments.tint, 1e-4f)
    }

    // ---- process version routing ----

    @Test
    fun `a process 2 preset uses the legacy contrast scale about 128`() {
        val r = XmpImport.parse("""crs:ProcessVersion="2.6" crs:Contrast="138"""").recipe
        assertEquals(1.1f, r.adjustments.contrast, 1e-3f)
    }

    @Test
    fun `a legacy contrast of 128 is neutral`() {
        val r = XmpImport.parse("""crs:ProcessVersion="2.6" crs:Contrast="128"""").recipe
        assertEquals(1f, r.adjustments.contrast, 1e-4f)
    }

    /**
     * The *2012 key names arrived with Process Version 3 (Lightroom 4) and every
     * version since still writes them, so a preset tagged 5.0 is modern. Only 1.x
     * and 2.x are legacy. Getting this backwards reads a modern preset's
     * unprefixed keys as absent, or worse, reads a legacy 0-255 contrast as if it
     * were already on the -100..100 scale.
     */
    @Test
    fun `process version 3 and above is a modern preset`() {
        for (v in listOf("3.0", "5.0", "6.6", "11.0", "15.0")) {
            val rec = XmpImport.parse("""crs:ProcessVersion="$v" crs:Contrast="138"""").recipe
            // Unprefixed Contrast must be ignored outright, not coerced.
            assertEquals("PV $v", 1f, rec.adjustments.contrast, 1e-4f)
            val modern = XmpImport.parse("""crs:ProcessVersion="$v" crs:Contrast2012="+10"""").recipe
            assertEquals("PV $v", 1.1f, modern.adjustments.contrast, 1e-3f)
        }
    }

    @Test
    fun `the 2012 keys win when a file somehow carries both families`() {
        val x = """crs:Contrast="200" crs:Contrast2012="+10""""
        assertEquals(1.1f, XmpImport.parse(x).recipe.adjustments.contrast, 1e-3f)
    }

    /**
     * The two failure modes that would be invisible if they only showed up on a
     * device: values run past their knob range, and a hostile file making the
     * renderer build a chain of nine passes.
     */
    @Test
    fun `absurd values are clamped into the knob ranges`() {
        val r = XmpImport.parse(
            """crs:Exposure2012="+9" crs:Contrast2012="+9000" crs:Saturation="+9000" """ +
                """crs:ColorTemp="200000" crs:Tint="+900" crs:Sharpness="5000"""",
        ).recipe
        assertTrue(r.gamma in 0.2f..3f)
        assertTrue(r.adjustments.contrast in 0f..3f)
        assertTrue(r.adjustments.saturation in 0f..3f)
        assertTrue(r.adjustments.warmth in -1f..1f)
        assertTrue(r.adjustments.tint in -1f..1f)
        assertTrue(r.sharpen in 0f..1f)
    }

    @Test
    fun `an imported recipe is a usable recipe`() {
        val rec = XmpImport.parse(modern).recipe
        assertTrue(!rec.isIdentity)
        // No stages, so the chain stays at the grade alone.
        assertTrue(rec.stages.isEmpty())
    }

    // ---- honest reporting ----

    @Test
    fun `the report names every key that was mapped`() {
        val res = XmpImport.parse(modern)
        val keys = res.applied.map { it.key }.toSet()
        assertTrue(
            "missing keys: " + (setOf("Exposure2012", "Contrast2012", "Saturation", "Vibrance", "Sharpness", "ColorTemp", "Tint") - keys),
            keys.containsAll(setOf("Exposure2012", "Contrast2012", "Saturation", "Vibrance", "Sharpness", "ColorTemp", "Tint")),
        )
    }

    @Test
    fun `approximate mappings are flagged as such`() {
        val res = XmpImport.parse(modern)
        val approx = res.applied.filter { it.approx }.map { it.key }.toSet()
        // Exposure, Vibrance and ColorTemp are the lossy ones.
        assertTrue(approx.contains("Exposure2012"))
        assertTrue(approx.contains("Vibrance"))
        assertTrue(approx.contains("ColorTemp"))
        // These are exact, so claiming otherwise would be its own kind of lie.
        assertTrue(!res.applied.first { it.key == "Contrast2012" }.approx)
        assertTrue(!res.applied.first { it.key == "Tint" }.approx)
    }

    @Test
    fun `the range-specific keys are reported as dropped, not vanished`() {
        val res = XmpImport.parse(modern)
        val dropped = res.ignored.map { it.key }.toSet()
        for (k in listOf("Highlights2012", "Shadows2012", "Whites2012", "Blacks2012", "Texture", "Clarity", "Dehaze", "Look", "GrainAmount")) {
            assertTrue("$k was dropped without being reported", k in dropped)
        }
        // Every drop has to say why, or the report is just a list of absences.
        assertTrue(res.ignored.all { it.reason.isNotBlank() })
    }

    @Test
    fun `look is still dropped and says why`() {
        val res = XmpImport.parse(modern)
        val look = res.ignored.first { it.key == "Look" }
        assertTrue(look.reason.contains("no honest mapping"))
    }

    /** The tone curve is the biggest thing a preset carries, so it must land. */
    @Test
    fun `the tone curve is imported rather than dropped`() {
        val res = XmpImport.parse(modern)
        assertTrue(res.recipe.toneCurveActive)
        assertTrue(res.applied.any { it.key == "ToneCurvePV2012" })
        assertTrue(res.ignored.none { it.key == "ToneCurvePV2012" })
        val g = ToneCurve.parseGroup(res.recipe.toneCurves)
        assertNotNull(g[0])
        // 128,132: a lift of the midtones.
        assertEquals(132 / 255f, g[0]!![128], 1e-3f)
    }

    @Test
    fun `per-channel curves land in their own slots`() {
        val res = XmpImport.parse(
            "crs:ToneCurvePV2012Red=\"0, 0, 255, 255, 128, 200\" " +
                "crs:ToneCurvePV2012Blue=\"0, 0, 255, 255, 128, 60\"",
        )
        val g = ToneCurve.parseGroup(res.recipe.toneCurves)
        assertNull(g[0]); assertNotNull(g[1]); assertNull(g[2]); assertNotNull(g[3])
        assertEquals(200 / 255f, g[1]!![128], 1e-3f)
        assertEquals(60 / 255f, g[3]!![128], 1e-3f)
    }

    @Test
    fun `an unreadable curve is reported as unreadable, not as identity`() {
        val res = XmpImport.parse("""crs:ToneCurvePV2012="nonsense"""")
        assertTrue(!res.recipe.toneCurveActive)
        assertTrue(res.ignored.any { it.key == "ToneCurvePV2012" && it.reason.contains("unreadable") })
    }

    @Test
    fun `a preset with no curve leaves the recipe inactive`() {
        val r = XmpImport.parse("""crs:Contrast2012="+10"""").recipe
        assertTrue(!r.toneCurveActive)
        assertEquals(ToneCurve.NONE, r.toneCurves)
    }

    /** Nonsense in one curve must not take the rest of the import with it. */
    @Test
    fun `one bad curve does not discard the rest of the preset`() {
        val res = XmpImport.parse("""crs:ToneCurvePV2012Red="junk" crs:Contrast2012="+10"""")
        assertEquals(1.1f, res.recipe.adjustments.contrast, 1e-3f)
        assertTrue(res.applied.any { it.key == "Contrast2012" })
    }

    @Test
    fun `unrecognised keys still appear in the report`() {
        val res = XmpImport.parse("""crs:Contrast2012="+5" crs:SomeFutureSetting="+9"""")
        assertNotNull(res.ignored.firstOrNull { it.key == "SomeFutureSetting" })
    }

    @Test
    fun `metadata is not reported as a dropped look setting`() {
        val res = XmpImport.parse(modern)
        assertNull(res.ignored.firstOrNull { it.key == "ProcessVersion" })
        assertNull(res.ignored.firstOrNull { it.key == "HasSettings" })
    }

    /**
     * Two parses in a row must not share state. An earlier version kept its
     * accumulators on the object, so a preset that set no gamma would inherit
     * the last preset's gamma and look like it had been applied.
     */
    @Test
    fun `successive parses do not leak into each other`() {
        XmpImport.parse(modern)
        val fresh = XmpImport.parse("""crs:Contrast2012="+10"""").recipe
        assertEquals(1f, fresh.gamma, 1e-4f)
        assertEquals(0f, fresh.sharpen, 0f)
        assertEquals(0f, fresh.adjustments.warmth, 0f)
        assertEquals(1f, fresh.adjustments.saturation, 1e-4f)
    }

    @Test
    fun `a preset of all zeroes imports as an identity recipe`() {
        val r = XmpImport.parse(
            """crs:Contrast2012="0" crs:Exposure2012="0" crs:Saturation="0" crs:Tint="0" """,
        ).recipe
        assertTrue(r.isIdentity)
    }
}
