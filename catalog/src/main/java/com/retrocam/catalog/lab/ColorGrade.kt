package com.retrocam.catalog.lab

/**
 * Adobe's Color Grading: three wheels (Shadows / Midtones / Highlights), each
 * a hue + saturation, plus Blending and Balance.
 *
 * Supersedes the old Split Toning (which only had shadows + highlights). The
 * Lab keeps both: an old preset drives `splitAmount` + tints, a new one drives
 * this. If a file carries both, Color Grading wins, which is what Adobe means.
 *
 * ## Units
 *
 * Hue 0..1 maps to 0..360 degrees. Saturation 0..1. Blend 0..1 (how much the
 * three ranges overlap). Balance -1..1 (shifts weight toward shadows or
 * highlights). Stored packed as 8 space-separated numbers, or [NONE].
 */
object ColorGrade {

    const val NONE = "-"
    const val VALUES = 8

    fun shHueAt(v: FloatArray) = v.getOrElse(0) { 0f }
    fun shSatAt(v: FloatArray) = v.getOrElse(1) { 0f }
    fun midHueAt(v: FloatArray) = v.getOrElse(2) { 0f }
    fun midSatAt(v: FloatArray) = v.getOrElse(3) { 0f }
    fun hiHueAt(v: FloatArray) = v.getOrElse(4) { 0f }
    fun hiSatAt(v: FloatArray) = v.getOrElse(5) { 0f }
    fun blendAt(v: FloatArray) = v.getOrElse(6) { 0.5f }
    fun balanceAt(v: FloatArray) = v.getOrElse(7) { 0f }

    fun isActive(v: FloatArray?): Boolean {
        if (v == null || v.size != VALUES) return false
        // Any hue or saturation set means a grade (withGrade arms the matching
        // saturation, so a hue never travels alone from the UI, but a
        // hand-written field can still carry one and it must survive).
        for (i in 0..5) if (v[i] != 0f) return true
        // Blend neutral is 0.5, balance neutral is 0.
        if (v[6] != 0.5f || v[7] != 0f) return true
        return false
    }

    fun parse(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.trim().split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != VALUES) return null
        val out = FloatArray(VALUES)
        for (i in 0 until VALUES) {
            val f = parts[i].toFloatOrNull() ?: return null
            out[i] = when (i) {
                0, 2, 4 -> f.coerceIn(0f, 1f)
                1, 3, 5 -> f.coerceIn(0f, 1f)
                6 -> f.coerceIn(0f, 1f)
                else -> f.coerceIn(-1f, 1f)
            }
        }
        return out
    }

    fun encode(v: FloatArray?): String {
        if (!isActive(v)) return NONE
        val x = v ?: return NONE
        // Hue/sat to 3dp (360 degrees needs it), blend/balance to 2dp.
        fun q3(f: Float): String {
            val r = Math.round(f.coerceIn(0f, 1f) * 1000f) / 1000f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
        fun q2(f: Float, lo: Float, hi: Float): String {
            val r = Math.round(f.coerceIn(lo, hi) * 100f) / 100f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
        return listOf(
            q3(x[0]), q2(x[1], 0f, 1f), q3(x[2]), q2(x[3], 0f, 1f),
            q3(x[4]), q2(x[5], 0f, 1f), q2(x[6], 0f, 1f), q2(x[7], -1f, 1f),
        ).joinToString(" ")
    }

    fun defaults(): FloatArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0f)
}
