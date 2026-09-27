# Filters.md — The Complete Catalog

> Every filter: what it does, the GLSL technique, tunable uniforms, and difficulty. Implementation notes assume the shared helpers in Architecture.md §4.

## FAMILY 1 — DITHER & QUANTIZATION (your originals)

### 1. Dither Effect
- **Look:** Classic 1-bit-ish ordered dither; image becomes high-contrast dot patterns.
- **Technique:** `color = step(bayer4x4(uv * res / scale), luma)` on a luminance pass; optional tint uniform.
- **Uniforms:** `intensity` (mix original↔dithered), `scale` (dot cell size 2–12px).
- **Difficulty:** ★☆☆

### 2. Color Halftone
- **Look:** CMYK-style colored dots, like comic print. Each channel drawn at rotated angles per real print.
- **Technique:** Per channel, sample grid cells, `dot = smoothstep(radius, radius-edge, dist(cellCenter, lumaChannel))`; channels offset by rotation matrices (15°/75°/0°/45° like real CMYK).
- **Uniforms:** `intensity`, `dotSize` (cell px), `paperColor` (vec3).
- **Difficulty:** ★★☆

### 3. ASCII Art
- **Look:** Image rendered as live-updating character mosaic (charset: ` .:-=+*#%@`).
- **Technique:** CPU precompute: render charset glyphs to a glyph-atlas texture (10×10 tiles). Shader: cell-average luminance → atlas index → sample glyph tile. Color = original cell average.
- **Uniforms:** `intensity`, `cellSize` (6–16px), `colorMode` (0=original, 1=mono, 2=green-terminal).
- **Difficulty:** ★★★ (atlas + mipmap bleeding care)

### 4. Pixelated
- **Look:** Chunky nearest-neighbor mosaic, no stylization — just resolution crush.
- **Technique:** `uv2 = floor(uv * grid)/grid; texture(image, uv2)`. GL_NEAREST on the sampler.
- **Uniforms:** `intensity`, `blockSize`.
- **Difficulty:** ★☆☆

### 5. Matrix
- **Look:** Green cascading digital rain revealing the camera image inside falling glyphs.
- **Technique:** Rain columns: per-column head position `y = mod(time*speed[col] + offset[col], 1.0)`; brightness glyph where `abs(uv.y - head)` small, fading trail behind; multiply over camera frame with green palette. Glyph atlas shared with ASCII.
- **Uniforms:** `intensity`, `speed`, `trailLength`, `glyphSize`.
- **Difficulty:** ★★★ (needs per-frame `time`, column hash RNG)

### 6. Bayer Matrix
- **Look:** Smoother ordered dither using 8×8 Bayer — richer midtone texture than 4×4.
- **Technique:** Same as Dither but threshold from an 8×8 Bayer texture (or computed via recursive bit math: `bayer8(x,y)`). Apply per RGB channel for color dithering.
- **Uniforms:** `intensity`, `levels` (2–16), `colorMode` (luma vs per-channel).
- **Difficulty:** ★☆☆

### 7. Pixel Art
- **Look:** Downscale + **limited palette** + slight sharpen — the retro-game look, not just mosaic.
- **Technique:** 3-stage: (a) pixelate to `pixelSize`, (b) quantize each pixel to nearest of N palette colors (32-color hand-tuned LUT texture), (c) optional `1.2×` unsharp on edges.
- **Uniforms:** `intensity`, `pixelSize`, `palette` (id: gameboy16, cga, vapor, sunset).
- **Difficulty:** ★★☆

---

## FAMILY 2 — DITHER EXTENSIONS

### 8. Ordered Dither (alt patterns)
Non-Bayer thresholds: diamond, cluster-dot, void-and-cluster. Same engine as #1, threshold texture swap. **Uniforms:** `pattern` (0–2), `intensity`, `scale`.

### 9. Error Diffusion Dithering
Floyd–Steinberg / Atkinson / Sierra. **True error diffusion is sequential — not GPU-friendly per-pixel.** Strategy: run diffusion at reduced resolution (e.g. 384 wide) in a compute-style ping-pong on GPU (two-pass), then upscale with nearest. Atkinson = the retro Mac look. **Uniforms:** `algorithm` (0=FS,1=Atkinson,2=Sierra), `intensity`. Difficulty: ★★★★.

### 10. Limited Palette / EGA
Quantize to fixed 16-color EGA/CGA palettes via LUT. Difficulty: ★☆☆.

### 11. Posterize
Per-channel `floor(c * n) / (n - 1)`. Difficulty: ★☆☆.

---

## FAMILY 3 — PRINT & PAPER

### 12. Engraving
Sobel magnitude → threshold to ink lines on paper tint; directional hatching where gradient angle selects hatch orientation. Difficulty: ★★☆.

### 13. Blueprint
`ink = 1 - luma`, colorize `ink * white` on `blue = vec3(0.05,0.25,0.6)`; add subtle grid lines. Difficulty: ★☆☆.

### 14. Newsprint
Color Halftone + paper-grain noise texture + slight blur vignette. Composite of #2 + noise. Difficulty: ★★☆.

