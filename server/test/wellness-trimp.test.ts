import { describe, expect, it } from "vitest";
import {
  HOLD_CAP_SECONDS,
  type HrSample,
  holdSeconds,
  summarizeSamples,
  TRIMP_EXPONENT,
  trimpLoad,
} from "../src/wellness/trimp.ts";

/** Samples every `step` seconds from the start, `bpm(i)` for the i-th. */
function samples(count: number, step: number, bpm: (i: number) => number, startMs = 0): HrSample[] {
  return Array.from({ length: count }, (_, i) => ({ tMs: startMs + i * step * 1000, bpm: bpm(i) }));
}

// Rest 50, maximum 190, threshold 170 bpm: a reserve of 140, the threshold at 120 / 140 = 6/7 of it.
const limits = { rest: 50, max: 190, threshold: 170 };
const HOUR_MS = 3_600_000;

describe("heart-rate load", () => {
  it("uses Banister's coefficient for men", () => {
    expect(TRIMP_EXPONENT).toBe(1.92);
  });

  it("is 100 for one hour at threshold heart rate, however the samples are spaced", () => {
    // By hand: x = 6/7, y(x) = x e^(1.92 x); an hour at x_thr is y(x_thr) / y(x_thr) x 100 = 100.
    for (const step of [1, 5, 10, 60]) {
      const hour = samples(3600 / step, step, () => 170);
      expect(trimpLoad(hour, HOUR_MS, limits)).toBeCloseTo(100, 9);
    }
  });

  it("is 63.34 for an hour at 150 bpm", () => {
    // x = (150 - 50) / 140 = 5/7 = 0.714286, y = 0.714286 x e^1.371429 = 0.714286 x 3.94106 = 2.81498;
    // y(6/7) = 0.857143 x e^1.645714 = 0.857143 x 5.18467 = 4.44404; 100 x 2.81498 / 4.44404 = 63.343.
    const hour = samples(3600, 1, () => 150);
    expect(trimpLoad(hour, HOUR_MS, limits)).toBeCloseTo(63.3429, 3);
  });

  it("is 38.52 for an hour at 130 bpm and 153.49 for an hour at the maximum", () => {
    expect(
      trimpLoad(
        samples(3600, 1, () => 130),
        HOUR_MS,
        limits,
      ),
    ).toBeCloseTo(38.5183, 3);
    // x = 1: y(1) = e^1.92 = 6.82096; 100 x 6.82096 / 4.44404 = 153.486.
    expect(
      trimpLoad(
        samples(3600, 1, () => 190),
        HOUR_MS,
        limits,
      ),
    ).toBeCloseTo(153.4856, 3);
  });

  it("scales with time: 42 minutes at 150 bpm is 44.34", () => {
    const session = samples(2520, 1, () => 150);
    expect(trimpLoad(session, 2520_000, limits)).toBeCloseTo((63.3429 * 42) / 60, 3);
  });

  it("adds the parts of a session: half an hour at 130 and half at threshold", () => {
    const session = samples(3600, 1, (i) => (i < 1800 ? 130 : 170));
    // 0.5 x 38.5183 + 0.5 x 100 = 69.259.
    expect(trimpLoad(session, HOUR_MS, limits)).toBeCloseTo(69.2592, 3);
  });

  it("counts nothing below rest and caps at the maximum", () => {
    expect(
      trimpLoad(
        samples(3600, 1, () => 45),
        HOUR_MS,
        limits,
      ),
    ).toBe(0);
    expect(
      trimpLoad(
        samples(3600, 1, () => 250),
        HOUR_MS,
        limits,
      ),
    ).toBeCloseTo(153.4856, 3);
  });

  it("does not count the time between samples that are far apart, only the hold", () => {
    // Ten minutes of 1 Hz samples at threshold, then nothing for twenty minutes: the end of the data is
    // where the load stops, whatever the session's end says.
    const session = samples(600, 1, () => 170);
    expect(trimpLoad(session, 1800_000, limits)).toBeCloseTo(100 / 6, 6);
    // Per-minute samples hold a minute each; one a quarter hour apart holds the cap, five minutes.
    expect(
      trimpLoad(
        [
          { tMs: 0, bpm: 170 },
          { tMs: 900_000, bpm: 170 },
        ],
        1_200_000,
        limits,
      ),
    ).toBeCloseTo((100 * (HOLD_CAP_SECONDS + HOLD_CAP_SECONDS)) / 3600, 6);
  });

  it("has no load without a usable threshold or without samples", () => {
    const hour = samples(3600, 1, () => 150);
    expect(trimpLoad(hour, HOUR_MS, { rest: 50, max: 190, threshold: 50 })).toBeNull();
    expect(trimpLoad(hour, HOUR_MS, { rest: 50, max: 190, threshold: 190 })).toBeNull();
    expect(trimpLoad(hour, HOUR_MS, { rest: 60, max: 190, threshold: 40 })).toBeNull();
    expect(trimpLoad([], HOUR_MS, limits)).toBeNull();
  });
});

