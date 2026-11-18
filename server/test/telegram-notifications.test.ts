import type { FastifyInstance } from "fastify";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { rideSummaryText, weeklySummaryText } from "../src/telegram/messages.ts";
import type { RideBrief } from "../src/telegram/rides.ts";
import { asRide, expectUpload, putFit, putRecord, rideFixture } from "./helpers/app.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  FAKE_BOT_TOKEN,
  type FakeTelegram,
  OWNER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";
import { buildFit } from "./helpers/fit.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  clearIntegrations,
  type IntegrationHarness,
  type IntegrationOptions,
  planWorkout,
  startIntegrationHarness,
} from "./helpers/integrations.ts";

let db: Db;
let intervals: FakeIntervals;
let telegram: FakeTelegram;
const started: IntegrationHarness[] = [];

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  telegram = await startFakeTelegram();
});
afterAll(async () => {
  await telegram.close();
  await intervals.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  await clearIntegrations(db);
  await db.exec("TRUNCATE ride_destinations, rides");
  intervals.reset();
  telegram.reset();
});
afterEach(async () => {
  await Promise.all(started.splice(0).map((h) => h.app.close()));
});

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 5000, interval: 20 });

const MINUTE = 60_000;

/** The service with the bot on, started; the bot's first pass and the first plan read are done. */
async function boot(options: IntegrationOptions = {}): Promise<IntegrationHarness> {
  const h = startIntegrationHarness(db, { intervals, telegram, ...options });
  started.push(h);
  h.start();
  await h.services.refresher?.refresh();
  await h.services.telegram?.idle();
  return h;
}

/** Closes a running service and starts another over the same database and the same instant. */
async function restart(
  h: IntegrationHarness,
  options: IntegrationOptions = {},
): Promise<IntegrationHarness> {
  await h.app.close();
  started.splice(started.indexOf(h), 1);
  return boot({ now: h.clock.now().toISOString(), ...options });
}

/** One pass of the bot's scheduled messages at the clock's instant. */
async function tick(h: IntegrationHarness): Promise<void> {
  h.timers.tick();
  await h.services.telegram?.idle();
}

const said = (): string[] => telegram.sentTo(OWNER_CHAT).map((m) => m.text);

interface NotificationRow {
  kind: string;
  key: string;
  state: string;
  attempts: number;
}
async function notifications(): Promise<NotificationRow[]> {
  const result = await db.query<NotificationRow>(
    "SELECT kind, key, state, attempts FROM bot_notifications ORDER BY kind, key",
  );
  return result.rows;
}

const STEPS = "10 min at 60% FTP, 20 min at 88% FTP, 10 min at 50% FTP.";

