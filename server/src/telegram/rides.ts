import type { Db } from "../db.ts";
import { localDate } from "../time.ts";

/**
 * What the bot reads of a stored ride: the numbers of its summary (spec §8.2), computed by the tablet and
 * never recomputed here. The samples are not loaded: a ride record can be megabytes, the summary is a few
 * hundred bytes.
 */
export interface RideBrief {
  id: string;
  startMs: number;
  /** The planned workout the ride was started from (`icu-123`), if any. */
  workoutId: string | null;
  durationSeconds: number;
  movingSeconds: number;
  averagePower: number | null;
  normalizedPower: number | null;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  /** The load: the training stress score of the summary. */
  trainingStressScore: number | null;
}

interface RideRow {
  id: string;
  start: string;
  workout_id: string | null;
  summary: unknown;
}

const number = (value: unknown): number | null =>
  typeof value === "number" && Number.isFinite(value) ? value : null;

function toBrief(row: RideRow): RideBrief {
  const raw = typeof row.summary === "string" ? (JSON.parse(row.summary) as unknown) : row.summary;
  const summary = (typeof raw === "object" && raw !== null ? raw : {}) as Record<string, unknown>;
  return {
    id: row.id,
    startMs: Number(row.start),
    workoutId: row.workout_id === null || row.workout_id === "" ? null : row.workout_id,
    durationSeconds: number(summary.durationSeconds) ?? 0,
    movingSeconds: number(summary.movingSeconds) ?? 0,
    averagePower: number(summary.averagePower),
    normalizedPower: number(summary.normalizedPower),
    averageHeartRate: number(summary.averageHeartRate),
    maxHeartRate: number(summary.maxHeartRate),
    trainingStressScore: number(summary.trainingStressScore),
  };
}

const SELECT = `SELECT id, start_epoch_millis::text AS start, record->'ride'->>'workoutId' AS workout_id,
                       record->'summary' AS summary FROM rides`;

export interface RideQueries {
  /** Rides that started in `[fromMs, toMs)`, oldest first. */
  between(fromMs: number, toMs: number): Promise<RideBrief[]>;
  /** Rides whose record and FIT are both stored and that started at or after `sinceMs`, oldest first. */
  completeSince(sinceMs: number): Promise<RideBrief[]>;
  get(id: string): Promise<RideBrief | null>;
}

export function createRideQueries(db: Db): RideQueries {
  return {
    async between(fromMs, toMs) {
      const result = await db.query<RideRow>(
        `${SELECT} WHERE start_epoch_millis >= $1::bigint AND start_epoch_millis < $2::bigint
          ORDER BY start_epoch_millis, id`,
        [fromMs, toMs],
      );
      return result.rows.map(toBrief);
    },
    async completeSince(sinceMs) {
      const result = await db.query<RideRow>(
        `${SELECT} WHERE fit IS NOT NULL AND start_epoch_millis >= $1::bigint
          ORDER BY start_epoch_millis, id`,
        [sinceMs],
      );
      return result.rows.map(toBrief);
    },
    async get(id) {
      const result = await db.query<RideRow>(`${SELECT} WHERE id = $1`, [id]);
      const row = result.rows[0];
      return row === undefined ? null : toBrief(row);
    },
  };
}

/**
 * Which ride completed which planned workout. A ride started from a workout names it (`workoutId`). A ride
 * that names none of the given ones counts for the only workout still unmatched on its day, the rule the
 * intervals.icu upload pairs by (ADR 0016): with several unmatched on that day it does not guess.
 */
export function pairRides<P extends { id: string; date: string }>(
  planned: readonly P[],
  rides: readonly RideBrief[],
  timeZone: string,
): Map<string, RideBrief> {
  const paired = new Map<string, RideBrief>();
  const used = new Set<string>();
  for (const ride of rides) {
    const match = planned.find((p) => p.id === ride.workoutId && !paired.has(p.id));
    if (match !== undefined) {
      paired.set(match.id, ride);
      used.add(ride.id);
    }
  }
  for (const ride of rides) {
    if (used.has(ride.id)) continue;
    const day = localDate(ride.startMs, timeZone);
    const open = planned.filter((p) => p.date === day && !paired.has(p.id));
    const [only] = open;
    if (open.length === 1 && only !== undefined) {
      paired.set(only.id, ride);
      used.add(ride.id);
    }
  }
  return paired;
}
