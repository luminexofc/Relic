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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Camera
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.catalog.lab.BwMix
import com.retrocam.catalog.lab.Calibration
import com.retrocam.catalog.lab.ColorGrade
import com.retrocam.catalog.lab.Defringe
import com.retrocam.catalog.lab.Hsl
import com.retrocam.catalog.lab.LAB_EFFECTS
import com.retrocam.catalog.lab.LabKnob
import com.retrocam.catalog.lab.LabKnobGroup
import com.retrocam.catalog.lab.ToneCurve
import com.retrocam.catalog.lab.effectValue
import com.retrocam.catalog.lab.hslBand
import com.retrocam.catalog.lab.knobValue
import com.retrocam.catalog.lab.withKnob
import com.retrocam.catalog.lab.LabTemplates
import com.retrocam.catalog.lab.SavedRecipe
import com.retrocam.ui.components.ButtonSize
import com.retrocam.ui.components.ButtonVariant
import com.retrocam.ui.components.ShadcnButton
import com.retrocam.ui.theme.ShadcnColor
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
    recipe: com.retrocam.catalog.lab.LabRecipe,
    intensity: Float,
    saved: List<SavedRecipe>,
    onTab: (Int) -> Unit,
    onTemplate: (String?) -> Unit,
    onKnob: (Int, Float) -> Unit,
    onResetKnob: (Int) -> Unit,
    onResetAll: () -> Unit,
    onHsl: (Int, Int, Float) -> Unit,
    onGrade: (Int, Float) -> Unit,
    onBw: (Int, Float) -> Unit,
    onCal: (Int, Float) -> Unit,
    onDefringe: (Int, Float) -> Unit,
    onParametric: (Int, Float) -> Unit,
    onClearCurves: () -> Unit,
    onEffect: (Int, Float) -> Unit,
    onDuoColour: (Boolean, Int) -> Unit,
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
    onStageMaskDrag: (Int, Float, Float, Float, Float) -> Unit,
    onClearStages: () -> Unit,
    selectedStage: Int,
    onSelectStage: (Int) -> Unit,
    onShare: (String) -> Unit,
    onExportXmp: (String) -> Unit,
    onImportQr: () -> Unit,
    xmpReport: com.retrocam.catalog.lab.XmpResult?,
    onImportXmp: () -> Unit,
    onDismissReport: () -> Unit,
    onCopyReport: (String) -> Unit,
    onDeleteWatermark: (String) -> Unit,
    selectedRecipeId: String?,
    onUse: (String) -> Unit,
    onDeleteSelected: () -> Unit,
    onIntensity: (Float) -> Unit,
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
            // The six Lightroom-style groups, chosen from a row of icons.
            //
            // This replaces eleven stacked "GROUP .... SHOW" rows, one per
            // section, all collapsed on a blank recipe - so the tab opened on a
            // list of labels with no controls on it and you had to tap your way
            // down to find anything. Now there is one row of icons, the group
            // you picked is the only thing on screen, and every control in it is
            // immediately visible and draggable.
            //
            // Related controls share a group: curves sit with the tone sliders
            // they modify, the mixer and the 3-way grade sit with the colour
            // knobs, defringe with the detail pass it belongs to. Six groups is
            // as many as fit a phone row without the labels going unreadable.
            var group by remember { mutableStateOf(LabGroup.LIGHT) }
            GroupRail(group, { group = it }, accent, dim)
            Spacer(Modifier.height(4.dp))
            when (group) {
                LabGroup.LIGHT -> {
                    KnobSliders(LabKnobGroup.LIGHT, recipe, accent, dim, onKnob, onResetKnob)
                    GroupLabel("CURVES", dim)
                    CurveSliders(recipe, accent, dim, onParametric, onClearCurves)
                }
                LabGroup.COLOR -> {
                    KnobSliders(LabKnobGroup.COLOR, recipe, accent, dim, onKnob, onResetKnob)
                    if (recipe.grayscale > 0f) {
                        GroupLabel("B&W MIX", dim)
                        BwSliders(recipe, accent, dim, onBw)
                    }
                    GroupLabel("COLOR MIX", dim)
                    HslSliders(recipe, accent, dim, onHsl)
                    GroupLabel("COLOR GRADING", dim)
                    GradeSliders(recipe, accent, dim, onGrade)
                    GroupLabel("CALIBRATION", dim)
                    CalSliders(recipe, accent, dim, onCal)
                }
                LabGroup.EFFECTS -> {
                    KnobSliders(LabKnobGroup.EFFECTS, recipe, accent, dim, onKnob, onResetKnob)
                    // The stylize effects the Lab had always kept below the
                    // groups, and the two palettes that drive duotone.
                    StylizeSliders(recipe, accent, dim, onEffect)
                    if (recipe.duotone > 0f) {
                        DuotoneColours(recipe, accent, dim, onDuoColour)
                    }
                    if (recipe.splitAmount > 0f) {
                        GroupLabel("SPLIT TINT", dim)
                        TintRow("SHADOW", recipe.shadowTint, accent, dim) { onSplitTint(true, it) }
                        TintRow("HIGHLIGHT", recipe.highlightTint, accent, dim) { onSplitTint(false, it) }
                    }
                    Spacer(Modifier.height(8.dp))
                    GroupLabel("PALETTE FROM FRAME", dim)
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
                        // Only offered once duotone is actually on, because
                        // applying a colour with duotone at zero changes nothing
                        // and reads as a dead row of swatches.
                        if (palette.isNotEmpty() && recipe.duotone > 0f) {
                            SwatchRow(palette, -1, accent, dim) { onApplyPaletteColour(it, true) }
                        }
                    }
                }
                LabGroup.DETAIL -> {
                    KnobSliders(LabKnobGroup.DETAIL, recipe, accent, dim, onKnob, onResetKnob)
                    GroupLabel("DEFRINGE", dim)
                    DefringeSliders(recipe, accent, dim, onDefringe)
                }
                LabGroup.OPTICS -> {
                    KnobSliders(LabKnobGroup.OPTICS, recipe, accent, dim, onKnob, onResetKnob)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ShadcnButton(
                    text = "Reset all",
                    onClick = onResetAll,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
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
                            onClick = { onExportXmp(r.id) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Icon,
                            modifier = Modifier.size(30.dp),
                            leading = {
                                Icon(
                                    Icons.Filled.Description,
                                    "Save ${r.name} as XMP",
                                    tint = MaterialTheme.colorScheme.onSurface,
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

/**
 * A representative colour for each of the eight mixer bands, in [Hsl.BAND_NAMES]
 * order.
 *
 * The picker is these swatches rather than the band names: eight rows of
 * "RED HUE / SAT / LUM" told you the name of a band you could already see, and
 * the colour itself is the thing the slider is about. Aqua and magenta are the
 * two that are not obvious from the word alone, which is why the caption stays.
 */
private val BAND_SWATCHES = listOf(
    0xFFE53935.toInt(), 0xFFF57C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
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
 * The six groups, as icon tiles.
 *
 * A group is a category of controls, not a collapsible: picking one shows all
 * of it. The icon is the whole affordance, which is why the title is short.
 */
private enum class LabGroup(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    LIGHT("Light", Icons.Filled.WbSunny),
    COLOR("Color", Icons.Filled.Palette),
    EFFECTS("Effects", Icons.Filled.AutoAwesome),
    DETAIL("Detail", Icons.Filled.Tune),
    OPTICS("Optics", Icons.Filled.Camera),
}

/** The horizontal icon rail that picks a group. */
@Composable
private fun GroupRail(
    selected: LabGroup,
    onPick: (LabGroup) -> Unit,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp),
    ) {
        items(LabGroup.entries.toList()) { g ->
            GroupTile(g, g == selected, accent, dim) { onPick(g) }
        }
    }
}

/**
 * One group in the rail: a bordered square holding its icon, with the name
 * under it.
 *
 * Selection is a tint plus a border rather than a filled pill, so the row reads
 * as six peers you choose between, not as a tab bar with one item highlighted.
 */
@Composable
private fun GroupTile(
    group: LabGroup,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(56.dp).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(shape)
                .background(if (selected) accent.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (selected) accent else dim.copy(alpha = 0.3f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                group.icon,
                group.title,
                tint = if (selected) accent else dim,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            group.title,
            fontFamily = AppType.Sans,
            fontSize = 9.sp,
            color = if (selected) accent else dim,
            maxLines = 1,
        )
    }
}

/**
 * A quiet heading inside a group, for the sub-areas that used to be their own
 * collapsible sections: color mix inside color, curves inside light, defringe
 * inside detail. A label, not a control - the sub-areas are not hidden any more.
 */
@Composable
private fun GroupLabel(text: String, dim: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontFamily = AppType.Sans,
        fontSize = 10.sp,
        color = dim.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * The sliders for one [LabKnobGroup].
 *
 * Knobs are matched to their enum entry by label, so the display order in
 * [com.retrocam.catalog.lab.LabKnobGroup] is the only place a slider's position
 * is written down.
 */
@Composable
private fun KnobSliders(
    group: LabKnobGroup,
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onKnob: (Int, Float) -> Unit,
    onResetKnob: (Int) -> Unit,
) {
    group.knobs.forEach { k ->
        val knob = LabKnob.byLabel(k.label) ?: return@forEach
        val i = LabKnob.entries.indexOf(knob)
        LabSlider(
            k.label, recipe.knobValue(knob), k.min, k.max, accent, dim,
            { onKnob(i, it) },
            neutral = k.neutral,
            onReset = { onResetKnob(i) },
        )
    }
}

/**
 * Parametric curves. Full spline editing is a screen of its own; the four
 * sliders are the control set presets actually use, and they rebuild the
 * composite run.
 */
@Composable
private fun CurveSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onParametric: (Int, Float) -> Unit,
    onClear: () -> Unit,
) {
    val group = remember(recipe.toneCurves) { ToneCurve.parseGroup(recipe.toneCurves) }
    val names = listOf("Composite", "Red", "Green", "Blue")
    group.forEachIndexed { i, ch ->
        Text(
            "${names[i]}: ${if (ch == null) "linear" else "curved"}",
            fontFamily = AppType.Sans, fontSize = 9.sp, color = dim,
        )
    }
    var pars by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f, 0f)) }
    listOf("PAR SHADOWS", "PAR DARKS", "PAR LIGHTS", "PAR HIGHLIGHTS").forEachIndexed { i, label ->
        LabSlider(label, pars[i], -1f, 1f, accent, dim, {
            val cur = pars.copyOf(); cur[i] = it; pars = cur; onParametric(i, it)
        }, neutral = 0f, onReset = { val cur = pars.copyOf(); cur[i] = 0f; pars = cur; onParametric(i, 0f) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ShadcnButton(text = "Clear curves", onClick = onClear, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
    }
}

/**
 * Adobe's Color Mixer, one band at a time.
 *
 * All eight bands times three channels is 24 sliders: a screen and a half of
 * near-identical rows whose only difference is a small capital letter at the
 * start of each label. Choosing the band first and then showing its three
 * channels turns that wall into a row of eight names and three sliders.
 *
 * A dot marks any band the recipe has actually moved, so the picker also
 * answers "which colours did this preset change" before you go looking. It
 * opens on the first band that was touched, so an imported look does not land
 * you on a Red that was never adjusted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HslSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onHsl: (Int, Int, Float) -> Unit,
) {
    // Deliberately not keyed on recipe.hsl: this would re-run on every drag and
    // could jump the selection to a different band mid-adjustment.
    val firstMoved = (0 until Hsl.BANDS).firstOrNull { b ->
        (0..2).any { ch -> recipe.hslBand(b, ch) != 0f }
    } ?: 0
    var band by remember { mutableStateOf(firstMoved) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Hsl.BAND_NAMES.forEachIndexed { b, name ->
            val moved = (0..2).any { ch -> recipe.hslBand(b, ch) != 0f }
            BandSwatch(name, BAND_SWATCHES[b], b == band, moved, accent, dim) { band = b }
        }
    }
    Spacer(Modifier.height(4.dp))
    val name = Hsl.BAND_NAMES[band]
    listOf("HUE" to 0, "SAT" to 1, "LUM" to 2).forEach { (label, ch) ->
        LabSlider("$name $label", recipe.hslBand(band, ch), -1f, 1f, accent, dim,
            { onHsl(band, ch, it) }, neutral = 0f, onReset = { onHsl(band, ch, 0f) })
    }
}

/**
 * One hue band, shown as the colour it stands for.
 *
 * Selection is a ring rather than a fill change, because the fill is the band's
 * own colour and tinting it would destroy the thing you are identifying it by.
 * The underline marks a band the recipe has moved, so an imported preset's
 * mixer is still readable at a glance now that the values are hidden.
 */
@Composable
private fun BandSwatch(
    label: String,
    argb: Int,
    selected: Boolean,
    moved: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) accent else dim.copy(alpha = 0.3f),
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .size(width = 14.dp, height = 2.dp)
                .clip(CircleShape)
                .background(if (moved) accent else androidx.compose.ui.graphics.Color.Transparent),
        )
        Text(
            label,
            fontFamily = AppType.Sans,
            fontSize = 8.sp,
            color = if (selected) accent else dim,
        )
    }
}

/**
 * A small selectable chip, for picking what the sliders underneath apply to.
 *
 * Used where there is no colour to show for the choice - the three tone ranges
 * of a grade, the three primaries of a calibration - so a word is the honest
 * label. One row of these replaces a flat list of every channel, which is the
 * same fix the mixer got.
 */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    moved: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .border(1.dp, if (selected) accent else dim.copy(alpha = 0.35f), RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (moved) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (selected) accent else dim))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontFamily = AppType.Sans,
            fontSize = 10.sp,
            color = if (selected) accent else dim,
        )
    }
}

