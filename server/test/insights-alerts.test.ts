import type { Reading, WellnessDay } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  type AlertSettings,
  DEFAULT_ALERT_SETTINGS,
  deviationCandidates,
  evaluateDrift,
  type PlannedRow,
} from "../src/insights/alerts.ts";
import type { RideBrief } from "../src/telegram/rides.ts";

/**
 * When an alert is due (spec §6.6, ADR 0025): thresholds as tables. A deviation alert needs a metric on its adverse side for N
 * mornings in a row and is reported once per streak; plan drift needs missed sessions or load far from the plan, over the 7 days
 * before today.
 */

const NONE: Reading = {
  value: null,
  source: null,
  baselineLow: null,
  baselineHigh: null,
  deviation: null,
  streak: 0,
};
const reading = (value: number, deviation: Reading["deviation"], streak: number): Reading => ({
  value,
  source: "healthconnect",
  baselineLow: 50,
  baselineHigh: 54,
  deviation,
  streak,
});

function day(overrides: Partial<WellnessDay> = {}): WellnessDay {
  return {
    date: "2026-11-18",
    restingHr: NONE,
    sleepSeconds: NONE,
    bedtimeMinutes: NONE,
    wakeMinutes: NONE,
    respiratoryRate: NONE,
    spo2: NONE,
    sleepingHr: NONE,
    hrvRmssd: NONE,
    sleepStart: null,
    sleepEnd: null,
    steps: null,
    hrvSdnn: null,
    weightKg: null,
    weightKg7d: null,
    ...overrides,
  };
}

describe("deviation alerts", () => {
  // [metric, side the reading is on, streak, alerts]
  it.each([
    ["restingHr", "above", 3, true],
    ["restingHr", "above", 2, false],
    ["restingHr", "above", 7, true],
    ["restingHr", "below", 5, false],
    ["restingHr", "inside", 0, false],
    ["sleepSeconds", "below", 3, true],
    ["sleepSeconds", "above", 3, false],
    ["bedtimeMinutes", "above", 4, true],
    ["bedtimeMinutes", "below", 4, false],
    ["respiratoryRate", "above", 3, true],
    ["spo2", "below", 3, true],
    ["spo2", "above", 3, false],
    ["sleepingHr", "above", 3, true],
    ["hrvRmssd", "below", 3, true],
    ["hrvRmssd", "above", 9, false],
    // Waking time is shown and not scored: it never alerts.
    ["wakeMinutes", "above", 9, false],
    ["wakeMinutes", "below", 9, false],
  ] as const)("%s %s for %i mornings: alert %s (N = 3)", (metric, side, streak, alerts) => {
    const found = deviationCandidates(
      day({ [metric]: reading(60, side, streak) }),
      DEFAULT_ALERT_SETTINGS,
      new Set(),
    );
    expect(found.map((c) => c.metric)).toEqual(alerts ? [metric] : []);
  });

  it("needs a reading this morning: a streak with no value today is not an alert", () => {
    expect(
      deviationCandidates(
        day({ restingHr: { ...NONE, deviation: "above", streak: 5 } }),
        DEFAULT_ALERT_SETTINGS,
        new Set(),
      ),
    ).toEqual([]);
  });

  it.each([2, 3, 4, 5])("follows ALERT_STREAK_DAYS = %i", (streakDays) => {
    const settings: AlertSettings = { ...DEFAULT_ALERT_SETTINGS, streakDays };
    for (const streak of [1, 2, 3, 4, 5, 6]) {
      const found = deviationCandidates(
        day({ restingHr: reading(58, "above", streak) }),
        settings,
        new Set(),
      );
      expect(found.length > 0, `streak ${streak}`).toBe(streak >= streakDays);
    }
  });

  it("names where the run began, and reports a streak once: a longer one is the same streak, a new one is not", () => {
    const first = deviationCandidates(
      day({ restingHr: reading(58, "above", 3) }),
      DEFAULT_ALERT_SETTINGS,
      new Set(),
    );
    expect(first).toHaveLength(1);
    expect(first[0]).toMatchObject({
      streak: 3,
      streakStart: "2026-11-16",
      streakKey: "restingHr@2026-11-16",
    });
    const reported = new Set([first[0]?.streakKey ?? ""]);
    // The next morning it is day four of the same run.
    const next = deviationCandidates(
      day({ date: "2026-11-19", restingHr: reading(59, "above", 4) }),
      DEFAULT_ALERT_SETTINGS,
      reported,
    );
    expect(next).toEqual([]);
    // After a break a new run begins on another day: a new alert.
    const later = deviationCandidates(
      day({ date: "2026-11-30", restingHr: reading(57, "above", 3) }),
      DEFAULT_ALERT_SETTINGS,
      reported,
    );
    expect(later.map((c) => c.streakKey)).toEqual(["restingHr@2026-11-28"]);
  });

  it("alerts once per metric: another metric in the same streak window is its own alert", () => {
    const reported = new Set(["restingHr@2026-11-16"]);
    const found = deviationCandidates(
      day({ restingHr: reading(58, "above", 3), sleepingHr: reading(53, "above", 3) }),
      DEFAULT_ALERT_SETTINGS,
      reported,
    );
    expect(found.map((c) => c.metric)).toEqual(["sleepingHr"]);
  });

  it("puts the longest run first and carries the reading the facts need", () => {
    const found = deviationCandidates(
      day({
        restingHr: reading(58, "above", 3),
        sleepSeconds: { ...reading(21600, "below", 5), baselineLow: 25200, baselineHigh: 28800 },
      }),
      DEFAULT_ALERT_SETTINGS,
      new Set(),
    );
    expect(found.map((c) => [c.metric, c.streak])).toEqual([
      ["sleepSeconds", 5],
      ["restingHr", 3],
    ]);
    expect(found[1]?.fact).toEqual({
      metric: "restingHr",
      label: "Resting heart rate",
      value: 58,
      unit: "bpm",
      baselineLow: 50,
      baselineHigh: 54,
      deviation: "above",
      streak: 3,
    });
  });
});

