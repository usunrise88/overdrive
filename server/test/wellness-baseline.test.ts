import { addDaysToIsoDate } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  baselineBefore,
  deviationOf,
  sampleStats,
  shownRange,
  zScore,
} from "../src/wellness/baseline.ts";
import { DEFAULT_WELLNESS_SETTINGS, METRIC_SPECS } from "../src/wellness/metrics.ts";
import { buildDays, nightsLogged, plainSeries } from "../src/wellness/series.ts";
import {
  alternating,
  constant,
  makeTable,
  must,
  only,
  planted,
  type STEADY,
  steadyWith,
  TODAY,
} from "./helpers/wellness.ts";

const settings = DEFAULT_WELLNESS_SETTINGS;
const resting = METRIC_SPECS.restingHr;

/** Values by date, from patterns of days before today. */
const series = (pattern: (back: number) => number | undefined, days = 100) =>
  plainSeries(makeTable({ restingHr: pattern }, { days }).restingHr);

describe("sampleStats", () => {
  it("is the mean and the sample (n - 1) standard deviation", () => {
    // 7 x 50 and 7 x 54: mean 52, sum of squares 14 x 4 = 56, variance 56 / 13.
    const { n, mean, sd } = sampleStats([...Array(7).fill(50), ...Array(7).fill(54)]);
    expect(n).toBe(14);
    expect(mean).toBe(52);
    expect(sd).toBeCloseTo(Math.sqrt(56 / 13), 12);
    expect(sd).toBeCloseTo(2.0755, 4);
  });

  it("has no deviation from one value or none", () => {
    expect(sampleStats([5])).toEqual({ n: 1, mean: 5, sd: 0 });
    expect(sampleStats([])).toEqual({ n: 0, mean: 0, sd: 0 });
  });
});

