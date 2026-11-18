-- The intervals.icu integration (ADR 0016). Never edit this file once it has been released: add a new one.

-- The queue of ride_destinations (0001) learns when to try next. A row is created in the same
-- transaction that stores the ride's FIT file. state 'pending' covers a first try and a failed one
-- that will be retried (attempts and last_error say how it is going, next_attempt_at when);
-- 'done' has external_id; 'failed' is given up (the destination refused the file, or the retries ran out).
-- next_attempt_at is null until a try has been made: a new row is due at once, and the worker's
-- clock, not the database's, decides what "due" means afterwards.
ALTER TABLE ride_destinations
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN last_attempt_at timestamptz;

CREATE INDEX ride_destinations_due ON ride_destinations (destination, next_attempt_at)
  WHERE state = 'pending';

-- Rides stored before this migration (their FIT is there) join the queue; a destination that
-- already has the activity answers "already exists", which counts as done.
INSERT INTO ride_destinations (ride_id, destination)
  SELECT id, 'intervals.icu' FROM rides WHERE fit IS NOT NULL
  ON CONFLICT DO NOTHING;

-- The plan cache: the planned workouts of the next days, converted to the native format. One row
-- per calendar event of the source. A workout that cannot be converted keeps its row with the
-- reason (workout is null), so the cache knows about it and can say why it is not served.
CREATE TABLE planned_workouts (
  -- icu-<intervals.icu event id>; also the id inside the native workout.
  id text PRIMARY KEY,
  source text NOT NULL,
  -- The source's own id of the event, as text.
  external_id text NOT NULL,
  -- The day it is planned for, in the rider's local time.
  plan_date date NOT NULL,
  name text NOT NULL,
  -- The native workout (spec §8.1) as jsonb, or null when it could not be converted.
  workout jsonb,
  unsupported_reason text,
  -- SHA-256 (hex) of what is stored in this row's content columns: what "changed" means.
  content_sha256 text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT planned_workouts_converted_or_explained CHECK ((workout IS NULL) <> (unsupported_reason IS NULL)),
  UNIQUE (source, external_id)
);

CREATE INDEX planned_workouts_by_date ON planned_workouts (plan_date);

-- How the last refreshes of a plan source went, so a restart still knows how old the cache is.
CREATE TABLE plan_sync_state (
  source text PRIMARY KEY,
  last_success_at timestamptz,
  last_attempt_at timestamptz,
  -- Null after a successful attempt; the reason of the last failure otherwise.
  last_error text
);
