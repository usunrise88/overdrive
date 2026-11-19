# ADR 0004: The ride engine and its device layer

Status: accepted (stage M2a, 2026-11-18). **Amended by ADR 0032 (resume after a pause, 2026-11-19):** the auto-pause now ends on the
trainer notification that shows pedalling, not at the next tick, and "pedalling" is more than the cadence field; an auto-pause holds a
small target (`autoPauseTargetWatts`, 25 W) instead of 0 W, and the ramp-in starts from the hold. The sections below say where.

## Context

Stage M2 builds the real ride engine in `core/`, still driven by simulated devices; real Bluetooth (M3)
comes later and is written against the interfaces defined here, without the trainer, the strap or the
Click (roadmap, "Hardware verification is deferred"). Spec §4.1 to §4.5 and §8.2 say what the engine
does, but leave open most of how: what a pause is when three things can cause it, which second a
sample describes, what the trainer is told while paused, where a lap ends, which numbers the summary
averages. M2a is the pure-logic half of M2 (engine, physics, gears, workout player, ZWO, record); M2b adds
persistence, crash recovery and the FIT writer on top of the `RideRecorder` seam defined here.

## Decision

### Shape

- **Packages** under `app.overdrive.core`: `device` (trainer and strap interfaces, simulated devices),
  `physics`, `gear`, `workout` (player, import), `ride` (engine, session, snapshot), `record` (ride record,
  recorder, summary). All in `commonMain`, no platform API: even the ZWO reader is a small XML parser in
  Kotlin, about 250 lines, because core must not use JVM XML classes and no multiplatform XML library is
  needed for the five or six element types of a workout file.
- **One queue, one thread of control.** `RideEngine` funnels commands, device events and the 1 Hz tick through a
  single channel drained by one coroutine, as `SimulatedRideSession` does: no locks, deterministic under a test
  dispatcher. The device flows are forwarded into the channel from unconfined coroutines (they only enqueue), so an
  event emitted before a tick is queued before it.
- **Trainer commands leave from their own coroutine.** The engine writes the wanted command into a state flow and
  wakes a sender, which delivers the *latest* one: `requestControl()` first after every (re)connect, then the
  command, then again whenever it changes. A slow Bluetooth write never delays the ride clock (spec §7.3) and a
  congested link is never sent a queue of outdated targets; the price is that two commands issued in the same
  instant send only the second. A failing command is retried after a second, starting over with
  `requestControl()`. Nothing is sent to a trainer that is not connected.
- **Settings.** `RideSettings` bundles FTP, `PhysicsSettings`, `GearSettings`, auto-pause, ramp-in length, the paused
  target, the 1500 W cap and the two reading timeouts. *(ADR 0032 added the auto-pause target and the two resume
  settings.)*

### Devices (spec §4.1)

- `TrainerDevice` and `HeartRateDevice` have a `name`, a `connection` state flow and a hot `data` flow of already
  decoded notifications (`TrainerReading`: power, cadence, the trainer's own speed which is carried but never
  used for the ride's speed *(ADR 0032 uses it for one thing: to tell a rider who pedals again from a flywheel that only
  coasts)*; `HeartRateReading`: bpm and RR intervals in ms). The trainer's commands are the three FTMS control point
  operations the spec lists, as suspend functions. `SimulationParameters` is the FTMS payload without wind speed
  (always 0) and is validated against FTMS's encodable ranges, so a value that passes the model is a value the
  adapter can write.
- **Reconnect with backoff is the adapter's job** (spec §4.1); the engine only sees `connection` change. `CONNECTING`
  counts as down.
- **A heart-rate reading of 0 bpm means no signal** (a strap without skin contact sends 0).
- `SimulatedTrainerDevice` is deliberately strict like FTMS: a command while disconnected, or a target before
  `requestControl()`, throws and is counted. A test that passes has therefore also proved the engine starts over
  with Request Control after each reconnect. `SimulatedBike` adds a simulated rider and strap behind them, reacting to
  the commands, with scenario events: disconnect (null duration = power-off), silence, rider stop, plus the
  existing signal dropouts.
- **`SimulatedRideSession` stays, its rider does not get duplicated.** The M1 ride app is being built against
  `SimulatedRideSession`'s constructor in parallel, so replacing it with the engine over simulated devices would
  break that work for no gain; its tests are also a safety net for the refactor. The rider's signal logic (power lag,
  cadence, heart-rate lag, seeded noise) moved unchanged into the internal `SimulatedRider`, which both the session and
  `SimulatedBike` use. The existing tests pass untouched. The session now reports `pauseReason = MANUAL` when paused.

### Engine behaviour (spec §4.2)

- **States.** `IDLE` until the trainer connects, `READY`, `RIDING` and `PAUSED`, `FINISHED`. The strap is optional.
  A trainer that disconnects in `READY` goes back to `IDLE`. Sensor values in the snapshot stay null before the start
  and after the end, as ADR 0003 says.
