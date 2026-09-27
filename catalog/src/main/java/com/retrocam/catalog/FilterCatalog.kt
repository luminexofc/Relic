package com.retrocam.catalog

/** Phase-1 core seven, in PRD strip order. Default filter: dither. */
object FilterCatalog {

    /** Named 16-color palettes users can attach to custom presets. */
    val namedPalettes: Map<String, IntArray> by lazy {
        mapOf("Vapor" to VAPOR, "EGA" to EGA_PALETTE, "C64" to C64_PALETTE, "Jewel" to JEWEL_PALETTE)
    }

    private val VAPOR = intArrayOf(
        0xFF0F0F1A.toInt(), 0xFF1B1B2F.toInt(), 0xFF3B1F4E.toInt(), 0xFF7B2D5B.toInt(),
        0xFFC13B6B.toInt(), 0xFFF25C6A.toInt(), 0xFFFF8C5A.toInt(), 0xFFFFC45A.toInt(),
        0xFFF9F871.toInt(), 0xFF9BEB70.toInt(), 0xFF4AD395.toInt(), 0xFF2AA5A5.toInt(),
        0xFF2D6CDF.toInt(), 0xFF5B4DD3.toInt(), 0xFF9B5BD3.toInt(), 0xFFF2F2F7.toInt(),
    )

    // Standard 16-color EGA palette.
    private val EGA_PALETTE = intArrayOf(
        0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(),
        0xFFAA0000.toInt(), 0xFFAA00AA.toInt(), 0xFFAA5500.toInt(), 0xFFAAAAAA.toInt(),
        0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(), 0xFF55FFFF.toInt(),
        0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt(),
    )

