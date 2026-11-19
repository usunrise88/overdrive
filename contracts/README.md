# contracts

Wire formats shared by every Overdrive component. Kotlin and TypeScript do not share code; they share
these files and both test against them.

```
schema/      JSON Schemas (draft 2020-12). The source of truth.
fixtures/    Golden files. valid/ must be accepted, invalid/ must be rejected, by every implementation.
src/         @overdrive/contracts: the schema plus a validator for the TypeScript side.
```

## Formats

| Format | Schema | Spec |
|---|---|---|
| Native workout (schema 1) | `schema/workout.schema.json` | §8.1 |
| Ride record (schema 1) | `schema/ride-record.schema.json` | §8.2 |
| Upload response (`{rideId, status}`) | `schema/sync-upload-response.schema.json` | §8.3, ADR 0014 |
| API error (`{error: {code, message}}`) | `schema/api-error.schema.json` | §8.3, ADR 0014 |
| Planned workouts (`GET /v1/workouts`) | `schema/workouts-response.schema.json` | §5.1, §8.3, ADR 0016 |
| Rider's profile (`GET /v1/profile`) | `schema/profile.schema.json` | §8.3, ADR 0018 |
| Profile patch (`PATCH /v1/profile`) | `schema/profile-patch.schema.json` | §8.3, ADR 0018 |
| Training metrics (`GET /v1/metrics`) | `schema/metrics-response.schema.json` | §6.3, §8.3, ADR 0022 |
| One ride's metrics (`GET /v1/rides/{id}/metrics`) | `schema/ride-metrics-response.schema.json` | §6.3, §8.3, ADR 0022 |
| Ride list (`GET /v1/rides`) | `schema/rides-response.schema.json` | §6.7, §8.3, ADR 0022 |
| Dismissing an FTP suggestion (`POST /v1/ftp-suggestion/dismiss`) | `schema/ftp-suggestion-dismiss-request.schema.json`, `…-response.schema.json` | §6.3, §8.3, ADR 0022 |
| Ride detail (`GET /v1/rides/{id}`) | `schema/ride-detail-response.schema.json` | §8.3, ADR 0022 |
| Wellness upload (`POST /v1/wellness`) | `schema/wellness-upload.schema.json` | §6.2, §6.7, §12.5, §8.3, ADR 0023 |
| Wellness upload answer (counts only) | `schema/wellness-upload-response.schema.json` | §8.3, ADR 0023 |
| Daily wellness (`GET /v1/wellness`) | `schema/wellness-response.schema.json` | §6.4, §12.5, §8.3, ADR 0023 |
| Recovery (`GET /v1/recovery`) | `schema/recovery-response.schema.json` | §6.4, §8.3, ADR 0023 |
| Watch activity (`GET /v1/activities/{id}`) | `schema/activity.schema.json` | §6.7, §8.3, ADR 0023 |
| Written insights (`GET /v1/insights?from&to`) | `schema/insights-response.schema.json` | §6.5, §6.6, §8.3, ADR 0025 |
| Discussion topic (inside a ride note; MCP `list_discussion_topics`) | `schema/discussion-topic.schema.json` | §12.3, ADR 0025 |
| Repeated sessions (`GET /v1/repeats`) | `schema/repeats-response.schema.json` | §11.7, §8.3, ADR 0026 |
| One repeated session (`GET /v1/repeats/{hash}`) | `schema/repeat-detail-response.schema.json` | §11.7, §8.3, ADR 0026 |
| The attempt before a ride (`GET /v1/repeats/{hash}/previous`) | `schema/repeat-previous-response.schema.json` | §11.7, §8.3, ADR 0026 |
| Outdoor ride import answer (`POST /v1/activities/fit`) | `schema/activity-import-response.schema.json` | §11.8, §8.3, ADR 0026 |
| Coach notebook (`GET /v1/notebook`) | `schema/notebook-response.schema.json` | §11.6, §12.4, §8.3, ADR 0035 |
| Plan event (`POST /v1/plan-events` answer; MCP `list_plan_events`) | `schema/plan-event.schema.json` | §12.2, §8.3, ADR 0035 |
| Plan event declaration (`POST /v1/plan-events` body) | `schema/plan-event-declaration.schema.json` | §12.2, §8.3, ADR 0035 |
| Route with points (`GET /v1/routes/{id}`) | `schema/route.schema.json` | §11.4, §8.3, ADR 0036 |
| Routes (`GET /v1/routes`) | `schema/routes-response.schema.json` | §11.4, §8.3, ADR 0036 |
| Live coach: the workout's `live_coach`, `coach_brief` and the steps' `must_say` | `schema/workout.schema.json` (additive) | §12.1, §8.1, ADR 0038 |
| Live coach: start of a session (`POST /v1/live/sessions` body) and its answer | `schema/live-session-start.schema.json`, `…-response.schema.json` | §12.1, §8.3, ADR 0038 |
| Live coach: a snapshot of the ride (`POST /v1/live/sessions/{id}/snapshots` body, every 5 s) | `schema/live-snapshot.schema.json` | §12.1, §8.3, ADR 0038 |
| Live coach: a cue (`cue` event of `GET /v1/live/sessions/{id}/cues`, Server-Sent Events) and the `end` event | `schema/live-cue.schema.json`, `schema/live-stream-end.schema.json` | §12.1, §8.3, ADR 0038 |
| Live coach: end of a session (`POST …/end` body and answer), a cue played (`POST …/cues/{id}/played` body) | `schema/live-session-end.schema.json`, `…-end-response.schema.json`, `schema/live-cue-played.schema.json` | §12.1, §8.3, ADR 0038 |

