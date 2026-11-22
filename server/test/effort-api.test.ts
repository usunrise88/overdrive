import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type Activity,
  addDaysToIsoDate,
  type EffortResponse,
  type MetricsResponse,
  validateEffortResponse,
} from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION, expectError } from "./helpers/app.ts";
import { clearRides, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { hourRide, OUTDOOR_START, postFit } from "./helpers/outdoor.ts";
import { constant, insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";
import { batch, exercise, heartRateSeconds, post } from "./helpers/wellness-data.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    timezone: "Europe/Belgrade",
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.services.metrics.whenIdle();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await h.services.metrics.whenIdle();
  await clearRides(db);
  await clearWellness(db);
  await db.exec("TRUNCATE ride_metrics, ftp_suggestions, ftp_history, profile, ride_workouts");
  h.clock.set("2026-11-18T08:00:00Z");
});

const get = (url: string): Promise<LightMyRequestResponse> =>
  h.app.inject({ method: "GET", url, headers: bearer(COMPANION) });
const patchProfile = (body: unknown): Promise<LightMyRequestResponse> =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });

async function effort(query = ""): Promise<EffortResponse> {
  const res = await get(`/v1/effort${query}`);
  expect(res.statusCode, res.body).toBe(200);
  expect(res.headers["cache-control"]).toBe("no-store");
  const body: unknown = res.json();
  const checked = validateEffortResponse(body);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return body as EffortResponse;
}

/**
 * A ride whose training load is `load`: at the FTP of the ride (250 W) for `load / 100` of an hour, so the score is exactly the
 * number of seconds over 36.
 */
const ride = (id: string, start: string, load: number) =>
  syntheticRide({
    id,
    startEpochMillis: Date.parse(start),
    seconds: Math.round(load * 36),
    power: constant(250),
  });

const addRide = (id: string, start: string, load: number) => insertRide(db, ride(id, start, load));

/** A ride each Wednesday from `first` (a Monday) at 17:00 UTC, the loads of the list (0 is a week off). */
async function wednesdays(first: string, loads: number[]): Promise<void> {
  for (const [i, load] of loads.entries()) {
    if (load === 0) continue;
    await addRide(`ride-w${i}`, `${addDaysToIsoDate(first, 7 * i + 2)}T17:00:00Z`, load);
  }
}

/** The loads of ten completed weeks from Monday 2026-09-07; the current week begins on 2026-11-16. */
const L1 = [100, 150, 90, 200, 160, 0, 140, 110, 130, 120];

