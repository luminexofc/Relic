package com.retrocam.catalog.lab

import kotlin.math.ln
import kotlin.math.roundToInt

/** How faithfully one XMP setting is reproduced. */
enum class Fidelity {
    /** The same operation, same units. */
    EXACT,

    /**
     * Something in the same neighbourhood. The knob is not what the key does,
     * only close to it, and saying so is the difference between an import the
     * user can trust and one they have to guess at.
     */
    APPROXIMATE,

    /** No Lab equivalent. [XmpKey.reason] says why. */
    UNSUPPORTED,
}

/**
 * One XMP key and what became of it.
 *
 * A single tagged list rather than separate "applied" and "ignored" lists,
 * because the two are the same question asked twice and keeping them in sync
 * is how a key ends up in neither. [XmpResult.applied] and [XmpResult.ignored]
 * are views over this, not storage.
 */
data class XmpKey(
    val key: String,
    val value: String,
    val fidelity: Fidelity,
    /** The Lab control, e.g. "GAMMA = 1.27". Null when unsupported. */
    val mapsTo: String? = null,
    /** Why it was dropped. Null unless unsupported. */
    val reason: String? = null,
) {
    /** Kept because the report and the tests both read it under this name. */
    val approx: Boolean get() = fidelity == Fidelity.APPROXIMATE
}

