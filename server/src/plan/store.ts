import type { PlannedWorkout, PlanSource, Workout } from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import type { EventBus } from "../events.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";

/**
 * The plan cache (spec §5.1, ADR 0016): the planned workouts of the next days, already converted
 * to the native format, in the `planned_workouts` table. Other modules (the MCP tools, the
 * calendar mirror, the Telegram bot) read and write the plan through {@link PlanStore}, never the
 * table, and hear about changes as the `plan-changed` event.
 */

/** One calendar event of a plan source, converted or explained. */
export interface PlanEntry {
  /** `icu-123`; also the id inside `workout`. */
  id: string;
  source: PlanSource;
  /** The source's own id for the event, as text. */
  externalId: string;
  /** `YYYY-MM-DD`, the rider's local day. */
  date: string;
  name: string;
  /** The native workout, or null when it could not be converted. */
  workout: Workout | null;
  /** Why `workout` is null; null when it is not. */
  unsupportedReason: string | null;
}

/** What a write changed, by planned workout id. */
export interface PlanChange {
  added: string[];
  updated: string[];
  removed: string[];
}

export interface DateRange {
  /** `YYYY-MM-DD`. */
  from: string;
  /** `YYYY-MM-DD`, included. */
  to: string;
}

/** How the refreshes of a source went; `null` times mean it has never happened. */
export interface PlanSyncState {
  lastSuccessAt: Date | null;
  lastAttemptAt: Date | null;
  /** The reason the last attempt failed, or null if it succeeded (or none was made). */
  lastError: string | null;
}

export interface PlanStore {
  /** Workouts that were converted, from `from` to `to`, ordered by date then id. */
  list(range: DateRange): Promise<PlannedWorkout[]>;
  /** Every cached event in the range, converted or not (for the MCP's view of what the tablet cannot play). */
  entries(range: DateRange): Promise<PlanEntry[]>;
  get(id: string): Promise<PlannedWorkout | null>;
  /** Adds or replaces one entry; `plan-changed` is emitted when something changed. */
  upsert(entry: PlanEntry): Promise<"added" | "updated" | "unchanged">;
  /** Removes one entry; true if it was there. */
  remove(id: string): Promise<boolean>;
  /**
   * Makes the cache say exactly `entries` for `window`: upserts them, removes every other entry
   * inside the window and every entry dated before it. One transaction, one `plan-changed`.
   */
  mirror(window: DateRange, source: PlanSource, entries: PlanEntry[]): Promise<PlanChange>;
  syncState(source: PlanSource): Promise<PlanSyncState>;
  recordSuccess(source: PlanSource, at: Date): Promise<void>;
  recordFailure(source: PlanSource, at: Date, reason: string): Promise<void>;
}

interface Row {
  id: string;
  source: PlanSource;
  external_id: string;
  plan_date: string;
  name: string;
  workout: Workout | null;
  unsupported_reason: string | null;
  content_sha256: string;
}

const COLUMNS = `id, source, external_id, plan_date::text AS plan_date, name, workout,
                 unsupported_reason, content_sha256`;

const MAX_REASON = 500;

function toEntry(row: Row): PlanEntry {
  return {
    id: row.id,
    source: row.source,
    externalId: row.external_id,
    date: row.plan_date,
    name: row.name,
    workout: row.workout,
    unsupportedReason: row.unsupported_reason,
  };
}

function contentHash(entry: PlanEntry): string {
  return sha256Hex(
    canonicalJson({
      date: entry.date,
      name: entry.name,
      workout: entry.workout,
      unsupportedReason: entry.unsupportedReason,
    }),
  );
}

/** Writes one entry; says whether it is new, changed or the same. */
async function write(tx: Queryable, entry: PlanEntry): Promise<"added" | "updated" | "unchanged"> {
  const sha = contentHash(entry);
  const found = await tx.query<{ content_sha256: string }>(
    "SELECT content_sha256 FROM planned_workouts WHERE id = $1",
    [entry.id],
  );
  const stored = found.rows[0];
  if (stored?.content_sha256 === sha) return "unchanged";
  await tx.query(
    `INSERT INTO planned_workouts
       (id, source, external_id, plan_date, name, workout, unsupported_reason, content_sha256, updated_at)
     VALUES ($1, $2, $3, $4::date, $5, $6::jsonb, $7, $8, now())
     ON CONFLICT (id) DO UPDATE SET
       plan_date = EXCLUDED.plan_date, name = EXCLUDED.name, workout = EXCLUDED.workout,
       unsupported_reason = EXCLUDED.unsupported_reason, content_sha256 = EXCLUDED.content_sha256,
       updated_at = now()`,
    [
      entry.id,
      entry.source,
      entry.externalId,
      entry.date,
      entry.name,
      entry.workout === null ? null : canonicalJson(entry.workout),
      entry.unsupportedReason === null ? null : entry.unsupportedReason.slice(0, MAX_REASON),
      sha,
    ],
  );
  return stored === undefined ? "added" : "updated";
}

