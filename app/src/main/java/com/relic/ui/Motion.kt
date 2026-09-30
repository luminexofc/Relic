package com.relic.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import com.relic.ui.theme.MotionTokens

/** True when the system animator scale is 0 (reduced motion: fades/instant). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** Filter spring from Animations.md §1 (stiffness 400). */
fun filterSpring() = spring<Float>(
    stiffness = MotionTokens.FILTER_SPRING_STIFFNESS,
    dampingRatio = 0.7f,
)

/** Shutter spring from Animations.md §1 (stiffness 800). */
fun shutterSpring() = spring<Float>(
    stiffness = MotionTokens.SHUTTER_SPRING_STIFFNESS,
    dampingRatio = 0.7f,
)

/** Scale-pop on appearance (toggles, save check). Skipped under reduced motion. */
@Composable
fun PopIn(key: Any, reduced: Boolean, content: @Composable () -> Unit) {
    if (reduced) {
        content()
        return
    }
    var scale by remember(key) { mutableStateOf(0.6f) }
    LaunchedEffect(key) {
        animate(0.6f, 1f, animationSpec = tween(MotionTokens.FAST)) { value, _ -> scale = value }
    }
    Box(Modifier.scale(scale)) {
        content()
    }
}
