# ADR 0037: Routes and the easier variant on the tablet

Status: accepted (v0.4.0, stage A2b-2, 2026-11-19). The service side is ADR 0036; this is what the tablet reads, rides and writes.
Written without a bike, a real route or a Strava account: the numbers below are tested against hand-worked goldens and an independent
solver, and what only the bike and an upload settle is listed at the end.

## Context

The service now serves routes (`GET /v1/routes`, `GET /v1/routes/{id}`), a `route_id` on a `free` step, and an easier `variant` on a
planned workout, and the ride record may name a `routeId`, a `routeName` and `variantRidden`. The tablet had to learn all of it **before the
coach agent first writes a route or a variant**, because the Kotlin decoders are strict: a key they do not know fails the whole answer.

## Decision

### 1. Models, strictly, contracts first (nothing in `contracts/` that Kotlin reads changed)

`FreeStep.routeId` (`route_id`), `PlannedWorkout.variant` (`PlannedVariant`: `steps`, `note`, `structureHash`), `Route`, `RouteSummary`,
`RoutesResponse` (every rule of the schema's description: 2 to 5000 points, strictly increasing distances from 0, `distanceMeters` the last one
rounded, the ascent within a metre, coordinates on every point or none, `hasCoordinates` and the source in agreement, newest first), and in
the ride record's header `routeId`, `routeName` and `variantRidden`, **written only when there is something to say**, so a record of a ride
without them is byte for byte what it was (the crash-safe log's golden line did not change). `variantRidden` reads `true`, `false` and `null`
and the writer only ever writes `true`. A variant that is present is an object: `"variant": null` is refused (the model's nullable field would
otherwise let it through).

**Every Kotlin decoder that reads a server answer that can carry the keys** learns them in the same change: `PlanApiJson` (the tablet's plan
and **the companion's Today**: the companion decodes `GET /v1/workouts` and a ride's detail with the same `core` classes, so it understands
`route_id` and `variant` as of this change), the ride detail's `plan.workout`, and the record's header. **Say so in the release notes: the tablet
and the companion of this release are the first that read a route or a variant, so they must be installed before the coach agent writes one.**
An older tablet or companion refuses the whole plan answer (it shows "That is not the Overdrive service") as soon as one workout has either.

The fixtures stay where the service stage put them, `contracts/fixtures/routes/` (the TypeScript tests read them there, one valid and one invalid
directory for each kind), and the Kotlin contract tests read them there too, **beside** the older directories (`WorkoutContractTest`,
`PlanApiContractTest`, `RideRecordContractTest`, `MetricsContractTest`, a new `RoutesContractTest`), instead of moving them into directories other tests
list for other purposes. One more fixture, a ride detail whose plan has a route step, is new (`routes/ride-detail-response`). One rule is the service's
alone: a variant's `structureHash` is `workoutHash` of the primary with its steps; the tablet never computes it, so the one invalid fixture that
breaks it is skipped by the strict decoder and a test-only port of the algorithm checks every valid fixture's hash instead.

### 2. Routes on the tablet (`core`, `RouteLibrary`)

The list is a cached `RemoteResource` (ADR 0024): shown at once and offline, asked at start, when the Ready screen opens (at most once a minute),
when the rider presses refresh and when the address or token changed. The **points** are fetched when a route is chosen and for the routes the next
days' planned `free` steps name (the Ready screen's days: today and six on, the primary's and the variant's), **read from disk first, kept on disk one file
a route (at most 30, the oldest dropped), one request at a time, never asked for again** (a route never changes), and checked to be the route that was asked
for. A ride is built from the routes that are in memory and from nothing else, so **it never waits for the network**: a route that is not on the tablet is
said so plainly ("Route not downloaded yet", in the route row and in the plan row) and the ride is flat. When the points arrive while the ride is still
ready, the ready ride is built again. Another service's address drops what was loaded; setting the service up for the first time does not.

### 3. The grade (`RouteGrade`), the speed and the engine

- **Smoothing.** The grade at distance *s* is the rise over a window of **40 m** centred on *s*, over the run, i.e. exactly the mean of the road's grade
  over the window; near the ends the window **slides inside the route instead of shrinking** (a route shorter than the window uses all of it), so the grade
  is steady over the first and last 20 m and never jumps. The service smoothed the elevations over about 60 m; this takes the corners off the resampled points.
- **Difficulty** (Settings, a row of its own beside the gears): 0 to 100 percent in steps of 10, **default 50**, multiplies the smoothed grade.
- **Gears and the limit** are the existing model, kept as it is: `GearSettings.gradePercent` adds the gear's offset on top and **clamps the total to ±15%
  (climbing and descending alike)**, rounded to FTMS's 0.01%.
