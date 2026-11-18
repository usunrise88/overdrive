import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  ACTIVITY_FIT_MAX_BYTES,
  API_ERROR_STATUS,
  OUTDOOR_RIDE_SPORT,
  validateActivity,
  validateActivityImportResponse,
  validateApiError,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/outdoor/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("activity import response", () => {
  const valid = load("activity-import-response", "valid");
  const invalid = load("activity-import-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(6);
    expect(invalid.length).toBeGreaterThanOrEqual(25);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateActivityImportResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateActivityImportResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("has every status and every reason among the valid fixtures", () => {
    const statuses = new Set<string>();
    const reasons = new Set<string | null>();
    for (const [, body] of valid) {
      const answer = body as { status: string; reason: string | null };
      statuses.add(answer.status);
      reasons.add(answer.reason);
    }
    expect([...statuses].sort()).toEqual(["duplicate", "imported"]);
    expect([...reasons].sort()).toEqual([null, "overlap", "same-file", "same-start-and-duration"]);
  });

  it("states the limit and the sport the answers and the lists use", () => {
    expect(ACTIVITY_FIT_MAX_BYTES).toBe(16 * 1024 * 1024);
    expect(OUTDOOR_RIDE_SPORT).toBe("cycling-outdoor");
  });
});

describe("the error codes of a FIT import", () => {
  it("are 422 and in the closed list", () => {
    expect(API_ERROR_STATUS.invalid_fit).toBe(422);
    expect(API_ERROR_STATUS.unsupported_sport).toBe(422);
    expect(API_ERROR_STATUS.empty_activity).toBe(422);
    for (const code of ["unsupported_sport", "empty_activity"]) {
      const result = validateApiError({ error: { code, message: "x" } });
      expect(result, code).toMatchObject({ ok: true });
    }
  });
});

describe("an outdoor ride as an activity", () => {
  it("is accepted with source fit, its own id and the cycling-outdoor type", () => {
    const body = JSON.parse(
      readFileSync(
        new URL("../fixtures/wellness/activity/valid/outdoor-ride.json", import.meta.url),
        "utf8",
      ),
    );
    expect(validateActivity(body)).toMatchObject({ ok: true });
    expect(body.type).toBe(OUTDOOR_RIDE_SPORT);
  });
});
