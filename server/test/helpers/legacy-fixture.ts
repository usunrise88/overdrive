import { createHash, randomBytes } from "node:crypto";
import { defaultProfile } from "@overdrive/contracts";
import type { Db, Queryable } from "../../src/db.ts";
import { sha256Hex } from "../../src/hash.ts";
import { migrate, readMigrations } from "../../src/migrate.ts";
import { constant, insertRide, syntheticRide } from "./synthetic-rides.ts";

/**
 * The single-user layout the live database has: tenant migrations 0001 to 0013 applied to `public` (the default
 * `search_path`), filled with SYNTHETIC rows in every application table. Nothing here is a real token, secret or
 * recording: the tokens are random bytes made by the test run.
 */

export const LEGACY_VERSIONS = 13;

/** Applies migrations 0001 to 0013 and no later one: the layout the live database was made in. */
export async function migrateLegacyLayout(db: Db): Promise<void> {
  const all = await readMigrations();
  await migrate(db, all.slice(0, LEGACY_VERSIONS));
}

export interface LegacySecrets {
  /** The OAuth client's secret, random. */
  clientSecret: string;
  accessToken: string;
  refreshToken: string;
  /** What STRAVA_REFRESH_TOKEN holds in the environment: `strava_auth.seed_sha256` is its digest. */
  stravaSeed: string;
  /** The rotated Strava refresh token the database holds. */
  stravaRotated: string;
}

export function makeLegacySecrets(): LegacySecrets {
  const random = () => randomBytes(24).toString("hex");
  return {
    clientSecret: random(),
    accessToken: random(),
    refreshToken: random(),
    stravaSeed: random(),
    stravaRotated: random(),
  };
}

const NOW = "2026-11-10T08:00:00Z";

