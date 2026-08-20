# The Floor Is Lava — Sketch Platformer

Draw a level on paper, photograph it, play it. An Android app in Kotlin, Jetpack Compose
and CameraX that turns a hand-drawn sketch into a real 2D platformer: dark lines become
solid ground, red becomes lava, and the little blue guy has to reach the blue flag.

## What the detector reads

| What you draw          | What it becomes                        |
| ---------------------- | -------------------------------------- |
| Dark / black lines     | Solid platforms and colliders          |
| Green dot              | Player spawn point                     |
| Red areas              | Lava — touching it is game over        |
| Yellow / gold dots     | Collectible coins                      |
| Blue area              | Goal flag — reach it to win            |

Anything else on the page (paper colour, shadows, faint grid lines) is ignored.

### Tips for a good level

* Draw on white or light paper with a **thick, dark** pen — a fine pencil line can be
  thinner than one grid cell.
* Photograph the page straight on, filling the frame, with even light and no hard shadow
  across the paper.
* Keep in mind what the player can do: a jump clears about **23% of the page's width** and
  **18% of its height**, whatever the resolution the photo was sampled at. Gaps and ledge
  steps bigger than that are not reachable.
* Colour dots need to be at least a few grid cells across — a 2 px speck is treated as
  noise on purpose.
* No paper handy? The capture screen has a **sample sketch** that runs through the exact
  same pipeline.

## How it works

```
CameraScreen ──photo──> SketchGameViewModel ──> ImageProcessor ──> LevelBuilder ──> LevelData
                              │                                                        │
                        LevelTuneScreen  <──preview (GameRenderer)──────────────────────┤
                              │                                                        │
                            GameView ──> GameEngine (physics) ──> GameRenderer <────────┘
```

### 1. Capture — `capture/`

* `CameraScreen.kt` — CameraX `Preview` + `ImageCapture` in an `AndroidView`, the Android
  Photo Picker (`PickVisualMedia`, no storage permission needed) and the sample sketch.
* `ImageUtils.kt` — decoding that downsamples *while* decoding, plus EXIF/sensor rotation.
  A 12 MP photo is never fully materialised as a bitmap.

### 2. Detection — `processing/`

* `ImageProcessor.kt` — the `Bitmap` facing wrapper: scale to a 900 px analysis copy,
  normalise to `ARGB_8888`, hand the raw pixels over.
* `LevelBuilder.kt` — the actual pipeline, and pure Kotlin (no Android imports at all):
  1. **Downsample** into a grid. Each cell keeps the *darkest* pixel it saw (thin pen lines
     survive) and the *most saturated* pixel it saw (small colour dots survive).
  2. **Otsu threshold** over the per-cell darkness histogram — the ink/paper split adapts
     to the room's lighting instead of using a magic constant.
  3. **Classify**: saturated cells snap to the nearest reference hue (red 0°, yellow 50°,
     green 130°, blue 215°); remaining dark cells are platforms.
  4. **Denoise**: speckle filter for ink, minimum-blob-size filter for colour.
  5. **Vectorise**: greedy rectangle merging for platforms and lava, connected components
     for coins, the biggest green blob for the spawn, the biggest blue blob for the goal.
* `LevelData.kt` — the level model: a solid mask, a lava mask, merged rectangles, coins,
  spawn, goal and any `warnings` the detector wants to show the player.

### 3. Play — `game/`

* `GameEngine.kt` — gravity, acceleration/friction, variable jump height, coyote time,
  jump buffering, axis-separated **AABB collision against the dark-pixel grid**, coins,
  lava and the goal. Framework-free and unit tested.
  * A **fixed timestep** accumulator means a stutter or a 120 Hz screen cannot change how
    high the player jumps or let them tunnel through a platform.
  * `Tuning.forLevel()` scales every *length* with the grid resolution and leaves every
    *duration* alone, so the "detail" slider changes fidelity, never difficulty.
* `GameRenderer.kt` — all `DrawScope` drawing, in world units. Shared by the game and the
  tuning preview, so what you tune is exactly what you play.
* `GameView.kt` — the `withFrameNanos` loop, HUD, hold-to-move touch controls and the
  win/lose overlays. The loop writes one `Float` of state that is read inside the draw
  lambda, so frames invalidate **only the draw phase** — nothing recomposes at 60 fps.

### 4. Shell

`MainActivity.kt` (single activity) → `SketchPlatformerApp.kt` (Navigation Compose:
capture → tune → game) → `SketchGameViewModel.kt`, which owns the bitmap and the level so
they survive rotation and navigation.

## Build and run

```bash
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew installDebug           # onto a connected device
./gradlew testDebugUnitTest      # detector + physics tests, no emulator needed
```

Requirements: JDK 17, Android SDK 35, `minSdk 26`. CI (`.github/workflows/android.yml`)
runs the unit tests, lint and an APK build on every push.

## Tests

`app/src/test/` covers the two parts worth covering, both on the plain JVM:

* `LevelBuilderTest` — platforms/lava/coins/spawn/goal are found in a synthetic sketch, a
  blank page still yields a playable level plus a warning, hues snap correctly, Otsu splits
  ink from paper, rectangle merging is exact and colour specks are ignored.
* `GameEngineTest` — landing, jump arcs, variable jump height, walls, lava, falling off the
  page, coin pickup, winning, restart, frame-rate independence and resolution independence.
