package com.retrocam.catalog.lab

/**
 * 3D colour lookup tables for the Filter Lab.
 *
 * Two sources feed the same shader path:
 *  - **built-in** LUTs generated procedurally here, so the feature works with no
 *    assets in the APK and nothing to import;
 *  - **imported** HALD CLUTs (the 512x512 or 64x64 PNGs that Lightroom, Photoshop
 *    and DaVinci export), identified by a content hash.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan):
 * the tile/index layout and the 64-cube/16-cube size rule come from
 * `FilterEngine.applyHaldLut`. See THIRD_PARTY_NOTICES.md.
 *
 * Pure JVM so the index maths and the built-in curves are unit-testable without
 * a device.
 */
object LutCatalog {

    /**
     * Cube edge length for a Hald image, from upstream's rule: a 512x512 image is
     * a 64-cube (an 8x8 grid of 64px tiles), anything else is treated as a
     * 16-cube (a 4x4 grid of 16px tiles). 64 is what `grid * size` works out to
     * in both cases, and it is all the shader needs.
     */
    fun cubeFor(width: Int, height: Int): Int =
        if (width >= 512 && height >= 512) 64 else 16

    /** True for the image sizes the shader can actually index. */
    fun isSupportedSize(width: Int, height: Int): Boolean =
        (width >= 512 && height >= 512) || (width == 64 && height == 64)

    /** Human label for the picker. */
    fun describeSize(width: Int, height: Int): String = when {
        width >= 512 && height >= 512 -> "64^3 CUBE (512px)"
        width == 64 && height == 64 -> "16^3 CUBE (64px)"
        else -> "$width x $height (unsupported)"
    }

    /**
     * Tiles per row in a Hald image of this cube size: 4 for a 16-cube, 8 for a
     * 64-cube. Always `sqrt(cube)`, since there are `cube` tiles laid out square.
     */
    fun gridFor(cube: Int): Int = Math.round(Math.sqrt(cube.toDouble())).toInt()

    /**
     * Where a colour lives in a Hald image, as a texel index.
     *
     * Transcribed from `FilterEngine.applyHaldLut`:
     * ```
     * blueIndex = b * (cube-1) / 255
     * tileX     = blueIndex % grid
     * tileY     = blueIndex / grid
     * x         = tileX * cube + r * (cube-1) / 255
     * y         = tileY * cube + g * (cube-1) / 255
     * ```
     * The GLSL in Shaders.LAB_GRADE must stay in step with this, and
     * `HaldIndexTest` pins the layout so a shader change has to be deliberate.
     *
     * Colours are 0..255, matching upstream's pixel maths. The shader adds 0.5
     * for the texel centre and divides by the image side.
     */
    fun haldTexel(r: Int, g: Int, b: Int, cube: Int): Pair<Int, Int> {
        val grid = gridFor(cube)
        val maxColor = cube - 1
        val blueIndex = b * maxColor / 255
        val tileX = blueIndex % grid
        val tileY = blueIndex / grid
        val x = (tileX * cube + r * maxColor / 255).coerceIn(0, grid * cube - 1)
        val y = (tileY * cube + g * maxColor / 255).coerceIn(0, grid * cube - 1)
        return x to y
    }

    /**
     * The built-in LUTs. Each is a function from 0..1 rgb to 0..1 rgb, sampled
     * into a cube on first use.
     */
    val builtIn: List<BuiltinLut> = listOf(
        BuiltinLut(
            id = "builtin_faded",
            displayName = "FADED FILM",
            context = "lifted blacks faded film lut",
        ) { r, g, b ->
            // Lift the toe, roll the shoulder, warm slightly.
            Triple(lift(r), lift(g), lift(b) * 0.97f)
        },
        BuiltinLut(
            id = "builtin_cross",
            displayName = "CROSS PROCESS",
            context = "cyan shadows magenta highlights cross processed lut",
        ) { r, g, b ->
            // Cyan in the shadows, magenta in the highlights: the signature of
            // cross-processing, which no colour matrix can express.
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val cyan = 1f - smooth(l)
            val magenta = smooth(l)
            Triple(
                r * (1f - 0.35f * cyan) + 0.18f * magenta,
                g * (1f - 0.08f * cyan) + 0.02f * magenta,
                b * (1f + 0.30f * cyan) - 0.12f * magenta,
            )
        },
        BuiltinLut(
            id = "builtin_splittone",
            displayName = "SPLIT TONE",
            context = "cool shadows warm highlights split toned lut",
        ) { r, g, b ->
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val shadow = 1f - smooth(l)
            val high = smooth(l)
            Triple(
                r + 0.10f * high - 0.04f * shadow,
                g + 0.02f * high,
                b + 0.22f * shadow - 0.06f * high,
            )
        },
        BuiltinLut(
            id = "builtin_sepia3d",
            displayName = "SEPIA 3D",
            context = "true three dimensional sepia lut keeps hue variation",
        ) { r, g, b ->
            // A real 3D sepia: the matrix SEPIA flattens, this keeps some of the
            // original's colour in the shadows, which is why it is a LUT at all.
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            Triple(r * 0.10f, l * 0.9f + g * 0.04f, l * 0.68f + b * 0.02f)
        },
    )

    val builtInById: Map<String, BuiltinLut> = builtIn.associateBy { it.id }

    /**
     * Content hash for an imported LUT, so the same file always gets the same id
     * and a recipe can name it in a QR code. FNV-1a over the pixels and the
     * dimensions: not cryptographic, but this only has to distinguish the handful
     * of LUTs one person has imported.
     */
    fun hashPixels(pixels: IntArray, side: Int, cube: Int): String {
        var h = 14695981039346656037uL
        fun mix(v: Int) {
            h = h xor v.toULong()
            h *= 1099511628211uL
        }
        mix(side); mix(cube); mix(pixels.size)
        for (p in pixels) {
            mix((p shr 16) and 0xFF); mix((p shr 8) and 0xFF); mix(p and 0xFF)
        }
        return "lut_" + h.toString(16).padStart(16, '0')
    }

    private fun lift(v: Float): Float = v * 0.86f + 0.11f
    private fun smooth(v: Float): Float = v * v * (3f - 2f * v)
}

/** A procedurally generated LUT. [fn] maps 0..1 rgb to 0..1 rgb. */
class BuiltinLut(
    val id: String,
    val displayName: String,
    val context: String,
    val fn: (Float, Float, Float) -> Triple<Float, Float, Float>,
) {
    /**
     * Rasterise into a Hald image of [cube]^3. Returns packed 0xAARRGGBB pixels
     * in row-major order, ready for a Bitmap or a GL upload.
     */
    fun rasterize(cube: Int = 64): IntArray {
        val grid = if (cube == 64) 8 else 4
        val side = grid * cube
        val out = IntArray(side * side)
        val maxC = cube - 1
        for (y in 0 until side) {
            for (x in 0 until side) {
                val tileX = x / cube
                val tileY = y / cube
                val blueIndex = tileY * grid + tileX
                val r = ((x % cube) * 255) / maxC
                val g = ((y % cube) * 255) / maxC
                val b = (blueIndex * 255) / maxC
                val (nr, ng, nb) = fn(r / 255f, g / 255f, b / 255f)
                out[y * side + x] = packRgb(nr, ng, nb)
            }
        }
        return out
    }
}
