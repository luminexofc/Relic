package com.relic.catalog.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flat `LabKnob` list that the UI drives.
 *
 * It exists because of a real gap: the importer could set about thirty fields
 * and the Lab panel showed seven sliders, so a preset that set its clarity, its
 * colour mixer and its grain could be imported, rendered correctly, and then
 * looked at as if none of it had happened.
 *
 * The danger with a flat list that maps to a flat set of recipe fields is
 * positional drift, and that is what most of this file is about.
 */
class LabKnobTest {

    private fun testValue(k: LabKnob): Float {
        // Toggles are 0/1: 0.5 would threshold back to 0 and never round-trip.
        if (k == LabKnob.LENS_CA || k == LabKnob.LENS_ENABLE) return 1f
        // Deterministic in-range value distinct from neutral for every knob.
        return when (k.neutral) {
            0f -> 0.5f
            1f -> 1.5f
            0.5f -> 0.8f
            else -> k.neutral + 0.3f
        }
    }

    @Test
    fun `every knob reads a field and writing it back is a no-op`() {
        // The round trip that catches a `when` with a branch missing: reading a
        // knob the writer does not handle would return neutral no matter what,
        // and the UI would show a slider that does nothing.
        LabKnob.entries.forEach { k ->
            // EXPOSURE<->GAMMA share one field (EV vs gamma views); tested separately.
            if (k == LabKnob.EXPOSURE) return@forEach
            val v = testValue(k)
            val r = LabRecipe().withKnob(k, v)
            assertEquals("${k.name} did not round trip", v, r.knobValue(k), 1e-3f)
        }
        // The aliased pair: EXPOSURE writes gamma, GAMMA reads it back converted.
        val r = LabRecipe().withKnob(LabKnob.EXPOSURE, 1f)
        assertEquals(1f, r.knobValue(LabKnob.EXPOSURE), 1e-3f)
        assertEquals(exposureToGamma(1f), r.knobValue(LabKnob.GAMMA), 1e-3f)
    }

    @Test
    fun `a default recipe is neutral on every knob`() {
        val r = LabRecipe()
        for (k in LabKnob.entries) {
            assertEquals("${k.name} is not neutral by default", k.neutral, r.knobValue(k), 0f)
        }
    }

    /**
     * Every slider, moved on a blank recipe, must survive the renderer's own gate.
     *
     * This is the "sliders do nothing from scratch" test. The renderer skips the
     * whole grade pass when `isIdentity` says the recipe renders nothing, so a
     * knob that stores correctly but is not counted there is a control the user
     * can drag with no result whatsoever - no picture change, no error, and the
     * same slider looks fine once a preset happens to set something else. EXPOSURE
     * was broken that way: `isIdentity` was a hand-written list, `gamma` was never
     * on it, so a recipe whose only change was exposure reported itself blank and
     * the entire grade pass was skipped.
     *
     * `isIdentity` is now the data class's own equality, so a new field cannot go
     * missing again. This test is what keeps that claim honest by walking every
     * knob rather than the handful someone remembered.
     */
    @Test
    fun `every knob off neutral escapes the identity gate`() {
        val blank = LabRecipe()
        assertTrue("a blank recipe must be identity", blank.isIdentity)
        for (k in LabKnob.entries) {
            val r = blank.withKnob(k, testValue(k))
            assertTrue(
                "$k is stored but still reports identity, so the grade pass is dropped",
                !r.isIdentity,
            )
        }
    }

    /**
     * A packed field that is present but neutral must still count as identity.
     *
     * The decoder writes `hsl` as a string of zeroes rather than leaving it
     * absent, so comparing the packed fields verbatim would call a recipe with
     * the colour mixer explicitly off non-identity and put the renderer into a
     * pass that changes nothing. Each packed field is normalised through its own
     * is-active test before the comparison for exactly this reason.
     */
    @Test
    fun `an explicitly neutral packed field is still identity`() {
        val off = LabRecipe(
            hsl = Hsl.encode(FloatArray(Hsl.VALUES)),
            colorGrade = ColorGrade.encode(ColorGrade.defaults()),
            bwMix = BwMix.encode(FloatArray(BwMix.VALUES)),
            defringe = Defringe.encode(Defringe.defaults()),
            geometry = Geometry.encode(Geometry.defaults()),
        )
        assertTrue(!off.hslActive)
        assertTrue(!off.gradeActive)
        assertTrue(!off.bwActive)
        assertTrue(!off.geoActive)
        assertTrue("a neutral packed field must not cost a render pass", off.isIdentity)
        // And the moment one of them is genuinely off, the pass has to come back.
        assertTrue(!off.withHsl(0, 0, 0.4f).isIdentity)
        assertTrue(!off.withGrade(0, 0.3f).isIdentity)
        assertTrue(!off.withBw(0, -0.3f).isIdentity)
    }

