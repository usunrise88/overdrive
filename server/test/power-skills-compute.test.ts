import {
  POWER_SKILL_DURATIONS,
  type PowerProfileTable,
  type PowerSkillDuration,
  type PowerSkillsResponse,
  type RiderWeight,
  validatePowerSkillsResponse,
} from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildPowerSkills,
  LEVELS_NEED_WEIGHT_NOTE,
  LEVELS_NOTE,
  OUTDOOR_NOTE,
  type SkillRide,
} from "../src/power-skills/compute.ts";
import {
  ANCHOR_SECONDS,
  LEVEL_FLOORS,
  scoreAt,
  WOMEN_ANCHORS,
} from "../src/power-skills/levels.ts";

/** A ride that gives these watts at these durations. */
const ride = (
  rideId: string,
  date: string,
  start: string,
  powers: Record<number, number>,
  rideName = "Free ride",
): SkillRide => ({
  rideId,
  rideName,
  date,
  startEpochMillis: Date.parse(start),
  powers: new Map(Object.entries(powers).map(([seconds, watts]) => [Number(seconds), watts])),
});

const GENERATED = "2026-11-18T08:00:00.000Z";
const WEIGHT: RiderWeight = { kg: 80, date: "2026-11-17", days: 1 };

function answer(
  rides: SkillRide[],
  weight: RiderWeight | null = WEIGHT,
  table: PowerProfileTable = "men",
): PowerSkillsResponse {
  const response = buildPowerSkills({
    rides,
    from: "2026-10-10",
    to: "2026-11-18",
    weight,
    table,
    generatedAt: GENERATED,
  });
  // Whatever the service answers, the contract's own validator must accept.
  const checked = validatePowerSkillsResponse(response);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return response;
}

const row = (response: PowerSkillsResponse, seconds: PowerSkillDuration) =>
  response.durations.find((d) => d.seconds === seconds);

describe("the level scale", () => {
  const both = ["men", "women"] as const;

  it("has a floor below the top at each of the twelve durations, in both tables", () => {
    for (const table of both) {
      expect(Object.keys(LEVEL_FLOORS[table]).map(Number)).toEqual([...POWER_SKILL_DURATIONS]);
      for (const seconds of POWER_SKILL_DURATIONS) {
        const { b, t } = LEVEL_FLOORS[table][seconds];
        expect(t, `${table} ${seconds} s`).toBeGreaterThan(b);
        expect(b).toBeGreaterThan(0);
      }
    }
  });

  it("falls as the duration grows, the floor never rises and the top always falls: a longer effort is never asked for more watts per kilogram", () => {
    for (const table of both) {
      for (let i = 1; i < POWER_SKILL_DURATIONS.length; i++) {
        const shorter = LEVEL_FLOORS[table][POWER_SKILL_DURATIONS[i - 1] as PowerSkillDuration];
        const longer = LEVEL_FLOORS[table][POWER_SKILL_DURATIONS[i] as PowerSkillDuration];
        expect(longer.b, `${table} floor at ${POWER_SKILL_DURATIONS[i]} s`).toBeLessThanOrEqual(
          shorter.b,
        );
        expect(longer.t, `${table} top at ${POWER_SKILL_DURATIONS[i]} s`).toBeLessThan(shorter.t);
      }
    }
  });

  it("holds the men's table of the design", () => {
    expect(LEVEL_FLOORS.men[15]).toEqual({ b: 8.2, t: 18.5 });
    expect(LEVEL_FLOORS.men[60]).toEqual({ b: 5.6, t: 11.5 });
    expect(LEVEL_FLOORS.men[300]).toEqual({ b: 2.3, t: 7.6 });
    expect(LEVEL_FLOORS.men[3600]).toEqual({ b: 1.9, t: 6.4 });
  });

  it("has a women's table that is the log-log interpolation of its four published anchors, to the hundredth", () => {
    const at = (anchors: readonly number[], seconds: number): number => {
      for (let i = 0; i < ANCHOR_SECONDS.length - 1; i++) {
        const from = ANCHOR_SECONDS[i] as number;
        const to = ANCHOR_SECONDS[i + 1] as number;
        if (seconds >= from && seconds <= to) {
          const f = (Math.log(seconds) - Math.log(from)) / (Math.log(to) - Math.log(from));
          return Math.exp(
            Math.log(anchors[i] as number) +
              f * (Math.log(anchors[i + 1] as number) - Math.log(anchors[i] as number)),
          );
        }
      }
      throw new Error("outside the anchors");
    };
    for (const seconds of POWER_SKILL_DURATIONS) {
      const { b, t } = LEVEL_FLOORS.women[seconds];
      expect(Math.abs(b - at(WOMEN_ANCHORS.b, seconds)), `floor at ${seconds} s`).toBeLessThan(
        0.006,
      );
      expect(Math.abs(t - at(WOMEN_ANCHORS.t, seconds)), `top at ${seconds} s`).toBeLessThan(0.006);
    }
    // The anchors themselves: 1 min, 5 min and 60 min are three of the twelve.
    expect(LEVEL_FLOORS.women[60]).toEqual({ b: 4.75, t: 9.19 });
    expect(LEVEL_FLOORS.women[300]).toEqual({ b: 1.97, t: 6.64 });
    expect(LEVEL_FLOORS.women[3600]).toEqual({ b: 1.58, t: 5.61 });
  });

  it("scores 1 at the floor of level 1, 9 at the floor of level 9, 5 half way, and holds the score between 0 and 9", () => {
    expect(scoreAt("men", 300, 2.3)).toBe(1);
    expect(scoreAt("men", 300, 7.6)).toBe(9);
    expect(scoreAt("men", 300, (2.3 + 7.6) / 2)).toBe(5);
    expect(scoreAt("men", 300, 12)).toBe(9);
    expect(scoreAt("men", 300, 1)).toBe(0);
    expect(scoreAt("men", 300, 0)).toBe(0);
    // Between: 1 + 8 * (4 - 2.3) / 5.3 = 3.566 rounds to 3.57.
    expect(scoreAt("men", 300, 4)).toBe(3.57);
    expect(scoreAt("women", 300, 1.97)).toBe(1);
    expect(scoreAt("women", 3600, 5.61)).toBe(9);
  });
});

