import type { Activity, ActivitySummary, Profile } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import { INTERVALS_DESTINATION } from "../intervals/uploads.ts";
import { localDate } from "../time.ts";
import { roundTo } from "./metrics.ts";
import type { Rec } from "./records.ts";
import {
  type HeartRateSummary,
  type HrSample,
  MIN_COVERAGE,
  summarizeSamples,
  trimpLoad,
} from "./trimp.ts";

/**
 * Other activities from the watch (spec §6.7, ADR 0023): the `exerciseSession` records worked out
 * as activities with their heart-rate samples, and the load they count for. They feed fatigue, form
 * and recovery and **never** the power curve, FTP estimation or aerobic drift: those read rides only.
 */

/** A session older than this when it is received is not forwarded to intervals.icu (history is not posted). */
export const FORWARD_MAX_AGE_DAYS = 7;
/** Resting heart rate when the service knows nothing of the rider's. */
export const DEFAULT_RESTING_HEART_RATE = 60;
/** Resting heart rate is the mean of the readings of this many days before the activity ... */
export const REST_WINDOW_DAYS = 42;
/** ... when there are at least this many. */
export const REST_MIN_READINGS = 7;
/** The points of an activity's heart-rate series, at most. */
export const SERIES_POINTS = 300;

export const LOAD_NOTE =
  "From heart rate, approximate: short hard efforts are under-counted, because heart rate lags behind them.";

export type ExerciseRec = Extract<Rec, { kind: "exerciseSession" }>;

export interface ActivityRow {
  id: string;
  recordId: string;
  type: string;
  title: string | null;
  startMs: number;
  endMs: number;
  /** `[offset ms from start, bpm]`, in order; null without samples. */
  hr: [number, number][] | null;
  hrSource: "session" | "heart-rate-records" | null;
  samples: number;
  medianGapSeconds: number | null;
  maxGapSeconds: number | null;
  coverage: number;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  /** The tablet ride the watch recorded the same time as (overlapping by half the session or more), or null. */
  overlapsRideId: string | null;
}

export interface ActivityQuery {
  fromMs?: number;
  toMs?: number;
  limit: number;
  offset: number;
}

export interface RebuildOutcome {
  /** Activities that did not exist before. */
  created: string[];
  /** Of those, the ones to be forwarded to intervals.icu (queued as pending). */
  queued: string[];
}

export interface ActivityStore {
  /** Works the activities of these sessions out (their samples are the session's or, without any, the heart-rate records') and stores them. */
  rebuild(
    sessions: readonly ExerciseRec[],
    heartRateBetween: (startMs: number, endMs: number) => Promise<HrSample[]>,
    now: Date,
  ): Promise<RebuildOutcome>;
  get(id: string): Promise<ActivityRow | null>;
  /** Newest first. */
  list(query: ActivityQuery): Promise<{ total: number; rows: ActivityRow[] }>;
  /** Every activity that started in the range, with its heart-rate samples. */
  inRange(fromMs: number, toMs: number): Promise<ActivityRow[]>;
}

/** `watch-<record id>`. */
export const activityIdOf = (recordId: string): string => `watch-${recordId}`;

/** The samples of a session inside it, in order, one per instant. */
export function clipSamples(
  samples: readonly HrSample[],
  startMs: number,
  endMs: number,
): HrSample[] {
  const byTime = new Map<number, HrSample>();
  for (const sample of samples) {
    if (sample.tMs >= startMs && sample.tMs <= endMs) byTime.set(sample.tMs, sample);
  }
  return [...byTime.values()].sort((a, b) => a.tMs - b.tMs);
}

/** The ride overlap: a correlated subquery that names a tablet ride covering at least half of the activity. */
const OVERLAPPING_RIDE = `(
  SELECT r.id FROM rides r
   WHERE r.start_epoch_millis < a.end_ms
     AND COALESCE(r.end_epoch_millis, r.start_epoch_millis + 1000 * COALESCE((r.record->'summary'->>'durationSeconds')::bigint, 0)) > a.start_ms
     AND (LEAST(a.end_ms, COALESCE(r.end_epoch_millis, r.start_epoch_millis + 1000 * COALESCE((r.record->'summary'->>'durationSeconds')::bigint, 0)))
          - GREATEST(a.start_ms, r.start_epoch_millis)) * 2 >= (a.end_ms - a.start_ms)
   ORDER BY r.start_epoch_millis LIMIT 1)`;

