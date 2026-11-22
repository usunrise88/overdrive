import {
  type ProgressResponse,
  validateApiError,
  validateProgressResponse,
} from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION } from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * Progress over time through the API (spec §6.3 "Progress over time", ADR 0043), on PGlite: rides are synthetic, the watch's daily
 * figures are rows of `wellness_daily`, every expected number is worked out by hand in the comments. The clock is injected; the
 * weeks and months across the clock changes of Europe/Belgrade are tested with rides a few minutes either side of midnight.
 */

let db: Db;
let fake: FakeIntervals;
let h: Harness;
let belgrade: Harness;
let configured: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = startHarness(db, fake, {
    configured: false,
    now: "2026-11-18T08:00:00Z",
    timezone: "Europe/Paris",
  });
  belgrade = startHarness(db, fake, {
    configured: false,
    now: "2027-05-03T08:00:00Z",
    timezone: "Europe/Belgrade",
  });
  configured = startHarness(db, fake, { now: "2026-11-18T08:00:00Z", timezone: "Europe/Paris" });
});

afterAll(async () => {
  for (const harness of [h, belgrade, configured]) {
    await harness.services.metrics.whenIdle();
    await harness.services.progress.whenIdle();
    await harness.app.close();
  }
  await fake.close();
  await db.close();
});

afterEach(() => {
  vi.restoreAllMocks();
});

beforeEach(async () => {
  await clearRides(db);
  await db.exec(
    "TRUNCATE ride_metrics, ride_progress_stats, ride_workouts, ftp_history, profile, wellness_daily",
  );
});

const get = (
  url: string,
  harness: Harness = h,
  headers = bearer(COMPANION),
): Promise<LightMyRequestResponse> => harness.app.inject({ method: "GET", url, headers });

async function progress(url = "/v1/progress", harness: Harness = h): Promise<ProgressResponse> {
  const res = await get(url, harness);
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  const checked = validateProgressResponse(body);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return body as ProgressResponse;
}

/** A steady ride: `seconds` long, 90 W for the first 120 s (under 40% of the 250 W FTP) and `watts` after, at `bpm`. */
function steadyRide(id: string, start: string, seconds: number, watts: number, bpm: number) {
  return syntheticRide({
    id,
    startEpochMillis: Date.parse(start),
    ftp: 250,
    seconds,
    power: (s) => (s <= 120 ? 90 : watts),
    heartRate: () => bpm,
  });
}

const setFtp = (date: string, ftp: number | null) =>
  db.query("INSERT INTO ftp_history (changed_at, ftp) VALUES ($1::timestamptz, $2)", [
    `${date}T10:00:00Z`,
    ftp,
  ]);

async function wellness(
  metric: "restingHr" | "weightKg",
  day: string,
  value: number,
): Promise<void> {
  await db.query(
    "INSERT INTO wellness_daily (day, metric, source, value) VALUES ($1::date, $2, 'healthconnect', $3)",
    [day, metric, value],
  );
}

