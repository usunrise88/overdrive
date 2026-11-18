import { addDaysToIsoDate, type RecordCounts } from "@overdrive/contracts";
import type { StoredMetric } from "../../src/wellness/metrics.ts";
import type { DailyTable } from "../../src/wellness/series.ts";

/** `value`, which a test has just made sure is there. */
export function must<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) throw new Error("expected a value");
  return value;
}

/** The morning the synthetic histories end on. */
export const TODAY = "2026-11-18";

/** Days before TODAY: 0 is today, 1 yesterday. */
export type Back = number;

/** A value for each day before today, or nothing for a day without a reading. */
export type Pattern = (back: Back) => number | undefined;

/** `a` on even days before today and `b` on odd days: 21 of each in any window of 42 days, mean (a + b) / 2. */
export const alternating =
  (a: number, b: number): Pattern =>
  (back) =>
    back % 2 === 0 ? a : b;

/** The same value every day. */
export const constant =
  (value: number): Pattern =>
  () =>
    value;

/** `base`, with other values on the days named (days before today). */
export const planted =
  (base: Pattern, overrides: Record<number, number | undefined>): Pattern =>
  (back) =>
    back in overrides ? overrides[back] : base(back);

/** Readings only for the days from `from` to `to` before today (inclusive). */
export const only =
  (base: Pattern, from: number, to: number): Pattern =>
  (back) =>
    back >= from && back <= to ? base(back) : undefined;

/** A resolved daily table made of patterns, `days` days back from today, all from one source. */
export function makeTable(
  patterns: Partial<Record<StoredMetric, Pattern>>,
  options: { days?: number; source?: "healthconnect" | "intervals" } = {},
): DailyTable {
  const days = options.days ?? 130;
  const table: Partial<
    Record<StoredMetric, Map<string, { value: number; source: "healthconnect" | "intervals" }>>
  > = {};
  for (const [metric, pattern] of Object.entries(patterns) as [StoredMetric, Pattern][]) {
    const map = new Map<string, { value: number; source: "healthconnect" | "intervals" }>();
    for (let back = 0; back <= days; back++) {
      const value = pattern(back);
      if (value !== undefined) {
        map.set(addDaysToIsoDate(TODAY, -back), {
          value,
          source: options.source ?? "healthconnect",
        });
      }
    }
    table[metric] = map;
  }
  return table;
}

/**
 * A history in which every scored metric has a steady, known baseline: resting heart rate 50 and 54 (mean 52),
 * sleep 7 and 8 hours (mean 27000 s), bedtime 60 and 30 minutes before midnight (mean -45), respiratory rate
 * 13.8 and 14.6 (mean 14.2), oxygen saturation 95 and 97 (mean 96), heart rate in sleep 46 and 50 (mean 48).
 */
export const STEADY = {
  restingHr: alternating(50, 54),
  sleepSeconds: alternating(25200, 28800),
  bedtimeMinutes: alternating(-60, -30),
  respiratoryRate: alternating(13.8, 14.6),
  spo2: alternating(95, 97),
  sleepingHr: alternating(46, 50),
} satisfies Partial<Record<StoredMetric, Pattern>>;

/** The steady history with other values on some days before today. */
export function steadyWith(
  overrides: Partial<Record<keyof typeof STEADY, Record<number, number | undefined>>>,
  extra: Partial<Record<StoredMetric, Pattern>> = {},
): DailyTable {
  const patterns: Partial<Record<StoredMetric, Pattern>> = { ...extra };
  for (const [metric, pattern] of Object.entries(STEADY) as [keyof typeof STEADY, Pattern][]) {
    patterns[metric] = planted(pattern, overrides[metric] ?? {});
  }
  return makeTable(patterns);
}

/** The counts of an upload answer for `n` records of one kind. */
export const counts = (accepted: number, duplicates = 0, rejected = 0): RecordCounts => ({
  accepted,
  duplicates,
  rejected,
});
