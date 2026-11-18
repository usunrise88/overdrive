import {
  type FtpSuggestionDismissResponse,
  type MetricsResponse,
  type Profile,
  type RideDetailResponse,
  type RideItem,
  type RideMetricsResponse,
  type RidesResponse,
  validateFtpSuggestionDismissResponse,
  validateMetricsResponse,
  validateRideDetailResponse,
  validateRideMetricsResponse,
  validateRidesResponse,
  type Workout,
} from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import type { LoadSourceProvider } from "../src/metrics/load.ts";
import type { ActivitySourceProvider } from "../src/metrics/service.ts";
import { bearer, expectError, fitFixture, putFit, putRecord } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import { constant, insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = startHarness(db, fake, { timezone: "Europe/Paris" });
});

afterAll(async () => {
  await h.services.metrics.whenIdle();
  await h.app.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await h.services.metrics.whenIdle();
  await db.exec(
    "TRUNCATE rides, ride_destinations, ride_metrics, ftp_suggestions, ftp_history, profile, ride_workouts",
  );
  h.clock.set("2026-11-18T08:00:00Z");
});

const get = (url: string): Promise<LightMyRequestResponse> =>
  h.app.inject({ method: "GET", url, headers: bearer() });
const patchProfile = (body: unknown): Promise<LightMyRequestResponse> =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });
const dismiss = (body: unknown, headers = bearer()): Promise<LightMyRequestResponse> =>
  h.app.inject({
    method: "POST",
    url: "/v1/ftp-suggestion/dismiss",
    headers: { "content-type": "application/json", ...headers },
    payload: JSON.stringify(body),
  });

/** The answer is a 200 whose body the contract's validator accepts. */
function ok<T>(
  res: LightMyRequestResponse,
  validate: (body: unknown) => { ok: boolean; errors?: string[] },
): T {
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  const checked = validate(body);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return body as T;
}
const metrics = async (from: string, to: string): Promise<MetricsResponse> =>
  ok(await get(`/v1/metrics?from=${from}&to=${to}`), validateMetricsResponse);

// ---- Rides the tests ride (Europe/Paris is UTC+1 in November) -----------------------------------

const at = (instant: string): number => Date.parse(instant);

/** 10 Nov, 18:00 Paris: an hour at 200 W (FTP 250: IF 0.8, load 64.0), heart rate 140 then 147: drift 4.8%. */
const rideA = () =>
  syntheticRide({
    id: "ride-a",
    startEpochMillis: at("2026-11-10T17:00:00Z"),
    seconds: 3600,
    power: constant(200),
    heartRate: (s) => (s <= 1800 ? 140 : 147),
  });
/** 12 Nov, 18:00 Paris: an hour at 225 W (IF 0.9, load 81.0), heart rate steady: drift 0. */
const rideB = () =>
  syntheticRide({
    id: "ride-b",
    startEpochMillis: at("2026-11-12T17:00:00Z"),
    seconds: 3600,
    power: constant(225),
    heartRate: constant(150),
  });
/** 12 Nov, 20:30 Paris (the same day): half an hour at 250 W (IF 1, load 50.0). */
const rideC = () =>
  syntheticRide({
    id: "ride-c",
    startEpochMillis: at("2026-11-12T19:30:00Z"),
    seconds: 1800,
    power: constant(250),
  });
/** 14 Nov 23:30 UTC is 00:30 on the 15th in Paris: 20 minutes at 300 W (IF 1.2, load 48.0). */
const rideD = () =>
  syntheticRide({
    id: "ride-d",
    startEpochMillis: at("2026-11-14T23:30:00Z"),
    seconds: 1200,
    power: constant(300),
  });
/** A 20-minute effort at `watts` on a day (17:00 UTC). */
const effort = (id: string, day: string, watts: number) =>
  syntheticRide({
    id,
    startEpochMillis: at(`${day}T17:00:00Z`),
    seconds: 1200,
    power: constant(watts),
  });

async function seedWeek(): Promise<void> {
  for (const ride of [rideA(), rideB(), rideC(), rideD()]) await insertRide(db, ride);
}

