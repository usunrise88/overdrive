<!--
  Snapshot of the Overdrive product & technical spec (Claude Docs, rev 15, 2026-11-18).
  This is the working copy for people and agents inside the repo. The live document may be
  ahead of it: when the spec changes materially, re-sync this file in the same PR as the
  code that depends on the change. Section numbers are referenced from code and docs
  ("spec §4.7"), so keep them stable.
-->
# Overdrive — Product & Technical Spec

2026-11-18

## 1. Overview

Overdrive is a single-user indoor cycling system: an Android tablet app that controls a Van Rysel D500 and shows ride data in a small overlay on top of other apps, a thin self-hosted web service with an MCP server so an AI agent can plan workouts, and a phone companion for statistics and wellness data.

The core idea is that everything runs on the tablet in front of the bike. Video plays full screen, the overlay shows the numbers, and one Zwift Click controller on the handlebar drives the ride, the workout and the media.

**Goals**

- Ride free or follow a structured workout while watching any app full screen.
- Control gears, intervals, pause, volume and media playback from the handlebar.
- Let an AI agent create and schedule workouts through MCP.
- Record every ride as a FIT file and upload it to intervals.icu and Strava.
- Turn ride and wellness data into fitness, fatigue and recovery conclusions written in plain language.
- Look good and stay smooth: aesthetics, frame rate and battery use are requirements, not polish.

**Non-goals**

- iOS. iOS has no system overlay; the shared core keeps the door open but nothing is built for it.
- Virtual worlds, multiplayer, racing, video routes.
- Store distribution. Multiple users and accounts are not built now; they are the **last stage of the project**, after Phase 3 (ADR 0015).
- A custom training-plan database. intervals.icu is the plan backend.
- Medical conclusions.

**Phases**

1. **Android ride app MVP.** Trainer and heart-rate strap, Zwift Cog and Click v2, free ride on the flat, workouts from a local file, overlay, FIT export, sync stub.
2. **Web service and planned workouts.** intervals.icu planning, MCP server, Google Calendar, Telegram reminders, Strava and intervals.icu upload.
3. **Statistics and analysis.** Phone companion, wellness import from the watch, load and recovery models, LLM-written insights, health notifications.

## 2. Hardware and constraints

The system is built for one fixed set of devices. Each row lists what the device exposes and the constraint it puts on the design.

| Device | Role | Interface | Constraint |
|---|---|---|---|
| Van Rysel D500 (Zwift Cog and Click edition) | Smart trainer | Bluetooth FTMS, ANT+, Zwift protocol | 1500 W max, 15% max simulated grade, ±2.5% accuracy. The app is the only BLE client, so the number of BLE channels does not matter. Firmware updates through Onelap Fit. |
| Zwift Cog | Single sprocket | None | No mechanical shifting. All gearing is virtual. |
| Zwift Click v2 | Handlebar controller | BLE, Zwift proprietary encrypted protocol | Firmware 1.3+ needs a daily unlock through Zwift. BikeControl 6.0.0 reports the right controller working without unlock. **First bench (2026-11-18): the right puck connected and delivered buttons over a plain handshake with no unlock through Zwift (one puck; its firmware and the time since Zwift last touched it were not recorded).** Never update the Click firmware. |
| Coospo HR7 chest strap | Heart rate | BLE Heart Rate Service | RR intervals expected from a chest strap, not yet verified. |
| Lenovo Xiaoxin Pad Pro 2025 | Ride device | Android, China ROM | No dependency on Google Play Services. APK is sideloaded. |
| Xiaomi 14 Ultra | Companion device | Android, global ROM | Health Connect available. Needs autostart and a battery exemption for background sync. |
| OnePlus Watch 3 | Wellness source | Wear OS 5, OHealth, Health Connect | See below. |

**What the watch gives us.** OHealth writes these record types to Health Connect, confirmed on the phone on 2026-11-17: heart rate, resting heart rate, oxygen saturation, respiratory rate, sleep sessions, steps, distance, speed, elevation, floors, total calories, exercise sessions, height and weight.

OHealth holds write permission for heart rate variability and VO2 max but writes neither. OHealth has no data export. Sleep stages are unconfirmed: the Health Connect screen shows only session duration, and the record itself has to be read by code.

**Consequence.** Recovery analysis is designed to work without HRV. HRV is an optional input that any future source can supply.

## 3. System architecture

Three pieces of our own software sit between the hardware and four external services. The tablet never talks to an external service directly; everything goes through the self-hosted web service.

*(Architecture diagram omitted in this snapshot — see [architecture.md](architecture.md).)*

Devices talk only to the apps, the apps talk only to the web service, and the service is the single point that reaches intervals.icu, Google Calendar, Strava, Telegram and the LLM.

**Our components**

- **Ride app (tablet).** Connects to the trainer, strap and Click. Runs the ride engine, the workout player and the overlay. Records FIT files. Works fully offline.
- **Companion app (phone).** Reads Health Connect and uploads wellness data. Shows statistics, history and insights. Shares the design system and core code with the ride app.
- **Web service (self-hosted).** Sync API for both apps, MCP server, scheduler, Telegram bot, and the integrations. Stores ride files, wellness data and derived metrics.

**External services**

- **intervals.icu.** Source of truth for the training calendar. Also receives ride files and wellness data. Free tier, personal API key, 5000 requests a day.
- **Google Calendar.** Receives planned workouts as events. One-way.
- **Strava.** Receives ride files. Upload only, nothing is read back.
- **LLM endpoint.** Writes insight text from computed facts. MiniMax M3.1 or any OpenAI-compatible endpoint.

**Sources of truth**

| Data | Lives in | Copies |
|---|---|---|
| Training plan | intervals.icu calendar | Web service cache, Google Calendar, ride app cache |
| Ride recordings | FIT file on the tablet | Web service, intervals.icu, Strava |
| Wellness data | Health Connect on the phone | Web service, intervals.icu wellness |
| Derived metrics and insights | Web service database | Companion app cache |

**Main flows**

1. **Plan.** The AI agent calls the MCP server. The service writes the workout to the intervals.icu calendar and mirrors it to Google Calendar.
2. **Ride.** The ride app pulls the next 7 days of workouts from the service, plays one, and records a FIT file.
3. **Upload.** The ride app sends the FIT file to the service. The service uploads it to intervals.icu and Strava.
4. **Wellness.** The companion reads Health Connect once a day and on open, and sends new records to the service.
5. **Insight.** The service computes load and recovery metrics, asks the LLM for the text, and sends it through Telegram and to the companion.

## 4. Phase 1: Android ride app MVP

Phase 1 delivers a complete offline ride: connect the devices, ride free or play a workout file, see and control everything from the overlay and the Click, and get a FIT file at the end.

### 4.1 Device layer

- **Trainer (FTMS).** Subscribe to Indoor Bike Data for power, cadence and speed. Use the Fitness Machine Control Point to request control, set target power (ERG), and set simulation parameters (grade, rolling and wind resistance). Read Fitness Machine Feature and Status. FTMS is an open Bluetooth SIG specification (Fitness Machine Service 1.0), not an ISO standard.
- **Heart rate.** Standard Heart Rate Measurement characteristic. Record RR intervals when the strap sends them.
- **Click v2.** Zwift's proprietary encrypted protocol, ported from BikeControl. Button down and up events are delivered to the gesture layer.
  *As built (M4-1, M4-2):* not ported. The code is written from our own research notes, `docs/protocols/click-v2.md` (public
  sources, clean room), and implements only the **plain path on the right puck**: discovery, the `RideOn` handshake and the button
  bitmap. **Nothing here encrypts or unlocks a controller**; a locked one is reported (§4.7) and the ride goes on without it.
- **Reconnect.** Each device reconnects on its own with backoff. A dropout never stops the ride; missing values are recorded as gaps.
- **Pairing.** One setup screen scans and remembers the three devices. Later launches connect without user action.
- *Settled in stage M3-1* (`app.overdrive.core.ble`, ADR 0009; written from `docs/protocols/ftms-hr.md`, **nothing observed
  on the bench yet**). The platform boundary is four interfaces in `core`: `GattTransport` (one single-use GATT session; a
  failed or dropped one, status 133 included, is closed and replaced, never reused), `GattTransportFactory`, `GattScanner`
  and `BleEnvironment` (Bluetooth off, permission missing). Device ids are opaque (`DeviceId`), so no address can reach a log
  or a recording.
  **Trainer.** `FtmsTrainerDevice` is the engine's `TrainerDevice`. On each connection it discovers the services, asks for an
  MTU of 64, reads the Feature word, enables the Control Point (indications), Indoor Bike Data and Status (notifications) one
  after another, requests control and sends Start or Resume; only then is the trainer connected, so the engine's `READY`
  means it can be commanded. The Control Point has one command in flight and a 3 s answer timeout; *operation failed* is
  retried twice, *control not permitted* takes control again once, *op code not supported* is remembered. A control loss
  reported by Status is repaired by itself after a second and the last target is sent again. **The Feature bits are a
  report, not a gate:** a trainer that lacks power target setting or simulation is reported (`TrainerEvent.FeaturesMissing`)
  and the Response Code decides; `FtmsOptions.enforceFeatureBits` refuses the command instead (bench 3.2).
  **Heart rate.** `HeartRateStrapDevice` is the engine's `HeartRateDevice`: a strap that reports no skin contact reads as
  0 bpm without RR (no signal), RR arrives in whole milliseconds (`RrIntervals.rawToMillis`, rounded half up, in the domain
  layer) and a dropout is only a gap.
  **Reconnect.** One `LinkSupervisor` per device: 1 s, doubling, 30 s cap, ±20 % jitter, back to 1 s after a link that held
  30 s; Retry (`retryNow()`) cuts the wait; Bluetooth off or a revoked permission is a state (`LinkStatus.BluetoothUnavailable`),
  not a failure, and connects at once when it returns.
  **Recording.** A decorator writes every GATT event to a text file (`BleSessionFormat`, version 1, relative times, no
  addresses, identity values redacted) that replays through a fake peripheral on virtual time; the committed fixtures
  (`contracts/fixtures/ble/`) are generated, never real recordings.
  **Open until the bench:** which flag bit carries cadence, the units of distance, the contact bits and RR of the HR7, response
  timing, whether Start is needed; each is a named constant or option (ADR 0009 §8).