    // Commodore 64 palette (pepto).
    private val C64_PALETTE = intArrayOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF813338.toInt(), 0xFF75CEC8.toInt(),
        0xFF8E3C97.toInt(), 0xFF56AC4D.toInt(), 0xFF2E2C9B.toInt(), 0xFFEDF171.toInt(),
        0xFF8E5029.toInt(), 0xFF553800.toInt(), 0xFFC46C71.toInt(), 0xFF4A4A4A.toInt(),
        0xFF7B7B7B.toInt(), 0xFFA9FF9F.toInt(), 0xFF706DEB.toInt(), 0xFFB2B2B2.toInt(),
    )

    // Jewel tones for stained glass.
    private val JEWEL_PALETTE = intArrayOf(
        0xFF0A2463.toInt(), 0xFF3E92CC.toInt(), 0xFF1B998B.toInt(), 0xFF2EC4B6.toInt(),
        0xFFC5282F.toInt(), 0xFFEE5622.toInt(), 0xFFFAC05E.toInt(), 0xFFFDE74C.toInt(),
        0xFF5C8001.toInt(), 0xFF7D26CD.toInt(), 0xFFC77DFF.toInt(), 0xFF10002B.toInt(),
        0xFFE0AAFF.toInt(), 0xFF9C6644.toInt(), 0xFFB08968.toInt(), 0xFFF0E6EF.toInt(),
    )

    val all: List<FilterSpec> = listOf(
        FilterSpec(
            id = "original", displayName = "Original", family = FilterFamily.DITHER,
            context = "original none unfiltered no filter true colour",
            fragmentBody = Shaders.PLAIN, param1 = 0f, param2 = 0f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "dither", displayName = "DITHER", family = FilterFamily.DITHER,
            context = "dither retro dots two tone black and white halftone",
            fragmentBody = Shaders.DITHER, param1 = 160f, param2 = 5f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "ink", displayName = "INK", family = FilterFamily.DITHER,
            context = "ink drawing pen pen and wash lines brush",
            fragmentBody = Shaders.INK, param1 = 160f, param2 = 0f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "gray", displayName = "GRAY", family = FilterFamily.DITHER,
            context = "greyscale black and white no colour monochrome",
            fragmentBody = Shaders.GRAY, param1 = 160f, param2 = 5f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "color", displayName = "COLOR", family = FilterFamily.STYLIZE,
            context = "colour boost vivid saturated punchy",
            fragmentBody = Shaders.COLOR, param1 = 160f, param2 = 5f, defaultIntensity = 1f,
        ),
        FilterSpec(
            // id kept as "retro8" so saved intensity + favourites survive the
            // rename to 8 BIT.
            id = "retro8", displayName = "8 BIT", family = FilterFamily.HARDWARE,
            context = "8 bit console nes handheld pixel dither old video game chunky",
            fragmentBody = Shaders.BIT8, param1 = 96f, param2 = 0.35f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "halftone", displayName = "HALFTONE", family = FilterFamily.PRINT,
            context = "newspaper comic print dots press",
            fragmentBody = Shaders.HALFTONE, param1 = 160f, param2 = 0f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "ascii", displayName = "ASCII", family = FilterFamily.DITHER,
            context = "ascii art text characters letters code terminal",
            fragmentBody = Shaders.ASCII, param1 = 170f, param2 = 0f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "pixelated", displayName = "PIXEL", family = FilterFamily.DITHER,
            context = "pixelated blocky low res chunky big pixels",
            fragmentBody = Shaders.PIXELATED, param1 = 14f, param2 = 0f,
        ),
        FilterSpec(
            id = "bayer", displayName = "BAYER", family = FilterFamily.DITHER,
            context = "bayer dither pattern crosshatch matrix",
            fragmentBody = Shaders.BAYER, param1 = 160f, param2 = 5f, defaultIntensity = 1f,
        ),
        FilterSpec(
            id = "pixelart", displayName = "PIXEL ART", family = FilterFamily.DITHER,
            context = "pixel art sprite rpg game tiles",
            fragmentBody = Shaders.PIXEL_ART, param1 = 8f, param2 = 0f, palette = VAPOR,
        ),
        // ---- Phase 2: quick wins ----
        FilterSpec(
            id = "posterize", displayName = "POSTER", family = FilterFamily.STYLIZE,
            context = "poster flat bands few colours cel",
            fragmentBody = Shaders.POSTERIZE, param1 = 5f, param2 = 0f,
        ),
        FilterSpec(
            id = "ega", displayName = "EGA", family = FilterFamily.DITHER,
            context = "ega 16 colour dos pc game",
            fragmentBody = Shaders.EGA, param1 = 0f, param2 = 0f, palette = EGA_PALETTE,
        ),
        FilterSpec(
            id = "cyanotype", displayName = "CYANO", family = FilterFamily.PRINT,
            context = "blueprint cyanotype blue print white photo",
            fragmentBody = Shaders.CYANOTYPE, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "gameboy", displayName = "GAMEBOY", family = FilterFamily.HARDWARE,
            context = "game boy green lcd handheld four shade game",
            fragmentBody = Shaders.GAMEBOY, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "edge", displayName = "EDGE", family = FilterFamily.STYLIZE,
            context = "edge outline line detect contour",
            fragmentBody = Shaders.EDGE_ONLY, param1 = 0.25f, param2 = 0f,
        ),
        FilterSpec(
            id = "emboss", displayName = "EMBOSS", family = FilterFamily.STYLIZE,
            context = "emboss relief metal carved raised 3d",
            fragmentBody = Shaders.EMBOSS, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "thermal", displayName = "THERMAL", family = FilterFamily.STYLIZE,
            context = "thermal heat infrared hot cold temperature",
            fragmentBody = Shaders.THERMAL, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "xray", displayName = "XRAY", family = FilterFamily.STYLIZE,
            context = "x ray xray radiograph bone scan medical see through",
            fragmentBody = Shaders.XRAY, param1 = 0.5f, param2 = 0f,
        ),
        FilterSpec(
            id = "heatmap", displayName = "HEATMAP", family = FilterFamily.STYLIZE,
            context = "heatmap heat map contour gradient data plot",
            fragmentBody = Shaders.HEATMAP, param1 = 0.5f, param2 = 0f,
        ),
        FilterSpec(
            id = "swirl", displayName = "SWIRL", family = FilterFamily.DISTORT,
            context = "swirl spiral twist twisty rotate",
            fragmentBody = Shaders.SWIRL, param1 = 3f, param2 = 0f,
        ),
        FilterSpec(
            id = "fisheye", displayName = "FISHEYE", family = FilterFamily.DISTORT,
            context = "fisheye fish eye wide angle lens curved barrel",
            fragmentBody = Shaders.FISHEYE, param1 = 0.6f, param2 = 0f,
        ),
        // ---- Phase 3: hardware & print ----
        FilterSpec(
            id = "crt", displayName = "CRT", family = FilterFamily.HARDWARE,
            context = "crt tv scanline monitor screen old television",
            fragmentBody = Shaders.CRT, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "vhs", displayName = "VHS", family = FilterFamily.HARDWARE,
            context = "vhs tape video 80s camcorder tracking retro home video",
            fragmentBody = Shaders.VHS, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "c64", displayName = "C64", family = FilterFamily.HARDWARE,
            context = "commodore 64 c64 colours retro computer",
            fragmentBody = Shaders.C64, param1 = 0f, param2 = 0f, palette = C64_PALETTE,
        ),
        FilterSpec(
            id = "nightvision", displayName = "NIGHT", family = FilterFamily.HARDWARE,
            context = "night vision military infrared green goggles",
            fragmentBody = Shaders.NIGHT_VISION, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "sketch", displayName = "SKETCH", family = FilterFamily.STYLIZE,
            context = "sketch pencil drawing graphite",
            fragmentBody = Shaders.PENCIL_SKETCH, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "anime", displayName = "ANIME", family = FilterFamily.STYLIZE,
            context = "anime cel cartoon manga flat",
            fragmentBody = Shaders.ANIME_CEL, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "kaleido", displayName = "KALEIDO", family = FilterFamily.DISTORT,
            context = "kaleidoscope mirror symmetry pattern",
            fragmentBody = Shaders.KALEIDOSCOPE, param1 = 8f, param2 = 0f,
        ),
        FilterSpec(
            id = "trails", displayName = "TRAILS", family = FilterFamily.STYLIZE,
            context = "trails motion blur ghost long exposure",
            fragmentBody = Shaders.MOTION_TRAILS, param1 = 0.25f, param2 = 0f, temporal = true,
        ),
        FilterSpec(
            id = "newsprint", displayName = "NEWS", family = FilterFamily.PRINT,
            context = "newspaper news print press halftone",
            fragmentBody = Shaders.NEWSPRINT, param1 = 7f, param2 = 0f,
        ),
        FilterSpec(
            id = "blueprint", displayName = "BLUEPRT", family = FilterFamily.PRINT,
            context = "blueprint technical drawing plan architect",
            fragmentBody = Shaders.BLUEPRINT, param1 = 0f, param2 = 0f,
        ),
        // ---- Phase 4: advanced & stretch ----
        FilterSpec(
            id = "errdiff", displayName = "ATKINSON", family = FilterFamily.DITHER,
            context = "atkinson error diffusion dither smooth",
            fragmentBody = Shaders.ERRDIFF, param1 = 1f, param2 = 0f,
        ),
        FilterSpec(
            id = "zx", displayName = "ZX", family = FilterFamily.HARDWARE,
            context = "zx spectrum sinclair retro computer",
            fragmentBody = Shaders.ZX_SPECTRUM, param1 = 8f, param2 = 0f,
        ),
        FilterSpec(
            id = "xhatch", displayName = "XHATCH", family = FilterFamily.STYLIZE,
            context = "crosshatch hatching shading lines pen",
            fragmentBody = Shaders.CROSSHATCH, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "stained", displayName = "STAINED", family = FilterFamily.STYLIZE,
            context = "stained glass jewel colourful church",
            fragmentBody = Shaders.STAINED_GLASS, param1 = 0f, param2 = 0f, palette = JEWEL_PALETTE,
        ),
        FilterSpec(
            id = "engrave", displayName = "ENGRAVE", family = FilterFamily.PRINT,
            context = "engraving etched banknote line art intaglio",
            fragmentBody = Shaders.ENGRAVING, param1 = 0f, param2 = 0f,
        ),
        FilterSpec(
            id = "ordered", displayName = "ORDERED", family = FilterFamily.DITHER,
            context = "ordered dither bayer pattern regular",
            fragmentBody = Shaders.ORDERED, param1 = 5f, param2 = 0f,
        ),
        // ---- Photo-look grades ----
        FilterSpec(
            id = "softlight", displayName = "SOFT", family = FilterFamily.STYLIZE,
            context = "soft light soft airy matte faded gentle hazy",
            fragmentBody = Shaders.SOFT_LIGHT, param1 = 0.7f, param2 = 0f,
        ),
        FilterSpec(
            id = "gloomy", displayName = "GLOOMY", family = FilterFamily.STYLIZE,
            context = "gloomy dark moody cold eerie bleak",
            fragmentBody = Shaders.GLOOMY, param1 = 0.8f, param2 = 0f,
        ),
        FilterSpec(
            id = "vintage", displayName = "VINTAGE", family = FilterFamily.STYLIZE,
            context = "vintage retro old photo film sepia warm faded nostalgic",
            fragmentBody = Shaders.VINTAGE, param1 = 0.75f, param2 = 0f,
        ),
    )

    val byId: Map<String, FilterSpec> = all.associateBy { it.id }

    /** App opens unfiltered; the camera shows what the lens sees. */
    val default: FilterSpec = byId.getValue("original")
}
