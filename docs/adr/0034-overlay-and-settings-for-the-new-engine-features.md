# ADR 0034: The overlay and the settings for the new engine features

Status: accepted (stage A1c of v0.4.0, Lific OVD-19 part 2, 2026-11-19). **Nothing here was seen on the tablet or ridden on the bike.** The
pictures are from the JVM screenshot tests (`build/shots/`, never committed); what only the tablet can settle is in section 9.

## Context

Stages A1a and A1b (ADR 0032, 0033) gave the engine a resume-after-pause path, an ERG stall guard and a heart-rate step, with settings, snapshot
fields and events that had no screen. Spec §11.3 (the overlay expands around step changes), §11.7 (the live line against the previous attempt) and
§12.5 (body weight in the physics) were not built either. This stage is the tablet's half of all of it, and a small server half for two of them.

## Decision

### 1. The overlay expands around step changes (spec §11.3)

`core` `StepChangeWindow` is **pure functions of the snapshot's step progress and the rider's chosen mode**, tested as a table. The window is open
when the ride is live in a workout, the setting is on, and the current step is within **10 s of its end with a step after it** or within **10 s of
its start and not the first**. So the start of the workout is not a change; the end of the workout is not one (there is no next step, the ride
finishes and the overlay closes); a skip with Next or Previous is one (the new step has just begun), except a skip back to the first step; a step
of 20 s or less is expanded from its first to its last second with no gap; a pause freezes the progress and so the window; a free-ride step
changes like any other.

**The rider's choice wins.** The overlay is *shown* detailed when the rider chose detailed or the window is open and not dismissed. A tap flips what is
**shown**: tapping an overlay that expanded by itself collapses it, **leaves the chosen mode as it was** and keeps that window from expanding it
again, so the automatic return afterwards does nothing; tapping a compact overlay chooses detailed for good (`ModeChoice`, `StepChangeWindow.tap`).
A dismissal lasts until the window closes; the next step change expands again, and the two touching windows of a short step count as one. The
service keeps only that flag (`OverlayModeState`), and the persisted `overlayMode` stays the rider's choice. No sound, no new animation: the window
flow changes twice per step change and is read off the main thread from the snapshot flow that already exists; the mode switch is the same resize as
a tap's. The detailed mode already has the countdown ("2:43 left") and the next step, which is what the spec asks the expansion to show.

### 2. What the overlay shows of the new states (design/system.md §5.6)

- **Cadence guard.** The fifth cell's label becomes **"Cadence guard"** in primary text (a word, not a colour) and its value is what the trainer is held at
  (`stallGuardWatts`). It takes the cell from "Target 100%" and also in a heart-rate step.
- **A heart-rate step.** The fifth cell is "Target" with no intensity beside it (the percentage would do nothing) and the controller's power. **One line
  under the main row, in both modes**, says where the heart rate is against the band **by position and words**: a 72 x 8 dp gauge (the band in its middle
  half, the heart rate a marker, clamped at the ends) and "Target 135 bpm ±5 · In band" / "Above band" / "Below band"; "Easing in" during the start ramp
  (not judged); "No heart rate" before the strap counts as lost; **"Strap lost · holding 142 W"** after 15 s and **"Strap lost · power at the minimum,
  80 W"** after 60 s. The ±2 bpm of the gear buttons is the target changing at once, with what it differs from the step's own in brackets ("137 bpm (+2)");
  a change of the target is a rider action and bypasses the once-a-second limit, as the guard taking hold does (it changes the fifth cell). The line is
  one line in every state, so no state changes the overlay's height (compact 84 to 108 dp, detailed 282 to 306 dp).
- **The ride screen** says the same where it would otherwise be wrong: "Cadence guard" and the held watts in place of "Target", "Heart rate target" in
  place of "Intensity" (and the two buttons say so), and a banner for the strap.
