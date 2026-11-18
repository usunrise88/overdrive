-- Repeat-session comparison and outdoor rides (spec §11.7, §11.8, ADR 0026). Never edit this file once it has been released: add a
-- new one. HEALTH DATA: nothing in these tables is ever logged, only counts and ids.

-- ---- Repeats ------------------------------------------------------------------------------------------------------------------

-- The hash of the structure of the workout a ride was played from (`workoutHash` of the contracts: steps with repeats expanded, type,
-- duration and targets; no names, labels or cadence), and the version of that definition. Null until it is worked out: the rides
-- stored before this migration are given theirs by the first read (and at start), idempotently, from the snapshot kept here. A ride
-- without a snapshot has none and never matches another.
ALTER TABLE ride_workouts ADD COLUMN workout_hash text;
ALTER TABLE ride_workouts ADD COLUMN workout_hash_version integer;
CREATE INDEX ride_workouts_by_hash ON ride_workouts (workout_hash) WHERE workout_hash IS NOT NULL;

-- What the service works out once from each ride played from a workout, for the repeat comparison: for each fixed-power step the
-- average heart rate at the same power (steps), and how the ride went against the plan. One row per ride, tied to the exact record
-- it was computed from (`record_sha256`) and to the formulas (`version`); a row that is missing or out of date is computed again.
-- No foreign key to rides, as ride_metrics has none.
CREATE TABLE ride_repeat_stats (
  ride_id text PRIMARY KEY,
  version integer NOT NULL,
  record_sha256 text NOT NULL,
  workout_hash text NOT NULL,
  -- The FTP the ride was recorded with.
  ftp integer NOT NULL,
  -- [{"step": 2, "targetWatts": 220, "seconds": 480, "averagePower": 220.4, "averageHeartRate": 148.2, "reason": null}, ...]
  steps jsonb NOT NULL,
  steps_planned integer NOT NULL,
  steps_completed integer NOT NULL,
  planned_seconds integer NOT NULL,
  actual_seconds integer NOT NULL,
  -- Average power over the steps with a target against the plan, in percent; null when not known.
  power_delta_percent double precision,
  -- The step boundaries as ridden (the first lap of each step): [{"step": 1, "startSeconds": 0, "endSeconds": 600}, ...]
  laps jsonb NOT NULL,
  computed_at timestamptz NOT NULL
);

CREATE INDEX ride_repeat_stats_by_hash ON ride_repeat_stats (workout_hash);

-- ---- Outdoor rides ------------------------------------------------------------------------------------------------------------

-- An outdoor ride imported from a FIT file is a row of `activities` like the watch's workouts (spec §11.8): `source` says which. Its
-- id is fit-<first 16 hex of the file's SHA-256>, its record_id the whole hash. hr holds the heart rate at most every 5 seconds
-- ([offset ms from start_ms, bpm]); the raw FIT bytes are kept (fit) and forwarded to intervals.icu as they are. The distance and
-- the climb are the file's own (null when it does not say). No positions are stored apart from the raw file.
ALTER TABLE activities ADD COLUMN source text NOT NULL DEFAULT 'watch' CHECK (source IN ('watch', 'fit'));
ALTER TABLE activities ADD COLUMN distance_m double precision;
ALTER TABLE activities ADD COLUMN elevation_gain_m double precision;
ALTER TABLE activities ADD COLUMN fit bytea;
ALTER TABLE activities ADD COLUMN fit_sha256 text;
ALTER TABLE activities DROP CONSTRAINT IF EXISTS activities_hr_source_check;
ALTER TABLE activities ADD CONSTRAINT activities_hr_source_check
  CHECK (hr_source IN ('session', 'heart-rate-records', 'fit'));
ALTER TABLE activities ADD CONSTRAINT activities_fit_has_hash CHECK ((fit IS NULL) = (fit_sha256 IS NULL));
-- The same bytes are one file, whatever the id: the idempotency of the import.
CREATE UNIQUE INDEX activities_fit_sha256 ON activities (fit_sha256) WHERE fit_sha256 IS NOT NULL;
CREATE INDEX activities_by_source_end ON activities (source, end_ms);
