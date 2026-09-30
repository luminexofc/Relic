package com.relic.ui

import com.relic.ui.theme.AppType
import com.relic.ui.theme.ShadcnRadius

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relic.catalog.FilterSpec
import kotlinx.coroutines.delay

/** Snap strip with live thumbnails. Long-press toggles favorite (★ pinned front). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterStrip(
    specs: List<FilterSpec>,
    selectedId: String,
    favorites: Set<String>,
    thumbnails: Map<String, Bitmap?>,
    onSelect: (FilterSpec) -> Unit,
    onToggleFavorite: (FilterSpec) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    showHint: Boolean = false,
    onHintShown: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val selectedIndex = specs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    LaunchedEffect(selectedIndex) {
        runCatching { listState.animateScrollToItem(selectedIndex) }
    }
    // First-launch hint: nudge one item over and back, once ever.
    LaunchedEffect(showHint) {
        if (showHint && specs.size > 1) {
            delay(400)
            runCatching { listState.animateScrollToItem(1) }
            delay(350)
            runCatching { listState.animateScrollToItem(0) }
            onHintShown()
        }
    }
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        modifier = modifier.height(96.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(specs, key = { it.id }) { spec ->
            val selected = spec.id == selectedId
            val starred = spec.id in favorites
            val targetScale = if (selected) 1.15f else 1f
            val thumbScale by animateFloatAsState(
                targetScale,
                animationSpec = if (reducedMotion) tween(150) else filterSpring(),
                label = "filterSelect",
            )
            Column(
                modifier = Modifier
                    .width(64.dp)
                    .combinedClickable(
                        onClick = { onSelect(spec) },
                        onLongClick = { onToggleFavorite(spec) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val thumb = thumbnails[spec.id]
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(thumbScale)
                        .clip(RoundedCornerShape(ShadcnRadius.Xl))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (selected) Modifier.border(
                                2.dp, Color.White, RoundedCornerShape(ShadcnRadius.Xl),
                            ) else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = spec.displayName,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(ShadcnRadius.Xl)),
                        )
                    }
                }
                Text(
                    text = (if (starred) "★" else "") + spec.displayName,
                    fontSize = 10.sp,
                    fontFamily = AppType.Sans,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Color.White
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Renders each filter thumbnail once per picker open (staggered, no GL hog). */
suspend fun thumbnailFillOnce(
    specs: List<FilterSpec>,
    render: (FilterSpec, (Bitmap) -> Unit) -> Unit,
    onThumb: (String, Bitmap) -> Unit,
) {
    specs.forEach { spec ->
        render(spec) { bmp -> onThumb(spec.id, bmp) }
        delay(40)
    }
}