/**
 * The 3-way colour grade, one tone range at a time.
 *
 * Eight flat sliders where the first six are really three pairs: shadows,
 * midtones and highlights, each with a hue and a saturation. Showing the pairs
 * flat means scrolling past hue, sat, hue, sat to compare one range with
 * another. Picking the range shows its two channels, and the two global
 * controls that are not per-range stay visible underneath because they affect
 * all three at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onGrade: (Int, Float) -> Unit,
) {
    val g = remember(recipe.colorGrade) { recipe.gradeArray() ?: ColorGrade.defaults() }
    val firstMoved = (0..2).firstOrNull { r -> g[r * 2] != 0f || g[r * 2 + 1] != 0f } ?: 0
    var range by remember { mutableStateOf(firstMoved) }
    val rangeNames = listOf("SHADOWS", "MIDTONES", "HIGHLIGHTS")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rangeNames.forEachIndexed { r, name ->
            ChoiceChip(name, r == range, g[r * 2] != 0f || g[r * 2 + 1] != 0f, accent, dim) { range = r }
        }
    }
    Spacer(Modifier.height(4.dp))
    listOf("HUE" to 0, "SAT" to 1).forEach { (label, ch) ->
        val slot = range * 2 + ch
        LabSlider("${rangeNames[range]} $label", g[slot], 0f, 1f, accent, dim, { onGrade(slot, it) },
            neutral = 0f, onReset = { onGrade(slot, 0f) })
    }
    // Blend and balance shape how the three ranges meet, so they apply to all of
    // them and stay out of the range picker rather than hiding in it.
    Spacer(Modifier.height(6.dp))
    LabSlider("BLEND", g[6], 0f, 1f, accent, dim, { onGrade(6, it) },
        neutral = 0.5f, onReset = { onGrade(6, 0.5f) })
    LabSlider("BALANCE", g[7], -1f, 1f, accent, dim, { onGrade(7, it) },
        neutral = 0f, onReset = { onGrade(7, 0f) })
}

/** The channel mix that turns a grayscale image into a toned black and white. */
@Composable
private fun BwSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onBw: (Int, Float) -> Unit,
) {
    val cur = remember(recipe.bwMix) { recipe.bwArray() ?: FloatArray(BwMix.VALUES) }
    Hsl.BAND_NAMES.forEachIndexed { b, name ->
        LabSlider(name, cur[b], -1f, 1f, accent, dim, { onBw(b, it) }, neutral = 0f, onReset = { onBw(b, 0f) })
    }
}

