package com.retrocam.ui

import android.graphics.SurfaceTexture
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.retrocam.ui.components.ShadcnInput
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import com.retrocam.catalog.lab.LabMask
import android.opengl.GLSurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.retrocam.renderer.FilterRenderer
import com.retrocam.ui.components.ButtonSize
import com.retrocam.ui.components.ButtonVariant
import com.retrocam.ui.components.ShadcnButton
import com.retrocam.ui.theme.AppType
import com.retrocam.ui.theme.ShadcnRadius

/**
 * Icon size for the header's action buttons.
 *
 * Bigger than Material's 24dp default on purpose. These are the only controls
 * in the top bar and they sit on a black background, where a thin 24dp glyph
 * has very little to contrast against; at 22dp with a wider gap between the two
 * actions they are legible at arm's length, which is how a camera is held.
 */
private val HEADER_ICON = 22.dp

/**
 * The Filter Lab, as a screen of its own.
 *
 * It owns a second [FilterRenderer] and its own [GLSurfaceView], so it is a
 * genuinely separate viewfinder rather than a panel drawn over the camera's. Only
 * one of the two is ever composed: the mode branch in [CameraScreen] picks one or
 * the other, and `CameraController.bind` unbinds everything before rebinding, so
 * the camera follows whichever surface is currently alive.
 *
 * The two halves only ever meet through recipes, which live in DataStore. This
 * screen can save into that list and hand a recipe to the camera via
 * `useRecipeInCamera`; it cannot reach into the camera's filter selection any
 * other way.
 */
