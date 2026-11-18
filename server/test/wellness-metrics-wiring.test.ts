import {
  type RideRecord,
  validateMetricsResponse,
  validateRidesResponse,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, putRecord } from "./helpers/app.ts";
import { clearRides, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import { batch, exercise, get, heartRateSeconds, post } from "./helpers/wellness-data.ts";

/** The watch's activities feed the training metrics (stage P3-1) through the hooks it offers: load as the `other` kind, rows in the rides list. */

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = startHarness(db, fake, {
    now: "2026-11-18T08:00:00Z",
    timezone: "Europe/Paris",
    configured: false,
  });
});

afterAll(async () => {
  await h.app.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearWellness(db);
  await clearRides(db);
  await db.exec("TRUNCATE profile, ride_metrics, ftp_suggestions, ftp_history");
  await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
});

const patchProfile = (body: unknown) =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });

const VR_START = "2026-11-17T19:05:00Z";
const VR_END = "2026-11-17T19:47:00Z";
const vrSession = (id: string, bpm = 140) =>
  exercise(id, "other_workout", VR_START, VR_END, {
    title: "Synthetic VR session",
    heartRateSamples: heartRateSeconds("t", VR_START, 2519, 1, () => bpm).samples,
  });

function rideWithHeartRate(id: string, startMs: number, seconds: number, bpm: number): RideRecord {
  const samples = Array.from({ length: seconds }, (_, i) => ({
    timeOffsetSeconds: i + 1,
    power: 150,
    heartRate: bpm,
    cadence: 85,
    speedKmh: 25,
    distanceMeters: 7 * (i + 1),
    paused: false,
  }));
  return {
    schema: 1,
    ride: {
      id,
      startEpochMillis: startMs,
      endEpochMillis: startMs + seconds * 1000,
      ftp: 220,
      devices: ["Test trainer"],
    },
    samples,
    rrIntervals: [],
    laps: [{ startSeconds: 0, endSeconds: seconds, averagePower: 150 }],
    summary: {
      durationSeconds: seconds,
      movingSeconds: seconds,
      distanceMeters: 7 * seconds,
      averagePower: 150,
      maxPower: 150,
      averageHeartRate: bpm,
      maxHeartRate: bpm,
      workKilojoules: 0.15 * seconds,
    },
  };
}

async function storeRide(record: RideRecord): Promise<void> {
  expect((await putRecord(h.app, record.ride.id, record)).statusCode).toBe(201);
}

interface Metrics {
  daily: { date: string; load: number; loadCycling: number; loadOther: number; ctl: number }[];
  weekly: { otherLoad: number; rideLoad: number; activities: number; rides: number }[];
  powerCurve: { allTime: unknown[] };
}

interface Rides {
  hasMore: boolean;
  nextBefore: string | null;
  items: {
    id: string;
    kind: string;
    sport: string | null;
    load: number | null;
    loadApproximate: boolean;
    averagePower: number | null;
    averageHeartRate: number | null;
    name: string;
  }[];
}

const metrics = async (from: string, to: string): Promise<Metrics> => {
  const res = await get(h.app, `/v1/metrics?from=${from}&to=${to}`);
  expect(res.statusCode, res.body).toBe(200);
  expect(validateMetricsResponse(res.json())).toMatchObject({ ok: true });
  return res.json() as Metrics;
};

const rides = async (query: string): Promise<Rides> => {
  const res = await get(h.app, `/v1/rides?${query}`);
  expect(res.statusCode, res.body).toBe(200);
  expect(
    validateRidesResponse(res.json()),
    JSON.stringify(validateRidesResponse(res.json())),
  ).toMatchObject({ ok: true });
  return res.json() as Rides;
};