/**
 * Adobe's Calibration, one primary at a time.
 *
 * The same pair-per-choice shape as the grade: three primaries, each with a hue
 * and a saturation, so six interleaved rows become three chips and two sliders.
 * Swatches carry the primary's colour, which is the one thing here that is
 * literally a colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onCal: (Int, Float) -> Unit,
) {
    val parts = remember(recipe.calibration) { recipe.calibrationParts() }
    val h = parts?.first ?: FloatArray(3)
    val s = parts?.second ?: FloatArray(3)
    val firstMoved = (0..2).firstOrNull { i -> h[i] != 0f || s[i] != 0f } ?: 0
    var primary by remember { mutableStateOf(firstMoved) }
    val names = listOf("RED", "GREEN", "BLUE")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEachIndexed { i, name ->
            ChoiceChip(name, i == primary, h[i] != 0f || s[i] != 0f, accent, dim) { primary = i }
        }
    }
    Spacer(Modifier.height(4.dp))
    // Slots are interleaved hue, sat, hue, sat, so slot 2i is the hue.
    LabSlider("${names[primary]} HUE", h[primary], -1f, 1f, accent, dim, { onCal(primary * 2, it) },
        neutral = 0f, onReset = { onCal(primary * 2, 0f) })
    LabSlider("${names[primary]} SAT", s[primary], -1f, 1f, accent, dim, { onCal(primary * 2 + 1, it) },
        neutral = 0f, onReset = { onCal(primary * 2 + 1, 0f) })
}

/** Chromatic aberration removal, as two hue ranges with an amount each. */
@Composable
private fun DefringeSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onDefringe: (Int, Float) -> Unit,
) {
    val cur = remember(recipe.defringe) { recipe.defringeArray() ?: Defringe.defaults() }
    val labels = listOf("PURPLE AMT", "PURPLE LO", "PURPLE HI", "GREEN AMT", "GREEN LO", "GREEN HI")
    labels.forEachIndexed { i, label ->
        LabSlider(label, cur[i], 0f, 1f, accent, dim, { onDefringe(i, it) }, neutral = Defringe.defaults()[i],
            onReset = { onDefringe(i, Defringe.defaults()[i]) })
    }
}

