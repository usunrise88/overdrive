import {
  type RepeatDetailResponse,
  type RepeatPreviousResponse,
  type RepeatsResponse,
  validateApiError,
  validateRepeatDetailResponse,
  validateRepeatPreviousResponse,
  validateRepeatsResponse,
  workoutHash,
} from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION } from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import {
  at,
  attempt,
  DAYS,
  overall,
  SESSION,
  SESSION_CHANGED,
  SESSION_RENAMED,
  store,
} from "./helpers/repeats-data.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * Repeat-session comparison through the API (spec §11.7, ADR 0026): detection by the hash of the workout's structure, the
 * list, the attempts side by side with their trend, and the attempt before a ride. Rides are synthetic with planted heart rates;
 * every expected number is worked out by hand in the comments.
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
  await db.exec("TRUNCATE ride_metrics, ride_workouts, ride_repeat_stats, profile");
});

const get = (url: string): Promise<LightMyRequestResponse> =>
  h.app.inject({ method: "GET", url, headers: bearer(COMPANION) });

const stash = (record: ReturnType<typeof attempt>, workout = SESSION) =>
  store(db, h.services.rides, record, workout);

/** The step rates of the standard history: heart rate falling by 2, 1 and 2 bpm between attempts. */
const RATES = [
  [150, 120, 156],
  [148, 118, 154],
  [147, 117, 153],
  [145, 115, 151],
] as const;

/** Four attempts at the session, the second one from a renamed copy of the workout. */
async function history(): Promise<void> {
  const workouts = [SESSION, SESSION_RENAMED, SESSION, SESSION_RENAMED];
  for (const [i, rates] of RATES.entries()) {
    await stash(
      attempt({ id: `ride-${i + 1}`, start: at(DAYS[i] as string), heartRates: rates }),
      workouts[i] as typeof SESSION,
    );
  }
}