@Composable
fun LabScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The renderer posts onTextureReady on the main thread already.
    //
    // The callback MUST assign `texture`, and `texture` must be declared first:
    // it is how the camera learns this screen's SurfaceTexture, and without the
    // assignment the camera stays bound to nothing and the viewfinder is black.
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val renderer = remember { FilterRenderer(onTextureReady = { texture = it }) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    // A blank recipe has nothing worth saving, so the icon stays dimmed until
    // something has actually been changed.
    val canSave = viewModel.canSaveLab()
    /**
     * Which stage the viewfinder drag and the area controls act on.
     *
     * Without this the picker has nothing to point at: a freshly added stage has
     * a full-frame mask, so "the first stage that is not full frame" would be
     * nobody and the mask could never be drawn in the first place.
     */
    var selectedStage by remember { mutableStateOf(0) }
    // The name is asked for on demand, when Save is tapped, not while editing.
    // Held here because the header button opens it and the dialog reads it.
    var showSaveDialog by remember { mutableStateOf(false) }
    // -1 when there is nothing to select, and clamped otherwise.
    //
    // This must NOT be `coerceIn(0, (size - 1).coerceAtLeast(0))`: that yields 0
    // for an empty list, so every `index >= 0` guard downstream passes and the
    // first read of stages[0] throws. The Lab always opens with no stages, so
    // that crashed on launch rather than at some edge case.
    val stageIndex = if (state.labRecipe.stages.isEmpty()) {
        -1
    } else {
        selectedStage.coerceIn(0, state.labRecipe.stages.lastIndex)
    }
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val watermarks by viewModel.watermarks.collectAsStateWithLifecycle()

    // Every picker the Lab needs, created here rather than on the camera screen:
    // these belong to the Lab, and the camera screen has no use for them now.
    val markPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importWatermark(uri)
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importRecipe(uri)
    }
    // No mime filter: an .xmp is XMP-as-UTF8, so it arrives as text/xml,
    // application/octet-stream or nothing at all depending on who exported it.
    // Filtering on a type would hide the file on some devices rather than fail
    // usefully, so the extension is checked after reading instead.
    val xmpPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importXmp(uri)
    }
    // Exporting a curated preset as a shareable .xmp file. The pending id is
    // needed because CreateDocument's callback only hands back the uri.
    var xmpExportId by remember { mutableStateOf<String?>(null) }
    val xmpExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
            val id = xmpExportId
            xmpExportId = null
            if (id != null && uri != null) viewModel.exportXmp(id, uri)
        }

    // The Lab's own renderer, not the camera's. syncRenderer is what tells the
    // ViewModel which GL pipeline is live, so overlay uploads land on the
    // right one.
    LaunchedEffect(renderer) { viewModel.syncRenderer(renderer) }
    // The Lab's shaders need the same theme flag the camera's do.
    LaunchedEffect(state.paperTheme) {
        renderer.theme = if (state.paperTheme) 1f else 0f
    }
    LaunchedEffect(state.frontCamera, state.mirrorFront) { viewModel.syncRenderer(renderer) }

    // Let go of the GL pipeline when the Lab goes away, so switching back and
    // forth does not accumulate a context and a program cache per visit.
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    // Bind the camera to the Lab's surface while it is alive, and let go on the
    // way out so the camera screen can take it back.
    DisposableEffect(lifecycleOwner, texture) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    texture?.let { viewModel.attachPreview(lifecycleOwner, it) }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glView?.onPause()
                    viewModel.detachPreview()
                }
                Lifecycle.Event.ON_DESTROY -> viewModel.detachPreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releasePreview(texture)
        }
    }

    // The Lab preview always shows the draft, at the Lab's own intensity.
    LaunchedEffect(state.labRecipe, state.labBaseId, state.labIntensity) {
        renderer.setSpec(viewModel.effectiveSpec(), viewModel.effectiveIntensity())
    }

    BackHandler { viewModel.closeLab() }

    // A Box so the name dialog is an overlay on the whole screen rather than a
    // layout child. Inside the Column it was laid out below the panel and
    // scrolled with it, so tapping Save pushed a name field into the middle of
    // the Basic tab instead of appearing over the top.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---- header ----
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShadcnButton(
                onClick = { viewModel.closeLab() },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Icon,
                leading = {
                    Icon(
                        Icons.Filled.ArrowBack,
                        "Back to camera",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(HEADER_ICON),
                    )
                },
            )
            Text(
                "FILTER LAB",
                fontFamily = AppType.Sans,
                fontWeight = AppType.Strong,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // Save lives up here rather than at the bottom of a scrolling panel,
            // where it was a row you had to scroll back to find. The name is only
            // asked for once you actually tap it, so a slider you keep nudging
            // never puts a text field in the way.
            ShadcnButton(
                onClick = { showSaveDialog = true },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Icon,
                enabled = canSave,
                leading = {
                    Icon(
                        Icons.Filled.Save,
                        "Save this look",
                        tint = if (canSave) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.size(HEADER_ICON),
                    )
                },
            )
            // A real gap, not the 0dp padding a bare icon button uses. Save and
            // flip are two different actions on opposite sides of the screen's
            // purpose, and with nothing between them the save icon reads as part
            // of the flip control.
            Spacer(Modifier.width(14.dp))
            ShadcnButton(
                onClick = { viewModel.flipCamera() },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Icon,
                leading = {
                    Icon(
                        Icons.Filled.Autorenew,
                        "Flip camera",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(HEADER_ICON),
                    )
                },
            )
        }

        // ---- the Lab's own viewfinder ----
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(ShadcnRadius.Lg))
                .background(Color.Black)
                .onSizeChanged {
                    if (it.width > 0 && it.height > 0) {
                        renderer.viewAspect = it.width.toFloat() / it.height
                    }
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        preserveEGLContextOnPause = true
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        renderer.attach(this)
                        glView = this
                    }
                },


                modifier = Modifier.fillMaxSize(),
            )
            // After the AndroidView, not before: Compose draws later children on
            // top, and a picker underneath a GLSurfaceView would never see a
            // touch or show its outline.
            // ---- area mask picker ----
            // A drag over the preview sets the selected stage's mask, and the
            // outline is drawn on top so what you are selecting is visible while
            // the filter runs underneath it. The drag only arms once a stage is
            // selected, otherwise every tap on the viewfinder would start moving
            // a mask nobody asked for.
            MaskPicker(
                enabled = stageIndex >= 0,
                mask = state.labRecipe.stages.getOrNull(stageIndex)?.maskClamped,
                onDrag = { x0, y0, x1, y1 ->
                    if (stageIndex >= 0) {
                        viewModel.setLabStageMaskFromDrag(stageIndex, x0, y0, x1, y1)
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
            ViewfinderCorners(Modifier.fillMaxSize())
            Text(
                "LIVE PREVIEW",
                fontFamily = AppType.Sans,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- controls ----
        FilterLabPanel(
            tab = state.labTab,
            recipe = state.labRecipe,
            intensity = state.labIntensity,
            saved = state.labRecipes,
            selectedRecipeId = state.savedRecipeId,
            onTab = viewModel::setLabTab,
            onTemplate = viewModel::setLabTemplate,
            onKnob = viewModel::setLabKnob,
            onResetKnob = viewModel::resetLabKnob,
            onResetAll = viewModel::resetLabKnobs,
            onHsl = viewModel::setHslBand,
            onGrade = viewModel::setGrade,
            onBw = viewModel::setBw,
            onCal = viewModel::setCal,
            onDefringe = viewModel::setDefringe,
            onParametric = viewModel::setLabParametric,
            onClearCurves = viewModel::clearLabCurves,
            onEffect = viewModel::setLabEffect,
            onDuoColour = viewModel::setLabDuotoneColour,
            palette = palette,
            watermarks = watermarks,
            onPickWatermark = viewModel::setLabWatermark,
            onImportWatermark = { markPicker.launch("image/*") },
            onWatermarkAlpha = viewModel::setLabWatermarkAlpha,
            onExtractPalette = viewModel::extractPalette,
            onApplyPaletteColour = viewModel::applyPaletteColour,
            onSplitTint = viewModel::setLabSplitTint,
            onAddStage = viewModel::addLabStage,
            onRemoveStage = viewModel::removeLabStage,
            onMoveStage = viewModel::moveLabStage,
            onStageAmount = viewModel::setLabStageAmount,
            onStageParam = viewModel::setLabStageParam,
            onStageParamReset = viewModel::resetLabStageParam,
            onStageMaskDrag = viewModel::setLabStageMaskFromDrag,
            onClearStages = viewModel::clearLabStages,
            selectedStage = stageIndex,
            onSelectStage = { selectedStage = it },
            onShare = viewModel::shareRecipe,
            onExportXmp = { id ->
                xmpExportId = id
                val nm = state.labRecipes.firstOrNull { it.id == id }?.name ?: "PRESET"
                xmpExportPicker.launch("$nm.xmp")
            },
            onImportQr = { qrPicker.launch("image/*") },
            xmpReport = state.xmpReport,
            onImportXmp = { xmpPicker.launch("*/*") },
            onDismissReport = viewModel::clearXmpReport,
            onCopyReport = { text ->
                // The clipboard is the point: the missing scopes are the list of
                // what a future version has to implement, and a report you can
                // only photograph is a report nobody acts on.
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("RetroCam XMP report", text))
                Feedback.info(context, "Report copied")
            },
            onDeleteWatermark = viewModel::deleteWatermark,
            onUse = viewModel::useRecipeInCamera,
            onDeleteSelected = viewModel::clearLabSelection,
            onIntensity = viewModel::setLabIntensity,
            onEdit = viewModel::editLabRecipe,
            onDelete = viewModel::deleteLabRecipe,
            )
        }

        // The name is only ever asked for at the moment of saving, so the panel
        // has no text field in it at all while you are adjusting sliders.
        if (showSaveDialog) {
            SaveNameDialog(
                initial = state.labName.ifBlank { state.labBaseId.uppercase() },
                onDismiss = { showSaveDialog = false },
                onConfirm = { name ->
                    viewModel.setLabName(name)
                    viewModel.saveLab()
                    showSaveDialog = false
                },
            )
        }
    }
}

