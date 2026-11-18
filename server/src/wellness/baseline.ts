import { addDaysToIsoDate, type Deviation } from "@overdrive/contracts";
import { type MetricSpec, roundTo, type WellnessSettings } from "./metrics.ts";

/**
 * Personal baselines (spec §6.4, ADR 0023). A metric's baseline for a day is the mean of its daily
 * values over the `baselineDays` days **before** that day (the day's own value is not part of what it
 * is compared with), with the sample standard deviation as the spread, never smaller than the
 * metric's floor (`MetricSpec.minSpread`). The usual range is the mean minus and plus one spread. A
 * baseline exists only with at least `nightsNeeded` readings behind it.
 */

/** Daily values of one metric, by local date `YYYY-MM-DD`. */
export type DaySeries = ReadonlyMap<string, number>;

export interface Baseline {
  /** Readings behind it. */
  n: number;
  /** The mean, on the scale the metric is compared on (natural log for heart-rate variability). */
  mean: number;
  /** One spread: the sample standard deviation on that scale, at least the metric's floor. */
  spread: number;
  /** The usual range in the metric's own unit (mean minus and plus one spread, back from the log scale if need be). */
  low: number;
  high: number;
}

/** The mean and the sample (n - 1) standard deviation; the deviation is 0 for fewer than two values. */
export function sampleStats(values: readonly number[]): { n: number; mean: number; sd: number } {
  const n = values.length;
  if (n === 0) return { n: 0, mean: 0, sd: 0 };
  const mean = values.reduce((a, b) => a + b, 0) / n;
  if (n < 2) return { n, mean, sd: 0 };
  const sumSquares = values.reduce((a, b) => a + (b - mean) ** 2, 0);
  return { n, mean, sd: Math.sqrt(sumSquares / (n - 1)) };
}

/** A value on the scale the metric is compared on. */
export function onScale(spec: MetricSpec, value: number): number {
  return spec.ln ? Math.log(value) : value;
}

function backFromScale(spec: MetricSpec, scaled: number): number {
  return spec.ln ? Math.exp(scaled) : scaled;
}

/** Baseline from readings already on the comparison scale; null with too few. */
export function baselineFromScaled(
  scaled: readonly number[],
  spec: MetricSpec,
  settings: WellnessSettings,
): Baseline | null {
  if (scaled.length < settings.nightsNeeded) return null;
  const { n, mean, sd } = sampleStats(scaled);
  const spread = Math.max(sd, spec.minSpread);
  return {
    n,
    mean,
    spread,
    low: backFromScale(spec, mean - spread),
    high: backFromScale(spec, mean + spread),
  };
}

/** The baseline in force on `date`: the readings of the `baselineDays` days before it. Null with too few. */
export function baselineBefore(
  series: DaySeries,
  date: string,
  spec: MetricSpec,
  settings: WellnessSettings,
): Baseline | null {
  const scaled: number[] = [];
  for (let back = 1; back <= settings.baselineDays; back++) {
    const value = series.get(addDaysToIsoDate(date, -back));
    if (value !== undefined && (!spec.ln || value > 0)) scaled.push(onScale(spec, value));
  }
  return baselineFromScaled(scaled, spec, settings);
}

/** How many spreads `value` is from the mean: positive above, negative below. */
export function zScore(value: number, baseline: Baseline, spec: MetricSpec): number {
  return (onScale(spec, value) - baseline.mean) / baseline.spread;
}

/** Outside the usual range by more than one spread, and on which side. */
export function deviationOf(z: number): Deviation {
  if (z > 1) return "above";
  if (z < -1) return "below";
  return "inside";
}

/** The usual range as it is shown: rounded to the metric's decimals. */
export function shownRange(baseline: Baseline, spec: MetricSpec): { low: number; high: number } {
  return { low: roundTo(baseline.low, spec.digits), high: roundTo(baseline.high, spec.digits) };
}