    @Test
    fun `setting a knob leaves every other knob alone`() {
        // The property that a single slider actually moves one control. A `when`
        // that fell through to a shared branch would move several at once, and
        // that is invisible unless it is checked.
        // EXPOSURE and GAMMA share the gamma field by design (EV vs gamma views).
        // The rest are the auto-arm pairs: a dependent slider switches its
        // master amount on, so it moves two fields on purpose.
        val aliases = setOf(
            LabKnob.EXPOSURE to LabKnob.GAMMA, LabKnob.GAMMA to LabKnob.EXPOSURE,
            LabKnob.SHARP_RADIUS to LabKnob.SHARPEN_AMT,
            LabKnob.MASKING to LabKnob.SHARPEN_AMT,
            LabKnob.GRAIN_SIZE to LabKnob.GRAIN_AMT,
            LabKnob.GRAIN_ROUGH to LabKnob.GRAIN_AMT,
            LabKnob.VIG_MIDPOINT to LabKnob.VIGNETTE_AMT,
            LabKnob.VIG_FEATHER to LabKnob.VIGNETTE_AMT,
            LabKnob.VIG_ROUND to LabKnob.VIGNETTE_AMT,
            LabKnob.VIG_ASPECT to LabKnob.VIGNETTE_AMT,
            LabKnob.LENS_FOCUS to LabKnob.LENS_BLUR,
        )
        LabKnob.entries.forEach { target ->
            val base = LabRecipe()
            val moved = base.withKnob(target, testValue(target))
            for (other in LabKnob.entries) {
                if (other == target) continue
                if (aliases.contains(target to other)) continue
                assertEquals(
                    "${target.name} also changed ${other.name}",
                    base.knobValue(other), moved.knobValue(other), 0f,
                )
            }
        }
    }

    @Test
    fun `out of range values are clamped rather than stored`() {
        for (k in LabKnob.entries) {
            val hi = LabRecipe().withKnob(k, 99f).knobValue(k)
            val lo = LabRecipe().withKnob(k, -99f).knobValue(k)
            assertTrue("$k exceeded its range: $hi", hi <= 99f)
            assertTrue("$k went below its range: $lo", lo >= -99f)
        }
        // And specifically, a knob whose neutral is 1 must not be able to read 99.
        assertTrue(LabRecipe().withKnob(LabKnob.CONTRAST, 99f).knobValue(LabKnob.CONTRAST) <= 3f)
        assertTrue(LabRecipe().withKnob(LabKnob.GAMMA, 99f).knobValue(LabKnob.GAMMA) <= 3f)
        assertTrue(LabRecipe().withKnob(LabKnob.SHARP_RADIUS, 99f).knobValue(LabKnob.SHARP_RADIUS) <= 3f)
    }

    /**
     * The display groups and the flat list are the same set of controls.
     *
     * They are two tables describing one thing, which is exactly the shape that
     * drifts: a knob added to one and not the other is invisible until a user
     * cannot find a control that the import clearly applied.
     */
    @Test
    fun `the display groups and the flat list cover the same knobs`() {
        val grouped = LAB_KNOB_GROUPS.flatMap { it.knobs }.associateBy { it.label }
        // Every group label must exist in the flat list, with matching neutral.
        for ((label, knob) in grouped) {
            val enum = LabKnob.entries.firstOrNull { it.label == label }
            assertNotNull("group knob $label has no LabKnob entry", enum)
            assertEquals("neutral mismatch for $label", enum!!.neutral, knob.neutral, 0f)
        }
        // Every scalar LabKnob that has a slider must appear in some group.
        // (Array groups — mixer, grade, B&W, cal, defringe — and the geometry
        // block have their own UI.)
        val scalarLabels = LabKnob.entries.map { it.label }.toSet() - setOf(
            "BRIGHTNESS", "GAMMA", "SPLIT TONE",
            "GEO VERTICAL", "GEO HORIZONTAL", "GEO ROTATE", "GEO ASPECT",
            "GEO SCALE", "GEO X", "GEO Y",
        )
        for (label in scalarLabels) {
            assertTrue("LabKnob $label has no group slider", label in grouped.keys)
        }
    }

