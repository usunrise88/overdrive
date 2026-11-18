import type { RideHeader, RideRecord, RideSummary, Workout } from "@overdrive/contracts";
import type { Db } from "./db.ts";
import { canonicalJson } from "./hash.ts";

/**
 * Reads of the stored rides (spec §8.4: `list_rides`, `get_ride`) and the copy of the workout each
 * ride was played from (`ride_workouts`, migration 0003). The intake (`rides.ts`) writes the rides;
 * nothing here changes one.
 */

export interface RideListing {
  id: string;
  startEpochMillis: number;
  endEpochMillis: number | null;
  /** The workout it was played from (`icu-123`), or null for a free ride. */
  workoutId: string | null;
  /** FTP in watts at the time of the ride. */
  ftp: number;
  devices: string[];
  /** As the tablet computed it when the ride ended. */
  summary: RideSummary;
}

export interface RideQuery {
  /** Rides that started at or after this instant (epoch milliseconds). */
  fromMillis?: number;
  /** Rides that started before this instant. */
  toMillis?: number;
  limit: number;
  offset: number;
}

export interface RideStore {
  /** Newest first, with how many rides there are in all that match. */
  list(query: RideQuery): Promise<{ total: number; rides: RideListing[] }>;
  /** The whole record, with every sample, or null when there is no such ride. */
  getRecord(id: string): Promise<RideRecord | null>;
  /** The workout the ride was played from, as it was when it was captured. */
  workoutSnapshot(rideId: string): Promise<Workout | null>;
  saveWorkoutSnapshot(rideId: string, workout: Workout, at: Date): Promise<void>;
}

export function createRideStore(db: Db): RideStore {
  return {
    async list(query) {
      const conditions: string[] = [];
      const params: unknown[] = [];
      if (query.fromMillis !== undefined) {
        params.push(query.fromMillis);
        conditions.push(`start_epoch_millis >= $${params.length}`);
      }
      if (query.toMillis !== undefined) {
        params.push(query.toMillis);
        conditions.push(`start_epoch_millis < $${params.length}`);
      }
      const where = conditions.length === 0 ? "" : `WHERE ${conditions.join(" AND ")}`;
      const total = await db.query<{ n: string }>(
        `SELECT count(*)::text AS n FROM rides ${where}`,
        params,
      );
      const page = await db.query<{
        id: string;
        start: string;
        ended: string | null;
        ride: RideHeader;
        summary: RideSummary;
      }>(
        `SELECT id, start_epoch_millis::text AS start, end_epoch_millis::text AS ended,
                record->'ride' AS ride, record->'summary' AS summary
           FROM rides ${where}
          ORDER BY start_epoch_millis DESC
          LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
        [...params, query.limit, query.offset],
      );
      return {
        total: Number(total.rows[0]?.n ?? 0),
        rides: page.rows.map((row) => ({
          id: row.id,
          startEpochMillis: Number(row.start),
          endEpochMillis: row.ended === null ? null : Number(row.ended),
          workoutId: row.ride.workoutId ?? null,
          ftp: row.ride.ftp,
          devices: row.ride.devices ?? [],
          summary: row.summary,
        })),
      };
    },

    async getRecord(id) {
      const result = await db.query<{ record: RideRecord }>(
        "SELECT record FROM rides WHERE id = $1",
        [id],
      );
      return result.rows[0]?.record ?? null;
    },

    async workoutSnapshot(rideId) {
      const result = await db.query<{ workout: Workout }>(
        "SELECT workout FROM ride_workouts WHERE ride_id = $1",
        [rideId],
      );
      return result.rows[0]?.workout ?? null;
    },

    async saveWorkoutSnapshot(rideId, workout, at) {
      await db.query(
        `INSERT INTO ride_workouts (ride_id, workout, captured_at)
         VALUES ($1, $2::jsonb, $3::timestamptz) ON CONFLICT (ride_id) DO NOTHING`,
        [rideId, canonicalJson(workout), at.toISOString()],
      );
    },
  };
}
