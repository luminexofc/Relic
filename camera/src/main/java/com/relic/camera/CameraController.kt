package com.relic.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds CameraX Preview to the GL-owned SurfaceTexture. Flash = torch toggle
 * so preview and EGL capture always agree (WYSIWYG).
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(context) }
    private var camera: Camera? = null
    var torchOn = false
        private set

    /**
     * CameraX derives the buffer's target rotation from the display itself and
     * re-issues the surface request when it changes (including 180°, which
     * fires no config change). We deliberately do NOT set targetRotation here:
     * overriding it left the preview sideways in portrait.
     */

    /** Last delivered buffer resolution (for metering + aspect math). */
    @Volatile var bufferWidth = 1280
        private set
    @Volatile var bufferHeight = 720
        private set

    /** Invoked on the main thread whenever the delivered resolution changes. */
    var onResolutionChanged: ((Int, Int) -> Unit)? = null

    @Volatile var zoomRatio = 1f
        private set

    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceTexture: android.graphics.SurfaceTexture,
        frontCamera: Boolean = false,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            provider.unbindAll()
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
            preview.setSurfaceProvider(mainExecutor) { request ->
                val resolution = request.resolution
                bufferWidth = resolution.width
                bufferHeight = resolution.height
                onResolutionChanged?.invoke(resolution.width, resolution.height)
                surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
                request.provideSurface(android.view.Surface(surfaceTexture), cameraExecutor) { }
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(
                    if (frontCamera) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK,
                )
                .build()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
            camera?.cameraControl?.enableTorch(torchOn)
        }, mainExecutor)
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
    }

    fun focusAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val cam = camera ?: return
        runCatching {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                viewWidth.toFloat(), viewHeight.toFloat(),
            )
            val point = factory.createPoint(x, y)
            val action = androidx.camera.core.FocusMeteringAction.Builder(
                point,
                androidx.camera.core.FocusMeteringAction.FLAG_AF or
                    androidx.camera.core.FocusMeteringAction.FLAG_AE,
            ).setAutoCancelDuration(3L, java.util.concurrent.TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        }
    }

    /** Tap-and-hold: lock AF/AE until unlocked. Returns false when unavailable. */
    fun lockFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val cam = camera ?: return false
        return runCatching {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                viewWidth.toFloat(), viewHeight.toFloat(),
            )
            val point = factory.createPoint(x, y)
            val action = androidx.camera.core.FocusMeteringAction.Builder(
                point,
                androidx.camera.core.FocusMeteringAction.FLAG_AF or
                    androidx.camera.core.FocusMeteringAction.FLAG_AE,
            ).disableAutoCancel().build()
            cam.cameraControl.startFocusAndMetering(action)
            true
        }.getOrDefault(false)
    }

    fun unlockFocus() {
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
    }

    fun exposureRange(): IntRange {
        val state = camera?.cameraInfo?.exposureState
        val range = state?.exposureCompensationRange
        return if (range == null) -2..2 else range.lower..range.upper
    }

    fun exposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    fun setExposure(index: Int) {
        val range = exposureRange()
        runCatching { camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range)) }
    }

    fun zoomRange(): Pair<Float, Float> {
        val state = camera?.cameraInfo?.zoomState?.value
        return (state?.minZoomRatio ?: 1f) to (state?.maxZoomRatio ?: 8f)
    }

    fun setZoomRatio(ratio: Float) {
        val (min, max) = zoomRange()
        zoomRatio = ratio.coerceIn(min, max)
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    fun unbind() {
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
        camera = null
    }
}
