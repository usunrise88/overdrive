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

Not yet: the BLE codecs (M3), the Android storage and share button (M2b-2), gesture detection (M4).

The first section below is the real engine; the one after it is the UI's view of a session
(`RideSession`, `RideSnapshot`), which the engine and the simulated session both provide.

## Ride engine (`app.overdrive.core.ride`)

`RideEngine` is the real thing behind `RideSession` (spec §4.2): a 1 Hz loop over a `TrainerDevice`, an
optional `HeartRateDevice` and the `WorkoutPlayer`. Decisions and reasons: `docs/adr/0004-ride-engine.md`.

```kotlin
val recorder = InMemoryRideRecorder()        // or the persistent recorder of M2b
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

`TrainerDevice` and `HeartRateDevice` are what the BLE adapters of M3 implement: a `connection` state flow, a hot
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
