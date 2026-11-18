import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type Activity,
  addDaysToIsoDate,
  type Profile,
  type RideRecord,
  validateActivity,
  validateProfile,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
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
import { batch, exercise, get, heartRateSeconds, post, resting } from "./helpers/wellness-data.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    timezone: "Europe/Paris",
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
  await clearWellness(db);
  await clearRides(db);
  await db.exec("TRUNCATE profile");
  fake.reset();
  h.clock.set("2026-11-18T08:00:00Z");
});

const mcp = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const patchProfile = (body: unknown) =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });

const profile = async (): Promise<Profile> => {
  const res = await h.app.inject({ method: "GET", url: "/v1/profile", headers: bearer() });
  const body: unknown = res.json();
  const checked = validateProfile(body);
  expect(checked, JSON.stringify(body)).toMatchObject({ ok: true });
  return body as Profile;
};

const activity = async (id: string): Promise<Activity> => {
  const res = await get(h.app, `/v1/activities/${id}`);
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  expect(validateActivity(body), JSON.stringify(body)).toMatchObject({ ok: true });
  return body as Activity;
};

/** The evening of the 17th: a 42-minute VR session, 20:05 to 20:47 in Paris (19:05 to 19:47 UTC). */
const VR_START = "2026-11-17T19:05:00Z";
const VR_END = "2026-11-17T19:47:00Z";
const vrSession = (id = "vr-1", extra: Record<string, unknown> = {}) =>
  exercise(id, "other_workout", VR_START, VR_END, { title: "Synthetic VR session", ...extra });

/** 42 minutes at one sample a second. */
const vrSamples = (bpm: number) =>
  (heartRateSeconds("tmp", VR_START, 2519, 1, () => bpm) as { samples: unknown[] }).samples;

/** A ride on the tablet with a heart rate every second. */
function rideWithHeartRate(
  id: string,
  startMs: number,
  seconds: number,
  bpm: (i: number) => number,
): RideRecord {
  const samples = Array.from({ length: seconds }, (_, i) => ({
    timeOffsetSeconds: i + 1,
    power: 150,
    heartRate: bpm(i),
    cadence: 85,
    speedKmh: 25,
    distanceMeters: 7 * (i + 1),
    paused: false,
  }));
  const rates = samples.map((s) => s.heartRate);
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
      averageHeartRate: rates.reduce((a, b) => a + b, 0) / seconds,
      maxHeartRate: Math.max(...rates),
      workKilojoules: 0.15 * seconds,
    },
  };
}

async function storeRide(record: RideRecord): Promise<void> {
  const res = await putRecord(h.app, record.ride.id, record);
  expect(res.statusCode, res.body).toBe(201);
}

