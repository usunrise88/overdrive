import { createHash } from "node:crypto";
import {
  type Activity,
  type RideRecord,
  validateActivity,
  validateMetricsResponse,
  validateRidesResponse,
} from "@overdrive/contracts";
import Fastify from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { installErrorHandling } from "../src/errors.ts";
import { outdoorRoutes } from "../src/outdoor/routes.ts";
import { bearer, COMPANION, fitFixture, TABLET } from "./helpers/app.ts";
import { clearRides, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { FitBuilder, fitSeconds, frameFit, syntheticRide } from "./helpers/fit-encoder.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import {
  expectApiError,
  expectImport,
  hourRide,
  OUTDOOR_START,
  postFit,
} from "./helpers/outdoor.ts";
import { constant, insertRide, syntheticRide as tabletRide } from "./helpers/synthetic-rides.ts";
import { batch, exercise, get, post } from "./helpers/wellness-data.ts";

/**
 * The import of an outdoor ride from a FIT file (spec §11.8, ADR 0026): `POST /v1/activities/fit`. Files are written by the
 * test-side encoder; every number in them is invented. The hand-worked load: 150 bpm for exactly one hour, resting heart rate 60
 * (nothing is known of the rider's), maximum 190 and threshold 170 give x = 90/130 and x_thr = 110/130, and
 * 100 * y(x) / y(x_thr) with y(x) = x * exp(1.92 * x) is 60.89, so 60.9.
 */

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = startHarness(db, fake, { now: "2027-03-14T12:00:00Z", timezone: "Europe/Paris" });
});

afterAll(async () => {
  await h.app.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearWellness(db);
  await clearRides(db);
  await db.exec("TRUNCATE profile, ride_metrics, ftp_suggestions, ftp_history, ride_workouts");
  fake.reset();
  await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
});

const patchProfile = (body: unknown) =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });

const iso = (ms: number): string => new Date(ms).toISOString();
const sha = (bytes: Uint8Array): string => createHash("sha256").update(bytes).digest("hex");

