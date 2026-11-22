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
/** A week's usual range is taken from the 8 complete weeks before it, and exists only with all 8. */
export const EFFORT_WEEKS_NEEDED = 8;

export type EffortKind = "ride" | "activity";
export type EffortVerdict = "above" | "within" | "below" | "none" | "learning";

export interface EffortActivity {
  /** A ride id, `watch-…` for a workout of the watch or `fit-…` for an outdoor ride imported from a FIT file. */
  id: string;
  kind: EffortKind;
  /** Null for a ride; for an activity the watch's type, or `cycling-outdoor` for a ride imported from a FIT file. */
  sport: string | null;
  name: string;
  /** The rider's local day. */
  date: string;
  /** One decimal; null when it could not be worked out (no heart rate, or no limits to scale it by). */
  load: number | null;
  /** The load comes from heart rate. */
  approximate: boolean;
  /** The load's share of the week's load, 0 to 1, three decimals; null when `load` is. */
  share: number | null;
}

export interface EffortDay {
  date: string;
  load: number;
}

export interface EffortWeek {
  /** The Monday, a local date. */
  weekStart: string;
  load: number;
  /** Any of the load comes from heart rate. */
  approximate: boolean;
  /** True for the week in progress, the last one. */
  current: boolean;
  /** The mean minus one standard deviation of the 8 complete weeks before this one (not below 0); null while learning. */
  usualLow: number | null;
  /** The mean plus one standard deviation; null while learning. */
  usualHigh: number | null;
  /** How many complete weeks of training precede this one, at most {@link EFFORT_WEEKS_NEEDED}. */
  weeksLogged: number;
  verdict: EffortVerdict;
  /** Monday to Sunday: seven days. */
  days: EffortDay[];
  /** Oldest first. */
  activities: EffortActivity[];
}

/** The answer to `GET /v1/effort`. */
export interface EffortResponse {
  /** UTC instant. */
  generatedAt: string;
  weeksNeeded: 8;
  /** The current week's `weeksLogged`. */
  weeksLogged: number;
  /** The weeks ending with the current one, oldest first. */
  weeks: EffortWeek[];
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
const SHARE_TOLERANCE = 0.0006;

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
 * week from its Monday, every sum and share, the approximate flags, the usual range against `weeksLogged`, and the verdict against the
 * load and the range.
 */
export function effortConsistencyErrors(response: EffortResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const lastIndex = response.weeks.length - 1;
  let before = -1;
  for (const [index, week] of response.weeks.entries()) {
    const at = `/weeks/${index}`;
    if (isoDateToEpochDay(week.weekStart) === null) {
      errors.push(`${at}/weekStart is not a real calendar date`);
      continue;
    }
    if (!isMonday(week.weekStart)) errors.push(`${at}/weekStart is not a Monday`);
    const previous = response.weeks[index - 1];
    if (previous !== undefined && isoDateToEpochDay(previous.weekStart) !== null) {
      if (week.weekStart !== addDaysToIsoDate(previous.weekStart, 7)) {
        errors.push(`${at}/weekStart does not follow the week before it`);
      }
    }
    if (week.current !== (index === lastIndex)) {
      errors.push(`${at}/current must be true for the last week and only for it`);
    }

    let weekSum = 0;
    for (const [dayIndex, day] of week.days.entries()) {
      if (day.date !== addDaysToIsoDate(week.weekStart, dayIndex)) {
        errors.push(`${at}/days/${dayIndex}/date is not the day it should be from the Monday on`);
      }
      weekSum += day.load;
    }
    if (Math.abs(weekSum - week.load) > SUM_TOLERANCE) {
      errors.push(`${at}/load is not the sum of its days`);
    }

    const daySums = new Map<string, number>();
    let anyApproximate = false;
    let lastDate = "";
    for (const [activityIndex, activity] of week.activities.entries()) {
      const where = `${at}/activities/${activityIndex}`;
      if (isoDateToEpochDay(activity.date) === null) {
        errors.push(`${where}/date is not a real calendar date`);
        continue;
      }
      if (!week.days.some((day) => day.date === activity.date)) {
        errors.push(`${where}/date is not a day of its week`);
      }
      if (activity.date < lastDate) errors.push(`${where}/date is before the activity before it`);
      lastDate = activity.date;
      if (activity.kind === "ride" && activity.sport !== null) {
        errors.push(`${where}/sport must be null for a ride`);
      }
      if (activity.kind === "activity" && activity.sport === null) {
        errors.push(`${where}/sport is needed for an activity`);
      }
      if (activity.approximate) anyApproximate = true;
      if (activity.load === null) {
        if (activity.share !== null) errors.push(`${where}/share must be null without a load`);
      } else {
        daySums.set(activity.date, (daySums.get(activity.date) ?? 0) + activity.load);
        if (activity.share === null) {
          errors.push(`${where}/share is needed with a load`);
        } else {
          const wanted = week.load === 0 ? 0 : activity.load / week.load;
          if (Math.abs(activity.share - wanted) > SHARE_TOLERANCE) {
            errors.push(`${where}/share is not the activity's load over the week's`);
          }
        }
      }
    }
    for (const day of week.days) {
      if (Math.abs((daySums.get(day.date) ?? 0) - day.load) > SUM_TOLERANCE) {
        errors.push(`${at}/days is not the sum of the activities on ${day.date}`);
      }
    }
    if (anyApproximate !== week.approximate) {
      errors.push(`${at}/approximate is true exactly when one of its activities is`);
    }

    const { usualLow: low, usualHigh: high } = week;
    if ((low === null) !== (high === null)) {
      errors.push(`${at}/usualLow and /usualHigh must both be numbers or both null`);
    }
    if (low !== null && high !== null && low > high) {
      errors.push(`${at}/usualLow is above /usualHigh`);
    }
    const learning = week.weeksLogged < EFFORT_WEEKS_NEEDED;
    if (learning !== (low === null)) {
      errors.push(`${at} has a usual range exactly when weeksLogged has reached weeksNeeded`);
    }
    if (week.weeksLogged < before) errors.push(`${at}/weeksLogged falls from the week before`);
    before = week.weeksLogged;

    let wanted: EffortVerdict;
    if (low === null || high === null) wanted = "learning";
    else if (week.current && week.activities.length === 0) wanted = "none";
    else wanted = week.load < low ? "below" : week.load > high ? "above" : "within";
    if (week.verdict !== wanted) {
      errors.push(`${at}/verdict does not follow from the load and the usual range`);
    }
  }
  const current = response.weeks[lastIndex];
  if (current !== undefined && current.weeksLogged !== response.weeksLogged) {
    errors.push("/weeksLogged is not the current week's");
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateEffortResponse(input: unknown): Validation<EffortResponse> {
  if (!checkEffort(input)) return { ok: false, errors: plain(checkEffort.errors) };
  const errors = effortConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
