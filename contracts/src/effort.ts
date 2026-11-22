import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { addDaysToIsoDate, isoDateToEpochDay } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: weekly effort (spec §6.3, §8.3, ADR 0042) --------------------------------------------------------------
// Hand-written mirror of schema/effort-response.schema.json, which is the source of truth; the fixtures test keeps the two
// honest. Kotlin's `EffortApiJson` reads the same bodies against the same fixtures.

/** `GET /v1/effort` without `weeks`: twelve weeks. */
export const EFFORT_DEFAULT_WEEKS = 12;
/** The most weeks the endpoint serves. */
export const EFFORT_MAX_WEEKS = 52;
/** The usual range is taken from at most this many of the previous completed weeks ... */
export const EFFORT_USUAL_WEEKS = 8;
/** ... and exists only with at least this many. */
export const EFFORT_MIN_USUAL_WEEKS = 4;
/** The week in progress is not compared with the usual range before this weekday (1 is Monday): Monday and Tuesday are too early. */
export const EFFORT_FIRST_VERDICT_WEEKDAY = 3;

export type EffortKind = "ride" | "watch" | "outdoor";
export type EffortVerdict = "below" | "within" | "above" | "unknown";

export interface EffortActivity {
  /** A ride id, `watch-…` for a workout of the watch or `fit-…` for an outdoor ride imported from a FIT file. */
  id: string;
  kind: EffortKind;
  name?: string;
  /** One decimal. */
  load: number;
  /** The load rests on an estimated threshold or a thin heart rate. */
  approximate: boolean;
}

export interface EffortDay {
  date: string;
  load: number;
  activities: EffortActivity[];
}

export interface EffortWeek {
  /** The Monday, a local date. */
  weekStart: string;
  load: number;
  activityCount: number;
  durationSeconds: number;
  approximate: boolean;
  /** True for the current week only. */
  inProgress: boolean;
  /** From Monday: seven days, or up to today for the week in progress. */
  days: EffortDay[];
}

/** A band of load: the lower and the upper quartile. */
export interface EffortBand {
  low: number;
  high: number;
}

export interface EffortUsual extends EffortBand {
  median: number;
  /** 4 to 8: the completed weeks the range is taken from. */
  basedOnWeeks: number;
}

/** The answer to `GET /v1/effort`. */
export interface EffortResponse {
  /** UTC instant. */
  generatedAt: string;
  /** The weeks ending with the current one, oldest first. */
  weeks: EffortWeek[];
  /** Null with fewer than four completed weeks of history. */
  usual: EffortUsual | null;
  thisWeek: {
    load: number;
    /** What the usual weeks had reached by the same weekday; null when the verdict is unknown. */
    usualSoFar: EffortBand | null;
    verdict: EffortVerdict;
    /** A plain sentence the app shows as it is. */
    verdictText: string;
  };
  note: string;
}

// ---- Validation ------------------------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

const checkEffort: ValidateFunction<EffortResponse> = ajv.compile<EffortResponse>(
  JSON.parse(
    readFileSync(new URL("../schema/effort-response.schema.json", import.meta.url), "utf8"),
  ),
);

const MAX_REPORTED = 20;
/** Loads are one decimal and every sum is of rounded figures: this is only float noise. */
const SUM_TOLERANCE = 0.0501;

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

const isMonday = (date: string): boolean => {
  const day = isoDateToEpochDay(date);
  // 1970-01-01 was a Thursday: day 4 (Monday) is 1970-01-05.
  return day !== null && (((day - 4) % 7) + 7) % 7 === 0;
};

/**
 * What the schema cannot say about the answer (see its description): real dates and instants, Mondays one week apart, the days of a
 * week from its Monday, every sum to the decimal, the approximate flags, the order low, median, high and the verdict against the band.
 */
export function effortConsistencyErrors(response: EffortResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const lastIndex = response.weeks.length - 1;
  for (const [index, week] of response.weeks.entries()) {
    const at = `/weeks/${index}`;
    const real = isoDateToEpochDay(week.weekStart) !== null;
    if (!real) {
      errors.push(`${at}/weekStart is not a real calendar date`);
      continue;
    }
    if (!isMonday(week.weekStart)) errors.push(`${at}/weekStart is not a Monday`);
    const before = response.weeks[index - 1];
    if (before !== undefined && isoDateToEpochDay(before.weekStart) !== null) {
      if (week.weekStart !== addDaysToIsoDate(before.weekStart, 7)) {
        errors.push(`${at}/weekStart does not follow the week before it`);
      }
    }
    if (week.inProgress !== (index === lastIndex)) {
      errors.push(`${at}/inProgress must be true for the last week and only for it`);
    }
    if (!week.inProgress && week.days.length !== 7) {
      errors.push(`${at}/days must have seven days for a completed week`);
    }
    let weekSum = 0;
    let activities = 0;
    let anyApproximate = false;
    for (const [dayIndex, day] of week.days.entries()) {
      const dayAt = `${at}/days/${dayIndex}`;
      if (day.date !== addDaysToIsoDate(week.weekStart, dayIndex)) {
        errors.push(`${dayAt}/date is not the day it should be from the Monday on`);
      }
      let daySum = 0;
      for (const activity of day.activities) {
        daySum += activity.load;
        if (activity.approximate) anyApproximate = true;
      }
      if (Math.abs(daySum - day.load) > SUM_TOLERANCE) {
        errors.push(`${dayAt}/load is not the sum of its activities`);
      }
      weekSum += day.load;
      activities += day.activities.length;
    }
    if (Math.abs(weekSum - week.load) > SUM_TOLERANCE) {
      errors.push(`${at}/load is not the sum of its days`);
    }
    if (activities !== week.activityCount) {
      errors.push(`${at}/activityCount is not the number of its activities`);
    }
    if (anyApproximate && !week.approximate) {
      errors.push(`${at}/approximate must be true when an activity's load is approximate`);
    }
  }

  const { usual, thisWeek } = response;
  if (usual !== null && !(usual.low <= usual.median && usual.median <= usual.high)) {
    errors.push("/usual must have low not above median and median not above high");
  }
  const current = response.weeks[lastIndex];
  if (current !== undefined && Math.abs(current.load - thisWeek.load) > SUM_TOLERANCE) {
    errors.push("/thisWeek/load is not the load of the last week");
  }
  const band = thisWeek.usualSoFar;
  if (band !== null && band.low > band.high) errors.push("/thisWeek/usualSoFar has low above high");
  if (band === null) {
    if (thisWeek.verdict !== "unknown")
      errors.push("/thisWeek/verdict must be unknown without usualSoFar");
  } else {
    if (usual === null) errors.push("/thisWeek/usualSoFar needs a usual range");
    const wanted: EffortVerdict =
      thisWeek.load < band.low ? "below" : thisWeek.load > band.high ? "above" : "within";
    if (thisWeek.verdict !== wanted) {
      errors.push("/thisWeek/verdict does not follow from the load and usualSoFar");
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateEffortResponse(input: unknown): Validation<EffortResponse> {
  if (!checkEffort(input)) return { ok: false, errors: plain(checkEffort.errors) };
  const errors = effortConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
