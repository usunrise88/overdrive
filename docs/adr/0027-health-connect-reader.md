# ADR 0027: The Health Connect reader and the watch screens

Status: accepted (Phase 3, stage P3-4b, 2026-11-18). Builds on ADR 0023 (the service's side of the watch data), ADR 0024 (the companion
as built in P3-4a) and ADR 0025 (the written insights).

## Context

Spec §6.1 and §6.2 put the watch's data on the owner's phone, in Health Connect, and send it to the owner's own service; `design/companion.md`
specifies the Wellness screen, Today's recovery and note cards, the Notes screen, the Health Connect and Heart rate groups of Settings and the
three steps of the first run. ADR 0024 left the seams (`RecoveryState`, `ONBOARDING_STEPS`) and the service side exists (ADR 0023, 0025). The
rules of `CLAUDE.md` hold: no Google Play Services and no Firebase, the phone talks only to our service, the repository holds no real data,
and **health data is private**: it goes only to the owner's service.

Written **without a phone that has OHealth's data**: no real Health Connect record was available, so everything below is verified against a
fake `HealthSource`, against records built with the client library's own classes and with synthetic figures, and against the contract's
fixtures. The last section lists what only the phone settles.

## Decision

### 1. Layers

| Where | What |
|---|---|
| `core/health` | The reading logic, pure Kotlin, tested on the JVM: `HealthSource` (the one door to Health Connect), the neutral records, `WellnessMapper` (records into the wire contract), `WellnessBatcher`, `BatchStore`, `HealthSyncState`, `HealthSync` (first read, Changes API, queue, upload, retry) |
| `core/remote` | `WellnessResources` (`GET /v1/wellness` per range), `recoveryResource` (`GET /v1/recovery`), `InsightsRepository` (`GET /v1/insights`, 30 days at a time), each a `RemoteResource` of ADR 0024 |
| `android/data` | `HealthConnectSource` (the thin AndroidX adapter), `HealthPermissions` and the record mapping, `FileBatchStore` (the outbox on disk) |
| `android/app-companion` | `WatchWorker` and `WorkManagerScheduler` (the daily job), the container's wiring, the screens' models (`WellnessUi`, `RecoveryWords`, `NotesUi`, `HealthWords`, `HeartRateWords`) and the Compose screens |
| `android/ui-kit` | `SleepTimingChart`, `RangeBandLayer` (a band that follows the days), `ShowingLine`, a `drawn` flag on `DotLayer` |

### 2. Record types and permissions

Exactly the read permissions the app needs, one request, one dialog: **resting heart rate, heart rate, sleep sessions, respiratory rate, oxygen
saturation, exercise sessions** (the six of the design's list, which the recovery rating and the watch's activities stand on), and, optional,
**weight** (§12.5), **heart rate variability (rMSSD)** (a recovery signal the service uses when the watch writes it) and **steps** (spec §6.2, kept
for the assistant). The design lists six; the brief and the spec list the other three, so the first run's list shows nine, the last three marked
optional, and "recovery says less" is said only when an essential one is off. **No write permission is declared and the code never writes.** Two
more permissions ride in the same request where this Health Connect has them (`getFeatureStatus`): **history** (reading older than 30 days
before the app was first allowed, which the 60-day read needs) and **background** (the daily read). On a Health Connect without them the
corresponding "needed" flag is false and nothing is asked. A permission taken away in Health Connect is noticed at the next read (`SecurityException`
becomes `HealthPermissionException`) and said on Today and in Settings. Android asks every app that reads health data for a rationale screen:
`PermissionsRationaleActivity` (and the `VIEW_PERMISSION_USAGE` alias for Android 14) shows the same list and the promises.

### 3. What is read, and when

- **First read** (`firstRead`, "Reading the last 60 days"): the last 60 days in windows of 5, **newest window first**, every type in pages
  (`readPage`), the changes token made **before** the first window, so what changes while it runs is read next. The progress is written after
  each window (`Backfill.chunksDone`), so a killed process or a stopped job goes on with the next window. The progress bar counts windows and the
  text extrapolates the records ("1,240 of about 3,100 records": records read so far times windows in all over windows done), which is honest
  about being "about".
