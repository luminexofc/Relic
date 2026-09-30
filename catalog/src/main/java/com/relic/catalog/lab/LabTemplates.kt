package com.relic.catalog.lab

/**
 * The 35 FilterLibrary presets that are pure 4x5 colour matrices, offered in the
 * Filter Lab as starting points to build a recipe on top of.
 *
 * DERIVED FROM FilterLibrary (Apache-2.0, Copyright 2019-2026 Himshikhar Gayan).
 * https://github.com/hgayan7/FilterLibrary
 *
 * The numbers are transcribed mechanically from upstream
 * `FilterEngine.getColorMatrixForFilter`, not hand-typed. The remaining 13
 * FilterLibrary presets are pixel/convolution effects rather than matrices and
 * arrive with the effect stages; see LabTemplates.kt.
 *
 * Matrix convention is upstream's: a 4x5 row-major matrix in **0-255 units**,
 * applied as a row vector. [LabGrading] converts it to shader uniforms.
 */
data class LabTemplate(
    val id: String,
    val displayName: String,
    val category: String,
    val context: String,
    val matrix: FloatArray,
)

object LabTemplates {

    val all: List<LabTemplate> = listOf(
        LabTemplate(
            id = "GRAYSCALE",
            displayName = "GRAYSCALE",
            category = "CLASSIC",
            context = "grayscale preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "MONO_COOL",
            displayName = "MONO COOL",
            category = "CLASSIC",
            context = "mono cool preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.25f, 0.55f, 0.15f, 0f, -10f,
            0.28f, 0.58f, 0.16f, 0f, 0f,
            0.35f, 0.68f, 0.22f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "MONO_WARM",
            displayName = "MONO WARM",
            category = "CLASSIC",
            context = "mono warm preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.35f, 0.65f, 0.15f, 0f, 15f,
            0.33f, 0.62f, 0.14f, 0f, 10f,
            0.28f, 0.52f, 0.12f, 0f, -5f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "NOIR",
            displayName = "NOIR",
            category = "CLASSIC",
            context = "noir preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.45f, 0.88f, 0.17f, 0f, -40f,
            0.45f, 0.88f, 0.17f, 0f, -40f,
            0.45f, 0.88f, 0.17f, 0f, -40f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "SEPIA",
            displayName = "SEPIA",
            category = "CLASSIC",
            context = "sepia preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "SILVER_TONE",
            displayName = "SILVER TONE",
            category = "CLASSIC",
            context = "silver tone preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.33f, 0.60f, 0.20f, 0f, 25f,
            0.33f, 0.60f, 0.20f, 0f, 25f,
            0.35f, 0.62f, 0.22f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CROSS_PROCESS",
            displayName = "CROSS PROCESS",
            category = "VINTAGE",
            context = "cross process preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            -0.3597f, 0.3773f, 0.6638f, 0f, 0f,
            1.5668f, 0.4567f, 1.1261f, 0f, 0f,
            -0.1471f, 0.2261f, -0.7300f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "FUJI_VELVIA",
            displayName = "FUJI VELVIA",
            category = "VINTAGE",
            context = "fuji velvia preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.30f, -0.10f, -0.05f, 0f, 0f,
            -0.05f, 1.25f, -0.05f, 0f, 0f,
            -0.05f, -0.10f, 1.35f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "KODAK_CHROME",
            displayName = "KODAK CHROME",
            category = "VINTAGE",
            context = "kodak chrome preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.25f, -0.05f, -0.05f, 0f, 5f,
            -0.05f, 1.10f, -0.05f, 0f, 0f,
            -0.05f, -0.05f, 0.95f, 0f, -5f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "KODAK_PORTRA",
            displayName = "PORTRA 400",
            category = "VINTAGE",
            context = "portra 400 preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.15f, 0.05f, -0.05f, 0f, 12f,
            0.02f, 1.08f, 0.02f, 0f, 8f,
            -0.05f, 0.02f, 0.95f, 0f, 2f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "LOMO",
            displayName = "LOMO",
            category = "VINTAGE",
            context = "lomo preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.40f, -0.10f, -0.10f, 0f, -20f,
            -0.10f, 1.30f, -0.10f, 0f, -20f,
            -0.10f, -0.10f, 1.20f, 0f, -20f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "POLAROID_70S",
            displayName = "POLAROID '70S",
            category = "VINTAGE",
            context = "polaroid '70s preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.7281f, -0.4121f, 0.5411f, 0f, 0f,
            0.2894f, 1.1884f, -1.1764f, 0f, 0f,
            -1.0175f, 0.2237f, 1.6352f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "RETRO_WARM",
            displayName = "RETRO WARM",
            category = "VINTAGE",
            context = "retro warm preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.2f, 0.1f, 0.2f, 0f, 0f,
            0.7f, 1.0f, 0.0f, 0f, -12.75f,
            -0.7f, 0.2f, 0.5f, 0f, 0f,
            0f, -0.1f, 0f, 0.9f, 0f,
            ),
        ),
        LabTemplate(
            id = "VINTAGE",
            displayName = "VINTAGE",
            category = "VINTAGE",
            context = "vintage preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.90f, 0.05f, 0.05f, 0f, 15f,
            0.05f, 0.85f, 0.05f, 0f, 10f,
            0.02f, 0.05f, 0.70f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "BLEACH_BYPASS",
            displayName = "BLEACH BYPASS",
            category = "CINEMATIC",
            context = "bleach bypass preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.82f, 0.08f, 0.08f, 0f, 12f,
            0.08f, 0.82f, 0.08f, 0f, 12f,
            0.08f, 0.08f, 0.82f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CINEMATIC_POP",
            displayName = "CINEMATIC POP",
            category = "CINEMATIC",
            context = "cinematic pop preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            2.1028f, -0.2982f, 0.4213f, 0f, 0f,
            0.2229f, 1.6870f, -0.8834f, 0f, 0f,
            -0.7657f, 0.1712f, 2.0221f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "COOL_BLUE",
            displayName = "COOL BLUE",
            category = "CINEMATIC",
            context = "cool blue preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.0f, 0.0f, 0.1f, 0f, -25.5f,
            0.0f, 1.0f, 0.2f, 0f, 0f,
            0.0f, 0.0f, 1.3f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CYBERPUNK",
            displayName = "CYBERPUNK",
            category = "CINEMATIC",
            context = "cyberpunk preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.45f, -0.20f, 0.30f, 0f, 20f,
            -0.20f, 1.10f, -0.10f, 0f, -10f,
            -0.10f, 0.40f, 1.60f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "DRAMATIC",
            displayName = "DRAMATIC",
            category = "CINEMATIC",
            context = "dramatic preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            -2.0f, -1.0f, 1.0f, 0f, 255f,
            0.0f, -2.0f, 0.0f, 0f, 255f,
            0.0f, 0.0f, -1.0f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "HIGH_KEY",
            displayName = "HIGH KEY",
            category = "CINEMATIC",
            context = "high key preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.2299f, 0.0210f, 0.3832f, 0f, 11.91f,
            0.4501f, 1.1874f, -0.1069f, 0f, 11.91f,
            -0.3401f, 0.1317f, 1.0637f, 0f, 11.91f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "MOODY_DARK",
            displayName = "MOODY DARK",
            category = "CINEMATIC",
            context = "moody dark preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.30f, 0.0f, 0.0f, 0f, 0f,
            0.0f, 0.65f, 0.0f, 0f, 0f,
            0.0f, 0.0f, 0.49f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "ROSE_GOLD",
            displayName = "ROSE GOLD",
            category = "CINEMATIC",
            context = "rose gold preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.25f, 0.10f, 0.15f, 0f, 15f,
            0.05f, 0.95f, 0.05f, 0f, 5f,
            0.10f, 0.05f, 0.90f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "TEAL_AND_ORANGE",
            displayName = "TEAL & ORANGE",
            category = "CINEMATIC",
            context = "teal & orange preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.25f, -0.05f, -0.10f, 0f, 15f,
            -0.05f, 1.05f, -0.05f, 0f, 0f,
            -0.15f, 0.10f, 1.30f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "WARM_SUNSET",
            displayName = "WARM SUNSET",
            category = "CINEMATIC",
            context = "warm sunset preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.2749f, -0.2285f, 0.4411f, 0f, 0f,
            0.3237f, 0.9551f, -0.7059f, 0f, 0f,
            -0.6985f, 0.1734f, 1.1648f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "ANIME_VIBE",
            displayName = "ANIME VIBE",
            category = "SOCIAL",
            context = "anime vibe preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.10f, 0.05f, 0.05f, 0f, 15f,
            0.02f, 1.22f, 0.05f, 0f, 18f,
            0.02f, 0.05f, 1.35f, 0f, 22f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CLARENDON",
            displayName = "CLARENDON",
            category = "SOCIAL",
            context = "clarendon preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.20f, -0.05f, -0.05f, 0f, 5f,
            -0.05f, 1.15f, -0.05f, 0f, 5f,
            -0.05f, -0.05f, 1.25f, 0f, 15f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "JUNO",
            displayName = "JUNO",
            category = "SOCIAL",
            context = "juno preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.20f, 0.05f, -0.05f, 0f, 10f,
            0.02f, 1.10f, -0.02f, 0f, 5f,
            -0.05f, -0.05f, 0.90f, 0f, -5f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "LARK",
            displayName = "LARK",
            category = "SOCIAL",
            context = "lark preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.12f, 0.02f, 0.02f, 0f, 8f,
            0.02f, 1.18f, 0.02f, 0f, 12f,
            0.02f, 0.02f, 1.15f, 0f, 15f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "SLUMBER",
            displayName = "SLUMBER",
            category = "SOCIAL",
            context = "slumber preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.90f, 0.08f, 0.08f, 0f, 15f,
            0.05f, 0.90f, 0.05f, 0f, 12f,
            0.08f, 0.05f, 0.85f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "VALENCIA",
            displayName = "VALENCIA",
            category = "SOCIAL",
            context = "valencia preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.08f, 0.04f, 0.02f, 0f, 12f,
            0.02f, 1.05f, 0.02f, 0f, 10f,
            0.01f, 0.03f, 0.88f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "BRIGHTNESS_BOOST",
            displayName = "EXPOSURE BOOST",
            category = "ARTISTIC",
            context = "exposure boost preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.44f, 0f, 0f, 0f, 0f,
            0f, 1.44f, 0f, 0f, 0f,
            0f, 0f, 1.44f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CYAN_BOOST",
            displayName = "CYAN BOOST",
            category = "ARTISTIC",
            context = "cyan boost preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.0f, 0.0f, 0.0f, 0f, 0f,
            0.0f, 0.78f, 0.0f, 0f, 0f,
            0.0f, 0.0f, 1.0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "CYANOTYPE",
            displayName = "CYANOTYPE",
            category = "ARTISTIC",
            context = "cyanotype preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            0.05f, 0.15f, 0.45f, 0f, -20f,
            0.10f, 0.35f, 0.65f, 0f, 0f,
            0.20f, 0.55f, 0.95f, 0f, 35f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "INVERT",
            displayName = "INVERT",
            category = "ARTISTIC",
            context = "invert preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
        LabTemplate(
            id = "SOLARIZE",
            displayName = "SOLARIZE",
            category = "ARTISTIC",
            context = "solarize preset colour grade from FilterLibrary",
            matrix = floatArrayOf(
            1.5f, 0f, 0f, 0f, -128f,
            0f, 1.5f, 0f, 0f, -128f,
            0f, 0f, 1.5f, 0f, -128f,
            0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    val byId: Map<String, LabTemplate> = all.associateBy { it.id }
}
