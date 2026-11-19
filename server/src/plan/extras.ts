import {
  type PlannedVariant,
  type Step,
  variantStructureHash,
  type Workout,
} from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";

/**
 * What the Overdrive service keeps about a planned workout that intervals.icu cannot hold (spec §11.4, §11.5, ADR 0036): the easier
 * variant, and which route backs which `free` step. The calendar holds the primary as text; these are laid over it when the plan is
 * read, never written to it (until a variant is ridden, `variants/service.ts`). The table is `planned_workout_extras` (migration 0011).
 */

/** The variant as stored: the steps and a short note. The hash is not stored: it depends on the primary and is worked out when read. */
export interface StoredVariant {
  steps: Step[];
  note?: string;
}

/** Which route backs which free step: the key is the step's position, `2` for the third step, `1.0` for the first step of the second. */
export type RouteIds = Record<string, string>;

export interface ExtrasRow {
  workoutId: string;
  /** `YYYY-MM-DD`, the planned day. */
  date: string;
  routeIds: RouteIds | null;
  /** The fingerprint of the primary's steps the route ids were set on. */
  stepsHash: string | null;
  variant: StoredVariant | null;
}

// ---- Pure helpers ----------------------------------------------------------------------------------------------------

/** The steps without their cues and route ids, which are not part of what a workout asks for. */
function bare(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(bare);
  if (typeof node !== "object" || node === null) return node;
  return Object.fromEntries(
    Object.entries(node)
      .filter(([key]) => key !== "label" && key !== "route_id")
      .map(([key, value]) => [key, bare(value)]),
  );
}

/**
 * A fingerprint of what the steps ask for (types, durations, targets, cadence, in order), without labels and route ids: a cue's words
 * may change on the way through the calendar's text, but if anything else changed the route ids no longer sit on the right steps.
 */
export function stepsFingerprint(steps: readonly Step[]): string {
  return sha256Hex(canonicalJson(bare(steps))).slice(0, 16);
}

/** The route ids written on the `free` steps, by position. Empty when there are none. */
export function routeIdsOf(steps: readonly Step[]): RouteIds {
  const found: RouteIds = {};
  for (const [i, step] of steps.entries()) {
    if (step.type === "free" && step.route_id !== undefined) found[String(i)] = step.route_id;
    if (step.type === "repeat") {
      for (const [j, inner] of step.steps.entries()) {
        if (inner.type === "free" && inner.route_id !== undefined) {
          found[`${i}.${j}`] = inner.route_id;
        }
      }
    }
  }
  return found;
}

/** The steps with the route ids laid on their `free` steps; a position that is not a free step (the steps changed) is left alone. */
export function withRouteIds(steps: readonly Step[], routeIds: RouteIds): Step[] {
  return steps.map((step, i) => {
    if (step.type === "free") {
      const id = routeIds[String(i)];
      return id === undefined ? step : { ...step, route_id: id };
    }
    if (step.type === "repeat") {
      return {
        ...step,
        steps: step.steps.map((inner, j) => {
          const id = routeIds[`${i}.${j}`];
          return inner.type === "free" && id !== undefined ? { ...inner, route_id: id } : inner;
        }),
      };
    }
    return step;
  });
}

/** The ids of every route a list of steps names. */
export function routeIdList(steps: readonly Step[]): string[] {
  return [...new Set(Object.values(routeIdsOf(steps)))];
}

/** The primary with its route ids laid on, when the extras are still those of its steps. */
export function applyExtras(
  workout: Workout,
  extras: Pick<ExtrasRow, "routeIds" | "stepsHash" | "variant"> | null,
): { workout: Workout; variant?: PlannedVariant } {
  if (extras === null) return { workout };
  let result = workout;
  if (
    extras.routeIds !== null &&
    Object.keys(extras.routeIds).length > 0 &&
    extras.stepsHash === stepsFingerprint(workout.steps)
  ) {
    result = { ...workout, steps: withRouteIds(workout.steps, extras.routeIds) };
  }
  if (extras.variant === null) return { workout: result };
  return {
    workout: result,
    variant: {
      steps: extras.variant.steps,
      ...(extras.variant.note === undefined ? {} : { note: extras.variant.note }),
      structureHash: variantStructureHash(result, extras.variant.steps),
    },
  };
}

/** The variant with the route taken off the free steps that named it; the same object when none did. */
function withoutRoute(variant: StoredVariant, routeId: string): StoredVariant {
  const strip = (step: Step): Step => {
    if (step.type === "free" && step.route_id === routeId) {
      const { route_id: _gone, ...rest } = step;
      return rest;
    }
    if (step.type === "repeat") {
      const inner = step.steps.map(strip) as typeof step.steps;
      return inner.some((s, i) => s !== step.steps[i]) ? { ...step, steps: inner } : step;
    }
    return step;
  };
  const steps = variant.steps.map(strip);
  return steps.some((s, i) => s !== variant.steps[i]) ? { ...variant, steps } : variant;
}

// ---- The store -------------------------------------------------------------------------------------------------------

interface Row {
  workout_id: string;
  plan_date: string;
  route_ids: RouteIds | null;
  steps_hash: string | null;
  variant: StoredVariant | null;
}

const COLUMNS = "workout_id, plan_date::text AS plan_date, route_ids, steps_hash, variant";

const toRow = (row: Row): ExtrasRow => ({
  workoutId: row.workout_id,
  date: row.plan_date,
  routeIds: row.route_ids,
  stepsHash: row.steps_hash,
  variant: row.variant,
});

