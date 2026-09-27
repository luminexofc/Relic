package com.retrocam.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max

/**
 * Photo card: the shot baked onto a paper card, written into saved photos.
 *
 * Proportions come from the reference frame in `Media/frame.jpg`: a rounded
 * photo window sitting in the top of the card, a thick blank bar beneath it, and
 * nothing else. The reference also shows a small platform affordance badge
 * overhanging the bottom-right corner, which is deliberately not reproduced -
 * it is UI belonging to whatever is displaying the image, not to the card.
 *
 * The bar carries the caption, which is the part the reference leaves blank.
 * Everything is expressed as a fraction of the card width so a card looks the
 * same at any output resolution.
 */
object PhotoCards {

    private val PAPER = Color.rgb(0xFA, 0xF7, 0xF0)
    private const val INK = 0xFF1A1A1A.toInt()
    private val DIM = Color.rgb(0x8A, 0x8A, 0x8A)

    // ---- geometry, as fractions of the card width ----

    /** Margin around the photo window: sides and top. */
    private const val PAD = 0.0439f

    /** Photo window height. */
    private const val WIN_H = 0.6667f

    /** Caption bar height. */
    private const val BAR_H = 0.5263f

    /** Card corner radius. */
    private const val OUTER_R = 0.1864f

    /** Window corner radii, top then bottom. The reference's are not equal. */
    private const val WIN_R_TOP = 0.110f
    private const val WIN_R_BOTTOM = 0.150f

    /** Total height is exactly pad + window + bar: the bar runs to the bottom edge. */
    private val CARD_H: Float get() = PAD + WIN_H + BAR_H

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        textSize = size
    }

    /** The photo window's aspect ratio, for callers that want to match it. */
    const val WINDOW_ASPECT: Float = (1f - 2f * PAD) / WIN_H

    fun render(photo: Bitmap, header: String, title: String, details: String? = null): Bitmap {
        val w = photo.width.coerceAtLeast(1)
        val cardW = w
        val cardH = (cardW * CARD_H).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // Paper card. The area outside the rounded corners stays transparent so
        // the card can sit on any background.
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
        canvas.drawRoundRect(
            0f, 0f, cardW.toFloat(), cardH.toFloat(),
            cardW * OUTER_R, cardW * OUTER_R, cardPaint,
        )

        // Photo window, centre cropped and clipped to its rounded shape. A
        // BitmapShader keeps the crop and the rounded corners in one antialiased
        // draw; clipping a path and then drawing the bitmap separately gives
        // jaggy corners on a software canvas.
        val winL = cardW * PAD
        val winT = cardW * PAD
        val winR = cardW - winL
        val winB = winT + cardW * WIN_H
        val rTop = cardW * WIN_R_TOP
        val rBottom = cardW * WIN_R_BOTTOM
        val path = Path().apply {
            addRoundRect(
                RectF(winL, winT, winR, winB),
                floatArrayOf(rTop, rTop, rTop, rTop, rBottom, rBottom, rBottom, rBottom),
                Path.Direction.CW,
            )
        }
        val shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max((winR - winL) / photo.width, (winB - winT) / photo.height)
        val tx = winL + ((winR - winL) - photo.width * scale) / 2f
        val ty = winT + ((winB - winT) - photo.height * scale) / 2f
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })

        // Caption, in the bar.
        val barTop = winB
        val barH = cardH - barTop
        val cx = cardW / 2f
        if (header.isNotBlank()) {
            canvas.drawText(
                header.uppercase().take(28), cx, barTop + barH * 0.28f,
                paint(barH * 0.10f, DIM),
            )
        }
        if (title.isNotBlank()) {
            canvas.drawText(
                title.uppercase().take(24), cx, barTop + barH * 0.55f,
                paint(barH * 0.17f, INK, bold = true),
            )
        }
        if (!details.isNullOrBlank()) {
            canvas.drawText(
                details.uppercase().take(40), cx, barTop + barH * 0.78f,
                paint(barH * 0.105f, DIM),
            )
        }
        return out
    }
}
