import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type PowerSkillsResponse,
  type Profile,
  validatePowerSkillsResponse,
  validateProfile,
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
import { hourRide, postFit } from "./helpers/outdoor.ts";
import { constant, insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";
import { batch, post } from "./helpers/wellness-data.ts";

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

const get = (url: string, headers = bearer(COMPANION)): Promise<LightMyRequestResponse> =>
  h.app.inject({ method: "GET", url, headers });
const patchProfile = (body: unknown): Promise<LightMyRequestResponse> =>
  h.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": "application/json", ...bearer() },
    payload: JSON.stringify(body),
  });

async function skills(query = ""): Promise<PowerSkillsResponse> {
  const res = await get(`/v1/power-skills${query}`);
  expect(res.statusCode, res.body).toBe(200);
  expect(res.headers["cache-control"]).toBe("no-store");
  const body: unknown = res.json();
  const checked = validatePowerSkillsResponse(body);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return body as PowerSkillsResponse;
}

const at = (instant: string): number => Date.parse(instant);
const weigh = (kg: number) =>
  post(h.app, batch({ kind: "weight", id: `w-${kg}`, time: "2026-11-17T06:40:00Z", kg }));

/** A ride whose first `seconds` of `count` are at `high` watts and the rest at `low`. */
const spike = (
  id: string,
  start: string,
  count: number,
  high: number,
  low: number,
  seconds: number,
) =>
  syntheticRide({
    id,
    startEpochMillis: at(start),
    seconds,
    power: (s) => (s <= count ? high : low),
  });

/**
 * The rides of the golden, in Belgrade (UTC+1 in November, UTC+2 until 25 October): a, 20 minutes at 300 W on 10 Nov; b, 50 minutes at
 * 220 W on 22 Oct; d and e on 12 Nov, two minutes each with 15 s at 800 W and 100 W after (e later in the day); c, 61 minutes at 250 W
 * on 3 June, outside the window; and a ride on 8 Nov with no power at all.
 */
async function seed(): Promise<void> {
  await insertRide(
    db,
    syntheticRide({
      id: "ride-a",
      startEpochMillis: at("2026-11-10T17:00:00Z"),
      seconds: 1200,
      power: constant(300),
    }),
  );
  await insertRide(
    db,
    syntheticRide({
      id: "ride-b",
      startEpochMillis: at("2026-10-22T17:00:00Z"),
      seconds: 3000,
      power: constant(220),
    }),
  );
  await insertRide(db, spike("ride-d", "2026-11-12T17:00:00Z", 15, 800, 100, 120));
  await insertRide(db, spike("ride-e", "2026-11-12T19:00:00Z", 15, 800, 100, 120));
  await insertRide(
    db,
    syntheticRide({
      id: "ride-c",
      startEpochMillis: at("2026-06-03T17:00:00Z"),
      seconds: 3700,
      power: constant(250),
    }),
  );
  await insertRide(
    db,
    syntheticRide({
      id: "ride-np",
      startEpochMillis: at("2026-11-08T17:00:00Z"),
      seconds: 600,
      power: () => null,
    }),
  );
}

const row = (r: PowerSkillsResponse, seconds: number) =>
  r.durations.find((d) => d.seconds === seconds);

