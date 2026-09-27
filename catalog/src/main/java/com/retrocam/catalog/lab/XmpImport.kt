package com.retrocam.catalog.lab

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * One XMP key that was turned into something.
 *
 * [approx] is the honest part: when true, the Lab knob is not the same operation
 * as the XMP key, only something in its neighbourhood. Showing that is the
 * difference between an import the user can trust and one they have to guess at.
 */
data class XmpApplied(
    val key: String,
    val value: String,
    val mapsTo: String,
    val approx: Boolean = false,
)

/** One XMP key that was deliberately dropped, and why. */
data class XmpIgnored(val key: String, val value: String, val reason: String)

data class XmpResult(val recipe: LabRecipe, val applied: List<XmpApplied>, val ignored: List<XmpIgnored>) {
    val isEmpty: Boolean get() = applied.isEmpty()
}

/**
 * Reads an Adobe Camera Raw `.xmp` preset and maps it onto Lab knobs.
 *
 * ## Why an attribute scanner and not an XML parser
 *
 * Every Camera Raw setting is an *attribute* on an `rdf:Description` element
 * (`crs:Exposure2012="+0.35"`), never an element, and even the 200-character
 * tone curve is one quoted attribute value. So a scanner for `crs:name="value"`
 * reads the whole namespace, and it keeps this file in the pure-Kotlin catalog
 * module where the mapping is unit-testable. Pulling in an XML parser to read a
 * flat attribute list would add a dependency and an Android-only code path for
 * nothing.
 *
 * The assumption is therefore narrow and stated rather than silent: values are
 * always double-quoted, which is what every Adobe tool emits. A hand-edited
 * file using single quotes would be read as absent, not as corrupt.
 *
 * ## What is deliberately not mapped
 *
 * A Camera Raw preset has around forty recognised settings and the Lab has seven
 * knobs. Most are dropped on purpose, and [XmpIgnored] says which and why,
 * because a preset that silently loses its highlights curve is worse than one
 * that admits it. The big ones we do not implement:
 *
 *  - `ToneCurvePV2012` and its per-channel variants. A real tone curve is a
 *    lookup table and the Lab has no curve texture. This is the single largest
 *    thing an XMP preset carries, so an import is always an approximation.
 *  - `Highlights2012` / `Whites2012` / `Shadows2012` / `Blacks2012`. These are
 *    range-specific, and folding them into a midtone gamma would lift the whole
 *    image rather than one end of it.
 *  - `Texture` / `Clarity` / `Dehaze`. Local-contrast operators with no analogue.
 *  - `Look`. An enum naming a named curve set, not a number; guessing a mapping
 *    would be inventing behaviour.
 *  - `GrainAmount`, `PostCropVignetteAmount`. The Lab has both effects, but
 *    Adobe's units are their own and the pairing would be guesswork.
 */
object XmpImport {

    private val ATTR = Regex("""crs:([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""")

