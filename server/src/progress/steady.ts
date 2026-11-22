import {
  PROGRESS_EF_MIN_SECONDS,
  PROGRESS_STEADY_BAND_PERCENT,
  PROGRESS_STEADY_MAX_CV_PERCENT,
  PROGRESS_STEADY_MIN_HEART_RATE_PERCENT,
  PROGRESS_STEADY_MIN_POWER_PERCENT,
  PROGRESS_STEADY_MIN_SECONDS,
  PROGRESS_STEADY_SKIP_SECONDS,
  type RideRecord,
  type RideSample,
} from "@overdrive/contracts";
import { normalizedPower } from "../metrics/drift.ts";

/**
 * Steady stretches of a ride, and what is taken from them (spec §6.3 "Progress over time", ADR 0043). Pure functions; the numbers of the
 * Progress screen's two heart-rate measures (aerobic efficiency, efficiency factor) come from here.
 *
 * A **steady stretch** is a run of seconds of a ride that
 *  - starts after the ride's first {@link PROGRESS_STEADY_SKIP_SECONDS} (the warm-up is not a measure),
 *  - has no paused second and no second without a power reading, and in every second a power of at least
 *    {@link PROGRESS_STEADY_MIN_POWER_PERCENT}% of the FTP the ride was recorded with,
 *  - is at least {@link PROGRESS_STEADY_MIN_SECONDS} seconds long,
 *  - has 30-second rolling means of power (one per second, each over the 30 seconds that end at it) whose coefficient of variation is under
 *    {@link PROGRESS_STEADY_MAX_CV_PERCENT}% and none of which is more than {@link PROGRESS_STEADY_BAND_PERCENT}% from the stretch's mean power,
 *  - and has a heart rate in at least {@link PROGRESS_STEADY_MIN_HEART_RATE_PERCENT}% of its seconds.
 *
 * It works for free rides and workouts alike: it reads the ride's samples, not its plan. Stretches are found greedily from the start of
 * the ride: from a start second the stretch is extended while it stays steady, the longest steady extension is taken when it is long
 * enough (of the starts in the next 30 seconds the one that reaches furthest, the earliest on a tie; the next search starts after it),
 * otherwise the start moves on by one second. A sprint in the middle of a long steady
 * effort splits it into the two stretches on either side of it.
 */

export const STRETCH_VERSION = 1;

const ROLLING_SECONDS = 30;
/** A heart rate outside this is a sensor fault, not a reading. */
const PLAUSIBLE_HEART_RATE: readonly [number, number] = [30, 250];

export interface SteadyStretch {
  /** The second of the ride (0-based index of its first sample). */
  startSec: number;
  seconds: number;
  /** Watts, one decimal. */
  meanPower: number;
  /** bpm, one decimal, over the seconds that have a heart rate. */
  meanHeartRate: number;
  /** Seconds with a heart rate (at least 90% of `seconds`). */
  heartRateSeconds: number;
}

/** What the service keeps per ride for the Progress measures (`ride_progress_stats`). */
export interface RideProgressStats {
  stretches: SteadyStretch[];
  /** Normalised power over average heart rate of the steady parts together; null under 20 minutes of them. */
  efficiency: {
    value: number;
    normalizedPower: number;
    averageHeartRate: number;
    seconds: number;
  } | null;
}

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor + 0;
};

const heartRateOf = (sample: RideSample): number => {
  const bpm = sample.heartRate;
  return bpm != null && bpm >= PLAUSIBLE_HEART_RATE[0] && bpm <= PLAUSIBLE_HEART_RATE[1] ? bpm : 0;
};

