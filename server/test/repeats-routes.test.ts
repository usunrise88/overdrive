import {
  type RepeatDetailResponse,
  type RepeatsResponse,
  type RideRecord,
  routeKey,
  routePointsHash,
  routeSessionHash,
  validateRepeatDetailResponse,
  validateRepeatsResponse,
} from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION } from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import { at, attempt, DAYS, SESSION, store } from "./helpers/repeats-data.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * Two rides on the same route are one session (spec §11.4, §11.7, ADR 0036): `routeKey` is the route id and the hash of its points,
 * and a ride played from no workout that followed a route is an attempt at the route's session. Rides are synthetic and the routes
 * are made from invented profiles.
 */

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = startHarness(db, fake, {
    configured: false,
    now: "2026-11-18T08:00:00Z",
    timezone: "Europe/Paris",
  });
});

afterAll(async () => {
  await h.app.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearRides(db);
  await db.exec(
    "TRUNCATE routes, ride_routes, ride_metrics, ride_workouts, ride_repeat_stats, profile",
  );
});

const get = (url: string): Promise<LightMyRequestResponse> =>
  h.app.inject({ method: "GET", url, headers: bearer(COMPANION) });

async function makeRoute(name: string, gradePercent = 3): Promise<string> {
  const created = await h.services.routes.create({
    mode: "profile",
    name,
    segments: [{ lengthMeters: 5000, gradePercent }],
  });
  return created.route.id;
}

/** A free ride of `seconds` on the route (or on none), with a planted heart rate; the header says which route. */
async function freeRide(
  id: string,
  start: string,
  routeId: string | null,
  seconds = 600,
  heartRate = 140,
  routeName = "A route",
): Promise<RideRecord> {
  const record = syntheticRide({
    id,
    startEpochMillis: Date.parse(start),
    seconds,
    power: () => 180,
    heartRate: () => heartRate,
  });
  if (routeId !== null) Object.assign(record.ride, { routeId, routeName });
  await insertRide(db, record);
  return record;
}

/** The service's work when a ride is stored: its route key is recorded. */
const stored = (record: RideRecord) => h.services.routes.noteRide(record.ride);