describe("POST /v1/activities/fit", () => {
  it("imports an hour of an outdoor ride: summary, load from heart rate, and the activity behind GET /v1/activities/{id}", async () => {
    const bytes = hourRide({ totalAscent: 412 });
    const answer = expectImport(await postFit(h.app, bytes), "imported");
    expect(answer.id).toBe(`fit-${sha(bytes).slice(0, 16)}`);
    expect(answer.kind).toBe("activity");
    expect(answer.reason).toBeNull();
    expect(answer.summary).toEqual({
      name: "Outdoor ride",
      start: iso(OUTDOOR_START + 1000),
      end: iso(OUTDOOR_START + 3_601_000),
      startDate: "2027-03-14",
      durationSeconds: 3600,
      distanceMeters: 27_000,
      elevationGainMeters: 412,
      averageHeartRate: 150,
      maxHeartRate: 150,
      load: 60.9,
      // The resting heart rate is the default, an estimate.
      loadApproximate: true,
    });

    const res = await get(h.app, `/v1/activities/${answer.id}`);
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json() as Activity;
    expect(validateActivity(body), JSON.stringify(validateActivity(body))).toMatchObject({
      ok: true,
    });
    expect(body).toMatchObject({
      id: answer.id,
      kind: "activity",
      source: "fit",
      type: "cycling-outdoor",
      name: "Outdoor ride",
      startDate: "2027-03-14",
      durationSeconds: 3600,
      averageHeartRate: 150,
      maxHeartRate: 150,
      load: 60.9,
      countsTowardLoad: true,
      overlapsRideId: null,
      distanceMeters: 27_000,
      elevationGainMeters: 412,
      thresholdHr: 170,
      maxHr: 190,
    });
    // How dense the heart rate in the file is, at the file's own 1 Hz, whatever is stored.
    expect(body.heartRate).toEqual({
      samples: 3600,
      medianGapSeconds: 1,
      maxGapSeconds: 1,
      coverage: 1,
    });
    expect(body.series.length).toBeGreaterThan(0);
    expect(body.series.length).toBeLessThanOrEqual(300);

    // The row keeps the raw bytes whole and the heart rate thinned to one reading in 5 s.
    const row = await db.query<{
      fit: Buffer;
      fit_sha256: string;
      samples: number;
      hr: [number, number][];
    }>("SELECT fit, fit_sha256, samples, hr FROM activities WHERE id = $1", [answer.id]);
    expect(Buffer.from(row.rows[0]?.fit ?? []).equals(bytes)).toBe(true);
    expect(row.rows[0]?.fit_sha256).toBe(sha(bytes));
    expect(row.rows[0]?.samples).toBe(3600);
    expect(row.rows[0]?.hr).toHaveLength(720);
  });

  it("leaves the load null, and the answer valid, while no maximum and threshold heart rate are known", async () => {
    await db.exec("TRUNCATE profile");
    const answer = expectImport(await postFit(h.app, hourRide()), "imported");
    expect(answer.summary.load).toBeNull();
    expect(answer.summary.loadApproximate).toBe(false);
    expect(answer.summary.averageHeartRate).toBe(150);
  });

  it("is idempotent: the same file again is a duplicate of the stored one, and nothing is stored or queued twice", async () => {
    const bytes = hourRide();
    const first = expectImport(await postFit(h.app, bytes), "imported");
    const again = expectImport(await postFit(h.app, bytes), "duplicate");
    expect(again.id).toBe(first.id);
    expect(again.kind).toBe("activity");
    expect(again.reason).toBe("same-file");
    expect(again.summary.load).toBeNull();
    expect(again.summary.startDate).toBe(first.summary.startDate);
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
    expect(count.rows[0]?.n).toBe("1");
    const queue = await db.query<{ activity_id: string; destination: string; state: string }>(
      "SELECT activity_id, destination, state FROM activity_destinations",
    );
    expect(queue.rows).toEqual([
      { activity_id: first.id, destination: "intervals.icu", state: "pending" },
    ]);
  });

  it("answers two uploads of one file at once with one import and one duplicate", async () => {
    const bytes = hourRide();
    const [a, b] = await Promise.all([postFit(h.app, bytes), postFit(h.app, bytes)]);
    expect([a.statusCode, b.statusCode].sort()).toEqual([200, 201]);
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
    expect(count.rows[0]?.n).toBe("1");
  });

  it("needs a device's token", async () => {
    const none = await postFit(h.app, hourRide(), { headers: {} });
    expect(none.statusCode).toBe(401);
    const wrong = await postFit(h.app, hourRide(), { headers: { authorization: "Bearer nope" } });
    expect(wrong.statusCode).toBe(401);
    const tablet = expectImport(
      await postFit(h.app, hourRide(), { headers: bearer(TABLET) }),
      "imported",
    );
    expect(tablet.status).toBe("imported");
  });

  describe("what is refused, with a code of the closed list", () => {
    it("is 415 for another content type and 422 invalid_fit for a body that is not a FIT file", async () => {
      expectApiError(
        await postFit(h.app, "{}", { contentType: "application/json" }),
        415,
        "unsupported_media_type",
      );
      expectApiError(await postFit(h.app, "not a fit file at all"), 422, "invalid_fit");
      expectApiError(await postFit(h.app, new Uint8Array(0)), 422, "invalid_fit");
      const good = hourRide();
      expectApiError(await postFit(h.app, good.subarray(0, good.length - 40)), 422, "invalid_fit");
      const flipped = Buffer.from(good);
      flipped[100] = (flipped[100] ?? 0) ^ 0x55;
      expect(expectApiError(await postFit(h.app, flipped), 422, "invalid_fit")).toMatch(/CRC/);
      // Framing and CRC right, structure nonsense.
      expectApiError(
        await postFit(h.app, frameFit(Uint8Array.from([3, 1, 2, 3]))),
        422,
        "invalid_fit",
      );
      // The tablet's own minimal fixture has a data message without a definition.
      expectApiError(await postFit(h.app, fitFixture("minimal.fit")), 422, "invalid_fit");
    });

    it("is 422 unsupported_sport for another sport and 422 empty_activity for a file with no ride", async () => {
      expect(
        expectApiError(await postFit(h.app, hourRide({ sport: 1 })), 422, "unsupported_sport"),
      ).toMatch(/running/);
      expectApiError(await postFit(h.app, hourRide({ noRecords: true })), 422, "empty_activity");
      expect(
        expectApiError(await postFit(h.app, hourRide({ fileType: 6 })), 422, "invalid_fit"),
      ).toMatch(/not an activity file/);
      const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
      expect(count.rows[0]?.n).toBe("0");
    });

    it("accepts our own tablet's FIT file when no such ride is stored (it is a cycling activity) and says duplicate when it is", async () => {
      const golden = fitFixture("fit-golden-3s.fit");
      const first = expectImport(await postFit(h.app, golden), "imported");
      expect(first.summary.durationSeconds).toBe(3);
      expectImport(await postFit(h.app, golden), "duplicate");
    });

    it("is 413 payload_too_large over the limit (16 MiB in the app, shrunk here)", async () => {
      const small = Fastify();
      installErrorHandling(small);
      small.register(
        async (v1) => {
          v1.register(outdoorRoutes, { service: () => h.services.outdoor, maxBytes: 600 });
        },
        { prefix: "/v1" },
      );
      const big = hourRide();
      expect(big.length).toBeGreaterThan(600);
      expectApiError(await postFit(small, big, { headers: {} }), 413, "payload_too_large");
      await small.close();
    });
  });
});

