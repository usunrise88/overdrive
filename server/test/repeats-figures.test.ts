import type { RepeatTrend } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { changeOf, comparedSteps, overallHeartRate, trendOf } from "../src/repeats/compare.ts";
import {
  lapBoundaries,
  rideRepeatStats,
  type StepStat,
  stepStats,
} from "../src/repeats/figures.ts";
import { flattenWorkout } from "../src/ride-stats.ts";
import { attempt, overall, SESSION } from "./helpers/repeats-data.ts";

const flat = flattenWorkout(SESSION);
const stats = (spec: Parameters<typeof attempt>[0]): StepStat[] => stepStats(attempt(spec), flat);
const START = "2026-11-10T17:00:00Z";

describe("heart rate at the same power of a step", () => {
  it("is the mean heart rate over the seconds at the target power, after the first 60 s of the step", () => {
    // Steps 2, 3 and 4 at 150, 120 and 156 bpm, with 15 bpm more in the first 60 s of each step (heart rate settling).
    const result = stats({ id: "a", start: START });
    expect(result).toEqual([
      {
        step: 2,
        targetWatts: 220,
        seconds: 540,
        averagePower: 220,
        averageHeartRate: 150,
        reason: null,
      },
      {
        step: 3,
        targetWatts: 125,
        seconds: 240,
        averagePower: 125,
        averageHeartRate: 120,
        reason: null,
      },
      {
        step: 4,
        targetWatts: 220,
        seconds: 540,
        averagePower: 220,
        averageHeartRate: 156,
        reason: null,
      },
    ]);
  });

  it("has no figure for a ramp or a free step: only steady steps have a fixed power", () => {
    expect(stats({ id: "a", start: START }).map((s) => s.step)).toEqual([2, 3, 4]);
  });

  it("counts a second when the power is within 3% of the target, and not outside it", () => {
    // The target is 220 W: 3% is 6.6 W. 226 W (+6) counts and 227 W (+7) does not; 214 W (-6) counts and 213 W (-7) does not.
    const result = stats({
      id: "a",
      start: START,
      // Seconds 361 to 900 are step 2's counted ones: 100 s at 226 W, 100 s at 227 W, 100 s at 214 W, 100 s at 213 W, the rest on target.
      power: (second, planned) => {
        if (second >= 361 && second < 461) return 226;
        if (second >= 461 && second < 561) return 227;
        if (second >= 561 && second < 661) return 214;
        if (second >= 661 && second < 761) return 213;
        return planned;
      },
      // A heart rate of 190 in the seconds that must not count, 150 elsewhere.
      heartRate: (second, planned) => {
        if (planned === null) return 110;
        if (second >= 461 && second < 561) return 190;
        if (second >= 661 && second < 761) return 190;
        return second < 361 ? 165 : second > 900 ? 156 : 150;
      },
    });
    expect(result[0]).toMatchObject({ step: 2, seconds: 340, averageHeartRate: 150 });
    // 100 s at 226 W, 100 s at 214 W and 140 s at 220 W: (100 * 226 + 100 * 214 + 140 * 220) / 340.
    expect(result[0]?.averagePower).toBeCloseTo((100 * 226 + 100 * 214 + 140 * 220) / 340, 1);
  });

  it("leaves out the seconds of a pause, and the first 60 s after a resume as well", () => {
    // Step 2 is seconds 301 to 900. Paused for 100 s from 501 to 600: counted are 361 to 500 (140 s) and, after the resume, the
    // seconds 661 to 900 (240 s): 380 in all. The heart rate in the 60 s after the resume (601 to 660) is 190 and must not count.
    const result = stats({
      id: "a",
      start: START,
      paused: (second) => second >= 501 && second <= 600,
      heartRate: (second, planned) => {
        if (planned === null) return 110;
        if (second >= 601 && second <= 660) return 190;
        if (second >= 301 && second <= 360) return 165;
        return second <= 900 ? 150 : 156;
      },
    });
    expect(result[0]).toMatchObject({ step: 2, seconds: 380, averageHeartRate: 150 });
  });

  it("needs 120 counted seconds, and says what it found otherwise", () => {
    // Step 3 (flat step 2) is seconds 901 to 1200: the first 60 are left out and 240 are counted. With the power off the target for
    // the first `off` of the counted ones, `240 - off` are on target.
    const withOff = (off: number) =>
      stats({
        id: "a",
        start: START,
        power: (second, planned) => (second >= 961 && second < 961 + off ? 140 : planned),
      })[1];
    // 120 on target is enough ...
    expect(withOff(120)).toMatchObject({
      step: 3,
      seconds: 120,
      averageHeartRate: 120,
      reason: null,
    });
    // ... and 119 is not.
    expect(withOff(121)).toEqual({
      step: 3,
      targetWatts: 125,
      // The seconds that were counted: below the 120 that make a figure.
      seconds: 119,
      averagePower: null,
      averageHeartRate: null,
      reason:
        "only 119 s were within 3% of the target power after the first 60 s; 120 s are needed",
    });
    // On target for all 240 counted seconds but the heart rate missing in 121 of them: 119 have both.
    const noHr = stats({
      id: "a",
      start: START,
      heartRate: (second, planned) =>
        planned === null
          ? 110
          : planned === 125 && second >= 961 && second < 961 + 121
            ? null
            : 120,
    })[1];
    expect(noHr).toMatchObject({ step: 3, seconds: 119, averageHeartRate: null });
    expect(noHr?.reason).toBe("no heart rate was recorded at the target power");
  });

  it("says when no heart rate was recorded at all", () => {
    const result = stats({ id: "a", start: START, heartRate: () => null });
    for (const step of result) {
      expect(step).toMatchObject({
        seconds: 0,
        averageHeartRate: null,
        reason: "no heart rate was recorded at the target power",
      });
    }
  });

  it("says a step that was never ridden was not ridden, and one ridden for under a minute was too short", () => {
    // The ride stops at second 330: step 2 has 30 s, steps 3 and 4 have none.
    const result = stats({ id: "a", start: START, seconds: 330 });
    expect(result.map((s) => s.reason)).toEqual([
      "less than 60 s of the step was ridden without a pause (the first 60 s are left out)",
      "the step was not ridden",
      "the step was not ridden",
    ]);
  });

  it("uses the FTP the ride was recorded with for the target", () => {
    const result = stats({ id: "a", start: START, ftp: 262 });
    expect(result.map((s) => s.targetWatts)).toEqual([230.6, 131, 230.6]);
    expect(result.every((s) => s.averageHeartRate !== null)).toBe(true);
  });
});

