# ADR 0012: The Click in the app, Learn buttons, developer settings for the bench, and the persistent sync outbox

Status: accepted (stage M4-2, 2026-11-18)

## Context

M4-1 (ADR 0011) put everything about the Zwift Click that needs no Android into `core`: the adapter, the gesture layer, the
router, the learner, the sync coordinator and an in-memory outbox. M4-2 is the other half and the last of Phase 1: the Click
over the Android Bluetooth stack, in the app as a device like the trainer and the strap, its screens, the settings the
owner's bench needs, the outbox on disk, and the documents that close the phase.

The same limits as before apply. The Click is not in reach and `docs/protocols/click-v2.md` is research notes from public
sources with **nothing observed**; the tablet's Bluetooth stack has not been run either (ADR 0010). So every platform
assumption below is tested against a fake written from the same reading and **named in the table of section 11**, each cheap
to change, with the bench step that settles it.

**Clean room.** The only protocol source is `docs/protocols/click-v2.md`. No code was read, copied or ported from BikeControl
(any version), the Zwift Play library, trainer-control or any other project. Encryption stays unimplemented; nothing here
unlocks a controller. There is **no Google Play Services**, **no network code** and **no `INTERNET` permission**: nothing is
uploaded.

## Decision

### 1. Bluetooth layer

- **`GattTransport.writeWithoutResponse`** is overridden in `AndroidGattTransport` and in `BluetoothGattShim`
  (`WRITE_TYPE_NO_RESPONSE`, through `writeTypeOf`). It takes the same single operation slot as a write with response and
  completes on the same write callback. Like the fake radio, it **refuses a characteristic that does not offer** write without
  response with "write not permitted" (3): the platform would otherwise send a command the peripheral ignores and say nothing.
  The shim's two paths (Android 13 and later: `writeCharacteristic(target, value, type)`; before: `setWriteType`, then the
  deprecated call) are covered by a unit test of the mapping and, for the old path, by the bench only (Robolectric has no
  android-all jar below SDK 33 here).
- **`AndroidGattScanner` fills `Advertisement.manufacturerData`** from `ScanRecord.manufacturerSpecificData` (company id and
  the bytes after it), which is how a Click is told from any other device. The address never leaves the `ble` module; nothing
  prints the payload.
- **The scan filter** is the three services, trainer, strap and Click (`ClickAdvertisements.scanFilter`). When the developer
  setting **Needs its service listed** is off the scan is unfiltered and a Click is told by its manufacturer data alone
  (bench question CK 12); `core`'s `ScanFilter` is unchanged.
- **`BleLogRole.CLICK`** names the Click's session files (`...-click.txt`) beside the trainer's and the strap's.

### 2. Ownership: the Click is a device of the container

`PairedDevices` gains the role **Click** beside Trainer and Strap (`DeviceRole.Click`, remembered by `DeviceStore` like the
others). A `ClickDevice` is started for the remembered Click when the app starts, by the container, in the container's scope,
with its own reconnecting supervisor.

- **No session ever closes it.** A ride session only *uses* the trainer and the strap; the Click is not even that. It keeps
  its link between rides, and ending or starting a ride touches it not at all (`AppContainerClickTest`).
- **It is never a ride input.** Pairing, replacing or forgetting a Click does not count in `PairedDevices.changes`, so no ride
  is rebuilt for it (spec §4.7: the ride never depends on the Click).
- **One hot flow of buttons for the life of the container.** `PairedDevices.clickButtons` is fed from whichever Click is
  current and is **independent of device swaps**: the gesture layer collects it once and never follows the device. A device
  that is replaced, forgotten or closed while buttons were down first emits a **synthetic release** of what was held, because
  its own release will never come; `HandlebarControls` already treats a synthetic change as "forget the press and fire nothing".
- **Options follow the settings.** `PairedDevices` takes a flow of `ClickOptions`; the first value is waited for **before
  anything is started** (so the Click never connects once with the wrong ones) and a later value **replaces the Click's device
  only** (a reconnect of a few seconds), leaving the trainer, the strap and any ride alone.
- **Pairing a Click that is already paired changes nothing**; a different one replaces the old cleanly.

### 3. One routing rule, and background operation

