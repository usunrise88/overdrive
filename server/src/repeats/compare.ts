import {
  REPEAT_MIN_TREND_ATTEMPTS,
  REPEAT_POWER_TOLERANCE_PERCENT,
  type RepeatTrend,
} from "@overdrive/contracts";
import type { StepStat } from "./figures.ts";

/**
 * How attempts at one session are compared (spec §11.7, ADR 0026): the overall heart rate at the same power over the steps they
 * have in common, and the trend of a measure over the attempts. Pure functions; every number is computed by code.
 */

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor + 0;
};

/** What the comparison needs of a session's step: its place, and its planned length (the weight in an overall figure). */
export interface StepWeight {
  step: number;
  durationSeconds: number;
}

/** What the comparison needs of an attempt. */
export interface AttemptSteps {
  steps: readonly StepStat[];
}

/** The overall heart rate at the same power of one attempt over the compared steps, or why there is none. */
export interface Overall {
  value: number | null;
  /** The counted seconds behind it. */
  seconds: number;
  reason: string | null;
}

const stepOf = (attempt: AttemptSteps, step: number): StepStat | undefined =>
  attempt.steps.find((s) => s.step === step);

/**
 * The steps whose heart rates make an overall figure: those in which the reference attempt has one. The reference is the latest
 * attempt that has a figure in any step (`attempts` oldest first).
 */
export function comparedSteps(attempts: readonly AttemptSteps[]): {
  steps: number[];
  reference: AttemptSteps | null;
} {
  for (let i = attempts.length - 1; i >= 0; i--) {
    const attempt = attempts[i] as AttemptSteps;
    const steps = attempt.steps.filter((s) => s.averageHeartRate !== null).map((s) => s.step);
    if (steps.length > 0) return { steps, reference: attempt };
  }
  return { steps: [], reference: null };
}

/**
 * The overall heart rate at the same power of `attempt` over `compared` steps, weighted by the steps' planned length. Null with a
 * reason when the attempt has no figure in one of them, or its target power there is not the reference's within the tolerance (the
 * FTP changed, so the same power is not being compared).
 */
export function overallHeartRate(
  attempt: AttemptSteps,
  compared: readonly number[],
  reference: AttemptSteps | null,
  weights: readonly StepWeight[],
  options: { noFixedPowerStep: boolean },
): Overall {
  if (compared.length === 0 || reference === null) {
    return {
      value: null,
      seconds: 0,
      reason: options.noFixedPowerStep
        ? "the session has no fixed-power step"
        : "no attempt has a heart rate at the target power in any step",
    };
  }
  let weighted = 0;
  let total = 0;
  let seconds = 0;
  for (const step of compared) {
    const mine = stepOf(attempt, step);
    const theirs = stepOf(reference, step);
    if (mine === undefined || theirs === undefined) {
      return { value: null, seconds: 0, reason: `step ${step} was not ridden` };
    }
    if (mine.averageHeartRate === null) {
      return { value: null, seconds: 0, reason: `step ${step}: ${mine.reason ?? "no heart rate"}` };
    }
    if (
      Math.abs(mine.targetWatts - theirs.targetWatts) >
      (REPEAT_POWER_TOLERANCE_PERCENT / 100) * theirs.targetWatts
    ) {
      return {
        value: null,
        seconds: 0,
        reason: `the target power in step ${step} was ${mine.targetWatts} W against ${theirs.targetWatts} W where the comparison is taken (the FTP changed, so the same power is not being compared)`,
      };
    }
    const weight = weights.find((w) => w.step === step)?.durationSeconds ?? 1;
    weighted += mine.averageHeartRate * weight;
    total += weight;
    seconds += mine.seconds;
  }
  return { value: round(weighted / total, 1), seconds, reason: null };
}

/** The figures of a measure for a pair of attempts: latest, previous and their difference, or why there is none. */
export function changeOf(
  latest: number | null,
  previous: number | null,
  reasons: { latest: string | null; previous: string | null },
  names: { latest: string; previous: string } = {
    latest: "the latest attempt",
    previous: "the previous attempt",
  },
): {
  latest: number | null;
  previous: number | null;
  difference: number | null;
  reason: string | null;
} {
  if (latest !== null && previous !== null) {
    return { latest, previous, difference: round(latest - previous, 1), reason: null };
  }
  const reason =
    latest === null
      ? `${names.latest} has none: ${reasons.latest ?? "not available"}`
      : `${names.previous} has none: ${reasons.previous ?? "not available"}`;
  return { latest, previous, difference: null, reason: reason.slice(0, 300) };
}

export interface TrendSpec {
  unit: string;
  betterWhen: "lower" | "higher";
  /** A fitted change smaller than this in size is steady. */
  steadyBelow: number;
}

/**
 * The trend of a measure over the attempts that have a figure (`values` in the order ridden): a least-squares line against the
 * attempt's position, so the slope is in units per attempt, and the fitted change from the first to the last is the slope times
 * the attempts minus one. With fewer than {@link REPEAT_MIN_TREND_ATTEMPTS} attempts there is no direction: two points always make
 * a line, and one comparison is not a trend (spec §11.7).
 */
export function trendOf(values: readonly number[], spec: TrendSpec): RepeatTrend {
  const attempts = values.length;
  const base = {
    unit: spec.unit,
    betterWhen: spec.betterWhen,
    attempts,
    minAttempts: REPEAT_MIN_TREND_ATTEMPTS,
    steadyBelow: spec.steadyBelow,
  } as const;
  if (attempts < REPEAT_MIN_TREND_ATTEMPTS) {
    return {
      ...base,
      direction: "unknown",
      slopePerAttempt: null,
      change: null,
      reason: `a trend needs at least ${REPEAT_MIN_TREND_ATTEMPTS} attempts with a figure; there ${attempts === 1 ? "is" : "are"} ${attempts}`,
    };
  }
  const meanX = (attempts - 1) / 2;
  const meanY = values.reduce((a, b) => a + b, 0) / attempts;
  let covariance = 0;
  let variance = 0;
  for (const [x, y] of values.entries()) {
    covariance += (x - meanX) * (y - meanY);
    variance += (x - meanX) ** 2;
  }
  const slope = covariance / variance;
  const change = round(slope * (attempts - 1), 2);
  const direction =
    Math.abs(change) < spec.steadyBelow
      ? "steady"
      : change < 0 === (spec.betterWhen === "lower")
        ? "improving"
        : "worsening";
  return { ...base, direction, slopePerAttempt: round(slope, 3), change, reason: null };
}
