# Software 6 Port for Android

A **2.5D raycasting engine** ported to Android — the same technique used in classic '90s shooters like Wolfenstein 3D and Doom (the "2.5D" part refers to the fact that the world looks 3D but is actually drawn from a flat 2D map).

**Original game**: *Software 6* by Just Developer's Ltd. (2025)  
**Android port by**: Ian Kernels / Dead Line Studio

---

## Features

- **Raycasting renderer** — all walls, floors and ceilings are drawn pixel-by-pixel on the CPU using a DDA (Digital Differential Analyzer) ray-marching algorithm
- **5 wall texture types** — brick, stone, wood, tiles, and animated doors (64×64 resolution)
- **Interactive doors** — push USE next to a door to open it; it closes automatically after a few seconds
- **4 weapons** — Knife (infinite ammo), Pistol, Machine Gun, and Chain Gun, each with its own fire rate, damage, ammo count, and muzzle-flash animation
- **Touch controls**:
  - **Left half of screen** — virtual joystick for movement (forward/backward/strafe)
  - **Right half of screen** — swipe left/right to rotate the camera
  - **Bottom-right buttons** — FIRE (hold to shoot) and USE (interact with doors/objects)
  - **Top-right weapon buttons** — KNF / PST / MG / CG to switch weapons
- **HUD** — shows current weapon name and ammo count
- **Settings panel** (top-left gear icon):
  - Graphics quality: Low (240p) / Medium (300p) / High (480p) — affects internal render resolution
  - Camera rotation sensitivity
  - Toggle minimap visibility (top-right corner; shows walls, doors, player position and direction)
  - Toggle FPS and RAM overlay
- **Minimap** — bird's-eye view of the level with player position and facing direction
- **Performance HUD** — live FPS counter and memory usage in MB

---

## Controls reference

| Control | Action |
|---|---|
| Left-side joystick | Move / strafe |
| Right-side swipe (left/right) | Turn camera |
| FIRE button (hold) | Shoot current weapon |
| USE button | Interact with doors |
| KNF / PST / MG / CG buttons | Switch weapon (Knife / Pistol / Machine Gun / Chain Gun) |
| Gear icon (top-left) | Open settings |

---

## Technical notes

- Entirely **software-rendered** — no OpenGL or Vulkan. The frame is built into an `int[]` pixel buffer, written to a `Bitmap`, and drawn onto a `SurfaceView` via `Canvas`
- The game runs in its own thread (separate from the UI thread) at whatever framerate the device can sustain
- Doors use a simple state machine: CLOSED → OPENING → OPEN → CLOSING → CLOSED
- The weapons spritesheet is sliced programmatically at runtime (5 columns × 4 rows); white background is stripped for transparency
- Settings are persisted via `SharedPreferences` and restored on startup

---

## What's next (planned)

- Enemies with AI and hit detection
- Sound effects and music
- More levels with different themes
- Animated pickup items (ammo, health)
- Proper sprite rendering (not just weapons)
- Varying floor/ceiling heights

---

## Build

Open in Android Studio, sync Gradle (minSdk 21, targetSdk 33), and run on a device or emulator.

*Release APK is included under `app/release/`.*