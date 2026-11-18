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
- Multiple users, accounts, store distribution.
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
| Zwift Click v2 | Handlebar controller | BLE, Zwift proprietary encrypted protocol | Firmware 1.3+ needs a daily unlock through Zwift. BikeControl 6.0.0 reports the right controller working without unlock. Never update the Click firmware. |
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
- **Reconnect.** Each device reconnects on its own with backoff. A dropout never stops the ride; missing values are recorded as gaps.
- **Pairing.** One setup screen scans and remembers the three devices. Later launches connect without user action.

### 4.2 Ride engine

- A 1 Hz loop reads the latest sensor values, advances the ride state and writes one record.
- States: idle, ready, riding, paused, finished.
- **Auto-pause.** The ride pauses after 3 s of zero cadence and resumes when pedalling starts. Manual pause overrides it.
- **Trainer link.** A trainer that disconnects pauses the ride at once and its reconnect resumes it, with nothing lost:
  the seconds in between are still recorded, as paused samples with no power or cadence. The snapshot says why a ride
  is paused (trainer, manual or auto). A heart-rate dropout never pauses or stops anything; it is a gap.
- **Crash safety.** Records are appended to disk every second. An interrupted ride is recovered on the next launch.

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
- **Unlock handling.** On connect the app detects whether the controller is delivering events. If not, it tells the user to unlock it through Zwift once and continues without the Click.

### 4.8 FIT export

- One FIT activity file per ride. FIT is Garmin's published format, written with the FIT SDK profile.
- Records at 1 Hz: timestamp, power, heart rate, cadence, speed, distance.
- One lap per workout step. Session sport is cycling, sub-sport virtual activity.
- RR intervals are written as HRV messages when available.
- Files are kept in app storage and can be shared or saved to any folder.

### 4.9 Sync stub

- A `SyncProvider` interface with a no-op implementation.
- Finished rides go into a persistent outbox, so Phase 2 uploads them without touching ride code.
- Settings hold the service URL and token, unused until Phase 2.

### 4.10 Acceptance criteria

1. A 60-minute free ride completes with video playing full screen and the overlay visible throughout.
2. A workout file with steady, ramp and interval steps plays in ERG and holds target power.
3. All ten Click actions work from the right controller with the app in the background.
4. Pulling the trainer's power mid-ride pauses the ride; it resumes after reconnect with no data lost.
5. Killing the app mid-ride and relaunching recovers the ride.
6. The exported FIT file opens correctly in intervals.icu with power, heart rate, cadence and laps.
7. Dragging the overlay drops no frames at the tablet's refresh rate.

## 5. Phase 2: Web service and planned workouts

Phase 2 connects the ride app to a plan: an AI agent schedules workouts, they appear on the tablet and in the calendar, reminders arrive in Telegram, and finished rides upload themselves.

### 5.1 Planning backend

- The intervals.icu calendar is the source of truth for planned workouts. The service reads and writes it with a personal API key.
- The service converts intervals.icu workouts to the native workout JSON and caches the next 14 days.
- If intervals.icu is unreachable, the cache keeps the apps working. If the project ever leaves intervals.icu, only this adapter changes.

### 5.2 MCP server

- Remote MCP server over HTTP with OAuth, hosted on the user's own domain, usable from desktop and mobile AI clients.
- Tools cover three jobs: plan (create, move, edit, delete workouts), review (rides, load, wellness, insights), and profile (FTP, zones, availability). The tool list is in section 8.
- Write tools validate the workout against the native schema before saving and return the stored result.

### 5.3 Google Calendar

- One-way push of planned workouts to a dedicated calendar: title, duration, short description of the session.
- Changes and deletions in the plan are mirrored. Edits made in Google Calendar are not read back.
- Calendar reminders come free with the events.

### 5.4 Telegram bot

- **Reminders:** the evening before and a configurable time before the workout.
- **After a ride:** a short summary with duration, average and normalised power, heart rate and load.
- **Weekly:** planned against completed, load trend.
- **Commands:** today, week, skip, move to another day.
- The bot answers only the owner's Telegram account.

### 5.5 Ride upload

- The ride app sends each FIT file to the service. The service stores it and fans it out.
- **intervals.icu:** uploaded and matched to the planned workout of that day.
- **Strava:** uploaded through the public upload endpoint as a virtual ride, with a text description of the session. Requires the user's Strava subscription, which the 2026 API terms demand for standard-tier access.
- Nothing is read from Strava. Its API terms forbid using its data in AI models, and our analysis uses our own files.
- Uploads are idempotent: a ride is identified by its start time and never posted twice.

### 5.6 Strava presentation

- **Photos are not possible.** Attaching images on upload is limited to Strava partners.
- **Map, to be verified.** A FIT file with positions along a real route should render a map on a virtual ride. If the first test upload confirms it, free rides get a selectable route whose track is written into the file.

### 5.7 Ride app changes

- A list of today's and this week's planned workouts, pulled from the service.
- Starting a planned workout links the recording to it.
- The Phase 1 outbox starts uploading. Local workout files keep working.

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
| `data` | Local database, outbox, sync client | Android |
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

**Settled in stage M2a** (`app.overdrive.core.record`, ADR 0004; the wire format is still to be fixed in `contracts/`):

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

### 8.3 Sync API

HTTPS and JSON between the apps and the service, authenticated with a per-device token.

| Endpoint | Used by | Purpose |
|---|---|---|
| `GET /workouts?from&to` | Ride app | Planned workouts in native format |
| `POST /rides` | Ride app | Upload a FIT file and its summary |
| `GET /rides`, `GET /rides/{id}` | Companion | History and detail |
| `POST /wellness` | Companion | Batch of Health Connect records |
| `GET /metrics?from&to` | Companion | Load, form, recovery state, baselines |
| `GET /insights?from&to` | Companion | Written notes |
| `GET /profile`, `PATCH /profile` | Both | FTP, zones, physics settings |

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

## 9. Risks and open checks

One risk can change how a ride starts: the Click v2 unlock. The rest are small checks that resolve during development and block nothing.

| Item | What is unknown | How it gets resolved | Fallback |
|---|---|---|---|
| Click v2 unlock bypass | Whether the no-unlock method from BikeControl 6.0.0 is in the open-source code or only in the paid build | Read the BikeControl repository before starting the Click module | Unlock once through Zwift before a ride. On-screen controls always work. |
| Click firmware | A firmware update could break the bypass | Never update the Click. Decline the prompt in Zwift Companion. | Same as above |
| Click v2 reliability | Zwift forum users report random disconnects | Watch for it during the first rides | Auto-reconnect; on-screen controls |
| RR intervals from the Coospo strap | Whether the strap sends them | Visible in the first build, or in nRF Connect | Ride HRV data is simply absent |
| Sleep stages in Health Connect | Whether OHealth writes stages or only session duration | First companion build reads the record | Sleep analysis uses duration and timing only |
| HRV from the watch | Whether Wear OS passive monitoring exposes HRV on the OnePlus Watch 3 | One-evening probe on the watch, optional | Recovery model without HRV, already the baseline |
| Strava map for virtual rides | Whether positions in the FIT file render a map | First test upload in Phase 2 | Rides appear without a map |
| Gear feel | Whether a 1% grade step per gear feels right on the D500 | Tune by riding in Phase 1 | Step and gear count are settings |
| ZUI background limits | Whether the China ROM interferes with the foreground service | First long ride on the tablet | Battery and autostart exemptions |
| Literature citations | The references in section 10 were cited from memory | Verify each before relying on it | None needed for implementation |

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
