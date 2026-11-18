import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Step, Validation, Workout } from "./index.ts";
import { isIsoDate } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: repeated sessions (spec §11.7, §8.3, ADR 0026) ---------------------------------------------------
// Hand-written mirrors of schema/repeats-response.schema.json, repeat-detail-response.schema.json and
// repeat-previous-response.schema.json, which are the source of truth; the fixtures test keeps the two honest. Kotlin's
// `RepeatsApiJson` reads and writes the same bodies against the same fixtures.
//
// HEALTH DATA IS PRIVATE: no message built here quotes a figure, only paths and rules.

/** A hash identifies a session: 16 lower-case hex characters. */
export const REPEAT_HASH_PATTERN: RegExp = /^[0-9a-f]{16}$/;

/** The version of the structure's definition below; it is part of what is hashed, so changing the definition changes every hash. */
export const WORKOUT_HASH_VERSION = 1;

/** A second counts toward a step's heart rate at the same power when the power is within this percent of the step's target. */
export const REPEAT_POWER_TOLERANCE_PERCENT = 3;
/** The first seconds of a step (and of each stretch after a pause inside it) are left out: heart rate takes that long to settle. */
export const REPEAT_SKIP_SECONDS = 60;
/** A step has a heart rate at the same power only with this many counted seconds. */
export const REPEAT_MIN_STEP_SECONDS = 120;
/** A trend needs this many attempts with a figure. */
export const REPEAT_MIN_TREND_ATTEMPTS = 3;

/** The standing caution of every repeat page (spec §11.7). */
export const REPEAT_NOTE =
  "Heart rate also moves from day to day with heat, sleep and caffeine, so look at the trend over several attempts, not at a single comparison.";

// ---- The workout's structure and its hash ------------------------------------------------------------------------

const permille = (fraction: number): number => Math.round(fraction * 1000);

function leafToken(step: Exclude<Step, { type: "repeat" }>): string {
  switch (step.type) {
    case "steady":
      return `s${step.duration}@${permille(step.target)}`;
    case "ramp":
      return `r${step.duration}@${permille(step.from)}-${permille(step.to)}`;
    case "free":
      return `f${step.duration}`;
  }
}

/**
 * The canonical text of a workout's structure: its steps with repeats expanded, in order, each as a token (`s600@880` a steady
 * step of 600 s at 88% of FTP, `r300@450-700` a ramp from 45% to 70%, `f300` a free step), joined by commas. In it: the step type, the
 * duration in whole seconds, the targets in thousandths of FTP (rounded, so float noise from a converter does not matter) and
 * the order. **Not** in it: the workout's id, name and description, every label, every cadence target, the schema number and
 * whether steps were written as a repeat or one by one. Two workouts with the same structure are the same session.
 */
export function workoutStructure(workout: Workout): string {
  const tokens: string[] = [];
  for (const step of workout.steps) {
    if (step.type === "repeat") {
      for (let i = 0; i < step.count; i++)
        for (const inner of step.steps) tokens.push(leafToken(inner));
    } else {
      tokens.push(leafToken(step));
    }
  }
  return tokens.join(",");
}

/** The session's hash: the first 16 hex characters of SHA-256 over `overdrive.workout-structure.v<version>\n` and the structure. */
export function workoutHash(workout: Workout): string {
  return createHash("sha256")
    .update(
      `overdrive.workout-structure.v${WORKOUT_HASH_VERSION}\n${workoutStructure(workout)}`,
      "utf8",
    )
    .digest("hex")
    .slice(0, 16);
}

// ---- Types -----------------------------------------------------------------------------------------------------

/** One figure of the latest attempt against the previous one. */
export interface RepeatChange {
  latest: number | null;
  previous: number | null;
  /** Latest minus previous, to one decimal; null with a reason when either figure is missing. */
  difference: number | null;
  reason: string | null;
}

