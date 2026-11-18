# ADR 0005: UI architecture of the ride app

Status: accepted (stage M1-A, 2026-11-18)

## Context

Stage M1-A turns the design (ADR 0002, `design/system.md`, the mock-ups) into Jetpack Compose and wires it to
the simulated ride engine of ADR 0004, so the owner can ride a simulated workout on the tablet. M1-B adds
the overlay window and service and the permission onboarding, M2b adds persistence and FIT, M3 adds real
Bluetooth. This ADR records the decisions M1-A had to make where the design or the engine was silent, and what the
later stages can rely on.

## Decision

### Modules and dependencies

- `ui-kit` is the design system in code and knows nothing about `core` or the engine: it takes plain values
  (`OverlayUiState`, `ProfileData`, `TracePoint`). `app-ride` maps `RideSnapshot` to those values
  (`RideMapping`). Both depend on the generated tokens (`OverdriveTokens.kt`, ADR 0002).
- **Compose foundation only, no Material 3.** The design specifies every component down to the pixel, flat,
  without ripple, elevation or tonal colour. Material 3 would be used for nothing but its text and
  a `Surface`, and every one of its defaults (ripple, minimum touch size, colour roles, overscroll glow) would have to be
  switched off. `OverdriveTheme` provides what foundation needs: `LocalContentColor`, `LocalTextStyle`, the
  `LocalMotionEnabled` flag and no overscroll effect.
- **Text lines are exact.** Compose does not shrink a first line below the font's natural height (Geist: ascent
  1.0 em, descent 0.29 em), so a 24 px style with a 20 px line comes out at 29 px and the overlay measured 91 dp
  instead of 84 dp. `Modifier.nominalLine` in `OdText` clamps the layout to the nominal line height, centres the
  glyph box and re-exports the first and last baseline, so rows can align on baselines (`alignByBaseline`). Tests
  assert the overlay sizes of design/system.md §5.1: 464 by 84, 282 and 159 dp.
- **Dependencies added** (all through `gradle/libs.versions.toml`): Compose foundation (from the existing BOM),
  `lifecycle-runtime-compose`, DataStore Preferences 1.3.0; for tests Robolectric, Roborazzi and Compose UI test.
  No DI framework, no navigation library, no image loader.

### Icons

`design/tools/build-icons.mjs` converts the 34 Lucide SVGs of `design/icons/` into `ImageVector`s
(`OverdriveIcons.kt`, generated, excluded from ktlint). Only the shapes Lucide uses are handled (paths, rects, circles,
lines, polylines); the generator fails on anything else instead of drawing something wrong. Stroke 1.5, round caps
and joins, as in the mock-ups. `--check` fails when the generated file is stale and runs in CI next to the tokens
check. Lucide is ISC licensed: the licence text ships in `ui-kit/src/main/assets/licenses/lucide-ISC.txt`
(the generator copies it) and the app's credits must name it.

### State holders (`app-ride`)

- **`AppContainer`** is owned by the `Application` and holds the whole object graph: the settings repository, the
  workout library, the one `RideHost` and the media controller. The activity only renders; rotating the tablet or
  leaving to a video app and returning never touches a ride. M1-B's overlay service reads the same container.
- **`RideHost`** runs one ride behind the `RideSessionFactory` interface on
  `Dispatchers.Default.limitedParallelism(1)` (the simulated devices are single-threaded) and exposes
  `StateFlow<RideSnapshot>` plus `RideUi` (snapshot, statistics, workout profile, finished summary) and
  `DevicesUi`. It prepares a session for the chosen `RideChoice` (workout or free ride), re-prepares after 400 ms
  when a ride-affecting setting changes while idle or ready, never replaces a ride that has started, and `reset()`
  returns to ready after the summary. `SimulatedRideSessionFactory` is the only implementation; M3 adds one over
  the BLE adapters and nothing above the interface changes.