describe("GET /v1/metrics", () => {
  it("needs a device token", async () => {
    const res = await h.app.inject({
      method: "GET",
      url: "/v1/metrics?from=2026-11-01&to=2026-11-02",
    });
    expectError(res, "unauthorized");
  });

  it.each([
    ["no range", "/v1/metrics", /Give from as a date/],
    ["no to", "/v1/metrics?from=2026-11-01", /Give to as a date/],
    ["a date that is not one", "/v1/metrics?from=2026-02-30&to=2026-03-01", /real dates/],
    ["a reversed range", "/v1/metrics?from=2026-11-05&to=2026-11-01", /is after to/],
    ["367 days", "/v1/metrics?from=2025-11-17&to=2026-11-18", /at most 366/],
  ])("answers 400 invalid_range for %s", async (_what, url, message) => {
    expect(expectError(await get(url), "invalid_range").message).toMatch(message);
  });

  it("serves 366 days of nothing, with every day and week there and the FTP empty", async () => {
    const body = await metrics("2025-11-18", "2026-11-18");
    expect(body.daily).toHaveLength(366);
    expect(body.weekly.length).toBeLessThanOrEqual(54);
    expect(body.generatedAt).toBe("2026-11-18T08:00:00.000Z");
    expect(body.stale).toBe(false);
    expect(body.firstLoadDate).toBeNull();
    expect(body.daily.every((d) => d.load === 0 && d.ctl === 0 && d.tsb === 0)).toBe(true);
    expect(body.powerCurve).toEqual({ range: [], allTime: [] });
    expect(body.ftp).toEqual({
      current: null,
      setAt: null,
      suggestion: null,
      latestEstimate: null,
      history: [],
      estimates: [],
    });
    expect(body.drift).toEqual([]);
  });

  it("works out load by the rider's day, fitness, fatigue and form, the weeks, the curve and the drift", async () => {
    await seedWeek();
    const body = await metrics("2026-11-09", "2026-11-16");
    expect(body.firstLoadDate).toBe("2026-11-10");

    // Loads: 64.0 on the 10th, 81.0 + 50.0 on the 12th, and the ride at 23:30 UTC on the 14th belongs to the 15th in Paris: 48.0.
    // Fitness, fatigue and form from the formulas (k42 = 0.0235283, k7 = 0.1331221), day by day from the 10th.
    expect(body.daily.map((d) => [d.date, d.load, d.ctl, d.atl, d.tsb])).toEqual([
      ["2026-11-09", 0, 0, 0, 0],
      ["2026-11-10", 64, 1.5, 8.5, -7],
      ["2026-11-11", 0, 1.5, 7.4, -5.9],
      ["2026-11-12", 131, 4.5, 23.8, -19.3],
      ["2026-11-13", 0, 4.4, 20.7, -16.3],
      ["2026-11-14", 0, 4.3, 17.9, -13.6],
      ["2026-11-15", 48, 5.3, 21.9, -16.6],
      ["2026-11-16", 0, 5.2, 19, -13.8],
    ]);
    expect(body.daily[3]).toMatchObject({ loadCycling: 131, loadOther: 0 });

    // The week of Monday 9 Nov has all four rides; Monday 16 Nov has none yet.
    expect(body.weekly).toEqual([
      { weekStart: "2026-11-09", load: 243, rideLoad: 243, otherLoad: 0, rides: 4, activities: 0 },
      { weekStart: "2026-11-16", load: 0, rideLoad: 0, otherLoad: 0, rides: 0, activities: 0 },
    ]);

    // The best of each duration: the 20 minutes of ride D (300 W) cover every duration up to 1200 s; 30 minutes: ride C (250 W);
    // 40 and 60 minutes: ride B's hour (225 W; ride A's was 200 W). Nobody rode more than an hour.
    const shortest = body.powerCurve.range.filter((p) => p.durationSec <= 1200);
    expect(shortest).toHaveLength(20);
    expect(
      shortest.every((p) => p.power === 300 && p.rideId === "ride-d" && p.date === "2026-11-15"),
    ).toBe(true);
    expect(body.powerCurve.range.filter((p) => p.durationSec > 1200)).toEqual([
      { durationSec: 1800, power: 250, date: "2026-11-12", rideId: "ride-c" },
      { durationSec: 2400, power: 225, date: "2026-11-12", rideId: "ride-b" },
      { durationSec: 3600, power: 225, date: "2026-11-12", rideId: "ride-b" },
    ]);
    expect(body.powerCurve.allTime).toEqual(body.powerCurve.range);

    // Aerobic drift: ride A 4.8% (EF 140/147 halves at 200 W), ride B none (225 W over 150 bpm = 1.5 both halves).
    expect(body.drift).toEqual([
      {
        date: "2026-11-10",
        rideId: "ride-a",
        name: "Free ride",
        driftPercent: 4.8,
        efFirst: 1.429,
        efSecond: 1.361,
      },
      {
        date: "2026-11-12",
        rideId: "ride-b",
        name: "Free ride",
        driftPercent: 0,
        efFirst: 1.5,
        efSecond: 1.5,
      },
    ]);
    // No acute:chronic workload ratio anywhere.
    expect(JSON.stringify(body)).not.toMatch(/acwr|acute/i);
  });

  it("counts a ride by its own FTP, not the profile's", async () => {
    // The same hour at 200 W recorded with an FTP of 200: IF 1, load 100.0.
    await insertRide(
      db,
      syntheticRide({
        id: "ride-own",
        startEpochMillis: at("2026-11-10T17:00:00Z"),
        ftp: 200,
        seconds: 3600,
        power: constant(200),
      }),
    );
    await patchProfile({ ftp: 300 });
    const body = await metrics("2026-11-10", "2026-11-10");
    expect(body.daily[0]?.load).toBe(100);
  });

  it("keeps the range's best apart from the best ever", async () => {
    await insertRide(db, effort("ride-old", "2026-08-01", 320));
    await insertRide(db, effort("ride-new", "2026-11-12", 280));
    const body = await metrics("2026-11-09", "2026-11-18");
    const twenty = (curve: MetricsResponse["powerCurve"]["range"]) =>
      curve.find((p) => p.durationSec === 1200);
    expect(twenty(body.powerCurve.range)).toMatchObject({ power: 280, rideId: "ride-new" });
    expect(twenty(body.powerCurve.allTime)).toMatchObject({
      power: 320,
      rideId: "ride-old",
      date: "2026-08-01",
    });
    // Fitness remembers the old ride (seeded at 0 on 1 Aug), the week list is only the range's.
    expect(body.firstLoadDate).toBe("2026-08-01");
    expect(body.weekly).toHaveLength(2);
  });

  it("adds the load of other activities from a registered source, apart, and says stale when a source fails", async () => {
    await seedWeek();
    const watch: LoadSourceProvider = {
      name: "test watch",
      entries: async (until) =>
        [
          { date: "2026-11-12", kind: "other" as const, load: 20 },
          { date: "2026-11-13", kind: "other" as const, load: 30 },
        ].filter((e) => e.date <= until),
    };
    h.services.metrics.registerLoadSource(watch);
    const body = await metrics("2026-11-09", "2026-11-16");
    // With 20 on the 12th and 30 on the 13th on top of the rides: the same formulas.
    expect(
      body.daily
        .map((d) => [d.date, d.load, d.loadCycling, d.loadOther, d.ctl, d.atl, d.tsb])
        .slice(3, 5),
    ).toEqual([
      ["2026-11-12", 151, 131, 20, 5, 26.5, -21.5],
      ["2026-11-13", 30, 0, 30, 5.6, 27, -21.4],
    ]);
    expect(body.weekly[0]).toEqual({
      weekStart: "2026-11-09",
      load: 293,
      rideLoad: 243,
      otherLoad: 50,
      rides: 4,
      activities: 2,
    });
    // The power curve, the FTP and the drift read rides only.
    expect(body.powerCurve.range.some((p) => p.rideId.startsWith("act"))).toBe(false);
    expect(body.stale).toBe(false);

    h.services.metrics.registerLoadSource({
      name: "broken source",
      entries: () => Promise.reject(new Error("down")),
    });
    const degraded = await metrics("2026-11-09", "2026-11-16");
    expect(degraded.stale).toBe(true);
    expect(degraded.daily[3]?.loadOther).toBe(20);
    h.logging.lines.length = 0;
  });
});