describe("how long a sample holds", () => {
  it("is until the next one, at most five minutes; the last for the median gap, to the end at most", () => {
    const list: HrSample[] = [
      { tMs: 0, bpm: 100 },
      { tMs: 60_000, bpm: 100 },
      { tMs: 120_000, bpm: 100 },
      { tMs: 1_020_000, bpm: 100 },
    ];
    // Gaps 60, 60 and 900 s: median 60; the last holds 60 s if the session goes on, less if it ends sooner.
    expect(holdSeconds(list, 2_000_000)).toEqual([60, 60, 300, 60]);
    expect(holdSeconds(list, 1_030_000)).toEqual([60, 60, 300, 10]);
    expect(holdSeconds(list.slice(0, 1), 100_000)).toEqual([100]);
    expect(holdSeconds(list.slice(0, 1), 1_000_000)).toEqual([300]);
  });
});

describe("the density of the samples", () => {
  it("says how many, the gaps, the share of the session covered and the heart rate", () => {
    // 100 samples every 5 s over a 500 s session, 100 bpm for the first half and 140 for the rest.
    const list = samples(100, 5, (i) => (i < 50 ? 100 : 140));
    const summary = summarizeSamples(list, 0, 500_000);
    expect(summary).toMatchObject({
      samples: 100,
      medianGapSeconds: 5,
      maxGapSeconds: 5,
      coverage: 1,
      maxHeartRate: 140,
    });
    expect(summary.averageHeartRate).toBeCloseTo(120, 9);
  });

  it("covers less of a session with a gap in it, and weighs the average by time", () => {
    // Samples 0..299 s at 1 Hz, then a 20-minute gap, then 1200..1499 s: 600 of 1500 s, with a 300 s hold
    // after the first block's end (the last sample of a block holds the median gap, 1 s).
    const list = [...samples(300, 1, () => 100), ...samples(300, 1, () => 140, 1_200_000)];
    const summary = summarizeSamples(list, 0, 1_500_000);
    expect(summary.maxGapSeconds).toBe(901);
    expect(summary.medianGapSeconds).toBe(1);
    // The sample before the gap holds the cap (300 s); every other holds a second: 299 + 300 + 300 = 899.
    expect(summary.coverage).toBeCloseTo(899 / 1500, 9);
    expect(summary.coverage).toBeLessThan(0.8);
  });

  it("a session with per-minute samples is covered, with coarse figures", () => {
    const list = samples(30, 60, () => 130);
    const summary = summarizeSamples(list, 0, 1_800_000);
    expect(summary.medianGapSeconds).toBe(60);
    expect(summary.coverage).toBe(1);
  });

  it("is empty without samples", () => {
    expect(summarizeSamples([], 0, 60_000)).toEqual({
      samples: 0,
      medianGapSeconds: null,
      maxGapSeconds: null,
      coverage: 0,
      averageHeartRate: null,
      maxHeartRate: null,
    });
  });

  it("has no gap statistics from one sample", () => {
    const summary = summarizeSamples([{ tMs: 10_000, bpm: 120 }], 0, 60_000);
    expect(summary).toMatchObject({
      samples: 1,
      medianGapSeconds: null,
      maxGapSeconds: null,
      maxHeartRate: 120,
    });
    expect(summary.averageHeartRate).toBe(120);
  });
});