describe("the load of the watch's activities in the training metrics", () => {
  it("counts as the other kind of load, never as a ride's, and not at all in the power curve", async () => {
    await post(h.app, batch(vrSession("vr-1")));
    const answer = await metrics("2026-11-16", "2026-11-18");
    const day = answer.daily.find((d) => d.date === "2026-11-17");
    expect(day).toMatchObject({ load: 32.7, loadCycling: 0, loadOther: 32.7 });
    expect(day?.ctl).toBeGreaterThan(0);
    expect(answer.weekly[0]).toMatchObject({
      otherLoad: 32.7,
      rideLoad: 0,
      activities: 1,
      rides: 0,
    });
    expect(answer.powerCurve.allTime).toEqual([]);
  });

  it("follows the heart rates in force: a lower threshold makes the same session worth more", async () => {
    await post(h.app, batch(vrSession("vr-1")));
    await patchProfile({ thresholdHeartRate: 160 });
    expect((await metrics("2026-11-17", "2026-11-17")).daily[0]?.loadOther).toBeGreaterThan(32.7);
  });

  it("is added to a ride's load on the same day, each in its own part", async () => {
    await storeRide(rideWithHeartRate("ride-1", Date.parse("2026-11-17T09:00:00Z"), 1800, 150));
    await post(h.app, batch(vrSession("vr-1")));
    const day = (await metrics("2026-11-17", "2026-11-17")).daily[0];
    expect(day?.loadOther).toBe(32.7);
    expect(day?.load).toBeCloseTo((day?.loadCycling ?? 0) + 32.7, 1);
  });

  it("does not count a session that is a ride recorded twice", async () => {
    await storeRide(rideWithHeartRate("ride-a", Date.parse(VR_START), 2400, 150));
    await post(
      h.app,
      batch(
        exercise("bike-1", "biking_stationary", VR_START, VR_END, {
          heartRateSamples: heartRateSeconds("t", VR_START, 2519, 1, () => 150).samples,
        }),
      ),
    );
    expect((await metrics("2026-11-17", "2026-11-17")).daily[0]?.loadOther).toBe(0);
  });
});

describe("the rides list of the companion", () => {
  it("has the watch's activities next to the rides, newest first, marked as activities", async () => {
    await storeRide(rideWithHeartRate("ride-newest", Date.parse("2026-11-18T06:00:00Z"), 600, 150));
    await post(
      h.app,
      batch(
        vrSession("vr-1"),
        exercise("w-2", "running", "2026-11-16T07:00:00Z", "2026-11-16T07:40:00Z"),
      ),
    );
    const list = await rides("limit=10");
    expect(list.items.map((i) => [i.id, i.kind, i.sport])).toEqual([
      ["ride-newest", "ride", null],
      ["watch-vr-1", "activity", "other_workout"],
      ["watch-w-2", "activity", "running"],
    ]);
    expect(list.items[1]).toMatchObject({
      name: "Synthetic VR session",
      load: 32.7,
      loadApproximate: true,
      averagePower: null,
      averageHeartRate: 140,
    });
  });

  it("leaves a session that is a ride recorded twice to the ride", async () => {
    await storeRide(rideWithHeartRate("ride-a", Date.parse(VR_START), 2400, 150));
    await post(h.app, batch(exercise("bike-1", "biking_stationary", VR_START, VR_END)));
    expect((await rides("limit=10")).items.map((i) => i.id)).toEqual(["ride-a"]);
  });

  it("pages through rides and activities together", async () => {
    await post(
      h.app,
      batch(
        exercise("a", "running", "2026-11-12T07:00:00Z", "2026-11-12T07:40:00Z"),
        exercise("b", "running", "2026-11-13T07:00:00Z", "2026-11-13T07:40:00Z"),
        exercise("c", "running", "2026-11-14T07:00:00Z", "2026-11-14T07:40:00Z"),
      ),
    );
    const first = await rides("limit=2");
    expect(first.items.map((i) => i.id)).toEqual(["watch-c", "watch-b"]);
    expect(first.hasMore).toBe(true);
    const next = await rides(`limit=2&before=${encodeURIComponent(first.nextBefore ?? "")}`);
    expect(next.items.map((i) => i.id)).toEqual(["watch-a"]);
    expect(next.hasMore).toBe(false);
  });
});
