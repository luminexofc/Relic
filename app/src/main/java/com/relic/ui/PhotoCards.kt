package com.relic.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Photo card: the shot baked onto a white polaroid-style card, written into saved photos.
 *
 * Proportions measured off the reference frame in
 * `Media/Untitled16_20261002104904.png`: a square photo window with uniform
 * padding on the sides and top, sharp (unrounded) corners throughout, and a
 * thick blank bar beneath the photo. The bar carries an outlined square logo
 * box plus a bold "Relic" wordmark on the left, and the filter name over the
 * date, right-aligned in light grey, on the right.
 *
 * Everything is expressed as a fraction of the card width so a card looks the
 * same at any output resolution.
 */
object PhotoCards {

    private val PAPER = Color.WHITE
    private const val INK = 0xFF1A1A1A.toInt()
    private val DIM = Color.rgb(0x8A, 0x8A, 0x8A)

    // ---- geometry, as fractions of the card width ----

    /** Margin around the photo window: sides and top are equal. */
    private const val PAD = 0.0565f

    /** Photo window side: square, full width minus the side padding. */
    private const val WIN = 1f - 2f * PAD

    /** Caption bar height. */
    private const val BAR_H = 0.2565f

    /** Total height is exactly pad + window + bar: the bar runs to the bottom edge. */
    private val CARD_H: Float get() = PAD + WIN + BAR_H

    // ---- bar content, as fractions of the card width ----

    /** Logo box side and its left margin. */
    private const val LOGO_SIDE = 0.162f
    private const val LOGO_LEFT = 0.059f

    /** Gap between the logo box and the wordmark. */
    private const val WORD_GAP = 0.049f

    /** Right margin for the filter/date lines. */
    private const val RIGHT_PAD = 0.061f

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = align
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            textSize = size
        }

    /** Draws [text] with its vertical centre at [centerY]. */
    private fun Canvas.drawCentred(text: String, x: Float, centerY: Float, p: Paint) {
        val f = p.fontMetrics
        drawText(text, x, centerY - (f.ascent + f.descent) / 2f, p)
    }

    /** The photo window's aspect ratio, for callers that want to match it. */
    const val WINDOW_ASPECT: Float = 1f

    fun render(photo: Bitmap, header: String, title: String, details: String? = null): Bitmap {
        val w = photo.width.coerceAtLeast(1)
        val cardW = w
        val cardH = (cardW * CARD_H).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // White card, sharp corners like the reference.
        canvas.drawColor(PAPER)

        // Photo window, centre cropped. The window is square; crop the long
        // edge of the source and stretch nothing.
        val winL = cardW * PAD
        val winT = cardW * PAD
        val winSide = cardW * WIN
        val srcSide = minOf(photo.width, photo.height)
        val src = Rect(
            (photo.width - srcSide) / 2,
            (photo.height - srcSide) / 2,
            (photo.width + srcSide) / 2,
            (photo.height + srcSide) / 2,
        )
        canvas.drawBitmap(
            photo, src,
            Rect(winL.toInt(), winT.toInt(), (winL + winSide).toInt(), (winT + winSide).toInt()),
            null,
        )

        // Caption bar.
        val barTop = winT + winSide
        val barH = cardH - barTop
        val barMid = barTop + barH / 2f

        // Logo box: outlined square, vertically centred in the bar.
        val logoSide = cardW * LOGO_SIDE
        val logoLeft = cardW * LOGO_LEFT
        val logoTop = barMid - logoSide / 2f
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.STROKE
            strokeWidth = (cardW * 0.005f).coerceAtLeast(1f)
        }
        canvas.drawRect(logoLeft, logoTop, logoLeft + logoSide, logoTop + logoSide, boxPaint)

        // Wordmark, centred on the logo box.
        val wordX = logoLeft + logoSide + cardW * WORD_GAP
        canvas.drawCentred(
            "Relic", wordX, barMid,
            paint(logoSide * 0.42f, INK, bold = true),
        )

        // Filter name over date, right aligned, light grey like the reference.
        val rightX = cardW - cardW * RIGHT_PAD
        val small = paint(barH * 0.105f, DIM, align = Paint.Align.RIGHT)
        if (title.isNotBlank()) {
            canvas.drawCentred(title.take(24), rightX, barTop + barH * 0.40f, small)
        }
        if (!details.isNullOrBlank()) {
            canvas.drawCentred(details.take(40), rightX, barTop + barH * 0.58f, small)
        }
        return out
    }
}
