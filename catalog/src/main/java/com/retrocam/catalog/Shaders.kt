package com.retrocam.catalog

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
    """

    const val FOOTER = """
        void main() {
            vec4 src = sampleSrc(vFrameCoord);
            gl_FragColor = mix(src, applyFilter(src, vFrameCoord), u_intensity);
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
            float r2 = dot(d, d);
            vec3 c = sampleSrc(clamp(uv + d * r2 * 0.25, 0.0, 1.0)).rgb;
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
     * Filter Lab colour grade. Runs as a second chain stage after the base
     * filter, so `src` is the base filter's output and this applies the recipe's
     * matrix on top of it.
     *
     * The matrix maths lives in LabGrading rather than here; see the note on
     * why. The only thing left to do in the shader is clamp, because upstream's
     * `ColorMatrixColorFilter` clamps implicitly by drawing into an 8-bit
     * Bitmap and several FilterLibrary matrices (POLAROID_70S, CROSS_PROCESS,
     * DRAMATIC) deliberately push channels past 0-255.
     */
    const val LAB_GRADE = """
        vec4 applyFilter(vec4 src, vec2 uv) {
            vec3 c = vec3(
                dot(u_ccmR0, src.rgb),
                dot(u_ccmR1, src.rgb),
                dot(u_ccmR2, src.rgb)
            ) + u_ccmOffset;
            return vec4(clamp(c, 0.0, 1.0), src.a);
        }
    """
}