- **`RideActions`** is the single dispatcher of everything the rider can do: start, pause, resume, finish,
  previous and next interval, intensity and gear steps. The screen buttons and the Click use it, so there is one
  routing rule: in ERG the Click's gear buttons change intensity, in a free-ride step they change gear
  (design/system.md §5.2).
- **`RideStatsAccumulator`** is fed by the host (not the UI, so nothing is lost while the screen is off) and
  produces the average power, the lap average and the 10 s power trace; a trace point is published when the workout
  position leaves its bucket, so the chart changes once per 10 s.
- **`SettingsRepository`** keeps `AppSettings` in DataStore Preferences. Every read and every write goes through
  `AppSettings.sanitized()`, so a value edited or corrupted on disk can never reach the engine out of range
  and the Settings screen needs no checks of its own. Ranges: FTP 50 to 600 W in steps of 5, gears 10 to 30, grade per gear
  0.5 to 2.0 %, system mass 40 to 200 kg, CdA 0.10 to 1.00, Crr 0.001 to 0.020, overlay opacity 85 to 100 %.
- **Developer settings** (a group at the end of Settings): demo speed 1×, 10× or 60× (the engine's and the bike's
  `tickInterval`), scripted dropouts (heart rate at 30 s for 20 s, cadence at 75 s for 10 s, power at 105 s for 6 s,
  trainer link at 150 s for 12 s) and the simulated Click state (connected, locked, not paired). They exist so the "—"
  and pause states can be seen without waiting and are persisted like any setting.

### Navigation and screens