/** An existing record of the same ride, by kind. */
interface Existing {
  name: string;
  /** Stores it; returns its id. */
  store(): Promise<string>;
  kind: "activity" | "ride";
}

const fitExisting = (): Existing => ({
  name: "an imported ride",
  kind: "activity",
  async store() {
    const answer = expectImport(
      await postFit(h.app, hourRide({ heartRate: () => 140 })),
      "imported",
    );
    return answer.id;
  },
});

const tabletExisting = (startOffsetSeconds = 0): Existing => ({
  name: "a ride on the tablet",
  kind: "ride",
  async store() {
    const record: RideRecord = tabletRide({
      id: "ride-existing",
      startEpochMillis: OUTDOOR_START + 1000 + startOffsetSeconds * 1000,
      seconds: 3600,
      power: constant(180),
    });
    await insertRide(db, record);
    return "ride-existing";
  },
});

const watchExisting = (startOffsetSeconds = 0): Existing => ({
  name: "a workout from the watch",
  kind: "activity",
  async store() {
    const start = OUTDOOR_START + 1000 + startOffsetSeconds * 1000;
    const res = await post(
      h.app,
      batch(exercise("w-1", "biking", iso(start), iso(start + 3_600_000), { title: "Ride" })),
    );
    expect(res.statusCode, res.body).toBe(200);
    return "watch-w-1";
  },
});

