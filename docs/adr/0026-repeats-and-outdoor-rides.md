# ADR 0026: Repeat-session comparison and outdoor rides

Status: accepted (Phase 3, stage P3-7, server side, 2026-11-18).

## Context

Two additions of the owner's second spec (2026-11-18) belong to Phase 3. **Repeat-session comparison** (spec §11.7): riding the same
session again is the cleanest measure of progress, so the system detects repeats by itself and shows every attempt side by side, with the
average heart rate at the same power, the aerobic drift, the compliance and their trend; the agent reads it through `get_ride`; a live
line against the previous attempt on the tablet's overlay comes later. **Outdoor rides** (§11.8): from March 2027 the owner rides outside
with a Geoid CC600 (speed sensor and heart-rate strap, no power, no cadence); the CC600 syncs through the OnelapFit app, which exports
FIT files and shares them to other Android apps; the ride must count in the fatigue and form picture, with the load from heart rate by
the method of the watch's workouts (§6.7) and without touching the power curve or the FTP estimate. The rules of `CLAUDE.md` hold:
every number is computed by code and never by a model, Strava is write-only (nothing is read from it, nothing is sent to it from here),
the ride app is not involved, the repository is public (no real recording: every FIT file in a test is written by the tests, every
figure is invented), and health data is never in a log line.

Built without a real OnelapFit file, a real CC600 recording, an intervals.icu account or the owner's rides: the FIT files are written by a
test-side encoder, intervals.icu and Telegram are the fakes of ADR 0016 and 0019. The last section lists what only real data settles.

## Decision

### 1. Shape

| Piece | Where |
|---|---|
| Contracts: `repeats-response`, `repeat-detail-response`, `repeat-previous-response`, `activity-import-response`, the activity (`source` `fit`), two error codes, the workout hash and its golden numbers | `contracts/` (schemas, fixtures, `src/repeats.ts`, `src/outdoor.ts`), Kotlin `RepeatsApiJson`, `ActivityImportJson`, `Activity` in `core` |
| The FIT decoder (messages, fields, base types, compressed timestamps) | `server/src/fit-decode.ts` (framing and CRC stay in `server/src/fit.ts`) |
| The outdoor ride out of a decoded file; storage and the duplicate rule; the import service and its route | `server/src/outdoor/` (`fit-ride.ts`, `store.ts`, `service.ts`, `routes.ts`) |
| Heart rate at the same power, compliance, step boundaries (pure); the comparison and the trend (pure); storage; the service; the routes | `server/src/repeats/` (`figures.ts`, `compare.ts`, `store.ts`, `service.ts`, `routes.ts`) |
| MCP | `src/mcp/tools/repeats.ts` (`list_repeats`, `get_repeat`), `repeatOf` in `get_ride`, `fit` in `list_activities` and `get_activity` |
| Telegram | a document handler in `src/telegram/` (`client.ts` `downloadFile`, `commands.ts` `onDocument`, `messages.ts`) |
| Tables | migration `0009_repeats_and_outdoor.sql` |

Shared files touched, additively: `activities` (a `source`, the distance, the climb and the raw file), `ride_workouts` (the hash), the
activity store and forwarder of the wellness stage (a row has a source; a FIT ride is forwarded as the file), the trimp functions (the
hold cap is a parameter), `services.ts` and `app.ts`, the Telegram client and bot.

### 2. Repeat sessions (spec §11.7)

**What is the same session.** Two rides match when the workouts they were played from have the same **structure**. The service keeps a copy
of the workout each ride was played from (`ride_workouts`, ADR 0018) and the hash is taken over that copy: the workout's steps **with
repeats expanded**, each leaf step as a token, in order, joined by commas: `s600@880` a steady step of 600 s at 88.0% of FTP, `r300@450-700`
a ramp from 45.0% to 70.0%, `f300` a free step of 300 s. Targets are in **thousandths of FTP, rounded**, so the float noise of a converter
(`0.88000000000001`) is not a difference. The input to SHA-256 is the line `overdrive.workout-structure.v1`, a line feed and that text; the hash is
the first 16 hex characters. (`workoutStructure` and `workoutHash` in `contracts/src/repeats.ts`; golden numbers in
`contracts/fixtures/repeats/workout-hash.json`, tested in `contracts/test/repeats.test.ts`.)

- **In the hash:** the step type, the duration in seconds, the targets, the order, and the expansion of repeats (a repeat of 3 blocks and the
  same six steps written out are one session).
