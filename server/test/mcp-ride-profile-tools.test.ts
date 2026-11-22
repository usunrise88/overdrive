import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  defaultProfile,
  type Profile,
  powerZoneRanges,
  type RideRecord,
  validateProfile,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import type { ProfileChangedEvent } from "../src/events.ts";
import { rideStatistics } from "../src/ride-stats.ts";
import { asRide, bearer, fitFixture, putFit, putRecord, rideFixture } from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;
let profileChanges: ProfileChangedEvent[];

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "test", token: STATIC_TOKEN }] });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
  h.services.bus.on("profile-changed", (event) => {
    profileChanges.push(event);
  });
});

afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearRides(db);
  await clearPlan(db);
  await db.exec("TRUNCATE profile, ride_workouts");
  fake.reset();
  profileChanges = [];
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const NOW = Date.parse("2026-11-18T08:00:00Z");
const HOUR = 3_600_000;

/** Stores a ride through the same intake the tablet uses. */
async function store(record: RideRecord, withFit = true): Promise<void> {
  const res = await putRecord(h.app, record.ride.id, record);
  expect(res.statusCode, res.body).toBe(201);
  if (withFit)
    expect((await putFit(h.app, record.ride.id, fitFixture("minimal.fit"))).statusCode).toBe(201);
}

/** The synthetic five-minute ride (60 s at 120 W, 90 s at 220 W, 60 s at 125 W, 90 s free) as another ride. */
function synthetic(
  id: string,
  startedAt: number,
  workoutId: string | null = "icu-5001",
): RideRecord {
  const record = asRide(rideFixture("synthetic-5min.json"), id, startedAt);
  if (workoutId === null) delete record.ride.workoutId;
  else record.ride.workoutId = workoutId;
  return record;
}

/** The workout the synthetic ride followed, in the calendar and in the server's cache. */
async function planSyntheticWorkout(date = "2026-11-18"): Promise<void> {
  fake.addEvent(
    plannedEvent(date, "Synthetic steps", [
      steady(60, 48, "Easy"),
      steady(90, 88, "Hard"),
      steady(60, 50, "Easy again"),
      { duration: 90, freeride: true, text: "Free" },
    ]),
  );
  expect((await h.services.refresher?.refresh())?.ok).toBe(true);
}

