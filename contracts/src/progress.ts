import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { addDaysToIsoDate, isIsoDate, isoDateToEpochDay } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: progress over time (spec §6.3, §8.3, ADR 0043) -----------------------------------------------------
// Hand-written mirror of schema/progress-response.schema.json, which is the source of truth; the fixtures test keeps the two
// honest. Kotlin's `ProgressApiJson` reads and writes the same bodies against the same fixtures.
//
// HEALTH DATA IS PRIVATE: no message built here quotes a figure, only paths and rules.

/** The longest range `GET /v1/progress` serves, in whole years back from `to`. */
export const PROGRESS_MAX_RANGE_YEARS = 5;
/** Without `from` and `to`: this many months back from today. */
export const PROGRESS_DEFAULT_MONTHS = 6;

/**
 * The rules of a steady stretch (ADR 0043): a run of seconds of a ride, not paused, after the ride's first
 * {@link PROGRESS_STEADY_SKIP_SECONDS}, each with a power of at least {@link PROGRESS_STEADY_MIN_POWER_PERCENT} of the ride's FTP,
 * at least {@link PROGRESS_STEADY_MIN_SECONDS} long, whose 30-second rolling means of power have a coefficient of variation under
 * {@link PROGRESS_STEADY_MAX_CV_PERCENT} and none outside {@link PROGRESS_STEADY_BAND_PERCENT} of the stretch's mean power, and with
 * a heart rate in at least {@link PROGRESS_STEADY_MIN_HEART_RATE_PERCENT} of its seconds.
 */
export const PROGRESS_STEADY_MIN_SECONDS = 300;
export const PROGRESS_STEADY_SKIP_SECONDS = 60;
export const PROGRESS_STEADY_MIN_POWER_PERCENT = 40;
export const PROGRESS_STEADY_MAX_CV_PERCENT = 8;
export const PROGRESS_STEADY_BAND_PERCENT = 10;
export const PROGRESS_STEADY_MIN_HEART_RATE_PERCENT = 90;
/** The reference power is this percent of the FTP at the range's start, rounded to {@link PROGRESS_REFERENCE_POWER_ROUND} W. */
export const PROGRESS_REFERENCE_POWER_PERCENT = 70;
export const PROGRESS_REFERENCE_POWER_ROUND = 5;
/** The reference heart rate is this percent of the threshold heart rate (else the maximum), rounded to 5 bpm. */
export const PROGRESS_REFERENCE_HEART_RATE_PERCENT = 80;
export const PROGRESS_REFERENCE_HEART_RATE_ROUND = 5;
/** A stretch counts for the heart rate at the reference power when its mean power is within this percent of it. */
export const PROGRESS_POWER_MATCH_PERCENT = 5;
/** A stretch counts for the power at the reference heart rate when its mean heart rate is within this many bpm of it. */
export const PROGRESS_HEART_RATE_MATCH_BPM = 3;
/** The steady parts of a ride must add up to this many seconds for an efficiency factor. */
export const PROGRESS_EF_MIN_SECONDS = 1200;
/** A week has a resting heart rate with this many nights, a weight with this many weighings. */
export const PROGRESS_MIN_NIGHTS = 3;
export const PROGRESS_MIN_WEIGHINGS = 2;
/** The mean of this many rides is the rolling mean and the ends of the ride measures' trends. */
export const PROGRESS_ROLLING_RIDES = 5;

/** The measures of the page, in the order the headline prefers them when their trends are equally clear. */
export const PROGRESS_MEASURES = [
  "aerobicEfficiency",
  "bestPower",
  "efficiencyFactor",
  "restingHeartRate",
  "decoupling",
  "weight",
] as const;
export type ProgressMeasure = (typeof PROGRESS_MEASURES)[number];

