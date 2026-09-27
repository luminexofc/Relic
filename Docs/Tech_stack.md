# Tech Stack.md — RetroCam

## 1. Platform & Language

| Choice | Decision | Why |
|---|---|---|
| Platform | **Android native** (minSdk 26, target 35) | GPU pipeline needs low-level GL control; RN/Flutter adds a frame-copy tax on live filters |
| Language | **Kotlin 100%** | Coroutines for pipeline, data classes for FilterSpec |
| UI | **Jetpack Compose** (Material 3) | Matches your Sway codebase skills; bottom sheets & strip UI are fast to build. *Fallback: Views if you prefer — architecture is UI-agnostic* |

## 2. Camera & Graphics

| Library | Role |
|---|---|
| **CameraX** (`1.4.x`) | Preview + ImageCapture use cases, lifecycle handling, EXIF/orientation |
| **OpenGL ES 3.0** | Filter rendering. Custom GLSL fragment shaders per filter |
| **GLSurfaceView** (or `SurfaceView` + custom EGL) | Preview surface hosting |
| `SurfaceTexture` (OES external texture) | Camera frame input to GL |

> ⚠️ **RenderScript is deprecated** — do not use. All pixel ops are GPU shaders.
> For CPU-side experiments (k-means, ASCII maps precompute): plain Kotlin — no OpenCV dependency needed at runtime (keep APK lean; use OpenCV only in a dev-only test harness if desired).

## 3. DI & Async

| Library | Role |
|---|---|
| **Hilt** | ViewModel / Controller injection |
| **Kotlin Coroutines + Flow** | UiState streams, GL-thread command queue |

## 4. Data & Storage

| Library | Role |
|---|---|
| **DataStore (Preferences + Proto)** | Settings, favorites, per-filter intensities |
| **MediaStore API** | Gallery saving (no WRITE_EXTERNAL_STORAGE needed on 29+) |
| **Appwrite** *(optional, Phase 3)* | Favorites/settings sync — you already operate Appwrite; reuse your client setup from Sway |

## 5. Media Handling

| Library | Role |
|---|---|
| `android.graphics.Bitmap` + `Bitmap.compress(JPEG)` | Capture encode (95 quality) |
| `ExifInterface` (androidx) | Orientation fix on capture |
| `android.media.MediaCodec` *(Phase 3)* | Filtered video recording |

## 6. Supporting Libraries

| Library | Role |
|---|---|
| **Coil 3** | Gallery thumbnails in capture-preview screen |
| **Kotlinx Serialization** | FilterSpec JSON export/import (share filter recipes) |
| **Timber** | Logging (DebugTree only in debug builds) |

## 7. Build & Tooling

| Tool | Config |
|---|---|
| Gradle KTS | version catalogs (`libs.versions.toml`) |
| AGP 8.x | |
| R8/ProGuard | Full mode; GLSL strings are kept (they're resources, not obfuscated) |
| Spotless + ktlint | Code style in CI |
| **Baseline Profiles** | Startup + filter-switch jank reduction (generate with Macrobenchmark) |
| LeakCanary | Debug builds only — texture/EGL leak detection |

## 8. Testing

| Layer | Tool |
|---|---|
| Unit | JUnit5 + Turbine (Flow assertions) + MockK |
| GL golden renders | Dev-only harness: render catalog to offscreen pbuffer on emulator with swiftshader, save PNGs for SSIM comparison |
| UI | Compose UI-test + Firebase Test Lab (3-device matrix: low/mid/high end) |
| Performance | Macrobenchmark (frame timing), `adb shell gfxinfo` |

## 9. Repo Layout (multi-module, keeps build fast)

```
:app                → UI, ViewModels, navigation
:catalog            → FilterSpec definitions + GLSL sources (pure Kotlin/JVM module — testable without Android!)
:renderer           → EGL/GL machinery (FilterRenderer, CaptureProcessor)
:camera             → CameraX glue
:core:datastore     → persistence
:core:designsystem  → theme, tokens (Terminal Glass)
```

**Key win:** `:catalog` is a JVM module → GLSL compile-sanity tests run on CI in seconds without an emulator.

## 10. APK Budget

Target **< 25 MB** release APK. CameraX + Compose dominate; no native libs beyond what's required. R8 strips ~40%.
