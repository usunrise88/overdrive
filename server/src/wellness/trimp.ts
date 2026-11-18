/**
 * Heart-rate load of an activity from the watch (spec §6.7, ADR 0023), on the same scale as the
 * power-based training stress score: **one hour at threshold heart rate is 100**.
 *
 * The method is Banister's exponential TRIMP (Banister 1991) per sample interval. With the heart-rate
 * reserve fraction `x = (HR - rest) / (max - rest)`, clamped to 0..1, a sample that holds for `dt`
 * hours contributes `dt * y(x)` with `y(x) = x * exp(b * x)`. The load is that sum divided by `y(x_thr)`
 * (the hour at threshold, `x_thr = (thr - rest) / (max - rest)`) times 100, so the constant `0.64` of
 * Banister's formula cancels and a steady hour at threshold heart rate is exactly 100.
 *
 * `b` is Banister's coefficient for men, 1.92 (women 1.67). The profile has no sex, and dividing by the
 * threshold's own value absorbs most of the difference (the two coefficients differ by under 10% in the
 * relative weight of an easy hour against a threshold hour), so one fixed `b` is used and the figure
 * stays approximate. Known bias (spec §6.7): heart rate lags in short intermittent efforts, so a
 * HIIT-style session is under-counted.
 */
export const TRIMP_EXPONENT = 1.92;

/** A sample holds until the next one, but not longer than this: a gap in the data is not heart rate. */
export const HOLD_CAP_SECONDS = 300;

/**
 * The same cap for a ride imported from a FIT file (spec §11.8): a bike computer writes a record every second or every few, and
 * stops writing while the timer is stopped (a traffic light, a café), so a longer gap is a stop and not heart rate that went on.
 */
export const FIT_HOLD_CAP_SECONDS = 30;

/** Below this share of the session within reach of a sample, the load is flagged approximate. */
export const MIN_COVERAGE = 0.8;

export interface HrSample {
  /** Epoch milliseconds. */
  tMs: number;
  bpm: number;
}

export interface HeartRateSummary {
  samples: number;
  /** The median gap between consecutive samples in seconds; null with fewer than two samples. */
  medianGapSeconds: number | null;
  maxGapSeconds: number | null;
  /** The share of the session within reach of a sample (each holds up to {@link HOLD_CAP_SECONDS}), 0 to 1. */
  coverage: number;
  /** Time-weighted; null without samples. */
  averageHeartRate: number | null;
  maxHeartRate: number | null;
}

function median(sorted: readonly number[]): number | null {
  if (sorted.length === 0) return null;
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1
    ? (sorted[mid] as number)
    : ((sorted[mid - 1] as number) + (sorted[mid] as number)) / 2;
}

/**
 * How long each sample holds, in seconds: until the next sample, at most {@link HOLD_CAP_SECONDS}; the
 * last one for the median gap (or the cap) and no further than the end of the session.
 */
export function holdSeconds(
  samples: readonly HrSample[],
  endMs: number,
  capSeconds: number = HOLD_CAP_SECONDS,
): number[] {
  const gaps: number[] = [];
  for (let i = 1; i < samples.length; i++) {
    gaps.push(((samples[i] as HrSample).tMs - (samples[i - 1] as HrSample).tMs) / 1000);
  }
  const typical = median([...gaps].sort((a, b) => a - b)) ?? capSeconds;
  return samples.map((sample, i) => {
    const next = samples[i + 1];
    const hold =
      next === undefined
        ? Math.min(capSeconds, typical, Math.max(0, (endMs - sample.tMs) / 1000))
        : Math.min(capSeconds, (next.tMs - sample.tMs) / 1000);
    return Math.max(0, hold);
  });
}

/** What the samples of a session (in order, inside it) say about heart rate and about how dense they are. */
export function summarizeSamples(
  samples: readonly HrSample[],
  startMs: number,
  endMs: number,
  capSeconds: number = HOLD_CAP_SECONDS,
): HeartRateSummary {
  if (samples.length === 0) {
    return {
      samples: 0,
      medianGapSeconds: null,
      maxGapSeconds: null,
      coverage: 0,
      averageHeartRate: null,
      maxHeartRate: null,
    };
  }
  const gaps: number[] = [];
  for (let i = 1; i < samples.length; i++) {
    gaps.push(((samples[i] as HrSample).tMs - (samples[i - 1] as HrSample).tMs) / 1000);
  }
  gaps.sort((a, b) => a - b);
  const holds = holdSeconds(samples, endMs, capSeconds);
  const covered = holds.reduce((a, b) => a + b, 0);
  const weighted = samples.reduce((a, sample, i) => a + sample.bpm * (holds[i] as number), 0);
  const duration = Math.max(1, (endMs - startMs) / 1000);
  return {
    samples: samples.length,
    medianGapSeconds: median(gaps),
    maxGapSeconds: gaps.length === 0 ? null : (gaps[gaps.length - 1] as number),
    coverage: Math.min(1, covered / duration),
    averageHeartRate:
      covered > 0 ? weighted / covered : samples.reduce((a, s) => a + s.bpm, 0) / samples.length,
    maxHeartRate: samples.reduce((a, s) => Math.max(a, s.bpm), 0),
  };
}

export interface HeartRateLimits {
  rest: number;
  max: number;
  threshold: number;
}

const y = (x: number): number => x * Math.exp(TRIMP_EXPONENT * x);

/**
 * The load of a session: 100 for an hour at threshold heart rate. Null when the limits do not make
 * sense (the threshold must lie above rest and below the maximum) or there are no samples.
 */
export function trimpLoad(
  samples: readonly HrSample[],
  endMs: number,
  limits: HeartRateLimits,
  capSeconds: number = HOLD_CAP_SECONDS,
): number | null {
  const { rest, max, threshold } = limits;
  if (samples.length === 0 || !(rest < threshold && threshold < max)) return null;
  const reserve = max - rest;
  const holds = holdSeconds(samples, endMs, capSeconds);
  let sum = 0;
  for (const [i, sample] of samples.entries()) {
    const x = Math.min(1, Math.max(0, (sample.bpm - rest) / reserve));
    sum += ((holds[i] as number) / 3600) * y(x);
  }
  return (100 * sum) / y((threshold - rest) / reserve);
}
