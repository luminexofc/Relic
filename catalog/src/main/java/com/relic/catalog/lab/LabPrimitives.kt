package com.relic.catalog.lab

/**
 * The 40 filters that can be used as chain stages, with their knobs named.
 *
 * Every entry is a thin description of a filter that already exists: the body,
 * the default parameters and the palette are all already in [FilterCatalog], so a
 * stage does not reimplement anything. What this table adds is the one thing the
 * catalog lacks — a human label and a range for each `u_param1..3`, so the Lab
 * can render real sliders without knowing what any given parameter does.
 *
 * Ranges are chosen so the catalog default sits comfortably inside rather than at
 * an edge, because a knob whose neutral is at 0 or 1 gives you one direction of
 * travel only.
 *
 * `original` is absent on purpose: it is the identity shader, so a stage using it
 * would burn a chain slot to change nothing. `trails` is present but flagged
 * unchainable — it accumulates from a feedback buffer, and mid-chain the sampler
 * it needs holds the previous stage's output rather than the real previous frame.
 */
object LabPrimitives {

    private fun p(label: String, param: Int, min: Float, max: Float, neutral: Float) =
        LabControl(label, param, min, max, neutral)

    /** Dither and halftone cell sizes are in device pixels. */
    private fun cell(label: String, neutral: Float) = p(label, 1, 24f, 400f, neutral)

    /** Palette depth for the quantising filters. */
    private fun levels(label: String, param: Int, neutral: Float) = p(label, param, 2f, 16f, neutral)

    /** A 0/1 style switch used by a couple of the print filters. */
    private fun toggle(label: String, param: Int) = p(label, param, 0f, 1f, 0f)

