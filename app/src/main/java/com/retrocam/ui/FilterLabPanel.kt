package com.retrocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.catalog.lab.LAB_EFFECTS
import com.retrocam.catalog.lab.LabAdjustments
import com.retrocam.catalog.lab.effectValue
import com.retrocam.catalog.lab.LabTemplates
import com.retrocam.catalog.lab.SavedRecipe
import com.retrocam.ui.theme.RetroType

/**
 * Filter Lab. Composed *below* the live viewfinder rather than over it, so the
 * grade being edited is visible while it is edited.
 *
 * The preview keeps running underneath because the Lab is a sibling of the
 * camera chrome in the same layout, not an early-return screen like Settings.
 */
@Composable
fun FilterLabPanel(
    tab: Int,
    name: String,
    recipe: com.retrocam.catalog.lab.LabRecipe,
    intensity: Float,
    saved: List<SavedRecipe>,
    canSave: Boolean,
    onTab: (Int) -> Unit,
    onName: (String) -> Unit,
    onTemplate: (String?) -> Unit,
    onKnob: (Int, Float) -> Unit,
    onResetKnob: (Int) -> Unit,
    onResetAll: () -> Unit,
    onEffect: (Int, Float) -> Unit,
    onDuoColour: (Boolean, Int) -> Unit,
    onIntensity: (Float) -> Unit,
    onSave: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 14.dp),
    ) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Science,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "FILTER LAB",
                    fontFamily = RetroType.Display,
                    fontSize = 12.sp,
                    color = accent,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close Filter Lab", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        // ---- tabs ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabTab("BASIC", tab == 0, accent, Modifier.weight(1f)) { onTab(0) }
            LabTab("ADVANCED", tab == 1, accent, Modifier.weight(1f)) { onTab(1) }
        }

        if (tab == 0) {
            TemplateCarousel(recipe.templateId, accent, dim, onTemplate)
            Spacer(Modifier.height(6.dp))
            LabSlider("INTENSITY", intensity, 0f, 1f, accent, dim, onIntensity)
        } else {
            LabAdjustments.RANGES.forEachIndexed { i, k ->
                val v = when (i) {
                    0 -> recipe.adjustments.brightness
                    1 -> recipe.adjustments.contrast
                    2 -> recipe.adjustments.saturation
                    3 -> recipe.adjustments.warmth
                    4 -> recipe.adjustments.tint
                    else -> k.neutral
                }
                LabSlider(k.label, v, k.min, k.max, accent, dim, { onKnob(i, it) }, neutral = k.neutral, onReset = { onResetKnob(i) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onResetAll) {
                    Text("RESET ALL", fontFamily = RetroType.Mono, fontSize = 11.sp, color = dim)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("EFFECTS", fontFamily = RetroType.Mono, fontSize = 10.sp, color = dim)
            LAB_EFFECTS.forEachIndexed { i, e ->
                val v = recipe.effectValue(i)
                LabSlider(
                    e.label, v, e.min, e.max, accent, dim,
                    { onEffect(i, it) },
                    neutral = 0f,
                    onReset = { onEffect(i, 0f) },
                )
            }
            if (recipe.duotone > 0f) {
                DuotoneColours(recipe, accent, dim, onDuoColour)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- name + save ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = name,
                onValueChange = onName,
                singleLine = true,
                placeholder = { Text("NAME IT", fontFamily = RetroType.Mono, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = RetroType.Mono,
                    fontSize = 13.sp,
                ),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            LabButton("SAVE", enabled = canSave, accent = accent, onClick = onSave)
        }

        // ---- saved recipes ----
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "MY RECIPES",
                fontFamily = RetroType.Mono,
                fontSize = 10.sp,
                color = dim,
            )
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .height((saved.size * 44).coerceAtMost(132).dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                saved.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable { onEdit(r.id) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            r.name,
                            fontFamily = RetroType.Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onEdit(r.id) }) {
                            Icon(Icons.Filled.Edit, "Edit ${r.name}", tint = dim, modifier = Modifier.size(17.dp))
                        }
                        IconButton(onClick = { onDelete(r.id) }) {
                            Icon(Icons.Filled.Delete, "Delete ${r.name}", tint = dim, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabTab(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(
                if (selected) accent else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = RetroType.Mono,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            color = if (selected) androidx.compose.ui.graphics.Color.Black else accent,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LabButton(
    label: String,
    enabled: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (enabled) accent else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = RetroType.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (enabled) androidx.compose.ui.graphics.Color.Black else accent.copy(alpha = 0.4f),
        )
    }
}

/**
 * Lab slider with a live numeric readout and an optional neutral-point reset.
 * Long-press the label to snap back to neutral, which is what people actually
 * want after overshooting a knob.
 */
@Composable
private fun LabSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onChange: (Float) -> Unit,
    neutral: Float? = null,
    onReset: (() -> Unit)? = null,
) {
    val atNeutral = neutral != null && kotlin.math.abs(value - neutral) < 1e-4f
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (onReset != null) {
                    Modifier.clickable(enabled = !atNeutral, onClick = onReset)
                } else {
                    Modifier
                },
            ) {
                Text(
                    label,
                    fontFamily = RetroType.Mono,
                    fontSize = 10.sp,
                    color = if (atNeutral) dim else accent,
                )
                if (onReset != null && !atNeutral) {
                    Text("  reset", fontFamily = RetroType.Mono, fontSize = 9.sp, color = dim)
                }
            }
            Text(
                formatKnob(value),
                fontFamily = RetroType.Terminal,
                fontSize = 15.sp,
                color = if (atNeutral) dim else accent,
            )
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

private fun formatKnob(v: Float): String =
    if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e4f) v.toInt().toString() else String.format("%.2f", v)

/**
 * The 35 FilterLibrary presets as starting points. Horizontal, because the
 * category set is wide and a vertical list would bury the preview.
 */
@Composable
private fun TemplateCarousel(
    selected: String?,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onSelect: (String?) -> Unit,
) {
    var category by remember { mutableStateOf<String?>(null) }
    val categories = remember { LabTemplates.all.map { it.category }.distinct() }
    val shown = remember(category) {
        LabTemplates.all.filter { category == null || it.category == category }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            item {
                CategoryChip("ALL", category == null, accent, dim) { category = null }
            }
            items(categories) { c ->
                CategoryChip(
                    c.replace('_', ' '),
                    category == c,
                    accent,
                    dim,
                ) { category = if (category == c) null else c }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                TemplateChip(
                    "NONE",
                    selected == null,
                    accent,
                    dim,
                ) { onSelect(null) }
            }
            items(shown, key = { it.id }) { t ->
                TemplateChip(t.displayName, selected == t.id, accent, dim) { onSelect(t.id) }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .border(1.dp, if (selected) accent else dim.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontFamily = RetroType.Mono,
            fontSize = 9.sp,
            color = if (selected) accent else dim,
        )
    }
}

@Composable
private fun TemplateChip(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (selected) accent else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontFamily = RetroType.Mono,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 10.sp,
            color = if (selected) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.onBackground,
        )
    }
}


/**
 * Duotone shadow/highlight swatches. Not a full colour picker: two presets plus
 * a hue slider would be a whole screen, and duotone only needs two anchors.
 */
@Composable
private fun DuotoneColours(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onPick: (Boolean, Int) -> Unit,
) {
    val swatches = listOf(
        0xFF141450.toInt(), 0xFF0F172A.toInt(), 0xFF1B4332.toInt(),
        0xFF7F1D1D.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("DUO SHADOW", fontFamily = RetroType.Mono, fontSize = 9.sp, color = dim)
        SwatchRow(swatches, recipe.duotoneShadow, accent, dim) { onPick(true, it) }
        Text("DUO HIGHLIGHT", fontFamily = RetroType.Mono, fontSize = 9.sp, color = dim)
        SwatchRow(swatches, recipe.duotoneHighlight, accent, dim) { onPick(false, it) }
    }
}

@Composable
private fun SwatchRow(
    swatches: List<Int>,
    selected: Int,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        swatches.forEach { c ->
            val argb = c.toUInt().toLong().toInt()
            val col = androidx.compose.ui.graphics.Color(
                ((argb shr 16) and 0xFF) / 255f,
                ((argb shr 8) and 0xFF) / 255f,
                (argb and 0xFF) / 255f,
            )
            Box(
                Modifier
                    .size(26.dp)
                    .background(col, RoundedCornerShape(50))
                    .border(
                        if (selected == argb) 2.dp else 1.dp,
                        if (selected == argb) accent else dim.copy(alpha = 0.4f),
                        RoundedCornerShape(50),
                    )
                    .clickable { onPick(argb) },
            )
        }
    }
}
