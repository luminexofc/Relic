package com.retrocam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.retrocam.catalog.lab.BuiltinLut
import com.retrocam.catalog.lab.CubeLut
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

    /**
     * Imported LUTs found on disk, newest first.
     *
     * Both formats are kept as they arrived: a `.cube` stays a `.cube` and is
     * converted on read, so there is no encode step that could lose precision
     * and no second copy of a megabyte of text on disk.
     */
    /**
     * A NUL byte in the first kilobyte means binary. A .cube is ASCII digits and
     * directive names, and a PNG or JPEG is not, so this separates them
     * without trusting a filename or a MIME type from the provider.
     */
    private fun looksLikeText(raw: ByteArray): Boolean {
        val n = minOf(raw.size, 1024)
        for (i in 0 until n) if (raw[i] == 0.toByte()) return false
        return true
    }

    /** Stores a parsed `.cube` as-is, converted to pixels on read. */
    private fun importCube(text: String): Result<Entry> {
        val parsed = CubeLut.parse(text)
        if (parsed is CubeLut.Result.Bad) {
            return Result.failure(IllegalArgumentException(parsed.why))
        }
        val cube = (parsed as CubeLut.Result.Ok).cube
        val id = "lut_cube${cube.size}_${cube.title.hashCode().toUInt().toString(16)}"
        val f = File(dir, "$id.cube")
        if (!f.exists()) f.writeText(text)
        return Result.success(
            Entry(id, cube.title.ifBlank { id.take(14).uppercase() }, "imported ${cube.size}^3 cube", null),
        )
    }

    fun imported(): List<Entry> = dir.listFiles()
        ?.filter {
            it.isFile && (
                it.extension.equals("png", ignoreCase = true) ||
                    it.extension.equals("cube", ignoreCase = true)
                )
        }
        ?.sortedByDescending { it.lastModified() }
        ?.map { f ->
            val id = f.nameWithoutExtension
            val cube = cubeSizeOf(f)
            Entry(
                id,
                id.removePrefix("lut_").take(10).uppercase(),
                if (cube > 0) "imported $cube^3 cube" else "imported lut",
                null,
            )
        }
        ?: emptyList()

    /** The cube edge a stored LUT holds, or 0 if it cannot be read. */
    private fun cubeSizeOf(f: File): Int = when (f.extension.lowercase()) {
        "cube" -> (CubeLut.parse(f.readText()) as? CubeLut.Result.Ok)?.cube?.size ?: 0
        "png" -> {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return 0
            val c = LutCatalog.cubeFor(bmp.width, bmp.height)
            bmp.recycle()
            c
        }
        else -> 0
    }

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
        // Sniff the bytes rather than trusting the extension. A .cube is text and
        // BitmapFactory cannot read it, and plenty of providers hand back an
        // octet-stream with no usable name, so the content decides.
        val raw = try {
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } ?: return Result.failure(IllegalArgumentException("could not read that file"))
        if (raw.isEmpty()) {
            return Result.failure(IllegalArgumentException("that file is empty"))
        }

        if (looksLikeText(raw)) {
            return importCube(String(raw, Charsets.UTF_8))
        }

        val bmp = try {
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (t: Throwable) {
            null
        } ?: return Result.failure(
            IllegalArgumentException("not a .cube file and not a Hald image"),
        )

        val w = bmp.width
        val h = bmp.height
        if (!LutCatalog.isSupportedSize(w, h)) {
            bmp.recycle()
            return Result.failure(
                IllegalArgumentException(
                    "a Hald image needs to be 512x512, 64x64 or 198x198; " +
                        "got ${LutCatalog.describeSize(w, h)}",
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

        // A .cube is converted here rather than on import, so a .cube and a PNG
        // of the same LUT go through exactly the same upload path afterwards.
        val cubeFile = File(dir, "$id.cube")
        if (cubeFile.exists()) {
            val parsed = CubeLut.parse(cubeFile.readText())
            if (parsed is CubeLut.Result.Ok) {
                val px = CubeLut.toHaldPixels(parsed.cube)
                return Pixels(px, LutCatalog.gridFor(parsed.cube.size) * parsed.cube.size, parsed.cube.size)
            }
            return null
        }

        val f = File(dir, "$id.png")
        if (!f.exists()) return null
        val bmp = try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (t: Throwable) {
            null
        } ?: return null
        val w = bmp.width
        val h = bmp.height
        val cube = LutCatalog.cubeFor(w, h)
        if (cube <= 0) {
            bmp.recycle()
            return null
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Pixels(px, w, cube)
    }

    data class Pixels(val pixels: IntArray, val side: Int, val cube: Int)

    fun delete(id: String): Boolean {
        if (LutCatalog.builtInById.containsKey(id)) return false
        return File(dir, "$id.png").delete() || File(dir, "$id.cube").delete()
    }
}