- **The rule lives once, in `core`'s `ActionRouter`.** `RideActions` (what the ride screen's buttons call) **delegates to it**,
  so an on-screen button and a handlebar button cannot diverge. `ActionRouter` gained an optional `snapshot` accessor
  (default: the session's own) so `RideActions(session, snapshot)` stays source-compatible for the previews and tests that
  hold their own copy of the state. `ActionRoutingTest` runs every ride action through the screen's route (with and without a
  snapshot of its own), the Click's router and the screen's exposed router, in **every state and both kinds of step** (ERG
  and simulation, every pause reason), against the rule written out as a table.
- **`HandlebarService` runs in the container's scope with no activity.** It wires `HandlebarControls` (gestures) to the
  router, reads the mapping at every change (a learned mapping applies at once), takes the gesture timing from a flow (a new
  timing replaces the detector; a press in progress is forgotten) and can be **suspended** (while the buttons are being
  learned): gestures are still recognised but nothing is carried out or announced.
- **Proof without a tablet.** `AppContainerClickTest` builds the real container over a fake radio and **never creates an
  activity** (`visibility` stays false): a press of the fake Click reaches the real `RideEngine` over a fake trainer, for the
  gears, the pause and the resume, and the media keys (recording ones). `HandlebarServiceTest` fires all ten actions from their
  gestures in virtual time.
- **What keeps the process alive** with another app in front is the overlay's foreground service, which a live ride starts
  (ADR 0006, ADR 0010 section 5). With **no ride live there is no foreground service**, so Android may stop the process and
  the Click then reconnects at the next start. That is acceptable (nothing needs the Click without a ride), but whether the
  process survives, and whether a Click press reaches a ride with the screen off, are **bench items** (5.6, 5.11).
- **Media.** `MediaController` extends core's `MediaKeys` and is the router's media; a refusal is swallowed.

### 4. The Click's row and the unlock notice

`BleDeviceRows.clickRow` maps `ClickState`, the battery, the radio and the retry countdown to words (table in the KDoc and
in bench session 5): Not paired; Connecting; "checking it answers" (connected, nothing yet); Connected with "battery 87%" once
known (a value outside 0 to 100 is not shown); "Not found · retrying in N s", "Connection lost · …" and "Could not connect · …"
with **Retry now**; **locked**; and the same blocked rows as the other devices when Bluetooth is off or the permission is
missing. The countdown ticks once a second **only while a retry waits**; at rest nothing is emitted, and a battery report never
restarts a wait.

A locked Click gets **one notice with two wordings**, chosen by the sign that gave it away (`UnlockSignal`):

| Sign | Title | Text |
|---|---|---|
| The Zwift service is hidden from discovery | Click is locked | Zwift has it locked. Unlock it through Zwift once; the ride continues without it. |
| The handshake was answered and then nothing came | Click sends no presses | It is connected but silent. Unlock it through Zwift once; the ride continues without it. |

Spec §4.7 says "tell the user to unlock it through Zwift once and continue without the Click"; the notice does, in one short
sentence each, and **Check again** retries now. Neither state is ever an error of the ride.

### 5. Learn buttons

`ButtonLearning` runs core's `ButtonLearner` over `clickButtons`: it asks for the five roles in turn, **rejects a
combination and a duplicate** with a plain message (naming the role that has the button), can be **cancelled** (nothing saved),
and on the fifth button **saves the mapping through `ButtonMapping.serialize()`** into the settings store. **Reset to default**
saves the default. It collects before it asks, so no press is missed, and ignores a button that was already down when it
began.

- **Gestures are suspended while it runs** (`HandlebarService.suspended`), and stay suspended on the "all five set" screen
  until **Done**, so the last press cannot move a ride. The Click does nothing else meanwhile.
- **Offered only when no ride is live** and the Click is connected (and not with simulated devices); the container also ends a
  lesson if a ride goes live meanwhile.
- **A stored mapping is sanitised on read**: anything that `ButtonMapping.parse` refuses (cut, edited, two roles on one button,
  a role missing, not hex) reads as `ButtonMapping.DEFAULT`, and never reaches the gesture layer.
- The names shown are the Click's own (`Y`, `Z`, `A`, `B`, `Plus`).

### 6. Developer settings for the bench

