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
     * The short name of a Color Mixer key, so the report says
     * "GREEN HUE" rather than repeating a forty-character key name 24 times.
     */
    private fun kindOf(key: String): String = when {
        key.startsWith("HueAdjustment") -> "HUE"
        key.startsWith("SaturationAdjustment") -> "SAT"
        key.startsWith("LuminanceAdjustment") -> "LUM"
        else -> key
    }

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
        "ProfileCopyright", "ProfileName", "ToneCurveName", "ToneCurveName2012",
        "OverrideLookVignette", "OverrideLook", "ProcessVersion",
        // Crop is framing, not grading, and the Lab does not crop.
        "CropAmount", "CropTop", "CropLeft", "CropBottom", "CropRight",
        // Raw converter inputs. Honouring these means DNG profile support.
        "CameraProfile", "LensProfileSetup",
        "CalibrationIlluminant1", "CalibrationIlluminant2",
        "CalibrationHue", "CalibrationSat", "AsShotNeutral",
    )

    /** Non-look prefixes. Lens/Geometry are now real controls, not metadata. */
    private val NOT_A_LOOK_PREFIXES = listOf(
        "CameraCalibration", "WB",
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
        // Adobe renamed Clarity to Clarity2012 in Process 3 and both spellings
        // are still in files in the wild.
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
        // Old split toning, superseded by Color Grading but still emitted by
        // every preset saved before Lightroom 4.
        "SplitToningShadowHue" to emptyList(),
        "SplitToningShadowSaturation" to emptyList(),
        "SplitToningHighlightHue" to emptyList(),
        "SplitToningHighlightSaturation" to emptyList(),
        "SplitToningBalance" to emptyList(),
        // The old continuous grayscale, superseded by the boolean switch.
        "GrayscaleMix" to listOf("GrayscaleMixer"),
        // Calibration, six primary trims.
        "RedHue" to emptyList(),
        "RedSaturation" to emptyList(),
        "GreenHue" to emptyList(),
        "GreenSaturation" to emptyList(),
        "BlueHue" to emptyList(),
        "BlueSaturation" to emptyList(),
        // Vignette shape (now real controls).
        "PostCropVignetteRoundness" to emptyList(),
        "PostCropVignetteAspect" to emptyList(),
        // Detail noise.
        "LuminanceSmoothing" to emptyList(),
        "ColorNoiseReduction" to emptyList(),
        // Defringe.
        "DefringePurpleAmount" to emptyList(),
        "DefringePurpleHueLo" to emptyList(),
        "DefringePurpleHueHi" to emptyList(),
        "DefringeGreenAmount" to emptyList(),
        "DefringeGreenHueLo" to emptyList(),
        "DefringeGreenHueHi" to emptyList(),
        // Optics / geometry (now real controls).
        "LensProfileEnable" to emptyList(),
        "LensManualDistortionAmount" to emptyList(),
        "AutoLateralCA" to emptyList(),
        "PerspectiveVertical" to emptyList(),
        "PerspectiveHorizontal" to emptyList(),
        "PerspectiveRotate" to emptyList(),
        "PerspectiveScale" to emptyList(),
        "PerspectiveAspect" to emptyList(),
        "PerspectiveX" to emptyList(),
        "PerspectiveY" to emptyList(),
        "PerspectiveUpright" to emptyList(),
        // New color grading + B&W mixer families.
        "ColorGradeMidtoneHue" to emptyList(),
        "ColorGradeMidtoneSat" to emptyList(),
        "ColorGradeShadowHue" to emptyList(),
        "ColorGradeShadowSat" to emptyList(),
        "ColorGradeHighlightHue" to emptyList(),
        "ColorGradeHighlightSat" to emptyList(),
        "ColorGradeBlending" to emptyList(),
        "ColorGradeBalance" to emptyList(),
        "GrayMixerRed" to emptyList(),
        "GrayMixerOrange" to emptyList(),
        "GrayMixerYellow" to emptyList(),
        "GrayMixerGreen" to emptyList(),
        "GrayMixerAqua" to emptyList(),
        "GrayMixerBlue" to emptyList(),
        "GrayMixerPurple" to emptyList(),
        "GrayMixerMagenta" to emptyList(),
    )

    /**
     * Resolves a canonical key to the one actually present in [a], or null.
     *
     * Returns the matched name too, because the report has to name the key the
     * file used, not the one we wished it had used. A preset that says
     * `SharpenEdgeMasking` and gets a masking slider is a success, but a report
     * claiming `Masking` was applied would be a lie about the file.
     *
     * [consumed] records what was matched. A file carrying BOTH spellings is
     * possible and then the loser is never visited by any block, so without
     * this it fell through to the unrecognised-key loop and was reported as
     * having no Lab equivalent - which is the exact thing the alias table
     * exists to prevent.
     */
    private fun resolve(
        a: Map<String, String>,
        canonical: String,
        consumed: MutableSet<String>,
    ): Pair<String, String>? {
        for (name in listOf(canonical) + ALIASES[canonical].orEmpty()) {
            val v = a[name] ?: continue
            consumed += name
            return name to v
        }
        return null
    }

    /**
     * Every spelling of every setting, canonical and alias.
     *
     * A file may carry two spellings of one setting. We apply one of them and
     * have to account for the other, and "accounted for" is not the same as
     * "unsupported": it was honoured, under the other name.
     */
    private val ALL_SPELLINGS: Set<String> =
        (ALIASES.keys + ALIASES.values.flatten()).toSet()

    /** All locals, so two imports cannot bleed into each other. */
    fun parse(xmp: String): XmpResult {
        val a = readAttributes(xmp)
        val keys = mutableListOf<XmpKey>()
        // Which spellings were actually read, so the inventory pass below can
        // tell "we never heard of this key" from "we heard of it, under another
        // name in the same file".
        val consumed = mutableSetOf<String>()

        var gamma = 1f
        var contrast = 1f
        var saturation = 1f
        var vibrance = 0f
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
        var vigRound = 0.5f
        var vigAspect = 0.5f
        var vignette = 0f
        var denoiseLum = 0f
        var denoiseColor = 0f

        // Old split toning, whose hue/saturation pairs become two colours.
        var shadowHue = 0f
        var shadowSat = 0f
        var highlightHue = 0f
        var highlightSat = 0f
        var splitShadowTint = LabRecipe.DEFAULT_SHADOW_TINT
        var splitHighlightTint = LabRecipe.DEFAULT_HIGHLIGHT_TINT

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
            val (name, raw) = resolve(a, canonical, consumed) ?: return
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

        // ---- saturation and vibrance, now separate controls. Vibrance protects
        // skin tones (low-sat boost), saturation is uniform.
        knob("Saturation", "SATURATION") { _, v -> saturation *= 1f + v / 100f }
        knob("Vibrance", "VIBRANCE") { _, v -> vibrance = (v / 100f).coerceIn(-1f, 1f) }
        saturation = saturation.coerceIn(0f, 3f)

        // ---- sharpness -> the sharpen effect ----
        knob("Sharpness", "SHARPEN") { _, v -> sharpen = (v / 100f).coerceIn(0f, 1f) }

        // ---- colour temperature -> warmth ----
        // Kelvin is absolute and warmth is a -1..1 opinion, so the pivot is
        // Adobe's own 5500K reference and the scale is logarithmic: 2000K to
        // 5000K is one step to a person, 5000K to 50000K is barely one.
        val tempResolved = resolve(a, "Temperature", consumed)
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

        // ---- Parametric curve: the same curve by a different description ----
        // Adobe stores four amounts and three splits instead of the shape, so a
        // preset that uses it has no ToneCurvePV2012 at all. Folded into the
        // composite run, which the shader already samples, so this costs
        // nothing on the GPU.
        val parAmounts = arrayOfNulls<Float>(4)
        listOf(
            "ParametricShadows" to 0,
            "ParametricDarks" to 1,
            "ParametricLights" to 2,
            "ParametricHighlights" to 3,
        ).forEach { (key, slot) ->
            val label = "TONE CURVE " + key.removePrefix("Parametric").uppercase()
            knob(key, label, approx = true) { _, v -> parAmounts[slot] = v }
        }
        val parSplits = arrayOfNulls<Float>(3)
        listOf(
            "ParametricShadowSplit" to 0,
            "ParametricMidtoneSplit" to 1,
            "ParametricHighlightSplit" to 2,
        ).forEach { (key, slot) ->
            val label = "TONE CURVE " + key.removePrefix("Parametric").uppercase()
            knob(key, label, approx = true) { _, v -> parSplits[slot] = v }
        }
        val parametric = ToneCurve.foldParametric(
            shadows = parAmounts[0] ?: 0f,
            darks = parAmounts[1] ?: 0f,
            lights = parAmounts[2] ?: 0f,
            highlights = parAmounts[3] ?: 0f,
            shadowSplit = parSplits[0] ?: 50f,
            midtoneSplit = parSplits[1] ?: 50f,
            highlightSplit = parSplits[2] ?: 50f,
        )
        if (parametric != null) {
            // A file that somehow has both: the explicit points are the real
            // curve, so they win, and the parametric fold is dropped rather than
            // applied on top of something it was only ever an alternative to.
            if (curves[0] == null) {
                curves[0] = parametric
                anyCurve = true
            } else {
                keys += XmpKey(
                    "Parametric*", "(7 keys)", Fidelity.EXACT,
                    mapsTo = "not applied: this preset also carries a ToneCurvePV2012",
                )
            }
        }

        // ---- Calibration: six primary trims, plus a shadow tint ----
        //
        // A 3x3 on the primaries, and this is the one place a matrix would be
        // the right answer rather than a loss. The SATURATION half is exactly a
        // diagonal scale, which is what warmth/tint already is, so those three
        // keys fold in perfectly. The HUE half is a per-primary rotation and has
        // no diagonal representation at all, so it needs a matrix of its own.
        //
        // Both halves are done rather than approximated into the wrong knob: a
        // preset that rotates its reds without touching its blues is the whole
        // point of a calibration panel, and folding a hue rotation into a
        // per-channel scale produces a colour nobody asked for.
        val calHue = floatArrayOf(0f, 0f, 0f)
        val calSat = floatArrayOf(0f, 0f, 0f)
        val calNames = listOf("Red", "Green", "Blue")
        calNames.forEachIndexed { ch, name ->
            knob("${name}Hue", "CALIBRATION ${name.uppercase()} HUE", approx = true) { _, v ->
                calHue[ch] = (v / 100f).coerceIn(-1f, 1f)
            }
            knob("${name}Saturation", "CALIBRATION ${name.uppercase()} SAT", approx = true) { _, v ->
                calSat[ch] = (v / 100f).coerceIn(-1f, 1f)
            }
        }
        val calibrationField = Calibration.encode(calHue, calSat)

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

        // ---- Adobe's Color Mixer: 8 bands x (hue, saturation, luminance) ----
        // Twenty-four keys, and the largest single group in a real preset after
        // the ones that were never being read at all. Each is a plain -100..100
        // remap, so all twenty-four are exact.
        val hsl = FloatArray(Hsl.VALUES)
        var anyHsl = false
        for (band in 0 until Hsl.BANDS) {
            val suffix = Hsl.XMP_SUFFIXES[band]
            listOf(
                "HueAdjustment$suffix" to Hsl.hueAt(band),
                "SaturationAdjustment$suffix" to Hsl.satAt(band),
                "LuminanceAdjustment$suffix" to Hsl.lumAt(band),
            ).forEach { (key, slot) ->
                knob(key, "${Hsl.BAND_NAMES[band]} ${kindOf(key)}") { _, v ->
                    hsl[slot] = (v / 100f).coerceIn(-1f, 1f)
                    anyHsl = true
                }
            }
        }

        // ---- grayscale: a real switch, and the only colour key that removes
        // colour outright rather than shifting it ----
        var grayscale = 0f
        resolve(a, "ConvertToGrayscale", consumed)?.let { (name, raw) ->
            val on = when (raw.trim()) {
                "True", "true", "1" -> 1f
                "False", "false", "0" -> 0f
                else -> null
            }
            if (on == null) {
                drop(name, "not a boolean")
            } else if (on == 0f) {
                neutral(name, raw)
            } else {
                grayscale = 1f
                add(name, raw, "GRAYSCALE")
            }
        }
        // GrayscaleMix* is the old, continuous form of the same switch. A file
        // carrying both has said the same thing twice and we apply it once.
        if (grayscale == 0f) {
            knob("GrayscaleMix", "GRAYSCALE", approx = true) { _, v ->
                grayscale = (v / 100f).coerceIn(0f, 1f)
            }
        }

        // ---- old split toning: five keys, and the Lab already has the three
        // controls they map onto ----
        var splitAmount = 0f
        knob("SplitToningBalance", "SPLIT BALANCE", approx = true) { _, v ->
            // Balance is 0..100 with 50 neutral, and it moves where the two
            // tints meet rather than how strong they are. The Lab's tints are
            // mirrored about mid grey, so balance maps onto how much of each is
            // applied, which is the closest thing here.
            val b = (v / 100f).coerceIn(0f, 1f)
            splitAmount = kotlin.math.abs(b - 0.5f) * 2f
        }
        knob("SplitToningShadowHue", "SHADOW TINT HUE", approx = true) { _, v ->
            shadowHue = (v / 100f).coerceIn(0f, 1f)
        }
        knob("SplitToningShadowSaturation", "SHADOW TINT SAT", approx = true) { _, v ->
            shadowSat = (v / 100f).coerceIn(0f, 1f)
        }
        knob("SplitToningHighlightHue", "HIGHLIGHT TINT HUE", approx = true) { _, v ->
            highlightHue = (v / 100f).coerceIn(0f, 1f)
        }
        knob("SplitToningHighlightSaturation", "HIGHLIGHT TINT SAT", approx = true) { _, v ->
            highlightSat = (v / 100f).coerceIn(0f, 1f)
        }
        // The old keys carry hue and saturation; the Lab carries a colour. The
        // conversion is a guess about which colour was meant, so it is
        // approximate, and only fires when the preset actually asked for it.
        if (shadowSat > 0f) {
            val c = Hsl.hslToRgb(shadowHue * 360f, shadowSat, 0.5f)
            splitShadowTint = packRgb(c[0], c[1], c[2])
        }
        if (highlightSat > 0f) {
            val c = Hsl.hslToRgb(highlightHue * 360f, highlightSat, 0.5f)
            splitHighlightTint = packRgb(c[0], c[1], c[2])
        }

        // ---- Vignette shape (now real controls, not dropped) ----
        knob("PostCropVignetteRoundness", "VIGNETTE ROUNDNESS", approx = true) { _, v ->
            vigRound = ((v + 100f) / 200f).coerceIn(0f, 1f)
        }
        knob("PostCropVignetteAspect", "VIGNETTE ASPECT", approx = true) { _, v ->
            vigAspect = ((v + 100f) / 200f).coerceIn(0f, 1f)
        }

        // ---- Noise reduction: edge-masked luminance + chroma smoothing.
        // Honest approx of neighbourhood stats with one shared blur; flat areas
        // smooth, edges preserved.
        knob("LuminanceSmoothing", "NOISE LUMINANCE") { _, v ->
            denoiseLum = (v / 100f).coerceIn(0f, 1f)
        }
        knob("ColorNoiseReduction", "NOISE COLOR") { _, v ->
            denoiseColor = (v / 100f).coerceIn(0f, 1f)
        }

        // ---- Defringe: hue-masked desat, purple + green.
        val def = FloatArray(6).apply {
            val d = Defringe.defaults(); for (i in 0 until 6) this[i] = d[i]
        }
        var anyDef = false
        knob("DefringePurpleAmount", "DEFRINGE PURPLE") { _, v -> def[0] = (v / 100f).coerceIn(0f, 1f); anyDef = true }
        knob("DefringePurpleHueLo", "DEFRINGE PURPLE LO", approx = true) { _, v -> def[1] = (v / 360f).coerceIn(0f, 1f); anyDef = true }
        knob("DefringePurpleHueHi", "DEFRINGE PURPLE HI", approx = true) { _, v -> def[2] = (v / 360f).coerceIn(0f, 1f); anyDef = true }
        knob("DefringeGreenAmount", "DEFRINGE GREEN") { _, v -> def[3] = (v / 100f).coerceIn(0f, 1f); anyDef = true }
        knob("DefringeGreenHueLo", "DEFRINGE GREEN LO", approx = true) { _, v -> def[4] = (v / 360f).coerceIn(0f, 1f); anyDef = true }
        knob("DefringeGreenHueHi", "DEFRINGE GREEN HI", approx = true) { _, v -> def[5] = (v / 360f).coerceIn(0f, 1f); anyDef = true }
        var lensCAFlag = 0f
        resolve(a, "AutoLateralCA", consumed)?.let { (name, raw) ->
            when (raw.trim()) {
                "True", "true", "1" -> { lensCAFlag = 1f; add(name, raw, "REMOVE CA") }
                "False", "false", "0" -> neutral(name, raw)
                else -> drop(name, "not a boolean")
            }
        }

        // ---- Optics: lens corrections + manual distort + lens blur hooks.
        // Lens blur amount itself has no XMP key (AI depth blur); manual distort does.
        var lensEnableFlag = 0f
        var lensDistortVal = 0f
        resolve(a, "LensProfileEnable", consumed)?.let { (name, raw) ->
            when (raw.trim()) {
                "True", "true", "1" -> { lensEnableFlag = 1f; add(name, raw, "LENS CORRECTIONS") }
                "False", "false", "0" -> neutral(name, raw)
                else -> drop(name, "not a boolean")
            }
        }
        knob("LensManualDistortionAmount", "LENS DISTORTION", approx = true) { _, v ->
            lensDistortVal = (v / 100f).coerceIn(-1f, 1f)
            if (lensEnableFlag == 0f) lensEnableFlag = 1f
        }

        // ---- Geometry: Upright mode + 7 manual sliders. Auto/Guided/Level are
        // presets that set the sliders (no scene analysis on device).
        val geo = FloatArray(8)
        var anyGeo = false
        resolve(a, "PerspectiveUpright", consumed)?.let { (name, raw) ->
            val mode = when (raw.trim()) {
                "0", "Off", "off" -> 0; "1", "Auto", "auto" -> 1
                "2", "Guided", "guided" -> 2; "3", "Level", "level" -> 3
                "4", "Vertical", "vertical" -> 4; "5", "Full", "full" -> 5
                else -> null
            }
            if (mode == null) drop(name, "unknown upright mode")
            else {
                geo[0] = mode.toFloat(); anyGeo = true
                add(name, raw, "GEOMETRY ${Geometry.MODE_NAMES[mode]}", approx = mode != 0)
            }
        }
        knob("PerspectiveVertical", "GEO VERTICAL") { _, v -> geo[1] = (v / 100f).coerceIn(-1f, 1f); anyGeo = true }
        knob("PerspectiveHorizontal", "GEO HORIZONTAL") { _, v -> geo[2] = (v / 100f).coerceIn(-1f, 1f); anyGeo = true }
        knob("PerspectiveRotate", "GEO ROTATE", approx = true) { _, v -> geo[3] = (v / 30f).coerceIn(-1f, 1f); anyGeo = true }
        knob("PerspectiveAspect", "GEO ASPECT", approx = true) { _, v -> geo[4] = (v / 100f).coerceIn(-1f, 1f); anyGeo = true }
        knob("PerspectiveScale", "GEO SCALE", approx = true, isNeutralAtZero = 100f) { _, v ->
            geo[5] = ((v - 100f) / 100f).coerceIn(0f, 1f); anyGeo = true
        }
        knob("PerspectiveX", "GEO X") { _, v -> geo[6] = (v / 100f).coerceIn(-1f, 1f); anyGeo = true }
        knob("PerspectiveY", "GEO Y") { _, v -> geo[7] = (v / 100f).coerceIn(-1f, 1f); anyGeo = true }

        // ---- Color Grading 3-way (new wheels, supersedes split toning).
        val grade = ColorGrade.defaults()
        var anyGrade = false
        knob("ColorGradeShadowHue", "GRADE SHADOW HUE", approx = true) { _, v -> grade[0] = (v / 360f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeShadowSat", "GRADE SHADOW SAT") { _, v -> grade[1] = (v / 100f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeMidtoneHue", "GRADE MID HUE", approx = true) { _, v -> grade[2] = (v / 360f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeMidtoneSat", "GRADE MID SAT") { _, v -> grade[3] = (v / 100f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeHighlightHue", "GRADE HIGH HUE", approx = true) { _, v -> grade[4] = (v / 360f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeHighlightSat", "GRADE HIGH SAT") { _, v -> grade[5] = (v / 100f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeBlending", "GRADE BLEND", approx = true) { _, v -> grade[6] = (v / 100f).coerceIn(0f, 1f); anyGrade = true }
        knob("ColorGradeBalance", "GRADE BALANCE", approx = true) { _, v -> grade[7] = (v / 100f).coerceIn(-1f, 1f); anyGrade = true }

        // ---- B&W mixer (8 bands, only applies when grayscale on).
        val bw = FloatArray(8)
        var anyBw = false
        listOf("Red" to 0, "Orange" to 1, "Yellow" to 2, "Green" to 3, "Aqua" to 4, "Blue" to 5, "Purple" to 6, "Magenta" to 7).forEach { (suf, slot) ->
            knob("GrayMixer$suf", "B&W $suf".uppercase()) { _, v -> bw[slot] = (v / 100f).coerceIn(-1f, 1f); anyBw = true }
        }

        // ---- ShadowTint: the calibration panel's shadow bias ----
        //
        // A green/magenta shift applied to the shadows only, which is what the
        // Lab's split tone already is. The Lab's shadow tint is a colour and
        // Adobe's is a signed green/magenta amount, so this maps onto the
        // existing `shadowTint` rather than needing a new control.
        knob("ShadowTint", "SHADOW TINT", approx = true) { _, v ->
            val amt = (v / 100f).coerceIn(-1f, 1f)
            // Positive is green, negative magenta, matching Adobe's direction.
            splitShadowTint = if (amt >= 0f) {
                packRgb(0.5f - 0.5f * amt * 0.3f, 0.5f + 0.5f * amt * 0.3f, 0.5f)
            } else {
                val m = -amt * 0.3f
                packRgb(0.5f + 0.5f * m, 0.5f - 0.5f * m, 0.5f)
            }
            if (splitAmount == 0f) splitAmount = kotlin.math.abs(amt)
        }

        // ---- everything else, reported rather than silently dropped ----
        for (k in listOf("AutoBrightness", "Auto Tone")) drop(k, "needs a scene analysis")

        drop("Look", "an enum naming a curve set; no honest mapping")

        // Anything else in the file is listed too, so the report is a real
        // inventory of the preset rather than a fixed list with holes in it.
        // What decides the tier now is [notALook], not "we have never heard of
        // it": an unrecognised key that is plainly a look setting is a genuine
        // gap and is scored as one.
        val seen = keys.map { it.key }.toSet()
        for ((k, v) in a) {
            if (k in seen || k in METADATA) continue
            when {
                k in NOT_A_LOOK_EXACT || NOT_A_LOOK_PREFIXES.any { k.startsWith(it) } ->
                    keys += XmpKey(k, v, Fidelity.NOT_A_LOOK, reason = "not a look setting")
                // A second spelling of a setting we already applied. It was
                // honoured, under the other name, so it is neutral rather than
                // unsupported - and saying otherwise is what made a preset with
                // both VignetteAmount and PostCropVignetteAmount report one of
                // them as unimplemented.
                k in ALL_SPELLINGS ->
                    keys += XmpKey(k, v, Fidelity.EXACT, mapsTo = "neutral (duplicate spelling)")
                else ->
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
                vigRound = vigRound,
                vigAspect = vigAspect,
                hsl = if (anyHsl) Hsl.encode(hsl) else Hsl.NONE,
                grayscale = grayscale,
                calibration = calibrationField,
                vibrance = vibrance,
                colorGrade = if (anyGrade) ColorGrade.encode(grade) else ColorGrade.NONE,
                bwMix = if (anyBw) BwMix.encode(bw) else BwMix.NONE,
                denoiseLum = denoiseLum,
                denoiseColor = denoiseColor,
                defringe = if (anyDef || lensCAFlag > 0f) Defringe.encode(def) else Defringe.NONE,
                lensCA = lensCAFlag,
                lensEnable = lensEnableFlag,
                lensDistort = lensDistortVal,
                lensBlur = 0f,
                lensFocus = 0.5f,
                geometry = if (anyGeo) Geometry.encode(geo) else Geometry.NONE,
                splitAmount = splitAmount,
                shadowTint = splitShadowTint,
                highlightTint = splitHighlightTint,
                vignette = vignette,
            ),
            keys = keys,
        )
    }
}
