import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type RecoveryResponse,
  validateRecoveryResponse,
  validateWellnessResponse,
  type WellnessResponse,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, expectError } from "./helpers/app.ts";
import { clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { alternating, constant, planted, TODAY } from "./helpers/wellness.ts";
import {
  batch,
  get,
  history,
  type NightPatterns,
  nightRecords,
  post,
  uploadAll,
} from "./helpers/wellness-data.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    configured: false,
    timezone: "UTC",
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearWellness(db);
  h.clock.set("2026-11-18T08:00:00Z");
});

/** The steady history: resting heart rate 50 and 54 (mean 52), respiratory rate 13.8 and 14.6, oxygen 95 and 97, heart rate in sleep 46 and 50. */
const STEADY_NIGHTS: NightPatterns = {
  restingHr: alternating(50, 54),
  respiratoryRate: alternating(13.8, 14.6),
  spo2: alternating(95, 97),
  sleepingHr: alternating(46, 50),
};

async function recovery(): Promise<RecoveryResponse> {
  const res = await get(h.app, "/v1/recovery");
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  expect(validateRecoveryResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  return body as RecoveryResponse;
}

const mcp = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

describe("upload, daily figures, recovery: the whole path over 60 synthetic days", () => {
  it("rates a steady history ready, naming the rule and the signals", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 60));
    const answer = await recovery();
    expect(answer).toMatchObject({
      date: TODAY,
      state: "ready",
      rule: "OK",
      reason: "All six signals are in your usual range.",
      guidance: "Train as planned.",
      nightsLogged: 42,
      nightsNeeded: 14,
      stale: false,
    });
    expect(answer.facts).toHaveLength(4);
    expect(answer.facts.map((f) => f.metric)).toEqual([
      "restingHr",
      "sleepSeconds",
      "respiratoryRate",
      "spo2",
    ]);
    expect(answer.facts[0]).toMatchObject({
      value: 50,
      unit: "bpm",
      deviation: "inside",
      streak: 0,
    });
  });

  it("rates caution when the resting heart rate stays above its usual range for a second morning", async () => {
    await uploadAll(
      h.app,
      history({ ...STEADY_NIGHTS, restingHr: planted(alternating(50, 54), { 1: 55, 0: 55 }) }, 60),
    );
    const answer = await recovery();
    expect(answer).toMatchObject({
      state: "caution",
      rule: "C3",
      reason: "Resting heart rate is above your usual range for the second morning.",
      guidance: "Train, but keep the hard parts short.",
    });
    expect(answer.facts[0]).toMatchObject({
      metric: "restingHr",
      value: 55,
      deviation: "above",
      streak: 2,
    });
  });

  it("rates rest when three signals are far outside their usual range", async () => {
    // Today: resting heart rate 62, a night cut to 4 h 30 min, respiratory rate 17.5.
    const records = [
      ...history(STEADY_NIGHTS, 60, 1),
      ...nightRecords(TODAY, {
        restingHr: 62,
        respiratoryRate: 17.5,
        spo2: 96,
        sleepingHr: 48,
        wake: "03:15",
      }),
    ];
    await uploadAll(h.app, records);
    const answer = await recovery();
    expect(answer).toMatchObject({
      state: "rest",
      rule: "R1",
      guidance: "Keep today easy or take it off.",
    });
    expect(answer.reason).toMatch(/^Three signals are well outside your usual range: /);
    expect(answer.reason).toContain("resting heart rate");
    expect(answer.reason).toContain("sleep");
    expect(answer.reason).toContain("respiratory rate");
    expect(
      answer.facts
        .slice(0, 3)
        .map((f) => f.deviation)
        .sort(),
    ).toEqual(["above", "above", "below"]);
  });

  it("learns for the first 14 nights and says how many it has", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 9));
    const answer = await recovery();
    expect(answer).toMatchObject({
      state: "learning",
      rule: null,
      guidance: null,
      nightsLogged: 9,
      reason: "Overdrive rates recovery after 14 nights with the watch. 9 nights so far.",
    });
    expect(answer.facts.length).toBeGreaterThan(0);
    expect(answer.facts.every((f) => f.baselineLow === null && f.deviation === null)).toBe(true);
  });

  it("is noData when nothing has come for two days, and rates yesterday while today's data is on its way", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 60, 3));
    expect(await recovery()).toMatchObject({ state: "noData", date: TODAY, rule: null, facts: [] });

    await clearWellness(db);
    await uploadAll(h.app, history(STEADY_NIGHTS, 60, 1));
    expect(await recovery()).toMatchObject({ state: "ready", date: "2026-11-17" });
  });

  it("is rated afresh when the morning's data arrives", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 60, 1));
    expect((await recovery()).date).toBe("2026-11-17");
    await uploadAll(
      h.app,
      nightRecords(TODAY, { restingHr: 52, respiratoryRate: 14.2, spo2: 96, sleepingHr: 48 }),
    );
    expect(await recovery()).toMatchObject({ state: "ready", date: TODAY });
  });

  it("answers GET /v1/wellness with the baselines in force, the streaks and the usual sleep times", async () => {
    await uploadAll(
      h.app,
      history(
        { ...STEADY_NIGHTS, restingHr: planted(alternating(50, 54), { 2: 58, 1: 58, 0: 58 }) },
        60,
      ),
    );
    const res = await get(h.app, `/v1/wellness?from=2026-11-16&to=${TODAY}`);
    const body = res.json() as WellnessResponse;
    expect(validateWellnessResponse(body)).toMatchObject({ ok: true });
    expect(
      body.days.map((d) => [d.date, d.restingHr.value, d.restingHr.deviation, d.restingHr.streak]),
    ).toEqual([
      ["2026-11-16", 58, "above", 1],
      ["2026-11-17", 58, "above", 2],
      ["2026-11-18", 58, "above", 3],
    ]);
    expect(body.nightsLogged).toBe(42);
    // A night from 22:45 to 06:15 every night: bedtime -75 (the range is an hour either side at least) and waking 375 (half an hour).
    expect(body.usual).toEqual({
      bedtime: { low: -135, high: -15 },
      wake: { low: 345, high: 405 },
    });
    expect(body.days[2]).toMatchObject({
      sleepStart: "2026-11-17T22:45:00Z",
      sleepEnd: "2026-11-18T06:15:00Z",
    });
    // Sleep is the same every night, so the range is the floor of 20 minutes around 7 h 30.
    expect(body.days[2]?.sleepSeconds).toMatchObject({
      value: 27000,
      baselineLow: 25800,
      baselineHigh: 28200,
      deviation: "inside",
    });
  });

  it("uses the heart-rate variability where there is one, 7-day mean and all", async () => {
    // 60 ms every morning, then five mornings at 40 ms: the 7-day mean falls far below its usual range.
    const hrvPattern = planted(constant(60), { 4: 40, 3: 40, 2: 40, 1: 40, 0: 40 });
    await uploadAll(
      h.app,
      history(
        { ...STEADY_NIGHTS, hrv: hrvPattern, restingHr: planted(alternating(50, 54), { 0: 55 }) },
        60,
      ),
    );
    const answer = await recovery();
    expect(answer.state).toBe("caution");
    expect(answer.facts.map((f) => f.metric)).toContain("hrvRmssd");
    const fact = answer.facts.find((f) => f.metric === "hrvRmssd");
    expect(fact).toMatchObject({ unit: "ms", deviation: "below" });
  });
});

