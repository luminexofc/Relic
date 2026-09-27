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
                append(lab.duotoneHighlight)
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

    /** Bumped when the field list changes. v2 added the six effect amounts and the duotone pair. */
    const val VERSION = 2

    private const val SEP = ","
    private const val FIELD_COUNT = 17

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
        ).joinToString(SEP)
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
