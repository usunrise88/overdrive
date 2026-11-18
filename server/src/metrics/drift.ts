import type { Lap, RideSample } from "@overdrive/contracts";

/**
 * Aerobic drift: how much the heart rate rises against a steady power (spec §6.3). Pw:HR
 * decoupling in the manner of Friel's coaching practice, not a validated clinical measure.
 *
 * What is measured (ADR 0022):
 *  - A **steady stretch** of at least {@link DRIFT_MIN_SECONDS} (40 minutes): the whole ride, or one
 *    lap of it (a structured workout with a long steady step), the longest one that qualifies.
 *  - A second **counts** when it is not paused and has a power reading and a heart rate above 0.
 *    At least 95% of the stretch must count.
 *  - It is **steady** when its variability index (normalised power over average power, over the
 *    counted seconds) is at most {@link DRIFT_MAX_VI}.
 *  - The counted seconds are split in two halves of equal length (the second one second longer
 *    when they are odd). For each half the efficiency factor is normalised power over average
 *    heart rate; the drift is `(EF first - EF second) / EF first * 100`, positive when the heart
 *    rate rose against the power.
 *
 * A ride, or a stretch of it, that does not qualify is skipped with the reason in plain words, never
 * measured badly. Other activities are not rides and never get here.
 */

export const DRIFT_MIN_SECONDS = 40 * 60;
export const DRIFT_MAX_VI = 1.05;
export const DRIFT_MIN_COVERAGE = 0.95;

const NP_WINDOW = 30;

export interface DriftMeasured {
  available: true;
  /** Percent, one decimal. */
  driftPercent: number;
  /** Normalised power over average heart rate in the first half, three decimals. */
  efFirst: number;
  efSecond: number;
  /** The stretch measured: after this many seconds of the ride, up to the second `endSec`. */
  startSec: number;
  endSec: number;
  /** Counted seconds, both halves together. */
  seconds: number;
  variabilityIndex: number;
}

export interface DriftSkipped {
  available: false;
  reason: string;
}

export type DriftResult = DriftMeasured | DriftSkipped;

const roundTo = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

const sum = (values: readonly number[]): number => values.reduce((a, b) => a + b, 0);

/** Coggan's normalised power: the fourth-power mean of 30-sample rolling averages; null for under 30 samples. */
export function normalizedPower(powers: readonly number[]): number | null {
  if (powers.length < NP_WINDOW) return null;
  let window = 0;
  let fourth = 0;
  let windows = 0;
  for (const [index, power] of powers.entries()) {
    window += power;
    if (index >= NP_WINDOW) window -= powers[index - NP_WINDOW] as number;
    if (index >= NP_WINDOW - 1) {
      fourth += (window / NP_WINDOW) ** 4;
      windows++;
    }
  }
  return (fourth / windows) ** 0.25;
}

function minutes(seconds: number): string {
  return `${Math.round(seconds / 60)} minutes`;
}

interface Candidate {
  startSec: number;
  endSec: number;
  whole: boolean;
}

/** Measures one stretch, or says why it cannot be. */
function measure(samples: readonly RideSample[], candidate: Candidate): DriftResult {
  const { startSec, endSec, whole } = candidate;
  const length = endSec - startSec;
  const what = whole ? "The ride" : "The longest steady step";
  if (length < DRIFT_MIN_SECONDS) {
    return {
      available: false,
      reason: `${what} is ${minutes(length)} long; aerobic drift needs a steady stretch of at least ${DRIFT_MIN_SECONDS / 60} minutes`,
    };
  }
  const powers: number[] = [];
  const heartRates: number[] = [];
  for (const sample of samples.slice(startSec, endSec)) {
    if (sample.paused === true || sample.power == null) continue;
    if (sample.heartRate == null || sample.heartRate <= 0) continue;
    powers.push(sample.power);
    heartRates.push(sample.heartRate);
  }
  const share = powers.length / length;
  if (share < DRIFT_MIN_COVERAGE) {
    return {
      available: false,
      reason: `${what} has power and heart rate for only ${Math.round(share * 100)}% of its ${minutes(length)} (pauses and gaps count against it); at least ${DRIFT_MIN_COVERAGE * 100}% is needed`,
    };
  }
  const np = normalizedPower(powers) as number;
  const average = sum(powers) / powers.length;
  if (average <= 0) {
    return { available: false, reason: `${what} has no power above 0 W` };
  }
  const variabilityIndex = np / average;
  if (variabilityIndex > DRIFT_MAX_VI) {
    return {
      available: false,
      reason: `${what} is not steady: its variability index is ${roundTo(variabilityIndex, 2)}, above ${DRIFT_MAX_VI}`,
    };
  }
  const half = Math.floor(powers.length / 2);
  const efOf = (from: number, to: number): number => {
    const halfNp = normalizedPower(powers.slice(from, to)) as number;
    return halfNp / (sum(heartRates.slice(from, to)) / (to - from));
  };
  const first = efOf(0, half);
  const second = efOf(half, powers.length);
  return {
    available: true,
    driftPercent: roundTo(((first - second) / first) * 100, 1),
    efFirst: roundTo(first, 3),
    efSecond: roundTo(second, 3),
    startSec,
    endSec,
    seconds: powers.length,
    variabilityIndex: roundTo(variabilityIndex, 2),
  };
}

/**
 * The aerobic drift of a ride: of its longest steady stretch (the whole ride or one lap) that
 * qualifies, or the reason the ride has none.
 */
export function aerobicDrift(samples: readonly RideSample[], laps: readonly Lap[]): DriftResult {
  if (samples.length === 0) return { available: false, reason: "The ride has no samples" };
  const candidates: Candidate[] = [{ startSec: 0, endSec: samples.length, whole: true }];
  for (const lap of laps) {
    if (lap.startSeconds === 0 && lap.endSeconds === samples.length) continue;
    candidates.push({ startSec: lap.startSeconds, endSec: lap.endSeconds, whole: false });
  }
  // The longest first; the whole ride before a lap of the same length.
  candidates.sort((a, b) => b.endSec - b.startSec - (a.endSec - a.startSec));
  let reason: DriftSkipped | null = null;
  for (const candidate of candidates) {
    const result = measure(samples, candidate);
    if (result.available) return result;
    // The reason given is the longest stretch's: the ride's own when it has one lap or none that is longer.
    reason ??= result;
  }
  return reason as DriftSkipped;
}
