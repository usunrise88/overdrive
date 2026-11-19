# server

The self-hosted web service (spec §7.2): sync API, MCP server, scheduler, integrations, Telegram
bot, insights. One deployable with internal modules. Today it has the ride intake (ADR 0014:
the tablet's rides arrive here and are stored), the PostgreSQL schema behind it, the intervals.icu
integration (ADR 0016: rides are forwarded, the planned workouts are read, converted, cached and
served to the tablet), the rider's profile (`GET`/`PATCH /v1/profile`), the MCP server with its
OAuth server for AI clients (ADR 0018), the Google Calendar mirror and the Telegram bot (ADR 0019), the Strava upload (ADR 0020), the
training metrics (ADR 0022), wellness, recovery and the watch's other activities (ADR 0023), the written insights, alerts and discussion
topics (ADR 0025), the repeat-session comparison and the outdoor rides imported from FIT files (ADR 0026), the coach notebook and the plan events
(ADR 0035), the routes with elevation and the easier variant (ADR 0036) and `GET /healthz`.

```bash
pnpm --filter @overdrive/server dev      # node --watch src/main.ts, http://127.0.0.1:8080 (needs DATABASE_URL)
pnpm --filter @overdrive/server test     # hermetic: PGlite stands in for PostgreSQL
pnpm --filter @overdrive/server build    # tsc -> dist/, then: node dist/main.js
```

Node runs the TypeScript sources directly in development (type stripping), so source files use
`.ts` import extensions and avoid enums and parameter properties; `tsc` rewrites the extensions
for the build. `@overdrive/contracts` resolves to its TypeScript sources through its `source` export
condition (`dev`, the tests and the typecheck), and to its built `dist/` in the image.

## Configuration

Environment variables, validated at startup in `src/config.ts` (an invalid value stops the start
and names the variable, never its value):

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | required | `postgres://user:password@host:5432/db`. Compose builds it from `POSTGRES_PASSWORD` |
| `DEVICE_TOKENS` | empty | `name:token,name:token`: the devices allowed to call `/v1`. Every token is at least 32 characters from `A-Z a-z 0-9 + / = . _ ~ -`, names and tokens are unique. **Empty means every `/v1` request is answered 401** (`/healthz` stays open) |
| `HOST` | `127.0.0.1` | The container image sets `0.0.0.0` |
| `PORT` | `8080` | |
| `LOG_LEVEL` | `info` | `fatal` … `trace`, `silent` |
| `INTERVALS_API_KEY` | unset | The personal API key of intervals.icu (Settings, Developer Settings). **Unset or empty means the integration is off**: no request leaves the server, `GET /v1/workouts` answers `configured: false`, rides are queued and go out once the key is set. At most 256 visible characters, no spaces |
| `INTERVALS_ATHLETE_ID` | `0` | `i12345`; `0` is the athlete the key belongs to |
| `INTERVALS_BASE_URL` | `https://intervals.icu` | Another http(s) address (a proxy, a bench); no credentials or query. The tests point it at a fake |
| `PLAN_REFRESH_MINUTES` | `15` | 1 to 1440: how often the plan is read |
| `TIMEZONE` | `UTC` | The rider's IANA time zone (`Europe/Paris`): which day is "today" for the plan and which day a ride, a night, a reading or a watch workout belongs to |
| `WELLNESS_SOURCE_ORDER` | `healthconnect,intervals` | Which source a daily wellness figure is taken from: the first in the list that has one. After semicolons a metric gets an order of its own, `healthconnect,intervals;restingHr=intervals,healthconnect`; a source left out of a metric's list is not used for it. Metrics: `restingHr`, `sleepSeconds`, `bedtimeMinutes`, `wakeMinutes`, `respiratoryRate`, `spo2`, `sleepingHr`, `hrvRmssd`, `hrvSdnn`, `steps`, `weightKg`. ADR 0023 |
| `WELLNESS_PULL_HOURS` | `6` | 1 to 168: how often intervals.icu's wellness is read (only with an intervals.icu key) |
| `WELLNESS_BASELINE_DAYS` | `42` | 28 to 60: the window a personal baseline is the mean and standard deviation of |
| `WELLNESS_NIGHTS_NEEDED` | `14` | 7 to 28: readings a metric needs before it has a baseline, and nights before any recovery verdict |
| `INSIGHTS_ENABLED` | `true` | `false`: no written note or alert is written or sent (the stored ones are still served). ADR 0025 |
| `LLM_BASE_URL`, `LLM_MODEL` | unset | An OpenAI-compatible chat-completions endpoint and the model name. **The language model is on only when both are set**; otherwise the notes are written from templates and no health data leaves the server. `LLM_BASE_URL` is https (http only for `localhost`), no credentials, query or fragment: the facts of the rider's training and recovery are sent there and nowhere else |
| `LLM_API_KEY` | unset | The endpoint's key, if it needs one. A secret: only in the server's environment; no log line or error holds it |
| `LLM_TIMEOUT_SECONDS` | `60` | 5 to 600: per request; a failing or slow one is retried (three requests, 1 s and 2 s apart), then the template is used |
| `INSIGHTS_LANGUAGE` | `en` | A language name or code put in the prompt (letters, spaces and hyphens only). The template is English |
| `INSIGHT_MORNING_TIME` | `07:00` | `HH:MM` in `TIMEZONE`, or `off`: when the morning note is written (and the morning alerts checked) |
| `INSIGHT_DENY_WORDS` | empty | Comma-separated words a note may never contain, added to the built-in list |
| `ALERT_STREAK_DAYS` | `3` | 2 to 14: mornings in a row a metric is outside its usual range (adverse side) before an alert |
| `ALERT_MISSED_SESSIONS` | `2` | 1 to 7: planned sessions missed in the last 7 days before a plan-drift alert |
| `ALERT_LOAD_ABOVE_PERCENT`, `ALERT_LOAD_BELOW_PERCENT` | `30`, `40` | The ride load of the last 7 days this far above (5 to 300) or below (5 to 95) the planned load: plan drift |
| `NOTEBOOK_MAX_CHARS`, `NOTEBOOK_LOG_MAX_CHARS` | `12000`, `3000` | 2000 to 100000 and 500 to 50000: the most the whole coach notebook, and its dated log, may hold, in characters. ADR 0035 |
| `PLAN_EVENT_DAY_END_GRACE_MINUTES` | `180` | 0 to 720: how long after local midnight a day waits before it is checked for a planned session no ride completed |
| `BROUTER_URL` | unset | The BRouter routing server, such as `http://brouter:17777` (`docs/deploy.md`, section 20). **Empty: road routing is off** and `create_route` mode `waypoints` says so; GPX files and synthetic profiles need no engine. ADR 0036 |
| `BROUTER_TIMEOUT_SECONDS` | `30` | 1 to 300: how long a routing request may take before the service says routing is unavailable. `compose.yaml` does not pass it yet: add `BROUTER_TIMEOUT_SECONDS: ${BROUTER_TIMEOUT_SECONDS:-}` to its `environment:` to set it from `.env` |
| `ROUTE_STEP_METERS` | `20` | 5 to 200: a route is resampled to one point every this many metres (coarser for a route that would pass 5000 points). Same note as above for `compose.yaml` |
| `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET`, `STRAVA_REFRESH_TOKEN` | unset | The Strava upload (ADR 0020). **The feature is off unless all three are set**; with only some set it is off and the log names the missing ones. The client id is the number of the application on strava.com/settings/api; the refresh token (which only seeds the first run, Strava rotates it) comes from `strava-auth` (below). Letters, digits and `. _ ~ + / = : -` only |
| `STRAVA_BACKFILL` | `false` | `true` also sends the rides stored before Strava was turned on, at start. Off, turning Strava on does not post the history |
| `STRAVA_BASE_URL` | `https://www.strava.com` | Another http(s) address; the tests point it at a fake |
| `PUBLIC_BASE_URL` | unset | The service's public address, `https://overdrive.llmto.day` (http only for `localhost`): the OAuth issuer and the origin of the MCP endpoint's addresses. **With `OWNER_PASSPHRASE_HASH`, turns the MCP server on** |
| `OWNER_PASSPHRASE_HASH` | unset | The owner's passphrase as a scrypt hash, `scrypt:…`, made by `hash-passphrase` (below). **With `PUBLIC_BASE_URL`, turns the MCP server on.** The plain passphrase is never configured |
| `MCP_STATIC_TOKENS` | empty | `name:token,name:token`: fixed bearer tokens for MCP clients that cannot do OAuth, at least 32 characters each. Open `/mcp` only. Ignored while the MCP server is off |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, `GOOGLE_CALENDAR_ID` | unset | Google Calendar (ADR 0019). **On only when all four are set**; with some set the calendar stays off and the log names the missing ones. The refresh token comes from `pnpm --filter @overdrive/server run google-auth` |
| `GCAL_WORKOUT_START` | `07:00` | `HH:MM` in `TIMEZONE`: when a planned workout starts, for the calendar event and the lead reminder |
| `TELEGRAM_BOT_TOKEN` | unset | The bot is on when this is set (BotFather) |
| `TELEGRAM_OWNER_CHAT_ID` | unset | The one chat the bot answers, a number (a private chat's is positive). Unset with the token set: setup mode, the bot only logs the chat ids that write to it |
| `REMINDER_EVENING_TIME` | `20:00` | The evening-before reminder, `HH:MM` or `off` |
| `REMINDER_LEAD_MINUTES` | `60` | The second reminder, this many minutes before the start; `0` is off, at most 1440 |
| `WEEKLY_SUMMARY_DAY`, `WEEKLY_SUMMARY_TIME` | `sun`, `18:00` | A day (`mon` … `sun`, or the full name) and `HH:MM`, or the time `off` |
| `GOOGLE_API_BASE_URL`, `GOOGLE_TOKEN_URL`, `TELEGRAM_BASE_URL` | the real ones | For a proxy or a test; the tests point them at fakes |

Empty values mean the defaults (compose passes `${VAR:-}`). Secrets for intervals.icu, Strava,
Google, Telegram and the LLM endpoint live in the server environment only, never in the repo; a
configuration error names the variable and never prints its value.

### Making a device token

```bash
openssl rand -base64 36        # 48 characters; put it in .env as DEVICE_TOKENS=tablet:<that>
```

Type the same string into the tablet's Settings, Sync. Do not paste it into a chat, an issue or a
commit; to revoke a device, remove its entry and restart. The server compares tokens in constant
time, writes the device's **name** (not the token) to its logs and to `rides.uploaded_by`, and
redacts the `authorization` header wherever it logs a request.

## The API (`/v1`)

Every request needs `Authorization: Bearer <token>` (401 otherwise, before the body is read). All
answers are JSON; success is `{ "rideId", "status": "stored" | "already-stored" }`
(`contracts/schema/sync-upload-response.schema.json`) and failure is
`{ "error": { "code", "message" } }` (`contracts/schema/api-error.schema.json`; `code` is stable, the
message is for people). A ride is **uploaded** only when both requests below have succeeded;
each is idempotent and keyed by the ride id (`ride.id` in the record: `ride-<start millis>`; in
general 1 to 128 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit).

| Request | Body | Answers |
|---|---|---|
| `PUT /v1/rides/{rideId}` | The ride record, `application/json` (spec §8.2), at most 16 MiB | `201` stored · `200` the identical record was stored before, nothing changed · `409 ride_conflict` another record is stored under this id · `409 start_time_conflict` the same start time is stored under another id (the message names it) · `422 invalid_record` schema or structure (samples 1, 2, 3, …; laps tile the ride; duration = sample count) · `422 ride_id_mismatch` the path and `ride.id` differ · `400 invalid_json` · `400 invalid_ride_id` · `413` · `415` |
| `PUT /v1/rides/{rideId}/fit` | The FIT file, `application/vnd.ant.fit`, at most 8 MiB | `201` stored · `200` the identical file was stored before · `404 ride_not_found` the record is not stored yet · `409 fit_conflict` another file is stored · `422 invalid_fit` · `400 invalid_ride_id` · `413` · `415` |

| `GET /v1/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD[&refresh=true]` | none | `200` the planned workouts of those days in the native format (`workouts-response.schema.json`) · `400 invalid_range` a missing, malformed or reversed range, or more than 62 days |
| `GET /v1/profile` | none | `200` the rider's profile (`profile.schema.json`): FTP or null, the six zone bounds (Coggan's by default), minutes per weekday, goals, the physics constants of the ride engine, `updatedAt` |
| `PATCH /v1/profile` | A partial update, `application/json` or `application/merge-patch+json` (`profile-patch.schema.json`) | `200` the whole profile after the change · `422 invalid_profile` the change does not make a valid profile (nothing changed) · `400 invalid_json` · `415` |
| `GET /v1/metrics?from=YYYY-MM-DD&to=YYYY-MM-DD` | none | `200` the training metrics of those local days, at most 366 (`metrics-response.schema.json`, ADR 0022): `daily` load, fitness `ctl`, fatigue `atl` and form `tsb`; `weekly` loads; the power curve at 25 durations for the range and for all time; the FTP with its pending suggestion, history and weekly estimates; the aerobic drift of steady rides. `generatedAt` and `stale` · `400 invalid_range` a missing, malformed or reversed range, or more than 366 days |
| `GET /v1/rides[?before=<UTC instant>][&limit=n]` | none | `200` the rider's rides, newest first, by cursor (`rides-response.schema.json`): `limit` 1 to 100 (default 50), `nextBefore` is the next page's `before`; activities from the watch join the list when the wellness part registers its source · `400 invalid_query` a malformed `before` or `limit` |
| `GET /v1/rides/{rideId}[?series=true]` | none | `200` one ride (`ride-detail-response.schema.json`): summary, laps with the plan's targets, seconds in power zones, the plan and how it went, CP and W′ used; `series=true` adds the series every 10 s with the W′ balance · `404 ride_not_found` · `400 invalid_ride_id`, `invalid_query` |
| `GET /v1/rides/{rideId}/metrics[?wbal=true[&series=true][&cp=watts][&wPrimeKj=kJ]]` | none | `200` the ride's load, power curve and aerobic drift (and why there is none), and with `wbal=true` the W′ balance (`ride-metrics-response.schema.json`) · `404 ride_not_found` · `400 invalid_query` |
| `POST /v1/ftp-suggestion/dismiss` | `{ "suggestionId": n }` | `200` `{ suggestionId, status }` (`dismissed`, or `accepted`/`superseded` when it had left that way) · `404 not_found` no such suggestion · `400 invalid_json`. Accepting a suggestion is `PATCH /v1/profile` with its value |

| `POST /v1/wellness` | A batch of Health Connect records, `application/json` (`wellness-upload.schema.json`, ADR 0023): `{ source: "healthconnect", device?, records }`, each record typed by `kind` (`restingHeartRate`, `heartRateSamples`, `sleepSession`, `respiratoryRate`, `oxygenSaturation`, `steps`, `exerciseSession`, `heartRateVariabilityRmssd`, `weight`); at most 2000 records and 4 MiB | `200` `{ accepted, duplicates, rejected, byType, generatedAt }`: counts only (`wellness-upload-response.schema.json`); idempotent by record id, a newer `lastModified` replaces · `422 invalid_record` one bad record refuses the whole batch, the message names its index and field and never the value · `413 payload_too_large` · `400 invalid_json` (without quoting the body) · `415` |
| `GET /v1/wellness?from=YYYY-MM-DD&to=YYYY-MM-DD[&refresh=true]` | none | `200` the daily figures from `from` to `to` (at most 186 days) with each metric's value, source, usual range, deviation and streak, the usual bedtime and wake-up, `nightsLogged`, `stale` (`wellness-response.schema.json`) · `400 invalid_range` |
| `GET /v1/recovery[?refresh=true]` | none | `200` today's state `ready`, `caution`, `rest`, `learning` or `noData`, the rule that decided, a one-sentence reason, guidance, 3 or 4 facts, `nightsLogged` of `nightsNeeded` (`recovery-response.schema.json`) |
| `GET /v1/activities/{id}` | none | `200` a workout from the watch, `watch-<record id>`, or an outdoor ride imported from a FIT file, `fit-<16 hex>` (with `distanceMeters` and `elevationGainMeters` when the file says them): summary, heart-rate load (`loadApproximate`, `loadNote`), how dense the samples are, the heart rate in at most 300 points (`activity.schema.json`) · `404 not_found` |
| `POST /v1/activities/fit` | An outdoor ride's FIT file, `application/vnd.ant.fit`, at most 16 MiB (ADR 0026) | `201` `imported` / `200` `duplicate` with the id of what it duplicates and why (`activity-import-response.schema.json`): the ride's summary and heart-rate load · `422 invalid_fit` (framing, CRC, structure, not an activity file) · `422 unsupported_sport` (not cycling) · `422 empty_activity` · `413` · `415` |
| `GET /v1/repeats` | none | `200` the workouts ridden more than once, most recently ridden first, each with its latest attempt against the previous (`repeats-response.schema.json`, ADR 0026) |
| `GET /v1/repeats/{hash}` | none | `200` every attempt at one session side by side: heart rate at the same power per fixed-power step and overall, aerobic drift, compliance, and the trend of each, with the minimum attempts stated (`repeat-detail-response.schema.json`) · `404 not_found` a hash that is not a session ridden twice |
| `GET /v1/repeats/{hash}/previous[?before=<rideId>]` | none | `200` the attempt before a ride with its step boundaries and per-step heart rate, for the tablet's overlay (`repeat-previous-response.schema.json`); `previous` is null when there is none · `404 ride_not_found` an unknown `before` that does not carry its start · `400 invalid_ride_id` |

| `GET /v1/insights?from=YYYY-MM-DD&to=YYYY-MM-DD` | none | `200` the written notes created on those local days, at most 366, newest first, not cached (`insights-response.schema.json`, ADR 0025): `{ id, kind, createdAt, text, rideId, topic }` with `kind` `morning`, `ride`, `week`, `deviation` or `drift`, and, on a ride note, its discussion topic (`discussion-topic.schema.json`) · `400 invalid_range` |
| `GET /v1/routes` | none | `200` the routes the agent created, newest first, without points, not cached (`routes-response.schema.json`, ADR 0036): `{ id, name, source, distanceMeters, ascentMeters, hasCoordinates, createdAt }` |
| `GET /v1/routes/{id}` | none | `200` one route with its points `[{ d, e, lat?, lon? }]` (`route.schema.json`), `Cache-Control: private, max-age=300`; **no grade**: the tablet computes it · `404 not_found` an id that is not a route |
| `GET /v1/notebook` | none | `200` the coach notebook, read-only, not cached (`notebook-response.schema.json`, ADR 0035): the seven fixed sections with their text, version, time and reason, the caps and the total. **Only the coach scope; the game master's notebook is never served** |
| `POST /v1/plan-events` | `{ "kind": "illness" or "travel", "until": "YYYY-MM-DD"? }` (`plan-event-declaration.schema.json`) | `201` the plan event (`plan-event.schema.json`) / `200` when the same declaration was made before · `400 invalid_json` a body that is not a declaration, or an `until` before today |

Also `401 unauthorized` everywhere, `404 not_found` for any other path or method, and `500
internal_error` (the tablet retries those). "The same" means the same content: a record is compared
by the SHA-256 of its canonical JSON (keys sorted, no whitespace), a file by the SHA-256 of its bytes. A stored
ride is immutable. The FIT of a ride from the tablet is checked for framing only (12- or 14-byte header, `.FIT`, data size
equal to the body, header and file CRC-16), never parsed; chained FIT files are refused. The FIT of an outdoor ride
(`POST /v1/activities/fit`) is decoded as well (see below).

## Database

PostgreSQL through one small `Db` interface (`src/db.ts`): `pg` in production, PGlite in the tests,
so the same SQL runs on both. Schema changes are numbered files in `migrations/` (`0001_rides.sql`, …),
applied at start, each in its own transaction under an advisory lock, and recorded with a checksum in
`schema_migrations`; an applied file must never be edited, add a new one. No ORM.

`rides` holds the record (jsonb) and the FIT file (bytea) of each ride, unique by id and by start
time (spec §5.5); `ride_destinations` is the queue for the integrations that come after intake
(ADR 0014 section 5): a row per ride and destination, written in the transaction that stores the
FIT, drained by the upload worker below. `planned_workouts` is the plan cache and `plan_sync_state`
says how the last refreshes went (migration 0002, ADR 0016). Migration 0003 (ADR 0018) adds `profile`
(one row), `ride_workouts` (the workout each ride was played from, kept when the ride is stored) and the
OAuth tables `oauth_clients`, `oauth_codes` and `oauth_tokens`, in which codes, tokens and client secrets
are only ever digests. `calendar_events`, `plan_history`, `bot_notifications` and `bot_state` belong to the
calendar mirror and the bot (migration 0004, ADR 0019).
Migration 0005 (ADR 0020) adds `strava_auth` (the one rotated Strava refresh token) and
`strava_uploads` (the Strava upload each ride made, so a ride is never posted twice).
Migration 0006 (ADR 0022) adds `ride_metrics` (what is worked out once per ride: load, power curve, aerobic drift; computed when
the ride's FIT arrives and, for rides stored earlier or when the formulas change, on the next read), `ftp_suggestions` (each FTP
suggestion with its status: pending, accepted, dismissed, superseded) and `ftp_history` (every change of the profile's FTP).

Migration 0007 (ADR 0023) adds `wellness_records` (the raw Health Connect records, compact jsonb, unique by source and record id),
`wellness_daily` (the daily figures per day, metric and source, the value and where it came from), `wellness_sync_state` (how the pulls
of intervals.icu's wellness went), `activities` (the watch's workouts with their heart-rate samples and how dense they are),
`activity_destinations` (the queue that forwards them to intervals.icu) and `ride_heart_rate` (what each ride says about the rider's
heart rates, for the profile's estimates). **Health data: nothing in them is ever logged.**

Migration 0010 (ADR 0035) adds `notebook_versions` (every edit of a section of a notebook is a new row, never updated or deleted: the
coach notebook and the game master's, told apart by `scope`), `plan_events` (the queue of facts that tell the coach agent the plan may need
rebuilding, idempotent by `natural_key`) and `plan_event_cursor` (how far the end-of-day check for missed sessions has got). **Private: the
notebook is what the agent knows about the rider; nothing in these tables is ever logged.**

Migration 0011 (ADR 0036) adds `routes` (a route's points as compact JSON), `ride_routes` (the route a stored ride followed, with its route key),
`planned_workout_extras` (what the service keeps about a planned workout that intervals.icu cannot hold: the easier variant and which route
backs which free step) and `variant_rides` (the queue that changes an intervals.icu event to the variant that was ridden).

Tests run on PGlite and need nothing installed. To run them on a real PostgreSQL as well (CI does),
set `TEST_DATABASE_URL`: **the `public` schema of that database is dropped**, so point it at a scratch one.

## intervals.icu (ADR 0016)

With `INTERVALS_API_KEY` set, two loops start once the schema is migrated and the port is open (`app.services.start()` in
`main.ts`); without it neither runs and the service says so in its log.

- **Upload worker** (`src/intervals/uploads.ts`). Taking a ride's FIT queues a `ride_destinations` row for `intervals.icu` in
  the same transaction (whether or not a key is set, so a key added later also sends the rides that came before it).
  The worker takes rows that are due (on the `ride-stored` event, every 30 s, and at start), uploads the stored FIT
  (`POST /api/v1/athlete/{id}/activities`, the ride id as `external_id`), and records the outcome: `done` with the
  activity's id, or an activity that intervals.icu already had (counted as done); `pending` again after 1, 2, 4 … minutes
  up to 6 hours for no answer, 429 and 5xx; `failed` when intervals.icu refuses the file, or after 20 failed tries; a wrong
  key or athlete id waits at 6 hours and is never given up, and a restart tries everything again at once. The tablet's
  request never waits for any of it. The activity is paired with that day's planned workout when the ride's `workoutId`
  names one, or exactly one cycling workout is planned that day.
- **Plan refresher** (`src/plan/refresher.ts`). Reads today and the next 14 days of the calendar (`category=WORKOUT`) at
  start and every `PLAN_REFRESH_MINUTES`, converts each event to the native workout, and makes the `planned_workouts` cache
  say exactly that (changes are upserted, vanished events deleted, past days dropped). A workout the native format cannot
  hold is not served: the log says why once (`planned workout skipped: not supported on the tablet`) and the row keeps
  `unsupported_reason`. If intervals.icu cannot be reached the cache keeps serving and the answer says `stale: true`.

Looking at the queue, and retrying a ride that was given up once the cause is fixed:

```sql
SELECT ride_id, state, attempts, last_error, external_id, next_attempt_at FROM ride_destinations;
UPDATE ride_destinations SET state = 'pending', attempts = 0, next_attempt_at = NULL
 WHERE ride_id = '<id>' AND destination = 'intervals.icu';
```

The tests never touch the real intervals.icu: `test/helpers/fake-intervals.ts` is a fake of it (a Node HTTP server on a free
port; every payload in it and in `test/fixtures/intervals/` is invented). What it assumes about the real API, and what only an
account can confirm, is listed in ADR 0016.

## Wellness, recovery and the watch's activities (ADR 0023)

The companion app on the phone uploads the owner's Health Connect records (`POST /v1/wellness`); the service keeps them raw, works the
daily figures out (a night belongs to the morning it ends, in `TIMEZONE`), keeps a personal baseline per metric (mean and standard
deviation over 42 days, a spread never below a floor) and rates each morning `ready`, `caution`, `rest`, `learning` (fewer than 14
nights) or `noData` from an **explicit rule table** that names the rule that fired. Everything is deterministic code: no model is called.

| Piece | Where |
|---|---|
| Rule table, thresholds, reasons | `src/wellness/recovery.ts` (the table in its header comment; every row has a test), `src/wellness/metrics.ts` (directions, floors, plausible ranges) |
| Baselines and streaks | `src/wellness/baseline.ts`, `src/wellness/series.ts` |
| Daily figures from records | `src/wellness/derive.ts` (night, bedtime, density rule for heart rate in sleep, weight) |
| Sources | `src/wellness/sources.ts`: `HealthConnectUpload` (push), `IntervalsWellnessSource` (pull, every `WELLNESS_PULL_HOURS` and on demand), `WELLNESS_SOURCE_ORDER` |
| Heart-rate load (TRIMP, 100 = one hour at threshold) | `src/wellness/trimp.ts`, `src/wellness/activities.ts` |
| Forwarding the watch's workouts to intervals.icu | `src/wellness/forward.ts` (queue `activity_destinations`, off without a key, nothing to Strava) |
| Estimates of maximum and threshold heart rate from rides | `src/wellness/heart-rate-index.ts` (the profile's read-only `estimated*HeartRate`) |

The watch's load is registered with the metrics service as its `other` load source and its activities as a source of `GET /v1/rides`
(`createWellness` does it, given `services.metrics`); a watch session that overlaps a ride from the tablet is the same ride and counts once.
Failures and logs never name a measured value (`test/wellness-privacy.test.ts`). Without an intervals.icu key the upload and everything
computed from it work; the pull and the forwarding are off. What it assumes about intervals.icu's wellness and `POST activities/manual`
could not be checked without an account (ADR 0023, last section).

## Repeat sessions and outdoor rides (ADR 0026)

**Repeats** (spec §11.7). Two rides are the same session when the workouts they were played from have the same *structure*: `workoutHash` in
`contracts/src/repeats.ts` is the first 16 hex of the SHA-256 of the steps with repeats expanded (`s600@880`, `r300@450-700`, `f300`: type, seconds,
targets in thousandths of FTP; no name, label or cadence). The hash is stored with each ride's workout copy (`ride_workouts.workout_hash`) and worked
out, idempotently, for the rides stored before it. The heart rate at the same power of each steady step, the compliance and the step boundaries are
worked out once per ride (`ride_repeat_stats`); the comparison and the trend are pure (`src/repeats/compare.ts`).

| Piece | Where |
|---|---|
| The rules of "heart rate at the same power" (3% of the target, not paused, after the first 60 s of each stretch, at least 120 s per step) | `src/repeats/figures.ts` (its header states them; every rule has a test) |
| Overall figure over the compared steps, change of two figures, least-squares trend (at least 3 attempts) | `src/repeats/compare.ts` |
| Hashes, per-ride figures | `src/repeats/store.ts`; the service `src/repeats/service.ts` (`services.repeats`: `list`, `detail`, `previous`, `repeatOf`); the routes `src/repeats/routes.ts` |
| MCP | `src/mcp/tools/repeats.ts` (`list_repeats`, `get_repeat`) and `repeatOf` in `get_ride` |

**Outdoor rides** (spec §11.8). `services.outdoor.importFit(bytes, device)` is the one import function: the HTTP route `POST /v1/activities/fit` and
the Telegram bot's document handler both call it. `src/fit.ts` checks the framing and CRCs, **`src/fit-decode.ts` is our own decoder** (the messages an
outdoor ride needs; developer fields skipped; Garmin's SDK is not used because its licence forbids redistribution, ADR 0026), `src/outdoor/fit-ride.ts`
reads the cycling ride out of a decoded file, `src/outdoor/store.ts` stores it and applies the duplicate rule, `src/outdoor/service.ts` ties them together.
The ride is a row of `activities` (`source = 'fit'`, `type = 'cycling-outdoor'`) with the heart rate thinned to 5 s and the raw bytes; its **load is
heart-rate load of the `other` kind** (the watch's TRIMP, `src/wellness/trimp.ts`): it feeds fatigue, form and recovery and never the power curve, FTP
or drift. The forwarder of the watch's workouts (`src/wellness/forward.ts`) sends the file as it is to intervals.icu (off without a key; nothing to
Strava). A duplicate is the same bytes, the same start (±60 s) and length (±2%) as any stored ride, imported ride or watch session, or an overlap of half
of the file or more with a ride, a watch session or another imported ride. Tests write their FIT files with a **test-side encoder**
(`test/helpers/fit-encoder.ts`); every number in them is invented. Logs and error messages never name a measured value (`test/outdoor-privacy.test.ts`).

## Written insights, alerts and discussion topics (ADR 0025)

The service writes a morning note, a note after each ride, a weekly review, alerts and discussion topics from **facts it computes**; a
language model, when one is configured, only chooses the words. **It never calculates**: every number a note may quote is in the facts
(`quotable`, with a unit), and the text is checked after it is written. A number, date or time that was not supplied, or a word of the deny
list (no diagnosis, no medical condition), rejects the text; the model is asked once more with the problem named; a second rejection or any
failure of the endpoint stores the deterministic template's text (`source: "template"`), so a note is never missing. With no model set, every
note is the template's and **no health data leaves the server**.

| Piece | Where |
|---|---|
| Facts builders (pure, golden-tested in `test/fixtures/insights-facts-*.json`) | `src/insights/facts.ts`, `quotes.ts`, `planned.ts`; what they read: `gather.ts` |
| The check on a text | `src/insights/validator.ts`, `deny.ts` |
| The model client and the writer | `src/insights/llm.ts` (OpenAI-compatible chat completions, retries, no redirects, nothing sensitive logged), `writer.ts` |
| Prompts, versioned (stored as `prompt_version`) | `src/insights/prompts/` (`system.ts` and one file per kind) |
| Template text; the readings of the deviation alerts | `src/insights/template.ts`, `readings.ts` |
| Alert thresholds; the topic rule table | `src/insights/alerts.ts`, `topics.ts` (the table has named, unavailable rows for later stages) |
| Triggers, idempotence, the Telegram source | `src/insights/service.ts` (one pass a minute and on events; `UNIQUE (kind, key)` in `insights`), `store.ts` |
| MCP tools | `src/mcp/tools/insights.ts`: `list_insights`, `get_facts`, `list_discussion_topics`, `mark_discussion_topic` |

The Telegram bot sends what the service stores, through its exactly-once `bot_notifications` and to the owner's chat only (a ride note with
a topic ends with the opening question in a monospace block to copy). The tests use a **fake OpenAI-compatible server**
(`test/helpers/fake-llm.ts`: well-behaved, a wrong number, medical wording, a hang, a 500, garbage) and the fake Telegram; they prove the
check, the retry and the fallback, the triggers across a restart, the thresholds as tables, the topic limits and that no log line holds a
value, a sentence, the key or the address. What only a real endpoint settles is in ADR 0025.

## Coach notebook and plan events (ADR 0035)

The coach agent's memory and the queue that tells it the plan needs rebuilding. The agent works through MCP; the rider reads the notebook
in the companion and can declare illness or travel; the service itself only keeps, detects and queues.

| Piece | Where |
|---|---|
| The notebooks' sections (coach, game master) | `src/notebook/sections.ts` (the coach's are `NOTEBOOK_SECTIONS` of the contracts) |
| Versioned storage, one writer at a time per notebook | `src/notebook/store.ts` (`notebook_versions`; `mutate` decides under an advisory lock) |
| The rules: caps, the condense answer, the methodology's reason, rollback, the announcement | `src/notebook/service.ts` |
| `GET /v1/notebook` (coach scope only) | `src/notebook/routes.ts` |
| Plan events: emit, declare, resolve, the end-of-day check | `src/plan-events/service.ts`, `store.ts` (`plan_events`, `plan_event_cursor`), `routes.ts` (`POST /v1/plan-events`) |
| What moved or was deleted behind the service's back | `PlanStore.mirror` reports it (`moved`, `deleted`); the refresher hands it to `planEvents.notePlanChanges` |
| The rider's notes (methodology changed, event resolved), written by code | `src/notebook/service.ts` (`methodologyChangedText`), `src/plan-events/messages.ts`; both are `NotificationSource`s the bot sends |
| `/ill` and `/travel` | `src/telegram/commands.ts` |
| MCP tools | `src/mcp/tools/notebook.ts`: `get_notebook`, `update_notebook_section`, `append_notebook_log`, `list_notebook_versions`, `rollback_notebook_section`; `src/mcp/tools/plan-events.ts`: `list_plan_events`, `resolve_plan_event` |
| The game master's scope | `GAME_MASTER_SCOPE` in `src/oauth/routes.ts`; the MCP route builds the tools for the token's scopes (`createMcpServer(ctx, caller)`) |

The tools are built **per request, for the caller's token**: a session without the `game-master` scope has no `scope` argument on the
notebook tools and nothing in their descriptions or the server instructions mentions it. The tests (`test/notebook-service.test.ts`,
`notebook-mcp.test.ts`, `plan-events.test.ts`, `plan-events-mcp.test.ts`, `config-coach.test.ts`) run on PGlite with the fake intervals.icu
and Telegram and a clock the test moves.

## Routes with elevation and the easier variant (ADR 0036)

The agent makes routes and writes easier versions of sessions through MCP; the tablet reads both. The service keeps them, resamples and
smooths the routes and works out their numbers, and keeps the calendar honest when a variant is ridden.

| Piece | Where |
|---|---|
| Resampling, smoothing, distance and ascent; profiles; the limits | `src/routes/geometry.ts` |
| The GPX reader (own scanner, no XML dependency) | `src/routes/gpx.ts` |
| The BRouter client and the mapping of its errors | `src/routes/brouter.ts` |
| Storage, the service (`create`, `list`, `get`, `delete`, the ride's route key), the REST routes | `src/routes/store.ts`, `service.ts`, `rest.ts` (`routes`, `ride_routes`) |
| The variant and the route ids kept beside the calendar's primary; laid over the workout when the plan is read | `src/plan/extras.ts` (`planned_workout_extras`), `src/plan/store.ts` |
| What happens when a variant is ridden: the event update queue, the plan event, the sweep | `src/variants/service.ts` (`variant_rides`) |
| A ride's workout copy is the variant when it was ridden | `src/ride-workout.ts` |
| MCP tools | `src/mcp/tools/routes.ts`: `create_route`, `list_routes`, `delete_route`; `src/mcp/tools/plan.ts`: `route_id`, `variant`, `variantNote`, `removeVariant` |
| Route sessions in the repeat comparison | `src/repeats/` (`routeKey`, `ride_routes`) |
| The fact for the ride note, and Telegram's line | `src/insights/facts.ts` (`easierVersionRidden`), `src/telegram/messages.ts` |

The tests (`test/routes-geometry.test.ts` with its hand-computed goldens, `routes-gpx.test.ts`, `routes-brouter.test.ts` against
`test/helpers/fake-brouter.ts`, `routes-api.test.ts`, `workout-routes-variants.test.ts`, `variant-ridden.test.ts`, `repeats-routes.test.ts`,
`config-routes.test.ts`) run on PGlite with the fake intervals.icu and the fake BRouter, a clock the test moves and no network.

## Strava (ADR 0020)

With `STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET` and `STRAVA_REFRESH_TOKEN` set, each ride whose FIT arrives is posted to Strava as a
virtual ride, with its name and a short text description written by code from the ride record and its workout. **Strava is
write-only**: the service sends the token request, the upload, and the status of *its own* upload, and nothing else (no athlete,
activity, stream or segment is ever requested, the only scope is `activity:write`), and `test/strava-write-only.test.ts` fails if
that changes. Without the three variables the module does nothing and rides are not queued for it.

- **Upload worker** (`src/strava/uploads.ts`). Same queue as intervals.icu (`ride_destinations`, destination `strava`; the row is
  made in the transaction that stores the FIT, and `ride-stored` wakes the worker), one ride at a time. It posts the stored FIT
  (`POST /api/v3/uploads`: `data_type=fit`, `sport_type=VirtualRide`, `trainer=1`, `commute=0`, `external_id` the ride id, `name`,
  `description`), writes the upload id to `strava_uploads`, then checks `GET /api/v3/uploads/{id}` (4 checks 2 s apart, then waits
  of 30 s, 60 s, 2 min, 4 min; 20 checks at most) until Strava names the activity, which goes in `external_id`. A ride already
  posted is never posted again (the table says so, and Strava's "duplicate of activity N" counts as sent). 5xx and no answer back
  off 1, 2, 4 … minutes up to 6 hours and are given up after 20 tries; a refusal of the file is `failed` at once; a 429 waits for
  `Retry-After` or the end of Strava's 15-minute (or daily) window, and the service stops at a limit its headers report; none of
  these waits counts against the ride. A wrong scope, a refused token or a missing subscription (401, 403) is one logged error with
  the reason on every waiting ride, and no request until a restart or six hours.
- **Tokens** (`src/strava/auth.ts`). The access token is in memory only. Strava rotates the refresh token, so the newest is in
  `strava_auth` and the environment value only seeds the first run. If the environment value changes (you ran `strava-auth` again),
  it replaces the stored one at the next start; if the stored one is refused, run `strava-auth`, put the new line in `.env` and
  restart. Nothing prints a token: not the log, not an error, not the table.
- **History.** Rides stored before Strava was turned on are not sent. `STRAVA_BACKFILL=true` queues them at start (oldest first, paced
  by Strava's limits); set it back to `false` afterwards if you like.
- **`strava-auth`** (the owner's terminal, once): `pnpm --filter @overdrive/server run strava-auth` (in the image: `docker compose run
  --rm --no-deps -it server node dist/strava-auth.js`). It asks for the Client ID and Secret, prints the address to open, reads the
  address the browser ends on (the page does not load; copy it from the bar) and prints `STRAVA_REFRESH_TOKEN=…` to the terminal
  only. Steps with the Strava application: `docs/deploy.md` section 15.
- **Description** (`src/strava/description.ts`, pure): the session in one line, duration, average and normalised power, average
  heart rate, work, load, the plan against the ride, the FTP; no emoji, no claims. Free rides are named `Virtual ride <date>`.

Looking at the queue and retrying a ride that was given up once the cause is fixed (a ride whose upload is already in
`strava_uploads` is checked again, never posted again; to check one that was given up for taking too long, also set its `polls` to 0):

```sql
SELECT ride_id, state, attempts, last_error, external_id, next_attempt_at FROM ride_destinations WHERE destination = 'strava';
SELECT * FROM strava_uploads;
UPDATE ride_destinations SET state = 'pending', attempts = 0, next_attempt_at = NULL
 WHERE ride_id = '<id>' AND destination = 'strava';
```

The tests never touch the real Strava: `test/helpers/fake-strava.ts` is a fake of it (a Node HTTP server on a free port that records
every request it gets; everything in it is invented). What it assumes about the real API, and what only an account can confirm
(the subscription, how a duplicate is reported, the limits, whether a map shows), is listed in ADR 0020.

## MCP server (ADR 0018)

A remote MCP server for AI clients (Claude's apps and others), in this same service: Streamable HTTP at
`https://overdrive.llmto.day/mcp`, behind an OAuth 2.1 authorization server built for one owner. It is **off**
(its routes do not exist, and the log says `MCP server is off: … is not set`) until both `PUBLIC_BASE_URL` and
`OWNER_PASSPHRASE_HASH` are set.

**Turn it on.** Make the hash on any machine with the repository (it reads the passphrase without echoing it and
asks twice; use a few random words, at least 12 characters):

```bash
pnpm --filter @overdrive/server run hash-passphrase      # prints OWNER_PASSPHRASE_HASH=scrypt:32768:8:1:…
# on the host, from the image:  docker compose run --rm --no-deps server node dist/hash-passphrase.js
```

(pnpm adds a `$ node …` line above the result; the one that starts with `OWNER_PASSPHRASE_HASH=` is the one to copy.)

Copy the `OWNER_PASSPHRASE_HASH=…` line into `.env` (the hash contains colons and no dollar sign, so compose keeps it as it is),
set `PUBLIC_BASE_URL=https://overdrive.llmto.day`, and `docker compose up -d server`. The log then says `MCP server is on`. The
passphrase itself is never stored: remember it, it is what you type to approve each client. To change it, make a new hash.

**Connect a client.** Add a custom connector (a remote MCP server) in the client with the address
`https://overdrive.llmto.day/mcp`. The client finds the rest by itself (`/.well-known/oauth-protected-resource`,
`/.well-known/oauth-authorization-server`), registers (`POST /oauth/register`) and opens a page of ours in the browser:
**Connect to Overdrive**, naming the client and where the browser goes back to. Type the passphrase and press Approve. Five wrong
passphrases lock the page for 15 minutes (doubling each time, a day at most); tokens already issued keep working. Access tokens last an hour, the
client renews them with a refresh token that lasts 30 days from its last use and changes at every renewal.
A client that cannot do OAuth and can send a fixed header (`Authorization: Bearer …`) can use a token from `MCP_STATIC_TOKENS`
instead: `openssl rand -base64 36`, `MCP_STATIC_TOKENS=claude:<that>`, restart.

**The tools** (spec §8.4, Phase 2): `list_planned_workouts`, `create_workout`, `update_workout`, `delete_workout` (the calendar in
intervals.icu, through the same client the plan refresher uses, then the plan cache and the `plan-changed` event), `list_rides`,
`get_ride` (statistics, laps and compliance with the plan, power curve, aerobic drift and W′ balance, computed here from the stored record),
`get_fitness` (load, fitness, fatigue, form, best efforts, drift and the FTP with its suggestion, which is only a suggestion; ADR 0022),
`get_profile`, `update_profile` (also the maximum and threshold heart rate and the read-only estimates from the rides), and, from
stage P3-2 (ADR 0023): `get_wellness` (the daily figures, with the owner's own usual ranges and body weight and its trend),
`get_recovery` (today's state, the rule that decided, the facts), `list_activities` and `get_activity` (the tablet's rides and the
watch's workouts, each with a `type` and a `source`). `list_rides` and `get_ride` remain as deprecated aliases for one release. From stage A2a
(ADR 0035): the coach notebook (`get_notebook`, `update_notebook_section`, `append_notebook_log`, `list_notebook_versions`,
`rollback_notebook_section`) and the plan events (`list_plan_events`, `resolve_plan_event`).
Results are compact JSON, failures are tool errors in plain words (`plan source not configured: …`, `workout does not validate: /steps/0/duration must
be integer`); without `INTERVALS_API_KEY` the plan tools say so and the rest work.

**Revoking.** A client revokes its own grant (`POST /oauth/revoke`). To end every grant at once:

```sql
DELETE FROM oauth_tokens;               -- every client must ask for approval again
-- DELETE FROM oauth_clients;           -- and forget the clients too
```

(run with `docker compose exec db psql -U overdrive -d overdrive`). A static token is revoked by removing it from `.env` and
`docker compose up -d server`. Changing `OWNER_PASSPHRASE_HASH` does not end grants already issued.

**Seeing what happens.** The log names the client at registration, approval and denial, a wrong passphrase (never its value), a code
used twice and a refresh token reused (a family revoked), and each write tool (`workout planned through the MCP`, …). Tokens,
codes, secrets, the passphrase and its hash are never logged.
## Google Calendar and Telegram (ADR 0019)

Both start with `app.services.start()` and are off (null, one log line) without their settings.

- **Calendar mirror** (`src/gcal/`). A pass compares the plan cache (today onwards, workouts the tablet can play) with the
  `calendar_events` table and creates, updates or deletes events in the dedicated calendar: title the workout's name, start at
  `GCAL_WORKOUT_START` in `TIMEZONE`, length the workout's, the steps in plain words as the description (by code, `src/plan/workout-summary.ts`).
  The event id is derived from the planned id, so a repeated create is a `409` and a patch, never a duplicate. Passes run on
  `plan-changed`, every minute and at start, when the start-up sweep also deletes our events whose workout is gone (it waits until
  the plan has been read once). Retries and their waits (1, 2, 4 … minutes to 6 hours, 20 tries) are in the table; wrong credentials
  stop it for six hours with one error line; a day that has passed is left alone. Google Calendar is never read back.
  `calendar_events`: `state`, `attempts`, `last_error`, `next_attempt_at` say how it is going.
- **`google-auth`** (`src/gcal/auth-cli.ts`). `pnpm --filter @overdrive/server run google-auth`: the OAuth loopback flow with PKCE
  in the owner's terminal; prints `GOOGLE_REFRESH_TOKEN=…` and writes no file. Steps: `docs/deploy.md`, section 14.
- **Bot** (`src/telegram/`). Long polling (`getUpdates`), the next offset kept in `bot_state`; answers **only**
  `TELEGRAM_OWNER_CHAT_ID`, logs any other chat's id and never its content. Commands `/today`, `/week`, `/skip` and `/move` (buttons,
  confirmation before a skip; they write through `PlanStore` and the intervals.icu client, and say so plainly when there is no plan
  source), `/ill` and `/travel` (tell the coach you are ill or away, ADR 0035), `/help`. By itself: reminders (the evening before, and `REMINDER_LEAD_MINUTES` before the start), a summary after each
  stored ride, a weekly summary. Each is claimed in `bot_notifications` before it is sent and marked after, so a restart neither
  repeats nor loses one. Numbers are computed by code; no model is involved. `plan_history` keeps the planned workouts after the
  plan cache has dropped their day, for the weekly "planned against done". Steps: `docs/deploy.md`, section 13.

Looking at what they hold:

```sql
SELECT planned_id, google_event_id, state, attempts, last_error, next_attempt_at FROM calendar_events;
SELECT kind, key, state, attempts, last_error, sent_at FROM bot_notifications ORDER BY created_at DESC LIMIT 20;
```

The tests never touch Google or Telegram: `test/helpers/fake-google.ts` and `fake-telegram.ts` are fakes (Node HTTP servers on free
ports; everything in them is invented), and `test/helpers/integrations.ts` builds the whole service against them with a clock and
timers the test moves. What the real services must still confirm is listed in ADR 0019.

## Modules (for the next ones)

Other modules (the MCP tools, Telegram, Google Calendar, Strava) plug in without touching these; they get what they need from
`app.services` (`src/services.ts`):

| What | Where | Use |
|---|---|---|
| Events | `src/events.ts` | `services.bus.on("ride-stored", …)` (a ride with its record and FIT stored) and `"plan-changed"` (ids added, updated, removed). Listeners may be async; one that throws is logged and does not affect the emitter |
| Plan store | `src/plan/store.ts` | `list`, `get`, `entries` (also the ones that cannot be played), `upsert`, `remove`, `mirror`; any write that changes something emits `plan-changed` |
| Refresh now | `services.refresher?.refresh()` | after a write to the calendar; never throws, joins a refresh already running |
| intervals.icu | `services.intervals` | `createEvent`, `updateEvent`, `deleteEvent`, `listEvents`; `null` when the key is not set. `workoutToEventPayload(workout, date)` in `src/intervals/workout-convert.ts` makes the payload from a native workout |
| Calendar, bot | `services.calendar`, `services.telegram` | `null` when off. They only read the plan and the events; the commands' writes are `doSkip` and `doMove` in `src/telegram/commands.ts` (plan store plus intervals.icu client), to be moved onto a shared function if the MCP tools need the same |
| Destinations | `services.destinations` | the destinations a stored ride is queued for; a new one (Strava) adds its name and runs its own worker over `ride_destinations` like `src/intervals/uploads.ts` |
| Profile | `services.profile` (`src/profile/store.ts`) | `get()` the FTP, zones, availability, goals and physics; `update(patch)` (validated, atomic); a change emits `profile-changed` (`{ fields }`) |
| Rides | `services.rides` (`src/ride-store.ts`), `services.rideWorkouts` | `list`, `getRecord` of the stored rides; the workout a ride was played from, copied when it was stored. `src/ride-stats.ts` has the pure statistics, series and compliance (`rideStatistics`, `rideSeries`, `rideCompliance`) a ride summary for Telegram can reuse |
| Training metrics | `services.metrics` (`src/metrics/`, ADR 0022) | `metrics(from, to)`, `rideMetrics`, `rideList`, `rideDetail`, `ftpAdvice`, `dismissSuggestion`; `registerLoadSource({ name, entries(untilDate) })` feeds the daily load with other activities (`kind: "other"`: counts for fatigue and form, never for the power curve, eFTP or drift) and `registerActivitySource({ name, list({ beforeMillis, limit }) })` adds them to `GET /v1/rides`. The pure math (`power-curve.ts`, `load.ts`, `ftp.ts`, `drift.ts`, `wbal.ts`) knows nothing of the database. A listener on `ride-stored` computes a ride, one on `profile-changed` keeps the FTP history and marks a suggestion accepted |
| Repeats | `services.repeats` (`src/repeats/`, ADR 0026) | `list()`, `detail(hash)`, `previous(hash, before)`, `repeatOf(rideId)`, `ensureHashes()`; `ride_workouts.workout_hash` is set when a ride's workout copy is saved |
| Outdoor rides | `services.outdoor` (`src/outdoor/`, ADR 0026) | `importFit(bytes, device)` → `{ response, created }`, or throws `ImportError` (`invalid_fit`, `unsupported_sport`, `empty_activity`); stores an activity (`source = 'fit'`) and emits `activity-stored` |
| Wellness | `services.wellness` (`src/wellness/`, ADR 0023) | `upload.ingest(batch, device)`, `wellness(from, to)`, `recovery()`, `activity(id)`, `listActivities(query)`, `otherLoadByDay(from, to)` (the watch's load per local day), `refresh()` (pulls intervals.icu, `null` without a key); the `activity-stored` event queues a forwarding |
| Insights | `services.insights` (`src/insights/`, ADR 0025) | `list(from, to)` (the wire shape), `facts(request)`, `topics(statuses, limit)`, `markTopicDiscussed(id)`, `tick()`; `notifications` is a `NotificationSource` (`src/notifications.ts`) the bot sends; a stored note emits `insight-stored`, new wellness figures `wellness-updated`. The plan history (`createPlanHistory`, shared with the bot) keeps each planned workout with its length and planned load after the cache drops its day |
| Notebook | `services.notebook` (`src/notebook/`, ADR 0035) | `get(scope)`, `coachResponse()` (the wire shape, coach only), `update`, `append`, `rollback`, `versions`; a write emits `notebook-changed`; `notifications` announces a methodology change |
| Plan events | `services.planEvents` (`src/plan-events/`, ADR 0035) | `emit({ kind, day, workoutId, detail, naturalKey? })` (the easier variant of stage A2b calls it with kind `variant`), `declare(kind, until)`, `list`, `resolve`, `detectMissed()`, `notePlanChanges(diff)`; a new or resolved event emits `plan-event-changed`; `notifications` tells the rider of a resolution |
| Plan entries | `src/plan/entries.ts` (`readPlanEvent`), `src/plan/summary.ts` | one reading of a calendar event as a plan entry; duration, estimated load and a one-line outline of a native workout |

## Deployment

The runbook for the owner's host, with every variable the server reads: [`docs/deploy.md`](../docs/deploy.md).

`docker compose up -d --build` from the repository root builds `server/Dockerfile` (the build
context is the repo root) and starts PostgreSQL next to it; the server waits for the database,
migrates it and listens on `127.0.0.1:8080`. Set `POSTGRES_PASSWORD` (URL-safe: `openssl rand -hex 24`)
and `DEVICE_TOKENS` in `.env` (copy `.env.example`; the file is git-ignored). The existing Caddy
reverse proxy on the host fronts the service (ADR 0014 section 1):

```
overdrive.llmto.day {
    import common
    reverse_proxy 127.0.0.1:27180
}
```
