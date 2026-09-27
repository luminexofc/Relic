package com.retrocam.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.retrocam.catalog.lab.RecipeQr
import com.retrocam.catalog.lab.RecipeCodec
import com.retrocam.catalog.lab.SavedRecipe
import java.io.File

/**
 * Sharing a recipe as a QR image, and reading one back.
 *
 * Import is deliberately "pick an image" rather than a live camera scanner. The
 * main camera is bound to the viewfinder for the whole session, and adding a
 * second CameraX use case for scanning means unbinding and rebinding the one
 * thing this app exists to do. A QR sent over chat is usually an image anyway, so
 * this covers the real workflow without touching the camera path. A live scanner
 * is a separate piece of work, not a different import path.
 */
object QrShare {

    /**
     * Renders [recipe] as a QR bitmap.
     *
     * Dark modules are near-black on white, matching the reference frame's
     * palette rather than the app's dark theme: a QR is most often scanned off
     * someone else's screen, and maximum contrast is what survives a camera.
     */
    fun qrBitmap(recipe: SavedRecipe, px: Int = 720): Bitmap? {
        val m = RecipeQr.encodeMatrix(recipe, 512) ?: return null
        val side = 512
        val scale = (px / side).coerceAtLeast(1)
        val out = Bitmap.createBitmap(side * scale, side * scale, Bitmap.Config.ARGB_8888)
        val dark = Color.rgb(0x10, 0x10, 0x14)
        val light = Color.WHITE
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                out.setPixel(x, y, if (m[(y / scale) * side + (x / scale)]) dark else light)
            }
        }
        return out
    }

    /**
     * Shares the QR as a PNG.
     *
     * The raw recipe string goes in the chooser text as well, so a target that
     * cannot take an image still gets something usable.
     */
    fun share(context: Context, recipe: SavedRecipe): Boolean {
        val bmp = qrBitmap(recipe) ?: return false
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${recipe.id}.png")
        val ok = try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (t: Throwable) {
            false
        } finally {
            bmp.recycle()
        }
        if (!ok) return false

        val uri = try {
            FileProvider.getUriForFile(context, "com.retrocam.fileprovider", file)
        } catch (t: Throwable) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${recipe.name}\\n${RecipeCodec.encode(recipe)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, "Share ${recipe.name}"))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Reads a recipe out of a QR in [uri]. Handles the whole-image case and the
     * cropped-photo case by scanning the image and, if that misses, its centre
     * crop - people photograph the QR on a poster and the edges get cut off.
     */
    fun import(context: Context, uri: Uri): SavedRecipe? {
        val bmp = try {
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        } ?: return null
        try {
            decode(bmp)?.let { return it }
            // Retry on a centre crop, which is what a tightly framed photo of a
            // QR actually looks like.
            val w = bmp.width
            val h = bmp.height
            val side = (minOf(w, h) * 0.8f).toInt().coerceAtLeast(32)
            val l = (w - side) / 2
            val t = (h - side) / 2
            return decode(Bitmap.createBitmap(bmp, l, t, side, side))
        } finally {
            bmp.recycle()
        }
    }

    private fun decode(bmp: Bitmap): SavedRecipe? {
        val w = bmp.width
        val h = bmp.height
        if (w < 32 || h < 32) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return RecipeQr.decodeLuminances(RecipeQr.toLuminance(px, w, h), w, h)
    }
}
