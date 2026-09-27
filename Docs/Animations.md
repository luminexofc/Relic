# Animations.md — Micro-interactions

> Philosophy: **feedback, not decoration.** Every animation answers "did my input register?" Values follow Material motion specs, tuned for a camera app's snappiness. All durations scale with system animator duration (respect accessibility).

---

## 1. Global Motion Tokens

| Token | Value | Use |
|---|---|---|
| `dur/instant` | 80ms | Tap ripples, reticle flash |
| `dur/fast` | 150ms | Filter select, toggles |
| `dur/med` | 250–300ms | Screen transitions, sheet open |
| `dur/slow` | 400–450ms | Empty-state entrances |
| `ease/standard` | `FastOutSlowInInterpolator` | Default |
| `ease/emphasis` | cubic-bezier(0.2, 0, 0, 1) | Hero elements (shutter) |
| `spring/filter` | stiffness 400, damping 30 | Filter strip items |
| `spring/shutter` | stiffness 800, damping 18 | Capture press |

---

## 2. Component Micro-interactions

### 2.1 Shutter Button
| Event | Animation | Haptic |
|---|---|---|
| Press down | Spring scale to 0.88 (fast spring) | light impact |
| Capture fired | Ring stroke draws 360° over 300ms + white flash overlay 120ms | success notification |
| Long-press (burst, P2) | Ring pulses 2× + counter appears above | medium impact on each frame |
| Release without capture | Spring back overshoot 1.04 → rest | none |

### 2.2 Filter Strip
| Event | Animation |
|---|---|
| Item selected | Spring scale 1.0→1.15, accent underline slides in 150ms, neighbors dim to 80% alpha |
| Swipe/fling | Snap to center with spring physics; offscreen items fade+translate in |
| Live thumbnail | Crossfades 150ms when filter changes (avoid pop) |
| Long-press item | Scale 1.05 + haptic → favorite toggle (★ pops in with rotation spring) |

### 2.3 Intensity Slider
| Event | Animation |
|---|---|
| Appear | Bottom sheet peek, slide+fade 250ms |
| Drag | Thumb scales 1→1.3, track fill animates with drag (no delay) |
| Release | Thumb settle spring; value persisted (debounce 300ms) |

### 2.4 Camera Flip
- Horizontal mirror flip 300ms ease/emphasis on viewfinder container (cameraX rebind behind it)
- Flip button rotates 180° in sync

### 2.5 Capture → Preview Transition
- Still frame of captured image shared-element expands from shutter area to fullscreen, 300ms
- System back: reverse

### 2.6 Tap-to-Focus Reticle
- Reticle appears at tap point: scale 1.4→1.0 (150ms) + corner brackets draw in
- On lock: accent color ping ring
- Auto-dismiss 1200ms fade

### 2.7 Toggles (flash, grid, sound)
- Icon morphs between states (flash off→on: bolt draws in) 150ms
- Glass panel background tint shift to accent at 12% alpha

### 2.8 Save Success
- ✓ morphs from shutter icon, check path draws 250ms
- Toast: slide up + fade, 2s, spring settle

### 2.9 Empty / Permission States
- Scanline illustration draws on with 450ms staggered line reveals (like a CRT warming up — on-brand)
- CTA button heartbeat pulse (scale 1.0→1.03, 1.6s loop, infinite — stops on touch)

### 2.10 Filter First-Launch Hint
- Strip auto-scrolls one item left→right once (600ms) with "swipe filters" tooltip bubble; plays once ever (DataStore flag)

---

## 3. Haptic Map (one-to-one with visuals)

| Trigger | HapticFeedback/ VibrationEffect |
|---|---|
| Filter selected | `KEYBOARD_TAP` |
| Shutter press | `KEYBOARD_TAP` (light) |
| Photo saved | `CONFIRM` |
| Permission denied / error | `REJECT` (rigid, double) |
| Favorite toggled | `TOGGLE_ON/OFF` |
| Slider boundary hit | `CLOCK_TICK` |

Guard: respect system "touch feedback" off setting; never haptic in Do-Not-Disturb-alike states (check `areHapticsEnabled` pattern).

---

## 4. Reduced Motion

When `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or user toggle:
- All springs → fades (150ms) or instant
- Flip/slide transitions → crossfade only
- Looping pulses disabled
- Haptics preserved (not visual)

---

## 5. Implementation Notes

- Compose: use `Modifier.animateItemPlacement()` for strip reorder; `Animatable` + `rememberInfiniteTransition` for loops; `updateTransition` for toggle morphs
- All shader-driven visual feedback (flash overlay, freeze frame) runs on the **GL thread** — post UI events via `Choreographer` to avoid jank
- Never block the render loop on an animation — animations are property changes consumed next frame
- Benchmark: capture-to-preview transition must stay under 300ms on mid-range; measure with Macrobenchmark `frameTiming` and record in CI
