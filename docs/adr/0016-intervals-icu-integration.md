# ADR 0016: The intervals.icu integration and the planned workouts backend

Status: accepted (Phase 2, stages P2-1c and P2-2a, server side, 2026-11-18).

## Context

ADR 0014 made the tablet's rides arrive on our server and left "the intervals.icu step" for the second half of stage P2-1.
Spec §5.1 makes the intervals.icu calendar the source of truth for planned workouts, to be converted to the native workout
and cached, and §5.5 says a ride is uploaded to intervals.icu and matched to the planned workout of its day. This ADR records
how the server does both, written **without an intervals.icu account**: the endpoints and shapes come from the public OpenAPI
description (`https://intervals.icu/api/v1/docs`, read once, nothing from it is committed) and from the forum threads "API access
to Intervals.icu", "Workout builder" and its syntax quick guide, "Downloading planned workouts from the API". Every test runs
against a fake intervals.icu that we wrote from the same notes (`server/test/helpers/fake-intervals.ts`), so the tests prove
the code agrees with the notes and not with the real service. The last section lists what only a real account settles.

## Decision

### 1. Shape of the code

One deployable, modules that do not import each other's insides (`server/README.md`, "Modules"):

| Module | Role |
|---|---|
| `src/events.ts` | An in-process typed event bus: `ride-stored` (record **and** FIT stored; emitted after the transaction committed) and `plan-changed` (planned workout ids added, updated, removed). Delivery is synchronous, listeners may be async, a listener that fails is logged and cannot fail the emitter |
| `src/services.ts` | The composition (`app.services`): bus, `PlanStore`, the intervals.icu client, the plan refresher, the upload worker, `destinations`, the time zone. The loops start with `services.start()` (from `main.ts`, after migration and listen), never inside `buildApp`, so tests build the whole service without background work. Closing the app stops them |
| `src/intervals/` | The only code that knows intervals.icu: `client.ts`, `workout-doc.ts` (its parsed workout), `workout-text.ts` (its text syntax), `workout-convert.ts` (both directions), `uploads.ts` (the worker), `ids.ts` |
| `src/plan/` | `store.ts` (`PlanStore`), `refresher.ts`, `routes.ts` (`GET /v1/workouts`) |
| `src/timers.ts`, `src/time.ts`, `src/log.ts` | Timers the tests tick by hand, time-zone dates, the little logger interface |

The next modules (MCP tools, Telegram, Google Calendar, Strava) read and write the plan through `PlanStore`, call
`services.intervals` to change the calendar, call `services.refresher.refresh()` afterwards, and subscribe to the events.

### 2. Configuration

`INTERVALS_API_KEY` (unset or empty: **the integration is off**), `INTERVALS_ATHLETE_ID` (default `0`, the athlete the key
belongs to), `INTERVALS_BASE_URL` (default `https://intervals.icu`; the tests point it at the fake), `PLAN_REFRESH_MINUTES`
(default 15, 1 to 1440) and, not in the brief, **`TIMEZONE`** (IANA name, default `UTC`). A time zone is needed because the plan
is by calendar day: "today" starts the 14-day window and the day of a ride decides which planned workout it pairs with, and neither
the tablet's record nor the service has any other idea of where the rider is. Validation is in `config.ts` and never prints
a value; blank means default (compose passes `${VAR:-}`). Off means no request leaves the server, `GET /v1/workouts` answers
`configured: false`, and rides are still queued.

### 3. The client

HTTP Basic, user name `API_KEY`, password the key (OpenAPI `securitySchemes`, the cookbook). One header carries it; it is in no URL,
error message or log line (a test checks every failure path). Redirects are refused (they would carry the key elsewhere).
Timeouts: 20 s per attempt, 60 s for an upload. Up to four attempts with waits of 0.5 s doubling (30 s at most, or a 429's
`Retry-After`) on a network error or a 5xx **for requests that may be repeated** (reads, update, delete, upload: intervals.icu
recognises a file it has seen); a 429 is always waited out; **creating an event is retried only after a 429**, since a repeated
`POST` could make two events. Failures are `IntervalsError` with a `kind` (`unauthorized`, `not-found`, `rejected`, `rate-limited`,
`server`, `unreachable`, `bad-response`): `rejected` (another 4xx) is permanent, `unauthorized` and `not-found` mean the
configuration is wrong. Methods: `uploadActivity`, `findActivityId`, `listEvents` (`category=WORKOUT`, `resolve=true`),
`createEvent`, `updateEvent`, `deleteEvent` (an event that is gone is fine).

### 4. Uploading rides

- **Queue.** The FIT's `PUT` (ADR 0014) inserts a `ride_destinations` row (`intervals.icu`) in the **same transaction** as the
  FIT, and emits `ride-stored` afterwards; a failure of either rolls back both and the tablet retries. The row is created whether
  or not a key is set, so enabling the integration later also sends the rides that came before; migration 0002 queues the
  rides already stored with a FIT. The destination list is `services.destinations`, ready for Strava.
