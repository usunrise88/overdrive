-- Routes with elevation and the easier variant (spec §11.4, §11.5, ADR 0036). Never edit this file once it has been released: add a new
-- one. Nothing in these tables is ever logged except ids and counts.

-- ---- Routes -----------------------------------------------------------------------------------------------------------------

-- A route the agent created (create_route) and the tablet rides (GET /v1/routes). `points` is compact JSON, one array per point
-- (`[d, e]` or `[d, e, lat, lon]`: metres along the route, smoothed elevation in metres, degrees), at most 5000, resampled by the
-- service to one every ROUTE_STEP_METERS. `distance_m` and `ascent_m` are worked out by code from the points; `points_hash` is
-- `routePointsHash` of the contracts. A route is never changed, only created and deleted.
CREATE TABLE routes (
  id text PRIMARY KEY,
  name text NOT NULL,
  source text NOT NULL CHECK (source IN ('gpx', 'waypoints', 'profile')),
  distance_m integer NOT NULL,
  ascent_m integer NOT NULL,
  has_coordinates boolean NOT NULL,
  point_count integer NOT NULL,
  points jsonb NOT NULL,
  points_hash text NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX routes_by_time ON routes (created_at DESC, id);

-- The route a stored ride followed (`ride.routeId` of its record), with the route key worked out when the ride was stored: the route's
-- id and the hash of its points as they were then (`routeKey` of the contracts), and the session hash two rides on the same route share
-- (spec §11.7). The ride keeps the route's name too, so deleting the route leaves the ride valid. No foreign keys: a ride outlives its
-- route, and rides are never deleted.
CREATE TABLE ride_routes (
  ride_id text PRIMARY KEY,
  route_id text NOT NULL,
  route_name text NOT NULL,
  route_key text NOT NULL,
  session_hash text NOT NULL CHECK (session_hash ~ '^[0-9a-f]{16}$'),
  created_at timestamptz NOT NULL
);
CREATE INDEX ride_routes_by_session ON ride_routes (session_hash);

-- ---- The easier variant and the route ids of a planned workout ----------------------------------------------------------------

-- What the Overdrive service keeps about a planned workout that intervals.icu cannot hold (the calendar holds the primary, as text):
-- `variant` is the easier version (`{"steps": [...], "note": "…"}`, only here: never sent to intervals.icu or Google Calendar, until it
-- is ridden), and `route_ids` which route backs which free step (`{"2": "route-1", "1.0": "route-2"}`: the step's position, `i` or
-- `i.j` inside a repeat), valid while the steps are those of `steps_hash` (the hash of the primary's steps without labels and route
-- ids, so a workout the rider changed in intervals.icu loses its route ids instead of getting them on the wrong step). `plan_date` is
-- the planned day: a variant is dropped when the workout is moved or deleted, and the whole row some days after its day has passed.
-- No foreign key: the plan cache's rows come and go with the calendar.
CREATE TABLE planned_workout_extras (
  workout_id text PRIMARY KEY,
  plan_date date NOT NULL,
  route_ids jsonb,
  steps_hash text,
  variant jsonb,
  updated_at timestamptz NOT NULL,
  CHECK (route_ids IS NULL OR steps_hash IS NOT NULL)
);
CREATE INDEX planned_workout_extras_by_date ON planned_workout_extras (plan_date);

-- A ride that played the variant (`ride.variantRidden`), and how far the update of its intervals.icu event has got: the event is
-- changed to the steps that were ridden so that planned and completed line up. `pending` until done (a retry follows a failure with a
-- growing wait), `done` also when the event was already as wanted or is gone, `failed` after too many tries. Idempotent by ride.
CREATE TABLE variant_rides (
  ride_id text PRIMARY KEY,
  workout_id text NOT NULL,
  day date NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX variant_rides_pending ON variant_rides (next_attempt_at) WHERE state = 'pending';