describe("body weight (spec §12.5)", () => {
  const weight = (id: string, time: string, kg: number) => ({
    kind: "weight" as const,
    id,
    time,
    kg,
  });
  const profileOf = async () =>
    (await h.app.inject({ method: "GET", url: "/v1/profile", headers: bearer() })).json();

  it("keeps the day's weight and a 7-day average, and the days between weighings share the average", async () => {
    // 78.0 on the 12th, two weighings on the 14th (78.4 and 78.8), one on the 17th (77.0).
    const res = await post(
      h.app,
      batch(
        weight("w-12", "2026-11-12T06:40:00Z", 78),
        weight("w-14a", "2026-11-14T06:40:00Z", 78.4),
        weight("w-14b", "2026-11-14T19:40:00Z", 78.8),
        weight("w-17", "2026-11-17T06:40:00Z", 77),
      ),
    );
    expect(res.json()).toMatchObject({ accepted: 4, byType: { weight: { accepted: 4 } } });
    const answer = (
      await get(h.app, "/v1/wellness?from=2026-11-11&to=2026-11-18")
    ).json() as WellnessResponse;
    expect(validateWellnessResponse(answer)).toMatchObject({ ok: true });
    const byDate = Object.fromEntries(answer.days.map((d) => [d.date, [d.weightKg, d.weightKg7d]]));
    expect(byDate).toEqual({
      "2026-11-11": [null, null],
      // The day's weight is the mean of its weighings; the average is of the days that have one, in the 7 days up to the day.
      "2026-11-12": [78, 78],
      "2026-11-13": [null, 78],
      "2026-11-14": [78.6, 78.3],
      "2026-11-15": [null, 78.3],
      "2026-11-16": [null, 78.3],
      "2026-11-17": [77, 77.9],
      // From the 12th to the 18th are 7 days: the 12th is still in.
      "2026-11-18": [null, 77.9],
    });
  });

  it("drops a weighing that is not a body weight", async () => {
    await post(
      h.app,
      batch(weight("slip", "2026-11-17T06:40:00Z", 7.8), weight("ok", "2026-11-17T06:41:00Z", 78)),
    );
    const day = (
      (await get(h.app, "/v1/wellness?from=2026-11-17&to=2026-11-17")).json() as WellnessResponse
    ).days[0];
    expect(day?.weightKg).toBe(78);
  });

  it("is idempotent, and a corrected weighing replaces the old one", async () => {
    await post(
      h.app,
      batch({ ...weight("w", "2026-11-17T06:40:00Z", 78), lastModified: "2026-11-17T06:41:00Z" }),
    );
    const again = await post(
      h.app,
      batch({ ...weight("w", "2026-11-17T06:40:00Z", 78), lastModified: "2026-11-17T06:41:00Z" }),
    );
    expect(again.json()).toMatchObject({ accepted: 0, duplicates: 1 });
    await post(
      h.app,
      batch({ ...weight("w", "2026-11-17T06:40:00Z", 79.2), lastModified: "2026-11-17T08:00:00Z" }),
    );
    const day = (
      (await get(h.app, "/v1/wellness?from=2026-11-17&to=2026-11-17")).json() as WellnessResponse
    ).days[0];
    expect(day?.weightKg).toBe(79.2);
  });

  it("changes nothing else: the profile, with its physics, is as it was", async () => {
    const before = await profileOf();
    await post(h.app, batch(weight("w", "2026-11-17T06:40:00Z", 91.5)));
    expect(await profileOf()).toEqual(before);
    expect((before as { physics: { systemMassKg: number } }).physics.systemMassKg).toBe(80);
  });

  it("is told to the agent: get_wellness has the weight and its trend", async () => {
    await post(
      h.app,
      batch(weight("a", "2026-11-17T06:40:00Z", 78), weight("b", "2026-11-18T06:40:00Z", 79)),
    );
    const { json } = await mcp("get_wellness", { from: "2026-11-17", to: "2026-11-18" });
    const answer = json as { units: Record<string, string>; days: Record<string, unknown>[] };
    expect(answer.units.weightKg).toBe("kg");
    expect(answer.days).toMatchObject([
      { date: "2026-11-17", weightKg: 78, weightKg7d: 78 },
      { date: "2026-11-18", weightKg: 79, weightKg7d: 78.5 },
    ]);
  });
});

