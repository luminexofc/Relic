# Architecture.md — RetroCam

## 1. High-Level Pattern

**MVVM + Unidirectional Data Flow.** One Activity, Compose (or Views — see Tech Stack) for UI, a single `CameraController` orchestrating CameraX ↔ OpenGL pipeline. Filters are pure data (uniforms + shader source), never objects with behavior — this keeps adding filters trivial.

```
┌────────────┐   intents    ┌─────────────┐
│   UI Layer │──────────────►│  ViewModel  │
│ (Compose)  │◄── UiState ───│  (StateFlow)│
└────────────┘              └──────┬───────┘
                                   │ FilterSpec
                          ┌────────▼─────────┐
                          │ CameraController │
                          │ (CameraX + EGL)  │
                          └────────┬─────────┘
              EGL SurfaceTexture   │
                          ┌────────▼─────────┐
                          │ FilterRenderer   │
                          │ (GLSL programs)  │
                          └────────┬─────────┘
                                   │ Frame
                     ┌─────────────┴────────────┐
                     ▼                          ▼
              GLSurfaceView              CaptureProcessor
              (live preview)             (EGL → JPEG encoder → MediaStore)
```

## 2. Core Components

### 2.1 FilterSpec (the heart of the system)
```kotlin
data class FilterSpec(
    val id: String,            // "dither", "ascii", ...
    val displayName: String,
    val family: FilterFamily,  // DITHER, PRINT, HARDWARE, STYLIZE, DISTORT
    val vertexSrc: String,     // shared passthrough 99% of the time
    val fragmentSrc: String,   // the GLSL
    val uniforms: List<UniformDef>,      // intensity, dotSize, palette, ...
    val defaultIntensity: Float = 0.75f
)
```
- Filters are **registered in a catalog object** (`FilterCatalog.all: List<FilterSpec>`).
- Adding a filter = one GLSL string + one catalog entry. No new classes.

### 2.2 FilterRenderer
- Owns the EGL context, compiles/caches `Map<filterId, GLProgram>` (lazy, LRU 8).
- Per frame: `render(textureId, filterSpec, uniformValues, outSurface)`.
- Uniform values come from a `StateFlow<Map<String, Float>>` — intensity slider writes here; render loop reads latest.

### 2.3 CameraController
- CameraX `Preview` use case with a `SurfaceProvider` backed by an **external EGL texture** (`SurfaceTexture`).
- Handles rotation/orientation so preview and capture agree (EXIF via `ImageCapture` metadata).
- Lifecycle-aware: binds on STARTED, releases on DESTROYED.

### 2.4 CaptureProcessor
- For stills: render the EGL frame through the active filter program to an offscreen `EGLPbuffer` at full sensor resolution → readback as `Bitmap` → encode JPEG (quality 95) → `MediaStore.Images` insert with EXIF orientation.
- WYSIWYG guarantee: same program, same uniforms, only higher resolution.

### 2.5 ViewModel / State
```kotlin
data class CameraUiState(
    val filter: FilterSpec,          // current
    val intensity: Float,
    val favorites: List<String>,     // ordered
    val flash: FlashMode,
    val lensFacing: LensFacing,
    val capturing: Boolean
)
```
- UI events → ViewModel → CameraController. One source of truth.

## 3. Frame Pipeline Detail

1. CameraX delivers camera frames to `SurfaceTexture` (external OES texture).
2. `FilterRenderer.onDrawFrame()`:
   - Bind active program
   - Upload uniforms (intensity, time for Matrix rain, resolution)
   - Draw fullscreen triangle to GLSurfaceView (preview) — **and, on capture request, additionally** to pbuffer (still).
3. Matrix/ASCII etc. that need temporal state keep a small ring buffer of previous frames as textures (uniform `prevFrame`).

## 4. Filter Family Strategies (shared code, not shared shaders)

| Family | Shared machinery |
|---|---|
| Dither (dither, bayer, error-diffusion) | matrix lookup utils, palette LUT texture |
| Halftone/print (halftone, blueprint, newsprint) | dot-grid distance functions |
| Hardware CRT/VHS/GameBoy | scanline + noise + chroma-aberration helpers |
| Stylize (posterize, sketch, thermal) | LUT + edge-detect helpers |
| Distort (kaleido, swirl, pinch) | UV-warp helper functions |

GLSL `#include`-style string composition at catalog build time — keeps shaders DRY.

## 5. Threading Model

| Thread | Job |
|---|---|
| Main | UI, gesture events |
| CameraX internal | frame production |
| **GL thread** (renderer) | all EGL/GL calls — preview + capture render |
| Default (Dispatchers.IO) | JPEG encode, MediaStore write, catalog load |

Rule: **GL calls only on the GL thread.** ViewModel never touches GL; it pushes `FilterSpec`/uniforms through a thread-safe queue consumed in `onDrawFrame`.

## 6. Persistence

| Data | Store |
|---|---|
| Favorites, recents order | DataStore Preferences (proto) |
| Per-filter intensity memory | DataStore |
| Settings (sound, grid, perf mode) | DataStore |
| Photos | Device MediaStore (app-owned folder `Pictures/RetroCam/`) |

No account needed. Optional Phase 3: Appwrite sync of favorites/settings via your existing backend.

## 7. Performance Guardrails

- Preview target: 1280×720 on most devices; drop to 960×540 in Performance mode.
- Shader compile happens **off the GL thread during filter-strip scroll** (precompile ±2 neighbors).
- Frame budget check: if `onDrawFrame` > 16ms for 30 consecutive frames, auto-suggest Performance mode (one-time banner).
- No per-frame allocations in render loop (uniform arrays pre-allocated).

## 8. Testing Strategy

- **Unit:** uniform-mapping logic, catalog integrity (every spec compiles GLSL source statically — regex sanity checks), state reducers.
- **Device/UI:** screenshot tests for each filter at fixed intensity on a test chart image (golden-file GL renders), capture round-trip (render→encode→decode→compare SSIM > 0.98 vs golden).
- **Soak:** 500 captures, memory must stay flat (no texture leaks — track `glDeleteTextures` pairing).
