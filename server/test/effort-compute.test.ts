import { validateEffortResponse } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildEffort,
  type EffortInput,
  firstMondayNeeded,
  usualRange,
  verdictOf,
} from "../src/effort/compute.ts";

let counter = 0;
/** An activity of a given day: `at` orders those of one day. */
function act(
  date: string,
  load: number | null,
  extra: Partial<EffortInput> & { at?: string } = {},
): EffortInput {
  counter++;
  const { at, ...rest } = extra;
  return {
    id: `act-${counter}`,
    kind: "ride",
    sport: null,
    name: "Free ride",
    date,
    startEpochMillis: Date.parse(`${date}T${at ?? "17:00:00"}Z`),
    load,
    approximate: false,
    ...rest,
  };
}

function run(activities: EffortInput[], today: string, weeks: number) {
  const response = buildEffort({
    activities,
    today,
    weeks,
    generatedAt: `${today}T08:00:00.000Z`,
  });
  const checked = validateEffortResponse(response);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return response;
}

const addDays = (date: string, days: number): string =>
  new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);

/** One ride on the Wednesday of each week from `first` (a Monday): a week of 0 has none. */
const wednesdays = (first: string, loads: number[]): EffortInput[] =>
  loads.flatMap((load, i) => (load === 0 ? [] : [act(addDays(first, 7 * i + 2), load)]));

/**
 * Ten completed weeks from Monday 2026-09-07, a ride each Wednesday: 100, 150, 90, 200, 160, 0 (a week off), 140, 110, 130, 120.
 * The current week begins on 2026-11-16.
 */
const L1 = [100, 150, 90, 200, 160, 0, 140, 110, 130, 120];
const HISTORY = wednesdays("2026-09-07", L1);
const NOW = [act("2026-11-17", 58.5), act("2026-11-18", 63.2)];

describe("the weeks the answer needs", () => {
  it("reach back eight complete weeks before the oldest week shown", () => {
    expect(firstMondayNeeded("2026-11-18", 1)).toBe("2026-09-21");
    expect(firstMondayNeeded("2026-11-18", 12)).toBe("2026-07-06");
    expect(firstMondayNeeded("2026-11-16", 52)).toBe("2025-09-29");
    // A Sunday belongs to the week that began six days before.
    expect(firstMondayNeeded("2026-11-22", 1)).toBe("2026-09-21");
  });
});

describe("the usual range", () => {
  it("is the mean minus and plus one sample standard deviation, to one decimal", () => {
    // 90, 200, 160, 0, 140, 110, 130, 120: mean 118.75, the sum of squares 23887.5 over 7 is 3412.5, its root 58.4166.
    expect(usualRange([90, 200, 160, 0, 140, 110, 130, 120])).toEqual({ low: 60.3, high: 177.2 });
    expect(usualRange([100, 100, 100, 100, 100, 100, 100, 100])).toEqual({ low: 100, high: 100 });
  });

  it("holds the low edge at 0: a load is never negative", () => {
    // 0 seven times and 300: mean 37.5, spread 106.07.
    expect(usualRange([0, 0, 0, 0, 0, 0, 0, 300])).toEqual({ low: 0, high: 143.6 });
  });
});

describe("the verdict", () => {
  const range = { low: 60.3, high: 177.2 };

  it("is below under the low edge, above over the high edge and within between them, the edges included", () => {
    expect(verdictOf(60.2, range, false)).toBe("below");
    expect(verdictOf(60.3, range, false)).toBe("within");
    expect(verdictOf(120, range, false)).toBe("within");
    expect(verdictOf(177.2, range, false)).toBe("within");
    expect(verdictOf(177.3, range, false)).toBe("above");
  });

  it("is learning without a range, and none for a week in which nothing has been recorded", () => {
    expect(verdictOf(0, null, false)).toBe("learning");
    expect(verdictOf(0, null, true)).toBe("learning");
    expect(verdictOf(0, range, true)).toBe("none");
  });
});

