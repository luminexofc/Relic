package com.retrocam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings + per-filter intensity memory. Favorites arrive in Phase 2. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsDataStore

    val soundEnabled: Flow<Boolean> = store.data.map { it[Keys.SOUND] ?: true }
    val gridOverlay: Flow<Boolean> = store.data.map { it[Keys.GRID] ?: false }
    val performanceMode: Flow<Boolean> = store.data.map { it[Keys.PERF] ?: false }
    val paperTheme: Flow<Boolean> = store.data.map { it[Keys.PAPER] ?: false }
    val favorites: Flow<Set<String>> = store.data.map { it[Keys.FAVORITES] ?: emptySet() }
    val stripHintSeen: Flow<Boolean> = store.data.map { it[Keys.HINT] ?: false }
    val timerSeconds: Flow<Int> = store.data.map { it[Keys.TIMER] ?: 0 }
    val viewAspect: Flow<Int> = store.data.map { it[Keys.VIEW_ASPECT] ?: 0 } // 0 4:3, 1 16:9, 2 1:1
    val fileFormat: Flow<String> = store.data.map { it[Keys.FORMAT] ?: "JPEG" }
    val filterPreview: Flow<Boolean> = store.data.map { it[Keys.PREVIEW] ?: true }
    val photoCard: Flow<Boolean> = store.data.map { it[Keys.CARD] ?: false }
    val mirrorFront: Flow<Boolean> = store.data.map { it[Keys.MIRROR] ?: true }
    /** MediaStore base folder: "Pictures" or "DCIM" (shots live in <base>/RetroCam). */
    val saveFolder: Flow<String> = store.data.map { it[Keys.FOLDER] ?: "Pictures" }

    /**
     * Full MediaStore relative dir, e.g. "Pictures/RetroCam".
     * Migrates the old Pictures/DCIM toggle on first read.
     */
    val saveDir: Flow<String> = store.data.map { prefs ->
        prefs[Keys.DIR] ?: ((prefs[Keys.FOLDER] ?: "Pictures") + "/RetroCam")
    }

    fun intensityFor(filterId: String, default: Float = 0.75f): Flow<Float> =
        store.data.map { it[floatPreferencesKey("intensity_$filterId")] ?: default }

    /** Effect-size multiplier (1 = filter default). Only meaningful when param1 > 0. */
    fun sizeFor(filterId: String): Flow<Float> =
        store.data.map { it[floatPreferencesKey("size_$filterId")] ?: 1f }

    suspend fun setSoundEnabled(value: Boolean) {
        store.edit { it[Keys.SOUND] = value }
    }

    suspend fun setGridOverlay(value: Boolean) {
        store.edit { it[Keys.GRID] = value }
    }

    suspend fun setPaperTheme(value: Boolean) {
        store.edit { it[Keys.PAPER] = value }
    }

    suspend fun setTimerSeconds(value: Int) {
        store.edit { it[Keys.TIMER] = value }
    }

    suspend fun setViewAspect(value: Int) {
        store.edit { it[Keys.VIEW_ASPECT] = value }
    }

    suspend fun setFileFormat(value: String) {
        store.edit { it[Keys.FORMAT] = value }
    }

    suspend fun setSaveFolder(value: String) {
        store.edit { it[Keys.FOLDER] = if (value == "DCIM") "DCIM" else "Pictures" }
    }

    /**
     * Stores a free-form save dir. Normalized to "<Base>/sub/..." where Base is
     * one of Pictures/DCIM/Movies (MediaStore rejects anything else); "..",
     * blank segments and surrounding slashes are stripped. Blank input is
     * ignored so the setting can never be wiped.
     */
    suspend fun setSaveDir(raw: String) {
        val clean = normalizeSaveDir(raw) ?: return
        store.edit {
            it[Keys.DIR] = clean
            it[Keys.FOLDER] = clean.substringBefore("/")
        }
    }

    suspend fun setMirrorFront(value: Boolean) {
        store.edit { it[Keys.MIRROR] = value }
    }

    suspend fun setPhotoCard(value: Boolean) {
        store.edit { it[Keys.CARD] = value }
    }

    suspend fun setFilterPreview(value: Boolean) {
        store.edit { it[Keys.PREVIEW] = value }
    }

    suspend fun setStripHintSeen() {
        store.edit { it[Keys.HINT] = true }
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }

    suspend fun toggleFavorite(filterId: String) {
        store.edit {
            val current = it[Keys.FAVORITES] ?: emptySet()
            it[Keys.FAVORITES] = if (filterId in current) current - filterId else current + filterId
        }
    }

    suspend fun setIntensity(filterId: String, value: Float) {
        store.edit { it[floatPreferencesKey("intensity_$filterId")] = value }
    }

    suspend fun setSize(filterId: String, value: Float) {
        store.edit { it[floatPreferencesKey("size_$filterId")] = value }
    }

    /** Detail (param2) multiplier. Only meaningful when param2 > 0. */
    fun detailFor(filterId: String): Flow<Float> =
        store.data.map { it[floatPreferencesKey("detail_$filterId")] ?: 1f }

    suspend fun setDetail(filterId: String, value: Float) {
        store.edit { it[floatPreferencesKey("detail_$filterId")] = value }
    }

    /**
     * Saved Filter Lab recipes as opaque encoded strings, newest last.
     *
     * Deliberately untyped: this module must not depend on :catalog, and the
     * recipe codec belongs there. Callers in :app decode with
     * `RecipeCodec`, merge and re-encode, so the QR path and the stored path
     * are the same format by construction rather than by convention.
     */
    val customRecipes: Flow<List<String>> = store.data.map { prefs ->
        (prefs[Keys.CUSTOM_RECIPES] ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
    }

    suspend fun setCustomRecipes(encoded: List<String>) {
        store.edit { it[Keys.CUSTOM_RECIPES] = encoded.joinToString("\n") }
    }

    private object Keys {
        val SOUND = booleanPreferencesKey("sound_enabled")
        val GRID = booleanPreferencesKey("grid_overlay")
        val PERF = booleanPreferencesKey("performance_mode")
        val PAPER = booleanPreferencesKey("paper_theme")
        val FAVORITES = stringSetPreferencesKey("favorites")
        val HINT = booleanPreferencesKey("strip_hint_seen")
        val TIMER = intPreferencesKey("timer_seconds")
        val VIEW_ASPECT = intPreferencesKey("view_aspect")
        val FORMAT = stringPreferencesKey("file_format")
        val PREVIEW = booleanPreferencesKey("filter_preview")
        val FOLDER = stringPreferencesKey("save_folder")
        val DIR = stringPreferencesKey("save_dir")
        val MIRROR = booleanPreferencesKey("mirror_front")
        val CARD = booleanPreferencesKey("photo_card")
        val CUSTOM_RECIPES = stringPreferencesKey("custom_recipes")
    }

    companion object {
        private val ALLOWED_BASES = setOf("Pictures", "DCIM", "Movies")

        /** Returns the normalized dir, or null when [raw] carries nothing usable. */
        fun normalizeSaveDir(raw: String): String? {
            val parts = raw.replace('\\', '/').split("/")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { it.filter { c -> c.isLetterOrDigit() || c in " _-()" } }
                .filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val base = parts[0].replaceFirstChar { it.uppercase() }
            val rest = parts.drop(1)
            val withBase = if (base in ALLOWED_BASES) listOf(base) + rest else listOf("Pictures") + parts
            return withBase.joinToString("/").take(120)
        }
    }
}