/** The fewest points with a figure for a verdict, by measure (the design's table, `design/companion.md` §6.13). */
export const PROGRESS_MIN_POINTS: Readonly<Record<ProgressMeasure, number>> = {
  bestPower: 3,
  aerobicEfficiency: 4,
  efficiencyFactor: 5,
  decoupling: 5,
  restingHeartRate: 4,
  weight: 4,
};

/** The standing caution of every heart-rate measure (the `note` of its trend). */
export const PROGRESS_NOTE =
  "Heart rate also moves with heat, sleep and caffeine, so trust a trend over several weeks, not one ride.";

// ---- Types -------------------------------------------------------------------------------------------------------

export type ProgressDirection = "improving" | "worsening" | "steady" | "unknown";
export type ProgressUnit = "W" | "bpm" | "W/kg" | "W/bpm" | "%";

export interface ProgressTrend {
  direction: ProgressDirection;
  betterWhen: "higher" | "lower";
  unit: ProgressUnit;
  /** The points that have a figure. */
  points: number;
  minPoints: number;
  /** The fitted change over the range (a least-squares line); null while the direction is unknown. */
  change: number | null;
  /** `latest` minus `from`; null while the direction is unknown or either is missing. */
  difference: number | null;
  /** The first figure of the range (the mean of the first five rides for the ride measures); null without one. */
  from: number | null;
  /** The last figure (the mean of the last five rides for the ride measures). */
  latest: number | null;
  /** The day of `from`; set exactly when `from` is. */
  since: string | null;
  /** A fitted change smaller than this in size is steady, in `unit`. */
  steadyBand: number;
  verdictText: string;
  /** Why the direction is unknown; null otherwise. */
  reason: string | null;
  /** The heat, sleep and caffeine caution for the heart-rate measures; null otherwise. */
  note: string | null;
}

export interface ProgressOverallTrend {
  direction: ProgressDirection;
  betterWhen: "higher";
  /** How many of the three durations have a verdict. */
  lines: number;
  verdictText: string;
  reason: string | null;
  note: null;
}

export interface ProgressBestEffort {
  watts: number;
  wattsPerKg: number | null;
  weightKg: number | null;
  date: string;
  rideId: string;
  rideName: string;
}

export interface ProgressMonth {
  /** `YYYY-MM`, in the rider's time zone. */
  month: string;
  /** True for the current month: it is not in the trend. */
  partial: boolean;
  d300: ProgressBestEffort | null;
  d1200: ProgressBestEffort | null;
  d3600: ProgressBestEffort | null;
}

export interface ProgressStretchCount {
  stretches: number;
  seconds: number;
}

export interface ProgressEfficiencyWeek {
  /** The Monday. */
  weekStart: string;
  heartRateAtPower: number | null;
  powerAtHeartRate: number | null;
  atPower: ProgressStretchCount;
  atHeartRate: ProgressStretchCount;
}

export interface ProgressEfficiencyRide {
  date: string;
  rideId: string;
  name: string;
  /** Normalised power over average heart rate, three decimals. */
  value: number;
  normalizedPower: number;
  averageHeartRate: number;
  /** The seconds of the ride's steady stretches that it is taken over. */
  seconds: number;
}

export interface ProgressDriftRide {
  date: string;
  rideId: string;
  name: string;
  driftPercent: number;
}

export interface ProgressRolling {
  date: string;
  rideId: string;
  value: number;
}

export interface ProgressRestingWeek {
  weekStart: string;
  mean: number | null;
  nights: number;
}

export interface ProgressWeightWeek {
  weekStart: string;
  kg: number | null;
  weighings: number;
  ftp: number | null;
  wattsPerKg: number | null;
}

export interface ProgressHeadline {
  measure: ProgressMeasure;
  direction: "improving" | "worsening" | "steady";
  verdictText: string;
}

