package com.retrocam.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrocam.camera.CameraController
import com.retrocam.catalog.FilterCatalog
import com.retrocam.catalog.FilterSpec
import com.retrocam.data.SettingsRepository
import com.retrocam.renderer.FilterRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject


data class CameraUiState(
    val filter: FilterSpec = FilterCatalog.default,
    val specs: List<FilterSpec> = FilterCatalog.all,
    val favorites: Set<String> = emptySet(),
    val intensity: Float = 0.75f,
    /** Effect-size multiplier for the current filter (1 = default). */
    val sizeScale: Float = 1f,
    /** Detail (param2) multiplier for the current filter (1 = default). */
    val detailScale: Float = 1f,
    val flashOn: Boolean = false,
    val frontCamera: Boolean = false,
    val gridOn: Boolean = false,
    val soundOn: Boolean = true,
    val paperTheme: Boolean = false,
    val timerSeconds: Int = 0,
    val viewAspect: Int = 0, // index into ASPECT_LABELS
    val fileFormat: String = "JPEG",
    val filterPreview: Boolean = true,
    /** Full MediaStore relative dir, e.g. "Pictures/RetroCam". */
    val saveDir: String = "Pictures/RetroCam",
    /** Front selfie mirror (preview + capture). */
    val mirrorFront: Boolean = true,
    /** Bake a polaroid card into saved photos. */
    val photoCard: Boolean = false,
    val settingsOpen: Boolean = false,
    val zoomRatio: Float = 1f,
    val mode: String = "photo",
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
    val focusLocked: Boolean = false,
    val exposureIndex: Int = 0,
    val exposureMin: Int = CameraViewModel.EXPOSURE_MIN,
    val exposureMax: Int = CameraViewModel.EXPOSURE_MAX,
    val capturing: Boolean = false,
    /** Bumped on every save so the gallery icon refreshes. */
    val galleryTick: Int = 0,
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: CameraController,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _stripHintSeen = MutableStateFlow(true)
    val stripHintSeen: StateFlow<Boolean> = _stripHintSeen.asStateFlow()

    fun markStripHintSeen() {
        viewModelScope.launch { settings.setStripHintSeen() }
    }

    private var previewOwner: LifecycleOwner? = null
    private var previewTexture: SurfaceTexture? = null
    private var glRenderer: FilterRenderer? = null
    private var videoUri: Uri? = null
    private var ticker: Job? = null
    private var persistJob: Job? = null
    private var sizePersistJob: Job? = null
    private var detailPersistJob: Job? = null

    init {
        Feedback.ensureSoundLoaded()
        viewModelScope.launch {
            settings.stripHintSeen.collect { _stripHintSeen.value = it }
        }
        viewModelScope.launch {
            val filter = _uiState.value.filter
            _uiState.update {
                it.copy(
                    intensity = settings.intensityFor(filter.id, filter.defaultIntensity).first(),
                    sizeScale = settings.sizeFor(filter.id).first(),
                    detailScale = settings.detailFor(filter.id).first(),
                )
            }
        }
        viewModelScope.launch {
            combine(
                settings.favorites,
                settings.gridOverlay,
                settings.soundEnabled,
                settings.paperTheme,
                settings.timerSeconds,
                settings.viewAspect,
                settings.fileFormat,
                settings.filterPreview,
                settings.saveDir,
                settings.mirrorFront,
                settings.photoCard,
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val fav = args[0] as Set<String>
                val grid = args[1] as Boolean
                val sound = args[2] as Boolean
                val paper = args[3] as Boolean
                val timer = args[4] as Int
                val aspect = args[5] as Int
                val format = args[6] as String
                val preview = args[7] as Boolean
                val folder = args[8] as String
                val mirror = args[9] as Boolean
                val card = args[10] as Boolean
                FullState(fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card)
            }.collect { (fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card) ->
                _uiState.update {
                    it.copy(
                        specs = orderSpecs(fav),
                        favorites = fav,
                        gridOn = grid,
                        soundOn = sound,
                        paperTheme = paper,
                        timerSeconds = timer,
                        viewAspect = aspect,
                        fileFormat = format,
                        filterPreview = preview,
                        saveDir = folder,
                        mirrorFront = mirror,
                        photoCard = card,
                    )
                }
            }
        }
    }

    fun attachPreview(owner: LifecycleOwner, texture: SurfaceTexture) {
        previewOwner = owner
        previewTexture = texture
        controller.onResolutionChanged = { w, h ->
            glRenderer?.let {
                it.bufWidth = w
                it.bufHeight = h
            }
        }
        controller.bind(owner, texture, _uiState.value.frontCamera)
    }

    fun detachPreview() {
        if (_uiState.value.recording) stopVideo()
        controller.unbind()
        previewOwner = null
        previewTexture = null
    }

    fun selectFilter(spec: FilterSpec) {
        if (spec.id == _uiState.value.filter.id) return
        persistJob?.cancel()
        Feedback.select(context)
        _uiState.update { it.copy(filter = spec) }
        viewModelScope.launch {
            _uiState.update { it.copy(intensity = settings.intensityFor(spec.id, spec.defaultIntensity).first(), sizeScale = settings.sizeFor(spec.id).first(), detailScale = settings.detailFor(spec.id).first()) }
        }
    }

    fun toggleFavorite(spec: FilterSpec) {
        viewModelScope.launch { settings.toggleFavorite(spec.id) }
    }

    /** Moves a stacked filter up (-1) or down (+1) in the pipeline order. */
    fun onIntensityChange(value: Float) {
        _uiState.update { it.copy(intensity = value) }
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300)
            settings.setIntensity(_uiState.value.filter.id, _uiState.value.intensity)
        }
    }

    private fun onIntensityImmediate(value: Float) {
        _uiState.update { it.copy(intensity = value.coerceIn(0f, 1f)) }
        viewModelScope.launch { settings.setIntensity(_uiState.value.filter.id, _uiState.value.intensity) }
    }

    /** Effect-size multiplier (0.25–2.5× the filter default). Debounced persist. */
    fun onSizeChange(value: Float) {
        _uiState.update { it.copy(sizeScale = value.coerceIn(0.25f, 2.5f)) }
        sizePersistJob?.cancel()
        sizePersistJob = viewModelScope.launch {
            delay(300)
            settings.setSize(_uiState.value.filter.id, _uiState.value.sizeScale)
        }
    }

    /** Detail (param2) multiplier (0.25–2.5×). Debounced persist. */
    fun onDetailChange(value: Float) {
        _uiState.update { it.copy(detailScale = value.coerceIn(0.25f, 2.5f)) }
        detailPersistJob?.cancel()
        detailPersistJob = viewModelScope.launch {
            delay(300)
            settings.setDetail(_uiState.value.filter.id, _uiState.value.detailScale)
        }
    }

    fun setSound(on: Boolean) {
        viewModelScope.launch { settings.setSoundEnabled(on) }
    }

    fun setGrid(on: Boolean) {
        viewModelScope.launch { settings.setGridOverlay(on) }
    }

    fun setPaperTheme(on: Boolean) {
        viewModelScope.launch { settings.setPaperTheme(on) }
    }

    fun setTimerSeconds(value: Int) {
        viewModelScope.launch { settings.setTimerSeconds(value) }
    }

    fun setViewAspect(value: Int) {
        viewModelScope.launch { settings.setViewAspect(value) }
    }

    fun cycleAspect() {
        setViewAspect((_uiState.value.viewAspect + 1) % ASPECT_LABELS.size)
    }

    fun cycleTimer() {
        val order = listOf(0, 3, 5, 10)
        val next = order[(order.indexOf(_uiState.value.timerSeconds) + 1) % order.size]
        setTimerSeconds(next)
    }

    fun setFileFormat(value: String) {
        viewModelScope.launch { settings.setFileFormat(value) }
    }

    fun setSaveDir(value: String) {
        viewModelScope.launch { settings.setSaveDir(value) }
    }

    /**
     * Turns a picked SAF tree into a MediaStore RELATIVE_PATH. The document id
     * is "primary:Pictures/RetroCam" — strip the volume prefix and we have the
     * relative path, so saves still show up in the gallery. Anything that does
     * not parse (a non-primary volume) keeps the current folder.
     */
    fun setSaveDirFromTree(uri: android.net.Uri) {
        val doc = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return
        val volume = doc.substringBefore(':', "")
        val path = doc.substringAfter(':', "")
        if (volume.isEmpty() || path.isBlank() || path.contains("..")) return
        viewModelScope.launch { settings.setSaveDir(path.trim('/')) }
    }

    /** What the preview pipeline should render (bypass Original when toggled off). */
    fun effectiveSpec(): FilterSpec {
        val base = if (_uiState.value.filterPreview) _uiState.value.filter else FilterCatalog.byId.getValue("original")
        var effective = base
        if (base.param1 > 0f) effective = effective.copy(param1 = base.param1 * _uiState.value.sizeScale)
        if (base.param2 > 0f) effective = effective.copy(param2 = base.param2 * _uiState.value.detailScale)
        return effective
    }

    /** Toggles the front-camera selfie mirror (preview + capture). */
    fun toggleMirror() {
        viewModelScope.launch { settings.setMirrorFront(!_uiState.value.mirrorFront) }
    }

    fun setPhotoCard(on: Boolean) {
        viewModelScope.launch { settings.setPhotoCard(on) }
    }

    /** Rebinds the main preview (used after the gallery unbound everything). */
    fun rebindPreview() {
        val owner = previewOwner
        val texture = previewTexture
        if (owner != null && texture != null) {
            controller.bind(owner, texture, _uiState.value.frontCamera)
        }
    }

    /** Full MediaStore relative dir for inserts + queries. */
    fun saveRelativePath(): String = _uiState.value.saveDir

    fun setFilterPreview(on: Boolean) {
        viewModelScope.launch { settings.setFilterPreview(on) }
    }

    fun setSettingsOpen(open: Boolean) {
        _uiState.update { it.copy(settingsOpen = open) }
    }

    fun exportDiagnostics() {
        val renderer = glRenderer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val text = "RetroCam diagnostics\nfilters=" + FilterCatalog.all.size + "\n" + renderer.snapshot()
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    fun resetAll() {
        viewModelScope.launch { settings.clearAll() }
    }

    fun toggleFlash() {
        val on = !_uiState.value.flashOn
        controller.setTorch(on)
        _uiState.update { it.copy(flashOn = on) }
    }

    fun flipCamera() {
        val owner = previewOwner
        val texture = previewTexture
        controller.setZoomRatio(1f)
        _uiState.update { it.copy(frontCamera = !it.frontCamera, zoomRatio = 1f) }
        if (owner != null && texture != null) {
            controller.bind(owner, texture, _uiState.value.frontCamera)
        }
    }

    fun focusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        controller.unlockFocus()
        controller.focusAt(x, y, viewWidth, viewHeight)
        _uiState.update { it.copy(focusLocked = false) }
        Feedback.select(context)
    }

    fun lockFocusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        if (controller.lockFocus(x, y, viewWidth, viewHeight)) {
            _uiState.update { it.copy(focusLocked = true) }
            Feedback.saved(context)
        }
    }

    fun unlockFocus() {
        controller.unlockFocus()
        _uiState.update { it.copy(focusLocked = false) }
    }

    /**
     * The slider speaks EV ([EXPOSURE_MIN]..[EXPOSURE_MAX]) and we map that onto
     * whatever range the active camera actually reports. Front cameras advertise
     * a far wider compensation range than back ones, so showing the raw index
     * gave the front camera bigger numbers for the same adjustment; normalising
     * makes both controls read the same and use their whole travel.
     */
    fun setExposure(ev: Int) {
        // AE lock (from tap/hold focus) fights compensation — release it first
        // so the slider always drives live auto-exposure.
        controller.unlockFocus()
        val span = controller.exposureRange().let { maxOf(abs(it.first), abs(it.last)) }
            .coerceAtLeast(1)
        val index = (ev.toFloat() / EXPOSURE_MAX * span).toInt().coerceIn(-span, span)
        controller.setExposure(index)
        _uiState.update { it.copy(focusLocked = false, exposureIndex = ev) }
    }

    fun zoomBy(factor: Float) {
        controller.setZoomRatio(_uiState.value.zoomRatio * factor)
        _uiState.update { it.copy(zoomRatio = controller.zoomRatio) }
    }

    /** Pushes mirror + buffer size into the GL pipeline. Call on bind + flip. */
    fun syncRenderer(renderer: FilterRenderer) {
        glRenderer = renderer
        // Selfie mirror only when the user wants it; back camera never mirrors.
        renderer.mirror = _uiState.value.frontCamera && _uiState.value.mirrorFront
        renderer.bufWidth = controller.bufferWidth
        renderer.bufHeight = controller.bufferHeight
        val span = controller.exposureRange().let { maxOf(abs(it.first), abs(it.last)) }
            .coerceAtLeast(1)
        val index = controller.exposureIndex()
        _uiState.update {
            it.copy(
                exposureMin = EXPOSURE_MIN,
                exposureMax = EXPOSURE_MAX,
                exposureIndex = (index.toFloat() / span * EXPOSURE_MAX).toInt()
                    .coerceIn(EXPOSURE_MIN, EXPOSURE_MAX),
            )
        }
    }

    fun setMode(mode: String) {
        if (_uiState.value.recording) return
        _uiState.update { it.copy(mode = mode) }
    }

    fun toggleRecording(micGranted: Boolean) {
        if (_uiState.value.recording) stopVideo() else startVideo(micGranted)
    }

    private fun startVideo(micGranted: Boolean) {
        val renderer = glRenderer ?: return
        val w = renderer.lastWidth
        val h = renderer.lastHeight
        if (w <= 0 || h <= 0 || _uiState.value.recording) return
        viewModelScope.launch(Dispatchers.IO) {
            val longEdge = if (w > h) w else h
            val scale = 1920f / longEdge
            var vw = (w * scale).toInt() / 2 * 2
            var vh = (h * scale).toInt() / 2 * 2
            if (vw < 2) vw = 2
            if (vh < 2) vh = 2
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "RetroCam_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, saveRelativePath())
                // Hidden until the encoder is stopped, so a failed take never
                // leaves a zero-byte video in the gallery.
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Feedback.error(context)
                return@launch
            }
            val pfd = try {
                resolver.openFileDescriptor(uri, "rwt")
            } catch (t: Throwable) {
                null
            }
            if (pfd == null) {
                resolver.delete(uri, null, null)
                Feedback.error(context)
                return@launch
            }
            renderer.queueVideoStart(pfd, vw, vh, micGranted) { ok ->
                if (ok) {
                    videoUri = uri
                    _uiState.update { it.copy(recording = true, recordSeconds = 0) }
                    ticker?.cancel()
                    ticker = viewModelScope.launch {
                        var s = 0
                        while (_uiState.value.recording) {
                            delay(1000)
                            s += 1
                            _uiState.update { it.copy(recordSeconds = s) }
                        }
                    }
                } else {
                    runCatching { resolver.delete(uri, null, null) }
                    Feedback.error(context)
                }
            }
        }
    }

    private fun stopVideo() {
        val renderer = glRenderer ?: return
        renderer.queueVideoStop { ok ->
            ticker?.cancel()
            _uiState.update { it.copy(recording = false, galleryTick = it.galleryTick + 1) }
            val uri = videoUri
            val resolver = context.contentResolver
            viewModelScope.launch(Dispatchers.IO) {
                if (uri != null) {
                    // Publish: clear IS_PENDING or the file stays invisible to
                    // the gallery, which is what made videos look "lost".
                    if (ok) {
                        val done = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        runCatching { resolver.update(uri, done, null, null) }
                    } else {
                        // No frames reached the encoder: don't publish a stub.
                        runCatching { resolver.delete(uri, null, null) }
                    }
                }
                if (ok) Feedback.saved(context) else Feedback.error(context)
            }
            videoUri = null
        }
    }

    fun onCaptureStarted() {
        Feedback.shutter(context)
        Feedback.shutterClick(context, _uiState.value.soundOn)
        _uiState.update { it.copy(capturing = true) }
    }

    fun onCaptured(bitmap: Bitmap) {
        // No CPU crop: the GL pass already cover-cropped the buffer to the
        // capture target, which is the viewfinder's own aspect.
        // No review screen: the shot saves and the gallery icon picks it up.
        _uiState.update { it.copy(capturing = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val uri = writeToGallery(bitmap)
            bitmap.recycle()
            if (uri != null) {
                Feedback.saved(context)
                _uiState.update { it.copy(galleryTick = it.galleryTick + 1) }
            }
        }
    }

    private fun writeToGallery(bitmap: Bitmap): Uri? {
        val png = _uiState.value.fileFormat == "PNG"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (png) "png" else "jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        // Polaroid card baked in when enabled (photos only).
        val card = _uiState.value.photoCard
        val shot = if (card) {
            PhotoCards.render(bitmap, "RETROCAM", _uiState.value.filter.displayName, stamp)
        } else {
            bitmap
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "RetroCam_$stamp.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, saveRelativePath())
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            if (shot !== bitmap) shot.recycle()
            return null
        }
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (png) {
                    shot.compress(format, 100, out)
                } else {
                    val buffer = ByteArrayOutputStream()
                    shot.compress(format, 95, buffer)
                    out.write(buffer.toByteArray())
                }
            }
        }.onFailure {
            resolver.delete(uri, null, null)
            if (shot !== bitmap) shot.recycle()
            return null
        }
        if (shot !== bitmap) shot.recycle()
        return uri
    }

    private data class FullState(
        val favorites: Set<String>,
        val grid: Boolean,
        val sound: Boolean,
        val paper: Boolean,
        val timer: Int,
        val aspect: Int,
        val format: String,
        val preview: Boolean,
        val folder: String,
        val mirror: Boolean,
        val card: Boolean,
    )

    companion object {
        /** Slider range in EV, identical for every camera (see setExposure). */
        const val EXPOSURE_MIN = -6
        const val EXPOSURE_MAX = 6

        /** Original leads, then favorites, then the rest of the catalog in order. */
        private fun orderSpecs(fav: Set<String>): List<FilterSpec> {
            val all = FilterCatalog.all
            val original = all.filter { it.id == "original" }
            val rest = all - original.toSet()
            val favored = rest.filter { it.id in fav }
            return original + favored + (rest - favored.toSet())
        }
    }
}
