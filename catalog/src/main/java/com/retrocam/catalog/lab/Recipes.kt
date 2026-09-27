package com.retrocam.catalog.lab

import com.retrocam.catalog.FilterCatalog
import com.retrocam.catalog.FilterFamily
import com.retrocam.catalog.FilterSpec
import java.util.Base64

/**
 * A recipe the user has actually saved: a name, the base filter it layers on,
 * and the grade.
 *
 * Distinct from [LabRecipe], which is just the grade. This is the shareable
 * unit — it is what gets persisted, encoded into a QR code, and turned back into
 * a strip entry.
 */
data class SavedRecipe(
    /** Content hash, so re-importing the same recipe updates it instead of duplicating. */
    val id: String,
    val name: String,
    /** Id of the catalog filter this layers on top of. */
    val baseId: String,
    val lab: LabRecipe,
) {
    /**
     * The strip entry for this recipe: the base filter with the lab grade
     * attached, re-identified and re-labelled.
     *
     * Returns null when [baseId] names a filter that no longer exists, which is
     * a real possibility for a shared recipe pointing at a filter we have since
     * removed. Callers should skip those rather than fail.
     */
    fun toSpec(): FilterSpec? {
        val base = FilterCatalog.byId[baseId] ?: return null
        return base.copy(
            id = id,
            displayName = name,
            family = FilterFamily.LAB,
            context = listOfNotNull(
                "my filter lab recipe",
                lab.templateId?.lowercase()?.replace('_', ' '),
                lab.adjustments.describe().takeIf { it != "no grading" },
                lab.activeEffects().takeIf { it.isNotEmpty() }?.joinToString(" "),
                lab.lutId?.removePrefix("lut_")?.take(6),
                lab.lutId?.removePrefix("lut_")?.take(6),
                "over ${base.displayName}",
            ).joinToString(", "),
            lab = lab,
        )
    }

    companion object {
        const val MAX_NAME = 18

        /**
         * Build a saveable recipe. The name is trimmed and length-capped so a
         * strip full of "MY FILTER 3" duplicates is at least bounded, and the id
         * is derived from the content so saving twice is idempotent.
         */
        fun create(name: String, baseId: String, lab: LabRecipe): SavedRecipe {
            val clean = name.trim().uppercase().take(MAX_NAME).ifBlank { "UNTITLED" }
            val clamped = LabRecipe.coerce(lab)
            val id = contentId(clean, baseId, clamped)
            return SavedRecipe(id, clean, baseId, clamped)
        }

        /**
         * FNV-1a over every field, so identical content always yields the same id
         * (importing the same QR twice is a no-op) while anything different gets
         * its own entry.
         */
        fun contentId(name: String, baseId: String, lab: LabRecipe): String {
            val a = lab.adjustments
            val canonical = buildString {
                append(RecipeCodec.VERSION).append('|').append(name).append('|').append(baseId).append('|')
                append(lab.templateId ?: "-").append('|')
                append(RecipeCodec.q(a.brightness)).append(',')
                append(RecipeCodec.q(a.contrast)).append(',')
                append(RecipeCodec.q(a.saturation)).append(',')
                append(RecipeCodec.q(a.warmth)).append(',')
                append(RecipeCodec.q(a.tint)).append('|')
                append(RecipeCodec.q(lab.vignette)).append(',')
                append(RecipeCodec.q(lab.grain)).append(',')
                append(RecipeCodec.q(lab.sharpen)).append(',')
                append(RecipeCodec.q(lab.blur)).append(',')
                append(RecipeCodec.q(lab.glitch)).append(',')
                append(RecipeCodec.q(lab.duotone)).append(',')
                append(lab.duotoneShadow).append(',')
                append(lab.duotoneHighlight).append('|')
                append(lab.lutId ?: "-").append(',')
                append(RecipeCodec.q(lab.lutAmount)).append(',')
                append(RecipeCodec.q(lab.gamma)).append(',')
                append(RecipeCodec.q(lab.splitAmount)).append(',')
                append(lab.shadowTint).append(',')
                append(lab.highlightTint).append('|')
                append(lab.stampText ?: "-").append('|')
                append(lab.stampColor).append('|')
                append(lab.stampPosition.ordinal).append('|')
                append(RecipeCodec.q(lab.stampAlpha)).append('|')
                append(lab.watermarkId ?: "-").append('|')
                append(RecipeCodec.q(lab.watermarkAlpha)).append('|')
                append(lab.watermarkPosition.ordinal).append('|')
            append(lab.toneCurves).append('|')
            append(RecipeCodec.q(lab.highlights)).append(',')
            append(RecipeCodec.q(lab.shadows)).append(',')
            append(RecipeCodec.q(lab.whites)).append(',')
            append(lab.blacks).append('|')
            append(lab.stagesClamped().joinToString("!") { st ->
                st.primitiveId + '~' + RecipeCodec.q(st.amountClamped) + '~' +
                    st.maskClamped.toString() + '~' +
                    st.params.entries.sortedBy { it.key }
                        .joinToString(",") { "${it.key}=${RecipeCodec.q(it.value)}" }
            })
            }
            return ID_PREFIX + fnv1a(canonical).toString(36)
        }

        private const val ID_PREFIX = "lab_"

        private fun fnv1a(s: String): ULong {
            var h = 14695981039346656037uL
            for (c in s) {
                h = h xor c.code.toULong()
                h *= 1099511628211uL
            }
            return h
        }
    }
}