describe("duplicate protection: the same ride arriving twice", () => {
  // The new file is an hour from OUTDOOR_START + 1 s (a session of 3600 s), at the heart rate 150; the existing record is the same hour
  // unless the case moves or stretches one of them.
  const cases: {
    name: string;
    existing: Existing;
    file: { startOffsetSeconds?: number; seconds?: number };
    expected: { status: "duplicate"; reason: string } | { status: "imported" };
  }[] = [
    {
      name: "an imported ride, the same hour with other bytes: same start and length",
      existing: fitExisting(),
      file: {},
      expected: { status: "duplicate", reason: "same-start-and-duration" },
    },
    {
      name: "an imported ride, 59 s later and 1.9% longer: still the same start and length",
      existing: fitExisting(),
      file: { startOffsetSeconds: 59, seconds: 3668 },
      expected: { status: "duplicate", reason: "same-start-and-duration" },
    },
    {
      name: "an imported ride, 61 s later: not the same start, but it overlaps almost all of it",
      existing: fitExisting(),
      file: { startOffsetSeconds: 61 },
      expected: { status: "duplicate", reason: "overlap" },
    },
    {
      name: "an imported ride, 3% longer: not the same length, but it overlaps",
      existing: fitExisting(),
      file: { seconds: 3710 },
      expected: { status: "duplicate", reason: "overlap" },
    },
    {
      name: "an imported ride, half an hour later: it overlaps exactly half of the file",
      existing: fitExisting(),
      file: { startOffsetSeconds: 1800 },
      expected: { status: "duplicate", reason: "overlap" },
    },
    {
      name: "an imported ride, a second more than half an hour later: under half, a different ride",
      existing: fitExisting(),
      file: { startOffsetSeconds: 1801 },
      expected: { status: "imported" },
    },
    {
      name: "an imported ride, the next day: a different ride",
      existing: fitExisting(),
      file: { startOffsetSeconds: 86_400 },
      expected: { status: "imported" },
    },
    {
      name: "a ride on the tablet, the same hour",
      existing: tabletExisting(),
      file: {},
      expected: { status: "duplicate", reason: "same-start-and-duration" },
    },
    {
      name: "a ride on the tablet that overlaps 60% of the file",
      existing: tabletExisting(-1440),
      file: {},
      expected: { status: "duplicate", reason: "overlap" },
    },
    {
      name: "a ride on the tablet that overlaps 40% of the file",
      existing: tabletExisting(-2160),
      file: {},
      expected: { status: "imported" },
    },
    {
      name: "a workout from the watch, the same hour",
      existing: watchExisting(),
      file: {},
      expected: { status: "duplicate", reason: "same-start-and-duration" },
    },
    {
      name: "a workout from the watch that overlaps 60% of the file",
      existing: watchExisting(-1440),
      file: {},
      expected: { status: "duplicate", reason: "overlap" },
    },
    {
      name: "a workout from the watch that overlaps 40% of the file",
      existing: watchExisting(-2160),
      file: {},
      expected: { status: "imported" },
    },
  ];

  it.each(cases)("$name", async ({ existing, file, expected }) => {
    const existingId = await existing.store();
    const bytes = hourRide({
      startMs: OUTDOOR_START + (file.startOffsetSeconds ?? 0) * 1000,
      seconds: file.seconds ?? 3600,
      // Other bytes than the stored file, whatever the case.
      heartRate: () => 150,
    });
    const answer = expectImport(await postFit(h.app, bytes), expected.status);
    if (expected.status === "duplicate") {
      expect(answer.id).toBe(existingId);
      expect(answer.kind).toBe(existing.kind);
      expect(answer.reason).toBe(expected.reason);
      const count = await db.query<{ n: string }>(
        "SELECT count(*)::text AS n FROM activities WHERE source = 'fit'",
      );
      expect(Number(count.rows[0]?.n)).toBe(
        existing.kind === "activity" && existingId.startsWith("fit-") ? 1 : 0,
      );
    } else {
      expect(answer.id).toMatch(/^fit-[0-9a-f]{16}$/);
    }
  });

  it("recognises a file that was imported before it by its bytes before anything else", async () => {
    const bytes = hourRide();
    const first = expectImport(await postFit(h.app, bytes), "imported");
    // A tablet ride of the same hour arrives afterwards: the bytes are still the same file.
    await insertRide(
      db,
      tabletRide({
        id: "ride-late",
        startEpochMillis: OUTDOOR_START + 1000,
        seconds: 3600,
        power: constant(150),
      }),
    );
    const again = expectImport(await postFit(h.app, bytes), "duplicate");
    expect(again.id).toBe(first.id);
    expect(again.reason).toBe("same-file");
  });

  it("counts a ride seen by both the watch and the bike computer once, whichever arrives first", async () => {
    const start = OUTDOOR_START + 1000;
    const watch = exercise("w-2", "biking", iso(start), iso(start + 3_600_000), { title: "Ride" });
    // The file first, then the watch session: the session is the same ride and is not counted.
    const imported = expectImport(await postFit(h.app, hourRide()), "imported");
    expect((await post(h.app, batch(watch))).statusCode).toBe(200);
    const session = (await get(h.app, "/v1/activities/watch-w-2")).json() as Activity;
    expect(session.countsTowardLoad).toBe(false);
    expect(session.overlapsRideId).toBe(imported.id);
    expect(session.load).toBeNull();
    expect(validateActivity(session)).toMatchObject({ ok: true });

    const day = await metrics("2027-03-14", "2027-03-14");
    expect(day.daily[0]?.loadOther).toBe(60.9);
    const list = await rides();
    expect(list.items.map((i) => i.id)).toEqual([imported.id]);
  });

  it("does not count an imported ride that a ride from the tablet turns out to overlap", async () => {
    const imported = expectImport(await postFit(h.app, hourRide()), "imported");
    await insertRide(
      db,
      tabletRide({
        id: "ride-late",
        startEpochMillis: OUTDOOR_START + 1000,
        seconds: 3600,
        power: constant(150),
      }),
    );
    const row = (await get(h.app, `/v1/activities/${imported.id}`)).json() as Activity;
    expect(row.countsTowardLoad).toBe(false);
    expect(row.overlapsRideId).toBe("ride-late");
    expect(row.load).toBeNull();
    const list = await rides();
    expect(list.items.map((i) => i.id)).toEqual(["ride-late"]);
  });
});

