# ADR 0032: Resuming after a pause

Status: accepted (stage A1a of v0.4.0, Lific OVD-24, 2026-11-19). **Nothing here was observed on the bike.** The cause below is a
hypothesis, built from reading the code and from bench report 1; a Bluetooth log of a pause and a resume on the D500 is what
confirms or rejects it (section 8). The change is built to be right under each reading of the hypothesis, and the tests run a
model of the trainer that has the suspected fault.

## Context

The owner rode an ERG workout on the Van Rysel D500 and reported: after a pause (the ride pauses by itself when the rider stops
pedalling, or the rider pauses it), at first the cadence is not picked up and the resistance does not come up; he pedals against
almost no resistance for 10 to 15 seconds.

What the engine did (ADR 0004, spec §4.2, §4.5):

- While paused, an ERG step is commanded `RideSettings.pausedTargetWatts`, **0 W**. The reason is sound: a trainer holding the
  step's 250 W against a stopped flywheel cannot be restarted.
- The auto-pause was lifted **only** when a 1 Hz tick saw cadence above 0. Cadence is the only sign of pedalling the engine used.
- After the lift, the target ramped in over 3 s from 0 W, one whole-second step per tick.

Three hypotheses (**none verified**):

1. **A deadlock at 0 W.** The D500 has no crank sensor; its cadence field is probably inferred from how the flywheel is being
   worked. At 0 W of resistance the inferred cadence can read 0 while the rider already pedals. The engine, seeing cadence 0,
   keeps the trainer at 0 W. The pause ends only when the rider spins the flywheel hard enough for the estimate to appear: the
   10 to 15 seconds.
2. **A chain of delays.** Even when cadence is seen, the engine looks once a second, the target then ramps from 0 over 3 s, and the
   D500's ERG response to a new target takes about 4 to 9 s (bench report 1, section 2). Together these are the same complaint.
3. **Other signs exist.** A `TrainerReading` also carries power and the trainer's own flywheel speed (spec §4.3 "received but
   ignored"). Both are other evidence that the rider turns the pedals.

What the bench does tell: the D500 notifies about four times a second (median 239 ms), accepts every Control Point command in about
60 ms, reports power 0 for a target of 0, and its Indoor Bike Data carries speed (0.01 km/h), cadence and power in 8 announced
bytes.

## Options

| Option | Fixes | Why not (or why) |
|---|---|---|
| A. Lift the pause on every notification, cadence only | hypothesis 2 | Does nothing for hypothesis 1: cadence still reads 0 at 0 W. |
| B. A small hold target during an auto-pause | hypothesis 1, if the trainer registers a stroke at that resistance | **Chosen**, with C. 25 W is a guess; it is a setting. |
| C. Other signs of pedalling (power, flywheel speed) | hypothesis 1 whichever of power and speed the trainer shows | **Chosen.** The ride otherwise ignores both. |
| D. Command simulation on the flat during an auto-pause instead of an ERG target | hypothesis 1, if cadence is right in simulation | Not chosen: two Control Point writes and a mode change on each pause and resume, and no evidence that cadence reads better in simulation. **The fallback** if the log shows cadence 0 at 25 W and no power or speed either. |
| E. Tick faster than 1 Hz | hypothesis 2 | Changes the record clock (one sample a second, spec §4.2, ADR 0004) for what a notification handler does better. |
| F. Hold the workout's target through a pause | nothing | The reason for 0 W: a trainer holding 250 W cannot be restarted. |

## Decision

### 1. The pause ends on the notification, not on the tick

`RideEngine` handles each trainer notification (the `TrainerData` input) when it arrives. While the ride is auto-paused and the
notification shows pedalling (section 2), it lifts the auto-pause **in that same input**: the state is `RIDING`, the ramp-in
begins, the order to the trainer is updated and the sender woken (as for every input, `updateOrder()` runs at its end). The first
target therefore leaves within the notification, not up to a second later. The queue, the single thread of control and virtual
time are as before; there is no wall clock.