interface DbRow {
  id: string;
  record_id: string;
  type: string;
  title: string | null;
  start_ms: string;
  end_ms: string;
  hr: [number, number][] | null;
  hr_source: "session" | "heart-rate-records" | null;
  samples: number;
  median_gap_s: number | null;
  max_gap_s: number | null;
  coverage: number;
  avg_hr: number | null;
  max_hr: number | null;
  overlaps_ride_id: string | null;
}

const COLUMNS = `a.id, a.record_id, a.type, a.title, a.start_ms::text AS start_ms, a.end_ms::text AS end_ms, a.hr, a.hr_source,
  a.samples, a.median_gap_s, a.max_gap_s, a.coverage, a.avg_hr, a.max_hr, ${OVERLAPPING_RIDE} AS overlaps_ride_id`;

function rowOf(row: DbRow): ActivityRow {
  return {
    id: row.id,
    recordId: row.record_id,
    type: row.type,
    title: row.title,
    startMs: Number(row.start_ms),
    endMs: Number(row.end_ms),
    hr: row.hr,
    hrSource: row.hr_source,
    samples: row.samples,
    medianGapSeconds: row.median_gap_s,
    maxGapSeconds: row.max_gap_s,
    coverage: row.coverage,
    averageHeartRate: row.avg_hr,
    maxHeartRate: row.max_hr,
    overlapsRideId: row.overlaps_ride_id,
  };
}