describe("the FTP: history, suggestion, dismissal and acceptance", () => {
  it("records every change of the profile's FTP with its day, and gives the value in force at the start of the range", async () => {
    h.clock.set("2026-11-10T12:00:00Z");
    await patchProfile({ ftp: 250 });
    h.clock.set("2026-11-20T12:00:00Z");
    await patchProfile({ ftp: 260 });
    await h.services.metrics.whenIdle();

    const early = await metrics("2026-11-09", "2026-11-12");
    expect(early.ftp.history).toEqual([{ date: "2026-11-10", ftp: 250 }]);
    // From the 18th: the 250 W of the 10th is the one in force, then the change on the 20th.
    const later = await metrics("2026-11-18", "2026-11-22");
    expect(later.ftp.current).toBe(260);
    expect(later.ftp.setAt).toBe("2026-11-20");
    expect(later.ftp.history).toEqual([
      { date: "2026-11-10", ftp: 250 },
      { date: "2026-11-20", ftp: 260 },
    ]);
    // Clearing it is a change too.
    await patchProfile({ ftp: null });
    await h.services.metrics.whenIdle();
    const cleared = await metrics("2026-11-20", "2026-11-22");
    expect(cleared.ftp.current).toBeNull();
    expect(cleared.ftp.setAt).toBeNull();
    expect(cleared.ftp.history.at(-1)).toEqual({ date: "2026-11-20", ftp: null });
  });

  it("reads an FTP set before the history existed from the profile's own date", async () => {
    await db.query(
      "INSERT INTO profile (id, data, updated_at) VALUES (true, $1::jsonb, $2::timestamptz)",
      [JSON.stringify({ ...stored(), ftp: 245 }), "2026-10-01T09:00:00Z"],
    );
    const body = await metrics("2026-11-09", "2026-11-12");
    expect(body.ftp).toMatchObject({
      current: 245,
      setAt: "2026-10-01",
      history: [{ date: "2026-10-01", ftp: 245 }],
    });
  });

  /** A profile as stored, without its stamp. */
  function stored(): Omit<Profile, "updatedAt"> {
    return {
      ftp: null,
      wPrimeKj: null,
      zones: { upperPercentOfFtp: [55, 75, 90, 105, 120, 150] },
      weeklyAvailability: {
        mon: null,
        tue: null,
        wed: null,
        thu: null,
        fri: null,
        sat: null,
        sun: null,
      },
      goals: [],
      physics: {
        systemMassKg: 80,
        cdA: 0.35,
        crr: 0.004,
        airDensity: 1.225,
        drivetrainEfficiency: 1,
      },
    };
  }

  it("suggests nothing from one hard ride, then a suggestion once a second one backs it", async () => {
    await patchProfile({ ftp: 250 });
    await seedWeek(); // the only 20-minute effort close to the best is ride D's 300 W
    let body = await metrics("2026-11-09", "2026-11-18");
    expect(body.ftp.suggestion).toBeNull();
    expect(body.ftp.latestEstimate).toBeNull();
    const advice = await h.services.metrics.ftpAdvice();
    expect(advice.note).toMatch(
      /Only 1 ride in the last 42 days has a 20-minute effort close to the best/,
    );

    // A second ride with 290 W for 20 minutes (above 90% of 300 W): eFTP = 0.95 * 300 = 285, two rides back it.
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    body = await metrics("2026-11-09", "2026-11-18");
    expect(body.ftp.latestEstimate).toBe(285);
    expect(body.ftp.suggestion).toMatchObject({
      watts: 285,
      basedOnRides: 2,
      windowDays: 42,
      confidence: "low",
      basis: { durationSec: 1200, power: 300, rideId: "ride-d", date: "2026-11-15" },
    });
    expect(body.ftp.suggestion?.createdAt).toBe("2026-11-18T08:00:00.000Z");
    // The estimate as of the end of each week of the range: none for the first week, 285 on the last day of the range.
    expect(body.ftp.estimates).toEqual([{ date: "2026-11-18", eftp: 285 }]);
    // Only a suggestion: the profile is as it was.
    expect(body.ftp.current).toBe(250);
    expect(((await patchProfile({})).json() as Profile).ftp).toBe(250);
  });

  it("makes a suggestion only when it differs from the FTP by 3% or more", async () => {
    await seedWeek();
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    // eFTP 285 against an FTP of 280: 5 W is 1.8%: no suggestion, and the note says why.
    await patchProfile({ ftp: 280 });
    let body = await metrics("2026-11-09", "2026-11-18");
    expect(body.ftp.latestEstimate).toBe(285);
    expect(body.ftp.suggestion).toBeNull();
    expect((await h.services.metrics.ftpAdvice()).note).toMatch(/within 3% of the FTP of 280 W/);
    // Against 276 it is 9 W, 3.26%: a suggestion. (A change of FTP re-evaluates by itself.)
    await patchProfile({ ftp: 276 });
    await h.services.metrics.whenIdle();
    body = await metrics("2026-11-09", "2026-11-18");
    expect(body.ftp.suggestion?.watts).toBe(285);
    // And at 277 (8 W, 2.89%) the pending suggestion no longer applies.
    await patchProfile({ ftp: 277 });
    await h.services.metrics.whenIdle();
    body = await metrics("2026-11-09", "2026-11-18");
    expect(body.ftp.suggestion).toBeNull();
    const kept = await h.services.metrics.suggestions();
    expect(kept.map((s) => s.status)).toEqual(["superseded"]);
  });

  it("keeps every suggestion with its status, one pending at a time, and a materially different one supersedes", async () => {
    await patchProfile({ ftp: 250 });
    await seedWeek();
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    await metrics("2026-11-09", "2026-11-18"); // 285 pending
    // 1 W of movement (301 * 0.95 = 285.95, 286) is the same suggestion.
    await insertRide(db, effort("ride-f", "2026-11-17", 301));
    const same = await metrics("2026-11-09", "2026-11-18");
    expect(same.ftp.suggestion?.watts).toBe(285);
    // 310 W gives 294.5, 295: 10 W away, a new one.
    await insertRide(db, effort("ride-g", "2026-11-18", 310));
    const moved = await metrics("2026-11-09", "2026-11-18");
    expect(moved.ftp.suggestion).toMatchObject({
      watts: 295,
      basedOnRides: 4,
      confidence: "medium",
    });
    const all = await h.services.metrics.suggestions();
    expect(all.map((s) => [s.value, s.status])).toEqual([
      [295, "pending"],
      [285, "superseded"],
    ]);
    expect(all[1]?.decidedAt).not.toBeNull();
    expect(all[0]?.decidedAt).toBeNull();
  });

  it("dismisses a suggestion, hides it until the estimate moves 3 W, and is idempotent", async () => {
    await patchProfile({ ftp: 250 });
    await seedWeek();
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    const first = await metrics("2026-11-09", "2026-11-18");
    const id = first.ftp.suggestion?.id as number;
    expect(id).toBeGreaterThan(0);

    const done = ok<FtpSuggestionDismissResponse>(
      await dismiss({ suggestionId: id }),
      validateFtpSuggestionDismissResponse,
    );
    expect(done).toEqual({ suggestionId: id, status: "dismissed" });
    expect((await metrics("2026-11-09", "2026-11-18")).ftp.suggestion).toBeNull();
    // Again: the same answer, nothing changes.
    expect(
      ok<FtpSuggestionDismissResponse>(
        await dismiss({ suggestionId: id }),
        validateFtpSuggestionDismissResponse,
      ).status,
    ).toBe("dismissed");

    // A ride that moves the estimate by 1 W (286 against the dismissed 285) stays hidden; the advice says so.
    await insertRide(db, effort("ride-f", "2026-11-17", 301));
    expect((await metrics("2026-11-09", "2026-11-18")).ftp.suggestion).toBeNull();
    expect((await h.services.metrics.ftpAdvice()).note).toMatch(/dismissed a suggestion of 285 W/);
    // Moving it by 10 W brings one back.
    await insertRide(db, effort("ride-g", "2026-11-18", 310));
    const back = await metrics("2026-11-09", "2026-11-18");
    expect(back.ftp.suggestion?.watts).toBe(295);
    expect(back.ftp.suggestion?.id).toBeGreaterThan(id);
  });

  it("forgets a dismissal once the FTP has changed", async () => {
    await patchProfile({ ftp: 250 });
    await seedWeek();
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    const id = (await metrics("2026-11-09", "2026-11-18")).ftp.suggestion?.id as number;
    await dismiss({ suggestionId: id });
    // The rider sets 270 themselves a day later: 285 is 5.6% above it, so it is suggested afresh.
    h.clock.advance(24 * 3_600_000);
    await patchProfile({ ftp: 270 });
    await h.services.metrics.whenIdle();
    const body = await metrics("2026-11-09", "2026-11-19");
    expect(body.ftp.suggestion?.watts).toBe(285);
    expect(body.ftp.suggestion?.id).toBeGreaterThan(id);
  });

  it("marks the pending suggestion accepted when the profile gets its value, and leaves it pending for any other value", async () => {
    await patchProfile({ ftp: 250 });
    await seedWeek();
    await insertRide(db, effort("ride-e", "2026-11-16", 290));
    const id = (await metrics("2026-11-09", "2026-11-18")).ftp.suggestion?.id as number;

    // Another FTP of the rider's own choosing does not accept it.
    await patchProfile({ ftp: 262 });
    await h.services.metrics.whenIdle();
    expect((await h.services.metrics.store.suggestion(id))?.status).toBe("pending");

    // Use 285: PATCH /v1/profile with the suggested value.
    await patchProfile({ ftp: 285 });
    const body = await metrics("2026-11-09", "2026-11-18");
    expect((await h.services.metrics.store.suggestion(id))?.status).toBe("accepted");
    expect(body.ftp.suggestion).toBeNull();
    expect(body.ftp.current).toBe(285);
    expect(body.ftp.history.map((e) => e.ftp)).toEqual([250, 262, 285]);
    // And dismissing an accepted one says what became of it.
    expect(
      ok<FtpSuggestionDismissResponse>(
        await dismiss({ suggestionId: id }),
        validateFtpSuggestionDismissResponse,
      ).status,
    ).toBe("accepted");
  });

  it("answers the dismissal's errors in the contract's words", async () => {
    expectError(await dismiss({ suggestionId: 1 }, {}), "unauthorized");
    expectError(await dismiss({}), "invalid_json");
    expectError(await dismiss({ suggestionId: "1" }), "invalid_json");
    expectError(await dismiss({ suggestionId: 0 }), "invalid_json");
    expectError(await dismiss({ suggestionId: 4242 }), "not_found");
    const noBody = await h.app.inject({
      method: "POST",
      url: "/v1/ftp-suggestion/dismiss",
      headers: bearer(),
    });
    expect(noBody.statusCode).toBe(400);
  });
});

