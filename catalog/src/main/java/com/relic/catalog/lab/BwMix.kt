package com.relic.catalog.lab

/**
 * Adobe's B&W mixer: how much each of the 8 hue bands contributes to the grey.
 *
 * Only applies when `grayscale` is on. Each value -1..1, neutral 0. Positive
 * lightens that band's contribution, negative darkens it. Stored packed as 8
 * space-separated numbers, or [NONE].
 *
 * The grey itself is `luminance + sum(weights * mix * 0.25)`, where weights are
 * the same [Hsl] band weights. So a mixer does nothing to a grey pixel (all
 * weights equal, sum of mix may still shift it — which is intended: a global
 * lift is a valid B&W move).
 */
object BwMix {

    const val NONE = "-"
    const val VALUES = 8

    fun isActive(v: FloatArray?): Boolean {
        if (v == null || v.size != VALUES) return false
        for (x in v) if (x != 0f) return true
        return false
    }

    fun parse(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.trim().split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != VALUES) return null
        val out = FloatArray(VALUES)
        for (i in 0 until VALUES) {
            out[i] = parts[i].toFloatOrNull()?.coerceIn(-1f, 1f) ?: return null
        }
        return out
    }

    fun encode(v: FloatArray?): String {
        if (!isActive(v)) return NONE
        val x = v ?: return NONE
        return (0 until VALUES).joinToString(" ") { i ->
            val r = Math.round(x[i].coerceIn(-1f, 1f) * 100f) / 100f
            if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
    }
}
