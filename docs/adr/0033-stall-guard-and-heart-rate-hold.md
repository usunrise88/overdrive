# ADR 0033: The ERG stall guard and the heart-rate hold step

Status: accepted (stage A1b of v0.4.0, Lific OVD-19 part 1, 2026-11-19). **Nothing here was ridden on the bike.** The guard's numbers are
the spec's (§11.1) and the controller's are first guesses found on a first-order model of the heart rate; the owner tunes them (section 9).
The overlay, the previous-attempt line and the settings screens are stage A1c.

## Context

Two additions of the owner's first review (spec §11.1, §11.2) were agreed for Phase 1 and never built. In ERG a falling cadence makes the
trainer raise the torque to hold the power, which drops the cadence further until the pedals stop: the **stall guard** breaks that loop.
Base training is ridden at a heart rate, not at a power, and the FTP is not always known or right: the **heart-rate hold** step lets a
planned workout say "40 minutes at 135 bpm" and lets a slow controller find the watts. Both touch the ride engine, the workout contract, the
ride record, the plan service and the insights; ADR 0032 (resume after a pause) gave the guard `RideSnapshot.secondsSinceResume`.

## Decision

### 1. The stall guard (`core` `StallGuard`, `RideEngine`)

A pure class, one `tick(cadence, secondsSinceResume)` per second of riding, no clock. **Takes hold** after 3 ticks in a row below the floor
(55 rpm); **lets go** after 5 ticks in a row at or above floor + 10 rpm; the target then **climbs back in whole-second steps over 5 s**; a new
stall while it climbs takes hold again at once and is a new event. All of it is `StallGuardSettings` inside `RideSettings.stallGuard`.

**How it composes (the decisions the spec left open):**

| With | Rule |
|---|---|
| Intensity setting, ramp-in after a resume, the heart-rate controller | The guard is a **factor on whatever the player would command** (0.70 while holding, rising to 1): `limit(watts)` is `round(watts x factor)` and never raises anything. So 70% of 160 W is 112 W, of 168 W at 105% is 118 W, and a step change while it holds is 70% of the new step. |
| Heart-rate step | It caps the controller's power the same way, and **the controller is frozen** while the guard is active (no decisions; the filter still follows the heart rate), so the controller does not wind the power up against the guard. |
| Free-ride steps, no workout | **No guard**: no target to limit. A guard that had hold is forgotten when the step becomes free. |
| Auto-pause at 0 rpm | **Auto-pause wins.** A cadence of exactly 0 is a stop, not a slow rider: it breaks the run of low seconds instead of adding to it, so the guard never fires on a stop; a guard that already has hold stays until the pause resets it. With auto-pause off, 0 rpm is below the floor like any cadence. |
| Pause | Any pause resets the guard (unarmed). After the resume the ramp-in goes to the full target; the guard does not remember an old stall. |
| Restart after a pause (**must not fire**) | Not judged for **5 s after a resume** (`secondsSinceResume < resumeGraceSeconds`), and, after the start or a resume, **not until the rider has pedalled at or above the floor once**, bounded at **30 s** (`armTimeoutSeconds`): getting up to speed is not a stall, but a rider who never gets up to speed against the target is still protected after 30 s. |
| Missing cadence (null) | Not low: it breaks the run of low seconds and never counts toward letting go. |

`RideSnapshot` gains (additive) `stallGuardActive` (true from taking hold until the climb back is over), `stallGuardWatts` (what the trainer is
held at) and keeps `targetPower` as what the step asks, so the overlay can show both. **The ride record keeps the workout's target, not the guard's.**

**Each activation is an event** on the ride record (section 4); the engine calls the new `RideRecorder.onEvent` (a default no-op, so every
recorder compiles unchanged) right after the sample of the tick, with the step of that second.

### 2. The step `hr` (contract, `core` `HeartRateStep`, `FlatStep.heartRate`)

`{ "type": "hr", "duration", "target_bpm" | "target_lthr", "band" (default 5), "power_min", "power_max", "label", "cadence" }`, exactly one of the
two targets, `power_min < power_max` in absolute watts (a rule the schema cannot state: every implementation checks it, with invalid fixtures).
It needs no FTP. `band` is the half-width: the heart rate is inside the band within `band` bpm of the target.

**A fraction of the threshold heart rate** (`target_lthr`) needs the rider's threshold. The profile already stores it
(`thresholdHeartRate`, else `estimatedThresholdHeartRate` from the rides, the figure the heart-rate load uses). **Rule: the service resolves the fraction to whole bpm
when it serves the workout to the tablet** (`GET /v1/workouts`, `server/src/plan/heart-rate.ts`); the stored plan and what the agent reads keep the fraction;
`create_workout` and `update_workout` **refuse early**, in plain words, a workout with a fraction while no threshold is known (nothing is written to the calendar).
A fraction that is still unresolved when it reaches the tablet (the rider cleared the threshold later) flattens to a **free-ride step**: the ride still plays.

