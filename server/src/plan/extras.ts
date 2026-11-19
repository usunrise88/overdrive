import {
  type LeafStep,
  type MustSay,
  type PlannedVariant,
  type Step,
  variantStructureHash,
  type Workout,
} from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";

/**
 * What the Overdrive service keeps about a planned workout that intervals.icu cannot hold (spec §11.4, §11.5, §12.1, ADR 0036, ADR 0038):
 * the easier variant, which route backs which `free` step, and what is for the live coach (`live_coach`, `coach_brief` and the steps'
 * `must_say` lines). The calendar holds the primary as text; these are laid over it when the plan is read, never written to it (until a
 * variant is ridden, `variants/service.ts`). The table is `planned_workout_extras` (migrations 0011 and 0012).
 */

/** The variant as stored: the steps and a short note. The hash is not stored: it depends on the primary and is worked out when read. */
export interface StoredVariant {
  steps: Step[];
  note?: string;
}

/** Which route backs which free step: the key is the step's position, `2` for the third step, `1.0` for the first step of the second. */
export type RouteIds = Record<string, string>;

/** The `must_say` lines of the steps, by the step's position as for {@link RouteIds}: `{"1.0": [{"at": "start", "text": "Go."}]}`. */
export type MustSayLines = Record<string, MustSay[]>;

export interface ExtrasRow {
  workoutId: string;
  /** `YYYY-MM-DD`, the planned day. */
  date: string;
  routeIds: RouteIds | null;
  /** The fingerprint of the primary's steps the route ids and the must-say lines were set on. */
  stepsHash: string | null;
  variant: StoredVariant | null;
  /** true: the agent asked for the live coach in this session (spec §12.1). Never false: a session without the coach has none (null). */
  liveCoach: boolean | null;
  /** The agent's brief to the live coach, at most 1000 characters. */
  coachBrief: string | null;
  mustSay: MustSayLines | null;
}

/** What {@link applyExtras} reads. */
export type ExtrasView = Pick<
  ExtrasRow,
  "routeIds" | "stepsHash" | "variant" | "liveCoach" | "coachBrief" | "mustSay"
>;

// ---- Pure helpers ----------------------------------------------------------------------------------------------------

/** The steps without their cues, route ids and spoken lines, which are not part of what a workout asks for. */
function bare(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(bare);
  if (typeof node !== "object" || node === null) return node;
  return Object.fromEntries(
    Object.entries(node)
      .filter(([key]) => key !== "label" && key !== "route_id" && key !== "must_say")
      .map(([key, value]) => [key, bare(value)]),
  );
}

/**
 * A fingerprint of what the steps ask for (types, durations, targets, cadence, in order), without labels, route ids and `must_say`
 * lines: a cue's words may change on the way through the calendar's text, but if anything else changed the route ids and the lines no
 * longer sit on the right steps.
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

/** The `must_say` lines written on the steps, by position. Empty when there are none. */
export function mustSayOfSteps(steps: readonly Step[]): MustSayLines {
  const found: MustSayLines = {};
  for (const [i, step] of steps.entries()) {
    if (step.type === "repeat") {
      for (const [j, inner] of step.steps.entries()) {
        if (inner.must_say !== undefined && inner.must_say.length > 0) {
          found[`${i}.${j}`] = inner.must_say;
        }
      }
    } else if (step.must_say !== undefined && step.must_say.length > 0) {
      found[String(i)] = step.must_say;
    }
  }
  return found;
}

/** The steps with the lines laid on them by position; a position that is not a step (the steps changed) is left alone. */
export function withMustSay(steps: readonly Step[], lines: MustSayLines): Step[] {
  return steps.map((step, i) => {
    if (step.type === "repeat") {
      return {
        ...step,
        steps: step.steps.map((inner, j) => {
          const here = lines[`${i}.${j}`];
          return here === undefined ? inner : { ...inner, must_say: here };
        }),
      };
    }
    const here = lines[String(i)];
    return here === undefined ? step : { ...step, must_say: here };
  });
}