- **The profile chart** draws a heart-rate step as a **floating band** (between 40% and 65% of the 150% scale, in the border-strong grey) and not as the
  free ride's block from the baseline (`ProfileStep.heartRate`), in the workout profile and in the plan under a finished ride; the companion's two builders pass
  the flag. The ready screen's lists already said it in words.

### 3. Settings and persistence

`AppSettings` gains `overlayExpandsAtSteps` (**on**) and `tuning: RideTuning`: 27 numbers, each a `TuningField` (id, plain label, a note, unit, default,
rule) that the Settings screen, the repository and the tests all walk, so a number added is shown, kept (`tune_<id>`) and tested without more code.
They are the numbers ADR 0032 and 0033 list for the bike. `toRideSettings()` builds `RideSettings` from a **sanitized** copy, so an invalid value can never
reach the engine whose `require`s would throw (a test walks every field at both ends of its rule). The rules are narrower than the engine's; the one
rule between two fields, **the power drops after the strap signal is held**, makes the two steppers refuse each other and the row says why ("Not above
30 s (the other strap time)"). A value in the file outside its rule is read on it. A change reaches the next ride (a ready ride is rebuilt after the usual
debounce; one under way keeps what it began with).

| Where | Number | Default |
|---|---|---|
| **Ride** | Cadence guard (switch) | on |
| | Cadence floor | 55 rpm |
| Overlay | Expand at step changes (switch) | on |
| **Developer · Resuming after a pause** (ADR 0032) | Resistance in an auto-pause `autoPauseTargetWatts` | 25 W |
| | Resume at power `autoResumePowerWatts` | 10 W |
| | Resume at flywheel speed-up `autoResumeFlywheelStepKmh` | 0.5 km/h |
| | Ramp back in `rampInSeconds` | 3 s |
| | Target while paused `pausedTargetWatts` | 0 W |
| **Developer · Cadence guard** (ADR 0033) | Seconds below the floor / target while it holds / let go above the floor by / seconds above to let go / climb back over / not judged after a resume / longest wait to get up to speed | 3 s / 70 % / 10 rpm / 5 s / 5 s / 5 s / 30 s |
| **Developer · Heart-rate hold** (ADR 0033) | Start ramp / ends at / gain / look-ahead / largest rise / largest fall / ignore errors under / smoothing / trend window / strap lost: hold power after / drop to minimum after / gear button changes the target by / target can move at most | 180 s / 30 % / 0.5 W/bpm / 30 s / 5 W / 10 W / 1 bpm / 15 s / 30 s / 15 s / 60 s / 2 bpm / 20 bpm |

Each group has a **Reset** that puts it back to the engine's numbers (off at the defaults). The overlay switch sits in the Overlay card, where a rider looks for
what the overlay does, not in the Ride group.

### 4. The previous-attempt line (spec §11.7)

**The service hands the tablet the hash; the tablet never computes one.** `GET /v1/workouts` gives each planned workout an additive `structureHash` (the
repeats' own `workoutHash` of the workout as served, so a heart-rate step is already in bpm as in the ride's snapshot); a workout opened from a file has none and no line.
**Once, when the ride starts**, the host asks `GET /v1/repeats/{hash}/previous` through the same `ServiceClient` as everything else, on the ride's own
coroutine (the transport works on `Dispatchers.IO`): the ride never waits. No `before` is sent (the ride is not on the service yet, so the latest attempt is the
one before it). Everything that is not a clean answer with an attempt in it (no address, no network, 404, 401, 5xx, a bad body, another session's hash, no
earlier attempt) is **no line** and nothing else: no error, no retry; the answer is kept in memory for the ride.

