package com.retrocam.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.ui.theme.AppType
import com.retrocam.ui.theme.ShadcnColor
import com.retrocam.ui.theme.ShadcnMotion
import com.retrocam.ui.theme.ShadcnShape

/**
 * shadcn `Dialog` / `AlertDialog`: a centred card on a scrim.
 *
 * Replaces Material's `AlertDialog`, which brings its own container, elevation
 * and layout that do not match anything else in the app. This one is the same
 * `ShadcnCard` used everywhere else, so dialogs stop looking like a different
 * application bolted on.
 */
@Composable
fun ShadcnDialog(
    title: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(ShadcnColor.Background.copy(alpha = 0.7f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(ShadcnShape.lg)
                .background(ShadcnColor.Card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(20.dp),
        ) {
            Text(
                title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = ShadcnColor.Foreground,
                    fontWeight = AppType.Strong,
                ),
            )
            Spacer(Modifier.height(14.dp))
            options.forEach { option ->
                val isSelected = option == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ShadcnShape.md)
                        .background(
                            if (isSelected) ShadcnColor.Surface else Color.Transparent,
                        )
                        .clickable { onPick(option) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(14.dp)
                            .clip(ShadcnShape.full)
                            .background(
                                if (isSelected) ShadcnColor.Primary else Color.Transparent,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        option,
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = if (isSelected) ShadcnColor.Foreground
                            else ShadcnColor.MutedForeground,
                            fontWeight = if (isSelected) AppType.Strong else AppType.Normal,
                        ),
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                ShadcnButton(text = "Close", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
            }
        }
    }
}

/**
 * A bottom sheet in shadcn's language: a scrim, a raised surface, and the
 * Radix enter/exit timing.
 *
 * One deliberate regression against Material's `ModalBottomSheet`: there is no
 * drag-to-dismiss. Tapping the scrim and the back gesture both work, but a
 * Material sheet can be flicked away, and this one cannot. Worth revisiting if it
 * turns out to be noticed - drag would need an anchored draggable state rather
 * than an animation.
 */
@Composable
fun ShadcnBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Entry animation via animateFloatAsState rather than fadeIn/slideInVertically:
    // those live in the androidx.compose.animation artifact, which is not resolving
    // onto this module, while animation-core is and is what the rest of the app
    // already uses. Same 200ms and the same easing, reached differently.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val t by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(ShadcnMotion.ENTER, easing = ShadcnMotion.Easing),
        label = "sheetIn",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * t))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .graphicsLayer {
                    alpha = t
                    // Slides up from a quarter of its own height.
                    translationY = (1f - t) * (size.height / 4f)
                },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ShadcnShape.xl)
                    .background(ShadcnColor.Card)
                    .padding(bottom = 28.dp),
            ) {
                // Grabber, as a bottom sheet has.
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(ShadcnShape.full)
                        .background(ShadcnColor.Border),
                )
                content()
            }
        }
    }
}