describe("GET /v1/progress", () => {
  it("answers an empty history with a valid body, no verdict and no headline", async () => {
    const answer = await progress();
    expect(answer.headline).toBeNull();
    // The default range: the last six months up to today (2026-11-18 in Paris).
    expect(answer.range).toEqual({ from: "2026-05-18", to: "2026-11-18" });
    expect(answer.generatedAt).toBe("2026-11-18T08:00:00Z");
    expect(answer.stale).toBe(false);
    expect(answer.bestPower.months.map((m) => m.month)).toEqual([
      "2026-05",
      "2026-06",
      "2026-07",
      "2026-08",
      "2026-09",
      "2026-10",
      "2026-11",
    ]);
    expect(answer.bestPower.months.at(-1)?.partial).toBe(true);
    expect(answer.efficiencyFactor.rides).toEqual([]);
    expect(answer.restingHeartRate.trend).toMatchObject({ direction: "unknown", points: 0 });
    expect(answer.weight.weightChange).toBeNull();
  });

  it("is no-store, needs a device token and refuses a range that is not one, naming the parameter", async () => {
    const res = await get("/v1/progress");
    expect(res.headers["cache-control"]).toBe("no-store");
    expect((await get("/v1/progress", h, {})).statusCode).toBe(401);
    expect((await get("/v1/progress", h, { authorization: "Bearer nope" })).statusCode).toBe(401);

    const refused = async (query: string, parameter: string) => {
      const bad = await get(`/v1/progress?${query}`);
      expect(bad.statusCode, query).toBe(400);
      const body: unknown = bad.json();
      expect(validateApiError(body), query).toMatchObject({ ok: true });
      const error = (body as { error: { code: string; message: string } }).error;
      expect(error.code).toBe("invalid_range");
      expect(error.message, query).toContain(parameter);
    };
    await refused("from=June", "from");
    await refused("to=2026-02-30", "to");
    await refused("from=2026-11-03&to=2026-11-01", "from");
    await refused("from=2026-11-19", "from");
    await refused("from=2021-11-17&to=2026-11-18", "5 years");
    await refused("from=2026-01-01&from=2026-02-01", "from");
    // Exactly five years is allowed.
    expect((await get("/v1/progress?from=2021-11-18&to=2026-11-18")).statusCode).toBe(200);
    // A `to` after today is today, and the answer says so.
    const future = await progress("/v1/progress?from=2026-10-01&to=2026-12-31");
    expect(future.range).toEqual({ from: "2026-10-01", to: "2026-11-18" });
  });

  it("works out the ride measures from steady rides: efficiency factor, drift, aerobic efficiency and best power", async () => {
    // The FTP was 250 W since January; the threshold heart rate is 170 bpm: references 0.70 x 250 = 175 W and 0.80 x 170 = 136 -> 135 bpm.
    await setFtp("2026-01-10", 250);
    expect(
      (
        await h.app.inject({
          method: "PATCH",
          url: "/v1/profile",
          headers: { "content-type": "application/json", ...bearer() },
          payload: JSON.stringify({ thresholdHeartRate: 170, maxHeartRate: 190 }),
        })
      ).statusCode,
    ).toBe(200);
    // Five rides of 50 minutes (a Thursday each week from 1 Oct), 180 W after the warm-up, the heart rate falling a beat each time.
    const bpms = [140, 139, 138, 137, 136];
    for (const [i, bpm] of bpms.entries()) {
      const day = String(1 + 7 * i).padStart(2, "0");
      const start = i < 4 ? `2026-10-${day}T07:00:00Z` : "2026-10-29T07:00:00Z";
      await insertRide(db, steadyRide(`ride-${i + 1}`, start, 3000, 180, bpm));
    }
    const answer = await progress("/v1/progress?from=2026-09-28&to=2026-11-18");

    // Each ride has one steady stretch from second 120 (the first 120 s are under the floor) to the end: 2880 s; EF = NP / average
    // heart rate = 180 / bpm: 1.286, 1.295, 1.304, 1.314, 1.324.
    expect(answer.efficiencyFactor.rides.map((r) => r.value)).toEqual([
      1.286, 1.295, 1.304, 1.314, 1.324,
    ]);
    expect(answer.efficiencyFactor.rides[0]).toMatchObject({
      date: "2026-10-01",
      rideId: "ride-1",
      name: "Free ride",
      normalizedPower: 180,
      averageHeartRate: 140,
      seconds: 2880,
    });
    // Slope 0.0095 a ride, change 0.038 > 0.02 (the means of the first and last five rides are the same five).
    expect(answer.efficiencyFactor.trend).toMatchObject({
      direction: "improving",
      change: 0.038,
      points: 5,
    });
    expect(answer.efficiencyFactor.rolling5).toHaveLength(1);

    // The existing drift metric (spec 6.3) on the whole ride: the 2-minute warm-up at 90 W against the same heart rate makes the first
    // half's efficiency factor lower than the second's, -1.8% for each ride, five rides: steady.
    expect(answer.decoupling.rides.map((r) => r.driftPercent)).toEqual([
      -1.8, -1.8, -1.8, -1.8, -1.8,
    ]);
    expect(answer.decoupling.trend).toMatchObject({ direction: "steady", points: 5, minPoints: 5 });

    // 180 W is within 5% of 175 W (166.25 to 183.75): heart rate at power is each ride's, a week at a time.
    expect(answer.aerobicEfficiency).toMatchObject({
      ftpAtStart: 250,
      referencePower: 175,
      referenceHeartRate: 135,
      referenceHeartRateBasis: "threshold",
    });
    expect(answer.aerobicEfficiency.weeks.slice(0, 5).map((w) => w.heartRateAtPower)).toEqual(bpms);
    expect(answer.aerobicEfficiency.weeks[0]?.atPower).toEqual({ stretches: 1, seconds: 2880 });
    expect(answer.aerobicEfficiency.trend).toMatchObject({
      direction: "improving",
      change: -4,
      from: 140,
      latest: 136,
      verdictText: "Heart rate at the same power is trending down: fitter",
    });
    // Within 3 bpm of 135: 136, 137 and 138, the last three weeks, 180 W each: three weeks are not four.
    expect(answer.aerobicEfficiency.weeks.map((w) => w.powerAtHeartRate).slice(0, 5)).toEqual([
      null,
      null,
      180,
      180,
      180,
    ]);
    expect(answer.aerobicEfficiency.powerTrend).toMatchObject({
      direction: "unknown",
      points: 3,
      reason: "3 of 4 weeks have a figure. Ride steady for 5 minutes or more to add one.",
    });

    // Best power: 180 W for 5 and 20 minutes (the warm-up is 90 W), no ride of an hour; all in October.
    const october = answer.bestPower.months.find((m) => m.month === "2026-10");
    expect(october?.d300).toMatchObject({ watts: 180, rideId: "ride-5", date: "2026-10-29" });
    expect(october?.d1200).toMatchObject({ watts: 180 });
    expect(october?.d3600).toBeNull();
    expect(answer.headline).toMatchObject({ measure: "aerobicEfficiency", direction: "improving" });
  });

  it("reads the watch: weekly resting heart rate, weight and watts per kilogram with the FTP in force that week", async () => {
    await setFtp("2026-01-10", 250);
    await setFtp("2026-10-12", 260);
    // Resting heart rate on the first six nights of each of eight weeks from 28 Sep: 55, 54, ... 48.
    for (let w = 0; w < 8; w++) {
      for (let d = 0; d < 6; d++) {
        const day = new Date(Date.UTC(2026, 8, 28 + 7 * w + d)).toISOString().slice(0, 10);
        await wellness("restingHr", day, 55 - w);
        if (d < 2) await wellness("weightKg", day, 72);
      }
    }
    const answer = await progress("/v1/progress?from=2026-09-28&to=2026-11-18");
    expect(answer.restingHeartRate.weeks).toHaveLength(8);
    expect(answer.restingHeartRate.weeks[0]).toEqual({
      weekStart: "2026-09-28",
      mean: 55,
      nights: 6,
    });
    expect(answer.restingHeartRate.trend).toMatchObject({
      direction: "improving",
      change: -7,
      verdictText: "Resting heart rate is trending down: better",
    });
    // 250 W until the 12th, 260 W after: 3.47 then 3.61 W/kg (72 kg).
    expect(answer.weight.weeks[1]).toMatchObject({ kg: 72, ftp: 250, wattsPerKg: 3.47 });
    expect(answer.weight.weeks[2]).toMatchObject({ kg: 72, ftp: 260, wattsPerKg: 3.61 });
    // 3.47 for two weeks, 3.61 for six: a rise of about 0.1 W/kg.
    expect(answer.weight.trend).toMatchObject({ direction: "improving", unit: "W/kg", points: 8 });
    expect(answer.weight.weightChange).toMatchObject({ difference: 0 });
    // 55 to 48 bpm over seven weeks (7 bpm band 1): more than 3.47 to 3.61 against its band of 0.05 (2.8x vs 7x): the heart rate is clearer.
    expect(answer.headline?.measure).toBe("restingHeartRate");
  });

  it("says stale when the watch's source has not been read", async () => {
    const answer = await progress("/v1/progress", configured);
    // An intervals.icu key is set and nothing has been pulled: the figures may be out of date.
    expect(answer.stale).toBe(true);
  });

  it("works out each ride's stats once and keeps them: the second read opens no record", async () => {
    await setFtp("2026-01-10", 250);
    for (let i = 0; i < 3; i++) {
      await insertRide(
        db,
        steadyRide(`ride-${i + 1}`, `2026-10-0${i + 1}T07:00:00Z`, 1500, 180, 140),
      );
    }
    const first = await progress("/v1/progress?from=2026-09-01&to=2026-11-18");
    expect(first.efficiencyFactor.rides).toHaveLength(3);
    const stored = await db.query<{ n: string }>(
      "SELECT count(*)::text AS n FROM ride_progress_stats",
    );
    expect(stored.rows[0]?.n).toBe("3");

    const spy = vi.spyOn(h.services.rides, "getRecord");
    const second = await progress("/v1/progress?from=2026-09-01&to=2026-11-18");
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
    expect({ ...second, generatedAt: "" }).toEqual({ ...first, generatedAt: "" });

    // A ride stored later is worked out on the next read, and only that one.
    await insertRide(db, steadyRide("ride-4", "2026-10-09T07:00:00Z", 1500, 180, 141));
    const spyAgain = vi.spyOn(h.services.rides, "getRecord");
    const third = await progress("/v1/progress?from=2026-09-01&to=2026-11-18");
    // One record for the metrics of the new ride and one for its stretches; the other three are not opened.
    expect(spyAgain).toHaveBeenCalledTimes(2);
    expect(spyAgain.mock.calls.map((call) => call[0])).toEqual(["ride-4", "ride-4"]);
    spyAgain.mockRestore();
    expect(third.efficiencyFactor.rides).toHaveLength(4);
  });

  it("reads a five-year range from the kept stats: many rides, no record opened twice", async () => {
    await setFtp("2022-01-01", 250);
    // 130 short rides, one every two weeks from late 2021: five years of riding.
    const startDay = Date.UTC(2021, 11, 1);
    for (let i = 0; i < 130; i++) {
      const start = new Date(startDay + i * 14 * 86_400_000).toISOString().slice(0, 10);
      await insertRide(db, steadyRide(`old-${i}`, `${start}T07:00:00Z`, 700, 150 + (i % 40), 140));
    }
    const answer = await progress("/v1/progress?from=2021-11-18&to=2026-11-18");
    expect(answer.bestPower.months.length).toBe(61);
    expect(answer.aerobicEfficiency.weeks.length).toBeGreaterThan(250);
    const spy = vi.spyOn(h.services.rides, "getRecord");
    await progress("/v1/progress?from=2021-11-18&to=2026-11-18");
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  }, 60_000);

  it("is not moved by a clock change: the week and the month are those of the rider's own midnight", async () => {
    await setFtp("2026-01-10", 250);
    // Reference 175 W at an FTP of 250 W; each ride of 700 s is one stretch of 640 s at the given power (all within 5% of 175 W).
    const rides: [string, string, number][] = [
      // The clocks went back on 25 Oct 2026 at 01:00 UTC (03:00 to 02:00): Belgrade is UTC+1 from then on, UTC+2 before.
      ["r4", "2026-10-25T22:30:00Z", 168], // 23:30 on Sunday 25 Oct: the week of 19 Oct, October
      ["r5", "2026-10-25T23:30:00Z", 172], // 00:30 on Monday 26 Oct: the week of 26 Oct
      ["r1", "2026-10-31T22:30:00Z", 173], // 23:30 on Saturday 31 Oct: October (UTC+1: a naive +2 would say 00:30 on 1 Nov)
      ["r2", "2026-10-31T23:30:00Z", 174], // 00:30 on Sunday 1 Nov: November, still the week of 26 Oct
      ["r3", "2026-11-01T23:30:00Z", 176], // 00:30 on Monday 2 Nov: the week of 2 Nov
      // The clocks went forward on 28 Mar 2027 at 01:00 UTC (02:00 to 03:00): UTC+2 from then on, UTC+1 before.
      ["r6", "2027-03-28T21:30:00Z", 169], // 23:30 on Sunday 28 Mar: the week of 22 Mar
      ["r7", "2027-03-28T22:30:00Z", 171], // 00:30 on Monday 29 Mar: the week of 29 Mar (a naive +1 would say Sunday)
      ["r8", "2027-03-31T21:30:00Z", 175], // 23:30 on Wednesday 31 Mar: March
      ["r9", "2027-03-31T22:30:00Z", 177], // 00:30 on Thursday 1 Apr: April (a naive +1 would say 23:30 on 31 Mar)
    ];
    for (const [id, start, watts] of rides)
      await insertRide(db, steadyRide(id, start, 700, watts, 140));
    const answer = await progress("/v1/progress?from=2026-10-19&to=2027-04-04", belgrade);

    const stretches = (monday: string) =>
      answer.aerobicEfficiency.weeks.find((w) => w.weekStart === monday)?.atPower.stretches;
    expect(stretches("2026-10-19")).toBe(1); // r4
    expect(stretches("2026-10-26")).toBe(3); // r5, r1, r2
    expect(stretches("2026-11-02")).toBe(1); // r3
    expect(stretches("2027-03-22")).toBe(1); // r6
    expect(stretches("2027-03-29")).toBe(3); // r7, r8, r9
    const month = (name: string) => answer.bestPower.months.find((m) => m.month === name)?.d300;
    // October: r4 168, r5 172, r1 173 -> 173 on 31 Oct. November: r2 174, r3 176 -> 176 on 2 Nov.
    expect(month("2026-10")).toMatchObject({ watts: 173, rideId: "r1", date: "2026-10-31" });
    expect(month("2026-11")).toMatchObject({ watts: 176, rideId: "r3", date: "2026-11-02" });
    // March: r6 169, r7 171, r8 175 -> 175 on 31 Mar. April: r9 177 on 1 Apr.
    expect(month("2027-03")).toMatchObject({ watts: 175, rideId: "r8", date: "2027-03-31" });
    expect(month("2027-04")).toMatchObject({ watts: 177, rideId: "r9", date: "2027-04-01" });
    // The days of the week hold across the change, too: seven days from each Monday, 25 hours long once and 23 once.
    expect(
      answer.aerobicEfficiency.weeks.every(
        (w) => new Date(`${w.weekStart}T00:00:00Z`).getUTCDay() === 1,
      ),
    ).toBe(true);
    // Today is 3 May 2027: April is over, so no month of the range is partial.
    expect(answer.bestPower.months.every((m) => !m.partial)).toBe(true);
  });

  it("leaves a ride out of the figures it cannot give: the ride measures need power and heart rate together", async () => {
    await setFtp("2026-01-10", 250);
    // A ride with power and no heart rate: a best power, no stretch, no efficiency factor, no drift.
    await insertRide(
      db,
      syntheticRide({
        id: "no-hr",
        startEpochMillis: Date.parse("2026-10-05T07:00:00Z"),
        ftp: 250,
        seconds: 1800,
        power: () => 200,
      }),
    );
    const answer = await progress("/v1/progress?from=2026-09-01&to=2026-11-18");
    expect(answer.bestPower.months.find((m) => m.month === "2026-10")?.d1200).toMatchObject({
      watts: 200,
    });
    expect(answer.efficiencyFactor.rides).toEqual([]);
    expect(answer.decoupling.rides).toEqual([]);
    expect(answer.aerobicEfficiency.weeks.every((w) => w.atPower.stretches === 0)).toBe(true);
  });

  it("does not log a figure", async () => {
    await insertRide(db, steadyRide("ride-1", "2026-10-01T07:00:00Z", 1500, 183, 147));
    await wellness("restingHr", "2026-10-01", 51);
    h.logging.lines.length = 0;
    await progress("/v1/progress?from=2026-09-01&to=2026-11-18");
    const text = h.logging.text();
    expect(text).not.toMatch(/\b183\b|\b147\b/);
  });
});