export function createPlanStore(db: Db, bus: EventBus): PlanStore {
  const announce = (change: PlanChange): void => {
    if (change.added.length + change.updated.length + change.removed.length > 0) {
      bus.emit("plan-changed", change);
    }
  };

  return {
    async list(range) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM planned_workouts
          WHERE plan_date BETWEEN $1::date AND $2::date AND workout IS NOT NULL
          ORDER BY plan_date, id`,
        [range.from, range.to],
      );
      return result.rows.map((row) => ({
        id: row.id,
        date: row.plan_date,
        name: row.name,
        source: row.source,
        workout: row.workout as Workout,
      }));
    },

    async entries(range) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM planned_workouts
          WHERE plan_date BETWEEN $1::date AND $2::date ORDER BY plan_date, id`,
        [range.from, range.to],
      );
      return result.rows.map(toEntry);
    },

    async get(id) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM planned_workouts WHERE id = $1 AND workout IS NOT NULL`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined
        ? null
        : {
            id: row.id,
            date: row.plan_date,
            name: row.name,
            source: row.source,
            workout: row.workout as Workout,
          };
    },

    async upsert(entry) {
      const outcome = await write(db, entry);
      if (outcome === "added") announce({ added: [entry.id], updated: [], removed: [] });
      if (outcome === "updated") announce({ added: [], updated: [entry.id], removed: [] });
      return outcome;
    },

    async remove(id) {
      const result = await db.query("DELETE FROM planned_workouts WHERE id = $1 RETURNING id", [
        id,
      ]);
      const removed = result.rows.length > 0;
      if (removed) announce({ added: [], updated: [], removed: [id] });
      return removed;
    },

    async mirror(window, source, entries) {
      const change = await db.transaction(async (tx) => {
        const change: PlanChange = { added: [], updated: [], removed: [] };
        const keep = new Set(entries.map((entry) => entry.id));
        for (const entry of entries) {
          const outcome = await write(tx, entry);
          if (outcome === "added") change.added.push(entry.id);
          if (outcome === "updated") change.updated.push(entry.id);
        }
        const stale = await tx.query<{ id: string }>(
          `DELETE FROM planned_workouts
            WHERE source = $3 AND (plan_date < $1::date OR plan_date BETWEEN $1::date AND $2::date)
              AND NOT (id = ANY($4::text[]))
            RETURNING id`,
          [window.from, window.to, source, [...keep]],
        );
        change.removed.push(...stale.rows.map((row) => row.id));
        return change;
      });
      announce(change);
      return change;
    },

    async syncState(source) {
      const result = await db.query<{
        last_success_at: Date | string | null;
        last_attempt_at: Date | string | null;
        last_error: string | null;
      }>(
        "SELECT last_success_at, last_attempt_at, last_error FROM plan_sync_state WHERE source = $1",
        [source],
      );
      const row = result.rows[0];
      const toDate = (value: Date | string | null | undefined): Date | null =>
        value === null || value === undefined ? null : new Date(value);
      return {
        lastSuccessAt: toDate(row?.last_success_at),
        lastAttemptAt: toDate(row?.last_attempt_at),
        lastError: row?.last_error ?? null,
      };
    },

    async recordSuccess(source, at) {
      await db.query(
        `INSERT INTO plan_sync_state (source, last_success_at, last_attempt_at, last_error)
         VALUES ($1, $2::timestamptz, $2::timestamptz, NULL)
         ON CONFLICT (source) DO UPDATE SET
           last_success_at = EXCLUDED.last_success_at, last_attempt_at = EXCLUDED.last_attempt_at,
           last_error = NULL`,
        [source, at.toISOString()],
      );
    },

    async recordFailure(source, at, reason) {
      await db.query(
        `INSERT INTO plan_sync_state (source, last_attempt_at, last_error)
         VALUES ($1, $2::timestamptz, $3)
         ON CONFLICT (source) DO UPDATE SET
           last_attempt_at = EXCLUDED.last_attempt_at, last_error = EXCLUDED.last_error`,
        [source, at.toISOString(), reason.slice(0, MAX_REASON)],
      );
    },
  };
}
