import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { type RepeatDetailResponse, type RepeatsResponse, workoutHash } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { at, attempt, DAYS, SESSION, SESSION_RENAMED, store } from "./helpers/repeats-data.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/** The MCP side of the repeat comparison (spec §11.7, §8.4): `list_repeats`, `get_repeat` and `repeatOf` in `get_ride`. */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
    now: "2026-11-18T08:00:00Z",
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.services.metrics.whenIdle();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await h.services.metrics.whenIdle();
  await clearRides(db);
  await db.exec("TRUNCATE ride_metrics, ride_workouts, ride_repeat_stats, profile");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const RATES = [
  [150, 120, 156],
  [148, 118, 154],
  [147, 117, 153],
  [145, 115, 151],
] as const;

async function history(): Promise<void> {
  const workouts = [SESSION, SESSION_RENAMED, SESSION, SESSION_RENAMED];
  for (const [i, rates] of RATES.entries()) {
    await store(
      db,
      h.services.rides,
      attempt({ id: `ride-${i + 1}`, start: at(DAYS[i] as string), heartRates: rates }),
      workouts[i],
    );
  }
}

describe("list_repeats and get_repeat", () => {
  it("are read tools described with what the figures mean and the standing caution", async () => {
    const { tools } = await client.listTools();
    for (const name of ["list_repeats", "get_repeat"]) {
      const tool = tools.find((t) => t.name === name);
      expect(tool?.annotations?.readOnlyHint, name).toBe(true);
      expect(tool?.description, name).toMatch(/heat, sleep and caffeine/);
      expect(tool?.description, name).toMatch(/computed by code/);
    }
    expect(tools.find((t) => t.name === "list_repeats")?.description).toMatch(/renamed copy/);
    expect(tools.find((t) => t.name === "get_repeat")?.description).toMatch(/within 3%/);
  });

  it("list_repeats gives the sessions ridden more than once with the latest against the previous", async () => {
    await history();
    await store(
      db,
      h.services.rides,
      attempt({ id: "ride-9", start: at("2026-11-12"), workoutId: "icu-free" }),
      null,
    );
    const { json, isError } = await call("list_repeats");
    expect(isError).toBe(false);
    const body = json as { count: number; repeats: RepeatsResponse["repeats"] };
    expect(body.count).toBe(1);
    expect(body.repeats[0]).toMatchObject({
      hash: workoutHash(SESSION),
      attempts: 4,
      latest: { rideId: "ride-4" },
      previous: { rideId: "ride-3" },
      comparison: { heartRateAtPower: { latest: 141.4, previous: 143.4, difference: -2 } },
    });
  });

  it("get_repeat gives every attempt with the trend, and fails in words for a hash that is not a session", async () => {
    await history();
    const { json, isError } = await call("get_repeat", { hash: workoutHash(SESSION) });
    expect(isError).toBe(false);
    const body = json as RepeatDetailResponse;
    expect(body.attempts.map((a) => a.heartRateAtPower.value)).toEqual([
      146.4, 144.4, 143.4, 141.4,
    ]);
    expect(body.trends.heartRateAtPower).toMatchObject({
      direction: "improving",
      slopePerAttempt: -1.6,
      change: -4.8,
    });

    const missing = await call("get_repeat", { hash: "0123456789abcdef" });
    expect(missing.isError).toBe(true);
    expect(missing.text).toMatch(
      /there is no session 0123456789abcdef that has been ridden at least twice/,
    );
    const bad = await call("get_repeat", { hash: "NOPE" });
    expect(bad.isError).toBe(true);
    expect(bad.text).toMatch(/is not a session hash/);
  });
});

describe("get_ride: repeatOf", () => {
  const repeatOf = async (id: string) => {
    const { json, isError } = await call("get_ride", { rideId: id });
    expect(isError).toBe(false);
    return (json as { repeatOf: unknown }).repeatOf;
  };

  it("says which attempt the ride is and how its heart rate at the same power compares with the attempt before", async () => {
    await history();
    expect(await repeatOf("ride-3")).toEqual({
      hash: workoutHash(SESSION),
      attemptNumber: 3,
      attempts: 4,
      previous: {
        rideId: "ride-2",
        date: DAYS[1],
        // 143.4 for this ride, 144.4 for the one before.
        heartRateAtSamePower: { thisRide: 143.4, previous: 144.4, difference: -1, reason: null },
      },
      note: expect.stringMatching(/heat, sleep and caffeine/),
    });
  });

  it("has no previous for the first attempt, and null for a ride with no workout or a session ridden once", async () => {
    await history();
    expect(await repeatOf("ride-1")).toMatchObject({
      attemptNumber: 1,
      attempts: 4,
      previous: null,
    });
    await insertRide(
      db,
      syntheticRide({
        id: "ride-free",
        startEpochMillis: Date.parse(at("2026-11-12")),
        seconds: 600,
      }),
    );
    expect(await repeatOf("ride-free")).toBeNull();
    await clearRides(db);
    await db.exec("TRUNCATE ride_workouts");
    await store(db, h.services.rides, attempt({ id: "ride-1", start: at(DAYS[0]) }));
    expect(await repeatOf("ride-1")).toBeNull();
  });

  it("says why there is no heart-rate difference when this ride has no figure", async () => {
    await store(db, h.services.rides, attempt({ id: "ride-1", start: at(DAYS[0]) }));
    await store(
      db,
      h.services.rides,
      attempt({ id: "ride-2", start: at(DAYS[1]), heartRate: () => null }),
    );
    const result = (await repeatOf("ride-2")) as {
      previous: { heartRateAtSamePower: { reason: string; difference: number | null } };
    };
    expect(result.previous.heartRateAtSamePower.difference).toBeNull();
    expect(result.previous.heartRateAtSamePower.reason).toBe(
      "this ride has none: no attempt has a heart rate at the target power in any step",
    );
  });

  it("is described in get_ride's description", async () => {
    const { tools } = await client.listTools();
    const text = tools.find((t) => t.name === "get_ride")?.description ?? "";
    expect(text).toMatch(/repeatOf/);
    expect(text).toMatch(/heat, sleep and caffeine/);
  });
});