describe("GET /v1/rides", () => {
  it("lists rides newest first, by cursor, naming a ride after its workout or Free ride", async () => {
    await seedWeek();
    await h.services.rides.saveWorkoutSnapshot(
      "ride-b",
      workoutOf("icu-1", "Sweet spot"),
      h.clock.now(),
    );
    await db.query(
      "UPDATE rides SET record = jsonb_set(record, '{ride,workoutId}', '\"icu-1\"') WHERE id = 'ride-b'",
    );
    await db.query("UPDATE rides SET record_sha256 = record_sha256 || 'x' WHERE id = 'ride-b'");

    const page = ok<RidesResponse>(await get("/v1/rides?limit=2"), validateRidesResponse);
    expect(page.items.map((i) => [i.id, i.kind, i.name])).toEqual([
      ["ride-d", "ride", "Free ride"],
      ["ride-c", "ride", "Free ride"],
    ]);
    expect(page).toMatchObject({ before: null, limit: 2, hasMore: true, stale: false });
    expect(page.nextBefore).toBe("2026-11-12T19:30:00.000Z");
    expect(page.items[0]).toEqual({
      id: "ride-d",
      kind: "ride",
      name: "Free ride",
      start: "2026-11-14T23:30:00.000Z",
      date: "2026-11-15",
      durationSeconds: 1200,
      load: 48,
      loadApproximate: false,
      sport: null,
      averagePower: 300,
      averageHeartRate: null,
    });

    const next = ok<RidesResponse>(
      await get(`/v1/rides?limit=2&before=${page.nextBefore}`),
      validateRidesResponse,
    );
    // Ride B was changed under the table above: its row is out of date until it is computed again, and then it is named after its workout.
    expect(next.items.map((i) => [i.id, i.name])).toEqual([
      ["ride-b", "Sweet spot"],
      ["ride-a", "Free ride"],
    ]);
    expect(next.hasMore).toBe(false);
    expect(next.nextBefore).toBeNull();
    expect(next.before).toBe("2026-11-12T19:30:00.000Z");
  });

  it("is empty, not an error, with no rides, and merges in what the registered sources have", async () => {
    expect(ok<RidesResponse>(await get("/v1/rides"), validateRidesResponse)).toMatchObject({
      items: [],
      hasMore: false,
      limit: 50,
    });
    await seedWeek();
    const activity = (id: string, start: string): RideItem => ({
      id,
      kind: "activity",
      name: "Beat Saber",
      start,
      date: start.slice(0, 10),
      durationSeconds: 2500,
      load: 38.2,
      loadApproximate: true,
      sport: "VirtualReality",
      averagePower: null,
      averageHeartRate: 142.3,
    });
    const source: ActivitySourceProvider = {
      name: "test watch",
      list: async ({ beforeMillis, limit }) =>
        [
          activity("act-new", "2026-11-17T20:00:00.000Z"),
          activity("act-old", "2026-11-11T20:00:00.000Z"),
        ]
          .filter((a) => beforeMillis === null || Date.parse(a.start) < beforeMillis)
          .slice(0, limit),
    };
    h.services.metrics.registerActivitySource(source);
    const page = ok<RidesResponse>(await get("/v1/rides?limit=3"), validateRidesResponse);
    expect(page.items.map((i) => [i.kind, i.id])).toEqual([
      ["activity", "act-new"],
      ["ride", "ride-d"],
      ["ride", "ride-c"],
    ]);
    expect(page.hasMore).toBe(true);
    const rest = ok<RidesResponse>(
      await get(`/v1/rides?limit=10&before=${page.nextBefore}`),
      validateRidesResponse,
    );
    expect(rest.items.map((i) => i.id)).toEqual(["ride-b", "act-old", "ride-a"]);
    expect(rest.hasMore).toBe(false);

    h.services.metrics.registerActivitySource({
      name: "down",
      list: () => Promise.reject(new Error("down")),
    });
    expect(ok<RidesResponse>(await get("/v1/rides?limit=1"), validateRidesResponse).stale).toBe(
      true,
    );
  });

  it.each([
    ["limit 0", "/v1/rides?limit=0"],
    ["limit 101", "/v1/rides?limit=101"],
    ["limit abc", "/v1/rides?limit=abc"],
    ["limit 2.5", "/v1/rides?limit=2.5"],
    ["before a date", "/v1/rides?before=2026-11-12"],
    ["before not a real instant", "/v1/rides?before=2026-13-45T10:00:00Z"],
  ])("answers 400 invalid_query for %s", async (_what, url) => {
    expectError(await get(url), "invalid_query");
  });

  it("needs a token", async () => {
    expectError(await h.app.inject({ method: "GET", url: "/v1/rides" }), "unauthorized");
  });
});

