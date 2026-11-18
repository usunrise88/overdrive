import type { RecordCounts, WellnessRecord, WellnessRecordKind } from "@overdrive/contracts";
import { parseInstant } from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";
import { datesAffectedBy, deriveDay, recordsWindow } from "./derive.ts";
import { SOURCE_IDS, STORED_METRICS, type StoredMetric } from "./metrics.ts";
import { fromStored, type Rec, toStored } from "./records.ts";
import type { DailyTable, DailyValue } from "./series.ts";
import type { DailyObservation, SourceOrder } from "./sources.ts";
import type { HrSample } from "./trimp.ts";

/**
 * The wellness tables (migration 0007): the raw Health Connect records, the daily figures per source,
 * and how the pulls of a source went. Nothing here logs a value.
 */

export const HEALTH_CONNECT = "healthconnect";
export const INTERVALS = "intervals";

/** A record that ends more than this far ahead of the service's clock cannot have been recorded. */
const FUTURE_LIMIT_MS = 24 * 3_600_000;
const ID_CHUNK = 500;

export interface IngestOutcome {
  totals: RecordCounts;
  byType: Partial<Record<WellnessRecordKind, RecordCounts>>;
  /** Local dates whose daily figures must be worked out again. */
  dates: string[];
  /** The exercise sessions that were stored or changed (record ids). */
  exerciseIds: string[];
  /** The time spans of the heart-rate records that were stored or changed. */
  heartRateSpans: { startMs: number; endMs: number }[];
}

export interface SyncState {
  lastSuccessAt: Date | null;
  lastAttemptAt: Date | null;
  lastError: string | null;
}

export interface WellnessStore {
  /**
   * Stores a validated batch: new records are inserted, a record already stored is a duplicate when it
   * is the same or not newer (by `lastModified`) and replaces the stored one otherwise. A record whose
   * id is already used by another kind, or that ends over a day in the future, is rejected.
   */
  ingest(records: readonly WellnessRecord[], device: string, nowMs: number): Promise<IngestOutcome>;
  /** The Health Connect records around a local date (everything its figures are worked out from). */
  recordsAround(date: string): Promise<Rec[]>;
  /** Works the daily figures of these local dates out again from the raw records. */
  deriveDates(dates: readonly string[]): Promise<void>;
  /** The heart-rate samples recorded between two instants, in order, from the heart-rate records. */
  heartRateBetween(startMs: number, endMs: number): Promise<HrSample[]>;
  /** The stored exercise sessions with these record ids (all of them when none are named). */
  exerciseSessions(ids?: readonly string[]): Promise<Extract<Rec, { kind: "exerciseSession" }>[]>;
  /** The exercise sessions that overlap a time span. */
  exerciseSessionsOverlapping(
    startMs: number,
    endMs: number,
  ): Promise<Extract<Rec, { kind: "exerciseSession" }>[]>;
  /** Replaces what a pull source knows for the days it covers. */
  replaceDaily(
    source: string,
    from: string,
    to: string,
    observations: readonly DailyObservation[],
  ): Promise<void>;
  /** The resolved daily figures from `from` to `to` (local dates): for each metric and day, the preferred source's. */
  dailyTable(from: string, to: string, order: SourceOrder): Promise<DailyTable>;
  syncState(source: string): Promise<SyncState>;
  recordAttempt(source: string, at: Date, error: string | null): Promise<void>;
}

interface Existing {
  kind: string;
  sha: string;
  lastModifiedMs: number | null;
  startMs: number;
  endMs: number;
}

const zero = (): RecordCounts => ({ accepted: 0, duplicates: 0, rejected: 0 });

async function existingFor(tx: Queryable, ids: readonly string[]): Promise<Map<string, Existing>> {
  const found = new Map<string, Existing>();
  for (let i = 0; i < ids.length; i += ID_CHUNK) {
    const chunk = ids.slice(i, i + ID_CHUNK);
    const result = await tx.query<{
      record_id: string;
      kind: string;
      content_sha256: string;
      last_modified_ms: string | null;
      start_ms: string;
      end_ms: string;
    }>(
      `SELECT record_id, kind, content_sha256, last_modified_ms::text AS last_modified_ms,
              start_ms::text AS start_ms, end_ms::text AS end_ms
         FROM wellness_records WHERE source = $1 AND record_id = ANY($2::text[])`,
      [HEALTH_CONNECT, chunk],
    );
    for (const row of result.rows) {
      found.set(row.record_id, {
        kind: row.kind,
        sha: row.content_sha256,
        lastModifiedMs: row.last_modified_ms === null ? null : Number(row.last_modified_ms),
        startMs: Number(row.start_ms),
        endMs: Number(row.end_ms),
      });
    }
  }
  return found;
}