data class XmpResult(val recipe: LabRecipe, val keys: List<XmpKey>) {
    val applied: List<XmpKey> get() = keys.filter { it.fidelity != Fidelity.UNSUPPORTED }
    val ignored: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.UNSUPPORTED }
    val exact: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.EXACT }
    val approximate: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.APPROXIMATE }
    val isEmpty: Boolean get() = applied.isEmpty()

    /**
     * How much of this preset the Lab represents, as a whole percentage.
     *
     * Counts keys, not visual weight, and that is the honest limit of it: one
     * `ToneCurvePV2012` is worth more than four sliders, and a percentage
     * cannot say so. The breakdown is right there beside it for exactly that
     * reason, and the report names the unsupported keys so a reader can judge.
     */
    val coveragePercent: Int
        get() = if (keys.isEmpty()) 0 else applied.size * 100 / keys.size

    /** The share reproduced exactly, with approximations counted out. */
    val exactPercent: Int
        get() = if (keys.isEmpty()) 0 else exact.size * 100 / keys.size
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
        val keys = mutableListOf<XmpKey>()

        var gamma = 1f
        var contrast = 1f
        var saturation = 1f
        var warmth = 0f
        var tint = 0f
        var sharpen = 0f
        var sharpRadius = 1f
        var detail = 0f
        var masking = 0f
        var grain = 0f
        var grainSize = 1f
        var grainRough = 0.5f
        var vigMid = 0.5f
        var vigFeather = 0.5f
        var vignette = 0f

        fun add(key: String, value: String, mapsTo: String, approx: Boolean = false) {
            keys += XmpKey(
                key, value,
                if (approx) Fidelity.APPROXIMATE else Fidelity.EXACT,
                mapsTo = mapsTo,
            )
        }

        fun drop(key: String, reason: String) {
            a[key]?.let { keys += XmpKey(key, it, Fidelity.UNSUPPORTED, reason = reason) }
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

        // ---- tone curve: the single largest thing a preset carries ----
        // Composite plus per-channel, packed into one recipe field. Look is
        // still dropped: it names one of Adobe's proprietary curve sets, and
        // inventing a substitute would be guessing at someone's look.
        val curveKeys = listOf(
            "ToneCurvePV2012" to 0,
            "ToneCurvePV2012Red" to 1,
            "ToneCurvePV2012Green" to 2,
            "ToneCurvePV2012Blue" to 3,
        )
        val curves = arrayOfNulls<FloatArray>(4)
        var anyCurve = false
        for ((key, slot) in curveKeys) {
            val v = a[key] ?: continue
            val c = ToneCurve.parse(v)
            if (c == null) {
                keys += XmpKey(key, v, Fidelity.UNSUPPORTED, reason = "unreadable: not a list of (x, y) points")
                continue
            }
            curves[slot] = c
            anyCurve = true
            add(key, v.take(24) + if (v.length > 24) "..." else "", "TONE CURVE", approx = true)
        }

        // ---- the four range controls, XMP -100..100 mapped to -1..1 ----
        val ranges = arrayOfNulls<Float>(4)
        listOf(
            Triple("Highlights2012", "Highlights", 0),
            Triple("Shadows2012", "Shadows", 1),
            Triple("Whites2012", "Whites", 2),
            Triple("Blacks2012", "Blacks", 3),
        ).forEach { (modernKey, legacyKey, slot) ->
            val key = if (modern && modernKey in a) modernKey else legacyKey
            val v = num(a[key]) ?: return@forEach
            if (v == 0f) return@forEach
            val m = (v / 100f).coerceIn(-1f, 1f)
            ranges[slot] = m
            add(key, q(v), "${legacyKey.uppercase()} = ${q(m)}")
        }

        // ---- local contrast: Texture, Clarity, Dehaze, all -100..100 ----
        val local = arrayOfNulls<Float>(3)
        listOf("Texture" to 0, "Clarity" to 1, "Dehaze" to 2).forEach { (key, slot) ->
            val v = num(a[key]) ?: return@forEach
            if (v == 0f) return@forEach
            val m = (v / 100f).coerceIn(-1f, 1f)
            local[slot] = m
            add(key, q(v), "${key.uppercase()} = ${q(m)}")
        }

        // ---- operator parameters, the small ones that change the look a lot ----
        // SharpnessRadius, Detail and Masking. Masking is the important one: it
        // is a threshold on the local difference, and without it a preset tuned
        // to a threshold rings every flat patch of sky.
        num(a["SharpnessRadius"])?.let {
            val r = it.coerceIn(0.5f, 3f)
            sharpRadius = r
            add("SharpnessRadius", q(it), "SHARP RADIUS = ${q(r)}", approx = true)
        }
        num(a["Detail"])?.let {
            if (it != 0f) {
                val d = (it / 100f).coerceIn(0f, 1f)
                detail = d
                add("Detail", q(it), "DETAIL = ${q(d)}")
            }
        }
        num(a["Masking"])?.let {
            if (it != 0f) {
                val m = (it / 100f).coerceIn(0f, 1f)
                masking = m
                add("Masking", q(it), "MASKING = ${q(m)}")
            }
        }

        // ---- grain distribution, and the vignette falloff ----
        // GrainAmount maps onto the Lab's own grain, which is the one thing the
        // earlier "units differ" note was wrong about: the range is the same, it
        // is the distribution that needed real parameters, and those are above.
        num(a["GrainAmount"])?.let {
            if (it != 0f) {
                grain = (it / 100f).coerceIn(0f, 1f)
                add("GrainAmount", q(it), "GRAIN = ${q(grain)}")
            }
        }
        num(a["GrainSize"])?.let {
            // Adobe's GrainSize is 0..100, 50 neutral; the Lab's is a 0.5..3
            // multiplier, so the scale is remapped rather than divided.
            val sz = (0.5f + (it / 100f) * 2.5f).coerceIn(0.5f, 3f)
            grainSize = sz
            add("GrainSize", q(it), "GRAIN SIZE = ${q(sz)}", approx = true)
        }
        num(a["GrainRoughness"])?.let {
            val r = (it / 100f).coerceIn(0f, 1f)
            grainRough = r
            add("GrainRoughness", q(it), "GRAIN ROUGH = ${q(r)}", approx = true)
        }
        // Midpoint and Feather are 0..100 with 50 neutral. Roundness and Aspect
        // change the shape of the falloff rather than its strength and are not
        // implemented; they are reported as dropped below.
        num(a["PostCropVignetteMidpoint"])?.let {
            val v = (it / 100f).coerceIn(0f, 1f)
            vigMid = v
            add("PostCropVignetteMidpoint", q(it), "VIGNETTE MIDPOINT = ${q(v)}", approx = true)
        }
        num(a["PostCropVignetteFeather"])?.let {
            val v = (it / 100f).coerceIn(0f, 1f)
            vigFeather = v
            add("PostCropVignetteFeather", q(it), "VIGNETTE FEATHER = ${q(v)}", approx = true)
        }
        num(a["PostCropVignetteAmount"])?.let {
            if (it != 0f) {
                val v = (kotlin.math.abs(it) / 100f).coerceIn(0f, 1f)
                vignette = v
                add("PostCropVignetteAmount", q(it), "VIGNETTE = ${q(v)}", approx = true)
            }
        }

        // ---- everything else, reported rather than silently dropped ----
        for (k in listOf("AutoBrightness", "Auto Tone")) drop(k, "needs a scene analysis")
        for (k in listOf("PostCropVignetteRoundness", "PostCropVignetteAspect")) {
            drop(k, "changes the falloff shape, not its strength; not implemented")
        }

        drop("ToneCurveName", "a named curve set, not a value")
        drop("Look", "an enum naming a curve set; no honest mapping")

        // Anything else in the file is listed too, so the report is a real
        // inventory of the preset rather than a fixed list with holes in it.
        val seen = keys.map { it.key }.toSet()
        for ((k, v) in a) {
            if (k in seen || k in METADATA) continue
            keys += XmpKey(k, v, Fidelity.UNSUPPORTED, reason = "not a look setting")
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
                toneCurves = if (anyCurve) ToneCurve.encodeGroup(curves.toList()) else ToneCurve.NONE,
                highlights = ranges[0] ?: 0f,
                shadows = ranges[1] ?: 0f,
                whites = ranges[2] ?: 0f,
                blacks = ranges[3] ?: 0f,
                texture = local[0] ?: 0f,
                clarity = local[1] ?: 0f,
                dehaze = local[2] ?: 0f,
                sharpRadius = sharpRadius,
                detail = detail,
                masking = masking,
                grain = grain,
                grainSize = grainSize,
                grainRough = grainRough,
                vigMidpoint = vigMid,
                vigFeather = vigFeather,
                vignette = vignette,
            ),
            keys = keys,
        )
    }
}
