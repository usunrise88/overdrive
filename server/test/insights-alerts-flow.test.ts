import { addDaysToIsoDate } from "@overdrive/contracts";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type FakeLlm, startFakeLlm } from "./helpers/fake-llm.ts";
import { type FakeTelegram, startFakeTelegram } from "./helpers/fake-telegram.ts";
import {
  at,
  boot,
  clearAll,
  getInsights,
  type InsightsEnv,
  notes,
  notifications,
  pass,
  restart,
  rows,
  sweetSpotRide,
  uploadRide,
} from "./helpers/insights-harness.ts";
import type { IntegrationHarness } from "./helpers/integrations.ts";
import { batch, nightRecords, post } from "./helpers/wellness-data.ts";

/**
 * The alerts through the whole service (spec §6.6, ADR 0025): a metric outside its own baseline for N mornings in a row, once per
 * streak; plan drift, at most once a week; both sent to Telegram once and listed in GET /v1/insights.
 */

let db: Db;
let env: InsightsEnv;
let intervals: FakeIntervals;
let telegram: FakeTelegram;
let llm: FakeLlm;

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  telegram = await startFakeTelegram();
  llm = await startFakeLlm(() => "unused");
  env = { db, intervals, telegram, llm, started: [] };
});
afterAll(async () => {
  await telegram.close();
  await intervals.close();
  await llm.close();
  await db.close();
});
beforeEach(async () => {
  await clearAll(db);
  intervals.reset();
  telegram.reset();
  llm.reset();
});
afterEach(async () => {
  await Promise.all(env.started.splice(0).map((h) => h.app.close()));
});

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 5000, interval: 20 });

/** Resting heart rate by date: 50 and 54 on alternating days (mean 52, usual range 50 to 54), `planted` on the days named. */
const restingOn = (date: string, planted: Record<string, number>): number => {
  const planted_ = planted[date];
  if (planted_ !== undefined) return planted_;
  const back = Math.round(
    (Date.parse("2026-11-19T00:00:00Z") - Date.parse(`${date}T00:00:00Z`)) / 86_400_000,
  );
  return back % 2 === 0 ? 50 : 54;
};

/** 60 nights ending on `last`, uploaded as the companion would. */
async function uploadNightsUntil(
  h: IntegrationHarness,
  last: string,
  planted: Record<string, number> = {},
  from?: string,
): Promise<void> {
  const first = from ?? addDaysToIsoDate(last, -59);
  const records = [];
  for (let date = first; date <= last; date = addDaysToIsoDate(date, 1)) {
    records.push(...nightRecords(date, { restingHr: restingOn(date, planted) }));
  }
  for (let i = 0; i < records.length; i += 1000) {
    const res = await post(h.app, batch(...records.slice(i, i + 1000)));
    expect(res.statusCode, res.body).toBe(200);
  }
}

const alerts = async (kind: string) => (await rows(db)).filter((r) => r.kind === kind);