- *Settled in stage M3-2* (`android/ble`, `android/app-ride`, ADR 0010; **nothing run on a radio yet**). The four interfaces
  are implemented on the platform (`BluetoothGatt` behind a thin shim, `BluetoothLeScanner`, the adapter-state broadcast and
  the permission check). **Pairing** is on the **Devices** screen, not a separate setup screen: **Scan for devices** lists
  trainers and straps apart by advertised name with the signal as a word ("Strong · −58 dBm"), a tap pairs; one trainer and one
  strap are remembered (`DeviceStore`), a new pairing replaces the old one, **Forget** removes it, and neither is offered while
  a ride is live. (The Click row was a simulated placeholder until M4-2, where it became the real Click.) **Remembered devices are owned by the app container,
  not by a ride**: they connect when the app starts with no user action, keep their links between rides and reconnect with the
  backoff above, and a ride session only uses them. A Devices row reads Connected, Connecting, "Connection lost · retrying in
  N s", "Not found · retrying in N s", Bluetooth off, needs Nearby devices, or Not paired; **Retry now** cuts a wait. Bluetooth
  that cannot be used is **one notice with one action** ("Allow nearby devices" with **Allow**, then **Open settings** once
  refused; "Bluetooth is off" with **Turn on**), re-read on every resume. The manifest asks for `BLUETOOTH_SCAN`
  (`neverForLocation`) and `BLUETOOTH_CONNECT` only: **no location** (and, until stage P2-1b, no network; §4.9 says what the
  one network permission is for). **A ride with no trainer paired does not
  start** and says so ("No trainer is paired." with **Pair a trainer**); the simulation is a Developer choice (Settings,
  Developer, **Devices**: Bluetooth, the default, or Simulated), never a fallback. The overlay's foreground service is
  `connectedDevice|specialUse`, passing `connectedDevice` only while `BLUETOOTH_CONNECT` is granted (Android 14 refuses a
  type whose permission is missing). **Every connection is recorded** to `filesDir/ble-logs/` (a file per session named by date
  and role, redacted, 2 MB a file, 60 files, 20 MB; Developer setting, on in the early builds) and **Share Bluetooth logs**
  in Settings hands them to another app on the owner's explicit action only; they hold heart-rate values and are never
  uploaded. **Open until the bench:** every platform assumption of ADR 0010 section 10.

### 4.2 Ride engine

- A 1 Hz loop reads the latest sensor values, advances the ride state and writes one record.
- States: idle, ready, riding, paused, finished.
- **Auto-pause.** The ride pauses after 3 s of zero cadence and resumes when pedalling starts. Manual pause overrides it.
- **Trainer link.** A trainer that disconnects pauses the ride at once and its reconnect resumes it, with nothing lost:
  the seconds in between are still recorded, as paused samples with no power or cadence. The snapshot says why a ride
  is paused (trainer, manual or auto). A heart-rate dropout never pauses or stops anything; it is a gap.
- **Crash safety.** Records are appended to disk every second. An interrupted ride is recovered on the next launch.
  *Settled in stage M2b-1* (`RideLogRecorder`, `RideLog.recover`, ADR 0007): one checksummed line per record, in a
  file of its own; a log cut off or damaged anywhere recovers up to its last intact record, and says whether the
  ride had finished.
  *Settled in stage M2b-2* (`RideStore`, `RideLibrary`, ADR 0008): the log is written on a thread of its own behind a
  queue, so the engine never waits for the disk, in `filesDir/rides/` under the ride's id. On the next launch a ride
  that finished but was never exported is completed silently; an interrupted ride of at least 60 s is offered on the
  Ready screen (**Save** or **Discard**, after a confirmation) and one shorter is deleted. If the log cannot be written
  the riding screen says so once ("This ride is not being saved") and the ride goes on.

### 4.3 Free ride on the flat

- The trainer runs in simulation mode at 0% grade.
- Virtual speed and distance come from a physics model: power equals rolling resistance plus aerodynamic drag at the given speed. System mass, CdA and Crr are settings.
- The trainer's own speed value is ignored. It reflects flywheel speed, which a single cog ties to cadence.

### 4.4 Virtual gears

- Gears are applied through FTMS simulation parameters: each gear adds a fixed grade offset on top of the route grade.
- Defaults: 24 gears, 1% per gear, neutral gear equals the flat. Gear count and step are settings, to be tuned by feel on the D500.
- The total offset stays inside the trainer's 15% simulation limit.
- Known difference from a real drivetrain: flywheel inertia does not change with the gear. Zwift's own virtual shifting on a Cog has the same property.
- The Zwift-protocol shifting built into the trainer is not used in Phase 1.

### 4.5 Workout player

- Loads a workout from a local file: the native JSON format (section 8) or a Zwift ZWO file.
- Step types: steady, ramp, repeated interval set, free ride.
- Targets are a percentage of FTP. FTP is a manual setting in Phase 1.
- Structured steps run in ERG mode. Free-ride steps run in simulation mode with gears.
- Controls: pause, next interval, previous interval, intensity up and down in 5% steps between 50% and 150%.
- After a pause, target power ramps back in over 3 s. While paused the trainer holds 0 W in ERG steps, so the ramp
  starts from there.

### 4.6 Overlay

- A small window drawn over other apps by the ride's foreground service, using Android's display-over-other-apps permission.
- **Compact mode:** power, heart rate, cadence, elapsed time, gear or target.
- **Detailed mode:** adds speed, distance, interval name, time left in the interval, next step and a miniature workout profile.
- Draggable, snaps to screen edges, remembers its position and mode. Opacity is a setting.
- Tap toggles the mode. Long press opens the full app.
- The window never takes input focus, so the video app keeps working underneath.
- Redraws only when a value changes, at most once a second. No continuous animation.
- Readable over video: solid or blurred backing, tabular numerals, power-zone colour as the only accent.
- **As built (stage M1-B, `docs/adr/0006-overlay-service.md`).** The service starts from the ride screen once a ride is
  live and the window is shown while a ride is live and Overdrive itself is not on screen; it stops after the summary
  has been seen or dismissed. A drag starts after 8 dp, follows the finger 1:1 by moving the window (the content never
  recomposes) and on release snaps to the nearer side edge with a 16 dp margin (a 180 ms slide of x only); the position
  is saved when the drag ends. A tap toggles the mode, a long press (500 ms) brings the app to the front. What the rider
  just did (gear, target, intensity, pause) shows at once, everything else at most once a second. The first time, a row
  on the Ready screen asks for "Display over other apps" (and notifications on Android 13 and later); without the
  permission the ride is unchanged and the top bar's overlay button previews the overlay inside the app.

### 4.7 Handlebar controls

One controller, the right Click v2, drives everything with two gestures: press and hold. No double-clicks, because they delay the single press.

| Button | Press | Hold |
|---|---|---|
| Plus | Gear up | Next interval |
| Button 2 | Gear down | Previous interval |
| Button 3 | Play or pause media | Pause or resume the ride |
| Button 4 | Volume up | Volume up, repeating |
| Button 5 | Volume down | Volume down, repeating |

- In ERG steps the two gear buttons change workout intensity by 5% instead.
- Hold threshold 500 ms. Volume repeats every 150 ms.
- Physical buttons are assigned once their identifiers are observed on the device. The mapping is a setting.
- Volume uses the system media volume. Play and pause is sent as a media key and reaches whichever app is playing.
- Every action also has an on-screen control in the app, so a ride never depends on the Click.
- **Unlock handling.** On connect the app detects whether the controller is delivering events. If not, it tells the user to unlock it through Zwift once and continues without the Click. *First bench (2026-11-18): a healthy Click delivers nothing until a button is pressed, so "delivering" means the handshake was answered; the sign of a lock the app acts on by default is the hidden Zwift service, and "answered and then silent" is an opt-in rule.*

**Settled in stage M4-1** (`app.overdrive.core.click`, `app.overdrive.core.control`, ADR 0011, `docs/protocols/click-v2.md` §10;
**first run on a Click: the bench ride of 2026-11-18, below**):

- *When each action fires.* There are no double clicks, so for Plus, Button 2 and Button 3 the **press fires on release** if
  the button was down less than 500 ms, and the **hold fires once at 500 ms** while the button is still down, with nothing on
  the release after it. For Button 4 and Button 5 the **first volume step fires on press** and the repeats start after the
  threshold: a step at 500 ms and then every 150 ms (a two-second hold is 12 steps). The thresholds are the named constants
  `GestureTiming.HOLD_THRESHOLD_MILLIS` and `VOLUME_REPEAT_MILLIS`. A hold fires only if no other button is down at that moment
  (a palm over the puck pauses nothing); a lost release (the link dropped) forgets the press and fires nothing.