    /** Every `crs:` attribute in the file, first occurrence winning. */
    fun readAttributes(xmp: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(xmp)) {
            val k = m.groupValues[1]
            if (k !in out) out[k] = m.groupValues[2]
        }
        return out
    }

    private fun num(v: String?): Float? = v?.trim()?.toFloatOrNull()

    private fun q(v: Float): String =
        if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString()

    /** Metadata rather than look: reported as read, but not a knob. */
    private val METADATA = setOf(
        "ProcessVersion", "HasSettings", "Cluster", "ClusterName", "UUID",
        "ToneCurveName", "SupportsAmount", "UseToneCurve", "ProfileCopyright",
        "ProfileName", "Description", "Creator", "ExposureCompensation",
        "AutoBrightness", "CameraProfile", "CropAmount", "ToneCurvePV2012",
    )

    /** All locals, so two imports cannot bleed into each other. */
    fun parse(xmp: String): XmpResult {
        val a = readAttributes(xmp)
        val applied = mutableListOf<XmpApplied>()
        val ignored = mutableListOf<XmpIgnored>()

        var gamma = 1f
        var contrast = 1f
        var saturation = 1f
        var warmth = 0f
        var tint = 0f
        var sharpen = 0f

        fun add(key: String, value: String, mapsTo: String, approx: Boolean = false) {
            applied += XmpApplied(key, value, mapsTo, approx)
        }

        fun drop(key: String, reason: String) {
            a[key]?.let { ignored += XmpIgnored(key, it, reason) }
        }

        // Which key family is present decides the scale, and getting this wrong
        // is the difference between a +10 contrast tweak and a +1000 one, so it
        // is resolved first and from the data rather than assumed.
        val modern = a.containsKey("Contrast2012") || a.containsKey("Exposure2012") ||
            (num(a["ProcessVersion"]) ?: 0f) >= 3f

        // ---- exposure -> gamma ----
        // Exposure is a multiplicative stop count and gamma is a power curve,
        // the only multiplicative knob available. brightness looks like the
        // natural home but it is an additive 0-255 offset inside the 4x5 matrix,
        // so mapping a stop count onto it would move blacks.
        val expKey = if (modern) "Exposure2012" else "Exposure"
        num(a[expKey])?.let { e ->
            if (e != 0f) {
                // 1 EV is a doubling, and out = in^(1/gamma) doubles brightness at
                // gamma = 2, so gamma = 2^stops is the natural correspondence.
                gamma = Math.pow(2.0, e.toDouble()).toFloat().coerceIn(0.2f, 3f)
                add(expKey, q(e), "GAMMA = ${q(gamma)}", approx = true)
            }
        }

        // ---- contrast -> contrast ----
        val cKey = if (modern) "Contrast2012" else "Contrast"
        num(a[cKey])?.let { c ->
            // Process 1 and 2 stored contrast as 0-255 about 128, the 2012
            // family as -100..100. Re-centring the legacy value onto 128 puts
            // both families on one scale so a single divide applies. The legacy
            // range is really +/-128 and this treats it as +/-100, which is a
            // slight under-read of an extreme legacy contrast and is preferable
            // to rescaling and moving the neutral.
            val normalised = if (modern) c else (c - 128f)
            if (normalised != 0f) {
                contrast = (1f + normalised / 100f).coerceIn(0f, 3f)
                add(cKey, q(c), "CONTRAST = ${q(contrast)}")
            }
        }

        // ---- saturation, from Saturation and Vibrance together ----
        // Lightroom applies both, and Vibrance protects skin tones, so they
        // multiply rather than one replacing the other. A preset that sets only
        // Vibrance still does something.
        num(a["Saturation"])?.let { s ->
            if (s != 0f) {
                saturation *= 1f + s / 100f
                add("Saturation", q(s), "SATURATION")
            }
        }
        num(a["Vibrance"])?.let { v ->
            if (v != 0f) {
                saturation *= 1f + v / 100f
                add("Vibrance", q(v), "SATURATION", approx = true)
            }
        }
        saturation = saturation.coerceIn(0f, 3f)

        // ---- sharpness -> the sharpen effect ----
        num(a["Sharpness"])?.let { sh ->
            if (sh != 0f) {
                sharpen = (sh / 100f).coerceIn(0f, 1f)
                add("Sharpness", q(sh), "SHARPEN = ${q(sharpen)}")
            }
        }

        // ---- colour temperature -> warmth ----
        // Kelvin is absolute and warmth is a -1..1 opinion, so the pivot is
        // Adobe's own 5500K reference and the scale is logarithmic: 2000K to
        // 5000K is one step to a person, 5000K to 50000K is barely one.
        num(a["ColorTemp"])?.let { k ->
            if (k > 0f) {
                warmth = (ln(k / 5500.0) / ln(9.0)).toFloat().coerceIn(-1f, 1f)
                add("ColorTemp", q(k), "WARMTH = ${q(warmth)}", approx = true)
            }
        }

        // ---- tint: a direct match, both are -1..1 magenta-to-green ----
        num(a["Tint"])?.let { t ->
            if (t != 0f) {
                tint = (t / 100f).coerceIn(-1f, 1f)
                add("Tint", q(t), "TINT = ${q(tint)}")
            }
        }

        // ---- everything else, reported rather than silently dropped ----
        for (k in listOf("Highlights2012", "Highlights")) drop(k, "range-specific, no knob")
        for (k in listOf("Whites2012", "Whites")) drop(k, "range-specific, no knob")
        for (k in listOf("Shadows2012", "Shadows")) drop(k, "range-specific, no knob")
        for (k in listOf("Blacks2012", "Blacks")) drop(k, "range-specific, no knob")
        for (k in listOf("Texture", "Clarity", "Dehaze")) drop(k, "local contrast, no analogue")
        for (k in listOf("GrainAmount", "PostCropVignetteAmount")) {
            drop(k, "units differ, no honest mapping")
        }
        for (k in listOf("ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue")) {
            drop(k, "per-channel curve, no curve texture")
        }
        drop("ToneCurvePV2012", "needs a curve texture, not a knob")
        drop("ToneCurveName", "a named curve set, not a value")
        drop("Look", "an enum naming a curve set; no honest mapping")

        // Anything else in the file is listed too, so the report is a real
        // inventory of the preset rather than a fixed list with holes in it.
        val seen = applied.map { it.key }.toSet() + ignored.map { it.key }.toSet()
        for ((k, v) in a) {
            if (k in seen || k in METADATA) continue
            ignored += XmpIgnored(k, v, "not a look setting")
        }

        return XmpResult(
            recipe = LabRecipe(
                adjustments = LabAdjustments(
                    // brightness stays neutral: exposure went to gamma instead.
                    brightness = 0f,
                    contrast = contrast,
                    saturation = saturation,
                    warmth = warmth,
                    tint = tint,
                ),
                gamma = gamma,
                sharpen = sharpen,
            ),
            applied = applied,
            ignored = ignored,
        )
    }
}
