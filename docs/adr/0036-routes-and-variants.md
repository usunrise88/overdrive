# ADR 0036: Routes with elevation and the easier variant, service side

Status: accepted (v0.4.0, stage A2b-1, server and contracts only, 2026-11-19).

## Context

Two additions of the owner's second spec belong to Phase 4 (spec §11.4, §11.5). **Routes with elevation**: an optional alternative to
the flat free ride; the agent supplies a route as a GPX file, as waypoints that the service turns into a road route, or as a synthetic
elevation profile; the tablet feeds its grade to the trainer's simulation mode. **The easier variant**: a planned workout may carry one
alternative written by the agent; it lives only in the Overdrive service, the rider chooses at the start of the ride, and if it is ridden
the intervals.icu event is updated so that planned and completed line up. The tablet's side of both (the choice at the start, the grade in
the simulation, positions in the FIT) is the next stage; the BRouter container is the other half of the stage
(`docs/deploy.md` section 20). Written without a real MCP client, account or ride: the tests use the SDK's client,
the fake intervals.icu of ADR 0016 and a fake BRouter. A real BRouter 1.7.10 run on a local machine checked the client once, by hand, with a short
route (section 3); no real map data or recording is in the repository.

## Decision

### 1. Contracts first

`contracts/schema/route.schema.json` (a route with its points) and `routes-response.schema.json` (the list), with `validateRoute` and
`validateRoutesResponse`. A point is `{ d, e, lat?, lon? }`: metres along the route, smoothed elevation in metres, and coordinates on **all
points or none**. `distanceMeters` and `ascentMeters` are computed by code from the points and the validator checks them (the last `d`
rounded; the sum of the rises within a metre); source `profile` has no coordinates, `gpx` and `waypoints` always have them. The service
sends **no grade**: the tablet works it out.

Additive fields of existing contracts, **all optional and absent unless there is something to say**, so what a tablet that does not know
them reads is unchanged until the agent first writes a route or a variant: `route_id` on a `free` step of `workout.schema.json`;
`routeId`, `routeName` and `variantRidden` in the ride record's `ride`; `variant { steps, note?, structureHash }` on an entry of
`GET /v1/workouts`. The Kotlin decoders are strict (an unknown key fails), so the fixtures of these additions are in
`contracts/fixtures/routes/` (`workout/`, `ride-record/`, `workouts-response/`) and **not** in the directories Kotlin decodes today;
the tablet stage moves them next to the others when its models learn the fields. Golden numbers for `routePointsHash`, `routeKey` and
`routeSessionHash` are in `contracts/fixtures/routes/route-key.json`, written by a script that shares no code with the functions.

### 2. The geometry (`server/src/routes/geometry.ts`)

- **Resampling.** One point every `ROUTE_STEP_METERS` (default **20 m**), by linear interpolation along the great-circle distance of the
  raw track (R = 6371008.8 m), consecutive points under a centimetre apart counted as one. At most **5000 points**: a route that would pass
  it gets the smallest whole-metre step that fits, `ceil(length / 4998)`, so 20 m holds up to 99.96 km, 21 m at 100 km and 41 m at the
  profile's longest 200 km. The tool's answer says `pointSpacingMeters` and a note when it is not the setting. A last gap under a metre is
  folded into the point before it, so rounding can never make two points equal.
- **Smoothing.** A moving average of the elevation over about **60 m** (3 points at 20 m; `smoothingWindow` keeps it odd and at least 3),
  centred, with the window shrinking symmetrically towards the ends, which are kept. Worked by hand in `routes-geometry.test.ts`
  (`[0,0,60,0,0,0,0]` becomes `[0,20,20,20,0,0,0]`; a 5% hill of 100 m up and 100 m down peaks at 104.3 m instead of 105).
- **Numbers by code.** Distance, ascent and ids are never a model's. The ascent is a plain sum of the rises of the smoothed points at 20 m: a
  little higher than BRouter's own "filtered ascend" (46 m against 30 m on the 6 km route of the probe). They are different definitions and
  the tablet uses the grade, not the ascent; the answer's number is the service's.
- **Limits.** At least 100 m; at most 500 km from a GPX or the routing engine and 200 km for a profile (spec §11.4); elevations between
  -500 and 9000 m (anything else is not metres above sea level); a synthetic profile's grade is **clamped to ±20%**, said in the answer;
  rise is `length × grade / 100`. At most 200 routes are kept.

### 3. Three ways in (`create_route`, one tool)

The input is `mode`-discriminated: the tool advertises one flat object (a client needs a single `type: object`; each mode's fields say
which mode they belong to), and a `z.discriminatedUnion` checks what the chosen mode needs and answers the first problem in plain words.
The waypoints' profile is called **`routingProfile`** (`trekking` by default, `fastbike`, `gravel`), because `profile` is also a mode.