describe("GET /v1/power-skills", () => {
  it("needs a device token", async () => {
    expectError(await h.app.inject({ method: "GET", url: "/v1/power-skills" }), "unauthorized");
    expectError(
      await h.app.inject({
        method: "GET",
        url: "/v1/power-skills",
        headers: { authorization: "Bearer nope" },
      }),
      "unauthorized",
    );
  });

  it("answers the last 56 days: the best of the window and of all time at twelve durations, with the ride, the name and the day", async () => {
    await seed();
    const response = await skills();
    // Today is 18 Nov: 56 days back from it, both ends included, is 24 Sep. The ride with no power is not counted.
    expect(response.window).toEqual({ from: "2026-09-24", to: "2026-11-18", rideCount: 4 });
    expect(response.table).toBe("men");
    expect(response.riderWeight).toBeNull();
    expect(response.durations.map((d) => d.seconds)).toEqual([
      15, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600,
    ]);

    // 15 s: d and e tie at 800 W; the later ride (e) wins. No weight: no watts per kilogram, no score.
    expect(row(response, 15)).toEqual({
      seconds: 15,
      window: {
        watts: 800,
        wattsPerKg: null,
        score: null,
        date: "2026-11-12",
        rideId: "ride-e",
        rideName: "Free ride",
      },
      allTime: {
        watts: 800,
        wattsPerKg: null,
        score: null,
        date: "2026-11-12",
        rideId: "ride-e",
        rideName: "Free ride",
      },
    });
    // 30 s: 15 s at 800 W and 15 s at 100 W is 450 W.
    expect(row(response, 30)?.window?.watts).toBe(450);
    // 60 s: d and e give (15 * 800 + 45 * 100) / 60 = 275 W; a, at 300 W, is better.
    expect(row(response, 60)?.window).toMatchObject({
      watts: 300,
      rideId: "ride-a",
      date: "2026-11-10",
    });
    expect(row(response, 1200)?.window).toMatchObject({ watts: 300, rideId: "ride-a" });
    // 30 min: a is too short; b (50 minutes at 220 W) is the window's; c (250 W) is the all-time best, outside the window.
    expect(row(response, 1800)).toMatchObject({
      window: { watts: 220, rideId: "ride-b", date: "2026-10-22" },
      allTime: { watts: 250, rideId: "ride-c", date: "2026-06-03" },
    });
    // 45 min, a duration the contract's 25 do not have, is kept for each ride too; 60 min only c has.
    expect(row(response, 2700)).toMatchObject({ window: { watts: 220 }, allTime: { watts: 250 } });
    expect(row(response, 3600)).toEqual({
      seconds: 3600,
      window: null,
      allTime: {
        watts: 250,
        wattsPerKg: null,
        score: null,
        date: "2026-06-03",
        rideId: "ride-c",
        rideName: "Free ride",
      },
    });
    expect(response.skills.every((s) => s.level === null)).toBe(true);
    expect(response.levelsNote).toMatch(/weight/);
  });

  it("scores and levels the efforts against the men's table once the weight is known", async () => {
    await seed();
    expect((await weigh(80)).statusCode).toBe(200);
    const response = await skills();
    expect(response.riderWeight).toEqual({ kg: 80, date: "2026-11-17", days: 1 });
    // 800 W at 80 kg is 10 W/kg; 1 + 8 * (10 - 8.2) / (18.5 - 8.2) = 2.40. 450 W is 5.63 W/kg, under the 6.9 floor of 30 s: score 0.
    expect(row(response, 15)?.window).toMatchObject({ wattsPerKg: 10, score: 2.4 });
    expect(row(response, 30)?.window).toMatchObject({ watts: 450, wattsPerKg: 5.63, score: 0 });
    // 300 W is 3.75 W/kg: 5 min 1 + 8 * (3.75 - 2.3) / 5.3 = 3.19, 10 min 3.46.
    expect(row(response, 300)?.window).toMatchObject({ wattsPerKg: 3.75, score: 3.19 });
    expect(row(response, 600)?.window?.score).toBe(3.46);
    // 250 W is 3.125 W/kg, rounded half up to 3.13.
    expect(row(response, 3600)?.allTime?.wattsPerKg).toBe(3.13);
    const levels = Object.fromEntries(response.skills.map((s) => [s.id, s.level]));
    // Sprint: (2.40 + 0) / 2 = 1.2 -> 1. Attack: (0 + 0.36 + 1.58) / 3 -> 0.65, held at 1. Climb: (3.19 + 3.46) / 2 = 3.33 -> 3.
    // Time trial: (3.67 + 3.72 + 2.26) / 3 = 3.22 -> 3. Endurance: only 45 min has an effort in the window: 2.39 -> 2.
    expect(levels).toEqual({
      sprint: { index: 1, name: "Starting" },
      attack: { index: 1, name: "Starting" },
      climb: { index: 3, name: "Regular" },
      "time-trial": { index: 3, name: "Regular" },
      endurance: { index: 2, name: "Casual" },
    });
    expect(response.levelsNote).toMatch(/approximate/);
  });

  it("reads the levels against the women's table when the profile says so, and goes back with null", async () => {
    await seed();
    await weigh(80);
    const before = (await get("/v1/profile")).json() as Profile;
    expect(before.powerProfileTable).toBeUndefined();

    expect((await patchProfile({ powerProfileTable: "women" })).statusCode).toBe(200);
    const profile = (await get("/v1/profile")).json();
    expect(validateProfile(profile)).toMatchObject({ ok: true });
    expect((profile as Profile).powerProfileTable).toBe("women");
    const women = await skills();
    expect(women.table).toBe("women");
    // 10 W/kg at 15 s on the women's 6.63 to 13.86: 4.73; 5.63 at 30 s on 5.61 to 11.29: 1.03.
    expect(row(women, 15)?.window?.score).toBe(4.73);
    expect(row(women, 30)?.window?.score).toBe(1.03);
    expect(women.skills[0]?.level).toEqual({ index: 2, name: "Casual" });

    expect((await patchProfile({ powerProfileTable: null })).statusCode).toBe(200);
    expect((await skills()).table).toBe("men");
    expect((await patchProfile({ powerProfileTable: "children" })).statusCode).toBe(422);
  });

  it("answers a custom range: the window's best, and all time up to its end only", async () => {
    await seed();
    await insertRide(
      db,
      syntheticRide({
        id: "ride-f",
        startEpochMillis: at("2026-11-16T17:00:00Z"),
        seconds: 1200,
        power: constant(350),
      }),
    );
    const response = await skills("?from=2026-11-11&to=2026-11-13");
    expect(response.window).toEqual({ from: "2026-11-11", to: "2026-11-13", rideCount: 2 });
    expect(row(response, 15)?.window).toMatchObject({ watts: 800, rideId: "ride-e" });
    // 20 minutes: nothing in the window is that long, but a (300 W, 10 Nov) is all time up to 13 Nov and f (350 W, 16 Nov) is not.
    expect(row(response, 1200)).toMatchObject({
      window: null,
      allTime: { watts: 300, rideId: "ride-a" },
    });
    // With the default window the later ride is the best.
    expect(row(await skills(), 1200)?.window).toMatchObject({ watts: 350, rideId: "ride-f" });
  });

  it("answers a window with no ride from all time alone, and an empty history with nothing", async () => {
    await seed();
    const old = await skills("?from=2026-06-04&to=2026-06-30");
    expect(old.window.rideCount).toBe(0);
    expect(old.durations.every((d) => d.window === null)).toBe(true);
    expect(row(old, 3600)?.allTime).toMatchObject({ watts: 250, rideId: "ride-c" });
    expect(old.skills.every((s) => s.level === null)).toBe(true);

    await clearRides(db);
    await db.exec("TRUNCATE ride_metrics");
    const empty = await skills();
    expect(empty.window.rideCount).toBe(0);
    expect(empty.durations.every((d) => d.window === null && d.allTime === null)).toBe(true);
  });

  it("counts a ride by its local day in Belgrade, across the clock change in summer time", async () => {
    // 23 Sep 22:30 UTC is 00:30 on the 24th in Belgrade (UTC+2): the first day of the window. 21:30 UTC is 23:30 on the 23rd: out.
    // 17 Nov 23:30 UTC is 00:30 on the 18th (UTC+1): the last day. 18 Nov 23:30 UTC is 00:30 on the 19th: after the window.
    const tiny = (id: string, start: string, watts: number) =>
      syntheticRide({ id, startEpochMillis: at(start), seconds: 60, power: constant(watts) });
    await insertRide(db, tiny("ride-in-start", "2026-09-23T22:30:00Z", 100));
    await insertRide(db, tiny("ride-before", "2026-09-23T21:30:00Z", 200));
    await insertRide(db, tiny("ride-in-end", "2026-11-17T23:30:00Z", 300));
    await insertRide(db, tiny("ride-after", "2026-11-18T23:30:00Z", 400));
    const response = await skills();
    expect(response.window.rideCount).toBe(2);
    expect(row(response, 15)?.window).toMatchObject({
      watts: 300,
      rideId: "ride-in-end",
      date: "2026-11-18",
    });
    // All time to the end of the window: the ride before the window counts, the one after the end does not.
    expect(row(response, 15)?.allTime?.watts).toBe(300);
    h.clock.set("2026-11-19T08:00:00Z");
    expect((await skills()).window).toEqual({ from: "2026-09-25", to: "2026-11-19", rideCount: 2 });
  });

  it("does not count the watch's workouts or an outdoor ride", async () => {
    await seed();
    const before = await skills();
    await patchProfile({ maxHeartRate: 190, thresholdHeartRate: 170 });
    const imported = await postFit(h.app, hourRide({ power: () => 500 }));
    expect([200, 201]).toContain(imported.statusCode);
    await post(
      h.app,
      batch({
        kind: "exerciseSession",
        id: "vr-1",
        type: "other_workout",
        start: "2026-11-17T19:05:00Z",
        end: "2026-11-17T19:47:00Z",
      }),
    );
    expect(await skills()).toEqual(before);
  });

  it("counts a ride that has no stored 45-minute figure again: a ride kept before it is worked out afresh", async () => {
    await seed();
    await skills();
    // As the rows of the first version of the metrics were: no 2700 key in the curve, the old version number.
    await db.exec("UPDATE ride_metrics SET version = 1, power_curve = power_curve - '2700'");
    expect(row(await skills(), 2700)?.window).toMatchObject({ watts: 220, rideId: "ride-b" });
    expect(
      (
        await db.query<{ n: string }>(
          "SELECT count(*)::text AS n FROM ride_metrics WHERE version = 2",
        )
      ).rows[0]?.n,
    ).toBe("6");
  });

  it("names a ride that was played from a planned workout when no snapshot is kept", async () => {
    await insertRide(
      db,
      syntheticRide({
        id: "ride-p",
        startEpochMillis: at("2026-11-10T17:00:00Z"),
        seconds: 600,
        power: constant(200),
        workoutId: "icu-77",
      }),
    );
    expect(row(await skills(), 15)?.window?.rideName).toBe("Planned workout");
  });

  describe("parameters", () => {
    const bad = async (query: string): Promise<string> =>
      expectError(await get(`/v1/power-skills${query}`), "invalid_range").message;

    it("refuses what is not a real date, naming the parameter", async () => {
      expect(await bad("?from=yesterday")).toMatch(/from/);
      expect(await bad("?to=2026-02-30")).toMatch(/to/);
      expect(await bad("?from=")).toMatch(/from/);
      expect(await bad("?to=")).toMatch(/to/);
      expect(await bad("?from=2026-01-01&from=2026-02-01")).toMatch(/from/);
    });

    it("refuses a range that is reversed or longer than three years, and serves one of exactly 1096 days", async () => {
      expect(await bad("?from=2026-11-19&to=2026-11-18")).toMatch(/after/);
      expect(await bad("?from=2023-11-18&to=2026-11-18")).toMatch(/1097 days/);
      const longest = await skills("?from=2023-11-19&to=2026-11-18");
      expect(longest.window.from).toBe("2023-11-19");
    });

    it("defaults the end to today and the start to 55 days before the end", async () => {
      expect((await skills("?from=2026-11-01")).window).toMatchObject({
        from: "2026-11-01",
        to: "2026-11-18",
      });
      expect((await skills("?to=2026-10-31")).window).toMatchObject({
        from: "2026-09-06",
        to: "2026-10-31",
      });
    });
  });
});

