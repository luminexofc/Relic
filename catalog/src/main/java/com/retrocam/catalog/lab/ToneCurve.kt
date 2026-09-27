package com.retrocam.catalog.lab

/**
 * Adobe's 1D tone curve, the single largest thing an XMP preset carries.
 *
 * A curve is a list of control points in 0-255: `crs:ToneCurvePV2012="0, 0, 255,
 * 255, 128, 132, 64, 96, 192, 208"` means (0,0), (255,255), (128,132), (64,96),
 * (192,208). Adobe draws a smooth spline through them; this interpolates
 * linearly, which on the four-to-six points a real preset uses is within a
 * couple of levels of the spline everywhere except right at the control points.
 * That is the approximation to be aware of, and it is a good one.
 *
 * Four curves travel together: the composite and one per channel. They are
 * packed into the four channels of a 256x1 texture, with any channel the preset
 * does not set filled with the identity ramp. That means the shader applies all
 * four unconditionally and an absent curve costs nothing, instead of needing a
 * flag per channel.
 */
object ToneCurve {

    const val SIZE = 256

    /** Field separator between the four curves. Absent from any number. */
    const val GROUP_SEP = ';'

    /** Placeholder for a codec field that holds no curve. */
    const val NONE = "-"

    /**
     * One curve sampled to [SIZE] values in 0..1, or null if [raw] is absent or
     * unusable.
     *
     * Returns null rather than an identity curve for a malformed input: the
     * import report has to be able to say a curve was unreadable, which it
     * cannot do if the failure is a silent identity.
     */
    fun parse(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        val nums = raw.split(',', ' ', ';')
            .mapNotNull { it.trim().toFloatOrNull() }
        if (nums.size < 4 || nums.size % 2 != 0) return null

        val pts = Array(nums.size / 2) { floatArrayOf(nums[it * 2], nums[it * 2 + 1]) }
        // Adobe writes them in ascending x, but a hand-edited file might not, and
        // an unsorted list would make the search below walk backwards.
        pts.sortBy { it[0] }

        val out = FloatArray(SIZE)
        var seg = 0
        for (i in 0 until SIZE) {
            val x = i.toFloat()
            // Clamped search: the curve starts at pts[0] and ends at the last
            // point, so there is always a segment even outside the point range.
            while (seg < pts.size - 2 && x > pts[seg + 1][0]) seg++
            val a = pts[seg]
            val b = pts[seg + 1]
            val t = if (b[0] > a[0]) ((x - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f) else 0f
            out[i] = ((a[1] + (b[1] - a[1]) * t) / 255f).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * The four curves in fixed order: composite, red, green, blue.
     *
     * Each part is a run of [SIZE] sampled values, NOT control points. Reading
     * them as control points would halve them into SIZE/2 (x, y) pairs and then
     * re-interpolate, which bends a curve that was already exact. Control points
     * only ever come in from XMP, via [parse].
     */
    fun parseGroup(encoded: String?): List<FloatArray?> {
        if (encoded.isNullOrBlank() || encoded == NONE) return List(4) { null }
        // Always four, so a truncated field cannot shift the channels.
        return encoded.split(GROUP_SEP).map { part ->
            val v = part.trim()
            if (v.isEmpty()) {
                null
            } else {
                val nums = v.split(' ').mapNotNull { it.toFloatOrNull() }
                // A part that is not a full sample run is unusable rather than
                // silently wrong, which is the same rule [parse] follows.
                if (nums.size != SIZE) null else FloatArray(SIZE) {
                    (nums[it] / 255f).coerceIn(0f, 1f)
                }
            }
        }.let { if (it.size >= 4) it else it + List(4 - it.size) { null } }
    }

    /**
     * Pack the four sampled curves into a 256x1 RGBA buffer for upload.
     *
     * Absent curves are the identity ramp rather than black, so applying all
     * four unconditionally in the shader is safe.
     */
    fun toRgba(curves: List<FloatArray?>): ByteArray {
        val out = ByteArray(SIZE * 4)
        for (i in 0 until SIZE) {
            val v = i / 255f
            for (ch in 0 until 4) {
                out[i * 4 + ch] = (((curves.getOrNull(ch)?.get(i) ?: v) * 255f).toInt()
                    .coerceIn(0, 255)).toByte()
            }
        }
        return out
    }

    /**
     * Serialise the four curves into one codec field.
     *
     * Commas become spaces because the codec's field separator is a comma, and
     * the `;` group separator is what keeps four curves in one field instead of
     * four.
     *
     * This is the bulk of an XMP preset's payload in text terms, roughly a
     * kilobyte per curve, and no QR code will hold it. Persistence rides on
     * DataStore, which has no such limit; a recipe carrying a curve is not
     * shareable by QR and the share path says so rather than silently dropping
     * the curve.
     */
    fun encodeGroup(curves: List<FloatArray?>): String {
        if (curves.all { it == null }) return NONE
        return (0 until 4).joinToString(GROUP_SEP.toString()) { ch ->
            val c = curves.getOrNull(ch) ?: return@joinToString ""
            buildString {
                for (i in 0 until SIZE) {
                    if (i > 0) append(' ')
                    // Rounded, not truncated: the samples came from 0-255
                    // integers, so round-tripping through toInt() would shave a
                    // level off every one of them and bias the curve darker.
                    append((c[i] * 255f).let { (it + 0.5f).toInt() }.coerceIn(0, 255))
                }
            }
        }
    }
}
