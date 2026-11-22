import { describe, expect, it } from "vitest";
import {
  bandOf,
  type CompletedWeek,
  quantile,
  usualOf,
  verdictFor,
  weeksBehindUsual,
} from "../src/effort/usual.ts";

/** A completed week from its seven daily loads (the number of activities is the days that had load). */
const week = (...dayLoads: number[]): CompletedWeek => ({
  load: Math.round(dayLoads.reduce((a, b) => a + b, 0) * 10) / 10,
  activityCount: dayLoads.filter((d) => d > 0).length,
  dayLoads,
});

/**
 * Eight completed weeks by hand. Totals 120, 150, 90, 200, 160, 0, 140, 110; what each had reached by Wednesday (the first three
 * days): 90, 60, 30, 120, 80, 0, 70, 60.
 */
const EIGHT: CompletedWeek[] = [
  week(40, 0, 50, 0, 30, 0, 0),
  week(0, 60, 0, 50, 0, 40, 0),
  week(30, 0, 0, 0, 60, 0, 0),
  week(50, 0, 70, 40, 0, 40, 0),
  week(0, 80, 0, 0, 80, 0, 0),
  // A week off: it is part of the pattern and counts as the 0 it was (it comes after the first active week).
  { load: 0, activityCount: 0, dayLoads: [0, 0, 0, 0, 0, 0, 0] },
  week(70, 0, 0, 70, 0, 0, 0),
  week(0, 0, 60, 50, 0, 0, 0),
];

describe("quantile", () => {
  it("interpolates linearly between the sorted values", () => {
    expect(quantile([10, 20, 30, 40], 0.25)).toBe(17.5);
    expect(quantile([40, 10, 30, 20], 0.5)).toBe(25);
    expect(quantile([10, 20, 30, 40], 0.75)).toBe(32.5);
    expect(quantile([7], 0.25)).toBe(7);
    expect(quantile([1, 3], 0)).toBe(1);
    expect(quantile([1, 3], 1)).toBe(3);
  });

  it("gives the band of eight weeks as the lower and the upper quartile", () => {
    // Sorted 0, 90, 110, 120, 140, 150, 160, 200: the quartiles sit at 1.75 and 5.25.
    expect(bandOf(EIGHT.map((w) => w.load))).toEqual({ low: 105, high: 152.5 });
  });
});

describe("the weeks the usual range is taken from", () => {
  it("is the last eight completed weeks", () => {
    const older = [week(99, 0, 0, 0, 0, 0, 0), week(98, 0, 0, 0, 0, 0, 0)];
    expect(weeksBehindUsual([...older, ...EIGHT])).toEqual(EIGHT);
  });

  it("starts at the first week that had any activity, so the weeks before the rider started do not count", () => {
    const empty = { load: 0, activityCount: 0, dayLoads: [0, 0, 0, 0, 0, 0, 0] };
    const behind = weeksBehindUsual([empty, empty, ...EIGHT.slice(0, 4)]);
    expect(behind).toEqual(EIGHT.slice(0, 4));
    expect(weeksBehindUsual([empty, empty, empty])).toEqual([]);
    expect(weeksBehindUsual([])).toEqual([]);
  });

  it("keeps a week off that comes after an active one", () => {
    const empty = { load: 0, activityCount: 0, dayLoads: [0, 0, 0, 0, 0, 0, 0] };
    expect(
      weeksBehindUsual([week(50, 0, 0, 0, 0, 0, 0), empty, week(60, 0, 0, 0, 0, 0, 0)]),
    ).toHaveLength(3);
  });
});

