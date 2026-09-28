package com.retrocam.catalog.lab

import kotlin.math.roundToInt

/**
 * Iridas/Resolve `.cube` 3D LUTs, the format almost every LUT is sold in.
 *
 * The file is a header of directives and then `size^3` lines of `r g b` in
 * 0..1, with **red varying fastest**:
 *
 *     LUT_3D_SIZE 33
 *     0.000000 0.000000 0.000000
 *     0.001465 0.000000 0.000000     <- r has moved, g and b have not
 *
 * `LUT_1D_SIZE` is a different format with no 3D data and is rejected rather
 * than half-read, because a 1D curve applied as if it were a cube gives a
 * plausible-looking image that is simply wrong.
 */
object CubeLut {

    /** A parsed cube: [size] is the edge, [data] is `size^3` rgb triples. */
    data class Cube(val title: String, val size: Int, val data: FloatArray) {
        val entryCount: Int get() = size * size * size
    }

    /** Why a file could not be used, for a message the user can act on. */
    sealed class Result {
        data class Ok(val cube: Cube) : Result()
        data class Bad(val why: String) : Result()
    }

    private val DIRECTIVE = Regex("""^\s*([A-Za-z_0-9]+)\s*(.*)$""")

    fun parse(text: String): Result {
        var size = 0
        var title = ""
        var is1d = false
        val data = ArrayList<FloatArray>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // Only a known key makes a directive. Anything else is data, because
            // a data line can start with a minus sign and a directive pattern
            // would skip the whole line and then report the file as truncated.
            val m = DIRECTIVE.matchEntire(line)
            val key = m?.groupValues?.get(1)?.uppercase().orEmpty()
            val rest = m?.groupValues?.get(2)?.trim().orEmpty()
            when (key) {
                "TITLE" -> title = rest.trim('"').ifEmpty { "" }
                "LUT_3D_SIZE" -> size = rest.toIntOrNull() ?: return Result.Bad("LUT_3D_SIZE is not a number")
                "LUT_1D_SIZE" -> is1d = true
                // DOMAIN_MIN/MAX are accepted and ignored: every LUT in the wild
                // is 0..1 and honouring a different domain would need the shader
                // to rescale on read, which is not a change worth making blind.
                "DOMAIN_MIN", "DOMAIN_MAX" -> Unit
                else -> {
                    val v = line.split(',', ' ', '\t')
                        .mapNotNull { it.trim().toFloatOrNull() }
                    if (v.size >= 3) data.add(floatArrayOf(v[0], v[1], v[2]))
                }
            }
        }

        if (is1d) return Result.Bad("1D .cube curves are not supported, only 3D LUTs")
        if (size <= 0) return Result.Bad("no LUT_3D_SIZE line")
        // A cube bigger than this is a mistake, not a choice: it would be
        // gigabytes and no phone GPU wants it.
        if (size > 129) return Result.Bad("cube size $size is too large, max 129")
        if (size < 2) return Result.Bad("cube size $size is too small")
        val need = size * size * size
        if (data.size < need) {
            return Result.Bad("expected $need entries for a ${size}^3 cube, found ${data.size}")
        }
        val flat = FloatArray(need * 3)
        for (i in 0 until need) {
            flat[i * 3] = data[i][0]
            flat[i * 3 + 1] = data[i][1]
            flat[i * 3 + 2] = data[i][2]
        }
        return Result.Ok(Cube(title, size, flat))
    }

    /** The texel index a `.cube` stores at, given the red-fastest order. */
    private fun cubeIndex(r: Int, g: Int, b: Int, size: Int): Int = b * size * size + g * size + r

    /**
     * The cube as a Hald-layout pixel array, which is what the shader already
     * indexes.
     *
     * This is the whole reason no shader change was needed: `haldTexel` was
     * already written in terms of `gridFor(cube)`, and `grid * cube` is the side
     * length for any cube where `grid^2 >= cube`, which holds for 16, 33 and 64.
     *
     * Texels no colour maps to are left black. They are never sampled, because
     * the shader and this function use the same arithmetic.
     */
    fun toHaldPixels(cube: Cube): IntArray {
        val side = LutCatalog.gridFor(cube.size) * cube.size
        val px = IntArray(side * side)
        val maxC = cube.size - 1
        for (b in 0 until cube.size) {
            for (g in 0 until cube.size) {
                for (r in 0 until cube.size) {
                    val i = cubeIndex(r, g, b, cube.size) * 3
                    val red = (cube.data[i] * 255f).roundToInt().coerceIn(0, 255)
                    val green = (cube.data[i + 1] * 255f).roundToInt().coerceIn(0, 255)
                    val blue = (cube.data[i + 2] * 255f).roundToInt().coerceIn(0, 255)
                    // Laid out by cube index, which is what the shader samples.
                    // Going via 8-bit instead would collide for any size that is
                    // not a power of two.
                    val (x, y) = LutCatalog.indexTexel(r, g, b, cube.size)
                    px[y * side + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return px
    }

    /**
     * The cube edge a Hald image of this side length holds, or null.
     *
     * Deriving it from the size rather than storing it keeps imported LUTs as
     * plain PNGs with no sidecar and no filename convention. It is a guess in
     * principle, but `grid * cube == side` is a narrow equation and the
     * candidates that satisfy it are unique for every size LUTs come in.
     */
    fun cubeFromSide(side: Int): Int? =
        (2..side).firstOrNull { LutCatalog.gridFor(it) * it == side }
}