- **Three independent pause reasons**: manual, auto, trainer disconnected. The ride runs only when none holds, and
  `RideSnapshot.pauseReason` (new, additive) reports the first that does, in the order trainer, manual, auto, so the
  rider is told what to fix.
  - *Auto-pause*: three consecutive seconds of cadence exactly 0 (a missing cadence is not zero), while riding. The
    three seconds still count as riding time. `autoPause = false` turns it off. ~~Pedalling (cadence above 0) lifts it,
    seen at the next tick.~~ **Amended (ADR 0032):** pedalling lifts it **on the trainer notification that shows it**
    (not at the next tick), and pedalling is a cadence above 0, *or* power of at least `autoResumePowerWatts` while the
    flywheel is not winding down, *or* the flywheel's own speed rising by `autoResumeFlywheelStepKmh` between two
    readings. The three seconds are still counted at the tick.
  - *Manual pause* overrides it: pedalling does not lift a manual pause and stopping does not turn it into an auto
    pause; only `resume()` lifts it. `resume()` lifts a manual or auto pause and cannot bring the ride back while the
    trainer is gone. `pause()` only acts while `RIDING` (ADR 0003's table); on an auto-paused ride it does nothing and
    pedalling still resumes it.
  - *Trainer disconnect*: pauses at once (not at the next tick), clears the held readings, and the reconnect resumes the
    ride. This is acceptance criterion 4: the seconds of the outage are recorded as paused samples with null power
    and cadence, and the workout position stands still.
- **Clocks.** The ride has one `RideSample` for every second from its start, paused or not, so `samples.size` is
  the duration. `RideSnapshot.elapsedSeconds` and the workout position count only the seconds that were not paused.
  A second is paused or riding according to the state at the moment of its tick. *(With ADR 0032 the state can change
  between ticks, so the second in which pedalling is first seen is a riding second; before, it was the last paused one.)*
- **A sample describes the second that just ended**: offsets run 1, 2, 3, …; the readings are those at the end of
  the second; the target, gear and step are the plan in force *during* it. So a workout step of *n* seconds has exactly
  *n* samples and the laps tile the ride exactly. The sample at the instant a step changes still belongs to the old step.
- **Readings.** A held reading is reused for up to 3 s (trainer) or 5 s (strap) when none arrives, counted in ticks
  so no clock is involved; then it is a gap. Both are settings; the numbers are guesses to tune on the bench.
- **What the trainer is told** (spec §4.3 to §4.5): ERG target of the current second times the intensity, rounded and
  capped at 1500 W; simulation parameters at the gear in free steps and in a ride without a workout. While paused, or in
  `READY`, an ERG step gets **0 W**, because a trainer holding 250 W against a stopped flywheel cannot be restarted;
  on resume the target ramps in linearly over 3 s from there, in whole-second steps (a third, two thirds, full). The ramp
  is not applied at the start of the ride, which the spec does not ask for, nor in free steps. After the ride the trainer
  is put into simulation on the flat, so it is not left holding an ERG target. `pausedTargetWatts` is a setting; whether
  0 W is accepted by the D500 is a bench question. **Amended (ADR 0032):** 0 W is now the hold of a *manual* pause, of
  `READY` and of a pause by the trainer's link only. An *auto*-pause holds `autoPauseTargetWatts` (25 W, never more
  than the step asks for), because 0 W is suspected of hiding the first pedal strokes from a trainer with no crank
  sensor. The ramp-in starts from whichever hold was in force, and after a resume by pedalling its first step is
  taken at once.
- **Speed and distance** come from the physics model at the second's power: no power, no movement; paused, no
  movement. A ride finishing by itself needs a workout; a free ride ends with `finish()`.

### Physics and gears (spec §4.3, §4.4)