/** The leaf step at a position (`2`, or `1.0` inside a repeat), or null when there is none (or it is a repeat). */
export function stepAtPosition(steps: readonly Step[], position: string): LeafStep | null {
  const [outer, inner] = position.split(".");
  const top = steps[Number(outer)];
  if (top === undefined) return null;
  if (inner === undefined) return top.type === "repeat" ? null : top;
  if (top.type !== "repeat") return null;
  return top.steps[Number(inner)] ?? null;
}

/** The ids of every route a list of steps names. */
export function routeIdList(steps: readonly Step[]): string[] {
  return [...new Set(Object.values(routeIdsOf(steps)))];
}

/**
 * The primary with what the service keeps laid on: the route ids and the `must_say` lines when the extras are still those of its steps,
 * and `live_coach` and `coach_brief`, which belong to the whole session. Each is present only when there is something to say, so a
 * workout nobody gave a route, a variant or a coach brief is served exactly as before.
 */
export function applyExtras(
  workout: Workout,
  extras: Partial<ExtrasView> | null,
): { workout: Workout; variant?: PlannedVariant } {
  if (extras === null) return { workout };
  let result = workout;
  const stepsMatch =
    extras.stepsHash !== undefined &&
    extras.stepsHash !== null &&
    extras.stepsHash === stepsFingerprint(workout.steps);
  let steps = workout.steps;
  if (stepsMatch && extras.routeIds != null && Object.keys(extras.routeIds).length > 0) {
    steps = withRouteIds(steps, extras.routeIds);
  }
  if (stepsMatch && extras.mustSay != null && Object.keys(extras.mustSay).length > 0) {
    steps = withMustSay(steps, extras.mustSay);
  }
  if (steps !== workout.steps) result = { ...result, steps };
  if (extras.liveCoach != null) result = { ...result, live_coach: extras.liveCoach };
  if (extras.coachBrief != null) result = { ...result, coach_brief: extras.coachBrief };
  if (extras.variant == null) return { workout: result };
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
  live_coach: boolean | null;
  coach_brief: string | null;
  must_say: MustSayLines | null;
}

const COLUMNS =
  "workout_id, plan_date::text AS plan_date, route_ids, steps_hash, variant, live_coach, coach_brief, must_say";

const toRow = (row: Row): ExtrasRow => ({
  workoutId: row.workout_id,
  date: row.plan_date,
  routeIds: row.route_ids,
  stepsHash: row.steps_hash,
  variant: row.variant,
  liveCoach: row.live_coach,
  coachBrief: row.coach_brief,
  mustSay: row.must_say,
});

/** What a change may set: any of the fields of a row but its key and its day. */
type Fields = Omit<ExtrasRow, "workoutId" | "date">;

const NOTHING: Fields = {
  routeIds: null,
  stepsHash: null,
  variant: null,
  liveCoach: null,
  coachBrief: null,
  mustSay: null,
};

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
  /**
   * Changes what is for the live coach. A field that is absent is left as it is; `liveCoach` false and `coachBrief` null clear; `mustSay`
   * null removes every line and a value replaces them, valid for steps with this fingerprint.
   */
  setCoach(
    workoutId: string,
    date: string,
    change: {
      liveCoach?: boolean | null;
      coachBrief?: string | null;
      mustSay?: { lines: MustSayLines; stepsHash: string } | null;
    },
  ): Promise<void>;
  /** The workout is on another day now: the variant is dropped, the route ids and the live coach's part stay. */
  moved(workoutId: string, date: string): Promise<boolean>;
  /** Removes everything kept about the workout; true if there was something. */
  remove(workoutId: string): Promise<boolean>;
  /** A route was deleted: its id is taken off every workout; the ids of the workouts that changed. */
  forgetRoute(routeId: string): Promise<string[]>;
  /** Removes the rows of days before `date` (`YYYY-MM-DD`); how many. */
  sweep(before: string): Promise<number>;
}

