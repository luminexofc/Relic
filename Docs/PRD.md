# PRD — RetroCam (Working Title)

> A retro/procedural-filter camera app for Android. Every filter is real-time, GPU-accelerated, and shippable phase by phase.

---

## 1. Vision & Problem Statement

Camera apps today are either "beauty filters" or heavy pro editors. There's a gap for a fast, playful camera app built around procedural retro effects (dithering, halftone, ASCII, CRT) rendered live on the GPU — no server, no processing wait, instant shareable art.

**One-line pitch:** *Point, filter, shoot — your photos look like they came from 1985, rendered live.*

---

## 2. Goals & Success Metrics

| Goal | Metric |
|---|---|
| Sub-frame filter performance | 60fps preview on mid-range devices (shader < 2ms) |
| Delight-first UX | Filter switching animation ≤ 300ms, haptic on every toggle |
| Phase-1 viability | Photo capture works with all Phase-1 filters, saved to gallery |
| Retention hook | Users try ≥3 filters per session |

**Non-goals (v1):** video recording, social feed, in-app editing suite (crop/rotate only), iOS.

---

## 3. Target Users

- **Retro/aesthetic creators** (18–30) posting lo-fi content to Instagram/TikTok
- **Indie/dev-curious users** who love "how does this work" effects (ASCII, matrix rain)
- **Casual photographers** bored of default camera filters

---

## 4. Core User Stories

1. As a user, I open the app and see a **live filtered preview** instantly (default filter: Dither).
2. As a user, I **swipe horizontally** to cycle filters with a smooth transition animation.
3. As a user, I **tap the screen** to adjust filter intensity via a bottom slider.
4. As a user, I **capture** a photo and it saves to device gallery with the exact preview look (WYSIWYG).
5. As a user, I can **favorite** filters and have them pinned to the front of the strip.
6. As a user, I can **share** directly to social apps from the preview screen.

---

## 5. Feature Set

### 5.1 Must Have (Phase 1)
- Real-time camera preview (CameraX → OpenGL Surface)
- 7 core filters: **Dither, Color Halftone, ASCII Art, Pixelated, Matrix, Bayer Matrix, Pixel Art**
- Filter strip UI with swipe + tap selection
- Capture → save to gallery (JPEG, full resolution, EXIF orientation correct)
- Flash toggle, camera flip
- Basic settings (sound on/off, save location)

### 5.2 Should Have (Phase 2)
- Intensity slider per filter (uniform-driven)
- Filter favorites + recents ordering
- Crop/rotate after capture
- Grid overlay + level indicator

### 5.3 Could Have (Phase 3+)
- Extended filter packs (print, hardware, stylization, distortion families)
- Video recording with filters (MediaCodec + EGL)
- Custom dither-matrix editor (import image → user matrix)
- Shake-to-random-filter (reuse your Sway shake-to-add pattern)
- Batch export / ZIP of favorites
- Appwrite sync of favorites/settings across devices (you already run Appwrite)

### 5.4 Won't Have (explicitly deferred)
- Cloud photo storage, face filters/beauty, AR stickers, RAW capture, manual ISO/focus pro controls (v2+ maybe).

---

## 6. UX Principles

1. **WYSIWYG or nothing** — the saved photo must match preview pixels.
2. **One-thumb operation** — shutter, filter strip, intensity all reachable.
3. **Every action haptic** — light impact on toggle, success on save, rigid on error.
4. **Zero-load startup** — camera ready < 1.5s cold start.

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Low-end devices can't hit 60fps | Automatic preview-resolution downgrade + "performance mode" fallback |
| Filter ≠ capture mismatch | Render capture through the same EGL context/shader as preview |
| Camera permission denial | Graceful empty-state screen with re-prompt |
| OpenGL complexity creep | Single shared fragment-shader template, per-filter uniforms only (see Architecture.md) |

---

## 8. Release Definition of Done (Phase 1)

- [ ] All 7 core filters selectable & live
- [ ] Capture saves correct-orientation JPEG to gallery
- [ ] 60fps preview on a 2021 mid-range device (test matrix: 3 devices)
- [ ] No crashes in 500-capture soak test
- [ ] App size < 25 MB
