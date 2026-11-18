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

- the native workout model (`app.overdrive.core.workout`), checked against the shared fixtures in
  `../contracts`;
- the ride-session model and a simulated session (`app.overdrive.core.ride`), described below.

## Ride session (`app.overdrive.core.ride`)

What the overlay and the ride screen are built on (spec §4.2, §4.5 to §4.7). The UI depends only on
`RideSession` and `RideSnapshot`. `SimulatedRideSession` implements it with a simulated rider and
sensors for stage M1; the real engine on top of BLE replaces it in M2 and M3 without touching the UI.

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
| `speedKmh`, `distanceMeters` | virtual, from the speed model (a placeholder until M2) |
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
- After a pause the ERG target ramps back in over 3 s (spec §4.5): in the simulation that comes from the
  power lag, the real engine in M2 sets the commanded target itself.

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
