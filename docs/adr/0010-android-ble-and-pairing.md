# ADR 0010: Android Bluetooth, pairing and the ride over real devices

Status: accepted (stage M3-2, 2026-11-18)

## Context

M3-1 (ADR 0009) built everything above Android's Bluetooth stack in `core`: four small interfaces (`GattTransport`,
`GattTransportFactory`, `GattScanner`, `BleEnvironment`), the two device adapters, the reconnecting supervisor, the session
recorder and a strict fake radio. M3-2 puts the tablet under those interfaces and puts the rider in front of them: scanning,
pairing, remembering, connecting at launch, a ride over the real trainer and strap, the permission and the service type that
real Bluetooth needs, and the raw recordings the owner's first rides will leave behind for fixing the codecs.

**The trainer, the strap and the tablet are not in reach** (roadmap, "Hardware verification is deferred"). So nothing in this
stage was run on a radio. The rule that follows: the Android code is **small, defensive and fully logged** (privacy-redacted),
every line of logic lives where a JVM test reaches it, and every platform assumption that only a bench can settle is named in
section 10 and in `docs/bench-checklist.md`.

## Decision

### 1. A platform shim, and all the logic above it

`android/ble` (`app.overdrive.ble`) is split in two layers so that the part that touches `BluetoothGatt` is a few dozen lines:

| Layer | What | Where |
|---|---|---|
| `PlatformGatt` | the platform operations, one call each: `connect`, `discoverServices`, `requestMtu`, `read`, `write`, `enable`, `disconnect`, `release`. A call answers `Start` (`STARTED`, `BUSY`, `REFUSED`, `PERMISSION`, `UNAVAILABLE`, `ERROR`) **and never throws**; the result arrives later as a `PlatformEvent` | `gatt/PlatformGatt.kt` |
| `BluetoothGattShim` (+ `GattCallback`, `BluetoothGattOpener`) | the real `PlatformGatt` over `BluetoothGatt`; the callback turns each platform callback into a `PlatformEvent` by copying values and nothing else | `gatt/BluetoothGattShim.kt` |
| `AndroidGattTransport` | **all the logic**: the core `GattTransport` contract over any `PlatformGatt` | `gatt/AndroidGattTransport.kt` |

`AndroidGattTransport` meets the contract of core's `FakeBle` exactly, because the same adapters and the same
`LinkSupervisor` sit above it:

- **single-use**: after a failed connect or a dropped link the object is dead; the supervisor asks the factory for a new one
  (a failed or status-133 `BluetoothGatt` is closed and never reused, ftms-hr §7);
- **one operation at a time**: an overlapping call is answered `BUSY`, as the platform would refuse it ("device busy");
- **results, never exceptions**: every failure is a `GattResult.Failed(kind, status)` with the platform's own `Int` status;
- **`close()` is idempotent and safe at any time**: a pending operation completes as `Failed(DISCONNECTED, 0)`, later ones as
  `CLOSED`, and after a drop as `DISCONNECTED(status)`;
- **abandoned operations keep the slot**: an operation whose caller timed out or was cancelled still occupies the platform until
  its late callback arrives, so the next one is not started over it; if no callback comes within 5 s the link is declared dead
  (`Disconnected(UNKNOWN)`), so a stack that swallows a callback cannot wedge a ride;
- **the MTU request does not take the slot** and has its own 3 s timeout (Android 14 and later may negotiate the MTU unasked
  and never answer a second request);
- **release is deferred**: `close()` calls `disconnect()`, and `BluetoothGatt.close()` follows when the platform reports the
  disconnect, or after a 2 s grace, whichever comes first. Closing at once after `disconnect()` can lose the disconnect
  callback and leaks a GATT client slot on some stacks. The release runs even if the owning scope has been cancelled.