describe("the weeks, by hand", () => {
  const now = [...HISTORY, ...NOW];

  it("sums each day, each week and each load, with the days of the week in progress after today empty", () => {
    const response = run(now, "2026-11-18", 3);
    expect(
      response.weeks.map((w) => [w.weekStart, w.load, w.current, w.activities.length]),
    ).toEqual([
      ["2026-11-02", 130, false, 1],
      ["2026-11-09", 120, false, 1],
      ["2026-11-16", 121.7, true, 2],
    ]);
    const current = response.weeks[2];
    expect(current?.days.map((d) => [d.date, d.load])).toEqual([
      ["2026-11-16", 0],
      ["2026-11-17", 58.5],
      ["2026-11-18", 63.2],
      ["2026-11-19", 0],
      ["2026-11-20", 0],
      ["2026-11-21", 0],
      ["2026-11-22", 0],
    ]);
    // 58.5 and 63.2 of 121.7: 48.07% and 51.93%.
    expect(current?.activities.map((a) => [a.date, a.load, a.share])).toEqual([
      ["2026-11-17", 58.5, 0.481],
      ["2026-11-18", 63.2, 0.519],
    ]);
  });

  it("gives each week the usual range of the eight weeks before it, and learns until there are eight", () => {
    const response = run(now, "2026-11-18", 12);
    const byStart = Object.fromEntries(response.weeks.map((w) => [w.weekStart, w]));
    // The first week with a ride is 2026-09-07: eight complete weeks before 2026-11-02, the 10 weeks before it are not enough.
    expect(byStart["2026-10-26"]).toMatchObject({
      weeksLogged: 7,
      usualLow: null,
      usualHigh: null,
      verdict: "learning",
    });
    expect(byStart["2026-11-02"]).toMatchObject({
      weeksLogged: 8,
      usualLow: 58.9,
      usualHigh: 178.6,
      verdict: "within",
      load: 130,
    });
    expect(byStart["2026-11-09"]).toMatchObject({
      weeksLogged: 8,
      usualLow: 63,
      usualHigh: 182,
      verdict: "within",
      load: 120,
    });
    expect(byStart["2026-11-16"]).toMatchObject({
      weeksLogged: 8,
      usualLow: 60.3,
      usualHigh: 177.2,
      verdict: "within",
      load: 121.7,
      current: true,
    });
    expect(response.weeksNeeded).toBe(8);
    expect(response.weeksLogged).toBe(8);
    // Twelve weeks from 2026-08-31, the first of them before the rider began.
    expect(response.weeks).toHaveLength(12);
    expect(response.weeks[0]).toMatchObject({
      weekStart: "2026-08-31",
      load: 0,
      weeksLogged: 0,
      verdict: "learning",
    });
    expect(response.weeks[1]).toMatchObject({ weekStart: "2026-09-07", load: 100, weeksLogged: 0 });
  });

  it("answers the same range and verdict for the current week however many weeks are shown", () => {
    const one = run(now, "2026-11-18", 1);
    const many = run(now, "2026-11-18", 12);
    expect(one.weeks).toHaveLength(1);
    expect(one.weeks[0]).toEqual(many.weeks[11]);
    expect(one.weeksLogged).toBe(many.weeksLogged);
  });

  it("compares the load so far with the whole usual range, never scaled to the weekday", () => {
    // Below 60.3 on a Wednesday: below, which the app says "so far".
    expect(run([...HISTORY, act("2026-11-18", 40)], "2026-11-18", 1).weeks[0]?.verdict).toBe(
      "below",
    );
    // On the high edge: within; one tenth over: above.
    expect(run([...HISTORY, act("2026-11-18", 177.2)], "2026-11-18", 1).weeks[0]?.verdict).toBe(
      "within",
    );
    expect(run([...HISTORY, act("2026-11-18", 177.3)], "2026-11-18", 1).weeks[0]?.verdict).toBe(
      "above",
    );
    // Monday morning with 40 against 60.3 to 177.2: the same plain comparison.
    expect(run([...HISTORY, act("2026-11-16", 40)], "2026-11-16", 1).weeks[0]).toMatchObject({
      verdict: "below",
      days: [
        { date: "2026-11-16", load: 40 },
        { load: 0 },
        { load: 0 },
        { load: 0 },
        { load: 0 },
        { load: 0 },
        { load: 0 },
      ],
    });
  });

  it("says none when nothing at all is recorded in the current week yet, and learning when there is no range", () => {
    expect(run(HISTORY, "2026-11-16", 1).weeks[0]).toMatchObject({
      verdict: "none",
      load: 0,
      activities: [],
    });
    expect(run(HISTORY.slice(0, 5), "2026-10-14", 1).weeks[0]).toMatchObject({
      verdict: "learning",
      weeksLogged: 5,
      usualLow: null,
    });
    expect(run([], "2026-11-16", 2).weeks.map((w) => [w.verdict, w.weeksLogged])).toEqual([
      ["learning", 0],
      ["learning", 0],
    ]);
  });
});