- *The ten actions* are `ClickAction` (`GEAR_UP`, `GEAR_DOWN`, `NEXT_INTERVAL`, `PREVIOUS_INTERVAL`, `PLAY_PAUSE_MEDIA`,
  `TOGGLE_RIDE_PAUSE`, `VOLUME_UP`, `VOLUME_UP_REPEAT`, `VOLUME_DOWN`, `VOLUME_DOWN_REPEAT`); `ActionRouter` carries them out: in
  an ERG step the gear actions change intensity by 5 points, and the pause toggle works only while riding or paused.
- *The mapping* is a setting, `ButtonMapping`, role to the Click's bits. The default is a guess (Plus gear up, B gear down, Y play
  and pause, A volume up, Z volume down; ADR 0011 §5 says why) until the buttons are learned on the device: `ButtonLearner`
  asks for one press per role in order, and refuses a duplicate or a combo.
- *Unlock handling* is `ClickDevice.state`: **connected, the handshake answered** (`Active`), **needs unlocking through Zwift**
  (`NeedsUnlock`: the Zwift service is hidden, or, **opt-in and off by default since the first bench**, the handshake was answered
  and then no frame at all came within 30 s; each signal is an option), not found, Bluetooth unavailable, set-up failed. The
  default reply check accepts the bare `RideOn` a real right puck answers as well as `RideOn 02 03`. A release lost in a dropped link is made by the adapter,
  so no button is left held. The ride never depends on any of it.

**Settled in stage M4-2** (`android/ble`, `android/app-ride`, ADR 0012; **first run on a Click and on the tablet's Bluetooth stack: the
bench ride of 2026-11-18, informal, below; no acceptance criterion is confirmed by it**):

- *The Click is a long-lived device of the app, like the trainer and the strap.* It is paired from the **Devices** scan
  (listed under "Handlebar controllers" as "Zwift Click (right)": told by its manufacturer data and its service, never by its
  name, and only the right puck unless a developer setting says either), remembered (`DeviceStore`), connected when the app
  starts, and owned by the container, so **no ride session ever closes it** and it keeps its link between rides. It is never a
  ride input: pairing or forgetting it does not rebuild a ride. A different Click replaces the old one cleanly, and a button
  held on the old one is released first.
- *One routing rule.* The Click's gesture layer (`HandlebarControls`) calls `ActionRouter`, and the ride screen's buttons
  (`RideActions`) delegate to the same router, so a handlebar button and an on-screen button cannot diverge. The container
  runs the chain in its own scope, **with no activity**: a press with another app in front reaches the ride as one with the
  app on screen does. The foreground service of a live ride is what keeps the process alive; **that it does so with the
  screen off on this tablet, and that a media key from a background process reaches a video app, are bench items** (ADR 0012).
