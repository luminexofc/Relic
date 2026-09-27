package com.retrocam.renderer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.media.MediaRecorder
import com.retrocam.catalog.FilterSpec
import com.retrocam.catalog.Shaders
import com.retrocam.catalog.lab.LabUniforms
import com.retrocam.catalog.lab.LutCatalog
import com.retrocam.catalog.lab.LabRecipe
import com.retrocam.catalog.lab.LabShaderSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

/**
 * Owns the EGL context: preview render, program cache (LRU 8), offscreen
 * capture at full resolution, and on-demand filter thumbnails.
 * GL calls only on the GL thread; results posted to main.
 */
class FilterRenderer(
    private val onTextureReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    private data class Program(
        val id: Int,
        val uTexTransform: Int,
        val uCoverScale: Int,
        val uCoverCenter: Int,
        val uGlyphAtlas: Int,
        val uGlyphAtlasSize: Int,
        val uGlyphCell: Int,
        val uGlyphCount: Int,
        val uMirror: Int,
        val uTheme: Int,
        val uResolution: Int,
        val uIntensity: Int,
        val uTime: Int,
        val uParam1: Int,
        val uParam2: Int,
        val uParam3: Int,
        val uPalette: Int,
        val uFeedback: Int,
        val uCcmR0: Int,
        val uCcmR1: Int,
        val uCcmR2: Int,
        val uCcmOffset: Int,
        val uVignette: Int,
        val uGrain: Int,
        val uSharpen: Int,
        val uBlur: Int,
        val uGlitch: Int,
        val uDuotone: Int,
        val uDuoShadow: Int,
        val uDuoHighlight: Int,
        val uLut: Int,
        val uLutAmount: Int,
        val uLutCube: Int,
        val uLutGrid: Int,
        val uStampTex: Int,
        val uStampRect: Int,
        val uStampAlpha: Int,
        val uMarkTex: Int,
        val uMarkRect: Int,
        val uMarkAlpha: Int,
        val aPosition: Int,
        val aTexCoord: Int,
    )

    /** One-shot offscreen grab. [links] overrides the live chain for this shot. */
    private data class CaptureRequest(
        val width: Int,
        val height: Int,
        val links: List<ChainLink>?,
        val callback: (Bitmap) -> Unit,
    )

    private data class ThumbnailRequest(val spec: FilterSpec, val sizePx: Int, val callback: (Bitmap) -> Unit)

    private data class PaletteRequest(val sizePx: Int, val callback: (IntArray) -> Unit)

    private val paletteQueue = java.util.concurrent.ConcurrentLinkedQueue<PaletteRequest>()

    private sealed interface VideoOp {
        class Start(
            val pfd: ParcelFileDescriptor,
            val width: Int,
            val height: Int,
            val withAudio: Boolean,
            val callback: (Boolean) -> Unit,
        ) : VideoOp

        class Stop(val callback: (Boolean) -> Unit) : VideoOp
    }

    /** One composer stage: filter + the intensity it runs at. */
    data class ChainLink(val spec: FilterSpec, val intensity: Float)

    /** Active composer chain (2-3 links) or null for single-filter mode. */
    @Volatile var chain: List<ChainLink>? = null

    private val programs = object : LinkedHashMap<String, Program>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Program>): Boolean {
            if (size > 40) {
                GLES20.glDeleteProgram(eldest.value.id)
                return true
            }
            return false
        }
    }

    @Volatile private var currentSpec: FilterSpec? = null
    @Volatile private var currentIntensity = 0.75f
    private val captureQueue = ArrayDeque<CaptureRequest>()
    private val thumbnailQueue = ArrayDeque<ThumbnailRequest>()
    private val videoQueue = ArrayDeque<VideoOp>()

    /** True while the encoder is consuming frames. */
    @Volatile var isRecording = false
        private set

    private var recorder: MediaRecorder? = null
    // EGL10 throughout: GLSurfaceView hands us an EGL10 context (EGLContext
    // .getEGL()), so EGL14 handles can never share it. Mixing the two makes
    // eglMakeCurrent fail and the encoder receives zero frames.
    private var encDisplay: EGLDisplay? = null
    private var encSurface: EGLSurface? = null
    private var screenDraw: EGLSurface? = null
    private var screenRead: EGLSurface? = null
    private var encWidth = 0
    private var encHeight = 0

    /** Frames handed to the encoder this session (diagnostics). */
    @Volatile var videoFrames = 0
        private set

    /** Last encoder failure reason, if any (diagnostics). */
    @Volatile var lastVideoError: String? = null
        private set

    private var oesTextureId = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var viewRef: GLSurfaceView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startNanos = SystemClock.elapsedRealtimeNanos()
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val transformMatrix = FloatArray(16)
    private val coverScale = FloatArray(2)
    private val lastMatrix = FloatArray(16)

    /** Mirror preview + capture (front camera selfie expectation). */
    @Volatile var mirror = false

    /** Paper theme (1) vs midnight (0) for ink-style filters. */
    @Volatile var theme = 0f

    /** Camera buffer size (px). Updated on bind; defaults to the 720p target. */
    @Volatile var bufWidth = 1280
    @Volatile var bufHeight = 720

    /** View aspect (w/h). Updated from layout; 0 = follow camera. */
    @Volatile var viewAspect = 0f

    /** Last preview surface size — used as capture resolution (WYSIWYG 1:1). */
    @Volatile var lastWidth = 0
        private set
    @Volatile var lastHeight = 0
        private set

    /**
     * Composer ping-pong pair. Screen and offscreen sizes get their OWN pair:
     * sharing one meant the live pass and the preview grab kept resizing each
     * other's textures, destroying and recreating 2 FBOs + 2 textures per frame.
     */
    private class FboPair {
        var fboA = 0
        var fboB = 0
        var texA = 0
        var texB = 0
        var w = 0
        var h = 0
    }

    private val screenChain = FboPair()
    private val offChain = FboPair()

    // Persistent offscreen surfaces (avoid pbuffer churn per grab).
    private var offSurface: EGLSurface? = null
    private var offW = 0
    private var offH = 0
    private var pbufferConfig: EGLConfig? = null
    private var thumbSurface: EGLSurface? = null
    private var thumbSize = 0

    // Reused readback scratch (no per-grab allocation).
    private var rbPixels = IntArray(0)
    private var rbFlipped = IntArray(0)

    /** Wall time of the last offscreen grab, ms (diagnostics). */
    @Volatile var lastGrabMs = 0L
        private set

    /** Smoothed cost of one preview frame, ms (diagnostics). */
    @Volatile var frameMs = 0f
        private set

    // Trails ping-pong (FBO pair at preview size).
    private var fboRead = 0
    private var fboWrite = 0
    private var fboTexRead = 0
    private var fboTexWrite = 0
    private var fboW = 0
    private var fboH = 0

    private val quadPos: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    fun attach(view: GLSurfaceView) {
        viewRef = view
    }

    fun setSpec(spec: FilterSpec, intensity: Float) {
        currentSpec = spec
        currentIntensity = intensity
    }

    fun precompileAsync(spec: FilterSpec) {
        viewRef?.queueEvent { getProgram(spec) }
    }

    /**
     * Warms a whole pipeline before it first draws: link 0 in the OES variant,
     * the rest in the 2D variant — so stacking a filter never stalls the GL
     * thread on a shader compile.
     */
    fun precompileLinksAsync(links: List<ChainLink>) {
        if (links.isEmpty()) return
        viewRef?.queueEvent {
            links.forEachIndexed { i, l -> getProgram(l.spec, twoDimensional = i > 0) }
        }
    }

    /** Device-side state dump for debugging (call from any thread). */
    fun snapshot(): String {
        val sb = StringBuilder()
        sb.appendLine("spec=" + (currentSpec?.id ?: "none") + " intensity=" + currentIntensity)
        sb.appendLine("programs=" + programs.keys.sorted().joinToString(","))
        sb.appendLine("linkFailures=" + linkFailures.joinToString(",").ifEmpty { "none" })
        sb.appendLine("buf=" + bufWidth + "x" + bufHeight + " viewAspect=" + viewAspect)
        sb.appendLine("cover=" + coverScale[0] + "," + coverScale[1] + " mirror=" + mirror + " theme=" + theme)
        sb.appendLine(
            "dispAspect=" + displayedAspect(lastMatrix) +
                " rot90=" + isTransposed(lastMatrix) +
                " cover=" + coverScale[0] + "," + coverScale[1],
        )
        sb.appendLine("surface=" + surfaceWidth + "x" + surfaceHeight + " last=" + lastWidth + "x" + lastHeight)
        sb.appendLine("matrix=" + lastMatrix.joinToString(","))
        sb.appendLine("chain=" + (chain?.size ?: 0) + " recording=" + isRecording)
        sb.appendLine("grabMs=" + lastGrabMs + " frameMs=" + "%.1f".format(frameMs))
        sb.appendLine("videoFrames=" + videoFrames + " videoError=" + lastVideoError)
        return sb.toString()
    }

    fun queueVideoStart(
        pfd: ParcelFileDescriptor,
        width: Int,
        height: Int,
        withAudio: Boolean,
        callback: (Boolean) -> Unit,
    ) {
        synchronized(videoQueue) { videoQueue.addLast(VideoOp.Start(pfd, width, height, withAudio, callback)) }
    }

    fun queueVideoStop(callback: (Boolean) -> Unit) {
        synchronized(videoQueue) { videoQueue.addLast(VideoOp.Stop(callback)) }
    }

    fun queueCapture(width: Int, height: Int, callback: (Bitmap) -> Unit) {
        queueCapture(width, height, null, callback)
    }

    /** [links] renders this one shot through that pipeline instead of [chain]. */
    fun queueCapture(width: Int, height: Int, links: List<ChainLink>?, callback: (Bitmap) -> Unit) {
        synchronized(captureQueue) { captureQueue.addLast(CaptureRequest(width, height, links, callback)) }
    }

    /**
     * Grabs a tiny snapshot of the current spec for CPU-side colour analysis.
     *
     * A readback has to be CPU work by definition, and doing it on the live frame
     * would stall the GL thread, so this renders a small offscreen copy at the
     * current spec instead. Deliberately tiny: the caller only wants a rough
     * palette, and 32x32 is 4KB.
     */
    fun queuePaletteProbe(callback: (IntArray) -> Unit) {
        paletteQueue.add(PaletteRequest(32, callback))
    }

    fun queueThumbnail(spec: FilterSpec, sizePx: Int, callback: (Bitmap) -> Unit) {
        synchronized(thumbnailQueue) { thumbnailQueue.addLast(ThumbnailRequest(spec, sizePx, callback)) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Fresh GL context (first open or return from another screen): every
        // cached program/FBO/surface handle belonged to a dead context.
        programs.clear()
        linkFailures.clear()
        screenChain.fboA = 0
        screenChain.fboB = 0
        screenChain.texA = 0
        screenChain.texB = 0
        screenChain.w = 0
        screenChain.h = 0
        offChain.w = 0
        offChain.h = 0
        offSurface = null
        offW = 0
        offH = 0
        pbufferConfig = null
        fboRead = 0
        fboWrite = 0
        fboTexRead = 0
        fboTexWrite = 0
        fboW = 0
        fboH = 0
        thumbSurface = null
        thumbSize = 0
        oesTextureId = -1
        glyphTexId = 0
        // Fresh GL context: every cached GL id, LUT textures included, belonged
        // to a dead context. The app re-queues them.
        if (labLuts.isNotEmpty()) {
            GLES20.glDeleteTextures(labLuts.size, labLuts.values.toIntArray(), 0)
            labLuts.clear()
            lutCubes.clear()
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        bindExternalParams(oesTextureId)
        glyphTexId = buildGlyphAtlas()

        val texture = SurfaceTexture(oesTextureId)
        texture.setOnFrameAvailableListener { viewRef?.requestRender() }
        surfaceTexture = texture
        mainHandler.post { onTextureReady(texture) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        lastWidth = width
        lastHeight = height
        fboW = 0 // force trails FBO recreate at the new size
        screenChain.w = 0 // force composer FBO recreate at the new size
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStart = SystemClock.elapsedRealtimeNanos()
        surfaceTexture?.updateTexImage()
        drainVideoOps()
        drainLabOps()
        val links = chain?.takeIf { it.size >= 1 }
        if (links != null) {
            renderChain(links, surfaceWidth, surfaceHeight, toScreen = true)
        } else {
            val spec = currentSpec ?: return
            val recipe = recipeLinks(spec, currentIntensity)
            if (recipe != null) {
                // A temporal base (TRAILS) has to advance its feedback buffer
                // before the chain runs, or the trails freeze on frame one: the
                // chain's link 0 re-renders the base into an FBO and the lab link
                // reads that, so nothing else would do the ping-pong.
                if (spec.temporal) accumulateTrails(spec, currentIntensity)
                renderChain(recipe, surfaceWidth, surfaceHeight, toScreen = true)
            } else {
                drawToScreen(spec, currentIntensity, surfaceWidth, surfaceHeight)
                if (spec.temporal) accumulateTrails(spec, currentIntensity)
            }
        }
        if (isRecording) renderVideoFrame()
        drainThumbnails()
        drainPaletteProbes()
        drainCaptures()
        frameMs = frameMs * 0.9f + (SystemClock.elapsedRealtimeNanos() - frameStart) / 1_000_000f * 0.1f
    }

    /**
     * Filter Lab LUT textures, keyed by recipe-visible id. Owned here rather than
     * in the UI because they are GL objects and must be re-created after a
     * context loss, same as every other handle in this class.
     */
    private val labLuts = LinkedHashMap<String, Int>()
    private val lutCubes = HashMap<String, Int>()

    /**
     * Uploads (or replaces) a LUT. [pixels] is a row-major Hald image, [side] its
     * width and height, [cube] the cube edge (16 or 64).
     *
     * Must be called on the GL thread; the UI hands the work over via
     * [queueLutUpload].
     */
    fun uploadLut(id: String, pixels: IntArray, side: Int, cube: Int) {
        labLuts[id]?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val buf = java.nio.ByteBuffer
            .allocateDirect(side * side * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
            buf.put(0xFF.toByte())
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, side, side, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
        // NEAREST matches upstream, which indexes the CLUT with integer
        // arithmetic and no interpolation.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        labLuts[id] = tex[0]
        lutCubes[id] = cube
    }

    fun hasLut(id: String): Boolean = labLuts.containsKey(id)

    /**
     * Overlay textures (date stamp, watermark logos), keyed by [stampKey] or by
     * the logo's content hash. Same GL lifetime rules as everything else here.
     */
    private val overlayTexs = LinkedHashMap<String, Int>()
    private val overlayAspects = HashMap<String, Float>()

    /** Key for a rasterised stamp: same text and colour is the same bitmap. */
    private fun stampKey(recipe: com.retrocam.catalog.lab.LabRecipe): String? {
        val t = recipe.stampText ?: return null
        if (t.isBlank()) return null
        return "stamp:" + com.retrocam.catalog.lab.DateStamp.hashStampText(t, recipe.stampColor)
    }

    private sealed interface LabOp {
        data class Upload(val id: String, val pixels: IntArray, val side: Int, val cube: Int) : LabOp
        data class Overlay(
            val id: String, val pixels: IntArray, val w: Int, val h: Int, val aspect: Float,
        ) : LabOp
    }

    private val labQueue = java.util.concurrent.ConcurrentLinkedQueue<LabOp>()

    /** Queues a LUT upload onto the GL thread. Safe to call from the main thread. */
    fun queueLutUpload(id: String, pixels: IntArray, side: Int, cube: Int) {
        labQueue.add(LabOp.Upload(id, pixels, side, cube))
    }

    /**
     * Queues an overlay upload. [aspect] must be the rasterised bitmap's real
     * width/height: the placement maths needs it and a mismatch would stretch
     * the stamp.
     */
    fun queueOverlayUpload(id: String, pixels: IntArray, w: Int, h: Int, aspect: Float) {
        labQueue.add(LabOp.Overlay(id, pixels, w, h, aspect))
    }

    private fun uploadOverlay(id: String, pixels: IntArray, w: Int, h: Int, aspect: Float) {
        overlayTexs[id]?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val buf = java.nio.ByteBuffer
            .allocateDirect(w * h * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
            buf.put(((p ushr 24) and 0xFF).toByte())
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
        // The stamp is a small raster scaled up, so LINEAR keeps it from looking
        // like a mosaic; the watermark is usually shown near native size.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        overlayTexs[id] = tex[0]
        overlayAspects[id] = aspect
    }

    private fun drainLabOps() {
        while (true) {
            when (val op = labQueue.poll() ?: return) {
                is LabOp.Upload -> uploadLut(op.id, op.pixels, op.side, op.cube)
                is LabOp.Overlay -> uploadOverlay(op.id, op.pixels, op.w, op.h, op.aspect)
            }
        }
    }

    /** Drops every overlay texture. Part of context-loss and release handling. */
    private fun clearOverlays() {
        if (overlayTexs.isEmpty()) return
        GLES20.glDeleteTextures(overlayTexs.size, overlayTexs.values.toIntArray(), 0)
        overlayTexs.clear()
        overlayAspects.clear()
    }

    fun release() {
        try {
            if (isRecording) stopEncoder()
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder stop on release failed", t)
        }
        try {
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetCurrentDisplay()
            destroyThumbSurface(egl, display)
            offSurface?.let { runCatching { egl.eglDestroySurface(display, it) } }
            offSurface = null
        } catch (t: Throwable) {
            Log.e(TAG, "Offscreen surface destroy on release failed", t)
        }
        surfaceTexture?.release()
        surfaceTexture = null
        programs.values.forEach { GLES20.glDeleteProgram(it.id) }
        programs.clear()
        linkFailures.clear()
        deleteTrailsFBO()
        deleteChainFBO(screenChain)
        deleteChainFBO(offChain)
        if (oesTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = -1
        }
        if (glyphTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(glyphTexId), 0)
            glyphTexId = 0
        }
        if (labLuts.isNotEmpty()) {
            GLES20.glDeleteTextures(labLuts.size, labLuts.values.toIntArray(), 0)
            labLuts.clear()
            lutCubes.clear()
        }
        clearOverlays()
    }

    // ---- internals (GL thread only) ----

    private fun timeSeconds(): Float =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000_000f

    /** Filters whose shader failed to link and silently fell back. */
    private val linkFailures = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun getProgram(spec: FilterSpec, twoDimensional: Boolean = false): Program {
        val key = if (twoDimensional) spec.id + "_2d" else spec.id
        return programs.getOrPut(key) {
            val frag = if (twoDimensional) Shaders.fragmentFor2D(spec.fragmentBody)
            else Shaders.fragmentFor(spec.fragmentBody)
            val id = linkProgram(Shaders.VERTEX_PASSTHROUGH, frag)
            // Safe mode: a broken filter falls back to passthrough (never black).
            val finalId = if (id != 0) id else run {
                Log.e(TAG, "Falling back to plain for filter " + spec.id)
                linkFailures.add(spec.id)
                linkProgram(Shaders.VERTEX_PASSTHROUGH, Shaders.fragmentFor(Shaders.PLAIN))
            }
            // Uniforms MUST be read from the program we actually draw. Reading
            // them from the failed `id` yields -1 for everything, which
            // silently disables the transform, cover-crop and intensity.
            val p = finalId
            Program(
                id = p,
                uTexTransform = GLES20.glGetUniformLocation(p, "u_texTransform"),
                uCoverScale = GLES20.glGetUniformLocation(p, "u_coverScale"),
                uCoverCenter = GLES20.glGetUniformLocation(p, "u_coverCenter"),
                uGlyphAtlas = GLES20.glGetUniformLocation(p, "u_glyphAtlas"),
                uGlyphAtlasSize = GLES20.glGetUniformLocation(p, "u_glyphAtlasSize"),
                uGlyphCell = GLES20.glGetUniformLocation(p, "u_glyphCell"),
                uGlyphCount = GLES20.glGetUniformLocation(p, "u_glyphCount"),
                uMirror = GLES20.glGetUniformLocation(p, "u_mirror"),
                uTheme = GLES20.glGetUniformLocation(p, "u_theme"),
                uResolution = GLES20.glGetUniformLocation(p, "u_resolution"),
                uIntensity = GLES20.glGetUniformLocation(p, "u_intensity"),
                uTime = GLES20.glGetUniformLocation(p, "u_time"),
                uParam1 = GLES20.glGetUniformLocation(p, "u_param1"),
                uParam2 = GLES20.glGetUniformLocation(p, "u_param2"),
                uParam3 = GLES20.glGetUniformLocation(p, "u_param3"),
                uPalette = GLES20.glGetUniformLocation(p, "u_palette"),
                uFeedback = GLES20.glGetUniformLocation(p, "u_feedback"),
                uCcmR0 = GLES20.glGetUniformLocation(p, "u_ccmR0"),
                uCcmR1 = GLES20.glGetUniformLocation(p, "u_ccmR1"),
                uCcmR2 = GLES20.glGetUniformLocation(p, "u_ccmR2"),
                uCcmOffset = GLES20.glGetUniformLocation(p, "u_ccmOffset"),
                uVignette = GLES20.glGetUniformLocation(p, "u_vignette"),
                uGrain = GLES20.glGetUniformLocation(p, "u_grain"),
                uSharpen = GLES20.glGetUniformLocation(p, "u_sharpen"),
                uBlur = GLES20.glGetUniformLocation(p, "u_blur"),
                uGlitch = GLES20.glGetUniformLocation(p, "u_glitch"),
                uDuotone = GLES20.glGetUniformLocation(p, "u_duotone"),
                uDuoShadow = GLES20.glGetUniformLocation(p, "u_duoShadow"),
                uDuoHighlight = GLES20.glGetUniformLocation(p, "u_duoHighlight"),
                uLut = GLES20.glGetUniformLocation(p, "u_lut"),
                uLutAmount = GLES20.glGetUniformLocation(p, "u_lutAmount"),
                uLutCube = GLES20.glGetUniformLocation(p, "u_lutCube"),
                uLutGrid = GLES20.glGetUniformLocation(p, "u_lutGrid"),
                uStampTex = GLES20.glGetUniformLocation(p, "u_stampTex"),
                uStampRect = GLES20.glGetUniformLocation(p, "u_stampRect"),
                uStampAlpha = GLES20.glGetUniformLocation(p, "u_stampAlpha"),
                uMarkTex = GLES20.glGetUniformLocation(p, "u_markTex"),
                uMarkRect = GLES20.glGetUniformLocation(p, "u_markRect"),
                uMarkAlpha = GLES20.glGetUniformLocation(p, "u_markAlpha"),
                // Cached once: glGetAttribLocation per draw was a driver query
                // on every pass of every frame.
                aPosition = GLES20.glGetAttribLocation(p, "aPosition"),
                aTexCoord = GLES20.glGetAttribLocation(p, "aTexCoord"),
            )
        }
    }

    private fun drawToScreen(spec: FilterSpec, intensity: Float, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        drawFullQuad(getProgram(spec), spec, intensity, width, height, timeSeconds())
    }

    private var recipeSpec: FilterSpec? = null
    private var recipeIntensity = Float.NaN
    private var recipeLinksCache: List<ChainLink> = emptyList()

    /**
     * The passes for a spec carrying a Filter Lab recipe: the base filter, then
     * the grade. Returns null for a plain filter, which is the common case and
     * keeps the single-pass path.
     *
     * Every render path goes through here — viewfinder, still capture, video
     * frame and thumbnails — because a recipe that showed in the preview but not
     * in the saved file would be worse than no recipe at all. Rebuilt only when
     * the spec or the intensity slider actually moves, so the steady state
     * allocates nothing per frame.
     */
    private fun recipeLinks(spec: FilterSpec, intensity: Float): List<ChainLink>? {
        val lab = spec.lab ?: return null
        if (lab.isIdentity) return null
        if (spec !== recipeSpec || intensity != recipeIntensity) {
            recipeSpec = spec
            recipeIntensity = intensity
            recipeLinksCache = listOf(
                ChainLink(spec, intensity),
                ChainLink(LabShaderSpec.spec, intensity),
            )
        }
        return recipeLinksCache
    }

    /**
     * True when the producer's transform rotates the buffer 90/270 degrees, so
     * its width and height swap places on screen. A bounding box cannot detect
     * this — a rotated unit square still measures 1x1 — which is why the aspect
     * and the crop axis both have to be corrected for it explicitly.
     */
    private fun isTransposed(m: FloatArray): Boolean = kotlin.math.abs(m[0]) < 0.5f

    /**
     * Aspect (w/h) the buffer occupies on screen. The producer hands us a
     * transform for the unit texture square — it encodes the crop rect, the
     * front-camera flip and any 90° transpose, so its bounding box (swapped
     * when transposed) is the reliable width/height to scale against.
     */
    private fun displayedAspect(m: FloatArray): Float {
        val swap = isTransposed(m)
        val bw = if (swap) bufHeight else bufWidth
        val bh = if (swap) bufWidth else bufHeight
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (u in 0..1) {
            for (v in 0..1) {
                val x = m[0] * u + m[4] * v + m[12]
                val y = m[1] * u + m[5] * v + m[13]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val w = (maxX - minX) * bw
        val h = (maxY - minY) * bh
        if (w <= 0f || h <= 0f) return bw.toFloat() / bh
        return w / h
    }

    /** Aspect of a non-OES (FBO) source, which is already in target space. */
    private fun srcAspect(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height else viewAspect

    private val combinedMatrix = FloatArray(16)

    /** Column-major 4x4 multiply: returns a * b. */
    private fun multiplyMM(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0..3) {
            for (row in 0..3) {
                var s = 0f
                for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
                r[c * 4 + row] = s
            }
        }
        return r
    }

    /** Column-major 2D affine: x' = a00*x + a01*y + tx, y' = a10*x + a11*y + ty. */
    private fun affine2D(a00: Float, a01: Float, a10: Float, a11: Float, tx: Float, ty: Float) =
        floatArrayOf(
            a00, a10, 0f, 0f,
            a01, a11, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        )

    /**
     * Builds mirror * T(c) * S(cover) * T(-c) * M into [combinedMatrix] —
     * exactly the mapping the old vertex shader computed per-vertex, but as a
     * matrix the fragment can apply to any frame coordinate. The mirror pivots
     * on the crop-rect centre c, matching CameraX PreviewView (which mirrors
     * about the surface crop centre, not the texture centre).
     *
     * @param derotate180 composes a frame-space 180° rotation FIRST (rightmost).
     *  The front stream on this device arrives 180° off: back preview is
     *  upright while front shows a perfect upside-down image through the
     *  identical pipeline, and neither the mirror (a flip) nor the cover
     *  (axis-aligned) can manufacture an in-plane 180° — so the offset is in
     *  the front buffer itself. Composing R180 exactly undoes an exact-180°
     *  error under every hypothesis (pure 180° or vertical flip: R180 o FlipV
     *  = FlipH, the correct selfie mirror), while preserving mirror/cover.
     */
    private fun buildCombinedMatrix(
        m: FloatArray,
        sx: Float,
        sy: Float,
        cx: Float,
        cy: Float,
        doMirror: Boolean,
        derotate180: Boolean,
    ) {
        val t1 = affine2D(1f, 0f, 0f, 1f, -cx, -cy)
        val s = affine2D(sx, 0f, 0f, sy, 0f, 0f)
        val t2 = affine2D(1f, 0f, 0f, 1f, cx, cy)
        val mx = if (doMirror) affine2D(-1f, 0f, 0f, 1f, 2f * cx, 0f) else IDENTITY_MATRIX
        // Innermost first: T1*M, then S, T2, Mx, and the frame-space R180 last
        // (rightmost = applied first to frame coordinates).
        var acc = multiplyMM(t1, m)
        acc = multiplyMM(s, acc)
        acc = multiplyMM(t2, acc)
        acc = multiplyMM(mx, acc)
        if (derotate180) {
            acc = multiplyMM(acc, affine2D(-1f, 0f, 0f, -1f, 1f, 1f))
        }
        acc.copyInto(combinedMatrix)
    }

    private fun drawFullQuad(
        prog: Program,
        spec: FilterSpec,
        intensity: Float,
        width: Int,
        height: Int,
        time: Float,
        inputTexId: Int = 0,
        inputIsOES: Boolean = true,
        applyMirror: Boolean = true,
    ) {
        if (prog.id == 0) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog.id)
        if (inputIsOES) {
            surfaceTexture?.getTransformMatrix(transformMatrix)
        } else {
            IDENTITY_MATRIX.copyInto(transformMatrix)
        }
        transformMatrix.copyInto(lastMatrix)
        // (u_texTransform is uploaded below as the combined matrix.)
        // Cover-crop so the buffer fills [width]x[height] with no distortion.
        // The destination is the real target (GL surface, encoder surface or
        // capture bitmap), NOT the viewfinder element: reusing the viewfinder
        // aspect here is what stretched the image when the frame was resized.
        val srcA = if (inputIsOES) displayedAspect(transformMatrix) else srcAspect(width, height)
        val dstA = if (width > 0 && height > 0) width.toFloat() / height else viewAspect
        if (srcA > 0f && dstA > 0f) {
            val fx = minOf(1f, dstA / srcA) // crop fraction along display x
            val fy = minOf(1f, srcA / dstA) // crop fraction along display y
            // The cover scale multiplies the transform's OUTPUT axes, which are
            // the swapped ones when the buffer is transposed.
            val swap = inputIsOES && isTransposed(transformMatrix)
            coverScale[0] = if (swap) fy else fx
            coverScale[1] = if (swap) fx else fy
        } else {
            coverScale[0] = 1f
            coverScale[1] = 1f
        }
        GLES20.glUniform2f(prog.uCoverScale, coverScale[0], coverScale[1])
        // Centre of the transform rect, in texture space: (0.5,0.5) for a plain
        // transform, offset when the producer sends a crop rect.
        val m = transformMatrix
        val cx = m[0] * 0.5f + m[4] * 0.5f + m[12]
        val cy = m[1] * 0.5f + m[5] * 0.5f + m[13]
        GLES20.glUniform2f(prog.uCoverCenter, cx, cy)
        GLES20.glUniform1f(prog.uMirror, if (applyMirror && mirror) 1f else 0f)
        // Combined frame->buffer matrix: mirror * T(c) * S(cover) * T(-c) * M.
        // This is what the fragment samples through, so rotation, crop and
        // mirror apply to every tap, not just the current pixel.
        // The 180° correction belongs to OES sampling (first/only pass): the
        // mirror stays on the final pass (display-space flip), matching how
        // composer chains split rotation (pass 1) from mirror (final).
        buildCombinedMatrix(
            m,
            coverScale[0],
            coverScale[1],
            cx,
            cy,
            doMirror = applyMirror && mirror,
            derotate180 = inputIsOES && mirror,
        )
        GLES20.glUniformMatrix4fv(prog.uTexTransform, 1, false, combinedMatrix, 0)
        GLES20.glUniform1f(prog.uTheme, theme)
        GLES20.glUniform2f(prog.uResolution, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(prog.uIntensity, intensity)
        GLES20.glUniform1f(prog.uTime, time)
        GLES20.glUniform1f(prog.uParam1, spec.param1)
        GLES20.glUniform1f(prog.uParam2, spec.param2)
        GLES20.glUniform1f(prog.uParam3, spec.param3)
        val palette = spec.palette
        if (prog.uPalette != -1 && palette != null) {
            GLES20.glUniform3fv(prog.uPalette, 16, paletteFloatsCached(palette), 0)
        }
        val lab = spec.lab
        if (prog.uCcmR0 != -1 && lab != null) {
            val u = labUniformsCached(lab)
            GLES20.glUniform3f(prog.uCcmR0, u.ccm[0], u.ccm[1], u.ccm[2])
            GLES20.glUniform3f(prog.uCcmR1, u.ccm[3], u.ccm[4], u.ccm[5])
            GLES20.glUniform3f(prog.uCcmR2, u.ccm[6], u.ccm[7], u.ccm[8])
            GLES20.glUniform3f(prog.uCcmOffset, u.ccm[9], u.ccm[10], u.ccm[11])
            GLES20.glUniform1f(prog.uVignette, u.vignette)
            GLES20.glUniform1f(prog.uGrain, u.grain)
            GLES20.glUniform1f(prog.uSharpen, u.sharpen)
            GLES20.glUniform1f(prog.uBlur, u.blur)
            GLES20.glUniform1f(prog.uGlitch, u.glitch)
            GLES20.glUniform1f(prog.uDuotone, u.duotone)
            GLES20.glUniform3f(prog.uDuoShadow, u.duotoneShadow[0], u.duotoneShadow[1], u.duotoneShadow[2])
            GLES20.glUniform3f(prog.uDuoHighlight, u.duotoneHighlight[0], u.duotoneHighlight[1], u.duotoneHighlight[2])
            // A recipe naming a LUT we do not hold (someone else's recipe, or an
            // import we have not downloaded) still grades: amount stays 0 and the
            // rest of the recipe works.
            val lutId = lab.lutId
            val lutTex = if (lutId != null) labLuts[lutId] else null
            if (lutTex != null && prog.uLut != -1) {
                GLES20.glUniform1f(prog.uLutAmount, if (lab.lutActive) u.lutAmount else 0f)
                GLES20.glUniform1f(prog.uLutCube, lutCubes[lutId]?.toFloat() ?: 64f)
                GLES20.glUniform1f(prog.uLutGrid, LutCatalog.gridFor(lutCubes[lutId] ?: 64).toFloat())
                GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
                GLES20.glUniform1i(prog.uLut, 4)
            } else if (prog.uLutAmount != -1) {
                GLES20.glUniform1f(prog.uLutAmount, 0f)
            }
            // Overlays. The stamp is keyed by its text+colour because that is
            // what the CPU rasterises; the watermark by its content hash.
            val stampTex = stampKey(lab)?.let { overlayTexs[it] }
            if (stampTex != null && prog.uStampRect != -1) {
                GLES20.glUniform4f(prog.uStampRect, u.stampRect[0], u.stampRect[1], u.stampRect[2], u.stampRect[3])
                GLES20.glUniform1f(prog.uStampAlpha, u.stampAlpha)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stampTex)
                GLES20.glUniform1i(prog.uStampTex, 5)
            } else if (prog.uStampRect != -1) {
                GLES20.glUniform4f(prog.uStampRect, 0f, 0f, 0f, 0f)
            }
            val markTex = lab.watermarkId?.let { overlayTexs[it] }
            if (markTex != null && prog.uMarkRect != -1) {
                GLES20.glUniform4f(prog.uMarkRect, u.markRect[0], u.markRect[1], u.markRect[2], u.markRect[3])
                GLES20.glUniform1f(prog.uMarkAlpha, u.markAlpha)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, markTex)
                GLES20.glUniform1i(prog.uMarkTex, 6)
            } else if (prog.uMarkRect != -1) {
                GLES20.glUniform4f(prog.uMarkRect, 0f, 0f, 0f, 0f)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (inputIsOES) {
            GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        }
        if (prog.uFeedback != -1 && fboTexRead != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexRead)
            GLES20.glUniform1i(prog.uFeedback, 2)
        }
        if (prog.uGlyphAtlas != -1 && glyphTexId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glyphTexId)
            GLES20.glUniform1i(prog.uGlyphAtlas, 3)
            GLES20.glUniform2f(prog.uGlyphAtlasSize, glyphAtlasW.toFloat(), glyphAtlasH.toFloat())
            GLES20.glUniform1f(prog.uGlyphCell, GLYPH_CELL_PX.toFloat())
            GLES20.glUniform1f(prog.uGlyphCount, glyphRamp.length.toFloat())
        }
        val posLoc = prog.aPosition
        val texLoc = prog.aTexCoord
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    // ---- video recording (GL thread only) ----

    private fun drainVideoOps() {
        while (true) {
            val op = synchronized(videoQueue) { videoQueue.pollFirst() } ?: return
            when (op) {
                is VideoOp.Start -> {
                    val ok = startEncoder(op.pfd, op.width, op.height, op.withAudio)
                    if (!ok) {
                        runCatching { op.pfd.close() }
                    }
                    mainHandler.post { op.callback(ok) }
                }
                is VideoOp.Stop -> {
                    val ok = stopEncoder()
                    mainHandler.post { op.callback(ok) }
                }
            }
        }
    }

    private fun startEncoder(pfd: ParcelFileDescriptor, width: Int, height: Int, withAudio: Boolean): Boolean {
        stopEncoderQuiet()
        videoFrames = 0
        lastVideoError = null
        return try {
            val rec = MediaRecorder()
            if (withAudio) {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (withAudio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(10_000_000)
            rec.setOrientationHint(0)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.prepare()
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetCurrentDisplay()
            val config = choosePbufferConfig(egl, display, recordable = true)
            if (config == null) {
                rec.release()
                runCatching { pfd.close() }
                lastVideoError = "no recordable EGL config"
                return false
            }
            screenDraw = egl.eglGetCurrentSurface(EGL10.EGL_DRAW)
            screenRead = egl.eglGetCurrentSurface(EGL10.EGL_READ)
            val enc = egl.eglCreateWindowSurface(
                display, config, rec.surface, intArrayOf(EGL10.EGL_NONE),
            )
            if (enc == null || enc === EGL10.EGL_NO_SURFACE) {
                rec.release()
                runCatching { pfd.close() }
                lastVideoError = "eglCreateWindowSurface failed"
                return false
            }
            recorder = rec
            encDisplay = display
            encSurface = enc
            encWidth = width
            encHeight = height
            pendingPfd = pfd
            rec.start()
            isRecording = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder start failed", t)
            lastVideoError = t.message
            stopEncoderQuiet()
            runCatching { pfd.close() }
            false
        }
    }

    private var pendingPfd: ParcelFileDescriptor? = null

    private fun renderVideoFrame() {
        val egl = EGLContext.getEGL() as EGL10
        val display = encDisplay ?: return
        val enc = encSurface ?: return
        val screen = screenDraw
        val screenCtx = egl.eglGetCurrentContext()
        try {
            if (!egl.eglMakeCurrent(display, enc, enc, screenCtx)) {
                lastVideoError = "encoder makeCurrent failed 0x" +
                    Integer.toHexString(egl.eglGetError())
                return
            }
            GLES20.glViewport(0, 0, encWidth, encHeight)
            val spec = currentSpec
            val links = chain?.takeIf { it.size >= 1 }
                ?: spec?.let { recipeLinks(it, currentIntensity) }
            if (links != null) {
                renderChain(links, encWidth, encHeight, toScreen = false)
            } else if (spec != null) {
                drawFullQuad(getProgram(spec), spec, currentIntensity, encWidth, encHeight, timeSeconds())
            }
            // EGL10 has no eglPresentationTimeANDROID; the encoder surface is a
            // BufferQueue, so eglSwapBuffers stamps the frame itself.
            if (egl.eglSwapBuffers(display, enc)) {
                videoFrames += 1
            } else {
                lastVideoError = "encoder swap failed 0x" + Integer.toHexString(egl.eglGetError())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Video frame failed", t)
            lastVideoError = t.message
        } finally {
            // Always restore the preview surface, or the GL thread keeps
            // rendering into the encoder and the viewfinder freezes.
            runCatching {
                egl.eglMakeCurrent(display, screen, screenRead, screenCtx)
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
        }
    }

    private fun stopEncoder(): Boolean {
        var ok = true
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder stop failed", t)
            ok = false
        }
        stopEncoderQuiet()
        return ok
    }

    private fun stopEncoderQuiet() {
        isRecording = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        try {
            val egl = EGLContext.getEGL() as EGL10
            val display = encDisplay
            val enc = encSurface
            if (display != null && enc != null && enc !== EGL10.EGL_NO_SURFACE) {
                egl.eglDestroySurface(display, enc)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Destroy encoder surface failed", t)
        }
        encDisplay = null
        encSurface = null
        screenDraw = null
        screenRead = null
        runCatching { pendingPfd?.close() }
        pendingPfd = null
    }

    private fun drainPaletteProbes() {
        while (true) {
            // ConcurrentLinkedQueue, so poll() rather than pollFirst().
            val req = paletteQueue.poll() ?: return
            val spec = currentSpec
            if (spec == null) continue
            val n = req.sizePx
            val px = IntArray(n * n)
            renderThumbnail(spec, n)?.getPixels(px, 0, n, 0, 0, n, n)
            mainHandler.post { req.callback(px) }
        }
    }

    private fun drainThumbnails() {
        while (true) {
            val req = synchronized(thumbnailQueue) { thumbnailQueue.pollFirst() } ?: return
            val bmp = renderThumbnail(req.spec, req.sizePx)
            if (bmp != null) mainHandler.post { req.callback(bmp) }
        }
    }

    private fun drainCaptures() {
        while (true) {
            val req = synchronized(captureQueue) { captureQueue.pollFirst() } ?: return
            val spec = currentSpec ?: return
            val bmp = renderOffscreen(spec, currentIntensity, req.width, req.height, req.links)
            if (bmp != null) mainHandler.post { req.callback(bmp) }
        }
    }

    // ---- filter composer chains (GL thread only) ----

    private fun ensureChainFBO(pair: FboPair, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        if (pair.fboA != 0 && pair.w == w && pair.h == h) return
        deleteChainFBO(pair)
        val fbos = IntArray(2)
        GLES20.glGenFramebuffers(2, fbos, 0)
        val texs = IntArray(2)
        GLES20.glGenTextures(2, texs, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texs[i], 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        pair.fboA = fbos[0]
        pair.fboB = fbos[1]
        pair.texA = texs[0]
        pair.texB = texs[1]
        pair.w = w
        pair.h = h
    }

    /**
     * Renders [links] in sequence. Intermediate passes land in [pair]'s FBOs;
     * the final pass goes to the screen ([toScreen]) or stays on the current
     * surface (capture pbuffer).
     */
    private fun renderChain(
        links: List<ChainLink>,
        w: Int,
        h: Int,
        toScreen: Boolean,
        pair: FboPair = screenChain,
    ) {
        // Only the LIVE viewfinder scales its intermediate passes. The aspect
        // is preserved, so each pass's cover crop is unchanged — the last pass
        // just upscales. Captures keep full resolution end to end.
        val k = if (toScreen && links.size > 1) minOf(1f, 720f / maxOf(w, h)) else 1f
        val workW = (w * k).toInt().coerceAtLeast(2)
        val workH = (h * k).toInt().coerceAtLeast(2)
        ensureChainFBO(pair, workW, workH)
        if (pair.fboA == 0) return
        val time = timeSeconds()
        var readTex = 0
        var readIsOES = true
        links.forEachIndexed { index, link ->
            val last = index == links.lastIndex
            // Link 0 samples the camera's external OES texture; every later link
            // samples the previous link's FBO, which is an ordinary 2D texture.
            // The program must be the matching variant, or the declared sampler
            // type disagrees with what is bound and the pass reads garbage.
            val twoD = !readIsOES
            if (last && toScreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                drawFullQuad(getProgram(link.spec, twoD), link.spec, link.intensity, w, h, time, readTex, readIsOES, true)
                return@forEachIndexed
            }
            val targetFbo = if (index % 2 == 0) pair.fboA else pair.fboB
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
            GLES20.glViewport(0, 0, workW, workH)
            drawFullQuad(getProgram(link.spec, twoD), link.spec, link.intensity, workW, workH, time, readTex, readIsOES, last)
            readTex = if (index % 2 == 0) pair.texA else pair.texB
            readIsOES = false
        }
        if (toScreen) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
    }

    private fun deleteChainFBO(pair: FboPair) {
        if (pair.fboA != 0) {
            GLES20.glDeleteFramebuffers(2, intArrayOf(pair.fboA, pair.fboB), 0)
            pair.fboA = 0
            pair.fboB = 0
        }
        if (pair.texA != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(pair.texA, pair.texB), 0)
            pair.texA = 0
            pair.texB = 0
        }
        pair.w = 0
        pair.h = 0
    }

    // ---- motion-trails accumulation (GL thread only) ----

    private fun ensureTrailsFBO(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        if (fboRead != 0 && fboW == w && fboH == h) return
        deleteTrailsFBO()
        val fbos = IntArray(2)
        GLES20.glGenFramebuffers(2, fbos, 0)
        val texs = IntArray(2)
        GLES20.glGenTextures(2, texs, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texs[i], 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fboRead = fbos[0]
        fboWrite = fbos[1]
        fboTexRead = texs[0]
        fboTexWrite = texs[1]
        fboW = w
        fboH = h
    }

    private fun accumulateTrails(spec: FilterSpec, intensity: Float) {
        ensureTrailsFBO(surfaceWidth, surfaceHeight)
        if (fboWrite == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboWrite)
        GLES20.glViewport(0, 0, fboW, fboH)
        // Feedback buffer stays unmirrored; mirroring applies at final display.
        drawFullQuad(getProgram(spec), spec, intensity, fboW, fboH, timeSeconds(), applyMirror = false)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        var t = fboRead
        fboRead = fboWrite
        fboWrite = t
        t = fboTexRead
        fboTexRead = fboTexWrite
        fboTexWrite = t
    }

    private fun deleteTrailsFBO() {
        if (fboRead != 0) {
            GLES20.glDeleteFramebuffers(2, intArrayOf(fboRead, fboWrite), 0)
            fboRead = 0
            fboWrite = 0
        }
        if (fboTexRead != 0) {
            GLES20.glDeleteTextures(2, intArrayOf(fboTexRead, fboTexWrite), 0)
            fboTexRead = 0
            fboTexWrite = 0
        }
        fboW = 0
        fboH = 0
    }

    /** Renders the current camera frame through [spec] to a pbuffer and reads it back. */
    private fun renderOffscreen(
        spec: FilterSpec,
        intensity: Float,
        width: Int,
        height: Int,
        links: List<ChainLink>? = null,
    ): Bitmap? {
        val egl = EGLContext.getEGL() as EGL10
        val display: EGLDisplay = egl.eglGetCurrentDisplay()
        val context: EGLContext = egl.eglGetCurrentContext()
        if (display === EGL10.EGL_NO_DISPLAY || context === EGL10.EGL_NO_CONTEXT) return null
        val started = SystemClock.elapsedRealtimeNanos()
        val surface = ensureOffscreenSurface(egl, display, width, height) ?: return null
        val draw = egl.eglGetCurrentSurface(EGL10.EGL_DRAW)
        val read = egl.eglGetCurrentSurface(EGL10.EGL_READ)
        try {
            if (!egl.eglMakeCurrent(display, surface, surface, context)) return null
            GLES20.glViewport(0, 0, width, height)
            val chainLinks = (links ?: chain)?.takeIf { it.size >= 1 }
                ?: recipeLinks(spec, intensity)
            if (chainLinks != null) {
                renderChain(chainLinks, width, height, toScreen = false, pair = offChain)
            } else {
                drawFullQuad(getProgram(spec), spec, intensity, width, height, timeSeconds())
            }
            // No glFinish: glReadPixels below already blocks until the pass lands.
            val bmp = readbackCurrent(width, height)
            lastGrabMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
            return bmp
        } finally {
            egl.eglMakeCurrent(display, draw, read, context)
        }
    }

    /** One reusable pbuffer for every offscreen grab; recreated only on resize. */
    private fun ensureOffscreenSurface(egl: EGL10, display: EGLDisplay, w: Int, h: Int): EGLSurface? {
        offSurface?.let { if (offW == w && offH == h) return it }
        offSurface?.let { runCatching { egl.eglDestroySurface(display, it) } }
        val config = pbufferConfig ?: choosePbufferConfig(egl, display)?.also { pbufferConfig = it } ?: return null
        val created = egl.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL10.EGL_WIDTH, w, EGL10.EGL_HEIGHT, h, EGL10.EGL_NONE),
        ) ?: return null
        offSurface = created
        offW = w
        offH = h
        return created
    }

    /** Thumbnail path reusing one persistent pbuffer (no per-thumb churn). */
    private fun renderThumbnail(spec: FilterSpec, size: Int): Bitmap? {
        val egl = EGLContext.getEGL() as EGL10
        val display: EGLDisplay = egl.eglGetCurrentDisplay()
        val context: EGLContext = egl.eglGetCurrentContext()
        if (display === EGL10.EGL_NO_DISPLAY || context === EGL10.EGL_NO_CONTEXT) return null
        if (thumbSurface == null || thumbSize != size) {
            destroyThumbSurface(egl, display)
            val config = pbufferConfig ?: choosePbufferConfig(egl, display)?.also { pbufferConfig = it } ?: return null
            thumbSurface = egl.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL10.EGL_WIDTH, size, EGL10.EGL_HEIGHT, size, EGL10.EGL_NONE),
            ) ?: return null
            thumbSize = size
        }
        val draw = egl.eglGetCurrentSurface(EGL10.EGL_DRAW)
        val read = egl.eglGetCurrentSurface(EGL10.EGL_READ)
        try {
            if (!egl.eglMakeCurrent(display, thumbSurface, thumbSurface, context)) return null
            GLES20.glViewport(0, 0, size, size)
            // Thumbnails for a saved recipe must show its grade, or the strip
            // icon would not match what the camera does.
            val recipe = recipeLinks(spec, 1f)
            if (recipe != null) {
                renderChain(recipe, size, size, toScreen = false, pair = offChain)
            } else {
                drawFullQuad(getProgram(spec), spec, 1f, size, size, timeSeconds())
            }
            return readbackCurrent(size, size)
        } finally {
            egl.eglMakeCurrent(display, draw, read, context)
        }
    }

    private fun destroyThumbSurface(egl: EGL10, display: EGLDisplay) {
        thumbSurface?.let { runCatching { egl.eglDestroySurface(display, it) } }
        thumbSurface = null
        thumbSize = 0
    }

    private fun choosePbufferConfig(
        egl: EGL10,
        display: EGLDisplay,
        recordable: Boolean = false,
    ): EGLConfig? {
        val spec = ArrayList<Int>()
        fun put(key: Int, value: Int) { spec.add(key); spec.add(value) }
        put(EGL10.EGL_RED_SIZE, 8)
        put(EGL10.EGL_GREEN_SIZE, 8)
        put(EGL10.EGL_BLUE_SIZE, 8)
        put(EGL10.EGL_ALPHA_SIZE, 8)
        put(EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT)
        if (recordable) {
            // The encoder consumes this surface, so it must be recordable and
            // a window surface — a pbuffer config would be rejected.
            put(EGL_RECORDABLE_ANDROID, 1)
            put(EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT)
        } else {
            put(EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT)
        }
        spec.add(EGL10.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, spec.toIntArray(), configs, 1, num) || num[0] == 0) return null
        return configs[0]
    }

    /** Reads back the current surface (bottom-up RGBA → upright ARGB bitmap). */
    private fun readbackCurrent(width: Int, height: Int): Bitmap {
        val n = width * height
        if (rbPixels.size < n) {
            rbPixels = IntArray(n)
            rbFlipped = IntArray(n)
        }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.IntBuffer.wrap(rbPixels, 0, n))
        val pixels = rbPixels
        val flipped = rbFlipped
        for (y in 0 until height) {
            var src = (height - 1 - y) * width
            var dst = y * width
            for (x in 0 until width) {
                val rgba = pixels[src++]
                flipped[dst++] = (rgba ushr 24 shl 24) or
                    ((rgba and 0xFF) shl 16) or
                    ((rgba ushr 8 and 0xFF) shl 8) or
                    (rgba ushr 16 and 0xFF)
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(flipped, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
        val programId = GLES20.glCreateProgram()
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vertex == 0 || fragment == 0) {
            GLES20.glDeleteProgram(programId)
            return 0
        }
        GLES20.glAttachShader(programId, vertex)
        GLES20.glAttachShader(programId, fragment)
        GLES20.glLinkProgram(programId)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(programId))
            GLES20.glDeleteProgram(programId)
            return 0
        }
        return programId
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun bindExternalParams(textureId: Int) {
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // ---- ASCII glyph atlas (GL thread only) ----

    private var glyphTexId = 0
    private var glyphAtlasW = 0
    private var glyphAtlasH = 0

    /** Characters ordered light -> dense; index follows luminance. */
    /** Ink-coverage ordered. 20 steps so tone gradates instead of banding. */
    private val glyphRamp = " .',:;-~=+*xX0#%$@"

    private fun buildGlyphAtlas(): Int {
        val n = glyphRamp.length
        val cell = GLYPH_CELL_PX
        val bmp = Bitmap.createBitmap(cell * n, cell, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            // Fit the tallest glyph (|@) inside the cell with a little slack.
            textSize = cell * 0.78f
        }
        val fm = paint.fontMetrics
        val baseline = cell / 2f - (fm.ascent + fm.descent) / 2f
        for (i in 0 until n) {
            val ch = glyphRamp[i]
            if (ch != ' ') canvas.drawText(ch.toString(), i * cell + cell / 2f, baseline, paint)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        // NEAREST keeps glyph edges hard; CLAMP stops bleeding between glyphs.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        bmp.recycle()
        glyphAtlasW = cell * n
        glyphAtlasH = cell
        return ids[0]
    }

    companion object {
        private const val TAG = "RetroCamGL"

        /** EGL_ANDROID_recordable (0x3142): encoder-compatible configs. */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** ASCII atlas glyph cell in px; the shader scales it to the cell size. */
        // Sized to the rendered cell (~11px at 170 columns on a 1080p frame).
        // A 24px cell made the shader nearest-downsample the atlas ~2.4x, which
        // is what turned the glyph field into noise.
        private const val GLYPH_CELL_PX = 12

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        /** Pipelines share one palette array instance, so identity hits ~always. */
        fun paletteFloatsCached(palette: IntArray): FloatArray {
            if (palette === cachedPalette) return cachedPaletteFloats
            val out = paletteFloats(palette)
            cachedPalette = palette
            cachedPaletteFloats = out
            return out
        }

        /**
         * Same trick for the Filter Lab grade and effects. The recipe is
         * immutable, so an unchanged instance means unchanged uniforms and this is
         * free; editing any slider hands us a new instance and we recompute once.
         */
        fun labUniformsCached(recipe: LabRecipe): LabUniforms {
            if (recipe === cachedRecipe) return cachedLab
            val out = LabUniforms.of(recipe)
            cachedRecipe = recipe
            cachedLab = out
            return out
        }

        @JvmStatic private var cachedRecipe: LabRecipe? = null
        @JvmStatic private var cachedLab: LabUniforms = LabUniforms.of(LabRecipe())

        @JvmStatic private var cachedPalette: IntArray? = null
        @JvmStatic private var cachedPaletteFloats: FloatArray = FloatArray(48)

        fun paletteFloats(palette: IntArray): FloatArray {
            val out = FloatArray(48)
            palette.take(16).forEachIndexed { i, packed ->
                out[i * 3] = (packed shr 16 and 0xFF) / 255f
                out[i * 3 + 1] = (packed shr 8 and 0xFF) / 255f
                out[i * 3 + 2] = (packed and 0xFF) / 255f
            }
            return out
        }

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
    }
}
