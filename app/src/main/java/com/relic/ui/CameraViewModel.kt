package com.relic.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relic.camera.CameraController
import com.relic.catalog.FilterCatalog
import com.relic.catalog.FilterSpec
import com.relic.catalog.lab.DateStamp
import com.relic.catalog.lab.LabMask
import com.relic.catalog.lab.LabRecipe
import com.relic.catalog.lab.LabPrimitives
import com.relic.catalog.lab.LabStage
import com.relic.catalog.lab.neutral
import com.relic.catalog.lab.withBw
import com.relic.catalog.lab.withCal
import com.relic.catalog.lab.withDefringe
import com.relic.catalog.lab.withEffect
import com.relic.catalog.lab.withGrade
import com.relic.catalog.lab.withHsl
import com.relic.catalog.lab.withKnob
import com.uvstudio.him.photofilterlibrary.FilterEngine
import com.relic.data.SettingsRepository
import com.relic.renderer.FilterRenderer
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
    /** Full MediaStore relative dir, e.g. "Pictures/Relic". */
    val saveDir: String = "Pictures/Relic",
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
    val labRecipe: com.relic.catalog.lab.LabRecipe = com.relic.catalog.lab.LabRecipe(),
    /** Catalog id the draft layers on top of. */
    val labBaseId: String = "original",
    val labIntensity: Float = 1f,
    /** Saved recipes, newest last. Recipes whose base filter is gone are dropped. */
    val labRecipes: List<com.relic.catalog.lab.SavedRecipe> = emptyList(),
    /**
     * What the last `.xmp` import did, kept so the Lab can show it. An XMP
     * preset carries around forty settings and the Lab has seven knobs, so an
     * import that reported nothing would look like it had worked completely.
     */
    val xmpReport: com.relic.catalog.lab.XmpResult? = null,
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

    /**
     * Every message already shown this session, so a warning about stored data
     * is said once rather than on every emission.
     */
    private val said = mutableSetOf<String>()

    /**
     * Tells the user what failed, in words they can act on.
     *
     * Every one of these paths used to be a bare `return`, which made a save that
     * did nothing look exactly like a save that worked. Deduplicated on the
     * message rather than on a timer because the settings collector re-runs on
     * every stored preference - an un-deduplicated warning would reappear on
     * every rotation and every slider tick, and a warning you have to dismiss
     * thirty times a minute stops being read.
     */
    private fun notice(message: String) {
        if (said.add(message)) Feedback.info(context, message)
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
        val pos = com.relic.catalog.lab.STAMP_POSITIONS.getOrNull(index) ?: return
        setLabStampPosition(pos)
    }

    fun setLabStampPosition(pos: com.relic.catalog.lab.StampPosition) {
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

    fun setLabWatermarkPosition(pos: com.relic.catalog.lab.StampPosition) {
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(watermarkPosition = pos)) }
    }

    fun importWatermark(uri: android.net.Uri) {
        val r = overlayRaster.importWatermark(uri)
        val entry = r.getOrNull()
        if (entry == null) {
            notice("Couldn't use that image as a logo")
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

    /**
     * Shares a saved recipe as an `.xmp` file.
     *
     * Written to the shared cache dir and handed out through the app's
     * FileProvider, mirroring [QrShare.share], so any app that takes a file
     * gets a real preset file - Lightroom included - rather than a picture
     * of a QR code that only this app can read back.
     */
    fun shareRecipeXmp(id: String) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        val doc = com.relic.catalog.lab.XmpExport.export(r.lab, r.name)
        val file = try {
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            java.io.File(dir, "${r.name}.xmp").apply { writeText(doc) }
        } catch (t: Throwable) {
            null
        }
        if (file == null) {
            notice("Couldn't share that preset")
            Feedback.error(context)
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, "com.relic.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            notice("Couldn't share that preset")
            Feedback.error(context)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share ${r.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            notice("No app available to share with")
            Feedback.error(context)
        }
    }

    /**
     * Writes a saved recipe out as a Lightroom `.xmp` preset.
     *
     * Called with the uri from a CreateDocument picker, so the user chooses
     * where the file goes. A curated preset built from scratch leaves the app
     * as a file Lightroom understands — the other half of import.
     */
    fun exportXmp(id: String, uri: android.net.Uri) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        val doc = com.relic.catalog.lab.XmpExport.export(r.lab, r.name)
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(doc.toByteArray())
            } ?: throw IllegalStateException("no stream")
        }.isSuccess
        if (ok) Feedback.info(context, "XMP saved")
        else {
            notice("Couldn't write the file - try a different folder")
            Feedback.error(context)
        }
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
            notice("That isn't a preset QR code")
            Feedback.error(context)
            return
        }
        viewModelScope.launch {
            // Appended to the stored strings, never decoded-and-re-encoded. A
            // rewrite built from decoded recipes drops every entry this version
            // cannot read, so importing one QR quietly deleted all the others -
            // the same bug keepRecipe had. Nothing already stored is touched.
            val current = settings.customRecipes.first()
            if (current.none { raw -> com.relic.catalog.lab.RecipeCodec.decode(raw)?.id == r.id }) {
                settings.setCustomRecipes(current + com.relic.catalog.lab.RecipeCodec.encode(r))
            }
            // A recipe whose base filter this install no longer has cannot render,
            // so do not select it; it stays in the list for later.
            if (r.toSpec() != null) {
                // Selected, like an XMP import is. Leaving savedRecipeId unset
                // meant a QR-imported preset loaded into the controls but left
                // the "use in camera" button hidden, because that button is
                // drawn from the selection.
                _uiState.update {
                    it.copy(labName = r.name, labBaseId = r.baseId, labRecipe = r.lab, savedRecipeId = r.id)
                }
            } else {
                notice("Preset added, but it needs an effect this version no longer has")
            }
            Feedback.info(context, "Preset imported")
        }
    }

    fun deleteWatermark(id: String) {
        if (!overlayRaster.deleteWatermark(id)) {
            notice("Couldn't delete that logo")
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
                // Recipes can be unreadable for two different reasons, and the
                // user can only fix one of them, so they are counted separately
                // rather than folded into a single silent mapNotNull. The counts
                // ride along in FullState purely so the user can be told.
                var unreadable = 0
                var noBase = 0
                @Suppress("UNCHECKED_CAST")
                val recipes = (args[11] as List<String>).mapNotNull { raw ->
                    val r = com.relic.catalog.lab.RecipeCodec.decode(raw)
                    when {
                        r == null -> { unreadable++; null }
                        r.toSpec() == null -> { noBase++; null }
                        else -> r
                    }
                }
                FullState(fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card, recipes, unreadable, noBase)
            }.collect { (fav, grid, sound, paper, timer, aspect, format, preview, folder, mirror, card, recipes, unreadable, noBase) ->
                // Said here rather than at the point of failure because the
                // failure is not actionable by the user and never will be: the
                // only honest thing to do is tell them the filter is not coming
                // back and why, once, instead of leaving a gap in the strip that
                // looks like the save never happened.
                if (unreadable > 0) {
                    notice(
                        if (unreadable == 1) "1 saved filter could not be opened - it was made by an older version"
                        else "$unreadable saved filters could not be opened - they were made by an older version",
                    )
                }
                if (noBase > 0) {
                    notice(
                        if (noBase == 1) "1 saved filter needs an effect this version no longer has"
                        else "$noBase saved filters need effects this version no longer has",
                    )
                }
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
     * is "primary:Pictures/Relic" — strip the volume prefix and we have the
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
        // A draft handed to the camera was never saved, so coming back must
        // keep the Lab exactly as it was. Seeding from the transient filter
        // would overwrite labBaseId with "lab_draft" - an id no catalog knows
        // - and the next save dialog would offer LAB_DRAFT as a name.
        if (s.filter.id == DRAFT_FILTER_ID && !s.labRecipe.isIdentity) {
            _uiState.update {
                it.copy(mode = MODE_LAB, labTab = 0, labIntensity = s.intensity)
            }
            return
        }
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
                    labRecipe = s.filter.lab ?: com.relic.catalog.lab.LabRecipe(),
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
        _uiState.update { it.copy(labName = name.take(com.relic.catalog.lab.SavedRecipe.MAX_NAME)) }
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

    /**
     * Updates one grading knob. [which] indexes
     * [com.relic.catalog.lab.LabKnob], which covers every scalar the XMP
     * importer can set.
     *
     * Keyed by the enum rather than by the old positional `when`, because that
     * block had five cases in one branch and two `return@update` escapes, and
     * adding a sixth control to it meant remembering all of that. The whole
     * range table now lives with the enum.
     */
    fun setLabKnob(which: Int, value: Float) {
        val k = com.relic.catalog.lab.LabKnob.byIndex(which) ?: return
        pendingSelectedRecipe = null
        _uiState.update { s ->
            s.copy(labRecipe = s.labRecipe.withKnob(k, value))
        }
    }

    fun setHslBand(band: Int, ch: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.withHsl(band, ch, value)) }
    }
    fun setGrade(slot: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.withGrade(slot, value)) }
    }
    fun setBw(band: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.withBw(band, value)) }
    }
    fun setCal(slot: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.withCal(slot, value)) }
    }
    fun setDefringe(slot: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.withDefringe(slot, value)) }
    }
    /** Updates one effect amount. [which] indexes [com.relic.catalog.lab.LAB_EFFECTS]. */
    fun setLabEffect(which: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { it.copy(labRecipe = it.labRecipe.withEffect(which, value)) }
    }

    /**
     * Imports a Camera Raw `.xmp` preset onto the current recipe.
     *
     * The result is recorded rather than discarded so the Lab can list what was
     * mapped and what was dropped. The report survives until the next import:
     * there is nothing useful about "here is what your preset lost" if it has
     * already been dismissed.
     *
     * Applied *over* the recipe rather than replacing it, so a stage chain the
     * user built survives. Only the keys the preset actually sets are touched.
     */
    fun importXmp(uri: android.net.Uri) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            notice("Couldn't open that file - is it really an .xmp preset?")
            Feedback.error(context)
            return
        }
        val result = com.relic.catalog.lab.XmpImport.parse(text)
        if (result.isEmpty) {
            notice("No photo settings found in that file")
            Feedback.error(context)
            return
        }
        pendingSelectedRecipe = null
        // Stashed so dismissing the report reverts the draft: without this the
        // imported grade lingered in the draft after testing, and raising
        // intensity from 0 with nothing selected faded the test preset back in.
        preXmpDraft = _uiState.value.labRecipe
        // The file already has a name, so the draft takes it. Without this the
        // import landed nameless and the save dialog asked for a name the file
        // had already given.
        val importedName = result.name?.takeIf { it.isNotBlank() }
        _uiState.update { s ->
            val draft = s.labRecipe
            val r = result.recipe
            s.copy(
                labName = importedName ?: s.labName,
                labRecipe = draft.copy(
                    adjustments = r.adjustments,
                    gamma = r.gamma,
                    highlights = r.highlights, shadows = r.shadows,
                    whites = r.whites, blacks = r.blacks,
                    texture = r.texture, clarity = r.clarity, dehaze = r.dehaze,
                    sharpRadius = r.sharpRadius, detail = r.detail, masking = r.masking,
                    grain = r.grain, grainSize = r.grainSize, grainRough = r.grainRough,
                    vignette = r.vignette,
                    vigMidpoint = r.vigMidpoint, vigFeather = r.vigFeather,
                    vigRound = r.vigRound, vigAspect = r.vigAspect,
                    hsl = r.hsl, grayscale = r.grayscale, bwMix = r.bwMix,
                    calibration = r.calibration, vibrance = r.vibrance,
                    colorGrade = r.colorGrade,
                    denoiseLum = r.denoiseLum, denoiseColor = r.denoiseColor,
                    defringe = r.defringe,
                    lensCA = r.lensCA, lensEnable = r.lensEnable,
                    lensDistort = r.lensDistort,
                    toneCurves = r.toneCurves,
                    splitAmount = r.splitAmount,
                    shadowTint = r.shadowTint, highlightTint = r.highlightTint,
                    geometry = r.geometry,
                ),
                xmpReport = result,
            )
        }
        // Keep it. An import that only lived in the draft meant the preset never
        // appeared in the Presets list at all, so it had to be re-typed and
        // saved by hand before it existed anywhere - which is the opposite of
        // what importing a preset is for. Kept under the name the file carries,
        // then the picked document's own name, and only then the base filter's
        // name when neither exists.
        val keepAs = result.name?.takeIf { it.isNotBlank() }
            ?: documentName(uri)?.uppercase()
            ?: _uiState.value.labBaseId.uppercase()
        keepRecipe(keepAs, _uiState.value.labRecipe)
        Feedback.info(context, "Imported $keepAs")
    }

    private var preXmpDraft: com.relic.catalog.lab.LabRecipe? = null

    /**
     * Dismisses the report AND reverts the draft to what it was before the
     * import. Applying-then-dismissing must not leave test values behind:
     * with no saved recipe selected, intensity from 0 has to fade in nothing,
     * not the preset that was just tried.
     */
    fun clearXmpReport() {
        val back = preXmpDraft
        preXmpDraft = null
        _uiState.update {
            if (back != null) it.copy(labRecipe = back, xmpReport = null)
            else it.copy(xmpReport = null)
        }
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

    // ---- stage chain ----

    private fun withStages(
        index: Int,
        f: (List<LabStage>) -> List<LabStage>,
    ) = _uiState.update { s ->
        val cur = s.labRecipe.stages
        val next = f(cur)
        // The cap is enforced here as well as in the recipe, so the UI cannot
        // build a five-stage chain that the renderer would then silently drop.
        s.copy(labRecipe = s.labRecipe.copy(stages = next.take(LabRecipe.MAX_STAGES)))
    }

    /** Appends a stage on its catalog defaults, if there is room. */
    fun addLabStage(primitiveId: String) {
        val prim = LabPrimitives.byId(primitiveId) ?: return
        if (!prim.chainable) return
        pendingSelectedRecipe = null
        withStages(-1) { it + LabStage(primitiveId) }
    }

    fun removeLabStage(index: Int) {
        pendingSelectedRecipe = null
        withStages(index) { cur -> cur.filterIndexed { i, _ -> i != index } }
    }

    /** Moves a stage by one slot. Out-of-range moves are ignored, not clamped. */
    fun moveLabStage(index: Int, delta: Int) {
        pendingSelectedRecipe = null
        withStages(index) { cur ->
            val to = index + delta
            if (index !in cur.indices || to !in cur.indices) return@withStages cur
            cur.toMutableList().apply { add(to, removeAt(index)) }
        }
    }

    fun setLabStageAmount(index: Int, amount: Float) {
        pendingSelectedRecipe = null
        withStages(index) { cur ->
            cur.mapIndexed { i, st ->
                if (i == index) st.copy(amount = amount.coerceIn(0f, 1f)) else st
            }
        }
    }

    /**
     * Moves one of a stage's named knobs.
     *
     * [param] is a shader parameter number, not a control index, because a
     * primitive's controls do not always start at param 1.
     */
    fun setLabStageParam(index: Int, param: Int, value: Float) {
        pendingSelectedRecipe = null
        withStages(index) { cur ->
            cur.mapIndexed { i, st ->
                if (i == index) {
                    st.copy(params = st.params + (param.toString() to value))
                } else {
                    st
                }
            }
        }
    }

    /** Clears one knob so the stage falls back to the catalog default. */
    fun resetLabStageParam(index: Int, param: Int) {
        pendingSelectedRecipe = null
        withStages(index) { cur ->
            cur.mapIndexed { i, st ->
                if (i == index) st.copy(params = st.params - param.toString()) else st
            }
        }
    }

    fun setLabStageMask(index: Int, mask: LabMask) {
        pendingSelectedRecipe = null
        withStages(index) { cur ->
            cur.mapIndexed { i, st -> if (i == index) st.copy(mask = mask) else st }
        }
    }

    /**
     * Sets a stage's mask from a drag on the viewfinder.
     *
     * [uv] is in frame coordinates with y already flipped to point up, matching
     * what the shader sees, so a mask drawn to look right on screen is the same
     * mask the shader applies. Getting that flip wrong is the single easiest way
     * to end up with a mask that mirrors the one the user drew.
     */
    fun setLabStageMaskFromDrag(index: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        // Read the stage once. Reading _uiState twice means the second read can
        // land after the stage was removed, which is an index crash in the middle
        // of what should be a gesture.
        val current = _uiState.value.labRecipe.stages.getOrNull(index) ?: return
        setLabStageMask(
            index,
            current.mask.copy(
                x = minOf(x0, x1), y = minOf(y0, y1),
                width = kotlin.math.abs(x1 - x0), height = kotlin.math.abs(y1 - y0),
            ),
        )
    }

    fun clearLabStages() {
        pendingSelectedRecipe = null
        _uiState.update { it.copy(labRecipe = it.labRecipe.copy(stages = emptyList())) }
    }

    fun resetLabKnobs() {
        _uiState.update { s ->
            var r = s.labRecipe
            for (k in com.relic.catalog.lab.LabKnob.entries) r = r.withKnob(k, k.neutral)
            r = r.copy(
                hsl = com.relic.catalog.lab.Hsl.NONE,
                calibration = com.relic.catalog.lab.Calibration.NONE,
                colorGrade = com.relic.catalog.lab.ColorGrade.NONE,
                bwMix = com.relic.catalog.lab.BwMix.NONE,
                defringe = com.relic.catalog.lab.Defringe.NONE,
                geometry = com.relic.catalog.lab.Geometry.NONE,
            )
            s.copy(labRecipe = r)
        }
    }

    fun clearLabCurves() {
        pendingSelectedRecipe = null
        _uiState.update { s -> s.copy(labRecipe = s.labRecipe.copy(toneCurves = com.relic.catalog.lab.ToneCurve.NONE)) }
    }

    /** Parametric slider -> rebuild composite curve (default splits 25/50/75). */
    fun setLabParametric(slot: Int, value: Float) {
        pendingSelectedRecipe = null
        _uiState.update { s ->
            val cur = s.labRecipe
            // Read current parametric amounts back from UI state is impossible here,
            // so fold single-slot delta onto existing composite: decode composite,
            // adjust by value, re-encode. Simpler and stable: build from value alone
            // with other slots at 0 would erase. Instead keep a session cache.
            val amounts = parametricCache.sliceArray(0 until 4)
            amounts[slot.coerceIn(0, 3)] = (value * 100f).coerceIn(-100f, 100f)
            for (i in 0 until 4) parametricCache[i] = amounts[i]
            val comp = com.relic.catalog.lab.ToneCurve.foldParametric(
                amounts[0], amounts[1], amounts[2], amounts[3], 25f, 50f, 75f,
            )
            val group = com.relic.catalog.lab.ToneCurve.parseGroup(cur.toneCurves)
            val next = listOf(comp, group.getOrNull(1), group.getOrNull(2), group.getOrNull(3))
            s.copy(labRecipe = cur.copy(toneCurves = com.relic.catalog.lab.ToneCurve.encodeGroup(next)))
        }
    }

    private val parametricCache = floatArrayOf(0f, 0f, 0f, 0f)

    /** True when there is anything worth saving. */
    fun canSaveLab(): Boolean = !_uiState.value.labRecipe.isIdentity

    /**
     * The base filter a recipe saved now has to sit on.
     *
     * A saved Lab recipe is not itself a catalog filter, so entering the Lab
     * while one of those was active left [UiState.labBaseId] pointing at a recipe
     * id that FilterCatalog has never heard of. `toSpec()` then returned null and
     * the save did nothing at all, silently. Falling back to the default filter
     * keeps the save working; the caller tells the user it happened.
     */
    private fun baseFilterToSaveOn(labBaseId: String): String =
        if (com.relic.catalog.FilterCatalog.byId.containsKey(labBaseId)) labBaseId
        else com.relic.catalog.FilterCatalog.default.id

    /**
     * Persists [lab] under [name], replacing any recipe with the same content
     * hash, and selects it. Shared by the save button and the XMP import so both
     * land in the preset list the same way.
     *
     * Returns whether it was actually written, so a caller can tell the user it
     * worked instead of saying so regardless - which is the bug this whole
     * change is about.
     */
    private fun keepRecipe(name: String, lab: com.relic.catalog.lab.LabRecipe): Boolean {
        val saved = com.relic.catalog.lab.SavedRecipe.create(
            name = name.take(com.relic.catalog.lab.SavedRecipe.MAX_NAME),
            baseId = baseFilterToSaveOn(_uiState.value.labBaseId),
            lab = lab,
        )
        if (saved.toSpec() == null) {
            notice("Couldn't save - this preset has nothing to apply")
            Feedback.error(context)
            return false
        }
        viewModelScope.launch {
            // Merged on the raw stored strings, NOT on decoded recipes.
            // Decoding first and re-encoding the survivors drops every recipe
            // this version cannot read - so every save quietly deleted the
            // user's other presets, and the strip appeared to eat them. Nothing
            // here is rewritten unless it is genuinely the recipe being
            // replaced, so an unreadable entry stays exactly as it was stored.
            val kept = settings.customRecipes.first().filterNot { raw ->
                com.relic.catalog.lab.RecipeCodec.decode(raw)?.id == saved.id
            }
            settings.setCustomRecipes(kept + com.relic.catalog.lab.RecipeCodec.encode(saved))
            settings.setIntensity(saved.id, _uiState.value.labIntensity)
            _uiState.update { it.copy(savedRecipeId = saved.id, labName = saved.name) }
        }
        return true
    }

    /**
     * Persists the draft. Deliberately does NOT leave the Lab: the two are
     * separate screens now, so saving is just saving, and handing the recipe to
     * the camera is the explicit [useRecipeInCamera] step.
     */
    fun saveLab() {
        val s = _uiState.value
        if (s.labRecipe.isIdentity) {
            Feedback.info(context, "Nothing to save yet")
            return
        }
        // Remember which recipe this draft is, so "use in camera" knows what to
        // hand over without re-deriving it from the name.
        pendingSelectedRecipe = null
        // Confirmed here rather than inside keepRecipe, so the XMP import - the
        // other caller - reports its own outcome instead of stacking a second
        // toast on top of "XMP imported". One confirmation per action, and only
        // when something was really written.
        if (keepRecipe(s.labName.ifBlank { s.labBaseId.uppercase() }, s.labRecipe)) {
            Feedback.info(context, "Saved")
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
        // Both of these used to be a bare `return`, so pressing a button that
        // could not do anything looked identical to pressing one that could.
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id }
        if (r == null) {
            notice("That preset is no longer saved")
            Feedback.error(context)
            return
        }
        val spec = r.toSpec()
        if (spec == null) {
            notice("This preset needs an effect this version no longer has")
            Feedback.error(context)
            return
        }
        _uiState.update { it.copy(filter = spec, mode = MODE_PHOTO) }
        viewModelScope.launch {
            settings.setIntensity(spec.id, _uiState.value.labIntensity)
            _uiState.update {
                it.copy(intensity = it.labIntensity, sizeScale = 1f, detailScale = 1f)
            }
        }
        Feedback.info(context, "${r.name} applied")
    }

    /**
     * Uses the current draft directly in the main camera, without saving it.
     *
     * This is the Basic-tab path: pick a premade template, hit Use in camera,
     * and shoot with it. Nothing is written to DataStore, so nothing appears
     * in the filter strip - the strip only ever shows saved presets, and a
     * premade you have not kept is not yours yet. The draft stays in the Lab,
     * so going back lets you keep adjusting or save it properly.
     */
    fun useDraftInCamera() {
        val s = _uiState.value
        if (s.labRecipe.isIdentity) {
            Feedback.info(context, "Pick a preset first")
            return
        }
        val base = com.relic.catalog.FilterCatalog.byId[s.labBaseId]
            ?: com.relic.catalog.FilterCatalog.default
        val templateName = s.labRecipe.templateId
            ?.let { com.relic.catalog.lab.LabTemplates.byId[it]?.displayName }
        val title = s.labName.ifBlank { templateName ?: base.displayName }.uppercase()
        // A transient id, never persisted. The strip is built from stored
        // recipes, so this look is usable but not listed - exactly what Basic
        // wants. Reusing one stable id keeps the camera from accumulating a
        // new filter entry per tap.
        val spec = base.copy(
            id = DRAFT_FILTER_ID,
            displayName = title,
            family = com.relic.catalog.FilterFamily.LAB,
            context = "lab draft, over ${base.displayName}",
            lab = s.labRecipe,
        )
        _uiState.update { it.copy(filter = spec, mode = MODE_PHOTO) }
        viewModelScope.launch {
            settings.setIntensity(spec.id, s.labIntensity)
            _uiState.update {
                it.copy(intensity = s.labIntensity, sizeScale = 1f, detailScale = 1f)
            }
        }
        Feedback.info(context, "$title applied")
    }

    /**
     * Saves the current draft as a shareable `.xmp` file, named by the user
     * first, and keeps it in the preset list under the same name.
     *
     * Both halves happen on one tap on purpose. A file without a list entry
     * cannot be re-used without re-importing it, and a list entry without a
     * file is not shareable - doing only one is why custom looks seemed to
     * vanish. One confirmation covers both, so success is never ambiguous.
     */
    fun saveDraftXmp(name: String, uri: android.net.Uri) {
        val clean = name.trim().uppercase().take(com.relic.catalog.lab.SavedRecipe.MAX_NAME)
        if (clean.isBlank()) {
            notice("Give it a name first")
            Feedback.error(context)
            return
        }
        if (_uiState.value.labRecipe.isIdentity) {
            Feedback.info(context, "Nothing to save yet")
            return
        }
        val kept = keepRecipe(clean, _uiState.value.labRecipe)
        val doc = com.relic.catalog.lab.XmpExport.export(_uiState.value.labRecipe, clean)
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(doc.toByteArray())
            } ?: throw IllegalStateException("no stream")
        }.isSuccess
        if (kept && written) {
            Feedback.info(context, "Saved $clean")
        } else if (kept) {
            notice("Kept $clean, but the file couldn't be written")
            Feedback.error(context)
        } else {
            notice("Couldn't write the file - try a different folder")
            Feedback.error(context)
        }
    }

    /**
     * The file's own display name, without its extension.
     *
     * A preset file does not always carry `crs:Name`, and falling back to the
     * base filter's name called every such import ORIGINAL. The document the
     * user picked usually has a real name, so that is what the preset takes.
     */
    private fun documentName(uri: android.net.Uri): String? {
        val display = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()
        return display?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun deleteLabRecipe(id: String) {
        viewModelScope.launch {
            // Same reason as keepRecipe: filter the raw strings rather than
            // decoding and re-encoding, so deleting one preset cannot take the
            // unreadable ones down with it.
            val kept = settings.customRecipes.first().filterNot { raw ->
                com.relic.catalog.lab.RecipeCodec.decode(raw)?.id == id
            }
            settings.setCustomRecipes(kept)
            // Deleting the preset you are looking at must also empty the
            // controls. The preset is gone, so leaving its values sitting in the
            // draft is a filter the user cannot get back, cannot see the source
            // of, and will be silently re-saved by the next tap of the save
            // button. Only the deleted preset is cleared: deleting some other
            // preset while editing this one must not throw away the edit.
            _uiState.update { s ->
                if (s.savedRecipeId != id) s
                else s.copy(
                    labRecipe = com.relic.catalog.lab.LabRecipe(),
                    labName = "",
                    savedRecipeId = null,
                )
            }
            // The other half of the same problem: "use in camera" copies the
            // recipe into the live filter, so deleting the preset used to leave
            // the camera preview still showing a look that no longer exists and
            // can no longer be re-selected from the strip. Put the camera back
            // on the plain original filter.
            //
            // The live filter holds the deleted look under two different ids:
            // its own, when handed over from the Presets list, or the transient
            // draft id, when handed over from Basic/Advanced/Stages. Checking
            // only the first left the camera stuck on the deleted look - old
            // name still on the label - whenever the draft path was the one
            // used. savedRecipeId ties the draft to its preset, so it is the
            // second half of the check.
            _uiState.update { s ->
                val liveIsDeleted = s.filter.id == id ||
                    (s.filter.id == DRAFT_FILTER_ID && s.savedRecipeId == id)
                if (!liveIsDeleted) s
                else s.copy(
                    filter = com.relic.catalog.FilterCatalog.default,
                    intensity = com.relic.catalog.FilterCatalog.default.defaultIntensity,
                    sizeScale = 1f,
                    detailScale = 1f,
                )
            }
            Feedback.info(context, "Preset deleted")
        }
    }

    /**
     * Loads a saved recipe back into the draft for editing.
     *
     * Stays on the current tab on purpose: jumping to Advanced on every tap
     * meant selecting a preset in the Presets list yanked you to another
     * screen, so the selection highlight you just earned was never seen. The
     * sliders follow the preset wherever you are; switch tabs to look at them.
     */
    fun editLabRecipe(id: String) {
        val r = _uiState.value.labRecipes.firstOrNull { it.id == id } ?: return
        pendingSelectedRecipe = null
        _uiState.update {
            it.copy(labName = r.name, labBaseId = r.baseId, labRecipe = r.lab, savedRecipeId = r.id)
        }
    }

    /**
     * Resets a knob to its neutral point.
     *
     * The neutral is read off a default recipe rather than stored, so it cannot
     * drift from what the shader actually treats as off.
     */
    fun resetLabKnob(which: Int) {
        val k = com.relic.catalog.lab.LabKnob.byIndex(which) ?: return
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
            val text = "Relic diagnostics\nfilters=" + FilterCatalog.all.size + "\n" + renderer.snapshot()
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
                put(MediaStore.Video.Media.DISPLAY_NAME, "Relic_$stamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, saveRelativePath())
                // Hidden until the encoder is stopped, so a failed take never
                // leaves a zero-byte video in the gallery.
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                notice("Couldn't start the video - storage may be full")
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
                notice("Couldn't write the video - try changing the save folder")
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
                    notice("Recording failed - not enough space or storage is busy")
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
                if (ok) Feedback.saved(context)
                else {
                    notice("Couldn't finish saving the video")
                    Feedback.error(context)
                }
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

    /** The app logo for the photo-card bar, decoded once; null falls back to the outline box. */
    private val appLogoBitmap: Bitmap? by lazy {
        runCatching {
            BitmapFactory.decodeResource(context.resources, com.relic.R.drawable.app_logo)
        }.getOrNull()
    }

    private fun writeToGallery(bitmap: Bitmap): Uri? {
        val png = _uiState.value.fileFormat == "PNG"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (png) "png" else "jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        // Polaroid card baked in when enabled (photos only).
        val card = _uiState.value.photoCard
        val shot = if (card) {
            PhotoCards.render(bitmap, "RELIC", _uiState.value.filter.displayName, stamp, appLogoBitmap)
        } else {
            bitmap
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Relic_$stamp.$ext")
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
        val recipes: List<com.relic.catalog.lab.SavedRecipe>,
        /**
         * Stored recipes we could not read at all, and stored recipes we could
         * read but have nothing to render. Kept apart so the messages can say
         * which of the two happened: one is the app's fault, the other is not.
         */
        val unreadable: Int,
        val noBase: Int,
    )

    companion object {
        const val MODE_PHOTO = "photo"
        const val MODE_VIDEO = "video"
        const val MODE_LAB = "lab"

        /**
         * The live filter's id when the Lab draft was handed to the camera
         * directly. Never persisted: the strip is built from stored recipes,
         * so a direct-use look is usable but not listed. One stable id rather
         * than one per tap, so the camera cannot accumulate filter entries.
         */
        const val DRAFT_FILTER_ID = "lab_draft"

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
            recipes: List<com.relic.catalog.lab.SavedRecipe>,
        ): List<FilterSpec> {
            val base = orderSpecs(fav)
            val custom = recipes.mapNotNull { it.toSpec() }
            return if (custom.isEmpty()) base else base + custom
        }
    }
}
