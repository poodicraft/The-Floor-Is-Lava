# Paper Jump

Draw a level — on paper or on the screen — and play it. An Android app in Kotlin, Jetpack
Compose and CameraX that turns a hand-drawn sketch into a real 2D platformer: dark lines
become solid ground, red becomes lava, and the little blue guy has to reach the blue flag.

> Lives in the `The-Floor-Is-Lava` repository for historical reasons — it is a new,
> unrelated game, not a version of the GPS game that repository started as.

## Two ways to make a level

* **Draw it in the app.** A sketchpad with a pen per element (ground, lava, coin, start,
  flag), an eraser, undo/redo and a size slider.
* **Photograph a real drawing.** Point the camera at a page, or pick a photo from the
  gallery.

Either way the result goes through the *same* detector: the sketchpad rasterises its
strokes to a bitmap first, so a drawn level and a photographed one cannot behave
differently.

## Game types

| Mode | Rules |
| ---- | ----- |
| Classic | Reach the flag. No clock, no conditions. |
| Coin hunt | The flag stays shut until every coin has been collected. |
| Time attack | A countdown sized to the level. Reach the flag before it runs out. |
| Rising lava | Lava floods the page from the bottom and keeps coming. |

Modes only change the win and lose conditions — never the physics — so every level is
playable in all four. Both the countdown and the flood speed are derived from the level's
size in a way that ignores the detail slider, the same property the physics already had.

## What the detector reads

| What you draw          | What it becomes                        |
| ---------------------- | -------------------------------------- |
| Dark / black lines     | Solid platforms and colliders          |
| Green dot              | Player spawn point                     |
| Red areas              | Lava — touching it is game over        |
| Yellow / gold dots     | Collectible coins                      |
| Blue area              | Goal flag — reach it to win            |
| Purple areas           | Creatures that patrol and hurt         |

Anything else on the page (paper colour, shadows, faint grid lines) is ignored.

### Or draw anything at all

"Draw anything" is a second, optional route: draw whatever you like with no colour code and
a hosted vision model designs a level from the picture. It needs a free Hugging Face key,
which is typed into Settings and kept on the device — the app never ships one, because a key
compiled into an APK can be read straight back out of it.

The model's answer is not trusted with anything: it comes back as a plan in normalised
coordinates, which `PlanPainter` paints as an ordinary sketch in the app's own ink colours,
which the ordinary detector then reads. So the AI designs the level and the app still builds
it — an AI level is saved, re-tuned and replayed like any other, and needs neither key nor
signal the second time. If the call fails for any reason, the drawing is read the ordinary
way instead.

This is the only part of the app that uses the network.

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
     green 130°, blue 215°, purple 288°); remaining dark cells are platforms.
  4. **Denoise**: speckle filter for ink, minimum-blob-size filter for colour.
  5. **Read the ink as lines**: each connected run is reduced to its spine, the spine is
     simplified with Ramer–Douglas–Peucker, and the ink is redrawn from those vertices at
     an even thickness. A hand-drawn ledge comes back as one clean bar at the angle it was
     drawn at, instead of a staircase of blocks following every wobble of the pen; a line
     that barely leans is snapped exactly level. Filled shapes have no meaningful spine, so
     they are left as drawn.
  6. **Vectorise the rest**: greedy rectangle merging for whatever was not a line,
     connected components for coins and creatures, the biggest green blob for the spawn,
     the biggest blue blob for the goal.
* `LevelData.kt` — the level model: a solid mask, a lava mask, the recognised lines, merged
  rectangles for everything else, coins, creatures, spawn, goal and any `warnings` the
  detector wants to show the player.

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

### 4. Sketchpad — `draw/`

* `DrawingState.kt` — the document, in pure Kotlin. Points are stored as fractions of the
  sheet, so a drawing survives rotation and renders at any size. History is kept as
  whole-document **snapshots**, which is what makes an eraser sweep or a clear exactly one
  undo step instead of one per stroke.
* `DrawingController.kt` — editing state with a stable identity, so the long-lived gesture
  handler cannot capture a stale tool or a stale document.
* `StrokeRasterizer.kt` — paints the sheet at 1200px in ink colours picked to land on the
  detector's reference hues, then hands it to `ImageProcessor` like any photo.

### 5. Shell — `ui/`, `data/`

`MainActivity.kt` (single activity) → `PaperJumpApp.kt` (Navigation Compose:
home → draw/photograph/library → tune → mode → game) → `SketchGameViewModel.kt`, which owns
the bitmap, the level, the sketchpad and the library so they survive rotation and
navigation.

* **My levels** — saved as the *source image plus its tuning*, not as a serialised level.
  The level is re-derived in milliseconds by the current detector, so improving detection
  improves every level already in the library instead of leaving them frozen.
* **Personal bests** — kept per level *content* and per mode, so a level keeps its records
  whether or not it was ever saved.
* **Settings** — theme, which side the jump button sits on, button size, vibration, timer.
  Everything on that screen changes something.

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
* `GameModeTest` — each mode's win and lose conditions, and that neither the countdown nor
  the flood speed moves when the same drawing is sampled at a different resolution.
* `DrawingStateTest` — undo/redo, single-step erase and clear, and hit-testing against the
  *middle* of a long stroke rather than only its sampled points.
* `PersistenceCodecTest` — round trips for both on-disk formats, names containing `=` or a
  newline, and proof that one corrupt row cannot take the library down with it.
