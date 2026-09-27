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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Send
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
import android.opengl.GLSurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.retrocam.renderer.FilterRenderer
import com.retrocam.ui.theme.RetroType

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
    val renderer = remember { FilterRenderer(onTextureReady = {}) }
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val palette by viewModel.palette.collectAsStateWithLifecycle()
    val watermarks by viewModel.watermarks.collectAsStateWithLifecycle()

    // Every picker the Lab needs, created here rather than on the camera screen:
    // these belong to the Lab, and the camera screen has no use for them now.
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importLut(uri, renderer)
    }
    val markPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importWatermark(uri)
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importRecipe(uri)
    }

    // The Lab's own renderer, not the camera's. syncRenderer is what tells the
    // ViewModel which GL pipeline is live, so LUT and overlay uploads land on the
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

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---- header ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.closeLab() }) {
                Icon(
                    Icons.Filled.ArrowBack,
                    "Back to camera",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "FILTER LAB",
                fontFamily = RetroType.Display,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.flipCamera() }) {
                Icon(
                    Icons.Filled.Autorenew,
                    "Flip camera",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // ---- the Lab's own viewfinder ----
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
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
            ViewfinderCorners(Modifier.fillMaxSize())
            Text(
                "LIVE PREVIEW",
                fontFamily = RetroType.Mono,
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
            name = state.labName,
            recipe = state.labRecipe,
            intensity = state.labIntensity,
            saved = state.labRecipes,
            canSave = viewModel.canSaveLab(),
            selectedRecipeId = state.savedRecipeId,
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
            onShare = viewModel::shareRecipe,
            onImportQr = { qrPicker.launch("image/*") },
            onUse = viewModel::useRecipeInCamera,
            onDeleteSelected = viewModel::clearLabSelection,
            onIntensity = viewModel::setLabIntensity,
            onSave = viewModel::saveLab,
            onEdit = viewModel::editLabRecipe,
            onDelete = viewModel::deleteLabRecipe,
        )
    }
}