/** A native workout of the given name: warm-up, two sweet-spot efforts with a recovery between, cool-down. */
function workoutOf(id: string, name: string): Workout {
  return {
    schema: 1,
    id,
    name,
    steps: [
      { type: "steady", duration: 600, target: 0.55, label: "Warm-up" },
      {
        type: "repeat",
        count: 2,
        steps: [
          { type: "steady", duration: 1200, target: 0.9, label: "Sweet spot" },
          { type: "steady", duration: 300, target: 0.5, label: "Recovery" },
        ],
      },
      { type: "steady", duration: 300, target: 0.45, label: "Cool-down" },
    ],
  };
}

/** The ride that followed that workout: steps 0 to 5 (warm-up, effort, recovery, effort, recovery, cool-down), 3900 s. */
function workoutRide(id: string, workoutId: string | null) {
  const bounds = [600, 1800, 2100, 3300, 3600, 3900];
  const stepOf = (s: number) => bounds.findIndex((end) => s <= end);
  const watts = [140, 226, 125, 224, 125, 110];
  return syntheticRide({
    id,
    startEpochMillis: at("2026-11-12T17:00:00Z"),
    seconds: 3900,
    power: (s) => watts[stepOf(s)] ?? 0,
    heartRate: constant(120),
    step: stepOf,
    workoutId,
  });
}