- One `ComponentActivity`, sensor landscape. Navigation is the enum `Screen { Ride, Devices, Settings }` in
  `rememberSaveable`, a `BackHandler`, and a 200 ms cross-fade between pages (off when the system's "remove
  animations" is on). The ride screen's four states (ready, riding, paused, finished) follow the snapshot, not
  the navigation state, so the ride never depends on where the rider is looking.
- The screen stays on while a ride is live (`FLAG_KEEP_SCREEN_ON` through a `DisposableEffect`), never otherwise.
- **In-app overlay preview** behind the overlay button of the top bar: the real `OverlayCompact` or `OverlayDetailed`
  over the ride screen, tap toggles the mode. It is what the owner sees until M1-B adds the window.
- Workouts: the bundled sweet-spot JSON (a copy of the contract fixture), a 10-minute test workout touching every
  step type, a ZWO sample, free ride, and "Open file" through the Storage Access Framework (read-only, 1 MB cap,
  the first character tells JSON from ZWO). An invalid file produces a notice that names the file and says what to do;
  it never throws.

### The overlay composables

`OverlayCompact` and `OverlayDetailed` take one immutable `OverlayUiState` and an `OverlaySettings` and contain
no window code. Width is exactly 464 dp, slots are fixed-width so values do not move, a missing value is "—",
paused dims the values, the fifth cell is the gear in a free ride and target with intensity in ERG. Every cell is its own
skippable composable with primitive parameters, so it recomposes only when its displayed value changes; there is no
animation at rest. Evidence: the compose compiler report lists every overlay composable as skippable with
stable parameters (checked once, the report configuration is not kept). The frame is `width(464) + surface +
1 dp hairline + 12 dp vertical padding`.

### Screenshot pipeline and tests

- **Roborazzi 1.79.0 on Robolectric 4.18 (SDK 35)**, JVM only, no emulator. Paparazzi was not tried: Roborazzi
  renders real Compose through Robolectric's graphics shadow, runs in the same `testDebugUnitTest` as the other
  tests and needs no layoutlib download. Setting `roborazzi.test.record=true` makes every test write its PNG to
  `app-ride/build/shots/`; they are never committed (the design is judged by looking at them against `design/mockups/`).
- JUnit4 unit tests: formatting helpers, snapshot to UI mapping, statistics, settings validation and
  persistence (DataStore on a temporary folder), workout files and descriptions, `RideHost` lifecycle (virtual time with
  `kotlinx-coroutines-test`), `RideActions` routing, and Compose interaction tests that press every on-screen action.
- Release builds are shrunk by R8 with the default optimising rules. DataStore Preferences, Compose and `core` ship
  their own consumer rules and the app needs none of its own; if a future library does, add `proguard-rules.pro` to
  `app-ride` and list it in `proguardFiles`.

### Honest omissions

- No "Or start pedalling" hint: the engine cannot detect pedalling in the ready state (M3 with real devices).
- The finished screen says "Ride finished" and "This ride is not saved yet"; there is no Share FIT button or sync
  line before persistence and FIT (M2b). The finish sheet asks "Finish this ride?".
- "Change button mapping" on the Devices screen is disabled: remapping the Click is not in Phase 1's scope.
- The Ready screen's mode reads "ERG + gears" for a workout that contains a free step; the mock-up shows "ERG".
- The device row's countdown ("Retry in 8 s") reads "retrying": the simulated devices do not publish their backoff.
- The paused banner is 48 dp high as in spec §4.1; the mock-up CSS squeezed it to about 40 dp.

## What M1-B needs to know

(Written before M1-B. The stage is built and its decisions, including where they refine this list, are in
[ADR 0006](0006-overlay-service.md).)

- **Same instances.** The overlay service gets the container from the `Application` (`OverdriveApp.container`),
  collects `host.snapshots` (a `StateFlow` of the engine's snapshots) and `host.ui` for the workout profile, and
  renders `RideMapping.overlayState(snapshot, profile)` through `OverlayCompact` or `OverlayDetailed` in a
  `ComposeView` in the window. Do not build a second `RideHost`.
- **Settings keys** already exist and are sanitized: `overlay_mode` (`Compact` or `Detailed`, the mode the overlay
  starts in), `overlay_opacity_percent`, `overlay_blur`, `overlay_x` and `overlay_y` (the window's top-left corner as a
  fraction 0 to 1 of the screen, unset until the first drag; `resetOverlayPosition()` clears both and the Settings
  screen has a button for it). Persist the position when a drag ends, not while it moves. The overlay's
  blur is a window flag (`FLAG_BLUR_BEHIND` and `setBlurBehindRadius`, Android 12 and later); the composables only
  draw the translucent surface and never blur.
- **The overlay composables stay window-free.** Dragging, snapping to edges and the touch region belong to the
  service; the composable already reports nothing and takes no gestures. Keep the window at 464 dp wide and let it
  wrap its height (84, 159 or 282 dp).
- **Permissions and services** (assumptions to verify on the tablet): drawing needs `SYSTEM_ALERT_WINDOW`
  ("Display over other apps"), which only the system settings screen can grant, so onboarding sends the rider to
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` and re-checks `Settings.canDrawOverlays` on resume; the service is a
  foreground service (type `specialUse` or `connectedDevice` once BLE exists) with a persistent notification, started from the
  ride screen's Start so that Android 12's background-start limits do not apply, and stopped by the host's reset.
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` keeps the video app's keyboard and back button working; touches on the
  overlay itself are taken by the window. The manifest currently declares no permissions.
- **Performance.** Collect `snapshots` with `distinctUntilChanged` on the mapped `OverlayUiState`; it is a data
  class, so an unchanged second costs nothing. Dragging must move the window (`WindowManager.updateViewLayout`),
  not recompose the content.

## Consequences

- No Material 3: the next component is hand-built to the design, in the same few lines as the others. The
  design stays the single source; there is no default look to fight.
- The generated icon file is large and unformatted by design; the generator and its `--check` are the interface.
- Screenshots are evidence for review, not a regression gate: nothing compares them pixel for pixel, so a design
  regression is found by looking. Geometry that matters (the overlay sizes) is asserted by tests instead.
- `RideHost` re-preparing on a setting change means a ready ride is rebuilt in the background; a ride that started
  is never touched, so a setting changed mid-ride applies to the next ride.