describe("deviation alerts", () => {
  it("alerts at the third morning in a row outside the usual range, not at the second, once", async () => {
    const h = await boot(env, {
      now: "2026-11-17T07:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off" },
    });
    // 17 Nov: second morning above (the 16th and 17th).
    await uploadNightsUntil(h, "2026-11-17", { "2026-11-16": 58, "2026-11-17": 58 });
    await pass(h);
    expect(await alerts("deviation")).toEqual([]);

    // 18 Nov: the third.
    h.clock.set("2026-11-18T07:00:00Z");
    await uploadNightsUntil(
      h,
      "2026-11-18",
      { "2026-11-16": 58, "2026-11-17": 58, "2026-11-18": 59 },
      "2026-11-18",
    );
    await until(async () => expect(await alerts("deviation")).toHaveLength(1));
    const [alert] = await alerts("deviation");
    expect(alert?.source).toBe("template");
    expect(alert?.text).toMatch(
      /^Your resting heart rate has been above your usual range for 3 mornings in a row \(59 bpm, against a usual 50 to \d+\)\./,
    );
    expect(alert?.text).toContain("usually goes with accumulated fatigue or stress");
    expect(alert?.text).not.toMatch(/ill|infect|disease|doctor/i);
    expect(alert?.ride_id).toBeNull();
    await until(() => expect(notes(env).map((m) => m.text)).toEqual([alert?.text]));
  });

  it("alerts once per streak: the fourth day of the same run says nothing, a new run alerts again", async () => {
    const h = await boot(env, {
      now: "2026-11-18T07:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off" },
    });
    const planted: Record<string, number> = {
      "2026-11-16": 58,
      "2026-11-17": 58,
      "2026-11-18": 59,
    };
    await uploadNightsUntil(h, "2026-11-18", planted);
    await until(async () => expect(await alerts("deviation")).toHaveLength(1));

    // The fourth morning of the same run, and the fifth.
    for (const [day, value] of [
      ["2026-11-19", 58],
      ["2026-11-20", 60],
    ] as const) {
      planted[day] = value;
      h.clock.set(`${day}T07:00:00Z`);
      await uploadNightsUntil(h, day, planted, day);
      await pass(h);
    }
    expect(await alerts("deviation")).toHaveLength(1);

    // The run ends (a normal morning), and a new one of three begins: a new alert.
    for (const [day, value] of [
      ["2026-11-21", 52],
      ["2026-11-22", 58],
      ["2026-11-23", 59],
      ["2026-11-24", 60],
    ] as const) {
      planted[day] = value;
      h.clock.set(`${day}T07:00:00Z`);
      await uploadNightsUntil(h, day, planted, day);
      await pass(h);
    }
    expect(await alerts("deviation")).toHaveLength(2);
    await until(() => expect(notes(env)).toHaveLength(2));
    const streaks = await db.query<{ metric: string; start: string }>(
      "SELECT metric, streak_start::text AS start FROM alert_streaks ORDER BY streak_start",
    );
    expect(streaks.rows).toEqual([
      { metric: "restingHr", start: "2026-11-16" },
      { metric: "restingHr", start: "2026-11-22" },
    ]);
  });

  it("does not repeat an alert across a restart", async () => {
    const h = await boot(env, {
      now: "2026-11-18T07:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off" },
    });
    await uploadNightsUntil(h, "2026-11-18", {
      "2026-11-16": 58,
      "2026-11-17": 58,
      "2026-11-18": 59,
    });
    await until(async () => expect(await alerts("deviation")).toHaveLength(1));
    await until(() => expect(notes(env)).toHaveLength(1));
    const h2 = await restart(env, h, { config: { INSIGHT_MORNING_TIME: "off" } });
    await pass(h2);
    await at(h2, "2026-11-18T10:00:00Z");
    expect(await alerts("deviation")).toHaveLength(1);
    expect(notes(env)).toHaveLength(1);
    const sent = await notifications(db);
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ kind: "insight-deviation", state: "sent" });
    // The key says nothing about which metric deviated.
    expect(sent[0]?.key).not.toMatch(/restingHr|sleep|respiratory/i);
  });

  it("follows ALERT_STREAK_DAYS", async () => {
    const h = await boot(env, {
      now: "2026-11-18T07:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off", ALERT_STREAK_DAYS: 2 },
    });
    await uploadNightsUntil(h, "2026-11-18", { "2026-11-17": 58, "2026-11-18": 59 });
    await until(async () => expect(await alerts("deviation")).toHaveLength(1));
    expect((await alerts("deviation"))[0]?.text).toContain("for 2 mornings in a row");
  });

  it("is checked at INSIGHT_MORNING_TIME, with the morning note", async () => {
    const h = await boot(env, { now: "2026-11-18T06:00:00Z" });
    await uploadNightsUntil(h, "2026-11-18", {
      "2026-11-16": 58,
      "2026-11-17": 58,
      "2026-11-18": 59,
    });
    await pass(h);
    expect(await alerts("deviation")).toEqual([]);
    await at(h, "2026-11-18T07:00:00Z");
    expect(await alerts("deviation")).toHaveLength(1);
    expect((await rows(db)).map((r) => r.kind).sort()).toEqual(["deviation", "morning"]);
  });

  it("is listed with the other notes, as kind deviation", async () => {
    const h = await boot(env, {
      now: "2026-11-18T07:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off" },
    });
    await uploadNightsUntil(h, "2026-11-18", {
      "2026-11-16": 58,
      "2026-11-17": 58,
      "2026-11-18": 59,
    });
    await until(async () => expect(await alerts("deviation")).toHaveLength(1));
    const answer = await getInsights(h, "2026-11-18", "2026-11-18");
    expect(answer.insights.map((i) => [i.kind, i.rideId, i.topic])).toEqual([
      ["deviation", null, null],
    ]);
  });
});

// ---- Plan drift -----------------------------------------------------------------------------------