describe("the MCP tool get_power_skills", () => {
  it("is described with what the numbers mean, and says the levels are a rough guide", async () => {
    const { tools } = await client.listTools();
    const tool = tools.find((t) => t.name === "get_power_skills");
    expect(tool?.annotations?.readOnlyHint).toBe(true);
    const text = tool?.description ?? "";
    expect(text).toMatch(/twelve durations/);
    expect(text).toMatch(/ROUGH GUIDE/);
    expect(text).toMatch(/World class/);
    expect(text).toMatch(/quote it, do not recalculate it/);
    expect(tools.some((t) => t.name === "get_effort")).toBe(true);
  });

  it("answers what the endpoint answers, for the same window", async () => {
    await seed();
    await weigh(80);
    const { json, isError } = await callTool(client, "get_power_skills");
    expect(isError).toBe(false);
    expect(json).toEqual(await skills());
    const custom = await callTool(client, "get_power_skills", {
      from: "2026-11-11",
      to: "2026-11-13",
    });
    expect((custom.json as PowerSkillsResponse).window.rideCount).toBe(2);
  });

  it("says what is wrong with a window, in words", async () => {
    const reversed = await callTool(client, "get_power_skills", {
      from: "2026-11-19",
      to: "2026-11-18",
    });
    expect(reversed.isError).toBe(true);
    expect(reversed.text).toMatch(/from 2026-11-19 is after to 2026-11-18/);
    const long = await callTool(client, "get_power_skills", {
      from: "2020-01-01",
      to: "2026-11-18",
    });
    expect(long.isError).toBe(true);
    expect(long.text).toMatch(/at most 1096/);
  });

  it("lets update_profile choose the table and get_profile say it", async () => {
    const changed = await callTool(client, "update_profile", { powerProfileTable: "women" });
    expect(changed.isError).toBe(false);
    expect((changed.json as { powerProfileTable: string }).powerProfileTable).toBe("women");
    expect(
      ((await callTool(client, "get_profile")).json as { powerProfileTable: string })
        .powerProfileTable,
    ).toBe("women");
    const back = await callTool(client, "update_profile", { powerProfileTable: null });
    expect((back.json as { powerProfileTable: string }).powerProfileTable).toBe("men");
  });
});
