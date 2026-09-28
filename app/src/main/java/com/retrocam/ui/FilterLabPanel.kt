package com.retrocam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.catalog.lab.LAB_EFFECTS
import com.retrocam.catalog.lab.LabAdjustments
import com.retrocam.catalog.lab.effectValue
import com.retrocam.catalog.lab.LabTemplates
import com.retrocam.catalog.lab.SavedRecipe
import com.retrocam.ui.components.ButtonSize
import com.retrocam.ui.components.ButtonVariant
import com.retrocam.ui.components.ShadcnButton
import com.retrocam.ui.theme.ShadcnColor
import com.retrocam.ui.components.ShadcnInput
import com.retrocam.ui.components.ShadcnLabel
import com.retrocam.ui.components.ShadcnSlider
import com.retrocam.ui.theme.AppType
import com.retrocam.ui.theme.ShadcnRadius

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
    luts: List<LutStore.Entry>,
    onPickLut: (String?) -> Unit,
    onImportLut: () -> Unit,
    onLutAmount: (Float) -> Unit,
    palette: List<Int>,
    watermarks: List<Pair<String, String>>,
    onPickWatermark: (String?) -> Unit,
    onImportWatermark: () -> Unit,
    onWatermarkAlpha: (Float) -> Unit,
    onExtractPalette: () -> Unit,
    onApplyPaletteColour: (Int, Boolean) -> Unit,
    onSplitTint: (Boolean, Int) -> Unit,
    onAddStage: (String) -> Unit,
    onRemoveStage: (Int) -> Unit,
    onMoveStage: (Int, Int) -> Unit,
    onStageAmount: (Int, Float) -> Unit,
    onStageParam: (Int, Int, Float) -> Unit,
    onStageParamReset: (Int, Int) -> Unit,
    onStageMaskShape: (Int) -> Unit,
    onStageFeather: (Int, Float) -> Unit,
    onStageMaskDrag: (Int, Float, Float, Float, Float) -> Unit,
    onClearStages: () -> Unit,
    selectedStage: Int,
    onSelectStage: (Int) -> Unit,
    onShare: (String) -> Unit,
    onImportQr: () -> Unit,
    xmpReport: com.retrocam.catalog.lab.XmpResult?,
    onImportXmp: () -> Unit,
    onDismissReport: () -> Unit,
    onCopyReport: (String) -> Unit,
    onDeleteLut: (String) -> Unit,
    onDeleteWatermark: (String) -> Unit,
    selectedRecipeId: String?,
    onUse: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onIntensity: (Float) -> Unit,
    onSave: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 20.dp),
    ) {
        // ---- tabs ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LabTab("BASIC", tab == 0, accent, Modifier.weight(1f)) { onTab(0) }
            LabTab("ADVANCED", tab == 1, accent, Modifier.weight(1f)) { onTab(1) }
            LabTab("STAGES", tab == 2, accent, Modifier.weight(1f)) { onTab(2) }
        }

        if (tab == 2) {
            StageChain(
                recipe = recipe,
                accent = accent,
                dim = dim,
                onAdd = onAddStage,
                onRemove = onRemoveStage,
                onMove = onMoveStage,
                onAmount = onStageAmount,
                onParam = onStageParam,
                onParamReset = onStageParamReset,
                onMaskShape = onStageMaskShape,
                onFeather = onStageFeather,
                onMaskDrag = onStageMaskDrag,
                onClear = onClearStages,
                selected = selectedStage,
                onSelect = onSelectStage,
            )
        } else if (tab == 0) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImportChip("IMPORT .XMP", accent) { onImportXmp() }
                Text(
                    "Lightroom / Camera Raw preset",
                    fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
                )
            }
            xmpReport?.let { XmpReportBlock(it, accent, dim, onDismissReport, onCopyReport) }
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
                    5 -> recipe.gamma
                    6 -> recipe.splitAmount
                    else -> k.neutral
                }
                LabSlider(k.label, v, k.min, k.max, accent, dim, { onKnob(i, it) }, neutral = k.neutral, onReset = { onResetKnob(i) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ShadcnButton(
                    text = "Reset all",
                    onClick = onResetAll,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
            if (recipe.splitAmount > 0f) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("SHADOW TINT", fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
                    SwatchRow(TINT_SWATCHES, recipe.shadowTint, accent, dim) { onSplitTint(true, it) }
                    Text("HIGHLIGHT TINT", fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
                    SwatchRow(TINT_SWATCHES, recipe.highlightTint, accent, dim) { onSplitTint(false, it) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("EFFECTS", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
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
            Spacer(Modifier.height(4.dp))
            Text("3D LUT", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
            if (recipe.lutId != null) {
                LabSlider(
                    "LUT AMOUNT", recipe.lutAmount, 0f, 1f, accent, dim,
                    onLutAmount,
                    neutral = 1f,
                    onReset = { onLutAmount(1f) },
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "lut_none") {
                    TemplateChip(
                        label = "NONE",
                        selected = recipe.lutId == null,
                        accent = accent,
                        dim = dim,
                        onClick = { onPickLut(null) },
                    )
                }
                items(luts, key = { it.id }) { e ->
                    // Imported LUTs are deletable; the generated ones are not, so
                    // they get no long-press handler at all.
                    val onLong = if (e.builtin == null) {
                        { onDeleteLut(e.id) }
                    } else {
                        null
                    }
                    TemplateChip(
                        label = e.displayName,
                        selected = recipe.lutId == e.id,
                        accent = accent,
                        dim = dim,
                        onClick = { onPickLut(e.id) },
                        onLongClick = onLong,
                    )
                }
                item { ImportChip("+ IMPORT LUT", dim, onImportLut) }
            }
            Spacer(Modifier.height(10.dp))
                        Spacer(Modifier.height(8.dp))
            Text("WATERMARK", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    TemplateChip("NONE", recipe.watermarkId == null, accent, dim) { onPickWatermark(null) }
                }
                items(watermarks, key = { it.first }) { (id, label) ->
                    TemplateChip(
                        label = label,
                        selected = recipe.watermarkId == id,
                        accent = accent,
                        dim = dim,
                        onClick = { onPickWatermark(id) },
                        onLongClick = { onDeleteWatermark(id) },
                    )
                }
                item { ImportChip("+ IMPORT", dim, onImportWatermark) }
            }
            if (recipe.watermarkId != null) {
                LabSlider("MARK OPACITY", recipe.watermarkAlpha, 0f, 1f, accent, dim, onWatermarkAlpha)
            }
            Spacer(Modifier.height(10.dp))
            Text("PALETTE FROM FRAME", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShadcnButton(
                    text = "Extract",
                    onClick = onExtractPalette,
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                )
                if (palette.isNotEmpty()) {
                    SwatchRow(palette, -1, accent, dim) { onApplyPaletteColour(it, true) }
                }
            }
            if (palette.isNotEmpty()) {
                Text(
                    "tap a swatch to use it as the duotone shadow",
                    fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- name + save ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShadcnInput(
                value = name,
                onValueChange = onName,
                placeholder = "Name it",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            ShadcnButton(
                text = "Save",
                onClick = onSave,
                enabled = canSave,
                variant = if (canSave) ButtonVariant.Default else ButtonVariant.Outline,
            )
        }

        // ---- the bridge: hand the draft to the camera ----
        // Saving and using are separate on purpose. The two halves only meet
        // here, so this is the one action that crosses between them.
        if (selectedRecipeId != null) {
            Spacer(Modifier.height(10.dp))
            ShadcnButton(
                text = "Use in camera",
                onClick = { onUse(selectedRecipeId) },
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Lg,
                leading = {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = null,
                        tint = ShadcnColor.PrimaryForeground,
                        modifier = Modifier.size(15.dp),
                    )
                },
            )
        }

        // ---- saved recipes ----
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("MY RECIPES", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
                Text(
                    "+ IMPORT QR",
                    fontFamily = AppType.Sans,
                    fontSize = 10.sp,
                    color = accent,
                    modifier = Modifier.clickable(onClick = onImportQr).padding(4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .height((saved.size * 44).coerceAtMost(132).dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                saved.forEach { r ->
                    val selected = r.id == selectedRecipeId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                            )
                            .clickable { onEdit(r.id) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            r.name,
                            fontFamily = AppType.Sans,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        ShadcnButton(
                            onClick = { onEdit(r.id) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Icon,
                            modifier = Modifier.size(30.dp),
                            leading = {
                                Icon(
                                    Icons.Filled.Edit,
                                    "Edit ${r.name}",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        ShadcnButton(
                            onClick = { onShare(r.id) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Icon,
                            modifier = Modifier.size(30.dp),
                            leading = {
                                Icon(
                                    Icons.Filled.QrCode,
                                    "Share ${r.name} as QR",
                                    tint = ShadcnColor.Primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        ShadcnButton(
                            onClick = { onDelete(r.id) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Icon,
                            modifier = Modifier.size(30.dp),
                            leading = {
                                Icon(
                                    Icons.Filled.Delete,
                                    "Delete ${r.name}",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
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
            fontFamily = AppType.Sans,
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
            fontFamily = AppType.Sans,
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
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
                // shadcn's Label: small, muted, and it stays muted when at neutral
                // rather than changing colour, so the value is the only thing that
                // moves.
                ShadcnLabel(text = label, color = dim)
                if (onReset != null && !atNeutral) {
                    Text("  reset", fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
                }
            }
            // Numeric readout, so this is the one place the mono role earns its
            // keep: aligned digits that do not jitter as the value changes.
            Text(
                formatKnob(value),
                fontFamily = AppType.Mono,
                fontSize = 13.sp,
                color = if (atNeutral) dim else ShadcnColor.Foreground,
            )
        }
        ShadcnSlider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            label = label,
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
            fontFamily = AppType.Sans,
            fontSize = 9.sp,
            color = if (selected) accent else dim,
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TemplateChip(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    // Declared before onClick so onClick stays the last parameter and the
    // trailing-lambda call sites below still bind to it.
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (selected) accent else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(50),
            )
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontFamily = AppType.Sans,
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
/** Muted split-tone anchors. Anything strong would just tint the whole frame. */
private val TINT_SWATCHES = listOf(
    0xFF808080.toInt(), 0xFF2A3A5A.toInt(), 0xFF1B4332.toInt(),
    0xFF5A4632.toInt(), 0xFF3A2A4A.toInt(), 0xFF4A2A2A.toInt(),
)

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
        Text("DUO SHADOW", fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
        SwatchRow(swatches, recipe.duotoneShadow, accent, dim) { onPick(true, it) }
        Text("DUO HIGHLIGHT", fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
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

@Composable
private fun ImportChip(label: String, dim: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, dim.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
    }
}


/**
 * The stage chain editor: an ordered list of filters applied one after another on
 * top of the colour grade, each with its own amount, knobs and area mask.
 *
 * Order is the whole point of a chain, so every row shows its position and can
 * be moved rather than relying on insertion order to be obvious.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageChain(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onAmount: (Int, Float) -> Unit,
    onParam: (Int, Int, Float) -> Unit,
    onParamReset: (Int, Int) -> Unit,
    onMaskShape: (Int) -> Unit,
    onFeather: (Int, Float) -> Unit,
    onMaskDrag: (Int, Float, Float, Float, Float) -> Unit,
    onClear: () -> Unit,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val stages = recipe.stages
    val full = stages.size >= com.retrocam.catalog.lab.LabRecipe.MAX_STAGES

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "STAGE CHAIN",
            fontFamily = AppType.Sans, fontSize = 10.sp, color = dim,
            modifier = Modifier.weight(1f),
        )
        if (stages.isNotEmpty()) {
            ShadcnButton(
                text = "Clear",
                onClick = onClear,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        }
    }

    if (full) {
        Text(
            "Chain is full at ${com.retrocam.catalog.lab.LabRecipe.MAX_STAGES} stages. " +
                "Remove one to add another.",
            fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
        )
    }

    stages.forEachIndexed { index, stage ->
        val prim = com.retrocam.catalog.lab.LabPrimitives.byId(stage.primitiveId)
        val isSel = index == selected
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(
                    if (isSel) 2.dp else 1.dp,
                    if (isSel) accent else dim.copy(alpha = 0.4f),
                    RoundedCornerShape(ShadcnRadius.Lg),
                )
                .clickable { onSelect(index) }
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    fontFamily = AppType.Sans, fontSize = 10.sp, color = accent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    prim?.displayName ?: stage.primitiveId,
                    fontFamily = AppType.Sans, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (prim != null) {
                    Text(
                        prim.context,
                        fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            LabSlider(
                "AMOUNT", stage.amountClamped, 0f, 1f, accent, dim, { onAmount(index, it) },
            )

            // A knob only appears once it has been set, so a freshly added stage is
            // three lines long instead of showing every slider the filter could
            // have. The catalog default is the starting point.
            (prim?.controls ?: emptyList()).forEach { c ->
                val set = stage.params[c.param.toString()]
                if (set != null) {
                    LabSlider(
                        c.label, set, c.min, c.max, accent, dim,
                        { onParam(index, c.param, it) },
                        neutral = c.neutral,
                        onReset = { onParamReset(index, c.param) },
                    )
                }
            }
            if (prim != null && prim.controls.any { !stage.params.containsKey(it.param.toString()) }) {
                Text(
                    "+ ${prim.controls.count { !stage.params.containsKey(it.param.toString()) }} more knob(s) — " +
                        "reset to default to reveal",
                    fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AREA",
                    fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
                )
                Spacer(Modifier.width(6.dp))
                ShadcnButton(
                    text = if (stage.maskClamped.isFull) "FULL FRAME" else stage.maskClamped.shape.name,
                    onClick = { onMaskShape(index) },
                    variant = ButtonVariant.Outline,
                    size = ButtonSize.Sm,
                )
                Spacer(Modifier.width(6.dp))
                if (!stage.maskClamped.isFull) {
                    LabSlider(
                        "FEATHER", stage.maskClamped.feather, 0f, 1f, accent, dim,
                        { onFeather(index, it) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShadcnButton(
                    text = "Up", onClick = { onMove(index, -1) },
                    variant = ButtonVariant.Ghost, size = ButtonSize.Sm,
                    enabled = index > 0,
                )
                ShadcnButton(
                    text = "Down", onClick = { onMove(index, 1) },
                    variant = ButtonVariant.Ghost, size = ButtonSize.Sm,
                    enabled = index < stages.lastIndex,
                )
                ShadcnButton(
                    text = "Remove", onClick = { onRemove(index) },
                    variant = ButtonVariant.Ghost, size = ButtonSize.Sm,
                )
            }
        }
    }

    if (stages.isNotEmpty()) {
        Text(
            "Drag on the preview to set the area for stage ${selected + 1}. " +
                "Tap a stage to select it.",
            fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
        )
    }

    Spacer(Modifier.height(6.dp))
    Text("ADD STAGE", fontFamily = AppType.Sans, fontSize = 10.sp, color = dim)
    // A wrapping grid, not a Column. There are 39 chainable primitives, and a
    // Column gives each one its own full-width row, so the picker was a scroll
    // away from everything else on the screen.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.retrocam.catalog.lab.LabPrimitives.chainable.forEach { p ->
            ImportChip(p.displayName, dim) { onAdd(p.id) }
        }
    }
}

/**
 * What the last `.xmp` import actually did.
 *
 * Collapsed to one line by default, because the honest version of this report is
 * long: a typical preset lists seven mappings and fourteen dropped settings, and
 * putting all of that on screen unprompted would bury the controls. Expanded, it
 * names every key on both sides and says why each one was dropped.
 */
@Composable
private fun XmpReportBlock(
    result: com.retrocam.catalog.lab.XmpResult,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, dim.copy(alpha = 0.4f), RoundedCornerShape(ShadcnRadius.Lg))
            .clickable { open = !open }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${result.coveragePercent}% COVERED",
                fontFamily = AppType.Sans, fontSize = 11.sp, color = accent,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (open) "HIDE" else "DETAILS",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            // Grouped by tier rather than one flat list, because the question the
            // reader has is "what can I trust", and that is answered by tier.
            Text(
                "EXACT  ${result.exact.size}",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
            result.exact.forEach { k ->
                XmpLine(mark = "=", k.key, k.value, k.mapsTo, dim)
            }
            Text(
                "APPROXIMATE  ${result.approximate.size}",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
            result.approximate.forEach { k ->
                XmpLine(mark = "~", k.key, k.value, k.mapsTo, dim)
            }
            Text(
                "NOT SUPPORTED  ${result.ignored.size}",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
            result.ignored.forEach { k ->
                XmpLine(mark = "x", k.key, k.value, k.reason, dim)
            }
            // Shown, but not scored. A preset that asks for SupportsColor and
            // Copyright has not been imported worse for it, and counting those
            // in the denominator is what made a real preset report 9% covered.
            Text(
                "NOT A LOOK SETTING  ${result.metadata.size}  (not counted)",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
            result.metadata.forEach { k ->
                XmpLine(mark = "-", k.key, k.value, k.reason, dim)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "= exact, ~ close but not the same operation, x dropped, - not a " +
                    "look setting and not counted. The percentage is over the " +
                    "preset's look settings only. It counts keys, not visual " +
                    "weight: one tone curve is worth more than four sliders, and a " +
                    "number cannot say so.",
                fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Copy is the point of the report: the missing scopes are the
                // list of what a future version has to implement, and pasting it
                // somewhere is how that list gets written down. A report you can
                // only read is a report you have to photograph.
                ShadcnButton(
                    text = "Copy report",
                    onClick = { onCopy(reportText(result)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                )
                ShadcnButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        }
    }
}

/** One line of the import report. [detail] is where it went, or why not. */
@Composable
private fun XmpLine(
    mark: String,
    key: String,
    value: String,
    detail: String?,
    dim: androidx.compose.ui.graphics.Color,
) {
    Text(
        "$mark $key  $value  ->  ${detail.orEmpty()}",
        fontFamily = AppType.Sans, fontSize = 9.sp,
        color = if (mark == "x") dim else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The whole report as plain text, for pasting into an issue or a note.
 *
 * The missing keys come first, because that is the list anyone opening this
 * wants: the coverage number is the summary, the missing scopes are the work
 * list.
 */
private fun reportText(r: com.retrocam.catalog.lab.XmpResult): String = buildString {
    append("RetroCam XMP import: ${r.coveragePercent}% covered ")
    append("(${r.exact.size} exact, ${r.approximate.size} approximate, ")
    append("${r.ignored.size} not supported)\n")
    append("Percentages are over the ${r.lookKeys.size} keys in this preset that ")
    append("change the picture. The ${r.metadata.size} keys below that are not look ")
    append("settings are listed but not counted.\n")
    append("\nAPPLIED (${r.exact.size} exact)\n")
    r.exact.forEach { append("  ${it.key} = ${it.value}  ->  ${it.mapsTo}\n") }
    append("\nAPPROXIMATE (${r.approximate.size})\n")
    r.approximate.forEach { append("  ${it.key} = ${it.value}  ->  ${it.mapsTo}\n") }
    append("\nNOT SUPPORTED (${r.ignored.size}) - the work list\n")
    r.ignored.forEach { append("  ${it.key}: ${it.reason}\n") }
    append("\nNOT A LOOK SETTING (${r.metadata.size}) - not counted\n")
    r.metadata.forEach { append("  ${it.key}: ${it.reason}\n") }
}