async function seedPlan(
  rowsToSeed: { id: string; date: string; load?: number | null; status?: string }[],
): Promise<void> {
  for (const row of rowsToSeed) {
    await db.query(
      `INSERT INTO plan_history (plan_id, plan_date, name, planned_seconds, planned_load, status)
       VALUES ($1, $2::date, $3, 2400, $4, $5)`,
      [
        row.id,
        row.date,
        `Workout ${row.id}`,
        row.load === undefined ? 60 : row.load,
        row.status ?? "planned",
      ],
    );
  }
}

describe("plan drift", () => {
  const WEEK = [
    { id: "icu-1", date: "2026-11-12" },
    { id: "icu-2", date: "2026-11-14" },
    { id: "icu-3", date: "2026-11-16" },
  ];

  it("alerts at the morning when two planned sessions of the last 7 days were not ridden, names them, and changes nothing in the plan", async () => {
    await seedPlan(WEEK);
    const h = await boot(env, {
      now: "2026-11-18T06:30:00Z",
      config: { INSIGHT_MORNING_TIME: "07:00" },
    });
    await pass(h);
    expect(await alerts("drift")).toEqual([]);
    await at(h, "2026-11-18T07:00:00Z");
    const [alert] = await alerts("drift");
    expect(alert).toMatchObject({ kind: "drift", key: "2026-11-16", source: "template" });
    expect(alert?.text).toMatch(
      /^3 planned sessions were not ridden in the last 7 days: Workout icu-1 on 12 Nov, Workout icu-2 on 14 Nov and Workout icu-3 on 16 Nov\./,
    );
    expect(alert?.text).toContain("The plan itself has not been changed.");
    expect(intervals.requests.filter((r) => r.method !== "GET")).toEqual([]);
    await until(() => expect(notes(env).map((m) => m.text)).toEqual([alert?.text]));
  });

  it("alerts at most once a week, and again the next week", async () => {
    await seedPlan(WEEK);
    const h = await boot(env, { now: "2026-11-18T07:00:00Z" });
    await until(async () => expect(await alerts("drift")).toHaveLength(1));
    // Later the same week: no second alert.
    await at(h, "2026-11-19T07:00:00Z");
    await at(h, "2026-11-22T07:00:00Z");
    expect(await alerts("drift")).toHaveLength(1);
    // The next week it may alert again.
    await seedPlan([
      { id: "icu-4", date: "2026-11-23" },
      { id: "icu-5", date: "2026-11-24" },
    ]);
    await at(h, "2026-11-26T07:00:00Z");
    expect((await alerts("drift")).map((r) => r.key)).toEqual(["2026-11-16", "2026-11-23"]);
    await until(() => expect(notes(env)).toHaveLength(2));
  });

  it("does not alert for one missed session, or for sessions the rider skipped", async () => {
    // One planned session of load 36 is too little to judge a week's load by, and the two skipped ones are the rider's choice.
    await seedPlan([
      { id: "icu-1", date: "2026-11-12", load: 36 },
      { id: "icu-2", date: "2026-11-14", status: "skipped" },
      { id: "icu-3", date: "2026-11-16", status: "skipped" },
    ]);
    const h = await boot(env, { now: "2026-11-18T07:00:00Z" });
    await pass(h);
    expect(await alerts("drift")).toEqual([]);
  });

  it("does not alert for one missed session out of three when the load is near the plan", async () => {
    await seedPlan([
      { id: "icu-1", date: "2026-11-12", load: 36 },
      { id: "icu-2", date: "2026-11-14", load: 36 },
      { id: "icu-3", date: "2026-11-16", load: 36 },
    ]);
    const h = await boot(env, { now: "2026-11-18T06:30:00Z" });
    await uploadRide(h, sweetSpotRide("ride-a", "2026-11-12T06:00:00Z", 2400, 220, "icu-1"));
    await uploadRide(h, sweetSpotRide("ride-b", "2026-11-14T06:00:00Z", 2400, 220, "icu-2"));
    await at(h, "2026-11-18T07:00:00Z");
    expect(await alerts("drift")).toEqual([]);
  });

  it("alerts when the load ridden is more than 30% above the plan, and says so", async () => {
    await seedPlan([
      { id: "icu-1", date: "2026-11-12", load: 30 },
      { id: "icu-2", date: "2026-11-14", load: 30 },
    ]);
    const h = await boot(env, {
      now: "2026-11-18T06:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off", ALERT_MISSED_SESSIONS: 5 },
    });
    // Two sessions planned (load 60), ridden as three hard rides.
    await uploadRide(h, sweetSpotRide("ride-a", "2026-11-12T06:00:00Z", 2400, 240, "icu-1"));
    await uploadRide(h, sweetSpotRide("ride-b", "2026-11-14T06:00:00Z", 2400, 240, "icu-2"));
    await uploadRide(h, sweetSpotRide("ride-c", "2026-11-15T06:00:00Z", 2400, 240, null));
    await pass(h);
    await at(h, "2026-11-18T07:00:00Z");
    // INSIGHT_MORNING_TIME is off: the checks keep the default 07:00.
    const [alert] = await alerts("drift");
    expect(alert?.text).toMatch(
      /You rode a load of \d+ against 60 planned over the last 7 days, \d+% above the plan\./,
    );
    expect(alert?.text).toContain("Make the next day easy");
  });

  it("alerts when the load is more than 40% below the plan, with or without missed sessions", async () => {
    await seedPlan([
      { id: "icu-1", date: "2026-11-12", load: 40 },
      { id: "icu-2", date: "2026-11-14", load: 40 },
      { id: "icu-3", date: "2026-11-16", load: 40 },
    ]);
    const h = await boot(env, {
      now: "2026-11-18T07:00:00Z",
      config: { ALERT_MISSED_SESSIONS: 7 },
    });
    await uploadRide(h, sweetSpotRide("ride-a", "2026-11-12T06:00:00Z", 900, 150, "icu-1"));
    await pass(h);
    await at(h, "2026-11-18T07:05:00Z");
    const [alert] = await alerts("drift");
    expect(alert?.text).toMatch(/below the plan\./);
    expect(alert?.text).toContain("ask the coach to reshape the week");
  });

  it("follows ALERT_MISSED_SESSIONS: two missed sessions alert at 2 and not at 3", async () => {
    // Two sessions planned, none paired with a ride (the rides are on other days), the load on plan.
    const seed = [
      { id: "icu-1", date: "2026-11-12", load: 36 },
      { id: "icu-2", date: "2026-11-14", load: 36 },
    ];
    await seedPlan(seed);
    const h = await boot(env, {
      now: "2026-11-18T06:30:00Z",
      config: { ALERT_MISSED_SESSIONS: 3 },
    });
    await uploadRide(h, sweetSpotRide("ride-a", "2026-11-13T06:00:00Z", 2400, 220, null));
    await uploadRide(h, sweetSpotRide("ride-b", "2026-11-15T06:00:00Z", 2400, 220, null));
    await at(h, "2026-11-18T07:00:00Z");
    expect(await alerts("drift")).toEqual([]);
    const again = await restart(env, h, { config: { ALERT_MISSED_SESSIONS: 2 } });
    await pass(again);
    expect((await alerts("drift")).map((r) => r.key)).toEqual(["2026-11-16"]);
  });
});

