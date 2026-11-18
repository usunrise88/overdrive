import type { Confidence, PowerCurveDuration } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { DriftResult } from "./drift.ts";
import { curveFromJson, curveToJson } from "./power-curve.ts";
import { RIDE_METRICS_VERSION, type RideMetrics } from "./ride-metrics.ts";

/**
 * What the training metrics keep in PostgreSQL (migration 0006): the per-ride results (`ride_metrics`),
 * the FTP suggestions and their fate (`ftp_suggestions`) and the profile's FTP over time
 * (`ftp_history`). Nothing here calculates; `service.ts` does and asks this to remember.
 */

/** A ride's stored metrics, with what it takes to list it. */
export interface RideMetricsRow extends RideMetrics {
  rideId: string;
  startEpochMillis: number;
  /** `ride.workoutId`, or null for a free ride. */
  workoutId: string | null;
  /** The name of the workout the ride was played from, as it was when stored; null when not known. */
  workoutName: string | null;
}

export type SuggestionStatus = "pending" | "accepted" | "dismissed" | "superseded";

export interface SuggestionBasis {
  durationSec: PowerCurveDuration;
  power: number;
  rideId: string;
  date: string;
}

export interface SuggestionRow {
  id: number;
  createdAt: Date;
  value: number;
  basis: SuggestionBasis;
  confidence: Confidence;
  basedOnRides: number;
  windowDays: number;
  currentFtp: number | null;
  differencePercent: number | null;
  status: SuggestionStatus;
  decidedAt: Date | null;
}

export interface NewSuggestion {
  value: number;
  basis: SuggestionBasis;
  confidence: Confidence;
  basedOnRides: number;
  windowDays: number;
  currentFtp: number | null;
  differencePercent: number | null;
}

export interface FtpHistoryRow {
  changedAt: Date;
  ftp: number | null;
}

interface RideMetricsSql {
  ride_id: string;
  start: string;
  workout_id: string | null;
  duration_seconds: number;
  moving_seconds: number;
  ftp: number;
  load: number | null;
  normalized_watts: number | null;
  average_watts: number | null;
  power_curve: Record<string, number>;
  drift: DriftResult;
  workout_name: string | null;
}

const COLUMNS = `m.ride_id, m.start_epoch_millis::text AS start, m.workout_id, m.duration_seconds, m.moving_seconds,
       m.ftp, m.load, m.normalized_watts, m.average_watts, m.power_curve, m.drift,
       w.workout->>'name' AS workout_name`;

/** The rows whose ride is stored, with the very record they were computed from. */
const FROM = `ride_metrics m
       JOIN rides r ON r.id = m.ride_id AND r.record_sha256 = m.record_sha256 AND m.version = ${RIDE_METRICS_VERSION}
       LEFT JOIN ride_workouts w ON w.ride_id = m.ride_id`;

function toRow(row: RideMetricsSql): RideMetricsRow {
  return {
    rideId: row.ride_id,
    startEpochMillis: Number(row.start),
    workoutId: row.workout_id,
    workoutName: row.workout_name,
    durationSeconds: row.duration_seconds,
    movingSeconds: row.moving_seconds,
    ftp: row.ftp,
    load: row.load,
    normalizedWatts: row.normalized_watts,
    averageWatts: row.average_watts,
    powerCurve: curveFromJson(row.power_curve),
    drift: row.drift,
  };
}

interface SuggestionSql {
  id: number;
  created_at: Date | string;
  value: number;
  basis: SuggestionBasis;
  confidence: Confidence;
  based_on_rides: number;
  window_days: number;
  current_ftp: number | null;
  difference_percent: number | null;
  status: SuggestionStatus;
  decided_at: Date | string | null;
}

const SUGGESTION_COLUMNS =
  "id, created_at, value, basis, confidence, based_on_rides, window_days, current_ftp, difference_percent, status, decided_at";

function toSuggestion(row: SuggestionSql): SuggestionRow {
  return {
    id: row.id,
    createdAt: new Date(row.created_at),
    value: row.value,
    basis: row.basis,
    confidence: row.confidence,
    basedOnRides: row.based_on_rides,
    windowDays: row.window_days,
    currentFtp: row.current_ftp,
    differencePercent: row.difference_percent,
    status: row.status,
    decidedAt: row.decided_at === null ? null : new Date(row.decided_at),
  };
}