describe("a workout from the watch", () => {
  it("becomes an activity with its heart rate and how dense the samples are", async () => {
    const samples = vrSamples(140);
    const res = await post(h.app, batch(vrSession("vr-1", { heartRateSamples: samples })));
    expect(res.json()).toMatchObject({ accepted: 1, byType: { exerciseSession: { accepted: 1 } } });
    const a = await activity("watch-vr-1");
    expect(a).toMatchObject({
      id: "watch-vr-1",
      kind: "activity",
      source: "watch",
      type: "other_workout",
      name: "Synthetic VR session",
      start: "2026-11-17T19:05:00.000Z",
      end: "2026-11-17T19:47:00.000Z",
      // The local day in Paris.
      startDate: "2026-11-17",
      durationSeconds: 2520,
      averageHeartRate: 140,
      maxHeartRate: 140,
      countsTowardLoad: true,
      overlapsRideId: null,
      heartRate: { samples: 2520, medianGapSeconds: 1, maxGapSeconds: 1, coverage: 1 },
    });
    expect(a.series.length).toBeLessThanOrEqual(300);
    expect(a.series[0]).toEqual({ t: 0, heartRate: 140 });
    // Averaged into buckets of nine seconds: 2520 / 300 rounded up.
    expect(a.series[1]?.t).toBe(9);
    expect(a.loadNote).toMatch(/under-counted/);
  });

  it("names an activity the watch did not title after its type", async () => {
    await post(h.app, batch(exercise("w-1", "strength_training", VR_START, VR_END)));
    expect((await activity("watch-w-1")).name).toBe("Strength training");
  });

  it("has no load until a maximum and a threshold heart rate are known", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    const a = await activity("watch-vr-1");
    expect(a).toMatchObject({ load: null, loadApproximate: false, thresholdHr: null, maxHr: null });
  });

  it("works its load out from the owner's heart rates: 100 for an hour at threshold, here 32.7 for 42 minutes at 140", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    expect((await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 })).statusCode).toBe(
      200,
    );
    // No resting heart rate is known, so 60 is assumed: approximate.
    // x = (140 - 60) / 130, threshold x = 110 / 130: 100 x 0.7 h x y(0.6154) / y(0.8462) = 32.69.
    const guessed = await activity("watch-vr-1");
    expect(guessed).toMatchObject({
      load: 32.7,
      loadApproximate: true,
      thresholdHr: 170,
      maxHr: 190,
    });

    // Ten mornings of resting heart rate 60 in the weeks before: the same load, no longer a guess.
    const readings = Array.from({ length: 10 }, (_, i) =>
      resting(`rhr-${i}`, `${addDaysToIsoDate("2026-11-16", -i)}T06:00:00Z`, 60),
    );
    await post(h.app, batch(...readings));
    expect(await activity("watch-vr-1")).toMatchObject({ load: 32.7, loadApproximate: false });
  });

  it("flags the load approximate when the samples cover too little of the session", async () => {
    // Three minutes of samples in a 42-minute session.
    await post(
      h.app,
      batch(
        vrSession("vr-1", {
          heartRateSamples: heartRateSeconds("t", VR_START, 180, 1, () => 140).samples,
        }),
      ),
    );
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    await post(
      h.app,
      batch(
        ...Array.from({ length: 10 }, (_, i) =>
          resting(`rhr-${i}`, `${addDaysToIsoDate("2026-11-16", -i)}T06:00:00Z`, 60),
        ),
      ),
    );
    const a = await activity("watch-vr-1");
    expect(a.loadApproximate).toBe(true);
    expect(a.heartRate.coverage).toBeLessThan(0.8);
    expect(a.load).toBeGreaterThan(0);
  });

  it("uses the rides' estimate where the owner has set nothing, and says so", async () => {
    // A 30-minute ride: 5 minutes at 150 bpm, then 25 at 170. The best 20 minutes average 170: the threshold estimate is 95% of it.
    await storeRide(
      rideWithHeartRate("ride-1794996000000", 1794996000000, 1800, (i) => (i < 300 ? 150 : 170)),
    );
    const shown = await profile();
    expect(shown).toMatchObject({
      maxHeartRate: null,
      thresholdHeartRate: null,
      estimatedMaxHeartRate: 170,
      estimatedThresholdHeartRate: 162,
    });

    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    // Maximum 170 and threshold 162, resting heart rate assumed 60: 37.4, approximate.
    expect(await activity("watch-vr-1")).toMatchObject({
      load: 37.4,
      loadApproximate: true,
      thresholdHr: 162,
      maxHr: 170,
    });

    // The owner's own values win, the estimates stay as they were.
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    expect(await profile()).toMatchObject({
      maxHeartRate: 190,
      thresholdHeartRate: 170,
      estimatedMaxHeartRate: 170,
      estimatedThresholdHeartRate: 162,
    });
    expect(await activity("watch-vr-1")).toMatchObject({
      load: 32.7,
      thresholdHr: 170,
      maxHr: 190,
    });
  });

  it("has no estimate from a ride too short for 20 minutes, or without heart rate", async () => {
    await storeRide(rideWithHeartRate("ride-1794996000000", 1794996000000, 900, () => 165));
    expect(await profile()).toMatchObject({
      estimatedMaxHeartRate: 165,
      estimatedThresholdHeartRate: null,
    });
  });

  it("takes the heart rate from the watch's heart-rate records when the phone attached none, also when they arrive later", async () => {
    await post(h.app, batch(vrSession("vr-1")));
    expect(await activity("watch-vr-1")).toMatchObject({
      averageHeartRate: null,
      heartRate: { samples: 0, coverage: 0 },
      series: [],
    });
    // One sample every five seconds from a separate record.
    await post(
      h.app,
      batch(heartRateSeconds("hr-evening", VR_START, 2520, 5, (i) => 100 + (i % 2) * 20)),
    );
    const a = await activity("watch-vr-1");
    expect(a.heartRate).toMatchObject({ samples: 505, medianGapSeconds: 5, coverage: 1 });
    expect(a.averageHeartRate).toBeCloseTo(110, 0);
    expect(a.maxHeartRate).toBe(120);
  });

  it("is idempotent: the same session again is a duplicate and changes nothing", async () => {
    const records = batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) }));
    await post(h.app, records);
    const again = await post(h.app, records);
    expect(again.json()).toMatchObject({ accepted: 0, duplicates: 1 });
    expect((await db.query("SELECT 1 FROM activities")).rows).toHaveLength(1);
    expect((await db.query("SELECT 1 FROM activity_destinations")).rows).toHaveLength(1);
  });

  it("is 404 not_found for an unknown activity or an id that is not one", async () => {
    expectError(await get(h.app, "/v1/activities/watch-nope"), "not_found");
    expectError(await get(h.app, "/v1/activities/ride-1"), "not_found");
    expectError(await get(h.app, "/v1/activities/..%2Fetc"), "not_found");
  });

  it("keeps the activities out of the rides: the power curve and FTP read rides only", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    expect((await db.query("SELECT 1 FROM rides")).rows).toHaveLength(0);
    expect((await h.services.rides.list({ limit: 10, offset: 0 })).total).toBe(0);
  });
});