- **After that**, the **Changes API** with the token persisted: new and edited records only. A token that has expired (Health Connect keeps one for
  30 days; the adapter also reads an unusable token as expired), or a permission that was added or taken away, is a new token and a window read of
  what the old one does not cover (a type newly allowed: its 60 days; an expired token: since two days before the last read). Deletions are
  counted and ignored (the service has no delete).
- **When**: on app open (at most every five minutes), by Sync now, by the first read, and **once a day at about 06:00** by WorkManager
  (section 6). One run at a time (`HealthSync` is single-flight).

### 4. What is sent

`WellnessMapper` turns Health Connect's records into the records of `POST /v1/wellness` (contract `wellness-upload.schema.json`), pure and tested
against every valid fixture of `contracts/fixtures/wellness/upload` (read, turned back into neutral records, mapped again: identical).

- **Idempotence**: every record carries Health Connect's `metadata.id` (made safe for the contract's id rule, the same way every time) and
  `lastModified`; reading a window twice or re-sending a batch changes nothing at the service (duplicates), and an edit with a newer
  `lastModified` replaces.
- **Nothing the service would refuse goes out.** The service refuses a whole batch for one bad record, so a record the contract's models reject
  is skipped and counted, a heart-rate sample outside 1 to 300 bpm is dropped, an exercise type that is not a token is `other_workout`, and
  the library's type 0 (named `workout` in its own table) is named `other_workout` as the contract does.
