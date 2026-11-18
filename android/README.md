# android

One Gradle build for everything that runs on Android. The shared `core` is a separate build in
`../core`, pulled in with `includeBuild` (see `settings.gradle.kts`).

| Module | Contents | Spec |
|---|---|---|
| `ble` | Bluetooth transport behind an interface | §7.1 |
| `data` | The rides folder: `RideStore` (log, record, FIT, atomic finalize, scan) and `RideLogWriter`; the outbox and sync client come with §4.9 | §4.2, §4.8, §4.9, §7.1 |
| `ui-kit` | Design system: theme on the generated tokens, components, charts, the overlay composables, Lucide icons | §7.4 |
| `app-ride` | Tablet app: ride, devices and settings screens over a simulated ride; the overlay service and window | §4 |
| `app-companion` | Phone app: statistics, Health Connect reader | §6.1 |

Dependency direction: apps → `ui-kit`, `data`, `ble` → `core`.

## Building

Needs a JDK 17+ and the Android SDK with platform 37 (`ANDROID_HOME`, or `sdk.dir` in a git-ignored
`local.properties`).

```bash
./gradlew check assembleDebug   # tests, lint, ktlint, debug APKs
./gradlew assembleRelease       # R8-shrunk release APKs (unsigned without the signing environment)
./gradlew spotlessApply         # format
```

APKs land in `app-*/build/outputs/apk/debug/` and are sideloaded (spec §7.6). No dependency on
Google Play Services, anywhere.

## UI stack (ADR 0005)

- **Compose foundation only, no Material 3.** `ui-kit` draws every component from the design tokens;
  `OverdriveTheme` is dark only. Text goes through `OdText` and the `OdType` styles, which fix the line
  heights the design specifies.
- **Generated code.** `ui-kit/.../tokens/OverdriveTokens.kt` comes from `design/tokens.json`
  (`node design/tools/build-tokens.mjs`) and `ui-kit/.../icons/OverdriveIcons.kt` from `design/icons/*.svg`
  (`node design/tools/build-icons.mjs`). Both tools have `--check`, which CI runs; the files are excluded from ktlint.
  The Lucide icons are ISC licensed, the licence is in `ui-kit/src/main/assets/licenses/lucide-ISC.txt`.
- **`app-ride`.** `AppContainer` (owned by `OverdriveApp`) holds the settings (DataStore Preferences), the workout
  library and the one `RideHost`, which runs a `RideSession` from a `RideSessionFactory` on a background
  dispatcher. Today the factory builds a `RideEngine` over `SimulatedBike`; real Bluetooth replaces it in M3.
  Screens only read `StateFlow`s and call `RideActions`. `rides` (`RideLibrary`) owns the files (below).
- **Previews.** `ui-kit/.../preview/` has `@Preview`s for every component and state, and the overlay in each mode.
- **Developer settings** (end of the Settings screen): demo speed (10× and 60×), scripted sensor dropouts and
  the simulated Click state.

## Rides on disk (ADR 0008)

Every ride is written to `filesDir/rides/` as it happens and stored when it ends; a ride that was cut off is offered at the
next launch.

- **`data/.../rides/RideStore`** is plain Kotlin over `java.io.File` (tested on a temporary folder): `<id>.ridelog` while the
  ride runs, then `<id>.meta`, `<id>.json` (the wire format) and `<id>.fit`, each written through a temporary file and a
  rename, the log deleted last. `scan()` tidies the folder and lists rides as `Saved`, `Interrupted`, `Unsaved` or
  `Recording`; no call throws, every one returns a `StoreResult`.
- **`app-ride/.../rides/RideLibrary`** is what the app uses: `begin(title)` gives the host a recorder that queues the log
  writes (`AsyncRideRecorder` from `core`, on one `Dispatchers.IO` thread, so the engine never waits for the disk),
  `finish(record)` stores the ride and its FIT file, `start()` scans on launch, and `recovery` and `lastSaved` are the
  state flows the Ready screen reads (`saveRecovered()`, `discardRecovered()`).
- **Screens:** the recovery notice and the **Last ride** row on Ready, the Saving, Saved and Failed states of the finished
  screen, one notice on the riding screen if the log cannot be written. Share and Save go through `RideFileActions`
  (an interface; the Android side is `rememberRideFiles`: a `FileProvider` that serves only `files/rides/`, and the system
  file picker), so the screens are tested with a fake.
- `RideHost(persistence = RidePersistence.None)` is the default: tests and previews that do not look at the disk need no
  folder.

## The overlay (ADR 0006)

`app-ride/.../overlay/` holds the overlay: `OverlayService` (a `specialUse` foreground service started from the visible
ride screen) owns a `TYPE_APPLICATION_OVERLAY` window (`OverlayWindowController`) around the ui-kit overlay composable.
The window shows while a ride is live and the app is not on screen, drags and snaps to the nearer edge, and saves its
position and mode in the settings. The rules are plain Kotlin with their own tests: `OverlayPlacement` (snap and clamp),
`OverlayGestureDetector` (tap, long press, drag), `OverlayPolicy` and `ServiceStopRule` (lifecycle), `overlayStates`
(distinct, at most one update a second). `OverlayController` is what the activity sees; the permission rows are on the
Ready screen and in Settings. It needs "Display over other apps" (the rider grants it in system settings); without it the
ride is unchanged.

## Screenshots

`app-ride` renders its screens and every ui-kit component on the JVM with Roborazzi on Robolectric, with no
emulator:

```bash
./gradlew :app-ride:testDebugUnitTest      # tests and screenshots
ls app-ride/build/shots/                   # the PNGs, one per screen and state
```

The PNGs are build output and never committed; compare them with `../design/mockups/screenshots/` by eye. The tests also
assert the overlay's measured size (464 dp wide; 84, 159 and 282 dp high).

## SDK levels

`minSdk` 31 (modern Bluetooth permissions; both target devices run newer Android). `targetSdk` 36:
raise it only after testing the new runtime behaviour. `compileSdk` 37 is required by the current
Compose release. All three live in `../gradle/libs.versions.toml`.