The structure hash gets a token, `h<seconds>@<bpm>` (or `h<seconds>@l<permille of the threshold>`); the band, limits, label and cadence are not in it, and **no
existing token or golden number changed** (a test pins three). An `hr` step has no heart-rate-at-power figure and no planned load, like a ramp without a fixed power.

### 3. The controller (`core` `HeartRateHold`)

Pure, one `tick(bpm)` per second of riding. **Start:** a fixed ramp from `power_min` for 180 s to 30% of the way up `[power_min, power_max]`, the heart rate not judged;
then the controller takes over from the power reached. **Every 10 s:** smooth the heart rate (first-order filter, time constant 15 s), take the slope of the smoothed
value over 30 s, predict where it is heading (`smoothed + 30 s x slope`: the lead that makes a lagging signal controllable), and move the power by `0.5 W/bpm x (target - predicted)`,
**at most 5 W up and 10 W down**, nothing inside 1 bpm, never outside the limits; no decision until the 30 s window is full. Why this shape: heart rate follows effort with a lag of
tens of seconds, so a plain proportional controller on the raw value oscillates; an integral controller with a lead term on the trend is critically damped for a first-order rider
over a wide range of gains, and the step limits bound the damage of a wrong guess. **A pause** stops its clock; after a resume the filter starts over, the power stays and nothing is
decided for 30 s. **Strap loss:** a second without a heart rate decides nothing; at 15 s `signalLost` (last power held), at 60 s `strapLost` and the power drops to `power_min`; when the strap is
back the controller continues from there within its limits. **Gear buttons** (they reach `intensityUp/Down` through the Click router and the on-screen buttons alike) move the target by 2 bpm
within 20 bpm either way; the adjustment lasts for the ride and carries into the next heart-rate step. The intensity setting does not apply to the step. All numbers are
`RideSettings.heartRateHold`, not constants.

**What the model tests show** (`HeartRateHoldTest`, a first-order rider: gains 0.2 to 0.7 bpm/W, time constants 25 to 80 s, a steady state at 20%, 50% or 90% of the range, +-2 bpm noise in
whole bpm): in every case everything after the first 13 minutes of a 50-minute step (3 of ramp, 10 more) is inside a 5 bpm band, the true heart rate moves by at most 4 bpm peak to peak and the power by at most 8 W; the power never leaves the limits, even with a
target the rider cannot reach; a start ramp that ends well above what the rider needs overshoots by at most 12 bpm (gain 0.7, 96 W needed, ramp end 122 W) and is back in the band within 400 s of the ramp's end.
A rider whose heart rate is not first-order (cardiac drift, a hot room, caffeine) is not modelled: that is what the bike is for.

### 4. The ride record (contracts first)

Additive, so every old record and fixture stays valid and the writer leaves the key out when it is empty: **`events`** (`stall-guard`, `timeOffsetSeconds`, `stepIndex`) and, on a sample,
**`targetHeartRate`** (present only once the controller is in charge, so the start ramp is not judged). The crash-safe log has an `event` line; recovery brings the events back.
The ride detail's lap kind may be `hr`.

### 5. Compliance of an `hr` step (server, `ride-stats.ts`)

**The time inside the band**: of the seconds that were not paused and have a `targetHeartRate` and a heart rate (the **judged** seconds), those within the step's band of that second's target (the target the
rider may have moved with the buttons). Reported as `secondsInBand` of `judgedSeconds` and a percent, with the target in force, the planned one, the band, the limits and the mean heart rate; nothing judged says so and
gives no percent. The step has no planned watts: it is left out of the power totals and of the "steps within 5% of target" count. A workout that has one has **no planned load** (it would be only part of the session),
so the load-off-plan topic cannot fire on it and the facts say `plannedLoad: null`.

### 6. How the step reaches intervals.icu, and comes back

