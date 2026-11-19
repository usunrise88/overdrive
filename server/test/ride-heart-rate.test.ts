import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { type RideRecord, validateRideDetailResponse, type Workout } from "@overdrive/contracts";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { insertRide } from "./helpers/synthetic-rides.ts";

// After a ride with a heart-rate step and two stall-guard events (spec §11.1, §11.2): what the agent and the companion are told.
// Everything here is synthetic.

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

const hrWorkout: Workout = {
  schema: 1,
  id: "icu-7001",
  name: "Base by heart rate",
  steps: [
    { type: "hr", duration: 300, target_bpm: 135, band: 4, power_min: 80, power_max: 220 },
    { type: "steady", duration: 60, target: 0.4 },
  ],
};

const RIDE_ID = "ride-1795000000000";

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "test", token: STATIC_TOKEN }] });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);

  const samples = Array.from({ length: 360 }, (_, i) => {
    const second = i + 1;
    const inHeartRateStep = second <= 300;
    return {
      timeOffsetSeconds: second,
      power: inHeartRateStep ? 150 : 100,
      heartRate: inHeartRateStep ? (second % 5 === 0 ? 142 : 134) : 120,
      cadence: 90,
      stepIndex: inHeartRateStep ? 0 : 1,
      distanceMeters: second * 8,
      ...(inHeartRateStep && second > 60 ? { targetHeartRate: 135 } : {}),
    };
  });
  const record: RideRecord = {
    schema: 1,
    ride: {
      id: RIDE_ID,
      startEpochMillis: 1_795_000_000_000,
      endEpochMillis: 1_795_000_360_000,
      workoutId: "icu-7001",
      ftp: 250,
    },
    samples,
    rrIntervals: [],
    laps: [
      { stepIndex: 0, startSeconds: 0, endSeconds: 300 },
      { stepIndex: 1, startSeconds: 300, endSeconds: 360 },
    ],
    summary: {
      durationSeconds: 360,
      movingSeconds: 360,
      distanceMeters: 2880,
      workKilojoules: 44,
    },
    events: [
      { type: "stall-guard", timeOffsetSeconds: 120, stepIndex: 0 },
      { type: "stall-guard", timeOffsetSeconds: 200, stepIndex: 0 },
    ],
  };
  await insertRide(db, record);
  await h.services.rides.saveWorkoutSnapshot(RIDE_ID, hrWorkout, h.clock.now());
});

afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});

describe("a ride with a heart-rate step and two stall-guard events", () => {
  it("tells the agent how long the heart rate was inside the band and how often the guard took hold", async () => {
    const { json, isError, text } = await callTool(client, "get_activity", { id: RIDE_ID });
    expect(isError, text).toBe(false);
    const result = json as {
      compliance: {
        available: boolean;
        steps: {
          type: string;
          plannedWatts: number | null;
          heartRate: { judgedSeconds: number; secondsInBand: number; inBandPercent: number } | null;
        }[];
      };
      stallGuard: {
        activations: number;
        steps: { step: number; activations: number }[];
        minutes: number[];
      };
    };
    expect(result.compliance.available).toBe(true);
    const step = result.compliance.steps[0];
    expect(step?.type).toBe("hr");
    expect(step?.plannedWatts).toBeNull();
    // Seconds 61 to 300 are judged (240); every fifth second the heart rate is 142, 7 bpm off a band of 4.
    expect(step?.heartRate).toMatchObject({
      judgedSeconds: 240,
      secondsInBand: 192,
      inBandPercent: 80,
    });
    expect(result.stallGuard).toEqual({
      activations: 2,
      steps: [{ step: 1, label: "Heart rate", activations: 2 }],
      minutes: [2, 3.3],
    });
  });

  it("serves the ride detail with a lap of the heart-rate kind, valid in the contract", async () => {
    const res = await h.app.inject({
      method: "GET",
      url: `/v1/rides/${RIDE_ID}`,
      headers: bearer(TABLET),
    });
    expect(res.statusCode).toBe(200);
    const detail = res.json();
    expect(validateRideDetailResponse(detail)).toMatchObject({ ok: true });
    expect(detail.laps[0]).toMatchObject({ type: "hr", label: "Heart rate", targetPower: null });
  });
});