describe("the power skills of a set of rides, by hand", () => {
  // Window 2026-10-10 to 2026-11-18, weight 80 kg, men's table.
  const A = ride(
    "a",
    "2026-10-05",
    "2026-10-05T17:00:00Z",
    { 15: 900, 30: 700, 60: 500, 300: 320 },
    "Sweet spot 2x10",
  );
  const B = ride(
    "b",
    "2026-10-20",
    "2026-10-20T17:00:00Z",
    { 15: 800, 30: 650, 60: 480, 120: 400, 300: 300 },
    "VO2 5x3",
  );
  const C = ride("c", "2026-11-02", "2026-11-02T17:00:00Z", {
    15: 800,
    30: 600,
    60: 470,
    300: 310,
  });
  const D = ride("d", "2026-11-05", "2026-11-05T17:00:00Z", {});
  const E = ride("e", "2026-11-30", "2026-11-30T17:00:00Z", { 15: 1500, 3600: 400 });

  it("takes the best of the window and of all time, with the ride, its name and the day, and a tie goes to the later ride", () => {
    const response = answer([A, B, C, D, E]);
    expect(response.window).toEqual({ from: "2026-10-10", to: "2026-11-18", rideCount: 2 });
    expect(response.table).toBe("men");
    expect(response.riderWeight).toEqual(WEIGHT);
    // 15 s: B and C both 800 W, the later one (C) wins; the older ride A is outside the window. 800 / 80 = 10 W/kg; the score is
    // 1 + 8 * (10 - 8.2) / (18.5 - 8.2) = 2.40.
    expect(row(response, 15)).toEqual({
      seconds: 15,
      window: {
        watts: 800,
        wattsPerKg: 10,
        score: 2.4,
        date: "2026-11-02",
        rideId: "c",
        rideName: "Free ride",
      },
      // A's 900 W is 11.25 W/kg: 1 + 8 * 3.05 / 10.3 = 3.37.
      allTime: {
        watts: 900,
        wattsPerKg: 11.25,
        score: 3.37,
        date: "2026-10-05",
        rideId: "a",
        rideName: "Sweet spot 2x10",
      },
    });
    // 30 s: 650 W is 8.125 W/kg, rounded half up to 8.13; 1 + 8 * (8.13 - 6.9) / 8.1 = 2.21.
    expect(row(response, 30)?.window).toEqual({
      watts: 650,
      wattsPerKg: 8.13,
      score: 2.21,
      date: "2026-10-20",
      rideId: "b",
      rideName: "VO2 5x3",
    });
    expect(row(response, 120)).toEqual({
      seconds: 120,
      window: {
        watts: 400,
        wattsPerKg: 5,
        score: 2.14,
        date: "2026-10-20",
        rideId: "b",
        rideName: "VO2 5x3",
      },
      allTime: {
        watts: 400,
        wattsPerKg: 5,
        score: 2.14,
        date: "2026-10-20",
        rideId: "b",
        rideName: "VO2 5x3",
      },
    });
    // 300 s: C's 310 W beats B's 300 W in the window, A's 320 W is the all-time best.
    expect(row(response, 300)?.window?.rideId).toBe("c");
    expect(row(response, 300)?.allTime?.rideId).toBe("a");
  });

  it("leaves a duration null where no ride is long enough, and never uses a ride after the end of the window", () => {
    const response = answer([A, B, C, D, E]);
    expect(row(response, 180)).toEqual({ seconds: 180, window: null, allTime: null });
    expect(row(response, 3600)).toEqual({ seconds: 3600, window: null, allTime: null });
    // E (30 Nov) would have 1500 W at 15 s: it is after `to`.
    expect(row(response, 15)?.allTime?.watts).toBe(900);
  });

  it("makes a skill's level the floor of the mean of the scores of its durations that have an effort", () => {
    const { skills } = answer([A, B, C, D, E]);
    // Sprint: (2.40 + 2.21) / 2 = 2.305 -> level 2.
    expect(skills[0]?.level).toEqual({ index: 2, name: "Casual" });
    // Attack: 1 min 6 W/kg scores 1 + 8 * 0.4 / 5.9 = 1.54, 2 min 5 W/kg scores 2.14, 3 min has no effort: mean 1.84 -> level 1.
    expect(skills[1]?.level).toEqual({ index: 1, name: "Starting" });
    // Climb: 5 min 3.88 W/kg scores 1 + 8 * 1.58 / 5.3 = 3.38 -> level 3; 10 min has no effort.
    expect(skills[2]?.level).toEqual({ index: 3, name: "Regular" });
    // Time trial and Endurance have no effort in the window.
    expect(skills[3]?.level).toBeNull();
    expect(skills[4]?.level).toBeNull();
    expect(skills.map((s) => [s.id, s.name, s.fromSeconds, s.toSeconds])).toEqual([
      ["sprint", "Sprint", 15, 30],
      ["attack", "Attack", 60, 180],
      ["climb", "Climb", 300, 600],
      ["time-trial", "Time trial", 900, 1800],
      ["endurance", "Endurance", 2700, 3600],
    ]);
  });

  it("reads the same rides against the women's table when that is the rider's", () => {
    const response = answer([A, B, C, D, E], WEIGHT, "women");
    expect(response.table).toBe("women");
    // 15 s: 10 W/kg on the women's 6.63 to 13.86: 1 + 8 * 3.37 / 7.23 = 4.73; 30 s: 8.13 on 5.61 to 11.29: 4.55; mean 4.64 -> level 4.
    expect(row(response, 15)?.window?.score).toBe(4.73);
    expect(row(response, 30)?.window?.score).toBe(4.55);
    expect(response.skills[0]?.level).toEqual({ index: 4, name: "Fit" });
  });

  it("holds a score at 9 and at 0, so one huge effort or a very weak one cannot move a mean past its ends", () => {
    const strong = ride("s", "2026-11-01", "2026-11-01T17:00:00Z", { 300: 1000, 600: 900 });
    const response = answer([strong]);
    // 12.5 W/kg and 11.25 W/kg are over the top of the 5 and 10 minute scales: both score 9.
    expect(row(response, 300)?.window?.score).toBe(9);
    expect(response.skills[2]?.level).toEqual({ index: 9, name: "World class" });

    const weak = ride("w", "2026-11-01", "2026-11-01T17:00:00Z", { 3600: 100 });
    const slow = answer([weak]);
    // 1.25 W/kg is under the 1.9 floor of the hour: the score would be negative and is held at 0; the level is still 1.
    expect(row(slow, 3600)?.window?.score).toBe(0);
    expect(slow.skills[4]?.level).toEqual({ index: 1, name: "Starting" });
  });

  it("gives no scores, no levels and no watts per kilogram without a weight, and says why", () => {
    const response = answer([A, B, C], null);
    expect(response.riderWeight).toBeNull();
    expect(response.skills.every((s) => s.level === null)).toBe(true);
    expect(row(response, 15)?.window).toEqual({
      watts: 800,
      wattsPerKg: null,
      score: null,
      date: "2026-11-02",
      rideId: "c",
      rideName: "Free ride",
    });
    expect(response.levelsNote).toBe(LEVELS_NEED_WEIGHT_NOTE);
    expect(answer([A, B, C]).levelsNote).toBe(LEVELS_NOTE);
    expect(response.outdoorNote).toBe(OUTDOOR_NOTE);
  });

  it("answers an empty history with twelve empty durations and five skills without a level", () => {
    const response = answer([]);
    expect(response.window.rideCount).toBe(0);
    expect(response.durations).toHaveLength(12);
    expect(response.durations.every((d) => d.window === null && d.allTime === null)).toBe(true);
    expect(response.skills.every((s) => s.level === null)).toBe(true);
    expect(response.riderWeight).toEqual(WEIGHT);
  });

  it("answers a window with no ride from the all-time bests", () => {
    const response = buildPowerSkills({
      rides: [A],
      from: "2026-11-01",
      to: "2026-11-18",
      weight: WEIGHT,
      table: "men",
      generatedAt: GENERATED,
    });
    expect(validatePowerSkillsResponse(response)).toMatchObject({ ok: true });
    expect(response.window.rideCount).toBe(0);
    expect(row(response, 15)).toMatchObject({
      window: null,
      allTime: { watts: 900, rideId: "a" },
    });
    expect(response.skills.every((s) => s.level === null)).toBe(true);
  });

  it("does not count a ride with no power, and a ride the day the window starts or ends is inside it", () => {
    const first = ride("first", "2026-10-10", "2026-10-10T06:00:00Z", { 15: 500 });
    const last = ride("last", "2026-11-18", "2026-11-18T06:00:00Z", { 15: 520 });
    const response = answer([D, first, last]);
    expect(response.window.rideCount).toBe(2);
    expect(row(response, 15)?.window).toMatchObject({ watts: 520, rideId: "last" });
  });
});