describe("the same ride on the watch and on the tablet is one ride", () => {
  const RIDE_START = Date.parse("2026-11-17T19:05:00Z");

  it("does not count a session that overlaps a ride, and says which ride", async () => {
    await storeRide(rideWithHeartRate("ride-a", RIDE_START, 2400, () => 150));
    await post(
      h.app,
      batch(
        exercise("bike-1", "biking_stationary", VR_START, VR_END, {
          heartRateSamples: vrSamples(150),
        }),
      ),
    );
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    const a = await activity("watch-bike-1");
    expect(a).toMatchObject({ countsTowardLoad: false, overlapsRideId: "ride-a", load: null });
    // Not in the load by day either.
    expect(await h.services.wellness.otherLoadByDay("2026-11-17", "2026-11-17")).toEqual([]);
  });

  it("does count one that only touches a ride at its end", async () => {
    // The ride ends at 19:10, the session runs from 19:05 to 19:47: five of 42 minutes.
    await storeRide(rideWithHeartRate("ride-a", RIDE_START - 20 * 60_000, 25 * 60, () => 150));
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    expect((await activity("watch-vr-1")).countsTowardLoad).toBe(true);
  });
});

describe("the load by day: the other half of fatigue and form", () => {
  it("adds the loads of a day's sessions, on the rider's local days, and leaves out what has none", async () => {
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    await post(
      h.app,
      batch(
        // Two on the 17th (Paris), 30 minutes at 150 and 42 at 140; the second ends just after midnight UTC.
        exercise("a", "other_workout", "2026-11-17T10:00:00Z", "2026-11-17T10:30:00Z", {
          heartRateSamples: heartRateSeconds("t", "2026-11-17T10:00:00Z", 1799, 1, () => 150)
            .samples,
        }),
        exercise("b", "other_workout", VR_START, VR_END, { heartRateSamples: vrSamples(140) }),
        // One on the 16th without samples: no load, so no entry.
        exercise("c", "other_workout", "2026-11-16T10:00:00Z", "2026-11-16T10:30:00Z"),
        // One at 23:30 UTC on the 16th is on the 17th in Paris.
        exercise("d", "other_workout", "2026-11-16T23:30:00Z", "2026-11-17T00:00:00Z", {
          heartRateSamples: heartRateSeconds("t", "2026-11-16T23:30:00Z", 1799, 1, () => 150)
            .samples,
        }),
      ),
    );
    const load = await h.services.wellness.otherLoadByDay("2026-11-15", "2026-11-18");
    // Rest 60 is assumed in all: 150 bpm for 30 minutes is 30.4 (three times, one is the one after 23:30), 140 for 42 minutes is 32.7.
    expect(load).toEqual([{ date: "2026-11-17", kind: "other", load: 30.4 + 32.7 + 30.4 }]);
    expect(await h.services.wellness.otherLoadByDay("2026-11-18", "2026-11-18")).toEqual([]);
  });
});

