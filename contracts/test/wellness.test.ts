import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  checkWellnessUploadSchema,
  parseInstant,
  validateActivity,
  validateRecoveryResponse,
  validateWellnessResponse,
  validateWellnessUpload,
  validateWellnessUploadResponse,
  WELLNESS_MAX_RECORDS,
  WELLNESS_RECORD_KINDS,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/wellness/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("wellness upload", () => {
  const valid = load("upload", "valid");
  const invalid = load("upload", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(6);
    expect(invalid.length).toBeGreaterThanOrEqual(50);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateWellnessUpload(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
    // The whole schema, with its oneOf, agrees.
    expect(checkWellnessUploadSchema(body), JSON.stringify(checkWellnessUploadSchema.errors)).toBe(
      true,
    );
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateWellnessUpload(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  // What only the code's second step can see; the schema alone lets these through.
  const BEYOND_THE_SCHEMA = new Set([
    "time-not-a-real-date.json",
    "time-hour-24.json",
    "heart-rate-sample-time-not-real.json",
    "heart-rate-end-before-start.json",
    "sleep-end-before-start.json",
    "sleep-stage-end-before-start.json",
    "exercise-end-equals-start.json",
    "id-twice-in-batch.json",
    "a-valid-record-after-a-bad-one.json",
  ]);

  it.each(invalid.filter(([name]) => !BEYOND_THE_SCHEMA.has(name)))(
    "the whole schema alone rejects invalid/%s",
    (_name, body) => {
      expect(checkWellnessUploadSchema(body)).toBe(false);
    },
  );

  it("names the record index and the field of a bad record, never its value", () => {
    const byName = new Map(invalid);
    const batch = byName.get("a-valid-record-after-a-bad-one.json");
    const result = validateWellnessUpload(batch);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors).toEqual(["/records/1/bpm must be >= 1"]);
    }
  });

  it("reports a real instant problem by path", () => {
    const body = {
      source: "healthconnect",
      records: [
        { kind: "restingHeartRate", id: "a", time: "2026-11-18T06:30:00Z", bpm: 55 },
        { kind: "respiratoryRate", id: "b", time: "2026-02-30T06:30:00Z", rate: 14.3 },
      ],
    };
    const result = validateWellnessUpload(body);
    expect(result).toEqual({
      ok: false,
      errors: ["/records/1/time is not a real date and time"],
    });
  });

  it("never repeats what the sender wrote, whether it is a value or a property name", () => {
    const secret = 73317;
    const body = {
      source: "healthconnect",
      records: [
        {
          kind: "restingHeartRate",
          id: "a",
          time: "2026-11-18T06:30:00Z",
          bpm: secret,
          [`${secret}`]: secret,
          note: secret,
        },
      ],
    };
    const result = validateWellnessUpload(body);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      const text = result.errors.join("\n");
      expect(text).not.toContain(String(secret));
      expect(text).toContain("/records/0/bpm must be <= 300");
      expect(text).toContain("must not have the property note");
    }
  });

  it(`refuses more than ${WELLNESS_MAX_RECORDS} records`, () => {
    const record = {
      kind: "steps",
      id: "x",
      start: "2026-11-18T07:00:00Z",
      end: "2026-11-18T08:00:00Z",
      count: 1,
    };
    const records = Array.from({ length: WELLNESS_MAX_RECORDS + 1 }, (_, i) => ({
      ...record,
      id: `s${i}`,
    }));
    expect(validateWellnessUpload({ source: "healthconnect", records }).ok).toBe(false);
    expect(
      validateWellnessUpload({
        source: "healthconnect",
        records: records.slice(0, WELLNESS_MAX_RECORDS),
      }).ok,
    ).toBe(true);
  });

  it("knows the eight kinds, and the schema defines exactly those", () => {
    const schema = JSON.parse(
      readFileSync(new URL("../schema/wellness-upload.schema.json", import.meta.url), "utf8"),
    ) as { properties: { records: { items: { oneOf: { $ref: string }[] } } } };
    const refs = schema.properties.records.items.oneOf.map((r) => r.$ref.replace("#/$defs/", ""));
    expect(refs).toEqual([...WELLNESS_RECORD_KINDS]);
  });
});

describe("instants", () => {
  it.each([
    ["2026-11-18T06:30:00Z", Date.UTC(2026, 10, 18, 6, 30)],
    ["2026-11-18T06:30:00.5Z", Date.UTC(2026, 10, 18, 6, 30, 0, 500)],
    ["2026-11-18T06:30:00.123456789Z", Date.UTC(2026, 10, 18, 6, 30, 0, 123)],
  ])("reads %s", (text, ms) => {
    expect(parseInstant(text)).toBe(ms);
  });

  it.each([
    "2026-02-30T06:30:00Z",
    "2026-11-18T24:00:00Z",
    "2026-11-18T06:60:00Z",
    "2026-11-18T06:30:00",
    "2026-11-18 06:30:00Z",
    "",
  ])("refuses %s", (text) => {
    expect(parseInstant(text)).toBeNull();
  });
});

describe("wellness upload response", () => {
  it.each(load("upload-response", "valid"))("accepts valid/%s", (_name, body) => {
    expect(validateWellnessUploadResponse(body)).toMatchObject({ ok: true });
  });

  it.each(load("upload-response", "invalid"))("rejects invalid/%s", (_name, body) => {
    expect(validateWellnessUploadResponse(body).ok).toBe(false);
  });
});

describe("wellness response", () => {
  it.each(load("wellness", "valid"))("accepts valid/%s", (_name, body) => {
    const result = validateWellnessResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("wellness", "invalid"))("rejects invalid/%s", (_name, body) => {
    expect(validateWellnessResponse(body).ok).toBe(false);
  });
});

describe("recovery response", () => {
  it("has a fixture for every state", () => {
    const states = new Set(
      load("recovery", "valid").map(([, body]) => (body as { state: string }).state),
    );
    expect([...states].sort()).toEqual(["caution", "learning", "noData", "ready", "rest"]);
  });

  it.each(load("recovery", "valid"))("accepts valid/%s", (_name, body) => {
    const result = validateRecoveryResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("recovery", "invalid"))("rejects invalid/%s", (_name, body) => {
    expect(validateRecoveryResponse(body).ok).toBe(false);
  });
});

describe("watch activity", () => {
  it.each(load("activity", "valid"))("accepts valid/%s", (_name, body) => {
    const result = validateActivity(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("activity", "invalid"))("rejects invalid/%s", (_name, body) => {
    expect(validateActivity(body).ok).toBe(false);
  });
});
