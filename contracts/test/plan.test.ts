import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  addDaysToIsoDate,
  isIsoDate,
  MAX_WORKOUTS_RANGE_DAYS,
  rangeDays,
  validateWorkoutsResponse,
  type WorkoutsResponse,
  workoutHash,
  workoutsResponseConsistencyErrors,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/plan/workouts-response/", import.meta.url).pathname;
const schemas = new URL("../schema/", import.meta.url).pathname;

function load(dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("workouts response schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("valid").length).toBeGreaterThanOrEqual(5);
    expect(load("invalid").length).toBeGreaterThanOrEqual(15);
  });

  it.each(load("valid"))("accepts valid/%s", (_name, body) => {
    const result = validateWorkoutsResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validateWorkoutsResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("names the rule a consistent-looking answer breaks", () => {
    const byName = new Map(load("invalid"));
    const errors = (name: string): string => {
      const result = validateWorkoutsResponse(byName.get(name));
      return result.ok ? "" : result.errors.join("; ");
    };
    expect(errors("reversed-range.json")).toMatch(/after/);
    expect(errors("range-of-63-days.json")).toMatch(/63 days/);
    expect(errors("not-a-real-date.json")).toMatch(/real calendar dates/);
    expect(errors("date-outside-range.json")).toMatch(/outside/);
    expect(errors("duplicate-ids.json")).toMatch(/used twice/);
    expect(errors("workout-id-differs.json")).toMatch(/differs/);
    expect(errors("not-configured-with-workouts.json")).toMatch(/empty when not configured/);
    expect(errors("not-configured-but-stale.json")).toMatch(/stale/);
  });

  it("states the longest range as the code does", () => {
    const schema = JSON.parse(
      readFileSync(join(schemas, "workouts-response.schema.json"), "utf8"),
    ) as { description: string };
    expect(schema.description).toContain(`at most ${MAX_WORKOUTS_RANGE_DAYS} days`);
    expect(MAX_WORKOUTS_RANGE_DAYS).toBe(62);
  });

  it("serves a range of exactly 62 days and refuses 63", () => {
    const answer = (to: string): WorkoutsResponse => ({
      from: "2026-11-01",
      to,
      generatedAt: null,
      stale: false,
      configured: true,
      workouts: [],
    });
    expect(workoutsResponseConsistencyErrors(answer("2027-01-01"))).toEqual([]);
    expect(workoutsResponseConsistencyErrors(answer("2027-01-02"))).not.toEqual([]);
  });
});

describe("the structure hash of a planned workout (ADR 0034)", () => {
  const withHash = load("valid").find(([name]) => name === "week-with-structure-hashes.json")?.[1];

  it("is the repeats' own hash of the workout it sits beside, in the fixture", () => {
    const response = withHash as WorkoutsResponse;
    expect(response.workouts.length).toBeGreaterThanOrEqual(2);
    for (const entry of response.workouts) {
      expect(entry.structureHash).toBe(workoutHash(entry.workout));
    }
  });

  it("is optional: every older fixture, with none, is still valid", () => {
    const without = load("valid").filter(
      ([, body]) =>
        (body as WorkoutsResponse).workouts.length > 0 &&
        (body as WorkoutsResponse).workouts.every((w) => w.structureHash === undefined),
    );
    expect(without.length).toBeGreaterThanOrEqual(2);
    for (const [, body] of without)
      expect(validateWorkoutsResponse(body)).toMatchObject({ ok: true });
  });

  it("is refused when it is not 16 lower-case hex characters", () => {
    const byName = new Map(load("invalid"));
    for (const name of [
      "structure-hash-too-short.json",
      "structure-hash-upper-case.json",
      "structure-hash-not-hex.json",
      "structure-hash-null.json",
      "structure-hash-a-number.json",
    ]) {
      expect(validateWorkoutsResponse(byName.get(name)).ok, name).toBe(false);
    }
  });
});

describe("calendar dates", () => {
  it.each(["2026-11-18", "2028-02-29", "2026-12-31", "1970-01-01"])("accepts %s", (date) => {
    expect(isIsoDate(date)).toBe(true);
  });

  it.each([
    "2026-02-30",
    "2027-02-29",
    "2026-13-01",
    "2026-00-10",
    "2026-11-00",
    "2026-11-31",
    "0026-11-18",
    "26-11-18",
    "2026-1-18",
    "2026-11-18T00:00:00",
    "",
  ])("rejects %j", (date) => {
    expect(isIsoDate(date)).toBe(false);
  });

  it("counts days inclusively across month and year ends", () => {
    expect(rangeDays("2026-11-18", "2026-11-18")).toBe(1);
    expect(rangeDays("2026-12-30", "2027-01-02")).toBe(4);
    expect(rangeDays("2026-11-18", "2026-11-17")).toBe(0);
    expect(rangeDays("2026-02-30", "2026-03-01")).toBeNull();
  });

  it("adds days across month, year and leap-day ends", () => {
    expect(addDaysToIsoDate("2026-11-18", 14)).toBe("2026-12-02");
    expect(addDaysToIsoDate("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDaysToIsoDate("2028-02-28", 1)).toBe("2028-02-29");
    expect(addDaysToIsoDate("2026-03-01", -1)).toBe("2026-02-28");
    expect(() => addDaysToIsoDate("2026-02-30", 1)).toThrow();
  });
});
