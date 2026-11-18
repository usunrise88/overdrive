# core

The shared pure-Kotlin core (spec §7.1): protocol codecs (FTMS, heart rate, Click), ride engine,
workout model and player, physics, gear logic, FIT writer, gesture detection, and simulated
devices for hardware-free rides.

**No Android or other platform imports.** This is a separate Gradle build so the rule is enforced
structurally: it cannot see the Android SDK, and it builds and tests with only a JDK. `android/`
consumes it with `includeBuild("../core")`.

```bash
./gradlew check          # tests + ktlint
./gradlew spotlessApply  # format
```

Currently implemented:

| Package (`app.overdrive.core.`) | What | Spec |
|---|---|---|
| `workout` | Native workout model, ZWO import, the pure `WorkoutPlayer` | §4.5, §8.1 |
| `ride` | `RideSession` and `RideSnapshot` (what the UI reads), `RideEngine` (the real engine), `SimulatedRideSession` (M1 UI stand-in), `PowerZone` | §4.2, §4.6 |
| `device` | `TrainerDevice` and `HeartRateDevice` (FTMS-shaped), simulated devices, `SimulatedBike` | §4.1, §7.5 |
| `physics` | `FlatPhysicsSpeedModel`: virtual speed and distance on the flat | §4.3 |
| `gear` | `GearSettings`: virtual gears as clamped grade offsets | §4.4 |
| `record` | `RideRecord` (samples, laps, RR series, summary), `RideRecorder`, its JSON wire format, the crash-safe ride log | §4.2, §8.2 |
| `fit` | `FitWriter`: a ride record as a FIT activity file | §4.8 |
| `ble` | The Bluetooth boundary (`GattTransport`, scanner, environment), FTMS and Heart Rate codecs, the trainer and strap adapters, the reconnect supervisor, the session recorder, and a fake Bluetooth with replay | §4.1, §7.5 |
| `click` | The Zwift Click v2: `ClickCodec` (pure, never throws), the bit table, edge detection, `ClickAdvertisements`, `ClickOptions`, `ClickDevice` and its `ClickState` | §4.7, §9 |
| `control` | The handlebar: the ten `ClickAction`s, `GestureDetector` (500 ms hold, 150 ms volume repeat), `ButtonMapping` and `ButtonLearner`, `ActionRouter`, `HandlebarControls` | §4.7 |
| `sync` | The sync stub: `SyncProvider`, `NoopSyncProvider`, `Outbox`, `InMemoryOutbox`, `SyncCoordinator` | §4.9 |

The Android side of Bluetooth (the `GattTransport` on `BluetoothGatt`, scanning, permissions, remembered devices, pairing and the
Devices screen) is in `android/` (M3-2, ADR 0010), and so are the storage, recovery and share buttons (M2b-2, ADR 0008). The
Click's wiring into the app, the outbox on disk and the settings are M4-2.

The first section below is the real engine; the one after it is the UI's view of a session
(`RideSession`, `RideSnapshot`), which the engine and the simulated session both provide.

## Ride engine (`app.overdrive.core.ride`)

`RideEngine` is the real thing behind `RideSession` (spec §4.2): a 1 Hz loop over a `TrainerDevice`, an
optional `HeartRateDevice` and the `WorkoutPlayer`. Decisions and reasons: `docs/adr/0004-ride-engine.md`.

```kotlin
val recorder = InMemoryRideRecorder()        // plus the crash-safe log: see "Durable and exportable" below
val engine: RideSession = RideEngine(
    scope = rideScope,                       // background dispatcher, cancelled when the app stops
    trainer = trainer,                       // BLE adapter on the tablet, SimulatedTrainerDevice in tests
    heartRate = strap,                       // optional
    workout = WorkoutImport.parse(fileText), // native JSON or Zwift ZWO; null for a free ride
    settings = RideSettings(ftp = 250, physics = PhysicsSettings(systemMassKg = 82.0)),
    recorder = recorder,
)
engine.start()                               // once snapshots.value.state == READY
```