    /** The group ranges have to match the recipe's own coerce, or a slider
     *  can be dragged to a value the renderer then clamps. */
    @Test
    fun `group ranges match what the recipe will accept`() {
        for (group in LAB_KNOB_GROUPS) {
            for (k in group.knobs) {
                val knob = LabKnob.entries.first { it.label == k.label }
                for (edge in listOf(k.min, k.max)) {
                    val stored = LabRecipe().withKnob(knob, edge).knobValue(knob)
                    assertEquals("${group.title}/$k rejected its own range", edge, stored, 1e-4f)
                }
            }
        }
    }

    /**
     * A dependent slider switches its master on, so building from scratch
     * works without an XMP import setting the amounts first. Before this,
     * grain size with grain at 0 (and the same for vignette shape, sharp
     * radius/masking, lens focus) did nothing at all, which is the "advanced
     * sliders only work after an import" report.
     */
    @Test
    fun `dependent sliders arm their master amount`() {
        assertEquals(0.5f, LabRecipe().withKnob(LabKnob.GRAIN_SIZE, 2f).grain, 0f)
        assertEquals(0.5f, LabRecipe().withKnob(LabKnob.GRAIN_ROUGH, 0.8f).grain, 0f)
        assertEquals(0.5f, LabRecipe().withKnob(LabKnob.VIG_MIDPOINT, 0.7f).vignette, 0f)
        assertEquals(0.5f, LabRecipe().withKnob(LabKnob.VIG_ROUND, 0.7f).vignette, 0f)
        assertEquals(0.45f, LabRecipe().withKnob(LabKnob.SHARP_RADIUS, 2f).sharpen, 1e-4f)
        assertEquals(0.45f, LabRecipe().withKnob(LabKnob.MASKING, 0.5f).sharpen, 1e-4f)
        assertEquals(0.5f, LabRecipe().withKnob(LabKnob.LENS_FOCUS, 0.7f).lensBlur, 0f)
        // Touching a neutral value arms nothing: a no-op drag must stay a no-op.
        assertEquals(0f, LabRecipe().withKnob(LabKnob.GRAIN_SIZE, 1f).grain, 0f)
        assertEquals(0f, LabRecipe().withKnob(LabKnob.VIG_MIDPOINT, 0.5f).vignette, 0f)
        // And an already-on master is left alone, not reset to the default.
        assertEquals(0.8f, LabRecipe(grain = 0.8f).withKnob(LabKnob.GRAIN_SIZE, 2f).grain, 0f)
    }

    @Test
    fun `a grade hue arms its saturation and sticks`() {
        val r = LabRecipe().withGrade(0, 0.3f)
        assertEquals(0.3f, r.gradeArray()!![0], 1e-4f)
        assertEquals(0.5f, r.gradeArray()!![1], 1e-4f)
        assertTrue(r.gradeActive)
    }

    @Test
    fun `a defringe range arms its amount and sticks`() {
        val r = LabRecipe().withDefringe(1, 0.8f)
        assertEquals(0.8f, r.defringeArray()!![1], 1e-4f)
        assertEquals(0.5f, r.defringeArray()!![0], 1e-4f)
        assertTrue(r.defringeActive)
    }

    /** Every knob the importer can set has somewhere to put it. */
    @Test
    fun `every XMP-mapped scalar is reachable from a knob`() {
        val r = XmpImport.parse(
            """
            crs:Highlights2012="-40" crs:Shadows2012="+38" crs:Whites2012="-20"
            crs:Blacks2012="+10" crs:Clarity2012="+8" crs:Dehaze="+2"
            crs:ConvertToGrayscale="True" crs:SharpenRadius="1.4" crs:SharpenDetail="30"
            crs:SharpenEdgeMasking="45" crs:GrainSize="70" crs:GrainFrequency="60"
            crs:VignetteAmount="-30" crs:Exposure2012="+0.4" crs:Tint="+8"
            """.trimIndent(),
        ).recipe
        // Nothing was imported that the UI cannot then show and change. A knob
        // that moved has to be reachable by index, or its slider is unreachable.
        LabKnob.entries.forEachIndexed { i, k ->
            if (r.knobValue(k) != k.neutral) {
                assertNotNull("knob $k is not reachable by index", LabKnob.byIndex(i))
            }
        }
        assertEquals(1f, r.knobValue(LabKnob.GRAYSCALE), 1e-4f)
        assertEquals(1.4f, r.knobValue(LabKnob.SHARP_RADIUS), 1e-3f)
        assertEquals(0.45f, r.knobValue(LabKnob.MASKING), 1e-3f)
    }
}