interface MetricsBody {
  daily: { date: string; load: number; loadCycling: number; loadOther: number }[];
  weekly: { rideLoad: number; otherLoad: number; rides: number; activities: number }[];
  powerCurve: { range: unknown[]; allTime: unknown[] };
  ftp: { current: number | null; suggestion: unknown; latestEstimate: number | null };
  drift: unknown[];
}

async function metrics(from: string, to: string): Promise<MetricsBody> {
  const res = await get(h.app, `/v1/metrics?from=${from}&to=${to}`);
  expect(res.statusCode, res.body).toBe(200);
  expect(validateMetricsResponse(res.json())).toMatchObject({ ok: true });
  return res.json() as MetricsBody;
}

interface RidesBody {
  items: {
    id: string;
    kind: string;
    sport: string | null;
    load: number | null;
    loadApproximate: boolean;
  }[];
}

async function rides(): Promise<RidesBody> {
  const res = await get(h.app, "/v1/rides?limit=50");
  expect(res.statusCode, res.body).toBe(200);
  expect(validateRidesResponse(res.json())).toMatchObject({ ok: true });
  return res.json() as RidesBody;
}

describe("the load of an outdoor ride", () => {
  it("is heart-rate load of the other kind: it feeds fatigue and form, and never the power curve, the FTP estimate or drift, even with power in the file", async () => {
    await patchProfile({ ftp: 250 });
    // A tablet ride with a real effort on the 10th: the power curve, an eFTP and drift come from rides like this one.
    for (const [id, day] of [
      ["ride-a", "2027-03-10"],
      ["ride-b", "2027-03-11"],
      ["ride-c", "2027-03-12"],
    ] as const) {
      await insertRide(
        db,
        tabletRide({
          id,
          startEpochMillis: Date.parse(`${day}T17:00:00Z`),
          seconds: 2700,
          power: constant(260),
          heartRate: (s) => 130 + Math.floor(s / 600),
        }),
      );
    }
    await h.services.metrics.ensureComputed();
    await h.services.metrics.whenIdle();
    const before = await metrics("2027-03-01", "2027-03-14");
    expect(before.powerCurve.allTime.length).toBeGreaterThan(0);
    const suggestionBefore = JSON.stringify(before.ftp);
    const driftBefore = JSON.stringify(before.drift);

    // The file carries 400 W the whole ride, a better effort than any of the rides.
    const answer = expectImport(
      await postFit(h.app, hourRide({ power: () => 400, cadence: () => 90 })),
      "imported",
    );
    expect(answer.summary.load).toBe(60.9);
    await h.services.metrics.whenIdle();

    const after = await metrics("2027-03-01", "2027-03-14");
    expect(after.powerCurve).toEqual(before.powerCurve);
    expect(JSON.stringify(after.ftp)).toBe(suggestionBefore);
    expect(JSON.stringify(after.drift)).toBe(driftBefore);
    expect(after.ftp.latestEstimate).toBe(before.ftp.latestEstimate);

    const day = after.daily.find((d) => d.date === "2027-03-14");
    expect(day).toMatchObject({ load: 60.9, loadCycling: 0, loadOther: 60.9 });
    const week = after.weekly.at(-1);
    expect(week).toMatchObject({ otherLoad: 60.9, activities: 1 });
    // The rides of the week before are not touched.
    expect(after.daily.find((d) => d.date === "2027-03-10")?.loadOther).toBe(0);

    // The list shows the outdoor ride as an activity, with its sport and no power.
    const list = await rides();
    const item = list.items.find((i) => i.id === answer.id);
    expect(item).toMatchObject({ kind: "activity", sport: "cycling-outdoor", load: 60.9 });
    expect(list.items.filter((i) => i.kind === "ride")).toHaveLength(3);
    // No row of the tablet's metrics exists for it.
    const metricRows = await db.query<{ ride_id: string }>("SELECT ride_id FROM ride_metrics");
    expect(metricRows.rows.map((r) => r.ride_id).sort()).toEqual(["ride-a", "ride-b", "ride-c"]);
  });

  it("is approximate when a threshold or the resting heart rate is an estimate, and exact when none is", async () => {
    // Nothing set by the owner, nothing in the rides: no load.
    await db.exec("TRUNCATE profile");
    expect(expectImport(await postFit(h.app, hourRide()), "imported").summary.load).toBeNull();
  });

  it("does not hold a heart rate across a stop: a quarter of an hour without records is not heart rate that went on", async () => {
    // Half an hour riding at 150 bpm, a 15-minute stop with no records (the timer stopped), half an hour riding.
    const gap = syntheticRide({
      startMs: OUTDOOR_START,
      seconds: 3600,
      heartRate: () => 150,
      skip: (s) => s > 1800 && s <= 2700,
    });
    const answer = expectImport(await postFit(h.app, gap), "imported");
    // 45 minutes of heart rate (a sample holds 1 s here, plus at most 30 s at the stop): about three quarters of the hour's 60.9.
    expect(answer.summary.durationSeconds).toBe(3600);
    expect(answer.summary.load).toBeGreaterThan(60.9 * 0.74);
    expect(answer.summary.load).toBeLessThan(60.9 * 0.77);
  });
});