Settings, right column, **Developer · Click**: persisted in the settings store, sanitised on read and on write, with
**Reset** (the learned buttons stay). Each is one option of `ClickOptions` or `GestureTiming`, so a bench result is a switch and
not a rebuild.

| Setting | Option | Default | Range |
|---|---|---|---|
| Setup message | `sendPostHandshake` | on | on, off |
| Handshake with response | `writeWithResponse` | off | on, off |
| Reply check | `handshakeReply` | Exact (`RideOn 02 03`) | Exact, Any RideOn |
| Keep-alive | `keepAliveInterval` | off | off, 10 s, 30 s, 60 s |
| Silence limit | `noEventTimeout` | 30 s | 15 s, 30 s, 60 s, 2 min |
| Message size | `requestedMtu` | 128 | system, 128, 247 |
| Needs its service listed | `requireAdvertisedService` | on | on, off |
| Pucks found by Scan | `acceptedSides` | right | right, either |
| Hold threshold | `GestureTiming.holdThresholdMillis` | 500 ms | 250 to 1000 ms, step 50 |
| Volume repeat | `GestureTiming.repeatIntervalMillis` | 150 ms | 50 to 500 ms, step 10 |

The first five of the brief were the keep-alive, the silence limit, the setup message, the message size and the two timings;
**four more are exposed because each blocks first contact** if the guess is wrong and a rebuild at the bench is the slow way to
find out: the write type of the handshake (CK 2), the strictness of the reply check (CK 2 to 4), whether the scan needs the
service in the advertisement (CK 12) and which pucks a scan lists (CK 1). The others (`rightSideByte`, `leftSideByte`,
`serviceHiddenMeansLocked`, `silentMeansLocked`, `acceptLegacyService`, `ignoredBits`, `handshakeTimeout`, `operationTimeout`,
`readStandardCharacteristics`, `bounceMillis`) stay one line of code each (ADR 0011 section 9). Every default equals `core`'s
(`ClickSettingsTest`), every value is brought into a range `ClickOptions` and `GestureTiming` accept (the whole grid of the two
timings is built in a test and never throws), and a damaged file reads as defaults.

### 7. The persistent outbox

`FileOutbox` (`android/data`, `app.overdrive.data.sync`) is `core`'s `Outbox` on the tablet's disk (`filesDir/sync/outbox.log`).

- **Format: an append-only journal.** A header, then one line per fact, `<crc32 of the rest, 8 hex digits><TAB><record>`: `E`
  queues a ride (id, enqueue time, start time, duration, title), `D` says it was uploaded. The state is the journal replayed.
- **Durability.** Every append is forced to storage (`fsync`) **before the call returns**, so a ride that `enqueue` returned
  for is on disk. A process that dies *during* an append leaves a partial last line, which the checksum rejects at the next
  start; nothing earlier is ever rewritten by an append. **Tolerates a corrupt tail**: the good lines are kept, the rest ignored,
  never an exception, and the file is compacted.
- **Compaction** (when anything was ignored, or more than 200 superseded lines accumulated) writes the live state to a temporary
  file, forces it, and **renames it over the journal atomically**; a death at any step leaves the old file or the whole new one,
  and a leftover temporary file is deleted at the next start. A failed append marks the journal for compaction before the next
  write, so a half line is never followed by a good one that would be glued to it. `FileOutboxTest` stops the "process" after
  every step (`afterStep`) and proves nothing is lost.
- **A ride is queued once, ever.** The journal remembers the ids it has been told were uploaded, so a ride marked done is not
  accepted again. This is stricter than `InMemoryOutbox` (which forgets a ride once it is done), on purpose, because of how rides
  are announced:
- **Every stored ride is announced.** `RideLibrary` (parameter `onSaved`) tells the container about a ride that has just
  finished, a recovered one the rider kept, one completed silently at start, **and, at each start, every ride already on
  disk**: an outbox that has seen a ride ignores it, so this only catches one whose entry was lost between the last rename of
  the store and the outbox's append. **Consequence for an app updated from an alpha**: the stored rides, simulated ones among
  them, are queued once at the first start. That is by design, not a duplicate; the count in Settings is the number of rides
  stored. A callback that throws is reported and dropped; a ride is never failed by its outbox.