/** The answer to `GET /v1/progress`. */
export interface ProgressResponse {
  generatedAt: string;
  stale: boolean;
  range: { from: string; to: string };
  headline: ProgressHeadline | null;
  bestPower: {
    months: ProgressMonth[];
    trend: ProgressOverallTrend;
    trends: { d300: ProgressTrend; d1200: ProgressTrend; d3600: ProgressTrend };
  };
  aerobicEfficiency: {
    ftpAtStart: number | null;
    referencePower: number | null;
    referenceHeartRate: number | null;
    referenceHeartRateBasis: "threshold" | "maximum" | null;
    weeks: ProgressEfficiencyWeek[];
    /** Heart rate at the reference power: the card's verdict. */
    trend: ProgressTrend;
    /** Power at the reference heart rate. */
    powerTrend: ProgressTrend;
  };
  efficiencyFactor: {
    rides: ProgressEfficiencyRide[];
    rolling5: ProgressRolling[];
    trend: ProgressTrend;
  };
  decoupling: {
    rides: ProgressDriftRide[];
    rolling5: ProgressRolling[];
    trend: ProgressTrend;
  };
  restingHeartRate: { weeks: ProgressRestingWeek[]; trend: ProgressTrend };
  weight: {
    weeks: ProgressWeightWeek[];
    /** Of watts per kilogram. */
    trend: ProgressTrend;
    /** Weight, reported and never judged; null with fewer than two weekly figures. */
    weightChange: {
      points: number;
      from: number;
      latest: number;
      difference: number;
      since: string;
    } | null;
  };
}

// ---- Validation --------------------------------------------------------------------------------------------------

const MAX_REPORTED = 20;

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkProgress = ajv.compile<ProgressResponse>(load("progress-response.schema.json"));

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

const isMonday = (date: string): boolean => {
  const day = isoDateToEpochDay(date);
  return day !== null && (((day + 3) % 7) + 7) % 7 === 0;
};

function nextMonth(month: string): string {
  const year = Number(month.slice(0, 4));
  const m = Number(month.slice(5, 7));
  return m === 12 ? `${year + 1}-01` : `${year}-${String(m + 1).padStart(2, "0")}`;
}

/** The direction a fitted change, a steady band and the better side say (the rule every trend of the page follows). */
export function progressDirection(
  change: number,
  steadyBand: number,
  betterWhen: "higher" | "lower",
): "improving" | "worsening" | "steady" {
  if (Math.abs(change) < steadyBand) return "steady";
  return change < 0 === (betterWhen === "lower") ? "improving" : "worsening";
}

function trendErrors(at: string, trend: ProgressTrend): string[] {
  const errors: string[] = [];
  const enough = trend.points >= trend.minPoints;
  if (!enough) {
    if (trend.direction !== "unknown")
      errors.push(`${at}/direction must be unknown below minPoints`);
    if (trend.change !== null || trend.difference !== null) {
      errors.push(`${at} has a change or a difference below minPoints`);
    }
    if (trend.reason === null) errors.push(`${at}/reason is needed when the direction is unknown`);
  } else {
    if (trend.direction === "unknown") {
      errors.push(`${at}/direction cannot be unknown with enough points`);
    }
    if (trend.reason !== null) errors.push(`${at}/reason must be null when there is a direction`);
    if (trend.change === null) {
      errors.push(`${at}/change is needed with enough points`);
    } else if (
      trend.direction !== "unknown" &&
      trend.direction !== progressDirection(trend.change, trend.steadyBand, trend.betterWhen)
    ) {
      errors.push(`${at}/direction does not follow from /change, /steadyBand and /betterWhen`);
    }
  }
  if ((trend.from === null) !== (trend.since === null)) {
    errors.push(`${at}/since must be set exactly when /from is`);
  }
  if (trend.since !== null && !isIsoDate(trend.since)) {
    errors.push(`${at}/since is not a real calendar date`);
  }
  if (trend.points === 0 && (trend.from !== null || trend.latest !== null)) {
    errors.push(`${at} has a figure without a point`);
  }
  if (trend.difference !== null) {
    if (trend.from === null || trend.latest === null) {
      errors.push(`${at}/difference needs /from and /latest`);
    } else if (Math.abs(trend.difference - (trend.latest - trend.from)) > 0.0501) {
      errors.push(`${at}/difference is not latest minus from`);
    }
  }
  return errors;
}

