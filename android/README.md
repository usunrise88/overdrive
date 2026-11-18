# android

One Gradle build for everything that runs on Android. The shared `core` is a separate build in
`../core`, pulled in with `includeBuild` (see `settings.gradle.kts`).

| Module | Contents | Spec |
|---|---|---|
| `ble` | Android Bluetooth under core's four interfaces: GATT transport over a thin platform shim, scanner, radio and permission state, raw session logs (ADR 0010) | §4.1, §7.1 |
| `data` | The rides folder: `RideStore` (log, record, FIT, atomic finalize, scan) and `RideLogWriter`; the outbox and sync client come with §4.9 | §4.2, §4.8, §4.9, §7.1 |
| `ui-kit` | Design system: theme on the generated tokens, components, charts, the overlay composables, Lucide icons | §7.4 |
| `app-ride` | Tablet app: ride, devices and settings screens over the real trainer and strap (or a simulated ride); remembered devices and pairing; the overlay service and window | §4 |
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
  dispatcher. The factory is `DeviceSourceFactory`: a `RideEngine` over the paired Bluetooth devices
  (`BleRideSessionFactory`, the default) or over `SimulatedBike` (Developer setting), never one in place of the other.
  Screens only read `StateFlow`s and call `RideActions`. `rides` (`RideLibrary`) owns the files (below).
- **Previews.** `ui-kit/.../preview/` has `@Preview`s for every component and state, and the overlay in each mode.
- **Developer settings** (end of the Settings screen): where the devices come from (Bluetooth or Simulated), recording
  and sharing the Bluetooth logs, demo speed (10× and 60×), scripted sensor dropouts and the simulated Click state.

## Bluetooth (ADR 0010)

`ble` (`app.overdrive.ble`): `AndroidBle(context, scope)` is the `BleStack` the container owns (`environment`, `scanner`,
`transports`, `nanoClock`, `refresh()`). `AndroidGattTransport` holds all the logic of a GATT link over a `PlatformGatt`
(`BluetoothGattShim` is the real one; `ScriptedPlatform` and `CorePlatform` drive it in tests), `AndroidGattScanner` and
`AndroidBleEnvironment` the rest. `BleSessionLogger` (with `BleLogStore`) records every connection to
`filesDir/ble-logs/` on its own writer thread.

`app-ride/.../devices/`: `DeviceStore` (one trainer and one strap, DataStore `devices`), `PairedDevices` (the long-lived
devices, owned by the container and not by any ride; `start()` connects what is remembered), `PairingController` (scan,
list, pair, forget; refused during a ride), `BleDevicesModel` (the Devices rows from the link states).
`host/BleRideSessionFactory` builds the engine over those devices; with no trainer paired the engine stays `IDLE` and
nothing is simulated. `RideHost(rideTriggers = pairedDevices.changes)` rebuilds a ride that has not started when the devices
change. `ui/BluetoothOnboarding` is the one notice with one action (permission, Bluetooth off). `ui/BleLogFiles` shares the
logs through `BleLogActions` (an interface, like `RideFileActions`); they hold heart-rate values and leave the tablet only
through that action.

Tests use no radio: `FakeBle` from `core` stands in for it (`BleWorld` in `app-ride`'s test sources), in virtual time.
Container tests (`AppContainerBleTest`) take a `FakeBleStack` through `AppContainer(bleStack = ...)`. What only a bench can
settle is the table in `docs/adr/0010-android-ble-and-pairing.md` section 10.

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
  (an interface; the Android side is `rememberRideFiles`: a `FileProvider` that serves only `files/rides/` and
  `files/ble-logs/`, and the system file picker), so the screens are tested with a fake.
- `RideHost(persistence = RidePersistence.None)` is the default: tests and previews that do not look at the disk need no
  folder.

## The overlay (ADR 0006)

`app-ride/.../overlay/` holds the overlay: `OverlayService` (a `connectedDevice|specialUse` foreground service, with
`connectedDevice` only while the Bluetooth permission is granted, `ForegroundServiceTypes`; started from the visible
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
