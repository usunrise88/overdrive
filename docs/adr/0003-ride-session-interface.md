# ADR 0003: Ride session interface and the simulated session

Status: accepted (stage M1, 2026-11-18)

## Context

Stage M1 ships a ride app driven by a simulated trainer and strap (roadmap), and the overlay and ride
screen are built before any BLE code exists. The UI needs one stable seam to build against, which the
real ride engine (M2) and the BLE devices (M3) must later satisfy without UI changes (spec §4.2, §7.5).
Spec §4.2, §4.5, §4.6 and §4.7 say what the UI shows and which controls exist, but not the shape of the
state it reads or what each control does in each state.

## Decision

`app.overdrive.core.ride` in `core` holds the seam; the UI depends on two types only.

- **`RideSnapshot`**: one immutable value with everything on screen, in `core`, free of UI concerns.
  `RideSession.snapshots` is a `StateFlow<RideSnapshot>` republished at 1 Hz while riding and right after
  any command that changes something.
- **Missing data is null, never 0.** Power, heart rate and cadence are `Int?` (spec §4.1 "gaps"). A rider
  who is not pedalling reads 0. Sensors are also null before the start and after the end.
- **Commands are fire and forget, and a command that does not apply is a no-op.** `RideSession` has ten
  commands; none throws or blocks. A UI and the Click gesture layer can bind them to buttons without state
  checks, and a held or doubled press is harmless. The simulation queues them on a channel and applies them
  in order on its own coroutine, which makes it thread-safe without locks (no atomics exist in common
  Kotlin) and deterministic under virtual time. Effects appear in `snapshots` a moment after the call.
- **Gears and intensity are separate commands.** In an ERG step the Click's gear buttons change intensity
  (spec §4.7); that routing is the gesture layer's job, using `RideSnapshot.mode`. The session does what
  each command says: gear only in simulation steps, intensity only in ERG steps. Nothing changes silently.
- **Gears are 1-based**, the number a rider reads: 1 is the easiest, `gearCount` the hardest, default 24 with
  neutral 12 (the extra gear on the hard side). `gear` is null in ERG steps.
- **Skipping.** `nextInterval` in the last step is a no-op (accidental holds must not end a ride; `finish`
  does that). `previousInterval` follows the media-player convention: more than 3 s into a step it restarts
  the step, otherwise it goes back one. Skipping moves the workout position only, never the ride clock.
- **Clocks.** `elapsedSeconds` is ride time and stops while paused. While paused the sensors keep reporting
  (power and cadence 0, heart rate relaxing), so the strap still shows.
- **Zones.** `PowerZone.of` reads Coggan's printed upper bounds as inclusive and compares exact ratios in
  integer arithmetic, which closes the gaps of the whole-percent table: 55.0% is Z1, 55.1% is Z2.
- **`SimulatedRideSession`** is deterministic from a seed (`kotlin.random.Random`; the same four noise
  draws every tick, so dropouts and pauses never shift the noise) and driven by the coroutine scope it is
  given, so tests run an hour of riding in virtual time. Signal dropouts live in a `SimulationScenario`
  and only blank the reading. Speed comes from a `SpeedModel` whose placeholder implementation is replaced
  by the physics model in M2.
- `core` now depends on kotlinx-coroutines (`api`, because `StateFlow` is in the public API).

## Consequences

- The M2 engine implements `RideSession` and produces `RideSnapshot`s; the UI does not change. If M2 needs
  a field the snapshot lacks, it is added there, and the simulation fills it in.
- The ride record's per-second sample (spec §8.2) can be built from a snapshot plus the step index, which
  is why the snapshot carries `workout.stepIndex` and `targetPower`.
- Fire-and-forget means a test must let the session run (`runCurrent`) after issuing a command.
- Auto-pause and the 3 s ERG ramp-in after a pause (spec §4.2, §4.5) are engine behaviour and are not in
  the simulation; they arrive with the real engine in M2.