/**
 * Serialises a [SavedRecipe] to a short, URL-safe, deterministic string.
 *
 * One format serves all three consumers — DataStore, the QR code and the
 * scanner — so saving, sharing and restoring are the same code path rather than
 * three that can drift apart.
 *
 * Layout: `1,name,baseId,templateId,b,c,s,w,t`, comma separated.
 *
 * The separator is a comma, not a dot, because the five numbers are decimal and
 * a dot separator splits `1.2` into two fields. Only the name is free text, so
 * only it is base64url encoded; the two ids come from closed sets and the
 * numbers are quantised. Between them, no field can contain a comma, so the
 * split is unambiguous.
 */
object RecipeCodec {

    /** Bumped when the field list changes. v2 effects, v3 LUT pair, v4 overlays. */
    const val VERSION = 8

    private const val SEP = ","
    private const val FIELD_COUNT = 37

    private val b64 get() = Base64.getUrlEncoder().withoutPadding()
    private val unb64 get() = Base64.getUrlDecoder()

    fun encode(r: SavedRecipe): String {
        val lab = LabRecipe.coerce(r.lab)
        val a = lab.adjustments
        return listOf(
            VERSION.toString(),
            b64.encodeToString(r.name.uppercase().toByteArray()),
            r.baseId,
            lab.templateId ?: "-",
            q(a.brightness), q(a.contrast), q(a.saturation), q(a.warmth), q(a.tint),
            q(lab.vignette), q(lab.grain), q(lab.sharpen), q(lab.blur), q(lab.glitch), q(lab.duotone),
            r.lab.duotoneShadow.toString(), r.lab.duotoneHighlight.toString(),
            r.lab.lutId ?: "-", q(lab.lutAmount),
            q(lab.gamma), q(lab.splitAmount),
            lab.shadowTint.toString(), lab.highlightTint.toString(),
            r.lab.stampText?.let { b64.encodeToString(it.toByteArray()) } ?: "-",
            r.lab.stampColor.toString(),
            r.lab.stampPosition.ordinal.toString(),
            q(lab.stampAlpha),
            r.lab.watermarkId ?: "-",
            q(lab.watermarkAlpha),
            r.lab.watermarkPosition.ordinal.toString(),
            // Reserved slot, so the next version can append a field without
            // reinterpreting every payload already out there.
            "0",
            encodeStages(lab.stages),
            // Tone curve last, because it is the only field big enough to matter
            // for payload size and it should not push the fixed fields around.
            lab.toneCurves,
            q(lab.highlights), q(lab.shadows), q(lab.whites), q(lab.blacks),
        ).joinToString(SEP)
    }

    /**
     * The stage chain as a single comma-free field.
     *
     * A stage is `id~amount~shape~x~y~w~h~feather~p1-p2-p3` and stages are joined
     * with `!`. Tilde and bang are both absent from every other field, so this
     * cannot break the comma split, and a variable-length list stays one field
     * rather than blowing up [FIELD_COUNT] by forty.
     *
     * Params are written as `paramNumber=value` joined by `+` rather than
     * positionally. Positional encoding looks shorter and is ambiguous: a stage
     * that sets only param 2 would decode that value onto param 1, because
     * `controls[0]` is the param-1 knob of some filters and the param-2 knob of
     * others. Naming the number costs six characters and is never wrong.
     */
    private fun encodeStages(stages: List<LabStage>): String {
        if (stages.isEmpty()) return "-"
        return stages.take(LabRecipe.MAX_STAGES).joinToString("!") { st ->
            val m = st.maskClamped
            val p = st.params
            val params = p.entries.sortedBy { it.key }
                .joinToString("+") { "${it.key}=${q(it.value)}" }
            listOf(
                st.primitiveId,
                q(st.amountClamped),
                m.shape.ordinal.toString(),
                q(m.x), q(m.y), q(m.width), q(m.height), q(m.feather),
                params,
            ).joinToString("~")
        }
    }