describe("GET /v1/rides/{id}", () => {
  it("shows a ride from a workout: summary, laps with the plan's targets, zones, the plan and how it went", async () => {
    const record = workoutRide("ride-w", "icu-7001");
    await insertRide(db, record);
    await h.services.rides.saveWorkoutSnapshot(
      "ride-w",
      workoutOf("icu-7001", "Sweet spot"),
      h.clock.now(),
    );
    await patchProfile({ ftp: 250 });

    const detail = ok<RideDetailResponse>(
      await get("/v1/rides/ride-w"),
      validateRideDetailResponse,
    );
    expect(detail).toMatchObject({
      id: "ride-w",
      name: "Sweet spot",
      startTime: "2026-11-12T17:00:00.000Z",
      endTime: "2026-11-12T18:05:00.000Z",
      date: "2026-11-12",
      workoutId: "icu-7001",
      ftp: 250,
      cp: 250,
      wPrime: 20,
      stale: false,
    });
    expect(detail.series).toBeUndefined();
    expect(detail.summary).toEqual(record.summary);

    // Targets: the step's fraction times the ride's FTP, in whole watts: 0.55*250 = 137.5 -> 138, 0.9*250 = 225, 0.5*250 = 125, 0.45*250 = 112.5 -> 113.
    expect(
      detail.laps.map((l) => [
        l.stepIndex,
        l.label,
        l.type,
        l.startSeconds,
        l.endSeconds,
        l.averagePower,
        l.targetPower,
      ]),
    ).toEqual([
      [0, "Warm-up", "steady", 0, 600, 140, 138],
      [1, "Sweet spot", "steady", 600, 1800, 226, 225],
      [2, "Recovery", "steady", 1800, 2100, 125, 125],
      [3, "Sweet spot", "steady", 2100, 3300, 224, 225],
      [4, "Recovery", "steady", 3300, 3600, 125, 125],
      [5, "Cool-down", "steady", 3600, 3900, 110, 113],
    ]);
    // Seconds in zones by FTP 250 and Coggan's bounds: 125 W (50%) and 110 W (44%) are zone 1: 300 + 300 + 300 = 900 s;
    // 140 W (56%) zone 2: 600 s; 224 W (89.6%) zone 3: 1200 s; 226 W (90.4%) zone 4: 1200 s.
    expect(detail.secondsInZones).toEqual([900, 600, 1200, 1200, 0, 0, 0]);
    // The efforts are the two steady steps at 90% of FTP or more: planned 225 W, ridden 226 and 224 W.
    expect(detail.plan).toMatchObject({
      stepsRidden: 6,
      stepsPlanned: 6,
      effortTarget: 225,
      effortRange: { minWatts: 224, maxWatts: 226 },
    });
    expect(detail.plan?.workout.name).toBe("Sweet spot");
    // Power never passed the CP of 250 W: W′ was never touched.
    expect(detail.wPrimeLowest).toEqual({ kilojoules: 20, atSec: 0 });
  });

  it("has no plan for a free ride, and null targets", async () => {
    await insertRide(db, rideC());
    const detail = ok<RideDetailResponse>(
      await get("/v1/rides/ride-c"),
      validateRideDetailResponse,
    );
    expect(detail.name).toBe("Free ride");
    expect(detail.plan).toBeNull();
    expect(detail.laps).toEqual([
      {
        stepIndex: null,
        label: null,
        type: null,
        startSeconds: 0,
        endSeconds: 1800,
        averagePower: 250,
        averageHeartRate: null,
        targetPower: null,
      },
    ]);
    // 250 W at FTP 250 is 100% of FTP: zone 4, all 1800 s.
    expect(detail.secondsInZones).toEqual([0, 0, 0, 1800, 0, 0, 0]);
  });

  it("says stale when the workout could not be looked up, and keeps the ride", async () => {
    await insertRide(db, workoutRide("ride-x", "icu-9999"));
    // No snapshot, not in the plan cache, and the fake calendar has no such event: gone, not failed.
    const gone = ok<RideDetailResponse>(await get("/v1/rides/ride-x"), validateRideDetailResponse);
    expect(gone.plan).toBeNull();
    expect(gone.name).toBe("Planned workout");
    expect(gone.stale).toBe(false);
  });

  it("gives the series every 10 s with W′ balance when asked, and only then", async () => {
    await insertRide(db, workoutRide("ride-w", "icu-7001"));
    await h.services.rides.saveWorkoutSnapshot(
      "ride-w",
      workoutOf("icu-7001", "Sweet spot"),
      h.clock.now(),
    );
    const detail = ok<RideDetailResponse>(
      await get("/v1/rides/ride-w?series=true"),
      validateRideDetailResponse,
    );
    // 3900 s at 10 s a step: 390 points, the first the mean of seconds 1 to 10 (140 W, 120 bpm), W′ untouched at 20 kJ.
    expect(detail.series).toHaveLength(390);
    expect(detail.series?.[0]).toEqual({ t: 10, power: 140, heartRate: 120, wBal: 20 });
    expect(detail.series?.[389]).toEqual({ t: 3900, power: 110, heartRate: 120, wBal: 20 });
    expect(detail.series?.[60]).toMatchObject({ t: 610, power: 226 });
  });

  it("uses a longer step for a very long ride so that there are at most 600 points", async () => {
    await insertRide(
      db,
      syntheticRide({
        id: "ride-long",
        startEpochMillis: at("2026-11-12T08:00:00Z"),
        seconds: 7200 * 2,
        power: constant(100),
      }),
    );
    const detail = ok<RideDetailResponse>(
      await get("/v1/rides/ride-long?series=true"),
      validateRideDetailResponse,
    );
    // 14400 s / 600 = 24 s a step.
    expect(detail.series).toHaveLength(600);
    expect(detail.series?.[0]?.t).toBe(24);
  });

  it("answers 404 for a ride that is not stored, 400 for an id that is not one, 400 for a bad flag", async () => {
    expectError(await get("/v1/rides/ride-none"), "ride_not_found");
    expectError(await get("/v1/rides/-nope"), "invalid_ride_id");
    await insertRide(db, rideC());
    expectError(await get("/v1/rides/ride-c?series=maybe"), "invalid_query");
  });
});

