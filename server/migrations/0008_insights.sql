-- Written insights, alerts and discussion topics (spec §6.5, §6.6, §12.3, ADR 0025). Never edit this file once it has been
-- released: add a new one. HEALTH DATA: the text of a note and its facts are health data; nothing here is ever logged, only ids
-- and counts.

-- Every written note: the morning note, the note after a ride, the weekly review and the two kinds of alert. `key` makes a note
-- idempotent: the local day for a morning note, the ride id for a ride note, the Monday for a weekly review, the metrics and the
-- day for a deviation alert, the Monday for a plan-drift alert (at most one a week). A note is written once: a second attempt
-- for the same (kind, key) is a no-op, so a restart or a retried event never makes two.
-- source says who wrote `text`: llm (a model, whose text passed the validator) or template (deterministic text from the facts:
-- no model is configured, it failed, or its text was rejected twice). prompt_version names the prompt used, or the template.
-- facts is exactly what the writer saw (the structured facts with their quotable numbers); facts_hash is the SHA-256 of its
-- canonical JSON.
CREATE TABLE insights (
  id serial PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('morning', 'ride', 'week', 'deviation', 'drift')),
  key text NOT NULL,
  created_at timestamptz NOT NULL,
  text text NOT NULL,
  -- The ride a ride note is about; null for every other kind.
  ride_id text,
  source text NOT NULL CHECK (source IN ('llm', 'template')),
  prompt_version text NOT NULL,
  facts_hash text NOT NULL,
  facts jsonb NOT NULL,
  UNIQUE (kind, key)
);
CREATE INDEX insights_by_time ON insights (created_at DESC, id DESC);

-- A discussion topic (spec §12.3): why a ride is worth a conversation with the coach agent. One per ride at most; `rule` is the
-- row of the topic rule table that found it (ADR 0025). open until marked discussed or until 14 days have passed (expired, set
-- when the service looks); decided_at says when it left open.
CREATE TABLE discussion_topics (
  id serial PRIMARY KEY,
  ride_id text NOT NULL UNIQUE,
  rule text NOT NULL,
  text text NOT NULL,
  opening_question text NOT NULL,
  created_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'discussed', 'expired')),
  decided_at timestamptz
);
CREATE INDEX discussion_topics_by_time ON discussion_topics (created_at DESC, id DESC);

-- The deviation alerts already sent: one row per metric per streak (the day the run of days outside the usual range began), so a
-- streak that goes on for ten days alerts once and a new streak alerts again.
CREATE TABLE alert_streaks (
  metric text NOT NULL,
  streak_start date NOT NULL,
  insight_id integer NOT NULL,
  PRIMARY KEY (metric, streak_start)
);

-- What the plan history keeps of a planned workout besides its length: the training stress it would give if ridden as planned
-- (the sum over its steps of seconds x target^2 / 36), so that "load against plan" (plan drift) can be told for days the plan
-- cache has already dropped. null for rows written before this column and for a workout the tablet cannot play.
ALTER TABLE plan_history ADD COLUMN planned_load integer;
