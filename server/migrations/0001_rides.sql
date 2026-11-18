-- Rides leave the tablet (ADR 0014). Never edit this file once it has been released: add a new one.

-- One row per ride. The record (spec §8.2) is stored as uploaded, as jsonb; the FIT file the tablet
-- generated from it comes in a second request, so it starts null. Both are immutable once stored.
CREATE TABLE rides (
  -- The ride's id from the record (ride.id): the engine's ride-<start millis>, or any id of the
  -- syntax in contracts (RIDE_ID_PATTERN). Not a UUID.
  id text PRIMARY KEY,
  -- A ride is identified by its start time as well (spec §5.5): the same start under another id
  -- is the same ride and is refused.
  start_epoch_millis bigint NOT NULL UNIQUE,
  end_epoch_millis bigint,
  record jsonb NOT NULL,
  -- SHA-256 (hex) of the record's canonical JSON (keys sorted, no whitespace): what "the same body" means.
  record_sha256 text NOT NULL,
  fit bytea,
  -- SHA-256 (hex) of the FIT bytes.
  fit_sha256 text,
  -- The name of the device token that uploaded the record (DEVICE_TOKENS).
  uploaded_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT rides_fit_has_hash CHECK ((fit IS NULL) = (fit_sha256 IS NULL))
);

-- Where a stored ride still has to go (intervals.icu first, Strava later), one row per ride and
-- destination, driven by a separate step that retries with backoff and never delays an upload.
-- Nothing writes here yet.
CREATE TABLE ride_destinations (
  ride_id text NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
  destination text NOT NULL,
  -- pending: to be sent (attempts and last_error say how it is going); done: external_id is set;
  -- failed: given up, last_error says why.
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'done', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  last_error text,
  -- The destination's id for the ride (the intervals.icu activity id, the Strava upload id).
  external_id text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (ride_id, destination)
);