describe("what is kept of a ride", () => {
  it("has the figures, the compliance and the step boundaries as ridden", () => {
    const record = attempt({ id: "a", start: START });
    const kept = rideRepeatStats(record, SESSION, flat);
    expect(kept.ftp).toBe(250);
    expect(kept.stepsPlanned).toBe(5);
    expect(kept.stepsCompleted).toBe(5);
    expect(kept.plannedSeconds).toBe(1920);
    expect(kept.actualSeconds).toBe(1920);
    expect(kept.laps).toEqual([
      { step: 1, startSeconds: 0, endSeconds: 300 },
      { step: 2, startSeconds: 300, endSeconds: 900 },
      { step: 3, startSeconds: 900, endSeconds: 1200 },
      { step: 4, startSeconds: 1200, endSeconds: 1800 },
      { step: 5, startSeconds: 1800, endSeconds: 1920 },
    ]);
    expect(lapBoundaries(record)).toEqual(kept.laps);
  });

  it("counts a ride that stopped early as that many steps completed and that share of the time", () => {
    const record = attempt({ id: "a", start: START, seconds: 960 });
    const kept = rideRepeatStats(record, SESSION, flat);
    expect(kept.stepsCompleted).toBe(2);
    expect(kept.actualSeconds).toBe(960);
  });

  it("copes with a ride that recorded no workout step", () => {
    const record = attempt({ id: "a", start: START });
    for (const sample of record.samples) delete sample.stepIndex;
    const kept = rideRepeatStats(record, SESSION, flat);
    expect(kept.stepsCompleted).toBe(0);
    expect(kept.actualSeconds).toBe(0);
    expect(kept.powerDeltaPercent).toBeNull();
    expect(kept.steps.every((s) => s.reason === "the step was not ridden")).toBe(true);
  });
});

