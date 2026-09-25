# Floor Is Lava — Real Life Edition

A native Android app (Kotlin) where a random "safe zone" is placed on a real
map near your live GPS location. Tap START, a 2-minute timer begins, and
you have to physically walk to the green circle before time runs out. GPS
is checked continuously — stepping into the circle triggers an instant win.
An AR camera view can point you toward it in real time.

**No API key. No Google account signup. No config needed.**
Uses OpenStreetMap tiles via osmdroid — completely free, no registration.

## Setup (~5 minutes, no accounts required)

1. **Install Android Studio** (free): https://developer.android.com/studio
2. **Open this project**: Android Studio → Open → select the `FloorIsLava` folder
3. Let it sync (auto-downloads Gradle + osmdroid + CameraX + AndroidX — no key needed anywhere)
4. Plug in an Android phone (USB debugging on) or use an emulator
5. Click the green ▶ **Run** button — the app installs and launches

## Get the standalone `.apk` file (to share/install elsewhere)

No Android Studio needed: every push is built by GitHub Actions
(`.github/workflows/build-apk.yml`) and the APK is published to the
[`floor-is-lava-apk` release](https://github.com/poodicraft/The-Floor-Is-Lava/releases/tag/floor-is-lava-apk)
as `FloorIsLava.apk`.

To build it yourself in Android Studio instead:

- Build menu → Generate App Bundles or APKs → Generate APK(s)
- Android Studio shows a notification with a "locate" link
- File will be at `app/build/outputs/apk/debug/app-debug.apk`
- Copy it to any Android phone and tap to install (enable "Install from
  unknown sources" if prompted — normal for APKs installed outside the Play Store)

## Main menu

- Animated lava backdrop with rising embers
- Big pulsing **PLAY** button
- **Difficulty** picker, remembered between launches:

  | Difficulty | Time | Safe zone distance | Safe zone size |
  |------------|------|--------------------|----------------|
  | Easy       | 3:00 | 15–30 m            | 8 m radius     |
  | Normal     | 2:00 | 15–45 m            | 6 m radius     |
  | Hard       | 1:15 | 30–70 m            | 5 m radius     |

- **Your record**: wins, win rate, best streak and fastest escape on the
  selected difficulty
- **How to play** guide and a **vibration** on/off toggle

## Game rules implemented

- Real OpenStreetMap map centered on your live GPS position, dark lava/ember
  theme overlaid on top
- Tapping **START** searches for a safe-zone spot 15–45 meters away that is
  VERIFIED clear of buildings, water, roads, railways, fences, and private
  land — see below for exactly how
- **Countdown** per round (length depends on difficulty); a red lava glow
  creeps in from the screen edges as time runs out, and the final 10 seconds
  pulse the timer with a vibration tick each second
- Your live position is tracked continuously (high-accuracy GPS, ~700ms
  updates) and shown as a marker with a **compass-direction arrow** that
  rotates to match which way you're physically facing
- Marker movement is smoothly animated between GPS fixes rather than
  snapping, so it looks fluid even though raw GPS updates are coarser
- **Live distance-to-safe-zone** shown in a dedicated meter readout
- A **camera icon button** appears once a round starts — opens a full-screen
  AR view (see below)
- Stepping inside the ~12-meter-wide green circle before time runs out = **win**
- Timer hits zero while you're still outside the zone = **lose**
- Win/lose screens themed accordingly, showing your escape time (and
  whether it's a new best), win streak, or how close you got, with **Play
  Again** and **Main Menu** buttons
- ✕ button / back gesture returns to the menu, asking first if a round is live

## AR camera direction view

Tapping the camera button during a round opens a full-screen live camera
feed with a floating green marker showing exactly where the safe zone is —
pan your phone around and the marker stays anchored to that real-world
direction, the way AR wayfinding apps work. It shows live distance, grows
larger as you get closer and shrinks as you move away (like a real object
would), and sits at the correct ground-level height based on real
trigonometry — not a fixed guess — so it doesn't float unrealistically high
regardless of distance. If the safe zone is off-screen, an arrow points
which way to turn or tilt: left/right if it's behind you, up/down if it's
above or below the camera's current view.

This is built from the phone's own compass (rotation vector sensor,
correctly remapped for how a phone is actually held during AR/camera use)
and the device's real camera field of view (read from its actual sensor
size and focal length, not a generic guess) — not a heavyweight AR SDK like
ARCore, so treat it as a fun directional aid rather than pinpoint-precise
AR. It works best held roughly upright and panned smoothly; fast whips can
still make the marker jump around briefly, since that's a real limitation
of phone compass sensors in general (there's no gyroscope-based smoothing
layer on top, which is what dedicated AR SDKs add).

## How the "verified reachable safe zone" check works

When you tap START, the app generates a candidate point and asks
OpenStreetMap's free Overpass API for every building, water, road, railway,
fence, and private-land feature within 40 meters of it. Area features
(buildings, water, pools, construction sites) get a real point-in-polygon
geometry test — checking whether the point falls inside the actual shape,
not just whether something is nearby, so large buildings are caught too.
Line features (roads, railways, fences) get a minimum-distance check instead,
since standing right next to a live traffic lane is just as unsafe as
standing on it.

This is a safety-critical check, so it's deliberately strict: if the network
request fails, times out, or can't be verified after 3 retries, that
candidate point is treated as **blocked**, never as "probably fine." The
app tries up to 15 different candidate points looking for one it can
positively confirm is clear. If every single one fails verification (e.g.
you have no internet connection at that moment), the app tells you plainly
rather than silently guessing.

Realistic limits worth knowing: this only catches what's actually mapped on
OpenStreetMap. Coverage is generally strong for buildings/roads in cities
and decent in most suburbs, but a brand-new building, an unmapped private
driveway, or a rural area with sparse OSM data could still slip through.
There's no fully bulletproof version of this without a paid, much more
complete geodata service.

## About "Home Mode" (removed)

An earlier version of this app had a mode that detected whether you were
standing inside your own house and placed the safe zone somewhere in it.
This was removed because GPS accuracy indoors is fundamentally unreliable —
walls and roofs weaken satellite signal enough that phones commonly report
a position 10–50 meters off while indoors. This isn't fixable in-app with
GPS alone; a real fix would need a different positioning method entirely
(e.g. manually marking the house's corners once, or WiFi/Bluetooth indoor
positioning).

## Project structure

```
FloorIsLava/
├── app/
│   ├── src/main/
│   │   ├── java/com/lava/floorislava/
│   │   │   ├── LavaApp.kt          # Application class
│   │   │   ├── SplashActivity.kt   # Permission request screen
│   │   │   ├── GameActivity.kt     # Core game loop (map, timer, win/lose)
│   │   │   ├── ArActivity.kt       # AR camera direction view
│   │   │   ├── ArOverlayView.kt    # Custom View that draws the floating AR marker
│   │   │   ├── ArMath.kt           # Bearing/projection math for the AR view
│   │   │   ├── GeoUtils.kt         # Random point + distance math
│   │   │   └── OverpassChecker.kt  # Verifies points against real OSM building/water/road/rail shapes
│   │   ├── res/
│   │   │   ├── layout/             # activity_splash.xml, activity_game.xml, activity_ar.xml
│   │   │   ├── drawable/           # Lava-themed backgrounds, buttons, icons
│   │   │   └── values/             # colors.xml, strings.xml, themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## Tuning the difficulty

In `GameActivity.kt`:
- `gameDurationMs` — round length (default 120,000 ms = 2 minutes)
- `safeZoneMinRadiusM` / `safeZoneMaxRadiusM` — how far away the safe zone
  can spawn (default 15–45 m)
- `safeZoneRadiusM` — physical size of the green circle (default 6 m radius,
  ~12 m across)
- `maxSafeZoneAttempts` — how many verified candidate points to try before
  giving up and telling the player to retry (default 15)

In `OverpassChecker.kt`:
- `SEARCH_RADIUS_METERS` — how far out to fetch area shapes for checking (default 40 m)
- `ROAD_BUFFER_METERS` — minimum distance to keep from roads/rails/fences (default 8 m)

In `ArActivity.kt`:
- `horizontalFovDeg` / `verticalFovDeg` — assumed camera field of view used
  for projecting the marker onto screen space (defaults 60°/45°, reasonable
  for most phone rear cameras but not device-exact)