// ---- Plan drift -----------------------------------------------------------------------------------

const TODAY = "2026-11-18";
const planned = (
  id: string,
  date: string,
  load: number | null = 60,
  status: PlannedRow["status"] = "planned",
): PlannedRow => ({
  id,
  date,
  name: `Workout ${id}`,
  plannedLoad: load,
  status,
});

const rideOn = (
  id: string,
  date: string,
  tss: number | null,
  workoutId: string | null = null,
): RideBrief => ({
  id,
  startMs: Date.parse(`${date}T12:00:00Z`),
  workoutId,
  durationSeconds: 3600,
  movingSeconds: 3600,
  averagePower: 150,
  normalizedPower: 160,
  averageHeartRate: 130,
  maxHeartRate: 150,
  trainingStressScore: tss,
});

const drift = (rows: PlannedRow[], rides: RideBrief[], settings = DEFAULT_ALERT_SETTINGS) =>
  evaluateDrift(TODAY, rows, rides, "UTC", settings);

describe("plan drift: missed sessions", () => {
  const week = [planned("a", "2026-11-12"), planned("b", "2026-11-14"), planned("c", "2026-11-16")];

  it.each([
    ["none ridden", [], 3, true],
    ["one ridden", [rideOn("r1", "2026-11-12", 60, "a")], 2, true],
    [
      "two ridden",
      [rideOn("r1", "2026-11-12", 60, "a"), rideOn("r2", "2026-11-14", 60, "b")],
      1,
      false,
    ],
    [
      "all ridden",
      [
        rideOn("r1", "2026-11-12", 60, "a"),
        rideOn("r2", "2026-11-14", 60, "b"),
        rideOn("r3", "2026-11-16", 60, "c"),
      ],
      0,
      false,
    ],
  ] as const)("%s: %i missed, alert %s (2 or more)", (_name, rides, missed, alert) => {
    const result = drift(week, [...rides]);
    expect(result.missed).toHaveLength(missed);
    expect(result.reasons.includes("missed")).toBe(alert);
  });

  it.each([1, 2, 3])("follows ALERT_MISSED_SESSIONS = %i", (missedSessions) => {
    const result = drift(week.slice(0, 2), [], { ...DEFAULT_ALERT_SETTINGS, missedSessions });
    expect(result.reasons.includes("missed")).toBe(2 >= missedSessions);
  });

  it("pairs a ride that names no workout with the only session planned that day", () => {
    const result = drift(week, [rideOn("r1", "2026-11-12", 60), rideOn("r2", "2026-11-14", 60)]);
    expect(result.missed.map((m) => m.date)).toEqual(["2026-11-16"]);
  });

  it("counts only the 7 days before today: today's session is still to come and last week's is too old", () => {
    const rows = [
      planned("old", "2026-11-10"),
      planned("a", "2026-11-11"),
      planned("today", TODAY),
    ];
    expect(drift(rows, []).missed.map((m) => m.date)).toEqual(["2026-11-11"]);
  });

  it("does not count a session the rider skipped, nor plan it", () => {
    const result = drift(
      [
        planned("a", "2026-11-12", 60, "skipped"),
        planned("b", "2026-11-14"),
        planned("c", "2026-11-16", 60, "skipped"),
      ],
      [],
    );
    expect(result.missed.map((m) => m.date)).toEqual(["2026-11-14"]);
    expect(result.reasons).not.toContain("missed");
  });
});