export interface MetricsStore {
  /** The stored rides with no metrics yet, or with metrics of an older version, at most `limit`, oldest first; `skip` are left out. */
  missing(limit: number, skip: readonly string[]): Promise<{ id: string; sha: string }[]>;
  saveRideMetrics(
    rideId: string,
    sha: string,
    startEpochMillis: number,
    workoutId: string | null,
    metrics: RideMetrics,
    at: Date,
  ): Promise<void>;
  /** Every ride that started before `untilMillis` (exclusive), oldest first. */
  listUntil(untilMillis: number): Promise<RideMetricsRow[]>;
  /** The rides of a window: started at or after `fromMillis` and before `untilMillis`, oldest first. */
  listBetween(fromMillis: number, untilMillis: number): Promise<RideMetricsRow[]>;
  /** The newest rides that started before `beforeMillis` (no limit when null), newest first. */
  listPage(beforeMillis: number | null, limit: number): Promise<RideMetricsRow[]>;
  get(rideId: string): Promise<RideMetricsRow | null>;

  /** The suggestion that is pending, if any (there is at most one). */
  pendingSuggestion(): Promise<SuggestionRow | null>;
  /** The most recently dismissed suggestion, if any. */
  lastDismissedSuggestion(): Promise<SuggestionRow | null>;
  suggestion(id: number): Promise<SuggestionRow | null>;
  /** Makes `suggestion` the pending one: any pending suggestion becomes 'superseded' in the same step. */
  addSuggestion(suggestion: NewSuggestion, at: Date): Promise<SuggestionRow>;
  /** Moves a pending suggestion to `status`; returns the suggestion as it is now, or null if there is none with this id. */
  decideSuggestion(
    id: number,
    status: "accepted" | "dismissed" | "superseded",
    at: Date,
  ): Promise<SuggestionRow | null>;
  /** Every suggestion, newest first (for the planning agent and the companion's history). */
  suggestions(limit: number): Promise<SuggestionRow[]>;

  appendFtpHistory(ftp: number | null, at: Date): Promise<void>;
  /** The profile's FTP changes, oldest first. */
  ftpHistory(): Promise<FtpHistoryRow[]>;
}

