package com.retrocam.catalog.lab

import com.retrocam.catalog.FilterCatalog
import com.retrocam.catalog.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabStageTest {

    // ---- mask geometry ----

    @Test
    fun `rect keeps x0 below x1 even when width is negative`() {
        val r = LabMask(shape = MaskShape.RECT, x = 0.8f, y = 0.1f, width = -0.4f, height = 0.2f).rect()
        assertEquals(0.4f, r[0], 1e-5f)
        assertEquals(0.1f, r[1], 1e-5f)
        assertEquals(0.8f, r[2], 1e-5f)
        assertEquals(0.3f, r[3], 1e-5f)
    }

    @Test
    fun `rect is clamped into the frame`() {
        val r = LabMask(x = -0.3f, y = -0.2f, width = 2f, height = 2f).rect()
        assertEquals(0f, r[0], 0f)
        assertEquals(0f, r[1], 0f)
        assertEquals(1f, r[2], 0f)
        assertEquals(1f, r[3], 0f)
    }

    @Test
    fun `the default mask is the whole frame`() {
        assertTrue(LabMask().isFull)
        assertTrue(LabMask.FULL.isFull)
    }

    /**
     * A full-frame rect with no feather is the same thing as no mask at all, so
     * it must not be encoded as one — otherwise a stage the user drew over the
     * entire image would still cost three uniform uploads per pass.
     */
    @Test
    fun `a full-frame rect with no feather counts as unmasked`() {
        assertTrue(LabMask(shape = MaskShape.RECT, x = 0f, y = 0f, width = 1f, height = 1f).isFull)
        assertFalse(LabMask(shape = MaskShape.ELLIPSE, x = 0f, y = 0f, width = 1f, height = 1f).isFull)
        assertFalse(LabMask(shape = MaskShape.RECT, x = 0f, y = 0f, width = 1f, height = 1f, feather = 0.2f).isFull)
    }

    /**
     * A zero-extent mask would make the feather term divide by zero in the
     * shader, so it has to collapse to full frame rather than render a black
     * rectangle.
     */
    @Test
    fun `a zero or negative extent collapses to full frame`() {
        assertTrue(LabMask.coerce(LabMask(shape = MaskShape.RECT, width = 0f)).isFull)
        assertTrue(LabMask.coerce(LabMask(shape = MaskShape.ELLIPSE, height = -0.5f)).isFull)
    }

    @Test
    fun `coerce pulls a mask back inside the frame`() {
        val m = LabMask.coerce(LabMask(shape = MaskShape.RECT, x = -1f, y = 2f, width = 0.5f, height = 0.5f, feather = 9f))
        assertEquals(0f, m.x, 0f)
        // y is clamped on its own; rect() is what turns it into an edge.
        assertEquals(1f, m.y, 0f)
        assertEquals(1f, m.feather, 0f)
    }

    // ---- shader invariants ----

    /**
     * The footer multiplies every filter's intensity by `maskFactor`, including
     * all 41 built-ins that have nothing to do with the Lab. If the default
     * returned anything but exactly 1.0, every one of them would be dimmed by a
     * hair on every frame. This is the single most dangerous line in the shader,
     * so it is asserted rather than trusted.
     */
    @Test
    fun `an unmasked stage multiplies by exactly one`() {
        assertTrue(
            "maskFactor must return a literal 1.0 for shape 0",
            Shaders.HEADER.contains("if (u_maskShape < 0.5) return 1.0;"),
        )
        assertTrue(
            "the footer must scale intensity by the mask",
            Shaders.FOOTER.contains("u_intensity * m"),
        )
    }

    @Test
    fun `every shader gets the mask uniforms from the shared header`() {
        for (name in listOf("u_maskRect", "u_maskShape", "u_maskFeather")) {
            assertTrue("$name missing from HEADER", Shaders.HEADER.contains("uniform") && Shaders.HEADER.contains(name))
        }
    }

    // ---- primitives ----

    @Test
    fun `every control points at a real parameter inside its own range`() {
        assertEquals(emptyList<String>(), LabPrimitives.validate())
    }

    /**
     * A stage naming an id the catalog does not have is dropped by the renderer
     * at draw time, which looks like a stage that silently does nothing. Catching
     * it here instead makes the table impossible to get out of sync.
     */
    @Test
    fun `every primitive resolves to a catalog filter`() {
        for (p in LabPrimitives.all) {
            assertNotNull("no catalog filter for primitive ${p.id}", FilterCatalog.byId[p.id])
        }
    }

    @Test
    fun `original is not offered as a stage`() {
        // It is the identity shader: a stage using it would spend a chain slot to
        // change nothing.
        assertEquals(null, LabPrimitives.byId("original"))
    }

    @Test
    fun `trails is listed but not chainable`() {
        val t = LabPrimitives.byId("trails")
        nn(t)
        assertFalse(t!!.chainable)
        assertTrue(LabPrimitives.chainable.none { it.id == "trails" })
    }

    @Test
    fun `chainable covers the catalog minus original and trails`() {
        val expected = FilterCatalog.all.map { it.id }.toSet() - "original"
        assertEquals(expected, LabPrimitives.all.map { it.id }.toSet())
    }

    @Test
    fun `neutral values are the catalog defaults so a fresh stage looks plain`() {
        for (p in LabPrimitives.all) {
            val spec = FilterCatalog.byId.getValue(p.id)
            val d1 = p.controls.firstOrNull { it.param == 1 }?.neutral
            val d2 = p.controls.firstOrNull { it.param == 2 }?.neutral
            if (d1 != null) {
                assertEquals("${p.id} param1 neutral", spec.param1, d1, 1e-4f)
            }
            if (d2 != null) {
                assertEquals("${p.id} param2 neutral", spec.param2, d2, 1e-4f)
            }
        }
    }

    // ---- stage clamping ----

    @Test
    fun `a stage list is capped at four`() {
        val many = (1..9).map { LabStage("vintage", amount = 0.5f) }
        assertEquals(LabRecipe.MAX_STAGES, LabRecipe(stages = many).stagesClamped().size)
    }

    @Test
    fun `stage amount is clamped and the mask is coerced`() {
        val s = LabStage("vintage", amount = 7f, mask = LabMask(shape = MaskShape.RECT, width = -1f))
        val c = LabRecipe(stages = listOf(s)).stagesClamped().single()
        assertEquals(1f, c.amount, 0f)
        assertTrue(c.mask.isFull)
    }

    // ---- codec ----

    @Test
    fun `stages survive a round trip`() {
        val r = SavedRecipe.create(
            "STAGED", "original",
            LabRecipe(
                stages = listOf(
                    LabStage("ascii", amount = 0.8f, params = mapOf("1" to 12f)),
                    LabStage(
                        "swirl", amount = 0.4f, params = mapOf("1" to 2.5f),
                        mask = LabMask(MaskShape.ELLIPSE, 0.2f, 0.3f, 0.5f, 0.4f, 0.15f),
                    ),
                ),
            ),
        )
        val back = nn(RecipeCodec.decode(RecipeCodec.encode(r)))
        assertEquals(r, back)
    }

    @Test
    fun `masks survive a round trip including the feather`() {
        val st = LabStage("vintage", mask = LabMask(MaskShape.BAND_H, 0f, 0.45f, 1f, 0.1f, 0.3f))
        val r = SavedRecipe.create("M", "original", LabRecipe(stages = listOf(st)))
        val back = RecipeCodec.decode(RecipeCodec.encode(r))
        assertEquals(st.mask, nn(back).lab.stagesClamped().single().mask)
    }

    @Test
    fun `stage params decode back onto the right parameter numbers`() {
        // gameboy's controls are param 1 and param 2, but a filter with a gap
        // would break an encoder that assumed 1,2,3 in order.
        val st = LabStage("gameboy", amount = 0.6f, params = mapOf("2" to 0.35f))
        val r = SavedRecipe.create("G", "original", LabRecipe(stages = listOf(st)))
        val back = RecipeCodec.decode(RecipeCodec.encode(r))
        assertEquals(mapOf("2" to 0.35f), nn(back).lab.stagesClamped().single().params)
    }

    @Test
    fun `a stage with no params set encodes compactly and stays unset`() {
        val r = SavedRecipe.create("P", "original", LabRecipe(stages = listOf(LabStage("vintage"))))
        val enc = RecipeCodec.encode(r)
        // id, amount, shape, x, y, w, h, feather, params -> 9 fields, the last empty
        val tok = enc.split(",")[31]
        assertEquals(9, tok.split("~").size)
        assertEquals("", tok.split("~")[8])
        assertEquals(LabStage("vintage"), nn(RecipeCodec.decode(enc)).lab.stagesClamped().single())
    }

    @Test
    fun `recipes differing only by stage order get different ids`() {
        val a = SavedRecipe.create(
            "S", "original",
            LabRecipe(stages = listOf(LabStage("vintage"), LabStage("ascii"))),
        )
        val b = SavedRecipe.create(
            "S", "original",
            LabRecipe(stages = listOf(LabStage("ascii"), LabStage("vintage"))),
        )
        // Order is the whole point of a chain, so it has to be in the hash.
        assertTrue(a.id != b.id)
    }

    @Test
    fun `recipes differing only by mask get different ids`() {
        val a = SavedRecipe.create("M", "original", LabRecipe(stages = listOf(LabStage("vintage"))))
        val b = SavedRecipe.create(
            "M", "original",
            LabRecipe(stages = listOf(LabStage("vintage", mask = LabMask(MaskShape.ELLIPSE, 0.2f, 0.2f, 0.5f, 0.5f, 0.1f)))),
        )
        assertTrue(a.id != b.id)
    }

    @Test
    fun `an over-long stage list is truncated on decode`() {
        // A hand-edited or hostile payload must not be able to force nine passes.
        val tok = (1..9).joinToString("!") { "vintage~0.5~0~0~0~1~1~0~" }
        val wild = tok
        val r = nn(RecipeCodec.decode(payload(wild)))
        assertEquals(LabRecipe.MAX_STAGES, r.lab.stagesClamped().size)
    }

    @Test
    fun `a stage naming an unknown filter is dropped but the rest of the recipe survives`() {
        val tok = "notafilter~0.5~0~0~0~1~1~0~" + "!" + "vintage~0.5~0~0~0~1~1~0~"
        val r = nn(RecipeCodec.decode(payload(tok)))
        assertEquals(listOf("vintage"), r.lab.stagesClamped().map { it.primitiveId })
    }

    @Test
    fun `an unchainable stage is dropped on decode`() {
        val tok = "trails~0.5~0~0~0~1~1~0~"
        val r = nn(RecipeCodec.decode(payload(tok)))
        // trails needs the feedback buffer, which mid-chain holds the previous
        // stage's output rather than the last frame.
        assertEquals(emptyList<LabStage>(), r.lab.stagesClamped())
    }

    @Test
    fun `a truncated stage token is dropped rather than failing the payload`() {
        val tok = "grain~0.5"  // missing fields
        val r = nn(RecipeCodec.decode(payload(tok)))
        assertEquals(emptyList<LabStage>(), r.lab.stagesClamped())
    }

    @Test
    fun `a recipe with no stages still encodes to the dash placeholder`() {
        val enc = RecipeCodec.encode(SavedRecipe.create("N", "original", LabRecipe()))
        assertEquals("-", enc.split(",")[32])
    }

    /**
     * JUnit's assertNotNull returns Unit, so there is no way to get the value
     * back out of it. This does both in one step.
     */
    private fun <T : Any> nn(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    /**
     * A syntactically valid payload whose only interesting field is [stageField],
     * built field by field. String surgery on a 49-field format is how the first
     * version of this helper ended up emitting 62 fields and failing every test
     * for the wrong reason.
     */
    private fun payload(stageField: String): String {
        // Sized from a real encode rather than a hand-typed number, which is
        // what §4.7 and the second version of this helper both got wrong.
        val f = MutableList(RecipeCodec.encode(SavedRecipe.create("T", "original", LabRecipe())).split(",").size) { "0" }
        f[0] = RecipeCodec.VERSION.toString()
        f[1] = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("T".toByteArray())
        f[2] = "original"
        f[3] = "-"    // templateId
        f[5] = "1"   // contrast
        f[6] = "1"   // saturation
        // These three are decoded, not parsed as numbers: a bare "0" here makes
        // the base64 stamp decoder throw, and decode swallows it into a null.
        f[17] = "-"   // lutId
        f[23] = "-"   // stampText
        f[27] = "-"   // watermarkId
        f[31] = stageField
        f[32] = "-"   // tone curve
        // sharpRadius, grainSize default above 0 and vigMid/vigFeather to 0.5.
        f[40] = "1"
        f[43] = "1"
        f[45] = "0.5"
        f[46] = "0.5"
        // 33..47 are the tone, local and operator controls.
        // 40..46 have non-zero defaults, so a zero there must still decode.
        return f.joinToString(",")
    }

}
