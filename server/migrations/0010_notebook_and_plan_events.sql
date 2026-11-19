-- The coach notebook and plan events (spec §11.6, §12.2, §12.4, ADR 0035). Never edit this file once it has been released: add a
-- new one. PRIVATE: the notebook holds what the coach agent knows about the rider (training, injuries, plans). Nothing in these
-- tables is ever logged, only ids, sections and counts.

-- Every edit of a section of a notebook is a new row here: history is never rewritten. The current text of a section is its
-- row with the highest version; a section with no row is empty (version 0). `scope` is the notebook: `coach` (what the
-- companion shows the rider, read-only) or `game-master` (the agent's game plans, which the rider's device tokens never read).
-- `kind` says how the version came about: `edit` (update_notebook_section), `append` (append_notebook_log) or `rollback` (a new
-- version carrying the text of an earlier one, `rolled_back_to`). `reason` is why (required for the methodology section,
-- optional elsewhere). `author` is the MCP client that wrote it (`static:<name>` or an OAuth client id, never a token).
CREATE TABLE notebook_versions (
  id bigserial PRIMARY KEY,
  scope text NOT NULL CHECK (scope IN ('coach', 'game-master')),
  section text NOT NULL,
  version integer NOT NULL CHECK (version >= 1),
  text text NOT NULL,
  reason text,
  kind text NOT NULL CHECK (kind IN ('edit', 'append', 'rollback')),
  rolled_back_to integer CHECK (rolled_back_to IS NULL OR rolled_back_to >= 1),
  author text NOT NULL,
  created_at timestamptz NOT NULL,
  UNIQUE (scope, section, version),
  CHECK ((kind = 'rollback') = (rolled_back_to IS NOT NULL))
);
CREATE INDEX notebook_versions_by_time ON notebook_versions (scope, section, created_at DESC);

-- Plan events (spec §12.2): facts that tell the coach agent the plan may need rebuilding. The service only detects and queues
-- them; the agent resolves them. `natural_key` makes a detection idempotent (`missed:<plan id>:<day>`, `moved:<plan id>:<from>:<to>`,
-- `deleted:<plan id>:<day>`, `variant:<ride id>`, `illness:<day>:<until>`): a second attempt for the same key is a no-op, so a
-- missed day is one event however often it is looked at. `workout_id` is the planned workout (null for illness and travel),
-- `detail` what the kind knows (see contracts/schema/plan-event.schema.json), `day` the rider's local day. An open event has no
-- resolution; a resolved one has the time, the agent's note (what changed and why: the rider is sent it in Telegram) and whether
-- the session was dropped.
CREATE TABLE plan_events (
  id serial PRIMARY KEY,
  natural_key text NOT NULL UNIQUE,
  kind text NOT NULL CHECK (kind IN ('missed', 'moved', 'deleted', 'variant', 'illness', 'travel')),
  day date NOT NULL,
  workout_id text,
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
  created_at timestamptz NOT NULL,
  resolved_at timestamptz,
  resolution_note text,
  dropped boolean,
  CHECK (
    (status = 'open' AND resolved_at IS NULL AND resolution_note IS NULL AND dropped IS NULL)
    OR (status = 'resolved' AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL AND dropped IS NOT NULL)
  )
);
CREATE INDEX plan_events_by_status ON plan_events (status, created_at, id);

-- How far the end-of-day check for missed sessions has got: every local day up to and including `checked_through` has been looked
-- at, so a restart does not look again and a server that was down catches up (at most a week). One row.
CREATE TABLE plan_event_cursor (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  checked_through date NOT NULL
);