What the 1 Hz tick does **does not change**: it counts the three seconds of zero cadence that start an auto-pause (a missing
cadence is not zero), writes one sample, advances the workout. A second is paused or riding by the state at the moment of its tick
(ADR 0004). The tick no longer lifts the pause itself (a notification has already done it). One visible consequence: the second
in which pedalling is first seen is now a **riding** second; it used to be the last paused one. A pause can therefore cost up to a
second less than before. The paused seconds themselves are recorded exactly as before (paused, the plan's target and step, no
movement).

A manual pause is lifted only by Resume, as before, whatever the readings say. A trainer that is gone is `TRAINER_DISCONNECTED`
first (readings do not arrive anyway); when it is back an auto-pause that was in force is still there, holds its target again and
is ended by the first stroke.

### 2. "Pedalling" is more than cadence above 0

A notification shows pedalling if **any** of these holds (`RideEngine.showsPedalling`):

1. **Cadence above 0.** As before. Zero cadence still counts toward starting the auto-pause; a missing cadence is neither.
2. **Power of at least `autoResumePowerWatts`** (default **10 W**; 0 turns it off), **unless the flywheel is winding down.** With the
   hold of section 3 this is the main path: a rider pushing against 25 W makes the trainer report about 25 W. But an ERG trainer
   also reports its target while a flywheel turns on without the rider, so after every stop the first notifications would end the
   auto-pause again (the ride would pause, resume, and pause again every 3 s until the flywheel stops). Hence the condition.
3. **The flywheel being accelerated:** the trainer's `speedKmh` at least `autoResumeFlywheelStepKmh` (default **0.5 km/h**; 0 turns
   the rule off) above the previous reading.

**The speed rule, and why it is built this way** (`FlywheelWatch`, internal to the engine):

- *Consecutive readings, no clock.* The step is between two successive notifications, so the engine needs no timestamps and the
  rule works at the D500's 4 Hz and at 1 Hz. A rider's first stroke accelerates a flywheel by several km/h per second, which is a
  step of 0.5 km/h per notification at 4 Hz and more at 1 Hz; the flicker of a flywheel sensor (0.01 km/h on the D500) is far
  below it. A flywheel that is only coasting down **never** rises, so it cannot end a pause.
- *Nothing is compared across a gap.* The first reading of a link, a reading without a speed and a value that is not a number start
  the comparison over; so does every reconnect. A rise from "nothing" is not a rise.
- *Winding down is judged against where the speed last settled, not against the reading before.* A heavy flywheel under a small
  hold loses a few tenths of a km/h per notification, and a sensor may repeat its value between its own updates. The engine keeps
  the speed at which the flywheel last moved by more than 0.05 km/h; a move down from there is "winding down" and a move up
  is not. A rider who pedals steadily makes the speed wobble both ways, so the flag follows the wobble and is false about half
  the time, which is enough: one notification showing power and no coast is a resume.
- *One setting turns both off.* `autoResumeFlywheelStepKmh = 0` disables the acceleration rule and the winding-down watch (the
  power rule is then unguarded; turn it off too with `autoResumePowerWatts = 0` if power keeps ending pauses).
- *Known limit.* Without a speed field the engine cannot tell a coasting flywheel that still reports its ERG target from a
  rider. The D500 sends speed. For a trainer that does not, set `autoResumePowerWatts` to 0.

### 3. A small hold target during an auto-pause

`RideSettings.autoPauseTargetWatts`, default **25 W**, range 0 to `maxTargetWatts`. In an ERG step an **auto**-pause holds
`min(autoPauseTargetWatts, the step's target)` (never more than the step asks for); **0 gives the old behaviour** (an auto-pause
holds `pausedTargetWatts` like any pause). A **manual** pause, the state before the start and a pause by the trainer's link keep
`pausedTargetWatts` (0 W): a rider who pressed Pause is not pedalling, and 0 W is what lets him spin up freely. Free-ride steps
are in simulation mode and have no target, so nothing changes there. The hold is an ordinary Set Target Power: no FTMS wire
behaviour changed.

