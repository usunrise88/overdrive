import {
  validateWellnessResponse,
  validateWellnessUploadResponse,
  type WellnessDay,
  type WellnessResponse,
  type WellnessUploadResponse,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, expectError } from "./helpers/app.ts";
import { clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, startHarness } from "./helpers/harness.ts";
import {
  batch,
  get,
  heartRate,
  hrv,
  nightRecords,
  oxygen,
  post,
  respiratory,
  resting,
  sleep,
  steps,
} from "./helpers/wellness-data.ts";

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});

afterAll(async () => {
  await h?.app.close();
  await fake.close();
  await db.close();
});

/** The service with no intervals.icu key (nothing is pulled, so nothing is stale), UTC unless another zone is asked for. */
async function boot(timezone = "UTC"): Promise<void> {
  await h?.app.close();
  await clearWellness(db);
  fake.reset();
  h = startHarness(db, fake, { now: "2026-11-18T08:00:00Z", timezone, configured: false });
}

beforeEach(() => boot());

const count = async (table: string): Promise<number> =>
  Number((await db.query<{ n: string }>(`SELECT count(*)::text AS n FROM ${table}`)).rows[0]?.n);

/** The wellness answer for a range, checked against the contract. */
async function wellness(from: string, to: string): Promise<WellnessResponse> {
  const res = await get(h.app, `/v1/wellness?from=${from}&to=${to}`);
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  expect(validateWellnessResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  return body as WellnessResponse;
}

const day = async (date: string): Promise<WellnessDay> => {
  const answer = await wellness(date, date);
  const [only] = answer.days;
  if (only === undefined) throw new Error("no day");
  return only;
};

describe("POST /v1/wellness", () => {
  it("needs a device's bearer token", async () => {
    expectError(await post(h.app, batch(), {}), "unauthorized");
    expectError(await post(h.app, batch(), { authorization: "Bearer nope" }), "unauthorized");
  });

  it("stores a batch and answers with counts only", async () => {
    const res = await post(
      h.app,
      batch(
        resting("rhr-1", "2026-11-18T06:30:00Z", 52),
        sleep("sleep-1", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z"),
        respiratory("resp-1", "2026-11-18T02:00:00Z", 14.2),
        oxygen("spo2-1", "2026-11-18T03:00:00Z", 96),
        respiratory("resp-2", "2026-11-18T04:00:00Z", 14.4),
      ),
    );
    expect(res.statusCode, res.body).toBe(200);
    const body = res.json() as WellnessUploadResponse;
    expect(validateWellnessUploadResponse(body)).toMatchObject({ ok: true });
    expect(body).toEqual({
      accepted: 5,
      duplicates: 0,
      rejected: 0,
      byType: {
        restingHeartRate: { accepted: 1, duplicates: 0, rejected: 0 },
        sleepSession: { accepted: 1, duplicates: 0, rejected: 0 },
        respiratoryRate: { accepted: 2, duplicates: 0, rejected: 0 },
        oxygenSaturation: { accepted: 1, duplicates: 0, rejected: 0 },
      },
      generatedAt: "2026-11-18T08:00:00.000Z",
    });
    expect(res.headers["cache-control"]).toBe("no-store");
    expect(await count("wellness_records")).toBe(5);
  });

  it("answers an empty batch with zeros", async () => {
    const res = await post(h.app, batch());
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ accepted: 0, duplicates: 0, rejected: 0, byType: {} });
  });

  it("is idempotent by record id: the same batch again is all duplicates and changes nothing", async () => {
    const records = nightRecords("2026-11-18", {
      restingHr: 52,
      respiratoryRate: 14.2,
      spo2: 96,
      sleepingHr: 48,
    });
    const first = (await post(h.app, batch(...records))).json() as WellnessUploadResponse;
    expect(first.accepted).toBe(records.length);
    const before = await day("2026-11-18");
    const again = (await post(h.app, batch(...records))).json() as WellnessUploadResponse;
    expect(again).toMatchObject({ accepted: 0, duplicates: records.length, rejected: 0 });
    expect(await day("2026-11-18")).toEqual(before);
    expect(await count("wellness_records")).toBe(records.length);
  });

  it("replaces a record sent again with a newer lastModified, and ignores an older or equal one", async () => {
    const at = (bpm: number, lastModified: string) => ({
      ...resting("rhr-1", "2026-11-18T06:30:00Z", bpm),
      lastModified,
    });
    expect((await post(h.app, batch(at(52, "2026-11-18T06:31:00Z")))).json()).toMatchObject({
      accepted: 1,
    });
    expect((await day("2026-11-18")).restingHr.value).toBe(52);

    // Newer: replaces.
    expect((await post(h.app, batch(at(55, "2026-11-18T07:00:00Z")))).json()).toMatchObject({
      accepted: 1,
      duplicates: 0,
    });
    expect((await day("2026-11-18")).restingHr.value).toBe(55);

    // Older and equal: duplicates, whatever they say.
    expect((await post(h.app, batch(at(40, "2026-11-18T06:31:00Z")))).json()).toMatchObject({
      accepted: 0,
      duplicates: 1,
    });
    expect((await post(h.app, batch(at(41, "2026-11-18T07:00:00Z")))).json()).toMatchObject({
      accepted: 0,
      duplicates: 1,
    });
    expect((await day("2026-11-18")).restingHr.value).toBe(55);

    // The same content with a newer stamp is still the same record.
    expect((await post(h.app, batch(at(55, "2026-11-18T09:00:00Z")))).json()).toMatchObject({
      accepted: 0,
      duplicates: 1,
    });
    expect(await count("wellness_records")).toBe(1);
  });

  it("replaces a changed record that has no lastModified, as the newer", async () => {
    await post(h.app, batch(resting("rhr-1", "2026-11-18T06:30:00Z", 52)));
    const res = await post(h.app, batch(resting("rhr-1", "2026-11-18T06:30:00Z", 54)));
    expect(res.json()).toMatchObject({ accepted: 1, duplicates: 0 });
    expect((await day("2026-11-18")).restingHr.value).toBe(54);
  });

  it("rejects an id that is already another kind of record, and leaves the stored one alone", async () => {
    await post(h.app, batch(resting("same-id", "2026-11-18T06:30:00Z", 52)));
    const res = await post(
      h.app,
      batch(
        oxygen("same-id", "2026-11-18T06:30:00Z", 96),
        steps("fresh", "2026-11-18T07:00:00Z", "2026-11-18T08:00:00Z", 10),
      ),
    );
    expect(res.json()).toEqual({
      accepted: 1,
      duplicates: 0,
      rejected: 1,
      byType: {
        oxygenSaturation: { accepted: 0, duplicates: 0, rejected: 1 },
        steps: { accepted: 1, duplicates: 0, rejected: 0 },
      },
      generatedAt: "2026-11-18T08:00:00.000Z",
    });
    expect((await day("2026-11-18")).restingHr.value).toBe(52);
    expect((await day("2026-11-18")).spo2.value).toBeNull();
  });

  it("rejects a record that ends more than a day after the service's clock, and takes one that ends within the day", async () => {
    const res = await post(
      h.app,
      batch(
        resting("future", "2026-11-19T09:00:00Z", 52),
        resting("tomorrow-early", "2026-11-19T07:59:00Z", 53),
      ),
    );
    expect(res.json()).toMatchObject({ accepted: 1, rejected: 1 });
  });

  describe("limits", () => {
    const stepsRecord = (i: number) =>
      steps(`s${i}`, "2026-11-18T06:00:00Z", "2026-11-18T06:05:00Z", 1);

    it("takes 2000 records and refuses 2001 with 413 payload_too_large", async () => {
      const ok = await post(
        h.app,
        batch(...Array.from({ length: 2000 }, (_, i) => stepsRecord(i))),
      );
      expect(ok.statusCode, ok.body).toBe(200);
      expect(ok.json()).toMatchObject({ accepted: 2000 });
      const tooMany = await post(
        h.app,
        batch(...Array.from({ length: 2001 }, (_, i) => stepsRecord(i + 5000))),
      );
      const error = expectError(tooMany, "payload_too_large");
      expect(error.message).toContain("2000");
      expect(await count("wellness_records")).toBe(2000);
    });

    it("refuses a body over 4 MiB with 413 payload_too_large, whatever it holds", async () => {
      const padding = " ".repeat(4 * 1024 * 1024);
      const res = await post(h.app, `{"source":"healthconnect","records":[]}${padding}`);
      expectError(res, "payload_too_large");
    });
  });

  describe("a batch that is not valid is refused whole, naming the record and the field, never the value", () => {
    it("422 invalid_record with the index and the field", async () => {
      const res = await post(
        h.app,
        batch(
          resting("ok-1", "2026-11-18T06:30:00Z", 52),
          resting("bad", "2026-11-18T06:30:00Z", 913),
          resting("ok-2", "2026-11-18T07:30:00Z", 54),
        ),
      );
      const error = expectError(res, "invalid_record");
      expect(error.message).toBe("/records/1/bpm must be <= 300");
      expect(error.message).not.toContain("913");
      // Nothing was stored: not even the valid ones around it.
      expect(await count("wellness_records")).toBe(0);
    });

    it("says what is missing, what is the wrong type, and which instant is not real", async () => {
      const res = await post(h.app, {
        source: "healthconnect",
        records: [
          { kind: "respiratoryRate", id: "a", time: "2026-02-30T06:30:00Z", rate: 14 },
          { kind: "oxygenSaturation", id: "b", time: "2026-11-18T06:30:00Z" },
          {
            kind: "steps",
            id: "c",
            start: "2026-11-18T06:30:00Z",
            end: "2026-11-18T06:35:00Z",
            count: "many",
          },
          {
            kind: "sleepSession",
            id: "d",
            start: "2026-11-18T06:30:00Z",
            end: "2026-11-18T05:30:00Z",
          },
        ],
      });
      const error = expectError(res, "invalid_record");
      expect(error.message).toContain("/records/0/time is not a real date and time");
      expect(error.message).toContain("/records/1 must have required property 'percent'");
      expect(error.message).toContain("/records/2/count must be integer");
      expect(error.message).toContain("/records/3/end is before /start");
    });

    it("refuses an unknown kind, a repeated id and a source that is not Health Connect", async () => {
      expect(
        expectError(
          await post(h.app, { source: "healthconnect", records: [{ kind: "height", id: "x" }] }),
          "invalid_record",
        ).message,
      ).toContain("/records/0/kind");
      expect(
        expectError(
          await post(
            h.app,
            batch(
              resting("dup", "2026-11-18T06:30:00Z", 52),
              resting("dup", "2026-11-18T07:30:00Z", 53),
            ),
          ),
          "invalid_record",
        ).message,
      ).toContain("/records/1/id is used by an earlier record");
      expect(
        expectError(await post(h.app, { source: "fitbit", records: [] }), "invalid_record").message,
      ).toContain("source");
    });

    it("is 400 invalid_json for a body that is not JSON, without quoting any of it", async () => {
      const res = await post(h.app, '{"source":"healthconnect","records":[{"bpm":81234, oops');
      const error = expectError(res, "invalid_json");
      expect(error.message).toBe("The body is not valid JSON");
      expect(res.body).not.toContain("81234");
    });

    it("is 415 for another content type and 422 for a body that is not an object", async () => {
      const text = await h.app.inject({
        method: "POST",
        url: "/v1/wellness",
        headers: {
          "content-type": "text/plain",
          ...bearer({ name: "companion", token: "companion-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" }),
        },
        payload: "x",
      });
      expectError(text, "unsupported_media_type");
      expectError(await post(h.app, "[1,2,3]"), "invalid_record");
      expectError(await post(h.app, "null"), "invalid_record");
    });
  });
});

