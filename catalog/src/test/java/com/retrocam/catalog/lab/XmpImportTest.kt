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
            crs:SharpnessRadius="1.20"
            crs:Detail="25"
            crs:Masking="40"
            crs:ColorTemp="5200"
            crs:Tint="+6"
            crs:Look="Medium High Contrast"
            crs:ToneCurvePV2012="0, 0, 255, 255, 128, 132, 64, 96, 192, 208"
            crs:GrainAmount="18"
            crs:GrainSize="60"
            crs:GrainRoughness="70"
            crs:PostCropVignetteAmount="-22"
            crs:PostCropVignetteMidpoint="65"
            crs:PostCropVignetteFeather="75"
            crs:PostCropVignetteRoundness="+12"
            crs:PostCropVignetteAspect="+8"
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
     * and 2.x are legacy.
     *
     * The SCALE belongs to the key, not to ProcessVersion. `Contrast` is 0-255
     * about 128 and `Contrast2012` is -100..100, so a file carrying the
     * unprefixed key is read on the legacy scale whichever version it claims.
     * The old reader inferred the scale from ProcessVersion and consequently
     * threw away a perfectly good legacy contrast value from any file that
     * mentioned a modern process version.
     */
    @Test
    fun `the scale comes from the key name, not from ProcessVersion`() {
        for (v in listOf("3.0", "5.0", "6.6", "11.0", "15.0")) {
            val legacy = XmpImport.parse("""crs:ProcessVersion="$v" crs:Contrast="138"""").recipe
            assertEquals("PV $v legacy scale", 1.1f, legacy.adjustments.contrast, 1e-3f)
            val modern = XmpImport.parse("""crs:ProcessVersion="$v" crs:Contrast2012="+10"""").recipe
            assertEquals("PV $v 2012 scale", 1.1f, modern.adjustments.contrast, 1e-3f)
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
    fun `the keys with no Lab equivalent are reported, not vanished`() {
        val res = XmpImport.parse(modern)
        val dropped = res.ignored.map { it.key }.toSet()
        // The only keys with no Lab equivalent left: Adobe's proprietary curve
        // sets, and the two vignette shape parameters. GrainAmount now maps, and
        // does, because the grain got real size and roughness parameters.
        // ToneCurveName is metadata, not a look setting, so it is deliberately
        // absent from the report rather than listed as dropped.
        for (k in listOf(
            "Look", "PostCropVignetteRoundness", "PostCropVignetteAspect",
        )) {
            assertTrue("$k was dropped without being reported", k in dropped)
        }
        assertTrue(
            "GrainAmount should be applied now, not dropped",
            res.applied.any { it.key == "GrainAmount" },
        )
        assertTrue("the report should still name the keys it did handle",
            res.applied.map { it.key }.containsAll(
                listOf("Texture", "Clarity", "Dehaze", "Highlights2012", "Shadows2012")))
        // Every drop has to say why, or the report is just a list of absences.
        assertTrue(res.ignored.all { it.reason.orEmpty().isNotBlank() })
    }

    /** Local contrast used to be reported as having no analogue. */
    @Test
    fun `texture clarity and dehaze are applied not dropped`() {
        val r = XmpImport.parse(modern).recipe
        assertEquals(15 / 100f, r.texture, 1e-3f)
        assertEquals(5 / 100f, r.clarity, 1e-3f)
        assertEquals(3 / 100f, r.dehaze, 1e-3f)
        assertTrue(r.localActive)
        val res = XmpImport.parse(modern)
        for (k in listOf("Texture", "Clarity", "Dehaze")) {
            assertTrue("$k should be applied", res.applied.any { it.key == k })
            assertTrue("$k should not be dropped", res.ignored.none { it.key == k })
        }
    }

    @Test
    fun `local contrast values are clamped`() {
        val r = XmpImport.parse("""crs:Texture="+9000" crs:Clarity="-9000"""").recipe
        assertTrue(r.texture in -1f..1f)
        assertTrue(r.clarity in -1f..1f)
    }

    /** The four range controls used to be the biggest thing we threw away. */
    @Test
    fun `highlights shadows whites and blacks are applied not dropped`() {
        val res = XmpImport.parse(modern)
        val r = res.recipe
        assertEquals(-42 / 100f, r.highlights, 1e-3f)
        assertEquals(24 / 100f, r.shadows, 1e-3f)
        assertEquals(8 / 100f, r.whites, 1e-3f)
        assertEquals(-11 / 100f, r.blacks, 1e-3f)
        assertTrue(r.rangesActive)
        for (k in listOf("Highlights2012", "Shadows2012", "Whites2012", "Blacks2012")) {
            assertTrue("$k should be applied", res.applied.any { it.key == k })
            assertTrue("$k should not be in the dropped list", res.ignored.none { it.key == k })
        }
    }

    @Test
    fun `range values are clamped rather than allowed to run away`() {
        val r = XmpImport.parse("""crs:Highlights2012="+9000" crs:Shadows2012="-9000"""").recipe
        assertTrue(r.highlights in -1f..1f)
        assertTrue(r.shadows in -1f..1f)
    }

    @Test
    fun `look is still dropped and says why`() {
        val res = XmpImport.parse(modern)
        val look = res.ignored.first { it.key == "Look" }
        assertTrue(look.reason.orEmpty().contains("no honest mapping"))
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
        assertTrue(res.ignored.any { it.key == "ToneCurvePV2012" && it.reason.orEmpty().contains("unreadable") })
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

    // ---- renamed keys ----

    /**
     * Adobe renamed several settings between Process Versions, and the reader
     * only knew the new names. A preset using the old names therefore lost all
     * three of its sharpening parameters, its clarity and its grain
     * distribution while reporting every one of them as "no Lab equivalent" -
     * which is the exact opposite of true and the reason a real preset read as
     * 9% covered.
     */
    @Test
    fun `the old key names are read, not treated as unknown`() {
        val res = XmpImport.parse(
            """
            crs:Clarity2012="+8" crs:SharpenRadius="0.8" crs:SharpenDetail="25"
            crs:SharpenEdgeMasking="40" crs:GrainFrequency="40" crs:VignetteAmount="-22"
            crs:Exposure="+0.5" crs:Contrast="128" crs:Temperature="9000"
            """.trimIndent(),
        )
        assertEquals("no key should be reported as unknown", 0, res.ignored.size)
        val r = res.recipe
        assertEquals(0.08f, r.clarity, 1e-3f)
        assertEquals(0.8f, r.sharpRadius, 1e-3f)
        assertEquals(0.25f, r.detail, 1e-3f)
        assertEquals(0.4f, r.masking, 1e-3f)
        assertEquals(0.4f, r.grainRough, 1e-3f)
        assertEquals(0.22f, r.vignette, 1e-3f)
        // Exposure +0.5 EV: gamma = 2^0.5
        assertEquals(1.4142f, r.gamma, 1e-3f)
        // Contrast 128 is exactly neutral on the legacy scale.
        assertEquals(1f, r.adjustments.contrast, 1e-4f)
        assertTrue(r.adjustments.warmth > 0f)
    }

    /** The report has to name the key the file used, not the one we wished for. */
    @Test
    fun `the report names the key the file actually used`() {
        val res = XmpImport.parse("""crs:SharpenEdgeMasking="40"""")
        assertTrue(res.applied.any { it.key == "SharpenEdgeMasking" })
        assertTrue(res.applied.none { it.key == "Masking" })
    }

    /** Both families present: the 2012 key wins, which is what Adobe means. */
    @Test
    fun `the newer name wins when a file carries both`() {
        val res = XmpImport.parse("""crs:Clarity="+5" crs:Clarity2012="+40"""")
        assertEquals(0.4f, res.recipe.clarity, 1e-3f)
        assertTrue(res.applied.any { it.key == "Clarity2012" })
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

    // ---- coverage ----

    /**
     * A percentage is only useful if it is not always 100 and not always 0, so
     * these pin both ends and the arithmetic between them.
     *
     * The denominator is the look settings, NOT every attribute in the file. A
     * preset reporting 9% while reproducing nearly all of its actual settings
     * was measuring how verbose XMP is, not how good the import is.
     */
    @Test
    fun `coverage counts look keys, not visual weight, and says so`() {
        val res = XmpImport.parse(modern)
        val expected = res.applied.size * 100 / res.lookKeys.size
        assertEquals(expected, res.coveragePercent)
        assertTrue("coverage should be partial for a real preset", res.coveragePercent in 1..99)
        assertEquals(res.exact.size * 100 / res.lookKeys.size, res.exactPercent)
        assertTrue("exact should not exceed total", res.exactPercent <= res.coveragePercent)
    }

    /**
     * The headline number has to be about the picture. Every key a preset sets
     * to zero is honoured, and a capability flag is not scored as a failure, so
     * a preset whose look settings we all handle reports full coverage even
     * though its file lists a hundred attributes.
     */
    @Test
    fun `a real preset is not scored on the keys that are not a look`() {
        val res = XmpImport.parse(
            """
            crs:Contrast2012="+12" crs:Exposure2012="+0.25" crs:Highlights2012="-40"
            crs:Shadows2012="+38" crs:Whites2012="-20" crs:Blacks2012="+10"
            crs:Saturation="-2" crs:Vibrance="+10" crs:Clarity2012="+8"
            crs:GrainAmount="6" crs:GrainSize="11" crs:GrainFrequency="40"
            crs:SharpenRadius="0.8" crs:SharpenDetail="25" crs:SharpenEdgeMasking="40"
            crs:ProcessVersion="11.0" crs:PresetType="Normal"
            crs:SupportsColor="True" crs:SupportsMonochrome="False"
            crs:Copyright="Someone" crs:ContactInfo="x" crs:Version="15.0"
            crs:CameraModelRestriction="all" crs:AutoLateralCA="True"
            crs:PerspectiveVertical="12" crs:LensProfileEnable="False"
            crs:LensProfileSetup="0" crs:CameraProfile="Adobe Standard"
            crs:HasSettings="True" crs:Name="my preset"
            """.trimIndent(),
        )
        // Everything above that is a look setting, we handle. Everything above
        // that is not, is out of the denominator.
        assertEquals(0, res.ignored.size)
        assertEquals(100, res.coveragePercent)
        // And the non-look keys are still visible, not vanished.
        assertTrue(res.metadata.isNotEmpty())
        for (k in listOf("Copyright", "PresetType", "PerspectiveVertical", "Name")) {
            assertTrue("$k should be listed as not-a-look", res.metadata.any { it.key == k })
        }
        // 15 of the file's keys are look settings, and we handle all 15.
        assertEquals(15, res.lookKeys.size)
    }

    /**
     * Zero is a value. A preset that says `Exposure2012="0"` has told us its
     * exposure, and the old reader scored that as a failure to import.
     */
    @Test
    fun `a key set to zero is honoured rather than reported as dropped`() {
        val res = XmpImport.parse(
            """crs:Exposure2012="0" crs:Contrast2012="0" crs:Clarity2012="0" """,
        )
        assertEquals(0, res.ignored.size)
        for (k in listOf("Exposure2012", "Contrast2012", "Clarity2012")) {
            val key = res.exact.firstOrNull { it.key == k }
            assertTrue("$k should be reported as honoured", key != null)
            assertEquals("$k neutral", "neutral (0)", key!!.mapsTo)
        }
        assertEquals(100, res.coveragePercent)
        // And the recipe is untouched, because zero really is neutral.
        assertTrue(res.recipe.isIdentity)
    }

    @Test
    fun `a preset with nothing unsupported is full coverage`() {
        val r = XmpImport.parse("""crs:Contrast2012="+10"""")
        assertEquals(100, r.coveragePercent)
        assertEquals(100, r.exactPercent)
    }

    @Test
    fun `a preset with nothing mappable is zero coverage`() {
        val r = XmpImport.parse("""crs:Look="Medium High Contrast"""")
        assertEquals(0, r.coveragePercent)
        assertTrue(r.isEmpty)
    }

    @Test
    fun `an empty file reports zero rather than dividing by nothing`() {
        val r = XmpImport.parse("<html></html>")
        assertEquals(0, r.coveragePercent)
        assertEquals(0, r.exactPercent)
        assertTrue(r.keys.isEmpty())
    }

    /**
     * Every key lands in exactly one of the four tiers. Two parallel lists
     * could drift; one tagged list cannot, and this is what proves the tagging
     * is total.
     */
    @Test
    fun `every key is in exactly one tier and the views partition them`() {
        val res = XmpImport.parse(modern)
        assertEquals(
            res.keys.size,
            res.exact.size + res.approximate.size + res.ignored.size + res.metadata.size,
        )
        assertEquals(res.applied.size, res.exact.size + res.approximate.size)
        assertTrue(res.exact.none { it.reason != null })
        assertTrue(res.ignored.all { it.mapsTo == null })
        assertTrue(res.applied.all { it.mapsTo != null && it.mapsTo.isNotBlank() })
        // lookKeys is applied plus the unsupported ones, and excludes the
        // not-a-look tier entirely.
        assertEquals(res.applied.size + res.ignored.size, res.lookKeys.size)
        assertEquals(res.keys.size - res.metadata.size, res.lookKeys.size)
    }

    @Test
    fun `no key appears twice in the report`() {
        val res = XmpImport.parse(modern)
        val dupes = res.keys.groupBy { it.key }.filterValues { it.size > 1 }
        assertTrue("duplicated: " + dupes.keys, dupes.isEmpty())
    }

    /** The tiers are what the UI marks, so they have to be the real ones. */
    @Test
    fun `the tiers separate the exact from the approximate`() {
        val res = XmpImport.parse(modern)
        val exact = res.exact.map { it.key }.toSet()
        val approx = res.approximate.map { it.key }.toSet()
        // Direct unit matches.
        assertTrue(exact.contains("Contrast2012"))
        assertTrue(exact.contains("Tint"))
        // Remapped or rescaled.
        assertTrue(approx.contains("Exposure2012"))
        assertTrue(approx.contains("ColorTemp"))
        assertTrue(approx.contains("GrainSize"))
        assertTrue(exact.none { it in approx })
    }
}