- **Not in the hash:** the workout's `id`, `name` and `description`, every `label`, every `cadence` target, the schema number. So a renamed
  copy, a copy with other labels or cadence targets, and a workout re-imported from intervals.icu under a new event id all match; a changed
  target, duration, order, count or step type does not (`test/repeats-api.test.ts`, `contracts/test/repeats.test.ts`).
- The version (1) is in what is hashed: a change of the definition changes every hash and is a new version; the rows are then worked out
  again.
- A ride **without a workout snapshot** (a free ride, or a ride whose workout was gone from the plan and from intervals.icu when it was
  stored) has no hash and never matches. Routes do not exist yet (stage v0.4.0): `routeKey` is in every schema, always `null` today, and
  detection by route is that stage's.

**Storage.** `ride_workouts.workout_hash` and `workout_hash_version`, set when the snapshot is saved. The rides stored before the migration
get theirs from the snapshot already kept: `ensureHashes()` works out every hash that is missing or of an older version, in batches, and
is idempotent; it runs at start and before every read. What is worked out per ride (the heart rate per step, the compliance, the step
boundaries) is kept in `ride_repeat_stats`, tied to the record's SHA-256 and to a version, and worked out again when it is missing or out of
date, as `ride_metrics` is (ADR 0022).

**Heart rate at the same power** (the standard fitness marker of coaching practice; it also moves with heat, sleep and caffeine, which is
why the response says so in a plain `note` and why the trend over several attempts is what counts). For each **steady** step of the workout
(only a steady step has a fixed power; a ramp and a free step have none), with the step's target the fraction of FTP times the FTP the ride
was recorded with:

- a **second counts** when it is not paused, its power is within **3%** of the target (`|P − target| ≤ 0.03 · target`) and it has a heart rate;
- the **first 60 s are left out**: of each *stretch* of the step, a stretch being a run of its seconds with no pause in it, so the minute after
  a resume is left out as well (heart rate takes that long to settle);
- the step has a figure only with **at least 120 counted seconds**, and then it is the mean heart rate of those seconds (one decimal), with
  their mean power and their number; otherwise it has none, **with a reason in words** that says which rule failed (not ridden; under 60 s
  ridden; "only 119 s were within 3% of the target power after the first 60 s; 120 s are needed"; no heart rate recorded at the target power).

**The overall figure of an attempt** is the mean of its step figures over the **compared steps**, weighted by the steps' planned length. The
compared steps are those in which the **latest attempt that has any figure** has one: that is the reference. An attempt has an overall figure
only if it has a figure in each of them *and* its target power there is within 3% of the reference's (a changed FTP asks for other watts, so the
same power is then not being compared: the attempt shows its step figures and no overall one, and says so). This makes every overall figure of
one page a mean over the same steps at the same power. An attempt that stopped early has no overall figure and still shows what it did.

**Trends.** For each of `heartRateAtPower` (bpm, lower is better, steady below 2 bpm of fitted change), `driftPercent` (percent, lower, 1) and
`compliance` (percent of the planned time ridden, higher, 5): the attempts that have a figure, in the order ridden, a **least-squares line
against the attempt's position**, its slope per attempt and the fitted change from the first to the last (the slope times the attempts minus
one). A change smaller than the steady band in size is `steady`, else `improving` or `worsening` by which side is better. **At least 3
attempts with a figure are needed** (`minAttempts`, stated in every trend): two points always make a line and one comparison is not a trend
(§11.7); below that the direction is `unknown` and says how many there are. The bands are first guesses (day-to-day heart rate at a fixed
power moves by a few bpm); the response states them. Compliance is `100 · actual seconds / planned seconds`, at most 100. The line is a line:
three attempts of 100%, 47% and 100% make a flat trend, and the attempts show the dip.