    /**
     * Inverse of [encodeStages]. A malformed stage is dropped rather than failing
     * the whole payload: a shared recipe with one bad stage should still restore
     * its grade, LUT and overlays, since those are the parts a person would miss.
     */
    private fun decodeStages(raw: String): List<LabStage> {
        if (raw == "-") return emptyList()
        return raw.split('!').take(LabRecipe.MAX_STAGES).mapNotNull { tok ->
            val f = tok.split('~')
            if (f.size != 9) return@mapNotNull null
            val prim = LabPrimitives.byId(f[0]) ?: return@mapNotNull null
            if (!prim.chainable) return@mapNotNull null
            val shape = MaskShape.entries.getOrNull(f[2].toIntOrNull() ?: 0)
                ?: return@mapNotNull null
            val params = buildMap {
                if (f[8].isNotEmpty()) {
                    f[8].split('+').forEach { kv ->
                        val k = kv.substringBefore('=')
                        // Only a real u_paramN slot, so a hand-edited payload
                        // cannot invent a fourth knob.
                        if (k.toIntOrNull() !in 1..3) return@forEach
                        val n = kv.substringAfter('=', "").toFloatOrNull() ?: return@forEach
                        put(k, n)
                    }
                }
            }
            runCatching {
                LabStage(
                    primitiveId = prim.id,
                    amount = f[1].toFloat(),
                    params = params,
                    mask = LabMask(
                        shape = shape,
                        x = f[3].toFloat(), y = f[4].toFloat(),
                        width = f[5].toFloat(), height = f[6].toFloat(),
                        feather = f[7].toFloat(),
                    ),
                )
            }.getOrNull()
        }
    }

    /** Returns null for anything malformed, so a bad scan can never crash the app. */
    fun decode(raw: String): SavedRecipe? {
        val parts = raw.trim().split(SEP)
        if (parts.size != FIELD_COUNT) return null
        if (parts[0] != VERSION.toString()) return null
        return try {
            val name = String(unb64.decode(parts[1])).uppercase()
            if (name.isBlank() || name.length > SavedRecipe.MAX_NAME) return null
            val lab = LabRecipe.coerce(
                LabRecipe(
                    templateId = parts[3].takeIf { it != "-" },
                    adjustments = LabAdjustments(
                        brightness = parts[4].toFloat(),
                        contrast = parts[5].toFloat(),
                        saturation = parts[6].toFloat(),
                        warmth = parts[7].toFloat(),
                        tint = parts[8].toFloat(),
                    ),
                    vignette = parts[9].toFloat(),
                    grain = parts[10].toFloat(),
                    sharpen = parts[11].toFloat(),
                    blur = parts[12].toFloat(),
                    glitch = parts[13].toFloat(),
                    duotone = parts[14].toFloat(),
                    duotoneShadow = parts[15].toInt(),
                    duotoneHighlight = parts[16].toInt(),
                    lutId = parts[17].takeIf { it != "-" },
                    lutAmount = parts[18].toFloat(),
                    // Free text again, so it gets the same base64 treatment as the
                    // recipe name rather than being trusted to avoid separators.
                    gamma = parts[19].toFloat(),
                    splitAmount = parts[20].toFloat(),
                    shadowTint = parts[21].toInt(),
                    highlightTint = parts[22].toInt(),
                    stampText = parts[23].takeIf { it != "-" }?.let { String(unb64.decode(it)) },
                    stampColor = parts[24].toInt(),
                    stampPosition = StampPosition.entries.getOrElse(parts[25].toInt()) {
                        StampPosition.BOTTOM_RIGHT
                    },
                    stampAlpha = parts[26].toFloat(),
                    watermarkId = parts[27].takeIf { it != "-" },
                    watermarkAlpha = parts[28].toFloat(),
                    watermarkPosition = StampPosition.entries.getOrElse(parts[29].toInt()) {
                        StampPosition.BOTTOM_RIGHT
                    },
                    // Unused today, reserved so a future field can be appended
                    // without reinterpreting every existing payload.
                    _reserved = parts[30],
                    stages = decodeStages(parts[31]),
                    toneCurves = parts[32].takeIf { it != ToneCurve.NONE } ?: ToneCurve.NONE,
                    highlights = parts[33].toFloat(),
                    shadows = parts[34].toFloat(),
                    whites = parts[35].toFloat(),
                    blacks = parts[36].toFloat(),
                ),
            )
            SavedRecipe(
                id = SavedRecipe.contentId(name, parts[2], lab),
                name = name,
                baseId = parts[2],
                lab = lab,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Quantise a knob to 4dp and drop a trailing `.0`.
     *
     * Determinism is the point: the content id is hashed over this output, so
     * `0.30000001` and `0.3` must encode identically or re-saving a recipe would
     * quietly mint a second copy of it.
     */
    fun q(v: Float): String {
        val r = Math.round(v * 10000f) / 10000f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
}

/** Short human summary for the recipe's search context. */
private fun LabAdjustments.describe(): String {
    val moved = mutableListOf<String>()
    if (saturation != 1f) moved += "sat " + RecipeCodec.q(saturation)
    if (contrast != 1f) moved += "con " + RecipeCodec.q(contrast)
    if (brightness != 0f) moved += "bri " + RecipeCodec.q(brightness)
    if (warmth != 0f) moved += "warm " + RecipeCodec.q(warmth)
    if (tint != 0f) moved += "tint " + RecipeCodec.q(tint)
    return if (moved.isEmpty()) "no grading" else moved.joinToString(" ")
}
