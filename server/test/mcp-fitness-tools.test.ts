import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { constant, insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "test", token: STATIC_TOKEN }] });
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
  await db.exec(
    "TRUNCATE rides, ride_destinations, ride_metrics, ftp_suggestions, ftp_history, profile, ride_workouts",
  );
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);
const at = (instant: string): number => Date.parse(instant);

/** 20 minutes at `watts` on a day (17:00 UTC, 18:00 in Paris). */
const effort = (id: string, day: string, watts: number) =>
  syntheticRide({
    id,
    startEpochMillis: at(`${day}T17:00:00Z`),
    seconds: 1200,
    power: constant(watts),
  });

describe("get_fitness", () => {
  it("is described with what the numbers mean, and says the eFTP is only a suggestion", async () => {
    const { tools } = await client.listTools();
    const tool = tools.find((t) => t.name === "get_fitness");
    expect(tool?.annotations?.readOnlyHint).toBe(true);
    const text = tool?.description ?? "";
    expect(text).toMatch(/fitness \(CTL\)/);
    expect(text).toMatch(/fatigue \(ATL\)/);
    expect(text).toMatch(/form \(TSB\) is fitness minus fatigue/);
    expect(text).toMatch(/ONLY a suggestion/);
    expect(text).toMatch(/never call update_profile with it unasked/);
    expect(text).toMatch(/never by a model/);
  });

  it("gives today, the weeks, the best efforts and the FTP, compactly, for the last 42 days", async () => {
    await h.services.profile.update({ ftp: 250 });
    // Three efforts of 20 minutes: 300 W on the 10th, 290 W on the 12th and 295 W on the 16th.
    await insertRide(db, effort("ride-a", "2026-11-10", 300));
    await insertRide(db, effort("ride-b", "2026-11-12", 290));
    await insertRide(db, effort("ride-c", "2026-11-16", 295));

    const { json, isError } = await call("get_fitness");
    expect(isError).toBe(false);
    const body = json as {
      from: string;
      to: string;
      stale: boolean;
      today: Record<string, number | string | null>;
      weeks: { weekStart: string; rides: number; rideLoad: number }[];
      powerCurve: { range: { durationSec: number; power: number }[]; allTime: unknown[] };
      ftp: {
        current: number;
        setAt: string;
        suggestion: Record<string, unknown> | null;
        latestEstimate: number;
        note: string | null;
        history: unknown[];
        estimates?: unknown;
      };
      daily?: unknown;
    };
    // The last 42 days up to today, 18 Nov.
    expect(body.from).toBe("2026-10-08");
    expect(body.to).toBe("2026-11-18");
    expect(body.stale).toBe(false);
    expect(body.today).toMatchObject({ date: "2026-11-18", load: 0 });
    expect(body.today.fitness).toBeGreaterThan(0);
    expect(body.today.fatigue).toBeGreaterThan(body.today.fitness as number);
    // The fitness 7 days ago is known (the range is 42 days) and so is the change.
    expect(typeof body.today.fitnessChange7Days).toBe("number");
    expect(typeof body.today.fitnessChange28Days).toBe("number");
    expect(body.weeks.map((w) => w.weekStart).slice(-2)).toEqual(["2026-11-09", "2026-11-16"]);
    expect(body.weeks.at(-2)).toMatchObject({ rides: 2 });
    // The headline durations only, 20 minutes among them: the best is the 300 W.
    expect(body.powerCurve.range.map((p) => p.durationSec)).toEqual([5, 60, 300, 1200]);
    expect(body.powerCurve.range.find((p) => p.durationSec === 1200)?.power).toBe(300);
    // eFTP: 0.95 * 300 = 285 with 3 rides above 270 W behind it.
    expect(body.ftp.current).toBe(250);
    expect(body.ftp.latestEstimate).toBe(285);
    expect(body.ftp.suggestion).toMatchObject({
      suggestedWatts: 285,
      onlyASuggestion: true,
      basedOnRides: 3,
      windowDays: 42,
      confidence: "medium",
      basis: { durationSec: 1200, power: 300, rideId: "ride-a", date: "2026-11-10" },
    });
    expect(body.ftp.note).toBeNull();
    // Compact: no day-by-day series, no estimates, unless asked.
    expect(body.daily).toBeUndefined();
    expect(body.ftp.estimates).toBeUndefined();
  });

  it("changes nothing: the profile's FTP is the rider's until the rider says", async () => {
    await h.services.profile.update({ ftp: 250 });
    await insertRide(db, effort("ride-a", "2026-11-10", 300));
    await insertRide(db, effort("ride-b", "2026-11-12", 290));
    await call("get_fitness");
    expect((await h.services.profile.get()).ftp).toBe(250);
  });

  it("gives the daily series, the whole curve and the estimates when asked, for a range of dates", async () => {
    await insertRide(db, effort("ride-a", "2026-11-10", 300));
    await insertRide(db, effort("ride-b", "2026-11-12", 290));
    const { json } = await call("get_fitness", {
      from: "2026-11-09",
      to: "2026-11-15",
      series: true,
    });
    const body = json as {
      today: { date: string; fitnessChange28Days: number | null };
      daily: { date: string; ctl: number; atl: number; tsb: number }[];
      powerCurve: { range: { durationSec: number }[] };
      ftp: { estimates: { date: string; eftp: number }[]; suggestion: unknown; note: string };
    };
    expect(body.daily.map((d) => d.date)).toEqual([
      "2026-11-09",
      "2026-11-10",
      "2026-11-11",
      "2026-11-12",
      "2026-11-13",
      "2026-11-14",
      "2026-11-15",
    ]);
    expect(body.today.date).toBe("2026-11-15");
    // A week is too short to look 28 days back.
    expect(body.today.fitnessChange28Days).toBeNull();
    expect(body.powerCurve.range.map((p) => p.durationSec)).toHaveLength(20);
    // 285 W as of the end of the week, and with no FTP in the profile a suggestion is worth making.
    expect(body.ftp.estimates).toEqual([{ date: "2026-11-15", eftp: 285 }]);
    expect(body.ftp.suggestion).not.toBeNull();
  });

  it("says why there is no suggestion in plain words", async () => {
    await h.services.profile.update({ ftp: 250 });
    await insertRide(db, effort("ride-a", "2026-11-10", 300));
    const { json } = await call("get_fitness");
    expect((json as { ftp: { note: string } }).ftp.note).toMatch(
      /Only 1 ride in the last 42 days has a 20-minute effort/,
    );
  });

  it.each([
    [{ from: "2026-11-18", to: "2026-11-01" }, /is before from/],
    [{ from: "2026-02-30" }, /not a real calendar date/],
    [{ from: "2025-01-01", to: "2026-11-18" }, /at most 366/],
  ])("answers a bad range in plain words: %j", async (args, message) => {
    const result = await call("get_fitness", args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
  });
});

describe("get_ride with the power curve, drift and W′", () => {
  it("adds the curve, the drift and the W′ balance to what it already said", async () => {
    await h.services.profile.update({ ftp: 250, wPrimeKj: 18 });
    // One hour at 200 W (heart rate 140 then 147): drift 4.8%; 250 W never passed.
    await insertRide(
      db,
      syntheticRide({
        id: "ride-hour",
        startEpochMillis: at("2026-11-12T17:00:00Z"),
        seconds: 3600,
        power: constant(200),
        heartRate: (s) => (s <= 1800 ? 140 : 147),
      }),
    );
    const { json } = await call("get_ride", { rideId: "ride-hour" });
    const body = json as {
      statistics: unknown;
      powerCurve: { durationSec: number; power: number }[];
      aerobicDrift: { available: boolean; driftPercent: number };
      wPrimeBalance: {
        cpWatts: number;
        cpSource: string;
        wPrimeKj: number;
        minBalanceKj: number;
        series?: unknown;
      };
    };
    expect(body.statistics).toBeDefined();
    expect(body.powerCurve.at(-1)).toEqual({ durationSec: 3600, power: 200 });
    expect(body.aerobicDrift).toMatchObject({ available: true, driftPercent: 4.8 });
    expect(body.wPrimeBalance).toMatchObject({
      cpWatts: 250,
      cpSource: "profile",
      wPrimeKj: 18,
      minBalanceKj: 18,
    });
    expect(body.wPrimeBalance.series).toBeUndefined();
  });

  it("says in its description what the three mean and how W′ is chosen", async () => {
    const { tools } = await client.listTools();
    const text = tools.find((t) => t.name === "get_ride")?.description ?? "";
    expect(text).toMatch(/powerCurve: the best average power over each of 25 durations/);
    expect(text).toMatch(/aerobicDrift:/);
    expect(text).toMatch(/wPrimeBalance:.*Skiba 2012/);
  });
});

describe("update_profile and get_profile with W′", () => {
  it("sets W′ in kilojoules, shows it, and takes null back to the default", async () => {
    const set = await call("update_profile", { wPrimeKj: 22.5 });
    expect(set.json).toMatchObject({ changed: ["wPrimeKj"], wPrimeKj: 22.5 });
    expect((await call("get_profile")).json).toMatchObject({ wPrimeKj: 22.5 });
    const cleared = await call("update_profile", { wPrimeKj: null });
    expect(cleared.json).toMatchObject({ changed: ["wPrimeKj"], wPrimeKj: null });
    const bad = await call("update_profile", { wPrimeKj: 0 });
    expect(bad.isError).toBe(true);
    expect(bad.text).toMatch(/profile does not validate/);
  });
});
