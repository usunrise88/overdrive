import { WORKOUT_HASH_VERSION, type Workout, workoutHash } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { LapBoundary, RideRepeatStats, StepStat } from "./figures.ts";
import { REPEAT_STATS_VERSION } from "./figures.ts";

/**
 * What the repeat comparison keeps in PostgreSQL (migration 0009): the hash of each ride's workout structure (a column of
 * `ride_workouts`, backfilled here, idempotently) and the figures worked out once per ride (`ride_repeat_stats`). A ride without a
 * workout snapshot has no hash and never matches another.
 */

/** A ride that was played from a workout whose structure has a hash. */
export interface AttemptRow {
  rideId: string;
  hash: string;
  startMs: number;
  /** The workout's name as it was when the ride was stored. */
  name: string;
  /** The tablet's own summary of the ride. */
  durationSeconds: number;
  averagePower: number | null;
  averageHeartRate: number | null;
}

export interface RepeatStore {
  /** Works out the hash of every snapshot that has none (or one of an older definition); the number done. Idempotent. */
  ensureHashes(): Promise<number>;
  /** The rides of one session, or of every session, oldest first. */
  attempts(hash?: string): Promise<AttemptRow[]>;
  /** The hash of a ride's workout, or null when it has none. */
  hashOf(rideId: string): Promise<string | null>;
  /** The stored figures of these rides that are still those of their record; the others are missing from the map. */
  stats(rideIds: readonly string[]): Promise<Map<string, RideRepeatStats>>;
  saveStats(
    rideId: string,
    recordSha: string,
    hash: string,
    stats: RideRepeatStats,
    at: Date,
  ): Promise<void>;
  /** A ride's current record hash, or null when there is no such ride. */
  recordSha(rideId: string): Promise<string | null>;
}

interface AttemptSql {
  ride_id: string;
  hash: string;
  start: string;
  name: string | null;
  duration: string | null;
  avg_power: string | null;
  avg_hr: string | null;
}

const number = (text: string | null): number | null => {
  if (text === null) return null;
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
};

const BATCH = 100;

export function createRepeatStore(db: Db): RepeatStore {
  return {
    async ensureHashes() {
      let done = 0;
      for (;;) {
        const pending = await db.query<{ ride_id: string; workout: Workout }>(
          `SELECT ride_id, workout FROM ride_workouts
            WHERE workout_hash IS NULL OR workout_hash_version IS DISTINCT FROM $1
            ORDER BY ride_id LIMIT $2`,
          [WORKOUT_HASH_VERSION, BATCH],
        );
        if (pending.rows.length === 0) return done;
        for (const row of pending.rows) {
          await db.query(
            "UPDATE ride_workouts SET workout_hash = $2, workout_hash_version = $3 WHERE ride_id = $1",
            [row.ride_id, workoutHash(row.workout), WORKOUT_HASH_VERSION],
          );
          done++;
        }
      }
    },

    async attempts(hash) {
      const result = await db.query<AttemptSql>(
        `SELECT w.ride_id, w.workout_hash AS hash, r.start_epoch_millis::text AS start, w.workout->>'name' AS name,
                r.record->'summary'->>'durationSeconds' AS duration,
                r.record->'summary'->>'averagePower' AS avg_power,
                r.record->'summary'->>'averageHeartRate' AS avg_hr
           FROM ride_workouts w JOIN rides r ON r.id = w.ride_id
          WHERE w.workout_hash IS NOT NULL AND w.workout_hash_version = $1
            AND ($2::text IS NULL OR w.workout_hash = $2)
          ORDER BY r.start_epoch_millis, w.ride_id`,
        [WORKOUT_HASH_VERSION, hash ?? null],
      );
      return result.rows.map((row) => ({
        rideId: row.ride_id,
        hash: row.hash,
        startMs: Number(row.start),
        name: row.name ?? "Planned workout",
        durationSeconds: number(row.duration) ?? 0,
        averagePower: number(row.avg_power),
        averageHeartRate: number(row.avg_hr),
      }));
    },

    async hashOf(rideId) {
      const result = await db.query<{ hash: string | null }>(
        `SELECT workout_hash AS hash FROM ride_workouts
          WHERE ride_id = $1 AND workout_hash_version = $2`,
        [rideId, WORKOUT_HASH_VERSION],
      );
      return result.rows[0]?.hash ?? null;
    },

    async stats(rideIds) {
      const map = new Map<string, RideRepeatStats>();
      if (rideIds.length === 0) return map;
      const result = await db.query<{
        ride_id: string;
        ftp: number;
        steps: StepStat[];
        steps_planned: number;
        steps_completed: number;
        planned_seconds: number;
        actual_seconds: number;
        power_delta_percent: number | null;
        laps: LapBoundary[];
      }>(
        `SELECT s.ride_id, s.ftp, s.steps, s.steps_planned, s.steps_completed, s.planned_seconds, s.actual_seconds,
                s.power_delta_percent, s.laps
           FROM ride_repeat_stats s
           JOIN rides r ON r.id = s.ride_id AND r.record_sha256 = s.record_sha256
          WHERE s.ride_id = ANY($1::text[]) AND s.version = $2`,
        [[...rideIds], REPEAT_STATS_VERSION],
      );
      for (const row of result.rows) {
        map.set(row.ride_id, {
          ftp: row.ftp,
          steps: row.steps,
          stepsPlanned: row.steps_planned,
          stepsCompleted: row.steps_completed,
          plannedSeconds: row.planned_seconds,
          actualSeconds: row.actual_seconds,
          powerDeltaPercent: row.power_delta_percent,
          laps: row.laps,
        });
      }
      return map;
    },

    async saveStats(rideId, recordSha, hash, stats, at) {
      await db.query(
        `INSERT INTO ride_repeat_stats
           (ride_id, version, record_sha256, workout_hash, ftp, steps, steps_planned, steps_completed, planned_seconds,
            actual_seconds, power_delta_percent, laps, computed_at)
         VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, $10, $11, $12::jsonb, $13::timestamptz)
         ON CONFLICT (ride_id) DO UPDATE SET
           version = EXCLUDED.version, record_sha256 = EXCLUDED.record_sha256, workout_hash = EXCLUDED.workout_hash,
           ftp = EXCLUDED.ftp, steps = EXCLUDED.steps, steps_planned = EXCLUDED.steps_planned,
           steps_completed = EXCLUDED.steps_completed, planned_seconds = EXCLUDED.planned_seconds,
           actual_seconds = EXCLUDED.actual_seconds, power_delta_percent = EXCLUDED.power_delta_percent,
           laps = EXCLUDED.laps, computed_at = EXCLUDED.computed_at`,
        [
          rideId,
          REPEAT_STATS_VERSION,
          recordSha,
          hash,
          stats.ftp,
          JSON.stringify(stats.steps),
          stats.stepsPlanned,
          stats.stepsCompleted,
          stats.plannedSeconds,
          stats.actualSeconds,
          stats.powerDeltaPercent,
          JSON.stringify(stats.laps),
          at.toISOString(),
        ],
      );
    },

    async recordSha(rideId) {
      const result = await db.query<{ sha: string }>(
        "SELECT record_sha256 AS sha FROM rides WHERE id = $1",
        [rideId],
      );
      return result.rows[0]?.sha ?? null;
    },
  };
}