describe("detection: two rides are the same session when the workouts' structures match", () => {
  it("matches a renamed copy and not a changed step, and a ride with no workout never matches", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }), SESSION);
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]) }), SESSION_RENAMED);
    // One step differs: another session, ridden once.
    await stash(
      attempt({ id: "ride-3", start: at(DAYS[2]), workoutId: SESSION_CHANGED.id }),
      SESSION_CHANGED,
    );
    // A free ride, and a ride that names a workout of which no copy was kept: no hash, no match.
    await insertRide(
      db,
      syntheticRide({ id: "ride-4", startEpochMillis: Date.parse(at(DAYS[3])), seconds: 600 }),
    );
    await insertRide(db, attempt({ id: "ride-5", start: at(DAYS[4]), workoutId: "icu-gone" }));

    const res = await get("/v1/repeats");
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json() as RepeatsResponse;
    expect(
      validateRepeatsResponse(body),
      JSON.stringify(validateRepeatsResponse(body)),
    ).toMatchObject({ ok: true });
    expect(body.repeats).toHaveLength(1);
    const [session] = body.repeats;
    expect(session?.hash).toBe(workoutHash(SESSION));
    expect(session?.hash).toBe(workoutHash(SESSION_RENAMED));
    expect(session?.hash).not.toBe(workoutHash(SESSION_CHANGED));
    expect(session).toMatchObject({
      // The name of the latest attempt's copy.
      name: "Over-unders, copy",
      routeKey: null,
      attempts: 2,
      firstDate: DAYS[0],
      lastDate: DAYS[1],
    });
    expect(session?.latest.rideId).toBe("ride-2");
    expect(session?.previous.rideId).toBe("ride-1");
  });

  it("stores the hash with the ride's workout, and works it out for the rides stored before it existed, once", async () => {
    await history();
    const stored = await db.query<{ workout_hash: string; workout_hash_version: number }>(
      "SELECT DISTINCT workout_hash, workout_hash_version FROM ride_workouts",
    );
    expect(stored.rows).toEqual([{ workout_hash: workoutHash(SESSION), workout_hash_version: 1 }]);

    // Rides stored before the migration have no hash.
    await db.exec("UPDATE ride_workouts SET workout_hash = NULL, workout_hash_version = NULL");
    expect(await h.services.repeats.ensureHashes()).toBe(4);
    expect(await h.services.repeats.ensureHashes()).toBe(0);
    const again = await db.query<{ n: string }>(
      "SELECT count(*)::text AS n FROM ride_workouts WHERE workout_hash = $1",
      [workoutHash(SESSION)],
    );
    expect(again.rows[0]?.n).toBe("4");

    // A read does it as well.
    await db.exec("UPDATE ride_workouts SET workout_hash = NULL, workout_hash_version = NULL");
    const res = await get("/v1/repeats");
    expect((res.json() as RepeatsResponse).repeats).toHaveLength(1);
  });

  it("is an empty list when nothing has been ridden twice", async () => {
    expect(((await get("/v1/repeats")).json() as RepeatsResponse).repeats).toEqual([]);
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }));
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(body.repeats).toEqual([]);
    expect(validateRepeatsResponse(body)).toMatchObject({ ok: true });
  });

  it("lists the sessions most recently ridden first", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }), SESSION);
    await stash(attempt({ id: "ride-2", start: at(DAYS[2]) }), SESSION);
    await stash(
      attempt({ id: "ride-3", start: at(DAYS[1]), workoutId: SESSION_CHANGED.id }),
      SESSION_CHANGED,
    );
    await stash(
      attempt({ id: "ride-4", start: at(DAYS[3]), workoutId: SESSION_CHANGED.id }),
      SESSION_CHANGED,
    );
    const body = (await get("/v1/repeats")).json() as RepeatsResponse;
    expect(validateRepeatsResponse(body)).toMatchObject({ ok: true });
    expect(body.repeats.map((r) => r.lastDate)).toEqual([DAYS[3], DAYS[2]]);
    expect(body.repeats.map((r) => r.hash)).toEqual([
      workoutHash(SESSION_CHANGED),
      workoutHash(SESSION),
    ]);
  });

  it("compares the latest attempt with the previous one: heart rate at the same power, drift and compliance", async () => {
    await history();
    const [session] = ((await get("/v1/repeats")).json() as RepeatsResponse).repeats;
    // Attempts 3 and 4: overall (600 * 147 + 300 * 117 + 600 * 153) / 1500 = 143.4 and (600 * 145 + 300 * 115 + 600 * 151) / 1500 = 141.4.
    expect(overall(RATES[2])).toBe(143.4);
    expect(overall(RATES[3])).toBe(141.4);
    expect(session).toMatchObject({
      attempts: 4,
      latest: { rideId: "ride-4", date: DAYS[3] },
      previous: { rideId: "ride-3", date: DAYS[2] },
    });
    expect(session?.comparison.heartRateAtPower).toEqual({
      latest: 141.4,
      previous: 143.4,
      difference: -2,
      reason: null,
    });
    // The rides are 32 minutes: no steady stretch of 40 minutes for the drift.
    expect(session?.comparison.driftPercent).toMatchObject({
      latest: null,
      previous: null,
      difference: null,
    });
    expect(session?.comparison.driftPercent.reason).toMatch(/^the latest attempt has none: /);
    expect(session?.comparison.compliance).toEqual({
      latest: 100,
      previous: 100,
      difference: 0,
      reason: null,
    });
  });

  it("says why there is no heart-rate comparison when the previous attempt has no figure for a compared step", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]), heartRate: () => null }));
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]), heartRates: [148, 118, 154] }));
    const [session] = ((await get("/v1/repeats")).json() as RepeatsResponse).repeats;
    expect(session?.comparison.heartRateAtPower).toMatchObject({
      latest: overall([148, 118, 154]),
      previous: null,
      difference: null,
    });
    expect(session?.comparison.heartRateAtPower.reason).toBe(
      "the previous attempt has none: step 2: no heart rate was recorded at the target power",
    );
  });

  it("needs a device's token", async () => {
    const res = await h.app.inject({ method: "GET", url: "/v1/repeats" });
    expect(res.statusCode).toBe(401);
  });
});

