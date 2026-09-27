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
import com.retrocam.catalog.lab.withEffect
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
    // ---- Filter Lab ----
    val labOpen: Boolean = false,
    /** 0 = Basic (template + intensity), 1 = Advanced (the five knobs). */
    val labTab: Int = 0,
    val labName: String = "",
    /** The grade being edited. Immutable, so identity drives renderer updates. */
    val labRecipe: com.retrocam.catalog.lab.LabRecipe = com.retrocam.catalog.lab.LabRecipe(),
    /** Catalog id the draft layers on top of. */
    val labBaseId: String = "original",
    val labIntensity: Float = 1f,
    /** Saved recipes, newest last. Recipes whose base filter is gone are dropped. */
    val labRecipes: List<com.retrocam.catalog.lab.SavedRecipe> = emptyList(),
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
                settings.customRecipes,
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
                // Unparseable entries are dropped, and so are recipes whose base
                // filter has been removed: a shared recipe pointing at a filter we
                // no longer ship has nothing to render.
                val recipes = (args[11] as List<String>)
                    .mapNotNull { com.retrocam.catalog.lab.RecipeCodec.decode(it) }
                    .filter { it.toSpec() != null }
                FullState(fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card, recipes)
            }.collect { (fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card, recipes) ->
                _uiState.update {
                    it.copy(
                        specs = orderSpecs(fav, recipes),
                        labRecipes = recipes,
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
        val s = _uiState.value
        // While the Lab is open the draft is what the finder shows, so the grade
        // is live rather than something you only see after saving.
        if (s.labOpen) {
            val base = FilterCatalog.byId[s.labBaseId] ?: FilterCatalog.default
            return base.copy(lab = s.labRecipe)
        }
        val base = if (s.filterPreview) s.filter else FilterCatalog.byId.getValue("original")
        var effective = base
        if (base.param1 > 0f) effective = effective.copy(param1 = base.param1 * s.sizeScale)
        if (base.param2 > 0f) effective = effective.copy(param2 = base.param2 * s.detailScale)
        return effective
    }

    /** Intensity for the live preview: the Lab's own slider while it is open. */
    fun effectiveIntensity(): Float =
        if (_uiState.value.labOpen) _uiState.value.labIntensity else _uiState.value.intensity

    // ---- Filter Lab ----

    /**
     * Opens the Lab. The draft starts as a copy of whatever is selected, so
     * "adjust the filter I am already using" is the default path rather than
     * something you have to set up.
     */
    fun openLab() {
        val s = _uiState.value
        val editing = s.labRecipes.firstOrNull { it.id == s.filter.id }
        _uiState.update {
            if (editing != null) {
                it.copy(
                    labOpen = true, labTab = 0, labName = editing.name,
                    labBaseId = editing.baseId, labRecipe = editing.lab,
                    labIntensity = s.intensity,
                )
            } else {
                it.copy(
                    labOpen = true, labTab = 0,
                    labName = if (s.filter.id.startsWith("lab_")) s.filter.displayName else "",
                    labBaseId = s.filter.id,
                    labRecipe = s.filter.lab ?: com.retrocam.catalog.lab.LabRecipe(),
                    labIntensity = s.intensity,
                )
            }
        }
    }

    fun closeLab() {
        _uiState.update { it.copy(labOpen = false) }
    }

    fun setLabTab(tab: Int) {
        _uiState.update { it.copy(labTab = tab) }
    }

    fun setLabName(name: String) {
        _uiState.update { it.copy(labName = name.take(com.retrocam.catalog.lab.SavedRecipe.MAX_NAME)) }
    }

    fun setLabIntensity(value: Float) {
        _uiState.update { it.copy(labIntensity = value.coerceIn(0f, 1f)) }
    }

    fun setLabTemplate(templateId: String?) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(templateId = templateId)) }
    }

    fun setLabBase(baseId: String) {
        if (FilterCatalog.byId.containsKey(baseId)) {
            _uiState.update { it.copy(labBaseId = baseId) }
        }
    }

    /** Updates one grading knob. [which] indexes [com.retrocam.catalog.lab.Knob.RANGES]. */
    fun setLabKnob(which: Int, value: Float) {
        _uiState.update { s ->
            val knobs = com.retrocam.catalog.lab.LabAdjustments.RANGES
            val k = knobs.getOrNull(which) ?: return@update s
            val a = s.labRecipe.adjustments
            val next = when (which) {
                0 -> a.copy(brightness = value.coerceIn(k.min, k.max))
                1 -> a.copy(contrast = value.coerceIn(k.min, k.max))
                2 -> a.copy(saturation = value.coerceIn(k.min, k.max))
                3 -> a.copy(warmth = value.coerceIn(k.min, k.max))
                4 -> a.copy(tint = value.coerceIn(k.min, k.max))
                else -> a
            }
            s.copy(labRecipe = s.labRecipe.copy(adjustments = next))
        }
    }

    /** Updates one effect amount. [which] indexes [com.retrocam.catalog.lab.LAB_EFFECTS]. */
    fun setLabEffect(which: Int, value: Float) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.withEffect(which, value)) }
    }

    /** [shadow] true for the shadow anchor, false for the highlight. Packed ARGB. */
    fun setLabDuotoneColour(shadow: Boolean, argb: Int) {
        _uiState.update {
            it.copy(
                labRecipe = if (shadow) {
                    it.labRecipe.copy(duotoneShadow = argb)
                } else {
                    it.labRecipe.copy(duotoneHighlight = argb)
                },
            )
        }
    }

    fun resetLabEffects() {
        _uiState.update {
            val r = it.labRecipe
            it.copy(
                labRecipe = r.copy(
                    vignette = 0f, grain = 0f, sharpen = 0f,
                    blur = 0f, glitch = 0f, duotone = 0f,
                ),
            )
        }
    }

    fun resetLabKnobs() {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(adjustments = com.retrocam.catalog.lab.LabAdjustments.NEUTRAL)) }
    }

    /** True when there is anything worth saving. */
    fun canSaveLab(): Boolean = !_uiState.value.labRecipe.isIdentity

    /**
     * Saves the draft, selects it, and closes the Lab. The selection matters:
     * otherwise the finder would snap back to the old filter the moment the Lab
     * closed, which reads as "my filter didn't save".
     */
    fun saveLab() {
        val s = _uiState.value
        if (s.labRecipe.isIdentity) return
        val saved = com.retrocam.catalog.lab.SavedRecipe.create(
            name = s.labName.ifBlank { s.labBaseId.uppercase() },
            baseId = s.labBaseId,
            lab = s.labRecipe,
        )
        val spec = saved.toSpec() ?: return
        viewModelScope.launch {
            val merged = (settings.customRecipes.first().mapNotNull { com.retrocam.catalog.lab.RecipeCodec.decode(it) }
                .filter { it.id != saved.id }) + saved
            settings.setCustomRecipes(merged.map { com.retrocam.catalog.lab.RecipeCodec.encode(it) })
            settings.setIntensity(saved.id, s.labIntensity)
            _uiState.update {
                it.copy(
                    labOpen = false,
                    filter = spec,
                    intensity = s.labIntensity,
                    sizeScale = 1f,
                    detailScale = 1f,
                )
            }
        }
    }

    fun deleteLabRecipe(id: String) {
        viewModelScope.launch {
            val kept = settings.customRecipes.first()
                .mapNotNull { com.retrocam.catalog.lab.RecipeCodec.decode(it) }
                .filter { it.id != id }
            settings.setCustomRecipes(kept.map { com.retrocam.catalog.lab.RecipeCodec.encode(it) })
        }
    }

    /** Loads a saved recipe back into the draft for editing. */
    fun editLabRecipe(id: String) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                labOpen = true, labTab = 1, labName = r.name,
                labBaseId = r.baseId, labRecipe = r.lab,
            )
        }
    }

    /** Resets a knob to its neutral point. */
    fun resetLabKnob(which: Int) {
        val k = com.retrocam.catalog.lab.LabAdjustments.RANGES.getOrNull(which) ?: return
        setLabKnob(which, k.neutral)
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
        val recipes: List<com.retrocam.catalog.lab.SavedRecipe>,
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

        /**
         * Built-in strip order, then the user's own recipes at the end.
         *
         * Recipes are appended rather than interleaved so the 41 built-ins keep
         * the positions muscle memory has already learned, and so a long list of
         * custom filters never pushes a favourite out of reach.
         */
        private fun orderSpecs(
            fav: Set<String>,
            recipes: List<com.retrocam.catalog.lab.SavedRecipe>,
        ): List<FilterSpec> {
            val base = orderSpecs(fav)
            val custom = recipes.mapNotNull { it.toSpec() }
            return if (custom.isEmpty()) base else base + custom
        }
    }
}