**What the API gives.** `GET /v1/repeats` lists the sessions ridden at least twice, most recently ridden first (then by hash), each with its
latest and previous attempt and the three comparisons (latest, previous, difference = latest minus previous, or why there is none); `GET
/v1/repeats/{hash}` gives every attempt side by side with the fixed-power steps, the compared steps, the step figures, the overall figure, the
drift, the compliance, `effortRating: null` (reserved for the rider's own rating, which no stage has built) and the trends; `404 not_found`
for a hash that is not a session ridden twice. **`GET /v1/repeats/{hash}/previous?before=<rideId>`** is what the tablet's overlay line will need
(stage v0.4.0, not built): the attempt before the ride (the latest of all without `before`), with its step boundaries as ridden (the first lap of
each step, in seconds from the start of that ride), the target in watts and the heart rate at that power of each steady step, and its
overall figure; a small payload (under 1.5 KB for a five-step workout). `before` may be a ride that is not stored yet if its id carries its
start (`ride-<start millis>`, as the engine makes them), because the ride in progress is not on the server; any other unknown id is `404
ride_not_found`. A session never ridden, or no earlier attempt, is `200` with `previous: null`. A step ridden out of order is left out of the
boundaries so that they never overlap. MCP: `list_repeats` and `get_repeat` (compact, with the caution in their descriptions), and `get_ride`
gains `repeatOf` (the attempt number, how many attempts, the attempt before it and the difference in heart rate at the same power, this ride
minus that one), which is how the agent and, later, the game read repeats.

### 3. Outdoor rides (spec §11.8)

**Path A, built.** `POST /v1/activities/fit` takes the FIT file as `application/vnd.ant.fit`, at most 16 MiB, with a device token (the
companion shares the file from OnelapFit to it, spec §11.8; the Telegram bot imports through the same function). **Path B**
(OnelapFit to Strava, intervals.icu imports from Strava) needs nothing from the service and is **not built**; it is documented in
`docs/deploy.md` with its caveats (less control, and it depends on Strava's API terms). The service never reads Strava. If both paths
deliver the same ride to intervals.icu, the forwarding below finds the one already there by its start time and does not send a second.

**The FIT decoder: our own, not Garmin's SDK.** `@garmin/fitsdk` was evaluated (21.222.0). Its licence (`LICENSE.txt`, "FIT Protocol License
Agreement", last updated October 12, 2022) is proprietary: a *non-exclusive, royalty-free, non-transferable, non-sublicensable, limited licence ...
for Licensee's internal business purposes*; §2(c) forbids distributing, publishing or making it available to any third party; the technology and
its documentation are Garmin's *Confidential Information* (§4); §9 requires deleting it on termination; §10 lets Garmin require the latest version;
§7 an indemnity; §2(f) no benchmarking. The repository is public and the server image is built for publication, so a dependency whose licence
forbids redistribution does not fit (`docs/references-licences.md` section 4 reached the same conclusion for the tablet's writer). The service
needs a handful of messages, so it has **a small decoder of its own** (`server/src/fit-decode.ts`, about 400 lines with its comments): `file_id`, `sport`, `session`,
`lap`, `record` and `activity`; every other message and every developer field is skipped by its size; both byte orders; compressed-timestamp
headers; the base types' invalid values mean "absent"; a file over 400 000 records is refused (it bounds the memory a 16 MiB upload can take).
The layout is from `docs/protocols/fit.md`; the field numbers (checked against profile 21.222, as facts: no code was copied) are listed in the
decoder. It is tested against our own fixtures (the tablet writer's golden file, the minimal file) and against files written by a **test-side FIT
encoder** (`server/test/helpers/fit-encoder.ts`: header and CRC, definition and data messages, both byte orders, developer fields, compressed
timestamps, multi-session files, 12-byte headers), including truncated files, wrong CRCs and files with good framing and nonsense inside.

**What is read.** A file must be an activity (`file_id.type` 4, or no `file_id`; another type is `422 invalid_fit`). Its **cycling sessions**
(sport cycling or e-biking) are the ride; a session of another sport in the same file is not part of it. No cycling session, or a file with no
session whose `sport` message does not say cycling, is `422 unsupported_sport` ("This FIT file is not a cycling ride (its sport is running);
only cycling is imported"); no record with a time, or a ride under a second, is `422 empty_activity`. The two new codes are in the closed list
(contracts first). The ride starts at the first cycling session's start and ends at the last one's end (`max(timestamp, start + elapsed)`);
several cycling sessions are one ride, the gaps between them included. The distance and the climb are the sessions' own totals when they say
them, else the odometer of the records and a climb worked out from the altitudes with a **3 m dead band** (changes smaller than that are
noise), else null. The heart rate is every plausible reading (30 to 250 bpm; 0 is "not measured") at the file's own density. Power, cadence and
position in the file are **ignored**: the positions stay in the raw file and nowhere else (routes are stage v0.4.0).

**What is stored.** A row of `activities` with `source = 'fit'`, `type = 'cycling-outdoor'`, the title "Outdoor ride", `id = fit-<first 16 hex of
the file's SHA-256>`, the distance and climb, the average and maximum heart rate and how dense the readings are (count, median and longest
gap, coverage, worked out from the file's own readings), the heart rate **thinned to at most one reading every 5 s** (the mean of the slot, at
the first reading's time; at 1 Hz a ride's heart rate would be a row of hundreds of kilobytes that every load computation reads; the load
differs by a fraction of a percent), and the **raw FIT bytes**, unchanged. It is idempotent: the same bytes are one row (a unique index on the
hash).

**Load.** From heart rate with **the same TRIMP function as the watch's workouts** (ADR 0023: Banister's exponential TRIMP per sample
interval, 100 for an hour at threshold heart rate, the profile's maximum and threshold or the rides' estimates, resting heart rate from the
wellness readings or 60), registered with the metrics service as the `other` kind: it feeds fatigue, form and recovery and **never** the
power curve, the FTP estimate or aerobic drift, because it is not a row of `rides`, which are the only thing those read (a test imports a ride
whose file carries 400 W and checks the curve, the suggestion, the drift and the eFTP are unchanged). `loadApproximate` is true when a
threshold, the maximum or the resting heart rate is an estimate or the readings cover under 80% of the ride; the load is null while the
maximum and threshold heart rate are unknown. One difference from the watch: a bike computer **stops writing records while the timer is
stopped** (a traffic light, a café), so a sample holds **at most 30 s** instead of 5 minutes (`FIT_HOLD_CAP_SECONDS`), otherwise each stop
would count up to five minutes of heart rate that went on; a test shows a 15-minute stop is not counted. The known bias is the watch's: heart
rate lags in short hard efforts, which this method under-counts.

**Duplicate protection.** The same ride can arrive twice (the companion and the bot, a re-export from OnelapFit, the tablet's own file, the
watch). In this order, the first rule that matches gives `status: duplicate` (`200`) and the id of what it is the same as, `kind` `ride` or
`activity`, and a `reason`:

1. `same-file`: the same bytes (SHA-256) were imported;
2. `same-start-and-duration`: the start within **60 s** and the length within **2%** (of the longer) of any stored **ride, imported ride or
   watch session**;
3. `overlap`: the file overlaps a ride on the tablet, a watch session or another imported ride by **half of the file's own length or more**
   (the overlap rule of ADR 0023, with the half taken of the new thing).

Anything else is imported. Two uploads of one file at once are serialised in the process and the unique index decides a race between processes.
Where both records of one ride exist the one that counts is fixed, whichever arrives first: **a tablet ride beats an imported ride, which beats a
watch session**. A watch session that overlaps an imported ride by half of its own length or more (it arrived later) is not counted, not listed and
not forwarded (`overlapsRideId` names the `fit-…` id: the wellness stage's overlap query now also looks for imported rides), and an imported
ride that a tablet ride uploaded later turns out to overlap is not counted and not forwarded either. Asymmetry, stated: a *watch session that
arrived first* makes a later FIT of the same ride a duplicate (rule 3), so intervals.icu then has the watch's manual activity (no map) and not
the file; the owner is not expected to wear the watch's exercise recording on a ride that the bike computer records.

**Forwarding.** The ride is queued for intervals.icu in `activity_destinations` in the same transaction as the row, and the activity forwarder
of ADR 0023 sends **the FIT file as it is** (`POST activities` with the bytes, as a ride from the tablet is sent), with the external id
`overdrive-<activity id>`, once; before sending it looks for an activity with that external id or one that started within a minute (found: done,
nothing is sent: this is the other path's ride), and a duplicate answer of intervals.icu is done. Off without an intervals.icu key (the row stays
`pending`). A history import is sent too: the seven-day limit of the watch's workouts exists so that a phone's first sync does not post weeks of
history, which a deliberate share is not. **Nothing is sent to Strava.**

**Telegram.** The owner sends a FIT document to the bot: only the owner's chat (every other chat is ignored as before). The bot refuses a
document that plainly is not a FIT file (a name that does not end in `.fit` and a type that is neither FIT nor "a file"), one larger than 16 MB
(Telegram lets a bot download up to 20 MB; the bot says both limits and downloads nothing), downloads the rest (`getFile`, then the file; the
token is in the URL and is kept out of every error and log line, as in ADR 0019), imports it through the same function and replies in a few
lines: date, duration, distance, average heart rate, load (or that none can be worked out yet), or "Already imported." / "That ride is already
in your history (same start and length)." / "... overlaps one already in your history, so it counts once."; a file that is not valid, not
cycling or empty gets a plain sentence with the reason. Tested against the fake Telegram.

### 4. Contracts and the closed list of error codes

Contracts first (`contracts/README.md`): the four new schemas with valid and invalid fixtures, the TypeScript validators with the rules a
schema cannot state, the Kotlin models and `RepeatsContractTest` and `ActivityImportContractTest`. The activity schema gains `source: "fit"`, the
id `fit-<16 hex>`, the type `cycling-outdoor` (the pattern now allows a hyphen) and the optional `distanceMeters` and `elevationGainMeters`
(absent when the file does not say; the watch's workouts have neither; **optional on read**, so the companion built before this stage still
reads every answer it reads today). `RideItem.sport` is `cycling-outdoor` for an imported ride, so the rides list needs no new field. New API
error codes: `unsupported_sport` and `empty_activity` (both 422).

### 5. Privacy

No measured value in a log line or an error message. The import logs the device, the id, the status, the duplicate reason and sizes; the Telegram
path logs a status and a size; the repeats module logs how many hashes it worked out. A refusal names a structure and, at most, a size or a sport
(never a heart rate or a position); the file name a phone gives is not logged. `test/outdoor-privacy.test.ts` sends a ride with distinctive
figures through every door (import, duplicate, every refusal, a forwarding that fails and an intervals.icu that quotes what it was sent) and
searches the HTTP log, the service log and every error body; `test/repeats-api.test.ts` does the same for the repeats. Positions are kept only
in the raw file (the table has no position column) and are never in an answer.

## What is not claimed

Heart rate at a fixed power is a marker, not a measurement of fitness: it moves with heat, sleep, caffeine, hydration and how the day went, and
the response says so; one comparison proves little, and the trend is a straight line through at least three points with a band that is a first
guess. Detection is by the structure of the *planned* workout: two rides of the same workout where the owner changed the intensity on the
tablet are one session (the target the ride recorded is the plan's); a ride ridden free that happens to look like a workout is not detected.
The outdoor ride's load is approximate (heart rate lags, short efforts are under-counted, thresholds may be estimates, a cap of 30 s per stop
is a choice); its distance and climb are what the device wrote; nothing is said of speed, power, cadence or position, which a CC600 may not
write at all. An outdoor ride's maximum heart rate does not move the profile's estimate, which reads the tablet's rides only. The FIT decoder
reads what a bike computer writes; a file with another structure can be refused.

## What only real data settles

- **A real OnelapFit export** (and a CC600): which messages and fields it writes (`session` and `sport` present? 1 Hz or smart recording? does
  it stop writing records while the timer is stopped, as the hold cap assumes? developer fields? the altitude fields, the ascent?), its sub-sport
  and manufacturer, and whether the file the share target hands over is the `.fit` or a zip. A file the decoder cannot read is `invalid_fit`
  with a reason, so it shows at once; the decoder is small enough to extend in one place.
- **intervals.icu**: how it answers an upload of a file whose start matches an activity it got from Strava (the duplicate styles of the fake
  are guesses, as in ADR 0016), and whether it keeps the external id; **Strava path B** and its duplicates are intervals.icu's business.
- **The thresholds**: 3% and 60 s and 120 s (the owner asked for them; real ERG data will say whether 3% is too tight on the trainer), the
  steady bands of the trends (2 bpm, 1 point, 5 points), 3 attempts, the 30-second hold, the 3 m dead band, the 60 s and 2% of the duplicate rule.
- **Whether detection by structure is what the owner means** by "the same session": a re-imported workout, a workout the coach rewrote with the
  same steps, a ride played from a workout whose copy was not kept.

## Consequences

- The companion (stage P3-4) shows `GET /v1/repeats` and `GET /v1/repeats/{hash}` and shares a FIT file to `POST /v1/activities/fit`; the activity
  detail for a `fit-…` id is `GET /v1/activities/{id}`.
- The overlay line (stage v0.4.0) asks `GET /v1/repeats/{hash}/previous?before=<its ride id>`; the tablet needs the hash of the workout in
  progress (`workoutHash` and the golden numbers are in `contracts/`; the plan's answer does not carry it yet, which would be an additive field
  of `GET /v1/workouts` once the tablet that reads it exists), or the service can resolve it from the workout id: a decision for that stage.
- Routes (stage v0.4.0) fill `routeKey` and add a second way for two rides to match; the position data is in the raw files.
- Migration `0009` adds columns and one table; rows written before it are unchanged (`source` defaults to `watch`).