describe("repeated plan drift hands a topic to the coach", () => {
  it("attaches the repeated-drift topic to the next ride's note after the second drift alert in 28 days", async () => {
    const h = await boot(env, { now: "2026-11-18T09:00:00Z" });
    const facts = JSON.stringify({
      schema: 1,
      kind: "drift",
      date: "2026-11-02",
      weekday: "Monday",
      quotable: [],
    });
    for (const [key, when] of [
      ["2026-10-26", "2026-10-28T07:00:00Z"],
      ["2026-11-02", "2026-11-04T07:00:00Z"],
    ] as const) {
      await db.query(
        "INSERT INTO insights (kind, key, created_at, text, source, prompt_version, facts_hash, facts) VALUES ('drift', $1, $2::timestamptz, 'x', 'template', 'template.v1', $3, $4::jsonb)",
        [key, when, "0".repeat(64), facts],
      );
    }
    await uploadRide(h, {
      id: "ride-free",
      startEpochMillis: Date.parse("2026-11-18T08:00:00Z"),
      seconds: 1800,
      power: () => 160,
    });
    await until(async () =>
      expect((await rows(db)).filter((r) => r.kind === "ride")).toHaveLength(1),
    );
    const topics = await h.services.insights.topics(null, 10);
    expect(topics).toHaveLength(1);
    expect(topics[0]).toMatchObject({
      rule: "repeated-drift",
      rideId: "ride-free",
      status: "open",
    });
  });
});