describe("what the outdoor ride is, for the others", () => {
  it("is listed in the rides list, and its distance and climb are in GET /v1/activities/{id}", async () => {
    const answer = expectImport(
      await postFit(h.app, hourRide({ altitude: (s) => 100 + s / 10 })),
      "imported",
    );
    // A steady climb of 360 m: the dead band of 3 m leaves out at most the last stretch.
    expect(answer.summary.elevationGainMeters).toBeGreaterThan(355);
    expect(answer.summary.elevationGainMeters).toBeLessThanOrEqual(360);
    const list = await rides();
    expect(list.items).toHaveLength(1);
    expect(list.items[0]).toMatchObject({
      id: answer.id,
      kind: "activity",
      sport: "cycling-outdoor",
    });
    const detail = (await get(h.app, `/v1/activities/${answer.id}`)).json() as Activity;
    expect(detail.elevationGainMeters).toBe(answer.summary.elevationGainMeters);
    expect(detail.distanceMeters).toBe(27_000);
  });

  it("is 404 for an id that is not stored, and for one that is not an activity id", async () => {
    expect((await get(h.app, "/v1/activities/fit-0000000000000000")).statusCode).toBe(404);
    expect((await get(h.app, "/v1/activities/fit-")).statusCode).toBe(404);
  });

  it("keeps a distance the file does not give out of the answer rather than writing a zero", async () => {
    const builder = new FitBuilder()
      .define(0, 0, [{ num: 0, type: "enum" }])
      .data(0, [4])
      .define(1, 20, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint8" },
      ])
      .define(2, 12, [{ num: 0, type: "enum" }])
      .data(2, [2]);
    for (let s = 0; s < 600; s++) builder.data(1, [fitSeconds(OUTDOOR_START) + s, 140]);
    const answer = expectImport(await postFit(h.app, builder.build()), "imported");
    expect(answer.summary.distanceMeters).toBeNull();
    expect(answer.summary.elevationGainMeters).toBeNull();
    const detail = (await get(h.app, `/v1/activities/${answer.id}`)).json() as Activity;
    expect("distanceMeters" in detail).toBe(false);
    expect(validateActivity(detail)).toMatchObject({ ok: true });
  });
});

