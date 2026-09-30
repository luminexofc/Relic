package com.relic.catalog.lab

/**
 * Writes a [LabRecipe] back out as an Adobe Camera Raw `.xmp` preset.
 *
 * The import path ([XmpImport]) reads Lightroom's keys; this writes the same
 * keys back, so a curated preset built from scratch in the Lab can leave the
 * app as a file Lightroom understands. Round trip is approximate by nature —
 * the Lab's warmth is not Kelvin, its grain is not Adobe's distribution — but
 * every key written is one the importer reads back to the same value, which
 * `XmpExportTest` pins field by field.
 *
 * Only non-neutral values are written. A preset file full of explicit zeroes
 * imports fine either way, but a 40-attribute file for a two-slider look is
 * noise, and noise is what makes people stop trusting exports.
 *
 * Values are formatted with [q]/[qs] (signed/unsigned): Lightroom writes
 * `"+0.35"` with an explicit plus, and while its parser accepts both, a file
 * that looks like its files is a file its users trust.
 */
object XmpExport {

    private fun q(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        val s = if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        return if (r >= 0f) "+$s" else s
    }

    private fun qi(v: Float): String {
        val r = Math.round(v).toInt()
        return if (r >= 0) "+$r" else r.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Serialises [recipe] as an XMP document named [name].
     *
     * The attribute set is the primitive core every Camera Raw version since
     * Process 3 reads: exposure through grain. Array groups (mixer, grade,
     * calibration, tone curves) use their real key names; the aliases the
     * importer accepts (`SharpenRadius`, `GrainFrequency`, `Clarity`) are never
     * written, because a file should speak the current dialect, not ours.
     */
    fun export(recipe: LabRecipe, name: String): String {
        val r = LabRecipe.coerce(recipe)
        val a = StringBuilder()
        fun attr(key: String, value: String) {
            a.append("   crs:").append(key).append("=\"").append(esc(value)).append("\"\n")
        }

        attr("PresetType", "Normal")
        attr("ProcessVersion", "15.0")
        if (name.isNotBlank()) attr("Name", name.trim().uppercase().take(SavedRecipe.MAX_NAME))

        // ---- Light ----
        if (r.gamma != 1f) {
            val ev = gammaToExposure(r.gamma)
            attr("Exposure2012", q(ev))
        }
        if (r.adjustments.contrast != 1f) attr("Contrast2012", qi((r.adjustments.contrast - 1f) * 100f))
        if (r.highlights != 0f) attr("Highlights2012", qi(r.highlights * 100f))
        if (r.shadows != 0f) attr("Shadows2012", qi(r.shadows * 100f))
        if (r.whites != 0f) attr("Whites2012", qi(r.whites * 100f))
        if (r.blacks != 0f) attr("Blacks2012", qi(r.blacks * 100f))

        // ---- Color ----
        if (r.adjustments.warmth != 0f) {
            // Inverse of the importer's log scale around 5500K.
            val k = (5500.0 * Math.exp(r.adjustments.warmth * Math.log(9.0))).toFloat()
            attr("Temperature", k.toInt().coerceIn(2000, 50000).toString())
        }
        if (r.adjustments.tint != 0f) attr("Tint", qi(r.adjustments.tint * 100f))
        if (r.vibrance != 0f) attr("Vibrance", qi(r.vibrance * 100f))
        if (r.adjustments.saturation != 1f) attr("Saturation", qi((r.adjustments.saturation - 1f) * 100f))
        if (r.grayscale > 0f) attr("ConvertToGrayscale", "True")
        r.hslArray()?.let { h ->
            for (b in 0 until Hsl.BANDS) {
                val s = Hsl.XMP_SUFFIXES[b]
                if (h[Hsl.hueAt(b)] != 0f) attr("HueAdjustment$s", qi(h[Hsl.hueAt(b)] * 100f))
                if (h[Hsl.satAt(b)] != 0f) attr("SaturationAdjustment$s", qi(h[Hsl.satAt(b)] * 100f))
                if (h[Hsl.lumAt(b)] != 0f) attr("LuminanceAdjustment$s", qi(h[Hsl.lumAt(b)] * 100f))
            }
        }
        r.gradeArray()?.let { g ->
            if (ColorGrade.isActive(g)) {
                attr("ColorGradeShadowHue", ((g[0] * 360f).toInt()).toString())
                attr("ColorGradeShadowSat", qi(g[1] * 100f))
                attr("ColorGradeMidtoneHue", ((g[2] * 360f).toInt()).toString())
                attr("ColorGradeMidtoneSat", qi(g[3] * 100f))
                attr("ColorGradeHighlightHue", ((g[4] * 360f).toInt()).toString())
                attr("ColorGradeHighlightSat", qi(g[5] * 100f))
                attr("ColorGradeBlending", qi(g[6] * 100f))
                attr("ColorGradeBalance", qi(g[7] * 100f))
            }
        }
        r.bwArray()?.let { b ->
            if (r.grayscale > 0f && BwMix.isActive(b)) {
                val names = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
                names.forEachIndexed { i, s -> if (b[i] != 0f) attr("GrayMixer$s", qi(b[i] * 100f)) }
            }
        }
        r.calibrationParts()?.let { (h, s) ->
            val names = listOf("Red", "Green", "Blue")
            names.forEachIndexed { i, n ->
                if (h[i] != 0f) attr("${n}Hue", qi(h[i] * 100f))
                if (s[i] != 0f) attr("${n}Saturation", qi(s[i] * 100f))
            }
        }

        // ---- Effects ----
        if (r.texture != 0f) attr("Texture", qi(r.texture * 100f))
        if (r.clarity != 0f) attr("Clarity2012", qi(r.clarity * 100f))
        if (r.dehaze != 0f) attr("Dehaze", qi(r.dehaze * 100f))
        if (r.vignette != 0f) attr("PostCropVignetteAmount", qi(-r.vignette * 100f))
        if (r.vigMidpoint != 0.5f) attr("PostCropVignetteMidpoint", qi(r.vigMidpoint * 100f))
        if (r.vigFeather != 0.5f) attr("PostCropVignetteFeather", qi(r.vigFeather * 100f))
        if (r.vigRound != 0.5f) attr("PostCropVignetteRoundness", qi((r.vigRound - 0.5f) * 200f))
        if (r.vigAspect != 0.5f) attr("PostCropVignetteAspect", qi((r.vigAspect - 0.5f) * 200f))
        if (r.grain != 0f) attr("GrainAmount", qi(r.grain * 100f))
        if (r.grainSize != 1f) attr("GrainSize", qi((r.grainSize - 0.5f) / 2.5f * 100f))
        if (r.grainRough != 0.5f) attr("GrainFrequency", qi(r.grainRough * 100f))

        // ---- Detail ----
        if (r.sharpen != 0f) attr("Sharpness", qi(r.sharpen * 100f))
        if (r.sharpRadius != 1f) attr("SharpenRadius", r.sharpRadius.toString())
        if (r.detail != 0f) attr("SharpenDetail", qi(r.detail * 100f))
        if (r.masking != 0f) attr("SharpenEdgeMasking", qi(r.masking * 100f))
        if (r.denoiseLum != 0f) attr("LuminanceSmoothing", qi(r.denoiseLum * 100f))
        if (r.denoiseColor != 0f) attr("ColorNoiseReduction", qi(r.denoiseColor * 100f))
        r.defringeArray()?.let { d ->
            if (Defringe.isActive(d)) {
                attr("DefringePurpleAmount", qi(d[0] * 100f))
                attr("DefringePurpleHueLo", ((d[1] * 360f).toInt()).toString())
                attr("DefringePurpleHueHi", ((d[2] * 360f).toInt()).toString())
                attr("DefringeGreenAmount", qi(d[3] * 100f))
                attr("DefringeGreenHueLo", ((d[4] * 360f).toInt()).toString())
                attr("DefringeGreenHueHi", ((d[5] * 360f).toInt()).toString())
            }
        }

        // ---- Optics + geometry ----
        if (r.lensCA > 0.5f) attr("AutoLateralCA", "True")
        if (r.lensEnable > 0.5f) attr("LensProfileEnable", "True")
        if (r.lensDistort != 0f) attr("LensManualDistortionAmount", q(r.lensDistort * 100f))
        r.geoArray()?.let { g ->
            if (Geometry.isActive(g)) {
                attr("PerspectiveUpright", g[0].toInt().toString())
                if (g[1] != 0f) attr("PerspectiveVertical", qi(g[1] * 100f))
                if (g[2] != 0f) attr("PerspectiveHorizontal", qi(g[2] * 100f))
                if (g[3] != 0f) attr("PerspectiveRotate", (g[3] * 30f).toString())
                if (g[4] != 0f) attr("PerspectiveAspect", qi(g[4] * 100f))
                if (g[5] != 0f) attr("PerspectiveScale", ((g[5] * 100f + 100f).toInt()).toString())
                if (g[6] != 0f) attr("PerspectiveX", qi(g[6] * 100f))
                if (g[7] != 0f) attr("PerspectiveY", qi(g[7] * 100f))
            }
        }

        // ---- Tone curves: downsampled to control points. ----
        if (r.toneCurveActive) {
            val group = ToneCurve.parseGroup(r.toneCurves)
            curvePoints(group[0])?.let { attr("ToneCurvePV2012", it) }
            curvePoints(group[1])?.let { attr("ToneCurvePV2012Red", it) }
            curvePoints(group[2])?.let { attr("ToneCurvePV2012Green", it) }
            curvePoints(group[3])?.let { attr("ToneCurvePV2012Blue", it) }
        }

        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
            " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "  <rdf:Description rdf:about=\"\"\n" +
            "   xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"\n" +
            a.toString() +
            "  />\n </rdf:RDF>\n</x:xmpmeta>\n"
    }

    /**
     * Ten evenly spaced samples of a 256-run as `(x, y)` control points.
     *
     * Null when the run is the identity ramp (or absent): writing an explicit
     * ten-point straight line would import back fine but would bloat every
     * preset that never touched its curves.
     */
    private fun curvePoints(run: FloatArray?): String? {
        if (run == null || run.size != ToneCurve.SIZE) return null
        var flat = true
        for (i in 0 until ToneCurve.SIZE) {
            if (Math.abs(run[i] - i / 255f) > 2 / 255f) { flat = false; break }
        }
        if (flat) return null
        return (0 until 10).joinToString(", ") { k ->
            val i = (k * 255 / 9).coerceIn(0, 255)
            "$i, ${(run[i] * 255f + 0.5f).toInt().coerceIn(0, 255)}"
        }
    }
}