Why 25 W: enough that a trainer which infers cadence from the work done on the flywheel has something to infer it from, and little
enough not to be the "250 W against a stopped flywheel" that cannot be restarted. It is a guess for the D500 and the first thing
to tune.

### 4. The ramp-in starts from the hold

`WorkoutPlayer.beginRampIn(fromWatts)`: the ramp starts at the target the trainer was holding (never above the target it heads
for) and moves one of `rampInSeconds` equal steps (default 3) with each second the player counts. `PlayerTarget.Erg` keeps its
meaning: `watts` is what to command, `nominalWatts` is the target without the ramp-in (what the record and the overlay show).
Example, step at 160 W, hold 25 W: 25, then 70, 115, 160.

**After a resume by pedalling the first step is taken at once** (`WorkoutPlayer.stepRampIn`, called by the engine in the input that
lifted the pause): the notification that shows the rider is answered with 70 W, the second step comes with the next tick, the full
target with the one after; full target about two seconds after the first stroke instead of three seconds plus a tick of detection.
After **Resume** (a manual pause) and after a **trainer reconnect** the ramp keeps its old timing (the first rising step one
second after the resume): nobody is known to be pedalling at those instants, and the committed BLE session fixtures of
`contracts/fixtures/ble/` stay valid. Steps are on the 1 Hz tick, so after a stroke the second step comes between 0 and 1 s after
the first, depending on the phase of the tick; with the D500's 4 to 9 s response to a new target this does not matter.

### 5. A guard for the stall check

`RideSnapshot.secondsSinceResume: Int?` (additive, default null): whole ticks since the last resume of any kind, 0 from the instant
of the resume, null while paused, before the first resume (the start of a ride is not a resume) and after the end. Cheap and
tested; stage A1b's stall guard reads "do not judge cadence while `secondsSinceResume` is below N". A resume part-way through a
second makes the first tick come early, so a guard of N covers between N - 1 and N real seconds.

### 6. Settings, and where they live