- **Schema.** Migration 0002 adds `next_attempt_at` (null until a try has been made: a new row is due at once, the worker's
  clock and not the database's decides what is due, which keeps tests exact) and `last_attempt_at`. States stay ADR 0014's:
  `pending` is a first try or a failed one to be retried, `done` has `external_id`, `failed` is given up.
- **Worker.** Takes rows that are due, five at a time, oldest first: on `ride-stored`, every 30 s, and at start. A row is
  claimed by moving its `next_attempt_at` ten minutes ahead (a lease), so two passes, or two instances, never upload one ride
  twice and a crash mid-upload resumes after the lease. At start every waiting row is made due, so a restart after fixing the key
  retries at once.
- **Outcomes.** Uploaded or already there: `done` with the activity id (the upload's answer, else a lookup by external id or by
  start time within a minute, else null). `rejected` (a 4xx that is not a duplicate): `failed` with the message. No answer, 429, 5xx:
  `pending` with the wait 1, 2, 4 … minutes up to 6 hours, **persisted in `next_attempt_at`**, and `failed` after 20 tries. A wrong
  key or athlete id waits at 6 hours and is never given up (it needs the owner, and the restart rule applies).
- **Already exists is success.** A 200 that created no activity, a 409, or a 400/422 whose body says duplicate or already exists
  is `duplicate`, counted as `done`. intervals.icu dedups by a hash of the file (forum); the FIT the tablet uploads is stored and
  forwarded byte for byte, so a repeat is the same file.
- **Pairing.** Before uploading, the worker lists that day's WORKOUT events (the ride's start in `TIMEZONE`) and passes
  `paired_event_id` when the ride's `workoutId` is `icu-<event>` and that event is there, or when exactly one cycling workout is
  planned that day; with several and no `workoutId` it does not guess. If the calendar cannot be read it uploads unpaired and logs
  it (an upload is worth more than a pairing). A ride that intervals.icu already had is not paired afterwards.
- **Never in the tablet's way.** Intake answers when the transaction commits; the worker only runs from the event or its timer, and
  a failing intervals.icu changes nothing the tablet sees.

### 5. The plan cache

- **Tables** (migration 0002). `planned_workouts`: id (`icu-<event id>`), source, external id, `plan_date`, name, the native workout
  (jsonb) **or** an `unsupported_reason` (never both, never neither), a content hash that decides "changed". `plan_sync_state`: last
  success, last attempt, last error per source, so a restart still knows how old the cache is.
- **Window.** The cache mirrors intervals.icu for **today and the next 14 days** (today in `TIMEZONE`): events are upserted, those
  that vanished in the window are deleted, and days before today are dropped. Entries beyond the window (a future write through
  `PlanStore.upsert`) are left alone. A write between a read and its mirror can be removed for one cycle; the next refresh brings
  it back.
- **Refresher.** Runs at start and every `PLAN_REFRESH_MINUTES`; `refresh()` is also the on-demand entry (after the MCP writes, or
  `GET /v1/workouts?refresh=true`), never throws, and joins a refresh already running. One `plan-changed` per refresh that changed
  something. Only cycling events (`Ride`, `VirtualRide`, `GravelRide`, …, or no sport) of category WORKOUT are kept.
- **Unreachable.** A failed read records the failure and leaves the cache as it was; the endpoint then says `stale: true` and keeps
  `generatedAt` at the last good read.

### 6. The conversion adapter

Reading an event: its parsed `workout_doc`, else the text description, else its planned duration, become a native workout with
id `icu-<event id>` (so the id the tablet records as `ride.workoutId` is the one the upload pairs by) and are validated against
`workout.schema.json`. Rules:

