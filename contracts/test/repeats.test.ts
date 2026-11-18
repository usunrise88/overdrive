import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  REPEAT_HASH_PATTERN,
  REPEAT_MIN_STEP_SECONDS,
  REPEAT_MIN_TREND_ATTEMPTS,
  REPEAT_NOTE,
  REPEAT_POWER_TOLERANCE_PERCENT,
  REPEAT_SKIP_SECONDS,
  validateRepeatDetailResponse,
  validateRepeatPreviousResponse,
  validateRepeatsResponse,
  WORKOUT_HASH_VERSION,
  type Workout,
  workoutHash,
  workoutStructure,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/repeats/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("repeats response", () => {
  const valid = load("repeats-response", "valid");
  const invalid = load("repeats-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(5);
    expect(invalid.length).toBeGreaterThanOrEqual(30);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateRepeatsResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateRepeatsResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });
});

describe("repeat detail response", () => {
  const valid = load("repeat-detail-response", "valid");
  const invalid = load("repeat-detail-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(8);
    expect(invalid.length).toBeGreaterThanOrEqual(60);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateRepeatDetailResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateRepeatDetailResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("covers every direction of a trend, and the unknown one", () => {
    const directions = new Set<string>();
    for (const [, body] of valid) {
      const trends = (body as { trends: Record<string, { direction: string }> }).trends;
      for (const trend of Object.values(trends)) directions.add(trend.direction);
    }
    expect([...directions].sort()).toEqual(["improving", "steady", "unknown", "worsening"]);
  });

  it("states the definition the schema fixes, and the note is the standing caution", () => {
    expect(REPEAT_POWER_TOLERANCE_PERCENT).toBe(3);
    expect(REPEAT_SKIP_SECONDS).toBe(60);
    expect(REPEAT_MIN_STEP_SECONDS).toBe(120);
    expect(REPEAT_MIN_TREND_ATTEMPTS).toBe(3);
    for (const [, body] of valid) {
      const answer = body as { note: string; definition: Record<string, number> };
      expect(answer.note).toBe(REPEAT_NOTE);
      expect(answer.definition).toEqual({
        powerTolerancePercent: REPEAT_POWER_TOLERANCE_PERCENT,
        skipSeconds: REPEAT_SKIP_SECONDS,
        minStepSeconds: REPEAT_MIN_STEP_SECONDS,
        minTrendAttempts: REPEAT_MIN_TREND_ATTEMPTS,
      });
    }
    expect(REPEAT_NOTE).toMatch(/heat, sleep and caffeine/);
  });

  it("never quotes a figure in an error", () => {
    const [, body] = valid[0] as [
      string,
      { attempts: { heartRateAtPower: { value: number | null; reason: string | null } }[] },
    ];
    const broken = structuredClone(body);
    const attempt = broken.attempts.find((a) => a.heartRateAtPower.value !== null);
    if (attempt === undefined) throw new Error("fixture has no attempt with a figure");
    attempt.heartRateAtPower.value = 143.987;
    attempt.heartRateAtPower.reason = "both a value and a reason";
    const result = validateRepeatDetailResponse(broken);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.join("\n")).not.toContain("143.987");
  });
});

describe("repeat previous response", () => {
  const valid = load("repeat-previous-response", "valid");
  const invalid = load("repeat-previous-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(5);
    expect(invalid.length).toBeGreaterThanOrEqual(20);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateRepeatPreviousResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateRepeatPreviousResponse(body);
    expect(result.ok).toBe(false);
  });
});

interface HashCase {
  name: string;
  workout: Workout;
  structure: string;
  hash: string;
}

describe("the workout hash", () => {
  const golden = JSON.parse(readFileSync(join(fixtures, "workout-hash.json"), "utf8")) as {
    version: number;
    cases: HashCase[];
    same: string[][];
    different: string[][];
  };
  const byName = new Map(golden.cases.map((c) => [c.name, c]));
  const of = (name: string): HashCase => {
    const found = byName.get(name);
    if (found === undefined) throw new Error(`no case ${name}`);
    return found;
  };

  it("is version 1 and the fixture is of that version", () => {
    expect(WORKOUT_HASH_VERSION).toBe(1);
    expect(golden.version).toBe(WORKOUT_HASH_VERSION);
  });

  it.each(golden.cases.map((c) => [c.name, c] as const))(
    "%s: structure and hash are the golden ones",
    (_n, c) => {
      expect(workoutStructure(c.workout)).toBe(c.structure);
      expect(workoutHash(c.workout)).toBe(c.hash);
      expect(c.hash).toMatch(REPEAT_HASH_PATTERN);
    },
  );

  it("matches a renamed copy, a copy with other labels, cadence targets and float noise, and one written out step by step", () => {
    for (const group of golden.same) {
      const hashes = new Set(group.map((name) => workoutHash(of(name).workout)));
      expect(hashes.size, group.join(" = ")).toBe(1);
    }
  });

  it("does not match when one target, one duration, the count, the order or a step's type changes", () => {
    for (const [a, b] of golden.different) {
      expect(workoutHash(of(a as string).workout), `${a} != ${b}`).not.toBe(
        workoutHash(of(b as string).workout),
      );
    }
  });

  it("does not match when a single step of a workout written out changes", () => {
    const written = of("sweet-spot-every-step-written-out").workout;
    for (let index = 0; index < written.steps.length; index++) {
      const changed = structuredClone(written);
      const step = changed.steps[index];
      if (step === undefined || step.type === "repeat") continue;
      step.duration += 1;
      expect(workoutHash(changed), `step ${index + 1}`).not.toBe(workoutHash(written));
    }
  });
});
