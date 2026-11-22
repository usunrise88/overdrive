-- Progress over time (spec §6.3 "Progress over time", ADR 0043). Never edit this file once it has been released: add a new one.
-- HEALTH DATA: nothing in this table is ever logged, only counts and ids.

-- What the service works out once from each stored ride for the Progress screen's heart-rate measures: the steady stretches (a run of
-- 5 minutes or more with steady power: start second, length, mean power, mean heart rate) and the efficiency factor of the steady parts.
-- Finding them reads the ride's samples, which is megabytes for a long ride, so a five-year range is a read of small rows, not of every
-- record. One row per ride, tied to the exact record it was computed from (`record_sha256`) and to the rules (`version`); a row that is
-- missing or out of date is worked out again on the next read or at start (that is also the backfill of the rides stored before this
-- migration). No foreign key to rides, as ride_metrics has none.
CREATE TABLE ride_progress_stats (
  ride_id text PRIMARY KEY,
  version integer NOT NULL,
  record_sha256 text NOT NULL,
  start_epoch_millis bigint NOT NULL,
  -- [{"startSec": 312, "seconds": 1180, "meanPower": 181.4, "meanHeartRate": 139.2, "heartRateSeconds": 1180}, ...]
  stretches jsonb NOT NULL,
  -- {"value": 1.312, "normalizedPower": 182.1, "averageHeartRate": 138.8, "seconds": 1500} or null under 20 minutes of steady parts.
  efficiency jsonb,
  computed_at timestamptz NOT NULL
);

CREATE INDEX ride_progress_stats_start ON ride_progress_stats (start_epoch_millis);
