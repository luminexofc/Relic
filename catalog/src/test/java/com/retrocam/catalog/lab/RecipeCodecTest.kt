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

    @Test
    fun `round trips`() {
        val r = recipe()
        assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
    }

    @Test
    fun `encoded form has the expected field count even though the numbers are decimal`() {
        // Regression guard. The separator was originally a dot, which split every
        // decimal knob in half and turned the fields into double, so every decode
        // returned null. Assert the shape directly so that failure is obvious.
        val enc = RecipeCodec.encode(recipe())
        assertEquals(17, enc.split(',').size, "bad field count in '$enc'")
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
    fun `recipes differing only by an effect get different ids`() {
        val plain = recipe()
        val grainy = SavedRecipe.create("SUNSET 94", "original", plain.lab.copy(grain = 0.5f))
        assertTrue(plain.id != grainy.id, "grain must be part of the content hash")
    }

    @Test
    fun `effect amounts are clamped on decode`() {
        val wild = "2,${b64("X")},original,-,0,1,1,0,0,9,-9,9,-9,9,-9,-1,999999999"
        val back = assertNotNull(RecipeCodec.decode(wild))
        val r = back.lab
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
            "1,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,9", // too many fields
            "1,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0",   // wrong version (v1)
            "3,QUJD,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0",   // future version
            "1,!!!!,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0",   // bad base64
            "1,QUJD,original,-,x,1,1,0,0,0,0,0,0,0,0,0,0,0",   // unparseable float
            "1,,original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0",     // empty name
            "1," + "A".repeat(4000) + ",original,-,0,1,1,0,0,0,0,0,0,0,0,0,0,0", // oversized
        )
        for (s in junk) {
            assertNull(RecipeCodec.decode(s), "should not decode: '$s'")
        }
    }

    @Test
    fun `hostile knob values are clamped not rejected`() {
        // A hand-edited string should degrade to a usable filter, not vanish.
        val wild = "2,${b64("X")},original,-,99,99,-5,50,-50,99,-99,99,-99,99,-99,0,255"
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