describe("the MCP tools", () => {
  it("get_recovery answers as the endpoint does, and says what the rule means", async () => {
    await uploadAll(
      h.app,
      history({ ...STEADY_NIGHTS, restingHr: planted(alternating(50, 54), { 1: 55, 0: 55 }) }, 60),
    );
    const { json, isError } = await mcp("get_recovery");
    expect(isError).toBe(false);
    const { ruleMeaning, ...rest } = json as RecoveryResponse & { ruleMeaning: string };
    expect(rest).toEqual({ ...(await recovery()), generatedAt: rest.generatedAt });
    expect(ruleMeaning).toMatch(/two mornings in a row/);
  });

  it("get_recovery for a learning rider has no rule", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 5));
    const { json } = await mcp("get_recovery");
    expect(json).toMatchObject({ state: "learning", rule: null, ruleMeaning: null });
  });

  it("get_wellness is compact by default: values in plain units, the usual ranges, who is outside", async () => {
    await uploadAll(
      h.app,
      history({ ...STEADY_NIGHTS, restingHr: planted(alternating(50, 54), { 1: 58, 0: 58 }) }, 60),
    );
    const { json, isError } = await mcp("get_wellness");
    expect(isError).toBe(false);
    const answer = json as {
      from: string;
      to: string;
      units: Record<string, string>;
      baselinesAsOfLastDay: Record<string, unknown>;
      days: Record<string, unknown>[];
    };
    // The last 14 days by default.
    expect(answer.to).toBe(TODAY);
    expect(answer.from).toBe("2026-11-05");
    expect(answer.days).toHaveLength(14);
    const last = answer.days[13];
    expect(last).toMatchObject({
      date: TODAY,
      restingHr: 58,
      sleepHours: 7.5,
      bedtime: "22:45",
      wake: "06:15",
      respiratoryRate: 13.8,
      spo2: 95,
      outsideUsualRange: [{ metric: "restingHr", side: "above", days: 2 }],
    });
    expect(answer.baselinesAsOfLastDay).toMatchObject({
      bedtime: { low: "21:45", high: "23:45" },
      sleepHours: { low: 7.17, high: 7.83 },
    });
    expect(answer.units.sleepHours).toBe("h");
    // Compact: no per-day readings with sources.
    expect(JSON.stringify(json)).not.toContain("baselineLow");
  });

  it("get_wellness with detail full is the contract's answer", async () => {
    await uploadAll(h.app, history(STEADY_NIGHTS, 20));
    const { json } = await mcp("get_wellness", { from: "2026-11-16", to: TODAY, detail: "full" });
    expect(validateWellnessResponse(json)).toMatchObject({ ok: true });
  });

  it("get_wellness says in plain words what is wrong with a range", async () => {
    for (const args of [
      { from: "2026-02-30" },
      { from: "2026-11-18", to: "2026-11-17" },
      { from: "2026-01-01", to: TODAY },
    ]) {
      const { isError, text } = await mcp("get_wellness", args);
      expect(isError, text).toBe(true);
      expect(text).toMatch(/not a real calendar date|is before from|at most 186/);
    }
  });

  it("uses no medical wording in what it says about itself or the state", async () => {
    const medical =
      /\b(ill|illness|sick|infect\w*|disease|diagnos\w*|fever|syndrome|overtrain\w*|doctor|symptom\w*|condition)\b/i;
    const { tools } = await client.listTools();
    for (const tool of tools.filter((t) => ["get_wellness", "get_recovery"].includes(t.name))) {
      // They may say what they are not.
      const text = (tool.description ?? "").replace(
        /not medical advice|names no cause|never a diagnosis/gi,
        "",
      );
      expect(text, tool.name).not.toMatch(medical);
    }
    await uploadAll(
      h.app,
      history({ ...STEADY_NIGHTS, restingHr: planted(alternating(50, 54), { 1: 62, 0: 62 }) }, 60),
    );
    const state = await recovery();
    expect(`${state.reason} ${state.guidance}`).not.toMatch(medical);
  });

  it("the endpoints refuse a token that is not a device's, and /v1/recovery needs one", async () => {
    expectError(await h.app.inject({ method: "GET", url: "/v1/recovery" }), "unauthorized");
  });
});