/** At least two rows in every table that allows it, bytea, jsonb, serials with a gap, the rides to destinations chain. */
export async function seedLegacy(db: Db, secrets: LegacySecrets): Promise<void> {
  const q: Queryable = db;
  const day = 24 * 3600 * 1000;
  const t0 = Date.parse("2026-11-02T07:00:00Z");

  // rides (valid records) and their destinations, one with a FIT
  const rideIds = ["ride-synth-1", "ride-synth-2", "ride-synth-3"];
  for (const [index, id] of rideIds.entries()) {
    await insertRide(
      db,
      syntheticRide({
        id,
        startEpochMillis: t0 + index * 2 * day,
        seconds: 600,
        power: constant(180 + index * 20),
        heartRate: () => 130 + index * 5,
      }),
    );
  }
  const fit = randomBytes(4096);
  fit[0] = 0;
  fit[1] = 255;
  await q.query("UPDATE rides SET fit = $1, fit_sha256 = $2 WHERE id = 'ride-synth-1'", [
    fit,
    createHash("sha256").update(fit).digest("hex"),
  ]);
  await q.exec(`
    INSERT INTO ride_destinations (ride_id, destination, state, attempts, external_id)
      VALUES ('ride-synth-1', 'intervals.icu', 'done', 1, 'i-1'),
             ('ride-synth-1', 'strava', 'pending', 0, NULL),
             ('ride-synth-2', 'strava', 'failed', 3, NULL)
      ON CONFLICT (ride_id, destination) DO NOTHING;
    UPDATE ride_destinations SET last_error = 'synthetic failure' WHERE ride_id = 'ride-synth-2'`);

  await q.exec(`
    INSERT INTO planned_workouts (id, source, external_id, plan_date, name, workout, unsupported_reason, content_sha256)
      VALUES ('pw-1', 'intervals.icu', 'e-1', '2026-11-12', 'Sweet spot', '{"steps":[{"seconds":600}]}', NULL, 'aa'),
             ('pw-2', 'intervals.icu', 'e-2', '2026-11-13', 'Odd one', NULL, 'not supported', 'bb');
    INSERT INTO plan_sync_state (source, last_success_at, last_attempt_at, last_error)
      VALUES ('intervals.icu', '${NOW}', '${NOW}', NULL), ('other', NULL, '${NOW}', 'down');
    INSERT INTO ride_workouts (ride_id, workout, captured_at, workout_hash, workout_hash_version)
      VALUES ('ride-synth-1', '{"steps":[{"seconds":600}]}', '${NOW}', 'hash-a', 1),
             ('ride-synth-2', '{"steps":[{"seconds":300}]}', '${NOW}', NULL, NULL);
    INSERT INTO calendar_events (planned_id, google_event_id, plan_date, state)
      VALUES ('pw-1', 'g-1', '2026-11-12', 'synced'), ('pw-2', NULL, '2026-11-13', 'pending');
    INSERT INTO plan_history (plan_id, plan_date, name, planned_seconds, status, planned_load)
      VALUES ('pw-1', '2026-11-12', 'Sweet spot', 3600, 'planned', 70), ('pw-0', '2026-11-05', 'Rest', NULL, 'skipped', NULL);
    INSERT INTO bot_notifications (kind, key, state, attempts, sent_at)
      VALUES ('reminder', '2026-11-12', 'sent', 1, '${NOW}'), ('summary', '2026-W45', 'pending', 0, NULL);
    INSERT INTO bot_state (key, value) VALUES ('offset', '123456'), ('other', 'x');
    INSERT INTO strava_uploads (ride_id, upload_id, polls, posted_at)
      VALUES ('ride-synth-1', 'u-1', 2, '${NOW}'), ('ride-synth-2', 'u-2', 0, '${NOW}')`);

  // The stored profile is the default one with an FTP and a goal, as the profile store writes it.
  const { updatedAt: _stamp, ...stored } = defaultProfile();
  await q.query("INSERT INTO profile (id, data, updated_at) VALUES (true, $1::jsonb, $2)", [
    JSON.stringify({ ...stored, ftp: 255, goals: ["Synthetic goal"] }),
    NOW,
  ]);

  await q.query(
    "INSERT INTO strava_auth (id, refresh_token, seed_sha256, updated_at) VALUES (true, $1, $2, $3)",
    [secrets.stravaRotated, sha256Hex(secrets.stravaSeed), NOW],
  );

  // OAuth: two clients (one public, one with a secret), a code and tokens that share a family, another family
  await q.query(
    `INSERT INTO oauth_clients (client_id, client_secret_sha256, client_name, redirect_uris, token_endpoint_auth_method, created_at, last_authorized_at)
     VALUES ('client-public', NULL, 'Synthetic public', '["https://example.invalid/cb"]', 'none', $1, $1),
            ('client-secret', $2, 'Synthetic confidential', '["https://example.invalid/cb2"]', 'client_secret_post', $1, NULL)`,
    [NOW, sha256Hex(secrets.clientSecret)],
  );
  await q.query(
    `INSERT INTO oauth_codes (code_sha256, client_id, redirect_uri, code_challenge, scope, resource, family_id, expires_at, consumed_at)
     VALUES ($1, 'client-public', 'https://example.invalid/cb', 'challenge', 'mcp', 'https://example.invalid/mcp', 'family-a', $2, $2),
            ($3, 'client-secret', 'https://example.invalid/cb2', 'challenge', 'mcp', 'https://example.invalid/mcp', 'family-c', $2, NULL)`,
    [sha256Hex(`code-${secrets.accessToken}`), NOW, sha256Hex(`code2-${secrets.accessToken}`)],
  );
  await q.query(
    `INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at, used_at, revoked_at)
     VALUES ($1, 'access', 'family-a', 'client-public', 'mcp', 'https://example.invalid/mcp', '2026-12-01T00:00:00Z', $3, NULL, NULL),
            ($2, 'refresh', 'family-a', 'client-public', 'mcp', 'https://example.invalid/mcp', '2027-01-01T00:00:00Z', $3, NULL, NULL),
            ($4, 'refresh', 'family-b', 'client-secret', 'mcp', 'https://example.invalid/mcp', '2027-01-01T00:00:00Z', $3, $3, $3)`,
    [
      sha256Hex(secrets.accessToken),
      sha256Hex(secrets.refreshToken),
      NOW,
      sha256Hex(`old-${secrets.refreshToken}`),
    ],
  );

  await q.exec(`
    INSERT INTO ride_metrics (ride_id, version, record_sha256, start_epoch_millis, workout_id, duration_seconds, moving_seconds, ftp, load, normalized_watts, average_watts, power_curve, drift, computed_at)
      VALUES ('ride-synth-1', 0, 'stale', ${t0}, NULL, 600, 600, 250, 20.5, 190.2, 180, '[[1,300]]', '{"a":1}', '${NOW}'),
             ('ride-synth-2', 0, 'stale', ${t0 + 2 * day}, NULL, 600, 600, 250, NULL, NULL, 200, '[[1,320]]', '{}', '${NOW}');
    INSERT INTO ftp_suggestions (value, basis, confidence, based_on_rides, window_days, current_ftp, difference_percent, status, created_at)
      VALUES (250, '{"x":1}', 'low', 3, 28, 245, 2.04, 'superseded', '${NOW}'),
             (255, '{"x":2}', 'medium', 4, 28, 245, 4.08, 'pending', '${NOW}'),
             (999, '{"x":3}', 'high', 5, 28, 245, 5.5, 'dismissed', '${NOW}');
    DELETE FROM ftp_suggestions WHERE value = 999;
    INSERT INTO ftp_history (changed_at, ftp) VALUES ('${NOW}', 245), ('${NOW}', NULL);
    INSERT INTO wellness_records (source, record_id, kind, start_ms, end_ms, last_modified_ms, payload, content_sha256, device)
      VALUES ('health-connect', 'r-1', 'sleep', ${t0}, ${t0 + 3600000}, NULL, '{"stages":[1,2]}', 'c1', 'phone'),
             ('health-connect', 'r-2', 'heart-rate', ${t0}, ${t0 + 1000}, ${t0 + 5}, '{"bpm":[60]}', 'c2', 'phone');
    INSERT INTO wellness_daily (day, metric, source, value) VALUES ('2026-11-02', 'restingHr', 'health-connect', 51.5), ('2026-11-02', 'sleepHours', 'intervals.icu', 7.25);
    INSERT INTO wellness_sync_state (source, last_success_at, last_attempt_at, last_error) VALUES ('intervals.icu', '${NOW}', '${NOW}', NULL), ('x', NULL, NULL, 'e');
    INSERT INTO activities (id, record_id, type, title, start_ms, end_ms, hr, hr_source, samples, avg_hr, max_hr, source, fit, fit_sha256)
      VALUES ('act-1', 'ar-1', 'run', 'Synthetic run', ${t0}, ${t0 + 1800000}, '[[0,100],[60000,110]]', 'session', 2, 105.0, 110, 'watch', NULL, NULL),
             ('act-2', 'ar-2', 'ride', NULL, ${t0 + day}, ${t0 + day + 1000}, NULL, NULL, 0, NULL, NULL, 'fit', '\\x00ff10ee'::bytea, 'fit-sha-2');
    INSERT INTO activity_destinations (activity_id, destination, state) VALUES ('act-1', 'strava', 'done'), ('act-2', 'strava', 'skipped');
    INSERT INTO ride_heart_rate (ride_id, max_hr, best_20min_hr) VALUES ('ride-synth-1', 150, 140.5), ('ride-synth-2', NULL, NULL);
    INSERT INTO insights (kind, key, created_at, text, ride_id, source, prompt_version, facts_hash, facts)
      VALUES ('ride', 'k1', '${NOW}', 'First note.', 'ride-synth-1', 'template', 'v1', 'h1', '{"n":1}'),
             ('morning', 'k2', '${NOW}', 'Second note.', NULL, 'llm', 'v1', 'h2', '{"n":2}'),
             ('week', 'k3', '${NOW}', 'Third note.', NULL, 'template', 'v1', 'h3', '{"n":3}');
    DELETE FROM insights WHERE key = 'k3';
    INSERT INTO discussion_topics (ride_id, rule, text, opening_question, created_at, status, decided_at)
      VALUES ('ride-synth-1', 'r1', 'A topic', 'Why?', '${NOW}', 'open', NULL), ('ride-synth-2', 'r2', 'Another', 'How?', '${NOW}', 'discussed', '${NOW}');
    INSERT INTO alert_streaks (metric, streak_start, insight_id) VALUES ('restingHr', '2026-11-01', 1), ('sleepHours', '2026-11-03', 2);
    INSERT INTO ride_repeat_stats (ride_id, version, record_sha256, workout_hash, ftp, steps, steps_planned, steps_completed, planned_seconds, actual_seconds, power_delta_percent, laps, computed_at)
      VALUES ('ride-synth-1', 0, 'stale', 'hash-a', 250, '[]', 3, 3, 600, 600, 1.5, '[]', '${NOW}'),
             ('ride-synth-2', 0, 'stale', 'hash-a', 250, '[]', 3, 2, 600, 590, NULL, '[]', '${NOW}');
    INSERT INTO notebook_versions (scope, section, version, text, reason, kind, rolled_back_to, author, created_at)
      VALUES ('coach', 'methodology', 1, 'v1 text', NULL, 'edit', NULL, 'agent', '${NOW}'),
             ('coach', 'methodology', 2, 'v1 text again', 'undo', 'rollback', 1, 'agent', '${NOW}');
    INSERT INTO plan_events (natural_key, kind, day, workout_id, detail, status, created_at, resolved_at, resolution_note, dropped)
      VALUES ('missed:2026-11-05', 'missed', '2026-11-05', 'pw-0', '{"why":"x"}', 'open', '${NOW}', NULL, NULL, NULL),
             ('moved:2026-11-06', 'moved', '2026-11-06', 'pw-1', '{}', 'resolved', '${NOW}', '${NOW}', 'moved on', false),
             ('travel:2026-11-07', 'travel', '2026-11-07', NULL, '{}', 'open', '${NOW}', NULL, NULL, NULL);
    DELETE FROM plan_events WHERE kind = 'travel';
    INSERT INTO plan_event_cursor (id, checked_through) VALUES (true, '2026-11-09');
    INSERT INTO routes (id, name, source, distance_m, ascent_m, has_coordinates, point_count, points, points_hash, created_at)
      VALUES ('route-1', 'Synthetic loop', 'profile', 10000, 100, false, 2, '[[0,0],[10000,100]]', 'ph1', '${NOW}'),
             ('route-2', 'Another', 'gpx', 5000, 20, true, 2, '[[0,0,0],[5000,0,20]]', 'ph2', '${NOW}');
    INSERT INTO ride_routes (ride_id, route_id, route_name, route_key, session_hash, created_at)
      VALUES ('ride-synth-1', 'route-1', 'Synthetic loop', 'k', '0123456789abcdef', '${NOW}'),
             ('ride-synth-2', 'route-2', 'Another', 'k2', 'fedcba9876543210', '${NOW}');
    INSERT INTO planned_workout_extras (workout_id, plan_date, route_ids, steps_hash, variant, updated_at, live_coach, coach_brief, must_say)
      VALUES ('pw-1', '2026-11-12', '["route-1"]', 'sh1', '{"note":"easier"}', '${NOW}', true, 'Keep it smooth', '[]'),
             ('pw-2', '2026-11-13', NULL, NULL, NULL, '${NOW}', NULL, NULL, NULL);
    INSERT INTO variant_rides (ride_id, workout_id, day, state, attempts, created_at, updated_at)
      VALUES ('ride-synth-1', 'pw-1', '2026-11-12', 'done', 1, '${NOW}', '${NOW}'), ('ride-synth-2', 'pw-2', '2026-11-13', 'pending', 0, '${NOW}', '${NOW}');
    INSERT INTO live_cues (ride_id, session_id, seq, trigger, source, text, step_index, at_seconds, created_at, played, played_at)
      VALUES ('ride-synth-1', 'sess-1', 1, 'step-start', 'model', 'Settle in.', 0, 5, '${NOW}', true, '${NOW}'),
             ('ride-synth-1', 'sess-1', 2, 'must-say', 'must-say', 'Drink now.', NULL, 300, '${NOW}', NULL, NULL);
    INSERT INTO ride_progress_stats (ride_id, version, record_sha256, start_epoch_millis, stretches, efficiency, computed_at)
      VALUES ('ride-synth-1', 0, 'stale', ${t0}, '[]', NULL, '${NOW}'), ('ride-synth-2', 0, 'stale', ${t0 + 2 * day}, '[]', '{"e":1}', '${NOW}')`);
}

/** An independent content hash of a table (its rows as JSON, sorted), not the bootstrap's own method. */
export async function independentHash(q: Queryable, qualifiedTable: string): Promise<string> {
  const res = await q.query<{ n: string; h: string }>(
    `SELECT count(*)::text AS n,
            md5(coalesce(string_agg(row_to_json(t)::text, '|' ORDER BY row_to_json(t)::text), '')) AS h
       FROM ${qualifiedTable} t`,
  );
  const row = res.rows[0];
  return `${row?.n}:${row?.h}`;
}
