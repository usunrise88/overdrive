import type { Db } from "../db.ts";
import { type RideProgressStats, STRETCH_VERSION, type SteadyStretch } from "./steady.ts";

/**
 * What the Progress screen keeps in PostgreSQL (migration 0013): the steady stretches and the efficiency factor of each ride, worked
 * out once from its record (`ride_progress_stats`). Nothing here calculates; `service.ts` asks `steady.ts` and remembers the answer.
 */

export interface ProgressStore {
  /** The stored rides that started in `[fromMs, untilMs)` and have no stats yet, or stats of another record or version; oldest first, at most `limit`. */
  missing(
    fromMs: number | null,
    untilMs: number | null,
    limit: number,
    skip: readonly string[],
  ): Promise<{ id: string; sha: string; startMs: number }[]>;
  save(
    rideId: string,
    sha: string,
    startMs: number,
    stats: RideProgressStats,
    at: Date,
  ): Promise<void>;
  /** The stats of these rides that are still those of their record and version; the others are missing from the map. */
  stats(rideIds: readonly string[]): Promise<Map<string, RideProgressStats>>;
}

export function createProgressStore(db: Db): ProgressStore {
  return {
    async missing(fromMs, untilMs, limit, skip) {
      const result = await db.query<{ id: string; sha: string; start: string }>(
        `SELECT r.id, r.record_sha256 AS sha, r.start_epoch_millis::text AS start
           FROM rides r
           LEFT JOIN ride_progress_stats s
             ON s.ride_id = r.id AND s.record_sha256 = r.record_sha256 AND s.version = ${STRETCH_VERSION}
          WHERE s.ride_id IS NULL AND NOT (r.id = ANY($4::text[]))
            AND ($1::bigint IS NULL OR r.start_epoch_millis >= $1)
            AND ($2::bigint IS NULL OR r.start_epoch_millis < $2)
          ORDER BY r.start_epoch_millis
          LIMIT $3`,
        [fromMs, untilMs, limit, [...skip]],
      );
      return result.rows.map((row) => ({ id: row.id, sha: row.sha, startMs: Number(row.start) }));
    },

    async save(rideId, sha, startMs, stats, at) {
      await db.query(
        `INSERT INTO ride_progress_stats
           (ride_id, version, record_sha256, start_epoch_millis, stretches, efficiency, computed_at)
         VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, $7::timestamptz)
         ON CONFLICT (ride_id) DO UPDATE SET
           version = EXCLUDED.version, record_sha256 = EXCLUDED.record_sha256,
           start_epoch_millis = EXCLUDED.start_epoch_millis, stretches = EXCLUDED.stretches,
           efficiency = EXCLUDED.efficiency, computed_at = EXCLUDED.computed_at`,
        [
          rideId,
          STRETCH_VERSION,
          sha,
          startMs,
          JSON.stringify(stats.stretches),
          stats.efficiency === null ? null : JSON.stringify(stats.efficiency),
          at.toISOString(),
        ],
      );
    },

    async stats(rideIds) {
      const map = new Map<string, RideProgressStats>();
      if (rideIds.length === 0) return map;
      const result = await db.query<{
        ride_id: string;
        stretches: SteadyStretch[];
        efficiency: RideProgressStats["efficiency"];
      }>(
        `SELECT s.ride_id, s.stretches, s.efficiency
           FROM ride_progress_stats s
           JOIN rides r ON r.id = s.ride_id AND r.record_sha256 = s.record_sha256
          WHERE s.ride_id = ANY($1::text[]) AND s.version = ${STRETCH_VERSION}`,
        [[...rideIds]],
      );
      for (const row of result.rows) {
        map.set(row.ride_id, { stretches: row.stretches, efficiency: row.efficiency });
      }
      return map;
    },
  };
}
