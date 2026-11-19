import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Validation, Workout } from "./index.ts";

// ---- Sync API: planned workouts (spec §5.1, §8.3, ADR 0016) ----------------------------------
// Hand-written mirror of schema/workouts-response.schema.json, which is the source of truth; the
// fixtures test keeps the two honest.

/** Where a planned workout comes from. */
export type PlanSource = "intervals.icu";

/** One workout of the plan, already converted to the native format. */
export interface PlannedWorkout {
  /** `icu-123` for intervals.icu event 123. Equals `workout.id`. */
  id: string;
  /** The day it is planned for, `YYYY-MM-DD`, in the rider's local time. */
  date: string;
  /** The calendar title; the same as `workout.name` today. */
  name: string;
  source: PlanSource;
  workout: Workout;
  /**
   * The workout's structure hash (spec §11.7, ADR 0026, `workoutHash`), worked out by the service from the workout as it serves it, so the
   * tablet can ask for the previous attempt (`GET /v1/repeats/{hash}/previous`) without computing it (ADR 0034). Absent from an older service.
   */
  structureHash?: string;
}

/** The answer to `GET /v1/workouts?from&to`. */
export interface WorkoutsResponse {
  from: string;
  to: string;
  /** When the plan was last read from its source successfully (UTC), or null when never. */
  generatedAt: string | null;
  /** The last attempt to refresh failed: the workouts are the last known ones. */
  stale: boolean;
  /** false: the service has no plan source, and `workouts` is empty. */
  configured: boolean;
  workouts: PlannedWorkout[];
}

/** The longest range `GET /v1/workouts` serves, in days, both ends included. */
export const MAX_WORKOUTS_RANGE_DAYS = 62;

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Days since 1970-01-01 of a calendar date (proleptic Gregorian), or null if it is not a real date. */
export function isoDateToEpochDay(date: string): number | null {
  const match = DATE.exec(date);
  if (match === null) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  // Four-digit years from 1000: Date.UTC maps years 0 to 99 to 1900 to 1999.
  if (year < 1000 || month < 1 || month > 12 || day < 1) return null;
  const ms = Date.UTC(year, month - 1, day);
  const check = new Date(ms);
  // Date.UTC rolls 2026-02-30 over to March: compare back.
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day
  ) {
    return null;
  }
  return Math.round(ms / 86_400_000);
}

/** `YYYY-MM-DD` is a real calendar date. */
export function isIsoDate(text: string): boolean {
  return isoDateToEpochDay(text) !== null;
}

/** The calendar date `days` after (or before, when negative) `date`. Throws on a date that is not real. */
export function addDaysToIsoDate(date: string, days: number): string {
  const epochDay = isoDateToEpochDay(date);
  if (epochDay === null) throw new Error(`Not a calendar date: ${date}`);
  return new Date((epochDay + days) * 86_400_000).toISOString().slice(0, 10);
}

/** How many days `from` to `to` span, both included (`from == to` is 1), or null if either is not a date. */
export function rangeDays(from: string, to: string): number | null {
  const a = isoDateToEpochDay(from);
  const b = isoDateToEpochDay(to);
  return a === null || b === null ? null : b - a + 1;
}

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

// The response schema points at the workout schema by its file name.
ajv.addSchema(load("workout.schema.json"), "workout.schema.json");
const checkWorkoutsResponse = ajv.compile<WorkoutsResponse>(load("workouts-response.schema.json"));

const MAX_REPORTED = 5;

/**
 * What the schema cannot say (see its description): a real range of at most
 * {@link MAX_WORKOUTS_RANGE_DAYS} days, real dates inside it, unique ids that equal the workouts',
 * and an empty, fresh answer when nothing is configured. Returns the violations, or an empty list.
 */
export function workoutsResponseConsistencyErrors(response: WorkoutsResponse): string[] {
  const errors: string[] = [];
  const days = rangeDays(response.from, response.to);
  if (days === null) {
    errors.push("/from and /to must be real calendar dates");
  } else if (days < 1) {
    errors.push(`/from ${response.from} is after /to ${response.to}`);
  } else if (days > MAX_WORKOUTS_RANGE_DAYS) {
    errors.push(`The range spans ${days} days, at most ${MAX_WORKOUTS_RANGE_DAYS} are served`);
  }
  const ids = new Set<string>();
  for (const [index, entry] of response.workouts.entries()) {
    const at = `/workouts/${index}`;
    const epochDay = isoDateToEpochDay(entry.date);
    if (epochDay === null) {
      errors.push(`${at}/date ${entry.date} is not a real calendar date`);
    } else if (
      days !== null &&
      days >= 1 &&
      (epochDay < (isoDateToEpochDay(response.from) ?? 0) ||
        epochDay > (isoDateToEpochDay(response.to) ?? 0))
    ) {
      errors.push(`${at}/date ${entry.date} is outside ${response.from} to ${response.to}`);
    }
    if (ids.has(entry.id)) errors.push(`${at}/id ${entry.id} is used twice`);
    ids.add(entry.id);
    if (entry.workout.id !== entry.id) {
      errors.push(`${at}/workout/id ${entry.workout.id} differs from the entry's id ${entry.id}`);
    }
  }
  if (!response.configured) {
    if (response.workouts.length > 0) errors.push("/workouts must be empty when not configured");
    if (response.stale) errors.push("/stale must be false when not configured");
    if (response.generatedAt !== null) {
      errors.push("/generatedAt must be null when not configured");
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

/** Validates a planned workouts answer: the schema first, then the rules a schema cannot state. */
export function validateWorkoutsResponse(input: unknown): Validation<WorkoutsResponse> {
  if (!checkWorkoutsResponse(input)) {
    const errors = (checkWorkoutsResponse.errors ?? []).map(
      (e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`,
    );
    return { ok: false, errors };
  }
  const errors = workoutsResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
