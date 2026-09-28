package com.retrocam.catalog.lab

import kotlin.math.cos
import kotlin.math.sin

/**
 * Adobe's Calibration panel: a hue rotation and a saturation scale for each of
 * the three primaries.
 *
 * Six XMP keys, and the one place in the Lab where a 3x3 really is the right
 * shape rather than a loss. A calibration exists to move one primary without
 * disturbing the others - to pull a green out of a fluorescent cast without
 * touching the red in a face - and a diagonal scale, which is all `warmth` and
 * `tint` are, cannot express that. A rotation needs off-diagonal terms.
 *
 * ## The rotation
 *
 * Rotating the red primary in the hue plane is a rotation of its 2D chroma
 * vector, so the matrix is `I + sin(t)*K + (1 - cos(t))*K²` where `K` is the
 * generator of the rotation in that plane. For a two-channel plane that is
 * three lines, which is why this is a small object rather than a general
 * matrix library: the only degree of freedom is one angle per primary.
 *
 * ## Why the saturation half is separate
 *
 * A saturation change is a pure diagonal scale, and diagonal is exactly what
 * [LabGrading] uploads as `rScale`/`gScale`/`bScale`. So the saturation
 * trims are folded into the recipe's existing warmth scale and cost nothing,
 * and only the rotations need to be carried. Splitting them this way is what
 * lets the exact half stay exact.
 *
 * ## Units
 *
 * Adobe's sliders are -100..100, and full scale is 60 degrees of rotation. That
 * is chosen so the visible range of a camera calibration is reachable without
 * the extremes, which is where Adobe's own panel is deliberately weak too: a
 * stronger rotation than that is not a white balance correction, it is a
 * different colour space.
 */
object Calibration {

    const val NONE = "-"

    /** Degrees of hue rotation at full scale, for one primary. */
    const val DEGREES = 60f

    /**
     * Builds the 3x3 from six -1..1 adjustments.
     *
     * Rows uploaded as three vec3s, like the template matrix, so there is no
     * column-major transpose to get wrong. [LabUniforms] is where it meets the
     * shader.
     */
    fun build(hue: FloatArray, sat: FloatArray): FloatArray {
        val m = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
        )
        // Each primary's plane is itself and one other channel: rotating red
        // moves red against green, rotating green against blue, and blue
        // against red. The pairs are (self, other) per primary, and `self` is
        // the primary's own channel index - which is the easy thing to get
        // wrong, and getting it wrong rotates the wrong primary while the
        // matrix still looks perfectly valid.
        val planes = intArrayOf(0, 1, 1, 2, 2, 0)
        for (p in 0 until 3) {
            val t = (hue.getOrElse(p) { 0f }).coerceIn(-1f, 1f) * DEGREES * DEG
            if (t != 0f) rotate(m, planes[p * 2], planes[p * 2 + 1], t)
            val s = 1f + (sat.getOrElse(p) { 0f }).coerceIn(-1f, 1f) * SAT_RANGE
            val self = p
            // Scaling the primary's own channel only: this is the part that is
            // exact and it never touches the other two.
            m[self * 5 + self] *= s
        }
        return m
    }

    /**
     * How much a full-scale saturation trim moves a primary.
     *
     * Small, because a calibration is a correction. Matching `warmth`'s +-0.2
     * keeps the two controls' strengths comparable when a user has both.
     */
    const val SAT_RANGE = 0.2f

    private const val DEG = (Math.PI / 180.0).toFloat()

    /**
     * Rotates the plane spanned by channels [a] and [b] by [theta] radians.
     *
     * Composed onto the right of the existing matrix, so the three primaries
     * compose in a defined order rather than depending on map iteration order.
     */
    private fun rotate(m: FloatArray, a: Int, b: Int, theta: Float) {
        val c = cos(theta)
        val s = sin(theta)
        // 2x2 rotation, applied as m[a][a] etc. in 4x5 row-major terms.
        val aa = m[a * 5 + a]
        val ab = m[a * 5 + b]
        val ba = m[b * 5 + a]
        val bb = m[b * 5 + b]
        m[a * 5 + a] = aa * c - ba * s
        m[a * 5 + b] = ab * c - bb * s
        m[b * 5 + a] = aa * s + ba * c
        m[b * 5 + b] = ab * s + bb * c
    }

    /** True when [m] is worth uploading at all. */
    fun isActive(m: FloatArray?): Boolean {
        if (m == null || m.size != 15) return false
        for (i in 0 until 3) for (j in 0 until 3) {
            if (m[i * 5 + j] != if (i == j) 1f else 0f) return true
        }
        return false
    }

    // ---- the codec field ----

    /**
     * Packs the six source adjustments, not the matrix.
     *
     * The adjustments are stored rather than the derived 3x3 for the same
     * reason `toneCurves` stores samples: the source values are what the user
     * set, they round-trip exactly, and a matrix serialised to 4dp would come
     * back visibly different from the one that went in.
     *
     * `hue,red sat,green,green sat,blue,blue sat` in that order.
     */
    fun encode(hue: FloatArray, sat: FloatArray): String {
        if (!isActiveFrom(hue, sat)) return NONE
        val out = StringBuilder()
        for (i in 0 until 3) {
            if (i > 0) out.append(' ')
            out.append(q(hue.getOrElse(i) { 0f }))
            out.append(' ')
            out.append(q(sat.getOrElse(i) { 0f }))
        }
        return out.toString()
    }

    /** Parses [encode] back, or null when the field holds no calibration. */
    fun parse(encoded: String?): Pair<FloatArray, FloatArray>? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.trim().split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != 6) return null
        val hue = FloatArray(3)
        val sat = FloatArray(3)
        for (i in 0 until 3) {
            hue[i] = parts[i * 2].toFloatOrNull()?.coerceIn(-1f, 1f) ?: return null
            sat[i] = parts[i * 2 + 1].toFloatOrNull()?.coerceIn(-1f, 1f) ?: return null
        }
        return hue to sat
    }

    private fun isActiveFrom(hue: FloatArray, sat: FloatArray): Boolean {
        for (i in 0 until 3) {
            if (hue.getOrElse(i) { 0f } != 0f) return true
            if (sat.getOrElse(i) { 0f } != 0f) return true
        }
        return false
    }

    private fun q(v: Float): String {
        val r = Math.round(v.coerceIn(-1f, 1f) * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}