function overallErrors(
  at: string,
  overall: ProgressOverallTrend,
  lines: readonly ProgressTrend[],
): string[] {
  const errors: string[] = [];
  const known = lines.filter((t) => t.direction !== "unknown");
  const improving = known.filter((t) => t.direction === "improving").length;
  const worsening = known.filter((t) => t.direction === "worsening").length;
  if (overall.lines !== known.length) errors.push(`${at}/lines is not the lines with a verdict`);
  const wanted: ProgressDirection =
    known.length < 2
      ? "unknown"
      : improving >= 2 && worsening === 0
        ? "improving"
        : worsening >= 2 && improving === 0
          ? "worsening"
          : "steady";
  if (overall.direction !== wanted) errors.push(`${at}/direction does not follow from the lines`);
  if ((overall.direction === "unknown") !== (overall.reason !== null)) {
    errors.push(`${at}/reason must be set exactly when the direction is unknown`);
  }
  return errors;
}

/** Dates in order and unique by the given key, and consecutive for weeks and months. */
function weeksErrors(at: string, weeks: readonly { weekStart: string }[]): string[] {
  const errors: string[] = [];
  for (const [i, week] of weeks.entries()) {
    if (!isIsoDate(week.weekStart)) {
      errors.push(`${at}/${i}/weekStart is not a real calendar date`);
      continue;
    }
    if (!isMonday(week.weekStart)) errors.push(`${at}/${i}/weekStart is not a Monday`);
    const before = weeks[i - 1];
    if (before !== undefined && isIsoDate(before.weekStart)) {
      if (addDaysToIsoDate(before.weekStart, 7) !== week.weekStart) {
        errors.push(`${at}/${i}/weekStart does not follow the week before it`);
      }
    }
  }
  return errors;
}

function ridesErrors(at: string, rides: readonly { date: string; rideId: string }[]): string[] {
  const errors: string[] = [];
  const seen = new Set<string>();
  for (const [i, ride] of rides.entries()) {
    if (!isIsoDate(ride.date)) errors.push(`${at}/${i}/date is not a real calendar date`);
    if (seen.has(ride.rideId)) errors.push(`${at}/${i}/rideId is used twice`);
    seen.add(ride.rideId);
    const before = rides[i - 1];
    if (before !== undefined && before.date > ride.date) {
      errors.push(`${at}/${i} is out of order: oldest first`);
    }
  }
  return errors;
}

/**
 * What the schema cannot say (see its description): real dates and instants, the range's order, consecutive weeks (Mondays) and
 * months, rides oldest first and unique, every trend's arithmetic and direction, the overall best-power direction, and a headline that
 * names a block with that direction.
 */
