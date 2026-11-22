import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  EFFORT_DEFAULT_WEEKS,
  EFFORT_MAX_WEEKS,
  EFFORT_WEEKS_NEEDED,
  type EffortResponse,
  validateEffortResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/effort/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("effort response", () => {
  const valid = load("response", "valid");
  const invalid = load("response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(14);
    expect(invalid.length).toBeGreaterThanOrEqual(60);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateEffortResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateEffortResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("covers every verdict, both kinds, an activity with no load and a week that is approximate", () => {
    const bodies = valid.map(([, body]) => body as EffortResponse);
    const weeks = bodies.flatMap((b) => b.weeks);
    expect(new Set(weeks.map((w) => w.verdict))).toEqual(
      new Set(["above", "within", "below", "none", "learning"]),
    );
    const activities = weeks.flatMap((w) => w.activities);
    expect(new Set(activities.map((a) => a.kind))).toEqual(new Set(["ride", "activity"]));
    expect(activities.some((a) => a.sport === "cycling-outdoor")).toBe(true);
    expect(activities.some((a) => a.load === null)).toBe(true);
    expect(weeks.some((w) => w.approximate)).toBe(true);
    expect(bodies.some((b) => b.weeks.length === EFFORT_MAX_WEEKS)).toBe(true);
  });
});

describe("the effort constants", () => {
  it("are twelve weeks by default, at most fifty-two and eight weeks needed for a usual range", () => {
    expect(EFFORT_DEFAULT_WEEKS).toBe(12);
    expect(EFFORT_MAX_WEEKS).toBe(52);
    expect(EFFORT_WEEKS_NEEDED).toBe(8);
  });
});
