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
import com.retrocam.catalog.lab.DateStamp
import com.retrocam.catalog.lab.withEffect
import com.uvstudio.him.photofilterlibrary.FilterEngine
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
    /** Bumped when the set of available LUTs changes, to re-read the list. */
    val labLutTick: Int = 0,
    /** Id of the saved recipe the draft came from, if any. Drives the bridge button. */
    val savedRecipeId: String? = null,
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

    /**
     * Created lazily: it touches filesDir, and the property has to be declared
     * before the [init] block that used to assign it.
     */
    private val lutStore: LutStore by lazy { LutStore(context) }

    /** The CPU half of the overlay path; see [OverlayRaster] for why it exists. */
    private val overlayRaster: OverlayRaster by lazy { OverlayRaster(context) }

    /** Dominant colours read back from the live frame, newest last. */
    private val _palette = MutableStateFlow<List<Int>>(emptyList())
    val palette: StateFlow<List<Int>> = _palette.asStateFlow()

    /** Watermark logos the user has imported. */
    private val _watermarks = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val watermarks: StateFlow<List<Pair<String, String>>> = _watermarks.asStateFlow()

    fun refreshOverlays() {
        _watermarks.value = overlayRaster.watermarks()
    }

    // ---- overlays ----

    fun setLabStampText(text: String) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(stampText = text.take(24))) }
    }

    fun toggleLabStamp() {
        pendingSelectedRecipe = null
        _uiState.update { s ->
            val on = !s.labRecipe.stampText.isNullOrBlank()
            s.copy(
                labRecipe = s.labRecipe.copy(
                    stampText = if (on) null else DateStamp.defaultText(java.util.Calendar.getInstance()),
                ),
            )
        }
        syncStamp()
    }

    fun setLabStampColour(argb: Int) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(stampColor = argb)) }
        syncStamp()
    }

    fun setLabStampPosition(index: Int) {
        val pos = com.retrocam.catalog.lab.STAMP_POSITIONS.getOrNull(index) ?: return
        setLabStampPosition(pos)
    }

    fun setLabStampPosition(pos: com.retrocam.catalog.lab.StampPosition) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(stampPosition = pos)) }
    }

    fun setLabStampAlpha(v: Float) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(stampAlpha = v.coerceIn(0f, 1f))) }
    }

    fun setLabWatermark(id: String?) {
        pendingSelectedRecipe = null
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(watermarkId = id)) }
        id?.let { uploadWatermark(it) }
    }

    fun setLabWatermarkAlpha(v: Float) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(watermarkAlpha = v.coerceIn(0f, 1f))) }
    }

    fun setLabWatermarkPosition(pos: com.retrocam.catalog.lab.StampPosition) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(watermarkPosition = pos)) }
    }

    fun importWatermark(uri: android.net.Uri) {
        val r = overlayRaster.importWatermark(uri)
        val entry = r.getOrNull()
        if (entry == null) {
            Feedback.error(context)
            return
        }
        refreshOverlays()
        setLabWatermark(entry.first)
    }

    /**
     * Rasterises the current stamp text and queues it for the GL thread.
     *
     * Deliberately off the main thread. Rasterising allocates a multi-megabyte
     * probe, has upstream copy it, and then scans it for the glyph bounds, so
     * doing that inline in a click handler was stalling long enough to be killed
     * as an ANR.
     */
    private fun syncStamp() {
        val r = _uiState.value.labRecipe
        val text = r.stampText ?: return
        if (text.isBlank()) return
        val color = r.stampColor
        val id = stampTextureId(text, color)
        viewModelScope.launch(Dispatchers.Default) {
            val raster = overlayRaster.stamp(text, color) ?: return@launch
            glRenderer?.queueOverlayUpload(id, raster.pixels, raster.width, raster.height, raster.aspect)
        }
    }

    private fun stampTextureId(text: String, color: Int) =
        "stamp:" + DateStamp.hashStampText(text, color)

    private fun uploadWatermark(id: String) {
        val raster = overlayRaster.watermark(id) ?: return
        glRenderer?.queueOverlayUpload(id, raster.pixels, raster.width, raster.height, raster.aspect)
    }

    /**
     * Re-uploads whatever overlays the current draft needs. Called from
     * [syncRenderer] because a fresh GL context has no textures at all.
     */
    fun syncOverlays() {
        val r = _uiState.value.labRecipe
        if (!r.stampText.isNullOrBlank()) syncStamp()
        r.watermarkId?.let { uploadWatermark(it) }
    }

    /**
     * Grabs a small snapshot of the current frame and extracts its dominant
     * colours.
     *
     * A CPU readback by definition: the GPU cannot hand back pixels mid-frame
     * without stalling, so this renders a tiny offscreen copy and samples that.
     * The result is offered as duotone anchors, which is the use that actually
     * pays off in a filter app.
     */
    fun extractPalette() {
        val renderer = glRenderer ?: return
        renderer.queuePaletteProbe { argb ->
            viewModelScope.launch {
                val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.setPixels(argb, 0, 32, 0, 0, 32, 32)
                val colors = try {
                    FilterEngine.extractPalette(bmp, 5)
                } catch (t: Throwable) {
                    emptyList()
                } finally {
                    bmp.recycle()
                }
                _palette.value = colors
            }
        }
    }

    // ---- sharing ----

    /** Shares a saved recipe as a QR image. */
    fun shareRecipe(id: String) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        if (!QrShare.share(context, r)) Feedback.error(context)
    }

    /**
     * Imports a recipe from a QR in [uri] and adds it to the strip.
     *
     * Re-importing something already present is a no-op, because the recipe id is
     * a content hash, so sharing the same recipe twice does not fill the strip
     * with duplicates.
     */
    fun importRecipe(uri: android.net.Uri) {
        val r = QrShare.import(context, uri)
        if (r == null) {
            Feedback.error(context)
            return
        }
        viewModelScope.launch {
            val current = settings.customRecipes.first()
                .mapNotNull { com.retrocam.catalog.lab.RecipeCodec.decode(it) }
            if (current.none { it.id == r.id }) {
                settings.setCustomRecipes(
                    (current + r).map { com.retrocam.catalog.lab.RecipeCodec.encode(it) },
                )
            }
            // A recipe whose base filter this install no longer has cannot render,
            // so do not select it; it stays in the list for later.
            if (r.toSpec() != null) {
                _uiState.update { it.copy(labName = r.name, labBaseId = r.baseId, labRecipe = r.lab) }
            }
        }
    }

    /**
     * Deletes an imported LUT and drops it from the draft if it was selected.
     *
     * Clearing the reference matters: the shader already degrades gracefully when
     * a named LUT is missing, but leaving a dangling id in the draft means the
     * recipe re-encodes a reference to something the user just threw away.
     */
    fun deleteLut(id: String) {
        if (com.retrocam.catalog.lab.LutCatalog.builtInById.containsKey(id)) {
            Feedback.info(context, "Built-in LUTs can't be deleted")
            return
        }
        if (!lutStore.delete(id)) {
            Feedback.error(context)
            return
        }
        _uiState.update {
            if (it.labRecipe.lutId == id) it.copy(labRecipe = it.labRecipe.copy(lutId = null, lutAmount = 0f)) else it
        }
        _uiState.update { it.copy(labLutTick = it.labLutTick + 1) }
        Feedback.info(context, "LUT deleted")
    }

    fun deleteWatermark(id: String) {
        if (!overlayRaster.deleteWatermark(id)) {
            Feedback.error(context)
            return
        }
        _uiState.update {
            if (it.labRecipe.watermarkId == id) it.copy(labRecipe = it.labRecipe.copy(watermarkId = null)) else it
        }
        refreshOverlays()
        Feedback.info(context, "Watermark deleted")
    }

    /** Applies a palette colour to a duotone anchor. */
    fun applyPaletteColour(argb: Int, shadow: Boolean) {
        setLabDuotoneColour(shadow, argb)
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(duotone = 1f)) }
    }

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

    /**
     * Detach, but only if [texture] is the surface currently bound.
     *
     * Both the camera and the Lab register a surface, and when the mode switches
     * both compositions tear down and set up within the same pass. If the camera
     * screen's dispose lands *after* the Lab has already bound its own surface, an
     * unconditional detach would leave the Lab's viewfinder black. Checking which
     * surface we are releasing makes the order irrelevant.
     */
    fun releasePreview(texture: SurfaceTexture?) {
        if (texture != null && previewTexture != null && previewTexture !== texture) return
        detachPreview()
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
        // In the Lab the draft is what its own viewfinder shows, so the grade is
        // live rather than something you only see after saving.
        if (isLab()) {
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
        if (isLab()) _uiState.value.labIntensity else _uiState.value.intensity

    // ---- Filter Lab ----

    fun isLab(): Boolean = _uiState.value.mode == MODE_LAB

    /**
     * Entering the Lab seeds the draft from whatever the camera is currently
     * showing, so "tweak the filter I am already using" is the default path
     * rather than something you have to set up.
     */
    private fun enterLab() {
        val s = _uiState.value
        refreshOverlays()
        val editing = s.labRecipes.firstOrNull { it.id == s.filter.id }
        _uiState.update {
            if (editing != null) {
                it.copy(
                    mode = MODE_LAB, labTab = 0, labName = editing.name,
                    labBaseId = editing.baseId, labRecipe = editing.lab,
                    labIntensity = s.intensity, savedRecipeId = editing.id,
                )
            } else {
                it.copy(
                    mode = MODE_LAB, labTab = 0,
                    labName = if (s.filter.id.startsWith("lab_")) s.filter.displayName else "",
                    labBaseId = s.filter.id,
                    labRecipe = s.filter.lab ?: com.retrocam.catalog.lab.LabRecipe(),
                    labIntensity = s.intensity,
                )
            }
        }
    }

    /** Leaves the Lab and goes back to the camera. */
    fun closeLab() {
        if (_uiState.value.recording) return
        _uiState.update { it.copy(mode = MODE_PHOTO) }
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
        pendingSelectedRecipe = null
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(templateId = templateId)) }
    }

    fun setLabBase(baseId: String) {
        if (FilterCatalog.byId.containsKey(baseId)) {
            _uiState.update { it.copy(labBaseId = baseId) }
        }
    }

    /** Updates one grading knob. [which] indexes [com.retrocam.catalog.lab.Knob.RANGES]. */
    fun setLabKnob(which: Int, value: Float) {
        pendingSelectedRecipe = null
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
            // Indices 5 and 6 are not part of LabAdjustments: gamma is a power
            // curve and split tone is luminance-keyed, so neither can ride in the
            // 4x5 colour matrix. They live on the recipe instead.
            if (which == 5) return@update s.copy(
                labRecipe = s.labRecipe.copy(gamma = value.coerceIn(k.min, k.max)),
            )
            if (which == 6) return@update s.copy(
                labRecipe = s.labRecipe.copy(splitAmount = value.coerceIn(k.min, k.max)),
            )
            s.copy(labRecipe = s.labRecipe.copy(adjustments = next))
        }
    }

    /** Updates one effect amount. [which] indexes [com.retrocam.catalog.lab.LAB_EFFECTS]. */
    fun setLabEffect(which: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { it.copy(labRecipe = it.labRecipe.withEffect(which, value)) }
    }

    // ---- Filter Lab LUTs ----

    /**
     * Every LUT the Lab can offer: the generated built-ins plus anything the user
     * has imported. Re-read whenever the Lab opens, since importing happens
     * outside this ViewModel's flow.
     */
    fun labLuts(): List<com.retrocam.ui.LutStore.Entry> {
        return lutStore.all()
    }

    fun setLabLut(id: String?) {
        pendingSelectedRecipe = null
        _uiState.update {
            it.copy(
                labRecipe = it.labRecipe.copy(
                    lutId = id,
                    // Selecting a LUT turns it on at full strength, which is what
                    // picking a colour normally means.
                    lutAmount = if (id == null) 0f else 1f,
                ),
            )
        }
    }

    fun setLabLutAmount(value: Float) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(lutAmount = value.coerceIn(0f, 1f))) }
    }

    /**
     * Imports a Hald PNG, then makes sure the renderer has its pixels.
     *
     * The renderer owns the GL texture, so a LUT selected later in the session
     * still has to be uploaded; [syncRenderer] re-uploads whatever the current
     * recipe needs, and this covers the rest by uploading on selection.
     */
    fun importLut(uri: android.net.Uri, renderer: FilterRenderer?) {
        val r = lutStore.import(uri)
        val entry = r.getOrNull()
        if (entry == null) {
            Feedback.error(context)
            return
        }
        uploadLut(entry.id, renderer)
        _uiState.update { it.copy(labLutTick = it.labLutTick + 1) }
        setLabLut(entry.id)
    }

    /** Reads a LUT's pixels and hands them to the GL thread. */
    fun uploadLut(id: String, renderer: FilterRenderer?) {
        val px = lutStore.pixelsFor(id) ?: return
        renderer?.queueLutUpload(id, px.pixels, px.side, px.cube)
    }

    /** Split-tone anchor colours. [shadow] picks which of the two tints. */
    fun setLabSplitTint(shadow: Boolean, argb: Int) {
        _uiState.update {
            it.copy(
                labRecipe = if (shadow) {
                    it.labRecipe.copy(shadowTint = argb)
                } else {
                    it.labRecipe.copy(highlightTint = argb)
                },
            )
        }
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
     * Persists the draft. Deliberately does NOT select it or leave the Lab: the
     * two are separate screens now, so saving is just saving, and handing the
     * recipe to the camera is the explicit [useRecipeInCamera] step.
     */
    fun saveLab() {
        val s = _uiState.value
        if (s.labRecipe.isIdentity) return
        // Remember which recipe this draft is, so "use in camera" knows what to
        // hand over without re-deriving it from the name.
        pendingSelectedRecipe = null
        val saved = com.retrocam.catalog.lab.SavedRecipe.create(
            name = s.labName.ifBlank { s.labBaseId.uppercase() },
            baseId = s.labBaseId,
            lab = s.labRecipe,
        )
        if (saved.toSpec() == null) return
        viewModelScope.launch {
            val merged = (settings.customRecipes.first()
                .mapNotNull { com.retrocam.catalog.lab.RecipeCodec.decode(it) }
                .filter { it.id != saved.id }) + saved
            settings.setCustomRecipes(merged.map { com.retrocam.catalog.lab.RecipeCodec.encode(it) })
            settings.setIntensity(saved.id, s.labIntensity)
            _uiState.update { it.copy(savedRecipeId = saved.id) }
        }
    }

    /** Forgets the current selection, hiding the bridge button. */
    fun clearLabSelection() {
        _uiState.update { it.copy(savedRecipeId = null) }
        pendingSelectedRecipe = null
    }

    @Volatile private var pendingSelectedRecipe: String? = null

    /**
     * The bridge: hand a saved recipe to the camera and switch to it.
     *
     * This is the only thing that crosses between the two halves. Recipes live in
     * DataStore, so both sides read the same list without sharing any state.
     */
    fun useRecipeInCamera(id: String) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        val spec = r.toSpec() ?: return
        _uiState.update { it.copy(filter = spec, mode = MODE_PHOTO) }
        viewModelScope.launch {
            settings.setIntensity(spec.id, _uiState.value.labIntensity)
            _uiState.update {
                it.copy(intensity = it.labIntensity, sizeScale = 1f, detailScale = 1f)
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
            it.copy(labTab = 1, labName = r.name, labBaseId = r.baseId, labRecipe = r.lab, savedRecipeId = r.id)
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
        // A fresh GL context has no LUT textures, so re-upload whatever the
        // current recipe names. Without this, returning from the background after
        // a context loss would silently drop the LUT from every recipe.
        val lutId = _uiState.value.labRecipe.lutId ?: _uiState.value.filter.lab?.lutId
        if (lutId != null) uploadLut(lutId, renderer)
        syncOverlays()
        refreshOverlays()
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
        if (mode == MODE_LAB) {
            enterLab()
            return
        }
        if (mode == MODE_PHOTO || mode == MODE_VIDEO) {
            _uiState.update { it.copy(mode = mode) }
            return
        }
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
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        const val MODE_LAB = "lab"

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