describe("the daily figures worked out from the records", () => {
  it("makes a night of sleep, bedtime and waking, with the asleep time from the stages", async () => {
    await post(
      h.app,
      batch(
        sleep("night", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", {
          stages: [
            { stage: "light", start: "2026-11-17T22:45:00Z", end: "2026-11-18T00:45:00Z" },
            { stage: "awake", start: "2026-11-18T00:45:00Z", end: "2026-11-18T01:00:00Z" },
            { stage: "deep", start: "2026-11-18T01:00:00Z", end: "2026-11-18T03:00:00Z" },
            { stage: "out_of_bed", start: "2026-11-18T03:00:00Z", end: "2026-11-18T03:30:00Z" },
            { stage: "rem", start: "2026-11-18T03:30:00Z", end: "2026-11-18T06:15:00Z" },
          ],
        }),
      ),
    );
    const d = await day("2026-11-18");
    // 2 h + 2 h + 2 h 45 min asleep = 6 h 45 min; awake and out of bed do not count.
    expect(d.sleepSeconds).toMatchObject({ value: 24300, source: "healthconnect" });
    // 22:45 the evening before is 75 minutes before midnight; 06:15 is 375 after it.
    expect(d.bedtimeMinutes.value).toBe(-75);
    expect(d.wakeMinutes.value).toBe(375);
    expect(d.sleepStart).toBe("2026-11-17T22:45:00Z");
    expect(d.sleepEnd).toBe("2026-11-18T06:15:00Z");
    // The evening's date has no night of its own.
    expect((await day("2026-11-17")).sleepSeconds.value).toBeNull();
  });

  it("counts the whole session when there are no stages", async () => {
    await post(h.app, batch(sleep("night", "2026-11-17T23:00:00Z", "2026-11-18T06:30:00Z")));
    expect((await day("2026-11-18")).sleepSeconds.value).toBe(7.5 * 3600);
  });

  it("adds the sessions of a broken night and leaves a nap out", async () => {
    await post(
      h.app,
      batch(
        sleep("first", "2026-11-17T23:00:00Z", "2026-11-18T03:00:00Z"),
        sleep("second", "2026-11-18T03:30:00Z", "2026-11-18T06:30:00Z"),
        // A nap that afternoon is not part of the night (it starts after noon), nor is one the afternoon before.
        sleep("nap", "2026-11-18T14:00:00Z", "2026-11-18T14:40:00Z"),
        sleep("nap-before", "2026-11-17T13:00:00Z", "2026-11-17T13:30:00Z"),
      ),
    );
    const d = await day("2026-11-18");
    expect(d.sleepSeconds.value).toBe(7 * 3600);
    expect(d.sleepStart).toBe("2026-11-17T23:00:00Z");
    expect(d.sleepEnd).toBe("2026-11-18T06:30:00Z");
    expect(d.bedtimeMinutes.value).toBe(-60);
    expect(d.wakeMinutes.value).toBe(390);
    // The nap ended on the 18th too but is not a night; the one the day before has no night either.
    expect((await day("2026-11-17")).sleepSeconds.value).toBeNull();
  });

  it("is not a night when it is shorter than ten minutes", async () => {
    await post(h.app, batch(sleep("blip", "2026-11-18T04:00:00Z", "2026-11-18T04:05:00Z")));
    expect((await day("2026-11-18")).sleepSeconds.value).toBeNull();
  });

  it("gives the night to the date of the morning it ends in the rider's time zone", async () => {
    await boot("Europe/Paris");
    // 22:10 on the 17th in Paris (UTC+1) to 00:30 on the 18th... and a longer night: 22:10 to 06:40.
    await post(h.app, batch(sleep("night", "2026-11-17T21:10:00Z", "2026-11-18T05:40:00Z")));
    const d = await day("2026-11-18");
    expect(d.sleepSeconds.value).toBe(8.5 * 3600);
    expect(d.bedtimeMinutes.value).toBe(-110);
    expect(d.wakeMinutes.value).toBe(400);
    // A night that ends after midnight there but before it in UTC belongs to the Paris date.
    await post(h.app, batch(sleep("late", "2026-11-18T18:30:00Z", "2026-11-18T23:30:00Z")));
    const next = await day("2026-11-19");
    expect(next.wakeMinutes.value).toBe(30);
    expect(next.sleepSeconds.value).toBe(5 * 3600);
    // 19:30 in Paris, after the 18:00 evening cut-off of the 18th: this is the 19th's night.
    expect(next.bedtimeMinutes.value).toBe(-270);
  });

  it("takes the latest resting heart rate of the day and ignores an impossible one", async () => {
    await post(
      h.app,
      batch(
        resting("early", "2026-11-18T05:00:00Z", 50),
        resting("late", "2026-11-18T12:00:00Z", 53),
        resting("glitch", "2026-11-18T13:00:00Z", 12),
      ),
    );
    expect((await day("2026-11-18")).restingHr.value).toBe(53);
  });

  it("averages the respiratory rate and oxygen saturation inside the night, not the daytime", async () => {
    await post(
      h.app,
      batch(
        sleep("night", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z"),
        respiratory("r1", "2026-11-17T23:30:00Z", 14),
        respiratory("r2", "2026-11-18T03:00:00Z", 15),
        respiratory("day", "2026-11-18T14:00:00Z", 22),
        oxygen("o1", "2026-11-18T01:00:00Z", 95),
        oxygen("o2", "2026-11-18T04:00:00Z", 97),
        oxygen("o-low", "2026-11-18T05:00:00Z", 40),
        oxygen("o-day", "2026-11-18T15:00:00Z", 99),
        hrv("h1", "2026-11-18T02:00:00Z", 60),
        hrv("h2", "2026-11-18T04:00:00Z", 70),
      ),
    );
    const d = await day("2026-11-18");
    expect(d.respiratoryRate.value).toBe(14.5);
    expect(d.spo2.value).toBe(96);
    expect(d.hrvRmssd.value).toBe(65);
  });

  it("uses the records of the date when there is no night", async () => {
    await post(
      h.app,
      batch(
        respiratory("a", "2026-11-18T09:00:00Z", 16),
        respiratory("b", "2026-11-18T10:00:00Z", 18),
        respiratory("other-day", "2026-11-19T09:00:00Z", 30),
      ),
    );
    expect((await day("2026-11-18")).respiratoryRate.value).toBe(17);
  });

  describe("heart rate in sleep needs enough samples", () => {
    const night = sleep("night", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z");

    it("takes the mean of dense samples: one every ten minutes", async () => {
      await post(
        h.app,
        batch(
          night,
          heartRate("hr", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", 10, (i) =>
            i % 2 === 0 ? 46 : 50,
          ),
        ),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBeCloseTo(48, 0);
    });

    it("takes one every twenty minutes, the sparsest that counts", async () => {
      // 7.5 h = 450 minutes: 23 samples at 20-minute gaps.
      await post(
        h.app,
        batch(
          night,
          heartRate("hr", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", 20, () => 47),
        ),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBe(47);
    });

    it("gives none from samples that are sparser than one every twenty minutes", async () => {
      await post(
        h.app,
        batch(
          night,
          heartRate("hr", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", 25, () => 47),
        ),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBeNull();
    });

    it("gives none from fewer than twenty samples, however close together", async () => {
      await post(
        h.app,
        batch(
          sleep("short", "2026-11-18T01:00:00Z", "2026-11-18T04:00:00Z"),
          heartRate("hr", "2026-11-18T01:00:00Z", "2026-11-18T03:50:00Z", 10, () => 47),
        ),
      );
      // 18 samples in 3 hours.
      expect((await day("2026-11-18")).sleepingHr.value).toBeNull();
      await post(
        h.app,
        batch(heartRate("hr2", "2026-11-18T01:00:00Z", "2026-11-18T03:00:00Z", 5, () => 47)),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBe(47);
    });

    it("ignores samples outside the night and impossible ones", async () => {
      await post(
        h.app,
        batch(
          night,
          heartRate("in", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", 10, () => 48),
          heartRate("awake", "2026-11-18T08:00:00Z", "2026-11-18T10:00:00Z", 1, () => 90),
          heartRate("glitch", "2026-11-18T01:00:00Z", "2026-11-18T01:30:00Z", 10, () => 250),
        ),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBe(48);
    });

    it("is worked out again when the heart-rate records arrive after the night", async () => {
      await post(h.app, batch(night));
      expect((await day("2026-11-18")).sleepingHr.value).toBeNull();
      await post(
        h.app,
        batch(heartRate("late", "2026-11-17T22:45:00Z", "2026-11-18T06:15:00Z", 10, () => 49)),
      );
      expect((await day("2026-11-18")).sleepingHr.value).toBe(49);
    });
  });

  it("adds the steps that start on the date", async () => {
    await post(
      h.app,
      batch(
        steps("a", "2026-11-18T07:00:00Z", "2026-11-18T08:00:00Z", 1200),
        steps("b", "2026-11-18T12:00:00Z", "2026-11-18T13:00:00Z", 800),
        steps("c", "2026-11-19T07:00:00Z", "2026-11-19T08:00:00Z", 999),
      ),
    );
    expect((await day("2026-11-18")).steps).toBe(2000);
  });

  it("moves a night when its session is changed to another date", async () => {
    const session = (end: string, lastModified: string) => ({
      ...sleep("night", "2026-11-17T22:45:00Z", end),
      lastModified,
    });
    await post(h.app, batch(session("2026-11-18T06:15:00Z", "2026-11-18T06:20:00Z")));
    expect((await day("2026-11-18")).sleepSeconds.value).toBe(7.5 * 3600);
    // Edited: it now runs to the morning after.
    await post(
      h.app,
      batch({
        ...sleep("night", "2026-11-18T22:45:00Z", "2026-11-19T06:15:00Z"),
        lastModified: "2026-11-19T06:20:00Z",
      }),
    );
    expect((await day("2026-11-18")).sleepSeconds.value).toBeNull();
    expect((await day("2026-11-19")).sleepSeconds.value).toBe(7.5 * 3600);
  });

  it("keeps the raw records compactly, with the samples as pairs", async () => {
    await post(
      h.app,
      batch(heartRate("hr", "2026-11-18T01:00:00Z", "2026-11-18T01:30:00Z", 10, (i) => 50 + i)),
    );
    const row = (
      await db.query<{ payload: { h: number[][]; s: number } }>(
        "SELECT payload FROM wellness_records",
      )
    ).rows[0];
    expect(row?.payload.h).toEqual([
      [0, 50],
      [600000, 51],
      [1200000, 52],
      [1800000, 53],
    ]);
    expect(row?.payload.s).toBe(Date.parse("2026-11-18T01:00:00Z"));
  });
});

describe("GET /v1/wellness", () => {
  it("answers the range asked, every day in it, in the shape of the contract", async () => {
    await post(
      h.app,
      batch(
        ...nightRecords("2026-11-17", { restingHr: 52 }),
        ...nightRecords("2026-11-18", { restingHr: 54 }),
      ),
    );
    const answer = await wellness("2026-11-16", "2026-11-18");
    expect(answer.days.map((d) => d.date)).toEqual(["2026-11-16", "2026-11-17", "2026-11-18"]);
    expect(answer.days.map((d) => d.restingHr.value)).toEqual([null, 52, 54]);
    expect(answer).toMatchObject({
      from: "2026-11-16",
      to: "2026-11-18",
      stale: false,
      nightsNeeded: 14,
      baselineDays: 42,
    });
    expect(answer.generatedAt).toBe("2026-11-18T08:00:00.000Z");
  });

  it("is not cached", async () => {
    expect(
      (await get(h.app, "/v1/wellness?from=2026-11-18&to=2026-11-18")).headers["cache-control"],
    ).toBe("no-store");
  });

  it.each([
    ["no range", "/v1/wellness"],
    ["no to", "/v1/wellness?from=2026-11-18"],
    ["a date that is not real", "/v1/wellness?from=2026-02-30&to=2026-03-01"],
    ["a reversed range", "/v1/wellness?from=2026-11-18&to=2026-11-17"],
    ["more than 186 days", "/v1/wellness?from=2026-01-01&to=2026-11-18"],
  ])("is 400 invalid_range for %s", async (_name, url) => {
    expectError(await get(h.app, url), "invalid_range");
  });

  it("serves 186 days", async () => {
    const res = await get(h.app, "/v1/wellness?from=2026-05-17&to=2026-11-18");
    expect(res.statusCode).toBe(200);
    expect((res.json() as WellnessResponse).days).toHaveLength(186);
  });
});
