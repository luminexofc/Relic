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

    /**
     * Read, reported, and deliberately outside the coverage percentage.
     *
     * `SupportsColor`, `Copyright`, `Version`, `PerspectiveVertical` and the
     * rest. They are in the file and a user reading the report should see them,
     * but a preset that changes the picture does not depend on any of them, so
     * counting them as failures made the number meaningless.
     */
    NOT_A_LOOK,
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
    /**
     * Keys that were mapped onto something.
     *
     * Spelled out as two tiers rather than `!= UNSUPPORTED` on purpose: a not-a-look
     * key IS not unsupported, and filtering on that would list `Copyright` as an
     * applied setting and push the coverage figure over 100%.
     */
    val applied: List<XmpKey>
        get() = keys.filter { it.fidelity == Fidelity.EXACT || it.fidelity == Fidelity.APPROXIMATE }
    val ignored: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.UNSUPPORTED }
    val exact: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.EXACT }
    val approximate: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.APPROXIMATE }
    val isEmpty: Boolean get() = applied.isEmpty()

    /**
     * Keys that change the picture, and so are the only honest denominator.
     *
     * A Camera Raw file carries ~140 attributes. Around 40 of them change the
     * image; the rest are camera capability flags, copyright strings, raw
     * converter inputs and lens geometry. Counting those in the denominator
     * made a preset that we reproduce almost completely report 9%, which is
     * not a measurement of anything - it measures how verbose XMP is.
     *
     * So the percentage is over the look settings, and the non-look keys are
     * still listed in the report under [metadata] rather than being dropped
     * silently. Nothing disappears; it just stops being counted as a failure.
     */
    val lookKeys: List<XmpKey> get() = keys.filter { it.fidelity != Fidelity.NOT_A_LOOK }

    /** Read but not a look setting: capability flags, copyright, lens geometry. */
    val metadata: List<XmpKey> get() = keys.filter { it.fidelity == Fidelity.NOT_A_LOOK }

    /**
     * How much of this preset's *look* the Lab represents, as a whole
     * percentage, over [lookKeys].
     *
     * Counts keys, not visual weight, and that is the honest limit of it: one
     * `ToneCurvePV2012` is worth more than four sliders, and a percentage
     * cannot say so. The breakdown is right there beside it for exactly that
     * reason, and the report names the unsupported keys so a reader can judge.
     */
    val coveragePercent: Int
        get() = if (lookKeys.isEmpty()) 0 else applied.size * 100 / lookKeys.size

    /** The share reproduced exactly, with approximations counted out. */
    val exactPercent: Int
        get() = if (lookKeys.isEmpty()) 0 else exact.size * 100 / lookKeys.size
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
 * A Camera Raw file carries around 140 attributes and about 40 of them change
 * the picture. The rest are camera capability flags, copyright strings, raw
 * converter inputs and lens geometry, and they are reported as
 * [Fidelity.NOT_A_LOOK] rather than counted against the preset.
 *
 * Of the keys that do change the picture, the ones with no Lab equivalent are
 * named in the report with a reason, because a preset that silently loses its
 * highlights curve is worse than one that admits it.
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

    /**
     * Keys that are in the file but change nothing about the picture.
     *
     * Split into two lists on purpose. [SILENT] is historical: keys that were
     * never worth a line in the report. [NOT_A_LOOK] is everything else that is
     * not a look setting, and it IS reported, just not counted - a user reading
     * an import report should be able to see that a preset asked for
     * `AutoLateralCA` and we did not do it, without that fact being scored
     * against the preset's fidelity.
     *
     * Camera capability flags, provenance strings, raw converter inputs and
     * lens geometry. None of them survive a JPEG and none of them are a look.
     */
    private val NOT_A_LOOK_EXACT = setOf(
        // Capability flags: what the camera can do, not what the preset wants.
        "PresetType", "SupportsColor", "SupportsMonochrome", "SupportsHighDynamicRange",
        "SupportsNormalDynamicRange", "SupportsSceneReferred", "SupportsOutputReferred",
        "CameraModelRestriction", "HasSettings", "SupportsAmount", "UseToneCurve",
        "UseLegacyAdobe2012Adjustments", "Cluster", "ClusterName", "UUID",
        // Provenance.
        "Version", "Copyright", "ContactInfo", "Name", "Description", "Creator",
        "ProfileCopyright", "ProfileName", "ToneCurveName", "ProcessVersion",
        // Crop is framing, not grading, and the Lab does not crop.
        "CropAmount", "CropTop", "CropLeft", "CropBottom", "CropRight",
        // Raw converter inputs. Honouring these means DNG profile support.
        "CameraProfile", "LensProfileEnable", "LensProfileSetup", "LensManualDistortionAmount",
        "AutoLateralCA", "CalibrationIlluminant1", "CalibrationIlluminant2",
        "CalibrationHue", "CalibrationSat", "AsShotNeutral",
    )

    /** Optical geometry: a lens correction, not a grade. */
    private val NOT_A_LOOK_PREFIXES = listOf(
        "Perspective", "LensProfile", "CameraCalibration", "WB",
    )

    /** Historical: never reported at all. A strict subset of NOT_A_LOOK. */
    private val METADATA = setOf(
        "ProcessVersion", "HasSettings", "Cluster", "ClusterName", "UUID",
        "ToneCurveName", "SupportsAmount", "UseToneCurve", "ProfileCopyright",
        "ProfileName", "Description", "Creator", "ExposureCompensation",
        "AutoBrightness", "CameraProfile", "CropAmount",
    )

    /**
     * One setting under several names.
     *
     * Adobe renamed keys between Process Versions and the old names are still
     * in files in the wild, and a preset that carries `SharpenRadius` was
     * silently losing all three of its sharpening parameters because the reader
     * only knew `SharpnessRadius`. Same for `Clarity`/`Clarity2012` and
     * `GrainRoughness`/`GrainFrequency`, which are a rename rather than a new
     * setting. The first name that is present in the file wins.
     */
    /**
     * One setting under several names, newest first.
     *
     * Adobe renamed keys between Process Versions and the old names are still in
     * files in the wild. A preset carrying `SharpenRadius` was silently losing
     * all three of its sharpening parameters, because the reader only knew
     * `SharpnessRadius` and then reported it as an unknown key.
     *
     * Order is the resolution order, so the canonical name is always the NEWEST
     * spelling. `Clarity2012` beat `Clarity` for exactly as long as it did not
     * exist here, which is the failure this table exists to stop.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "Exposure2012" to listOf("Exposure"),
        "Contrast2012" to listOf("Contrast"),
        "Temperature" to listOf("ColorTemp"),
        "Tint" to emptyList(),
        "Highlights2012" to listOf("Highlights"),
        "Shadows2012" to listOf("Shadows"),
        "Whites2012" to listOf("Whites"),
        "Blacks2012" to listOf("Blacks"),
        "Texture" to emptyList(),
        "Clarity2012" to listOf("Clarity"),
        "Dehaze" to emptyList(),
        "Saturation" to emptyList(),
        "Vibrance" to emptyList(),
        "Sharpness" to emptyList(),
        "SharpnessRadius" to listOf("SharpenRadius"),
        "Detail" to listOf("SharpenDetail"),
        "Masking" to listOf("SharpenEdgeMasking"),
        "GrainAmount" to emptyList(),
        "GrainSize" to emptyList(),
        "GrainRoughness" to listOf("GrainFrequency"),
        "PostCropVignetteAmount" to listOf("VignetteAmount"),
        "PostCropVignetteMidpoint" to listOf("VignetteMidpoint"),
        "PostCropVignetteFeather" to listOf("VignetteFeather"),
    )

    /**
     * Resolves a canonical key to the one actually present in [a], or null.
     *
     * Returns the canonical name too, because the report has to name the key the
     * file used, not the one we wished it had used. A preset that says
     * `SharpenEdgeMasking` and gets a masking slider is a success, but a report
     * claiming `Masking` was applied would be a lie about the file.
     */
    private fun resolve(a: Map<String, String>, canonical: String): Pair<String, String>? {
        for (name in listOf(canonical) + ALIASES[canonical].orEmpty()) {
            val v = a[name] ?: continue
            return name to v
        }
        return null
    }

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

        /**
         * A key the preset set to zero.
         *
         * Zero is a *value*, not an absence, and the earlier reader treated it
         * as an absence: every block was guarded by `if (v != 0f) add(...)`, so
         * a preset full of honest zeroes fell through to the "unrecognised key"
         * loop and got reported as dropped. A file that says `Exposure2012="0"`
         * was scored as a failure to import an exposure. It is neutral, it was
         * honoured, and it counts.
         */
        fun neutral(key: String, raw: String) {
            keys += XmpKey(key, raw, Fidelity.EXACT, mapsTo = "neutral (0)")
        }

        fun drop(key: String, reason: String) {
            a[key]?.let { keys += XmpKey(key, it, Fidelity.UNSUPPORTED, reason = reason) }
        }

        /**
         * One numeric look setting, resolved through [resolve], with the zero
         * case handled once instead of fifteen times.
         *
         * [f] runs only for a non-zero value and is expected to set the
         * accumulator; the report line is written either way. Collapsing these
         * into one helper is not tidiness for its own sake: fifteen hand-written
         * copies of "look it up, skip if zero, record it" is exactly the shape
         * of drift, and they had already drifted.
         *
         * [f] receives the key name that actually matched, not the canonical
         * one, because the scale belongs to the key rather than to the file:
         * `Contrast` is 0-255 about 128 and `Contrast2012` is -100..100, and no
         * amount of ProcessVersion guessing changes that.
         *
         * [isNeutralAtZero] is for the keys whose neutral is not zero, i.e. the
         * ones Adobe centres (GrainSize and the two vignette parameters sit at
         * 50, not 0).
         */
        fun knob(
            canonical: String,
            mapsTo: String,
            approx: Boolean = false,
            isNeutralAtZero: Float = 0f,
            f: (String, Float) -> Unit,
        ) {
            val (name, raw) = resolve(a, canonical) ?: return
            val v = num(raw) ?: return
            if (v == isNeutralAtZero) {
                neutral(name, raw)
                return
            }
            f(name, v)
            add(name, q(v), mapsTo, approx)
        }

        // ---- exposure -> gamma ----
        // Exposure is a multiplicative stop count and gamma is a power curve,
        // the only multiplicative knob available. brightness looks like the
        // natural home but it is an additive 0-255 offset inside the 4x5 matrix,
        // so mapping a stop count onto it would move blacks.
        knob("Exposure2012", "GAMMA", approx = true) { _, e ->
            // 1 EV is a doubling, and out = in^(1/gamma) doubles brightness at
            // gamma = 2, so gamma = 2^stops is the natural correspondence.
            gamma = Math.pow(2.0, e.toDouble()).toFloat().coerceIn(0.2f, 3f)
        }

        // ---- contrast -> contrast ----
        knob("Contrast2012", "CONTRAST") { name, c ->
            // The scale is decided by which key the file used, not by
            // ProcessVersion. `Contrast` is 0-255 about 128 whatever the
            // process version claims; `Contrast2012` is -100..100. Re-centring
            // the legacy value onto 128 puts both families on one scale so a
            // single divide applies. The legacy range is really +/-128 and this
            // treats it as +/-100, which is a slight under-read of an extreme
            // legacy contrast and is preferable to rescaling and moving the
            // neutral.
            val normalised = if (name.endsWith("2012")) c else (c - 128f)
            contrast = (1f + normalised / 100f).coerceIn(0f, 3f)
        }

        // ---- saturation, from Saturation and Vibrance together ----
        // Lightroom applies both, and Vibrance protects skin tones, so they
        // multiply rather than one replacing the other. A preset that sets only
        // Vibrance still does something.
        knob("Saturation", "SATURATION") { _, v -> saturation *= 1f + v / 100f }
        knob("Vibrance", "SATURATION", approx = true) { _, v -> saturation *= 1f + v / 100f }
        saturation = saturation.coerceIn(0f, 3f)

        // ---- sharpness -> the sharpen effect ----
        knob("Sharpness", "SHARPEN") { _, v -> sharpen = (v / 100f).coerceIn(0f, 1f) }

        // ---- colour temperature -> warmth ----
        // Kelvin is absolute and warmth is a -1..1 opinion, so the pivot is
        // Adobe's own 5500K reference and the scale is logarithmic: 2000K to
        // 5000K is one step to a person, 5000K to 50000K is barely one.
        val tempResolved = resolve(a, "Temperature")
        if (tempResolved != null) {
            val (tempKey, tempRaw) = tempResolved
            val k = num(tempRaw)
            when {
                k == null -> drop(tempKey, "not a number")
                k <= 0f -> neutral(tempKey, tempRaw)
                else -> {
                    warmth = (ln(k / 5500.0) / ln(9.0)).toFloat().coerceIn(-1f, 1f)
                    add(tempKey, q(k), "WARMTH = ${q(warmth)}", approx = true)
                }
            }
        }

        // ---- tint: a direct match, both are -1..1 magenta-to-green ----
        knob("Tint", "TINT") { _, v -> tint = (v / 100f).coerceIn(-1f, 1f) }

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
            var m: Float? = null
            knob(modernKey, legacyKey.uppercase()) { _, v -> m = (v / 100f).coerceIn(-1f, 1f) }
            ranges[slot] = m
        }

        // ---- local contrast: Texture, Clarity, Dehaze, all -100..100 ----
        // The label is the knob, not the key, because `Clarity2012` is the key
        // and CLARITY is the thing it drives.
        val local = arrayOfNulls<Float>(3)
        listOf(
            Triple("Texture", "TEXTURE", 0),
            Triple("Clarity2012", "CLARITY", 1),
            Triple("Dehaze", "DEHAZE", 2),
        ).forEach { (key, label, slot) ->
            knob(key, label) { _, v -> local[slot] = (v / 100f).coerceIn(-1f, 1f) }
        }

        // ---- operator parameters, the small ones that change the look a lot ----
        // SharpnessRadius, Detail and Masking. Masking is the important one: it
        // is a threshold on the local difference, and without it a preset tuned
        // to a threshold rings every flat patch of sky.
        //
        // Radius has no zero to treat as neutral - Adobe's default is 1.0 and a
        // file that says 1.0 has told us its radius, which is worth reporting.
        knob("SharpnessRadius", "SHARP RADIUS", approx = true) { _, v ->
            sharpRadius = v.coerceIn(0.5f, 3f)
        }
        knob("Detail", "DETAIL") { _, v -> detail = (v / 100f).coerceIn(0f, 1f) }
        knob("Masking", "MASKING") { _, v -> masking = (v / 100f).coerceIn(0f, 1f) }

        // ---- grain distribution, and the vignette falloff ----
        // GrainAmount maps onto the Lab's own grain, which is the one thing the
        // earlier "units differ" note was wrong about: the range is the same, it
        // is the distribution that needed real parameters, and those are above.
        knob("GrainAmount", "GRAIN") { _, v -> grain = (v / 100f).coerceIn(0f, 1f) }
        knob("GrainSize", "GRAIN SIZE", approx = true, isNeutralAtZero = 50f) { _, v ->
            // Adobe's GrainSize is 0..100, 50 neutral; the Lab's is a 0.5..3
            // multiplier, so the scale is remapped rather than divided.
            grainSize = (0.5f + (v / 100f) * 2.5f).coerceIn(0.5f, 3f)
        }
        knob("GrainRoughness", "GRAIN ROUGH", approx = true, isNeutralAtZero = 50f) { _, v ->
            grainRough = (v / 100f).coerceIn(0f, 1f)
        }
        // Midpoint and Feather are 0..100 with 50 neutral. Roundness and Aspect
        // change the shape of the falloff rather than its strength and are not
        // implemented; they are reported as dropped below.
        knob("PostCropVignetteMidpoint", "VIGNETTE MIDPOINT", approx = true, isNeutralAtZero = 50f) { _, v ->
            vigMid = (v / 100f).coerceIn(0f, 1f)
        }
        knob("PostCropVignetteFeather", "VIGNETTE FEATHER", approx = true, isNeutralAtZero = 50f) { _, v ->
            vigFeather = (v / 100f).coerceIn(0f, 1f)
        }
        knob("PostCropVignetteAmount", "VIGNETTE", approx = true) { _, v ->
            vignette = (kotlin.math.abs(v) / 100f).coerceIn(0f, 1f)
        }

        // ---- everything else, reported rather than silently dropped ----
        for (k in listOf("AutoBrightness", "Auto Tone")) drop(k, "needs a scene analysis")
        for (k in listOf("PostCropVignetteRoundness", "PostCropVignetteAspect")) {
            drop(k, "changes the falloff shape, not its strength; not implemented")
        }

        drop("Look", "an enum naming a curve set; no honest mapping")

        // Anything else in the file is listed too, so the report is a real
        // inventory of the preset rather than a fixed list with holes in it.
        // What decides the tier now is [notALook], not "we have never heard of
        // it": an unrecognised key that is plainly a look setting is a genuine
        // gap and is scored as one.
        val seen = keys.map { it.key }.toSet()
        for ((k, v) in a) {
            if (k in seen || k in METADATA) continue
            if (k in NOT_A_LOOK_EXACT || NOT_A_LOOK_PREFIXES.any { k.startsWith(it) }) {
                keys += XmpKey(k, v, Fidelity.NOT_A_LOOK, reason = "not a look setting")
            } else {
                keys += XmpKey(k, v, Fidelity.UNSUPPORTED, reason = "no Lab equivalent yet")
            }
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
