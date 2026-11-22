import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  POWER_CURVE_DURATIONS,
  POWER_LEVEL_NAMES,
  POWER_SKILL_DURATIONS,
  POWER_SKILLS,
  POWER_SKILLS_DEFAULT_DAYS,
  POWER_SKILLS_MAX_DAYS,
  type PowerSkillsResponse,
  rangeDays,
  validatePowerSkillsResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/power-skills/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("power skills response", () => {
  const valid = load("response", "valid");
  const invalid = load("response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(8);
    expect(invalid.length).toBeGreaterThanOrEqual(60);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validatePowerSkillsResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validatePowerSkillsResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("covers a level with no next one, a window with no ride and a missing weight", () => {
    const bodies = valid.map(([, body]) => body as PowerSkillsResponse);
    expect(
      bodies.some((b) =>
        b.skills.some((s) => s.level?.index === 9 && s.level.nextLevelWattsPerKg === undefined),
      ),
    ).toBe(true);
    expect(bodies.some((b) => b.window.rideCount === 0)).toBe(true);
    expect(bodies.some((b) => b.riderWeight === undefined)).toBe(true);
    expect(bodies.some((b) => b.skills.every((s) => s.level === null))).toBe(true);
  });
});

describe("the power skills' constants", () => {
  it("has twelve durations, in order, every one of them in a ride's stored curve or the one extra duration", () => {
    expect(POWER_SKILL_DURATIONS).toHaveLength(12);
    expect([...POWER_SKILL_DURATIONS]).toEqual([...POWER_SKILL_DURATIONS].sort((a, b) => a - b));
    const stored = new Set<number>(POWER_CURVE_DURATIONS);
    // 45 minutes is the one duration the contract's 25 do not have: the service keeps it as well (ADR 0042).
    expect(POWER_SKILL_DURATIONS.filter((d) => !stored.has(d))).toEqual([2700]);
  });

  it("has five skills that together cover every duration once", () => {
    expect(POWER_SKILLS.map((s) => s.name)).toEqual([
      "Sprint",
      "Attack",
      "Climb",
      "Time trial",
      "Endurance",
    ]);
    const covered = POWER_SKILLS.flatMap((s) =>
      POWER_SKILL_DURATIONS.filter((d) => d >= s.fromSeconds && d <= s.toSeconds),
    );
    expect(covered).toEqual([...POWER_SKILL_DURATIONS]);
  });

  it("has nine distinct level names, and a window of eight weeks by default and three years at most", () => {
    expect(POWER_LEVEL_NAMES).toHaveLength(9);
    expect(new Set(POWER_LEVEL_NAMES).size).toBe(9);
    expect(POWER_SKILLS_DEFAULT_DAYS).toBe(56);
    // 2023-11-19 to 2026-11-18 is the longest window: three years, a leap day inside.
    expect(rangeDays("2023-11-19", "2026-11-18")).toBe(POWER_SKILLS_MAX_DAYS);
  });
});
