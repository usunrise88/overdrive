import {
  addDaysToIsoDate,
  type Deviation,
  isoDateToEpochDay,
  type Reading,
  type UsualRange,
  type WellnessDay,
  type WellnessMetric,
  type WellnessSourceId,
} from "@overdrive/contracts";
import { baselineBefore, type DaySeries, deviationOf, shownRange, zScore } from "./baseline.ts";
import {
  METRIC_KEYS,
  METRIC_SPECS,
  type MetricSpec,
  roundTo,
  type StoredMetric,
  type WellnessSettings,
} from "./metrics.ts";

/**
 * The daily figures with the baseline in force on each day (spec §6.4, `GET /v1/wellness`).
 * `streak` looks back at most this many days before the first day asked for.
 */
export const STREAK_LOOKBACK_DAYS = 60;

/** A daily value and the source it was taken from. */
export interface DailyValue {
  value: number;
  source: WellnessSourceId;
}

/** The resolved daily table: for each metric, its value on each local date (the preferred source's). */
export type DailyTable = Partial<Record<StoredMetric, ReadonlyMap<string, DailyValue>>>;

/** One metric on one day, with what the rules need and the reading as it is shown. */
export interface Tracked {
  value: number;
  source: WellnessSourceId;
  /** The usual range in force that day, or null without enough readings behind it. */
  range: { low: number; high: number } | null;
  /** Spreads from the mean (positive above), null without a range. */
  z: number | null;
  deviation: Deviation | null;
  /** Days in a row up to and including this one outside the range on this side. */
  streak: number;
}

const NO_READING: Reading = {
  value: null,
  source: null,
  baselineLow: null,
  baselineHigh: null,
  deviation: null,
  streak: 0,
};

/** The values of one metric without their sources. */
export function plainSeries(table: ReadonlyMap<string, DailyValue> | undefined): DaySeries {
  const plain = new Map<string, number>();
  for (const [date, entry] of table ?? []) plain.set(date, entry.value);
  return plain;
}

/**
 * Tracks one metric from `first` to `last` (inclusive): for each day with a value, its range, how far
 * from the mean it is and how many days in a row it has been outside on the same side.
 */
export function trackMetric(
  table: ReadonlyMap<string, DailyValue> | undefined,
  spec: MetricSpec,
  settings: WellnessSettings,
  first: string,
  last: string,
): Map<string, Tracked> {
  const tracked = new Map<string, Tracked>();
  if (table === undefined) return tracked;
  const values = plainSeries(table);
  const firstDay = isoDateToEpochDay(first) as number;
  const lastDay = isoDateToEpochDay(last) as number;
  for (let day = firstDay; day <= lastDay; day++) {
    const date = addDaysToIsoDate("1970-01-01", day);
    const entry = table.get(date);
    if (entry === undefined || (spec.ln && entry.value <= 0)) continue;
    const baseline = baselineBefore(values, date, spec, settings);
    if (baseline === null) {
      tracked.set(date, {
        value: entry.value,
        source: entry.source,
        range: null,
        z: null,
        deviation: null,
        streak: 0,
      });
      continue;
    }
    const z = zScore(entry.value, baseline, spec);
    const deviation = deviationOf(z);
    const before = tracked.get(addDaysToIsoDate(date, -1));
    const streak =
      deviation === "inside" ? 0 : before?.deviation === deviation ? before.streak + 1 : 1;
    tracked.set(date, {
      value: entry.value,
      source: entry.source,
      range: shownRange(baseline, spec),
      z,
      deviation,
      streak,
    });
  }
  return tracked;
}

/** The reading of a tracked day as the contract writes it. */
export function readingOf(tracked: Tracked | undefined, spec: MetricSpec): Reading {
  if (tracked === undefined) return NO_READING;
  return {
    value: roundTo(tracked.value, spec.digits),
    source: tracked.source,
    baselineLow: tracked.range?.low ?? null,
    baselineHigh: tracked.range?.high ?? null,
    deviation: tracked.deviation,
    streak: tracked.streak,
  };
}

/** Days with at least one reading of a scored metric, among the `baselineDays` days before `date`. */
export function nightsLogged(table: DailyTable, date: string, settings: WellnessSettings): number {
  const scored = METRIC_KEYS.filter((key) => METRIC_SPECS[key].adverse !== null);
  let nights = 0;
  for (let back = 1; back <= settings.baselineDays; back++) {
    const day = addDaysToIsoDate(date, -back);
    if (scored.some((key) => table[key]?.has(day) === true)) nights++;
  }
  return nights;
}

const asInstant = (ms: number | undefined): string | null =>
  ms === undefined ? null : new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");

/**
 * The days from `from` to `to` with every metric's reading, and the usual bedtime and wake-up ranges
 * as of `to`. The table must hold values from `settings.baselineDays + STREAK_LOOKBACK_DAYS` days
 * before `from`.
 */
export function buildDays(
  table: DailyTable,
  from: string,
  to: string,
  settings: WellnessSettings,
): { days: WellnessDay[]; usual: { bedtime: UsualRange | null; wake: UsualRange | null } } {
  const lookback = addDaysToIsoDate(from, -STREAK_LOOKBACK_DAYS);
  const tracks = new Map<WellnessMetric, Map<string, Tracked>>();
  for (const key of METRIC_KEYS) {
    tracks.set(key, trackMetric(table[key], METRIC_SPECS[key], settings, lookback, to));
  }
  const days: WellnessDay[] = [];
  const first = isoDateToEpochDay(from) as number;
  const last = isoDateToEpochDay(to) as number;
  for (let day = first; day <= last; day++) {
    const date = addDaysToIsoDate("1970-01-01", day);
    const reading = (key: WellnessMetric): Reading =>
      readingOf(tracks.get(key)?.get(date), METRIC_SPECS[key]);
    const steps = table.steps?.get(date)?.value;
    const sdnn = table.hrvSdnn?.get(date)?.value;
    days.push({
      date,
      restingHr: reading("restingHr"),
      sleepSeconds: reading("sleepSeconds"),
      bedtimeMinutes: reading("bedtimeMinutes"),
      wakeMinutes: reading("wakeMinutes"),
      respiratoryRate: reading("respiratoryRate"),
      spo2: reading("spo2"),
      sleepingHr: reading("sleepingHr"),
      hrvRmssd: reading("hrvRmssd"),
      sleepStart: asInstant(table.sleepStartMs?.get(date)?.value),
      sleepEnd: asInstant(table.sleepEndMs?.get(date)?.value),
      steps: steps === undefined ? null : Math.round(steps),
      hrvSdnn: sdnn === undefined ? null : roundTo(sdnn, 1),
    });
  }
  const usualOf = (key: "bedtimeMinutes" | "wakeMinutes"): UsualRange | null => {
    const spec = METRIC_SPECS[key];
    const baseline = baselineBefore(plainSeries(table[key]), to, spec, settings);
    return baseline === null ? null : shownRange(baseline, spec);
  };
  return { days, usual: { bedtime: usualOf("bedtimeMinutes"), wake: usualOf("wakeMinutes") } };
}
