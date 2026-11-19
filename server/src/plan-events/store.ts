import type { PlanEventKind, PlanEventStatus } from "@overdrive/contracts";
import type { Db } from "../db.ts";

/**
 * The plan events in PostgreSQL (`plan_events`, migration 0010, ADR 0035). A detection is idempotent by its natural key: the same
 * key is the same event however often it is found. Nothing is deleted; an event is open until the coach agent resolves it.
 */

export interface PlanEventRow {
  id: number;
  naturalKey: string;
  kind: PlanEventKind;
  /** `YYYY-MM-DD`, the rider's local day. */
  day: string;
  workoutId: string | null;
  detail: Record<string, unknown>;
  status: PlanEventStatus;
  createdAt: Date;
  resolvedAt: Date | null;
  resolutionNote: string | null;
  dropped: boolean | null;
}

export interface NewPlanEvent {
  naturalKey: string;
  kind: PlanEventKind;
  day: string;
  workoutId: string | null;
  detail: Record<string, unknown>;
  at: Date;
}

export interface PlanEventQuery {
  status: PlanEventStatus | "all";
  kind?: PlanEventKind | undefined;
  limit: number;
}

export interface PlanEventStore {
  /** Stores the event unless one with the same natural key exists; says which. */
  insert(event: NewPlanEvent): Promise<{ event: PlanEventRow; created: boolean }>;
  get(id: number): Promise<PlanEventRow | null>;
  /** Open ones oldest first (the order to deal with them in); the others newest first. */
  list(query: PlanEventQuery): Promise<{ events: PlanEventRow[]; total: number }>;
  /** Resolves an open event; null when there is no such event, `changed: false` when it was already resolved (nothing is touched). */
  resolve(
    id: number,
    resolution: { note: string; dropped: boolean; at: Date },
  ): Promise<{ event: PlanEventRow; changed: boolean } | null>;
  /** Events resolved at or after `sinceMs`, oldest first. */
  resolvedSince(sinceMs: number): Promise<PlanEventRow[]>;
  /** The last local day the end-of-day check has looked at, or null before the first check. */
  checkedThrough(): Promise<string | null>;
  setCheckedThrough(day: string): Promise<void>;
}

interface Row {
  id: number;
  natural_key: string;
  kind: PlanEventKind;
  day: string;
  workout_id: string | null;
  detail: Record<string, unknown> | string;
  status: PlanEventStatus;
  created_at: Date | string;
  resolved_at: Date | string | null;
  resolution_note: string | null;
  dropped: boolean | null;
}

const COLUMNS = `id, natural_key, kind, day::text AS day, workout_id, detail, status, created_at,
                 resolved_at, resolution_note, dropped`;

function toRow(row: Row): PlanEventRow {
  return {
    id: row.id,
    naturalKey: row.natural_key,
    kind: row.kind,
    day: row.day,
    workoutId: row.workout_id,
    detail: (typeof row.detail === "string"
      ? (JSON.parse(row.detail) as unknown)
      : row.detail) as Record<string, unknown>,
    status: row.status,
    createdAt: new Date(row.created_at),
    resolvedAt: row.resolved_at === null ? null : new Date(row.resolved_at),
    resolutionNote: row.resolution_note,
    dropped: row.dropped,
  };
}

export function createPlanEventStore(db: Db): PlanEventStore {
  async function get(id: number): Promise<PlanEventRow | null> {
    const result = await db.query<Row>(`SELECT ${COLUMNS} FROM plan_events WHERE id = $1`, [id]);
    const row = result.rows[0];
    return row === undefined ? null : toRow(row);
  }

  return {
    async insert(event) {
      const inserted = await db.query<Row>(
        `INSERT INTO plan_events (natural_key, kind, day, workout_id, detail, created_at)
         VALUES ($1, $2, $3::date, $4, $5::jsonb, $6::timestamptz)
         ON CONFLICT (natural_key) DO NOTHING
         RETURNING ${COLUMNS}`,
        [
          event.naturalKey,
          event.kind,
          event.day,
          event.workoutId,
          JSON.stringify(event.detail),
          event.at.toISOString(),
        ],
      );
      const fresh = inserted.rows[0];
      if (fresh !== undefined) return { event: toRow(fresh), created: true };
      const existing = await db.query<Row>(
        `SELECT ${COLUMNS} FROM plan_events WHERE natural_key = $1`,
        [event.naturalKey],
      );
      const row = existing.rows[0];
      if (row === undefined) throw new Error("the plan event was neither stored nor found");
      return { event: toRow(row), created: false };
    },

    get,

    async list(query) {
      const conditions: string[] = [];
      const params: unknown[] = [];
      if (query.status !== "all") {
        params.push(query.status);
        conditions.push(`status = $${params.length}`);
      }
      if (query.kind !== undefined) {
        params.push(query.kind);
        conditions.push(`kind = $${params.length}`);
      }
      const where = conditions.length === 0 ? "" : `WHERE ${conditions.join(" AND ")}`;
      const order =
        query.status === "open" ? "ORDER BY created_at, id" : "ORDER BY created_at DESC, id DESC";
      const total = await db.query<{ n: string }>(
        `SELECT count(*)::text AS n FROM plan_events ${where}`,
        params,
      );
      params.push(query.limit);
      const rows = await db.query<Row>(
        `SELECT ${COLUMNS} FROM plan_events ${where} ${order} LIMIT $${params.length}`,
        params,
      );
      return { events: rows.rows.map(toRow), total: Number(total.rows[0]?.n ?? 0) };
    },

    async resolve(id, resolution) {
      const updated = await db.query<Row>(
        `UPDATE plan_events
            SET status = 'resolved', resolved_at = $2::timestamptz, resolution_note = $3, dropped = $4
          WHERE id = $1 AND status = 'open'
          RETURNING ${COLUMNS}`,
        [id, resolution.at.toISOString(), resolution.note, resolution.dropped],
      );
      const row = updated.rows[0];
      if (row !== undefined) return { event: toRow(row), changed: true };
      const existing = await get(id);
      return existing === null ? null : { event: existing, changed: false };
    },

    async resolvedSince(sinceMs) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM plan_events
          WHERE status = 'resolved' AND resolved_at >= $1::timestamptz
          ORDER BY resolved_at, id`,
        [new Date(sinceMs).toISOString()],
      );
      return result.rows.map(toRow);
    },

    async checkedThrough() {
      const result = await db.query<{ day: string }>(
        "SELECT checked_through::text AS day FROM plan_event_cursor WHERE id",
      );
      return result.rows[0]?.day ?? null;
    },

    async setCheckedThrough(day) {
      await db.query(
        `INSERT INTO plan_event_cursor (id, checked_through) VALUES (true, $1::date)
         ON CONFLICT (id) DO UPDATE SET checked_through = EXCLUDED.checked_through`,
        [day],
      );
    },
  };
}