- **States.** `IDLE` until the trainer connects, `READY`, then `RIDING` and `PAUSED`, `FINISHED` at the end.
  A pause has a reason, `RideSnapshot.pauseReason`: `MANUAL` (only `resume()` ends it), `AUTO` (3 s of zero
  cadence, pedalling again ends it, `RideSettings.autoPause` turns it off) or `TRAINER_DISCONNECTED` (pauses at
  once, the reconnect resumes). Nothing is lost: every second is still recorded, the outage as paused samples
  with null power and cadence.
- **Gaps.** A strap that drops, reads 0 bpm or goes silent for 5 s makes `heartRate` null; it never pauses or
  stops the ride. A trainer that is silent for 3 s makes power and cadence null.
- **What the trainer is told.** ERG step: the target (ramps interpolated, times the intensity). Free step: simulation
  parameters from the gear and the physics settings. While paused: 0 W in ERG steps, ramped back in over 3 s on
  resume. After the ride: simulation on the flat. After every reconnect: `requestControl()` first. Commands go out
  from their own coroutine and only the latest matters.
- **Clocks.** `elapsedSeconds` and the workout position count moving seconds only. The record has one sample for
  every second of the ride, pauses included, so `samples.size` is the ride's duration.
- **Speed and distance** come from `FlatPhysicsSpeedModel` (`PhysicsSettings`: system mass, CdA, Crr, air density,
  drivetrain efficiency); the trainer's own speed is ignored.

### Devices (`app.overdrive.core.device`)

`TrainerDevice` and `HeartRateDevice` are what the BLE adapters (`FtmsTrainerDevice`, `HeartRateStrapDevice`, below) implement: a `connection` state flow, a hot
`data` flow of decoded notifications, and for the trainer `requestControl()`, `setTargetPower(watts)` and
`setSimulation(SimulationParameters(gradePercent, crr, windResistance))`. The simulated devices are fakes you drive
by hand (`connect()`, `disconnect()`, `powerOff()`, `emit(...)`, and for the trainer `received` and `failNextCommands`);
`SimulatedTrainerDevice` is strict like FTMS, it refuses a target before `requestControl()` and anything while
disconnected. `SimulatedBike` puts a simulated rider on both, reacting to the trainer's commands, with scenario
events: `DeviceDisconnect` (a null duration is a power-off), `DeviceSilence`, `RiderStop`, and the existing
`SignalDropout`s.

```kotlin
val bike = SimulatedBike(scope, ftp = 250, seed = 1, events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, 800, 30)))
val engine = RideEngine(scope, bike.trainer, bike.heartRate, workout, RideSettings(ftp = 250), recorder = recorder)
bike.start()
engine.start()
```

### Bluetooth (`app.overdrive.core.ble`; ADR 0009, `docs/protocols/ftms-hr.md`)

Everything above the Bluetooth stack, with **no Android type**: Android (M3-2) implements four small interfaces, and the
rest runs, and is tested, on a plain JVM against a fake radio. Written from the Bluetooth notes; **nothing is verified on
the owner's trainer or strap yet**, and every guess is a named constant or option (ADR 0009 §8).

| Package | What |
|---|---|
| `ble` | The boundary: `GattTransport` (one single-use GATT session; results are `GattResult`, never exceptions; one operation at a time), `GattTransportFactory`, `GattScanner`, `BleEnvironment` (Bluetooth off, permission), opaque `DeviceId` (its `toString` is a fingerprint, never an address), `GattUuid` |
| `ble.codec` | Pure codecs, never throw: Indoor Bike Data, Fitness Machine Feature, Control Point (commands and Response Code), Fitness Machine Status, Heart Rate Measurement. All 44 vectors of `ftms-hr.md` §8 are tests |
| `ble.adapter` | `FtmsTrainerDevice` (a `TrainerDevice`) and `HeartRateStrapDevice` (a `HeartRateDevice`), `FtmsOptions`, `HeartRateOptions` |
| `ble.link` | `LinkSupervisor`: reconnect with backoff (`BackoffPolicy`), `retryNow()`, a fresh transport after every failure, `LinkStatus` for the Devices screen |
| `ble.recording` | `BleSessionRecorder` and a `GattTransport` decorator: every GATT event as a text line (format in `ftms-hr.md` §7.1), no addresses, identity values redacted |
| `ble.fake` | `FakeBle` (radio, factory, scanner, environment), `FakeFtmsTrainer`, `FakeHeartRateStrap`, `ReplayPeripheral` (plays a recording), scripted failures. Strict like Android: it records any contract violation |
| `device.RrIntervals` | The domain rule for RR in milliseconds (1/1024 s, halves round up) |

