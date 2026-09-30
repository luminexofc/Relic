package com.relic.catalog.lab

/**
 * Geometry (Upright + manual perspective).
 *
 * Packed as 8 space-separated numbers: `mode vertical horizontal rotate aspect
 * scale x y`, or [NONE] when mode is Off and all sliders neutral.
 *
 * - mode: 0 Off, 1 Auto, 2 Guided, 3 Level, 4 Vertical, 5 Full. Auto/Guided/
 *   Level are presets that set the sliders (no scene analysis on device);
 *   Vertical corrects vertical keystone only, Full corrects both axes + rotate.
 * - vertical/horizontal: -1..1 keystone.
 * - rotate: -1..1 maps to -30..+30 degrees.
 * - aspect: -1..1 maps to 0.5..2 stretch.
 * - scale: 0..1 maps to 0.5..1.5 zoom (to hide wedges after warp).
 * - x/y: -1..1 offset (in units of half-frame).
 *
 * Applied in the shader as a homography on frame coords before sampling, so it
 * composes with the existing `u_texTransform` cover-crop rather than replacing
 * it. Costs no texture fetches, only ALU.
 */
object Geometry {

    const val NONE = "-"
    const val VALUES = 8

    const val OFF = 0
    const val AUTO = 1
    const val GUIDED = 2
    const val LEVEL = 3
    const val VERTICAL = 4
    const val FULL = 5

    val MODE_NAMES = listOf("OFF", "AUTO", "GUIDED", "LEVEL", "VERTICAL", "FULL")

    fun isActive(v: FloatArray?): Boolean {
        if (v == null || v.size != VALUES) return false
        if (v[0].toInt() != OFF) return true
        for (i in 1 until VALUES) if (v[i] != neutralAt(i)) return true
        return false
    }

    fun neutralAt(i: Int): Float = when (i) {
        0 -> 0f
        1, 2, 3, 6, 7 -> 0f
        4, 5 -> 0f // aspect/scale neutral 0 maps to 1x
        else -> 0f
    }

    fun parse(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.trim().split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != VALUES) return null
        val out = FloatArray(VALUES)
        for (i in 0 until VALUES) {
            val f = parts[i].toFloatOrNull() ?: return null
            out[i] = when (i) {
                0 -> f.toInt().coerceIn(0, 5).toFloat()
                1, 2 -> f.coerceIn(-1f, 1f)
                3 -> f.coerceIn(-1f, 1f)
                4 -> f.coerceIn(-1f, 1f)
                5 -> f.coerceIn(0f, 1f)
                6, 7 -> f.coerceIn(-1f, 1f)
                else -> f
            }
        }
        return out
    }

    fun encode(v: FloatArray?): String {
        if (!isActive(v)) return NONE
        val x = v ?: return NONE
        fun q(f: Float): String {
            val r = Math.round(f * 100f) / 100f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
        return (0 until VALUES).joinToString(" ") { q(x[it]) }
    }

    fun defaults(): FloatArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
}
