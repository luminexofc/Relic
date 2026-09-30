package com.relic.catalog.lab

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR sharing for recipes.
 *
 * The payload is exactly [RecipeCodec]'s string, so save, share, scan and restore
 * are one format rather than two that can drift. A recipe is a couple of hundred
 * characters, which fits a QR comfortably at a scannable module size.
 *
 * Lives in :catalog rather than :app because ZXing's core is pure Java, so the
 * whole encode-then-decode round trip is verifiable on a desktop JVM. The Android
 * side only has to turn a `Bitmap` into luminance bytes.
 */
object RecipeQr {

    /**
     * Error correction. A shared QR is usually photographed off a screen, and M
     * tolerates roughly 15% damage, which is the difference between "it scanned
     * from a chat screenshot" and "it did not".
     */
    private val ECC = ErrorCorrectionLevel.M

    private val ENCODE_HINTS = mapOf(
        EncodeHintType.ERROR_CORRECTION to ECC,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )

    private val DECODE_HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        // A recipe is a short, single-segment string, so there is no reason to
        // spend time on the other barcode formats.
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * Encodes [recipe] as a square boolean matrix, true meaning a dark module.
     *
     * @param size requested edge length in modules-worth of pixels; ZXing rounds
     *   up to a legal QR version, so the result is at least this big.
     */
    fun encodeMatrix(recipe: SavedRecipe, size: Int = 512): BooleanArray? =
        encodeText(RecipeCodec.encode(recipe), size)

    fun encodeText(text: String, size: Int = 512): BooleanArray? = try {
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, ENCODE_HINTS)
        val out = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                out[y * size + x] = bits.get(x, y)
            }
        }
        out
    } catch (t: Throwable) {
        null
    }

    /**
     * Decodes a greyscale luminance buffer back into a recipe.
     *
     * @param luminances row-major greyscale bytes, `width * height` long
     * @return null for anything that is not a recipe QR, including a perfectly
     *   good QR carrying some other text
     */
    fun decodeLuminances(luminances: ByteArray, width: Int, height: Int): SavedRecipe? {
        val text = decodeText(luminances, width, height) ?: return null
        return RecipeCodec.decode(text)
    }

    /** The raw string behind a QR, or null. Exposed so tests can check the format. */
    fun decodeText(luminances: ByteArray, width: Int, height: Int): String? = try {
        val source: LuminanceSource = PlanarYUVLuminanceSource(
            luminances, width, height, 0, 0, width, height, false,
        )
        MultiFormatReader()
            .apply { setHints(DECODE_HINTS) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    } catch (t: NotFoundException) {
        null
    } catch (t: Throwable) {
        // Checksum failures, array length mistakes from a caller: all "not a QR".
        null
    }

    /**
     * Luminance threshold for turning an ARGB pixel buffer into the greyscale
     * bytes ZXing wants. Uses the same 0.299/0.587/0.114 weighting the rest of
     * the app does, and integer arithmetic, so it is deterministic.
     */
    fun toLuminance(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (i in out.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
        }
        return out
    }
}
