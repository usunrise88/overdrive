import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { type RideRecord, rideRecordConsistencyErrors, validateRideRecord } from "../src/index.ts";

const fixtures = new URL("../fixtures/rides/", import.meta.url).pathname;

function load(dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

function valid(name: string): RideRecord {
  const result = validateRideRecord(
    JSON.parse(readFileSync(join(fixtures, "valid", name), "utf8")),
  );
  if (!result.ok) throw new Error(`${name}: ${result.errors.join("; ")}`);
  return result.value;
}

describe("ride record schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("valid").length).toBeGreaterThanOrEqual(5);
    expect(load("invalid").length).toBeGreaterThanOrEqual(10);
  });

  it.each(load("valid"))("accepts valid/%s", (_name, record) => {
    const result = validateRideRecord(record);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("invalid"))("rejects invalid/%s", (_name, record) => {
    const result = validateRideRecord(record);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.errors.length).toBeGreaterThan(0);
    }
  });

  it("reads explicit nulls as the same ride as absent keys", () => {
    const withNulls = valid("explicit-nulls.json");
    const minimal = valid("minimal-free-ride.json");
    expect(withNulls.samples.map((s) => s.power)).toEqual(minimal.samples.map((s) => s.power));
    expect(withNulls.samples[0]?.heartRate ?? null).toBeNull();
  });
});

describe("ride record fixtures", () => {
  it("the synthetic ride is a coherent five minutes", () => {
    const ride = valid("synthetic-5min.json");
    expect(ride.samples).toHaveLength(300);
    expect(ride.summary.durationSeconds).toBe(300);
    expect(ride.laps.map((lap) => lap.stepIndex)).toEqual([0, 1, 2, 3]);
    expect(ride.samples.some((s) => s.paused === true)).toBe(true);
    expect(ride.samples.some((s) => s.heartRate === undefined)).toBe(true);
    expect(ride.rrIntervals.length).toBeGreaterThan(100);
    expect(rideRecordConsistencyErrors(ride)).toEqual([]);
  });

  it("the golden ride is the three seconds of the FIT worked example", () => {
    const ride = valid("fit-golden-3s.json");
    expect(ride.samples.map((s) => s.power)).toEqual([200, 210, 220]);
    expect(ride.samples.map((s) => s.heartRate)).toEqual([120, 121, 122]);
    expect(ride.samples.map((s) => s.cadence)).toEqual([90, 91, 92]);
    expect(ride.samples.map((s) => s.distanceMeters)).toEqual([0, 8, 16.1]);
    expect(ride.ride.startEpochMillis).toBe(Date.UTC(2026, 10, 18, 10, 0, 0));
  });

  it("an empty ride is valid and has no laps", () => {
    const ride = valid("empty-ride.json");
    expect(ride.samples).toEqual([]);
    expect(ride.laps).toEqual([]);
  });
});

describe("ride record consistency", () => {
  const base = (): RideRecord => structuredClone(valid("minimal-free-ride.json"));

  it("accepts a record that tiles", () => {
    expect(rideRecordConsistencyErrors(base())).toEqual([]);
  });

  it("reports samples out of order", () => {
    const record = base();
    record.samples.reverse();
    expect(rideRecordConsistencyErrors(record)[0]).toMatch(/\/samples\/0 is second 3/);
  });

  it("reports laps that leave a gap or stop early", () => {
    const gap = base();
    gap.laps = [
      { startSeconds: 0, endSeconds: 1 },
      { startSeconds: 2, endSeconds: 3 },
    ];
    expect(rideRecordConsistencyErrors(gap)[0]).toMatch(/\/laps\/1 starts at 2/);
    const early = base();
    early.laps = [{ startSeconds: 0, endSeconds: 2 }];
    expect(rideRecordConsistencyErrors(early)[0]).toMatch(/last lap ends at 2/);
  });

  it("reports a summary of another length", () => {
    const record = base();
    record.summary.durationSeconds = 9;
    expect(rideRecordConsistencyErrors(record)[0]).toMatch(/durationSeconds is 9/);
  });
});