describe("GET /v1/effort", () => {
  it("needs a device token", async () => {
    expectError(await h.app.inject({ method: "GET", url: "/v1/effort" }), "unauthorized");
  });

  it("answers twelve weeks without a parameter, and as many as it is asked for up to fifty-two", async () => {
    const twelve = await effort();
    expect(twelve.weeks).toHaveLength(12);
    expect(twelve.weeks[11]).toMatchObject({ weekStart: "2026-11-16", current: true });
    expect(twelve.weeks[0]?.weekStart).toBe("2026-08-31");
    expect(await effort("?weeks=1")).toMatchObject({ weeks: [{ weekStart: "2026-11-16" }] });
    expect((await effort("?weeks=52")).weeks).toHaveLength(52);
  });

  it("refuses a number of weeks that is not a whole number from 1 to 52, naming the parameter", async () => {
    for (const query of ["0", "53", "abc", "1.5", "", "12abc", "-1", "1e1", "0012x"]) {
      const error = expectError(await get(`/v1/effort?weeks=${query}`), "invalid_query");
      expect(error.message, query).toMatch(/weeks/);
    }
    expectError(await get("/v1/effort?weeks=1&weeks=2"), "invalid_query");
  });

  it("works out the weeks, each usual range and the verdicts from the rides, as worked by hand", async () => {
    await wednesdays("2026-09-07", L1);
    await addRide("ride-t", "2026-11-17T17:00:00Z", 58.5);
    await addRide("ride-u", "2026-11-18T07:00:00Z", 63);
    const response = await effort();
    // 121.5: 58.5 and 63. The ten weeks before it, and the eight before 16 Nov are 90, 200, 160, 0, 140, 110, 130 and 120.
    const current = response.weeks[11];
    expect(current).toMatchObject({
      weekStart: "2026-11-16",
      load: 121.5,
      current: true,
      approximate: false,
      weeksLogged: 8,
      usualLow: 60.3,
      usualHigh: 177.2,
      verdict: "within",
    });
    expect(response.weeksLogged).toBe(8);
    expect(response.weeksNeeded).toBe(8);
    expect(current?.days.map((d) => d.load)).toEqual([0, 58.5, 63, 0, 0, 0, 0]);
    expect(current?.activities).toEqual([
      {
        id: "ride-t",
        kind: "ride",
        sport: null,
        name: "Free ride",
        date: "2026-11-17",
        load: 58.5,
        approximate: false,
        share: 0.481,
      },
      {
        id: "ride-u",
        kind: "ride",
        sport: null,
        name: "Free ride",
        date: "2026-11-18",
        load: 63,
        approximate: false,
        share: 0.519,
      },
    ]);
    const byStart = Object.fromEntries(response.weeks.map((w) => [w.weekStart, w]));
    // The first week with a ride is 7 Sep: a week has a range only with eight weeks of training before it.
    expect(byStart["2026-10-26"]).toMatchObject({
      weeksLogged: 7,
      usualLow: null,
      verdict: "learning",
    });
    expect(byStart["2026-11-02"]).toMatchObject({
      load: 130,
      usualLow: 58.9,
      usualHigh: 178.6,
      verdict: "within",
    });
    expect(byStart["2026-11-09"]).toMatchObject({
      load: 120,
      usualLow: 63,
      usualHigh: 182,
      verdict: "within",
    });
    expect(byStart["2026-09-07"]).toMatchObject({ load: 100, weeksLogged: 0, verdict: "learning" });
    expect(byStart["2026-09-14"]?.activities[0]).toMatchObject({
      id: "ride-w1",
      load: 150,
      share: 1,
    });
  });

  it("compares the load so far with the whole range: below so far on a Wednesday, above, and none on a quiet Monday", async () => {
    await wednesdays("2026-09-07", L1);
    expect((await effort("?weeks=1")).weeks[0]).toMatchObject({
      verdict: "none",
      load: 0,
      activities: [],
    });
    await addRide("ride-q", "2026-11-18T07:00:00Z", 40);
    expect((await effort("?weeks=1")).weeks[0]).toMatchObject({ verdict: "below", load: 40 });
    await addRide("ride-r", "2026-11-17T17:00:00Z", 150);
    expect((await effort("?weeks=1")).weeks[0]).toMatchObject({ verdict: "above", load: 190 });
  });

  it("learns with no history at all, and keeps the weeks before the first ride at weeksLogged 0", async () => {
    const empty = await effort("?weeks=3");
    expect(empty.weeks.map((w) => [w.verdict, w.weeksLogged, w.load])).toEqual([
      ["learning", 0, 0],
      ["learning", 0, 0],
      ["learning", 0, 0],
    ]);
    await addRide("ride-1", "2026-11-04T17:00:00Z", 60);
    const one = await effort("?weeks=3");
    expect(one.weeks.map((w) => [w.weekStart, w.weeksLogged])).toEqual([
      ["2026-11-02", 0],
      ["2026-11-09", 1],
      ["2026-11-16", 2],
    ]);
  });

  it("agrees with the weekly load of the metrics the rest of the app draws", async () => {
    await wednesdays("2026-09-07", L1);
    await addRide("ride-t", "2026-11-17T17:00:00Z", 58.5);
    await addRide("ride-x", "2026-11-17T09:00:00Z", 22);
    const response = await effort();
    const metrics = (
      await get("/v1/metrics?from=2026-09-07&to=2026-11-18")
    ).json() as MetricsResponse;
    for (const week of metrics.weekly) {
      const mine = response.weeks.find((w) => w.weekStart === week.weekStart);
      expect(mine?.load, week.weekStart).toBeCloseTo(week.load, 1);
    }
  });

  describe("the watch and outdoor rides", () => {
    // One clock for the outdoor ride: Sunday 14 March 2027 in Belgrade (UTC+1); today is Wednesday the 17th.
    const watchSession = (id: string, start: string, end: string, bpm: number) => {
      const seconds = (Date.parse(end) - Date.parse(start)) / 1000;
      return exercise(id, "other_workout", start, end, {
        title: "Synthetic VR session",
        heartRateSamples: heartRateSeconds("tmp", start, seconds - 1, 1, () => bpm).samples,
      });
    };

    it("counts a workout of the watch and an outdoor ride with their heart-rate loads, flagged as approximate, with the sport", async () => {
      h.clock.set("2027-03-17T08:00:00Z");
      expect((await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 })).statusCode).toBe(
        200,
      );
      const imported = await postFit(h.app, hourRide({ totalAscent: 100 }));
      expect(imported.statusCode, imported.body).toBe(201);
      const outdoorId = (imported.json() as { id: string }).id;
      expect(
        (
          await post(
            h.app,
            batch(watchSession("vr-1", "2027-03-16T18:00:00Z", "2027-03-16T19:00:00Z", 170)),
          )
        ).statusCode,
      ).toBe(200);
      await addRide("ride-tab", "2027-03-15T17:00:00Z", 45);

      const watchLoad = ((await get("/v1/activities/watch-vr-1")).json() as Activity).load;
      const outdoorLoad = ((await get(`/v1/activities/${outdoorId}`)).json() as Activity).load;
      expect(watchLoad).toBeCloseTo(100, 0);
      expect(outdoorLoad).toBe(60.9);

      const response = await effort("?weeks=2");
      const [last, current] = response.weeks;
      // 10:00 on Sunday 14 March in Belgrade belongs to the week of the 8th.
      expect(last).toMatchObject({ weekStart: "2027-03-08", load: 60.9, approximate: true });
      expect(last?.days[6]).toEqual({ date: "2027-03-14", load: 60.9 });
      expect(last?.activities).toEqual([
        {
          id: outdoorId,
          kind: "activity",
          sport: "cycling-outdoor",
          name: "Outdoor ride",
          date: "2027-03-14",
          load: 60.9,
          approximate: true,
          share: 1,
        },
      ]);
      expect(current?.activities.map((a) => [a.id, a.kind, a.sport, a.approximate])).toEqual([
        ["ride-tab", "ride", null, false],
        ["watch-vr-1", "activity", "other_workout", true],
      ]);
      expect(current?.load).toBeCloseTo(45 + (watchLoad as number), 1);
      expect(current?.approximate).toBe(true);
      const shares = current?.activities.map((a) => a.share) as number[];
      expect((shares[0] ?? 0) + (shares[1] ?? 0)).toBeCloseTo(1, 2);
    });

    it("counts a session that overlaps a ride on the tablet once: the ride", async () => {
      h.clock.set("2026-11-18T08:00:00Z");
      await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
      await addRide("ride-tab", "2026-11-17T18:00:00Z", 100);
      await post(
        h.app,
        batch(watchSession("vr-same", "2026-11-17T18:05:00Z", "2026-11-17T18:55:00Z", 170)),
      );
      const week = (await effort("?weeks=1")).weeks[0];
      expect(week?.activities.map((a) => a.id)).toEqual(["ride-tab"]);
      expect(week?.load).toBe(100);
    });

    it("keeps a workout with no heart-rate limits to scale it by in the list, with no load, no share and no part in any sum", async () => {
      await post(
        h.app,
        batch(watchSession("vr-nolimits", "2026-11-17T18:00:00Z", "2026-11-17T19:00:00Z", 140)),
      );
      await addRide("ride-tab", "2026-11-17T07:00:00Z", 50);
      const week = (await effort("?weeks=1")).weeks[0];
      expect(week?.activities).toEqual([
        {
          id: "ride-tab",
          kind: "ride",
          sport: null,
          name: "Free ride",
          date: "2026-11-17",
          load: 50,
          approximate: false,
          share: 1,
        },
        {
          id: "watch-vr-nolimits",
          kind: "activity",
          sport: "other_workout",
          name: "Synthetic VR session",
          date: "2026-11-17",
          load: null,
          approximate: false,
          share: null,
        },
      ]);
      expect(week?.load).toBe(50);
      expect(week?.approximate).toBe(false);
    });

    it("has the outdoor ride's start where the helper puts it", () => {
      expect(new Date(OUTDOOR_START).toISOString()).toBe("2027-03-14T09:00:00.000Z");
    });
  });

  describe("weeks and days in Europe/Belgrade", () => {
    it("puts rides either side of the spring clock change on their own local days and weeks", async () => {
      // The clocks go forward at 02:00 on Sunday 28 March 2027 (01:00 UTC).
      h.clock.set("2027-03-31T08:00:00Z");
      await addRide("sat", "2027-03-27T22:30:00Z", 10); // 23:30 Saturday the 27th (UTC+1)
      await addRide("sun-1", "2027-03-27T23:30:00Z", 20); // 00:30 Sunday the 28th (UTC+1)
      await addRide("sun-2", "2027-03-28T00:30:00Z", 30); // 01:30 Sunday, before the change
      await addRide("sun-3", "2027-03-28T21:30:00Z", 40); // 23:30 Sunday (UTC+2)
      await addRide("mon", "2027-03-28T22:30:00Z", 50); // 00:30 Monday the 29th (UTC+2)
      const response = await effort("?weeks=2");
      const [last, current] = response.weeks;
      expect(last?.weekStart).toBe("2027-03-22");
      expect(last?.days.map((d) => [d.date, d.load])).toEqual([
        ["2027-03-22", 0],
        ["2027-03-23", 0],
        ["2027-03-24", 0],
        ["2027-03-25", 0],
        ["2027-03-26", 0],
        ["2027-03-27", 10],
        ["2027-03-28", 90],
      ]);
      expect(last?.load).toBe(100);
      expect(current?.weekStart).toBe("2027-03-29");
      expect(current?.days[0]).toEqual({ date: "2027-03-29", load: 50 });
      expect(current?.load).toBe(50);
    });

    it("does the same around the autumn change on 25 October 2026", async () => {
      // The clocks go back at 03:00 on Sunday 25 October (01:00 UTC).
      h.clock.set("2026-10-28T08:00:00Z");
      await addRide("a", "2026-10-24T22:30:00Z", 10); // 00:30 Sunday the 25th (UTC+2)
      await addRide("b", "2026-10-25T22:30:00Z", 20); // 23:30 Sunday (UTC+1)
      await addRide("c", "2026-10-25T23:30:00Z", 30); // 00:30 Monday the 26th (UTC+1)
      const [last, current] = (await effort("?weeks=2")).weeks;
      expect(last).toMatchObject({ weekStart: "2026-10-19", load: 30 });
      expect(last?.days[6]).toEqual({ date: "2026-10-25", load: 30 });
      expect(current).toMatchObject({ weekStart: "2026-10-26", load: 30 });
      expect(current?.days[0]).toEqual({ date: "2026-10-26", load: 30 });
    });

    it("carries a week over the end of a year", async () => {
      h.clock.set("2027-01-02T10:00:00Z");
      await addRide("a", "2026-12-31T22:30:00Z", 11); // 23:30 on 31 Dec
      await addRide("b", "2026-12-31T23:30:00Z", 22); // 00:30 on 1 Jan
      const current = (await effort("?weeks=2")).weeks[1];
      expect(current?.weekStart).toBe("2026-12-28");
      expect(current?.days.map((d) => [d.date, d.load])).toEqual([
        ["2026-12-28", 0],
        ["2026-12-29", 0],
        ["2026-12-30", 0],
        ["2026-12-31", 11],
        ["2027-01-01", 22],
        ["2027-01-02", 0],
        ["2027-01-03", 0],
      ]);
      expect(current?.load).toBe(33);
    });
  });
});

describe("the MCP tool get_effort", () => {
  it("is described with what the numbers and the verdicts mean", async () => {
    const { tools } = await client.listTools();
    const tool = tools.find((t) => t.name === "get_effort");
    expect(tool?.annotations?.readOnlyHint).toBe(true);
    const text = tool?.description ?? "";
    expect(text).toMatch(/same load as get_fitness/);
    expect(text).toMatch(/standard deviation/);
    expect(text).toMatch(/below there means below so far/);
    expect(text).toMatch(/quote it, do not recalculate it/);
  });

  it("answers what the endpoint answers, and takes the number of weeks", async () => {
    await wednesdays("2026-09-07", L1);
    const { json, isError } = await callTool(client, "get_effort");
    expect(isError).toBe(false);
    expect(json).toEqual(await effort());
    const three = await callTool(client, "get_effort", { weeks: 3 });
    expect((three.json as EffortResponse).weeks).toHaveLength(3);
    expect((await callTool(client, "get_effort", { weeks: 53 })).isError).toBe(true);
    expect((await callTool(client, "get_effort", { weeks: 0 })).isError).toBe(true);
  });
});
