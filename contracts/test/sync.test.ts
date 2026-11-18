import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  API_ERROR_STATUS,
  isValidRideId,
  RIDE_ID_PATTERN,
  validateApiError,
  validateUploadResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/sync/", import.meta.url).pathname;
const schemas = new URL("../schema/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

function schema(name: string): {
  properties: Record<string, { pattern?: string; enum?: string[]; properties?: unknown }>;
} {
  return JSON.parse(readFileSync(join(schemas, name), "utf8"));
}

describe("upload response schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("upload-response", "valid").length).toBeGreaterThanOrEqual(3);
    expect(load("upload-response", "invalid").length).toBeGreaterThanOrEqual(5);
  });

  it.each(load("upload-response", "valid"))("accepts valid/%s", (_name, body) => {
    const result = validateUploadResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("upload-response", "invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validateUploadResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("states the ride id syntax exactly as the code does", () => {
    const pattern = schema("sync-upload-response.schema.json").properties.rideId?.pattern;
    expect(pattern).toBe(RIDE_ID_PATTERN.source);
  });
});

describe("ride ids", () => {
  it.each(["ride-1794996000000", "free-ride-1", "a", "7", "a.b_c-d", "x".repeat(128)])(
    "accepts %s",
    (id) => {
      expect(isValidRideId(id)).toBe(true);
    },
  );

  it.each(["", "-ride", ".hidden", "../ride", "a/b", "a b", "x".repeat(129), "ride\n", "é"])(
    "rejects %j",
    (id) => {
      expect(isValidRideId(id)).toBe(false);
    },
  );
});

describe("api error schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("api-error", "valid").length).toBeGreaterThanOrEqual(3);
    expect(load("api-error", "invalid").length).toBeGreaterThanOrEqual(5);
  });

  it.each(load("api-error", "valid"))("accepts valid/%s", (_name, body) => {
    const result = validateApiError(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("api-error", "invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validateApiError(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("lists exactly the codes the code does, each with one HTTP status", () => {
    const error = schema("api-error.schema.json").properties.error as unknown as {
      properties: { code: { enum: string[] } };
    };
    expect([...error.properties.code.enum].sort()).toEqual(Object.keys(API_ERROR_STATUS).sort());
    for (const status of Object.values(API_ERROR_STATUS)) {
      expect(status).toBeGreaterThanOrEqual(400);
      expect(status).toBeLessThan(600);
    }
  });
});