export interface RepeatAttemptRef {
  rideId: string;
  /** The rider's local day. */
  date: string;
  /** UTC instant. */
  startTime: string;
}

/** One row of `GET /v1/repeats`. */
export interface RepeatSummary {
  hash: string;
  name: string;
  /** The hook for routes (stage v0.4.0): always null today. */
  routeKey: string | null;
  attempts: number;
  firstDate: string;
  lastDate: string;
  latest: RepeatAttemptRef;
  previous: RepeatAttemptRef;
  comparison: {
    /** bpm; lower at the same power is fitter. */
    heartRateAtPower: RepeatChange;
    /** Percent. */
    driftPercent: RepeatChange;
    /** Percent of the planned time ridden. */
    compliance: RepeatChange;
  };
}

export interface RepeatsResponse {
  generatedAt: string;
  repeats: RepeatSummary[];
}

export interface RepeatDefinition {
  powerTolerancePercent: 3;
  skipSeconds: 60;
  minStepSeconds: 120;
  minTrendAttempts: 3;
}

export interface RepeatSessionStep {
  /** 1-based position in the flattened workout. */
  step: number;
  label: string;
  durationSeconds: number;
  /** Fraction of FTP. */
  targetFraction: number;
}

export interface RepeatStepHeartRate {
  step: number;
  /** The step's target at the attempt's FTP. */
  targetWatts: number;
  /** Over the counted seconds. */
  averagePower: number | null;
  averageHeartRate: number | null;
  /** The counted seconds. */
  seconds: number;
  reason: string | null;
}

export interface RepeatFigure {
  value: number | null;
  reason: string | null;
}

export interface RepeatAttempt {
  rideId: string;
  /** 1 for the first attempt. */
  number: number;
  date: string;
  startTime: string;
  durationSeconds: number;
  ftp: number;
  averagePower: number | null;
  averageHeartRate: number | null;
  /** Reserved for the rider's own rating of the effort; null today. */
  effortRating: number | null;
  heartRateAtPower: { value: number | null; seconds: number; reason: string | null };
  stepHeartRates: RepeatStepHeartRate[];
  driftPercent: RepeatFigure;
  compliance: {
    percent: number | null;
    stepsCompleted: number;
    stepsPlanned: number;
    powerDeltaPercent: number | null;
  };
}

export type RepeatDirection = "improving" | "worsening" | "steady" | "unknown";

export interface RepeatTrend {
  unit: string;
  betterWhen: "lower" | "higher";
  /** The attempts that had a figure. */
  attempts: number;
  minAttempts: 3;
  direction: RepeatDirection;
  slopePerAttempt: number | null;
  change: number | null;
  steadyBelow: number;
  reason: string | null;
}

/** The answer to `GET /v1/repeats/{hash}`. */
export interface RepeatDetailResponse {
  generatedAt: string;
  hash: string;
  name: string;
  routeKey: string | null;
  note: string;
  definition: RepeatDefinition;
  steps: RepeatSessionStep[];
  comparedSteps: number[];
  attempts: RepeatAttempt[];
  trends: {
    heartRateAtPower: RepeatTrend;
    driftPercent: RepeatTrend;
    compliance: RepeatTrend;
  };
}

export interface RepeatPreviousStep {
  step: number;
  label: string;
  startSeconds: number;
  endSeconds: number;
  targetWatts: number | null;
  averageHeartRate: number | null;
}

/** The answer to `GET /v1/repeats/{hash}/previous[?before=rideId]`. */
export interface RepeatPreviousResponse {
  generatedAt: string;
  hash: string;
  before: string | null;
  previous: {
    rideId: string;
    date: string;
    startTime: string;
    durationSeconds: number;
    ftp: number;
    attempt: number;
    heartRateAtPower: number | null;
    steps: RepeatPreviousStep[];
  } | null;
}

// ---- Validation ------------------------------------------------------------------------------------------------