describe("get_profile and update_profile", () => {
  it("shows the defaults to a rider who has set nothing, with the derived numbers null", async () => {
    const { json } = await call("get_profile");
    expect(json).toEqual({
      ftp: null,
      wPrimeKj: null,
      maxHeartRate: null,
      thresholdHeartRate: null,
      estimatedMaxHeartRate: null,
      estimatedThresholdHeartRate: null,
      powerProfileTable: "men",
      zones: { upperPercentOfFtp: [55, 75, 90, 105, 120, 150], isDefault: true, watts: null },
      weeklyAvailability: {
        mon: null,
        tue: null,
        wed: null,
        thu: null,
        fri: null,
        sat: null,
        sun: null,
      },
      weeklyAvailabilityTotalMinutes: 0,
      weekdaysWithUnknownAvailability: 7,
      goals: [],
      physics: defaultProfile().physics,
      updatedAt: null,
    });
  });

  it("works out the zones in watts, the weekly total and how many days are unknown", async () => {
    await call("update_profile", {
      ftp: 250,
      weeklyAvailability: { tue: 60, thu: 90, sat: 180, sun: 0 },
    });
    const profile = (await call("get_profile")).json as {
      ftp: number;
      zones: { watts: { zone: number; minWatts: number; maxWatts: number | null }[] };
      weeklyAvailabilityTotalMinutes: number;
      weekdaysWithUnknownAvailability: number;
    };
    expect(profile.zones.watts).toEqual(powerZoneRanges(250, [55, 75, 90, 105, 120, 150]));
    expect(profile.zones.watts[0]).toEqual({ zone: 1, minWatts: 0, maxWatts: 137 });
    expect(profile.zones.watts[6]).toEqual({ zone: 7, minWatts: 376, maxWatts: null });
    expect(profile.weeklyAvailabilityTotalMinutes).toBe(330);
    expect(profile.weekdaysWithUnknownAvailability).toBe(3);
  });

  it("changes only what is named and returns the stored profile and what changed", async () => {
    const first = (
      await call("update_profile", {
        ftp: 255,
        goals: ["  Climb well  ", "Hold 300 W for 5 minutes"],
      })
    ).json as {
      changed: string[];
      ftp: number;
      goals: string[];
      updatedAt: string;
    };
    expect(first.changed).toEqual(["ftp", "goals"]);
    expect(first.ftp).toBe(255);
    // Goals are trimmed.
    expect(first.goals).toEqual(["Climb well", "Hold 300 W for 5 minutes"]);
    expect(first.updatedAt).toBe("2026-11-18T08:00:00.000Z");
    expect(profileChanges).toEqual([{ fields: ["ftp", "goals"] }]);

    h.clock.advance(HOUR);
    const second = (
      await call("update_profile", { physics: { systemMassKg: 76.5, drivetrainEfficiency: 0.975 } })
    ).json as {
      changed: string[];
      ftp: number;
      goals: string[];
      physics: Record<string, number>;
      updatedAt: string;
    };
    expect(second.changed).toEqual(["physics"]);
    expect(second.ftp).toBe(255);
    expect(second.goals).toHaveLength(2);
    expect(second.physics).toMatchObject({
      systemMassKg: 76.5,
      drivetrainEfficiency: 0.975,
      cdA: 0.35,
    });
    expect(second.updatedAt).toBe("2026-11-18T09:00:00.000Z");

    // The sync API serves the same profile.
    const served = await h.app.inject({ method: "GET", url: "/v1/profile", headers: bearer() });
    expect(validateProfile(served.json())).toMatchObject({ ok: true });
    expect((served.json() as Profile).physics.systemMassKg).toBe(76.5);
  });

  it("overrides the zones, and null puts Coggan's back", async () => {
    const custom = (
      await call("update_profile", {
        ftp: 200,
        zones: { upperPercentOfFtp: [50, 70, 85, 100, 115, 140] },
      })
    ).json as {
      zones: { isDefault: boolean; watts: { maxWatts: number | null }[] };
    };
    expect(custom.zones.isDefault).toBe(false);
    expect(custom.zones.watts.map((z) => z.maxWatts)).toEqual([100, 140, 170, 200, 230, 280, null]);
    const reset = (await call("update_profile", { zones: null })).json as {
      zones: { isDefault: boolean };
    };
    expect(reset.zones.isDefault).toBe(true);
  });

  it("clears the FTP and forgets a weekday with null", async () => {
    await call("update_profile", { ftp: 250, weeklyAvailability: { sat: 120 } });
    const cleared = (await call("update_profile", { ftp: null, weeklyAvailability: { sat: null } }))
      .json as {
      ftp: number | null;
      weeklyAvailability: { sat: number | null };
    };
    expect(cleared.ftp).toBeNull();
    expect(cleared.weeklyAvailability.sat).toBeNull();
  });

  it("says nothing changed when nothing did, and stores nothing", async () => {
    await call("update_profile", { ftp: 250 });
    profileChanges = [];
    const same = (await call("update_profile", { ftp: 250 })).json as { changed: string[] };
    expect(same.changed).toEqual([]);
    const empty = (await call("update_profile", {})).json as { changed: string[] };
    expect(empty.changed).toEqual([]);
    expect(profileChanges).toEqual([]);
  });

  it.each([
    ["an FTP of 10 W", { ftp: 10 }, /^profile does not validate: \/ftp must be >= 30/],
    ["an FTP of 2500 W", { ftp: 2500 }, /\/ftp must be <= 2000/],
    [
      "zones that do not increase",
      { zones: { upperPercentOfFtp: [55, 75, 70, 105, 120, 150] } },
      /not above the bound before it/,
    ],
    [
      "five zone bounds",
      { zones: { upperPercentOfFtp: [55, 75, 90, 105, 120] } },
      /upperPercentOfFtp/,
    ],
    ["a day of 25 hours", { weeklyAvailability: { mon: 1500 } }, /weeklyAvailability\.mon/],
    ["a blank goal", { goals: ["Fine", "   "] }, /\/goals\/1/],
    [
      "eleven goals",
      { goals: Array.from({ length: 11 }, (_, i) => `goal ${i}`) },
      /more than 10 items/,
    ],
    [
      "an efficiency of 120%",
      { physics: { drivetrainEfficiency: 1.2 } },
      /drivetrainEfficiency must be <= 1/,
    ],
    ["a mass of zero", { physics: { systemMassKg: 0 } }, /systemMassKg must be > 0/],
    ["an FTP that is text", { ftp: "250" }, /ftp/],
    [
      "a weekday that does not exist",
      { weeklyAvailability: { funday: 60 } },
      /funday|Unrecognized/,
    ],
    ["a physics constant that does not exist", { physics: { weight: 70 } }, /weight|Unrecognized/],
  ])("refuses %s in plain words and changes nothing", async (_what, args, message) => {
    await call("update_profile", { ftp: 250 });
    profileChanges = [];
    const result = await call("update_profile", args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
    expect(((await call("get_profile")).json as { ftp: number }).ftp).toBe(250);
    expect(profileChanges).toEqual([]);
  });

  it("does not leak a failure's detail, and logs it", async () => {
    const real = h.services.profile.get;
    h.services.profile.get = async () => {
      throw new Error("password authentication failed for user overdrive");
    };
    try {
      const result = await call("get_profile");
      expect(result.isError).toBe(true);
      expect(result.text).toMatch(/^Something went wrong on the server/);
      expect(result.text).not.toContain("password");
    } finally {
      h.services.profile.get = real;
    }
  });
});

describe("list_rides", () => {
  interface Listed {
    from: string;
    to: string;
    total: number;
    offset: number;
    returned: number;
    hasMore: boolean;
    rides: {
      id: string;
      startTime: string;
      date: string;
      durationSeconds: number;
      movingSeconds: number;
      distanceKilometers: number;
      averageWatts: number;
      normalizedWatts: number;
      maxWatts: number;
      averageHeartRate: number;
      maxHeartRate: number;
      workKilojoules: number;
      intensityFactor: number;
      load: number;
      ftp: number;
      workoutId: string | null;
      workoutName: string | null;
    }[];
  }

  it("says there are no rides when there are none", async () => {
    const listed = (await call("list_rides")).json as Listed;
    expect(listed).toMatchObject({
      from: "2026-10-20",
      to: "2026-11-18",
      total: 0,
      returned: 0,
      hasMore: false,
      rides: [],
    });
  });

  it("lists the last 30 days, newest first, with the summary the tablet recorded", async () => {
    await planSyntheticWorkout();
    await store(synthetic("ride-a", NOW - 1.5 * HOUR));
    await store(asRide(rideFixture("minimal-free-ride.json"), "ride-b", NOW - 40 * HOUR));
    await store(asRide(rideFixture("minimal-free-ride.json"), "ride-old", NOW - 60 * 24 * HOUR));
    const listed = (await call("list_rides")).json as Listed;
    expect(listed.total).toBe(2);
    expect(listed.rides.map((r) => r.id)).toEqual(["ride-a", "ride-b"]);

    const [a, b] = listed.rides;
    expect(a).toMatchObject({
      startTime: "2026-11-18T06:30:00.000Z",
      date: "2026-11-18",
      durationSeconds: 300,
      movingSeconds: 285,
      maxWatts: 225,
      maxHeartRate: 161,
      ftp: 250,
      workoutId: "icu-5001",
      workoutName: "Synthetic steps",
    });
    const summary = rideFixture("synthetic-5min.json").summary;
    expect(a?.averageWatts).toBeCloseTo(summary.averagePower ?? Number.NaN, 1);
    expect(a?.normalizedWatts).toBeCloseTo(summary.normalizedPower ?? Number.NaN, 1);
    expect(a?.load).toBeCloseTo(summary.trainingStressScore ?? Number.NaN, 1);
    expect(a?.intensityFactor).toBeCloseTo(summary.intensityFactor ?? Number.NaN, 2);
    expect(a?.distanceKilometers).toBe(3);
    expect(b).toMatchObject({ workoutId: null, workoutName: null });
  });

  it("names the workout of a ride after the plan has moved on, from the copy kept", async () => {
    await planSyntheticWorkout();
    await store(synthetic("ride-a", NOW - HOUR));
    await vi.waitFor(async () => {
      expect(await h.services.rides.workoutSnapshot("ride-a")).not.toBeNull();
    });
    await h.services.plan.remove("icu-5001");
    const listed = (await call("list_rides")).json as Listed;
    expect(listed.rides[0]).toMatchObject({
      workoutId: "icu-5001",
      workoutName: "Synthetic steps",
    });
  });

  it("reads the days as the rider's own: a ride just after midnight in Paris is that day's", async () => {
    // 23:30 UTC on the 17th is 00:30 on the 18th in Paris.
    await store(
      asRide(rideFixture("minimal-free-ride.json"), "late", Date.parse("2026-11-17T23:30:00Z")),
    );
    await store(
      asRide(rideFixture("minimal-free-ride.json"), "earlier", Date.parse("2026-11-17T22:30:00Z")),
    );
    const day = (await call("list_rides", { from: "2026-11-18", to: "2026-11-18" })).json as Listed;
    expect(day.rides.map((r) => [r.id, r.date])).toEqual([["late", "2026-11-18"]]);
    const before = (await call("list_rides", { from: "2026-11-17", to: "2026-11-17" }))
      .json as Listed;
    expect(before.rides.map((r) => r.id)).toEqual(["earlier"]);
  });

  it("pages through the rides", async () => {
    for (let i = 0; i < 5; i++) {
      await store(
        asRide(rideFixture("minimal-free-ride.json"), `ride-${i}`, NOW - (i + 1) * 24 * HOUR),
      );
    }
    const first = (await call("list_rides", { limit: 2 })).json as Listed;
    expect(first).toMatchObject({ total: 5, returned: 2, hasMore: true, offset: 0 });
    expect(first.rides.map((r) => r.id)).toEqual(["ride-0", "ride-1"]);
    const last = (await call("list_rides", { limit: 2, offset: 4 })).json as Listed;
    expect(last).toMatchObject({ total: 5, returned: 1, hasMore: false, offset: 4 });
    expect(last.rides.map((r) => r.id)).toEqual(["ride-4"]);
  });

  it.each([
    [
      "a range that runs backwards",
      { from: "2026-11-20", to: "2026-11-10" },
      /to 2026-11-10 is before from 2026-11-20/,
    ],
    ["a date that does not exist", { to: "2026-02-30" }, /not a real calendar date/],
    ["a limit of zero", { limit: 0 }, /limit/],
    ["a limit above 100", { limit: 101 }, /limit/],
    ["a negative offset", { offset: -1 }, /offset/],
  ])("refuses %s in plain words", async (_what, args, message) => {
    const result = await call("list_rides", args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
  });
});

describe("get_ride", () => {
  interface Detail {
    id: string;
    startTime: string;
    ftp: number;
    workoutId: string | null;
    durationSeconds: number;
    movingSeconds: number;
    pausedSeconds: number;
    statistics: {
      power: {
        averageWatts: number;
        normalizedWatts: number;
        maxWatts: number;
        bestEfforts: { seconds: number; watts: number }[];
        timeInZones: { zone: number; minWatts: number; maxWatts: number | null; seconds: number }[];
      } & Record<string, unknown>;
      heartRate: { averageBpm: number; maxBpm: number; minBpm: number };
      cadence: { averageRpm: number };
    };
    laps: {
      lap: number;
      step: number | null;
      label: string | null;
      durationSeconds: number;
      averageWatts: number;
    }[];
    compliance:
      | { available: false; reason: string }
      | {
          available: true;
          plannedSeconds: number;
          actualSeconds: number;
          stepsCompleted: number;
          targetSteps: { plannedWatts: number; actualWatts: number };
          steps: {
            step: number;
            label: string;
            plannedSeconds: number;
            actualSeconds: number;
            plannedWatts: number | null;
            actualWatts: number | null;
            deltaPercent: number | null;
          }[];
        };
    series?: {
      stepSeconds: number;
      power: (number | null)[];
      heartRate: (number | null)[];
      targetPower: (number | null)[];
    };
  }

  it("gives the statistics worked out by the server, the laps, and no series unless asked", async () => {
    const record = synthetic("ride-a", NOW - HOUR, null);
    await store(record);
    const result = await call("get_ride", { rideId: "ride-a" });
    expect(result.isError, result.text).toBe(false);
    const ride = result.json as Detail;
    expect(ride).toMatchObject({
      id: "ride-a",
      ftp: 250,
      durationSeconds: 300,
      movingSeconds: 285,
      pausedSeconds: 15,
      workoutId: null,
    });
    expect("series" in ride).toBe(false);

    const direct = rideStatistics(record, [55, 75, 90, 105, 120, 150]);
    expect(ride.statistics.power.averageWatts).toBe(direct.power.averageWatts);
    expect(ride.statistics.power.normalizedWatts).toBe(direct.power.normalizedWatts);
    expect(ride.statistics.heartRate).toEqual(direct.heartRate);
    expect(ride.statistics.power.bestEfforts).toEqual(direct.power.bestEfforts);
    // The seven zones, in watts for the ride's FTP, with the seconds spent in each.
    expect(ride.statistics.power.timeInZones.map((z) => [z.zone, z.minWatts, z.maxWatts])).toEqual([
      [1, 0, 137],
      [2, 138, 187],
      [3, 188, 225],
      [4, 226, 262],
      [5, 263, 300],
      [6, 301, 375],
      [7, 376, null],
    ]);
    expect(ride.statistics.power.timeInZones.reduce((a, z) => a + z.seconds, 0)).toBe(
      record.samples.filter((s) => s.paused !== true && s.power != null).length,
    );
    expect(ride.laps).toHaveLength(4);
    expect(ride.laps[1]).toMatchObject({ lap: 2, step: 2, durationSeconds: 90 });
    expect(ride.laps[0]?.label).toBeNull();
  });

  it("compares the ride with the workout it was played from, step by step", async () => {
    await planSyntheticWorkout();
    await store(synthetic("ride-a", NOW - HOUR));
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance.available).toBe(true);
    if (!ride.compliance.available) return;
    expect(ride.compliance.plannedSeconds).toBe(300);
    expect(ride.compliance.actualSeconds).toBe(285);
    expect(ride.compliance.stepsCompleted).toBe(2);
    expect(
      ride.compliance.steps.map((s) => [
        s.label,
        s.plannedSeconds,
        s.actualSeconds,
        s.plannedWatts,
      ]),
    ).toEqual([
      ["Easy", 60, 60, 120],
      ["Hard", 90, 80, 220],
      ["Easy again", 60, 55, 125],
      ["Free", 90, 90, null],
    ]);
    const hard = ride.compliance.steps[1];
    expect(hard?.actualWatts).not.toBeNull();
    expect(hard?.deltaPercent).toBeCloseTo((((hard?.actualWatts ?? 0) - 220) / 220) * 100, 1);
    // The laps carry the step names.
    expect(ride.laps.map((l) => l.label)).toEqual(["Easy", "Hard", "Easy again", "Free"]);
  });

  it("keeps comparing after the plan has forgotten the workout, from the copy taken when the ride was stored", async () => {
    await planSyntheticWorkout();
    await store(synthetic("ride-a", NOW - HOUR));
    await vi.waitFor(async () => {
      expect(await h.services.rides.workoutSnapshot("ride-a")).toMatchObject({
        id: "icu-5001",
        name: "Synthetic steps",
      });
    });
    // The owner deletes the planned workout in the calendar; the next refresh drops it from the cache.
    fake.events.length = 0;
    await h.services.refresher?.refresh();
    expect(await h.services.plan.get("icu-5001")).toBeNull();
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance.available).toBe(true);
  });

  it("asks the calendar for a workout it has no copy of, and keeps what it finds", async () => {
    // The ride was stored while the plan source was off, so nothing was captured.
    fake.addEvent(
      plannedEvent("2026-10-01", "Older workout", [
        steady(60, 48),
        steady(90, 88),
        steady(60, 50),
        { duration: 90, freeride: true },
      ]),
    );
    await store(synthetic("ride-a", NOW - HOUR), false);
    expect(await h.services.rides.workoutSnapshot("ride-a")).toBeNull();
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance.available).toBe(true);
    expect(fake.requestsTo("GET", "/events/5001")).toHaveLength(1);
    expect(await h.services.rides.workoutSnapshot("ride-a")).toMatchObject({
      name: "Older workout",
    });
    // Asked again, it does not ask the calendar again.
    await call("get_ride", { rideId: "ride-a" });
    expect(fake.requestsTo("GET", "/events/5001")).toHaveLength(1);
  });

  it.each([
    ["a free ride", null, /free ride: no workout was linked/],
    [
      "a workout that was deleted",
      "icu-777",
      /icu-777 is no longer in the plan \(it was deleted\)/,
    ],
    [
      "a workout id that is not the calendar's",
      "my-workout",
      /no longer in the plan and cannot be looked up/,
    ],
  ])("says why there is nothing to compare for %s", async (_what, workoutId, reason) => {
    await store(synthetic("ride-a", NOW - HOUR, workoutId));
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance).toEqual({ available: false, reason: expect.stringMatching(reason) });
    // The rest of the answer is there.
    expect(ride.statistics.power.averageWatts).toBeGreaterThan(0);
  });

  it("says so when the workout cannot be asked about", async () => {
    await store(synthetic("ride-a", NOW - HOUR, "icu-777"), false);
    fake.failAll(500);
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance).toMatchObject({
      available: false,
      reason: expect.stringMatching(/intervals\.icu could not be asked/),
    });
  });

  it("says when the ride has steps the workout does not", async () => {
    fake.addEvent(plannedEvent("2026-11-18", "Short", [steady(60, 50)]));
    await h.services.refresher?.refresh();
    await store(synthetic("ride-a", NOW - HOUR));
    const ride = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(ride.compliance).toEqual({
      available: false,
      reason: "the ride has a step 2 and the workout has only 1",
    });
  });

  it("adds the series only when asked, averaged in steps, and never more than 1200 points", async () => {
    await store(synthetic("ride-a", NOW - HOUR, null));
    const asked = (await call("get_ride", { rideId: "ride-a", series: true })).json as Detail;
    expect(asked.series?.stepSeconds).toBe(10);
    expect(asked.series?.power).toHaveLength(30);
    expect(asked.series?.heartRate).toHaveLength(30);
    expect(asked.series?.targetPower.slice(0, 6).every((t) => t === 120)).toBe(true);
    const raw = (await call("get_ride", { rideId: "ride-a", series: true, seriesStepSeconds: 1 }))
      .json as Detail;
    expect(raw.series?.power).toHaveLength(300);
    expect(JSON.stringify(raw).length).toBeGreaterThan(JSON.stringify(asked).length);
    // A step is not asked for without the series.
    expect(
      "series" in
        ((await call("get_ride", { rideId: "ride-a", seriesStepSeconds: 5 })).json as Detail),
    ).toBe(false);
  });

  it("reads the zones by the profile's bounds", async () => {
    await store(synthetic("ride-a", NOW - HOUR, null));
    const defaults = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    await call("update_profile", { zones: { upperPercentOfFtp: [90, 95, 100, 105, 110, 115] } });
    const custom = (await call("get_ride", { rideId: "ride-a" })).json as Detail;
    expect(custom.statistics.power.timeInZones[0]?.maxWatts).toBe(225);
    expect(custom.statistics.power.timeInZones[0]?.seconds).toBeGreaterThan(
      defaults.statistics.power.timeInZones[0]?.seconds ?? 0,
    );
  });

  it("refuses a ride that is not there and an id that cannot be one", async () => {
    expect((await call("get_ride", { rideId: "ride-nope" })).text).toBe(
      "there is no ride ride-nope",
    );
    expect((await call("get_ride", { rideId: "../ride" })).text).toMatch(
      /is not a ride id; list_rides shows them/,
    );
    expect((await call("get_ride", {})).isError).toBe(true);
    expect(
      (await call("get_ride", { rideId: "x", seriesStepSeconds: 0, series: true })).isError,
    ).toBe(true);
  });

  it("copes with a ride that has no samples", async () => {
    await store(asRide(rideFixture("empty-ride.json"), "ride-empty", NOW - 2 * HOUR), false);
    const result = await call("get_ride", { rideId: "ride-empty" });
    expect(result.isError, result.text).toBe(false);
    const ride = result.json as Detail;
    expect(ride.durationSeconds).toBe(0);
    expect(ride.laps).toEqual([]);
    expect(ride.compliance).toMatchObject({ available: false });
  });
});

describe("the workout is kept when a ride is stored", () => {
  it("captures the planned workout the ride names, once the ride is complete, and not for a free ride", async () => {
    await planSyntheticWorkout();
    await store(synthetic("with-workout", NOW - 2 * HOUR));
    await store(asRide(rideFixture("minimal-free-ride.json"), "free", NOW - HOUR));
    await vi.waitFor(async () => {
      expect(await h.services.rides.workoutSnapshot("with-workout")).toMatchObject({
        id: "icu-5001",
      });
    });
    expect(await h.services.rides.workoutSnapshot("free")).toBeNull();
  });

  it("does not fail the upload when the workout cannot be found", async () => {
    // No such workout anywhere: the tablet's upload still succeeds, and the listener says nothing is kept.
    const record = synthetic("orphan", NOW - HOUR, "icu-404");
    await store(record);
    await vi.waitFor(() => {
      expect(fake.requestsTo("GET", "/events/404")).toHaveLength(1);
    });
    expect(await h.services.rides.workoutSnapshot("orphan")).toBeNull();
  });
});