describe("the baseline in force on a day", () => {
  it("is the mean of the 42 days before it, minus and plus one standard deviation", () => {
    // 21 days at 50 and 21 at 54 before today: mean 52, variance 168 / 41.
    const baseline = baselineBefore(series(alternating(50, 54)), TODAY, resting, settings);
    expect(baseline?.n).toBe(42);
    expect(baseline?.mean).toBe(52);
    expect(baseline?.spread).toBeCloseTo(Math.sqrt(168 / 41), 12);
    expect(baseline?.low).toBeCloseTo(52 - 2.0243, 3);
    expect(baseline?.high).toBeCloseTo(52 + 2.0243, 3);
    expect(shownRange(must(baseline), resting)).toEqual({ low: 50, high: 54 });
  });

  it("does not include the day's own value", () => {
    const withToday = baselineBefore(
      series(planted(alternating(50, 54), { 0: 90 })),
      TODAY,
      resting,
      settings,
    );
    const without = baselineBefore(series(alternating(50, 54)), TODAY, resting, settings);
    expect(withToday).toEqual(without);
  });

  it("ignores what is older than the window and what is later than the day", () => {
    const old = planted(alternating(50, 54), { 43: 150, 60: 150, 90: 150 });
    expect(baselineBefore(series(old), TODAY, resting, settings)?.mean).toBe(52);
    const later = baselineBefore(
      series(alternating(50, 54)),
      addDaysToIsoDate(TODAY, -5),
      resting,
      settings,
    );
    expect(later?.n).toBe(42);
  });

  it("needs 14 readings, and a missing day is not a reading", () => {
    expect(
      baselineBefore(series(only(alternating(50, 54), 1, 13)), TODAY, resting, settings),
    ).toBeNull();
    expect(
      baselineBefore(series(only(alternating(50, 54), 1, 14)), TODAY, resting, settings)?.n,
    ).toBe(14);
    // Every other day: 21 readings.
    const sparse = (back: number) => (back % 2 === 0 ? 52 : undefined);
    expect(baselineBefore(series(sparse), TODAY, resting, settings)?.n).toBe(21);
  });

  it("can be asked for other windows and thresholds", () => {
    const wide = { baselineDays: 60, nightsNeeded: 14 };
    expect(baselineBefore(series(alternating(50, 54)), TODAY, resting, wide)?.n).toBe(60);
    const eager = { baselineDays: 28, nightsNeeded: 7 };
    expect(baselineBefore(series(only(alternating(50, 54), 1, 7)), TODAY, resting, eager)?.n).toBe(
      7,
    );
  });

  it("never has a spread below the metric's floor", () => {
    // A resting heart rate that never moves would make one beat an outlier; the floor is 1.5 bpm.
    const baseline = baselineBefore(series(constant(52)), TODAY, resting, settings);
    expect(baseline?.spread).toBe(1.5);
    expect(baseline?.low).toBe(50.5);
    expect(baseline?.high).toBe(53.5);
    // Sleep timing counts a shift only beyond an hour.
    const bedtime = plainSeries(
      makeTable({ bedtimeMinutes: alternating(-60, -30) }).bedtimeMinutes,
    );
    const timing = baselineBefore(bedtime, TODAY, METRIC_SPECS.bedtimeMinutes, settings);
    expect(timing?.mean).toBe(-45);
    expect(timing?.spread).toBe(60);
    expect(shownRange(must(timing), METRIC_SPECS.bedtimeMinutes)).toEqual({ low: -105, high: 15 });
  });

  it("works on the natural-log scale for heart-rate variability", () => {
    const hrv = METRIC_SPECS.hrvRmssd;
    // 60 ms and 80 ms alternate: the mean of ln is (ln 60 + ln 80) / 2, the geometric mean, 69.28 ms.
    const values = plainSeries(makeTable({ hrvRmssd: alternating(60, 80) }).hrvRmssd);
    const baseline = baselineBefore(values, TODAY, hrv, settings);
    expect(Math.exp(must(baseline).mean)).toBeCloseTo(Math.sqrt(60 * 80), 9);
    // The spread of ln: (ln 80 - ln 60) / 2 x sqrt(42 / 41) = 0.1438 x 1.012 = 0.1455, above the 0.08 floor.
    expect(baseline?.spread).toBeCloseTo(
      ((Math.log(80) - Math.log(60)) / 2) * Math.sqrt(42 / 41),
      9,
    );
    expect(baseline?.low).toBeCloseTo(Math.exp(must(baseline).mean - must(baseline).spread), 9);
    expect(zScore(Math.sqrt(60 * 80), must(baseline), hrv)).toBeCloseTo(0, 9);
  });

  it("scores a value in spreads from the mean, and calls it outside beyond one", () => {
    const baseline = must(baselineBefore(series(alternating(50, 54)), TODAY, resting, settings));
    expect(zScore(52, baseline, resting)).toBe(0);
    // (58 - 52) / sqrt(168 / 41) = 6 / 2.02424 = 2.9641.
    expect(zScore(58, baseline, resting)).toBeCloseTo(2.9641, 4);
    expect(zScore(46, baseline, resting)).toBeCloseTo(-2.9641, 4);
    expect(deviationOf(1)).toBe("inside");
    expect(deviationOf(-1)).toBe("inside");
    expect(deviationOf(1.0001)).toBe("above");
    expect(deviationOf(-1.0001)).toBe("below");
    // 54.05 is just beyond the high end of the usual range (54.02), 54.0 is not.
    expect(deviationOf(zScore(54, baseline, resting))).toBe("inside");
    expect(deviationOf(zScore(54.05, baseline, resting))).toBe("above");
  });
});

describe("nights logged", () => {
  it("counts the days with a reading of a scored metric in the window before the morning", () => {
    expect(nightsLogged(steadyWith({}), TODAY, settings)).toBe(42);
    const lately = makeTable({ restingHr: only(constant(52), 1, 9) });
    expect(nightsLogged(lately, TODAY, settings)).toBe(9);
    // A step count or a wake-up time alone is not a night.
    expect(
      nightsLogged(
        makeTable({ steps: constant(8000), wakeMinutes: constant(400) }),
        TODAY,
        settings,
      ),
    ).toBe(0);
    // Readings of different metrics on one day make one night.
    const mixed = makeTable({
      restingHr: only(constant(52), 1, 5),
      sleepSeconds: only(constant(27000), 3, 8),
    });
    expect(nightsLogged(mixed, TODAY, settings)).toBe(8);
  });
});

