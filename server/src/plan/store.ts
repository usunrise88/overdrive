import type { PlannedVariant, PlannedWorkout, PlanSource, Workout } from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import type { EventBus } from "../events.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";
import { applyExtras, type ExtrasRow, type RouteIds, type StoredVariant } from "./extras.ts";

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
  /**
   * The easier version of the session (spec §11.5), laid over the entry when it is read from the store; never part of what is written
   * (the calendar holds the primary). The route ids of the free steps are laid over `workout` the same way (spec §11.4).
   */
  variant?: PlannedVariant;
}

/** What a write changed, by planned workout id. */
export interface PlanChange {
  added: string[];
  updated: string[];
  removed: string[];
}

/**
 * What a mirror of the calendar found that the cache did not already say: a planned session on another day than the cache had it
 * (`moved`), or one inside the window that is no longer in the calendar (`deleted`: also one moved out of the window). A change
 * the service made itself through the tools is already in the cache, so it is never reported here: this is what happened in the
 * calendar behind the service's back, which is the rider's own doing (ADR 0035). A day that merely passed is not a deletion.
 */
export interface MirrorChange extends PlanChange {
  moved: { id: string; name: string; from: string; to: string }[];
  deleted: { id: string; name: string; date: string }[];
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
  /** Workouts that were converted, from `from` to `to`, ordered by date then id, with their variants and route ids laid on. */
  list(range: DateRange): Promise<PlannedWorkout[]>;
  /** Every cached event in the range, converted or not (for the MCP's view of what the tablet cannot play). */
  entries(range: DateRange): Promise<PlanEntry[]>;
  get(id: string): Promise<PlannedWorkout | null>;
  /** Lays the variants and route ids the service keeps over entries read from elsewhere (the calendar, for a range the cache lacks). */
  decorate(entries: PlanEntry[]): Promise<PlanEntry[]>;
  /** Adds or replaces one entry; `plan-changed` is emitted when something changed. */
  upsert(entry: PlanEntry): Promise<"added" | "updated" | "unchanged">;
  /** Removes one entry; true if it was there. */
  remove(id: string): Promise<boolean>;
  /**
   * Makes the cache say exactly `entries` for `window`: upserts them, removes every other entry
   * inside the window and every entry dated before it. One transaction, one `plan-changed`.
   */
  mirror(window: DateRange, source: PlanSource, entries: PlanEntry[]): Promise<MirrorChange>;
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
  /** Laid over the workout when it is read (`planned_workout_extras`): route ids, the fingerprint they hold for, the variant. */
  x_route_ids: RouteIds | null;
  x_steps_hash: string | null;
  x_variant: StoredVariant | null;
}

const COLUMNS = `p.id, p.source, p.external_id, p.plan_date::text AS plan_date, p.name, p.workout,
                 p.unsupported_reason, p.content_sha256,
                 x.route_ids AS x_route_ids, x.steps_hash AS x_steps_hash, x.variant AS x_variant`;
const FROM = "planned_workouts p LEFT JOIN planned_workout_extras x ON x.workout_id = p.id";

const extrasOf = (row: Row): Pick<ExtrasRow, "routeIds" | "stepsHash" | "variant"> => ({
  routeIds: row.x_route_ids,
  stepsHash: row.x_steps_hash,
  variant: row.x_variant,
});

const MAX_REASON = 500;

function toEntry(row: Row): PlanEntry {
  const laid = row.workout === null ? null : applyExtras(row.workout, extrasOf(row));
  return {
    id: row.id,
    source: row.source,
    externalId: row.external_id,
    date: row.plan_date,
    name: row.name,
    workout: laid === null ? null : laid.workout,
    unsupportedReason: row.unsupported_reason,
    ...(laid?.variant === undefined ? {} : { variant: laid.variant }),
  };
}

