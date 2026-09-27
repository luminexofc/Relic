package com.retrocam.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.retrocam.ui.theme.ShadcnColor
import com.retrocam.ui.theme.ShadcnMotion

private val TRACK_HEIGHT = 2.dp
private val THUMB_SIZE = 16.dp

/**
 * A Radix/shadcn slider.
 *
 * The differences from Material's `Slider` that you can see:
 *  - a **2dp** track, and the unfilled part is the primary colour at 20% rather
 *    than a separate grey;
 *  - a small **16dp** thumb sitting in a ring of the background colour, which is
 *    what gives shadcn its "thin rail with a dot" look;
 *  - **no ripple**. The only motion is a slight thumb scale on press, plus a
 *    focus ring for accessibility.
 *
 * Material's thumb grows on press and paints a large ripple, which dominates a
 * dense control panel - the wrong thing for a screen with five sliders now and
 * around forty more when the effect primitives land.
 *
 * The 20dp row is kept for touch even though the rail is 2dp.
 */
@Composable
fun ShadcnSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Material semantics: intervals between the endpoints. 0 = continuous. */
    steps: Int = 0,
    label: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val density = LocalDensity.current

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: 1f
    val fraction = ((value - start) / span).coerceIn(0f, 1f)

    var widthPx by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(density) { THUMB_SIZE.toPx() }
    // The thumb's centre can travel from the middle of the left cap to the middle
    // of the right one, which is why the rails are inset by half a thumb.
    val travel = (widthPx - thumbPx).coerceAtLeast(0f)

    fun valueAt(x: Float): Float {
        val f = ((x - thumbPx / 2f) / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = start + f * span
        if (steps <= 0) return raw
        val intervals = steps + 1
        val snapped = ((raw - start) / span * intervals).roundToInt().coerceIn(0, intervals)
        return start + (snapped.toFloat() / intervals) * span
    }

    val thumbScale by animateFloatAsState(
        targetValue = if (pressed) 1.15f else 1f,
        animationSpec = tween(ShadcnMotion.HOVER, easing = ShadcnMotion.Easing),
        label = "thumbScale",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, start..end, steps)
            }
            .pointerInput(enabled, valueRange, steps, widthPx) {
                if (!enabled) return@pointerInput
                detectTapGestures { onValueChange(valueAt(it.x)) }
            }
            .pointerInput(enabled, valueRange, steps, widthPx) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    onValueChange(valueAt(change.position.x))
                }
            },
    ) {
        // Unfilled rail: primary at 20%, lifting to the ring colour on hover or
        // focus so pointer and keyboard users get the same affordance.
        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(
                    if (hovered || focused) {
                        ShadcnColor.Ring.copy(alpha = 0.45f)
                    } else {
                        ShadcnColor.Primary.copy(alpha = 0.20f)
                    },
                ),
        )
        // Filled rail, inset by half a thumb at each end.
        Box(
            Modifier
                .width(with(density) { (travel * fraction).toDp() })
                .height(TRACK_HEIGHT)
                .align(Alignment.CenterStart)
                .offset(x = with(density) { (thumbPx / 2f).toDp() })
                .clip(CircleShape)
                .background(ShadcnColor.Primary),
        )
        // Thumb: a background-coloured disc with a primary disc inside it.
        Box(
            Modifier
                .size(THUMB_SIZE)
                .align(Alignment.CenterStart)
                .offset(x = with(density) { (thumbPx / 2f + travel * fraction).toDp() })
                .scale(thumbScale)
                .clip(CircleShape)
                .background(ShadcnColor.Background)
                .border(2.dp, ShadcnColor.Background, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(ShadcnColor.Primary),
        )
    }
}