export function createWellnessStore(db: Db, timezone: string): WellnessStore {
  async function recordsAround(date: string): Promise<Rec[]> {
    const { fromMs, toMs } = recordsWindow(date, timezone);
    const result = await db.query<{
      record_id: string;
      kind: string;
      payload: Record<string, unknown>;
    }>(
      `SELECT record_id, kind, payload FROM wellness_records
        WHERE source = $1 AND kind <> 'exerciseSession' AND end_ms >= $2 AND start_ms < $3`,
      [HEALTH_CONNECT, fromMs, toMs],
    );
    return result.rows.flatMap((row) => {
      const record = fromStored(row.kind, row.record_id, row.payload);
      return record === null ? [] : [record];
    });
  }

  return {
    recordsAround,

    async ingest(records, device, nowMs) {
      const outcome: IngestOutcome = {
        totals: zero(),
        byType: {},
        dates: [],
        exerciseIds: [],
        heartRateSpans: [],
      };
      const dates = new Set<string>();
      const count = (kind: WellnessRecordKind, field: keyof RecordCounts): void => {
        outcome.totals[field]++;
        const entry = outcome.byType[kind] ?? zero();
        entry[field]++;
        outcome.byType[kind] = entry;
      };

      await db.transaction(async (tx) => {
        const existing = await existingFor(
          tx,
          records.map((record) => record.id),
        );
        for (const record of records) {
          const stored = toStored(record);
          if (stored.endMs > nowMs + FUTURE_LIMIT_MS) {
            count(record.kind, "rejected");
            continue;
          }
          const sha = sha256Hex(canonicalJson(stored.payload));
          const lastModified =
            record.lastModified === undefined ? null : parseInstant(record.lastModified);
          const before = existing.get(record.id);
          if (before !== undefined) {
            if (before.kind !== record.kind) {
              count(record.kind, "rejected");
              continue;
            }
            const older =
              lastModified !== null &&
              before.lastModifiedMs !== null &&
              lastModified <= before.lastModifiedMs;
            if (before.sha === sha || older) {
              count(record.kind, "duplicates");
              continue;
            }
            // It replaces the stored record: the days it used to cover change too.
            for (const date of datesAffectedBy(before.startMs, before.endMs, timezone)) {
              dates.add(date);
            }
          }
          await tx.query(
            `INSERT INTO wellness_records
               (source, record_id, kind, start_ms, end_ms, last_modified_ms, payload, content_sha256, device)
             VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)
             ON CONFLICT (source, record_id) DO UPDATE SET
               kind = EXCLUDED.kind, start_ms = EXCLUDED.start_ms, end_ms = EXCLUDED.end_ms,
               last_modified_ms = EXCLUDED.last_modified_ms, payload = EXCLUDED.payload,
               content_sha256 = EXCLUDED.content_sha256, device = EXCLUDED.device, received_at = now()`,
            [
              HEALTH_CONNECT,
              record.id,
              record.kind,
              stored.startMs,
              stored.endMs,
              lastModified,
              canonicalJson(stored.payload),
              sha,
              device,
            ],
          );
          count(record.kind, "accepted");
          for (const date of datesAffectedBy(stored.startMs, stored.endMs, timezone)) {
            dates.add(date);
          }
          if (record.kind === "exerciseSession") outcome.exerciseIds.push(record.id);
          if (record.kind === "heartRateSamples") {
            outcome.heartRateSpans.push({ startMs: stored.startMs, endMs: stored.endMs });
          }
        }
      });
      outcome.dates = [...dates].sort();
      return outcome;
    },

    async deriveDates(dates) {
      for (const date of dates) {
        const derived = deriveDay(date, await recordsAround(date), timezone);
        await db.transaction(async (tx) => {
          await tx.query("DELETE FROM wellness_daily WHERE day = $1::date AND source = $2", [
            date,
            HEALTH_CONNECT,
          ]);
          for (const [metric, value] of Object.entries(derived)) {
            await tx.query(
              "INSERT INTO wellness_daily (day, metric, source, value) VALUES ($1::date, $2, $3, $4)",
              [date, metric, HEALTH_CONNECT, value],
            );
          }
        });
      }
    },

    async heartRateBetween(startMs, endMs) {
      const result = await db.query<{
        record_id: string;
        kind: string;
        payload: Record<string, unknown>;
      }>(
        `SELECT record_id, kind, payload FROM wellness_records
          WHERE source = $1 AND kind = 'heartRateSamples' AND end_ms >= $2 AND start_ms <= $3`,
        [HEALTH_CONNECT, startMs, endMs],
      );
      const samples: HrSample[] = [];
      for (const row of result.rows) {
        const record = fromStored(row.kind, row.record_id, row.payload);
        if (record?.kind !== "heartRateSamples") continue;
        for (const sample of record.samples) {
          if (sample.tMs >= startMs && sample.tMs <= endMs) samples.push(sample);
        }
      }
      return samples.sort((a, b) => a.tMs - b.tMs);
    },

    async exerciseSessions(ids) {
      const result =
        ids === undefined
          ? await db.query<{ record_id: string; kind: string; payload: Record<string, unknown> }>(
              "SELECT record_id, kind, payload FROM wellness_records WHERE source = $1 AND kind = 'exerciseSession'",
              [HEALTH_CONNECT],
            )
          : await db.query<{ record_id: string; kind: string; payload: Record<string, unknown> }>(
              `SELECT record_id, kind, payload FROM wellness_records
                WHERE source = $1 AND kind = 'exerciseSession' AND record_id = ANY($2::text[])`,
              [HEALTH_CONNECT, [...ids]],
            );
      return result.rows.flatMap((row) => {
        const record = fromStored(row.kind, row.record_id, row.payload);
        return record?.kind === "exerciseSession" ? [record] : [];
      });
    },

    async exerciseSessionsOverlapping(startMs, endMs) {
      const result = await db.query<{
        record_id: string;
        kind: string;
        payload: Record<string, unknown>;
      }>(
        `SELECT record_id, kind, payload FROM wellness_records
          WHERE source = $1 AND kind = 'exerciseSession' AND end_ms >= $2 AND start_ms <= $3`,
        [HEALTH_CONNECT, startMs, endMs],
      );
      return result.rows.flatMap((row) => {
        const record = fromStored(row.kind, row.record_id, row.payload);
        return record?.kind === "exerciseSession" ? [record] : [];
      });
    },

    async replaceDaily(source, from, to, observations) {
      await db.transaction(async (tx) => {
        await tx.query(
          "DELETE FROM wellness_daily WHERE source = $1 AND day >= $2::date AND day <= $3::date",
          [source, from, to],
        );
        for (const observation of observations) {
          for (const [metric, value] of Object.entries(observation.values)) {
            await tx.query(
              `INSERT INTO wellness_daily (day, metric, source, value) VALUES ($1::date, $2, $3, $4)
               ON CONFLICT (day, metric, source) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
              [observation.date, metric, source, value],
            );
          }
        }
      });
    },

    async dailyTable(from, to, order) {
      const result = await db.query<{ day: string; metric: string; source: string; value: number }>(
        `SELECT day::text AS day, metric, source, value FROM wellness_daily
          WHERE day >= $1::date AND day <= $2::date`,
        [from, to],
      );
      // For each metric and day, the first source in the metric's order that has a value.
      const best = new Map<string, { rank: number; entry: DailyValue }>();
      const table: Partial<Record<StoredMetric, Map<string, DailyValue>>> = {};
      for (const row of result.rows) {
        if (!STORED_METRICS.includes(row.metric as StoredMetric)) continue;
        if (!SOURCE_IDS.includes(row.source as DailyValue["source"])) continue;
        const metric = row.metric as StoredMetric;
        const rank = order(metric).indexOf(row.source as DailyValue["source"]);
        if (rank < 0) continue;
        const key = `${metric}|${row.day}`;
        const held = best.get(key);
        if (held === undefined || rank < held.rank) {
          best.set(key, {
            rank,
            entry: { value: Number(row.value), source: row.source as DailyValue["source"] },
          });
        }
      }
      for (const [key, { entry }] of best) {
        const [metric, day] = key.split("|") as [StoredMetric, string];
        const map = table[metric] ?? new Map<string, DailyValue>();
        map.set(day, entry);
        table[metric] = map;
      }
      return table;
    },

    async syncState(source) {
      const result = await db.query<{
        last_success_at: Date | string | null;
        last_attempt_at: Date | string | null;
        last_error: string | null;
      }>(
        "SELECT last_success_at, last_attempt_at, last_error FROM wellness_sync_state WHERE source = $1",
        [source],
      );
      const row = result.rows[0];
      const date = (value: Date | string | null | undefined): Date | null =>
        value === null || value === undefined ? null : new Date(value);
      return {
        lastSuccessAt: date(row?.last_success_at),
        lastAttemptAt: date(row?.last_attempt_at),
        lastError: row?.last_error ?? null,
      };
    },

    async recordAttempt(source, at, error) {
      await db.query(
        `INSERT INTO wellness_sync_state (source, last_success_at, last_attempt_at, last_error)
         VALUES ($1, $2::timestamptz, $3::timestamptz, $4)
         ON CONFLICT (source) DO UPDATE SET
           last_success_at = COALESCE(EXCLUDED.last_success_at, wellness_sync_state.last_success_at),
           last_attempt_at = EXCLUDED.last_attempt_at, last_error = EXCLUDED.last_error`,
        [source, error === null ? at.toISOString() : null, at.toISOString(), error],
      );
    },
  };
}
