import { addDaysToIsoDate } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { PlanEntry, PlanStore } from "../plan/store.ts";
import { plannedLoad } from "../plan/summary.ts";
import { workoutDurationSeconds } from "../plan/workout-summary.ts";
import { localDate } from "../time.ts";

/** One planned workout the bot has seen, kept after the plan cache has dropped its day. */
export interface HistoryRow {
  id: string;
  /** `YYYY-MM-DD`, the rider's local day. */
  date: string;
  name: string;
  /** null for a workout the tablet cannot play. */
  plannedSeconds: number | null;
  /**
   * The training stress the workout gives if ridden as planned (spec §6.3's score, from the steps), kept so that load against plan
   * (plan drift, ADR 0025) can be told for days the plan cache has dropped. null for a workout the tablet cannot play, and for rows
   * written before the column existed.
   */
  plannedLoad: number | null;
  /** skipped: the owner skipped it from the bot. */
  status: "planned" | "skipped";
}

export interface PlanHistory {
  /**
   * Makes the history say what the plan cache says: every cached workout is recorded, and a workout that
   * vanished from the cache while its day has not passed is forgotten (it was deleted, not ridden), unless
   * it was skipped from the bot. The days that passed stay.
   */
  sync(): Promise<void>;
  /** Records that the owner skipped this workout. Call it before the plan loses the workout. */
  markSkipped(entry: Pick<PlanEntry, "id" | "date" | "name" | "workout">): Promise<void>;
  get(id: string): Promise<HistoryRow | null>;
  /** From `from` to `to`, both included, by date then id. */
  between(from: string, to: string): Promise<HistoryRow[]>;
}

/** Rows older than this are removed: the weekly summary looks back two weeks. */
const KEEP_DAYS = 120;

interface Row {
  plan_id: string;
  plan_date: string;
  name: string;
  planned_seconds: number | null;
  planned_load: number | null;
  status: "planned" | "skipped";
}

const COLUMNS =
  "plan_id, plan_date::text AS plan_date, name, planned_seconds, planned_load, status";

const toRow = (row: Row): HistoryRow => ({
  id: row.plan_id,
  date: row.plan_date,
  name: row.name,
  plannedSeconds: row.planned_seconds,
  plannedLoad: row.planned_load,
  status: row.status,
});

const secondsOf = (entry: Pick<PlanEntry, "workout">): number | null =>
  entry.workout === null ? null : workoutDurationSeconds(entry.workout);

const loadOf = (entry: Pick<PlanEntry, "workout">): number | null =>
  entry.workout === null ? null : plannedLoad(entry.workout).estimatedTss;

export function createPlanHistory(options: {
  db: Db;
  plan: PlanStore;
  timezone: string;
  now?: () => Date;
}): PlanHistory {
  const { db, plan, timezone } = options;
  const now = options.now ?? (() => new Date());
  let running: Promise<void> | null = null;
  let again = false;

  async function syncOnce(): Promise<void> {
    const today = localDate(now(), timezone);
    const entries = await plan.entries({ from: "2000-01-01", to: "2999-12-31" });
    await db.transaction(async (tx) => {
      for (const entry of entries) {
        // A skipped workout stays skipped even if a refresh that began before the delete brings it back.
        await tx.query(
          `INSERT INTO plan_history (plan_id, plan_date, name, planned_seconds, planned_load)
           VALUES ($1, $2::date, $3, $4, $5)
           ON CONFLICT (plan_id) DO UPDATE SET
             plan_date = EXCLUDED.plan_date, name = EXCLUDED.name,
             planned_seconds = EXCLUDED.planned_seconds, planned_load = EXCLUDED.planned_load,
             updated_at = now()`,
          [entry.id, entry.date, entry.name, secondsOf(entry), loadOf(entry)],
        );
      }
      await tx.query(
        `DELETE FROM plan_history
          WHERE status = 'planned' AND plan_date >= $1::date AND NOT (plan_id = ANY($2::text[]))`,
        [today, entries.map((entry) => entry.id)],
      );
      await tx.query("DELETE FROM plan_history WHERE plan_date < $1::date", [
        addDaysToIsoDate(today, -KEEP_DAYS),
      ]);
    });
  }

  return {
    sync() {
      // One at a time; a request that comes while one runs makes it run once more afterwards.
      if (running !== null) {
        again = true;
        return running;
      }
      running = (async () => {
        do {
          again = false;
          await syncOnce();
        } while (again);
      })().finally(() => {
        running = null;
      });
      return running;
    },

    async markSkipped(entry) {
      await db.query(
        `INSERT INTO plan_history (plan_id, plan_date, name, planned_seconds, planned_load, status)
         VALUES ($1, $2::date, $3, $4, $5, 'skipped')
         ON CONFLICT (plan_id) DO UPDATE SET status = 'skipped', updated_at = now()`,
        [entry.id, entry.date, entry.name, secondsOf(entry), loadOf(entry)],
      );
    },

    async get(id) {
      const result = await db.query<Row>(`SELECT ${COLUMNS} FROM plan_history WHERE plan_id = $1`, [
        id,
      ]);
      const row = result.rows[0];
      return row === undefined ? null : toRow(row);
    },

    async between(from, to) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM plan_history
          WHERE plan_date BETWEEN $1::date AND $2::date ORDER BY plan_date, plan_id`,
        [from, to],
      );
      return result.rows.map(toRow);
    },
  };
}