/** The stylize effects: blur, glitch, duotone. The rest of the effects are knobs. */
@Composable
private fun StylizeSliders(
    recipe: com.retrocam.catalog.lab.LabRecipe,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onEffect: (Int, Float) -> Unit,
) {
    listOf(3, 4, 5).forEach { i ->
        val e = LAB_EFFECTS[i]
        LabSlider(
            e.label, recipe.effectValue(i), e.min, e.max, accent, dim,
            { onEffect(i, it) },
            neutral = 0f,
            onReset = { onEffect(i, 0f) },
        )
    }
}

/**
 * A named row of tint swatches.
 *
 * Two bare swatch rows stacked are indistinguishable from each other, and which
 * one is which only becomes obvious after you have already applied a colour to
 * the wrong end of the tone curve.
 */
@Composable
private fun TintRow(
    label: String,
    current: Int,
    accent: androidx.compose.ui.graphics.Color,
    dim: androidx.compose.ui.graphics.Color,
    onPick: (Int) -> Unit,
) {
    Text(label, fontFamily = AppType.Sans, fontSize = 9.sp, color = dim)
    SwatchRow(TINT_SWATCHES, current, accent, dim, onPick)
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

/** Adds or removes [t], for a collapsible group's open state. */
private fun Set<String>.toggle(t: String): Set<String> =
    if (t in this) this - t else this + t

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