- **Order.** The coordinator queues each ride on a coroutine of its own and the outbox orders by enqueue time, so the container
  gives every entry a **strictly increasing enqueue time** (two rides announced in one millisecond would otherwise come out in
  whatever order the coroutines ran). The rides on disk are announced oldest first, and `AppContainerSyncTest` proves the order.
- **Growth bound.** An entry is about 100 bytes and a done id about 30, so a ride a day is some 50 KB a year; compaction drops
  the entries of rides that are done and keeps their ids. Nothing is ever trimmed otherwise: a ride that was not uploaded must
  not be forgotten. Nothing about a ride's numbers, the FIT file or any secret is in it.
- **The container runs a `SyncCoordinator` over `NoopSyncProvider`**, which acknowledges nothing, so every ride stays queued
  for Phase 2 and the status reads "not configured". **There is no network code and no payload format** (spec §8.3).

### 8. Sync settings

Settings, **Sync**: the service address and the token are **stored and not used** ("Not used yet"), with "Waiting to upload".

- **The address** is `http://` or `https://`, a host, an optional port and path, at most 2048 characters, no spaces or control
  characters, and **no user name or password in it** (that is what the token is for). The scheme and host are written in lower
  case; a path is kept as typed. An address that fails is explained in plain words and **not stored** (the field keeps what was
  typed); a stored one that fails (a damaged or edited file) reads as empty.
- **The token** loses control characters and surrounding spaces and is cut at 512 characters; it is never validated for shape,
  because nothing here knows what the service will issue. It is a `SecretText`: it prints as dots, never as itself, so a settings
  object in a log line, an exception message or a failed assertion leaks nothing; the field is masked until **Show**, and typed
  text is kept in the field so the stored value coming back a moment later cannot eat letters.
- **Where it can and cannot be.** It is in the app's private settings file. It is **not** in a log, a ride record, the outbox or
  a Bluetooth session file (`AppContainerSyncTest` scans the ride files, the outbox and the Bluetooth session files, and `ShadowLog`), and not in a backup:
  `allowBackup` is `false` in the manifest and tested, as is the absence of `INTERNET`.

### 9. What changed in existing code

All source-compatible; every new parameter has a default.