describe("GET /v1/repeats/{hash}: every attempt side by side", () => {
  const hash = workoutHash(SESSION);

  it("has the steps, the heart rate at the same power of every attempt and the trend, with the numbers worked out by hand", async () => {
    await history();
    const res = await get(`/v1/repeats/${hash}`);
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json() as RepeatDetailResponse;
    expect(
      validateRepeatDetailResponse(body),
      JSON.stringify(validateRepeatDetailResponse(body)),
    ).toMatchObject({
      ok: true,
    });
    expect(body).toMatchObject({ hash, name: "Over-unders, copy", routeKey: null });
    expect(body.note).toMatch(/heat, sleep and caffeine/);
    expect(body.definition).toEqual({
      powerTolerancePercent: 3,
      skipSeconds: 60,
      minStepSeconds: 120,
      minTrendAttempts: 3,
    });
    expect(body.steps).toEqual([
      { step: 2, label: "On", durationSeconds: 600, targetFraction: 0.88 },
      { step: 3, label: "Off", durationSeconds: 300, targetFraction: 0.5 },
      { step: 4, label: "Steady", durationSeconds: 600, targetFraction: 0.88 },
    ]);
    expect(body.comparedSteps).toEqual([2, 3, 4]);
    expect(body.attempts.map((a) => [a.number, a.rideId, a.date])).toEqual([
      [1, "ride-1", DAYS[0]],
      [2, "ride-2", DAYS[1]],
      [3, "ride-3", DAYS[2]],
      [4, "ride-4", DAYS[3]],
    ]);

    // Per step, heart rate at 220 W, 125 W and 220 W after the first 60 s of each step (the settling seconds are left out).
    for (const [i, a] of body.attempts.entries()) {
      expect(a.stepHeartRates.map((s) => s.averageHeartRate)).toEqual(RATES[i]);
      expect(a.stepHeartRates.map((s) => [s.targetWatts, s.seconds])).toEqual([
        [220, 540],
        [125, 240],
        [220, 540],
      ]);
      expect(a.ftp).toBe(250);
      expect(a.effortRating).toBeNull();
      expect(a.heartRateAtPower).toEqual({
        value: overall(RATES[i] as readonly [number, number, number]),
        seconds: 1320,
        reason: null,
      });
      expect(a.compliance).toMatchObject({ percent: 100, stepsCompleted: 5, stepsPlanned: 5 });
      expect(a.durationSeconds).toBe(1920);
      expect(a.averageHeartRate).toBeGreaterThan(100);
    }
    expect(body.attempts.map((a) => a.heartRateAtPower.value)).toEqual([
      146.4, 144.4, 143.4, 141.4,
    ]);

    // The least-squares line over 146.4, 144.4, 143.4 and 141.4: mean 143.9, covariance -8, variance 5: slope -1.6 per attempt,
    // -4.8 over the three gaps.
    expect(body.trends.heartRateAtPower).toEqual({
      unit: "bpm",
      betterWhen: "lower",
      attempts: 4,
      minAttempts: 3,
      direction: "improving",
      slopePerAttempt: -1.6,
      change: -4.8,
      steadyBelow: 2,
      reason: null,
    });
    // Compliance is 100% every time: steady. No ride had a steady stretch of 40 minutes: no drift trend.
    expect(body.trends.compliance).toMatchObject({
      direction: "steady",
      slopePerAttempt: 0,
      change: 0,
      attempts: 4,
    });
    expect(body.trends.driftPercent).toMatchObject({
      direction: "unknown",
      attempts: 0,
      slopePerAttempt: null,
    });
    expect(body.trends.driftPercent.reason).toMatch(/at least 3 attempts/);
    for (const a of body.attempts) {
      expect(a.driftPercent.value).toBeNull();
      expect(a.driftPercent.reason).not.toBeNull();
    }
  });

  it("says the trend is unknown below three attempts, and still compares two", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]), heartRates: [150, 120, 156] }));
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]), heartRates: [140, 110, 146] }));
    const body = (await get(`/v1/repeats/${hash}`)).json() as RepeatDetailResponse;
    expect(validateRepeatDetailResponse(body)).toMatchObject({ ok: true });
    expect(body.attempts.map((a) => a.heartRateAtPower.value)).toEqual([146.4, 136.4]);
    expect(body.trends.heartRateAtPower).toMatchObject({
      direction: "unknown",
      attempts: 2,
      change: null,
    });
    expect(body.trends.heartRateAtPower.reason).toBe(
      "a trend needs at least 3 attempts with a figure; there are 2",
    );
  });

  it("has no overall figure for an attempt at another FTP, and leaves it out of the trend", async () => {
    // The third attempt was ridden at FTP 262: its steps ask for 230.6 W, 131 W and 230.6 W.
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]), heartRates: RATES[0] }));
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]), heartRates: RATES[1] }));
    await stash(attempt({ id: "ride-3", start: at(DAYS[2]), heartRates: RATES[2], ftp: 262 }));
    await stash(attempt({ id: "ride-4", start: at(DAYS[3]), heartRates: RATES[3] }));
    const body = (await get(`/v1/repeats/${hash}`)).json() as RepeatDetailResponse;
    expect(validateRepeatDetailResponse(body)).toMatchObject({ ok: true });
    const third = body.attempts[2];
    expect(third?.ftp).toBe(262);
    expect(third?.stepHeartRates.map((s) => s.targetWatts)).toEqual([230.6, 131, 230.6]);
    expect(third?.heartRateAtPower.value).toBeNull();
    expect(third?.heartRateAtPower.reason).toMatch(
      /the target power in step 2 was 230\.6 W against 220 W/,
    );
    expect(body.trends.heartRateAtPower.attempts).toBe(3);
    // 146.4, 144.4 and 141.4: slope (141.4 - 146.4) / 2 = -2.5 per attempt, -5 in all.
    expect(body.trends.heartRateAtPower).toMatchObject({
      slopePerAttempt: -2.5,
      change: -5,
      direction: "improving",
    });
  });

  it("compares over the steps the latest attempt has, and gives an attempt that stopped early no figure", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]), heartRates: RATES[0] }));
    // Stopped at second 900: step 2 is done, steps 3 and 4 are not ridden.
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]), heartRates: RATES[1], seconds: 900 }));
    await stash(attempt({ id: "ride-3", start: at(DAYS[2]), heartRates: RATES[2] }));
    const body = (await get(`/v1/repeats/${hash}`)).json() as RepeatDetailResponse;
    expect(validateRepeatDetailResponse(body)).toMatchObject({ ok: true });
    expect(body.comparedSteps).toEqual([2, 3, 4]);
    const short = body.attempts[1];
    expect(short?.heartRateAtPower).toEqual({
      value: null,
      seconds: 0,
      reason: "step 3: the step was not ridden",
    });
    expect(short?.stepHeartRates[0]).toMatchObject({ step: 2, averageHeartRate: 148 });
    expect(short?.compliance).toMatchObject({ stepsCompleted: 2, stepsPlanned: 5 });
    // 900 of 1920 s: 46.9%.
    expect(short?.compliance.percent).toBe(46.9);
    expect(body.trends.heartRateAtPower.attempts).toBe(2);
    // 100%, 46.9% and 100%: a straight line through them is flat (a trend is a line, not a verdict on one ride).
    expect(body.trends.compliance).toMatchObject({
      attempts: 3,
      slopePerAttempt: 0,
      direction: "steady",
    });
  });

  it("is 404 for a hash that is not a session ridden at least twice, and for one that is not a hash", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }));
    for (const url of [
      `/v1/repeats/${hash}`,
      "/v1/repeats/0123456789abcdef",
      "/v1/repeats/NOT-A-HASH",
      "/v1/repeats/b1add004",
    ]) {
      const res = await get(url);
      expect(res.statusCode, url).toBe(404);
      expect(validateApiError(res.json())).toMatchObject({ ok: true });
      expect((res.json() as { error: { code: string } }).error.code).toBe("not_found");
    }
  });

  it("works out each ride's figures once and keeps them: a second read does not read the records", async () => {
    await history();
    await get(`/v1/repeats/${hash}`);
    const kept = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM ride_repeat_stats");
    expect(kept.rows[0]?.n).toBe("4");
    // The rows are tied to the record: make one out of date and it is worked out again.
    await db.exec(
      "UPDATE ride_repeat_stats SET record_sha256 = 'stale', steps = '[]'::jsonb WHERE ride_id = 'ride-2'",
    );
    const body = (await get(`/v1/repeats/${hash}`)).json() as RepeatDetailResponse;
    expect(body.attempts[1]?.stepHeartRates.map((s) => s.averageHeartRate)).toEqual(RATES[1]);
  });
});