- `FlatPhysicsSpeedModel` solves `η·P = Crr·m·g·v + ½·ρ·CdA·v³` by Newton's method from the drag-only speed, which is
  always above the root of this convex function, so it converges monotonically. It is steady-state: no inertia, so
  a coast stops the rider at once. Defaults: 80 kg system, CdA 0.35, Crr 0.004, ρ 1.225, drivetrain efficiency 1.0
  (what other virtual-ride apps assume; 0.975 is the honest figure). With those, 200 W is 33.4 km/h (the brief's "about
  32" was a rough guess; the test compares with an independent bisection).
- `GearSettings`: grade offset `(gear - neutral) · 1%`, default 24 gears, neutral 12 (11 easier, 12 harder). The total with
  the route grade is clamped to ±15% (the trainer's limit, both directions) and rounded to FTMS's 0.01%. The simulation
  parameters carry Crr and the wind resistance coefficient `½·ρ·CdA` from the physics settings. The flywheel-inertia
  caveat of the spec is in the KDoc of `GearSettings`.

### Workout player and import (spec §4.5)

- `WorkoutPlayer` is pure and deterministic and works on `FlatStep`s: `advance(seconds)`, `target`, intensity 50 to
  150% in 5% steps (ERG steps only, as the session contract says), next and previous interval (previous restarts the
  step after more than 3 s into it), `beginRampIn()`. Targets are rounded to whole watts, halves up, with a 1e-9 W
  allowance because `0.45 · 250` is not exactly 112.5 in binary and a one-watt flicker between two computations of the
  same number is worth preventing.
- **ZWO import** (`WorkoutImport`): `SteadyState` to steady, `Warmup`, `Cooldown` and `Ramp` to a ramp **from
  `PowerLow` to `PowerHigh` in file order** (Zwift writes a cool-down's higher value first), `IntervalsT` to a repeat of a
  steady "On" and "Off" step, `FreeRide` to free, `MaxEffort` to free labelled "Max effort", `textevent` ignored. Any
  other element is an error: dropping a step would shift every later one. Warm-up and cool-down get labels, the id is the
  slug of the name prefixed `zwo-`. Golden fixtures with the expected native JSON are in `contracts/fixtures/zwo/`.

### Ride record (spec §8.2)

- `RideRecord` = `RideHeader` (id, wall-clock start and end, workout id, FTP, device names), samples, RR intervals
  (stamped with the second their notification arrived in), laps, summary. The ride's sync state lives in the M4 outbox,
  not in the record.
- **A lap is a maximal run of samples in the same workout step.** Skipping back starts a new lap, a skipped step has
  none, a pause does not split a lap, a ride without a workout is one lap with a null step. Laps tile `[0, duration]`.
- **Summary maths** run over the seconds that were *not paused* and, per quantity, those that have a reading. Average
  power, maximum, work (sum of watts in kJ) and heart rate are as stated. **Normalised power** is Coggan's: the 30 s
  rolling mean over the series of those seconds, each complete window to the fourth power, averaged, fourth root; no
  complete window (fewer than 30 values) means null, and then so are the intensity factor and the **TSS**
  `t·NP·IF/(FTP·3600)·100`, with `t` the length of that series. All of it is computed incrementally by an internal accumulator,
  so memory is constant and a ride recovered from disk goes through the same code. Hand-computed series in the tests.
- The record types are plain Kotlin with no `@Serializable` and there is no JSON schema yet: persistence in M2b and the
  sync payload in Phase 2 decide the wire format, and by `CLAUDE.md` that starts in `contracts/`. (Settled in M2b-1:
  ADR 0007.)

## What the next stages plug into

**M2b (persistence, recovery, FIT).**
- Implement `RideRecorder`. `onSample` is called once a second on the engine's coroutine, in order, offsets 1, 2, …
  without gaps: append it to disk there (hand the write to an IO dispatcher so the engine is not delayed), call
  `onRrIntervals` likewise, and `onRideFinished(endEpochMillis)` marks the end. A crash simply stops the calls.
- Recovery: read header, samples and RR intervals back and call `RideRecordBuilder.recover(...)`; laps and summary come
  out of the same code that ran live. `InMemoryRideRecorder` can be wrapped for the live `RideRecord`.
- The FIT file is generated from `RideRecord`: records from `samples` (power, heart rate, cadence, speed, distance;
  skip or mark `paused` samples as the writer's design decides), laps from `laps`, HRV from `rrIntervals`.

**M3 (BLE).**
- Implement `TrainerDevice` over FTMS: decode Indoor Bike Data into `TrainerReading` (cadence rounded to whole rpm, the
  speed carried but unused), map the three commands to Request Control (plus Start or Resume where the trainer needs
  it), Set Target Power and Set Indoor Bike Simulation Parameters, and throw when a write fails. Implement
  `HeartRateDevice` over the Heart Rate Measurement characteristic (RR in 1/1024 s to ms, 0 bpm without contact).
  Reconnect with backoff inside the adapter; the engine only watches `connection`.
- Feed recorded or synthetic GATT sessions through `SimulatedTrainerDevice.emit` and `SimulatedHeartRateDevice.emit` in
  the same tests; the strict simulated trainer already encodes the control-point ordering the engine relies on.
- The engine needs a scope on a background dispatcher.

## Consequences and open items

- `RideSnapshot` has a new field, `pauseReason`, at the end with a default: existing code and previews compile unchanged.
- Auto-pause relies on the trainer reporting cadence 0 when the rider stops; the D500's behaviour (and whether it reports
  0 or holds the last value) is for the bench. So are the 0 W paused target, the reading timeouts and the 3 s ramp's
  feel (it has whole-second steps). *Riding the bike showed the other side of it (owner's report, 2026-11-19, OVD-24):
  after a pause the cadence was not picked up for 10 to 15 s. ADR 0032 is the answer, and still a hypothesis.*
- The engine's speed has no inertia; a coasting rider stops instantly. A kinetic-energy model would be a change inside
  `SpeedModel`'s implementation.
- Two commands sent in the same instant (a hold of the gear button repeating faster than the loop) send only the last
  one to the trainer; the engine state is exact either way.
- `SimulatedBike` delivers second *k*'s readings just before the engine's tick *k* only if it is started no later than
  the ride (both tick from the same instant); otherwise readings arrive up to a second early or late, which the engine
  tolerates by design.
