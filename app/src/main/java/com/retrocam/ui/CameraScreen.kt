@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.retrocam.ui

import com.retrocam.ui.theme.RetroType

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrocam.catalog.FilterCatalog
import com.retrocam.catalog.matches
import com.retrocam.renderer.FilterRenderer
import com.retrocam.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2

internal val ASPECT_RATIOS = listOf(3f / 4f, 9f / 16f, 1f, 2f / 3f, 3f / 2f)
internal val ASPECT_LABELS = listOf("4:3", "16:9", "1:1", "2:3", "3:2")
internal val TIMER_LABELS = listOf("Off", "3s", "5s", "10s")
internal val TIMER_VALUES = listOf(0, 3, 5, 10)

/**
 * Reference-style camera home: title bar, quick toolbar, rounded viewfinder,
 * filter strip, Photo/Video modes, gallery + shutter + flip.
 */
@Composable
fun CameraScreen(viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermission = grants[Manifest.permission.CAMERA] == true
        micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
    }

    if (!hasPermission) {
        PermissionEmptyState(
            onGrant = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                )
            },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    },
                )
            },
        )
        return
    }

    // Renderer posts onTextureReady on the main thread already.
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val renderer = remember { FilterRenderer(onTextureReady = { texture = it }) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }
    var galleryOpen by remember { mutableStateOf(false) }

    if (state.settingsOpen) {
        BackHandler { viewModel.setSettingsOpen(false) }
        SettingsScreen(viewModel = viewModel, thumbnails = thumbnails)
        return
    }

    if (galleryOpen) {
        BackHandler { galleryOpen = false }
        GalleryScreen(saveDir = state.saveDir, onBack = { galleryOpen = false })
        return
    }

    var viewPx by remember { mutableStateOf(IntSize.Zero) }
    var reticleAt by remember { mutableStateOf<Offset?>(null) }
    var reticleKey by remember { mutableStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var optionSheet by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    // Filter Lab: import a Hald CLUT. GetContent rather than OpenDocument so the
    // user is not forced to grant persistent access to a whole provider.
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importLut(uri, renderer)
    }
    val markPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importWatermark(uri)
    }
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val watermarks by viewModel.watermarks.collectAsStateWithLifecycle()
    var galleryThumb by remember { mutableStateOf<Bitmap?>(null) }
    var orientBucket by remember { mutableStateOf(0) }
    var orientAngle by remember { mutableFloatStateOf(0f) }
    val hintSeen by viewModel.stripHintSeen.collectAsStateWithLifecycle()
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current

    // Shutter press spring (Animations.md §2.1).
    val shutterInteraction = remember { MutableInteractionSource() }
    val shutterPressed by shutterInteraction.collectIsPressedAsState()
    val shutterScale by animateFloatAsState(
        if (shutterPressed) 0.88f else 1f,
        animationSpec = if (reducedMotion) tween(80) else shutterSpring(),
        label = "shutter",
    )
    // Flip swing (Animations.md §2.4): edge-on, rebind behind, settle back.
    // The swing MUST end at 0: ending at 180 leaves the whole viewfinder
    // (GL surface included) permanently mirrored on front camera.
    val flipRot = remember { Animatable(0f) }
    val flipScope = rememberCoroutineScope()
    fun onFlip() {
        if (reducedMotion) {
            viewModel.flipCamera()
            return
        }
        flipScope.launch {
            flipRot.animateTo(90f, tween(150))
            viewModel.flipCamera()
            flipRot.animateTo(0f, tween(150))
        }
    }

    // Device rotation → chrome stays upright (shortest-path animated).
    DisposableEffect(Unit) {
        val listener = object : android.view.OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
                val bucket = ((orientation + 45) % 360) / 90 * 90
                if (bucket != orientBucket) {
                    var target = bucket.toFloat()
                    while (target - orientAngle > 180f) target -= 360f
                    while (target - orientAngle < -180f) target += 360f
                    orientBucket = bucket
                    orientAngle = target
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    fun doCapture() {
        val w = renderer.lastWidth
        val h = renderer.lastHeight
        if (w > 0 && h > 0) {
            val longEdge = if (w > h) w else h
            val scale = 4096f / longEdge
            viewModel.onCaptureStarted()
            renderer.queueCapture((w * scale).toInt(), (h * scale).toInt(), viewModel::onCaptured)
        }
    }

    fun fireShutter() {
        if (state.timerSeconds > 0 && countdown == null) {
            countdown = state.timerSeconds
        } else {
            doCapture()
        }
    }

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        if (c > 0) {
            delay(1000)
            Feedback.select(context)
            countdown = c - 1
        } else {
            countdown = null
            doCapture()
        }
    }

    LaunchedEffect(reticleAt, state.focusLocked) {
        if (reticleAt != null && !state.focusLocked) {
            delay(1200)
            reticleAt = null
        }
    }
    LaunchedEffect(texture) {
        texture?.let {
            viewModel.attachPreview(lifecycleOwner, it)
            viewModel.syncRenderer(renderer)
        }
    }
    LaunchedEffect(state.frontCamera, state.mirrorFront) {
        viewModel.syncRenderer(renderer)
    }
    LaunchedEffect(state.paperTheme) {
        renderer.theme = if (state.paperTheme) 1f else 0f
    }
    // Filtered preview toggle: bypass renders Original through the same pipeline.
    // labRecipe/labOpen are keys too: while the Lab is open the preview shows the
    // draft being edited, and each knob move is a new immutable LabRecipe, so this
    // is what pushes the new grade into the renderer.
    LaunchedEffect(
        state.filter, state.intensity, state.sizeScale, state.detailScale,
        state.filterPreview, state.labOpen, state.labRecipe, state.labBaseId, state.labIntensity,
    ) {
        renderer.setSpec(viewModel.effectiveSpec(), viewModel.effectiveIntensity())
    }
    LaunchedEffect(state.filter) {
        val specs = state.specs
        val i = specs.indexOf(state.filter).coerceAtLeast(0)
        renderer.precompileAsync(specs[(i + 1) % specs.size])
        renderer.precompileAsync(specs[(i - 1 + specs.size) % specs.size])
    }
    LaunchedEffect(showFilters || state.settingsOpen, state.specs) {
        if (!showFilters && !state.settingsOpen) return@LaunchedEffect
        thumbnails.clear()
        thumbnailFillOnce(
            specs = state.specs,
            render = { spec, done -> renderer.queueThumbnail(spec, 96, done) },
            onThumb = { id, bmp -> thumbnails[id] = bmp },
        )
    }
    LaunchedEffect(state.galleryTick) {
        galleryThumb = withContext(Dispatchers.IO) {
            Gallery.latestThumbnail(context, 144, state.saveDir)
        }
    }

    DisposableEffect(lifecycleOwner, texture) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    glView?.onResume()
                    // Rebind: the GL context/texture survives pause, CameraX does not.
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
            viewModel.detachPreview()
        }
    }

    // The finder box itself takes the chosen ratio (like the stock camera);
    // the image inside is cover-cropped by the renderer, never stretched.
    val aspectRatio = ASPECT_RATIOS[state.viewAspect.coerceIn(0, ASPECT_RATIOS.lastIndex)]

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The Lab keeps the viewfinder but drops the camera chrome: the grade is
        // edited below the finder, not behind a full-screen panel.
        if (!state.labOpen) {
            TopBar(
                rotation = orientAngle,
                showMirror = state.frontCamera,
                mirrorOn = state.mirrorFront,
                onMirror = viewModel::toggleMirror,
                onSettings = { viewModel.setSettingsOpen(true) },
            )
            QuickBar(
                rotation = orientAngle,
                aspectBadge = ASPECT_LABELS[state.viewAspect.coerceIn(0, ASPECT_LABELS.lastIndex)],
                timerBadge = state.timerSeconds.takeIf { it > 0 }?.let { "${it}s" },
                gridOn = state.gridOn,
                flashOn = state.flashOn,
                onAspect = viewModel::cycleAspect,
                onAspectLong = { optionSheet = "aspect" },
                onTimer = viewModel::cycleTimer,
                onTimerLong = { optionSheet = "timer" },
                onGrid = { viewModel.setGrid(!state.gridOn) },
                onFlash = viewModel::toggleFlash,
            )
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val boxW = minOf(maxWidth, maxHeight * aspectRatio)
            val boxH = boxW / aspectRatio
            Box(
                Modifier
                    .width(boxW)
                    .height(boxH)
                    .graphicsLayer { rotationY = flipRot.value }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .onSizeChanged {
                        viewPx = IntSize(it.width, it.height)
                        if (it.width > 0 && it.height > 0) {
                            renderer.viewAspect = it.width.toFloat() / it.height
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                reticleAt = offset
                                reticleKey += 1
                                if (viewPx.width > 0 && viewPx.height > 0) {
                                    viewModel.focusAt(offset.x, offset.y, viewPx.width, viewPx.height)
                                }
                            },
                            onLongPress = { offset ->
                                reticleAt = offset
                                reticleKey += 1
                                if (viewPx.width > 0 && viewPx.height > 0) {
                                    viewModel.lockFocusAt(offset.x, offset.y, viewPx.width, viewPx.height)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) viewModel.zoomBy(zoom)
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

                if (state.gridOn) {
                    GridOverlay(Modifier.fillMaxSize())
                    LevelDot(Modifier.align(Alignment.Center))
                }
                ViewfinderCorners(Modifier.fillMaxSize())

                reticleAt?.let { pos ->
                    FocusReticle(
                        key = reticleKey,
                        locked = state.focusLocked,
                        modifier = Modifier.offset {
                            with(density) {
                                IntOffset(
                                    (pos.x.toDp() - 36.dp).roundToPx(),
                                    (pos.y.toDp() - 36.dp).roundToPx(),
                                )
                            }
                        },
                    )
                    // Exposure control next to the focus point.
                    if (state.exposureMax > state.exposureMin) {
                        Column(
                            modifier = Modifier
                                .offset {
                                    with(density) {
                                        IntOffset(
                                            (pos.x.toDp() - 60.dp).roundToPx(),
                                            (pos.y.toDp() + 44.dp).roundToPx(),
                                        )
                                    }
                                }
                                .width(120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "EV " + (if (state.exposureIndex > 0) "+" else "") + state.exposureIndex,
                                fontSize = 10.sp,
                                fontFamily = RetroType.Mono,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Slider(
                                value = state.exposureIndex.toFloat(),
                                onValueChange = { viewModel.setExposure(it.toInt()) },
                                valueRange = state.exposureMin.toFloat()..state.exposureMax.toFloat(),
                                steps = (state.exposureMax - state.exposureMin - 1).coerceAtLeast(0),
                            )
                            if (state.focusLocked) {
                                Text(
                                    text = "LOCKED · tap to release",
                                    fontSize = 10.sp,
                                    fontFamily = RetroType.Mono,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        viewModel.unlockFocus()
                                        reticleAt = null
                                    },
                                )
                            }
                        }
                    }
                }

                if (countdown != null) {
                    Oriented(rotation = orientAngle, modifier = Modifier.align(Alignment.Center)) {
                        Text(
                            text = countdown.toString(),
                            fontSize = 64.sp,
                            fontFamily = RetroType.Display,
                            color = Color.White,
                        )
                    }
                }

                if (state.zoomRatio > 1.01f) {
                    Oriented(
                        rotation = orientAngle,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                    ) {
                        Text(
                            text = String.format("%.1f×", state.zoomRatio),
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = RetroType.Mono,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                if (state.mode == "video" && state.recording) {
                    Text(
                        text = "● " + String.format(
                            "%02d:%02d",
                            state.recordSeconds / 60,
                            state.recordSeconds % 60,
                        ),
                        fontFamily = RetroType.Mono,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Red,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (state.labOpen) {
            BackHandler { viewModel.closeLab() }
            FilterLabPanel(
                tab = state.labTab,
                name = state.labName,
                recipe = state.labRecipe,
                intensity = state.labIntensity,
                saved = state.labRecipes,
                canSave = viewModel.canSaveLab(),
                onTab = viewModel::setLabTab,
                onName = viewModel::setLabName,
                onTemplate = viewModel::setLabTemplate,
                onKnob = viewModel::setLabKnob,
                onResetKnob = viewModel::resetLabKnob,
                onResetAll = viewModel::resetLabKnobs,
                onEffect = viewModel::setLabEffect,
                onDuoColour = viewModel::setLabDuotoneColour,
                luts = remember(state.labLutTick) { viewModel.labLuts() },
                onPickLut = { id ->
                    viewModel.setLabLut(id)
                    viewModel.uploadLut(id, renderer)
                },
                onImportLut = { lutPicker.launch("image/*") },
                onLutAmount = viewModel::setLabLutAmount,
                palette = palette,
                watermarks = watermarks,
                onToggleStamp = viewModel::toggleLabStamp,
                onStampText = viewModel::setLabStampText,
                onStampColour = viewModel::setLabStampColour,
                onStampPosition = viewModel::setLabStampPosition,
                onStampAlpha = viewModel::setLabStampAlpha,
                onPickWatermark = viewModel::setLabWatermark,
                onImportWatermark = { markPicker.launch("image/*") },
                onWatermarkAlpha = viewModel::setLabWatermarkAlpha,
                onExtractPalette = viewModel::extractPalette,
                onApplyPaletteColour = viewModel::applyPaletteColour,
                onIntensity = viewModel::setLabIntensity,
                onSave = viewModel::saveLab,
                onEdit = viewModel::editLabRecipe,
                onDelete = viewModel::deleteLabRecipe,
                onClose = viewModel::closeLab,
            )
        } else {
            FilterHandleBar(
                    filterName = state.filter.displayName,
                    onOpen = { showFilters = true },
                )

        ModeRow(mode = state.mode, onMode = viewModel::setMode)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryButton(thumb = galleryThumb, rotation = orientAngle, onClick = { galleryOpen = true })
            if (state.mode == "video") {
                Surface(
                    onClick = { viewModel.toggleRecording(micGranted) },
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                    modifier = Modifier.size(76.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.recording) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(60.dp)
                                    .background(Color.Red, CircleShape),
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                    modifier = Modifier
                        .size(76.dp)
                        .graphicsLayer {
                            scaleX = shutterScale
                            scaleY = shutterScale
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = { fireShutter() },
                            interactionSource = shutterInteraction,
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(60.dp),
                        ) {}
                    }
                }
            }
            IconButton(onClick = { onFlip() }) {
                Oriented(rotation = orientAngle) {
                    key(state.frontCamera) {
                        PopIn(key = state.frontCamera, reduced = reducedMotion) {
                            Icon(
                                imageVector = Icons.Filled.Autorenew,
                                contentDescription = "Flip camera",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (showFilters) {
        FilterDrawer(
            specs = state.specs,
            selectedId = state.filter.id,
            favorites = state.favorites,
            intensity = state.intensity,
            sizeScale = state.sizeScale,
            hasSize = state.filter.param1 > 0f,
            sizeLabel = "%.2f×".format(state.sizeScale),
            thumbnails = thumbnails,
            showHint = !hintSeen,
            reducedMotion = reducedMotion,
            onIntensity = viewModel::onIntensityChange,
            onSize = viewModel::onSizeChange,
            onSelect = viewModel::selectFilter,
            onToggleFavorite = viewModel::toggleFavorite,
            onHintShown = viewModel::markStripHintSeen,
            onOpenLab = { viewModel.openLab() },
            onDismiss = { showFilters = false },
        )
    }

    // Long-press on the ratio / timer icons opens the full list.
    when (optionSheet) {
        "aspect" -> OptionsDialog(
            title = "Aspect Ratio",
            options = ASPECT_LABELS,
            selected = ASPECT_LABELS[state.viewAspect.coerceIn(0, ASPECT_LABELS.lastIndex)],
            onPick = { viewModel.setViewAspect(ASPECT_LABELS.indexOf(it)) },
            onDismiss = { optionSheet = null },
        )
        "timer" -> OptionsDialog(
            title = "Timer",
            options = TIMER_LABELS,
            selected = TIMER_LABELS[TIMER_VALUES.indexOf(state.timerSeconds).coerceAtLeast(0)],
            onPick = { viewModel.setTimerSeconds(TIMER_VALUES[TIMER_LABELS.indexOf(it)]) },
            onDismiss = { optionSheet = null },
        )
    }

}

@Composable
private fun TopBar(
    rotation: Float,
    showMirror: Boolean,
    mirrorOn: Boolean,
    onMirror: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "RetroCam",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = RetroType.Display,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Oriented(rotation) {
            if (showMirror) {
                IconButton(onClick = onMirror) {
                    Icon(
                        imageVector = Icons.Filled.Flip,
                        contentDescription = "Mirror front camera",
                        tint = if (mirrorOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        Oriented(rotation) {
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Keeps chrome upright as the device rotates (animated, shortest path). */
@Composable
private fun Oriented(rotation: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animated by animateFloatAsState(-rotation, animationSpec = tween(300), label = "orient")
    Box(modifier.graphicsLayer { rotationZ = animated }, contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun QuickBar(
    rotation: Float,
    aspectBadge: String,
    timerBadge: String?,
    gridOn: Boolean,
    flashOn: Boolean,
    onAspect: () -> Unit,
    onAspectLong: () -> Unit,
    onTimer: () -> Unit,
    onTimerLong: () -> Unit,
    onGrid: () -> Unit,
    onFlash: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oriented(rotation, Modifier.weight(1f)) {
            ToolButton(
                icon = Icons.Filled.Crop,
                description = "Aspect ratio",
                badge = aspectBadge,
                active = false,
                onClick = onAspect,
                onLongClick = onAspectLong,
            )
        }
        QuickDivider()
        Oriented(rotation, Modifier.weight(1f)) {
            ToolButton(
                icon = Icons.Filled.Timer,
                description = "Timer",
                badge = timerBadge,
                active = timerBadge != null,
                onClick = onTimer,
                onLongClick = onTimerLong,
            )
        }
        QuickDivider()
        Oriented(rotation, Modifier.weight(1f)) {
            ToolButton(
                icon = Icons.Filled.GridOn,
                description = "Grid",
                badge = null,
                active = gridOn,
                onClick = onGrid,
                onLongClick = null,
            )
        }
        QuickDivider()
        Oriented(rotation, Modifier.weight(1f)) {
            IconButton(onClick = onFlash) {
                key(flashOn) {
                    PopIn(key = flashOn, reduced = reduced) {
                        Icon(
                            imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Flash",
                            tint = if (flashOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    badge: String?,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = description,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
            if (badge != null) {
                Text(
                    text = badge,
                    fontSize = 9.sp,
                    fontFamily = RetroType.Mono,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickDivider() {
    Box(
        Modifier
            .height(24.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
    )
}

@Composable
fun OptionsDialog(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = RetroType.Mono) },
        text = {
            LazyColumn {
                items(options) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(option)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = option == selected,
                            onClick = null,
                        )
                        Text(option, fontFamily = RetroType.Mono)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FilterHandleBar(filterName: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = filterName.uppercase(),
            fontFamily = RetroType.Mono,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = "Open filters",
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDrawer(
    specs: List<com.retrocam.catalog.FilterSpec>,
    selectedId: String,
    favorites: Set<String>,
    intensity: Float,
    sizeScale: Float,
    hasSize: Boolean,
    sizeLabel: String,
    thumbnails: Map<String, Bitmap?>,
    showHint: Boolean,
    reducedMotion: Boolean,
    onIntensity: (Float) -> Unit,
    onSize: (Float) -> Unit,
    onSelect: (com.retrocam.catalog.FilterSpec) -> Unit,
    onToggleFavorite: (com.retrocam.catalog.FilterSpec) -> Unit,
    onHintShown: () -> Unit,
    onOpenLab: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        var query by remember { mutableStateOf("") }
        val shown = remember(query, specs) { specs.filter { it.matches(query) } }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "FILTERS",
                fontFamily = RetroType.Mono,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            // Name or context: "old film" finds VINTAGE, "x ray" finds XRAY.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("search name or look…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Pill shape: 50% of the field height, so both ends round over
                // instead of the theme's rectangular card edge.
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (shown.isEmpty()) {
                Text(
                    text = "no filter matches \"$query\"",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            if (showHint) {
                Text(
                    text = "swipe filters · long-press ★ to pin",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // Entry point for building your own filter. Sits above the strip so
            // it reads as "make one" rather than another entry in the list.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        onDismiss()
                        onOpenLab()
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "FILTER LAB · MAKE YOUR OWN",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = intensity,
                onValueChange = onIntensity,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
            if (hasSize) {
                Text(
                    text = "SIZE $sizeLabel",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Slider(
                    value = sizeScale,
                    onValueChange = onSize,
                    valueRange = 0.25f..2.5f,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            FilterStrip(
                specs = shown,
                selectedId = selectedId,
                favorites = favorites,
                thumbnails = thumbnails,
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite,
                reducedMotion = reducedMotion,
                showHint = showHint,
                onHintShown = onHintShown,
            )
        }
    }
}

@Composable
private fun ModeRow(mode: String, onMode: (String) -> Unit) {
    // Pills stay fixed: rotating the segmented control breaks its tap logic.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModePill("Photo", active = mode == "photo", onClick = { onMode("photo") })
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier
                .height(20.dp)
                .width(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.width(16.dp))
        ModePill("Video", active = mode == "video", onClick = { onMode("video") })
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontFamily = RetroType.Mono,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier
            .width(110.dp)
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun GalleryButton(thumb: Bitmap?, rotation: Float, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Oriented(rotation) {
            if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "Gallery",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = "Gallery",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        }
    }
}

@Composable
private fun ViewfinderCorners(modifier: Modifier = Modifier) {
    Canvas(modifier.padding(12.dp)) {
        val arm = 28.dp.toPx()
        val w = 3.dp.toPx()
        val c = Color.White
        drawLine(c, Offset(0f, 0f), Offset(arm, 0f), w)
        drawLine(c, Offset(0f, 0f), Offset(0f, arm), w)
        drawLine(c, Offset(size.width, 0f), Offset(size.width - arm, 0f), w)
        drawLine(c, Offset(size.width, 0f), Offset(size.width, arm), w)
        drawLine(c, Offset(0f, size.height), Offset(arm, size.height), w)
        drawLine(c, Offset(0f, size.height), Offset(0f, size.height - arm), w)
        drawLine(c, Offset(size.width, size.height), Offset(size.width - arm, size.height), w)
        drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - arm), w)
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val color = Color.White.copy(alpha = 0.55f)
        val thick = 2.dp.toPx()
        for (i in 1..2) {
            drawLine(color, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), thick)
            drawLine(color, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), thick)
        }
    }
}

@Composable
private fun LevelDot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val ax = event.values[0]
                val ay = event.values[1]
                roll = Math.toDegrees(atan2(-ax.toDouble(), ay.toDouble())).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        manager?.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    val level = roll.coerceIn(-30f, 30f) / 30f
    val leveled = kotlin.math.abs(roll) < 2f || kotlin.math.abs(roll) > 178f
    Box(modifier.offset(x = (level * 40).dp)) {
        Surface(
            shape = CircleShape,
            color = if (leveled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(10.dp),
        ) {}
    }
}

/** Tap-to-focus reticle: brackets draw in with a settle, auto-dismissed. */
@Composable
private fun FocusReticle(key: Int, locked: Boolean, modifier: Modifier = Modifier) {
    var scale by remember(key) { mutableStateOf(1.4f) }
    LaunchedEffect(key) {
        animate(1.4f, 1f, animationSpec = tween(150)) { value, _ -> scale = value }
    }
    val accent = if (locked) Color.Yellow else MaterialTheme.colorScheme.primary
    Canvas(modifier.size(72.dp).scale(scale)) {
        val edge = size.minDimension
        val len = edge * 0.3f
        val w = 3.dp.toPx()
        drawLine(accent, Offset(0f, 0f), Offset(len, 0f), w)
        drawLine(accent, Offset(0f, 0f), Offset(0f, len), w)
        drawLine(accent, Offset(edge, 0f), Offset(edge - len, 0f), w)
        drawLine(accent, Offset(edge, 0f), Offset(edge, len), w)
        drawLine(accent, Offset(0f, edge), Offset(len, edge), w)
        drawLine(accent, Offset(0f, edge), Offset(0f, edge - len), w)
        drawLine(accent, Offset(edge, edge), Offset(edge - len, edge), w)
        drawLine(accent, Offset(edge, edge), Offset(edge, edge - len), w)
    }
}

@Composable
private fun PermissionEmptyState(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                text = "▓▓▓ NO SIGNAL ▓▓▓",
                fontFamily = RetroType.Mono,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(text = "Camera access needed for the viewfinder.", color = TextDim)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) { Text("Enable camera") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