describe("GET /v1/repeats/{hash}/previous: the attempt before a ride, for the overlay", () => {
  const hash = workoutHash(SESSION);

  it("gives the attempt before the ride asked about, with its step boundaries and heart rate at each steady step", async () => {
    await history();
    const res = await get(`/v1/repeats/${hash}/previous?before=ride-4`);
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json() as RepeatPreviousResponse;
    expect(
      validateRepeatPreviousResponse(body),
      JSON.stringify(validateRepeatPreviousResponse(body)),
    ).toMatchObject({
      ok: true,
    });
    expect(body.before).toBe("ride-4");
    expect(body.previous).toEqual({
      rideId: "ride-3",
      date: DAYS[2],
      startTime: `${DAYS[2]}T17:00:00.000Z`,
      durationSeconds: 1920,
      ftp: 250,
      attempt: 3,
      heartRateAtPower: 143.4,
      steps: [
        {
          step: 1,
          label: "Warm-up",
          startSeconds: 0,
          endSeconds: 300,
          targetWatts: null,
          averageHeartRate: null,
        },
        {
          step: 2,
          label: "Sweet spot",
          startSeconds: 300,
          endSeconds: 900,
          targetWatts: 220,
          averageHeartRate: 147,
        },
        {
          step: 3,
          label: "Recover",
          startSeconds: 900,
          endSeconds: 1200,
          targetWatts: 125,
          averageHeartRate: 117,
        },
        {
          step: 4,
          label: "Sweet spot",
          startSeconds: 1200,
          endSeconds: 1800,
          targetWatts: 220,
          averageHeartRate: 153,
        },
        {
          step: 5,
          label: "Free ride",
          startSeconds: 1800,
          endSeconds: 1920,
          targetWatts: null,
          averageHeartRate: null,
        },
      ],
    });
    // Small enough for the overlay to carry.
    expect(res.body.length).toBeLessThan(1500);
  });

  it("is the latest attempt without before, and the one before any ride that is not stored yet but carries its start", async () => {
    await history();
    const latest = (await get(`/v1/repeats/${hash}/previous`)).json() as RepeatPreviousResponse;
    expect(latest.before).toBeNull();
    expect(latest.previous?.rideId).toBe("ride-4");
    expect(latest.previous?.attempt).toBe(4);

    // A ride being ridden now: not stored, its id carries its start.
    const live = `ride-${Date.parse(at("2026-11-18"))}`;
    const body = (
      await get(`/v1/repeats/${hash}/previous?before=${live}`)
    ).json() as RepeatPreviousResponse;
    expect(body.previous?.rideId).toBe("ride-4");
    // And one between the second and third attempt gets the second.
    const between = `ride-${Date.parse(at("2026-10-30"))}`;
    expect(
      (
        (
          await get(`/v1/repeats/${hash}/previous?before=${between}`)
        ).json() as RepeatPreviousResponse
      ).previous?.rideId,
    ).toBe("ride-2");
  });

  it("has previous null before the first attempt, and for a session never ridden", async () => {
    await history();
    const first = (
      await get(`/v1/repeats/${hash}/previous?before=ride-1`)
    ).json() as RepeatPreviousResponse;
    expect(first.previous).toBeNull();
    expect(validateRepeatPreviousResponse(first)).toMatchObject({ ok: true });
    const never = await get("/v1/repeats/0123456789abcdef/previous");
    expect(never.statusCode).toBe(200);
    expect((never.json() as RepeatPreviousResponse).previous).toBeNull();
  });

  it("works for a session ridden once: that ride is the previous attempt of the one on the tablet now", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }));
    const body = (
      await get(`/v1/repeats/${hash}/previous?before=ride-${Date.parse(at(DAYS[1]))}`)
    ).json() as RepeatPreviousResponse;
    expect(body.previous?.rideId).toBe("ride-1");
    expect(body.previous?.attempt).toBe(1);
  });

  it("is 404 ride_not_found for a ride that is not stored and does not carry its start, and 400 for one that is not an id", async () => {
    await history();
    const unknown = await get(`/v1/repeats/${hash}/previous?before=my-ride`);
    expect(unknown.statusCode).toBe(404);
    expect((unknown.json() as { error: { code: string } }).error.code).toBe("ride_not_found");
    const bad = await get(`/v1/repeats/${hash}/previous?before=../x`);
    expect(bad.statusCode).toBe(400);
    expect((bad.json() as { error: { code: string } }).error.code).toBe("invalid_ride_id");
    expect((await get("/v1/repeats/xyz/previous")).statusCode).toBe(404);
  });

  it("leaves a step ridden out of order out of the boundaries, so they never overlap", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]) }));
    // A ride that went to step 4 first (seconds 1 to 300), then back to step 2 and on: made by shifting the step indexes.
    const odd = attempt({ id: "ride-2", start: at(DAYS[1]) });
    for (const sample of odd.samples) {
      const second = sample.timeOffsetSeconds;
      if (second <= 300) sample.stepIndex = 3;
    }
    odd.laps = [
      { startSeconds: 0, endSeconds: 300, stepIndex: 3 },
      { startSeconds: 300, endSeconds: 900, stepIndex: 1 },
      { startSeconds: 900, endSeconds: 1200, stepIndex: 2 },
      { startSeconds: 1200, endSeconds: 1920, stepIndex: 4 },
    ];
    await stash(odd);
    const body = (
      await get(`/v1/repeats/${hash}/previous?before=ride-${Date.parse(at(DAYS[2]))}`)
    ).json() as RepeatPreviousResponse;
    expect(
      validateRepeatPreviousResponse(body),
      JSON.stringify(validateRepeatPreviousResponse(body)),
    ).toMatchObject({
      ok: true,
    });
    // Step 4 was ridden first; steps 2 and 3 came after it and are left out; step 5 follows it.
    expect(body.previous?.steps.map((s) => s.step)).toEqual([4, 5]);
  });
});

describe("privacy: no measured value in a log line", () => {
  it("logs counts and ids when it works the figures out, never a heart rate", async () => {
    await stash(attempt({ id: "ride-1", start: at(DAYS[0]), heartRates: [177, 133, 171] }));
    await stash(attempt({ id: "ride-2", start: at(DAYS[1]), heartRates: [176, 132, 170] }));
    await db.exec("UPDATE ride_workouts SET workout_hash = NULL, workout_hash_version = NULL");
    h.services.repeats.start();
    await h.services.repeats.whenIdle();
    await get("/v1/repeats");
    await get(`/v1/repeats/${workoutHash(SESSION)}`);
    await get(`/v1/repeats/${workoutHash(SESSION)}/previous?before=ride-2`);
    const text = h.logging.text();
    for (const secret of ["177", "133", "171", "176", "132", "170"]) {
      expect(new RegExp(`(?<![0-9a-zA-Z.])${secret}(?![0-9a-zA-Z])`).test(text), secret).toBe(
        false,
      );
    }
    expect(
      h.logging.lines.some((l) => l.message === "workout hashes worked out for the stored rides"),
    ).toBe(true);
  });
});