describe("the days of the wellness answer", () => {
  it("gives every day its value, source, usual range, deviation and streak", () => {
    // Resting heart rate 58 on the last three days (days 2, 1, 0 before today) after a steady history.
    const table = steadyWith({ restingHr: { 2: 58, 1: 58, 0: 58 } });
    const { days } = buildDays(table, addDaysToIsoDate(TODAY, -3), TODAY, settings);
    expect(days.map((d) => d.date)).toEqual([
      "2026-11-15",
      "2026-11-16",
      "2026-11-17",
      "2026-11-18",
    ]);
    const [steady, first, second, third] = days.map((d) => d.restingHr);
    expect(steady).toMatchObject({
      value: 54,
      source: "healthconnect",
      deviation: "inside",
      streak: 0,
    });
    expect(first).toMatchObject({ value: 58, deviation: "above", streak: 1 });
    expect(second).toMatchObject({ value: 58, deviation: "above", streak: 2 });
    expect(third).toMatchObject({ value: 58, deviation: "above", streak: 3 });
    // The range in force that day is of the 42 days before it, so it follows the readings in.
    expect(steady?.baselineLow).toBe(50);
    expect(steady?.baselineHigh).toBe(54);
    expect(third?.baselineHigh).toBeGreaterThan(54);
  });

  it("restarts the streak when the side changes and ends it inside the range, without a reading too", () => {
    const table = steadyWith({ restingHr: { 4: 58, 3: 58, 2: 46, 1: 52, 0: 58 } });
    const { days } = buildDays(table, addDaysToIsoDate(TODAY, -4), TODAY, settings);
    expect(days.map((d) => [d.restingHr.deviation, d.restingHr.streak])).toEqual([
      ["above", 1],
      ["above", 2],
      ["below", 1],
      ["inside", 0],
      ["above", 1],
    ]);
    // A day without a reading breaks the run: nothing is claimed about it.
    const gap = steadyWith({ restingHr: { 3: 58, 2: undefined, 1: 58, 0: 58 } });
    const gapped = buildDays(gap, addDaysToIsoDate(TODAY, -3), TODAY, settings).days;
    expect(gapped.map((d) => [d.restingHr.value, d.restingHr.streak])).toEqual([
      [58, 1],
      [null, 0],
      [58, 1],
      [58, 2],
    ]);
    expect(gapped[1]?.restingHr).toEqual({
      value: null,
      source: null,
      baselineLow: null,
      baselineHigh: null,
      deviation: null,
      streak: 0,
    });
  });

  it("counts a streak that began before the range asked for", () => {
    const table = steadyWith({ restingHr: { 5: 58, 4: 58, 3: 58, 2: 58, 1: 58, 0: 58 } });
    const { days } = buildDays(table, TODAY, TODAY, settings);
    expect(days[0]?.restingHr.streak).toBe(6);
  });

  it("shows no range, deviation or streak while fewer than 14 readings stand behind it", () => {
    const table = makeTable({ restingHr: only(alternating(50, 54), 0, 10) });
    const { days } = buildDays(table, TODAY, TODAY, settings);
    expect(days[0]?.restingHr).toEqual({
      value: 50,
      source: "healthconnect",
      baselineLow: null,
      baselineHigh: null,
      deviation: null,
      streak: 0,
    });
  });

  it("carries sleep times, steps and the usual bedtime and wake-up ranges", () => {
    const table = makeTable({
      bedtimeMinutes: alternating(-60, -30),
      wakeMinutes: alternating(400, 420),
      sleepStartMs: constant(Date.UTC(2026, 10, 17, 22, 5)),
      sleepEndMs: constant(Date.UTC(2026, 10, 18, 5, 45)),
      steps: constant(8123.4),
      hrvSdnn: constant(71.54),
    });
    const { days, usual } = buildDays(table, TODAY, TODAY, settings);
    expect(days[0]).toMatchObject({
      sleepStart: "2026-11-17T22:05:00Z",
      sleepEnd: "2026-11-18T05:45:00Z",
      steps: 8123,
      hrvSdnn: 71.5,
    });
    // Bedtime: mean -45, spread floored at an hour. Wake-up: mean 410, spread 10 x 1.012 floored at 30 minutes.
    expect(usual.bedtime).toEqual({ low: -105, high: 15 });
    expect(usual.wake).toEqual({ low: 380, high: 440 });
  });

  it("has an empty day for a date with nothing", () => {
    const { days, usual } = buildDays({}, TODAY, TODAY, settings);
    expect(days).toHaveLength(1);
    expect(days[0]?.sleepStart).toBeNull();
    expect(days[0]?.steps).toBeNull();
    expect(usual).toEqual({ bedtime: null, wake: null });
  });
});

describe("the steady synthetic history", () => {
  it("has the baselines the tests assume", () => {
    const table = steadyWith({});
    for (const [metric, mean] of Object.entries({
      restingHr: 52,
      sleepSeconds: 27000,
      bedtimeMinutes: -45,
      respiratoryRate: 14.2,
      spo2: 96,
      sleepingHr: 48,
    }) as [keyof typeof STEADY, number][]) {
      const baseline = baselineBefore(
        plainSeries(table[metric]),
        TODAY,
        METRIC_SPECS[metric],
        settings,
      );
      expect(baseline?.mean, metric).toBeCloseTo(mean, 9);
    }
  });
});