describe("reminders", () => {
  it("sends the evening reminder and the lead reminder once each, across restarts", async () => {
    const id = planWorkout(intervals, "2026-11-19", "Sweet spot"); // Thursday 07:00 Paris = 06:00 UTC
    let h = await boot({ now: "2026-11-18T18:59:00Z" }); // 19:59 in Paris
    await tick(h);
    expect(said()).toEqual([]);

    h.clock.set("2026-11-18T19:00:00Z"); // 20:00
    await tick(h);
    expect(said()).toEqual([`Tomorrow at 07:00: Sweet spot, 40 min.\n${STEPS}`]);
    expect(telegram.sentTo(OWNER_CHAT)[0]?.buttons).toEqual([
      [
        { text: "Skip", data: `sk:icu-${id}` },
        { text: "Move", data: `mv:icu-${id}` },
      ],
    ]);
    await tick(h);
    await tick(h);
    expect(said()).toHaveLength(1);

    // A restart neither repeats it nor forgets that it was sent.
    h = await restart(h);
    await tick(h);
    expect(said()).toHaveLength(1);

    h.clock.set("2026-11-19T04:59:00Z"); // 05:59, an hour and a minute before
    await tick(h);
    expect(said()).toHaveLength(1);
    h.clock.set("2026-11-19T05:00:00Z"); // 06:00 Paris: 60 minutes before the start
    await tick(h);
    expect(said()).toEqual([
      `Tomorrow at 07:00: Sweet spot, 40 min.\n${STEPS}`,
      `Starts at 07:00, in 1 h: Sweet spot, 40 min.\n${STEPS}`,
    ]);
    h = await restart(h);
    await tick(h);
    h.clock.set("2026-11-19T05:30:00Z");
    await tick(h);
    expect(said()).toHaveLength(2);
    expect(await notifications()).toEqual([
      { kind: "reminder-evening", key: `icu-${id}@2026-11-19`, state: "sent", attempts: 0 },
      { kind: "reminder-lead", key: `icu-${id}@2026-11-19`, state: "sent", attempts: 0 },
    ]);
  });

  it("does not lose a reminder that came due while the service was down", async () => {
    planWorkout(intervals, "2026-11-19");
    const h = await boot({ now: "2026-11-18T19:30:00Z" }); // down at 20:00, up at 20:30
    expect(said()).toEqual([`Tomorrow at 07:00: Sweet spot, 40 min.\n${STEPS}`]);
    await restart(h);
    expect(said()).toHaveLength(1);
  });

  it("does not send 'tomorrow' once the second reminder is due or the workout has started", async () => {
    planWorkout(intervals, "2026-11-19");
    const h = await boot({ now: "2026-11-19T05:30:00Z" }); // the evening reminder's time is long past
    expect(said()).toEqual([`Starts at 07:00, in 30 min: Sweet spot, 40 min.\n${STEPS}`]);
    h.clock.set("2026-11-19T06:30:00Z"); // after the start: nothing more
    await tick(h);
    expect(said()).toHaveLength(1);
  });

  it("says 'today' for a workout planned late, and sends nothing for one whose time has passed", async () => {
    planWorkout(intervals, "2026-11-18", "Sweet spot"); // today
    const h = await boot({ now: "2026-11-18T03:00:00Z", config: { REMINDER_LEAD_MINUTES: 0 } }); // 04:00 Paris
    expect(said()).toEqual([`Today at 07:00: Sweet spot, 40 min.\n${STEPS}`]);
    h.clock.set("2026-11-18T07:00:00Z"); // 08:00, the workout started an hour ago
    await tick(h);
    expect(said()).toHaveLength(1);
  });

  it("follows REMINDER_EVENING_TIME, REMINDER_LEAD_MINUTES and GCAL_WORKOUT_START", async () => {
    planWorkout(intervals, "2026-11-19");
    const h = await boot({
      now: "2026-11-18T20:29:00Z",
      config: {
        REMINDER_EVENING_TIME: "21:30",
        REMINDER_LEAD_MINUTES: 120,
        GCAL_WORKOUT_START: "08:00",
      },
    });
    expect(said()).toEqual([]);
    h.clock.set("2026-11-18T20:30:00Z"); // 21:30 in Paris
    await tick(h);
    expect(said()).toEqual([`Tomorrow at 08:00: Sweet spot, 40 min.\n${STEPS}`]);
    h.clock.set("2026-11-19T04:59:00Z");
    await tick(h);
    expect(said()).toHaveLength(1);
    h.clock.set("2026-11-19T05:00:00Z"); // 06:00 Paris: two hours before 08:00
    await tick(h);
    expect(said()[1]).toBe(`Starts at 08:00, in 2 h: Sweet spot, 40 min.\n${STEPS}`);
  });

  it("sends nothing when both reminders are switched off", async () => {
    planWorkout(intervals, "2026-11-19");
    const h = await boot({
      now: "2026-11-18T08:00:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    for (const at of ["2026-11-18T19:30:00Z", "2026-11-19T05:30:00Z", "2026-11-19T05:59:00Z"]) {
      h.clock.set(at);
      await tick(h);
    }
    expect(said()).toEqual([]);
    expect(await notifications()).toEqual([]);
  });

  it("drops a reminder for a workout that was removed or moved before it came due", async () => {
    const id = planWorkout(intervals, "2026-11-19");
    const h = await boot({ now: "2026-11-18T18:30:00Z" });
    // Moved to Friday: the Thursday reminder is not sent, Friday's will be.
    const event = intervals.events.find((e) => e.id === id);
    if (event === undefined) throw new Error("no event");
    event.start_date_local = "2026-11-20T00:00:00";
    await h.services.refresher?.refresh();
    h.clock.set("2026-11-18T19:30:00Z");
    await tick(h);
    expect(said()).toEqual([]);
    h.clock.set("2026-11-19T19:30:00Z");
    await tick(h);
    expect(said()).toEqual([`Tomorrow at 07:00: Sweet spot, 40 min.\n${STEPS}`]);

    intervals.events.length = 0;
    await h.services.refresher?.refresh();
    h.clock.set("2026-11-20T05:30:00Z");
    await tick(h);
    expect(said()).toHaveLength(1);
  });

  it("does not remind about a workout that is already ridden, and drops that reminder for good", async () => {
    const id = planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot({ now: "2026-11-18T04:30:00Z", config: { REMINDER_EVENING_TIME: "off" } });
    h.clock.set("2026-11-18T05:20:00Z"); // the lead reminder (05:00) is due
    const record = asRide(
      rideFixture("synthetic-5min.json"),
      "early-bird",
      Date.parse("2026-11-18T05:10:00Z"),
    );
    record.ride.workoutId = `icu-${id}`;
    expectUpload(await putRecord(h.app, "early-bird", record), "early-bird", "stored");
    expectUpload(await putFit(h.app, "early-bird", buildFit()), "early-bird", "stored");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toMatch(/^Ride saved: 5 min\./);
    // The message is out; the pass that sent it marks it and ends a moment later.
    await h.services.telegram?.idle();
    expect((await notifications()).map((n) => `${n.kind}:${n.state}`)).toEqual([
      "reminder-lead:dropped",
      "ride-summary:sent",
    ]);
  });

  it("retries a failed send later, from the table, and sends it exactly once", async () => {
    planWorkout(intervals, "2026-11-19");
    telegram.failNext(3, 500, { method: "sendMessage" }); // the client's three attempts
    const h = await boot({ now: "2026-11-18T19:00:00Z" });
    expect(said()).toEqual([]);
    expect(await notifications()).toMatchObject([
      { kind: "reminder-evening", state: "pending", attempts: 1 },
    ]);

    await tick(h); // 30 seconds have not passed: no new try
    expect(telegram.callsTo("sendMessage")).toHaveLength(3);
    h.clock.advance(30_000);
    await tick(h);
    expect(said()).toHaveLength(1);
    expect(await notifications()).toMatchObject([{ state: "sent", attempts: 1 }]);
    h.clock.advance(10 * MINUTE);
    await tick(h);
    expect(said()).toHaveLength(1);
  });

  it("waits out a 429 as Telegram asks and does not send twice", async () => {
    planWorkout(intervals, "2026-11-19");
    telegram.failNext(1, 429, { method: "sendMessage", retryAfter: 4 });
    const h = await boot({ now: "2026-11-18T19:00:00Z" });
    expect(said()).toHaveLength(1);
    expect(h.logging.text()).not.toContain("failed");
  });

  it("treats a try that crashed as still running until its lease ends, then tries again", async () => {
    const id = planWorkout(intervals, "2026-11-19");
    // What a crash between the claim and the send leaves behind.
    await db.query(
      `INSERT INTO bot_notifications (kind, key, state, next_attempt_at)
       VALUES ('reminder-evening', $1, 'pending', '2026-11-18T19:10:00Z')`,
      [`icu-${id}@2026-11-19`],
    );
    const h = await boot({ now: "2026-11-18T19:00:00Z" });
    expect(said()).toEqual([]);
    h.clock.set("2026-11-18T19:10:00Z");
    await tick(h);
    expect(said()).toHaveLength(1);
  });

  it("stops asking Telegram when the token is refused, and never prints it", async () => {
    planWorkout(intervals, "2026-11-19");
    planWorkout(intervals, "2026-11-19", "Second");
    telegram.failNext(1, 401, {
      method: "sendMessage",
      description: `Unauthorized ${FAKE_BOT_TOKEN}`,
    });
    const h = await boot({ now: "2026-11-18T19:00:00Z" });
    expect(telegram.callsTo("sendMessage")).toHaveLength(1);
    const log = h.logging.text();
    expect(log).toContain("TELEGRAM_BOT_TOKEN");
    expect(log).not.toContain(FAKE_BOT_TOKEN);
    expect(log).not.toContain(FAKE_BOT_TOKEN.split(":")[1] ?? "x");
    expect(JSON.stringify(await notifications())).not.toContain(
      FAKE_BOT_TOKEN.split(":")[1] ?? "x",
    );
  });
});

/** Stores a ride and its FIT through the API the way the tablet does. */
async function storeRide(
  app: FastifyInstance,
  id: string,
  start: string,
  options: { workoutId?: string; load?: number; moving?: number } = {},
): Promise<void> {
  const record = asRide(rideFixture("synthetic-5min.json"), id, Date.parse(start));
  if (options.workoutId === undefined) record.ride.workoutId = null;
  else record.ride.workoutId = options.workoutId;
  if (options.load !== undefined) record.summary.trainingStressScore = options.load;
  if (options.moving !== undefined) record.summary.movingSeconds = options.moving;
  expectUpload(await putRecord(app, id, record), id, "stored");
  const fit = buildFit({ data: Uint8Array.from([id.length, ...Buffer.from(id).subarray(0, 7)]) });
  expectUpload(await putFit(app, id, fit), id, "stored");
}

describe("the summary after a ride", () => {
  it("says duration, power, heart rate and load from the stored ride, once", async () => {
    const h = await boot({ now: "2026-11-18T10:30:00Z" });
    await storeRide(h.app, "synthetic-5min", "2026-11-18T10:00:00Z");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toBe(
      [
        "Ride saved: 5 min.",
        "Power: 162 W average, 176 W normalised.",
        "Heart rate: 131 bpm average, 161 bpm max.",
        "Load: 4.",
      ].join("\n"),
    );
    await tick(h);
    const again = await restart(h);
    await tick(again);
    expect(said()).toHaveLength(1);
    expect(await notifications()).toMatchObject([
      { kind: "ride-summary", key: "synthetic-5min", state: "sent" },
    ]);
  });

  it("compares with the plan when the ride came from a planned workout", async () => {
    const id = planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot({
      now: "2026-11-18T10:30:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    await storeRide(h.app, "ride-linked", "2026-11-18T10:00:00Z", { workoutId: `icu-${id}` });
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toContain("Plan: Sweet spot. Planned 40 min, ridden 5 min (35 min short).");
  });

  it("links a ride that names no workout to the only one planned that day", async () => {
    planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot({
      now: "2026-11-18T10:30:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    await storeRide(h.app, "ride-free", "2026-11-18T10:00:00Z");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toContain("Plan: Sweet spot.");
  });

  it("does not guess when two workouts were planned that day", async () => {
    planWorkout(intervals, "2026-11-18", "One");
    planWorkout(intervals, "2026-11-18", "Two");
    const h = await boot({
      now: "2026-11-18T10:30:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    await storeRide(h.app, "ride-free", "2026-11-18T10:00:00Z");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).not.toContain("Plan:");
  });

  it("catches up on rides stored while the bot was down, but only recent ones, and sends none of the very short", async () => {
    const quiet = startIntegrationHarness(db, { intervals, now: "2026-11-18T10:30:00Z" });
    started.push(quiet);
    await storeRide(quiet.app, "yesterday", "2026-11-17T18:00:00Z");
    await storeRide(quiet.app, "long-ago", "2026-11-10T10:00:00Z");
    const accident = asRide(
      rideFixture("minimal-free-ride.json"),
      "accident",
      Date.parse("2026-11-18T09:00:00Z"),
    );
    expectUpload(await putRecord(quiet.app, "accident", accident), "accident", "stored");
    expectUpload(
      await putFit(quiet.app, "accident", buildFit({ data: Uint8Array.from([9, 9, 9, 9]) })),
      "accident",
      "stored",
    );

    await boot({ now: "2026-11-18T10:30:00Z" });
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toMatch(/^Ride saved: 5 min\./);
    expect((await notifications()).map((n) => `${n.key}:${n.state}`)).toEqual([
      "accident:dropped",
      "yesterday:sent",
    ]);
  });

  it("tells about a ride that arrives late, as long as it arrives while the bot runs", async () => {
    const h = await boot({ now: "2026-11-18T10:30:00Z" });
    await storeRide(h.app, "old-news", "2026-11-14T10:00:00Z");
    await until(() => expect(said()).toHaveLength(1));
    await restart(h);
    expect(said()).toHaveLength(1);
  });
});

/** Plans a one-hour workout on `date` in the fake intervals.icu under a chosen event id (so the plan id is `icu-<id>`). */
function planHour(id: number, date: string, name: string): void {
  intervals.addEvent({ ...plannedEvent(date, name, [steady(3600, 60)]), id });
}

const weekly = (): string[] => said().filter((text) => text.startsWith("Week "));

/** Rides of the week of 9 to 15 Nov 2026 and of the week before, stored while no bot ran. */
async function ridesOfTheWeeks(): Promise<void> {
  const quiet = startIntegrationHarness(db, { intervals, now: "2026-11-15T10:00:00Z" });
  started.push(quiet);
  await storeRide(quiet.app, "mon", "2026-11-09T06:00:00Z", {
    workoutId: "icu-1",
    load: 50,
    moving: 3600,
  });
  await storeRide(quiet.app, "wed", "2026-11-11T06:00:00Z", {
    workoutId: "icu-2",
    load: 70,
    moving: 1800,
  });
  await storeRide(quiet.app, "sun", "2026-11-15T07:00:00Z", { load: 30, moving: 2400 });
  await storeRide(quiet.app, "prev-1", "2026-11-03T06:00:00Z", { load: 40, moving: 3000 });
  await storeRide(quiet.app, "prev-2", "2026-11-05T06:00:00Z", { load: 60, moving: 2400 });
}

describe("the weekly summary", () => {
  /**
   * The week as it really goes: the plan is in intervals.icu and is read on Monday evening, the bot's history
   * takes it in, and by Sunday the cache has dropped the days that passed (the refresher only reads from today on).
   * Ends on Sunday 15 Nov, 11:00 in Paris.
   */
  async function theWeek(config: IntegrationOptions["config"] = {}): Promise<IntegrationHarness> {
    await ridesOfTheWeeks();
    planHour(1, "2026-11-09", "Intervals A");
    planHour(2, "2026-11-11", "Tempo B");
    planHour(3, "2026-11-13", "Endurance C");
    planHour(4, "2026-11-14", "Recovery D");
    planHour(5, "2026-11-15", "Long E");
    const h = await boot({
      now: "2026-11-09T18:00:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0, ...config },
    });
    expect(await db.query("SELECT plan_id FROM plan_history ORDER BY plan_id")).toMatchObject({
      rows: [1, 2, 3, 4, 5].map((n) => ({ plan_id: `icu-${n}` })),
    });
    // D was skipped from the bot.
    await db.query("UPDATE plan_history SET status = 'skipped' WHERE plan_id = 'icu-4'");

    h.clock.set("2026-11-15T10:00:00Z");
    await h.services.refresher?.refresh();
    await h.services.telegram?.idle();
    expect(await h.services.plan.get("icu-1")).toBeNull();
    expect(await h.services.plan.get("icu-5")).not.toBeNull();
    return h;
  }

  it("counts planned against completed and the load against the week before, on Sunday at 18:00, once", async () => {
    const h = await theWeek();
    h.clock.set("2026-11-15T16:59:00Z"); // 17:59 in Paris
    await tick(h);
    expect(weekly()).toEqual([]);
    h.clock.set("2026-11-15T17:00:00Z");
    await tick(h);
    expect(weekly()).toEqual([
      [
        "Week 9 Nov to 15 Nov",
        "Sessions: 3 of 4 planned done, 1 skipped.",
        "Missed: Endurance C (Fri 13 Nov).",
        "Rides: 3, 2 h 10 min.",
        "Load: 150, up 50 on the week before (100).",
      ].join("\n"),
    ]);
    await tick(h);
    const again = await restart(h);
    await tick(again);
    again.clock.set("2026-11-16T08:00:00Z");
    await tick(again);
    expect(weekly()).toHaveLength(1);
    expect(await notifications()).toContainEqual({
      kind: "week-summary",
      key: "2026-11-15",
      state: "sent",
      attempts: 0,
    });
  });

  it("still sends the week that ended when the service was down at 18:00", async () => {
    // The service was off from Sunday morning until Monday 09:00 in Paris.
    const h = await restart(await theWeek(), {
      now: "2026-11-16T08:00:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    await tick(h);
    expect(weekly()).toHaveLength(1);
    expect(weekly()[0]).toMatch(/^Week 9 Nov to 15 Nov\n/);
    h.clock.set("2026-11-16T19:00:00Z"); // a day and an hour later the moment has gone for good
    await tick(h);
    expect(weekly()).toHaveLength(1);
  });

  it("follows WEEKLY_SUMMARY_DAY and WEEKLY_SUMMARY_TIME, comparing with the same days of the week before", async () => {
    const h = await theWeek({ WEEKLY_SUMMARY_DAY: "fri", WEEKLY_SUMMARY_TIME: "09:15" });
    h.clock.set("2026-11-13T08:14:00Z");
    await tick(h);
    expect(weekly()).toEqual([]);
    h.clock.set("2026-11-13T08:15:00Z");
    await tick(h);
    expect(weekly()).toEqual([
      [
        "Week 9 Nov to 13 Nov",
        "Sessions: 2 of 3 planned done.",
        "Missed: Endurance C (Fri 13 Nov).",
        "Rides: 2, 1 h 30 min.",
        "Load: 120, up 20 on the week before (100).",
      ].join("\n"),
    ]);
  });

  it("is off when WEEKLY_SUMMARY_TIME is off", async () => {
    const h = await theWeek({ WEEKLY_SUMMARY_TIME: "off" });
    h.clock.set("2026-11-15T17:30:00Z");
    await tick(h);
    expect(weekly()).toEqual([]);
  });

  it("says nothing about a week with no plan and no rides", async () => {
    const h = await boot({
      now: "2026-11-15T17:30:00Z",
      config: { REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: 0 },
    });
    await tick(h);
    expect(said()).toEqual([]);
    expect(await notifications()).toEqual([
      { kind: "week-summary", key: "2026-11-15", state: "dropped", attempts: 0 },
    ]);
  });
});

describe("the texts, from numbers", () => {
  const ride = (extra: Partial<RideBrief> = {}): RideBrief => ({
    id: "r",
    startMs: 0,
    workoutId: null,
    durationSeconds: 3725,
    movingSeconds: 3700,
    averagePower: null,
    normalizedPower: null,
    averageHeartRate: null,
    maxHeartRate: null,
    trainingStressScore: null,
    ...extra,
  });

  it("leaves out what the ride did not measure", () => {
    expect(rideSummaryText(ride(), null)).toBe("Ride saved: 1 h 02 min 5 s.");
    expect(rideSummaryText(ride({ averagePower: 187.6 }), null)).toBe(
      "Ride saved: 1 h 02 min 5 s.\nPower: 188 W average.",
    );
    expect(rideSummaryText(ride({ averageHeartRate: 141.5 }), null)).toContain(
      "Heart rate: 142 bpm average.",
    );
  });

  it("says how a ride compared with its plan: short, over, or on time", () => {
    const planned = (seconds: number | null) => ({ name: "Sweet spot", plannedSeconds: seconds });
    expect(rideSummaryText(ride({ durationSeconds: 3600 }), planned(3600))).toContain(
      "Planned 1 h, ridden 1 h.",
    );
    expect(rideSummaryText(ride({ durationSeconds: 3000 }), planned(3600))).toContain(
      "(10 min short)",
    );
    expect(rideSummaryText(ride({ durationSeconds: 4200 }), planned(3600))).toContain(
      "(10 min over)",
    );
    expect(rideSummaryText(ride({ durationSeconds: 3630 }), planned(3600))).not.toContain("over");
    expect(rideSummaryText(ride(), planned(null))).toContain("Plan: Sweet spot.");
  });

  it("writes the week in plain words", () => {
    const base = {
      from: "2026-11-09",
      to: "2026-11-15",
      planned: 0,
      done: 0,
      skipped: 0,
      missed: [],
      now: { rides: 0, movingSeconds: 0, load: 0 },
      previous: { rides: 0, movingSeconds: 0, load: 0 },
    };
    expect(weeklySummaryText(base)).toBe(
      "Week 9 Nov to 15 Nov\nSessions: nothing was planned.\nRides: none.\nLoad: 0 (no rides the week before).",
    );
    expect(
      weeklySummaryText({
        ...base,
        planned: 2,
        done: 2,
        now: { rides: 2, movingSeconds: 7200, load: 100.4 },
        previous: { rides: 3, movingSeconds: 9000, load: 100.2 },
      }),
    ).toBe(
      "Week 9 Nov to 15 Nov\nSessions: 2 of 2 planned done.\nRides: 2, 2 h.\nLoad: 100, the same as the week before.",
    );
    expect(
      weeklySummaryText({
        ...base,
        now: { rides: 1, movingSeconds: 3600, load: 40 },
        previous: { rides: 3, movingSeconds: 9000, load: 90 },
      }),
    ).toContain("Load: 40, down 50 on the week before (90).");
  });
});