`core` `PreviousAttemptComparison` (pure, no clock) counts the seconds of the current **steady ERG** step by the repeat rules of ADR 0026: not paused, power
within 3% of the step's target (its fraction of the FTP now), a heart rate, **after the first 60 s of each stretch** (a pause ends a stretch). It speaks only when the previous
attempt has a heart rate for that step **at the same power** (its target within 3% of this one's) and **at least 120 counted seconds, or half of the counted length the
previous attempt had, whichever is more**: the second is not the service's rule; a running mean over the first minutes of a long step sits below the whole step's mean by cardiac
drift, and "3 below" for that reason would be wrong in the rider's favour. At the end of the step the figure is the service's own. Words: "Heart rate 3 below last
time" / "same as last time" (within 1 bpm) / "Heart rate 4 above last time"; never "fitter" (one day's heart rate also moves with heat, sleep and caffeine). It is in the
**detailed** overlay's interval section, one line, only in an ERG steady step and only when there is data; the compact overlay does not carry it (a figure nobody acts on in the
ride is not on it). **The hook for routes** is the interface `LiveComparison`: a ride on a route (spec §11.4, next stage) is compared by time gap at the same distance by another implementation; the host and the overlay only know `line()`.

### 5. Body weight in the physics (spec §12.5)

The service's profile gains `riderWeight` (**read-only, derived**: the mean of the daily weights of the last seven local days from the Health Connect records, to one decimal, with the
local date of the newest weighing and how many of the seven days had one; absent when none has) and `bikeWeightKg` (**writable**, also through `update_profile`, above 0 and
at most 100; absent or null when not set). Both keys are written only when they have a value, so every older profile and fixture stays valid.

**What happens to the mass (ADR 0021 unchanged where it was):** the profile still beats the tablet's own mass. Inside the profile, **a derived mass applies when the profile has both
a rider weight and a bike weight: the mass is their sum, and `physics.systemMassKg` stands aside until the bike weight is cleared**; with either missing (no weighing this week, no bike weight) it is `physics.systemMassKg` as before. The
brief's wording was "a derived mass applies only when the profile's physics does not name a mass explicitly"; the profile cannot tell: its `systemMassKg` is always a number (80 until set,
and an owner who typed 80 is indistinguishable). **Setting the bike weight is therefore the explicit act** that chooses the weights, and clearing it is the way back; nothing changes for an
owner who never sets one. The derived mass is computed on every read and never stored; the open owner question of ADR 0021 (whether the service's physics should override the tablet's) is not touched.
The Settings screen shows where the mass comes from in one line ("Rider 78.4 kg (7-day average) + bike 9 kg", with "last weighed N days ago" after 3 days), shows the mass as a value and not a stepper while it is derived, and has a
bike weight row (stepper, 3 to 30 kg, "Not set" until the first press sets 9 kg; below 3 kg clears it) that is drawn only with a profile in force. The game never reacts to weight and nothing shows it on the overlay.

## Consequences

- `RideUi`, `OverlayUiState`, `OverlayInterval`, `OverlayControl`, `ProfileStep` and `Profile` gain fields or variants with defaults; `OverlayControl.Target.intensityPercent` is now nullable. `RideChoice.Planned` carries the hash and `PlannedSection.onSelect` gives the `PlannedWorkout`.
- The contracts changed first (`contracts/`), both ways: `structureHash` (optional on a planned workout), `bikeWeightKg` and `riderWeight` (optional on the profile and the patch schema), with valid and invalid fixtures; `core` and the server read them.
- The server's profile read does one more small query (the weights); the profile is read rarely.
- The overlay adds one more line to the heart-rate states and to a detailed ERG step; the geometry tests pin the heights.

## What only the tablet and the bike can settle

1. That the expansion reads well and does not flicker over a real video; that the resize at the step change is as quiet as a tap's; that a tap in the window collapses it for good.
2. Whether "Cadence guard" fits and reads in the fifth cell at arm's length; the heart-rate line and its gauge over a bright video.
3. Whether the previous-attempt line, with its minimum, says something true in the first minutes of a long step (cardiac drift), and whether detailed-only is the right place.
4. That a rider who sets the bike weight sees the mass change the next ride, and that "From your weight" and the one line are enough.
5. That the tuning rows are easy to change at the bike and that Reset does what it says. Bench: `docs/bench-checklist.md` 6.18, 6.19 and 7.12.
