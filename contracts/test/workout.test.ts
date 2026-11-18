import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { validateWorkout } from "../src/index.ts";

const fixtures = new URL("../fixtures/workouts/", import.meta.url).pathname;

function load(dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("workout schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("valid").length).toBeGreaterThan(0);
    expect(load("invalid").length).toBeGreaterThan(0);
  });

  it.each(load("valid"))("accepts valid/%s", (_name, workout) => {
    const result = validateWorkout(workout);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("invalid"))("rejects invalid/%s", (_name, workout) => {
    const result = validateWorkout(workout);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.length).toBeGreaterThan(0);
    }
  });
});