describe("GET /v1/rides/{id}/metrics", () => {
  /** 10 s at 300 W and 20 s at 100 W, recorded with an FTP of 200. */
  const surge = () =>
    syntheticRide({
      id: "ride-surge",
      startEpochMillis: at("2026-11-12T17:00:00Z"),
      ftp: 200,
      seconds: 30,
      power: (s) => (s <= 10 ? 300 : 100),
    });

  it("gives the ride's load, curve and why it has no drift", async () => {
    await insertRide(db, rideC());
    const body = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-c/metrics"),
      validateRideMetricsResponse,
    );
    expect(body).toMatchObject({
      rideId: "ride-c",
      startTime: "2026-11-12T19:30:00.000Z",
      date: "2026-11-12",
      ftp: 250,
      durationSec: 1800,
      movingSec: 1800,
      load: 50,
      stale: false,
    });
    expect(body.powerCurve.at(-1)).toEqual({ durationSec: 1800, power: 250 });
    expect(body.powerCurve.find((p) => p.durationSec === 1)).toEqual({
      durationSec: 1,
      power: 250,
    });
    expect(body.drift).toMatchObject({ available: false, driftPercent: null });
    expect(body.drift.reason).toMatch(/The ride is 30 minutes long/);
    expect(body.wPrimeBalance).toBeUndefined();
  });

  it("gives the drift of a steady ride", async () => {
    await insertRide(db, rideA());
    const body = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-a/metrics"),
      validateRideMetricsResponse,
    );
    expect(body.drift).toEqual({
      available: true,
      reason: null,
      driftPercent: 4.8,
      efFirst: 1.429,
      efSecond: 1.361,
      startSec: 0,
      endSec: 3600,
    });
  });

  it("gives the W′ balance only when asked, from the request's CP and W′, with the series every 5 s", async () => {
    await insertRide(db, surge());
    // CP 200 W, W′ 20 kJ, 10 s at 300 W then 20 s at 100 W: D_CP 100 W, tau 516.9 s, the lowest balance 19.01 kJ at second 10
    // (991.35 J used), 5.0% of W′; and the balance every fifth second 19.50, 19.01, 19.02, 19.03, 19.04, 19.05 kJ.
    const body = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-surge/metrics?wbal=true&series=true&cp=200&wPrimeKj=20"),
      validateRideMetricsResponse,
    );
    expect(body.wPrimeBalance).toEqual({
      cpWatts: 200,
      cpSource: "request",
      wPrimeKj: 20,
      wPrimeSource: "request",
      tauSeconds: 516.9,
      dCpWatts: 100,
      minBalanceKj: 19.01,
      minAtSec: 10,
      maxDepletionPercent: 5,
      series: { stepSeconds: 5, balanceKj: [19.5, 19.01, 19.02, 19.03, 19.04, 19.05] },
    });
    const without = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-surge/metrics"),
      validateRideMetricsResponse,
    );
    expect(without.wPrimeBalance).toBeUndefined();
  });

  it("takes CP and W′ from the profile, then from the ride and the default", async () => {
    await insertRide(db, surge());
    const byDefault = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-surge/metrics?wbal=true"),
      validateRideMetricsResponse,
    );
    // No FTP in the profile: the ride's own (200 W) is the CP; no W′: 20 kJ.
    expect(byDefault.wPrimeBalance).toMatchObject({
      cpWatts: 200,
      cpSource: "ride",
      wPrimeKj: 20,
      wPrimeSource: "default",
      minBalanceKj: 19.01,
    });
    expect(byDefault.wPrimeBalance?.series).toBeUndefined();

    await patchProfile({ ftp: 200, wPrimeKj: 18 });
    const byProfile = ok<RideMetricsResponse>(
      await get("/v1/rides/ride-surge/metrics?wbal=true"),
      validateRideMetricsResponse,
    );
    // 18000 - 991.35 = 17008.65 J: 17.01 kJ, 5.5% of W′.
    expect(byProfile.wPrimeBalance).toMatchObject({
      cpSource: "profile",
      wPrimeSource: "profile",
      wPrimeKj: 18,
      minBalanceKj: 17.01,
      maxDepletionPercent: 5.5,
    });
  });

  it.each([
    ["series without wbal", "/v1/rides/ride-surge/metrics?series=true"],
    ["cp without wbal", "/v1/rides/ride-surge/metrics?cp=200"],
    ["a cp that is not a number", "/v1/rides/ride-surge/metrics?wbal=true&cp=fast"],
    ["a cp of 0", "/v1/rides/ride-surge/metrics?wbal=true&cp=0"],
    ["a W′ of 101", "/v1/rides/ride-surge/metrics?wbal=true&wPrimeKj=101"],
    ["a flag that is not one", "/v1/rides/ride-surge/metrics?wbal=yes"],
  ])("answers 400 invalid_query for %s", async (_what, url) => {
    await insertRide(db, surge());
    expectError(await get(url), "invalid_query");
  });

  it("answers 404 for a ride that is not stored", async () => {
    expectError(await get("/v1/rides/ride-none/metrics"), "ride_not_found");
  });
});

