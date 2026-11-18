# server

The self-hosted web service (spec §7.2): sync API, MCP server, scheduler, integrations, Telegram
bot, insights. One deployable with internal modules. Today it has the ride intake (ADR 0014:
the tablet's rides arrive here and are stored), the PostgreSQL schema behind it, the intervals.icu
integration (ADR 0016: rides are forwarded, the planned workouts are read, converted, cached and
served to the tablet) and `GET /healthz`.

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
| `TIMEZONE` | `UTC` | The rider's IANA time zone (`Europe/Paris`): which day is "today" for the plan and which day a ride belongs to |

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

Also `401 unauthorized` everywhere, `404 not_found` for any other path or method, and `500
internal_error` (the tablet retries those). "The same" means the same content: a record is compared
by the SHA-256 of its canonical JSON (keys sorted, no whitespace), a file by the SHA-256 of its bytes. A stored
ride is immutable. The FIT is checked for framing only (12- or 14-byte header, `.FIT`, data size
equal to the body, header and file CRC-16), never parsed; chained FIT files are refused.

## Database

PostgreSQL through one small `Db` interface (`src/db.ts`): `pg` in production, PGlite in the tests,
so the same SQL runs on both. Schema changes are numbered files in `migrations/` (`0001_rides.sql`, …),
applied at start, each in its own transaction under an advisory lock, and recorded with a checksum in
`schema_migrations`; an applied file must never be edited, add a new one. No ORM.

`rides` holds the record (jsonb) and the FIT file (bytea) of each ride, unique by id and by start
time (spec §5.5); `ride_destinations` is the queue for the integrations that come after intake
(ADR 0014 section 5): a row per ride and destination, written in the transaction that stores the
FIT, drained by the upload worker below. `planned_workouts` is the plan cache and `plan_sync_state`
says how the last refreshes went (migration 0002, ADR 0016).

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

## Modules (for the next ones)

Other modules (the MCP tools, Telegram, Google Calendar, Strava) plug in without touching these; they get what they need from
`app.services` (`src/services.ts`):

| What | Where | Use |
|---|---|---|
| Events | `src/events.ts` | `services.bus.on("ride-stored", …)` (a ride with its record and FIT stored) and `"plan-changed"` (ids added, updated, removed). Listeners may be async; one that throws is logged and does not affect the emitter |
| Plan store | `src/plan/store.ts` | `list`, `get`, `entries` (also the ones that cannot be played), `upsert`, `remove`, `mirror`; any write that changes something emits `plan-changed` |
| Refresh now | `services.refresher?.refresh()` | after a write to the calendar; never throws, joins a refresh already running |
| intervals.icu | `services.intervals` | `createEvent`, `updateEvent`, `deleteEvent`, `listEvents`; `null` when the key is not set. `workoutToEventPayload(workout, date)` in `src/intervals/workout-convert.ts` makes the payload from a native workout |
| Destinations | `services.destinations` | the destinations a stored ride is queued for; a new one (Strava) adds its name and runs its own worker over `ride_destinations` like `src/intervals/uploads.ts` |

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
