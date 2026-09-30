package com.relic.catalog.lab

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a stage is allowed to act on the frame.
 *
 * The mask lives in the shared shader footer rather than in each filter, so a
 * stage can be limited to part of the image without any filter body knowing
 * about it. That also means every one of the 46 shaders gains the capability for
 * free.
 *
 * The default is [MaskShape.FULL] and must evaluate to **exactly** 1.0, because
 * the footer multiplies every filter's intensity by it. Anything less than
 * exactly 1.0 would quietly darken all 41 built-in filters.
 */
enum class MaskShape(val shaderCode: Float) {
    FULL(0f),
    RECT(1f),
    ELLIPSE(2f),
    /** Horizontal band: a strip across the middle of the frame. */
    BAND_H(3f),
    /** Vertical band. */
    BAND_V(4f),
}

/**
 * A rectangular region with feathering, in frame UV where y grows upward.
 *
 * Kept as plain maths so it is unit-testable; the shader only receives the
 * finished rect, the shape code and the feather.
 */
data class LabMask(
    val shape: MaskShape = MaskShape.FULL,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val feather: Float = 0f,
) {
    val isFull: Boolean
        get() = shape == MaskShape.FULL ||
            // Only RECT qualifies. An ellipse inscribed in the full frame still
            // misses the four corners, so treating it as unmasked would leave them
            // unfiltered for no reason.
            (shape == MaskShape.RECT &&
                x <= 0f && y <= 0f && width >= 1f && height >= 1f && feather <= 0f)

    /** `[x0, y0, x1, y1]`, clamped into the frame. */
    fun rect(): FloatArray {
        val x0 = x.coerceIn(0f, 1f)
        val y0 = y.coerceIn(0f, 1f)
        val x1 = (x + width).coerceIn(0f, 1f)
        val y1 = (y + height).coerceIn(0f, 1f)
        return floatArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }

    companion object {
        val FULL = LabMask()

        /**
         * Clamp a decoded or hand-edited mask. A mask with a zero or negative
         * extent would divide by zero in the feather term, so it collapses to
         * full frame rather than producing a black rectangle.
         */
        fun coerce(m: LabMask): LabMask {
            if (m.isFull) return FULL
            if (m.width <= 0f || m.height <= 0f) return FULL
            return m.copy(
                x = m.x.coerceIn(0f, 1f),
                y = m.y.coerceIn(0f, 1f),
                width = m.width.coerceIn(0.001f, 1f),
                height = m.height.coerceIn(0.001f, 1f),
                feather = m.feather.coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * One entry in a recipe's stage chain.
 *
 * A stage is a catalog filter used as a chain link, plus the controls that drive
 * its `u_param1..3` and the region it is allowed to affect. The filter bodies
 * already exist and already take those params, which is what makes exposing all
 * of them as stages mostly a metadata problem.
 */
data class LabStage(
    /** A catalog filter id, or a `lab_grade` pseudo-stage for the colour grade. */
    val primitiveId: String,
    /** Blend amount, 0..1. Fed to the shader as the link's intensity. */
    val amount: Float = 1f,
    /** Named controls for this primitive, in [LabPrimitive.controls] order. */
    val params: Map<String, Float> = emptyMap(),
    val mask: LabMask = LabMask.FULL,
) {
    val amountClamped: Float get() = amount.coerceIn(0f, 1f)
    val maskClamped: LabMask get() = LabMask.coerce(mask)
}

/**
 * Metadata for a stage: which filter it reuses, and what its knobs mean.
 *
 * The control list maps a friendly name onto one of the shader's three
 * parameters, so the UI can render real sliders without knowing anything about
 * what the parameter does.
 */
data class LabControl(
    val label: String,
    /** 1, 2 or 3, matching `u_param1..3`. */
    val param: Int,
    val min: Float,
    val max: Float,
    val neutral: Float,
)

data class LabPrimitive(
    val id: String,
    val displayName: String,
    val context: String,
    val controls: List<LabControl>,
    /**
     * Temporal filters need the frame-to-frame feedback buffer, which belongs to
     * the single-filter path and is not available mid-chain. MOTION_TRAILS is
     * therefore usable as a base filter but not as a stage.
     */
    val chainable: Boolean = true,
)