describe("the history of rides and watch activities", () => {
  beforeEach(async () => {
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    await storeRide(
      rideWithHeartRate("ride-newest", Date.parse("2026-11-18T06:00:00Z"), 600, () => 150),
    );
    await storeRide(
      rideWithHeartRate("ride-oldest", Date.parse("2026-11-10T06:00:00Z"), 600, () => 150),
    );
    await post(
      h.app,
      batch(
        exercise("w-new", "other_workout", "2026-11-17T19:05:00Z", "2026-11-17T19:47:00Z", {
          title: "Beat Saber",
          heartRateSamples: vrSamples(140),
        }),
        exercise("w-old", "running", "2026-11-12T07:00:00Z", "2026-11-12T07:40:00Z"),
      ),
    );
  });

  type Listed = {
    total: number;
    returned: number;
    hasMore: boolean;
    activities: { id: string; type: string; source: string; name?: string; load: number | null }[];
  };

  it("list_activities merges both, newest first, each with a type and a source", async () => {
    const { json, isError } = await mcp("list_activities", {
      from: "2026-11-01",
      to: "2026-11-18",
    });
    expect(isError).toBe(false);
    const listed = json as Listed;
    expect(listed.total).toBe(4);
    expect(listed.activities.map((a) => [a.id, a.type, a.source])).toEqual([
      ["ride-newest", "cycling", "tablet"],
      ["watch-w-new", "other_workout", "watch"],
      ["watch-w-old", "running", "watch"],
      ["ride-oldest", "cycling", "tablet"],
    ]);
    expect(listed.activities[1]).toMatchObject({
      name: "Beat Saber",
      load: 32.7,
      loadApproximate: true,
      countsTowardLoad: true,
    });
    // A ride keeps what list_rides tells.
    expect(listed.activities[0]).toMatchObject({
      averageHeartRate: 150,
      startTime: "2026-11-18T06:00:00.000Z",
    });
  });

  it("pages through the merge and narrows to one source", async () => {
    const first = (await mcp("list_activities", { from: "2026-11-01", to: "2026-11-18", limit: 3 }))
      .json as Listed;
    expect(first.activities.map((a) => a.id)).toEqual([
      "ride-newest",
      "watch-w-new",
      "watch-w-old",
    ]);
    expect(first.hasMore).toBe(true);
    const next = (
      await mcp("list_activities", { from: "2026-11-01", to: "2026-11-18", limit: 3, offset: 3 })
    ).json as Listed;
    expect(next.activities.map((a) => a.id)).toEqual(["ride-oldest"]);
    expect(next.hasMore).toBe(false);
    const watch = (
      await mcp("list_activities", { from: "2026-11-01", to: "2026-11-18", source: "watch" })
    ).json as Listed;
    expect(watch.activities.map((a) => a.id)).toEqual(["watch-w-new", "watch-w-old"]);
    const tablet = (
      await mcp("list_activities", { from: "2026-11-01", to: "2026-11-18", source: "tablet" })
    ).json as Listed;
    expect(tablet.total).toBe(2);
  });

  it("list_rides and get_ride still answer, as deprecated aliases", async () => {
    const rides = (await mcp("list_rides", { from: "2026-11-01", to: "2026-11-18" })).json as {
      total: number;
    };
    expect(rides.total).toBe(2);
    expect((await mcp("get_ride", { rideId: "ride-newest" })).isError).toBe(false);
  });

  it("get_activity gives a watch activity in detail, with the series on request", async () => {
    const plain = (await mcp("get_activity", { id: "watch-w-new" })).json as Record<
      string,
      unknown
    >;
    expect(plain).toMatchObject({
      type: "other_workout",
      source: "watch",
      name: "Beat Saber",
      durationSeconds: 2520,
      load: 32.7,
      loadApproximate: true,
      thresholdHr: 170,
      maxHr: 190,
      heartRate: { samples: 2520 },
    });
    expect(plain.series).toBeUndefined();
    const withSeries = (await mcp("get_activity", { id: "watch-w-new", series: true })).json as {
      series: unknown[];
    };
    expect(withSeries.series.length).toBeGreaterThan(10);
  });

  it("get_activity gives a ride as get_ride does, with the type", async () => {
    const ride = (await mcp("get_activity", { id: "ride-newest" })).json as Record<string, unknown>;
    const alias = (await mcp("get_ride", { rideId: "ride-newest" })).json as Record<
      string,
      unknown
    >;
    expect(ride).toEqual({ type: "cycling", source: "tablet", ...alias });
  });

  it("get_activity says in plain words when there is no such activity", async () => {
    for (const id of ["watch-nope", "ride-nope", "somewhere"]) {
      const { isError, text } = await mcp("get_activity", { id });
      expect(isError, id).toBe(true);
      expect(text, id).toMatch(/there is no|not an activity id/);
    }
  });
});