What M3-2 implemented (`android/ble`, ADR 0010), and what it gets:

```kotlin
// Android implements these four (the only place an address or a BluetoothGatt exists):
class AndroidTransports : GattTransportFactory { override fun open(device: DeviceId): GattTransport = ... }
class AndroidScanner : GattScanner { override fun scan(filter: ScanFilter): Flow<ScanEvent> = ... }
class AndroidEnvironment : BleEnvironment { override val availability: StateFlow<BleAvailability> = ... }

// Optional: record every session. Wrap once; the adapters never know.
val recorder = BleSessionRecorder(ioScope, sink = LineSink { file.appendLine(it) }, nanoClock = { SystemClock.elapsedRealtimeNanos() }, model = "D500")
val transports = AndroidTransports().recording(recorder, ioScope)

// The devices the unchanged RideEngine takes. Each reconnects on its own once started.
val trainer = FtmsTrainerDevice(scope, trainerId, transports, environment, name = "trainer", options = FtmsOptions())
val strap = HeartRateStrapDevice(scope, strapId, transports, environment, name = "strap", options = HeartRateOptions())
trainer.start(); strap.start()                       // LinkControls: start(), retryNow(), close(), status
val engine = RideEngine(scope, trainer, strap, workout, settings, recorder = rideRecorder)
```

- **Connected means commandable.** The trainer's `connection` becomes `CONNECTED` only after discovery, MTU, Feature, the
  three subscriptions, Request Control and Start. A dropped link (status 133 included) closes that transport and opens a
  fresh one after the backoff; `status` says why (`Connecting`, `WaitingToRetry`, `BluetoothUnavailable`, ...).
- **Failures are typed.** A rejected or unanswered Control Point command throws `TrainerCommandException` (the engine retries
  it after a second); a notification that cannot be decoded is dropped and reported as a `TrainerEvent`.
- **The fake in your tests.** `FakeBle(scope, nanoClock)` with `world.add(id, FakeFtmsTrainer())` and
  `world.add(id, FakeHeartRateStrap())`; the world is itself the `GattTransportFactory`, the `GattScanner` and the
  `BleEnvironment`. See `BleTestSupport.kt` (`BleRig`), `BleRideTest` (the real engine over the fake) and
  `LinkSupervisorTest`. Inject failures with `peripheral.faults` or `dropLink()`, Bluetooth off with
  `world.setAvailability(...)`, and check `world.violations` is empty at the end. A fake's strictness is the point: a green
  test also proves the code is a legal Android client.
- **Recordings and fixtures.** `BleSessionFormat.parse` and `write` read and write the file; `ReplayPeripheral(session)` plays
  one back on virtual time (failed connects and dropouts included). The files in `../contracts/fixtures/ble/` are generated
  (`./gradlew jvmTest -PregenerateBleFixtures=true`), never real recordings; a test fails when they go stale.

### The Zwift Click and the handlebar (`app.overdrive.core.click`, `control`; ADR 0011)

```kotlin
// One Click, reconnecting by itself; the ride never depends on it (spec §4.7: "continue without the Click").
val click = ClickDevice(scope, clickId, transports, environment, name = "Click", options = ClickOptions())
click.start()                  // LinkControls like the other devices: start(), retryNow(), close(), status
click.state                    // ClickState: Active, Waiting, NeedsUnlock(SERVICE_HIDDEN | NO_EVENTS), NotFound, BluetoothUnavailable, ...
click.buttons                  // hot flow of ButtonChange(pressed, released, held, synthetic): collect before start()

// Changes -> mapping -> gestures -> router -> the ride. media is a MediaKeys the Android side implements.
val router = ActionRouter(engine, media)                  // or ActionRouter({ currentSession }, media)
val controls = HandlebarControls(scope, click.buttons, { ButtonMapping.DEFAULT }, router::dispatch, nowMillis = ::clock)
controls.start()
```