export function progressConsistencyErrors(response: ProgressResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const { from, to } = response.range;
  if (!isIsoDate(from) || !isIsoDate(to)) errors.push("/range holds a date that is not real");
  else if (from > to) errors.push("/range/from is after /range/to");

  const best = response.bestPower;
  for (const [i, month] of best.months.entries()) {
    const before = best.months[i - 1];
    if (before !== undefined && nextMonth(before.month) !== month.month) {
      errors.push(`/bestPower/months/${i}/month does not follow the month before it`);
    }
    for (const key of ["d300", "d1200", "d3600"] as const) {
      const effort = month[key];
      if (effort === null) continue;
      if (!isIsoDate(effort.date)) {
        errors.push(`/bestPower/months/${i}/${key}/date is not a real calendar date`);
      } else if (effort.date.slice(0, 7) !== month.month) {
        errors.push(`/bestPower/months/${i}/${key}/date is not in the month`);
      }
      if ((effort.wattsPerKg === null) !== (effort.weightKg === null)) {
        errors.push(`/bestPower/months/${i}/${key} has a weight without watts per kilogram or not`);
      }
    }
  }
  const partial = best.months.filter((m) => m.partial).length;
  if (partial > 1 || best.months.some((m, i) => m.partial && i !== best.months.length - 1)) {
    errors.push("/bestPower/months: only the last month can be partial");
  }
  for (const key of ["d300", "d1200", "d3600"] as const) {
    errors.push(...trendErrors(`/bestPower/trends/${key}`, best.trends[key]));
  }
  errors.push(
    ...overallErrors("/bestPower/trend", best.trend, [
      best.trends.d300,
      best.trends.d1200,
      best.trends.d3600,
    ]),
  );

  const aerobic = response.aerobicEfficiency;
  errors.push(...weeksErrors("/aerobicEfficiency/weeks", aerobic.weeks));
  errors.push(...trendErrors("/aerobicEfficiency/trend", aerobic.trend));
  errors.push(...trendErrors("/aerobicEfficiency/powerTrend", aerobic.powerTrend));
  if ((aerobic.referenceHeartRate === null) !== (aerobic.referenceHeartRateBasis === null)) {
    errors.push("/aerobicEfficiency/referenceHeartRateBasis is set exactly when the reference is");
  }
  if ((aerobic.referencePower === null) !== (aerobic.ftpAtStart === null)) {
    errors.push("/aerobicEfficiency/referencePower is set exactly when /ftpAtStart is");
  }

  const factor = response.efficiencyFactor;
  errors.push(...ridesErrors("/efficiencyFactor/rides", factor.rides));
  errors.push(...ridesErrors("/efficiencyFactor/rolling5", factor.rolling5));
  errors.push(...trendErrors("/efficiencyFactor/trend", factor.trend));
  const drift = response.decoupling;
  errors.push(...ridesErrors("/decoupling/rides", drift.rides));
  errors.push(...ridesErrors("/decoupling/rolling5", drift.rolling5));
  errors.push(...trendErrors("/decoupling/trend", drift.trend));

  errors.push(...weeksErrors("/restingHeartRate/weeks", response.restingHeartRate.weeks));
  errors.push(...trendErrors("/restingHeartRate/trend", response.restingHeartRate.trend));
  for (const [i, week] of response.restingHeartRate.weeks.entries()) {
    if ((week.mean === null) !== week.nights < PROGRESS_MIN_NIGHTS) {
      errors.push(`/restingHeartRate/weeks/${i}/mean is set exactly with enough nights`);
    }
  }
  errors.push(...weeksErrors("/weight/weeks", response.weight.weeks));
  errors.push(...trendErrors("/weight/trend", response.weight.trend));
  for (const [i, week] of response.weight.weeks.entries()) {
    if ((week.kg === null) !== week.weighings < PROGRESS_MIN_WEIGHINGS) {
      errors.push(`/weight/weeks/${i}/kg is set exactly with enough weighings`);
    }
    if (week.wattsPerKg !== null && (week.kg === null || week.ftp === null)) {
      errors.push(`/weight/weeks/${i}/wattsPerKg needs a weight and an FTP`);
    }
  }
  const change = response.weight.weightChange;
  if (change !== null) {
    if (!isIsoDate(change.since)) errors.push("/weight/weightChange/since is not a real date");
    if (Math.abs(change.difference - (change.latest - change.from)) > 0.0501) {
      errors.push("/weight/weightChange/difference is not latest minus from");
    }
  }

  const headline = response.headline;
  if (headline !== null) {
    const direction =
      headline.measure === "bestPower"
        ? best.trend.direction
        : response[headline.measure].trend.direction;
    if (direction !== headline.direction) {
      errors.push("/headline/direction is not the direction of its measure");
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateProgressResponse(input: unknown): Validation<ProgressResponse> {
  if (!checkProgress(input)) return { ok: false, errors: plain(checkProgress.errors) };
  const errors = progressConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
