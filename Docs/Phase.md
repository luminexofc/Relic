# Phase.md — Build Order (every phase = shippable)

> Rule: **each phase ends with a working APK** you could hand to a tester. No phase leaves the build broken.

---

## PHASE 0 — Foundation (Week 1)
**Goal:** Camera opens, fullscreen live preview, no filters yet.

- CameraX preview → GLSurfaceView passthrough (no-op shader)
- Lifecycle binding, permission flow with empty-state
- App shell: Compose theme (Terminal Glass), single activity
- DataStore + Hilt wiring
- ✅ **Ship gate:** app opens to live viewfinder < 1.5s cold start; rotate/background-resume safe

## PHASE 1 — Core Seven Filters (Weeks 2–4)
**Goal:** All 7 original filters live + capture works.

1. Build `:catalog` module: FilterSpec + shared GLSL template (intensity mix)
2. Implement in order (easiest first, each fully done before next):
   - Pixelated (#4) → Dither (#1) → Bayer (#6) → Color Halftone (#2) → Pixel Art (#7) → ASCII (#3) → Matrix (#5)
3. Filter strip UI: horizontal snap RecyclerView (Compose), live thumbnails
4. Intensity slider (uniform-driven)
5. Capture path: EGL pbuffer → JPEG → MediaStore, EXIF orientation
6. Flash + flip camera
- ✅ **Ship gate:** 60fps preview mid-range device; saved JPEG matches preview (SSIM > 0.98 vs golden); 500-capture soak clean
- 🎁 **Soft launch candidate**

## PHASE 2 — Quick Wins & UX Polish (Weeks 5–6)
**Goal:** Depth with low-risk filters + UX that retains.

- Filters: Posterize (#11), Limited Palette/EGA (#10), Cyanotype (#15), Game Boy (#18), Edge-Only (#23), Emboss (#24), Thermal (#25), Swirl (#31), Pinch (#32)
- Favorites + recents ordering (strip sections)
- Crop/rotate on captured screen
- Grid overlay, level indicator
- Baseline Profiles + Macrobenchmark pass
- Sound toggle, haptics everywhere (see Animations.md)
- ✅ **Ship gate:** 16 filters total, all at 60fps; Play Console internal testing with 20 users

## PHASE 3 — Hardware & Print Families (Weeks 7–9)
**Goal:** The "wow" filters that get shared.

- CRT (#16), VHS (#17), C64 (#21), Night Vision (#26), Pencil Sketch (#27), Anime Cel (#29)
- Kaleidoscope (#30), Motion Trails (#34) — needs FBO ping-pong infra
- Newsprint (#14), Blueprint (#13)
- Filter groups (collapsible sections) in strip
- Video recording spike: MediaCodec + EGL — **timeboxed 1 week; defer if not clean**
- ✅ **Ship gate:** 25+ filters; video spike go/no-go decision recorded

## PHASE 4 — Advanced & Stretch (Weeks 10–12)
**Goal:** Differentiation + power-user features.

- Error diffusion (#9) — ping-pong compute pass
- ZX Spectrum attribute clash (#20), Crosshatch (#22), Stained Glass (#28)
- Engraving (#12), Ordered dither variants (#8)
- Filter composer: stack 2–3 filters (uses existing uniform pipeline)
- Filter recipe share (Kotlinx Serialization → deep link)
- Optional: Appwrite sync of favorites/settings; shake-to-random-filter (your Sway pattern)
- ✅ **Ship gate:** Public launch readiness review (store listing, screenshots per filter, privacy policy — camera-only, no data collection)

---

## Dependency Graph (why this order)

```
Phase 0 ──► Phase 1 (core 7) ──┬─► Phase 2 (quick wins) ──► Phase 3 (wow) ──► Phase 4 (advanced)
                               │
        Pixelated ──► Dither ──► Bayer ──► Halftone ──► PixelArt
                                              │
                              ASCII ──► (glyph atlas) ──► Matrix, later Crosshatch
                                              │
        Posterize ──► GameBoy/CGA ──► (LUT infra) ──► Thermal, C64, PixelArt palettes
                                              │
                          FBO infra (Phase 3) ──► Motion Trails, Error Diffusion, Composer
```

**Critical path:** Phase 0 → Phase 1. Everything else branches off filter-infra built there. The glyph-atlas work in ASCII/Matrix is the single biggest shared investment — schedule it with buffer.

## Risk Buffer
- Keep 1 week unallocated before public launch for device-specific GL driver bugs (especially Mali GPUs on dither shaders — known precision quirks; fix = explicit `mediump`/`highp` and epsilon handling).
