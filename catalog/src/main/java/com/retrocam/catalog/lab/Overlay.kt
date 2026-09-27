package com.retrocam.catalog.lab

import kotlin.math.min

/**
 * Where an overlay (the 1990s date stamp, a watermark logo) sits on the frame,
 * and how big it is. Kept as pure maths so it is testable and so the shader
 * receives a finished rect instead of re-deriving the geometry per pixel.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan):
 * the five positions and the 4% / 3% padding come from
 * `FilterEngine.applyDateStamp` and `applyWatermark`.
 */
enum class StampPosition(val label: String) {
    TOP_LEFT("TOP LEFT"),
    TOP_RIGHT("TOP RIGHT"),
    BOTTOM_LEFT("BOTTOM LEFT"),
    BOTTOM_RIGHT("BOTTOM RIGHT"),
    CENTER("CENTRE"),
}

object OverlayPlacement {

    /** Upstream's date stamp padding, as a fraction of the frame. */
    const val STAMP_PAD = 0.04f

    /** Upstream's watermark padding, as a fraction of the frame. */
    const val MARK_PAD = 0.03f

    /**
     * The overlay's rect in frame UV, as `[x0, y0, x1, y1]`.
     *
     * **Frame y is the bottom edge.** In the renderer `quadPos` pairs clip y=-1
     * with texcoord y=0, and clip -1 is the bottom of the viewport, so uv.y grows
     * upward. An Android Bitmap has row 0 at the top, so the shader flips v when
     * it samples. That inversion is the single easiest thing to get backwards
     * here, which is why the rect itself is computed and tested independently of
     * the sampling.
     *
     * @param overlayAspect overlay width / height
     * @param padX horizontal padding as a fraction of the frame
     * @param padY vertical padding as a fraction of the frame
     *
     * The overlay is fitted inside the padded frame: it shrinks if it would
     * overhang, rather than being clipped.
     */
    fun rect(
        position: StampPosition,
        overlayAspect: Float,
        padX: Float = STAMP_PAD,
        padY: Float = STAMP_PAD,
    ): FloatArray {
        val a = if (overlayAspect.isFinite() && overlayAspect > 0f) overlayAspect else 1f
        val availW = (1f - 2f * padX).coerceAtLeast(0.01f)
        val availH = (1f - 2f * padY).coerceAtLeast(0.01f)
        // Height fraction that keeps the overlay inside both margins.
        val h = min(availH, availW / a)
        val w = h * a
        val x0: Float
        val y0: Float
        when (position) {
            StampPosition.TOP_LEFT -> { x0 = padX; y0 = 1f - padY - h }
            StampPosition.TOP_RIGHT -> { x0 = 1f - padX - w; y0 = 1f - padY - h }
            StampPosition.BOTTOM_LEFT -> { x0 = padX; y0 = padY }
            StampPosition.BOTTOM_RIGHT -> { x0 = 1f - padX - w; y0 = padY }
            StampPosition.CENTER -> { x0 = (1f - w) / 2f; y0 = (1f - h) / 2f }
        }
        return floatArrayOf(x0, y0, x0 + w, y0 + h)
    }
}

/**
 * The 1990s LED date stamp, minus the Canvas drawing.
 *
 * Upstream rasterises the text itself with `Paint` + `setShadowLayer` and
 * composites into the photo. Text layout has no GPU equivalent, so the CPU does
 * what only it can do - measure and draw the glyphs - and hands the renderer a
 * small bitmap which the shader then composites live at any resolution. That is
 * the whole reason this phase is a hybrid rather than a full port.
 */
object DateStamp {

    /** Upstream's default: retro LED amber orange. */
    val DEFAULT_COLOR: Int = 0xFFFF8C14.toInt()

    /** The glow colour upstream uses in `setShadowLayer`. */
    val GLOW_COLOR: Int = (180 shl 24) or (255 shl 16) or (100 shl 8) or 0

    /**
     * Upstream sizes the text at `max(24px, height * 0.038)`. The GPU path
     * rasterises once at a fixed height instead, because the shader scales the
     * result: the rendered size is then proportional to the frame, which is the
     * same visual outcome at any resolution.
     */
    const val TEXT_HEIGHT_FRACTION = 0.038f

    /** Rasterise this tall, so it stays crisp when the shader scales it up. */
    const val RASTER_HEIGHT = 192

    /**
     * Aspect (width / height) the rasterised stamp will have for [text] in a
     * monospace face, including the glow padding.
     *
     * Monospace means every glyph is the same advance, so the width is
     * predictable without measuring: this keeps the Kotlin-side rect
     * computation in step with whatever the rasteriser produces.
     */
    fun aspectFor(text: String): Float {
        val chars = text.length.coerceAtLeast(1)
        // 0.6 em advance is the usual monospace figure; the glow adds a little.
        return (chars * 0.6f * TEXT_HEIGHT_FRACTION) + 0.02f
    }

    /**
     * Stable key for a rasterised (text, colour) pair, so the app and the
     * renderer agree on which texture belongs to a recipe without either of them
     * owning the raster.
     */
    fun hashStampText(text: String, color: Int): String {
        var h = 14695981039346656037uL
        for (c in text) {
            h = h xor c.code.toULong()
            h *= 1099511628211uL
        }
        h = h xor color.toULong()
        h *= 1099511628211uL
        return h.toString(16).padStart(16, '0')
    }

    /** A sensible default stamp for today, in the format those cameras used. */
    fun defaultText(calendar: java.util.Calendar): String {
        val y = calendar.get(java.util.Calendar.YEAR) % 100
        val m = calendar.get(java.util.Calendar.MONTH) + 1
        val d = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("'%02d %02d %02d", y, m, d)
    }
}
