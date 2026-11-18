-- The Strava upload (ADR 0020). Never edit this file once it has been released: add a new one.
-- Strava is write-only: these tables hold our own credentials and the answers to our own uploads, nothing
-- Strava holds about the rider.

-- The refresh token. Strava rotates it: each refresh may hand back a new one and the old one stops working at
-- once, so the newest is kept here and the environment's STRAVA_REFRESH_TOKEN only seeds the first run.
-- seed_sha256 is the SHA-256 of the environment's value this row was seeded from: when the owner puts another
-- value in the environment (after running strava-auth again), it no longer matches and the environment wins.
-- One row. The access token is never stored; it lives in memory for the six hours Strava gives it.
CREATE TABLE strava_auth (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  refresh_token text NOT NULL,
  seed_sha256 text NOT NULL,
  updated_at timestamptz NOT NULL
);

-- One row per ride Strava accepted for processing: the upload id Strava answered with, and how many times its
-- status has been asked about since (the polls are bounded). The row is what keeps a ride from being posted
-- twice while its processing is checked, even across a restart. It stays after the ride is done: it is the
-- record of what was sent. ride_destinations (destination 'strava') carries the state, and the activity id
-- in external_id once Strava says it is ready. No foreign key to rides (as ride_workouts, 0003): rides are
-- never deleted, and a table that points at rides would make every TRUNCATE of rides name this one too.
CREATE TABLE strava_uploads (
  ride_id text PRIMARY KEY,
  upload_id text NOT NULL,
  polls integer NOT NULL DEFAULT 0,
  posted_at timestamptz NOT NULL
);