| intervals.icu | Native |
|---|---|
| Step with a duration and a % FTP target | `steady` |
| `ramp` with start and end | `ramp` (descending allowed) |
| A band without `ramp` (`80-90%`) | `steady` at the middle (ERG needs one number); noted in the log |
| Watts | a fraction of the FTP the workout was resolved with (`workout_doc.ftp`, else the event's `icu_ftp`); no FTP is unsupported |
| Zone (`power_zone`), % of a best effort | the watts intervals.icu resolved it to (`resolve=true`, `_power`) over that FTP; unresolved is unsupported |
| `freeride`, no target, or a heart-rate or pace target | `free` (a trainer cannot hold a heart rate; the cue is kept as the label) |
| `reps` with `steps` | `repeat` (a block that runs once is spliced in) |
| Cue text | `label`; cadence (a value, or the middle of a range) | `cadence` |
| No steps and a planned duration | a `free` step of that length |
| Nested repeats, a step in distance, until the lap button, prompts at a time inside a step, a repeat with no steps, no steps and no duration, a target above 300% FTP | **unsupported**: that workout is skipped, logged once with the reason, and its row keeps the reason |

Targets are fractions rounded to three decimals, durations whole seconds. The text parser reads the syntax of the forum's quick
guide (durations `1h2m30s`, `5m`, `90s`, `1m30`, `5'`; `75%`, `80-90%`, `ramp 50-75%`, `220w`, `Z2`, `70% HR`; `90rpm`, `90-100 rpm`;
`Nx` headers closed by a blank line) into the same shape as `workout_doc`, so one converter serves both.
Writing: `workoutToEventPayload(workout, date)` makes a WORKOUT event (`type: "Ride"`, `indoor`, `start_date_local` = the day at
00:00:00) whose description is the steps as that text, with labels as cues (words that would read as a target are dropped from a
cue) and the workout's description as a plain first line. The description is not read back. A round trip of every
valid workout fixture of `contracts/` is a test.

### 7. The endpoint and the contract

`GET /v1/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD` (bearer, as ADR 0014) answers `{ from, to, generatedAt, stale, configured, workouts }`
(`contracts/schema/workouts-response.schema.json`, Kotlin `PlanApiJson`): each workout `{ id, date, name, source: "intervals.icu",
workout }` with `workout.id == id`, ordered by date and then id. Both ends are included, at most **62 days**, otherwise
`400 invalid_range` (a new code in the closed list). `generatedAt` is null when the plan was never read, `stale` is true when the last
attempt failed or none has finished while a source is configured, and **`configured: false`** (not in the brief: a field, not a header)
says there is no plan source: the list is empty and `stale` false. `refresh=true` reads the source first and waits at most ten
seconds. The cache only holds today and 14 days ahead, so a range beyond that comes back short; that is the plan's horizon, not an
error. The ready screen of the tablet (P2-2b) is built on this contract.

## Deviations from the brief

- `TIMEZONE` is an added variable (section 2).
- The queue row is created regardless of configuration, and a migration backfills (section 4).
- A `configured` field and a `refresh=true` query parameter were added to the endpoint (section 7), and `invalid_range` to the API
  errors.
- Zones are converted through intervals.icu's own resolution rather than a table of default zone boundaries, which would be a
  silent guess at the athlete's settings.
- Tests run on PGlite only here; the SQL (`FOR UPDATE SKIP LOCKED` in the claim, `= ANY($n::text[])`, `to_char`) is standard
  PostgreSQL and runs against a real one in CI (`TEST_DATABASE_URL`), which this sandbox has none of.

## What only a real intervals.icu account can settle

Nothing below was verified against the service. Each is a place where the code, the fake and the tests share an assumption.

1. **Uploading.** That `POST /api/v1/athlete/{id}/activities` takes the file as multipart field `file` and the query parameters
   `external_id`, `paired_event_id` (the OpenAPI description says so, a live call has not). Its answer for a new file (we read
   `{ activities: [{ id }] }`, also a bare array, per the OpenAPI and the forum) and, above all, **what a duplicate answers**: we
   treat a 200 with no activities, a 409, or a 400/422 that says duplicate or already exists as success. Whether intervals.icu
   dedups on the file hash only (forum) or also on `external_id` or the start time, and what it does with a different file for the
   same start time (the owner's hand-imported FIT of the first bench is the same bytes, so it should be a hash duplicate).
2. **Pairing.** That `paired_event_id` pairs on upload, that an event can be paired when a same-day activity is also auto-matched
   by intervals.icu, and that an already existing activity can still be paired (`PUT /api/v1/activity/{id}` has the field; we do not
   use it).
3. **Listing events.** The `workout_doc` shape we read (steps with `duration`, `power`/`hr`/`pace`/`cadence` as
   `{value | start,end, units}`, `ramp`, `freeride`, `reps` with nested `steps`, `text`, `workout_doc.ftp`) comes from forum posts, not
   the OpenAPI file (which calls it an object). Unverified: that a ramp is `ramp: true` with `start` and `end` (a forum post reported
   the two swapped in one field once), that a band is `start`/`end` without `ramp`, that zones are `units: "power_zone"` with the watts in
   `_power` under `resolve=true`, `category=WORKOUT` as a filter, `newest` inclusive, `start_date_local` as `YYYY-MM-DDT00:00:00`, the
   `type` values of cycling events, and whether events with only a text description come back with `workout_doc` filled in.
4. **Writing events.** That intervals.icu parses a created event's description into steps, that its parser accepts what we write
   (`- Sweet spot 10m 88%`, `ramp 55-40%` descending, `87.5%` with a decimal, `freeride`, a repeat as `Nx` between blank lines, cues
   before the duration), and what it does with `type: "Ride"` and `indoor: true`. The MCP stage should create one event and look at it.
5. **Time zone.** That `TIMEZONE` and the athlete's own zone in intervals.icu agree, since the day of a ride and "today" use ours.
6. **Limits.** Its rate limits and what a 429 carries (we honour `Retry-After` in seconds), whether the personal key has every right
   these calls need, and whether the athlete id `0` works for every endpoint here.
7. **Laps.** The FIT's laps showing as intervals (kickoff, bench report) is about the file, not this integration, and is still open.

## Consequences

- With a key set the owner's rides reach intervals.icu without a step, and the tablet has a list to show (stage P2-2b), both
  surviving an intervals.icu outage.
- Planned workouts the tablet cannot play are visible in the log and in `planned_workouts.unsupported_reason`, not silently
  missing; the MCP stage can tell the agent why.
- Moving off intervals.icu later changes `src/intervals/` only: the store, the endpoint, the events and the contract do not know it.
- Strava joins as another destination with its own worker over the same queue.
