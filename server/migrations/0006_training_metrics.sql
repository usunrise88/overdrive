-- Training metrics (ADR 0022, spec §6.3). Never edit this file once it has been released: add a new one.

-- What the service works out once from each stored ride (the load, the power curve, the aerobic drift), so
-- that a year of metrics is a read of small rows and not a parse of records of megabytes. A ride never
-- changes after it is stored; `record_sha256` ties the row to the exact record it was computed from and
-- `version` to the formulas, and a row that is missing or out of date is computed again on the next read
-- (that is also the backfill of the rides stored before this migration). No foreign key to rides: rides are
-- never deleted, and a table that points at rides would make every TRUNCATE of rides name this one too.
CREATE TABLE ride_metrics (
  ride_id text PRIMARY KEY,
  version integer NOT NULL,
  record_sha256 text NOT NULL,
  start_epoch_millis bigint NOT NULL,
  -- The workout the ride was played from (ride.workoutId), kept here so that listing rides never reads a record.
  workout_id text,
  duration_seconds integer NOT NULL,
  moving_seconds integer NOT NULL,
  -- The FTP the ride was recorded with.
  ftp integer NOT NULL,
  -- Training stress score; null when the ride has under 30 s of power.
  load double precision,
  normalized_watts double precision,
  average_watts double precision,
  -- {"60": 312.5, "300": 288.1, ...}: best average power by duration in seconds, only the durations the ride
  -- is long enough for; {} when no power was measured.
  power_curve jsonb NOT NULL,
  -- Aerobic drift: {"available": true, "driftPercent": …, "efFirst": …, …} or {"available": false, "reason": …}.
  drift jsonb NOT NULL,
  computed_at timestamptz NOT NULL
);

CREATE INDEX ride_metrics_start ON ride_metrics (start_epoch_millis);

-- FTP suggestions: an FTP the service estimates from recent best efforts. Only suggestions: the profile
-- changes when the rider accepts one (PATCH /v1/profile with its value marks it accepted), never by itself.
-- At most one is pending at a time: a materially different new one marks the old one 'superseded', and one
-- that no longer applies (the FTP moved, the efforts aged out) ends the same way. 'dismissed' is the rider
-- saying no (POST /v1/ftp-suggestion/dismiss): nothing like it is suggested again until the estimate moves.
CREATE TABLE ftp_suggestions (
  id serial PRIMARY KEY,
  created_at timestamptz NOT NULL,
  value integer NOT NULL CHECK (value BETWEEN 30 AND 2000),
  -- {"durationSec": 1200, "power": 275.8, "rideId": "…", "date": "2026-11-12"}
  basis jsonb NOT NULL,
  confidence text NOT NULL CHECK (confidence IN ('low', 'medium', 'high')),
  -- How many rides had an effort close to the basis one, and how many days back the efforts were looked at.
  based_on_rides integer NOT NULL,
  window_days integer NOT NULL,
  -- The profile's FTP when it was made, and the difference in percent; null when the profile had none.
  current_ftp integer,
  difference_percent double precision,
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'accepted', 'dismissed', 'superseded')),
  -- When it left 'pending'.
  decided_at timestamptz
);

CREATE INDEX ftp_suggestions_status ON ftp_suggestions (status, id);

-- Every change of the profile's FTP, with when it happened (the `profile-changed` event feeds it): the
-- FTP history of the companion's power screen. ftp is null when the FTP was cleared.
CREATE TABLE ftp_history (
  id serial PRIMARY KEY,
  changed_at timestamptz NOT NULL,
  ftp integer CHECK (ftp IS NULL OR ftp BETWEEN 30 AND 2000)
);