describe("rides on the same route", () => {
  it("are one session: the route's key, its session hash, the attempts side by side", async () => {
    const routeId = await makeRoute("Hill road");
    const route = await h.services.routes.get(routeId);
    if (route === null) throw new Error("no route");
    const key = routeKey(routeId, route.points);
    expect(key).toMatch(/^route-[0-9a-f]{10}:[0-9a-f]{16}$/);
    expect(key.endsWith(routePointsHash(route.points))).toBe(true);

    const rides = [
      await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road"),
      await freeRide("ride-2", at(DAYS[1]), routeId, 590, 148, "Hill road"),
      await freeRide("ride-3", at(DAYS[2]), routeId, 570, 146, "Hill road"),
    ];
    for (const ride of rides) await stored(ride);
    // A ride on the flat and a ride on another route are not part of it.
    await freeRide("ride-flat", at(DAYS[3]), null);
    const other = await makeRoute("Another road");
    await stored(await freeRide("ride-other", at(DAYS[4]), other, 600, 140, "Another road"));

    const response = await get("/v1/repeats");
    expect(response.statusCode, response.body).toBe(200);
    const body = response.json() as RepeatsResponse;
    expect(validateRepeatsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
    expect(body.repeats).toHaveLength(1);
    const session = body.repeats[0];
    expect(session).toMatchObject({
      hash: routeSessionHash(key),
      name: "Hill road",
      routeKey: key,
      attempts: 3,
      firstDate: DAYS[0],
      lastDate: DAYS[2],
      latest: { rideId: "ride-3" },
      previous: { rideId: "ride-2" },
    });
    // Nothing is said about heart rate at the same power for a ride with no fixed-power step, and the reason is in words.
    expect(session?.comparison.heartRateAtPower).toMatchObject({ latest: null, difference: null });
    expect(session?.comparison.heartRateAtPower.reason).toBeTruthy();

    const detail = (
      await get(`/v1/repeats/${routeSessionHash(key)}`)
    ).json() as RepeatDetailResponse;
    expect(validateRepeatDetailResponse(detail), JSON.stringify(detail)).toMatchObject({
      ok: true,
    });
    expect(detail).toMatchObject({
      name: "Hill road",
      routeKey: key,
      steps: [],
      comparedSteps: [],
    });
    expect(detail.attempts.map((a) => [a.number, a.rideId, a.durationSeconds])).toEqual([
      [1, "ride-1", 600],
      [2, "ride-2", 590],
      [3, "ride-3", 570],
    ]);
    expect(detail.attempts.map((a) => a.averageHeartRate)).toEqual([150, 148, 146]);
    expect(detail.attempts.every((a) => a.heartRateAtPower.value === null)).toBe(true);
  });

  it("two rides are enough to be listed and one is not", async () => {
    const routeId = await makeRoute("Hill road");
    await stored(await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road"));
    expect(((await get("/v1/repeats")).json() as RepeatsResponse).repeats).toEqual([]);
    await stored(await freeRide("ride-2", at(DAYS[1]), routeId, 600, 150, "Hill road"));
    expect(((await get("/v1/repeats")).json() as RepeatsResponse).repeats).toHaveLength(1);
  });

  it("keep their session when the route is deleted afterwards: the ride keeps the route's id and name", async () => {
    const routeId = await makeRoute("Hill road");
    await stored(await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road"));
    await stored(await freeRide("ride-2", at(DAYS[1]), routeId, 600, 150, "Hill road"));
    expect(await h.services.routes.delete(routeId)).toBe(true);
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(body.repeats).toHaveLength(1);
    expect(body.repeats[0]).toMatchObject({ name: "Hill road", attempts: 2 });
    expect(body.repeats[0]?.routeKey?.startsWith(`${routeId}:`)).toBe(true);
    // The rides themselves are untouched.
    expect((await h.services.rides.getRecord("ride-1"))?.ride).toMatchObject({
      routeId,
      routeName: "Hill road",
    });
  });

  it("are keyed by the id alone when the route was already deleted before the ride was stored", async () => {
    const routeId = await makeRoute("Hill road");
    await h.services.routes.delete(routeId);
    await stored(await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road"));
    await stored(await freeRide("ride-2", at(DAYS[1]), routeId, 600, 150, "Hill road"));
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(body.repeats[0]).toMatchObject({
      routeKey: `${routeId}:deleted`,
      name: "Hill road",
      attempts: 2,
    });
    expect(validateRepeatsResponse(body)).toMatchObject({ ok: true });
  });

  it("are found for rides stored before anything told the service about their route (a backfill on read)", async () => {
    const routeId = await makeRoute("Hill road");
    await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road");
    await freeRide("ride-2", at(DAYS[1]), routeId, 600, 150, "Hill road");
    expect((await db.query("SELECT 1 FROM ride_routes")).rows).toHaveLength(0);
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(body.repeats).toHaveLength(1);
    expect((await db.query("SELECT 1 FROM ride_routes")).rows).toHaveLength(2);
    // Idempotent: a second read changes nothing.
    expect(await h.services.routes.ensureRideRoutes()).toBe(0);
  });

  it("answer GET /v1/repeats/{hash}/previous with the attempt before, and get_ride-style repeatOf", async () => {
    const routeId = await makeRoute("Hill road");
    const key = routeKey(routeId, (await h.services.routes.get(routeId))?.points ?? []);
    for (const [i, day] of DAYS.slice(0, 3).entries()) {
      await stored(
        await freeRide(`ride-${i + 1}`, at(day), routeId, 600 - i * 10, 150 - i, "Hill road"),
      );
    }
    const hash = routeSessionHash(key);
    const previous = (await get(`/v1/repeats/${hash}/previous?before=ride-3`)).json() as {
      previous: { rideId: string; attempt: number; steps: unknown[] } | null;
    };
    expect(previous.previous).toMatchObject({ rideId: "ride-2", attempt: 2, steps: [] });
    const of = await h.services.repeats.repeatOf("ride-3");
    expect(of).toMatchObject({
      hash,
      attemptNumber: 3,
      attempts: 3,
      previous: { rideId: "ride-2" },
    });
    expect(await h.services.repeats.repeatOf("ride-flat-nothing")).toBeNull();
  });
});

describe("a ride played from a workout that also followed a route", () => {
  it("stays a session of the workout's structure, and carries the route's key when every attempt shares it", async () => {
    const routeId = await makeRoute("Hill road");
    const key = routeKey(routeId, (await h.services.routes.get(routeId))?.points ?? []);
    for (const [i, day] of DAYS.slice(0, 2).entries()) {
      const record = attempt({ id: `ride-${i + 1}`, start: at(day) });
      Object.assign(record.ride, { routeId, routeName: "Hill road" });
      await store(db, h.services.rides, record, SESSION);
      await stored(record);
    }
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(validateRepeatsResponse(body)).toMatchObject({ ok: true });
    expect(body.repeats).toHaveLength(1);
    expect(body.repeats[0]).toMatchObject({ name: SESSION.name, routeKey: key, attempts: 2 });
    // Not the route session's hash: the workout's structure identifies it.
    expect(body.repeats[0]?.hash).not.toBe(routeSessionHash(key));
  });

  it("has no route key when its attempts followed different routes, or one of them none", async () => {
    const a = await makeRoute("A");
    const b = await makeRoute("B");
    const first = attempt({ id: "ride-1", start: at(DAYS[0]) });
    Object.assign(first.ride, { routeId: a, routeName: "A" });
    const second = attempt({ id: "ride-2", start: at(DAYS[1]) });
    Object.assign(second.ride, { routeId: b, routeName: "B" });
    const third = attempt({ id: "ride-3", start: at(DAYS[2]) });
    for (const record of [first, second, third]) {
      await store(db, h.services.rides, record, SESSION);
      await stored(record);
    }
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(body.repeats).toHaveLength(1);
    expect(body.repeats[0]).toMatchObject({ attempts: 3, routeKey: null });
  });
});

describe("the wiring when a ride is stored", () => {
  it("records the route key on the ride-stored event, and not for a ride on the flat", async () => {
    const routeId = await makeRoute("Hill road");
    const withRoute = await freeRide("ride-1", at(DAYS[0]), routeId, 600, 150, "Hill road");
    const flat = await freeRide("ride-2", at(DAYS[1]), null);
    h.services.bus.emit("ride-stored", {
      rideId: "ride-1",
      startEpochMillis: withRoute.ride.startEpochMillis,
    });
    h.services.bus.emit("ride-stored", {
      rideId: "ride-2",
      startEpochMillis: flat.ride.startEpochMillis,
    });
    await vi.waitFor(async () =>
      expect(await h.services.routes.rideRoute("ride-1")).not.toBeNull(),
    );
    const found = await h.services.routes.rideRoute("ride-1");
    expect(found).toMatchObject({ routeId, routeName: "Hill road" });
    expect(found?.sessionHash).toBe(routeSessionHash(found?.routeKey ?? ""));
    expect(await h.services.routes.rideRoute("ride-2")).toBeNull();
  });
});