- **The codec is the notes' §3.1 and §4 as pure functions** (`ClickCodec.parse`, `handshake`, `postHandshake`, `neutralFrame`,
  `parseHandshakeReply`): no input throws, a type it does not know is `ClickMessage.Unrecognised`, never an error. The
  encryption of `docs/protocols/click-v2.md` §3.3 is **not implemented**, and nothing unlocks a controller. `ClickBits` is the bit
  table, `ButtonEdges` the press and release edges from successive bitmaps. Vectors are derived by hand from the notes
  (`ClickCodecTest`, CK-V1 to CK-V15).
- **Set-up** (§3.1): discover, ASYNC notify, SYNC_TX indicate, `RideOn` written without response, the reply, optionally
  `FF 04 00`, firmware and battery. **Everything the notes leave open is a `ClickOptions` field** with the bench question that
  settles it (the table is in ADR 0011 §9): the device-type bytes, whether to write `FF 04 00`, how strict the reply check is,
  the keep-alive (none by default) and its frame, the no-event timeout, what a hidden service means.
- **Unlock handling** is a state, `ClickState.NeedsUnlock`, for a hidden service or a controller that answers and then sends no
  frame at all. **A release lost in a drop never leaves a button held**: the adapter releases what was down with a synthetic
  `ButtonChange`, and the gesture layer forgets those presses without firing anything.
- **The gestures** (`GestureDetector`, pure, virtual-time tests): Plus, Button 2 and Button 3 fire their press **on release**
  (under 500 ms) and their hold **once at 500 ms**; Button 4 and 5 fire a volume step **on press** and repeat from 500 ms every
  150 ms. A hold needs the button alone, a late caller gets one step and never a burst, and the constants are
  `GestureTiming.HOLD_THRESHOLD_MILLIS` and `VOLUME_REPEAT_MILLIS`. `HandlebarButton` is the table of what each button does.
