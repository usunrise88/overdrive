import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  EFFORT_DEFAULT_WEEKS,
  EFFORT_FIRST_VERDICT_WEEKDAY,
  EFFORT_MAX_WEEKS,
  EFFORT_MIN_USUAL_WEEKS,
  EFFORT_USUAL_WEEKS,
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
    expect(valid.length).toBeGreaterThanOrEqual(10);
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

  it("covers every verdict, every kind of activity and a week that is approximate", () => {
    const bodies = valid.map(([, body]) => body as EffortResponse);
    expect(new Set(bodies.map((b) => b.thisWeek.verdict))).toEqual(
      new Set(["below", "within", "above", "unknown"]),
    );
    const kinds = new Set(
      bodies.flatMap((b) =>
        b.weeks.flatMap((w) => w.days.flatMap((d) => d.activities.map((a) => a.kind))),
      ),
    );
    expect(kinds).toEqual(new Set(["ride", "watch", "outdoor"]));
    expect(bodies.some((b) => b.weeks.some((w) => w.approximate))).toBe(true);
    expect(bodies.some((b) => b.usual === null)).toBe(true);
    expect(bodies.some((b) => b.weeks.length === EFFORT_MAX_WEEKS)).toBe(true);
  });
});

describe("the effort constants", () => {
  it("are twelve weeks by default, at most fifty-two, a usual range of four to eight weeks and no verdict before Wednesday", () => {
    expect(EFFORT_DEFAULT_WEEKS).toBe(12);
    expect(EFFORT_MAX_WEEKS).toBe(52);
    expect(EFFORT_MIN_USUAL_WEEKS).toBe(4);
    expect(EFFORT_USUAL_WEEKS).toBe(8);
    expect(EFFORT_FIRST_VERDICT_WEEKDAY).toBe(3);
  });
});
