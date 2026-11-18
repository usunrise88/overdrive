import type { ActivityImportReason } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import { INTERVALS_DESTINATION } from "../intervals/uploads.ts";

/**
 * Where imported outdoor rides live (migration 0009): rows of `activities` with `source = 'fit'`, next to the watch's workouts (ADR
 * 0023, 0026), their queue for intervals.icu in `activity_destinations`, and the rule that recognises the same ride arriving twice.
 */

/** The start of two records of the same ride may differ by this much (a device starts its timer a moment after another). */
export const DUPLICATE_START_TOLERANCE_MS = 60_000;
/** ... and their lengths by this share of the longer. */
export const DUPLICATE_DURATION_TOLERANCE = 0.02;
/** A file that overlaps a ride, a workout or another ride by at least this share of its own length is the same ride. */
export const DUPLICATE_OVERLAP_SHARE = 0.5;

export interface DuplicateOf {
  /** The ride, activity or watch session the file is the same ride as. */
  id: string;
  /** A ride recorded on the tablet is a `ride`; the rest are `activity`. */
  kind: "activity" | "ride";
  reason: ActivityImportReason;
}

export interface NewFitActivity {
  id: string;
  sha256: string;
  startMs: number;
  endMs: number;
  title: string;
  type: string;
  /** `[offset ms from start, bpm]`, thinned. */
  hr: [number, number][];
  samples: number;
  medianGapSeconds: number | null;
  maxGapSeconds: number | null;
  coverage: number;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  distanceMeters: number | null;
  elevationGainMeters: number | null;
  fit: Uint8Array;
}

export interface OutdoorStore {
  /** Whether the file, or the same ride, is already stored; the first rule that matches in the order of the ADR. */
  findDuplicate(file: {
    sha256: string;
    startMs: number;
    endMs: number;
  }): Promise<DuplicateOf | null>;
  /**
   * Stores the ride and queues it for intervals.icu in one transaction. False when a row with this id or these bytes exists (two
   * uploads of one file at once: the loser of the race is a duplicate of the winner).
   */
  insert(activity: NewFitActivity): Promise<boolean>;
}

/** A tablet ride's length in milliseconds. */
const RIDE_DURATION_MS = `COALESCE(r.end_epoch_millis - r.start_epoch_millis,
  1000 * COALESCE((r.record->'summary'->>'durationSeconds')::bigint, 0))`;
const RIDE_END_MS = `(r.start_epoch_millis + ${RIDE_DURATION_MS})`;

export function createOutdoorStore(db: Db): OutdoorStore {
  return {
    async findDuplicate({ sha256, startMs, endMs }) {
      const duration = endMs - startMs;

      // 1. The very same bytes.
      const same = await db.query<{ id: string }>(
        "SELECT id FROM activities WHERE fit_sha256 = $1 LIMIT 1",
        [sha256],
      );
      if (same.rows[0] !== undefined) {
        return { id: same.rows[0].id, kind: "activity", reason: "same-file" };
      }

      // 2. The same start (within a minute) and the same length (within 2%) as any ride, activity or watch session.
      const sameRide = await db.query<{ id: string }>(
        `SELECT r.id FROM rides r
          WHERE abs(r.start_epoch_millis - $1::bigint) <= $3::bigint
            AND abs(${RIDE_DURATION_MS} - $2::bigint) <= $4::double precision * greatest(${RIDE_DURATION_MS}, $2::bigint)
          ORDER BY abs(r.start_epoch_millis - $1::bigint), r.id LIMIT 1`,
        [startMs, duration, DUPLICATE_START_TOLERANCE_MS, DUPLICATE_DURATION_TOLERANCE],
      );
      if (sameRide.rows[0] !== undefined) {
        return { id: sameRide.rows[0].id, kind: "ride", reason: "same-start-and-duration" };
      }
      const sameActivity = await db.query<{ id: string }>(
        `SELECT a.id FROM activities a
          WHERE abs(a.start_ms - $1::bigint) <= $3::bigint
            AND abs((a.end_ms - a.start_ms) - $2::bigint) <= $4::double precision * greatest(a.end_ms - a.start_ms, $2::bigint)
          ORDER BY abs(a.start_ms - $1::bigint), a.id LIMIT 1`,
        [startMs, duration, DUPLICATE_START_TOLERANCE_MS, DUPLICATE_DURATION_TOLERANCE],
      );
      if (sameActivity.rows[0] !== undefined) {
        return { id: sameActivity.rows[0].id, kind: "activity", reason: "same-start-and-duration" };
      }

      // 3. Half of the file or more overlaps a ride on the tablet, a watch session or another imported ride.
      const overlappingRide = await db.query<{ id: string }>(
        `SELECT r.id FROM rides r
          WHERE r.start_epoch_millis < $2::bigint AND ${RIDE_END_MS} > $1::bigint
            AND (least($2::bigint, ${RIDE_END_MS}) - greatest($1::bigint, r.start_epoch_millis))
                >= $3::double precision * ($2::bigint - $1::bigint)
          ORDER BY r.start_epoch_millis LIMIT 1`,
        [startMs, endMs, DUPLICATE_OVERLAP_SHARE],
      );
      if (overlappingRide.rows[0] !== undefined) {
        return { id: overlappingRide.rows[0].id, kind: "ride", reason: "overlap" };
      }
      const overlappingActivity = await db.query<{ id: string }>(
        `SELECT a.id FROM activities a
          WHERE a.start_ms < $2::bigint AND a.end_ms > $1::bigint
            AND (least($2::bigint, a.end_ms) - greatest($1::bigint, a.start_ms))
                >= $3::double precision * ($2::bigint - $1::bigint)
          ORDER BY a.start_ms LIMIT 1`,
        [startMs, endMs, DUPLICATE_OVERLAP_SHARE],
      );
      if (overlappingActivity.rows[0] !== undefined) {
        return { id: overlappingActivity.rows[0].id, kind: "activity", reason: "overlap" };
      }
      return null;
    },

    async insert(activity) {
      return db.transaction(async (tx) => {
        const inserted = await tx.query<{ id: string }>(
          `INSERT INTO activities
             (id, record_id, source, type, title, start_ms, end_ms, hr, hr_source, samples, median_gap_s, max_gap_s,
              coverage, avg_hr, max_hr, distance_m, elevation_gain_m, fit, fit_sha256)
           VALUES ($1, $2, 'fit', $3, $4, $5, $6, $7::jsonb, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $2)
           ON CONFLICT DO NOTHING RETURNING id`,
          [
            activity.id,
            activity.sha256,
            activity.type,
            activity.title,
            activity.startMs,
            activity.endMs,
            activity.hr.length === 0 ? null : JSON.stringify(activity.hr),
            activity.hr.length === 0 ? null : "fit",
            activity.samples,
            activity.medianGapSeconds,
            activity.maxGapSeconds,
            activity.coverage,
            activity.averageHeartRate,
            activity.maxHeartRate,
            activity.distanceMeters,
            activity.elevationGainMeters,
            activity.fit,
          ],
        );
        if (inserted.rows.length === 0) return false;
        await tx.query(
          `INSERT INTO activity_destinations (activity_id, destination, state)
           VALUES ($1, $2, 'pending') ON CONFLICT DO NOTHING`,
          [activity.id, INTERVALS_DESTINATION],
        );
        return true;
      });
    },
  };
}