The remaining sync API payloads and the MCP tool inputs (spec §8.3–§8.4) get schemas here as they are fixed during
implementation. The sync outbox of the ride app (ADR 0012) is a local journal, not a wire format, and has no schema.
What an upload sends is two requests (ADR 0014): `PUT /v1/rides/{rideId}` with the ride record as the body (its schema is
above) and `PUT /v1/rides/{rideId}/fit` with the FIT file's bytes (`application/vnd.ant.fit`, not JSON, so no schema; its
fixtures are below). The service answers with the upload response, or on any failure with the API error, whose `code` is a
closed list of stable names (each with its HTTP status in `API_ERROR_STATUS`).

`fixtures/sync/` holds the golden files of those two answers, `upload-response/{valid,invalid}` and
`api-error/{valid,invalid}`, run by `test/sync.test.ts` and, in Kotlin, by `SyncApiContractTest` (`SyncApiJson`, strict like
the schemas). The ride id syntax the service accepts in a path (`RIDE_ID_PATTERN`) is stated in the response schema; the ride
record's own schema only asks for a non-empty id.

`fixtures/plan/workouts-response/{valid,invalid}` hold the golden files of `GET /v1/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD`
(ADR 0016): the range (at most 62 days, both ends included), `generatedAt` (when the plan was last read from its source, or
null), `stale` (the last refresh failed: the list is the last known one), `configured` (false: no plan source, the list is
empty) and `workouts`, each `{ id, date, name, source, workout }` with the native workout inside. The schema refers to
`workout.schema.json` by file name; what a schema cannot say (real calendar dates, the range's length, unique ids, `workout.id`
equal to the entry's `id`, an unconfigured service being empty and not stale) is checked by `validateWorkoutsResponse` here and
by `PlanApiJson` in Kotlin (`PlanApiContractTest`), against the same fixtures. The request also takes `refresh=true` (read the
source first, wait at most ten seconds); that is a query parameter and has no schema. A malformed range is `400 invalid_range`.

`fixtures/profile/` holds the golden files of the rider's profile (ADR 0018): `profile/{valid,invalid}` for the profile itself (FTP 30 to
2000 or null, six strictly increasing zone bounds, minutes per weekday, at most ten goals, the physics constants under the names of
the ride engine's `PhysicsSettings`, every key always present); `patch/{valid,invalid}` each hold `{ before, patch, after }` (valid: applying
the merge-patch-like `patch` to `before` must give exactly `after`; invalid: it must be refused, either as a patch or as the profile it
would make, and `before` is untouched); and `zone-ranges.json`, the seven zones in watts for an FTP and its six bounds, with the zone of
every whole-watt power derived from them (Kotlin also checks Coggan's against `PowerZone.of`). `contracts/test/profile.test.ts`,
Kotlin's `ProfileContractTest` (`ProfileApiJson`, strict like the schemas) and the service's `profile.test.ts` (through HTTP) run the
same files. The new API error code `invalid_profile` (422) has its fixture under `sync/api-error/valid/`.

`fixtures/metrics/` holds the golden files of the answers of stage P3-1 (ADR 0022), each in `{valid,invalid}`, shaped by the data the
companion's screens need (`design/companion.md` §11): `metrics-response` (`GET /v1/metrics?from&to`, at most 366 days: `generatedAt` and
`stale`, one entry per local day with load, fitness `ctl`, fatigue `atl` and form `tsb`, one per ISO week with `rideLoad`, `otherLoad`,
`rides` and `activities`, the power curve at 25 durations for the range and for all time, the FTP with `setAt`, the pending suggestion,
`latestEstimate`, `history` and weekly `estimates`, and the aerobic drift of the steady rides), `ride-metrics-response`
(`GET /v1/rides/{id}/metrics`: the ride's power curve and drift, and with `wbal=true` the W′ balance, with `series=true` its series),
`rides-response` (`GET /v1/rides?before&limit`: rides and, later, watch activities, newest first, by cursor), `ride-detail-response`
(`GET /v1/rides/{id}`: summary, laps with the plan's targets, time in zones, the plan and how it went, W′ and, with `series=true`,
the series at 10 s) and `ftp-suggestion-dismiss-request/-response` (`POST /v1/ftp-suggestion/dismiss`). What a schema cannot say
(tsb equal to ctl minus atl, a day for every day of the range, weeks that start on a Monday, a power curve that does not rise with the
duration, `nextBefore` that follows from the page, laps that tile the ride, …) is in each schema's description and checked by
`validateMetricsResponse` & co. here and by `MetricsApiJson` in Kotlin (`MetricsContractTest`), against the same fixtures. The profile has
a new key, `wPrimeKj` (W′ in kJ, 1 to 100, or null for the default of 20 kJ), in the schema, the patch and every profile fixture; the API
error codes have a new one, `invalid_query` (400), for a malformed `limit`, `before`, `step` or `cp`. All the fixtures are synthetic.

`fixtures/wellness/` holds the golden files of the wellness part of the sync API (ADR 0023): `upload`, `upload-response`, `wellness`,
`recovery` and `activity`, each with `valid/` and `invalid/`. The upload is a batch of Health Connect records typed by `kind` (nine kinds,
`weight` included); `validateWellnessUpload` checks the envelope, then each record against the schema of its kind, then what a schema cannot
say (real instants, end not before start, unique ids), and its messages name `/records/<index>/<field>` and the rule, **never a value**
(`contracts/test/wellness.test.ts` searches them for the value it sent). The whole `wellness-upload.schema.json` with its `oneOf` rejects every
invalid fixture except a documented few that only the second step can see. The answers carry consistency rules a schema cannot state
(a recovery verdict has a rule, guidance and facts; a reading without a value has no source or streak; an activity that overlaps a ride has no
load): `validateWellnessUploadResponse`, `validateWellnessResponse`, `validateRecoveryResponse`, `validateActivity`, and in Kotlin
`WellnessApiJson` (`WellnessContractTest`), against the same files. The profile gained four heart-rate keys (`maxHeartRate`,
`thresholdHeartRate`, `estimatedMaxHeartRate`, `estimatedThresholdHeartRate`), **optional on read** so that a profile cached before they existed
stays valid, with `profile/valid/heart-rates.json` and the patch fixtures `heart-rate-*`.

`fixtures/insights/` holds the golden files of the written notes (stage P3-3, ADR 0025): `insights-response` (`GET /v1/insights?from&to`, at most
366 days of local days by the note's `createdAt`, newest first: `generatedAt`, `stale` which is always false, and `insights`, each `{ id, kind, createdAt,
text, rideId, topic }` with `kind` one of `morning`, `ride`, `week` (the notes of spec §6.5) and `deviation`, `drift` (the alerts of §6.6), `rideId` set
exactly for a ride note, and `topic` null or the discussion topic of that ride note) and `discussion-topic` (`{ id, text, openingQuestion, rideId,
createdAt, status }`, status `open`, `discussed` or `expired`; spec §12.3), each with `valid/` and `invalid/`. What a schema cannot state (real dates,
at most 366 days, unique ids, newest first, `rideId` set exactly for ride notes, a topic only on a ride note and naming the same ride) is in the schema's
description and checked by `validateInsightsResponse` and `validateDiscussionTopic` here and by `InsightsApiJson` in Kotlin (`InsightsContractTest`),
against the same files. Errors name a path and a rule, never the text of a note. All the text in the fixtures is synthetic.

`fixtures/repeats/` holds the golden files of the repeat-session comparison (stage P3-7, ADR 0026): `repeats-response`
(`GET /v1/repeats`: the workouts ridden more than once, each with its hash, name, attempts, first and last day, the latest and
the previous attempt and a `comparison` of heart rate at the same power, aerobic drift and compliance, latest minus previous),
`repeat-detail-response` (`GET /v1/repeats/{hash}`: every attempt side by side, with the fixed-power steps, the steps the overall
figure is taken over, each step's heart rate at the same power and its reason when there is none, and a trend per measure with the
minimum attempts stated) and `repeat-previous-response` (`GET /v1/repeats/{hash}/previous?before=`: the attempt before a ride with
its step boundaries and per-step heart rate, for the tablet's overlay), each with `valid/` and `invalid/`. `workout-hash.json` holds
the golden numbers of the **hash**: `workoutStructure` (the canonical text of a workout's steps with repeats expanded: type,
duration, targets in thousandths of FTP; no id, name, labels or cadence) and `workoutHash` (16 hex of SHA-256 over the structure
and a version line), the cases that must match (a renamed copy, other labels, cadence and float noise, a repeat written out step
by step) and those that must not. What a schema cannot state (real dates, attempts in order, a figure and its reason exclusive,
at least 120 counted seconds behind a step's figure, the trend's slope, change and direction agreeing, steps that do not
overlap) is in each schema's description and checked by `validateRepeatsResponse` & co. here and by `RepeatsApiJson` in Kotlin
(`RepeatsContractTest`), against the same files.

`fixtures/outdoor/` holds the golden files of the outdoor-ride import (stage P3-7, ADR 0026): `activity-import-response` (the
answer of `POST /v1/activities/fit`, which takes the FIT file's bytes as `application/vnd.ant.fit`, up to 16 MiB: `imported` with
the ride's summary and heart-rate load, or `duplicate` with the id of the ride, activity or watch session that is the same ride and
why), checked by `validateActivityImportResponse` here and `ActivityImportJson` in Kotlin (`ActivityImportContractTest`). The
watch activity's schema (`wellness/activity`) now also covers an imported ride: `source` `fit`, id `fit-<16 hex>`, type
`cycling-outdoor` and the optional `distanceMeters` and `elevationGainMeters`. The API error codes have two new ones,
`unsupported_sport` and `empty_activity` (422), for a FIT file that is valid but not a cycling ride; their fixtures are under
`sync/api-error/valid/`. All the fixtures are synthetic.

`fixtures/routes/` holds the golden files of stage A2b-1 (ADR 0036): routes with elevation (spec §11.4) and the easier variant (§11.5). `route/` (`GET /v1/routes/{id}`:
points `{ d, e, lat?, lon? }` resampled one every 20 m, at most 5000, distance and ascent computed from the points, `hasCoordinates` all or nothing,
source `gpx`, `waypoints` or `profile`; the rules a schema cannot state are in the schema's description and checked by `validateRoute`) and `routes-response/`
(`GET /v1/routes`: the routes without points, newest first, `validateRoutesResponse`). **Additive fields of existing contracts**, which the Kotlin models do not
read yet and whose fixtures therefore live here and not under `rides/valid`, `workouts/valid` and `plan/workouts-response` (those the Kotlin tests decode strictly,
and an unknown key fails them): `ride-record/` (the ride header's optional `routeId`, `routeName` and `variantRidden`), `workout/` (the optional `route_id` of a `free`
step) and `workouts-response/` (the optional `variant` of a planned workout: `steps`, `note`, `structureHash`). The stage that teaches Kotlin these fields moves
them next to the others. `route-key.json` holds the golden numbers of `routePointsHash` and `routeKey` (the route id and the hash of its points, which makes two
rides on the same route one session, §11.7). Everything is synthetic, with invented coordinates. Run by `test/routes.test.ts`.

`fixtures/live-coach/` holds the golden files of stage A3 (ADR 0038): the live coach (spec §12.1). The workout gains three optional fields,
**additive and not yet read by Kotlin** (its decoders reject unknown keys), so the fixtures that carry them live here and not in `workouts/`
or `plan/workouts-response/`, which Kotlin decodes: `live_coach` (boolean), `coach_brief` (at most 1000 characters, never shown on the
tablet) and, on a steady, ramp, free or heart-rate step (not a repeat), `must_say`: at most 3 lines `{ at: "start" | seconds, text }`, `text`
at most 80 characters (code points, like a cue), `at` a whole second from 1 and below the step's duration, no two at the same moment (checked
by `validateWorkout`, which says the path and never quotes a line). They are kept only by the service (never in intervals.icu or Google
Calendar), served on `GET /v1/workouts`, and are **not part of a workout's structure**: the hash ignores them, and the copy of the workout a
ride keeps has none (`withoutLiveCoach`). The tablet speaks to the service over plain `HttpURLConnection`: `session-start` and
`session-start-response` (`POST /v1/live/sessions`: ride id, workout id, structure hash, the app's clock; the answer says whether the coach
is on and, when not, why: `feature-off`, `no-tts`, `no-llm`, `no-workout`, `no-live-coach`, `structure-changed`), `snapshot` (`POST
…/snapshots` every 5 s: ride time, paused, muted, the step with its time left and target, means over the last 5 s of power, heart rate and
cadence, the share of the step's judged seconds on target, the stall guard, recent events; every field bounded), `cue` and `stream-end` (the
data of the `cue` and `end` events of the Server-Sent Events stream `GET …/cues`: the cue's id is its SSE id, `text` at most 80
characters, the speech **inline** as base64 with its format, `createdAt`, `expiresAt` and `ttlMs`), `session-end` and
`session-end-response` (`POST …/end`), `cue-played` (`POST …/cues/{id}/played`, optional). `stream/cues.sse.txt` is a stream as the service
writes it (a `retry:` line, comments, `id:`, `event:`, `data:`), parsed and checked by `test/live-coach.test.ts`; the audio in it is a few
synthetic bytes, no speech. No new API error code: a bad body is `400 invalid_json`, an unknown session or cue `404 not_found`. What a schema
cannot say (the time left inside the step, a target that fits the step's type, `ttlMs` equal to `expiresAt` minus `createdAt`, the audio's
size, the answer to a start being consistent) is checked by `validateLiveSnapshot` & co. here. All the fixtures are synthetic.

`fixtures/fit/` holds two FIT files for the upload's tests: `fit-golden-3s.fit`, the 376 bytes `FitWriter` writes for
`rides/valid/fit-golden-3s.json` (the worked example of `docs/protocols/fit.md`; Kotlin's `FitFixturesTest` regenerates it
and compares), and `minimal.fit`, the smallest file the service's framing check accepts (the 12-byte header, four bytes of
records, the file CRC), which holds no real messages. Both are synthetic.

`fixtures/rides/` holds the ride record's golden files: `valid/*.json` must be accepted and `invalid/*.json`
rejected by every implementation, including the structural rules a schema cannot state (samples run 1, 2, 3, …; laps
tile the ride; the summary's duration is the number of samples). Kotlin's `RideRecordContractTest` and
`test/ride-record.test.ts` check them. Four valid ones are generated by Kotlin (`minimal-free-ride`, `empty-ride`,
`fit-golden-3s`, the five-minute `synthetic-5min`); the test explains how to regenerate them. All are synthetic.

`fixtures/zwo/` holds golden files for the Zwift ZWO import (spec §4.5, §8.1): each `valid/*.zwo` has a
`*.expected.json` beside it, the native workout it must convert to, and every `invalid/*.zwo` must be
rejected. Kotlin's `ZwoFixturesTest` checks them; any other ZWO importer (the web service, later) should too.

`fixtures/notebook/` and `fixtures/plan-events/` hold the golden files of stage A2a (ADR 0035), each in `{valid,invalid}`: `notebook/notebook-response`
(`GET /v1/notebook`: the coach notebook's seven fixed sections, always all present and in order, each with its text, version, time
and reason, the server's caps in characters, and the scope, which is only ever `coach`: the game master's notebook is never served),
`plan-events/plan-event` (a plan event of any kind and either status, as `POST /v1/plan-events` answers and `list_plan_events` lists it)
and `plan-events/declaration` (the body of `POST /v1/plan-events`: `illness` or `travel` and an optional `until` date). What a schema
cannot say (the sections' order and titles, version 0 meaning never written, `totalChars`, `workoutId` null exactly for illness and
travel, an open event having no resolution, `resolvedAt` not before `createdAt`, real dates and instants) is in each schema's
description and checked by `validateNotebookResponse`, `validatePlanEvent` and `validatePlanEventDeclaration` here. The Kotlin side
is a later stage. A body that is not a declaration is `400 invalid_json` (no new error code).

`fixtures/ble/` holds raw Bluetooth session recordings in the text format of `docs/protocols/ftms-hr.md` §7.1 (spec §7.5,
ADR 0009 §6): a trainer and a strap file for each of two synthetic rides (`erg-workout`, `dropouts`). They are not a wire
format of ours and have no schema; they are **generated** by running a ride over the fake Bluetooth with the recorder on
(`BleSessionFixturesTest`, regenerate with `cd core && ./gradlew jvmTest -PregenerateBleFixtures=true`, then review the
diff), and the same test replays each pair through the real adapters and engine into the ride that produced it. They hold
synthetic bytes, no addresses and no real person's heart rate. Six more, `click-*.click.session.txt` (buttons, real-puck, dropout, locked,
unlock, silent), are sessions of a fake Zwift Click (`docs/protocols/click-v2.md`, ADR 0011) in the same format, generated and
replayed the same way (`ClickSessionFixturesTest`); the firmware revision is `*` and the handshake key is zeroed, as for any
recording. **Never add a real recording here:** the repository is public.

## Changing a format

1. Edit the schema and add fixtures: a valid one for anything newly allowed, an invalid one for
   anything newly forbidden.
2. `pnpm --filter @overdrive/contracts test` — the TypeScript side validates every fixture.
3. `cd core && ./gradlew jvmTest` — the Kotlin model must agree on every fixture
   (`WorkoutContractTest`). Update the model until it does.
4. Bump the `schema` version if existing files would stop validating.

Fixtures are excluded from Biome formatting so they can stay hand-laid-out.