Two test platforms drive the same transport: `ScriptedPlatform` (the test hand-drives every callback: ordering, late and
missing callbacks, status codes, busy, permission) and `CorePlatform` (core's `FakeBle`/`FakeConnection` behind the shim,
mimicking Android's "busy"). With the second, `FtmsTrainerDevice` and `HeartRateStrapDevice` under the real `LinkSupervisor`
reproduce core's fake scenarios (ERG workout, trainer dropout, status 133, strap dropout, RR intervals) **over our transport**,
and the fake's `violations` list is part of each assertion.

**`connectGatt` is the four-argument overload on purpose**: `connectGatt(context, false, callback, TRANSPORT_LE)`. Its
replacement, `connectGatt(BluetoothGattConnectionSettings, Executor, Callback)`, exists only from API 37, the overload is
merely *deprecated* there, and the tablet runs Android 15. One call that works on every level the app supports (minSdk 31)
beats two code paths of which one cannot be run. `autoConnect = false` (a direct connect), because our own supervisor owns
the reconnect schedule; the day the bench says otherwise it is a one-line change in `BluetoothGattOpener` (and on API 37 the
settings object's `setAutoConnectEnabled(false)` / `setTransport(TRANSPORT_LE)`).

### 2. Threading

- Platform callbacks arrive on binder threads. **No `Handler` is passed to `connectGatt`**: before Android 13 the value of a
  notification lives in the shared characteristic object, and a callback posted to another thread could read the next
  notification's bytes. `GattCallback` copies the bytes on the thread the platform calls and hands over a `PlatformEvent`
  through a lock, a `CompletableDeferred` for the one outstanding operation and a `SharedFlow.tryEmit` for notifications.
- Everything else runs on the container's `Dispatchers.Default` scope: the transports, the supervisors, the adapters, the
  scan and the engine. **Nothing Bluetooth runs on the main thread.** The main thread only composes the screens and calls
  `Context` for the two system screens.
- The raw session recorder writes on **one thread of its own** (`Dispatchers.IO.limitedParallelism(1)`), so a slow disk never
  reaches the radio or the ride (section 8).

### 3. Scanner and environment

`AndroidGattScanner` is a `callbackFlow` over `BluetoothLeScanner.startScan(filters, SCAN_MODE_LOW_LATENCY, callback)` with one
service-UUID filter each for the Fitness Machine and the Heart Rate service. It is started **only while the Devices screen
asks for it** (the screen was left, 30 s passed, or the rider chose something: all three stop it) and it reports a failure as
one `ScanEvent.Failed(reason)`, then completes. Failure reasons are `BLUETOOTH_OFF`, `PERMISSION_DENIED` and `REFUSED` (Android
limits a client to five scan starts in 30 s; the screen says "Scanning was refused ... wait half a minute").

`AndroidBleEnvironment` answers `UNSUPPORTED` (no adapter or no BLE feature) before `PERMISSION_DENIED` (either of
`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` missing) before `BLUETOOTH_OFF` before `READY`. It listens to
`ACTION_STATE_CHANGED` and is told by `refresh()` about everything that has no broadcast: the permission being granted or
revoked, which the container does on every activity resume and after each permission answer.

### 4. Permissions

Manifest: `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`, `BLUETOOTH_CONNECT`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, and `<uses-feature android.hardware.bluetooth_le required="false">` (the Devices screen
says what is missing and the simulated devices stay). **No location permission and no `INTERNET`**; a test on the merged
manifest keeps both out.

Onboarding is the pattern of the overlay's (ADR 0006): **one plain notice with one action**, shown on the Devices and Ready
screens only while it applies, and re-checked on every resume so it disappears by itself.

| State | Notice | Action |
|---|---|---|
| a permission missing, never refused | "Allow nearby devices" | **Allow**: the system's question for both permissions at once |
| refused (the system asks at most twice) | "It is switched off for Overdrive ..." | **Open settings**: the app's settings page |
| Bluetooth off | "Bluetooth is off" | **Turn on**: the system's Bluetooth settings (`ACTION_BLUETOOTH_SETTINGS`; no app may switch it on silently any more) |
| no Bluetooth LE | "No Bluetooth on this tablet" | none; the Developer switch offers the simulation |

While a notice shows, the device rows say it too ("D500 · Bluetooth is off", "· needs Nearby devices") and **Pair** on a row
does the notice's action: a scan would find nothing. A permission that is missing is never a crash and never a failure to
retry: the supervisors **wait** in `LinkStatus.BluetoothUnavailable` and connect the moment the radio is usable again.

### 5. The foreground service: `connectedDevice|specialUse`, and a missing permission

The manifest declares `foregroundServiceType="connectedDevice|specialUse"` with the special-use subtype text rewritten
for what the service now does (it draws the numbers and keeps the Bluetooth links alive). ADR 0006 had planned this move
for "when the BLE adapters exist".

**The decision for Android 14 and later**: `startForeground` with a type throws `SecurityException` unless the app holds what
that type needs, and for `connectedDevice` that is a *granted* runtime permission (`BLUETOOTH_CONNECT` here). A rider who has
not allowed "Nearby devices", or who rides in simulated mode, must still get the overlay. So `ForegroundServiceTypes` chooses:

| Android | `BLUETOOTH_CONNECT` | Types passed |
|---|---|---|
| 14 and later | granted | `specialUse` and `connectedDevice` |
| 14 and later | not granted | `specialUse` only |
| 13 and earlier | any | none (the manifest's types apply) |

If the system refuses the pair all the same (the permission was revoked between the check and the call), the service starts
again with `specialUse` alone and logs the exception's class name; a refusal of `specialUse` itself is not swallowed. The
overlay therefore never depends on Bluetooth. A permission granted *after* the service started changes nothing until the next
start, which is harmless: the process stays foreground either way.

### 6. Remembered devices, and who owns them

`DeviceStore` keeps at most **one trainer and one strap** (`id` and cleaned `name` per role) in a DataStore file of its own
(`devices`), in the app's private files. Reading is forgiving like the settings: a blank or damaged value is "nothing paired",
an unreadable file is an empty one. The id is the hardware address underneath: it stays in that file, in the transport and
nowhere else (section 9).

**Devices are long-lived and owned by the container's scope, not by any ride.** `PairedDevices` holds an `FtmsTrainerDevice`
and a `HeartRateStrapDevice`, each with its own reconnecting `LinkSupervisor`:

- `start()` runs once from the container's `init` block, **after every property it reads is assigned** (a race found by the
  container tests: the first transport opens on another thread within milliseconds and the log header reads the paired
  device's name). It reads the store and **starts a device for everything remembered**, so the app connects at launch with
  no tap. The supervisors wait by themselves for Bluetooth and the permission.
- A ride session only *uses* them. The host throws a ready session away and builds another whenever the workout or a setting
  changes; none of that touches a link (a test counts the transports opened and closed).
- `pair(role, device)` stores the choice and **replaces the old device cleanly**: the old one is closed (its transport and its
  platform object with it, so the fake's "second transport before the first was closed" never fires) and a new one starts.
  Pairing the device that is already paired changes nothing. `forget(role)` closes the device and removes the memory.
- `changes` counts every real change of what is paired, plus the first load; the host uses it as a *ride trigger* (section 7).
- `PairingController` scans on the Devices screen's request, lists trainers and straps apart by their advertised service, by
  advertised name (the role's plain name when there is none), each once, with the signal rounded to steps of 5 dBm so the list
  does not flicker and shown as a word ("Strong · −58 dBm"). A device not heard for 12 s leaves the list (a sweeper runs
  every 2 s, so a lone device that is switched off also goes). **Pairing and forgetting are refused while a ride is live**
  (`DevicesUi.canChange`), because a ride never changes its devices.
- `BleDevicesModel` turns the link states into the Devices screen's rows (Connected, Connecting, "Connection lost · retrying in
  N s", "Not found · retrying in N s", "Could not connect", "Not a trainer", Bluetooth off, needs Nearby devices, Not paired).
  The retry countdown ticks once a second **only while a link waits**; at rest nothing is emitted. **Retry now** looks the
  device up at the time of the tap, since pairing may have replaced it since the session was built.

### 7. The ride over real devices, and the Developer switch

`BleRideSessionFactory` builds the unchanged `RideEngine` over the paired devices' adapters. Without a paired trainer the
engine runs over `UnpairedTrainer`, a stand-in that is never connected and refuses every command, so the engine stays `IDLE`:

- **No trainer paired**: the Ready screen says "No trainer is paired." with **Pair a trainer**, **Start** is off and the top
  bar says "No trainer". **Nothing simulated ever takes its place**: a recorded ride of made-up numbers would be worse than no
  ride.
- A trainer paired but not connected: `IDLE` until the adapter says `CONNECTED` (only after it can be commanded), then `READY`.
- No strap: a ride without heart rate, which is a gap and not an error (spec §4.1).

**`RideHost` rebuilds a session that is ready or idle** when what it was built from differs from what is wanted now: the
ride-affecting settings (which now include the device source) or the latest *ride trigger* (`PairedDevices.changes`). It
compares with what the session was **built from**, not with the previous value, so a change that arrived while the first
session was being built is never missed and one that was undone costs nothing. **A ride that has started is never replaced.**
The engine's and the host's public API are source-compatible (a new constructor parameter with a default, a new optional
field on the session handle).

**Developer setting "Devices": Bluetooth / Simulated, persisted and sanitised, default Bluetooth.** The default is Bluetooth
because the app now exists to ride on the real trainer, and a default that quietly rode the simulation would produce rides of
made-up numbers on the owner's first day; the simulation is kept for demos, the screenshots and development. **There is no
fallback between the two**: Bluetooth with no trainer waits, it does not simulate. In simulated mode the Devices screen says
so and offers no pairing; the paired devices still connect in the background, which is harmless and keeps a switch back to
Bluetooth instant.

### 8. Raw session recording, and sharing the logs

The owner's first rides are the only evidence for the codecs' guesses (ADR 0009 section 8), so **every connection of the
trainer and of the strap is written to a text file** in the format of core's `BleSessionFormat`, redacted by core's
`BleRecordingPolicy`, by `BleSessionLogger`, a decorator around each role's transport factory (core's `RecordingGattTransport`).

- **What a file is**: one *session*, the failed attempts that led to a link and that link until its transport is closed. The
  next `open` after that starts a new file; attempts that never connect keep appending to the same file until it is full, so
  a trainer left off all day leaves one file and not a thousand.
- **Name**: `filesDir/ble-logs/ble-<UTC date>T<time>Z-<role>.txt`, the **date and the role only**. Never an address or a name.
  Two sessions in one second take the next second's name, so name order stays time order.
- **Content**: the redaction policy of `BleRecordingPolicy`: no address, no identity values (serials, names written to the
  device-information characteristics), the advertised model cleaned to ASCII and 24 characters. Notification values stay,
  because they are the point. **They hold the rider's heart rate and power.**
- **Caps**: a file stops growing at **2 MB**, the folder keeps the newest **60 files** and at most **20 MB** together, and the
  oldest files go first (a file about to be created gets headroom for its own limit). The two sensors write about 100 bytes a
  second between them, so a file holds hours and the folder tens of hours; the numbers are generous because disk is cheap
  and a lost first ride is not.
- **Non-blocking**: the recorder queues a line and a writer on its own single IO thread writes it; a disk that fails or fills
  ends that recording and nothing else. Nothing is written on the radio's or the ride's thread.
- **Default on** (Developer setting "Record Bluetooth sessions") in the early builds, read at each `open`; a later release,
  once the codecs are settled, turns the default off.
- **Sharing**: Settings, Developer, **Share Bluetooth logs** hands every file to another app through the system share sheet
  (`ACTION_SEND` or `ACTION_SEND_MULTIPLE`, `text/plain`) over the app's `FileProvider` (`file_paths.xml` serves `rides/` and
  `ble-logs/`, read-only, one-time grants, the provider not exported). The screens only know a `BleLogActions` interface, so
  tests use a fake. **The logs leave the tablet only by this explicit action and are never uploaded**: the app has no network
  permission, and the files are not part of any sync.

### 9. Privacy and the logs

- `DeviceId.toString()` prints a fingerprint, never the address. The address exists in `DeviceStore`, the transport and the
  system's API calls, nowhere else.
- **Logcat** (`BleLog.Logcat`) lines hold operations, GATT statuses and device fingerprints; never an address, a name, a value
  or an exception message, only the exception's class name.
- The ride record's device list holds `BleRecordingPolicy.sanitizeModel(name)` (ASCII, no serial-like word, 24 characters) or
  the role's plain name.
- The repository holds no real address, name or recording: tests use opaque ids (`test-trainer`) and synthetic scripts.

### 10. Everything only a bench can settle

Built from the platform's documentation and what the engine and the adapters already require, and tested against fakes
written from the same reading. **A green test here proves consistency with that reading, not with the Xiaoxin Pad's Bluetooth
stack.** Each row is named in `docs/bench-checklist.md` (the step that settles it) and is cheap to change.

| # | Assumption | Where it lives | Settled by |
|---|---|---|---|
| S1 | A D500 and an HR7 advertise the Fitness Machine and the Heart Rate service UUIDs, so a service-UUID scan filter finds them | `AndroidGattScanner` (the filters come from `PairingController`) | 3.1, 4.1 |
| S2 | `ScanRecord.deviceName` carries the name the rider knows; `neverForLocation` drops no advertisement that matters; five scan starts in 30 s is the refusal rule | `AndroidGattScanner`, `ScanMapping` | 3.1 |
| C1 | `connectGatt(autoConnect = false, TRANSPORT_LE)` connects within the app's start-up without a bond or a prior scan result | `BluetoothGattOpener` | 3.1 |
| C2 | A new `BluetoothGatt` per attempt, the old one closed, reconnects reliably after the trainer is power-cycled; the 1 s first backoff lets the stack finish tearing down | `AndroidGattTransport`, `BackoffPolicy` | 3.11, 3.12, 3.14 |
| C3 | The deferred release (`disconnect()`, then `close()` on the callback or after 2 s) neither leaks a client slot nor loses the disconnect callback | `AndroidGattTransport`, `GattTimings.disconnectGrace` | 3.12, 3.14 |
| C4 | Android's connect timeout is about 30 s; our 35 s transport limit and the supervisor's 45 s one are backstops only | `GattTimings.connect` | 3.12 |
| T1 | Callbacks arrive on a binder thread, in order, and the bytes of a notification are valid in the callback | `GattCallback` | 3.3 to 3.9 |
| T2 | `requestMtu` may be answered, ignored or already done on Android 14 and later; 3 s is long enough to learn which | `GattTimings.mtu` | 4.5 |
| T3 | A callback that never arrives is caught by the 5 s watchdog and the link is rebuilt cleanly | `GattTimings.abandonedGrace` | 3.12 |
| T4 | The return codes of `writeCharacteristic`/`writeDescriptor` (Android 13 and later) and the boolean forms (before) map as written | `BluetoothGattShim` (`startOfStatusCode`) | 3.4, 3.5 |
| T5 | `setCharacteristicNotification` plus the CCCD write is enough on this stack (no `refresh()` of the GATT cache is needed) | `BluetoothGattShim.enable` | 3.2 |
| P1 | One system question covers both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on this ROM | `rememberBluetoothFix` | 2.1 |
| P2 | After a refusal the system stops asking and **Open settings** reaches the app's page | `BluetoothController.openAppSettings` | 2.1 |
| P3 | `ACTION_BLUETOOTH_SETTINGS` opens the Bluetooth page on this ROM | `BluetoothController.openBluetoothSettings` | 3.13 |
| R1 | The adapter-state broadcast and `refresh()` on resume are enough to follow Bluetooth being switched and the permission being changed | `AndroidBleEnvironment` | 3.13 |
| F1 | `connectedDevice` plus `specialUse` starts with the permission and `specialUse` alone starts without it; `dumpsys` shows the types as designed | `ForegroundServiceTypes`, `OverlayService` | 3.13, 3.16 |
| F2 | Android 15's time limits do not stop a `connectedDevice` plus `specialUse` service during an hour's ride (ADR 0006) | the manifest | 6.8 to 6.10 |
| L1 | A 2 MB file, 60 files and 20 MB are enough, and the share sheet takes several `text/plain` files at once | `BleLogLimits`, `BleLogShare` | 3.17 |
| H1 | Everything the adapters assume about the D500 and the HR7 (ADR 0009 section 8) | `core` | FT 1 to FT 12 |

**R8**: the release build minifies. The only classes the framework calls are `GattCallback`, `ScanCallback`'s anonymous
subclass and the broadcast receiver, and they are called through the framework methods they override. R8 keeps the names of
overrides of library methods, and no Bluetooth class is looked up by reflection, so **no keep rules are needed and none were
added**. Checked in the release build's `mapping.txt`: `GattCallback` is renamed, its overrides (`onConnectionStateChange`,
`onCharacteristicChanged`, `onCharacteristicWrite`, ...) keep their framework names; the merged release manifest carries
`foregroundServiceType="connectedDevice|specialUse"`, the two Bluetooth permissions and the optional `bluetooth_le` feature.
`assembleRelease` is part of the definition of done and ran green; `docs/bench-checklist.md` installs that very APK.

## Consequences

- The platform-facing part (`BluetoothGattShim`, the scanner and the environment, about 630 lines with their comments) is
  small next to the logic a JVM test reaches (`AndroidGattTransport`, the platform interface and the logs, about 1,000 lines).
  If the bench finds a platform behaviour that differs, the change is in one of the rows above.
- `RideHost`, `RideEngine` and the screens' APIs are source-compatible; the new parameters have defaults. Existing tests pass;
  the one container test that rides the simulation now says so (`deviceSource = Simulated`), because the default is Bluetooth.
- The owner's first rides leave their own evidence: the recordings answer FT 1 to FT 12 without a bench machine, and replay
  through the fake.
- Pairing is one trainer and one strap. A second trainer or a power meter is out of scope for Phase 1.
- Not done here: the Click (M4: the row stays a labelled placeholder), the companion app, any sync of the logs.

## First bench (2026-11-18)

The first ride on the tablet (`docs/bench-reports/2026-11-18-first-ride.md`; not run as the steps of the checklist, so no row of
its results table is filled in) bears on these rows, **without settling them for good**:

| Row | What the report shows |
|---|---|
| T2 | `requestMtu` was answered on this tablet: **251** for the 128 asked by the Click and, on the D500, 251 for the 64 asked. The app accepts what comes back |
| T5 | The CCCD writes were enough: notifications on ASYNC and indications on SYNC_TX, then the D500's Control Point, Indoor Bike Data and Status, and the strap's heart rate, all delivered, with no `refresh()` |
| C4 | A connect to a Click that was not reachable ended at the app's 30 second limit with status **147** |
| H1 | FT 1 is settled (bit 2 is cadence); the D500's Reset after Request Control is handled (ADR 0013); the rest of FT 1 to FT 12 stays open (`docs/protocols/ftms-hr.md` §11) |

Link losses seen: the Click's three linked sessions ended with status 8 (link supervision timeout) and the app reconnected each time;
the strap's ended with 19 (remote user terminated). The Click's own rows K1 to K7 are answered in part in ADR 0012 and ADR 0013.