describe("the usual range", () => {
  it("is the median and the interquartile band of the weeks, with how many", () => {
    expect(usualOf(EIGHT)).toEqual({ low: 105, median: 130, high: 152.5, basedOnWeeks: 8 });
  });

  it("needs four weeks", () => {
    expect(usualOf(EIGHT.slice(0, 3))).toBeNull();
    expect(usualOf(EIGHT.slice(0, 4))).toEqual({
      // 120, 150, 90, 200 sorted 90, 120, 150, 200: quartiles at 0.75 and 2.25.
      low: 112.5,
      median: 135,
      high: 162.5,
      basedOnWeeks: 4,
    });
  });

  it("is not moved much by one huge week", () => {
    const weeks = [...EIGHT.slice(0, 7), week(2000, 0, 0, 0, 0, 0, 0)];
    const usual = usualOf(weeks);
    // Sorted 0, 90, 120, 140, 150, 160, 200, 2000: the upper quartile is 170, not dragged toward 2000.
    expect(usual?.high).toBe(170);
    expect(usual?.median).toBe(145);
  });
});

describe("this week's verdict", () => {
  // What the eight weeks had reached by Wednesday, sorted 0, 30, 60, 60, 70, 80, 90, 120: the band is 52.5 to 82.5.
  it("is below under the lower edge, within on both edges and between them, above over the upper edge", () => {
    expect(verdictFor(EIGHT, 52.4, 3)).toMatchObject({ verdict: "below" });
    expect(verdictFor(EIGHT, 52.5, 3)).toMatchObject({ verdict: "within" });
    expect(verdictFor(EIGHT, 70, 3)).toMatchObject({ verdict: "within" });
    expect(verdictFor(EIGHT, 82.5, 3)).toMatchObject({ verdict: "within" });
    expect(verdictFor(EIGHT, 82.6, 3)).toMatchObject({ verdict: "above" });
    expect(verdictFor(EIGHT, 70, 3).usualSoFar).toEqual({ low: 52.5, high: 82.5 });
  });

  it("scales the band to the weekday: by Friday the same weeks had reached more", () => {
    // By Friday (five days): 120, 110, 90, 160, 160, 0, 140, 110 -> sorted 0, 90, 110, 110, 120, 140, 160, 160: 105 to 145.
    const friday = verdictFor(EIGHT, 100, 5);
    expect(friday.usualSoFar).toEqual({ low: 105, high: 145 });
    expect(friday.verdict).toBe("below");
    // On Sunday the band is the usual range itself.
    expect(verdictFor(EIGHT, 100, 7).usualSoFar).toEqual({ low: 105, high: 152.5 });
  });

  it("writes the verdict as a sentence with the numbers the service computed", () => {
    expect(verdictFor(EIGHT, 121.7, 3).verdictText).toBe(
      "Above your usual for this point of the week: 121.7 so far, usually 52.5 to 82.5 by Wednesday.",
    );
    expect(verdictFor(EIGHT, 40, 3).verdictText).toBe(
      "Below your usual for this point of the week: 40 so far, usually 52.5 to 82.5 by Wednesday.",
    );
    expect(verdictFor(EIGHT, 60, 3).verdictText).toBe(
      "Within your usual for this point of the week: 60 so far, usually 52.5 to 82.5 by Wednesday.",
    );
  });

  it("is unknown on Monday and Tuesday, with no band", () => {
    for (const weekday of [1, 2]) {
      const answer = verdictFor(EIGHT, 58.5, weekday);
      expect(answer).toEqual({
        verdict: "unknown",
        usualSoFar: null,
        verdictText:
          "Too early in the week to compare: 58.5 so far. A comparison with your usual starts on Wednesday.",
      });
    }
  });

  it("is unknown without four weeks behind it, and says how many there are", () => {
    expect(verdictFor(EIGHT.slice(0, 3), 100, 5)).toEqual({
      verdict: "unknown",
      usualSoFar: null,
      verdictText:
        "Not enough weeks yet to say what is usual: it needs 4 completed weeks of training, and there are 3 so far.",
    });
    expect(verdictFor([], 0, 5).verdictText).toContain("there are 0 so far");
    expect(verdictFor(EIGHT.slice(0, 1), 0, 5).verdictText).toContain("there is 1 so far");
  });
});
