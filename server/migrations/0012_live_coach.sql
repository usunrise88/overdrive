-- The live coach (spec §12.1, ADR 0038). Never edit this file once it has been released: add a new one. PRIVATE: a cue's text is
-- derived from the rider's ride (their power, heart rate and cadence); nothing here is ever logged, only ids, trigger names and counts.

-- What the service keeps about a planned workout for the live coach, next to the easier variant and the route ids (migration 0011):
-- `live_coach` (the agent asked for the coach in this session), `coach_brief` (what the session is for, what to watch in this
-- rider, what tone: at most 1000 characters), and `must_say` (the lines spoken as written, by the position of their step, `i` or
-- `i.j` inside a repeat, as the route ids: `{"1.0": [{"at": "start", "text": "First one."}]}`). The positions are valid while the
-- steps are those of `steps_hash`, the fingerprint the route ids use, so a workout the rider rewrote in intervals.icu loses its
-- lines instead of getting them on the wrong step. None of it is ever written to the calendar.
ALTER TABLE planned_workout_extras ADD COLUMN live_coach boolean;
ALTER TABLE planned_workout_extras ADD COLUMN coach_brief text;
ALTER TABLE planned_workout_extras ADD COLUMN must_say jsonb;
ALTER TABLE planned_workout_extras
  ADD CONSTRAINT planned_workout_extras_must_say_needs_hash CHECK (must_say IS NULL OR steps_hash IS NOT NULL);
ALTER TABLE planned_workout_extras
  ADD CONSTRAINT planned_workout_extras_brief_length CHECK (coach_brief IS NULL OR char_length(coach_brief) <= 1000);

-- Every cue the live coach made, kept on the ride (`ride_id` is the ride record's id: no foreign key, the record is uploaded after
-- the ride and a cue may be logged before it exists). `seq` is the cue's number in its session (the SSE id), `trigger` why it was
-- made (`step-start`, `two-minutes`, `power-drift`, `cadence-sag`, `heart-rate-climb`, `strong-finish`, `must-say`), `source` who
-- wrote the line (`model`, or the agent for a must-say line), `step_index` the flattened step it was about, `at_seconds` the ride's
-- time when it was triggered (the ride record's offsets). `played` is what the tablet reported: null while unknown.
CREATE TABLE live_cues (
  id bigserial PRIMARY KEY,
  ride_id text NOT NULL,
  session_id text NOT NULL,
  seq integer NOT NULL CHECK (seq >= 1),
  trigger text NOT NULL,
  source text NOT NULL CHECK (source IN ('model', 'must-say')),
  text text NOT NULL,
  step_index integer CHECK (step_index IS NULL OR step_index >= 0),
  at_seconds integer NOT NULL CHECK (at_seconds >= 0),
  created_at timestamptz NOT NULL,
  played boolean,
  played_at timestamptz,
  UNIQUE (session_id, seq)
);
CREATE INDEX live_cues_by_ride ON live_cues (ride_id, created_at, id);