export function createExtrasStore(db: Db): ExtrasStore {
  /**
   * Reads the row under lock, applies `change` to it (the fields it returns replace the row's), keeps the rows' own rules (the
   * fingerprint is kept only while a position-keyed field needs it; a row with nothing in it is deleted) and writes it back.
   */
  async function upsert(
    tx: Queryable,
    workoutId: string,
    date: string,
    change: (current: ExtrasRow | null) => Partial<Fields>,
  ): Promise<void> {
    const found = await tx.query<Row>(
      `SELECT ${COLUMNS} FROM planned_workout_extras WHERE workout_id = $1 FOR UPDATE`,
      [workoutId],
    );
    const current = found.rows[0] === undefined ? null : toRow(found.rows[0]);
    const next: Fields = {
      ...NOTHING,
      ...(current === null
        ? {}
        : {
            routeIds: current.routeIds,
            stepsHash: current.stepsHash,
            variant: current.variant,
            liveCoach: current.liveCoach,
            coachBrief: current.coachBrief,
            mustSay: current.mustSay,
          }),
      ...change(current),
    };
    if (next.routeIds !== null && Object.keys(next.routeIds).length === 0) next.routeIds = null;
    if (next.mustSay !== null && Object.keys(next.mustSay).length === 0) next.mustSay = null;
    if (next.liveCoach !== true) next.liveCoach = null;
    if (next.routeIds === null && next.mustSay === null) next.stepsHash = null;
    const empty =
      next.routeIds === null &&
      next.variant === null &&
      next.liveCoach === null &&
      next.coachBrief === null &&
      next.mustSay === null;
    if (empty) {
      if (current !== null) {
        await tx.query("DELETE FROM planned_workout_extras WHERE workout_id = $1", [workoutId]);
      }
      return;
    }
    await tx.query(
      `INSERT INTO planned_workout_extras
         (workout_id, plan_date, route_ids, steps_hash, variant, live_coach, coach_brief, must_say, updated_at)
       VALUES ($1, $2::date, $3::jsonb, $4, $5::jsonb, $6, $7, $8::jsonb, now())
       ON CONFLICT (workout_id) DO UPDATE SET
         plan_date = EXCLUDED.plan_date, route_ids = EXCLUDED.route_ids, steps_hash = EXCLUDED.steps_hash,
         variant = EXCLUDED.variant, live_coach = EXCLUDED.live_coach, coach_brief = EXCLUDED.coach_brief,
         must_say = EXCLUDED.must_say, updated_at = now()`,
      [
        workoutId,
        date,
        next.routeIds === null ? null : JSON.stringify(next.routeIds),
        next.stepsHash,
        next.variant === null ? null : JSON.stringify(next.variant),
        next.liveCoach,
        next.coachBrief,
        next.mustSay === null ? null : JSON.stringify(next.mustSay),
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
        upsert(tx, workoutId, date, (current) => {
          if (routes === null) return { routeIds: null };
          // The lines and the ids sit on the same steps: lines set for other steps cannot be placed any more.
          const staleLines = current?.mustSay != null && current.stepsHash !== routes.stepsHash;
          return {
            routeIds: routes.routeIds,
            stepsHash: routes.stepsHash,
            ...(staleLines ? { mustSay: null } : {}),
          };
        }),
      );
    },

    async setVariant(workoutId, date, variant) {
      await db.transaction((tx) => upsert(tx, workoutId, date, () => ({ variant })));
    },

    async setCoach(workoutId, date, change) {
      await db.transaction((tx) =>
        upsert(tx, workoutId, date, (current) => {
          const patch: Partial<Fields> = {};
          if (change.liveCoach !== undefined)
            patch.liveCoach = change.liveCoach === true ? true : null;
          if (change.coachBrief !== undefined) patch.coachBrief = change.coachBrief;
          if (change.mustSay !== undefined) {
            if (change.mustSay === null) {
              patch.mustSay = null;
            } else {
              patch.mustSay = change.mustSay.lines;
              patch.stepsHash = change.mustSay.stepsHash;
              // The ids and the lines sit on the same steps: ids set for other steps cannot be placed any more.
              if (current?.routeIds != null && current.stepsHash !== change.mustSay.stepsHash) {
                patch.routeIds = null;
              }
            }
          }
          return patch;
        }),
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
        await upsert(tx, workoutId, date, () => ({ variant: null }));
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
          await upsert(tx, row.workout_id, row.plan_date, () => ({
            routeIds: Object.keys(kept).length === 0 ? null : kept,
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