const MAX_REPORTED = 20;

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkRepeats = ajv.compile<RepeatsResponse>(load("repeats-response.schema.json"));
const checkDetail = ajv.compile<RepeatDetailResponse>(load("repeat-detail-response.schema.json"));
const checkPrevious = ajv.compile<RepeatPreviousResponse>(
  load("repeat-previous-response.schema.json"),
);

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** The rules of a change (see the schema's description). */
function changeErrors(at: string, change: RepeatChange): string[] {
  const both = change.latest !== null && change.previous !== null;
  if (both) {
    const errors: string[] = [];
    if (change.difference === null)
      errors.push(`${at}/difference must be set when both figures are`);
    else if (
      Math.abs(change.difference - ((change.latest as number) - (change.previous as number))) >
      0.0501
    ) {
      errors.push(`${at}/difference is not latest minus previous`);
    }
    if (change.reason !== null) errors.push(`${at}/reason must be null when both figures are set`);
    return errors;
  }
  const errors: string[] = [];
  if (change.difference !== null)
    errors.push(`${at}/difference must be null when a figure is missing`);
  if (change.reason === null) errors.push(`${at}/reason is needed when a figure is missing`);
  return errors;
}

/**
 * What the schema cannot say about the list (see its description): real dates and instants, the first day not after the last,
 * the last day that of the latest attempt, two different attempts in order, the changes' arithmetic, and the order of the sessions.
 */
export function repeatsResponseConsistencyErrors(response: RepeatsResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const seen = new Set<string>();
  let before: RepeatSummary | null = null;
  for (const [index, repeat] of response.repeats.entries()) {
    const at = `/repeats/${index}`;
    if (seen.has(repeat.hash)) errors.push(`${at}/hash is used twice`);
    seen.add(repeat.hash);
    for (const key of ["firstDate", "lastDate"] as const) {
      if (!isIsoDate(repeat[key])) errors.push(`${at}/${key} is not a real calendar date`);
    }
    for (const key of ["latest", "previous"] as const) {
      if (!isIsoDate(repeat[key].date))
        errors.push(`${at}/${key}/date is not a real calendar date`);
      if (parseInstant(repeat[key].startTime) === null) {
        errors.push(`${at}/${key}/startTime is not a real date and time`);
      }
    }
    if (repeat.firstDate > repeat.lastDate) errors.push(`${at}/firstDate is after /lastDate`);
    if (repeat.lastDate !== repeat.latest.date)
      errors.push(`${at}/lastDate is not the day of /latest`);
    if (repeat.firstDate > repeat.previous.date)
      errors.push(`${at}/firstDate is after the day of /previous`);
    if (repeat.latest.rideId === repeat.previous.rideId) {
      errors.push(`${at} compares a ride with itself`);
    }
    const latest = parseInstant(repeat.latest.startTime);
    const previous = parseInstant(repeat.previous.startTime);
    if (latest !== null && previous !== null && previous > latest) {
      errors.push(`${at}/previous started after /latest`);
    }
    for (const key of ["heartRateAtPower", "driftPercent", "compliance"] as const) {
      errors.push(...changeErrors(`${at}/comparison/${key}`, repeat.comparison[key]));
    }
    if (
      before !== null &&
      (repeat.lastDate > before.lastDate ||
        (repeat.lastDate === before.lastDate && repeat.hash < before.hash))
    ) {
      errors.push(`${at} is out of order: most recently ridden first, then by hash`);
    }
    before = repeat;
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateRepeatsResponse(input: unknown): Validation<RepeatsResponse> {
  if (!checkRepeats(input)) return { ok: false, errors: plain(checkRepeats.errors) };
  const errors = repeatsResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

function trendErrors(
  at: string,
  trend: RepeatTrend,
  usable: number,
  attemptCount: number,
): string[] {
  const errors: string[] = [];
  if (trend.attempts !== usable) {
    errors.push(`${at}/attempts is not the number of attempts that have the figure`);
  }
  if (trend.attempts > attemptCount) errors.push(`${at}/attempts is more than there are attempts`);
  const enough = trend.attempts >= trend.minAttempts;
  if (!enough) {
    if (trend.direction !== "unknown")
      errors.push(`${at}/direction must be unknown below minAttempts`);
    if (trend.slopePerAttempt !== null || trend.change !== null) {
      errors.push(`${at} has a slope or a change below minAttempts`);
    }
    if (trend.reason === null) errors.push(`${at}/reason is needed when the direction is unknown`);
    return errors;
  }
  if (trend.direction === "unknown")
    errors.push(`${at}/direction cannot be unknown with enough attempts`);
  if (trend.reason !== null) errors.push(`${at}/reason must be null when there is a direction`);
  if (trend.slopePerAttempt === null || trend.change === null) {
    errors.push(`${at}/slopePerAttempt and /change are needed with enough attempts`);
    return errors;
  }
  const expected = trend.slopePerAttempt * (trend.attempts - 1);
  if (Math.abs(trend.change - expected) > 0.0005 * (trend.attempts - 1) + 0.0051) {
    errors.push(`${at}/change is not slopePerAttempt times attempts minus one`);
  }
  const size = Math.abs(trend.change);
  const wanted: RepeatDirection =
    size < trend.steadyBelow
      ? "steady"
      : trend.change < 0 === (trend.betterWhen === "lower")
        ? "improving"
        : "worsening";
  if (trend.direction !== "unknown" && trend.direction !== wanted) {
    errors.push(`${at}/direction does not follow from /change, /steadyBelow and /betterWhen`);
  }
  return errors;
}

/**
 * What the schema cannot say about a session's detail (see its description): real dates and instants, attempts in order with
 * unique rides and numbers 1, 2, 3, …, the steps and the compared steps, a figure and its reason being exclusive, the minimum of
 * counted seconds, and the trends' arithmetic and direction.
 */
export function repeatDetailConsistencyErrors(response: RepeatDetailResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const stepNumbers = response.steps.map((s) => s.step);
  for (let i = 1; i < stepNumbers.length; i++) {
    if ((stepNumbers[i] as number) <= (stepNumbers[i - 1] as number)) {
      errors.push(`/steps/${i}/step is not after the step before it`);
      break;
    }
  }
  const known = new Set(stepNumbers);
  for (const [i, step] of response.comparedSteps.entries()) {
    if (!known.has(step)) errors.push(`/comparedSteps/${i} is not one of /steps`);
    const before = response.comparedSteps[i - 1];
    if (before !== undefined && step <= before) {
      errors.push(`/comparedSteps/${i} is not after the one before it`);
    }
  }
  const rides = new Set<string>();
  let lastStart: number | null = null;
  for (const [index, attempt] of response.attempts.entries()) {
    const at = `/attempts/${index}`;
    if (attempt.number !== index + 1) errors.push(`${at}/number is not ${index + 1}`);
    if (rides.has(attempt.rideId)) errors.push(`${at}/rideId is used twice`);
    rides.add(attempt.rideId);
    if (!isIsoDate(attempt.date)) errors.push(`${at}/date is not a real calendar date`);
    const start = parseInstant(attempt.startTime);
    if (start === null) errors.push(`${at}/startTime is not a real date and time`);
    else {
      if (lastStart !== null && start <= lastStart) {
        errors.push(`${at} is not later than the attempt before it: oldest first`);
      }
      lastStart = start;
    }
    if (attempt.stepHeartRates.length !== stepNumbers.length) {
      errors.push(`${at}/stepHeartRates must have one entry per step`);
    }
    for (const [i, figure] of attempt.stepHeartRates.entries()) {
      const where = `${at}/stepHeartRates/${i}`;
      if (figure.step !== stepNumbers[i])
        errors.push(`${where}/step is not the session's step ${i + 1}`);
      if ((figure.averageHeartRate === null) === (figure.reason === null)) {
        errors.push(`${where} has exactly one of /averageHeartRate and /reason`);
      }
      if (figure.averageHeartRate !== null) {
        if (figure.seconds < REPEAT_MIN_STEP_SECONDS) {
          errors.push(`${where}/seconds is below ${REPEAT_MIN_STEP_SECONDS}`);
        }
        if (figure.averagePower === null)
          errors.push(`${where}/averagePower is needed with a heart rate`);
      }
    }
    const overall = attempt.heartRateAtPower;
    if ((overall.value === null) === (overall.reason === null)) {
      errors.push(`${at}/heartRateAtPower has exactly one of /value and /reason`);
    }
    if (overall.value !== null) {
      if (overall.seconds <= 0) errors.push(`${at}/heartRateAtPower/seconds must be above 0`);
      if (response.comparedSteps.length === 0) {
        errors.push(`${at}/heartRateAtPower has a value but no step is compared`);
      }
      for (const step of response.comparedSteps) {
        const index = stepNumbers.indexOf(step);
        if (attempt.stepHeartRates[index]?.averageHeartRate == null) {
          errors.push(`${at}/heartRateAtPower has a value but step ${step} has no heart rate`);
        }
      }
    }
    if ((attempt.driftPercent.value === null) === (attempt.driftPercent.reason === null)) {
      errors.push(`${at}/driftPercent has exactly one of /value and /reason`);
    }
    if (attempt.compliance.stepsCompleted > attempt.compliance.stepsPlanned) {
      errors.push(`${at}/compliance/stepsCompleted is more than stepsPlanned`);
    }
  }
  const count = (pick: (a: RepeatAttempt) => number | null): number =>
    response.attempts.filter((a) => pick(a) !== null).length;
  errors.push(
    ...trendErrors(
      "/trends/heartRateAtPower",
      response.trends.heartRateAtPower,
      count((a) => a.heartRateAtPower.value),
      response.attempts.length,
    ),
    ...trendErrors(
      "/trends/driftPercent",
      response.trends.driftPercent,
      count((a) => a.driftPercent.value),
      response.attempts.length,
    ),
    ...trendErrors(
      "/trends/compliance",
      response.trends.compliance,
      count((a) => a.compliance.percent),
      response.attempts.length,
    ),
  );
  return errors.slice(0, MAX_REPORTED);
}

export function validateRepeatDetailResponse(input: unknown): Validation<RepeatDetailResponse> {
  if (!checkDetail(input)) return { ok: false, errors: plain(checkDetail.errors) };
  const errors = repeatDetailConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about the previous attempt (see its description). */
export function repeatPreviousConsistencyErrors(response: RepeatPreviousResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const previous = response.previous;
  if (previous === null) return errors;
  if (!isIsoDate(previous.date)) errors.push("/previous/date is not a real calendar date");
  if (parseInstant(previous.startTime) === null) {
    errors.push("/previous/startTime is not a real date and time");
  }
  if (response.before !== null && response.before === previous.rideId) {
    errors.push("/previous is the ride asked about, not the one before it");
  }
  let end = 0;
  let step = 0;
  for (const [index, entry] of previous.steps.entries()) {
    const at = `/previous/steps/${index}`;
    if (entry.step <= step) errors.push(`${at}/step is not after the step before it`);
    if (entry.endSeconds <= entry.startSeconds)
      errors.push(`${at}/endSeconds is not after /startSeconds`);
    if (entry.startSeconds < end) errors.push(`${at} starts before the step before it ends`);
    if (entry.averageHeartRate !== null && entry.targetWatts === null) {
      errors.push(`${at}/targetWatts is needed with a heart rate`);
    }
    step = entry.step;
    end = entry.endSeconds;
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateRepeatPreviousResponse(input: unknown): Validation<RepeatPreviousResponse> {
  if (!checkPrevious(input)) return { ok: false, errors: plain(checkPrevious.errors) };
  const errors = repeatPreviousConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
