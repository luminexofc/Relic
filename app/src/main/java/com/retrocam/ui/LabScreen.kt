package com.retrocam.ui

import android.graphics.SurfaceTexture
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
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
import androidx.compose.ui.platform.LocalConfiguration
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
 * Width of the control pane in the Lab's landscape layout.
 *
 * Wide enough for a slider with a readable label and its value, narrow enough
 * to leave the viewfinder the larger share. 380dp is about half of a landscape
 * phone, so the preview still dominates.
 */
private val LAB_PANE_W = 380.dp

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
    // Read off state, not off a ViewModel call. canSaveLab() reads the same
    // backing flow, but as a plain call it is not a State observation, so the
    // header kept the value from first composition and the icon stayed dimmed
    // after the first slider drag - the "save button feels completely dead"
    // report. Deriving from `state` recomposes on every draft change.
    val canSave = !state.labRecipe.isIdentity
    // Declared up here because both the renderer flag and the rebind below
    // key off it, and a local val has to precede its use.
    val displayOrientation = LocalConfiguration.current.orientation
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
    // Advanced-tab save: the draft itself as an .xmp file, named first.
    // CreateDocument only returns the uri, so the name waits here meanwhile.
    var draftExportName by remember { mutableStateOf<String?>(null) }
    val draftExportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
            val name = draftExportName
            draftExportName = null
            if (name != null && uri != null) viewModel.saveDraftXmp(name, uri)
        }

    // The Lab's own renderer, not the camera's. syncRenderer is what tells the
    // ViewModel which GL pipeline is live, so overlay uploads land on the
    // right one.
    LaunchedEffect(renderer) { viewModel.syncRenderer(renderer) }
    // The Lab's shaders need the same theme flag the camera's do.
    // The renderer takes back the quarter turn a landscape viewfinder picks up
    // from a still-portrait surface transform. It needs to know the orientation
    // for that, and it is the same answer the layout is already asking for.
    LaunchedEffect(displayOrientation) {
        renderer.landscapePreview = displayOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }
    LaunchedEffect(state.paperTheme) {
        renderer.theme = if (state.paperTheme) 1f else 0f
    }
    LaunchedEffect(state.frontCamera, state.mirrorFront) { viewModel.syncRenderer(renderer) }

    // Let go of the GL pipeline when the Lab goes away, so switching back and
    // forth does not accumulate a context and a program cache per visit.
    DisposableEffect(renderer) {
        onDispose { renderer.release() }
    }

    // Bind the camera to the Lab's own SurfaceTexture as soon as that texture
    // exists, AND again whenever the display orientation changes.
    //
    // The bind itself was missing, and the preview was sampling a texture that
    // had never received a frame: the camera screen's dispose calls
    // detachPreview() and nothing ever re-bound the camera here, because the
    // ON_RESUME branch below only fires on a real resume, and switching between
    // Camera and Lab is a composable branch inside one activity rather than a
    // new one. What the shader sampled was uninitialised buffer memory, which
    // is the blocky mess the viewfinder was showing and which no amount of
    // aspect-ratio work could have fixed. CameraScreen has always done this; the
    // Lab did not.
    //
    // The orientation key is the second half of the same story. The rotation the
    // renderer applies comes from the SurfaceTexture's own transform, which
    // CameraX fills in for the display orientation current when it issues the
    // surface request. Rotate the phone and the transform has to be re-issued
    // or the shader keeps applying the portrait one, and the viewfinder ends up
    // showing the scene turned on its side - which is what the landscape Lab was
    // doing while the surrounding UI stayed upright. Re-binding on orientation
    // change is one unbindAll plus one bind, once per rotation, and it does not
    // depend on CameraX having noticed the change for this particular surface.
    LaunchedEffect(texture, displayOrientation) {
        texture?.let { viewModel.attachPreview(lifecycleOwner, it) }
    }

    // Bind the camera to the Lab's surface while it is alive, and let go on the
    // way out so the camera screen can take it back. ON_RESUME still matters for
    // coming back from the background, where CameraX was torn down with the
    // process but the texture survived.
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

    // The viewfinder, factored out so portrait and landscape can place the same
    // thing differently. A local composable captures this scope directly, so
    // there is no fifteen-parameter helper to keep in step with the call site.
    //
    // The box takes the camera's aspect ratio rather than whatever shape the
    // caller hands it, exactly as the camera screen's finder does. It did not
    // before, and the renderer covers the feed to the box: a 9:16 feed in a
    // wide landscape box is scaled about 2.6x to fill it, so the preview was a
    // blocky over-zoomed crop. In portrait the fixed 230dp box had the same
    // mismatch, just a less obvious one.
    val previewAspect = previewAspect(state.viewAspect)
    @Composable
    fun previewPane(mod: Modifier) {
        Box(mod) {
            // The centring has to be HERE, on the constraints box, not on the
            // outer one. The outer Box's only child is this fillMaxSize() box,
            // so contentAlignment up there centred something that already filled
            // the space and did nothing, while the fitted box inside defaulted
            // to TopStart - which is why the portrait preview sat hard against
            // the left edge with a wide empty band to its right.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val w = minOf(maxWidth, maxHeight * previewAspect)
                val h = w / previewAspect
                Box(
                    Modifier
                        .width(w)
                        .height(h)
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
            }
        }
    }

    // The control panel, factored out the same way so both orientations place
    // one definition rather than two copies of forty callbacks.
    @Composable
    fun labPanel(mod: Modifier) {
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
            onShare = viewModel::shareRecipeXmp,
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
            onUseDraft = viewModel::useDraftInCamera,
            onDeleteSelected = viewModel::clearLabSelection,
            onIntensity = viewModel::setLabIntensity,
            onEdit = viewModel::editLabRecipe,
            onDelete = viewModel::deleteLabRecipe,
            modifier = mod,
        )
    }

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
            //
            // A labelled button rather than a bare save icon, so its promise is
            // readable: the draft leaves as a shareable .xmp file AND is kept in
            // the preset list under the same name. Always clickable, never
            // silently dead: the tap answers even when there is nothing to save.
            ShadcnButton(
                text = "SAVE XMP",
                onClick = {
                    if (!canSave) Feedback.info(context, "Nothing to save yet")
                    else showSaveDialog = true
                },
                variant = if (canSave) ButtonVariant.Default else ButtonVariant.Outline,
                size = ButtonSize.Sm,
            )
            // A real gap, not the 0dp padding a bare icon button uses. Save and
            // flip are two different actions on opposite sides of the screen's
            // purpose, and with nothing between them the save control reads as
            // part of the flip control.
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

        // ---- viewfinder + controls ----
        // Portrait stacks them: a fixed-height preview over a scrolling panel.
        // Landscape puts them side by side, because a 230dp preview plus the
        // panel leaves almost no height for the sliders on a phone turned on
        // its side, and the panel is the part that needs the room.
        if (isLandscape()) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    previewPane(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
                labPanel(
                    Modifier
                        .width(LAB_PANE_W)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // The preview takes the lion's share of the height so the
                // aspect-fitted box is as wide as it can be. At a fixed 230dp it
                // could only ever be 172dp wide on a 393dp screen - 48% of the
                // usable width - which read as the viewfinder having shrunk
                // rather than as it being correctly proportioned. At 1.5 against
                // the panel's 1 the box comes out 333dp wide, 92% of the
                // available width, and the panel still has 296dp to scroll in.
                previewPane(
                    Modifier
                        .fillMaxWidth()
                        .weight(1.5f)
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                labPanel(Modifier.fillMaxWidth().weight(1f))
            }
        }

        }
        // The name dialog is a direct child of the outer Box - an overlay over
        // the whole screen - and NOT a child of the Column above. Inside the
        // Column it was measured with the leftover height after the viewfinder
        // and the panel had taken theirs, which is zero, so tapping save
        // composed an invisible dialog: the "save button is completely dead"
        // report, with no error anywhere because nothing had actually failed.
        if (showSaveDialog) {
            SaveNameDialog(
                initial = state.labName.ifBlank { state.labBaseId.uppercase() },
                title = "NAME THIS XMP",
                onDismiss = { showSaveDialog = false },
                onConfirm = { name ->
                    viewModel.setLabName(name)
                    draftExportName = name.trim().uppercase()
                        .take(com.retrocam.catalog.lab.SavedRecipe.MAX_NAME)
                    showSaveDialog = false
                    draftExportPicker.launch("${draftExportName}.xmp")
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
    title: String = "NAME THIS LOOK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
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
                // Same reason as ShadcnDialog: full width in landscape is most
                // of the screen, which is not what a name prompt should be.
                .widthIn(max = 420.dp)
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
                title,
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
