# Build Report — RetroCam

A record of everything built, decided, broken and fixed while taking RetroCam
from "41 GPU filters" to a Filter Lab with chained stages, area masks and
near-complete Adobe XMP preset compatibility.

Written at the end of the session. It is a report of what happened, including
the parts that went wrong, because those are the parts worth reading twice.

---

## 1. Starting point

RetroCam is a retro/procedural camera app for Android. Every filter is a real-time
GPU pass, so the constraint that shapes everything is **frame time**: anything
added to the shader runs on every pixel of the live viewfinder.

Initial state: 41 filter shaders, a filter drawer, a camera and video modes, a
gallery, and settings. Roughly 16,000 lines of Kotlin across four modules.

| Module | Role |
|---|---|
| `catalog` | Pure Kotlin. Filter definitions, shader source strings, the Filter Lab's maths, the XMP reader. **No Android dependencies**, which is what lets it be unit-tested and also compiled standalone for the GLSL gate. |
| `renderer` | `FilterRenderer`, OpenGL ES 2.0. Compiles shaders, owns textures and framebuffers. |
| `core/designsystem` | Tokens and the shadcn component set. |
| `app` | Compose UI, `CameraViewModel`, the two screens. |

**The one rule that paid for itself:** *filters are data.* A filter is a GLSL
body string plus a `FilterSpec` entry. The renderer never inspects what a filter
does. Adding a filter is one string and one struct; adding a whole new
*capability* to every filter, as happened twice, was one shared-header change.

---

## 2. What was built

### 2.1 The Filter Lab (Phases 0–6, original plan)

The Lab started as a panel over the camera. It ended as **a screen of its own
with its own viewfinder**, because two viewfinders on one phone fight over the
camera and the panel version black-screened the user when they switched back.

| Phase | What |
|---|---|
| 0 | Attribution and dependency wiring. Verified `com.github.hgayan7:FilterLibrary:2.0.0` actually resolves. |
| 1 | Grading core on the GPU. 35 transcribed matrices, 5 knobs, the `LAB_GRADE` shader, `FilterSpec.lab`, `FilterFamily.LAB`. |
| 2 | Lab UI, live preview, saved recipes in the strip, codec and persistence. |
| 3 | Six effect stages: vignette, grain, sharpen, blur, glitch, duotone. Plus a trails bug. |
| 4 | 3D HALD LUT: four procedural built-ins, a `.xmp`-free import path, size validation. |
| 5 | 1990s date stamp, watermark, palette extraction. |
| 6 | QR sharing: 164-char payload, ECC M, tested to 2% damage. |

Then a structural change: **the Lab became its own screen**, with its own
`FilterRenderer` and `GLSurfaceView`, bridged to the camera only through saved
recipes. The two halves never touch.

### 2.2 The shadcn port (U1–U3)

The UI was moved from Material 3 to a shadcn-style system: Radix neutral palette,
reduced type scale, tighter radii, shadcn motion timings. Tokens, then the Lab,
then Settings/Gallery, then `CameraScreen`.

`Tokens.kt` ships one documented departure from stock shadcn, discussed in §4.5.

### 2.3 Chained stages and area masks (Phase C)

A recipe became a **chain**: colour grade, then up to four filter stages in any
order, each with its own amount, named knobs and area mask. All 40 non-identity
catalog filters are usable as stages.

The area mask lives in the **shared shader footer**, not in each filter body. That
one decision gave area masking to all 46 shaders for free and meant no filter
needed to know about it. `MOTION_TRAILS` is listed but flagged unchainable: it
accumulates from a feedback buffer, and mid-chain that sampler holds the previous
stage's output rather than the last frame.

A stage needs **no shader work at all** — `drawFullQuad` was already uploading
`spec.param1..3`, so the knobs are ordinary spec fields that happen to be
user-editable. That is why Phase C was mostly a data problem.

### 2.4 XMP compatibility (Phases 1–6, second plan)

The starting problem: an XMP preset carries around forty settings, the Lab had
seven knobs, and the import reported a list of things it had dropped. The user's
framing was that "somethings are imported & so many things are not."

The diagnosis that unlocked it: **it is not forty knobs, it is four mechanisms.**

| Mechanism | Absorbs |
|---|---|
| 1D curve | `ToneCurvePV2012` + Red/Green/Blue, `Look` |
| Range-weighted tone | `Highlights`, `Shadows`, `Whites`, `Blacks` |
| Local contrast | `Texture`, `Clarity`, `Dehaze` |
| Scalars and real operators | `Sharpness`/`Radius`/`Detail`/`Masking`, `GrainAmount`/`Size`/`Roughness`, all six vignette parameters |

All four fit in the chain link that already existed, because `sampleSrc`
neighbour taps arrived back in Phase 3. Nothing needed a new render path.

The plan is in `.opencode/plan/xmp-full-fidelity.md`. All six phases shipped:

