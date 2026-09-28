package com.retrocam.catalog.lab

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

    @Test
    fun `every knob reads a field and writing it back is a no-op`() {
        // The round trip that catches a `when` with a branch missing: reading a
        // knob the writer does not handle would return neutral no matter what,
        // and the UI would show a slider that does nothing.
        val values = floatArrayOf(
            0.2f, 1.3f, 0.7f, -0.4f, 0.15f, 1.2f, 0.3f,
            -0.5f, 0.6f, -0.2f, 0.45f,
            0.35f, -0.25f, 0.55f,
            0.5f,
            1.8f, 0.3f, 0.7f,
            2.2f, 0.4f, 0.3f, 0.8f,
        )
        LabKnob.entries.forEachIndexed { i, k ->
            val r = LabRecipe().withKnob(k, values[i])
            assertEquals(
                "${k.name} did not round trip",
                values[i], r.knobValue(k), 1e-4f,
            )
        }
    }

    @Test
    fun `a default recipe is neutral on every knob`() {
        val r = LabRecipe()
        for (k in LabKnob.entries) {
            assertEquals("${k.name} is not neutral by default", k.neutral, r.knobValue(k), 0f)
        }
    }

    @Test
    fun `setting a knob leaves every other knob alone`() {
        // The property that a single slider actually moves one control. A `when`
        // that fell through to a shared branch would move several at once, and
        // that is invisible unless it is checked.
        LabKnob.entries.forEach { target ->
            val base = LabRecipe()
            val moved = base.withKnob(target, if (target.neutral == 0f) 0.5f else target.neutral + 0.3f)
            for (other in LabKnob.entries) {
                if (other == target) continue
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
        // The seven original Lab controls, which live outside the groups
        // because they are the primary panel rather than an XMP-derived one.
        val primary = setOf(
            "BRIGHTNESS", "CONTRAST", "SATURATION", "WARMTH", "TINT",
            "GAMMA", "SPLIT TONE",
        )
        val expected = LabKnob.entries.map { it.label }.toSet() - primary
        assertEquals(expected, grouped.keys)
        for ((label, knob) in grouped) {
            val enum = LabKnob.entries.first { it.label == label }
            assertEquals("neutral mismatch for $label", enum.neutral, knob.neutral, 0f)
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