export function createMetricsStore(db: Db): MetricsStore {
  return {
    async missing(limit, skip) {
      const result = await db.query<{ id: string; sha: string }>(
        `SELECT r.id, r.record_sha256 AS sha
           FROM rides r
           LEFT JOIN ride_metrics m
             ON m.ride_id = r.id AND m.record_sha256 = r.record_sha256 AND m.version = ${RIDE_METRICS_VERSION}
          WHERE m.ride_id IS NULL AND NOT (r.id = ANY($2::text[]))
          ORDER BY r.start_epoch_millis
          LIMIT $1`,
        [limit, [...skip]],
      );
      return result.rows;
    },

    async saveRideMetrics(rideId, sha, startEpochMillis, workoutId, metrics, at) {
      await db.query(
        `INSERT INTO ride_metrics
           (ride_id, version, record_sha256, start_epoch_millis, workout_id, duration_seconds, moving_seconds,
            ftp, load, normalized_watts, average_watts, power_curve, drift, computed_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb, $13::jsonb, $14::timestamptz)
         ON CONFLICT (ride_id) DO UPDATE SET
           version = EXCLUDED.version, record_sha256 = EXCLUDED.record_sha256,
           start_epoch_millis = EXCLUDED.start_epoch_millis, workout_id = EXCLUDED.workout_id,
           duration_seconds = EXCLUDED.duration_seconds, moving_seconds = EXCLUDED.moving_seconds,
           ftp = EXCLUDED.ftp, load = EXCLUDED.load, normalized_watts = EXCLUDED.normalized_watts,
           average_watts = EXCLUDED.average_watts, power_curve = EXCLUDED.power_curve,
           drift = EXCLUDED.drift, computed_at = EXCLUDED.computed_at`,
        [
          rideId,
          RIDE_METRICS_VERSION,
          sha,
          startEpochMillis,
          workoutId,
          metrics.durationSeconds,
          metrics.movingSeconds,
          metrics.ftp,
          metrics.load,
          metrics.normalizedWatts,
          metrics.averageWatts,
          JSON.stringify(curveToJson(metrics.powerCurve)),
          JSON.stringify(metrics.drift),
          at.toISOString(),
        ],
      );
    },

    async listUntil(untilMillis) {
      const result = await db.query<RideMetricsSql>(
        `SELECT ${COLUMNS} FROM ${FROM} WHERE m.start_epoch_millis < $1 ORDER BY m.start_epoch_millis`,
        [untilMillis],
      );
      return result.rows.map(toRow);
    },

    async listBetween(fromMillis, untilMillis) {
      const result = await db.query<RideMetricsSql>(
        `SELECT ${COLUMNS} FROM ${FROM}
          WHERE m.start_epoch_millis >= $1 AND m.start_epoch_millis < $2 ORDER BY m.start_epoch_millis`,
        [fromMillis, untilMillis],
      );
      return result.rows.map(toRow);
    },

    async listPage(beforeMillis, limit) {
      const result = await db.query<RideMetricsSql>(
        `SELECT ${COLUMNS} FROM ${FROM}
          WHERE ($1::bigint IS NULL OR m.start_epoch_millis < $1)
          ORDER BY m.start_epoch_millis DESC LIMIT $2`,
        [beforeMillis, limit],
      );
      return result.rows.map(toRow);
    },

    async get(rideId) {
      const result = await db.query<RideMetricsSql>(
        `SELECT ${COLUMNS} FROM ${FROM} WHERE m.ride_id = $1`,
        [rideId],
      );
      const row = result.rows[0];
      return row === undefined ? null : toRow(row);
    },

    async pendingSuggestion() {
      const result = await db.query<SuggestionSql>(
        `SELECT ${SUGGESTION_COLUMNS} FROM ftp_suggestions WHERE status = 'pending' ORDER BY id DESC LIMIT 1`,
      );
      const row = result.rows[0];
      return row === undefined ? null : toSuggestion(row);
    },

    async lastDismissedSuggestion() {
      const result = await db.query<SuggestionSql>(
        `SELECT ${SUGGESTION_COLUMNS} FROM ftp_suggestions WHERE status = 'dismissed' ORDER BY id DESC LIMIT 1`,
      );
      const row = result.rows[0];
      return row === undefined ? null : toSuggestion(row);
    },

    async suggestion(id) {
      const result = await db.query<SuggestionSql>(
        `SELECT ${SUGGESTION_COLUMNS} FROM ftp_suggestions WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toSuggestion(row);
    },

    async addSuggestion(suggestion, at) {
      return db.transaction(async (tx) => {
        await tx.query(
          "UPDATE ftp_suggestions SET status = 'superseded', decided_at = $1::timestamptz WHERE status = 'pending'",
          [at.toISOString()],
        );
        const inserted = await tx.query<SuggestionSql>(
          `INSERT INTO ftp_suggestions
             (created_at, value, basis, confidence, based_on_rides, window_days, current_ftp, difference_percent)
           VALUES ($1::timestamptz, $2, $3::jsonb, $4, $5, $6, $7, $8)
           RETURNING ${SUGGESTION_COLUMNS}`,
          [
            at.toISOString(),
            suggestion.value,
            JSON.stringify(suggestion.basis),
            suggestion.confidence,
            suggestion.basedOnRides,
            suggestion.windowDays,
            suggestion.currentFtp,
            suggestion.differencePercent,
          ],
        );
        return toSuggestion(inserted.rows[0] as SuggestionSql);
      });
    },

    async decideSuggestion(id, status, at) {
      await db.query(
        `UPDATE ftp_suggestions SET status = $2, decided_at = $3::timestamptz
          WHERE id = $1 AND status = 'pending'`,
        [id, status, at.toISOString()],
      );
      const result = await db.query<SuggestionSql>(
        `SELECT ${SUGGESTION_COLUMNS} FROM ftp_suggestions WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toSuggestion(row);
    },

    async suggestions(limit) {
      const result = await db.query<SuggestionSql>(
        `SELECT ${SUGGESTION_COLUMNS} FROM ftp_suggestions ORDER BY id DESC LIMIT $1`,
        [limit],
      );
      return result.rows.map(toSuggestion);
    },

    async appendFtpHistory(ftp, at) {
      await db.query("INSERT INTO ftp_history (changed_at, ftp) VALUES ($1::timestamptz, $2)", [
        at.toISOString(),
        ftp,
      ]);
    },

    async ftpHistory() {
      const result = await db.query<{ changed_at: Date | string; ftp: number | null }>(
        "SELECT changed_at, ftp FROM ftp_history ORDER BY changed_at, id",
      );
      return result.rows.map((row) => ({ changedAt: new Date(row.changed_at), ftp: row.ftp }));
    },
  };
}