- **The end of the route.** At or beyond its length the grade is 0, `RideSnapshot.route.finished` is true and the ride goes on flat. **No looping.**
  (The snapshot's `route` is `RouteProgress`: id, name, distance along the route, length, grade, finished; no overlay draws it yet.)
- **Distance along the route** starts at 0 when the workout's step changes (a ride with no workout is one step), counts only while riding, and is the ride's
  virtual distance travelled since then. Going back to an earlier step starts its route again; restarting the same step with *Previous* does not (the road
  does not rewind). Because a lap of the record is exactly a run of the same step, **the FIT writer finds the distance along the route as the sample's
  distance minus the distance at the start of its lap**, with no new field in the record.
- **The speed includes the grade.** `FlatPhysicsSpeedModel` (the name stayed; with one argument it is the flat model to the last bit) solves
  `η·P = (Crr·m·g·cos θ + m·g·sin θ)·v + ½ρ·CdA·v³` with `θ = atan(grade/100)`. The right side is convex in `v` and the left is constant, so there is one
  positive root; **Newton's method started above it** converges monotonically: the start is the drag-only speed, plus `√(−a/c)` when the bracket `a` is negative
  (a steep descent), where the function is provably not negative (the inequality is in the code). With no power a rider on a descent coasts at the terminal speed and
  on the flat or a climb stops at once; steady state, no inertia, as the flat model. **A descent is capped at 80 km/h.** Tested against an independent
  bisection over a grid and 3000 random riders, bikes and roads (agreement to 1e-7 km/h) and against numbers worked out by hand (150 W: 30 km/h on the flat, 12.1 on
  a 5% road, 52.9 down 5%).
- **Speed and resistance agree through the route's grade as the trainer feels it with no gear on it** (`RouteGrade.routePercentAt`: scaled, clamped to the
  trainer's limit, rounded): the speed uses that, and the trainer gets that plus the gear. **The gear offset does not change the road's speed** (spec §4.4: a
  gear is a feel; on the flat shifting never changed the model's speed either): a harder gear makes the pedals heavier, the rider's power goes up, and the
  speed follows the power. Before the start and after the end of a ride the trainer is told the flat road.
- A free ride with a chosen route uses it; a `free` step with a `route_id` that the tablet has uses it; **every other step is flat** (an ERG step has no road;
  the chosen route is for a ride with no workout).

### 4. Choosing (Ready screen; design/system.md §6.1)

For a free ride, **one disclosure row, "Route", with "Flat road" or the route's name**, and the facts of the chosen route under it ("12.4 km · 340 m up · has a
map" or "profile only"), or "Route not downloaded yet". It opens a bottom sheet: the flat road, then every cached route with its distance, ascent and the word
for a map. With no list and nothing chosen the row is not drawn. A planned workout's `free` step that names a route uses it automatically and the plan row says
"Route: North road" (or "Routes: A, B", or "Route not downloaded yet"). The choice of route belongs to the free ride and survives choosing something else and back.

A planned workout **with a variant** shows its own row as before and, **right under it, an "Easier version" row** (the agent's note as its line, the length and
IF on the right): **one tap each**, then **Start** as always; **"Recovery: caution"** is one plain label under the two for a workout of today, from `GET /v1/recovery`
(the tablet did not read it before). It is asked **only when a workout of today has a variant** (start, the Ready screen opening, a change of settings; at most once
a minute), kept on disk, and **nothing is shown** for `learning`, `noData`, another day's answer or no answer. A planned workout without a variant looks exactly as before.
What the ride plays: the planned workout with the variant's steps (so `workoutId` is the planned id and the previous attempt is asked for by the **variant's** hash,
a session of its own, ADR 0036); the record's header says `variantRidden: true`.

### 5. The record and the FIT file

The header gets `routeId` and `routeName` of the **first route-backed step whose route the tablet had** (or the chosen route), not of one it did not have: the ride
did not follow that. `FitWriter.write(record, routes)` gives the seconds on a route **the altitude** (`altitude` and `enhanced_altitude`, same value) and, for a route with
coordinates, **`position_lat` and `position_long` in semicircles** interpolated at the distance along the route (a profile writes the altitude only, so no map; a ride with no
route is byte for byte the file it was; `FitOptions.positions` and `altitude` turn the new fields off after a bench finding). `docs/protocols/fit.md` 4.3 and 8.10, spec §4.8.
A ride completed from its log or its stored record after an interruption gets its positions only if it had **no workout** (the record names the route and the points are on the disk);
a workout ride has no way to say which step was on which route, so such a recovered file has none. That is a limit, not a bug.

## Not done, on purpose

The overlay's grade, distance and "route finished" (the snapshot carries them), the companion's routes screen and the notebook (later stages), a time gap line on the
same route (§11.7), a route progress bar, routes in the history, retention of routes by age, and a choice of route for a workout ride that has no route step.

## What only the bike and an upload settle

Whether 40 m (the grade) on top of the service's 60 m (the elevation) is the right amount of smoothing for the D500's lag, whether 50% is the right default and steps of 10
the right resolution, how the trainer takes a new grade every second on a hill, whether the speeds feel right on a climb and a descent (the model is steady state, so a descent
is faster at once and slower at once), whether Strava draws a map from a virtual activity with positions (or wants another sub-sport) and shows the altitude, whether the
recovery line and the two rows read at arm's length, and the real size of the points files on the tablet. Bench: `docs/bench-checklist.md` session 13.

## Consequences

- `core` gains the `route` package (`Route`, `RouteCourse`, `RouteGrade`, `RideRoutes`, `RouteLibrary`) and `FitRoutes`; `RideEngine` takes `routes` and `variantRidden`; `SpeedModel`
  gets a second function with the grade (the default ignores it); `RideSnapshot.route`; `FlatStep.routeId`.
- The container owns a `ServiceClient`, the `RouteLibrary` and the recovery resource beside the plan and the profile; `RideHost` takes the downloaded routes and a free route; `RideChoice.Planned`
  has `variant` and `variantNote`; `RideStore.finalize` takes the routes of the file.
- A stored ride's record is unchanged for every ride without a route or a variant; the upload (ADR 0014) and the service's checks (ADR 0036) need nothing new.