export function createActivityStore(db: Db): ActivityStore {
  return {
    async rebuild(sessions, heartRateBetween, now) {
      const outcome: RebuildOutcome = { created: [], queued: [] };
      for (const session of sessions) {
        // A session under a second has no duration to speak of.
        if (session.endMs - session.startMs < 1000) continue;
        const id = activityIdOf(session.id);
        let source: "session" | "heart-rate-records" = "session";
        let samples = clipSamples(session.samples ?? [], session.startMs, session.endMs);
        if (samples.length === 0) {
          source = "heart-rate-records";
          samples = clipSamples(
            await heartRateBetween(session.startMs, session.endMs),
            session.startMs,
            session.endMs,
          );
        }
        const summary: HeartRateSummary = summarizeSamples(samples, session.startMs, session.endMs);
        const hr = samples.map((s): [number, number] => [s.tMs - session.startMs, s.bpm]);
        const existed =
          (await db.query("SELECT 1 FROM activities WHERE id = $1", [id])).rows.length > 0;
        await db.query(
          `INSERT INTO activities
             (id, record_id, type, title, start_ms, end_ms, hr, hr_source, samples, median_gap_s, max_gap_s,
              coverage, avg_hr, max_hr)
           VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10, $11, $12, $13, $14)
           ON CONFLICT (id) DO UPDATE SET
             type = EXCLUDED.type, title = EXCLUDED.title, start_ms = EXCLUDED.start_ms, end_ms = EXCLUDED.end_ms,
             hr = EXCLUDED.hr, hr_source = EXCLUDED.hr_source, samples = EXCLUDED.samples,
             median_gap_s = EXCLUDED.median_gap_s, max_gap_s = EXCLUDED.max_gap_s, coverage = EXCLUDED.coverage,
             avg_hr = EXCLUDED.avg_hr, max_hr = EXCLUDED.max_hr, updated_at = now()`,
          [
            id,
            session.id,
            session.type,
            session.title,
            session.startMs,
            session.endMs,
            hr.length === 0 ? null : JSON.stringify(hr),
            hr.length === 0 ? null : source,
            summary.samples,
            summary.medianGapSeconds,
            summary.maxGapSeconds,
            summary.coverage,
            summary.averageHeartRate,
            summary.maxHeartRate,
          ],
        );
        if (existed) continue;
        outcome.created.push(id);
        // Queued for intervals.icu unless the session is history: a first sync of the phone must not post weeks of workouts.
        const old = now.getTime() - session.endMs > FORWARD_MAX_AGE_DAYS * 86_400_000;
        await db.query(
          `INSERT INTO activity_destinations (activity_id, destination, state, last_error)
           VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING`,
          [
            id,
            INTERVALS_DESTINATION,
            old ? "skipped" : "pending",
            old ? `older than ${FORWARD_MAX_AGE_DAYS} days when it was received` : null,
          ],
        );
        if (!old) outcome.queued.push(id);
      }
      return outcome;
    },

    async get(id) {
      const result = await db.query<DbRow>(`SELECT ${COLUMNS} FROM activities a WHERE a.id = $1`, [
        id,
      ]);
      const row = result.rows[0];
      return row === undefined ? null : rowOf(row);
    },

    async list(query) {
      const conditions: string[] = [];
      const params: unknown[] = [];
      if (query.fromMs !== undefined) {
        params.push(query.fromMs);
        conditions.push(`a.start_ms >= $${params.length}`);
      }
      if (query.toMs !== undefined) {
        params.push(query.toMs);
        conditions.push(`a.start_ms < $${params.length}`);
      }
      const where = conditions.length === 0 ? "" : `WHERE ${conditions.join(" AND ")}`;
      const total = await db.query<{ n: string }>(
        `SELECT count(*)::text AS n FROM activities a ${where}`,
        params,
      );
      const page = await db.query<DbRow>(
        `SELECT ${COLUMNS} FROM activities a ${where}
          ORDER BY a.start_ms DESC, a.id LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
        [...params, query.limit, query.offset],
      );
      return { total: Number(total.rows[0]?.n ?? 0), rows: page.rows.map(rowOf) };
    },

    async inRange(fromMs, toMs) {
      const result = await db.query<DbRow>(
        `SELECT ${COLUMNS} FROM activities a WHERE a.start_ms >= $1 AND a.start_ms < $2 ORDER BY a.start_ms`,
        [fromMs, toMs],
      );
      return result.rows.map(rowOf);
    },
  };
}

// ---- Heart rates in force, the load -----------------------------------------------------------------------

export interface LimitsInForce {
  max: number | null;
  threshold: number | null;
  /** Either of them comes from the rides' estimate, not from the owner. */
  estimated: boolean;
}

/** The maximum and threshold heart rate the load uses: the owner's, else the estimate from the rides. */
export function limitsInForce(profile: Profile): LimitsInForce {
  const max = profile.maxHeartRate ?? profile.estimatedMaxHeartRate;
  const threshold = profile.thresholdHeartRate ?? profile.estimatedThresholdHeartRate;
  return {
    max,
    threshold,
    estimated:
      (profile.maxHeartRate == null && max != null) ||
      (profile.thresholdHeartRate == null && threshold != null),
  };
}

/** Resting heart rate by local date (the resolved daily figures). */
export type RestingTable = ReadonlyMap<string, number>;

/**
 * The resting heart rate the load of an activity on `date` uses: the mean of the readings of the 42
 * days before it when there are at least 7; else the mean of all the readings when there are at least
 * 7 (an estimate); else 60 (an estimate).
 */
export function restHeartRate(
  resting: RestingTable,
  date: string,
): { value: number; estimated: boolean } {
  const near: number[] = [];
  const first = new Date(`${date}T00:00:00Z`).getTime();
  for (const [day, value] of resting) {
    const age = (first - new Date(`${day}T00:00:00Z`).getTime()) / 86_400_000;
    if (age >= 0 && age <= REST_WINDOW_DAYS) near.push(value);
  }
  if (near.length >= REST_MIN_READINGS) {
    return { value: near.reduce((a, b) => a + b, 0) / near.length, estimated: false };
  }
  const all = [...resting.values()];
  if (all.length >= REST_MIN_READINGS) {
    return { value: all.reduce((a, b) => a + b, 0) / all.length, estimated: true };
  }
  return { value: DEFAULT_RESTING_HEART_RATE, estimated: true };
}

export interface ActivityLoad {
  /** Null when it cannot be worked out, or the session is not counted. */
  load: number | null;
  approximate: boolean;
  thresholdHr: number | null;
  maxHr: number | null;
}

/** The heart-rate samples of an activity. */
export function samplesOf(row: ActivityRow): HrSample[] {
  return (row.hr ?? []).map(([offset, bpm]) => ({ tMs: row.startMs + offset, bpm }));
}

/**
 * The load of an activity: Banister's exponential TRIMP per sample interval, 100 for an hour at
 * threshold heart rate (`trimp.ts`). Null while the maximum and threshold heart rate are unknown (the
 * owner's or the rides' estimate), and for a session that overlaps a tablet ride (the watch recorded
 * the same ride; it is not counted twice).
 */
export function activityLoad(
  row: ActivityRow,
  limits: LimitsInForce,
  resting: RestingTable,
  timezone: string,
): ActivityLoad {
  const none = { load: null, approximate: false, thresholdHr: limits.threshold, maxHr: limits.max };
  if (row.overlapsRideId !== null || limits.max === null || limits.threshold === null) return none;
  const samples = samplesOf(row);
  if (samples.length === 0) return none;
  const rest = restHeartRate(resting, localDate(row.startMs, timezone));
  const load = trimpLoad(samples, row.endMs, {
    rest: rest.value,
    max: limits.max,
    threshold: limits.threshold,
  });
  if (load === null) return none;
  return {
    load: roundTo(load, 1),
    approximate: limits.estimated || rest.estimated || row.coverage < MIN_COVERAGE,
    thresholdHr: limits.threshold,
    maxHr: limits.max,
  };
}

/** "other_workout" as words, for an activity the watch gave no title. */
export function nameOf(row: Pick<ActivityRow, "title" | "type">): string {
  if (row.title !== null && row.title !== "") return row.title;
  const words = row.type.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const asInstant = (ms: number): string => new Date(ms).toISOString();

/** The row of a history list. */
export function summaryOf(row: ActivityRow, load: ActivityLoad, timezone: string): ActivitySummary {
  return {
    id: row.id,
    kind: "activity",
    source: "watch",
    type: row.type,
    name: nameOf(row),
    start: asInstant(row.startMs),
    end: asInstant(row.endMs),
    startDate: localDate(row.startMs, timezone),
    durationSeconds: Math.round((row.endMs - row.startMs) / 1000),
    averageHeartRate: row.averageHeartRate === null ? null : roundTo(row.averageHeartRate, 1),
    maxHeartRate: row.maxHeartRate,
    load: load.load,
    loadApproximate: load.approximate,
    countsTowardLoad: row.overlapsRideId === null,
  };
}

/** The heart rate over the session averaged into at most {@link SERIES_POINTS} points (`t` in seconds from the start). */
export function seriesOf(row: ActivityRow): { t: number; heartRate: number }[] {
  if (row.hr === null || row.hr.length === 0) return [];
  const seconds = Math.max(1, Math.round((row.endMs - row.startMs) / 1000));
  const bucket = Math.max(1, Math.ceil(seconds / SERIES_POINTS));
  const sums = new Map<number, { sum: number; n: number }>();
  for (const [offset, bpm] of row.hr) {
    const index = Math.floor(offset / 1000 / bucket);
    const entry = sums.get(index) ?? { sum: 0, n: 0 };
    entry.sum += bpm;
    entry.n++;
    sums.set(index, entry);
  }
  return [...sums.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([index, { sum, n }]) => ({ t: index * bucket, heartRate: roundTo(sum / n, 1) }));
}

/** The whole answer of `GET /v1/activities/{id}`. */
export function describeActivity(
  row: ActivityRow,
  load: ActivityLoad,
  timezone: string,
  generatedAt: Date,
): Activity {
  return {
    ...summaryOf(row, load, timezone),
    overlapsRideId: row.overlapsRideId,
    loadNote: LOAD_NOTE,
    thresholdHr: load.thresholdHr,
    maxHr: load.maxHr,
    heartRate: {
      samples: row.samples,
      medianGapSeconds: row.medianGapSeconds === null ? null : roundTo(row.medianGapSeconds, 1),
      maxGapSeconds: row.maxGapSeconds === null ? null : roundTo(row.maxGapSeconds, 1),
      coverage: roundTo(row.coverage, 3),
    },
    series: seriesOf(row),
    generatedAt: asInstant(generatedAt.getTime()),
  };
}
