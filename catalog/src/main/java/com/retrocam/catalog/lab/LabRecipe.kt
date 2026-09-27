package com.retrocam.catalog.lab

/**
 * A user-built Filter Lab recipe: a starting template plus the five grading
 * knobs, applied on top of whichever base filter the recipe was saved against.
 *
 * Immutable on purpose. Editing a slider produces a new instance, and the
 * renderer uses that identity change to know when its uniforms need recomputing.
 *
 * Effect stages (vignette, grain, LUT, stamp) arrive in later phases as further
 * fields; the grade is the part that has to be right first, because everything
 * else sits on top of it.
 */
data class LabRecipe(
    /** [LabTemplates] id to start from, or null to grade the base filter as-is. */
    val templateId: String? = null,
    val adjustments: LabAdjustments = LabAdjustments.NEUTRAL,
) {
    /** The template's 4x5 matrix, or null when there is no template. */
    fun templateMatrix(): FloatArray? = templateId?.let { LabTemplates.byId[it]?.matrix }

    /** True when this recipe would not change a pixel, so the stage is skippable. */
    val isIdentity: Boolean get() = templateId == null && adjustments.isNeutral
}

/**
 * The synthetic [com.retrocam.catalog.FilterSpec] for the lab's grade stage.
 *
 * It is never in the filter strip and never selected on its own — the renderer
 * appends it as the second link when a spec carries a [LabRecipe]. Keeping it a
 * normal spec means it reuses the existing program cache, uniform upload and
 * safe-mode fallback with no special cases.
 */
object LabShaderSpec {
    val spec = com.retrocam.catalog.FilterSpec(
        id = "lab_grade",
        displayName = "LAB",
        family = com.retrocam.catalog.FilterFamily.LAB,
        fragmentBody = com.retrocam.catalog.Shaders.LAB_GRADE,
        param1 = 0f,
        param2 = 0f,
        defaultIntensity = 1f,
        context = "filter lab colour grade stage",
    )
}
