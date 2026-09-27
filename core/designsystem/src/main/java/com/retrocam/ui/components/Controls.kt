package com.retrocam.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.ui.theme.AppType
import com.retrocam.ui.theme.ShadcnColor
import com.retrocam.ui.theme.ShadcnMotion
import com.retrocam.ui.theme.ShadcnShape

/**
 * shadcn `Button`, in the variants this app actually uses.
 *
 * The distinctions that matter: `ghost` has no chrome at all and only tints on
 * press, `outline` is a 1px border with no fill, and `default` is a filled surface
 * with **dark** text on it - because in shadcn's dark theme the primary is
 * near-white. Getting that last one wrong is what makes a shadcn clone look
 * like a Material app.
 */
enum class ButtonVariant { Default, Secondary, Outline, Ghost, Destructive }

enum class ButtonSize { Sm, Default, Lg, Icon }

@Composable
fun ShadcnButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Default,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val shape = ShadcnShape.lg
    val height: Dp = when (size) {
        ButtonSize.Sm -> 32.dp
        ButtonSize.Default -> 36.dp
        ButtonSize.Lg -> 44.dp
        ButtonSize.Icon -> 36.dp
    }
    val hPad: Dp = when (size) {
        ButtonSize.Sm -> 10.dp
        ButtonSize.Default -> 14.dp
        ButtonSize.Lg -> 20.dp
        ButtonSize.Icon -> 0.dp
    }
    val fontSize = when (size) {
        ButtonSize.Sm -> 12.sp
        ButtonSize.Default -> 13.sp
        ButtonSize.Lg -> 14.sp
        ButtonSize.Icon -> 13.sp
    }

    val bg: Color
    val fg: Color
    val borderColor: Color?
    when (variant) {
        ButtonVariant.Default -> {
            bg = ShadcnColor.Primary
            fg = ShadcnColor.PrimaryForeground
            borderColor = null
        }
        ButtonVariant.Secondary -> {
            bg = ShadcnColor.Secondary
            fg = ShadcnColor.SecondaryForeground
            borderColor = null
        }
        ButtonVariant.Outline -> {
            bg = Color.Transparent
            fg = ShadcnColor.Foreground
            borderColor = ShadcnColor.Border
        }
        ButtonVariant.Ghost -> {
            bg = Color.Transparent
            fg = ShadcnColor.Foreground
            borderColor = null
        }
        ButtonVariant.Destructive -> {
            bg = ShadcnColor.Destructive
            fg = ShadcnColor.DestructiveForeground
            borderColor = null
        }
    }

    // Only ghost and outline react to press, and only by tinting. That restraint
    // is the shadcn feel.
    val pressedBg = when (variant) {
        ButtonVariant.Ghost, ButtonVariant.Outline -> if (pressed) ShadcnColor.Accent else bg
        else -> bg
    }
    val animatedBg by animateColorAsState(pressedBg, tween(ShadcnMotion.HOVER), label = "btnBg")

    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(animatedBg)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = hPad),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (size == ButtonSize.Icon) {
            leading?.invoke()
        } else {
            leading?.let {
                it()
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = TextStyle(fontSize = fontSize, color = fg, fontWeight = AppType.Normal))
        }
    }
}

/**
 * shadcn `Input`: no fill, 1px border, small radius, and a ring on focus.
 * Built on BasicTextField rather than Material's TextField so there is no
 * floating label, no indicator line and no 56dp minimum height.
 */
@Composable
fun ShadcnInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = ShadcnShape.md

    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(ShadcnColor.Background)
            .border(1.dp, if (focused) ShadcnColor.Ring else ShadcnColor.Input, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(
                placeholder,
                style = TextStyle(fontSize = 13.sp, color = ShadcnColor.MutedForeground),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 13.sp, color = ShadcnColor.Foreground),
            cursorBrush = SolidColor(ShadcnColor.Ring),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** shadcn `Switch`: 36x20, full, with a white thumb. Not Material's 52x32 outlined pill. */
@Composable
fun ShadcnSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(
        if (checked) ShadcnColor.Primary else ShadcnColor.Input,
        tween(ShadcnMotion.HOVER, easing = ShadcnMotion.Easing),
        label = "switchTrack",
    )
    val thumbShift by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(ShadcnMotion.HOVER, easing = ShadcnMotion.Easing),
        label = "switchThumb",
    )

    Box(
        modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(CircleShape)
            .background(track)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .offset(x = thumbShift)
                .clip(CircleShape)
                .background(if (checked) ShadcnColor.PrimaryForeground else ShadcnColor.MutedForeground),
        )
    }
}

/** shadcn `Card`: border plus a slightly raised fill, 8px radius. */
@Composable
fun ShadcnCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(ShadcnShape.lg)
            .background(ShadcnColor.Card)
            .border(1.dp, ShadcnColor.Border, ShadcnShape.lg)
            .padding(padding),
    ) { content() }
}

/** shadcn `Label`: small, muted, uppercase. */
@Composable
fun ShadcnLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ShadcnColor.MutedForeground,
) {
    Text(
        text.uppercase(),
        style = TextStyle(fontSize = 11.sp, color = color),
        modifier = modifier,
    )
}

/** shadcn `Badge`: a small muted pill for counts and states. */
@Composable
fun ShadcnBadge(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Box(
        modifier
            .clip(ShadcnShape.full)
            .background(if (selected) ShadcnColor.Primary else ShadcnColor.Surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(
                fontSize = 10.sp,
                color = if (selected) ShadcnColor.PrimaryForeground else ShadcnColor.Foreground,
            ),
        )
    }
}

/** shadcn `Separator`. */
@Composable
fun ShadcnSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ShadcnColor.Border),
    )
}