describe("the overall heart rate at the same power", () => {
  const weights = [
    { step: 2, durationSeconds: 600 },
    { step: 3, durationSeconds: 300 },
    { step: 4, durationSeconds: 600 },
  ];
  const rows = (hrs: (number | null)[], target = [220, 125, 220]): StepStat[] =>
    hrs.map((hr, i) => ({
      step: i + 2,
      targetWatts: target[i] as number,
      seconds: hr === null ? 0 : 500,
      averagePower: hr === null ? null : (target[i] as number),
      averageHeartRate: hr,
      reason: hr === null ? "no heart rate was recorded at the target power" : null,
    }));

  it("weights the steps by their planned length", () => {
    const mine = { steps: rows([150, 120, 156]) };
    const { steps, reference } = comparedSteps([mine]);
    expect(steps).toEqual([2, 3, 4]);
    expect(overallHeartRate(mine, steps, reference, weights, { noFixedPowerStep: false })).toEqual({
      value: overall([150, 120, 156]),
      seconds: 1500,
      reason: null,
    });
    expect(overall([150, 120, 156])).toBe(146.4);
  });

  it("takes the steps in which the latest attempt with any figure has one, and gives an attempt without one of them none", () => {
    const older = { steps: rows([150, null, 156]) };
    const latest = { steps: rows([148, 119, 154]) };
    const { steps, reference } = comparedSteps([older, latest]);
    expect(steps).toEqual([2, 3, 4]);
    const olderOverall = overallHeartRate(older, steps, reference, weights, {
      noFixedPowerStep: false,
    });
    expect(olderOverall.value).toBeNull();
    expect(olderOverall.reason).toBe("step 3: no heart rate was recorded at the target power");
    // The latest has no figure at all: the comparison is taken over what the older one has.
    const empty = { steps: rows([null, null, null]) };
    const again = comparedSteps([older, empty]);
    expect(again.steps).toEqual([2, 4]);
    expect(
      overallHeartRate(older, again.steps, again.reference, weights, { noFixedPowerStep: false })
        .value,
    ).toBe(Math.round(((600 * 150 + 600 * 156) / 1200) * 10) / 10);
  });

  it("has no figure for an attempt whose target power differs by more than 3% (the FTP changed)", () => {
    const latest = { steps: rows([148, 119, 154]) };
    const harder = { steps: rows([149, 120, 155], [230.6, 131, 230.6]) };
    const { steps, reference } = comparedSteps([harder, latest]);
    const result = overallHeartRate(harder, steps, reference, weights, { noFixedPowerStep: false });
    expect(result.value).toBeNull();
    expect(result.reason).toMatch(/the target power in step 2 was 230\.6 W against 220 W/);
    expect(result.reason).toMatch(/the FTP changed/);
    // Within 3%: 226 W against 220 W (2.7%) is the same power.
    const near = { steps: rows([149, 120, 155], [226, 128, 226]) };
    expect(
      overallHeartRate(near, steps, reference, weights, { noFixedPowerStep: false }).value,
    ).not.toBeNull();
  });

  it("says so when the session has no fixed-power step", () => {
    const none = { steps: [] as StepStat[] };
    const { steps, reference } = comparedSteps([none]);
    expect(overallHeartRate(none, steps, reference, [], { noFixedPowerStep: true }).reason).toBe(
      "the session has no fixed-power step",
    );
  });

  it("makes a change of two figures, or says which one is missing", () => {
    expect(changeOf(142.3, 144.9, { latest: null, previous: null })).toEqual({
      latest: 142.3,
      previous: 144.9,
      difference: -2.6,
      reason: null,
    });
    expect(changeOf(null, 144.9, { latest: "no heart rate", previous: null })).toEqual({
      latest: null,
      previous: 144.9,
      difference: null,
      reason: "the latest attempt has none: no heart rate",
    });
    expect(changeOf(142.3, null, { latest: null, previous: "step 3 was not ridden" }).reason).toBe(
      "the previous attempt has none: step 3 was not ridden",
    );
  });
});

describe("the trend of a measure over the attempts", () => {
  const lower = { unit: "bpm", betterWhen: "lower", steadyBelow: 2 } as const;
  const higher = { unit: "%", betterWhen: "higher", steadyBelow: 5 } as const;
  const summary = (t: RepeatTrend) => [t.direction, t.slopePerAttempt, t.change];

  it.each([
    // [values, spec, direction, slope, change]
    ["falling heart rate, lower is better", [150, 149, 146, 145], lower, "improving", -1.8, -5.4],
    ["rising heart rate", [145, 146, 149, 150], lower, "worsening", 1.8, 5.4],
    ["a flat line with noise", [150, 150.4, 149.8, 150.1], lower, "steady", -0.03, -0.09],
    ["three attempts falling", [150, 148, 146], lower, "improving", -2, -4],
    ["a change of exactly the limit is not steady", [100, 101, 102], lower, "worsening", 1, 2],
    ["a change just under the limit is steady", [100, 100.5, 101], lower, "steady", 0.5, 1],
    ["compliance rising, higher is better", [80, 90, 100], higher, "improving", 10, 20],
    ["compliance falling", [100, 90, 80], higher, "worsening", -10, -20],
    ["compliance moving a little", [96, 97, 98, 97], higher, "steady", 0.4, 1.2],
  ] as const)("%s", (_name, values, spec, direction, slope, change) => {
    const trend = trendOf(values, spec);
    expect(trend.direction).toBe(direction);
    expect(trend.slopePerAttempt).toBeCloseTo(slope, 2);
    expect(trend.change).toBeCloseTo(change, 2);
    expect(trend.attempts).toBe(values.length);
    expect(trend.minAttempts).toBe(3);
    expect(trend.reason).toBeNull();
  });

  it("says unknown below three attempts, with the minimum stated, whatever the two figures say", () => {
    expect(summary(trendOf([], lower))).toEqual(["unknown", null, null]);
    expect(summary(trendOf([150], lower))).toEqual(["unknown", null, null]);
    expect(summary(trendOf([150, 100], lower))).toEqual(["unknown", null, null]);
    expect(trendOf([150, 100], lower).reason).toBe(
      "a trend needs at least 3 attempts with a figure; there are 2",
    );
    expect(trendOf([150], lower).reason).toBe(
      "a trend needs at least 3 attempts with a figure; there is 1",
    );
    expect(trendOf([], lower).minAttempts).toBe(3);
  });

  it("is the least-squares line against the attempt's position: the golden numbers", () => {
    // x = 0, 1, 2, 3 and y = 146.4, 144.9, 143.8, 142.3: mean x 1.5, mean y 144.35, covariance -6.7, variance 5 -> slope -1.34.
    const trend = trendOf([146.4, 144.9, 143.8, 142.3], lower);
    expect(trend.slopePerAttempt).toBe(-1.34);
    expect(trend.change).toBe(-4.02);
    expect(trend.direction).toBe("improving");
  });
});