describe("forwarding to intervals.icu", () => {
  it("sends the FIT file as it came, once, under an external id, and marks it done", async () => {
    const bytes = hourRide();
    const answer = expectImport(await postFit(h.app, bytes), "imported");
    const forwarder = h.services.wellness.forwarder;
    expect(forwarder).not.toBeNull();
    const summary = await forwarder?.runOnce();
    expect(summary).toMatchObject({ claimed: 1, done: 1 });
    expect(fake.activities).toHaveLength(1);
    expect(fake.activities[0]).toMatchObject({
      external_id: `overdrive-${answer.id}`,
      filename: `${answer.id}.fit`,
      name: "Outdoor ride",
      bytes: bytes.length,
      sha256: sha(bytes),
    });
    expect(fake.manualActivities).toHaveLength(0);
    const queue = await db.query<{ state: string; external_id: string | null }>(
      "SELECT state, external_id FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(queue.rows).toEqual([{ state: "done", external_id: fake.activities[0]?.id }]);
    expect(await forwarder?.runOnce()).toMatchObject({ claimed: 0 });

    // The same file again changes nothing there.
    expectImport(await postFit(h.app, bytes), "duplicate");
    expect(await forwarder?.runOnce()).toMatchObject({ claimed: 0 });
    expect(fake.activities).toHaveLength(1);
  });

  it("does not send it again when intervals.icu already has the ride (the other path, through Strava): found by its start", async () => {
    const answer = expectImport(await postFit(h.app, hourRide()), "imported");
    fake.activities.push({
      id: "i-from-strava",
      external_id: null,
      start_date: iso(OUTDOOR_START + 1000 + 20_000),
      name: "Morning Ride",
      paired_event_id: null,
      sha256: "elsewhere",
      filename: "strava.fit",
      bytes: 1,
    });
    expect(await h.services.wellness.forwarder?.runOnce()).toMatchObject({ claimed: 1, done: 1 });
    expect(fake.requestsTo("POST", "/activities")).toHaveLength(0);
    const queue = await db.query<{ state: string; external_id: string | null }>(
      "SELECT state, external_id FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(queue.rows).toEqual([{ state: "done", external_id: "i-from-strava" }]);
  });

  it("treats a duplicate answer of intervals.icu as done", async () => {
    fake.duplicateStyle = "conflict-409";
    const bytes = hourRide();
    fake.activities.push({
      id: "i-seen",
      external_id: "someone-elses",
      start_date: null,
      name: null,
      paired_event_id: null,
      sha256: sha(bytes),
      filename: "x.fit",
      bytes: bytes.length,
    });
    const answer = expectImport(await postFit(h.app, bytes), "imported");
    expect(await h.services.wellness.forwarder?.runOnce()).toMatchObject({ claimed: 1, done: 1 });
    const queue = await db.query<{ state: string }>(
      "SELECT state FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(queue.rows[0]?.state).toBe("done");
  });

  it("retries after intervals.icu fails, with a growing wait, and sends nothing to Strava", async () => {
    const answer = expectImport(await postFit(h.app, hourRide()), "imported");
    const forwarder = h.services.wellness.forwarder;
    // Every attempt of the lookup and of the upload fails (the client repeats a request four times).
    fake.failNext(8, 500, { path: "/activities" });
    expect(await forwarder?.runOnce()).toMatchObject({ claimed: 1, retry: 1, done: 0 });
    const failed = await db.query<{ state: string; attempts: number; last_error: string }>(
      "SELECT state, attempts, last_error FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(failed.rows[0]).toMatchObject({ state: "pending", attempts: 1 });
    // Not due yet; a minute later it is, and the second pass goes through.
    expect(await forwarder?.runOnce()).toMatchObject({ claimed: 0 });
    h.clock.advance(61_000);
    expect(await forwarder?.runOnce()).toMatchObject({ claimed: 1, done: 1 });
    expect(fake.activities).toHaveLength(1);
    expect(h.services.strava).toBeNull();
    const destinations = await db.query<{ destination: string }>(
      "SELECT DISTINCT destination FROM activity_destinations",
    );
    expect(destinations.rows).toEqual([{ destination: "intervals.icu" }]);
  });

  it("is queued and not sent without an intervals.icu key", async () => {
    const quiet = startHarness(db, fake, {
      now: "2027-03-14T12:00:00Z",
      timezone: "Europe/Paris",
      configured: false,
    });
    expect(quiet.services.wellness.forwarder).toBeNull();
    const answer = expectImport(await postFit(quiet.app, hourRide()), "imported");
    expect(fake.requests).toHaveLength(0);
    const queue = await db.query<{ state: string }>(
      "SELECT state FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(queue.rows).toEqual([{ state: "pending" }]);
    await quiet.app.close();
  });

  it("skips an imported ride that a ride from the tablet overlaps, with a reason in words", async () => {
    const answer = expectImport(await postFit(h.app, hourRide()), "imported");
    await insertRide(
      db,
      tabletRide({
        id: "ride-late",
        startEpochMillis: OUTDOOR_START + 1000,
        seconds: 3600,
        power: constant(150),
      }),
    );
    expect(await h.services.wellness.forwarder?.runOnce()).toMatchObject({
      claimed: 1,
      skipped: 1,
    });
    const queue = await db.query<{ state: string; last_error: string }>(
      "SELECT state, last_error FROM activity_destinations WHERE activity_id = $1",
      [answer.id],
    );
    expect(queue.rows[0]).toEqual({
      state: "skipped",
      last_error: "the same ride is recorded on the tablet, which sends it",
    });
    expect(fake.activities).toHaveLength(0);
  });
});

describe("the migration of a database that already holds the watch's workouts", () => {
  it("leaves them as they were: source watch, no file, no distance", async () => {
    await post(
      h.app,
      batch(
        exercise("w-9", "running", "2027-03-13T08:00:00Z", "2027-03-13T08:40:00Z", {
          title: "Run",
        }),
      ),
    );
    const row = await db.query<{ source: string; fit: unknown; distance_m: number | null }>(
      "SELECT source, fit, distance_m FROM activities WHERE id = 'watch-w-9'",
    );
    expect(row.rows).toEqual([{ source: "watch", fit: null, distance_m: null }]);
    const detail = (await get(h.app, "/v1/activities/watch-w-9")).json() as Activity;
    expect(detail.source).toBe("watch");
    expect("distanceMeters" in detail).toBe(false);
    expect(validateActivity(detail)).toMatchObject({ ok: true });
  });

  it("COMPANION and TABLET are the two devices that may import", () => {
    expect(COMPANION.name).toBe("companion");
    expect(TABLET.name).toBe("tablet");
  });
});