### 15. Cyanotype
Luminance → blue-tone ramp (`vec3(0.0, l*0.55, l*0.95)`), lifted blacks like old photo paper. Difficulty: ★☆☆.

---

## FAMILY 4 — RETRO HARDWARE

### 16. CRT
Scanlines (`sin(uv.y * res.y * π)`), RGB aperture-grille stripes, barrel distortion on UV, vignette, optional flicker (`time`). Difficulty: ★★☆.

### 17. VHS / Glitch
Chroma aberration (RGB sample offsets), horizontal tracking tear (row-shift by noise(time)), desaturated warm grade, tape noise. Difficulty: ★★★.

### 18. Game Boy
4-shade green ramp (`#0f380f → #306230 → #8bac0f → #9bbc0f`), 4:3 crop letterbox, dither for midtones. Difficulty: ★☆☆.

### 19. CGA / EGA 16-color
Palette quantize (reuse #10) + 320×200 pixelation (reuse #4 at blockSize 4–6). Difficulty: ★☆☆.

### 20. ZX Spectrum
2-color-per-8px-block attribute clash simulation: per 8×8 block pick top-2 colors, dither the rest. The hardest "easy-looking" one — block-level decision needs a downsample+reduce pass. Difficulty: ★★★★.

### 21. Commodore 64
C64 palette LUT (16 iconic colors) + PAL artifacting hint (1px horizontal color fringe). Difficulty: ★★☆.

---

## FAMILY 5 — STYLIZATION

### 22. Crosshatch / Ink Sketch
Luminance bands → hatch density layers (3-4 pre-made hatch textures, rotated per layer); edges inked via Sobel threshold. Difficulty: ★★★.

### 23. Edge-Only
`smoothstep` on Sobel magnitude over paper white. Difficulty: ★☆☆.

### 24. Emboss
Convolution `kernel = [[-2,-1,0],[-1,1,1],[0,1,2]]` via 3×3 taps; result + 0.5 gray. Difficulty: ★☆☆.

### 25. Thermal Camera
Luma → false-color LUT texture (iron/cold-hot). Difficulty: ★☆☆.

### 26. Night Vision
Green mono + animated noise grain + heavy vignette + slight horizontal smear. Difficulty: ★☆☆.

### 27. Pencil Sketch
`inv = 1 - luma; blur(inv); sketch = clamp(inv/blur, 0, 1)` + paper tint. Blur = separable 9-tap ×2. Difficulty: ★★☆.

### 28. Stained Glass / Cel Regions
K-means in 3-frame rolling window is overkill for GPU — instead: median-cut palette LUT (precomputed at capture-start from a thumbnail on CPU, uploaded as LUT) + region borders via `1 - edge`. Difficulty: ★★★.

### 29. Anime / Cel Shade
Posterize(4) + thick Sobel outlines + slight saturation boost. Composite of #11 + #23. Difficulty: ★★☆.

---

## FAMILY 6 — DISTORT & FUN

### 30. Kaleidoscope / Mirror
UV → polar → `angle mod (π/n)`, mirror fold; segment count 6/8/12. Difficulty: ★★☆.

### 31. Swirl
`r,θ` per-pixel; `θ += swirlStrength * (1 - r/maxR)²`. Difficulty: ★☆☆.

### 32. Pinch/Barrel
Radial `uv` warp: `uv = center + (uv-center) * (1 + k * r²)`. Difficulty: ★☆☆.

### 33. Chromakey
Distance in HSV to key color → `mix(foreground, backgroundTexture, mask)`; background = gallery image. Difficulty: ★★☆.

### 34. Motion Trails / Long Exposure
Frame accumulation: `acc = mix(acc, frame, decay)` with `decay ≈ 0.08–0.25`, stored in an FBO ping-pong. Ghostly light-painting look. Difficulty: ★★☆.

---

## Implementation Notes (cross-filter)

1. **Glyph atlas** (ASCII + Matrix) is shared: one 16×6 glyph texture, built once at GL init from a mono typeface.
2. **LUTs** (palette filters) use a 256×1 RGB texture, bilinear sampling for smooth ramps.
3. **Intensity uniform** convention: `mix(original, filtered, intensity)` as the final line in *every* fragment shader — gives you the global slider for free.
4. **Resolution-aware uniforms:** always pass `vec2 u_resolution`; dither/halftone patterns scale with it.
5. **Capture path reuses identical programs** — only the output surface changes (pbuffer at full res). Golden-file tests depend on this.
6. **Temporal filters** (Matrix rain, VHS, trails) accept `u_time`; frozen at capture moment for stills.

## Difficulty → Phase Map

| Phase | Filters |
|---|---|
| 1 | #1–7 (core seven) |
| 2 | #4 variants, #10, #11, #15, #18, #23, #24, #25, #31, #32 |
| 3 | #8, #9, #12–14, #16, #17, #19, #21, #26, #27, #29, #30, #34 |
| 4 (stretch) | #3 alt charsets, #20, #22, #28, #33 + filter composer (user stacks 2–3 filters) |