- **Heart rate is sent only where something reads it**: the samples **inside a sleep session** (as heart-rate records, for the heart rate in
  sleep) and **inside an exercise session** (attached to it, for the activity's load). A watch writes a heart rate every few seconds all day;
  ADR 0023 uses the samples in those two places and nowhere else, and all of it would be tens of megabytes per 60 days. This is a deviation
  from "send heart rate samples" and is one policy (`HeartRatePolicy`), `ALL` for a test. A heart-rate record with more than 20,000 samples
  is split under `id`, `id.1`, ...; an exercise session with more than 50,000 is thinned evenly. The samples a series sends are those inside
  **all** the sleep sessions around it, whichever change made it be looked at, so a series is the same each time.
- **Weight, steps, rMSSD** are sent as they are.

### 5. The outbox and the upload

- **Batches** of at most 2000 records and **3 MiB** (the service accepts 4 MiB: this side's limit is the one that decides), in order. A batch is
  written to disk (`FileBatchStore`: one file per batch, a header with the record count and the CRC-32 of the body, written to a temporary
  file, forced to storage and renamed) **before** the state that says it was read is kept, so a crash at any instant repeats work and never skips any.
- **Upload** through the shared `ServiceClient` (the one transport, the token read at every request, a rejected token remembered). A batch is
  removed **only when the service answered 2xx and the answer is the contract's** (`wellness-upload-response`): a 200 that is something else (a
  captive portal) is not an acceptance. The answer's counts feed "Last sync": records sent, by type.
- **Failure** stops the run with the rest waiting, in order. The next try is after a backoff of 30 s doubling to 30 minutes (`RetryPolicy`, kept in
  the state so a restart does not forget it): while the app is alive by a timer, in the daily job by WorkManager's own backoff, and at once when the
  rider asks (Sync now, which also tries a refused token once more). A refused token (401) is not retried until the rider changes it or asks.
- **A batch the service refuses for good** (400, 413, 422) is **set aside** in `parked/` and counted, never deleted, and never in the way of the
  ones behind it. A batch whose file is damaged reads as no body and is set aside the same way.

### 6. Background rules

- **One job a day**: `PeriodicWorkRequest` of 24 h with a 1 h flex, first run the next 06:00 on the phone's clock, **network connected**, exponential
  backoff from 15 minutes, `KEEP` (opening the app does not move the hour); plus the read on app open. WorkManager is AndroidX over the phone's own
  JobScheduler: no Play Services. **No foreground service**, no alarm, no polling.
- **The first read** is one **expedited** job (no network constraint: reading is local and the batches wait), which falls back to ordinary work when the
  system's quota is used up. If it cannot be queued the container reads in its own process.
- The job reads, queues, uploads, and then asks again for the figures Today shows (the rating, the notes, the metrics, the workouts).
- **Honest about the battery.** Android and Xiaomi may still delay or stop the job. Health Connect lets a background read through only with its
  background permission (where it has one): without it the daily run says so ("Health Connect does not let Overdrive read in the background") and
  reads nothing, instead of failing on a refused call. Settings shows "Background reading" as allowed, or blocked by the phone's battery saving
  (`isIgnoringBatteryOptimizations`), with **Open settings**; the first run's third step helps to allow both settings of Xiaomi phones. MIUI/HyperOS
  has no API for Autostart, so the screen shows its path and "Not checked", as designed. Today says "The phone stopped this morning's read. The
  figures are from yesterday." when the last read is more than 30 hours old.

### 7. The screens

- **Wellness** (`GET /v1/wellness`): one small multiple per metric in the design's order, each a line in the personal band the service computed
  (a polygon that follows the days: `RangeBandLayer`), the days outside it as dots, one selection for the whole screen with "Showing Sun 15 Nov" and
  **Today**, **Values** per card, the range selector 4 weeks, 3 months, 1 year. **The phone computes no baseline and no verdict.** Sleep timing is a new chart
  (floating bars on a clock axis, the usual bedtime and waking as two bands). **Heart rate in sleep** is drawn only when the service has it. **Weight** is a
  card of its own (added to the design: the brief and §12.5 ask for the trend): the daily weighings as dots and the 7-day average as the line, drawn only
  when the range has a weighing. The service serves at most 186 days at a time, so **a year is two requests** (the older half and the newer), joined on the
  phone (`WellnessData`); if the older half fails the newer is shown with the status line.
- **Today**: the recovery card from `GET /v1/recovery` in every state (ready, caution, rest, learning, no reading), the service's `reason` and `guidance` as one
  text, the facts in the service's order with "usual 50–56", "above usual 50–56" (a bedtime says "later than usual"), "last night", "no reading"; a row
  opens Wellness at that metric. The note card and **Notes** from `GET /v1/insights`: five kinds, the three of the design plus the two alerts (**Heads-up** for a
  metric outside its range for days, **Plan check** for the plan drifting); an after-ride note shows its open **topic** (what stood out, the opening question and
  **Copy question**) and a link to the ride. Read only.
- **Settings**: the Health Connect group (data access with **Manage** or **Allow**, last read, what the last upload sent by type, background reading, what
  waits and the last error), the Heart rate group (maximum and threshold as steppers with the profile's own ranges and the threshold below the maximum,
  the service's estimates beside them with **Use estimate**; a change goes on the profile at once and to the service with `PATCH /v1/profile` once the rider
  stops tapping), and the "Last sync" row, which now says what was sent and what waits.
- **First run**: connect, Health Connect, background reading, then the first read with its progress; the step is kept (`OnboardingStep`), a phone that was
  set up before the watch data meets the new steps once, **Not now** goes on without access (Today then says where to allow it), and nothing allowed skips the
  rest. Everything allowed goes on by itself; something off lists what was allowed and what is lost, and **Allow sleep** asks once more and then opens
  Health Connect's own screen.

### 8. Privacy

Health data goes only from Health Connect to the owner's service, through the one transport, with the one token. **Nothing logs**: no `Log` call
touches a record, a figure or an exception message from a read (they are dropped: only "the read did not finish" is kept). The state file holds
a token, instants, counts and the kind of the last trouble, never a record. The outbox holds the bodies by necessity, in the app's private folder, until
they are accepted. The token is in no file the watch data keeps. Tests: the state and the status never hold a failure's words (a read that fails with a
message holding a figure), the token is in no file, and no log line holds a figure or the token. The manifest declares read permissions only.

## Tests

