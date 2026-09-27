package com.retrocam.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Photo card baked into gallery saves: mat, photo, caption strip. Pure
 * android.graphics, no Compose.
 */
object PhotoCards {

    private val PAPER = Color.rgb(0xFA, 0xF7, 0xF0)
    private const val INK = 0xFF1A1A1A.toInt()

    private fun paint(size: Float, color: Int) = Paint().apply {
        isAntiAlias = true
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        textSize = size
    }

    fun render(photo: Bitmap, header: String, title: String, details: String? = null): Bitmap {
        val mat = (minOf(photo.width, photo.height) * 0.06f).toInt().coerceAtLeast(8)
        val strip = (photo.height * 0.20f).toInt().coerceAtLeast(120)
        val out = Bitmap.createBitmap(
            photo.width + mat * 2, photo.height + mat * 2 + strip, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        canvas.drawColor(PAPER)
        canvas.drawBitmap(photo, null, RectF(mat.toFloat(), mat.toFloat(), (mat + photo.width).toFloat(), (mat + photo.height).toFloat()), null)

        val cx = out.width / 2f
        var y = mat + photo.height + strip * 0.14f
        canvas.drawText(header.uppercase().take(24), cx, y, paint((strip * 0.07f).coerceAtLeast(20f), Color.rgb(0x66, 0x66, 0x66)))
        y += strip * 0.15f
        canvas.drawText(title.uppercase().take(24), cx, y, paint((strip * 0.13f).coerceAtLeast(28f), INK))
        if (details != null) {
            y += strip * 0.11f
            canvas.drawText(details.uppercase().take(40), cx, y, paint((strip * 0.075f).coerceAtLeast(20f), Color.rgb(0x66, 0x66, 0x66)))
        }
        return out
    }
}
