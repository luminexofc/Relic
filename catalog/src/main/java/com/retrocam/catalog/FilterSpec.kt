package com.retrocam.catalog

enum class FilterFamily { DITHER, PRINT, HARDWARE, STYLIZE, DISTORT }

/**
 * A filter is pure data: GLSL + tuning. Adding a filter = one body string +
 * one catalog entry. No new classes (see Architecture.md §2.1).
 *
 * @param fragmentBody GLSL defining `vec4 applyFilter(vec4 src, vec2 uv)`.
 *        May use shared uniforms/header helpers from [Shaders.HEADER].
 * @param param1 filter-specific knob (meaning documented per entry).
 * @param param2 second filter-specific knob.
 * @param palette optional 16-color LUT uploaded as `u_palette` (pixel art).
 */
data class FilterSpec(
    val id: String,
    val displayName: String,
    val family: FilterFamily,
    val fragmentBody: String,
    val param1: Float,
    val param2: Float,
    /** Third tuning knob (brightness/contrast/saturation needs three). */
    val param3: Float = 0f,
    val palette: IntArray? = null,
    val defaultIntensity: Float = 0.75f,
    /** True for filters needing frame-to-frame state (FBO ping-pong). */
    val temporal: Boolean = false,
    /**
     * Search terms describing what the filter looks like, so searching
     * "old film" finds VINTAGE instead of only matching display names.
     */
    val context: String = "",
)

/**
 * Name + context search. Every whitespace-separated token must appear
 * somewhere ("old film" needs both), and hyphens/underscores are treated as
 * spaces so "x-ray", "x ray" and "xray" all hit XRAY.
 */
fun FilterSpec.matches(rawQuery: String): Boolean {
    val tokens = rawQuery.trim().lowercase().replace('-', ' ').replace('_', ' ')
        .split(' ', '\t', '\n').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    val haystack = (displayName + " " + context).lowercase().replace('-', ' ').replace('_', ' ')
    return tokens.all { haystack.contains(it) }
}