- **`core` (JVM)**: the mapping of every record type against every valid fixture of `contracts/fixtures/wellness/upload` (read, turned into neutral records, mapped again:
  identical), the batches' limits, and `HealthSync` driven by a fake Health Connect and a fake service: permissions granted, partial and declined, a type allowed later, the first
  60-day read in pages and newest window first, the Changes API with an edit, an expired token, an upload that fails and is retried after its backoff and in order, an answer
  that is lost and re-sent, a restart half way through the first read and with batches waiting, a 401, a 422 set aside, a 200 that is not the contract's, a revoked permission, a
  background read without its permission, one run at a time, a run that begins while the state loads, and no figure in the state, the status or a failure.
- **`android/data`**: the outbox on disk (order, restart, set aside, a damaged file, no temporary file left) and the client library's own record classes (built with synthetic
  figures) mapped to the neutral records, with the exercise tokens checked against the library's constants.
- **`app-companion`**: every model's words (recovery in each state, Wellness' cards, the notes, Settings), screenshots of every screen and state into `build/shots` (Wellness in each
  range and state, Today's cards, Notes, Settings' groups, the first run's steps, the rationale), Compose flows (the first run through its steps, Wellness' one selection, a fact opening
  Wellness at its metric, Notes with Copy, the stepper), the WorkManager scheduling and the worker, and **the real container against the loopback stand-in of the service with a fake
  Health Connect** (`WatchContainerTest`): the first read, an app opened, a service that is down and comes back across a restart, a lost answer, a refused batch, a rejected token and
  Sync now, access given and taken away, a state damaged on disk, a first read stopped half way, the figures kept on disk and shown with the service down, a year in two requests, the
  heart-rate change reaching the service, and the secrecy of the token and of every figure in the files and the logs.
- **Every wait is for a condition**, and where a test then closes the container or reads a file it waits for the disk as well (the outbox and the state are written before the
  status that says so is published). **Stability**: the pipeline tests (`HealthSyncTest`, `WatchRepositoriesTest`) and the container tests (`WatchContainerTest`,
  `CompanionContainerTest`) were run ten times in a row pinned to two cores (`taskset -c 0-1`) with three busy loops on the same cores, build cache off so each run really ran.
  Two real races came out of earlier runs and were fixed in the code, not the tests: a run that began while the state was loading was turned away as "already running", and a
  read that began before the rider changed a permission overwrote what the screen had just learned.

## Deviations from the design and the brief

- **Nine permissions, not six**: weight, heart rate variability and steps are asked for, marked optional (the design lists six; the spec and brief name the rest).
- **Heart rate is sent only inside sleep and exercise sessions** (section 4).
- **The "usual" caption** of a bedtime fact says "later than usual 22:50–23:40" and of a missing baseline "last night".
- **A Weight card** on Wellness, and "Values" on every Wellness card (the design's mock-up shows none; §3.6 says every chart card has one).
- **A year of wellness is two requests** (the service's 186-day limit).
- **"Go to Today"** is a ghost button on the first read, besides the design's "you can leave the app" line.
- The progress of the first read counts windows, not records; the record count is an estimate.

## What only a phone with OHealth settles

- Which of the nine record types OHealth really writes, how dense its heart-rate samples are in sleep and in a session (the service's density rules, ADR 0023
  section 4), and which exercise types it uses (a VR session).
- Whether this Health Connect has the history and background permissions, what a background read does without the latter, and whether the daily job runs at
  06:00 on HyperOS with both Xiaomi settings on; whether the expedited first read survives leaving the app.
- Whether a changes token really expires after 30 days and what the adapter sees when it does (`IOException`, or the flag).
- The feel of the first run on the phone: the system dialog, the second ask, the battery and Health Connect screens the buttons open (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  Xiaomi's autostart component).
- How long the first 60-day read takes, and its size, with real data.

## Consequences

- The ride app is untouched; `core` gained `health` and the wellness repositories; the contracts are unchanged.
- `ONBOARDING_STEPS` is 3 and the first run has the design's steps; `UnavailableWellness` is gone (the card is made from the service's answer).
- The next stages that need the phone (the repeat-session page, the FIT share target, spec §11.7 and §11.8) find the container, the outbox pattern and the screens here.
