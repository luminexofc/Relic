# Design.md — Relic

## 1. Design Language: "Terminal Glass"

A dark, glassmorphic UI (consistent with your Sway aesthetic) where the camera viewfinder is the hero and UI chrome floats as translucent panels. Retro-futurism: monospace accents, scanline motifs in empty states, neon accent color.

### Palette

| Token | Hex | Usage |
|---|---|---|
| `bg/primary` | #0A0A0F | App background, behind glass |
| `bg/glass` | rgba(20,20,30,0.55) + blur | Floating panels |
| `accent` | #7CFC9B (phosphor green) | Primary actions, active filter |
| `accent/2` | #FF5C8A | Secondary accent (record, alerts) |
| `text/primary` | #F2F2F7 | Labels |
| `text/dim` | rgba(242,242,247,0.55) | Captions, metadata |

### Typography
- **UI:** Inter or Roboto (system default)
- **Retro accents / filter names:** JetBrains Mono or Space Mono
- **Filter names** render in mono uppercase — reinforces the procedural identity

### Shape & Elevation
- Panels: 16dp corner radius, 1dp stroke rgba(255,255,255,0.08)
- Buttons: pill-shaped, 52dp min touch target
- Backdrop blur: ~24px equivalent (RenderEffect on API 31+, fallback transparency)

---

## 2. Screens

### 2.1 Camera (Home) Screen
```
┌─────────────────────────┐
│  ⚡flash   RELIC  ⟲flip│  ← glass status bar (48dp)
│                         │
│                         │
│      VIEWFINDER         │  ← full-bleed, filter live
│      (tap = focus)      │
│                         │
│                         │
│ ┌─intensity slider─┐    │  ← appears when filter selected
│ │ ●──────○───────── │    │
│ └──────────────────┘    │
│ ◄ FILTER·DITHER ►       │  ← horizontal strip, 72dp tall
│ [gallery]   (●)   [⚙]   │  ← shutter center 76dp ring
└─────────────────────────┘
```

- **Filter strip:** horizontal RecyclerView, snap to center, selected item scales 1.15× with accent underline. Names in mono caps.
- **Shutter:** tap = capture (spring scale 0.88→1.0 + haptic). Long-press = burst (Phase 2).
- **Tap-to-focus:** reticle animation (200ms), auto-dismiss.

### 2.2 Captured Preview Screen
- Full-bleed result, top bar: ✕ (retake) / ✓ (save) / ✂️ (crop, Phase 2)
- Bottom: **Save to Gallery** primary button + Share row (system share sheet)
- Saved state: checkmark morph + toast "Saved ✓"

### 2.3 Settings Sheet (bottom sheet)
- Sound on/off, Save location, Grid overlay, Performance mode, Favorites reorder, About/credits

### 2.4 Empty / Permission State
- Camera denied: scanline-pattern illustration, "Enable camera" CTA → system settings intent

---

## 3. Navigation

Single-activity architecture:
`CameraFragment ↔ CapturedPreviewFragment` (add-slide transition)
Settings = modal bottom sheet (no navigation stack).

---

## 4. Key Interactions

| Interaction | Response |
|---|---|
| Swipe filter strip | Live shader swaps mid-frame (no reload) |
| Select filter | 150ms scale-up, accent underline slide, light haptic |
| Adjust intensity | Real-time uniform update, debounced param persistence |
| Capture | Shutter ring compress, 120ms freeze-frame flash overlay, success haptic |
| Flip camera | Viewfinder horizontal flip animation (300ms, ease-out) |

---

## 5. Filter Catalog Presentation

Filters are grouped into collapsible sections in the strip (Phase 3), each with a **live thumbnail**: the current camera frame rendered through the filter at 96×96. Thumbnails update only when strip is visible (perf guard).

---

## 6. Accessibility

- All controls ≥ 48dp, content descriptions on every icon button
- Intensity slider: 1% steps, announces value via AccessibilityNodeInfo
- Colorblind-safe: never encode state in color alone (selected filter = scale + underline + label)
- Reduced motion: honors `prefersReducedMotion` — disables flip/parallax, keeps fades only