- **`gpx`**: our own tolerant scanner (`gpx.ts`), no XML dependency (none was in `server/package.json`, nothing is added to
  `docs/references-licences.md`). It reads `trkpt` (else `rtept`) with their `lat`, `lon` and `<ele>`, joins several tracks and segments in the
  order of the file (said in a note), ignores namespaces' prefixes, extensions, waypoints, comments, CDATA and processing instructions.
  **A point without `<ele>` fails the whole file**, naming how many and the first: the server does not guess an elevation. It never expands an
  entity and **refuses a DOCTYPE** outright; a tag or quote that never closes is refused after at most 20 000 characters, so a hostile file
  costs time in proportion to its size and nothing else. At most **2 MB** (bytes, checked by the parser itself) and 200 000 points. The MCP
  endpoint's body limit went from 1 MiB to **3 MiB** for it.
- **`waypoints`**: 2 to 25 `{ lon, lat }` through BRouter (`brouter.ts`): `GET {BROUTER_URL}/brouter?lonlats=lon,lat|lon,lat&profile=<p>&format=geojson`
  through `URLSearchParams` (the pipe percent-encoded, one parameter), timeout `BROUTER_TIMEOUT_SECONDS` (default 30), `redirect: "error"`. The
  answer's `features[0].geometry.coordinates` are `[lon, lat, ele]`, its `track-length` is a string. **Errors are plain text or empty**, so a
  non-2xx body is never parsed as JSON: `400 datafile E5_N45.rd5 not found` is "no map data for this area" (naming the tile and where to read
  about it), `500` with an empty body "could not use the request" (an unknown profile, a point it cannot parse), another `400` carries BRouter's
  own words ("target island detected…", a deployment fault such as a tile version mismatch also lands here, with its text), anything else
  non-2xx, a network error, a timeout and a 200 that is not a route are "the routing service is unavailable". With `BROUTER_URL` empty the
  tool says routing is off, plainly, and names the other two ways. Messages never contain the engine's address.
- **`profile`**: `{ lengthMeters, gradePercent }` segments and an optional `startElevationMeters` (default 0), no coordinates, no map.

`list_routes` (also says whether routing is on) and `delete_route` (a planned workout whose free step named the route loses it: the step is the
flat free ride again; the answer says which workouts changed) complete the three tools. A route is never changed, only created and deleted.

### 4. Storage and REST

