package com.retrocam.catalog.lab

import kotlin.math.abs
import kotlin.math.cos

/**
 * Adobe's Color Mixer: eight hue bands, each with its own hue rotation,
 * saturation scale and luminance scale.
 *
 * Twenty-four XMP keys, and the thing most film-emulation presets actually
 * carry - a preset that shifts the greens without touching the reds is doing
 * this and nothing else. It was the largest group of keys the import reported
 * as having no equivalent.
 *
 * ## How a pixel picks its band
 *
 * Each band is centred on a hue (red at 0 degrees through magenta at 315) and
 * reaches 45 degrees either side with a raised-cosine falloff. Because the
 * centres are exactly 45 degrees apart and the window is exactly half that, the
 * eight weights sum to 1 for every hue: a pixel on a band boundary is half in
 * each neighbour, and there is no edge anywhere on the colour wheel.
 *
 * That is a deliberate choice over Adobe's, which uses hard band ranges with a
 * narrow transition. A hard boundary shows as a visible seam on a smooth sky
 * gradient, and a partition of unity cannot produce one by construction.
 *
 * The band is chosen from the pixel's ORIGINAL hue, so a rotation does not walk
 * a pixel into the next band's adjustment as it moves.
 *
 * ## Why this is Kotlin and not just GLSL
 *
 * Same reason as [RangeTone]: the arithmetic is easy to get subtly wrong and a
 * shader is a bad place to find out. This is the testable copy, and
 * `HslShaderTest` reads the expressions back out of `Shaders.HEADER` to prove
 * the two have not drifted.
 */
object Hsl {

    /** Adobe's eight bands, in the order the XMP keys use. */
    const val BANDS = 8

    /** Three values per band: hue rotation, saturation scale, luminance scale. */
    const val VALUES = BANDS * 3

    /**
     * The field separator inside the packed value.
     *
     * NOT a comma, and that is the whole reason this constant exists.
     * `RecipeCodec` splits a payload on commas, so a comma here would split 24
     * values into 24 codec fields: the payload would gain 23 phantom fields,
     * every decode would return null, and a recipe with a colour mixer in it
     * would simply be unloadable. `ToneCurve` already learned this and uses
     * spaces; so does this.
     *
     * A comma is not used anywhere else in a codec field, so this cannot
     * collide either.
     */
    const val SEP = ' '
    const val NONE = "-"

    /** Band name as the Lab UI and the report show it. */
    val BAND_NAMES = listOf(
        "RED", "ORANGE", "YELLOW", "GREEN", "AQUA", "BLUE", "PURPLE", "MAGENTA",
    )

    /** The same bands as the XMP key suffix, e.g. `HueAdjustmentAqua`. */
    val XMP_SUFFIXES = listOf(
        "Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta",
    )