describe("computing per ride", () => {
  it("computes a ride when its FIT arrives, without anyone asking", async () => {
    const record = rideC();
    expect((await putRecord(h.app, record.ride.id, record)).statusCode).toBe(201);
    expect((await putFit(h.app, record.ride.id, fitFixture("minimal.fit"))).statusCode).toBe(201);
    await h.services.metrics.whenIdle();
    const rows = await db.query<{ ride_id: string; load: number }>(
      "SELECT ride_id, load FROM ride_metrics",
    );
    expect(rows.rows).toEqual([{ ride_id: "ride-c", load: 50 }]);
  });

  it("backfills the rides stored before the metrics existed, once, and again when the formulas change", async () => {
    await seedWeek();
    expect(await h.services.metrics.ensureComputed()).toBe(4);
    expect(await h.services.metrics.ensureComputed()).toBe(0);
    // A row from older formulas is recomputed.
    await db.exec("UPDATE ride_metrics SET version = 0");
    expect(await h.services.metrics.ensureComputed()).toBe(4);
    // A ride whose row belongs to another record is recomputed too.
    await db.exec("UPDATE ride_metrics SET load = 999 WHERE ride_id = 'ride-c'");
    await db.exec("UPDATE ride_metrics SET record_sha256 = 'old' WHERE ride_id = 'ride-c'");
    expect(await h.services.metrics.ensureComputed()).toBe(1);
    const row = await db.query<{ load: number }>(
      "SELECT load FROM ride_metrics WHERE ride_id = 'ride-c'",
    );
    expect(row.rows[0]?.load).toBe(50);
  });

  it("joins a computation that is already running instead of starting another", async () => {
    await seedWeek();
    const [a, b] = await Promise.all([
      h.services.metrics.ensureComputed(),
      h.services.metrics.ensureComputed(),
    ]);
    expect([a, b]).toEqual([4, 4]);
    expect((await db.query("SELECT 1 FROM ride_metrics")).rows).toHaveLength(4);
  });
});

describe("a year of rides", () => {
  it("answers the metrics of 400 rides well under a second, from the stored rows", {
    timeout: 60_000,
  }, async () => {
    const day = 86_400_000;
    const first = at("2025-10-10T17:00:00Z");
    await db.transaction(async (tx) => {
      for (let i = 0; i < 400; i++) {
        const record = syntheticRide({
          id: `ride-${i}`,
          startEpochMillis: first + i * day,
          seconds: 600,
          power: (s) => 150 + ((s * 13 + i * 7) % 90),
          heartRate: (s) => 110 + (s % 25),
        });
        const stub = { query: tx.query.bind(tx) };
        await insertRideWith(stub, record);
      }
    });
    await patchProfile({ ftp: 250 });
    const computed = await h.services.metrics.ensureComputed();
    expect(computed).toBe(400);

    const started = performance.now();
    const res = await get("/v1/metrics?from=2025-11-18&to=2026-11-18");
    const answered = performance.now() - started;
    const body = ok<MetricsResponse>(res, validateMetricsResponse);
    expect(body.daily).toHaveLength(366);
    expect(body.weekly.length).toBeLessThanOrEqual(54);
    // The rides run from 10 Oct 2025 to 13 Nov 2026, one a day; the range starts on the 39th of them: 361 days with load.
    expect(body.daily.filter((d) => d.load > 0)).toHaveLength(361);
    expect(answered).toBeLessThan(1000);

    const listStarted = performance.now();
    const list = ok<RidesResponse>(await get("/v1/rides?limit=100"), validateRidesResponse);
    expect(list.items).toHaveLength(100);
    expect(performance.now() - listStarted).toBeLessThan(1000);
  });
});

/** `insertRide` on a transaction's `query`. */
async function insertRideWith(
  runner: { query: Db["query"] },
  record: ReturnType<typeof syntheticRide>,
): Promise<void> {
  const { canonicalJson, sha256Hex } = await import("../src/hash.ts");
  const json = canonicalJson(record);
  await runner.query(
    `INSERT INTO rides (id, start_epoch_millis, end_epoch_millis, record, record_sha256, uploaded_by)
     VALUES ($1, $2, $3, $4::jsonb, $5, 'test')`,
    [
      record.ride.id,
      record.ride.startEpochMillis,
      record.ride.endEpochMillis ?? null,
      json,
      sha256Hex(json),
    ],
  );
}