function toPlanned(row: Row): PlannedWorkout {
  const laid = applyExtras(row.workout as Workout, extrasOf(row));
  return {
    id: row.id,
    date: row.plan_date,
    name: row.name,
    source: row.source,
    workout: laid.workout,
    ...(laid.variant === undefined ? {} : { variant: laid.variant }),
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

/** Writes one entry; says whether it is new, changed or the same, and the day the cache had it on before. */
async function write(
  tx: Queryable,
  entry: PlanEntry,
): Promise<{ outcome: "added" | "updated" | "unchanged"; previousDate: string | null }> {
  const sha = contentHash(entry);
  const found = await tx.query<{ content_sha256: string; plan_date: string }>(
    "SELECT content_sha256, plan_date::text AS plan_date FROM planned_workouts WHERE id = $1",
    [entry.id],
  );
  const stored = found.rows[0];
  const previousDate = stored?.plan_date ?? null;
  if (stored?.content_sha256 === sha) return { outcome: "unchanged", previousDate };
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
  return { outcome: stored === undefined ? "added" : "updated", previousDate };
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
        `SELECT ${COLUMNS} FROM ${FROM}
          WHERE p.plan_date BETWEEN $1::date AND $2::date AND p.workout IS NOT NULL
          ORDER BY p.plan_date, p.id`,
        [range.from, range.to],
      );
      return result.rows.map(toPlanned);
    },

    async entries(range) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM ${FROM}
          WHERE p.plan_date BETWEEN $1::date AND $2::date ORDER BY p.plan_date, p.id`,
        [range.from, range.to],
      );
      return result.rows.map(toEntry);
    },

    async get(id) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM ${FROM} WHERE p.id = $1 AND p.workout IS NOT NULL`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toPlanned(row);
    },

    async decorate(entries) {
      const withWorkout = entries.filter((entry) => entry.workout !== null);
      if (withWorkout.length === 0) return entries;
      const found = await db.query<{
        workout_id: string;
        route_ids: RouteIds | null;
        steps_hash: string | null;
        variant: StoredVariant | null;
      }>(
        `SELECT workout_id, route_ids, steps_hash, variant FROM planned_workout_extras
          WHERE workout_id = ANY($1::text[])`,
        [withWorkout.map((entry) => entry.id)],
      );
      const byId = new Map(found.rows.map((row) => [row.workout_id, row]));
      return entries.map((entry) => {
        const row = byId.get(entry.id);
        if (entry.workout === null || row === undefined) return entry;
        const laid = applyExtras(entry.workout, {
          routeIds: row.route_ids,
          stepsHash: row.steps_hash,
          variant: row.variant,
        });
        return {
          ...entry,
          workout: laid.workout,
          ...(laid.variant === undefined ? {} : { variant: laid.variant }),
        };
      });
    },

    async upsert(entry) {
      const { outcome } = await write(db, entry);
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
        const change: MirrorChange = {
          added: [],
          updated: [],
          removed: [],
          moved: [],
          deleted: [],
        };
        const keep = new Set(entries.map((entry) => entry.id));
        for (const entry of entries) {
          const { outcome, previousDate } = await write(tx, entry);
          if (outcome === "added") change.added.push(entry.id);
          if (outcome === "updated") {
            change.updated.push(entry.id);
            if (previousDate !== null && previousDate !== entry.date) {
              change.moved.push({
                id: entry.id,
                name: entry.name,
                from: previousDate,
                to: entry.date,
              });
            }
          }
        }
        const stale = await tx.query<{ id: string; name: string; plan_date: string }>(
          `DELETE FROM planned_workouts
            WHERE source = $3 AND (plan_date < $1::date OR plan_date BETWEEN $1::date AND $2::date)
              AND NOT (id = ANY($4::text[]))
            RETURNING id, name, plan_date::text AS plan_date`,
          [window.from, window.to, source, [...keep]],
        );
        change.removed.push(...stale.rows.map((row) => row.id));
        for (const row of stale.rows) {
          if (row.plan_date >= window.from) {
            change.deleted.push({ id: row.id, name: row.name, date: row.plan_date });
          }
        }
        return change;
      });
      announce({ added: change.added, updated: change.updated, removed: change.removed });
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
