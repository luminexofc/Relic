package com.retrocam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.retrocam.catalog.lab.BuiltinLut
import com.retrocam.catalog.lab.LutCatalog
import java.io.File

/**
 * Filter Lab LUT sources: the procedurally generated built-ins, plus whatever
 * Hald PNGs the user has imported.
 *
 * Imported LUTs are copied into the app's own files directory, keyed by content
 * hash. That is what lets a shared recipe name its LUT by id instead of carrying
 * a megabyte of pixels through a QR code: if the receiving app has the file, the
 * id resolves; if not, the rest of the recipe still works without it.
 */
class LutStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "luts").apply { mkdirs() }

    /** A LUT the Lab can offer. [builtin] is null for imported files. */
    data class Entry(
        val id: String,
        val displayName: String,
        val context: String,
        val builtin: BuiltinLut?,
    )

    fun builtIns(): List<Entry> = LutCatalog.builtIn.map {
        Entry(it.id, it.displayName, it.context, it)
    }

    /** Imported LUTs found on disk, newest first. */
    fun imported(): List<Entry> = dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { f ->
            val id = f.nameWithoutExtension
            Entry(id, id.removePrefix("lut_").take(10).uppercase(), "imported hald lut", null)
        }
        ?: emptyList()

    fun all(): List<Entry> = builtIns() + imported()

    /**
     * Decodes an imported Hald PNG.
     *
     * Returns null for anything the shader could not index, with [reason] set for
     * the user-facing message. Rejects rather than silently resizing: resampling
     * a 512px CLUT down to 64 would quietly destroy the very gradients the user
     * exported it for.
     */
    fun import(uri: Uri): Result<Entry> {
        val bmp = try {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        } ?: return Result.failure(IllegalArgumentException("could not read that image"))

        val w = bmp.width
        val h = bmp.height
        if (!LutCatalog.isSupportedSize(w, h)) {
            bmp.recycle()
            return Result.failure(
                IllegalArgumentException(
                    "needs 512x512 or 64x64, got ${LutCatalog.describeSize(w, h)}",
                ),
            )
        }
        val cube = LutCatalog.cubeFor(w, h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        val id = LutCatalog.hashPixels(pixels, w, cube)
        val file = File(dir, "$id.png")
        if (!file.exists()) {
            // Re-encode rather than copying the original bytes: the pixels have
            // already been normalised to opaque RGBA, so what lands on disk is
            // exactly what was hashed.
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
        }
        return Result.success(Entry(id, id.removePrefix("lut_").take(10).uppercase(), "imported hald lut", null))
    }

    /**
     * Pixels for [id], or null if it is not available. Built-ins are rasterised
     * on demand; imports are read back from disk.
     */
    fun pixelsFor(id: String): Pixels? {
        LutCatalog.builtInById[id]?.let { return Pixels(it.rasterize(64), 512, 64) }
        val f = File(dir, "$id.png")
        if (!f.exists()) return null
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (t: Throwable) {
            null
        } ?: return null
        val w = bmp.width
        val h = bmp.height
        if (!LutCatalog.isSupportedSize(w, h)) {
            bmp.recycle()
            return null
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Pixels(px, w, LutCatalog.cubeFor(w, h))
    }

    data class Pixels(val pixels: IntArray, val side: Int, val cube: Int)

    fun delete(id: String): Boolean {
        if (LutCatalog.builtInById.containsKey(id)) return false
        return File(dir, "$id.png").delete()
    }
}