/** The steady stretches of a ride recorded with `ftp`, in order. A ride with no FTP above 0 has none. */
export function steadyStretches(samples: readonly RideSample[], ftp: number): SteadyStretch[] {
  const n = samples.length;
  if (ftp <= 0 || n < PROGRESS_STEADY_MIN_SECONDS + PROGRESS_STEADY_SKIP_SECONDS) return [];
  const floor = (PROGRESS_STEADY_MIN_POWER_PERCENT / 100) * ftp;
  const power = new Float64Array(n);
  const usable = new Uint8Array(n);
  const prefix = new Float64Array(n + 1);
  for (const [i, sample] of samples.entries()) {
    const watts = sample.power;
    const ok =
      i >= PROGRESS_STEADY_SKIP_SECONDS &&
      sample.paused !== true &&
      watts != null &&
      watts >= floor;
    usable[i] = ok ? 1 : 0;
    power[i] = ok ? (watts as number) : 0;
    prefix[i + 1] = (prefix[i] as number) + (power[i] as number);
  }
  const maxCv = PROGRESS_STEADY_MAX_CV_PERCENT / 100;
  const band = PROGRESS_STEADY_BAND_PERCENT / 100;
  const found: SteadyStretch[] = [];

  /** The length of the longest steady stretch that starts at `start` (0 when there is none of at least the minimum). */
  const extend = (start: number): number => {
    let rolling = 0;
    let sumRolling = 0;
    let sumSquares = 0;
    let lowest = Number.POSITIVE_INFINITY;
    let highest = Number.NEGATIVE_INFINITY;
    let best = 0;
    for (let end = start; end < n && usable[end] === 1; end++) {
      const length = end + 1 - start;
      if (length >= ROLLING_SECONDS) {
        const mean30 =
          ((prefix[end + 1] as number) - (prefix[end + 1 - ROLLING_SECONDS] as number)) /
          ROLLING_SECONDS;
        rolling++;
        sumRolling += mean30;
        sumSquares += mean30 * mean30;
        if (mean30 < lowest) lowest = mean30;
        if (mean30 > highest) highest = mean30;
        const mean = ((prefix[end + 1] as number) - (prefix[start] as number)) / length;
        const meanOfRolling = sumRolling / rolling;
        const variance = Math.max(0, sumSquares / rolling - meanOfRolling * meanOfRolling);
        const cv = Math.sqrt(variance) / meanOfRolling;
        if (cv >= maxCv || lowest < (1 - band) * mean || highest > (1 + band) * mean) break;
      }
      if (length >= PROGRESS_STEADY_MIN_SECONDS) best = length;
    }
    return best;
  };

  let start = 0;
  while (start + PROGRESS_STEADY_MIN_SECONDS <= n) {
    if (usable[start] === 0) {
      start++;
      continue;
    }
    let first = extend(start);
    if (first === 0) {
      start++;
      continue;
    }
    // A start a few seconds into the effort's edge can stop short (the edge's seconds dilute away); of the starts in the next
    // ROLLING_SECONDS the one that reaches furthest is taken, the earliest on a tie.
    let begin = start;
    for (let later = start + 1; later <= start + ROLLING_SECONDS && later < n; later++) {
      if (usable[later] === 0) break;
      const length = extend(later);
      if (length > 0 && later + length > begin + first) {
        begin = later;
        first = length;
      }
    }
    const stretch = measure(samples, power, begin, first);
    if (stretch !== null) found.push(stretch);
    start = begin + first;
  }
  return found;
}

/** The figures of a stretch, or null when it has a heart rate in too few of its seconds. */
function measure(
  samples: readonly RideSample[],
  power: Float64Array,
  start: number,
  seconds: number,
): SteadyStretch | null {
  let watts = 0;
  let heartSum = 0;
  let heartSeconds = 0;
  for (let i = start; i < start + seconds; i++) {
    watts += power[i] as number;
    const bpm = heartRateOf(samples[i] as RideSample);
    if (bpm > 0) {
      heartSum += bpm;
      heartSeconds++;
    }
  }
  if (heartSeconds < (PROGRESS_STEADY_MIN_HEART_RATE_PERCENT / 100) * seconds) return null;
  return {
    startSec: start,
    seconds,
    meanPower: round(watts / seconds, 1),
    meanHeartRate: round(heartSum / heartSeconds, 1),
    heartRateSeconds: heartSeconds,
  };
}

/**
 * The efficiency factor of a ride: normalised power over average heart rate, taken over the ride's steady stretches together (the
 * stretches' seconds one after the other), when they add up to at least {@link PROGRESS_EF_MIN_SECONDS}; otherwise null.
 */
export function efficiencyOf(
  samples: readonly RideSample[],
  stretches: readonly SteadyStretch[],
): RideProgressStats["efficiency"] {
  const seconds = stretches.reduce((sum, s) => sum + s.seconds, 0);
  if (seconds < PROGRESS_EF_MIN_SECONDS) return null;
  const powers: number[] = [];
  let heartSum = 0;
  let heartSeconds = 0;
  for (const stretch of stretches) {
    for (let i = stretch.startSec; i < stretch.startSec + stretch.seconds; i++) {
      const sample = samples[i] as RideSample;
      powers.push(sample.power ?? 0);
      const bpm = heartRateOf(sample);
      if (bpm > 0) {
        heartSum += bpm;
        heartSeconds++;
      }
    }
  }
  const np = normalizedPower(powers);
  if (np === null || heartSeconds === 0) return null;
  const averageHeartRate = heartSum / heartSeconds;
  return {
    value: round(np / averageHeartRate, 3),
    normalizedPower: round(np, 1),
    averageHeartRate: round(averageHeartRate, 1),
    seconds,
  };
}

/** Everything the Progress measures need of one stored ride. */
export function rideProgressStats(record: RideRecord): RideProgressStats {
  const stretches = steadyStretches(record.samples, record.ride.ftp);
  return { stretches, efficiency: efficiencyOf(record.samples, stretches) };
}
