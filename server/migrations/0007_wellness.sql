-- Wellness, recovery and the watch's other activities (spec §6.2, §6.4, §6.7, ADR 0023). Never edit this file once it has been
-- released: add a new one. HEALTH DATA: nothing in these tables is ever logged, only counts and ids.

-- The raw Health Connect records the phone uploads, one row per record, idempotent by (source, record id). payload is the
-- record in a compact form (instants as epoch milliseconds, heart-rate samples as [offset ms, bpm] pairs); start_ms and
-- end_ms say what time it covers (a point record has both the same). last_modified_ms is Health Connect's
-- metadata.lastModifiedTime: an upload of the same id with a newer one replaces the row. content_sha256 is the SHA-256 of
-- the canonical JSON of payload, which is what "the same record" means.
CREATE TABLE wellness_records (
  source text NOT NULL,
  record_id text NOT NULL,
  kind text NOT NULL,
  start_ms bigint NOT NULL,
  end_ms bigint NOT NULL,
  last_modified_ms bigint,
  payload jsonb NOT NULL,
  content_sha256 text NOT NULL,
  -- The name of the device token that uploaded it (DEVICE_TOKENS).
  device text NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source, record_id)
);

CREATE INDEX wellness_records_by_time ON wellness_records (end_ms, start_ms);
CREATE INDEX wellness_records_by_kind ON wellness_records (kind, end_ms);

-- The daily figures per local date, metric and source: the value and where it came from are both kept, and which source wins
-- for a metric is decided when they are read (WELLNESS_SOURCE_ORDER). Rows of source 'healthconnect' are worked out from
-- wellness_records and replaced whenever a day is worked out again; rows of 'intervals' are replaced by each pull.
CREATE TABLE wellness_daily (
  day date NOT NULL,
  metric text NOT NULL,
  source text NOT NULL,
  value double precision NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (day, metric, source)
);

CREATE INDEX wellness_daily_by_metric ON wellness_daily (metric, day);

-- How the last pulls of a wellness source went (intervals.icu), so a restart still knows how old the figures are.
CREATE TABLE wellness_sync_state (
  source text PRIMARY KEY,
  last_success_at timestamptz,
  last_attempt_at timestamptz,
  -- Null after a successful attempt; the reason of the last failure otherwise (never a value).
  last_error text
);

-- Workouts recorded on the watch (exerciseSession records), worked out from wellness_records: 'watch-<record id>'. hr holds
-- the samples inside the session as [offset ms from start_ms, bpm] pairs, taken from the record or, when the phone attached
-- none, from the heart-rate records that overlap it (hr_source says which). samples, median_gap_s, max_gap_s and coverage
-- describe how dense they are (spec §6.7, "to check": how dense OHealth's samples really are). The load is not stored: it
-- depends on the maximum and threshold heart rate in force, which change, so it is worked out when it is read.
CREATE TABLE activities (
  id text PRIMARY KEY,
  record_id text NOT NULL UNIQUE,
  type text NOT NULL,
  title text,
  start_ms bigint NOT NULL,
  end_ms bigint NOT NULL,
  hr jsonb,
  hr_source text CHECK (hr_source IN ('session', 'heart-rate-records')),
  samples integer NOT NULL DEFAULT 0,
  median_gap_s double precision,
  max_gap_s double precision,
  coverage double precision NOT NULL DEFAULT 0,
  avg_hr double precision,
  max_hr integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX activities_by_start ON activities (start_ms DESC);

-- Where a watch activity still has to go (intervals.icu as an activity of the matching type), one row per activity and
-- destination, like ride_destinations: pending (first try, or failed and to be retried), done (external_id is the
-- intervals.icu activity id), skipped (last_error says why it is not sent: too old when received, or the watch recorded a
-- ride that is already sent from the tablet), failed (given up). No foreign key to activities, as ride_workouts has none to
-- rides: nothing is ever deleted, and a reference would make every TRUNCATE of activities name this table too.
CREATE TABLE activity_destinations (
  activity_id text NOT NULL,
  destination text NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'skipped', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  last_error text,
  external_id text,
  next_attempt_at timestamptz,
  last_attempt_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (activity_id, destination)
);

CREATE INDEX activity_destinations_due ON activity_destinations (destination, next_attempt_at)
  WHERE state = 'pending';

-- The heart rates the tablet's rides say about the rider, for the estimates of the profile (estimatedMaxHeartRate,
-- estimatedThresholdHeartRate): one row per ride, made the first time they are asked for. max_hr is the highest heart rate
-- of the ride (null without heart rate), best_20min_hr the best average over 20 minutes in a row with at least 90% of the
-- seconds measured (null for a ride too short or too sparse). No foreign key, as above.
CREATE TABLE ride_heart_rate (
  ride_id text PRIMARY KEY,
  max_hr integer,
  best_20min_hr double precision,
  indexed_at timestamptz NOT NULL DEFAULT now()
);
