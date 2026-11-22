import {
  POWER_LEVEL_NAMES,
  POWER_SKILL_DURATIONS,
  type PowerSkillDuration,
  type PowerSkillsResponse,
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
import { LEVEL_COUNT, LEVEL_THRESHOLDS, levelAt } from "../src/power-skills/levels.ts";

/** A ride that gives these watts at these durations. */
const ride = (
  rideId: string,
  date: string,
  start: string,
  powers: Record<number, number>,
): SkillRide => ({
  rideId,
  date,
  startEpochMillis: Date.parse(start),
  powers: new Map(Object.entries(powers).map(([seconds, watts]) => [Number(seconds), watts])),
});

const GENERATED = "2026-11-18T08:00:00.000Z";
const WEIGHT = { kg: 80, asOf: "2026-11-17" };

function answer(rides: SkillRide[], weight: typeof WEIGHT | null = WEIGHT): PowerSkillsResponse {
  const response = buildPowerSkills({
    rides,
    from: "2026-10-10",
    to: "2026-11-18",
    weight,
    generatedAt: GENERATED,
  });
  // Whatever the service answers, the contract's own validator must accept.
  const checked = validatePowerSkillsResponse(response);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return response;
}

const row = (response: PowerSkillsResponse, seconds: PowerSkillDuration) =>
  response.durations.find((d) => d.seconds === seconds);

describe("the level table", () => {
  it("has a row for each of the twelve durations and nine thresholds in each, level 1 at 0", () => {
    expect(Object.keys(LEVEL_THRESHOLDS).map(Number)).toEqual([...POWER_SKILL_DURATIONS]);
    expect(LEVEL_COUNT).toBe(9);
    for (const seconds of POWER_SKILL_DURATIONS) {
      const row = LEVEL_THRESHOLDS[seconds];
      expect(row).toHaveLength(9);
      expect(row[0]).toBe(0);
    }
  });

  it("rises with the level at every duration, strictly", () => {
    for (const seconds of POWER_SKILL_DURATIONS) {
      const thresholds = LEVEL_THRESHOLDS[seconds];
      for (let level = 1; level < thresholds.length; level++) {
        expect(thresholds[level], `${seconds} s level ${level + 1}`).toBeGreaterThan(
          thresholds[level - 1] as number,
        );
      }
    }
  });

  it("falls as the duration grows at every level: a longer effort is never asked for more watts per kilogram", () => {
    for (let level = 2; level <= LEVEL_COUNT; level++) {
      for (let i = 1; i < POWER_SKILL_DURATIONS.length; i++) {
        const shorter = LEVEL_THRESHOLDS[POWER_SKILL_DURATIONS[i - 1] as PowerSkillDuration];
        const longer = LEVEL_THRESHOLDS[POWER_SKILL_DURATIONS[i] as PowerSkillDuration];
        expect(longer[level - 1], `level ${level} at ${POWER_SKILL_DURATIONS[i]} s`).toBeLessThan(
          shorter[level - 1] as number,
        );
      }
    }
  });

  it("holds the four anchor columns it says it is built from, at 1 min, 5 min and 60 min", () => {
    // The values written in the file's comment: levels 2 to 9.
    expect(LEVEL_THRESHOLDS[60].slice(1)).toEqual([4.0, 5.0, 6.0, 7.1, 8.2, 9.3, 10.4, 11.5]);
    expect(LEVEL_THRESHOLDS[300].slice(1)).toEqual([2.5, 3.2, 3.9, 4.6, 5.3, 6.0, 6.8, 7.6]);
    expect(LEVEL_THRESHOLDS[3600].slice(1)).toEqual([2.0, 2.6, 3.2, 3.8, 4.4, 5.0, 5.7, 6.4]);
  });

  it("puts a power at the highest level whose threshold it meets, the threshold itself included", () => {
    expect(levelAt(300, 0)).toEqual({ index: 1, name: "Starting", nextLevelWattsPerKg: 2.5 });
    expect(levelAt(300, 2.49).index).toBe(1);
    expect(levelAt(300, 2.5)).toEqual({ index: 2, name: "Casual", nextLevelWattsPerKg: 3.2 });
    expect(levelAt(300, 3.88)).toEqual({ index: 3, name: "Regular", nextLevelWattsPerKg: 3.9 });
    expect(levelAt(300, 3.9).index).toBe(4);
    expect(levelAt(300, 7.59).index).toBe(8);
    expect(levelAt(300, 7.6)).toEqual({ index: 9, name: "Elite", nextLevelWattsPerKg: null });
    expect(levelAt(300, 12).index).toBe(9);
    expect(levelAt(3600, 6.4).index).toBe(9);
    expect(levelAt(15, 6.47).index).toBe(1);
    expect(levelAt(15, 6.48).index).toBe(2);
  });

  it("names the nine levels in order", () => {
    expect(
      POWER_LEVEL_NAMES.map((_, i) => levelAt(300, LEVEL_THRESHOLDS[300][i] as number).name),
    ).toEqual([...POWER_LEVEL_NAMES]);
  });
});

describe("the power skills of a set of rides, by hand", () => {
  // Window 2026-10-10 to 2026-11-18, weight 80 kg.
  const A = ride("a", "2026-10-05", "2026-10-05T17:00:00Z", {
    15: 900,
    30: 700,
    60: 500,
    300: 320,
  });
  const B = ride("b", "2026-10-20", "2026-10-20T17:00:00Z", {
    15: 800,
    30: 650,
    60: 480,
    120: 400,
    300: 300,
  });
  const C = ride("c", "2026-11-02", "2026-11-02T17:00:00Z", {
    15: 800,
    30: 600,
    60: 470,
    300: 310,
  });
  const D = ride("d", "2026-11-05", "2026-11-05T17:00:00Z", {});
  const E = ride("e", "2026-11-30", "2026-11-30T17:00:00Z", { 15: 1500, 3600: 400 });

  it("takes the best of the window and of all time, with the ride and the day, and a tie goes to the later ride", () => {
    const response = answer([A, B, C, D, E]);
    expect(response.window).toEqual({ from: "2026-10-10", to: "2026-11-18", rideCount: 2 });
    // 15 s: B and C both 800 W, the later one (C) wins; the older ride A is outside the window.
    expect(row(response, 15)).toEqual({
      seconds: 15,
      window: { watts: 800, wattsPerKg: 10, date: "2026-11-02", rideId: "c" },
      allTime: { watts: 900, wattsPerKg: 11.25, date: "2026-10-05", rideId: "a" },
    });
    // 30 s: 650 W is 8.125 W/kg, rounded half up to 8.13.
    expect(row(response, 30)?.window).toEqual({
      watts: 650,
      wattsPerKg: 8.13,
      date: "2026-10-20",
      rideId: "b",
    });
    expect(row(response, 120)).toEqual({
      seconds: 120,
      window: { watts: 400, wattsPerKg: 5, date: "2026-10-20", rideId: "b" },
      allTime: { watts: 400, wattsPerKg: 5, date: "2026-10-20", rideId: "b" },
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

  it("judges each skill at its own durations and takes the best level, the shorter duration on a tie", () => {
    const { skills } = answer([A, B, C, D, E]);
    // Sprint: 15 s 10.00 W/kg is level 4 (9.43 to 10.97), 30 s 8.13 W/kg is level 4 too (7.52 to 8.82): the tie goes to 15 s.
    expect(skills[0]?.level).toEqual({
      index: 4,
      name: "Capable",
      bestWattsPerKg: 10,
      nextLevelWattsPerKg: 10.98,
      atSeconds: 15,
    });
    // Attack: 60 s 6.00 W/kg is exactly the threshold of level 4; 120 s 5.00 W/kg is level 4 as well (4.98).
    expect(skills[1]?.level).toEqual({
      index: 4,
      name: "Capable",
      bestWattsPerKg: 6,
      nextLevelWattsPerKg: 7.1,
      atSeconds: 60,
    });
    // Climb: 300 s 3.88 W/kg is one hundredth under level 4 (3.90).
    expect(skills[2]?.level).toEqual({
      index: 3,
      name: "Regular",
      bestWattsPerKg: 3.88,
      nextLevelWattsPerKg: 3.9,
      atSeconds: 300,
    });
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

  it("gives the level of the longer duration when it is the higher one", () => {
    const rider = ride("r", "2026-11-01", "2026-11-01T17:00:00Z", { 15: 700, 30: 640 });
    const response = answer([rider]);
    // 15 s: 8.75 W/kg is level 3 (7.96 to 9.42); 30 s: 8.00 W/kg is level 4 (7.52 to 8.82).
    expect(response.skills[0]?.level).toMatchObject({ index: 4, atSeconds: 30, bestWattsPerKg: 8 });
  });

  it("gives no levels and no watts per kilogram without a weight, and says why", () => {
    const response = answer([A, B, C], null);
    expect(response.riderWeight).toBeUndefined();
    expect(response.skills.every((s) => s.level === null)).toBe(true);
    expect(row(response, 15)?.window).toEqual({ watts: 800, date: "2026-11-02", rideId: "c" });
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

  it("reaches level 9 with no next level", () => {
    const strong = ride("s", "2026-11-01", "2026-11-01T17:00:00Z", { 300: 640 });
    // 640 W at 80 kg is 8 W/kg: over the 7.6 of level 9 at 5 minutes.
    const response = answer([strong]);
    expect(response.skills[2]?.level).toEqual({
      index: 9,
      name: "Elite",
      bestWattsPerKg: 8,
      atSeconds: 300,
    });
  });
});
