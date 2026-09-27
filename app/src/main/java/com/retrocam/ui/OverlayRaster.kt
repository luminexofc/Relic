package com.retrocam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.retrocam.catalog.lab.DateStamp
import com.retrocam.catalog.lab.LutCatalog
import com.uvstudio.him.photofilterlibrary.FilterEngine
import java.io.File

/**
 * Rasterises the Filter Lab's overlays into bitmaps the renderer can upload.
 *
 * This is the CPU half of the hybrid, and it exists for a concrete reason: text
 * layout has no GPU equivalent. `FilterEngine.applyDateStamp` measures the glyphs
 * with `Paint.getTextBounds` and draws them with a `setShadowLayer` glow, neither
 * of which a fragment shader can do. So the library does what only it can, once
 * per (text, colour), and the shader composites the result live at any
 * resolution from then on.
 */
class OverlayRaster(context: Context) {

    private val appContext = context.applicationContext
    private val marks = File(appContext.filesDir, "marks").apply { mkdirs() }
    private val stampCache = HashMap<String, Bitmap>()

    /** An uploaded overlay: pixels plus the aspect the placement maths needs. */
    data class Raster(val pixels: IntArray, val width: Int, val height: Int, val aspect: Float)

    /**
     * Rasterises [text] into a tight, transparent bitmap.
     *
     * Upstream derives its text size from the *source* height
     * (`max(24, height * 0.038)`), so the probe has to be tall enough to get the
     * size we want and the result is cropped back down to the glyphs. Position
     * and padding are irrelevant here: the shader places the finished bitmap in
     * UV space, so rasterising always uses TOP_LEFT with no padding.
     */
    fun stamp(text: String, color: Int): Raster? {
        val key = "$text|$color"
        stampCache[key]?.let { return it.toRaster() }
        if (text.isBlank()) return null

        // Aim for a text height that stays crisp when the shader scales it to
        // roughly 4% of a 1080p frame.
        val wantText = 64f
        val probeH = (wantText / DateStamp.TEXT_HEIGHT_FRACTION).toInt().coerceAtLeast(64)
        val probeW = (text.length * wantText * 0.75f).toInt().coerceAtLeast(16) + 64

        val probe = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
        val drawn = try {
            FilterEngine.applyDateStamp(
                probe,
                text,
                color,
                com.uvstudio.him.photofilterlibrary.WatermarkPosition.TOP_LEFT,
                0f,
            )
        } catch (t: Throwable) {
            probe.recycle()
            return null
        }
        val cropped = cropToContent(drawn) ?: run {
            drawn.recycle()
            return null
        }
        stampCache[key] = cropped
        return cropped.toRaster()
    }

    /** Tight bounding box of the non-transparent pixels, or null if empty. */
    private fun cropToContent(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) {
            src.recycle()
            return null
        }
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, minX, minY, cw, ch)
        src.recycle()
        return out
    }

    // ---- watermark logos ----

    /** Watermarks the user has imported, keyed by content hash. */
    fun watermarks(): List<Pair<String, String>> =
        marks.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension to it.nameWithoutExtension.removePrefix("mark_").take(10).uppercase() }
            ?: emptyList()

    /**
     * Imports a logo. Rejects rather than resizing for the same reason the LUT
     * import does: silently rescaling someone's artwork is not what they asked
     * for. Capped so a huge photo cannot become a 40 MB texture.
     */
    fun importWatermark(uri: Uri): Result<Pair<String, String>> {
        val bmp = try {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        } ?: return Result.failure(IllegalArgumentException("could not read that image"))
        if (bmp.width < 8 || bmp.height < 8) {
            bmp.recycle()
            return Result.failure(IllegalArgumentException("too small to be a logo"))
        }
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > 1024) {
            val k = 1024f / longest
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true,
            )
            bmp.recycle()
            return persist(scaled)
        }
        return persist(bmp)
    }

    private fun persist(bmp: Bitmap): Result<Pair<String, String>> {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val id = "mark_" + LutCatalog.hashPixels(px, bmp.width, 0).removePrefix("lut_")
        val file = File(marks, "$id.png")
        if (!file.exists()) {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bmp.recycle()
        return Result.success(id to id.removePrefix("mark_").take(10).uppercase())
    }

    fun watermark(id: String): Raster? {
        if (!id.startsWith("mark_")) return null
        val f = File(marks, "$id.png")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val r = bmp.toRaster()
        bmp.recycle()
        return r
    }

    private fun Bitmap.toRaster(): Raster {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return Raster(px, width, height, width.toFloat() / height.toFloat())
    }
}
