import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { Profile, RideRecord, RideSample } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import {
  estimatesFrom,
  heartRatesOfRide,
  MIN_WINDOW_COVERAGE,
  THRESHOLD_SHARE,
  THRESHOLD_WINDOW_SECONDS,
} from "../src/wellness/heart-rate-index.ts";
import { bearer, expectError, putRecord } from "./helpers/app.ts";
import { clearRides, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/** A ride record from a heart rate for each second: `null` is a second with no reading. */
function ride(
  id: string,
  startMs: number,
  rates: (number | null)[],
  paused: (i: number) => boolean = () => false,
): RideRecord {
  const samples: RideSample[] = rates.map((bpm, i) => ({
    timeOffsetSeconds: i + 1,
    power: 150,
    ...(bpm === null ? {} : { heartRate: bpm }),
    paused: paused(i),
  }));
  return {
    schema: 1,
    ride: {
      id,
      startEpochMillis: startMs,
      endEpochMillis: startMs + rates.length * 1000,
      ftp: 220,
    },
    samples,
    rrIntervals: [],
    laps: [{ startSeconds: 0, endSeconds: rates.length, averagePower: 150 }],
    summary: {
      durationSeconds: rates.length,
      movingSeconds: rates.length,
      distanceMeters: 0,
      workKilojoules: 0,
    },
  };
}

const steady = (seconds: number, bpm: number): number[] =>
  Array.from({ length: seconds }, () => bpm);

describe("the heart rates a ride says", () => {
  it("has the highest heart rate and the best 20-minute average", () => {
    // Ten minutes at 140, then thirty at 165: the best 20 minutes are all in the second part.
    const rates = [...steady(600, 140), ...steady(1800, 165)];
    expect(heartRatesOfRide(ride("r", 0, rates))).toEqual({ maxHr: 165, best20MinHr: 165 });
    // A ride whose hardest 20 minutes are spread: 20 minutes at 150 inside a ride of 150 and 170 in turn.
    const mixed = [...steady(300, 120), ...steady(1200, 160), ...steady(300, 120)];
    expect(heartRatesOfRide(ride("r", 0, mixed)).best20MinHr).toBe(160);
  });

  it("has no 20-minute figure for a ride under 20 minutes", () => {
    expect(heartRatesOfRide(ride("r", 0, steady(THRESHOLD_WINDOW_SECONDS - 1, 170)))).toEqual({
      maxHr: 170,
      best20MinHr: null,
    });
    expect(heartRatesOfRide(ride("r", 0, steady(THRESHOLD_WINDOW_SECONDS, 170))).best20MinHr).toBe(
      170,
    );
  });

  it("needs 90% of the seconds of a window measured", () => {
    const measured = Math.ceil(MIN_WINDOW_COVERAGE * THRESHOLD_WINDOW_SECONDS);
    const sparse = (gaps: number) => [
      ...steady(THRESHOLD_WINDOW_SECONDS - gaps, 170),
      ...Array<null>(gaps).fill(null),
    ];
    expect(measured).toBe(1080);
    expect(heartRatesOfRide(ride("r", 0, sparse(120))).best20MinHr).toBe(170);
    expect(heartRatesOfRide(ride("r", 0, sparse(121))).best20MinHr).toBeNull();
  });

  it("does not count a paused second or a strap glitch", () => {
    // 1200 s at 150, 600 of them paused (and reading 180): half is not enough; and a spike of 250 is not a maximum.
    const rates = [...steady(600, 150), ...steady(600, 180)];
    const record = ride("r", 0, rates, (i) => i >= 600);
    expect(heartRatesOfRide(record)).toEqual({ maxHr: 150, best20MinHr: null });
    const spike = [...steady(1500, 150), 250, ...steady(100, 150)];
    expect(heartRatesOfRide(ride("r", 0, spike)).maxHr).toBe(150);
  });

  it("has nothing from a ride without heart rate", () => {
    expect(heartRatesOfRide(ride("r", 0, Array<null>(1500).fill(null)))).toEqual({
      maxHr: null,
      best20MinHr: null,
    });
  });

  it("turns the best of every ride into estimates a profile can hold", () => {
    expect(THRESHOLD_SHARE).toBe(0.95);
    expect(estimatesFrom(186, 170)).toEqual({ max: 186, threshold: 162 });
    // 95% of 170 is 161.5: rounded up.
    expect(estimatesFrom(null, null)).toEqual({ max: null, threshold: null });
    // Out of what the profile holds: a maximum under 100 (an easy ride), a threshold under 80 or over 220, a maximum over 230.
    expect(estimatesFrom(95, 82)).toEqual({ max: null, threshold: null });
    expect(estimatesFrom(240, 240)).toEqual({ max: null, threshold: null });
    expect(estimatesFrom(190, 84.2)).toEqual({ max: 190, threshold: 80 });
  });
});

describe("the heart rates in the profile", () => {
  let db: Db;
  let fake: FakeIntervals;
  let h: McpHarness;
  let client: Client;

  beforeAll(async () => {
    db = await openMigratedTestDb();
    fake = await startFakeIntervals();
    h = await startMcpHarness(db, fake, {
      configured: false,
      staticTokens: [{ name: "test", token: STATIC_TOKEN }],
    });
    client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
  });

  afterAll(async () => {
    await client.close();
    await h.close();
    await fake.close();
    await db.close();
  });

  beforeEach(async () => {
    await clearRides(db);
    await clearWellness(db);
    await db.exec("TRUNCATE profile");
  });

  const patch = (body: unknown) =>
    h.app.inject({
      method: "PATCH",
      url: "/v1/profile",
      headers: { "content-type": "application/json", ...bearer() },
      payload: JSON.stringify(body),
    });
  const get = async () =>
    (
      await h.app.inject({ method: "GET", url: "/v1/profile", headers: bearer() })
    ).json() as Profile;

  it("shows all four keys, null, to a rider who has set nothing and has no rides", async () => {
    expect(await get()).toMatchObject({
      maxHeartRate: null,
      thresholdHeartRate: null,
      estimatedMaxHeartRate: null,
      estimatedThresholdHeartRate: null,
    });
  });

  it("is patched, and a patch that makes the threshold meet the maximum is refused whole", async () => {
    expect((await patch({ maxHeartRate: 186, thresholdHeartRate: 166 })).statusCode).toBe(200);
    expect(await get()).toMatchObject({ maxHeartRate: 186, thresholdHeartRate: 166 });
    expectError(await patch({ thresholdHeartRate: 190 }), "invalid_profile");
    expectError(await patch({ maxHeartRate: 160 }), "invalid_profile");
    expectError(await patch({ estimatedMaxHeartRate: 190 }), "invalid_profile");
    expect(await get()).toMatchObject({ maxHeartRate: 186, thresholdHeartRate: 166 });
    expect((await patch({ thresholdHeartRate: null })).statusCode).toBe(200);
    expect(await get()).toMatchObject({ maxHeartRate: 186, thresholdHeartRate: null });
  });

  it("announces a change of the heart rates like any other part of the profile", async () => {
    const events: string[][] = [];
    h.services.bus.on("profile-changed", (event) => {
      events.push(event.fields);
    });
    await patch({ maxHeartRate: 186 });
    await patch({ maxHeartRate: 186 });
    await patch({ thresholdHeartRate: 166, ftp: 250 });
    expect(events).toEqual([["maxHeartRate"], ["ftp", "thresholdHeartRate"]]);
  });

  it("works its estimates out from the rides, and indexes each ride once", async () => {
    const record = ride("ride-1794996000000", 1794996000000, [
      ...steady(300, 150),
      ...steady(1500, 170),
    ]);
    expect((await putRecord(h.app, record.ride.id, record)).statusCode).toBe(201);
    const shown = await get();
    expect(shown).toMatchObject({ estimatedMaxHeartRate: 170, estimatedThresholdHeartRate: 162 });
    const rows = await db.query<{ ride_id: string; max_hr: number; best_20min_hr: number }>(
      "SELECT * FROM ride_heart_rate",
    );
    expect(rows.rows).toHaveLength(1);
    expect(rows.rows[0]).toMatchObject({ ride_id: "ride-1794996000000", max_hr: 170 });
    // A second ride is indexed on the next read; the first is not read again.
    const harder = ride("ride-1795082400000", 1795082400000, steady(1300, 180));
    await putRecord(h.app, harder.ride.id, harder);
    expect(await get()).toMatchObject({
      estimatedMaxHeartRate: 180,
      estimatedThresholdHeartRate: 171,
    });
    expect((await db.query("SELECT 1 FROM ride_heart_rate")).rows).toHaveLength(2);
  });

  it("is in get_profile and update_profile of the MCP", async () => {
    const record = ride("ride-1794996000000", 1794996000000, steady(1500, 170));
    await putRecord(h.app, record.ride.id, record);
    const read = (await callTool(client, "get_profile")).json as Record<string, unknown>;
    expect(read).toMatchObject({
      maxHeartRate: null,
      thresholdHeartRate: null,
      estimatedMaxHeartRate: 170,
      estimatedThresholdHeartRate: 162,
    });
    const updated = (
      await callTool(client, "update_profile", { maxHeartRate: 188, thresholdHeartRate: 168 })
    ).json as Record<string, unknown>;
    expect(updated).toMatchObject({
      changed: ["maxHeartRate", "thresholdHeartRate"],
      maxHeartRate: 188,
      thresholdHeartRate: 168,
      estimatedMaxHeartRate: 170,
    });
    const refused = await callTool(client, "update_profile", {
      maxHeartRate: 150,
      thresholdHeartRate: 168,
    });
    expect(refused.isError).toBe(true);
    expect(refused.text).toMatch(/profile does not validate/);
  });
});
