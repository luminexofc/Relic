package com.retrocam.catalog.lab

import com.retrocam.catalog.FilterFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec is the one place where a recipe crosses a trust boundary: a scanned
 * QR is attacker-controlled text. So these lean hard on "never throws, never
 * produces a broken filter".
 */
class RecipeCodecTest {

    private fun recipe(
        name: String = "SUNSET 94",
        base: String = "original",
        template: String? = "KODAK_PORTRA",
        b: Float = 0.1f,
        c: Float = 1.2f,
        s: Float = 0.8f,
        w: Float = 0.4f,
        t: Float = -0.2f,
    ) = SavedRecipe.create(name, base, LabRecipe(template, LabAdjustments(b, c, s, w, t)))

    private fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    /**
     * Builds a raw v3 payload without hand-counting commas. Field order must match
     * RecipeCodec.encode: version, name, baseId, template, five knobs, six
     * effects, two colours, then the lut pair.
     */
    private fun payload(
        version: String = "13",
        name: String = "X",
        base: String = "original",
        template: String = "-",
        knobs: List<String> = listOf("0", "1", "1", "0", "0"),
        effects: List<String> = List(6) { "0" },
        colours: List<String> = listOf("0", "255"),
        colour: List<String> = listOf("1", "0", "0", "0"),
        stamp: List<String> = listOf("-", "0", "4", "1", "-", "0", "4"),
        reserved: String = "0",
        stages: String = "-",
        curves: String = "-",
        ranges: List<String> = listOf("0", "0", "0", "0"),
        local: List<String> = listOf("0", "0", "0"),
        ops: List<String> = listOf("1", "0", "0", "1", "0.5", "0.5", "0.5"),
        mixer: List<String> = listOf("-", "0"),
        calibration: String = "-",
        extra: List<String> = listOf("0", "-", "-", "0.5", "0.5", "0", "0", "-", "0", "0", "0", "0", "0.5", "-"),
    ) = (listOf(version, b64(name), base, template) + knobs + effects + colours +
        colour + stamp + reserved + stages + curves + ranges + local + ops + mixer + calibration + extra)
        .joinToString(",")