describe("a longer history", () => {
  // Twelve weeks from 2026-08-24 (a ride each Wednesday): 100 eight times, then a week off, a week of 300 and two of 100.
  const L2 = [100, 100, 100, 100, 100, 100, 100, 100, 0, 300, 100, 100];
  const history = wednesdays("2026-08-24", L2);

  it("calls a week off below, a very big week above and a usual one within, each against its own range", () => {
    const response = run(history, "2026-11-16", 6);
    const byStart = Object.fromEntries(response.weeks.map((w) => [w.weekStart, w]));
    // 2026-10-19 follows eight weeks of exactly 100: the range is 100 to 100 and the week off is below it.
    expect(byStart["2026-10-19"]).toMatchObject({
      usualLow: 100,
      usualHigh: 100,
      load: 0,
      verdict: "below",
    });
    // 2026-10-26: 100 seven times and 0, mean 87.5, spread 35.36: 52.1 to 122.9; 300 is above it.
    expect(byStart["2026-10-26"]).toMatchObject({
      usualLow: 52.1,
      usualHigh: 122.9,
      load: 300,
      verdict: "above",
    });
    // 2026-11-02: 100 six times, 0 and 300: mean 112.5, spread 83.45: 29.0 to 196.0; 100 is within.
    expect(byStart["2026-11-02"]).toMatchObject({
      usualLow: 29,
      usualHigh: 196,
      load: 100,
      verdict: "within",
    });
    expect(byStart["2026-11-16"]).toMatchObject({
      usualLow: 29,
      usualHigh: 196,
      verdict: "none",
      current: true,
    });
  });
});

describe("activities", () => {
  it("keeps an activity with no load out of every sum and share, and an approximate load flags its week", () => {
    const watch = act("2026-11-17", 30, {
      kind: "activity",
      sport: "other_workout",
      name: "Synthetic VR session",
      approximate: true,
      at: "18:00:00",
    });
    const lifting = act("2026-11-17", null, {
      kind: "activity",
      sport: "strength_training",
      name: "Strength training",
      at: "19:00:00",
    });
    const ride = act("2026-11-17", 70, { at: "07:00:00" });
    const response = run([...HISTORY, watch, lifting, ride], "2026-11-18", 2);
    const current = response.weeks[1];
    expect(current?.approximate).toBe(true);
    expect(response.weeks[0]?.approximate).toBe(false);
    // Oldest first within the day by start time: the ride at 07:00, the watch at 18:00, the lifting at 19:00.
    expect(
      current?.activities.map((a) => [a.name, a.kind, a.sport, a.load, a.approximate, a.share]),
    ).toEqual([
      ["Free ride", "ride", null, 70, false, 0.7],
      ["Synthetic VR session", "activity", "other_workout", 30, true, 0.3],
      ["Strength training", "activity", "strength_training", null, false, null],
    ]);
    expect(current?.days[1]?.load).toBe(100);
    expect(current?.load).toBe(100);
  });

  it("ignores what is outside the weeks it needs: older than the weeks and after today", () => {
    const response = run(
      [...HISTORY, act("2026-05-01", 500), act("2026-11-19", 500)],
      "2026-11-18",
      2,
    );
    expect(response.weeks[1]?.load).toBe(0);
    expect(response.weeks[0]?.load).toBe(120);
  });
});

describe("weeks across a month and a year", () => {
  it("runs the days of a week from Monday across the end of a month", () => {
    // Monday 2026-11-30 to Sunday 2026-12-06.
    const response = run(
      [act("2026-11-30", 40), act("2026-12-01", 20), act("2026-12-06", 55)],
      "2026-12-06",
      2,
    );
    const week = response.weeks[1];
    expect(week?.weekStart).toBe("2026-11-30");
    expect(week?.days.map((d) => d.date)).toEqual([
      "2026-11-30",
      "2026-12-01",
      "2026-12-02",
      "2026-12-03",
      "2026-12-04",
      "2026-12-05",
      "2026-12-06",
    ]);
    expect(week?.load).toBe(115);
  });

  it("runs the week of 28 December 2026 into 2027, and the week before it", () => {
    const response = run(
      [
        act("2026-12-24", 33),
        act("2026-12-31", 61),
        act("2027-01-01", 45.5),
        act("2027-01-02", 20),
      ],
      "2027-01-02",
      2,
    );
    expect(response.weeks.map((w) => w.weekStart)).toEqual(["2026-12-21", "2026-12-28"]);
    expect(response.weeks[1]?.days.map((d) => d.date)).toEqual([
      "2026-12-28",
      "2026-12-29",
      "2026-12-30",
      "2026-12-31",
      "2027-01-01",
      "2027-01-02",
      "2027-01-03",
    ]);
    expect(response.weeks[1]?.load).toBe(126.5);
    expect(response.weeks[0]?.load).toBe(33);
  });

  it("shows fifty-two weeks across a leap day without skipping or repeating a Monday", () => {
    const response = run([], "2028-03-15", 52);
    const mondays = response.weeks.map((w) => w.weekStart);
    expect(mondays).toHaveLength(52);
    expect(new Set(mondays).size).toBe(52);
    expect(mondays[51]).toBe("2028-03-13");
    for (const [i, monday] of mondays.entries()) {
      if (i === 0) continue;
      const gap = (Date.parse(monday) - Date.parse(mondays[i - 1] as string)) / 86_400_000;
      expect(gap).toBe(7);
    }
  });
});