export interface ExtrasStore {
  get(workoutId: string): Promise<ExtrasRow | null>;
  forIds(ids: readonly string[]): Promise<Map<string, ExtrasRow>>;
  /** Sets (or with `null` clears) the route ids of a workout, valid for steps with this fingerprint. */
  setRoutes(
    workoutId: string,
    date: string,
    routes: { routeIds: RouteIds; stepsHash: string } | null,
  ): Promise<void>;
  /** Sets or, with `null`, removes the variant. */
  setVariant(workoutId: string, date: string, variant: StoredVariant | null): Promise<void>;
  /** The workout is on another day now: the variant is dropped, the route ids stay. */
  moved(workoutId: string, date: string): Promise<boolean>;
  /** Removes everything kept about the workout; true if there was something. */
  remove(workoutId: string): Promise<boolean>;
  /** A route was deleted: its id is taken off every workout; the ids of the workouts that changed. */
  forgetRoute(routeId: string): Promise<string[]>;
  /** Removes the rows of days before `date` (`YYYY-MM-DD`); how many. */
  sweep(before: string): Promise<number>;
}

export function createExtrasStore(db: Db): ExtrasStore {
  async function upsert(
    tx: Queryable,
    workoutId: string,
    date: string,
    change: (current: ExtrasRow | null) => Omit<ExtrasRow, "workoutId" | "date"> | null,
  ): Promise<void> {
    const found = await tx.query<Row>(
      `SELECT ${COLUMNS} FROM planned_workout_extras WHERE workout_id = $1 FOR UPDATE`,
      [workoutId],
    );
    const current = found.rows[0] === undefined ? null : toRow(found.rows[0]);
    const next = change(current);
    if (next === null || (next.routeIds === null && next.variant === null)) {
      if (current !== null) {
        await tx.query("DELETE FROM planned_workout_extras WHERE workout_id = $1", [workoutId]);
      }
      return;
    }
    await tx.query(
      `INSERT INTO planned_workout_extras (workout_id, plan_date, route_ids, steps_hash, variant, updated_at)
       VALUES ($1, $2::date, $3::jsonb, $4, $5::jsonb, now())
       ON CONFLICT (workout_id) DO UPDATE SET
         plan_date = EXCLUDED.plan_date, route_ids = EXCLUDED.route_ids, steps_hash = EXCLUDED.steps_hash,
         variant = EXCLUDED.variant, updated_at = now()`,
      [
        workoutId,
        date,
        next.routeIds === null ? null : JSON.stringify(next.routeIds),
        next.routeIds === null ? null : next.stepsHash,
        next.variant === null ? null : JSON.stringify(next.variant),
      ],
    );
  }

  return {
    async get(workoutId) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM planned_workout_extras WHERE workout_id = $1`,
        [workoutId],
      );
      return result.rows[0] === undefined ? null : toRow(result.rows[0]);
    },

    async forIds(ids) {
      const map = new Map<string, ExtrasRow>();
      if (ids.length === 0) return map;
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM planned_workout_extras WHERE workout_id = ANY($1::text[])`,
        [[...ids]],
      );
      for (const row of result.rows) map.set(row.workout_id, toRow(row));
      return map;
    },

    async setRoutes(workoutId, date, routes) {
      await db.transaction((tx) =>
        upsert(tx, workoutId, date, (current) => ({
          routeIds: routes === null ? null : routes.routeIds,
          stepsHash: routes === null ? null : routes.stepsHash,
          variant: current?.variant ?? null,
        })),
      );
    },

    async setVariant(workoutId, date, variant) {
      await db.transaction((tx) =>
        upsert(tx, workoutId, date, (current) => ({
          routeIds: current?.routeIds ?? null,
          stepsHash: current?.stepsHash ?? null,
          variant,
        })),
      );
    },

    async moved(workoutId, date) {
      return db.transaction(async (tx) => {
        const found = await tx.query<Row>(
          `SELECT ${COLUMNS} FROM planned_workout_extras WHERE workout_id = $1 FOR UPDATE`,
          [workoutId],
        );
        const current = found.rows[0];
        if (current === undefined) return false;
        const hadVariant = current.variant !== null;
        await upsert(tx, workoutId, date, (row) => ({
          routeIds: row?.routeIds ?? null,
          stepsHash: row?.stepsHash ?? null,
          variant: null,
        }));
        return hadVariant;
      });
    },

    async remove(workoutId) {
      const result = await db.query(
        "DELETE FROM planned_workout_extras WHERE workout_id = $1 RETURNING workout_id",
        [workoutId],
      );
      return result.rows.length > 0;
    },

    async forgetRoute(routeId) {
      return db.transaction(async (tx) => {
        const found = await tx.query<Row>(
          `SELECT ${COLUMNS} FROM planned_workout_extras
            WHERE route_ids IS NOT NULL OR variant IS NOT NULL FOR UPDATE`,
        );
        const changed: string[] = [];
        for (const row of found.rows) {
          const ids = row.route_ids ?? {};
          const kept = Object.fromEntries(Object.entries(ids).filter(([, id]) => id !== routeId));
          const idsChanged = Object.keys(kept).length !== Object.keys(ids).length;
          // The variant's own free steps may name the route as well.
          const variant = row.variant === null ? null : withoutRoute(row.variant, routeId);
          if (!idsChanged && variant === row.variant) continue;
          changed.push(row.workout_id);
          await upsert(tx, row.workout_id, row.plan_date, (current) => ({
            routeIds: Object.keys(kept).length === 0 ? null : kept,
            stepsHash: Object.keys(kept).length === 0 ? null : (current?.stepsHash ?? null),
            variant,
          }));
        }
        return changed;
      });
    },

    async sweep(before) {
      const result = await db.query(
        "DELETE FROM planned_workout_extras WHERE plan_date < $1::date RETURNING workout_id",
        [before],
      );
      return result.rows.length;
    },
  };
}