    /**
     * Where each band sits on the wheel, in degrees.
     *
     * These are Adobe's, read off its band boundaries, and they are NOT evenly
     * spaced: red 0, orange 30, yellow 60, then a 60-degree jump to green 120,
     * another to aqua 180, and 45-degree steps after that. An earlier version
     * spaced them 45 degrees apart for a tidier formula and put green at 135,
     * which is not where green is: a "pure green" pixel then sat a third of the
     * way into the wrong band and the mixer missed the colour it was named for.
     *
     * Matching Adobe's centres is worth more than the tidier spacing, because
     * the point of the panel is that its eight bands feel like Adobe's eight
     * bands.
     */
    val CENTRES = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 270f, 315f)

    /** Value index of one band's hue rotation / saturation / luminance. */
    fun hueAt(i: Int) = i * 3
    fun satAt(i: Int) = i * 3 + 1
    fun lumAt(i: Int) = i * 3 + 2

    /**
     * How far a band reaches either side of its centre, in degrees.
     *
     * Must be strictly MORE than half the largest gap between two centres, and
     * the largest gap is 60 (yellow to green, green to aqua). Half of it is
     * exactly 30, which looks sufficient and is not: at hue 90, exactly between
     * yellow and green, both weights are 0 and the pixel receives NO adjustment
     * at all. A dead band on the colour wheel, silently, at the one hue where
     * the two neighbouring bands meet.
     *
     * The reach trades two things against each other, and 35 is the balance:
     *  - any value at or below 30 leaves a dead hue, because at exactly half a
     *    gap both neighbouring weights are 0
     *  - any value well above it makes a band-centre pixel pick up too much of
     *    its neighbours, so the band's own setting stops being its own
     *
     * 35 leaves a 5-degree overlap on each side of a gap, which is enough to
     * cover the dead hue and small enough that a band centre keeps 95% of its
     * own setting. Green is the only fully isolated band, which is why the
     * rotation tests use it.
     *
     * The residual 5% bleed is not a bug to be removed. Adobe's bands overlap
     * too, and a preset that pushes red towards orange genuinely is partly
     * asking for orange's behaviour.
     */
    const val REACH = 35f

    /** A sanity floor on the reach, so a future change to CENTRES cannot make it
     *  too small and reintroduce dead hues. Checked by a test. */
    fun requiredReach(): Float {
        var maxGap = 0f
        for (i in CENTRES.indices) {
            val a = CENTRES[i]
            val b = CENTRES[(i + 1) % BANDS]
            var d = b - a
            if (d <= 0f) d += 360f
            if (d > maxGap) maxGap = d
        }
        return maxGap / 2f
    }

    /**
     * Band weight for `hueDeg` (0..360) at [band], before normalisation.
     *
     * A raised cosine over [-[REACH], +[REACH]]: 1 at the centre, exactly 0 at
     * the edge. [weights] normalises by the total, which is what turns uneven
     * spacing into a smooth blend rather than a gap.
     */
    fun weight(hueDeg: Float, band: Int): Float {
        // Shortest way round the circle, so 350 and 10 are 20 apart, not 340.
        var d = abs(hueDeg - CENTRES[band]) % 360f
        if (d > 180f) d = 360f - d
        if (d >= REACH) return 0f
        return 0.5f * (1f + cos(Math.PI.toFloat() * d / REACH))
    }

    /**
     * The eight weights for one hue, normalised to sum to 1.
     *
     * The normalisation is the whole trick. Adobe uses hard band ranges with a
     * narrow transition; a hard boundary shows as a visible seam across a
     * smooth sky gradient, and a partition of unity cannot produce one at all.
     */
    fun weights(hueDeg: Float): FloatArray {
        val out = FloatArray(BANDS)
        var sum = 0f
        for (b in 0 until BANDS) {
            out[b] = weight(hueDeg, b)
            sum += out[b]
        }
        // sum is never 0 at REACH 30: the centres are at most 60 apart, so every
        // hue is within reach of at least one band. Guarded anyway, because a
        // division by zero here would put NaN into the frame and NaN does not
        // survive the clamp at the end of the shader.
        if (sum > 0f) for (b in 0 until BANDS) out[b] /= sum
        return out
    }

    /**
     * The adjustment that applies to a pixel of hue [hueDeg]: each band's
     * setting weighted by how much of that hue the band owns.
     *
     * Reads the ORIGINAL hue, before any rotation, so the weight does not move
     * under the transform.
     */
    fun weighted(values: FloatArray, hueDeg: Float): FloatArray {
        val w = weights(hueDeg)
        val out = FloatArray(3)
        for (b in 0 until BANDS) {
            out[0] += w[b] * values[hueAt(b)]
            out[1] += w[b] * values[satAt(b)]
            out[2] += w[b] * values[lumAt(b)]
        }
        return out
    }

    /** True when [values] would change nothing, so the shader can skip the work. */
    fun isActive(values: FloatArray?): Boolean {
        if (values == null || values.size != VALUES) return false
        for (v in values) if (v != 0f) return true
        return false
    }

    // ---- the colour space round trip ----

    /**
     * RGB to HSL, hue in degrees 0..360.
     *
     * Hue is meaningless at zero saturation, so it is reported as 0 rather than
     * as a division by zero. That costs nothing: a grey pixel has every band at
     * zero weight difference, because the saturation and luminance adjustments
     * are uniform across the wheel at s = 0 anyway.
     */
    fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) * 0.5f
        val d = max - min
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) * 60f
            g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
        return floatArrayOf(h, s, l)
    }

    /** HSL back to RGB. Hue in degrees, any value: it wraps. */
    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val s2 = s.coerceIn(0f, 1f)
        val l2 = l.coerceIn(0f, 1f)
        if (s2 == 0f) return floatArrayOf(l2, l2, l2)
        val q = if (l2 < 0.5f) l2 * (1f + s2) else l2 + s2 - l2 * s2
        val p = 2f * l2 - q
        val hk = (h % 360f) / 360f
        fun channel(t: Float): Float {
            var x = t
            if (x < 0f) x += 1f
            if (x > 1f) x -= 1f
            return when {
                x < 1f / 6f -> p + (q - p) * 6f * x
                x < 0.5f -> q
                x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
                else -> p
            }
        }
        return floatArrayOf(
            channel(hk + 1f / 3f),
            channel(hk),
            channel(hk - 1f / 3f),
        )
    }

    /**
     * What a full-scale hue rotation moves the hue by, in degrees.
     *
     * Adobe's slider runs -100..100, so 100 units is a hundred degrees. The
     * recipe stores -1..1, which is where this went wrong first: a factor left
     * at the XMP scale turned a full-strength rotation into 3.6 degrees, a
     * nudge rather than a rotation, and it looked like a colour mixer that
     * quietly did almost nothing.
     */
    const val HUE_DEGREES = 100f

    /**
     * The whole operator on one pixel: RGB in, RGB out.
     *
     * Saturation and luminance are scaled rather than offset. Adobe's sliders
     * are -100..100 and a band's saturation at -100 does go to nothing, which
     * a multiply reaches and an offset does not. The cost is that a negative
     * luminance pulls a band towards black faster than a person might expect;
     * that is the approximation to be aware of.
     */
    fun apply(r: Float, g: Float, b: Float, values: FloatArray): FloatArray {
        val hsl = rgbToHsl(r, g, b)
        val adj = weighted(values, hsl[0])
        val out = hslToRgb(
            hsl[0] + adj[0] * HUE_DEGREES,
            hsl[1] * (1f + adj[1]),
            hsl[2] * (1f + adj[2]),
        )
        return floatArrayOf(out[0].coerceIn(0f, 1f), out[1].coerceIn(0f, 1f), out[2].coerceIn(0f, 1f))
    }

    // ---- the codec field ----

    /**
     * Read the recipe field, or null when it holds no adjustment.
     *
     * Always [VALUES] long when it returns, so a caller can index it blind.
     * A field that is not exactly that many numbers is unusable rather than
     * partially applied, which is the same rule [ToneCurve] follows: a
     * half-applied colour mixer is a wrong picture, not a slightly wrong one.
     */
    fun parse(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank() || encoded == NONE) return null
        val parts = encoded.split(' ', ',').filter { it.isNotBlank() }
        if (parts.size != VALUES) return null
        val out = FloatArray(VALUES)
        for (i in 0 until VALUES) {
            out[i] = parts[i].trim().toFloatOrNull() ?: return null
        }
        return out
    }

    /**
     * Serialise, or [NONE] when nothing is set.
     *
     * Quantised to 2dp: the XMP source is -100..100 in whole numbers, so more
     * precision than that is noise being carried in a shareable field.
     */
    fun encode(values: FloatArray?): String {
        if (!isActive(values)) return NONE
        val v = values ?: return NONE
        return (0 until VALUES).joinToString(" ") { i ->
            val r = Math.round(v[i].coerceIn(-1f, 1f) * 100f) / 100f
            if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
    }
}