| Where | Change |
|---|---|
| `core` `ActionRouter` | optional third constructor parameter `snapshot: (RideSession) -> RideSnapshot` (default the session's own) |
| `ble` `GattTransport` implementations | `writeWithoutResponse` overridden (the interface method already had a default); `PlatformGatt.writeWithoutResponse` and `BluetoothGattShim` |
| `ble` scanner and log | `Advertisement.manufacturerData` filled; `BleLogRole.CLICK` |
| `PairedDevices` | role Click, `click`, `clickButtons`, `controls(Click)`, `clickOptions` flow parameter |
| `DeviceRole`, `DeviceStore` | Click added and persisted |
| `BleDevicesModel` | the `simulatedClick` parameter is gone (the Click's row is real); `clickRow` and `ClickRowState` added |
| `DevicesUi` / `DeviceRowUi` | `locked: UnlockSignal?` on a row |
| `RideActions(session, snapshot, media)` | `snapshot` is optional; delegates to the router; `router` exposed |
| `RideLibrary` | `onSaved` callback, announced at every store and at each start |
| `AppContainer` | `outboxFile`, `mediaFactory`, `elapsedMillis` parameters (tests inject); `outbox`, `sync`, `router`, `learning`, `handlebar` |
| `AppSettings` | `buttonMapping`, `click: ClickSettings`, `syncUrl`, `syncToken: SecretText` |
| ui-kit | `OdTextField` (a text field in the system's colours; `OdSwitch` already existed) |

`design/tools` generated files (`OverdriveTokens.kt`, `OverdriveIcons.kt`) are untouched, and both `--check` runs pass.

### 10. Tests

All on the JVM, no radio: `ble` (write without response and the mapping, the real `ClickDevice` over the Android transport),
`data` (`FileOutboxTest`: kill between steps, torn tail, duplicates, ordering, compaction), `core` (`ActionRouterTest`:
the snapshot accessor), `app-ride`: `ActionRoutingTest`, `HandlebarServiceTest`, `ButtonLearningTest`, `PairedClickTest`,
`PairingClickTest`, `BleDevicesModelTest` (Click row, live), `ClickSettingsTest`, `SyncSettingsTest`, `RideLibraryOutboxTest`,
`AppContainerClickTest` (real container, no activity), `AppContainerSyncTest`, and the Roborazzi pictures and taps of
`ClickUiTest` (`build/shots/`, never committed). One fact the container tests found and the ADR records: a **Start in the
first instants after a ride becomes READY is not heard** by the engine (`RideEngine.startRide` requires the engine's own
state, which follows the trainer's link by a moment); no rider can tap that fast, and the tests wait for the engine's first
snapshot, but it is the engine's behaviour and not changed here.

### 11. Unverified on hardware

**A green test here proves consistency with the notes and with our reading of Android, not with the Click or with the
Xiaoxin Pad.**

| # | Assumption | Where it lives | Settled by |
|---|---|---|---|
| K1 | The Click's manufacturer data (company `0x094A`, first byte `0x0A` right, `0x0B` left) appears in `ScanRecord.manufacturerSpecificData` and the first byte says the side | `ScanMapping.manufacturerData`, `ClickAdvertisements` | 5.1 |
| K2 | A scan filtered on the Click's service finds it, i.e. it advertises `FC82` in its advertisement (not only in GATT) | `PairingController` filter | 5.1 |
| K3 | A write without response to SYNC_RX is accepted and its callback arrives; on Android 13 and later through the three-argument call, before through `setWriteType` | `BluetoothGattShim`, `AndroidGattTransport` | 5.2 |
| K4 | The plain handshake needs no unlock on a right puck, and the reply is `RideOn 02 03` | `ClickOptions` (CK 2 to 4) | 5.2 |
| K5 | A hidden service shows as no characteristic in discovery, and a silent Click as no message within the silence limit; both are what "locked" means | `ClickDevice`, `BleDeviceRows.clickRow` | 5.2, 5.3 |
| K6 | The button bits of click-v2 §4 are right, and a button is one bit | `ClickBits`, `ButtonLearner` | 5.5 |
| K7 | A Click needs no keep-alive and survives five idle minutes | `ClickOptions.keepAliveInterval` | 5.8 |
| B1 | The overlay's foreground service keeps the process, and with it the Click's link and the handlebar chain, alive behind another app and with the screen off | `HandlebarService`, ADR 0006 | 5.6, 5.11 |
| B2 | With no ride live (no foreground service) Android may stop the process; the Click reconnects at the next start | `PairedDevices` | 5.11 |
| B3 | `AudioManager.dispatchMediaKeyEvent` from a background process reaches the video app, and a volume step shows the system volume bar | `AudioManagerMediaController` | 5.6 |
| B4 | `allowBackup="false"` is honoured by this ROM, so the token is never copied | manifest | 7.8 |
| O1 | `fsync` and an atomic rename behave as documented on the tablet's flash; the outbox survives a force-stop | `FileOutbox` | 7.8, 8.5 |

**R8.** The release build minifies and **no keep rules were added**. Checked: the release `mapping.txt` keeps and renames
`ClickDevice`, `PairedDevices`, `HandlebarService`, `ButtonLearning` and `FileOutbox` (they are reached by ordinary calls from
`AppContainer`); the enum names persisted in the settings (`ClickKeepAlive.Off`, …) are the strings the enums were built with,
which R8 does not rename; nothing is looked up by reflection (no serialisation library, no `Class.forName`), and the framework
calls only overrides of library methods (`ButtonLearning`'s and `HandlebarService`'s coroutines are ordinary code). The
`assembleRelease` task is part of the definition of done and ran green.

## Consequences

- A rider's whole handlebar is one chain (`ClickDevice` to `PairedDevices.clickButtons` to `HandlebarService` to `ActionRouter`)
  in the container, tested end to end with no activity, so what the bench can still break is the platform (section 11).
- Because the Click's options are switches and the Bluetooth logs record its sessions, the first bench run answers CK 1 to CK 14
  without a rebuild for most of them.
- The outbox adds one small file and no new permission. Phase 2 swaps `NoopSyncProvider` for a real provider and the ride code
  does not change.
- Not done, on purpose: encryption or any unlock of the Click, the left puck as a controller, a second Click, a button map
  per workout, anything of the sync payload, any network code, and a history or retention rule for the outbox.