All three new values are `RideSettings` fields with defaults: `autoPauseTargetWatts` (25), `autoResumePowerWatts` (10),
`autoResumeFlywheelStepKmh` (0.5). **(Stage A1c, ADR 0034, put them in Settings, Developer, and the stall guard's on/off and floor in Ride; what follows is how it was before.)** **They are engine defaults, not in the Settings screen:** `rampInSeconds` and
`pausedTargetWatts` are not persisted either (`AppSettings.toRideSettings()` builds `RideSettings` from the persisted FTP, physics
and gears only), so there is no Developer section for engine settings to join. Tuning them means a build, until the owner wants
them there; that is a small follow-up (one Developer row each).

### 7. How it is tested

`FlywheelTrainerModel` (test) is a trainer with a flywheel and the suspected fault: its cadence reads 0 while the commanded ERG
target is below 40 W although the rider pedals, it reports power and flywheel speed, and a coasting flywheel keeps reporting its
target as power. The first test was written against the unfixed engine and failed: a rider pedalling for a full minute stayed
paused at 0 W. `ResumeAfterPauseTest` covers manual pause and resume, auto-pause ended by cadence only, power only and speed only,
a coasting flywheel that must not resume (slow coast, a sensor repeating its value, no power field), a trainer that drops during a
pause, free-ride steps and a ride without a workout, the ramp-in numbers and their times, the records of the paused seconds,
`autoPause = false` and `secondsSinceResume`; `ResumeSignalsTest` feeds notifications by hand, one at a time, and checks each rule at
its boundary; `FlywheelWatchTest`, `WorkoutPlayerTest`, `RideSettingsTest` and a real-adapter ride in `BleRideTest` complete it.
With the three settings at 0 the old behaviour, deadlock included, comes back (a test says so).

## Consequences

- The pause ends on the notification that shows pedalling; the second of the first stroke is riding time. Three existing tests
  encoded the old timing (`RideEngineStateTest`, `RideEngineSimulatedBikeTest`, `RideEngineTrainerTest`) and were changed.
- **A trainer whose cadence reads 0 below some target** (suspected for the D500 at about 0 W; the threshold is not known) and a
  workout with a recovery step below it: the engine counts zero cadence, auto-pauses after 3 s, and power at the hold ends the
  pause at once, so the ride can pause and resume in a loop while the rider pedals steadily. Cadence is still the only trigger
  of an auto-pause (the owner's brief and spec §4.2). If the log shows this, the trigger should also look at power and the
  flywheel, or `autoPause` can be turned off for such a workout.
- A trainer that reports no speed and keeps showing its target as power while the flywheel coasts makes the ride pause and resume
  repeatedly for the seconds the flywheel needs to stop (section 2, known limit); `autoResumePowerWatts = 0` is the way out.
- 25 W against a stopped flywheel is one thing nobody has measured on the D500. It is far below what makes a restart impossible,
  but the bench decides.
- The tick still stands at one second: a sample is one second of the ride, the workout position moves one second at a time.

## What the owner should check on the bike

Use a workout with an ERG step well above 100 W, with **Share Bluetooth logs** (Settings, Developer) on, and keep the logs.

1. **Auto-pause and resume.** Stop pedalling until the overlay says the ride is paused (auto), wait 5 s, then start again. Expect the
   resistance to come up at the first strokes, within a second or two, and the overlay to say riding at once. Say how long it was
   until the resistance was felt. In the log, the Control Point write of the pause is `05 19 00` (25 W); after the first stroke
   comes `05 46 00` (70 W at 160 W; the numbers follow the step), then two more.
2. **What the trainer showed.** In the log's Indoor Bike Data lines after the flag bytes `44 00`: speed (2 bytes, 0.01 km/h), cadence
   (2 bytes, 0.5 rpm), power (2 bytes, W). In the first seconds of pedalling after the pause: did the **cadence** word stay 0 while
   you pedalled (then hypothesis 1 holds), did **power** reach 10 W or more, did the **speed** word rise? Which of the three ended
   the pause? If all three stayed flat the deadlock is not solved: send the log, and option D (simulation during an auto-pause) is
   next.
3. **A stop without pedalling again.** After you stop, does the ride stay paused? Flapping between paused and riding for a few
   seconds right after the stop is the coasting limit (section 2): look at the speed word (does it fall?) and the power word
   (does it keep showing 25 W?).
4. **Manual pause and Resume.** Pause, wait, press Resume. The target should start at 0 W, 53 W, 107 W, 160 W one second apart, as
   before. Resuming should feel the same; if pedalling is hard to start against that ramp, say so.
5. **The 4 to 9 s the D500 takes** to follow a new target is the trainer's own. If the engine sends the first target within one
   notification of the first stroke and the resistance still takes seconds, that is where the rest of the delay is.
6. **A low step.** If a step is below about 50 W and the ride keeps pausing while you pedal steadily, that is the loop of the
   consequences above; say at which target.

Settings to tune (all in `RideSettings`, engine defaults):

| Setting | Default | Raise or set to 0 when |
|---|---|---|
| `autoPauseTargetWatts` | 25 | **Raise** (40, 50) if the cadence word still reads 0 at the hold while you pedal and power stays under 10 W; **0** to go back to 0 W |
| `autoResumePowerWatts` | 10 | **Raise** if the trainer's power word is noisy at rest; **0** if power keeps ending pauses while the flywheel coasts |
| `autoResumeFlywheelStepKmh` | 0.5 | **Raise** if the speed word jumps by that much without anyone pedalling; **0** if the speed word is useless (it also turns the coasting guard off) |
| `rampInSeconds` | 3 | **Lower** if the climb back feels slow; 0 for none |
| `autoPauseAfterSeconds` | 3 | **Raise** if the ride auto-pauses on a coffee sip |
