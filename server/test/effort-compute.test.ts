import { validateEffortResponse } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildEffort,
  EFFORT_NOTE,
  type EffortInput,
  firstMondayNeeded,
} from "../src/effort/compute.ts";

let counter = 0;
/** An activity of a given day: `at` orders those of one day. */
function act(
  date: string,
  load: number,
  extra: Partial<EffortInput> & { at?: string } = {},
): EffortInput {
  counter++;
  const { at, ...rest } = extra;
  return {
    id: `act-${counter}`,
    kind: "ride",
    name: "Free ride",
    date,
    startEpochMillis: Date.parse(`${date}T${at ?? "17:00:00"}Z`),
    load,
    approximate: false,
    durationSeconds: 3600,
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

/** Eight completed weeks (Mondays 2026-09-21 to 2026-11-09): totals 120, 150, 90, 200, 160, 0, 140, 110, by Wednesday 90, 60, 30, 120, 80, 0, 70, 60. */
const HISTORY: EffortInput[] = [
  act("2026-09-21", 90),
  act("2026-09-26", 30),
  act("2026-09-29", 60),
  act("2026-10-03", 90),
  act("2026-10-05", 30),
  act("2026-10-09", 60),
  act("2026-10-12", 50),
  act("2026-10-14", 70),
  act("2026-10-15", 40),
  act("2026-10-17", 40),
  act("2026-10-20", 80),
  act("2026-10-23", 80),
  // 2026-10-26 to 2026-11-01: a week off.
  act("2026-11-02", 70),
  act("2026-11-05", 70),
  act("2026-11-11", 60),
  act("2026-11-12", 50),
];

describe("the weeks the answer needs", () => {
  it("reach back eight completed weeks even when fewer are shown", () => {
    expect(firstMondayNeeded("2026-11-18", 1)).toBe("2026-09-21");
    expect(firstMondayNeeded("2026-11-18", 9)).toBe("2026-09-21");
    expect(firstMondayNeeded("2026-11-18", 12)).toBe("2026-08-31");
    expect(firstMondayNeeded("2026-11-16", 52)).toBe("2025-11-24");
    // A Sunday belongs to the week that began six days before.
    expect(firstMondayNeeded("2026-11-22", 1)).toBe("2026-09-21");
  });
});

describe("the weeks, by hand", () => {
  const now = [...HISTORY, act("2026-11-17", 58.5), act("2026-11-18", 63.2)];

  it("sums each day, each week and each load, with the week in progress up to today", () => {
    const response = run(now, "2026-11-18", 3);
    expect(response.weeks.map((w) => [w.weekStart, w.load, w.activityCount, w.inProgress])).toEqual(
      [
        ["2026-11-02", 140, 2, false],
        ["2026-11-09", 110, 2, false],
        ["2026-11-16", 121.7, 2, true],
      ],
    );
    const [first, , current] = response.weeks;
    expect(first?.days.map((d) => [d.date, d.load])).toEqual([
      ["2026-11-02", 70],
      ["2026-11-03", 0],
      ["2026-11-04", 0],
      ["2026-11-05", 70],
      ["2026-11-06", 0],
      ["2026-11-07", 0],
      ["2026-11-08", 0],
    ]);
    // Monday to Wednesday only: the days after today do not exist yet.
    expect(current?.days.map((d) => [d.date, d.load])).toEqual([
      ["2026-11-16", 0],
      ["2026-11-17", 58.5],
      ["2026-11-18", 63.2],
    ]);
    expect(current?.durationSeconds).toBe(7200);
  });

  it("gives the usual range of the eight weeks and a verdict against what they had reached by Wednesday", () => {
    const response = run(now, "2026-11-18", 12);
    // Sorted 0, 90, 110, 120, 140, 150, 160, 200: the quartiles are 105 and 152.5.
    expect(response.usual).toEqual({ low: 105, median: 130, high: 152.5, basedOnWeeks: 8 });
    expect(response.thisWeek).toEqual({
      load: 121.7,
      usualSoFar: { low: 52.5, high: 82.5 },
      verdict: "above",
      verdictText:
        "Above your usual for this point of the week: 121.7 so far, usually 52.5 to 82.5 by Wednesday.",
    });
    expect(response.weeks).toHaveLength(12);
    expect(response.weeks[0]?.weekStart).toBe("2026-08-31");
    expect(response.weeks[0]?.load).toBe(0);
    expect(response.note).toBe(EFFORT_NOTE);
  });

  it("answers the same usual range and verdict however many weeks are shown", () => {
    const one = run(now, "2026-11-18", 1);
    const twelve = run(now, "2026-11-18", 12);
    expect(one.weeks).toHaveLength(1);
    expect(one.usual).toEqual(twelve.usual);
    expect(one.thisWeek).toEqual(twelve.thisWeek);
  });

  it("calls a week below, within or above by the load so far", () => {
    const quiet = run([...HISTORY, act("2026-11-17", 40)], "2026-11-18", 2);
    expect(quiet.thisWeek).toMatchObject({ verdict: "below", load: 40 });
    const usual = run([...HISTORY, act("2026-11-17", 40), act("2026-11-18", 20)], "2026-11-18", 2);
    expect(usual.thisWeek).toMatchObject({ verdict: "within", load: 60 });
  });

  it("does not compare on Monday or Tuesday", () => {
    const monday = run([...HISTORY, act("2026-11-16", 45)], "2026-11-16", 2);
    expect(monday.weeks[1]?.days).toHaveLength(1);
    expect(monday.thisWeek).toEqual({
      load: 45,
      usualSoFar: null,
      verdict: "unknown",
      verdictText:
        "Too early in the week to compare: 45 so far. A comparison with your usual starts on Wednesday.",
    });
    expect(monday.usual).not.toBeNull();
    expect(run(HISTORY, "2026-11-17", 2).thisWeek.verdict).toBe("unknown");
  });

  it("compares a Sunday against the whole usual weeks, with all seven days shown", () => {
    const sunday = run([...HISTORY, act("2026-11-16", 80), act("2026-11-22", 50)], "2026-11-22", 2);
    expect(sunday.weeks[1]?.days).toHaveLength(7);
    expect(sunday.weeks[1]?.inProgress).toBe(true);
    expect(sunday.thisWeek).toMatchObject({
      load: 130,
      verdict: "within",
      usualSoFar: { low: 105, high: 152.5 },
    });
  });

  it("has no usual range with fewer than four weeks of history, and says how many there are", () => {
    const young = [
      act("2026-10-27", 60),
      act("2026-11-04", 70),
      act("2026-11-10", 80),
      act("2026-11-18", 50),
    ];
    const response = run(young, "2026-11-18", 4);
    expect(response.usual).toBeNull();
    expect(response.thisWeek).toEqual({
      load: 50,
      usualSoFar: null,
      verdict: "unknown",
      verdictText:
        "Not enough weeks yet to say what is usual: it needs 4 completed weeks of training, and there are 3 so far.",
    });
  });

  it("starts counting at the first week with an activity: the weeks before the rider began are not zeros", () => {
    const four = [
      act("2026-10-20", 60),
      act("2026-10-27", 70),
      act("2026-11-04", 80),
      act("2026-11-10", 50),
    ];
    const response = run(four, "2026-11-18", 2);
    // Eight weeks back there are four empty ones before 2026-10-19: they are left out, the four after count.
    expect(response.usual).toEqual({ low: 57.5, median: 65, high: 72.5, basedOnWeeks: 4 });
  });

  it("flags a week approximate when any activity in it is, and keeps the kinds and names", () => {
    const watch = act("2026-11-17", 30.2, {
      kind: "watch",
      name: "Synthetic VR session",
      approximate: true,
      durationSeconds: 2400,
    });
    const outdoor = act("2026-11-17", 82.4, {
      kind: "outdoor",
      name: "Outdoor ride",
      durationSeconds: 5400,
      at: "07:00:00",
    });
    const response = run([...HISTORY, watch, outdoor, act("2026-11-18", 10)], "2026-11-18", 2);
    const tuesday = response.weeks[1]?.days[1];
    expect(response.weeks[1]?.approximate).toBe(true);
    expect(response.weeks[0]?.approximate).toBe(false);
    // The outdoor ride started earlier in the day than the VR session (07:00 against 17:00).
    expect(tuesday?.activities.map((a) => [a.kind, a.name, a.load, a.approximate])).toEqual([
      ["outdoor", "Outdoor ride", 82.4, false],
      ["watch", "Synthetic VR session", 30.2, true],
    ]);
    expect(tuesday?.load).toBe(112.6);
    expect(response.weeks[1]?.durationSeconds).toBe(2400 + 5400 + 3600);
  });

  it("ignores what is outside the weeks it needs: older than the eighth week back and after today", () => {
    const response = run(
      [...HISTORY, act("2026-09-14", 500), act("2026-11-19", 500)],
      "2026-11-18",
      2,
    );
    expect(response.usual?.basedOnWeeks).toBe(8);
    expect(response.weeks[1]?.load).toBe(0);
  });

  it("answers an empty history with empty weeks", () => {
    const response = run([], "2026-11-18", 2);
    expect(response.usual).toBeNull();
    expect(response.weeks.every((w) => w.load === 0 && w.activityCount === 0)).toBe(true);
    expect(response.thisWeek.verdict).toBe("unknown");
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
    const current = response.weeks[1];
    expect(current?.days.map((d) => d.date)).toEqual([
      "2026-12-28",
      "2026-12-29",
      "2026-12-30",
      "2026-12-31",
      "2027-01-01",
      "2027-01-02",
    ]);
    expect(current?.load).toBe(126.5);
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
