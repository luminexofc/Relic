package com.relic.catalog.lab

/**
 * Defringe: purple + green fringing removal, each an amount + hue range.
 *
 * 6 values packed: purpleAmt 0..1, purpleLo 0..1 (hue/360), purpleHi, greenAmt,
 * greenLo, greenHi. Or [NONE].
 *
 * The shader desaturates pixels whose hue falls inside either range,
 * proportionally to the amount. Hue ranges wrap (e.g. purple 0.75..0.9).
 */
object Defringe {

    const val NONE = "-"
    const val VALUES = 6

    fun isActive(v: FloatArray?): Boolean {
        if (v == null || v.size != VALUES) return false
        return v[0] != 0f || v[3] != 0f
    }

    fun parse(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.trim().split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != VALUES) return null
        val out = FloatArray(VALUES)
        for (i in 0 until VALUES) {
            val f = parts[i].toFloatOrNull() ?: return null
            out[i] = when (i) {
                0, 3 -> f.coerceIn(0f, 1f)
                else -> f.coerceIn(0f, 1f)
            }
        }
        return out
    }

    fun encode(v: FloatArray?): String {
        if (!isActive(v)) return NONE
        val x = v ?: return NONE
        return (0 until VALUES).joinToString(" ") { i ->
            val lo = 0f; val hi = 1f
            val r = Math.round(x[i].coerceIn(lo, hi) * 100f) / 100f
            if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
    }

    /** Default purple range (~270-330deg) + green (~90-150deg), amounts off. */
    fun defaults(): FloatArray = floatArrayOf(0f, 0.75f, 0.92f, 0f, 0.25f, 0.42f)
}