    val all: List<LabPrimitive> = listOf(
        // ---- ordered / error-diffusion dithering ----
        LabPrimitive("dither", "DITHER", "Ordered dithering", listOf(
            cell("DOT SIZE", 160f), levels("LEVELS", 2, 5f), p("SPREAD", 3, 0f, 4f, 0f),
        )),
        LabPrimitive("bayer", "BAYER", "Bayer 4x4 matrix dither", listOf(
            cell("DOT SIZE", 160f), levels("LEVELS", 2, 5f),
        )),
        LabPrimitive("ordered", "ORDERED", "Generic ordered threshold", listOf(
            levels("LEVELS", 1, 5f), toggle("INVERT", 2),
        )),
        LabPrimitive("errdiff", "ATKINSON", "Atkinson error diffusion", listOf(
            p("SPREAD", 1, 0f, 2f, 1f), p("BIAS", 2, -0.5f, 0.5f, 0f),
        )),
        LabPrimitive("ink", "INK", "Ink dots on paper", listOf(
            cell("DOT SIZE", 160f), p("BLEED", 2, 0f, 2f, 0f),
        )),
        LabPrimitive("halftone", "HALFTONE", "Rotated halftone screen", listOf(
            cell("LINE PITCH", 160f), p("ANGLE", 2, 0f, 90f, 0f),
        )),
        LabPrimitive("newsprint", "NEWS", "Newsprint dot screen", listOf(
            p("DOT SIZE", 1, 2f, 28f, 7f), p("INK SPREAD", 2, 0f, 2f, 0f),
        )),

        // ---- pixelation ----
        LabPrimitive("pixelated", "PIXEL", "Blocky pixels", listOf(
            p("BLOCK", 1, 2f, 64f, 14f), p("GRID", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("pixelart", "PIXEL ART", "Vapourwave pixel art", listOf(
            p("BLOCK", 1, 2f, 32f, 8f), p("SCANLINES", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("ascii", "ASCII", "Character ramp", listOf(
            p("CELL", 1, 8f, 400f, 170f), p("INVERT", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("posterize", "POSTER", "Posterise to N levels", listOf(
            levels("LEVELS", 1, 5f),
        )),
        LabPrimitive("zx", "ZX", "ZX Spectrum 8-colour", listOf(
            p("BLOCK", 1, 2f, 32f, 8f), p("DITHER", 2, 0f, 1f, 0f),
        )),

        // ---- monochrome and hardware ----
        LabPrimitive("gray", "GRAY", "Greyscale with dithered grain", listOf(
            cell("GRAIN", 160f), levels("LEVELS", 2, 5f),
        )),
        LabPrimitive("retro8", "8 BIT", "8-bit colour reduction", listOf(
            cell("PIXEL", 96f), p("SATURATION", 2, 0f, 1f, 0.35f),
        )),
        LabPrimitive("ega", "EGA", "EGA 16-colour palette", listOf(
            p("DITHER", 1, 0f, 1f, 0f),
        )),
        LabPrimitive("c64", "C64", "Commodore 64 palette", listOf(
            p("DITHER", 1, 0f, 1f, 0f),
        )),
        LabPrimitive("gameboy", "GAMEBOY", "Game Boy green screen", listOf(
            p("CONTRAST", 1, 0f, 1f, 0f), p("DITHER", 2, 0f, 4f, 0f),
        )),
        LabPrimitive("crt", "CRT", "CRT curvature and scanlines", listOf(
            p("CURVATURE", 1, 0f, 1f, 0f), p("SCANLINES", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("vhs", "VHS", "VHS tape artefacts", listOf(
            p("WOBBLE", 1, 0f, 1f, 0f), p("TRACKING", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("nightvision", "NIGHT", "Night vision green", listOf(
            p("GAIN", 1, 0f, 1f, 0f), p("GRAIN", 2, 0f, 1f, 0f),
        )),

        // ---- stylise ----
        LabPrimitive("edge", "EDGE", "Edge detect only", listOf(
            p("THICKNESS", 1, 0f, 2f, 0.25f), p("INVERT", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("emboss", "EMBOSS", "Emboss", listOf(
            p("STRENGTH", 1, 0f, 2f, 0f), p("DEPTH", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("xhatch", "XHATCH", "Crosshatch shading", listOf(
            p("DENSITY", 1, 0f, 1f, 0f), p("CROSSED", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("stained", "STAINED", "Stained glass", listOf(
            p("LEAD LINES", 1, 0f, 1f, 0f), p("SATURATION", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("sketch", "SKETCH", "Pencil sketch", listOf(
            p("STROKES", 1, 0f, 1f, 0f), p("GRAIN", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("anime", "ANIME", "Anime cel shading", listOf(
            p("POSTERISE", 1, 0f, 1f, 0f), p("EDGE", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("engrave", "ENGRAVE", "Engraving lines", listOf(
            p("FREQ", 1, 0f, 1f, 0f), p("THICKNESS", 2, 0f, 1f, 0f),
        )),
        LabPrimitive("blueprint", "BLUEPRT", "Blueprint grid", listOf(
            p("GRID", 1, 0f, 1f, 0f), p("CONTRAST", 2, 0f, 1f, 0f),
        )),

        // ---- tone ----
        LabPrimitive("thermal", "THERMAL", "Thermal palette", listOf(
            p("CONTRAST", 1, 0f, 1f, 0f),
        )),
        LabPrimitive("xray", "XRAY", "X-ray negative", listOf(
            p("STRENGTH", 1, 0f, 1f, 0.5f),
        )),
        LabPrimitive("heatmap", "HEATMAP", "Inferno heatmap", listOf(
            p("RANGE", 1, 0f, 1f, 0.5f),
        )),
        LabPrimitive("cyanotype", "CYANO", "Cyanotype blue", listOf(
            p("CONTRAST", 1, 0f, 1f, 0f),
        )),
        LabPrimitive("softlight", "SOFT", "Soft light blend", listOf(
            p("AMOUNT", 1, 0f, 1.5f, 0.7f),
        )),
        LabPrimitive("gloomy", "GLOOMY", "Gloomy desaturate", listOf(
            p("AMOUNT", 1, 0f, 1.5f, 0.8f),
        )),
        LabPrimitive("vintage", "VINTAGE", "Vintage wash", listOf(
            p("AMOUNT", 1, 0f, 1.5f, 0.75f),
        )),
        LabPrimitive("color", "COLOR", "False colour", listOf(
            cell("CELL", 160f), levels("LEVELS", 2, 5f),
        )),

        // ---- distortion ----
        LabPrimitive("swirl", "SWIRL", "Swirl", listOf(
            p("TWIST", 1, 0f, 8f, 3f),
        )),
        LabPrimitive("fisheye", "FISHEYE", "Fisheye barrel", listOf(
            p("WIDTH", 1, 0f, 1f, 0.6f),
        )),
        LabPrimitive("kaleido", "KALEIDO", "Kaleidoscope", listOf(
            p("SEGMENTS", 1, 2f, 24f, 8f),
        )),

        // ---- not chainable ----
        LabPrimitive("trails", "TRAILS", "Motion trails", listOf(
            p("DECAY", 1, 0f, 0.95f, 0.25f),
        ), chainable = false),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): LabPrimitive? = byId[id]

    /** The primitives offered in the Lab's stage picker, i.e. everything usable mid-chain. */
    val chainable: List<LabPrimitive> = all.filter { it.chainable }

    /**
     * Consistency check, run by the test suite rather than at runtime.
     *
     * A control pointing at `param` 0 or 4 would be silently dead — there is no
     * `u_param0` — and a neutral outside its own range would clamp the moment the
     * stage is added, so a fresh stage would not look like the plain filter.
     */
    fun validate(): List<String> = all.flatMap { prim ->
        prim.controls.mapNotNull { c ->
            when {
                c.param !in 1..3 -> "${prim.id}.${c.label} points at u_param${c.param}"
                c.min >= c.max -> "${prim.id}.${c.label} has an empty range"
                c.neutral < c.min || c.neutral > c.max ->
                    "${prim.id}.${c.label} neutral ${c.neutral} is outside ${c.min}..${c.max}"
                else -> null
            }
        }
    }
}