1. **Restructured the grade into Adobe's application order.** The composed 4×5
   matrix could not express it, because contrast is 2nd while temp/tint and
   saturation come much later, and one matrix applies everything at one point.
   The split cost **six floats, not two matrices** — contrast is
   `c*in + 0.502*(1-c)`, warmth/tint is three diagonal scales, and saturation is
   `lum + s*(in-lum)`, which *is* the matrix it replaced.
2. **1D tone curve.** Four curves in the four channels of a 256×1 texture; absent
   channels pack as the identity ramp so all four apply unconditionally. Three
   texture fetches cover all four curves.
3. **Range controls**, as `smoothstep` luminance bands with overlapping edges.
4. **Local contrast** as a difference against a blur, which is structurally what
   Adobe's operators are.
5. **Real operators**: masked unsharp, real grain size/roughness, vignette
   midpoint/feather.
6. **Coverage percentage** with per-key `EXACT` / `APPROXIMATE` / `UNSUPPORTED`.

### 2.5 Latest work

- **`.cube` LUT import.** Previously only Hald PNGs were supported, so any
  `.cube` — the format almost every LUT is sold in — failed to load at all.
- **LUT deselect.** There had been no way to turn a LUT off.
- **Copy report** in the XMP details box.
- Colour restored to the theme, and several layout fixes.

---

## 3. Numbers

| | |
|---|---|
| Commits | 31 |
| Kotlin lines | ~16,100 |
| Filters | 41 |
| Chainable primitives | 40 |
| Lab matrix templates | 35 |
| Unit tests | 197 |
| Shaders validated by the GLSL gate | 46 |
| Worst-case texture fetches per pixel per frame | ~27 |

---

## 4. Bugs found

This is the part worth reading. The dominant theme is that **almost every
catastrophic bug was in code that had already shipped, and almost none were in
the logic the tests covered.**

### 4.1 The GLSL gate lied four times

The gate compiles all 46 shaders through `glslangValidator`. For a long time it
guarded only the **number** of files written, which catches a missing file and
nothing else.

A run produced the correct 46 files while every `lab_grade.frag` was an *older
body*, and reported success. It surfaced only because uniforms I had just added
were declared but unused in the generated file.

The first hypotheses were wrong:

- Not `gen.jar` shadowing the catalog — `unzip -l` showed **zero** catalog
  classes bundled.
- Not classpath order — putting `catcls` first changed nothing.
- The staleness was in the **compiled generator itself**. Rebuilding `gen.jar`
  fixed it.

The gate now **re-reads what it wrote** and asserts it contains the current
`LAB_GRADE` and the current catalog body. A count guard cannot see staleness; a
content guard can.

**Standing rule:** after touching a shader, recompile `catcls` **and** rebuild
`gen.jar`.

### 4.2 Green and blue driven to black on every frame

Reading the diagonal out of a 4×5 at indices 0, 5, 10 takes the **first entry of
each row**, which is always zero. The diagonal is at 0, 6, 12. With `gScale` and
`bScale` reading zero, `c *= vec3(rScale, gScale, bScale)` would have zeroed two
channels of every frame.

Found because a scratch print showed `gScale=0.0` for a neutral recipe. The test
I had written to check the thing **reproduced the same off-by-one**, which is
the useful part — the test I'd written to check the thing was part of the bug.

### 4.3 `gridFor` was wrong for every non-power-of-two cube

`round(sqrt(cube))` only satisfies `grid² ≥ cube` when the cube is a perfect
square. `round(sqrt(17))` is 4, giving 16 slots for 17 tiles, so **two colours
land on one texel and the LUT is quietly wrong.** 16 and 64 are perfect squares,
so nothing already imported changed — but 17, 25, 33 and 65 are exactly what
real `.cube` files ship in. Found by `.cube` import, not by the Lab.

### 4.4 8-bit round-trips are lossy

Mapping a cube index through 8-bit and back loses information for any
non-power-of-two size: index 1 → 8-bit 7 → back to index 0. The image is now
laid out with `LutCatalog.indexTexel`, which is the shader's own arithmetic with
the index substituted for `floor(c*(cube-1)+0.5)`.

### 4.5 Reentrancy in the XMP parser

`parse()` accumulated its results in fields on an `object`, so a second import
inherited the first one's gamma, sharpen and warmth — a preset setting none of
them would look like it had. I wrote that bug, noticed it on re-read, and
rewrote it with locals. There is now a test that runs two parses in a row.

### 4.6 A silent `str.replace` no-op, caught by a guard added for a different reason

The band arithmetic exists twice: a testable Kotlin mirror and the shader that
actually runs. I added a test that reads the exact expressions out of
`Shaders.HEADER` to stop them drifting. On its first run it failed — my edit had
anchored on a kdoc that is actually a GLSL comment, so python's `replace` was a
no-op and the helpers never landed. Without the guard the suite would have stayed
green and the app would have had a shader calling undefined functions.

### 4.7 An off-by-one in the field count

`FIELD_COUNT` was 48 for 47 fields, which made **every single recipe decode
return null.** Caught by the round-trip tests, which is what they are for.

### 4.8 The Filter Lab crash on open

```kotlin
selectedStage.coerceIn(0, (stages.size - 1).coerceAtLeast(0))
```

