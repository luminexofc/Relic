# Third-Party Notices

RetroCam builds on, and derives filter mathematics from, the projects below.

---

## FilterLibrary (photofilterlibrary)

- **Project:** FilterLibrary v2.0.0
- **Upstream:** https://github.com/hgayan7/FilterLibrary
- **License:** Apache License, Version 2.0
- **Copyright:** Copyright 2019 - 2026 Himshikhar Gayan

RetroCam depends on `com.github.hgayan7:FilterLibrary:2.0.0` (via JitPack) and
ports filter mathematics from it into GLSL. The ported code retains this
attribution, and the files it touches say so at the top.

Specifically derived from that project:

| RetroCam file | Derived from |
|---|---|
| `catalog/…/lab/LabGrading.kt` | `FilterEngine.applyAdjustments`, `FilterEngine.getColorMatrixForFilter` |
| `catalog/…/lab/LabTemplates.kt` | the 48 `FilterType` presets and their 4x5 color matrices |
| `catalog/…/lab/LabShader.kt` (GLSL) | `FilterEngine.applyDuotone`, `applyVignette`, `applyFilmGrain`, `applySharpen`, `applyRgbGlitch`, `applyHaldLut` |

What was **not** taken: the `Bitmap`/`Canvas` rendering implementations. RetroCam
runs its filter chain on the GPU through an offscreen `EGL10` context, so the
per-pixel work was reimplemented as a GLSL fragment shader rather than ported
line-for-line. The observable behaviour is intended to match, and
`LabGradingOracleTest` asserts the numeric agreement of the grading path against
the upstream implementation.

The upstream project is used as the reference implementation in that test. It is
not used to draw pixels in the shipping app, with one exception: palette
extraction and the rasterization of date-stamp/watermark overlays, which are
Canvas text-layout operations with no GPU equivalent.

### Apache License, Version 2.0 — summary

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
these files except in compliance with the License. You may obtain a copy of the
License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.

Full text: <http://www.apache.org/licenses/LICENSE-2.0>

---

## ZXing Core

- **Project:** ZXing
- **Upstream:** https://github.com/zxing/zxing
- **License:** Apache License, Version 2.0
- **Copyright:** Copyright 2007 ZXing authors

Used for encoding and decoding Filter Lab recipe QR codes.

---

## Bundled fonts

**None.** The app previously shipped Press Start 2P, VT323 and Space Mono (all
SIL OFL 1.1) and no longer does: the type stack moved to the platform UI and
monospace faces as part of the shadcn-style port, so `res/font/` was deleted and
469 KB of assets went with it. The system faces carry no attribution duty.

The history stays here on purpose: those fonts were genuinely OFL-licensed and
this notice is what recorded that.

---

## RetroCam itself

RetroCam has no license file of its own yet. All rights reserved by default.