describe("forwarding to intervals.icu", () => {
  const forwarder = () => {
    const found = h.services.wellness.forwarder;
    if (found === null) throw new Error("no forwarder");
    return found;
  };

  const destination = async (id: string) =>
    (
      await db.query<{
        state: string;
        attempts: number;
        last_error: string | null;
        external_id: string | null;
      }>(
        "SELECT state, attempts, last_error, external_id FROM activity_destinations WHERE activity_id = $1",
        [id],
      )
    ).rows[0];

  it("sends a session as an activity of the matching type, once, and nothing goes to Strava", async () => {
    await post(
      h.app,
      batch(
        vrSession("vr-1", { heartRateSamples: vrSamples(140) }),
        exercise("run-1", "running", "2026-11-18T06:00:00Z", "2026-11-18T06:45:00Z"),
      ),
    );
    const summary = await forwarder().runOnce();
    expect(summary).toMatchObject({ claimed: 2, done: 2, failed: 0 });
    expect(fake.manualActivities).toHaveLength(2);
    const vr = fake.manualActivities.find((a) => a.external_id === "overdrive-watch-vr-1");
    expect(vr).toMatchObject({
      type: "Workout",
      name: "Synthetic VR session",
      // The athlete's wall clock: 20:05 in Paris.
      start_date_local: "2026-11-17T20:05:00",
      moving_time: 2520,
      average_heartrate: 140,
      max_heartrate: 140,
    });
    expect(
      fake.manualActivities.find((a) => a.external_id === "overdrive-watch-run-1"),
    ).toMatchObject({ type: "Run", start_date_local: "2026-11-18T07:00:00", moving_time: 2700 });
    expect(await destination("watch-vr-1")).toMatchObject({ state: "done", attempts: 1 });
    expect((await destination("watch-vr-1"))?.external_id).toMatch(/^i\d+$/);
    // Only intervals.icu is a destination; nothing for Strava.
    expect(
      (
        await db.query<{ destination: string }>(
          "SELECT DISTINCT destination FROM activity_destinations",
        )
      ).rows,
    ).toEqual([{ destination: "intervals.icu" }]);
    expect(h.services.strava).toBeNull();

    // A second pass has nothing to do, and the same session sent again does not queue a second send.
    expect(await forwarder().runOnce()).toMatchObject({ claimed: 0 });
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    expect(await forwarder().runOnce()).toMatchObject({ claimed: 0 });
    expect(fake.manualActivities).toHaveLength(2);
  });

  it("does not post twice when intervals.icu already has the activity under its external id", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(140) })));
    // It was posted, the answer was lost, and the row is still pending.
    fake.manualActivities.push({
      id: "i9",
      external_id: "overdrive-watch-vr-1",
      name: "Synthetic VR session",
    });
    await forwarder().runOnce();
    expect(fake.manualActivities).toHaveLength(1);
    expect(await destination("watch-vr-1")).toMatchObject({ state: "done", external_id: "i9" });
  });

  it("does not send history: a session older than a week when it arrives is skipped", async () => {
    await post(
      h.app,
      batch(exercise("old", "running", "2026-11-01T06:00:00Z", "2026-11-01T06:45:00Z")),
    );
    expect(await forwarder().runOnce()).toMatchObject({ claimed: 0 });
    expect(await destination("watch-old")).toMatchObject({
      state: "skipped",
      last_error: "older than 7 days when it was received",
    });
    expect(fake.manualActivities).toHaveLength(0);
  });

  it("does not send a session that overlaps a ride: the tablet sends the ride", async () => {
    await storeRide(rideWithHeartRate("ride-a", Date.parse(VR_START), 2400, () => 150));
    await post(h.app, batch(exercise("bike-1", "biking_stationary", VR_START, VR_END)));
    expect(await forwarder().runOnce()).toMatchObject({ claimed: 1, skipped: 1, done: 0 });
    expect(await destination("watch-bike-1")).toMatchObject({
      state: "skipped",
      last_error: "the watch recorded a ride that the tablet sends",
    });
    expect(fake.manualActivities).toHaveLength(0);
  });

  it("tries again after a failure, and never says what the heart rates were", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(143) })));
    fake.failNext(1, 500, { path: "/activities/manual" });
    expect(await forwarder().runOnce()).toMatchObject({ retry: 1, done: 0 });
    const waiting = await destination("watch-vr-1");
    expect(waiting).toMatchObject({ state: "pending", attempts: 1 });
    expect(waiting?.last_error).toMatch(/HTTP 500/);
    // Due again after a minute.
    h.clock.advance(61_000);
    expect(await forwarder().runOnce()).toMatchObject({ done: 1 });
    expect(await destination("watch-vr-1")).toMatchObject({ state: "done", attempts: 2 });
  });

  it("gives up on a refusal, quoting the status and not the heart rates sent", async () => {
    await post(h.app, batch(vrSession("vr-1", { heartRateSamples: vrSamples(143) })));
    fake.failNext(1, 422, {
      path: "/activities/manual",
      body: JSON.stringify({ error: "average_heartrate 143 is not allowed" }),
    });
    expect(await forwarder().runOnce()).toMatchObject({ failed: 1 });
    const row = await destination("watch-vr-1");
    expect(row).toMatchObject({ state: "failed" });
    expect(row?.last_error).toBe("intervals.icu refused the activity (HTTP 422)");
    expect(h.logging.text()).not.toContain("143");
  });

  it("queues the sessions even with no key, and sends nothing: the feature is off", async () => {
    const quiet = await startMcpHarness(db, fake, { configured: false, timezone: "Europe/Paris" });
    try {
      expect(quiet.services.wellness.forwarder).toBeNull();
      expect(quiet.services.wellness.puller).toBeNull();
      await post(quiet.app, batch(vrSession("vr-1")));
      expect(
        (await db.query("SELECT 1 FROM activity_destinations WHERE state = 'pending'")).rows,
      ).toHaveLength(1);
      expect(fake.manualActivities).toHaveLength(0);
    } finally {
      await quiet.close();
    }
  });

  it("maps the exercise types to intervals.icu sports", async () => {
    const { intervalsTypeOf } = await import("../src/wellness/forward.ts");
    expect(
      Object.fromEntries(
        [
          "biking",
          "biking_stationary",
          "running",
          "running_treadmill",
          "walking",
          "hiking",
          "swimming_pool",
          "rowing",
          "yoga",
          "strength_training",
          "other_workout",
          "dancing",
          "elliptical",
        ].map((type) => [type, intervalsTypeOf(type)]),
      ),
    ).toEqual({
      biking: "Ride",
      biking_stationary: "VirtualRide",
      running: "Run",
      running_treadmill: "Run",
      walking: "Walk",
      hiking: "Hike",
      swimming_pool: "Swim",
      rowing: "Rowing",
      yoga: "Yoga",
      strength_training: "WeightTraining",
      other_workout: "Workout",
      dancing: "Workout",
      elliptical: "Workout",
    });
  });
});