Migration `0011_routes_and_variants.sql`: `routes` (compact points as arrays, `[d, e]` or `[d, e, lat, lon]`, the points' hash), `ride_routes`,
`planned_workout_extras`, `variant_rides`. `GET /v1/routes` (the list without points, newest first, not cached) and `GET /v1/routes/{id}`
(`404 not_found` for an id that is not a route, whatever the reason; `Cache-Control: private, max-age=300`, since a route never changes) under
the device tokens. **No ETag**: no endpoint of the service uses one, so the `Cache-Control` of the brief applies. A deleted route leaves every stored
ride valid: the ride keeps `routeId` and `routeName` and its `ride_routes` row.

### 5. A route on a free step

The calendar holds the primary workout as intervals.icu's text, which has no place for a route id, so **the id lives beside it**:
`planned_workout_extras.route_ids` maps a free step's position (`2`, or `1.0` inside a repeat) to a route id, valid while the primary's steps keep
the **fingerprint** (a hash of the steps without labels and route ids) they had when it was saved. The plan store lays the ids over the workout
when it reads it (so `GET /v1/workouts`, the ride's workout copy, the Telegram bot and the tools all see them), and **never writes them to the
calendar**. If the rider rewrites the steps in intervals.icu the fingerprint no longer matches and the ids are not laid on, rather than landing on
the wrong step. `create_workout` and `update_workout` check that every route exists (a plain message, nothing is written) and say in a warning when
intervals.icu laid the steps out differently and an id could not be kept. `update_workout` with `steps` replaces the ids with what the new steps
carry; with a name or a day alone it keeps them.

### 6. The easier variant

- **Stored only here.** `planned_workout_extras.variant` (`{ steps, note? }`), checked like any workout (a plain message, with the path), its
  routes checked to exist, a note of at most 200 characters. The calendar and Google Calendar hold the primary: a test checks that the one write
  intervals.icu gets is the primary's. `GET /v1/workouts` and the planning tools return it (`structureHash` is `workoutHash` of the primary with
  the variant's steps, so a variant ride is a session of its own, §11.7). A variant-only change does not touch the calendar at all.
- **Lifecycle.** Dropped when the session is **moved** (by `update_workout` with another day, unless a new variant comes in the same call, and
  by the plan refresh when the rider moves it in the calendar) or **deleted** (the tool, the refresh); the whole row goes **two days** after its
  planned day (`VARIANT_KEEP_DAYS`, swept every five minutes and at start) so that a ride uploaded late still finds its variant. The tablet is
  only ever shown it with its planned workout, which leaves the plan cache when its day has passed.
- **Ridden** (`ride.variantRidden`, with the planned workout's id as `workoutId`): (a) the **ride's workout copy** is the primary with the
  variant's steps (the resolver, whichever listener asks first; the extras are used when the day has left the plan cache; when the variant is no
  longer known the primary is kept, with a warning in the log); (b) a plan event `variant` (`naturalKey variant:<ride id>`, once per ride) for the
  agent; (c) the **intervals.icu event is updated to what was ridden**: its description becomes "Easier version ridden: <note>." and the variant's
  steps as text, through a durable queue row (`variant_rides`: `pending`, `done`, `failed`; the growing waits of the upload worker, 20 tries, a
  wrong key never given up), **idempotent** (an event that already has that text, or that is gone, is done without a write; a copy that is the
  primary itself, because the variant was no longer known, writes nothing rather than say something untrue), and the plan cache is brought up to
  date at once; the variant is **consumed** when it is done. (d) A fact for the agent: `plan.easierVersionRidden: true` in the ride's facts (present
  only when true, so the stored facts and their golden fixtures are unchanged), a sentence in the template note, a line in the ride prompt (version
  2) and `variantRidden` in `get_ride`. Not ridden: nothing is sent anywhere, nothing is queued, and the variant is gone with its day.
- **Telegram.** The evening and lead reminders add "An easier version is ready on the tablet." to a planned session that has one (a pure change
  in `messages.ts`); nothing else about the bot changed.

### 7. `routeKey` and the repeat sessions (§11.7)

`routeKey(routeId, points)` is the route id, a colon and the first 16 hex characters of the SHA-256 of the points (`d e [lat lon]` to a tenth of a
metre and a millionth of a degree, a line each, after `overdrive.route-points.v1`): at most 64 characters, so it fits the existing schemas. When a
ride is stored the service records its key in `ride_routes` (from the route as it is then; a route already deleted gives `<id>:deleted`; reads
backfill rides stored without the event, idempotently). Two rides on the same route are **one session**:

- a ride played from **a workout** stays a session of the workout's structure (the hash and every golden number are unchanged); `routeKey` is the
  key when every attempt followed the same route, else `null`;
- a ride played from **no workout** that followed a route is an attempt at the route's session, whose `hash` is `routeSessionHash(routeKey)`
  (another text than a workout's, so no collision) and whose name is the route's. Its figures are the existing ones for a session with no fixed-power
  step: heart rate at the same power has a reason, the attempts show duration, average power and heart rate, drift and compliance where there is one.
  **The time gap on the same route (§11.7's line for a route) is the tablet's overlay and has no field yet**; the durations of the attempts are in
  the detail.

### 8. The FIT file

Only the **tablet writes the FIT** (`FitWriter`); the service checks its framing and forwards it unchanged (`server/src/fit.ts`, ADR 0014), so it
cannot add positions. Writing them (`position_lat` and `position_long` in semicircles, `docs/protocols/fit.md` section 4.3, which says today that they are not written and is
updated by that stage) from the route's points and
the ride's distance is the next stage's.

## Not built, on purpose

The Kotlin models, the route choice and the grade in the trainer's simulation, positions in the FIT, the choice at the start of a ride, the
companion's routes screen; a time gap field for route sessions; ETag; changing a route (delete and create); a route from a Strava segment or a
search (Strava is write-only). **Deployment:** `compose.yaml` passes `BROUTER_URL` only; `BROUTER_TIMEOUT_SECONDS` and `ROUTE_STEP_METERS` need a
pass-through line each in its `environment:` (`VAR: ${VAR:-}`) before they can be set from `.env`; the service runs with their defaults without.

## What only real use settles

Whether 20 m and 60 m suit the D500's simulation lag; whether a real agent sends a GPX of up to 2 MB as a tool argument and whether MCP clients
accept it; how BRouter behaves on the owner's real map tiles (the client was checked only against the short probe route); whether the ascent
should be filtered like the engine's; whether two days is long enough for a late upload to find its variant; how the coach agent uses variants.

## Consequences

- An agent can make a route in three ways and put it under a free step; the tablet will read both and ride the route.
- A workout can offer an easier version without a second calendar entry, and the service keeps the calendar honest when it is ridden.
- `PlanStore` reads join `planned_workout_extras`; `PlanEntry` and `PlannedWorkout` have an optional `variant`; `RideWorkoutResolver` knows the
  variant; `createRepeats` takes `routes`; `Services` has `routes`, `extras` and `variants`.
- **What the tablet stage must fill and read:** read `GET /v1/routes/{id}` (and the list) and `variant` and `route_id` of `GET /v1/workouts`; write
  `ride.routeId`, `ride.routeName` and `ride.variantRidden` (with `workoutId` the planned workout's id); write positions into the FIT.