describe("plan drift: load against the plan", () => {
  // Planned 200 over three sessions; ridden loads vary.
  const rows = [
    planned("a", "2026-11-12", 80),
    planned("b", "2026-11-14", 70),
    planned("c", "2026-11-16", 50),
  ];
  const rideAll = (tss: number) => [
    rideOn("r1", "2026-11-12", tss / 3, "a"),
    rideOn("r2", "2026-11-14", tss / 3, "b"),
    rideOn("r3", "2026-11-16", tss / 3, "c"),
  ];

  it.each([
    [200, null],
    [259, null],
    [260, null], // exactly +30%: not more than +30%
    [261, "load-above"],
    [320, "load-above"],
    [121, null],
    [120, null], // exactly -40%: not more than 40% below
    [119, "load-below"],
    [60, "load-below"],
  ] as const)("ridden %i against planned 200 (+30% / -40%): %s", (ridden, reason) => {
    const result = drift(rows, rideAll(ridden));
    const loadReasons = result.reasons.filter((r) => r.startsWith("load"));
    expect(loadReasons).toEqual(reason === null ? [] : [reason]);
    expect(result.load).toEqual({ planned: 200, ridden: expect.closeTo(ridden, 6) });
  });

  it.each([
    [10, 30, 100, 261 / 200 > 1.1],
    [50, 40, 100, 261 / 200 > 1.5],
  ])("follows ALERT_LOAD_ABOVE_PERCENT = %i", (above, below, _ridden, alert) => {
    const result = drift(rows, rideAll(261), {
      ...DEFAULT_ALERT_SETTINGS,
      loadAbovePercent: above,
      loadBelowPercent: below,
    });
    expect(result.reasons.includes("load-above")).toBe(alert);
  });

  it("follows ALERT_LOAD_BELOW_PERCENT", () => {
    expect(
      drift(rows, rideAll(150), { ...DEFAULT_ALERT_SETTINGS, loadBelowPercent: 20 }).reasons,
    ).toContain("load-below");
    expect(drift(rows, rideAll(150)).reasons).not.toContain("load-below");
  });

  it("counts every ride of the window as ridden, planned or not", () => {
    const result = drift(rows, [...rideAll(200), rideOn("extra", "2026-11-13", 100)]);
    expect(result.reasons).toContain("load-above");
    expect(result.load?.ridden).toBeCloseTo(300, 6);
  });

  it("makes no comparison when too little was planned, when nothing was, or when a planned load is unknown", () => {
    expect(
      drift([planned("a", "2026-11-12", 40)], [rideOn("r", "2026-11-12", 400, "a")]).load,
    ).toBeNull();
    expect(drift([], [rideOn("r", "2026-11-12", 400)]).load).toBeNull();
    const unknown = drift([planned("a", "2026-11-12", null), planned("b", "2026-11-14", 80)], []);
    expect(unknown.load).toBeNull();
    expect(unknown.reasons.filter((r) => r.startsWith("load"))).toEqual([]);
  });

  it("can name missed sessions and a low load together", () => {
    expect(drift(rows, [rideOn("r1", "2026-11-12", 20, "a")]).reasons).toEqual([
      "missed",
      "load-below",
    ]);
  });
});