- **The mapping** (`ButtonMapping`, role to the Click's bits, stored as text with `serialize` and `parse`) has an ergonomic default
  and is replaced by *Learn buttons*: feed `ButtonLearner` the `held` mask after each change; it asks for one press per role
  and refuses a combo or a duplicate.
- **The router** (`ActionRouter`) is the rule of the app's `RideActions` restated in `core`: in an ERG step the gear actions change
  intensity by 5 points, the pause toggle works while riding or paused.
- **The fake in your tests.** `FakeClick` (`world.add(id, FakeClick())`) answers `RideOn 02 03`, delivers frames, and can be
  `lock = HIDDEN` or `SILENT`; `fake.script { tap(ClickBits.PLUS, at = 3.seconds); hold(...); drop(...); silence(...); unlock(...) }`.
  See `ClickDeviceTest`, `ClickRideTest` (all ten actions through the real engine) and the sessions in
  `../contracts/fixtures/ble/click-*.click.session.txt`.

### The sync stub (`app.overdrive.core.sync`; ADR 0011, spec §4.9)

`SyncCoordinator(scope, outbox, { provider }).start()`; when a ride is stored, `coordinator.rideFinished(OutboxEntry.of(record, now))`
returns at once. `NoopSyncProvider` acknowledges nothing, so every ride stays in the outbox for Phase 2; a failing provider or outbox is a
`SyncStatus`, never an exception, and never reaches the ride. `InMemoryOutbox` is the stage's stand-in; the on-disk one is the
app's (M4-2). There is no network code and no payload format here.

### Workouts (`app.overdrive.core.workout`)

`WorkoutImport.parse(text)` reads native JSON or ZWO (`SteadyState`, `Warmup`, `Cooldown`, `Ramp`, `IntervalsT`,
`FreeRide`, `MaxEffort`; `textevent` is ignored; anything else is an error). `WorkoutPlayer` is pure and
deterministic: give it `Workout.flatSteps()` and call `advance()` once per second of riding; it answers with the
step, the ERG target (`PlayerTarget.Erg`) or `PlayerTarget.Free`, handles intensity, next and previous interval,
and the 3 s ramp-in (`beginRampIn()`). Golden ZWO files are in `../contracts/fixtures/zwo`.

### Record (`app.overdrive.core.record`)

`RideRecorder` receives `onRideStarted`, a `RideSample` per second, `onRrIntervals` and `onRideFinished`.
`InMemoryRideRecorder.record` is the `RideRecord` (header, samples, RR series, laps, summary) at any moment;
`RideRecordBuilder.recover(header, samples, rr)` rebuilds one from samples read back from disk. Gaps are null
fields, never zeros. A lap is one stretch in one workout step. The summary has duration, moving time, average and
normalised power (Coggan: 30 s rolling mean, fourth-power mean), work in kJ, intensity factor, TSS, heart-rate
average and maximum, all over the seconds that were not paused (`RideSummary.of(samples, ftp)` computes it).

### Durable and exportable (`record`, `fit`; ADR 0007)

```kotlin
val live = InMemoryRideRecorder()
val log = RideLogRecorder(FileByteSink(File(ridesDir, "$rideId.ridelog")))   // JVM file sink; ByteSink is the seam
val engine = RideEngine(scope, trainer, strap, workout, settings, recorder = CompositeRideRecorder(log, live))

// at the end, or on the next launch after a crash
val record: RideRecord = live.record ?: RideLog.recover(FileByteSource(file))!!.toRecord()
val fit: ByteArray = FitWriter(FitOptions(utcOffsetSeconds = tzOffset)).write(record)
val json: String = RideRecordJson.encode(record)       // schema: contracts/schema/ride-record.schema.json
```

- **The log** is one line per record, `<crc32> <kind> <json>`: a `header`, a `sample` per second, an `rr` per notification,
  a `finished` marker. `RideLog.recover` never throws and returns the intact beginning of whatever is on disk (cut off at
  any byte, zero-filled, garbled), saying whether the ride `FINISHED` or was `INTERRUPTED`; `toRecord()` rebuilds laps and
  summary with the live code. A failing sink is kept in `RideLogRecorder.failure` and never stops the ride.
- **`AsyncRideRecorder(delegate, scope)`** puts a queue in front of any recorder: the engine's calls return at once and one
  coroutine in `scope` passes them on in order, so a slow disk never delays the ride. `close()` waits until everything queued has
  been written; `flush()` waits without ending. The Android app wraps the log writer in it (ADR 0008).
- **The FIT writer** is our own (no Garmin SDK), deterministic, and reproduces the worked example of
  `docs/protocols/fit.md` section 6 byte for byte. What intervals.icu may disagree with is a field of `FitOptions`, with the
  doc's recommendation as default. Pass the device's UTC offset: the record holds UTC only. An opt-in test decodes the files
  with Garmin's decoder if you give it the jar (`-PfitOracleJar=...`, ADR 0007); the jar is never part of the build.

## Ride session (`app.overdrive.core.ride`)

What the overlay and the ride screen are built on (spec §4.2, §4.5 to §4.7). The UI depends only on
`RideSession` and `RideSnapshot`. `RideEngine` implements it over real or simulated devices;
`SimulatedRideSession` implements it with a built-in simulated rider and sensors, for building the UI without
any device layer (stage M1). Both share the rider's signal model; swapping one for the other does not touch the UI.

```kotlin
val session: RideSession = SimulatedRideSession(
    scope = rideScope,                 // any CoroutineScope; tests pass a virtual-time one
    workout = workout,                 // app.overdrive.core.workout.Workout, or null for a free ride
    ftp = 250,
    seed = 1,                          // same seed, same ride
    scenario = SimulationScenario(     // optional: gears, noise, signal dropouts
        dropouts = listOf(SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 30, durationSeconds = 20)),
    ),
)
val snapshot by session.snapshots.collectAsState()   // a StateFlow<RideSnapshot>
session.start()
```

**What a UI collects.** `session.snapshots` is a `StateFlow<RideSnapshot>`: an immutable value with
everything shown on screen. It is republished once a second while riding and right after any command
that changes something.

| Field | Meaning |
|---|---|
| `state` | `IDLE` (no devices), `READY`, `RIDING`, `PAUSED`, `FINISHED` (terminal) |
| `elapsedSeconds` | ride time; stops while paused |
| `power`, `heartRate`, `cadence` | `Int?`. **null means no signal, draw a gap, not 0.** Also null before the start and after the end. A rider who is not pedalling reads 0 |
| `speedKmh`, `distanceMeters` | virtual, from the speed model (`FlatPhysicsSpeedModel` in `RideEngine`; a placeholder in `SimulatedRideSession` unless given the real one) |
| `mode` | `ERG` or `SIMULATION`: how the current step drives the trainer. `SIMULATION` without a workout |
| `targetPower` | watts in an ERG step, already scaled by `intensityPercent`; null in a free step |
| `gear`, `neutralGear`, `gearCount` | `gear` is **1-based** (1 easiest, `gearCount` hardest, default 24 with neutral 12) and null in an ERG step. Compact overlay: show `targetPower` when `mode == ERG`, else `gear` |
| `intensityPercent` | 50 to 150 in steps of 5, default 100; scales ERG targets only |
| `zone` | `PowerZone?` (Z1..Z7, Coggan) of `power`, null when there is no power. Colour it, it is the one accent |
| `ftp` | watts |
| `workout` | `WorkoutProgress?`, null without a workout |

`WorkoutProgress` has the step list (`steps: List<FlatStep>`, repeats expanded, each with
`startSeconds`, `durationSeconds`, `targetFrom`/`targetTo` as FTP fractions and `mode`, enough to draw the
miniature profile), `stepIndex`, `stepLabel`, `nextStepLabel`, `stepElapsedSeconds`,
`stepRemainingSeconds`, `workoutElapsedSeconds` and `workoutRemainingSeconds`. The `steps` list is the same
instance in every snapshot, so a chart can key its cache on it.

**Commands** are `start`, `pause`, `resume`, `finish`, `nextInterval`, `previousInterval`, `gearUp`,
`gearDown`, `intensityUp` and `intensityDown`. They never throw and never block, may be called from any
thread, and **a command that does not apply in the current state is a silent no-op**, so buttons need no
state checks. They are queued and applied in order on the session's coroutine, so the effect shows up in
`snapshots` a moment after the call, not before. The full state-by-state table is in the KDoc of
`RideSession`; the short version:

- `start` only in `READY`; `pause` only while `RIDING`; `resume` only while `PAUSED`; `finish` while `RIDING`
  or `PAUSED`. `FINISHED` ignores everything.
- `nextInterval` jumps to the start of the next step and does nothing in the last one (use `finish`).
  `previousInterval` restarts the current step when more than 3 s into it, otherwise goes to the previous
  step. Both work while paused, need a workout and do not rewind `elapsedSeconds`.
- `gearUp`/`gearDown` act only in simulation steps; `intensityUp`/`intensityDown` only in ERG steps. Each is
  clamped at its ends. **The Click's rule "in ERG steps the gear buttons change intensity" (spec §4.7) belongs
  to the gesture layer**: it reads `snapshot.mode` and calls `intensityUp()` instead of `gearUp()` in ERG.
- A ride with a workout finishes by itself after the workout's last second; a free ride ends with `finish`.
- After a pause the ERG target ramps back in over 3 s (spec §4.5): `RideEngine` commands the ramp itself; in
  `SimulatedRideSession` it only shows as the simulated rider's power lag.
- A snapshot's `pauseReason` says why a ride is `PAUSED` (`MANUAL`, `AUTO`, `TRAINER_DISCONNECTED`). Only the
  engine pauses on its own; `SimulatedRideSession` only ever pauses manually.

**Simulated session.** Each tick advances the ride by one second; with the default `tickInterval` that is
real time, and a shorter interval fast-forwards a long workout while you try the UI. Power follows the ERG
target with a ~2 s lag and noise, floats around 55% of FTP in simulation steps and moves about 4% per gear;
cadence sits around 90 rpm; heart rate follows power with a ~25 s lag. `SimulationScenario.noise = 0.0`
gives smooth, exact signals, handy for screenshots. `SimulatedRideSession(startConnected = false)` begins in
`IDLE` until `connect()` is called. Dropouts (`SignalDropout`) blank one signal for N seconds of *sensor
time* (seconds since `start()`, which keeps running while paused) so gap rendering can be tried; the rest of
the ride is unaffected.

`PowerZone.of(power, ftp)` and `Workout.flatSteps()` are plain functions you can use anywhere.

Targets: JVM only. iOS is a non-goal (spec §1); keeping this module platform-free leaves the door
open.