With an empty stage list this is `coerceIn(0, 0)` → **0, not -1**, so the
`takeIf { it >= 0 }` guard never fired and the next expression read `stages[0]`.
The Lab always opens with no stages, so this was not an edge case — it was the
only path, and the screen was unreachable.

A second bug hid behind the first: the mask picker was composed **before** the
`AndroidView`. Compose draws later children on top, so it sat under the
`GLSurfaceView` and would never have received a touch even after the crash was
fixed.

### 4.9 The three UI bugs, and what they mean

- **39 stage chips stacked one per row.** A `Column` gives every child a full
  row. Now a `FlowRow`.
- **Mode labels wrapping** ("Vide/o", "Phot/o"). `weight(1f)` divides the row
  evenly *including* the dividers, which starved the labels. `weight(1f)` was
  also the wrong fix for the original "not centred" complaint — it centres by
  dividing, not by measuring.
- **A duplicate `.clickable` and a `Modifier.padding` overload that doesn't
  exist**, in a commit made without a build on request.

---

## 5. Decisions worth recording

**Filters are data.** GLSL body plus a `FilterSpec`. The renderer never
inspects what a filter does. This is why two whole capabilities could be added to
all 46 shaders with one shared-header change.

**Matrix maths stayed in Kotlin, grading maths moved to the GPU.** The original
port kept 0–255 offset units and composition order in Kotlin, where they are
easy to get wrong and easy to test. The parts a 4×5 cannot express — gamma, band
weights, curves — are shader steps.

**A bug was found in the composer while adding the first feature:** an OES
sampler declared on a 2D framebuffer. Fixed before it shipped.

**Tone curves are stored sampled, not as control points.** Reading a 256-number
run as control points halves it into 128 pairs and re-interpolates, bending a
curve that was already exact.

**Re-upload decided by a 1KB `contentEquals` per frame**, rather than a queue op
plus an invalidation counter that every recipe mutation would have to remember
to bump. Free, and one less thing to forget.

**The matrix split cost six floats.** I had predicted two additional matrices and
warned about the cost. All four 4×5s turned out to be exactly reproducible as
scalars.

**Report the drops, don't hide them.** Every unsupported XMP key is named with a
reason, and the coverage percentage counts keys rather than pretending to a
weighting it cannot have. One tone curve is worth more than four sliders, and a
number cannot say so.

---

## 6. The verification problem

**Nothing in this project has been fully verified.** Every phase shipped on unit
tests and a shader gate, with no Compose-level test and only occasional device
checks. That gap is not theoretical:

| Phase | What the tests covered | What actually broke |
|---|---|---|
| C | codec, mask maths, primitives, shader validity | crashed on open; picker invisible; chips stacked |
| C (uncommitted) | — | three compile errors, never compiled |
| UI fixes | — | labels wrapping, from the previous fix |

Six phases of shader work — approximately **27 texture fetches per pixel per
frame** at worst — have never run on hardware.

**What would close it:** one compose-level smoke test per screen, or a
screenshot-test harness. Three of the bugs above were caught by a human looking
at a photograph of the screen, which is not a repeatable check.

Two verification tools have already paid for themselves and are worth keeping:
the GLSL gate (caught the undeclared-`c` error and the header ordering error),
and the Kotlin/shader drift guard (caught a silent edit that never landed).

---

## 7. Current state

**Done and pushing:** 41 filters, the Filter Lab as its own screen, chained
stages with area masks, the shadcn port, `.cube` and Hald LUT import, XMP
import with a coverage report, QR sharing, palette extraction, date stamp,
watermark.

**Deliberately not delivered,** and reported as dropped rather than hidden:

- `Look` / `ToneCurveName` — Adobe's proprietary curve sets. Substituting our own
  would be guessing at someone's look.
- `PostCropVignetteRoundness` / `Aspect` — shape of the falloff, not strength.
- `CameraProfile`, `CameraCalibration*`, `WB`, `LensProfile` — raw-converter
  inputs. Honouring them means DNG profile support, which is not a Lab feature.
- sRGB vs scene-linear — a permanent difference in highlights rolloff.

**Known risks:**

1. **Performance is unmeasured.** ~27 texture fetches per pixel per frame.
2. **QR cannot carry a tone curve.** A recipe with one is not shareable by QR;
   the payload is ~1KB per curve and the share path does not yet say so.
3. **Clarity and Dehaze share a blur radius** where Adobe gives them separate
   ones. Named in the code at both ends so it is not mistaken for Adobe's
   behaviour.
4. **RetroCam still has no licence.** Only `THIRD_PARTY_NOTICES.md` exists.
5. **Something on the development phone deletes files from the project folder.**
   24 tracked files — every launcher icon and all the screenshots — were deleted
   mid-session and had to be restored from git; it happened again afterwards.
   `Media/` should move out of the repository or be gitignored, because the next
   `git add -A` would commit those deletions.

---

## 8. Licences

| Component | Licence |
|---|---|
| FilterLibrary | Apache-2.0 |
| ZXing | Apache-2.0 |
| **RetroCam itself** | **none — needs one** |
