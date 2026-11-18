# android

One Gradle build for everything that runs on Android. The shared `core` is a separate build in
`../core`, pulled in with `includeBuild` (see `settings.gradle.kts`).

| Module | Contents | Spec |
|---|---|---|
| `ble` | Android Bluetooth under core's four interfaces: GATT transport over a thin platform shim, scanner, radio and permission state, raw session logs (ADR 0010) | §4.1, §7.1 |
| `data` | The rides folder: `RideStore` (log, record, FIT, atomic finalize, scan) and `RideLogWriter`; `FileOutbox`, the sync outbox on disk (ADR 0012); the answers cached for the phone (`FileTextStore`) and its service settings rules (ADR 0024) | §4.2, §4.8, §4.9, §6.1, §7.1 |
| `ui-kit` | Design system: theme on the generated tokens, components, charts (workout profile, zone bars, and the companion's line, column and ride charts), the overlay composables, Lucide icons | §7.4 |
| `app-ride` | Tablet app: ride, devices and settings screens over the real trainer, strap and Click (or a simulated ride); remembered devices and pairing; Learn buttons; the handlebar; the overlay service and window; the sync outbox and settings | §4 |
| `app-companion` | Phone app (ADR 0024): Today, Fitness, Power, Wellness, Rides, ride detail, Settings and the first run, over the service's metrics, rides, plan and profile, cached on disk for offline. The Health Connect reader and the wellness data come with P3-4b | §6.1 |

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
  and sharing the Bluetooth logs, demo speed (10× and 60×), scripted sensor dropouts and the simulated Click state, and a
  **Developer · Click** group with the Click's connection options and the two gesture timings (below).

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

## The Click, the handlebar and Learn buttons (ADR 0012)

The Click is a long-lived device of `PairedDevices` (role `Click`) beside the trainer and the strap: paired from the Devices
scan (`PairingController`; told by its manufacturer data, listed as "Zwift Click (right)"), remembered in `DeviceStore`,
connected when the app starts, owned by the container and **not by any ride**, and never counted in `PairedDevices.changes`.
`PairedDevices.clickButtons` is one hot flow of its buttons for the life of the container (a synthetic release is emitted when a
Click is replaced or forgotten while a button is down).

- **`handlebar/HandlebarService`** wires that flow through `HandlebarControls` (gestures) and `ActionRouter` (the one routing rule,
  shared with `RideActions`) in the container's scope, **with no activity**; it can be suspended. The overlay's foreground service
  keeps the process alive during a ride. **`handlebar/ButtonLearning`** is the *Learn buttons* flow (`ui/LearnScreen`).
- **`devices/BleDeviceRows.clickRow`** is the Click's row in plain words; `ui/DevicesScreen` shows the unlock notice below a locked
  Click and the button map with **Learn buttons** and **Reset to default**.
- **Settings** (`settings/ClickSettings`, `SyncSettings`): the Click's connection options and gesture timings (every default is
  `core`'s, every value is sanitised, a change replaces the Click's device only) and the sync address and token (read at every
  upload, the token a `SecretText` that prints as dots). The manifest asks for `INTERNET` (and nothing else of the network, ADR 0014),
  allows cleartext for a plain `http://` bench address, and sets `allowBackup=false`.
- **Container injection for tests:** `AppContainer(mediaFactory = ..., elapsedMillis = ..., outboxFile = ..., bleStack = ...)`.
  Tests that write into `filesDir/rides` use a plain `Application` (`@Config(application = Application::class)`), like
  `AppContainerClickTest` and `AppContainerSyncTest`, so the app's own container does not start beside them.
- What only a bench can settle is the table in `docs/adr/0012-click-on-android-and-sync-outbox.md` section 11.

## The sync outbox (ADR 0012, spec §4.9)

`data/.../sync/FileOutbox` is `core`'s `Outbox` on disk (`filesDir/sync/outbox.log`): an append-only journal with a checksum per
line, every append forced to storage, a torn last line ignored, compaction through a temporary file and an atomic rename, and a
ride queued **once, ever**. `RideLibrary(onSaved = ...)` announces every stored ride (a finished one, a recovered one, one completed
at start, and at each start every ride on disk, which the outbox ignores if it has seen it). The container runs a `SyncCoordinator`
over `HttpSyncProvider` (ADR 0014; `core` holds the logic, `data/.../sync/UrlConnectionTransport` the `HttpURLConnection` and
`StoredRidePayloads` the stored files it sends). With no address or token nothing is sent. Settings, Sync, shows how many rides
wait, the last error in plain words, the last upload and **Send now**. `AppContainer(syncTransport = ..., lastUploadFile = ...,
syncSettleMillis = ...)` are the test seams; `LocalService` in the app tests is a stand-in for the intake on the loopback.

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

`app-companion` does the same for its screens and states (`design/mockups/companion/png/` is the target), and also drives the charts' touch and a whole
flow through the tabs against a local stand-in of the service:

```bash
./gradlew :app-companion:testDebugUnitTest   # tests and screenshots
ls app-companion/build/shots/                # today--*, fitness--*, power--*, rides--*, ride--*, settings--*, connect--*
```

## SDK levels

`minSdk` 31 (modern Bluetooth permissions; both target devices run newer Android). `targetSdk` 36:
raise it only after testing the new runtime behaviour. `compileSdk` 37 is required by the current
Compose release. All three live in `../gradle/libs.versions.toml`.