- *The Click's row* says what it is doing in plain words: Connected (with the battery once the Click has said), Connecting,
  "press any button" (only with the silence rule on), "Not found · retrying in N s", "Connection lost · …", "Could not connect · …", Bluetooth off or
  Nearby devices missing, and **locked**. A locked Click gets one notice, in two wordings by the sign that gave it away: the
  Zwift service is hidden ("Click is locked": "Zwift has it locked. Unlock it through Zwift once; the ride continues without
  it.") or, only with the silence rule on, it answered and says nothing ("Click sends no presses": "Press any button on it. If that does nothing, unlock it through Zwift once;
  the ride continues without it."). Neither is ever an error of the ride.
- *Learn buttons* (Devices, offered with a connected Click and no live ride) asks for the five roles in turn, rejects a
  combination and a duplicate with a plain message, can be cancelled, and saves the mapping (`ButtonMapping.serialize`) in the
  settings; a stored value that is not a valid mapping reads as the default. **Reset to default** puts it back. The Click does
  nothing while the lesson runs, so a press made to be learned never moves the ride, and a ride that goes live ends the lesson.
- *Developer settings for the bench* (Settings, **Developer · Click**, persisted and sanitised, a change reconnects only the
  Click): the setup message `FF 04 00`, the handshake with response, the reply check, the keep-alive, the silence rule (**Silence means
  locked**, off) and its limit, the message size, whether a scan result must list the service, which pucks a scan lists, and the two gesture timings (hold
  threshold 250 to 1000 ms, volume repeat 50 to 500 ms; the spec's 500 ms and 150 ms are the defaults), with **Reset**.

**First bench (2026-11-18)** (`docs/bench-reports/2026-11-18-first-ride.md`, `docs/protocols/click-v2.md` §11, ADR 0013; informal, not the
checklist's steps, **no acceptance criterion confirmed**). The right puck answered the handshake with the bare `RideOn` (the default
check now accepts it), sent nothing until a button was pressed (so silence is not a lock, and the rule that said it was is now an
opt-in), delivered buttons with no unlock through Zwift, and showed the bits A, B, Y, Minus and Plus (as `0x2000`). The D500 answered
every Request Control with a Reset, which the adapter no longer takes for lost control. Still open: the Click's advertisement, the
left puck, what it does after about three minutes, and every acceptance criterion.

### 4.8 FIT export

- One FIT activity file per ride. FIT is Garmin's published format, written with the FIT SDK profile.
- Records at 1 Hz: timestamp, power, heart rate, cadence, speed, distance.
- One lap per workout step. Session sport is cycling, sub-sport virtual activity.
- RR intervals are written as HRV messages when available.
- Files are kept in app storage and can be shared or saved to any folder.

**Settled in stage M2b-1** (`app.overdrive.core.fit`, ADR 0007, `docs/protocols/fit.md`):

- The writer is our own, `FitWriter(FitOptions).write(RideRecord): ByteArray`, deterministic and free of Garmin's SDK.
  The choices that only an import into intervals.icu can settle are the fields of `FitOptions`.
- A sample of second *k* is a `record` stamped with the start of that second, `start + k − 1`; every second is
  written, pauses included, as sampled, and elapsed and timer time are both the ride's length. Gaps are the base
  types' invalid values.
- One `lap` per lap of the record (a workout step), after its last record; `session` (sport cycling, sub-sport
  virtual activity, with normalised power, TSS, intensity factor and work once there is a normalised power) and
  `activity` last; timer start and stop `event`s around the records. RR intervals are `hrv` messages after the record of
  the second they arrived in.

**Settled in stage M2b-2** (`RideStore`, `RideLibrary`, ADR 0008):

- When a ride ends the record (JSON, §8.2) and the FIT file are written into the app's private `files/rides/`, each through
  a temporary file and a rename, and the crash-safe log is deleted only after both are on disk. The FIT file's local
  time uses the tablet's offset from UTC at the ride's start; every other `FitOptions` value is still the default.
- The finished screen shows "Saving the ride…", then "Saved on this tablet" with the file's name and size and two actions,
  **Share FIT file** (the system share sheet, through a `FileProvider` that serves only `files/rides/`) and **Save to folder**
  (the system file picker, suggested name `YYYY-MM-DD HH-mm <title>.fit`). If storing fails it says so and offers **Retry**.
  The Ready screen shows the last saved ride with **Share** and **Save to folder**, so the file stays reachable.
- Every ride is kept; there is no history screen or retention rule in Phase 1.

### 4.9 Sync stub

- A `SyncProvider` interface with a no-op implementation.
- Finished rides go into a persistent outbox, so Phase 2 uploads them without touching ride code.
- Settings hold the service URL and token, unused until Phase 2 (used since stage P2-1b, below).

**Settled in stage M4-1** (`app.overdrive.core.sync`, ADR 0011): `SyncProvider` (`upload(entry)` answers `Acknowledged`,
`RetryLater` or `NotConfigured`) with `NoopSyncProvider`, which acknowledges nothing; `Outbox` (`enqueue`, `pending`, `markDone`,
idempotent per ride id) with the in-memory `InMemoryOutbox`, the persistent one on the tablet's disk coming with the app in M4-2;
`OutboxEntry` is the ride id and a few facts for a "waiting to upload" line, **no payload and no wire format** (Phase 2, §8.3);
`SyncCoordinator` drains the outbox oldest first with the project's backoff (5 s, doubling, 15 minutes at most), and never
blocks or fails a ride: finishing a ride only queues it. With the no-op provider every ride stays queued for Phase 2.

**Settled in stage M4-2** (`android/data` `FileOutbox`, `android/app-ride`, ADR 0012):

- The persistent outbox is `FileOutbox`: an append-only journal in `filesDir/sync/outbox.log`, one checksummed line per entry
  (`E`) or upload (`D`), forced to storage before a call returns, with a torn last line ignored and the file compacted through
  a temporary file and an atomic rename. A ride is queued **once, ever** (the journal remembers uploaded ids). It grows by about
  100 bytes a ride; nothing is trimmed, because a ride that was not uploaded must not be forgotten.
- Every stored ride is announced to it: a ride that has just finished, a recovered one the rider kept, one completed silently at
  start, and at each start every ride already on disk (an outbox that has seen a ride ignores it, so this only catches one
  whose entry was lost between two steps). A tablet updated from an alpha therefore queues the rides it already holds, once.
- The container runs a `SyncCoordinator` over `NoopSyncProvider`: **nothing is uploaded**, every ride waits for Phase 2, and the
  app has no network code, no `INTERNET` permission and no payload format. A ride is never failed by its outbox.
- Settings, **Sync**: the service address and the token are stored and **not used** ("Not used yet"), with the number of rides
  waiting. The address must be `http(s)://` with a host and no user name or password, at most 2048 characters; its scheme and
  host are written in lower case and an address that fails is explained and not stored. The token loses control characters and
  surrounding spaces and is cut at 512 characters; it prints as dots, is hidden until **Show**, is never written to a log, a
  ride record or the outbox, and is not backed up (`allowBackup` is off).

**Settled in stage P2-1b** (`core` `HttpSyncProvider`, `android/data` `UrlConnectionTransport`, ADR 0014 sections 6 and 8): the
outbox drains for real, and what M4-2 said about "no network code, no `INTERNET` permission" and "Not used yet" no longer holds.

- The container's coordinator runs `HttpSyncProvider`, which sends a stored ride as `PUT {address}/v1/rides/{id}` (the record,
  `application/json`) and then `PUT .../fit` (`application/vnd.ant.fit`), streamed from the files the tablet already keeps, with
  `Authorization: Bearer <token>`. A ride leaves the outbox only when **both** answered 200 or 201 with the service's own upload
  response. The address and token are read at every upload: a changed setting takes effect, settled for 1.5 s, with no restart.
- `SyncResult` grew two answers. **`TokenRejected`** (401): nothing is asked again with that address and token; a changed
  address or token, or **Send now**, asks again. **`Refused(message)`** (409, 422, any other 4xx): the ride stays in the outbox
  and in the report with the service's short message, is skipped by the coordinator (no loop, the rides behind it go on) for this
  process, and is tried once more by a changed setting or **Send now**. 5xx, 408, 429, no answer (unreachable, timeout, TLS), a
  redirect and an answer that is not the service's upload response are `RetryLater` with the usual backoff.
  `SyncCoordinator.report` carries the last success time (kept in `filesDir/sync/last-upload`) and the refused rides.
- The manifest asks for `INTERNET` and nothing else of the network (no network-state permission, no Play Services), and sets
  `usesCleartextTraffic`, without which Android 9 and later refuse the plain `http://` address that Settings still accepts.
- Settings, **Sync**: the address (a plain `http://` one is flagged "Not encrypted: the token is sent as plain text") and the
  token ("Sent only to this address"); **Waiting to upload** with the count and one line in plain words about the last
  problem (not configured, "The service did not accept the token", "Service unreachable. Trying again soon", "1 ride refused:
  <the service's message>"); **Last upload** (a time, or "Never") with **Send now**, which tries the queue at once.

### 4.10 Acceptance criteria

1. A 60-minute free ride completes with video playing full screen and the overlay visible throughout.
2. A workout file with steady, ramp and interval steps plays in ERG and holds target power.
3. All ten Click actions work from the right controller with the app in the background.
4. Pulling the trainer's power mid-ride pauses the ride; it resumes after reconnect with no data lost.
5. Killing the app mid-ride and relaunching recovers the ride.
6. The exported FIT file opens correctly in intervals.icu with power, heart rate, cadence and laps.
7. Dragging the overlay drops no frames at the tablet's refresh rate.

**Status at the end of Phase 1 (`v0.1.0`).** Everything a JVM can prove is proved and green in CI; **no criterion is confirmed
on the device yet**. `docs/phase1-acceptance.md` says, for each criterion, what is automatic and what the owner's bench
settles (`docs/bench-checklist.md`), and has the table where a confirmation is recorded with its date.

## 5. Phase 2: Web service and planned workouts

Phase 2 connects the ride app to a plan: an AI agent schedules workouts, they appear on the tablet and in the calendar, reminders arrive in Telegram, and finished rides upload themselves.

### 5.1 Planning backend

- The intervals.icu calendar is the source of truth for planned workouts. The service reads and writes it with a personal API key.
- The service converts intervals.icu workouts to the native workout JSON and caches the next 14 days.
- If intervals.icu is unreachable, the cache keeps the apps working. If the project ever leaves intervals.icu, only this adapter changes.
- *Settled in stages P2-1c and P2-2a (ADR 0016, `server/src/intervals/`, `server/src/plan/`):* the service reads the calendar's WORKOUT
  events for **today and the next 14 days** (today in the rider's `TIMEZONE`) at start and every `PLAN_REFRESH_MINUTES` (15), and
  on demand, converts each to the native workout (`workout_doc` steps, else the text description, else the planned duration; steady,
  ramp, repeat and free steps; % FTP, watts over the FTP it was resolved with, zones through the watts intervals.icu resolves them
  to; a band becomes its middle; a heart-rate or pace step becomes a free step), and keeps them in the `planned_workouts` cache,
  mirroring deletions. A workout the native format cannot hold (nested repeats, steps in distance, until the lap button, …) is not
  served: it is logged once with the reason and kept with it in the cache. The native workout's id is `icu-<event id>`. With
  intervals.icu unreachable the cache answers with `stale: true`; with no key set the integration is off and the answer says
  `configured: false`. The calendar is written through `workoutToEventPayload` (the steps as intervals.icu's text syntax) for the MCP
  tools of §5.2, which come later. What the real service must still confirm is listed in ADR 0016.

### 5.2 MCP server

- Remote MCP server over HTTP with OAuth, hosted on the user's own domain, usable from desktop and mobile AI clients.
- Tools cover three jobs: plan (create, move, edit, delete workouts), review (rides, load, wellness, insights), and profile (FTP, zones, availability). The tool list is in section 8.
- Write tools validate the workout against the native schema before saving and return the stored result.
- *Settled in stage P2-3 (ADR 0018, `server/src/mcp/`, `server/src/oauth/`):* the server is Streamable HTTP at `<PUBLIC_BASE_URL>/mcp`
  (stateless, answers in JSON, the official SDK) behind an **OAuth 2.1 authorization server written for one owner**: protected
  resource and authorization server metadata, dynamic client registration, authorization code with PKCE `S256` only, rotating
  refresh tokens (access token 1 hour, refresh token 30 days, code 5 minutes, a code or refresh token reused later revokes its
  family), revocation, tokens bound to the MCP endpoint as their audience, stored only as digests. The owner approves each client on a
  page of the service by typing a passphrase, checked against a scrypt hash in `OWNER_PASSPHRASE_HASH` (never the passphrase itself),
  with lockout after five failures. Redirect addresses must be `https` or loopback. `MCP_STATIC_TOKENS` is a fixed-header escape
  for clients that cannot do OAuth. **The whole feature is off, its routes absent, unless `PUBLIC_BASE_URL` and the hash are set.**
  Eight tools exist (§8.4); `get_fitness`, `get_wellness` and `get_recovery` are Phase 3. What only a real client settles (which
  clients register dynamically, their redirect addresses, refresh behaviour) is listed in the ADR.

### 5.3 Google Calendar

- One-way push of planned workouts to a dedicated calendar: title, duration, short description of the session.
- Changes and deletions in the plan are mirrored. Edits made in Google Calendar are not read back.
- Calendar reminders come free with the events.
- *Settled in stage P2-4 (ADR 0019, `server/src/gcal/`):* the service pushes the planned workouts the tablet can play (today onwards, from
  the plan cache) into the calendar named by `GOOGLE_CALENDAR_ID`, one event each: the workout's name as the title, the date at
  `GCAL_WORKOUT_START` (default 07:00, in `TIMEZONE`) as the start, the workout's duration as the length, and its steps in plain words
  as the description (`10 min warm-up (45% to 70% FTP), 3 x 8 min at 95% FTP, 10 min cool-down. Total 1 h 15 min.`, written by code).
  Events carry no reminders of their own, so the calendar's notification settings apply. Creating is idempotent (the event id is derived
  from the planned id), changes become updates and removals become deletions, a day that has passed is left alone, and a start-up
  sweep deletes our events whose workout has gone. Failed pushes are retried with persisted backoff (1, 2, 4 … minutes up to 6 hours).
  The credentials are an OAuth client and a refresh token in the server environment (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
  `GOOGLE_REFRESH_TOKEN`, `GOOGLE_CALENDAR_ID`; all four or the calendar is off); the owner gets the refresh token once with
  `pnpm --filter @overdrive/server run google-auth`, which prints it to the terminal and writes no file. Nothing is read back from
  Google. What only a real account can confirm is listed in ADR 0019.

### 5.4 Telegram bot

- **Reminders:** the evening before and a configurable time before the workout.
- **After a ride:** a short summary with duration, average and normalised power, heart rate and load.
- **Weekly:** planned against completed, load trend.
- **Commands:** today, week, skip, move to another day.
- The bot answers only the owner's Telegram account.
- *Settled in stage P2-4 (ADR 0019, `server/src/telegram/`):* long polling (`getUpdates`), no inbound webhook; `TELEGRAM_BOT_TOKEN` turns the
  bot on and `TELEGRAM_OWNER_CHAT_ID` is the one chat it answers: any other chat is ignored silently and logged by chat id, never by
  content. Without the chat id the bot only listens and logs the chat ids that write to it (setup mode), which is how the owner finds
  theirs. Reminders: the evening before (`REMINDER_EVENING_TIME`, 20:00) and `REMINDER_LEAD_MINUTES` (60) before the same start time as the
  calendar, each sent once and persisted, so a restart repeats and loses none; both carry Skip and Move buttons. After a ride: duration,
  average and normalised power, average and maximum heart rate and load from the stored summary, and planned against ridden duration when
  the ride is linked to a plan. Weekly (`WEEKLY_SUMMARY_DAY` and `_TIME`, Sunday 18:00): sessions planned against done and skipped, the
  missed ones, rides and time, and the load (sum of the rides' training stress) against the week before up to the same weekday. Commands:
  `/today`, `/week` (the next seven days), `/skip` (asks to confirm, deletes the event in intervals.icu), `/move` (buttons for the next
  seven days, updates the event's date), `/help`; with no plan source they say so. All numbers are computed by code and no model is used:
  written insight is Phase 3 (§6.5, §6.6). What only a real bot can confirm is listed in ADR 0019.

### 5.5 Ride upload

- The ride app sends each FIT file to the service. The service stores it and fans it out.
- **intervals.icu:** uploaded and matched to the planned workout of that day.
- **Strava:** uploaded through the public upload endpoint as a virtual ride, with a text description of the session. Requires the user's Strava subscription, which the 2026 API terms demand for standard-tier access.
- Nothing is read from Strava. Its API terms forbid using its data in AI models, and our analysis uses our own files.
- Uploads are idempotent: a ride is identified by its start time and never posted twice.
- *Settled in stage P2-1 (ADR 0014):* the tablet uploads the ride record and the FIT as two idempotent requests (§8.3); the
  service identifies a ride by its id and by its start time, and a record whose start time is stored under another id is refused
  (`409`, naming the stored id) rather than stored twice. The push to intervals.icu and Strava is a separate step after intake.
- *Settled in stage P2-1c (ADR 0016):* storing a ride's FIT queues it for intervals.icu in the same transaction (`ride_destinations`),
  and a worker uploads the stored FIT (`POST /api/v1/athlete/{id}/activities`, the ride id as `external_id`), with backoff persisted
  in the table: 1, 2, 4 … minutes up to 6 hours, given up after 20 failures or when intervals.icu refuses the file, never for a wrong
  key. An activity intervals.icu already has counts as uploaded. The activity is paired with the planned workout of its day (the
  ride's `workoutId`, or the only cycling workout planned that day, in the rider's `TIMEZONE`). A failure there never fails or delays
  the tablet's upload. Strava will be another destination over the same queue.
- *Settled in stage P2-5 (ADR 0020):* Strava is another destination over that queue, off unless `STRAVA_CLIENT_ID`,
  `STRAVA_CLIENT_SECRET` and `STRAVA_REFRESH_TOKEN` are all set. The service posts the stored FIT (`POST /api/v3/uploads`,
  `data_type=fit`, `sport_type=VirtualRide`, `trainer=1`, `commute=0`, the ride id as `external_id`) and then checks **its own
  upload's** status until Strava names the activity (bounded, persisted, a repeat counts as sent). **Nothing else is requested from
  Strava, and a test fails if anything is:** the only GET is the status of an upload we made, and the only scope is
  `activity:write`. The refresh token rotates and the newest is kept in the database (the environment seeds the first run; a new
  value there replaces it); the owner gets one with `pnpm --filter @overdrive/server run strava-auth`. Rides stored before Strava
  was turned on are not posted unless `STRAVA_BACKFILL=true`. A 429 is waited out from the headers and `Retry-After`; a wrong
  scope, a refused token or a missing subscription is one logged error that stops requests until a restart or six hours. What only
  a real account settles (the subscription, the exact duplicate answer, the limits, the map) is in ADR 0020.

### 5.6 Strava presentation

- **Photos are not possible.** Attaching images on upload is limited to Strava partners.
- **Map, to be verified.** A FIT file with positions along a real route should render a map on a virtual ride. If the first test upload confirms it, free rides get a selectable route whose track is written into the file.
- *Settled in stage P2-5 (ADR 0020):* the title is the planned workout's name, or `Virtual ride` and the date for a free ride. The
  description is plain text written by code from the ride record and the linked workout, no model and no emoji: the session in one
  line (`Sweet spot 3x10: 10 min ramp 45 to 70% FTP, 3 x (10 min at 88% FTP + 5 min at 50% FTP), …`), duration, average and
  normalised power, average heart rate, work in kJ, load, the plan against the ride when one is linked, the FTP. No route feature
  is built; ADR 0020 section 7 lists what the first real test upload should look at (the activity type, the text and its line
  breaks, the streams, and whether any map shows).

### 5.7 Ride app changes

- A list of today's and this week's planned workouts, pulled from the service.
- Starting a planned workout links the recording to it.
- The Phase 1 outbox starts uploading. Local workout files keep working.
- *Settled in stage P2-1b (ADR 0014 section 8):* the outbox uploads through `HttpSyncProvider` (§4.9); Settings, Sync shows the
  waiting count, the last success and the last error, and has **Send now**. The list of planned workouts is stage P2-2.
- *Settled in stage P2-2b (the tablet's side; ADR 0017; the service's side is `GET /v1/workouts`, §8.3):*
  - **Fetching.** `PlanRepository` in `core` (`app.overdrive.core.plan`) asks `GET {address}/v1/workouts?from&to` with the same
    address, token and `HttpURLConnection` class as the ride upload (`UrlConnectionTransport` is also the `HttpGetTransport`), for
    **today to today plus 13 days** in the tablet's local dates. It asks at start, when the Ready screen comes on screen (at
    most once a minute), when the address or token has changed and settled (1.5 s), and on the **refresh** button; one request
    at a time. The last good answer is kept in `filesDir/plan/workouts` (a header line with a checksum, then the answer as sent;
    written through a temporary file and an atomic rename; a damaged or foreign file is no cache), so the list works with no
    network and after a restart. The ride app still works with no network at all.
  - **The Planned section** (Ready screen, left column, above Ride; design/system.md §6.1): today's workouts first (name, one line
    of description or else the step kinds, length in minutes, "IF 0.76" computed from the native steps), then **This week**,
    collapsed to a count, which holds tomorrow to six days on, by day. Below the label, one line says "Updated 07:30"; when the
    service says `stale` or the last request failed it says "Out of date · updated 07:30" with an alert and, for a failure, the
    reason ("Service unreachable"); a 401 says "The service did not accept the token. Check the token in Settings" (the Sync
    settings' wording). **With no address or token, or when the service answers `configured: false`, the section is not drawn
    and nothing is said.** The list is read through the clock, so a cache read days later shows the days that are now ahead.
  - **Starting one.** A planned row is chosen like any workout and **Start** plays it in the existing workout player; the
    ride's `workoutId` is the planned workout's id (it equals `workout.id`, so no mapping is involved), and the stored ride and the
    uploaded record carry it. A workout the player cannot run (more than 12 hours, or more than 2000
    steps once the repeats are expanded: the checks are arithmetic on the steps, never an expansion) is shown disabled with that
    reason. A planned workout whose id matches a stored ride is marked "Done 18:04" with a check (the date too when it was
    ridden on another day than it is planned for) and can still be ridden again. Local workout files and the bundled
    workouts are unchanged.

## 6. Phase 3: Statistics and analysis

Phase 3 turns the recorded data into answers: how fit, how tired, how recovered, and what to do about it today.

### 6.1 Companion app

- Runs on the phone, where the watch's data lives. Health Connect is per device, so the tablet cannot read it.
- **Background job:** reads new Health Connect records once a day and on app open, and uploads them to the service.
- **Screens:** today (recovery status, planned workout, latest insight), fitness (load and form over time, weekly load), power (power curve, FTP history), wellness (resting heart rate, sleep, respiratory rate trends), rides (history and detail).
- Same design system as the ride app. The visual quality bar is the same.

### 6.2 Wellness sources

Wellness input sits behind one `WellnessSource` interface so the wearable can change without touching the analysis.

| Source | Status | Provides |
|---|---|---|
| Health Connect, fed by OHealth | Phase 3 baseline | Resting heart rate, heart rate, sleep sessions, respiratory rate, oxygen saturation, steps |
| intervals.icu wellness | Phase 3 | Whatever a natively integrated device sends there |
| Health Connect, fed by another app | Later, no code change | Depends on the device. Gadgetbridge has initial Amazfit Helio Strap support and can write HRV. |
| Wear OS passive-data app on the watch | Only if a one-evening probe shows the watch exposes HRV through passive monitoring | HRV. Continuous sensor reading is ruled out for battery reasons. |

### 6.3 Training load

- **Per-ride load:** normalised power, intensity factor and a training stress score from power and FTP. This is the Coggan model: a practitioner standard described in Allen and Coggan, *Training and Racing with a Power Meter*, not a peer-reviewed physiological law.
- **Fitness, fatigue, form:** exponentially weighted averages of daily load over 42 and 7 days, and their difference. This descends from the impulse-response model of Banister et al. (1975), which is established in the sports-science literature.
- **FTP without a test:** estimated from the best efforts in recent rides (the eFTP approach intervals.icu uses) and updated automatically. The user confirms a change before zones move. An optional sub-maximal ramp with a heart-rate ceiling is available for a first estimate.
- **Aerobic progress:** power-to-heart-rate drift across steady rides. Coaching practice popularised by Friel, not a validated clinical measure.
- **Power curve** and **W′ balance** for interval analysis. W′ balance follows Skiba et al. (2012), peer-reviewed.
- **Not used:** acute-to-chronic workload ratio. Impellizzeri et al. (2020) describe conceptual flaws in it.

### 6.4 Recovery without HRV

- For each daily metric, keep a personal baseline: rolling mean and spread over the last 28 to 60 days.
- Metrics: resting heart rate, sleep duration and timing, respiratory rate, oxygen saturation, and heart rate during sleep when samples allow.
- Each morning, score each metric by its distance from baseline and combine them into one of three states: ready, caution, rest.
- The rules are explicit and inspectable. No opaque score.
- **HRV, when a source exists:** 7-day rolling average of ln rMSSD against the personal baseline, following Plews et al. (2013), peer-reviewed. It refines the state and is never required.

### 6.5 Written insights

- The service computes facts deterministically and passes them to the LLM as structured data. The model writes; it does not calculate.
- **Outputs:** a morning note (state, why, what to do with today's workout), a post-ride note (how the session went against the plan), a weekly review.
- **Rules for the text:** state a conclusion and a recommendation, quote only numbers that were supplied, make no diagnosis and name no medical condition.
- The same facts are exposed through MCP, so the planning agent can reason over them directly.
- The endpoint is configurable. Health data leaves the server only to the endpoint the user chooses.

### 6.6 Notifications

- **Morning:** recovery state and the day's workout, adjusted if the state is caution or rest.
- **Deviation alerts:** a metric outside its baseline for several days in a row, with a plain-language reading of what that usually means for training.
- **Plan drift:** missed sessions or load running well above or below plan.
- All through the Telegram bot and the companion's today screen.

### 6.7 Other activities from the watch

Workouts recorded on the watch, such as VR sessions in Beat Saber or Superhot, count toward fatigue and recovery but not toward cycling fitness metrics.

- **Import:** the companion reads exercise sessions and their heart-rate samples from Health Connect, where OHealth already writes them, and uploads them with the wellness batch.
- **Load:** computed from heart rate against the user's threshold heart rate, on the same scale as the power-based score, so one fatigue curve covers everything. Heart-rate load follows the TRIMP idea from Banister's work; the threshold-based scaling is practitioner convention.
- **Known bias:** heart rate lags in short intermittent efforts, so this method tends to under-count HIIT-style sessions. Treat the number as approximate.
- **Separation:** these sessions feed fatigue, form and recovery. Power curve, FTP estimation and aerobic drift use rides only.
- **Settings needed:** maximum and threshold heart rate, entered manually or estimated from rides.
- **Forwarding:** each session is also posted to intervals.icu as an activity of the matching type. Nothing is sent to Strava unless enabled.
- **Interfaces:** MCP `list_rides` and `get_ride` become `list_activities` and `get_activity` with a type field; the companion history shows both.
- **To check:** how dense the heart-rate samples are for an OHealth exercise session in Health Connect. Per-second or per-few-seconds is fine; per-minute would make the load estimate coarse.

## 7. Tech stack and non-functional requirements

Both apps are native Android in Kotlin and Jetpack Compose, written from scratch around a shared pure-Kotlin core. Native is the choice that best serves the three stated priorities: looks, frame rate and battery.

### 7.1 Android

- **Language and UI:** Kotlin, Jetpack Compose, coroutines and Flow.
- **Why not a fork:** the overlay is the main screen and the visual bar is high, so the UI is new in any case. Existing projects supply protocol code only.
- **Why not Flutter:** a system overlay needs a second rendering engine instance, which costs memory and battery.
- **No Google Play Services** in the ride app. No Firebase, no Google sign-in.

| Module | Contents | Platform |
|---|---|---|
| `core` | Protocol codecs (FTMS, heart rate, Click), ride engine, workout model and player, physics, gear logic, FIT writer, gesture detection | Kotlin Multiplatform, no Android imports |
| `ble` | Bluetooth transport behind an interface | Android |
| `data` | Ride files (the rides folder, the log writer; ADR 0008), outbox, sync client | Android |
| `ui-kit` | Design system: tokens, type, components, charts | Android, Compose |
| `app-ride` | Tablet app: ride screens, overlay service | Android |
| `app-companion` | Phone app: statistics, Health Connect reader | Android |

Keeping `core` free of Android code costs almost nothing now and leaves an iOS port possible later.

### 7.2 Web service

- **Proposed:** TypeScript on Node, PostgreSQL, the official MCP TypeScript SDK, Docker Compose behind the existing Caddy reverse proxy. This is a proposal; the language is an open decision.
- One deployable service with internal modules: sync API, MCP, scheduler, integrations, bot, insights.
- Any admin UI follows the user's standing web conventions. Phase 2 needs none: configuration is a file and the bot.
- Secrets for intervals.icu, Strava, Google, Telegram and the LLM live in the server environment only.

### 7.3 Performance and battery

- **Overlay:** no dropped frames while dragging at the tablet's refresh rate. At rest it recomposes at most once a second and runs no continuous animation.
- **Ride engine and BLE** run off the main thread. UI work never delays a control command to the trainer.
- **Ride app overhead target:** under 3% battery an hour beyond screen and video, measured on the Xiaoxin Pad. A target to verify, not a measured figure.
- **Companion:** one scheduled background run a day plus sync on open. No persistent service.
- **Startup:** app launch to devices connected in under 10 s with remembered devices.

### 7.4 Design

- Design comes before UI code: a design system and mock-ups of the overlay, the ride screen and the companion's main screens are a deliverable of their own at the start of Phase 1.
- Dark theme first. The ride app is used in a dim room next to video.
- Numbers use tabular figures and never shift width as they change.
- One accent system: power-zone colours. Everything else is neutral.
- Motion is short and purposeful, and absent from the overlay at rest.

### 7.5 Reliability and testing

- Simulated trainer, strap and Click implementations in `core` allow full rides without hardware.
- Recorded BLE sessions can be replayed to reproduce device behaviour.
- Protocol codecs, the workout player, the physics model and the FIT writer are unit-tested against fixed inputs.
- FIT output is validated by importing it into intervals.icu during Phase 1.

### 7.6 Distribution and privacy

- APKs are sideloaded. The service can host the latest build and the apps can check it for updates.
- All storage is self-hosted. Data leaves only to the services the user configured: intervals.icu, Strava, Google Calendar, Telegram and the chosen LLM endpoint.

## 8. Data model and interfaces

Four contracts hold the system together: the workout file, the ride record, the sync API and the MCP tool set. Names and fields here are a first draft to be fixed during implementation.

*Status at the end of Phase 1:* §8.1 (workout format) and §8.2 (ride record) are settled and live in `contracts/` with their
fixtures, read and written by `core` and checked by the TypeScript side. §8.3 and §8.4 are Phase 2: §8.3 is settled for the ride
intake (P2-1a), the planned workouts (P2-2a) and the profile (P2-3); §8.4 is settled for its Phase 2 tools (P2-3); the rest of §8.3 and
the Phase 3 tools stay a first draft. The
outbox entry of §4.9 is **not** a wire format (ride id, enqueue time, start time, duration, title); what an upload sends is
Phase 2's to settle, here, with its schema in `contracts/`, before any client or service code.

### 8.1 Workout format

Native JSON, version-tagged. Targets are fractions of FTP. Durations are seconds.

```json
{
  "schema": 1,
  "id": "2026-11-20-sweet-spot",
  "name": "Sweet spot 3x10",
  "description": "Three 10-minute efforts with 5-minute recoveries.",
  "steps": [
    { "type": "ramp", "duration": 600, "from": 0.45, "to": 0.70, "label": "Warm-up" },
    { "type": "repeat", "count": 3, "steps": [
      { "type": "steady", "duration": 600, "target": 0.88, "label": "Sweet spot" },
      { "type": "steady", "duration": 300, "target": 0.50, "label": "Recover" }
    ] },
    { "type": "free", "duration": 300, "label": "Free ride" },
    { "type": "ramp", "duration": 300, "from": 0.55, "to": 0.40, "label": "Cool-down" }
  ]
}
```

- Step types: `steady`, `ramp`, `repeat`, `free`.
- Optional per-step fields: `cadence` target for display, `label`.
- ZWO files and intervals.icu workouts are converted to this format on import.

### 8.2 Ride record

| Entity | Key fields |
|---|---|
| Ride | id, start time, end time, workout id if any, FTP at ride time, device list, sync state |
| Sample (1 Hz) | time offset, power, heart rate, cadence, virtual speed, distance, gear, target power, step index, paused flag |
| RR series | time offset, interval in ms |
| Lap | step index, start, end, average power, average heart rate |
| Summary | duration, moving time, average and normalised power, average and max heart rate, work in kJ, load |

The FIT file is generated from this record and can be regenerated at any time.

**Settled in stage M2a** (`app.overdrive.core.record`, ADR 0004; the wire format is below):

- *Ride*: `id`, `startEpochMillis`, `endEpochMillis`, `workoutId`, `ftp`, `devices` (names). Sync state belongs to
  the outbox (§4.9), not the record.
- *Sample*: one per second of the ride, pauses included, offsets 1, 2, 3, …. It holds the readings at the end of the
  second and the target, gear and step that were in force during it. `power`, `heartRate` and `cadence` are null for a
  gap; `speedKmh` and `distanceMeters` are virtual (§4.3); `gear` is set in free-ride steps, `targetPower` (the
  workout's, without the ramp-in after a pause) in ERG steps; `stepIndex` indexes the flattened workout.
- *RR series*: `timeOffsetSeconds` is the second of the notification that carried the interval, `intervalMs`.
- *Lap*: one for each maximal run of samples in the same workout step; `stepIndex`, `startSeconds`, `endSeconds`,
  `averagePower`, `averageHeartRate`. A ride without a workout is a single lap.
- *Summary*: `durationSeconds` (pauses included), `movingSeconds`, `distanceMeters`, `averagePower`,
  `normalizedPower`, `maxPower`, `averageHeartRate`, `maxHeartRate`, `workKilojoules`, `intensityFactor`, and the load as
  `trainingStressScore`. Everything but the duration and distance is computed over the seconds that were not paused.

**Wire format, settled in stage M2b-1** (`contracts/schema/ride-record.schema.json`, `RideRecordJson`, ADR 0007):

- One JSON document, `schema: 1`, with `ride`, `samples`, `rrIntervals`, `laps` and `summary`, under exactly the
  property names above (`ride` is the Ride entity; the sample's time offset is `timeOffsetSeconds`, the RR
  series' `timeOffsetSeconds` and `intervalMs`). **A gap is an absent key**: a writer never emits `null`, a reader
  accepts it. `speedKmh`, `distanceMeters`, `paused` and `devices` have defaults (0, 0, false, none) and are always
  written by the Kotlin writer.
- Beyond the schema, a record is valid only if its samples run 1, 2, 3, … in order, its laps follow each other from
  0 to the last second with each ending after it starts, and `summary.durationSeconds` is the number of samples.
  Kotlin and TypeScript both check this, against the same fixtures in `contracts/fixtures/rides/`.
- The ride in progress is stored not as this document but as the append-only log of §4.2: a `header` line, a
  `sample` line per second, an `rr` line when the strap sent intervals and a `finished` line, each one line of the
  same JSON behind a CRC-32. The record is rebuilt from it with `RideRecordBuilder`.

### 8.3 Sync API

HTTPS and JSON between the apps and the service, authenticated with a per-device bearer token
(`Authorization: Bearer …`; the service holds one named token per device, ADR 0014 section 4). The API is versioned in the
path: everything is under `/v1`.

| Endpoint | Used by | Purpose |
|---|---|---|
| `PUT /v1/rides/{rideId}` | Ride app | Upload the ride record (§8.2), the JSON body. **Settled** |
| `PUT /v1/rides/{rideId}/fit` | Ride app | Upload the FIT file generated from that record (`application/vnd.ant.fit`). **Settled** |
| `GET /v1/workouts?from&to` | Ride app | Planned workouts in native format. **Settled** (stage P2-2a, below) |
| `GET /v1/rides`, `GET /v1/rides/{id}` | Companion | History and detail |
| `POST /v1/wellness` | Companion | Batch of Health Connect records |
| `GET /v1/metrics?from&to` | Companion | Load, form, recovery state, baselines |
| `GET /v1/insights?from&to` | Companion | Written notes |
| `GET /v1/profile`, `PATCH /v1/profile` | Both | FTP, zones, physics settings. **Settled** (stage P2-3, below) |

**Settled in stage P2-1a (ride intake; ADR 0014, `server/README.md`, schemas in `contracts/`):**

- A ride is **uploaded** (the outbox acknowledges it) only when both PUTs have succeeded. The tablet sends the FIT because the
  service has no FIT writer; the service validates the record (schema and the structural rules of §8.2) and checks the FIT only
  for its header, sizes and CRCs.
- `{rideId}` is the record's `ride.id` (`ride-<start millis>`; 1 to 128 characters from `A-Z a-z 0-9 . _ -`). A different id in
  the record is `422`; a path that is not an id is `400`.
- **Success**: `201` when this request stored the record (or file), `200` when the identical body was stored before and nothing
  changed. Body `{ "rideId", "status": "stored" | "already-stored" }` (`sync-upload-response.schema.json`).
- **Immutability**: a stored ride never changes. Another record under a stored id, or another FIT for a stored ride, is `409`
  (`ride_conflict`, `fit_conflict`); a record whose start time is stored under another id is `409` `start_time_conflict` and its
  message names the stored id (§5.5). A FIT before its record is `404` `ride_not_found`.
- **Failure**: `{ "error": { "code", "message" } }` with a stable `code` from a closed list (`api-error.schema.json`): `400`
  `invalid_json`, `invalid_ride_id`; `401` `unauthorized`; `404` `not_found`; `413` `payload_too_large` (16 MiB record, 8 MiB
  FIT); `415` `unsupported_media_type`; `422` `invalid_record`, `ride_id_mismatch`, `invalid_fit` (and, from P2-3, `invalid_profile`); `500` `internal_error`. The
  tablet retries `5xx` and network errors, treats `401` as "token rejected" and other `4xx` as permanent (ADR 0014 section 6),
  except that `408` and `429` are retried and a `404 ride_not_found` for the FIT of a record it has just stored is retried too
  (section 8).

**Settled in stage P2-2a (planned workouts; ADR 0016, `contracts/schema/workouts-response.schema.json`, Kotlin `PlanApiJson`):**

- `GET /v1/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD[&refresh=true]` answers
  `{ "from", "to", "generatedAt", "stale", "configured", "workouts": [{ "id", "date", "name", "source": "intervals.icu", "workout" }] }`.
  `workout` is the native workout (§8.1) and `workout.id` equals the entry's `id` (`icu-123`), so a ride started from it records that id
  as `ride.workoutId` and the upload pairs by it. Dates are the rider's local days; both ends are included; at most **62 days**, otherwise
  `400 invalid_range` (a new code of the closed list, also for a missing, malformed or reversed range). Ordered by date, then id.
- The service serves its cache and does not wait for intervals.icu, unless `refresh=true` (it reads first and waits at most ten
  seconds). `generatedAt` is when the plan was last read successfully (UTC), or `null`. `stale` is true when the last refresh failed (the
  list is then the last known one) or none has finished yet. `configured: false` means no plan source is set up: the list is empty,
  `stale` is false and `generatedAt` null. The cache holds today and the next 14 days, so a range beyond that comes back short.
- Workouts the native format cannot hold are left out of the list (the service logs why); the answer does not mention them.

**Settled in stage P2-3 (the profile; ADR 0018, `contracts/schema/profile.schema.json`, `profile-patch.schema.json`, Kotlin `ProfileApiJson`):**

- `GET /v1/profile` answers `{ "ftp", "zones", "weeklyAvailability", "goals", "physics", "updatedAt" }`, every key always present.
  `ftp` is watts (30 to 2000) or `null` while the owner has not set one (a default would be a guess; the tablet then keeps its own).
  `zones.upperPercentOfFtp` are the six inclusive upper bounds of zones 1 to 6 in whole percent of FTP, strictly increasing, **Coggan's
  `55 75 90 105 120 150` by default** and overridable; the watts of each zone are derived from the FTP (`powerZoneRanges` in TypeScript,
  `PowerZones.ranges` in Kotlin, same ranges as `PowerZone.of`) and are not on the wire. `weeklyAvailability` is minutes (0 to 1440)
  for `mon` to `sun`, `null` where unknown. `goals` are at most 10 short lines. `physics` is `systemMassKg`, `cdA`, `crr`,
  `airDensity`, `drivetrainEfficiency`: the names and defaults of the ride engine's `PhysicsSettings` (§4.3; one mass for rider, bike
  and kit). `updatedAt` is UTC, `null` while it is the defaults.
- `PATCH /v1/profile` takes a partial update in the manner of a JSON merge patch (`application/json` or `application/merge-patch+json`):
  a key that is absent changes nothing; `ftp` is replaced or cleared with `null`; `zones` is replaced as a whole or reset with
  `null`; `weeklyAvailability` changes the weekdays it names (`null` forgets one); `goals` is replaced as a whole; `physics` changes the
  constants it names. It answers the whole profile. A change that would not make a valid profile changes nothing and is
  `422 invalid_profile` (a new code of the closed list); a body that is not JSON is `400 invalid_json`. A change emits the
  `profile-changed` event inside the service.

### 8.4 MCP tools

| Tool | Kind | Purpose |
|---|---|---|
| `list_planned_workouts` | Read | Calendar between two dates |
| `create_workout` | Write | Add a workout on a date, in native format |
| `update_workout` | Write | Change steps, name or date |
| `delete_workout` | Write | Remove from the plan |
| `list_rides` | Read | Completed rides with summaries |
| `get_ride` | Read | One ride: laps, power and heart-rate statistics, compliance with plan |
| `get_fitness` | Read | Load, fitness, fatigue, form series |
| `get_wellness` | Read | Daily wellness metrics and baselines |
| `get_recovery` | Read | Today's state and the facts behind it |
| `get_profile` | Read | FTP, zones, weekly availability |
| `update_profile` | Write | FTP, availability, goals |

Read tools return compact summaries by default and full series only on request, to keep agent context small.

**Settled in stage P2-3 (ADR 0018):** the Phase 2 tools are `list_planned_workouts`, `create_workout`, `update_workout`,
`delete_workout`, `list_rides`, `get_ride`, `get_profile` and `update_profile`.

- The plan is read and written through intervals.icu (§5.1): `create_workout` takes `date` and a native workout (§8.1; the model need
  not send `schema` or `id`, the calendar entry names the workout `icu-<n>`), validates it with the contract's own validator and says
  where it fails (`workout does not validate: /steps/0/duration must be integer`), writes the calendar, updates the plan cache,
  emits `plan-changed` and returns the workout as stored (with `warnings` where intervals.icu has it differently). `update_workout`
  changes `name`, `date` or `steps` (only workouts, sending only what changed); `delete_workout` removes one (a workout already
  gone succeeds). `list_planned_workouts` answers from the 15-day plan cache and reads intervals.icu directly for a range beyond it.
- `list_rides` gives the summaries the tablet recorded; `get_ride` recomputes statistics from the stored record (the ride app's
  definitions of average, normalised power and load, best 5 s, 1, 5 and 20 minutes, seconds in the seven zones, heart rate, cadence),
  gives the laps, and compares the ride with the **workout as it was when the ride was stored** (kept per ride) step by step, planned
  against actual length and average power; the time series come only on request. **Every number is computed by code, never by a model.**
- `get_profile` and `update_profile` are the profile of §8.3, with the zones in watts worked out.
- Results are compact JSON; failures are tool errors in plain words (`plan source not configured: …`). Without an intervals.icu key
  the plan tools say so and the others work.

## 9. Risks and open checks

One risk can change how a ride starts: the Click v2 unlock. The rest are small checks that resolve during development and block nothing.

| Item | What is unknown | How it gets resolved | Fallback | Bench step |
|---|---|---|---|---|
| Click v2 unlock bypass | Whether the no-unlock method from BikeControl 6.0.0 is in the open-source code or only in the paid build | Read the BikeControl repository before starting the Click module. **Answered by research: it is not in the open code** (implementation notes below); the plain path is built. **First bench (2026-11-18): one right puck connected and delivered buttons with no unlock through Zwift** (firmware and time since Zwift unknown); bench 5.2 to 5.4 still to run on purpose, with the left puck and a Zwift-unlocked one | Unlock once through Zwift before a ride. On-screen controls always work. | 5.2, 5.3, 5.4 |
| Click firmware | A firmware update could break the bypass | Never update the Click. Decline the prompt in Zwift Companion. | Same as above | 5.1 (record the version) |
| Click v2 reliability | Zwift forum users report random disconnects | Watch for it during the first rides. **First bench: three linked sessions ended with status 8 (link supervision timeout) after 135, 183 and 210 s and the app reconnected each time; why is not known** | Auto-reconnect; on-screen controls | 5.8, 5.10, 5.11 |
| RR intervals from the Coospo strap | Whether the strap sends them | Visible in the first build, or in nRF Connect | Ride HRV data is simply absent | 4.2 |
| Sleep stages in Health Connect | Whether OHealth writes stages or only session duration | First companion build reads the record | Sleep analysis uses duration and timing only | none (Phase 3) |
| HRV from the watch | Whether Wear OS passive monitoring exposes HRV on the OnePlus Watch 3 | One-evening probe on the watch, optional | Recovery model without HRV, already the baseline | none (Phase 3) |
| Strava map for virtual rides | Whether positions in the FIT file render a map | First test upload in Phase 2 (what to look at: ADR 0020 section 7; the upload is built and tested against a fake only) | Rides appear without a map | 7.7 (optional) |
| Gear feel | Whether a 1% grade step per gear feels right on the D500 | Tune by riding in Phase 1 | Step and gear count are settings | 3.6 |
| ZUI background limits | Whether the China ROM interferes with the foreground service | First long ride on the tablet | Battery and autostart exemptions | 1.3, 1.6, 6.9 to 6.12, 6.17, 5.11 |
| Literature citations | The references in section 10 were cited from memory | Verify each before relying on it | None needed for implementation | none (desk task) |

The bench steps are those of `docs/bench-checklist.md`. The protocol notes have their own numbered questions (`FT n` in
`docs/protocols/ftms-hr.md` §9, `CK n` in `docs/protocols/click-v2.md` §9, `FIT n` in `docs/protocols/fit.md` §8), each mapped
to a step in the checklist's coverage tables.

**Open decisions**

- Web service language. TypeScript is proposed in section 7.2.
- Gear model. Grade offset is the Phase 1 default; a power-based model is the alternative if the feel is wrong.
- Whether to buy a dedicated recovery tracker in Phase 3. The design does not depend on it.

## 10. References

Prior-art projects were found by web search on 2026-11-17 and 18. Only their descriptions were read; no code has been reviewed yet. Check each licence before reusing code.

### Prior art

| Project | Use for this spec |
|---|---|
| [BikeControl](https://github.com/OpenBikeControl/bikecontrol) and its [issue 68](https://github.com/jonasbark/swiftcontrol/issues/68) | Zwift Click v2 protocol, gesture mapping, unlock behaviour |
| [zwiftplay](https://www.github.com/ajchellew/zwiftplay) | Notes on the Zwift controller BLE service and encrypted protobuf messages |
| [trainer-control](https://github.com/alanburke/trainer-control) | Virtual gears as a 1% grade step per gear, built for a Van Rysel D100 with Cog and Click |
| [OpenTrainer](https://github.com/janpetr93/OpenTrainer) | MCP bridge for training planning next to an FTMS trainer app |
| [openspin](https://github.com/yaportmax/openspin) | Trainer app with MCP agent coaching and Strava upload |
| [QZ (qdomyos-zwift)](https://github.com/cagnulein/qdomyos-zwift) | Reference FTMS and virtual-gearing implementation, GPL-3.0 |
| [SHIFTR](https://github.com/doudar/SHIFTR) | Reverse-engineered Zwift trainer-side virtual shifting, if ever needed |
| [Indoor Bike](https://indoorbike.app/) | Kotlin Multiplatform trainer app with virtual gears and intervals.icu sync, GPLv3 |
| [Ewoc](https://github.com/Ewoc2026/ewoc) | Native Android FTMS workout app, GPL-3.0-or-later |
| [OpenBike](https://github.com/HugoLeBoennec/OpenBike) | Flutter trainer app with a clean layered architecture, Apache 2.0 |
| [intervals-icu-health-sync](https://github.com/daktak/intervals-icu-health-sync) | Health Connect to intervals.icu wellness sync, GPLv3 |
| [opw2-probe](https://github.com/grabbitz59-cmyk/opw2-probe) | Diagnostic app that lists sensors and passive data types on a OnePlus Watch 2 |
| [intervals-mcp-server](https://github.com/mvilanova/intervals-mcp-server) | Existing MCP server over the intervals.icu API |

### Specifications

- Bluetooth SIG, Fitness Machine Service 1.0. Open specification; the basis for trainer control.
- Bluetooth SIG, Heart Rate Service. Open specification.
- Garmin FIT protocol and SDK. Vendor-published format, the de facto standard for activity files.
- No ISO standard applies to any part of this system.

### Literature

Cited from memory during planning. Verify titles and details before quoting them elsewhere.

| Source | Supports | Standing |
|---|---|---|
| Banister et al., 1975, a systems model of training for athletic performance | Fitness and fatigue as impulse responses to training load | Peer-reviewed, foundational |
| Allen and Coggan, *Training and Racing with a Power Meter* | Normalised power, intensity factor, training stress score | Practitioner book, industry standard, not peer-reviewed |
| Skiba et al., 2012 | W′ balance model | Peer-reviewed |
| Plews et al., 2013 | Rolling-average HRV against a personal baseline | Peer-reviewed |
| Impellizzeri et al., 2020 | Critique of the acute-to-chronic workload ratio | Peer-reviewed |
| Friel, aerobic decoupling | Power-to-heart-rate drift as an aerobic marker | Coaching practice, not validated research |

### Service terms that shaped the design

- [intervals.icu pricing](https://www.intervals.icu/pricing): the core platform is free; API access does not need a subscription.
- [Strava API changes, 2026](https://forum.intervals.icu/t/strava-api-update-new-terms-subs-required-for-api-access/130240): a Strava subscription is required for standard-tier API access.
- [Strava upload documentation](https://developers.strava.com/docs/uploads/): lists VirtualRide as an activity type.

---

## Implementation notes (added in the repository, not part of the owner's spec)

Corrections and findings from research during implementation. The owner may want to fold them into the
live spec document.

- **§9, Click v2 unlock bypass.** The no-unlock method is *not* in BikeControl's open-source code. It
  first appears in the post-`gpl3` (Non-Commercial licence) versions; its handshake lives in a closed
  `prop` submodule and `prop_public` only holds a stub. §2 says "BikeControl 6.0.0 reports the right
  controller working without unlock": per BikeControl's own changelog, 6.0.0 only dropped the 24-hour
  unlock for Pro users, and the "right side only" mode came later. Consequence: Phase 1 implements the
  documented path (discovery, the `RideOn` write, the button bitmap) and falls back to the unlock
  notice of §4.7; the no-unlock handshake is a bench-test item. Details: `docs/protocols/click-v2.md`.
- **§4.7 and §4.9, M4-2.** The Click is built clean room from `docs/protocols/click-v2.md` alone (no code from BikeControl, the
  Zwift Play library or trainer-control), encryption stays unimplemented, and the sync outbox is on disk but uploads nothing.
  Decisions and what only the bench can settle: `docs/adr/0012-click-on-android-and-sync-outbox.md`.
- **§10, prior-art licences.** `intervals-icu-health-sync` is AGPL-3.0 (not GPLv3) and
  `intervals-mcp-server` is GPL-3.0. BikeControl is GPL-3.0 up to tag `gpl3` and under a
  Non-Commercial licence after it, so nothing from later versions may be copied. See
  `docs/references-licences.md`.
- **§4.6, overlay service type.** The foreground service was `specialUse` while the ride was simulated; M3-2 declares
  `connectedDevice|specialUse` with `FOREGROUND_SERVICE_CONNECTED_DEVICE` and the Bluetooth permissions, and passes
  `connectedDevice` only while `BLUETOOTH_CONNECT` is granted, so the overlay never depends on Bluetooth (ADR 0006, ADR 0010
  section 5). Opening the app from the overlay's long
  press depends on Android's background-activity-start exemptions for a visible window and `SYSTEM_ALERT_WINDOW`;
  that it works on the tablet is unverified, and the notification's tap is the fallback. See ADR 0006.
- **§4.1, Feature bits.** Settled in M3-1 as a report, not a precondition: the missing-feature check of `docs/protocols/ftms-hr.md`
  §10 is implemented (`FitnessMachineFeatures.missingForRide`) but refuses ERG or gears only when
  `FtmsOptions.enforceFeatureBits` is on, because the Feature word is single-source until the bench and a wrong one must not
  stop a ride. The Supported Inclination Range (`0x2AD5`) is not read yet; the gear model stays within ±15 % by construction.
- **§4.8, FIT export.** Garmin's FIT SDK is under a proprietary agreement that does not allow
  redistributing it inside an APK under a source-disclosure licence. The FIT writer is therefore our own
  implementation of the published format; the SDK is used only as a test-time decoder.