/**
 * Asks for a name, then saves.
 *
 * Split from [ShadcnDialog] because that one is a list picker and this needs a
 * text field; the shell is copied from it so the two do not look like different
 * applications. Enter saves, so the keyboard's own action key is the same as
 * tapping Save.
 */
@Composable
private fun SaveNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
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
                .clip(RoundedCornerShape(ShadcnRadius.Lg))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(20.dp),
        ) {
            Text(
                "NAME THIS LOOK",
                fontFamily = AppType.Sans,
                fontWeight = AppType.Strong,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            ShadcnInput(
                value = name,
                onValueChange = { name = it },
                placeholder = "Name it",
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadcnButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                ShadcnButton(
                    text = "Save",
                    onClick = { onConfirm(name) },
                    variant = ButtonVariant.Default,
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Draws a stage's area mask over the live preview and turns a drag into one.
 *
 * Frame uv has y growing upward, the same way the shader sees it, so the flip
 * here is the only place the two coordinate systems meet. Getting it wrong
 * produces a mask mirrored top-to-bottom from the one the user drew, which is
 * the easiest way to make the whole feature look broken.
 */
@Composable
private fun MaskPicker(
    enabled: Boolean,
    mask: LabMask?,
    onDrag: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Held in local state while dragging so the outline tracks the finger, then
    // handed to the view model on release. Writing straight to the recipe would
    // round-trip through the renderer on every pointer move.
    // Start and current, both in frame uv. Building the rect from the two is the
    // whole job: storing only a corner and a size means a drag back over itself
    // would need signed extents, which LabMask deliberately does not accept.
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var cursor by remember { mutableStateOf<Offset?>(null) }

    val shape = mask?.shape ?: com.retrocam.catalog.lab.MaskShape.RECT
    val feather = mask?.feather ?: 0f

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun toUv(p: Offset): Offset {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w <= 0f || h <= 0f) return Offset(0f, 0f)
                    return Offset(
                        (p.x / w).coerceIn(0f, 1f),
                        (1f - p.y / h).coerceIn(0f, 1f),
                    )
                }
                detectDragGestures(
                    onDragStart = { anchor = toUv(it); cursor = toUv(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        cursor = toUv(change.position)
                    },
                    onDragEnd = {
                        val a = anchor
                        val c = cursor
                        if (a != null && c != null) {
                            onDrag(
                                minOf(a.x, c.x), minOf(a.y, c.y),
                                maxOf(a.x, c.x), maxOf(a.y, c.y),
                            )
                        }
                        anchor = null
                        cursor = null
                    },
                    onDragCancel = { anchor = null; cursor = null },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val a = anchor
        val c = cursor
        val shown = if (a != null && c != null) {
            LabMask(
                shape = shape,
                x = minOf(a.x, c.x), y = minOf(a.y, c.y),
                width = kotlin.math.abs(c.x - a.x), height = kotlin.math.abs(c.y - a.y),
                feather = feather,
            )
        } else {
            mask
        }
        if (enabled && shown != null && !shown.isFull) {
            Canvas(Modifier.matchParentSize()) {
                val r = shown.rect()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                fun px(u: Float) = u * w
                fun py(v: Float) = (1f - v) * h
                val tl = Offset(px(r[0]), py(r[3]))
                val br = Offset(px(r[2]), py(r[1]))
                drawRect(
                    color = Color.White,
                    topLeft = tl,
                    size = androidx.compose.ui.geometry.Size(
                        (br.x - tl.x).coerceAtLeast(1f),
                        (br.y - tl.y).coerceAtLeast(1f),
                    ),
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}