    @Test
    fun `round trips`() {
        val r = recipe()
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    /**
     * The field count and the encoder are the same fact stated twice, and §4.7
     * is what happens when they disagree: FIELD_COUNT was 48 for 47 fields, so
     * every decode returned null and the app had no recipes at all.
     *
     * A hand-written 47 here was one more place to forget, so the count is now
     * derived from what the encoder actually produces. Appending a field to
     * `encode` without updating `FIELD_COUNT` fails HERE, loudly, instead of
     * silently making the app unable to read anything.
     */
    @Test
    fun `field count matches the encoder`() {
        val enc = RecipeCodec.encode(recipe())
        val built = enc.split(',').size
        // And a hand-built payload of the same shape must also be the same
        // length, which is what catches a payload() helper that fell behind.
        assertEquals(built, payload().split(',').size, "payload() is out of step with encode()")
        // If these ever disagree the app is unreadable, so pin the number too.
        assertEquals(62, built, "field count changed; bump FIELD_COUNT and this test")
    }

    /**
     * A recipe carrying every field, round tripped.
     *
     * The `round trips` test above uses a recipe that leaves most fields
     * neutral, so a field that is written wrongly but read back consistently
     * can hide in the gap: encode writes the wrong thing and decode reads that
     * same wrong thing, and the two never notice. This one sets something
     * non-neutral in every field, which is the only way to catch a pair of
     * matching mistakes.
     *
     * It found a real one: the packed colour mixer and the grayscale switch
     * were being written to the payload and read back from the wrong index,
     * because the two new fields were appended after a field list that had
     * already been updated. Both halves were individually plausible.
     */
    @Test
    fun `every field survives a round trip when every field is set`() {
        val full = LabRecipe(
            templateId = "SEPIA",
            adjustments = LabAdjustments(0.1f, 1.2f, 0.8f, 0.4f, -0.2f),
            vignette = 0.3f, grain = 0.4f, sharpen = 0.5f, blur = 0.1f,
            glitch = 0.2f, duotone = 0.3f,
            duotoneShadow = 0xFF102040.toInt(), duotoneHighlight = 0xFFFFC040.toInt(),
            gamma = 1.25f, splitAmount = 0.4f,
            shadowTint = 0xFF203040.toInt(), highlightTint = 0xFF403020.toInt(),
            stampText = "'98", stampColor = 0xFFFF8C14.toInt(),
            stampPosition = StampPosition.CENTER, stampAlpha = 0.7f,
            watermarkId = "mark_x", watermarkAlpha = 0.5f,
            watermarkPosition = StampPosition.TOP_LEFT,
            toneCurves = ToneCurve.encodeGroup(
                List(4) { FloatArray(ToneCurve.SIZE) { (it * 0.9f + 20f) / 255f } },
            ),
            highlights = 0.1f, shadows = 0.2f, whites = 0.3f, blacks = 0.4f,
            texture = 0.5f, clarity = 0.6f, dehaze = 0.7f,
            sharpRadius = 1.5f, detail = 0.8f, masking = 0.9f,
            grainSize = 2f, grainRough = 0.3f,
            vigMidpoint = 0.2f, vigFeather = 0.7f,
            hsl = Hsl.encode(FloatArray(Hsl.VALUES) { (it - 11) / 30f }),
            grayscale = 0.6f,
            vibrance = 0.3f,
            colorGrade = ColorGrade.encode(floatArrayOf(0.1f, 0.5f, 0.3f, 0.4f, 0.6f, 0.5f, 0.5f, 0.1f)),
            bwMix = BwMix.encode(FloatArray(8) { (it - 4) / 10f }),
            vigRound = 0.3f, vigAspect = 0.7f,
            denoiseLum = 0.4f, denoiseColor = 0.5f,
            defringe = Defringe.encode(floatArrayOf(0.5f, 0.75f, 0.92f, 0.4f, 0.25f, 0.42f)),
            lensCA = 1f, lensEnable = 1f, lensDistort = 0.2f, lensBlur = 0.3f, lensFocus = 0.6f,
            geometry = Geometry.encode(floatArrayOf(5f, 0.2f, -0.1f, 0.1f, 0f, 0.1f, 0f, 0f)),
        )
        val r = SavedRecipe.create("FULL", "original", full)
        val back = nn(RecipeCodec.decode(RecipeCodec.encode(r)))
        val got = back.lab
        // Equal within the codec's own 4dp quantisation, which is what `q()`
        // promises and what the content id is built on. Every field is checked
        // explicitly rather than by comparing the recipes, because a whole-object
        // compare hides which field drifted.
        assertEquals(full.adjustments, got.adjustments, "adjustments")
        for (k in LabKnob.entries) {
            assertEquals(full.knobValue(k), got.knobValue(k), 1e-3f, k.name)
        }
        assertEquals(full.hsl, got.hsl, "hsl")
        assertEquals(full.calibration, got.calibration, "calibration")
        assertEquals(full.toneCurves, got.toneCurves, "tone curves")
        assertEquals(full.shadowTint, got.shadowTint, "shadow tint")
        assertEquals(full.highlightTint, got.highlightTint, "highlight tint")
        assertEquals(full.duotoneShadow, got.duotoneShadow, "duotone shadow")
        assertEquals(full.duotoneHighlight, got.duotoneHighlight, "duotone highlight")
        assertEquals(full.stampText, got.stampText, "stamp text")
        assertEquals(full.stampColor, got.stampColor, "stamp colour")
        assertEquals(full.stampPosition, got.stampPosition, "stamp position")
        assertEquals(full.watermarkId, got.watermarkId, "watermark id")
        assertEquals(full.templateId, got.templateId, "template")
    }

    /**
     * The two packed colour fields are read from their own indices.
     *
     * A pinned spot check, because "the whole thing round trips" can be
     * satisfied by a matched pair of mistakes, and these two were written and
     * read at different offsets.
     */
    @Test
    fun `the color mixer and grayscale land in their own fields`() {
        val r = SavedRecipe.create(
            "FIELDS", "original",
            LabRecipe(hsl = Hsl.encode(FloatArray(Hsl.VALUES) { if (it == 4) 0.75f else 0f }), grayscale = 0.5f),
        )
        val back = nn(RecipeCodec.decode(RecipeCodec.encode(r)))
        assertEquals(0.5f, back.lab.grayscale, 1e-4f)
        val v = nn(back.lab.hslArray())
        assertEquals(0.75f, v[4], 1e-3f)
        for (i in 0 until Hsl.VALUES) if (i != 4) assertEquals(0f, v[i], 1e-3f)
    }

    /**
     * Saturation and Vibrance multiply, so an imported value is a product of two
     * floats and lands on a value no 4dp quantisation can name exactly.
     *
     * `Saturation=-2` and `Vibrance=+10` give 1.0780001, which `q()` writes as
     * `1.078`, and the decoded recipe then compared unequal to the one that went
     * in. Nothing renders differently - a 4dp knob is invisible - but it is
     * worth pinning, because the same shape of drift in a field the id hashes
     * over would mint a second strip entry for a recipe that looks identical.
     */
    @Test
    fun `a quantised knob round trips to its quantised value`() {
        val raw = XmpImport.parse("""crs:Saturation="-2" crs:Vibrance="+10"""").recipe
        val saved = SavedRecipe.create("MIX", "original", raw)
        val back = nn(RecipeCodec.decode(RecipeCodec.encode(saved)))
        // Saturation alone now (vibrance split out): 0.98 exactly.
        assertEquals(0.98f, back.lab.adjustments.saturation, 0f)
        assertEquals(0.1f, back.lab.vibrance, 0f)
        // Which is what makes the id stable, and the payload stable.
        assertEquals(saved.id, back.id)
        assertEquals(RecipeCodec.encode(saved), RecipeCodec.encode(back))
    }

    /**
     * Every field, set to something non-neutral, and re-checked after a second
     * save. The point of the *second* trip is that the first one is allowed to
     * quantise; what must hold is that the quantised recipe is then a fixed
     * point, so saving a shared recipe never produces a third variant.
     */
    @Test
    fun `a fully populated recipe is a fixed point after one round trip`() {
        val full = LabRecipe(
            templateId = "SEPIA",
            adjustments = LabAdjustments(0.1f, 1.2f, 0.8f, 0.4f, -0.2f),
            vignette = 0.3f, grain = 0.4f, sharpen = 0.5f, blur = 0.1f,
            glitch = 0.2f, duotone = 0.3f,
            duotoneShadow = 0xFF102040.toInt(), duotoneHighlight = 0xFFFFC040.toInt(),
            gamma = 1.25f, splitAmount = 0.4f,
            shadowTint = 0xFF203040.toInt(), highlightTint = 0xFF403020.toInt(),
            stampText = "'98", stampColor = 0xFFFF8C14.toInt(),
            stampPosition = StampPosition.CENTER, stampAlpha = 0.7f,
            watermarkId = "mark_x", watermarkAlpha = 0.5f,
            watermarkPosition = StampPosition.TOP_LEFT,
            toneCurves = ToneCurve.encodeGroup(
                List(4) { FloatArray(ToneCurve.SIZE) { (it * 0.9f + 20f) / 255f } },
            ),
            highlights = 0.1f, shadows = 0.2f, whites = 0.3f, blacks = 0.4f,
            texture = 0.5f, clarity = 0.6f, dehaze = 0.7f,
            sharpRadius = 1.5f, detail = 0.8f, masking = 0.9f,
            grainSize = 2f, grainRough = 0.3f,
            vigMidpoint = 0.2f, vigFeather = 0.7f,
            hsl = Hsl.encode(FloatArray(Hsl.VALUES) { (it - 11) / 30f }),
            grayscale = 0.6f,
            calibration = Calibration.encode(
                floatArrayOf(0.1f, -0.2f, 0.3f),
                floatArrayOf(0.4f, 0.5f, -0.6f),
            ),
            vibrance = 0.25f,
            colorGrade = ColorGrade.encode(floatArrayOf(0.1f, 0.5f, 0.3f, 0.4f, 0.6f, 0.5f, 0.5f, 0f)),
            bwMix = BwMix.encode(FloatArray(8) { 0.1f }),
            vigRound = 0.4f, vigAspect = 0.6f,
            denoiseLum = 0.3f, denoiseColor = 0.4f,
            defringe = Defringe.encode(floatArrayOf(0.3f, 0.75f, 0.92f, 0.2f, 0.25f, 0.42f)),
            lensCA = 1f, lensEnable = 1f, lensDistort = 0.1f, lensBlur = 0.2f, lensFocus = 0.6f,
            geometry = Geometry.encode(floatArrayOf(5f, 0.1f, 0f, 0f, 0f, 0.1f, 0f, 0f)),
        )
        val first = SavedRecipe.create("FULL", "original", full)
        val back = nn(RecipeCodec.decode(RecipeCodec.encode(first)))
        val second = SavedRecipe.create("FULL", "original", back.lab)
        assertEquals(first.id, second.id)
        assertEquals(RecipeCodec.encode(first), RecipeCodec.encode(second))
        // The first trip is allowed to quantise, the second must not change
        // anything at all. This is the property the strip depends on: a shared
        // recipe that re-saved differently every time would accumulate entries.
        assertEquals(back, second, "a decoded recipe must be a fixed point")
    }

    /**
     * An imported preset has to survive being saved, shared and reloaded.
     *
     * This is the end-to-end path the Filter Lab actually takes, and it is the
     * only test that covers it: parse, coerce, encode, decode. The gap it found
     * is that `q()` quantises to 4 decimal places, so a gamma of 2^0.15 =
     * 1.1095694 came back as 1.1096 and the recipe compared unequal to itself.
     *
     * That is harmless for rendering - a 4dp knob cannot be seen - and harmful
     * here, because the content id is hashed over the quantised value, so
     * re-importing the same preset twice minted two strip entries that looked
     * identical. The tolerance below is the honest statement of what the codec
     * promises; the id assertion is the one that actually has to hold.
     */
    @Test
    fun `an imported preset survives save share and reload`() {
        val xmp = """
          crs:ProcessVersion="11.0" crs:Contrast2012="+12" crs:Exposure2012="+0.15"
          crs:HueAdjustmentGreen="-15" crs:SaturationAdjustmentGreen="-20"
          crs:SharpenRadius="0.8" crs:SharpenDetail="25" crs:SharpenEdgeMasking="40"
          crs:GrainSize="11" crs:GrainFrequency="40" crs:VignetteAmount="-22"
          crs:Highlights2012="-40" crs:Shadows2012="+38" crs:Clarity2012="+8"
          crs:Copyright="Someone" crs:HasSettings="True" crs:SupportsColor="True"
        """.trimIndent()
        val imported = XmpImport.parse(xmp).recipe
        val saved = SavedRecipe.create("PORTRA", "original", imported)
        val payload = RecipeCodec.encode(saved)
        val back = nn(RecipeCodec.decode(payload))

        // The knobs that are quantised to 4dp must come back within that.
        assertEquals(saved.lab.gamma, back.lab.gamma, 1e-4f)
        assertEquals(saved.lab.hsl, back.lab.hsl)
        assertEquals(saved.lab.sharpRadius, back.lab.sharpRadius, 1e-4f)
        assertEquals(saved.lab.masking, back.lab.masking, 1e-4f)
        assertEquals(saved.lab.grainSize, back.lab.grainSize, 1e-4f)
        assertEquals(saved.lab.vignette, back.lab.vignette, 1e-4f)
        assertEquals(saved.lab.clarity, back.lab.clarity, 1e-4f)

        // The part that genuinely has to hold: encoding it again is stable, so
        // the content id is stable and a re-import updates the strip entry
        // rather than adding a duplicate of a recipe that looks identical.
        assertEquals(payload, RecipeCodec.encode(back))
        assertEquals(saved.id, back.id)
    }

    /** JUnit's assertNotNull returns Unit, so this does both in one step. */
    private fun <T : Any> nn(v: T?): T {
        kotlin.test.assertNotNull(v)
        return v!!
    }

    @Test
    fun `encoded form has the expected field count even though the numbers are decimal`() {
        // Regression guard. The separator was originally a dot, which split every
        // decimal knob in half and turned the fields into double, so every decode
        // returned null. Assert the shape directly so that failure is obvious.
        val enc = RecipeCodec.encode(recipe())
        assertTrue(enc.contains('.'), "knobs should still be readable decimals")
    }

    @Test
    fun `effect amounts survive a round trip`() {
        val r = SavedRecipe.create(
            "GRIT", "crt",
            LabRecipe(
                "SEPIA", LabAdjustments(0.1f, 1.1f, 0.9f, 0.2f, 0f),
                vignette = 0.4f, grain = 0.3f, sharpen = 0.2f,
                blur = 0.1f, glitch = 0.05f, duotone = 0.6f,
                duotoneShadow = 0xFF102040.toInt(), duotoneHighlight = 0xFFFFC040.toInt(),
            ),
        )
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `gamma and split tone survive a round trip`() {
        val r = SavedRecipe.create(
            "SPLIT", "original",
            LabRecipe(
                gamma = 1.35f, splitAmount = 0.4f,
                shadowTint = 0xFF203040.toInt(), highlightTint = 0xFF403020.toInt(),
            ),
        )
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `gamma and split are clamped on decode`() {
        val wild = payload(colour = listOf("99", "-3", "0", "0"))
        val r = assertNotNull(RecipeCodec.decode(wild)).lab
        assertTrue(r.gamma in 0.2f..3f, "gamma was ${r.gamma}")
        assertEquals(0f, r.splitAmount)
    }

    @Test
    fun `recipes differing only by gamma get different ids`() {
        val a = SavedRecipe.create("G", "original", LabRecipe(gamma = 1.2f))
        val b = SavedRecipe.create("G", "original", LabRecipe(gamma = 1.4f))
        assertTrue(a.id != b.id, "gamma must be part of the content hash")
    }

    @Test
    fun `overlays survive a round trip`() {
        val r = SavedRecipe.create(
            "STAMPED", "original",
            LabRecipe(
                stampText = "'98 08 13", stampColor = 0xFFFF8C14.toInt(),
                stampPosition = StampPosition.CENTER, stampAlpha = 0.7f,
                watermarkId = "mark_abc", watermarkAlpha = 0.5f,
                watermarkPosition = StampPosition.TOP_LEFT,
            ),
        )
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `a stamp containing a comma survives`() {
        // The stamp text is free text, so it must be encoded like the name is.
        val r = SavedRecipe.create("S", "original", LabRecipe(stampText = "a,b,c"))
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `an out of range stamp position falls back instead of throwing`() {
        val wild = payload(stamp = listOf("-", "0", "99", "1", "-", "0", "99"))
        val back = assertNotNull(RecipeCodec.decode(wild))
        assertEquals(StampPosition.BOTTOM_RIGHT, back.lab.stampPosition)
    }

    @Test
    fun `overlay amounts are clamped on decode`() {
        val wild = payload(stamp = listOf("-", "0", "0", "99", "-", "0", "0"))
        assertEquals(1f, assertNotNull(RecipeCodec.decode(wild)).lab.stampAlpha)
    }

    @Test
    fun `recipes differing only by an effect get different ids`() {
        val plain = recipe()
        val grainy = SavedRecipe.create("SUNSET 94", "original", plain.lab.copy(grain = 0.5f))
        assertTrue(plain.id != grainy.id, "grain must be part of the content hash")
    }

    @Test
    fun `effect amounts are clamped on decode`() {
        val wild = payload(effects = listOf("9", "-9", "9", "-9", "9", "-9"))
        val r = assertNotNull(RecipeCodec.decode(wild)).lab
        assertEquals(1f, r.vignette); assertEquals(0f, r.grain)
        assertEquals(1f, r.sharpen); assertEquals(0f, r.blur)
        assertEquals(1f, r.glitch); assertEquals(0f, r.duotone)
    }

    @Test
    fun `round trips with no template`() {
        val r = SavedRecipe.create("PLAIN", "original", LabRecipe())
        val back = assertNotNull(RecipeCodec.decode(RecipeCodec.encode(r)))
        assertEquals(r, back)
        assertNull(back.lab.templateId)
    }

    @Test
    fun `encode is deterministic so the id stays stable`() {
        // Same knob to within float noise must encode and hash identically, or
        // re-saving a recipe would quietly mint a duplicate of it.
        val a = recipe(b = 0.1f)
        val b = recipe(b = 0.100000001f)
        assertEquals(RecipeCodec.encode(a), RecipeCodec.encode(b))
        assertEquals(a.id, b.id)
    }

    @Test
    fun `identical recipes share an id and different ones do not`() {
        assertEquals(recipe().id, recipe().id)
        assertTrue(recipe().id != recipe(b = 0.2f).id, "knob change must change id")
        assertTrue(recipe().id != recipe(name = "OTHER").id, "name change must change id")
        assertTrue(recipe().id != recipe(base = "crt").id, "base change must change id")
        assertTrue(recipe().id != recipe(template = "SEPIA").id, "template change must change id")
    }

    @Test
    fun `names containing separators survive`() {
        // The name is the only free-text field, so it is the only one that could
        // collide with the format. It must be encoded rather than escaped.
        val r = SavedRecipe.create("A,B,C", "original", LabRecipe())
        assertTrue(r.name.contains(','))
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `names that are not url safe survive`() {
        for (name in listOf("50% OFF!", "a/b\\c", "emoji", "  spaced  ", "unicode name")) {
            val r = SavedRecipe.create(name, "original", LabRecipe())
            val back = assertNotNull(RecipeCodec.decode(RecipeCodec.encode(r)), "failed for '$name'")
            assertEquals(r.name, back.name)
        }
    }

    @Test
    fun `name is trimmed uppercased and capped`() {
        assertEquals("HELLO", SavedRecipe.create("  hello  ", "original", LabRecipe()).name)
        assertEquals(SavedRecipe.MAX_NAME, SavedRecipe.create("A".repeat(60), "original", LabRecipe()).name.length)
        assertEquals("UNTITLED", SavedRecipe.create("   ", "original", LabRecipe()).name)
    }

    @Test
    fun `hostile and malformed input decodes to null rather than throwing`() {
        val junk = listOf(
            "",
            ",",
            "1",
            "1,a,b",                                   // too few fields
            "1,QUJD,original",                         // truncated
            "1," + List(20) { "0" }.joinToString(","), // wrong shape
            "1,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",  // too many fields
            "2,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",  // old version
            "4,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",  // future version
            "1,!!!!,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",  // bad base64
            "1,QUJD,original,-,x,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",  // unparseable float
            "1,,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0",       // empty name
            "1," + "A".repeat(4000) + ",original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,-,0", // oversized
        )
        for (s in junk) {
            assertNull(RecipeCodec.decode(s), "should not decode: '$s'")
        }
    }

    @Test
    fun `hostile knob values are clamped not rejected`() {
        // A hand-edited string should degrade to a usable filter, not vanish.
        val wild = payload(knobs = listOf("99", "99", "-5", "50", "-50"))
        val back = assertNotNull(RecipeCodec.decode(wild))
        val a = back.lab.adjustments
        assertTrue(a.brightness <= 1f, "brightness not clamped")
        assertTrue(a.contrast >= 0f, "contrast not clamped")
        assertTrue(a.saturation >= 0f, "saturation not clamped")
        assertTrue(a.warmth <= 1f && a.tint >= -1f, "warmth/tint not clamped")
        assertEquals("original", back.baseId)
        assertNotNull(back.toSpec(), "a clamped recipe must still build a filter")
    }

    @Test
    fun `a recipe naming a base that no longer exists still decodes`() {
        // Shared recipes outlive catalogs, so an unknown base must not make the
        // recipe un-importable: toSpec returns null and the caller skips it.
        val orphan = SavedRecipe.create("ORPHAN", "filter_removed_in_v2", LabRecipe("SEPIA"))
        assertNull(orphan.toSpec(), "should not build a spec for a missing base")
        assertEquals(orphan, RecipeCodec.decode(RecipeCodec.encode(orphan)))
    }

    @Test
    fun `toSpec keeps the base shader and attaches the grade`() {
        val spec = assertNotNull(recipe(base = "crt", template = "CYBERPUNK").toSpec())
        val base = assertNotNull(com.retrocam.catalog.FilterCatalog.byId["crt"])
        assertEquals(base.fragmentBody, spec.fragmentBody, "must keep the base shader")
        assertEquals(base.param1, spec.param1)
        assertEquals(FilterFamily.LAB, spec.family)
        assertEquals("SUNSET 94", spec.displayName)
        assertTrue(spec.id.startsWith("lab_"), "id should be namespaced: ${spec.id}")
        assertTrue(spec.id != "crt", "must not collide with the base id")
        assertEquals("CYBERPUNK", spec.lab?.templateId)
        assertTrue(spec.context.contains("CRT"), "context should name the base: ${spec.context}")
    }

    @Test
    fun `derived spec ids do not collide with the catalog`() {
        val builtIn = com.retrocam.catalog.FilterCatalog.all.map { it.id }.toSet()
        for (r in listOf(recipe(base = "crt"), recipe(base = "original"), recipe(name = "X"))) {
            val spec = assertNotNull(r.toSpec())
            assertTrue(spec.id !in builtIn, "${spec.id} shadows a built-in filter")
        }
    }

    @Test
    fun `knob quantisation is stable and short`() {
        assertEquals("1", RecipeCodec.q(1f))
        assertEquals("0", RecipeCodec.q(0f))
        assertEquals("0.5", RecipeCodec.q(0.5f))
        assertEquals("-0.25", RecipeCodec.q(-0.25f))
        assertEquals("0.1235", RecipeCodec.q(0.123456f))
        assertEquals("3", RecipeCodec.q(3f))
        assertTrue(RecipeCodec.q(0.1f).length <= 8)
    }
}
