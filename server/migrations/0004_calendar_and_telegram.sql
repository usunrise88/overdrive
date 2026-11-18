-- Google Calendar mirror and Telegram bot (ADR 0019). Never edit this file once it has been released: add a new one.

-- Planned workout id (icu-123) -> the Google Calendar event that shows it, and how pushing it is going.
-- There is no foreign key to planned_workouts: the plan cache drops a day once it is past, the calendar
-- keeps it. A row of a day that has passed is left alone (and removed a week later); a row of a day that
-- has not passed whose workout vanished from the plan is the event to delete.
CREATE TABLE calendar_events (
  planned_id text PRIMARY KEY,
  -- null until Google has confirmed the event; the id is derived from planned_id, so a create that was
  -- sent but never answered is found again (Google answers 409 for it) and not made twice.
  google_event_id text,
  plan_date date NOT NULL,
  -- SHA-256 of what the event in Google says now (what was last pushed); null: nothing pushed yet.
  synced_sha text,
  -- What the current attempt is pushing (null: a deletion). A new target restarts attempts and waiting.
  target_sha text,
  -- synced: Google says what we want; pending: to be pushed (attempts, last_error and next_attempt_at say how
  -- it is going); failed: Google refused the content or the retries ran out, retried only when the target changes.
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'synced', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  last_attempt_at timestamptz,
  last_error text,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX calendar_events_by_date ON calendar_events (plan_date);

-- What the Telegram bot knows about the plan: one row per planned workout it has seen, kept after the plan
-- cache has dropped the day, because "planned against completed" (the weekly summary, a ride's summary)
-- needs last week's plan. skipped: the owner skipped it from the bot, which deletes it from the calendar.
CREATE TABLE plan_history (
  plan_id text PRIMARY KEY,
  plan_date date NOT NULL,
  name text NOT NULL,
  -- null for a workout the tablet cannot play (no native workout to add up)
  planned_seconds integer,
  status text NOT NULL DEFAULT 'planned' CHECK (status IN ('planned', 'skipped')),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plan_history_by_date ON plan_history (plan_date);

-- Every message the bot sends by itself (reminders, ride and weekly summaries): one row per (kind, key), so a
-- restart neither repeats nor loses one. pending: not sent yet (attempts, last_error, next_attempt_at say how it
-- is going; next_attempt_at doubles as the lease of a try in progress); sent; dropped: no longer true when its time
-- came (the workout moved, was ridden, or the week had nothing in it).
CREATE TABLE bot_notifications (
  kind text NOT NULL,
  key text NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'sent', 'dropped')),
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  last_attempt_at timestamptz,
  last_error text,
  sent_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (kind, key)
);

-- Small values the bot keeps between runs (the next getUpdates offset).
CREATE TABLE bot_state (
  key text PRIMARY KEY,
  value text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
