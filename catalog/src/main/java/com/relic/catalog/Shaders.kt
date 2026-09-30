package com.relic.catalog

/** Shared GLSL header/footer. Every program ends with the global intensity mix. */
object Shaders {

    // u_texTransform is the COMBINED matrix built on the CPU each draw:
    // mirror * translate(coverCenter) * scale(cover) * translate(-center) *
    // producerTransform. So one multiply maps frame coords to the exact buffer
    // texel, rotation included. Filters work in frame space (vFrameCoord), and
    // sampleSrc maps back through this matrix.
    const val VERTEX_PASSTHROUGH = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vFrameCoord;
        varying vec2 vTexCoord;
        uniform mat4 u_texTransform;
        void main() {
            gl_Position = aPosition;
            // Frame space: 0..1 across the destination, so pixel-space filter
            // math (uv * u_resolution) stays square at any aspect ratio.
            vFrameCoord = aTexCoord;
            vTexCoord = (u_texTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    const val HEADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        precision highp int;
        varying vec2 vFrameCoord;
        varying vec2 vTexCoord;
        uniform mat4 u_texTransform;
        uniform samplerExternalOES u_texture;
        uniform sampler2D u_feedback;
        uniform vec2 u_resolution;
        uniform sampler2D u_glyphAtlas;
        uniform vec2 u_glyphAtlasSize;  // atlas size in px
        uniform float u_glyphCell;      // glyph cell size in px
        uniform float u_glyphCount;     // characters in the ramp
        uniform float u_intensity;
        uniform float u_time;
        uniform float u_param1;
        uniform float u_param2;
        uniform float u_param3;
        uniform float u_theme;
        uniform vec3 u_palette[16];

        // Filter Lab grade (LabGrading.toUniforms). Rows are uploaded as three
        // vec3s rather than a mat3 so there is no column-major transpose to get
        // wrong; offset is already divided by 255.
        uniform vec3 u_ccmR0;
        uniform vec3 u_ccmR1;
        uniform vec3 u_ccmR2;
        uniform vec3 u_ccmOffset;

        // Filter Lab effect stages. All zero means off, so the branches are free.
        uniform float u_vignette;
        /** Adobe PostCropVignetteMidpoint, where the falloff starts. 0..1 */
        uniform float u_vigMid;
        /** Adobe PostCropVignetteFeather, how soft the falloff is. 0..1 */
        uniform float u_vigFeather;
        uniform float u_grain;
        uniform float u_sharpen;
        /** Adobe SharpnessRadius, as a multiple of a texel. 0.5 .. 3.0 */
        uniform float u_sharpRadius;
        /** Adobe Detail, a second finer unsharp, 0..1. */
        uniform float u_detail;
        /** Adobe Masking, a threshold on the local difference, 0..1. */
        uniform float u_masking;
        /** Adobe GrainSize, the noise cell scale. */
        uniform float u_grainSize;
        /** Adobe GrainRoughness, 0 smooth .. 1 blocky. */
        uniform float u_grainRough;
        uniform float u_blur;
        uniform float u_glitch;
        uniform float u_duotone;
        uniform vec3 u_duoShadow;
        uniform vec3 u_duoHighlight;

        // Adobe's Calibration: a 3x3 on the primaries. Rows rather than a mat3,
        // for the same reason as u_ccmR0: no column-major transpose to get wrong.
        uniform vec3 u_calR0;
        uniform vec3 u_calR1;
        uniform vec3 u_calR2;
        /** 0 unless a calibration is set, which is what makes the shader skip it. */
        uniform float u_calActive;

        // Filter Lab colour correction that a 4x5 matrix cannot express: a power
        // curve and a luminance-keyed split tone.
        // Area mask. Shape 0 is FULL and must evaluate to exactly 1.0, because the
        // footer multiplies every filter's intensity by this: any other value would
        // quietly dim all 41 built-in filters.
        uniform vec4 u_maskRect;   // x0, y0, x1, y1 in frame uv
        uniform float u_maskShape; // 0 full, 1 rect, 2 ellipse, 3 band h, 4 band v
        uniform float u_maskFeather;

        // Adobe's application order forces the grading knobs apart. Contrast is
        // applied second, before the range and local-contrast work, while
        // temp/tint and saturation come after it, so they cannot share one
        // matrix. u_ccm* now carries the template alone.
        uniform float u_contrast;
        uniform float u_brightness;   // 0-1, pre-divided from 0-255
        uniform float u_rScale;       // warmth
        uniform float u_gScale;       // tint
        uniform float u_bScale;       // warmth
        uniform float u_saturation;
        /** Vibrance, -1..1. Low-sat boost that protects skin tones. */
        uniform float u_vibrance;

        /** Color Grading 3-way: (shHue,shSat,midHue,midSat) + (hiHue,hiSat,blend,balance). */
        uniform vec4 u_gradeA;
        uniform vec4 u_gradeB;
        uniform float u_gradeActive;

        /** B&W mixer, 8 bands. Only applies when u_grayscale is on. */
        uniform vec4 u_bwMixA;
        uniform vec4 u_bwMixB;
        uniform float u_bwActive;

        /** Vignette shape. 0.5 neutral for both. */
        uniform float u_vigRound;
        uniform float u_vigAspect;

        /** Noise reduction, 0..1 each. Shares one 9-tap blur. */
        uniform float u_nrLum;
        uniform float u_nrColor;

        /** Defringe: (purpleAmt,purpleLo,purpleHi,greenAmt) + (greenLo,greenHi) + active flag. */
        uniform vec4 u_defringeA;
        uniform vec4 u_defringeB;
        uniform float u_defringeActive;

        /** Optics: CA toggle, lens enable, manual distort, lens blur + focus. */
        uniform float u_lensCA;
        uniform float u_lensEnable;
        uniform float u_lensDistort;
        uniform float u_lensBlur;
        uniform float u_lensFocus;

        /** Geometry: (mode,vert,horiz,rotate) + (aspect,scale,x,y) + active. */
        uniform vec4 u_geoA;
        uniform vec4 u_geoB;
        uniform float u_geoActive;

        // Adobe's 1D tone curve: a 256x1 texture holding four curves in its
        // channels, R composite, G red, B green, A blue. Any channel the preset
        // does not set is packed as the identity ramp, so all four are applied
        // unconditionally and an absent curve costs nothing.
        uniform sampler2D u_curve;
        uniform float u_curveAmount;

        /** Highlights, Shadows, Whites, Blacks, each -1..1. */
        uniform vec4 u_ranges;

        /** Texture, Clarity, Dehaze, each -1..1. */
        uniform vec3 u_local;

        /**
         * Adobe's Grayscale switch, 0..1. A mix toward luma, which is NOT the
         * same as Saturation at -100: that one is `mix(lum, c, s)` on the same
         * weights, so it goes to full luma too, but Adobe's B&W mixes channels
         * with their own coefficients. This is the same mix, kept separate so
         * the two controls are not the same knob twice.
         */
        uniform float u_grayscale;

        // Adobe's Color Mixer: eight hue bands, each a hue rotation, a
        // saturation scale and a luminance scale, in -1..1. u_hslActive is 0
        // unless a band is off neutral, so the step costs nothing for a recipe
        // that does not use it. Mirrored by Hsl in Kotlin, which is the
        // testable copy and which HslShaderTest checks against this file.
        //
        // vec3, not vec4: the recipe holds exactly 24 values and the upload is
        // one glUniform3fv of count 8. A vec4 here would read 32 floats out of
        // a 24-float array on every frame the mixer is on, which crashed the
        // app as soon as any mixer slider moved.
        uniform vec3 u_hsl[8];
        uniform float u_hslActive;

        // One HSL hue channel, t in turns, 0..1 wrapping. Declared before
        // hslToRgb because GLSL requires definition before use.
        float channelOf(float t, float p, float q) {
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
            if (t < 0.5) return q;
            if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
            return p;
        }

        // RGB to HSL, hue in degrees 0..360. Hue is meaningless with no
        // saturation, so it is reported as 0 rather than divided by zero: a grey
        // pixel has no band preference, and a rotation of 0 is a no-op.
        vec3 rgbToHsl(vec3 c) {
            float mx = max(c.r, max(c.g, c.b));
            float mn = min(c.r, min(c.g, c.b));
            float d = mx - mn;
            float l = (mx + mn) * 0.5;
            if (d == 0.0) return vec3(0.0, 0.0, l);
            float s = (l > 0.5) ? d / (2.0 - mx - mn) : d / (mx + mn);
            float h;
            if (mx == c.r)      h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
            else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
            else                h = (c.r - c.g) / d + 4.0;
            return vec3(h * 60.0, s, l);
        }

        vec3 hslToRgb(vec3 hsl) {
            float s = clamp(hsl.y, 0.0, 1.0);
            float l = clamp(hsl.z, 0.0, 1.0);
            if (s == 0.0) return vec3(l);
            float q = (l < 0.5) ? l * (1.0 + s) : l + s - l * s;
            float p = 2.0 * l - q;
            float hk = fract(hsl.x / 360.0);
            return vec3(
                channelOf(fract(hk + 1.0 / 3.0), p, q),
                channelOf(hk, p, q),
                channelOf(fract(hk - 1.0 / 3.0), p, q)
            );
        }

        // Band centres, in the order Hsl.CENTRES: red 0, orange 30, yellow 60,
        // green 120, aqua 180, blue 225, purple 270, magenta 315. Uneven on
        // purpose - these are Adobe's, and evenly spacing them would put green
        // at 135 where green does not live. Reach is half the largest gap
        // between centres, so every hue is inside at least one band.
        float hslCentre(int band) {
            if (band == 0) return 0.0;
            if (band == 1) return 30.0;
            if (band == 2) return 60.0;
            if (band == 3) return 120.0;
            if (band == 4) return 180.0;
            if (band == 5) return 225.0;
            if (band == 6) return 270.0;
            return 315.0;
        }

        /**
         * Band weight for a hue, before normalisation: a raised cosine 30
         * degrees either side of the band centre. The caller divides by the
         * total, which is what turns Adobe's uneven spacing into a blend
         * rather than a gap, and which means there is no edge on the colour
         * wheel to show as a seam across a sky gradient.
         */
        float hslWeight(float hueDeg, int band) {
            float d = abs(mod(hueDeg - hslCentre(band) + 540.0, 360.0) - 180.0);
            if (d >= 35.0) return 0.0;
            return 0.5 * (1.0 + cos(3.14159265 * d / 35.0));
        }

        /**
         * Adobe's Color Mixer over one pixel. The band is chosen from the
         * ORIGINAL hue, so a rotation cannot walk a pixel into the next band's
         * adjustment as it moves.
         */
        vec3 hslBands(vec3 c, vec3 b[8]) {
            vec3 hsl = rgbToHsl(c);
            vec3 adj = vec3(0.0);
            float total = 0.0;
            for (int i = 0; i < 8; i++) {
                float w = hslWeight(hsl.x, i);
                adj += b[i] * w;
                total += w;
            }
            if (total > 0.0) adj /= total;
            return hslToRgb(vec3(
                // 100 degrees at full scale, matching Hsl.HUE_DEGREES. This was
                // 3.6 once, which is a nudge rather than a rotation.
                hsl.x + adj.x * 100.0,
                hsl.y * (1.0 + adj.y),
                hsl.z * (1.0 + adj.z)
            ));
        }

        // B&W band weight: same centres as the mixer, so the mixer UI teaches
        // the B&W UI for free.
        float bwWeight(float hueDeg, int band) { return hslWeight(hueDeg, band); }

        // Hue inside [lo,hi] in turns (0..1, wraps). For defringe ranges.
        float hueInRange(float h, float lo, float hi) {
            if (lo <= hi) return step(lo, h) * (1.0 - step(hi, h));
            return step(lo, h) + (1.0 - step(hi, h));
        }

        // Geometry warp on frame coords. Mode selects which corrections apply;
        // sliders are manual in every mode (Auto/Guided/Level are presets that
        // set them, no scene analysis on device).
        vec2 geoWarp(vec2 uv, vec4 A, vec4 B) {
            float mode = A.x;
            float vert = A.y;
            float horiz = A.z;
            float rot = A.w; // -1..1 maps to -30..30deg
            float aspect = B.x; // -1..1 maps to 0.5..2
            float scale = B.y; // 0..1 maps to 0.5..1.5
            float ox = B.z;
            float oy = B.w;
            vec2 p = uv - 0.5;
            // Aspect + rotate + scale around centre.
            float aScale = 1.0 + aspect * 0.75;
            p.x *= aScale;
            float ang = rot * 0.5236;
            float ca = cos(ang);
            float sa = sin(ang);
            p = mat2(ca, -sa, sa, ca) * p;
            float sc = 0.5 + scale;
            // Vertical mode corrects vertical keystone only; Full both axes.
            float vv = vert;
            float hh = horiz;
            if (mode < 3.5 && mode > 0.5) {
                // Auto/Guided/Level: apply as stored (presets set them).
            }
            if (abs(mode - 4.0) < 0.5) { hh = 0.0; }
            // Keystone as division (perspective, not affine).
            float w = 1.0 + vv * p.y * 1.5 + hh * p.x * 1.5;
            w = max(w, 0.2);
            p = p / w;
            p = p / max(sc, 0.2) + vec2(ox * 0.5, oy * 0.5);
            return p + 0.5;
        }

        // Barrel/pincushion distortion around centre. d -1..1.
        vec2 lensDistort(vec2 uv, float d) {
            vec2 p = uv - 0.5;
            float r2 = dot(p, p);
            return uv + p * r2 * d * 2.0;
        }

        uniform float u_gamma;
        uniform float u_splitAmount;
        uniform vec3 u_shadowTint;
        uniform vec3 u_highlightTint;

        // Filter Lab overlays: the 1990s date stamp and a watermark logo. Each is
        // a pre-rasterised bitmap; the rect comes from OverlayPlacement.rect().
        // A zero-width rect means "no overlay".
        uniform sampler2D u_stampTex;
        uniform vec4 u_stampRect;
        uniform float u_stampAlpha;
        uniform sampler2D u_markTex;
        uniform vec4 u_markRect;
        uniform float u_markAlpha;

        // All filter code works in FRAME space (0..1 across the destination, so
        // uv * u_resolution is real square pixels). sampleSrc maps any frame
        // coordinate back to the source texture through the combined matrix
        // (producer rotation + cover-crop + mirror), so neighbour and block
        // sampling rotate exactly with the current pixel.
        vec4 sampleSrc(vec2 frame) {
            return texture2D(u_texture, (u_texTransform * vec4(frame, 0.0, 1.0)).xy);
        }

        float hash(float n) { return fract(sin(n) * 43758.5453); }

        // Sine-free 2D hash (stable on mediump mobile GPUs).
        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float luminance(vec3 color) { return dot(color, vec3(0.299, 0.587, 0.114)); }

        vec3 posterizeR(vec3 color, float levels) {
            float n = max(levels, 2.0);
            return floor(color * (n - 1.0) + 0.5) / (n - 1.0);
        }

        // Exact Bayer-4 (M4 rows: 0 8 2 10 / 12 4 14 6 / 3 11 1 9 / 15 7 13 5).
        float bayer4(vec2 p) {
            float x = mod(p.x, 4.0);
            float y = mod(p.y, 4.0);
            if (y < 0.5) {
                if (x < 0.5) return 0.0;
                if (x < 1.5) return 8.0;
                if (x < 2.5) return 2.0;
                return 10.0;
            }
            if (y < 1.5) {
                if (x < 0.5) return 12.0;
                if (x < 1.5) return 4.0;
                if (x < 2.5) return 14.0;
                return 6.0;
            }
            if (y < 2.5) {
                if (x < 0.5) return 3.0;
                if (x < 1.5) return 11.0;
                if (x < 2.5) return 1.0;
                return 9.0;
            }
            if (x < 0.5) return 15.0;
            if (x < 1.5) return 7.0;
            if (x < 2.5) return 13.0;
            return 5.0;
        }

        float bayer2(vec2 p) {
            vec2 q = mod(floor(p), 2.0);
            if (q.y < 0.5) { return q.x < 0.5 ? 0.0 : 0.5; }
            return q.x < 0.5 ? 0.75 : 0.25;
        }

        // Block sampler. The block grid is defined in DESTINATION pixels
        // (square cells on screen) and mapped back to source UV for sampling.
        // grid = blocks across the long edge (u_param1 style), mult = subdivision.
        // Returns the block-center color and the dither coord grid.
        vec3 sampleBlock(vec2 uv, float grid, float mult, out vec2 ditherCoord) {
            float longEdge = max(u_resolution.x, u_resolution.y);
            float cell = max(1.0, longEdge / (max(1.0, grid) * max(1.0, mult)));
            vec2 px = uv * u_resolution;
            vec2 centerPx = clamp((floor(px / cell) + 0.5) * cell, vec2(0.0), u_resolution);
            float blockCell = max(1.0, longEdge / max(1.0, grid) / max(1.0, mult));
            ditherCoord = floor(px / blockCell);
            return sampleSrc(centerPx / u_resolution).rgb;
        }
        // A 9-tap tent at spacing [s], the same weights the blur effect uses.
        vec3 tent3(vec3 c, vec2 s) {
            vec3 sum = c * 4.0;
            sum += sampleSrc(vFrameCoord + vec2(-s.x, -s.y)).rgb;
            sum += sampleSrc(vFrameCoord + vec2( 0.0, -s.y)).rgb * 2.0;
            sum += sampleSrc(vFrameCoord + vec2( s.x, -s.y)).rgb;
            sum += sampleSrc(vFrameCoord + vec2(-s.x,  0.0)).rgb * 2.0;
            sum += sampleSrc(vFrameCoord + vec2( s.x,  0.0)).rgb * 2.0;
            sum += sampleSrc(vFrameCoord + vec2(-s.x,  s.y)).rgb;
            sum += sampleSrc(vFrameCoord + vec2( 0.0,  s.y)).rgb * 2.0;
            sum += sampleSrc(vFrameCoord + vec2( s.x,  s.y)).rgb;
            return sum / 16.0;
        }

        // The same smoothstep the Kotlin RangeTone.smooth01 implements.
        float smooth01(float x) {
            float t = clamp(x, 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        // Band weight for one of the four range controls at luminance l. Band
        // order is Highlights, Shadows, Whites, Blacks, matching RangeTone in
        // Kotlin. That object is the testable copy of this arithmetic and a
        // test reads these exact expressions out of this file to check it.
        float rangeWeight(int band, float l) {
            if (band == 0) return 1.0 - smooth01((l - 0.05) / 0.50);
            if (band == 1) return 1.0 - smooth01(l / 0.28);
            if (band == 2) return smooth01((l - 0.45) / 0.50);
            return smooth01((l - 0.72) / 0.28);
        }

        // One band on one channel. Positive lifts, negative rolls off.
        float rangeAdjust(float c, float w, float a) {
            return a >= 0.0 ? c + a * w * (1.0 - c) : c * (1.0 + a * w);
        }

        // How much of this stage's effect applies at uv: 1 inside the mask, 0
        // outside, feathered in between. Frame y grows upward, which the band
        // shortcuts rely on. Shape 0 returns a literal 1.0 so the multiply in the
        float maskFactor(vec2 uv) {
        // for any filter that is not being masked.
        if (u_maskShape < 0.5) return 1.0;
        vec2 c = (u_maskRect.xy + u_maskRect.zw) * 0.5;
        vec2 h = (u_maskRect.zw - u_maskRect.xy) * 0.5;
        if (h.x <= 0.0 || h.y <= 0.0) return 1.0;

        if (u_maskShape < 1.5) {
        // Rect: distance to the box, normalised by the half-extent.
        vec2 d = abs(uv - c) - h;
        float outside = length(max(d, 0.0));
        return clamp(1.0 - outside / max(u_maskFeather, 1e-4), 0.0, 1.0);
        }
        if (u_maskShape < 2.5) {
        // Ellipse: normalised radius, aspect handled by the half-extent.
        vec2 d = (uv - c) / h;
        return clamp(1.0 - (length(d) - 1.0) / max(u_maskFeather, 1e-4), 0.0, 1.0);
        }
        // Bands: a strip across the frame. h is unused, so the band is defined by
        // the rect's thickness rather than its position, which is what makes a
        // "horizon" style effect usable.
        if (u_maskShape < 3.5) {
        float t = abs(uv.y - c.y) / h.y;
        return clamp(1.0 - (t - 1.0) / max(u_maskFeather, 1e-4), 0.0, 1.0);
        }
        float t = abs(uv.x - c.x) / h.x;
        return clamp(1.0 - (t - 1.0) / max(u_maskFeather, 1e-4), 0.0, 1.0);
        }

    """

    const val FOOTER = """
        void main() {
            vec4 src = sampleSrc(vFrameCoord);
            float m = maskFactor(vFrameCoord);
            gl_FragColor = mix(src, applyFilter(src, vFrameCoord), u_intensity * m);
        }
    """

    fun fragmentFor(body: String): String = "$HEADER\n$body\n$FOOTER"

    /** Bypass: output equals input (the "Original" strip entry). */
    const val PLAIN = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            return src;
        }
    """

    /** Variant whose input is a regular 2D texture (for composer chains). */
    fun fragmentFor2D(body: String): String =
        fragmentFor(body).replace(
            "uniform samplerExternalOES u_texture;",
            "uniform sampler2D u_texture;",
        )

    // param1 = block px (destination pixels, so cells stay square on screen)
    const val PIXELATED = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 grid = u_resolution / u_param1;
            vec2 uv2 = (floor(uv * grid) + 0.5) / grid;
            return sampleSrc(uv2);
        }
    """

    // Reference Dither: block posterize + Bayer-shifted threshold.
    // param1 = grid blocks across long edge (default 160), param2 = levels (default 5).
    const val DITHER = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 3.0, ditherCoord);
            float levels = max(u_param2, 2.0);
            float stepSize = 1.0 / (levels - 1.0);
            float threshold = (bayer4(ditherCoord) / 16.0 - 0.5) * stepSize;
            color = clamp(posterizeR(color + vec3(threshold), levels), 0.0, 1.0);
            return vec4(color, 1.0);
        }
    """

    // Reference Ink: 1-bit Bayer threshold, paper/ink by theme.
    const val INK = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 3.0, ditherCoord);
            float threshold = bayer4(ditherCoord) / 16.0 - 0.5;
            float ink = step(0.5, luminance(color) + threshold);
            return vec4(vec3(mix(ink, 1.0 - ink, u_theme)), 1.0);
        }
    """

    // Reference Gray: rounded grayscale quantization over grid blocks.
    const val GRAY = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 1.0, ditherCoord);
            float gray = luminance(color);
            float levels = max(u_param2, 2.0);
            float q = floor(gray * (levels - 1.0) + 0.5) / (levels - 1.0);
            return vec4(vec3(q), 1.0);
        }
    """

    // Reference Color: rounded per-channel quantization over grid blocks.
    const val COLOR = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 1.0, ditherCoord);
            return vec4(posterizeR(color, max(u_param2, 2.0)), 1.0);
        }
    """

    // Reference Retro8Bit: EGA cube + brightness boost (exact thresholds).
    /**
     * 8-bit console look: chunky pixels, ordered dither, then a hard 5-level
     * quantise. Contrast is applied BEFORE quantising so every pixel still
     * lands on a flat palette step — the dither is what makes it read as an
     * early console frame instead of a flat colour wash.
     * param1 = block count across the long edge, param2 = dither amplitude.
     */
    const val BIT8 = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 ditherCoord;
            vec3 c = sampleBlock(uv, u_param1, 1.0, ditherCoord);
            c = clamp((c - 0.5) * 1.12 + 0.5, 0.0, 1.0);
            float b = (bayer4(ditherCoord) / 16.0 - 0.5) * u_param2;
            vec3 q = floor((c + b) * 4.0 + 0.5) / 4.0;
            return vec4(clamp(q, 0.0, 1.0), 1.0);
        }
    """

    // Reference BayerMatrix: 1-bit, threshold amplitude scaled by levels.
    // param1 = grid (default 160), param2 = levels (default 5).
    const val BAYER = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 3.0, ditherCoord);
            float threshold = (bayer4(ditherCoord) / 16.0 - 0.5) / max(2.0, u_param2);
            float v = step(0.5, luminance(color) + threshold);
            return vec4(vec3(v), 1.0);
        }
    """

    // Reference ColorHalftone: multiplicative CMY dots with rotated-screen offsets.
    // param1 = grid (default 160).
    const val HALFTONE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 screenUv = gl_FragCoord.xy / u_resolution;
            vec2 ditherCoord;
            vec3 color = sampleBlock(uv, u_param1, 1.0, ditherCoord);
            float longEdge = max(u_resolution.x, u_resolution.y);
            float blockCell = max(1.0, longEdge / max(1.0, u_param1));
            float cellSize = max(10.0, blockCell * 0.88);
            vec2 hcell = fract(uv * u_resolution / cellSize) - 0.5;
            float cyan = 1.0 - color.r;
            float magenta = 1.0 - color.g;
            float yellow = 1.0 - color.b;
            float cDot = 1.0 - step(cyan * 0.46, length(hcell + vec2(-0.13, 0.08)));
            float mDot = 1.0 - step(magenta * 0.46, length(hcell + vec2(0.12, -0.10)));
            float yDot = 1.0 - step(yellow * 0.42, length(hcell));
            vec3 paper = vec3(0.96);
            vec3 result = paper;
            result *= mix(vec3(1.0), vec3(0.0, 0.847, 0.914), cDot);
            result *= mix(vec3(1.0), vec3(0.95, 0.0, 0.82), mDot);
            result *= mix(vec3(1.0), vec3(0.98, 0.92, 0.0), yDot);
            return vec4(clamp(result, 0.0, 1.0), 1.0);
        }
    """

    // param1 = pixel px (u_palette required)
    const val PIXEL_ART = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 grid = u_resolution / max(u_param1, 2.0);
            vec2 uv2 = (floor(uv * grid) + 0.5) / grid;
            vec3 c = sampleSrc(uv2).rgb;
            float best = 1000.0;
            vec3 outc = c;
            for (int i = 0; i < 16; i++) {
                float d = distance(c, u_palette[i]);
                if (d < best) { best = d; outc = u_palette[i]; }
            }
            return vec4(outc, 1.0);
        }
    """

    // ASCII art: real glyphs from a monospace atlas (built in FilterRenderer).
    // Bright pixels pick denser characters along the ramp. param1 = cells
    // across the long edge. Theme selects ink/paper.
    const val ASCII = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float longEdge = max(u_resolution.x, u_resolution.y);
            float cell = max(3.0, longEdge / max(u_param1, 8.0));
            vec2 px = uv * u_resolution;
            vec2 cellId = floor(px / cell);
            // Box-average the cell. A single tap per pixel aliases hard at this
            // density and the glyph field reads as noise instead of tone.
            vec2 s0 = (cellId * cell) / u_resolution;
            vec2 so = cell / u_resolution;
            vec3 avg = (
                sampleSrc(s0 + so * vec2(0.25, 0.25)) +
                sampleSrc(s0 + so * vec2(0.75, 0.25)) +
                sampleSrc(s0 + so * vec2(0.25, 0.75)) +
                sampleSrc(s0 + so * vec2(0.75, 0.75))).rgb * 0.25;
            float l = clamp(luminance(avg), 0.0, 1.0);
            // Gentle S-curve so the ramp spans the full tonal range.
            l = clamp((l - 0.5) * 1.15 + 0.5, 0.0, 1.0);
            float idx = floor(l * (u_glyphCount - 1.0) + 0.5);

            // Glyph box inside the cell, small margin so letters stay legible
            // when the cell is only ~11px.
            vec2 local = px - cellId * cell;
            float pad = max(0.5, floor(cell * 0.08));
            vec2 avail = max(vec2(1.0), cell - pad * 2.0);
            vec2 lp = local - pad;
            bool inside = lp.x >= 0.0 && lp.y >= 0.0 && lp.x < avail.x && lp.y < avail.y;
            vec2 g = clamp(floor(lp / avail * u_glyphCell), vec2(0.0), u_glyphCell - vec2(1.0));
            // NEAREST atlas sampled at texel centres: crisp, no glyph bleed.
            vec2 auv = vec2(
                (idx * u_glyphCell + g.x + 0.5) / u_glyphAtlasSize.x,
                (g.y + 0.5) / u_glyphAtlasSize.y);
            float mask = inside ? texture2D(u_glyphAtlas, auv).r : 0.0;
            vec3 ink = mix(vec3(1.0), vec3(0.0), u_theme);
            vec3 paper = mix(vec3(0.0), vec3(1.0), u_theme);
            return vec4(mix(paper, ink, mask), 1.0);
        }
    """

    // param1 = levels (rounded quantization, reference-correct).
    const val POSTERIZE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float n = max(u_param1, 2.0);
            return vec4(floor(src.rgb * (n - 1.0) + 0.5) / (n - 1.0), 1.0);
        }
    """

    // Full-res palette quantize through u_palette (EGA 16).
    const val EGA = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float best = 1000.0;
            vec3 outc = src.rgb;
            for (int i = 0; i < 16; i++) {
                float d = distance(src.rgb, u_palette[i]);
                if (d < best) { best = d; outc = u_palette[i]; }
            }
            return vec4(outc, 1.0);
        }
    """

    const val CYANOTYPE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            vec3 col = vec3(0.05, 0.08, 0.15) + l * vec3(0.10, 0.50, 0.85);
            return vec4(col, 1.0);
        }
    """

    const val GAMEBOY = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            l += (bayer4(uv * u_resolution / 4.0) / 16.0 - 0.5) * 0.12;
            vec3 c1 = vec3(0.06, 0.22, 0.06);
            vec3 c2 = vec3(0.19, 0.38, 0.19);
            vec3 c3 = vec3(0.55, 0.67, 0.06);
            vec3 c4 = vec3(0.61, 0.74, 0.06);
            vec3 col = l < 0.25 ? c1 : (l < 0.5 ? c2 : (l < 0.75 ? c3 : c4));
            return vec4(col, 1.0);
        }
    """

    // param1 = edge threshold
    const val EDGE_ONLY = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            vec2 px = 1.0 / u_resolution;
            float tl = dot(sampleSrc(uv + vec2(-px.x, -px.y)).rgb, LUM);
            float t  = dot(sampleSrc(uv + vec2(0.0, -px.y)).rgb, LUM);
            float tr = dot(sampleSrc(uv + vec2(px.x, -px.y)).rgb, LUM);
            float l  = dot(sampleSrc(uv + vec2(-px.x, 0.0)).rgb, LUM);
            float r  = dot(sampleSrc(uv + vec2(px.x, 0.0)).rgb, LUM);
            float bl = dot(sampleSrc(uv + vec2(-px.x, px.y)).rgb, LUM);
            float b  = dot(sampleSrc(uv + vec2(0.0, px.y)).rgb, LUM);
            float br = dot(sampleSrc(uv + vec2(px.x, px.y)).rgb, LUM);
            float sx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
            float sy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
            float mag = length(vec2(sx, sy));
            float ink = smoothstep(u_param1, u_param1 + 0.25, mag);
            return vec4(mix(vec3(0.96), vec3(0.08), ink), 1.0);
        }
    """

    const val EMBOSS = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 px = 1.0 / u_resolution;
            vec3 c = vec3(0.0);
            c += sampleSrc(uv + vec2(-px.x, -px.y)).rgb * -2.0;
            c += sampleSrc(uv + vec2(0.0, -px.y)).rgb * -1.0;
            c += sampleSrc(uv + vec2(-px.x, 0.0)).rgb * -1.0;
            c += sampleSrc(uv).rgb;
            c += sampleSrc(uv + vec2(px.x, 0.0)).rgb;
            c += sampleSrc(uv + vec2(0.0, px.y)).rgb;
            c += sampleSrc(uv + vec2(px.x, px.y)).rgb * 2.0;
            return vec4(c + 0.5, 1.0);
        }
    """

    const val THERMAL = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            vec3 col = mix(vec3(0.0, 0.0, 0.15), vec3(1.0, 0.0, 0.0), smoothstep(0.0, 0.4, l));
            col = mix(col, vec3(1.0, 1.0, 0.0), smoothstep(0.4, 0.7, l));
            col = mix(col, vec3(1.0), smoothstep(0.7, 1.0, l));
            return vec4(col, 1.0);
        }
    """

    /**
     * Heatmap: a smooth scientific colour ramp with isotherm contours, so it
     * reads as plotted data rather than THERMAL's four hard stops. param1 is
     * the contour strength (0 = pure gradient).
     */
    const val HEATMAP = """
        vec3 heatRamp(float t) {
            t = clamp(t, 0.0, 1.0);
            vec3 c0 = vec3(0.04, 0.02, 0.13);
            vec3 c1 = vec3(0.32, 0.06, 0.42);
            vec3 c2 = vec3(0.72, 0.16, 0.30);
            vec3 c3 = vec3(0.96, 0.47, 0.13);
            vec3 c4 = vec3(0.99, 0.92, 0.55);
            if (t < 0.25) return mix(c0, c1, t * 4.0);
            if (t < 0.50) return mix(c1, c2, (t - 0.25) * 4.0);
            if (t < 0.75) return mix(c2, c3, (t - 0.50) * 4.0);
            return mix(c3, c4, (t - 0.75) * 4.0);
        }

        vec4 applyFilter(vec4 src, vec2 uv) {
            float t = luminance(clamp(sampleSrc(uv).rgb, 0.0, 1.0));
            t = clamp((t - 0.5) * 1.25 + 0.5, 0.0, 1.0);
            vec3 c = heatRamp(t);
            // Isotherms: a dark hairline wherever the value crosses a step.
            float f = fract(t * 14.0);
            float line = 1.0 - smoothstep(0.40, 0.50, abs(f - 0.5));
            c *= 1.0 - line * 0.40 * u_param1;
            return vec4(c, 1.0);
        }
    """

    /**
     * X-ray: a continuous-tone radiograph, not line art. The scene inverts so
     * dark areas glow, a wide-radius gradient stands in for density edges
     * (soft, the way film shows structure), and everything lands on a cold
     * blue-black → cyan → hot-white ramp.
     */
    const val XRAY = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = luminance(clamp(sampleSrc(uv).rgb, 0.0, 1.0));
            // Wide radius on purpose: a 1px sobel would read as ENGRAVE.
            vec2 o = clamp(1.5 + u_param1 * 5.0, 1.0, 12.0) / u_resolution;
            float lr = luminance(sampleSrc(uv + vec2( o.x, 0.0)).rgb);
            float ll = luminance(sampleSrc(uv + vec2(-o.x, 0.0)).rgb);
            float lu = luminance(sampleSrc(uv + vec2(0.0,  o.y)).rgb);
            float ld = luminance(sampleSrc(uv + vec2(0.0, -o.y)).rgb);
            float structure = clamp(length(vec2(lr - ll, lu - ld)) * 2.2, 0.0, 1.0);

            float x = 1.0 - l;
            x = pow(x, 0.85) * 0.85 + 0.04;
            x = clamp(x + structure * 0.35, 0.0, 1.0);

            vec3 cold = mix(vec3(0.02, 0.05, 0.10), vec3(0.35, 0.62, 0.85), smoothstep(0.0, 0.55, x));
            vec3 film = mix(cold, vec3(0.93, 0.98, 1.0), smoothstep(0.55, 1.0, x));
            return vec4(film, 1.0);
        }
    """

    // param1 = swirl strength
    const val SWIRL = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float aspect = u_resolution.x / max(u_resolution.y, 1.0);
            vec2 d = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
            float r = length(d);
            float k = clamp(1.0 - r / 0.75, 0.0, 1.0);
            float a = u_param1 * k * k;
            float c = cos(a);
            float s = sin(a);
            vec2 rd = mat2(c, s, -s, c) * d;
            vec2 uv2 = vec2(rd.x / aspect + 0.5, rd.y + 0.5);
            return sampleSrc(clamp(uv2, 0.0, 1.0));
        }
    """

    // param1 = warp factor
    /**
     * Fisheye lens: aspect-corrected barrel warp. The sample radius is
     * pre-divided by the corner expansion so the warped corner still lands on
     * the frame corner — every sample stays inside the texture, so there is
     * nothing to clamp and no edge smear.
     */
    const val FISHEYE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float aspect = u_resolution.x / max(u_resolution.y, 1.0);
            vec2 d = (uv - 0.5) * vec2(aspect, 1.0);
            float r2 = dot(d, d);
            float k = u_param1;
            // Corner radius squared in this aspect-corrected space.
            float r2max = 0.25 * (aspect * aspect + 1.0);
            float m = 1.0 + k * r2max;
            vec2 warped = d * (1.0 + k * r2) / m;
            vec3 c = sampleSrc(warped / vec2(aspect, 1.0) + 0.5).rgb;
            // Lens character: a little contrast, a cool edge falloff.
            c = (c - 0.5) * 1.12 + 0.5;
            c *= 1.0 - 0.45 * r2 / r2max;
            c *= vec3(0.97, 1.0, 1.03);
            return vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /** Soft light: airy matte, lifted floor, highlight bloom, gentle vignette. */
    const val SOFT_LIGHT = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 c = clamp(sampleSrc(uv).rgb, 0.0, 1.0);
            float s = u_param1;
            // Matte curve: pull the ceiling down, lift the floor, flatten.
            c = c * (1.0 - s * 0.28) + s * 0.30;
            // Highlight-only bloom for the hazy glow.
            float hi = smoothstep(0.55, 1.0, luminance(c));
            c += hi * s * 0.22;
            c = mix(vec3(luminance(c)), c, 0.92);
            c *= vec3(1.03, 1.0, 0.96);
            vec2 d = uv - 0.5;
            c *= 1.0 - s * 0.35 * dot(d, d);
            return vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /** Gloomy: cold low-key grade, crushed floor, mist, heavy vignette. */
    const val GLOOMY = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 c = clamp(sampleSrc(uv).rgb, 0.0, 1.0);
            float s = u_param1;
            c = pow(c, vec3(1.0 + s * 0.9)) * 0.82;
            c = mix(c, c * vec3(0.72, 1.0, 0.86), 0.75);
            c = mix(vec3(luminance(c)), c, 0.45);
            c = max(c, vec3(0.02, 0.05, 0.045) * s);
            vec2 d = uv - 0.5;
            c *= 1.0 - s * 1.15 * dot(d, d);
            return vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    /** Vintage: warm sepia, faded matte, film grain, soft vignette. */
    const val VINTAGE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 c = clamp(sampleSrc(uv).rgb, 0.0, 1.0);
            float s = u_param1;
            vec3 sepia = vec3(
                dot(c, vec3(0.393, 0.769, 0.189)),
                dot(c, vec3(0.349, 0.686, 0.168)),
                dot(c, vec3(0.272, 0.534, 0.131)));
            c = mix(c, sepia, 0.72);
            c = c * (1.0 - s * 0.22) + s * 0.20;
            c *= vec3(1.06, 0.99, 0.88);
            float g = hash12(uv * u_resolution * 0.5) - 0.5;
            c += g * s * 0.045;
            vec2 d = uv - 0.5;
            c *= 1.0 - s * 0.42 * dot(d, d);
            return vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    const val CRT = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 d = uv - 0.5;
            // Aspect-correct the radius before using it. In raw UV space a
            // portrait preview has a much smaller uv.x span than uv.y, so an
            // uncorrected r2 is dominated by y: the "curvature" then stretches
            // the picture vertically instead of rounding it, which is what made
            // this look stretched rather than curved on a phone held upright.
            float aspect = u_resolution.x / max(u_resolution.y, 1.0);
            vec2 da = vec2(d.x * aspect, d.y);
            float r2 = dot(da, da);
            // Sample INWARD. Sampling outward and clamping smears the edge row
            // into a band along the top and bottom, which is the other half of
            // the stretching. Pulling inward keeps every lookup inside the
            // frame, so the bulge is a real magnification instead of a smear.
            vec3 c = sampleSrc(clamp(uv - d * r2 * 0.16, 0.0, 1.0)).rgb;
            float scan = 0.82 + 0.18 * sin(uv.y * u_resolution.y * 3.14159);
            float gx = fract(uv.x * u_resolution.x / 3.0);
            vec3 grille = vec3(
                0.85 + 0.15 * step(gx, 0.34),
                0.85 + 0.15 * step(abs(gx - 0.5), 0.17),
                0.85 + 0.15 * step(0.66, gx));
            c *= scan * grille;
            c *= 0.97 + 0.03 * sin(u_time * 120.0);
            c *= clamp(1.15 - r2 * 1.3, 0.25, 1.0);
            return vec4(c, 1.0);
        }
    """

    const val VHS = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float band = floor(uv.y * 160.0);
            float tear = step(0.965, hash12(vec2(band, floor(u_time * 8.0))));
            float shift = tear * 0.10 * (hash12(vec2(1.0, floor(u_time * 8.0))) - 0.5);
            vec2 uvT = uv + vec2(shift, 0.0);
            float r = sampleSrc(clamp(uvT + vec2(0.004, 0.0), 0.0, 1.0)).r;
            float g = sampleSrc(clamp(uvT, 0.0, 1.0)).g;
            float b = sampleSrc(clamp(uvT - vec2(0.004, 0.0), 0.0, 1.0)).b;
            float noise = (hash12(uv * u_resolution + fract(u_time) * 91.0) - 0.5) * 0.12;
            vec3 col = vec3(r, g, b) * vec3(1.06, 0.97, 0.88) + noise;
            return vec4(col, 1.0);
        }
    """

    // C64 palette + 1px PAL fringe (u_palette required).
    const val C64 = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 px = vec2(1.0 / u_resolution.x, 0.0);
            vec3 c = vec3(sampleSrc(clamp(uv + px, 0.0, 1.0)).r, src.g, src.b);
            float best = 1000.0;
            vec3 outc = c;
            for (int i = 0; i < 16; i++) {
                float dd = distance(c, u_palette[i]);
                if (dd < best) { best = dd; outc = u_palette[i]; }
            }
            return vec4(outc, 1.0);
        }
    """

    const val NIGHT_VISION = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec2 px = vec2(1.0 / u_resolution.x, 0.0);
            float l0 = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            float l1 = dot(sampleSrc(clamp(uv - px * 2.0, 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
            float l = (l0 * 2.0 + l1) / 3.0;
            float grain = hash12(uv * u_resolution + fract(u_time) * 57.0) * 0.22;
            vec2 d = uv - 0.5;
            float vig = clamp(1.0 - dot(d, d) * 1.8, 0.15, 1.0);
            vec3 col = vec3(0.10, 0.85, 0.30) * (l * 0.9 + grain * l) * vig;
            return vec4(col, 1.0);
        }
    """

    const val PENCIL_SKETCH = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            vec2 px = 2.0 / u_resolution;
            float blur = 0.0;
            blur += dot(sampleSrc(clamp(uv + vec2(-px.x, -px.y), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(0.0, -px.y), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(px.x, -px.y), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(-px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv, 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(-px.x, px.y), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM);
            blur += dot(sampleSrc(clamp(uv + vec2(px.x, px.y), 0.0, 1.0)).rgb, LUM);
            blur /= 9.0;
            float l = dot(src.rgb, LUM);
            float sketch = clamp((1.0 - l) / max(blur, 0.05), 0.0, 1.0);
            vec3 paper = vec3(0.94, 0.92, 0.87);
            return vec4(paper * (1.0 - sketch * 0.85), 1.0);
        }
    """

    const val ANIME_CEL = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            float l = dot(src.rgb, LUM);
            vec3 sat = mix(vec3(l), src.rgb, 1.35);
            vec3 cel = floor(sat * 4.0) / 4.0;
            vec2 px = 1.0 / u_resolution;
            float gx = dot(sampleSrc(clamp(uv + vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            float gy = dot(sampleSrc(clamp(uv + vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM);
            float edge = smoothstep(0.12, 0.35, length(vec2(gx, gy)));
            return vec4(mix(cel, vec3(0.08), edge), 1.0);
        }
    """

    // param1 = segments
    const val KALEIDOSCOPE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float aspect = u_resolution.x / max(u_resolution.y, 1.0);
            vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
            float r = length(p);
            float a = atan(p.y, p.x);
            float seg = 6.2831853 / max(u_param1, 2.0);
            a = mod(a, seg);
            a = abs(a - seg * 0.5);
            vec2 q = vec2(r * cos(a) / aspect + 0.5, r * sin(a) + 0.5);
            return sampleSrc(clamp(q, 0.0, 1.0));
        }
    """

    // param1 = frame blend (needs FBO ping-pong; see FilterRenderer).
    const val MOTION_TRAILS = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 prev = texture2D(u_feedback, vFrameCoord).rgb;
            vec3 acc = mix(prev, src.rgb, clamp(u_param1, 0.02, 1.0));
            return vec4(acc, 1.0);
        }
    """

    const val NEWSPRINT = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            vec2 q = (uv * u_resolution) / max(u_param1, 2.0);
            vec2 cell = fract(q) - 0.5;
            float dist = length(cell) * 1.41421356;
            float dot_ = smoothstep(sqrt(max(1.0 - l, 0.0)), sqrt(max(1.0 - l, 0.0)) - 0.2, dist);
            float grain = (hash12(uv * u_resolution) - 0.5) * 0.10;
            vec3 paper = vec3(0.93, 0.90, 0.82);
            vec2 d = uv - 0.5;
            float vig = clamp(1.0 - dot(d, d) * 1.2, 0.55, 1.0);
            return vec4(mix(paper, vec3(0.12), dot_) * (1.0 + grain) * vig, 1.0);
        }
    """

    const val BLUEPRINT = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            vec3 bg = vec3(0.05, 0.25, 0.60);
            vec2 g = abs(fract(uv * u_resolution / 32.0) - 0.5);
            float grid = smoothstep(0.48, 0.5, max(g.x, g.y)) * 0.25;
            vec3 col = bg + vec3(1.0) * ((1.0 - l) * 0.9) + grid;
            return vec4(col, 1.0);
        }
    """

    // Single-pass filmic dither (interleaved gradient noise).
    // ponytail: true sequential Floyd-Steinberg needs compute shaders;
    // IGN converges visually and is stable frame-to-frame.
    const val ERRDIFF = """
        float ign(vec2 p) {
            vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
            return fract(magic.z * fract(dot(p, magic.xy)));
        }
        vec4 applyFilter(vec4 src, vec2 uv) {
            float luma = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            float t = ign(floor(uv * u_resolution / max(u_param1, 1.0)));
            return vec4(vec3(step(t, luma)), 1.0);
        }
    """

    // param1 = block px (default 8)
    const val ZX_SPECTRUM = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            float bs = max(u_param1, 4.0);
            vec2 block = floor(uv * u_resolution / bs);
            vec2 origin = block * bs / u_resolution;
            vec2 step_ = vec2(bs) / u_resolution;
            vec3 c00 = sampleSrc(clamp(origin, 0.0, 1.0)).rgb;
            vec3 c10 = sampleSrc(clamp(origin + vec2(step_.x, 0.0), 0.0, 1.0)).rgb;
            vec3 c01 = sampleSrc(clamp(origin + vec2(0.0, step_.y), 0.0, 1.0)).rgb;
            vec3 c11 = sampleSrc(clamp(origin + step_, 0.0, 1.0)).rgb;
            float l00 = dot(c00, LUM);
            float l10 = dot(c10, LUM);
            float l01 = dot(c01, LUM);
            float l11 = dot(c11, LUM);
            vec3 dark = c00;
            vec3 bright = c00;
            float ldark = l00;
            float lbright = l00;
            if (l10 < ldark) { ldark = l10; dark = c10; }
            if (l01 < ldark) { ldark = l01; dark = c01; }
            if (l11 < ldark) { ldark = l11; dark = c11; }
            if (l10 > lbright) { lbright = l10; bright = c10; }
            if (l01 > lbright) { lbright = l01; bright = c01; }
            if (l11 > lbright) { lbright = l11; bright = c11; }
            float l = dot(src.rgb, LUM);
            float t = (l - ldark) / max(lbright - ldark, 0.01);
            float d = step(bayer4(uv * u_resolution / 2.0) / 16.0, clamp(t, 0.0, 1.0));
            return vec4(mix(dark, bright, d), 1.0);
        }
    """

    const val CROSSHATCH = """
        float hatchLayer(vec2 p, vec2 dir, float spacing) {
            float f = abs(fract(dot(p, dir) / spacing) - 0.5) * 2.0;
            return smoothstep(0.55, 0.9, f);
        }
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            float l = dot(src.rgb, LUM);
            vec2 p = uv * u_resolution;
            float h = 0.0;
            if (l < 0.75) { h += hatchLayer(p, normalize(vec2(1.0, 1.0)), 7.0); }
            if (l < 0.50) { h += hatchLayer(p, normalize(vec2(1.0, -1.0)), 7.0); }
            if (l < 0.30) { h += hatchLayer(p, vec2(1.0, 0.0), 5.0); }
            vec2 px = 1.0 / u_resolution;
            float gx = dot(sampleSrc(clamp(uv + vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            float gy = dot(sampleSrc(clamp(uv + vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM);
            float edge = smoothstep(0.10, 0.30, length(vec2(gx, gy)));
            vec3 paper = vec3(0.95, 0.93, 0.88);
            vec3 col = paper * (1.0 - 0.45 * clamp(h, 0.0, 1.5));
            return vec4(mix(col, vec3(0.08), edge), 1.0);
        }
    """

    // Static jewel palette (u_palette required); CPU median-cut deferred.
    const val STAINED_GLASS = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            float best = 1000.0;
            vec3 outc = src.rgb;
            for (int i = 0; i < 16; i++) {
                float dd = distance(src.rgb, u_palette[i]);
                if (dd < best) { best = dd; outc = u_palette[i]; }
            }
            vec2 px = 1.0 / u_resolution;
            float gx = dot(sampleSrc(clamp(uv + vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            float gy = dot(sampleSrc(clamp(uv + vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM);
            float border = smoothstep(0.08, 0.25, length(vec2(gx, gy)));
            return vec4(mix(outc, vec3(0.05), border), 1.0);
        }
    """

    const val ENGRAVING = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 LUM = vec3(0.299, 0.587, 0.114);
            vec2 px = 1.0 / u_resolution;
            float gx = dot(sampleSrc(clamp(uv + vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(px.x, 0.0), 0.0, 1.0)).rgb, LUM);
            float gy = dot(sampleSrc(clamp(uv + vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM) -
                       dot(sampleSrc(clamp(uv - vec2(0.0, px.y), 0.0, 1.0)).rgb, LUM);
            float mag = length(vec2(gx, gy));
            float ang = atan(gy, gx);
            vec2 dir = vec2(cos(ang), sin(ang));
            float lines = sin(dot(uv * u_resolution, dir) * 1.1);
            float l = dot(src.rgb, LUM);
            float midband = smoothstep(0.05, 0.3, l) * (1.0 - smoothstep(0.7, 0.95, l));
            float hatch = smoothstep(-0.2, 0.9, lines) * midband;
            float ink = smoothstep(0.18, 0.45, mag);
            vec3 paper = vec3(0.93, 0.90, 0.83);
            vec3 col = paper * (1.0 - 0.65 * hatch);
            return vec4(mix(col, vec3(0.10), ink), 1.0);
        }
    """

    // param1 = cell px, param2 = pattern (0 diamond, 1 cluster, 2 noise)
    const val ORDERED = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            float luma = dot(src.rgb, vec3(0.299, 0.587, 0.114));
            vec2 p = uv * u_resolution / max(u_param1, 2.0);
            float t;
            if (u_param2 < 0.5) {
                vec2 f = abs(fract(p) - 0.5);
                t = f.x + f.y;
            } else if (u_param2 < 1.5) {
                t = length(fract(p) - 0.5) * 1.41421356;
            } else {
                t = hash12(floor(p) + 0.5);
            }
            return vec4(vec3(step(t, luma)), 1.0);
        }
    """

    /**
     * Filter Lab: colour grade plus every effect stage, in one pass.
     *
     * Runs as the second chain link, so `src` is the base filter's output. Neighbour
     * taps come from `sampleSrc` rather than `src`, which is what lets sharpen,
     * blur and the RGB split live here instead of costing three more full-screen
     * passes. The intermediate FBO is GL_CLAMP_TO_EDGE, so taps past the border
     * replicate the edge pixel - the same thing upstream's convolution does by
     * copying border pixels through untouched.
     *
     * Stage order matches `PhotoFilterBuilder.build()`: grade, duotone, sharpen,
     * blur, then vignette and grain on top.
     */
    const val LAB_GRADE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            // One destination pixel in frame space, which is also the tap spacing
            // for the convolutions and the channel split.
            vec2 texel = 1.0 / max(u_resolution, vec2(1.0));

            // --- 0. Geometry + lens distortion warp (coordinate, not colour).
            // Warps where we sample from, before anything else. No fetches by
            // itself, just ALU to compute the warped coord + one resample.
            vec2 geoUv = uv;
            if (u_geoActive > 0.0) {
                geoUv = geoWarp(uv, u_geoA, u_geoB);
            }
            if (u_lensEnable > 0.5 && abs(u_lensDistort) > 0.001) {
                geoUv = lensDistort(geoUv, u_lensDistort);
            }
            // The working colour for the whole body. Every stage below reads and
            // writes this, so it is declared once here rather than next to
            // whichever stage happened to come first.
            vec3 c = src.rgb;
            if (u_geoActive > 0.0 || (u_lensEnable > 0.5 && abs(u_lensDistort) > 0.001)) {
                vec2 clampedUv = clamp(geoUv, 0.0, 1.0);
                c = sampleSrc(clampedUv).rgb;
            }

            // --- 1. the template, a whole base look, applied ahead of the
            // adjustments because it is not itself an adjustment ---
            vec3 graded = vec3(
                dot(u_ccmR0, c),
                dot(u_ccmR1, c),
                dot(u_ccmR2, c)
            ) + u_ccmOffset;
            c = clamp(graded, 0.0, 1.0);

            // --- 2. Exposure, as a midtone power curve. Applied as 1/gamma so
            // that raising it brightens, which is how every photo tool presents
            // it. This is the first adjustment in Adobe's order because it moves
            // the whole tonal range and everything after it is defined relative
            // to where the tones now sit. ---
            if (abs(u_gamma - 1.0) > 0.001) {
                vec3 g = vec3(1.0 / max(u_gamma, 0.001));
                c = pow(max(c, vec3(0.0)), g);
            }

            // --- 3. Contrast, pivoting on mid-grey: c*in + 0.502*(1-c) ---
            // Second in Adobe's order, ahead of the range work, so the highlight
            // and shadow adjustments below are computed against the final
            // contrast rather than a stale one.
            if (abs(u_contrast - 1.0) > 0.001 || abs(u_brightness) > 0.001) {
                c = c * u_contrast + 0.502 * (1.0 - u_contrast) + u_brightness;
            }

            // --- 4. Highlights, Shadows, Whites, Blacks. Fourth in Adobe's
            // order, after contrast and before local contrast. Per channel, so
            // lifting the shadows warms them the way a real print does ---
            if (abs(u_ranges.x) + abs(u_ranges.y) + abs(u_ranges.z) + abs(u_ranges.w) > 0.001) {
                float rl = luminance(c);
                for (int i = 0; i < 4; i++) {
                    float a = u_ranges[i];
                    if (abs(a) < 0.001) continue;
                    float w = rangeWeight(i, rl);
                    c = vec3(
                        rangeAdjust(c.r, w, a),
                        rangeAdjust(c.g, w, a),
                        rangeAdjust(c.b, w, a)
                    );
                }
            }

            // --- 5. Texture, Clarity, Dehaze. Adobe applies local contrast
            // BEFORE the tone curve, not after, so a preset's curve shapes the
            // tones and only then finds the local structure in them.
            //
            // This is structurally what Adobe's operators are: a difference
            // against a blur, added back. One 9-tap tent is shared, so clarity
            // and dehaze have the same radius rather than their own - see the
            // note in the recipe about that ceiling.
            if (u_local.x != 0.0 || u_local.y != 0.0 || u_local.z != 0.0) {
                // Texture: fine detail, added equally to all three channels so
                // it sharpens without shifting hue.
                if (u_local.x != 0.0) {
                    float l0 = luminance(c);
                    float ld = luminance(tent3(c, texel * 1.5));
                    c += u_local.x * (l0 - ld);
                }
                if (u_local.y != 0.0 || u_local.z != 0.0) {
                    // ponytail: clarity and dehaze share one radius. Separate
                    // radii would need a second 9-tap pass on the live
                    // viewfinder, which is 9 more fetches per pixel per frame.
                    vec3 wide = tent3(c, texel * 6.0);
                    if (u_local.y != 0.0) {
                        // Per channel, which is what gives clarity its
                        // characteristic colour shift in skin and foliage.
                        c += u_local.y * (c - wide);
                    }
                    if (u_local.z != 0.0) {
                        c += u_local.z * (c - wide);
                        // Dehaze also deepens, so it raises contrast rather
                        // than only adding local structure.
                        c = (c - 0.5) * (1.0 + u_local.z * 0.4) + 0.5;
                    }
                }
            }

            // --- 6. Temp and Tint: three diagonal scales, no offset ---
            c *= vec3(u_rScale, u_gScale, u_bScale);

            // --- 6b. Calibration. With temp and tint, and immediately after it:
            // both are corrections to the same thing, and a calibration that
            // undid the warmth above it would be pointless. Not a texture
            // fetch, three dot products.
            if (u_calActive > 0.0) {
                c = vec3(
                    dot(u_calR0, c),
                    dot(u_calR1, c),
                    dot(u_calR2, c)
                );
            }

            // --- 7. Vibrance then Saturation. Vibrance boosts low-sat pixels
            // more (skin-tone protection); saturation is uniform.
            if (abs(u_vibrance) > 0.001) {
                float lum = luminance(c);
                float satM = clamp(distance(c, vec3(lum)) * 2.0, 0.0, 1.0);
                float k = 1.0 + u_vibrance * (1.0 - satM);
                c = mix(vec3(lum), c, k);
            }
            if (abs(u_saturation - 1.0) > 0.001) {
                float lum = luminance(c);
                c = mix(vec3(lum), c, u_saturation);
            }

            // --- 8. Grayscale + B&W mixer. Mixer only applies when grayscale on.
            if (u_grayscale > 0.0) {
                float lum = luminance(c);
                float gray = lum;
                if (u_bwActive > 0.0) {
                    vec3 hsl = rgbToHsl(c);
                    float total = 0.0;
                    float adj = 0.0;
                    for (int i = 0; i < 4; i++) {
                        float w = hslWeight(hsl.x, i);
                        float m = i < 2 ? (i == 0 ? u_bwMixA.x : u_bwMixA.y) : (i == 2 ? u_bwMixA.z : u_bwMixA.w);
                        adj += w * m; total += w;
                    }
                    for (int i = 4; i < 8; i++) {
                        float w = hslWeight(hsl.x, i);
                        float m = i == 4 ? u_bwMixB.x : (i == 5 ? u_bwMixB.y : (i == 6 ? u_bwMixB.z : u_bwMixB.w));
                        adj += w * m; total += w;
                    }
                    if (total > 0.0) adj /= total;
                    gray = clamp(lum + adj * 0.25, 0.0, 1.0);
                }
                c = mix(c, vec3(gray), u_grayscale);
            }

            // --- 9. Tone Curve, then Look. Fourteen in Adobe's order, and the
            // single largest thing an XMP preset carries.
            // Three fetches cover all four curves: fetching at each input
            // channel returns every curve evaluated at that input, so the
            // composite for all three channels and each channel's own curve all
            // come out of the same three reads.
            if (u_curveAmount > 0.0) {
                vec4 cr = texture2D(u_curve, vec2(c.r, 0.5));
                vec4 cg = texture2D(u_curve, vec2(c.g, 0.5));
                vec4 cb = texture2D(u_curve, vec2(c.b, 0.5));
                // Composite first, matching Adobe, then each channel on top.
                c = mix(c, vec3(cr.r, cg.r, cb.r), u_curveAmount);
                c = mix(c, vec3(cr.g, cg.b, cb.a), u_curveAmount);
            }

            c = clamp(c, 0.0, 1.0);

            // --- 10. Color Mixer, eight hue bands. After the curve, because
            // the curve decides what colour each band actually contains.
            //
            // No texture fetches: the band weights are arithmetic. u_hslActive
            // is 0 unless a band is off neutral, so a recipe that does not use
            // the mixer pays nothing for it.
            if (u_hslActive > 0.0) {
                c = clamp(hslBands(c, u_hsl), 0.0, 1.0);
            }

            // --- 10b. Color Grading 3-way. After mixer. Three tints
            // from HSL->RGB, weighted by luminance zones with blend smoothing.
            if (u_gradeActive > 0.0) {
                float l = luminance(c);
                vec3 shTint = hslToRgb(vec3(u_gradeA.x * 360.0, u_gradeA.y, 0.5));
                vec3 midTint = hslToRgb(vec3(u_gradeA.z * 360.0, u_gradeA.w, 0.5));
                vec3 hiTint = hslToRgb(vec3(u_gradeB.x * 360.0, u_gradeB.y, 0.5));
                float blend = u_gradeB.z;
                float bal = u_gradeB.w;
                float wSh = 1.0 - smoothstep(0.0, 0.4 + blend * 0.4, l);
                float wHi = smoothstep(0.6 - blend * 0.4, 1.0, l);
                float wMid = clamp(1.0 - wSh - wHi, 0.0, 1.0);
                // Balance shifts mid weight toward shadows/highlights.
                wSh = clamp(wSh + bal * 0.3 * wMid, 0.0, 1.0);
                wHi = clamp(wHi - bal * 0.3 * wMid, 0.0, 1.0);
                vec3 tint = shTint * wSh + midTint * wMid + hiTint * wHi;
                tint = tint / max(wSh + wMid + wHi, 0.001);
                c = mix(c, c * (tint * 2.0), clamp(wSh * u_gradeA.y + wMid * u_gradeA.w + wHi * u_gradeB.y, 0.0, 1.0));
            }

            // --- split tone: pull a tint into the shadows and another into the
            // highlights, keyed off luminance. Mirrored about mid grey so the two
            // ends cannot both push the same way. ---
            if (u_splitAmount > 0.0) {
                float l = luminance(c);
                vec3 tint = mix(u_shadowTint, u_highlightTint, smoothstep(0.0, 1.0, l));
                // A tint at 0.5 grey is a no-op, so this shifts hue without
                // dragging overall brightness with it.
                c = mix(c, c * (tint * 2.0), u_splitAmount);
            }

            // --- Sharpness. Adobe applies it after the tone curve and Look, so
            // a preset's curve shapes the tones and the edges are found on the
            // shaped result.
            //
            // Masking is the part that matters and is not in the old 3x3: a
            // threshold on the local difference, so flat areas are left alone and
            // only real edges get sharpened. Without it a preset tuned to a
            // threshold would ring every flat patch of sky.
            if (u_sharpen > 0.0 || u_detail > 0.0) {
                vec3 wide = tent3(c, texel * u_sharpRadius);
                vec3 diff = c - wide;
                if (u_masking > 0.0) {
                    // Smoothstep on the magnitude of the difference. At masking 0
                    // the mix leaves the difference untouched, so the default is
                    // the old unthresholded behaviour.
                    float m0 = u_masking * 0.3;
                    vec3 w = vec3(smoothstep(m0, m0 + 0.1, abs(diff.r)),
                                  smoothstep(m0, m0 + 0.1, abs(diff.g)),
                                  smoothstep(m0, m0 + 0.1, abs(diff.b)));
                    diff *= mix(vec3(1.0), w, u_masking);
                }
                c += diff * u_sharpen * 2.0;
                if (u_detail > 0.0) {
                    // Detail is a second, much finer unsharp. Reusing the old
                    // 3x3 kernel costs no extra fetches, which is why Detail
                    // rides on it instead of a third blur.
                    vec3 s3 = vec3(0.0);
                    s3 += sampleSrc(vFrameCoord + vec2(-texel.x, 0.0)).rgb * -1.0;
                    s3 += sampleSrc(vFrameCoord + vec2( texel.x, 0.0)).rgb * -1.0;
                    s3 += sampleSrc(vFrameCoord + vec2(0.0, -texel.y)).rgb * -1.0;
                    s3 += sampleSrc(vFrameCoord + vec2(0.0,  texel.y)).rgb * -1.0;
                    s3 += c * 5.0;
                    c += (c - clamp(s3, 0.0, 1.0)) * u_detail;
                }
            }

            // --- denoise: luminance + color, shared one 9-tap blur. Lum smooths
            // flat areas (edge-masked); color smooths chroma, preserves luma.
            if (u_nrLum > 0.0 || u_nrColor > 0.0) {
                vec3 nb = tent3(c, texel * 2.0);
                if (u_nrLum > 0.0) {
                    float e0 = luminance(c);
                    float e1 = luminance(tent3(c, texel * 1.0));
                    float edge = clamp(abs(e0 - e1) * 8.0, 0.0, 1.0);
                    float k = u_nrLum * (1.0 - edge);
                    c = mix(c, vec3(luminance(nb)), k * 0.8);
                }
                if (u_nrColor > 0.0) {
                    float lum = luminance(c);
                    float blum = luminance(nb);
                    vec3 chroma = c - vec3(lum);
                    vec3 bchroma = nb - vec3(blum);
                    c = vec3(lum) + mix(chroma, bchroma, u_nrColor);
                }
            }

            // --- defringe: hue-masked desat for purple + green fringes.
            if (u_defringeActive > 0.0) {
                vec3 hsl = rgbToHsl(c);
                float h = hsl.x / 360.0;
                float s = hsl.y;
                float purpleM = hueInRange(h, u_defringeA.y, u_defringeA.z) * u_defringeA.x;
                float greenM = hueInRange(h, u_defringeB.x, u_defringeB.y) * u_defringeA.w;
                // Auto CA uses moderate defaults when manual amounts are zero.
                if (u_lensCA > 0.5) {
                    float autoP = hueInRange(h, 0.75, 0.92) * 0.5;
                    float autoG = hueInRange(h, 0.25, 0.42) * 0.5;
                    purpleM = max(purpleM, autoP);
                    greenM = max(greenM, autoG);
                }
                float k = clamp(purpleM + greenM, 0.0, 1.0) * step(0.1, s);
                c = mix(c, vec3(luminance(c)), k);
            }

            // --- blur: 3x3 tent at widening spacing (see LabUniforms.BLUR_MAX_SPACING_PX) ---
            if (u_blur > 0.0) {
                vec2 s = texel * (1.0 + u_blur * 6.0);
                vec3 sum = vec3(0.0);
                sum += sampleSrc(uv + vec2(-s.x, -s.y)).rgb * 1.0;
                sum += sampleSrc(uv + vec2( 0.0, -s.y)).rgb * 2.0;
                sum += sampleSrc(uv + vec2( s.x, -s.y)).rgb * 1.0;
                sum += sampleSrc(uv + vec2(-s.x,  0.0)).rgb * 2.0;
                sum += c * 4.0;
                sum += sampleSrc(uv + vec2( s.x,  0.0)).rgb * 2.0;
                sum += sampleSrc(uv + vec2(-s.x,  s.y)).rgb * 1.0;
                sum += sampleSrc(uv + vec2( 0.0,  s.y)).rgb * 2.0;
                sum += sampleSrc(uv + vec2( s.x,  s.y)).rgb * 1.0;
                c = mix(c, sum / 16.0, u_blur);
            }

            // --- lens blur: synthetic depth blur, radial mask from focus.
            if (u_lensBlur > 0.0) {
                vec2 s = texel * (2.0 + u_lensBlur * 10.0);
                vec3 sum = vec3(0.0);
                sum += sampleSrc(uv + vec2(-s.x, -s.y)).rgb;
                sum += sampleSrc(uv + vec2(0.0, -s.y)).rgb * 2.0;
                sum += sampleSrc(uv + vec2(s.x, -s.y)).rgb;
                sum += sampleSrc(uv + vec2(-s.x, 0.0)).rgb * 2.0;
                sum += c * 4.0;
                sum += sampleSrc(uv + vec2(s.x, 0.0)).rgb * 2.0;
                sum += sampleSrc(uv + vec2(-s.x, s.y)).rgb;
                sum += sampleSrc(uv + vec2(0.0, s.y)).rgb * 2.0;
                sum += sampleSrc(uv + vec2(s.x, s.y)).rgb;
                vec3 blurred = sum / 16.0;
                float dFocus = abs(luminance(c) - u_lensFocus);
                float m = clamp(dFocus * 2.0, 0.0, 1.0) * u_lensBlur;
                c = mix(c, blurred, m * 0.8);
            }

            // --- rgb split: red left, green centred, blue right ---
            if (u_glitch > 0.0) {
                vec2 g = vec2(max(1.0 / u_resolution.x, 0.0015) * u_glitch * 10.0, 0.0);
                c.r = sampleSrc(uv - g).r;
                c.b = sampleSrc(uv + g).b;
            }

            // --- duotone: luminance ramp between the two chosen colours ---
            if (u_duotone > 0.0) {
                float l = luminance(c);
                vec3 duo = mix(u_duoShadow, u_duoHighlight, l);
                c = mix(c, duo, u_duotone);
            }

            // --- vignette: falloff with roundness + aspect shape controls.
            if (u_vignette > 0.0) {
                float aspect = u_resolution.x / max(u_resolution.y, 1.0);
                aspect *= 0.5 + u_vigAspect;
                vec2 d = uv - 0.5;
                d.x *= aspect;
                float circ = length(d) / length(vec2(0.5 * aspect, 0.5));
                float rect = max(abs(d.x) / (0.5 * aspect), abs(d.y) / 0.5);
                float nd = mix(circ, rect, u_vigRound);
                float start = mix(0.15, 0.95, u_vigMid);
                float width = mix(0.02, 0.6, u_vigFeather);
                float f = smoothstep(start, start + width, clamp(nd, 0.0, 1.2));
                c *= 1.0 - u_vignette * f;
            }
            // Lens vignette correction (brighten corners) when enabled.
            if (u_lensEnable > 0.5) {
                float aspect = u_resolution.x / max(u_resolution.y, 1.0);
                vec2 d = uv - 0.5;
                d.x *= aspect;
                float nd = clamp(length(d) / length(vec2(0.5 * aspect, 0.5)), 0.0, 1.0);
                c *= 1.0 + nd * nd * 0.15;
            }

            // --- grain ---
            // Upstream seeds a fixed Random(42) per call, which is right for a
            // still and wrong for a viewfinder: frozen noise reads as a dirty
            // sensor, not film. Animated off u_time so it moves.
            if (u_grain > 0.0) {
                // Size scales the noise cell, so a preset's grain size changes how
                // coarse the grain is rather than only how strong.
                vec2 gc = uv * u_resolution * (0.5 / max(u_grainSize, 0.05))
                    + floor(u_time * 24.0);
                // Roughness blends between one sample per cell, which is blocky,
                // and four samples averaged, which is smooth. Adobe's roughness
                // is the blockiness of the grain distribution, and this is the
                // cheapest thing that moves that axis.
                vec2 cell = floor(gc);
                vec2 f = fract(gc);
                float blocky = hash12(cell) - 0.5;
                float smooth4 = (
                    hash12(cell) - 0.5
                    + hash12(cell + vec2(1.0, 0.0)) - 0.5
                    + hash12(cell + vec2(0.0, 1.0)) - 0.5
                    + hash12(cell + vec2(1.0, 1.0)) - 0.5
                ) * 0.25;
                vec2 sm = smoothstep(0.0, 1.0, f);
                float bilinear = mix(
                    mix(blocky, hash12(cell + vec2(1.0, 0.0)) - 0.5, sm.x),
                    mix(hash12(cell + vec2(0.0, 1.0)) - 0.5,
                        hash12(cell + vec2(1.0, 1.0)) - 0.5, sm.x),
                    sm.y
                ) * 0.25;
                float n = mix(bilinear, blocky, u_grainRough);
                c += n * u_grain * 0.47;
            }

            // --- watermark, then date stamp (upstream's order) ---
            // The v flip is not arbitrary: frame y grows upward (quadPos pairs
            // clip y=-1 with texcoord y=0) while a decoded Bitmap has row 0 at the
            // top, so v must run the other way inside the rect.
            if (u_markRect.z > u_markRect.x) {
                vec2 t = vec2(
                    (uv.x - u_markRect.x) / (u_markRect.z - u_markRect.x),
                    (u_markRect.w - uv.y) / (u_markRect.w - u_markRect.y)
                );
                if (t.x >= 0.0 && t.x <= 1.0 && t.y >= 0.0 && t.y <= 1.0) {
                    vec4 m = texture2D(u_markTex, t);
                    c = mix(c, m.rgb, m.a * u_markAlpha);
                }
            }
            if (u_stampRect.z > u_stampRect.x) {
                vec2 t = vec2(
                    (uv.x - u_stampRect.x) / (u_stampRect.z - u_stampRect.x),
                    (u_stampRect.w - uv.y) / (u_stampRect.w - u_stampRect.y)
                );
                if (t.x >= 0.0 && t.x <= 1.0 && t.y >= 0.0 && t.y <= 1.0) {
                    vec4 m = texture2D(u_stampTex, t);
                    c = mix(c, m.rgb, m.a * u_stampAlpha);
                }
            }

            return vec4(clamp(c, 0.0, 1.0), src.a);
        }
    """
}