**What was checked** (forum, "Workout builder", fetched 2026-11-19): heart-rate targets are `70% HR`, `100% LTHR` and `Z2 HR`; **an absolute number of beats is not documented, and neither is a power limit next to a heart-rate
target in one step.** So a native heart-rate target cannot carry `target_bpm` or the limits, and **is not used**. The step is written as **a step with its power limits as a power range**
(`- Zone by heart rate 40m 80-220w`: intervals.icu shows it and counts about 150 W), plus **a note after the steps**, one line per heart-rate step, numbered by the step lines in the order written (a repeat's body once):
`Overdrive heart-rate step 2: 135 bpm, band 4, 80-220 W, label Zone 2 by heart rate` (`88% of threshold heart rate` for a fraction; the band only when the step gave one; the label whole, because a cue loses the words
that start with a digit). The converter, when a description has such notes, **reads the description's own text and not the steps intervals.icu parsed** (which cannot hold the step), and applies each note to
the step line it numbers **if that line still agrees (its power range)**; a step edited in the calendar is read as the step it now is, with a note in the log. The round trip is exact (tested for every valid contract fixture and
through `create_workout`, with no warning). **Not verified against the real service:** the fake of ADR 0016 is written from the same notes as the client; that intervals.icu keeps a description's text and ignores a note line is
the assumption the whole text format already rests on.

### 7. Where the step types are enumerated (server)

`ride-stats.ts` (flatten, compliance), `insights/planned.ts` and `plan/summary.ts` (the plan's seconds apart from free steps), `insights/facts.ts` (`plan.heartRateSteps`, `stallGuard`, both only when present so the golden facts
of the other rides did not move), `plan/workout-summary.ts`, `strava/description.ts` (words), `intervals/workout-text.ts` and `workout-convert.ts`, `mcp/tools/plan.ts` (the tool description says, for a model, to use `hr` for base
training, for a day of uncertain condition and when the FTP is unknown; hard intervals stay power steps), `mcp/tools/rides.ts`. The repeat detector skips the step (no fixed power) with no code change. The ride prompt's version is 2: it
tells the model what the new facts are.

### 8. The topic `stall-guard`

The hook of ADR 0025 is a row: **at least 3 activations in one ride** (`TopicSettings.stallGuardActivations`), after the four rows that were there; text and opening question are written by code from the count. The limit of
two topics in any 7 days is untouched.

### 9. What the owner tunes on the bike, and what to look for

All are `RideSettings` fields with the defaults below. **They are engine defaults and not persisted** (like ADR 0032's): tuning means a build until stage A1c puts the few that matter in Settings.

| Setting | Default | Look for |
|---|---|---|
| `stallGuard.enabled` | on | A guard that fires while you pedal normally in a recovery step: turn it off or raise the floor's margin; a guard that never fires when the pedals really slow |
| `stallGuard.cadenceFloorRpm` | 55 | Your own comfortable low cadence; below it the guard cuts |
| `stallGuard.targetFraction` | 0.70 | Whether 70% is enough to let the cadence recover, or too much of a drop |
| `stallGuard.releaseMarginRpm`, `releaseSeconds`, `rampBackSeconds` | 10, 5, 5 | A target that comes back too early (stalls again at once: a second event) or too late |
| `stallGuard.resumeGraceSeconds`, `armTimeoutSeconds` | 5, 30 | A restart after a pause that trips the guard (raise) or a stall right after the start that is not caught (lower the timeout). **The D500 answers a new target in 4 to 9 s (bench report 1), so the guard acts late by that much.** |
| `heartRateHold.startRampSeconds`, `startRampFraction` | 180, 0.3 | The heart rate far above the target when the ramp ends (lower the fraction), or minutes spent climbing to a power you obviously need (raise it) |
| `heartRateHold.gainWattsPerBpm`, `leadSeconds` | 0.5, 30 | **Oscillation** (the power and the heart rate swing every 2 to 4 minutes): lower the gain or raise the lead. **Sluggishness** (the heart rate sits 8 bpm off for ten minutes): raise the gain |
| `heartRateHold.maxStepUpWatts`, `maxStepDownWatts` | 5, 10 | How long it takes to find the power after the ramp; how fast it backs off from a high heart rate |
| `heartRateHold.deadbandBpm`, `smoothingSeconds`, `trendWindowSeconds` | 1, 15, 30 | Power that twitches by single watts with every beat (more smoothing), or a lag that makes it late (less) |
| `heartRateHold.signalHoldSeconds`, `signalDropSeconds` | 15, 60 | What the strap does on your skin and in sweat: how often it drops, and whether 60 s at `power_min` is right |
| `heartRateHold.targetStepBpm`, `maxTargetOffsetBpm` | 2, 20 | Whether one press of a gear button is the size of change you want |

On the bike, with **Share Bluetooth logs** on: ride a 40-minute `hr` step at a heart rate you know; note the power the ramp ends at, how long until the heart rate is in the band, the size of the swing afterwards, the
heart rate at the end of a pause and resume, and what happens when you take the strap off for 20 s and for a minute. Ride a power step and stop pedalling slowly: does the guard take hold at about 3 s below 55 rpm, and
does the cadence come back?

## Consequences

- `RideSnapshot` and `RideSettings` have new fields at the end with defaults; `FlatStep` has `heartRate` at the end; `RideRecord` has `events` and `RideSample` has `targetHeartRate` at the end; `RideRecorder` has a default method. Existing code compiles unchanged.
- `Workout.flatSteps(thresholdHeartRate)` is the one place a fraction of the threshold becomes beats on the tablet; the apps do not pass it today (the service resolves it).
- **Not built (stage A1c, or not asked):** the overlay's look for the guard and the heart-rate step, the settings screens, the previous-attempt line; the profile chart draws an `hr` step as a free-ride block; `SimulatedRideSession` (the M1 demo session) plays an `hr`
  step without a controller (the real engine over `SimulatedBike` runs it); the plan's weekly planned load counts only the power part of a workout that has an `hr` step.
- A workout of `hr` steps only has no planned load and so no load-off-plan topic; its compliance is the band.
- The guard and the controller assume the trainer's cadence and the strap's heart rate are as the engine already reads them (ADR 0004, 0032); a D500 whose cadence reads 0 below some power (ADR 0032, a hypothesis) is not a case the guard handles: it would only fire with auto-pause off.
