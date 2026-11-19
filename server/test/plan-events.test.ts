import { validatePlanEvent } from "@overdrive/contracts";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { PlanEventError } from "../src/plan-events/service.ts";
import {
  asRide,
  bearer,
  COMPANION,
  expectError,
  putRecord,
  rideFixture,
  TABLET,
} from "./helpers/app.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type FakeTelegram, OWNER_CHAT, startFakeTelegram } from "./helpers/fake-telegram.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  clearIntegrations,
  type IntegrationHarness,
  type IntegrationOptions,
  startIntegrationHarness,
} from "./helpers/integrations.ts";

/**
 * Plan events (spec §12.2, ADR 0035): what is detected (a planned day that ended with no activity, a session the rider moved or
 * deleted in intervals.icu), what the rider declares (illness, travel), that each is one event however often it is found, and what
 * the rider is told when the coach agent resolves one. The service only detects and queues: nothing here moves a workout.
 */

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
  await db.exec("TRUNCATE ride_destinations, rides, plan_events, plan_event_cursor");
  intervals.reset();
  telegram.reset();
});
afterEach(async () => {
  await Promise.all(started.splice(0).map((h) => h.app.close()));
});

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 5000, interval: 20 });

/** The service with the plan source (and, if asked, the bot) but no loops: the tests call what they test. */
function make(options: IntegrationOptions = {}): IntegrationHarness {
  const h = startIntegrationHarness(db, { intervals, ...options });
  started.push(h);
  return h;
}

/** The service with the loops running, the bot on and the first plan read done. */
async function boot(options: IntegrationOptions = {}): Promise<IntegrationHarness> {
  const h = make({ telegram, ...options });
  h.start();
  await h.services.refresher?.refresh();
  await h.services.telegram?.idle();
  await h.services.planEvents.idle();
  return h;
}

/** A row of the plan history, as the bot and the insights keep it: what was planned on a day, kept after the plan drops it. */
async function planned(
  id: string,
  date: string,
  name = "Sweet spot 3x10",
  status: "planned" | "skipped" = "planned",
): Promise<void> {
  await db.query(
    `INSERT INTO plan_history (plan_id, plan_date, name, planned_seconds, planned_load, status)
     VALUES ($1, $2::date, $3, 3600, 62, $4)`,
    [id, date, name, status],
  );
}

/** A ride on the tablet started at `startedAt`, naming the planned workout it followed (or none). */
async function ride(
  h: IntegrationHarness,
  id: string,
  startedAt: string,
  workoutId: string | null,
) {
  const record = asRide(rideFixture("synthetic-5min.json"), id, Date.parse(startedAt));
  if (workoutId === null) delete record.ride.workoutId;
  else record.ride.workoutId = workoutId;
  expect((await putRecord(h.app, id, record)).statusCode).toBe(201);
}

async function events(h: IntegrationHarness, status: "open" | "resolved" | "all" = "all") {
  return (await h.services.planEvents.list({ status, limit: 100 })).events;
}

/** The "Plan update" messages the owner was sent (reminders and the like are not these). */
const updates = (): string[] =>
  telegram
    .sentTo(OWNER_CHAT)
    .map((m) => m.text)
    .filter((text) => text.startsWith("Plan update"));

describe("a planned day that ended with no matching activity", () => {
  // Paris is UTC+1 in November: 02:00 UTC on the 18th is 03:00 there, when the 17th has had its three hours of grace.
  const AFTER_GRACE = "2026-11-18T02:00:00Z";

  it("becomes one event once the day is over and its grace has passed, not before", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = make({ now: "2026-11-18T01:59:00Z" });
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    expect(await events(h)).toEqual([]);

    h.clock.set(AFTER_GRACE);
    expect(await h.services.planEvents.detectMissed()).toBe(1);
    const [event, ...others] = await events(h);
    expect(others).toEqual([]);
    expect(event).toMatchObject({
      kind: "missed",
      day: "2026-11-17",
      workoutId: "icu-5001",
      detail: { name: "Sweet spot 3x10", plannedSeconds: 3600, plannedLoad: 62 },
      status: "open",
      createdAt: "2026-11-18T02:00:00.000Z",
      resolvedAt: null,
      resolutionNote: null,
      dropped: null,
    });
    expect(validatePlanEvent(event), JSON.stringify(event)).toMatchObject({ ok: true });
  });

  it("is one event, not one per look: the same day found again changes nothing", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = make({ now: AFTER_GRACE });
    expect(await h.services.planEvents.detectMissed()).toBe(1);
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    // Even if the record of how far the check got is lost, the natural key keeps it one event.
    await db.exec("DELETE FROM plan_event_cursor");
    h.clock.set("2026-11-18T09:00:00Z");
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    await db.exec("DELETE FROM plan_event_cursor");
    h.clock.set(AFTER_GRACE);
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    expect(await events(h)).toHaveLength(1);
  });

  it("is not made of a session a ride completed, by the rule the missed-session alert uses", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = make({ now: AFTER_GRACE });
    await ride(h, "ride-a", "2026-11-17T06:00:00Z", "icu-5001");
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    expect(await events(h)).toEqual([]);
  });

  it("counts a ride that does not name its workout for the only session of that day, and not for one of two", async () => {
    await planned("icu-5001", "2026-11-17");
    const one = make({ now: AFTER_GRACE });
    await ride(one, "ride-free", "2026-11-17T06:00:00Z", null);
    expect(await one.services.planEvents.detectMissed()).toBe(0);

    await db.exec("TRUNCATE plan_event_cursor");
    await planned("icu-5002", "2026-11-17", "Endurance");
    // Two were planned and one free ride was made: it does not guess which, so both are missed.
    expect(await one.services.planEvents.detectMissed()).toBe(2);
    expect((await events(one)).map((e) => e.workoutId).sort()).toEqual(["icu-5001", "icu-5002"]);
  });

  it("makes an event of the session that was not ridden when another of the day was", async () => {
    await planned("icu-5001", "2026-11-17");
    await planned("icu-5002", "2026-11-17", "Endurance");
    const h = make({ now: AFTER_GRACE });
    await ride(h, "ride-a", "2026-11-17T06:00:00Z", "icu-5002");
    expect(await h.services.planEvents.detectMissed()).toBe(1);
    expect(await events(h)).toMatchObject([
      { workoutId: "icu-5001", detail: { name: "Sweet spot 3x10" } },
    ]);
  });

  it("is not made of a session the rider skipped", async () => {
    await planned("icu-5001", "2026-11-17", "Skipped one", "skipped");
    const h = make({ now: AFTER_GRACE });
    expect(await h.services.planEvents.detectMissed()).toBe(0);
  });

  it("gives a late upload its grace: a ride that arrives after midnight is not taken for a missed session", async () => {
    await planned("icu-5001", "2026-11-17");
    // 02:30 in Paris on the 18th: the 17th is over but still inside its grace.
    const h = make({ now: "2026-11-18T01:30:00Z" });
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    await ride(h, "ride-late", "2026-11-17T21:30:00Z", "icu-5001");
    h.clock.set("2026-11-18T02:05:00Z");
    expect(await h.services.planEvents.detectMissed()).toBe(0);
    expect(await events(h)).toEqual([]);
  });

  it("looks only at the last day the first time: the history from before it was switched on is not made into events", async () => {
    for (const day of ["2026-11-14", "2026-11-15", "2026-11-16", "2026-11-17"]) {
      await planned(`icu-${day.slice(-2)}`, day);
    }
    const h = make({ now: AFTER_GRACE });
    expect(await h.services.planEvents.detectMissed()).toBe(1);
    expect((await events(h)).map((e) => e.day)).toEqual(["2026-11-17"]);
  });

  it("catches up on the days that ended while the service was down, at most a week back", async () => {
    for (const day of [
      "2026-11-10",
      "2026-11-11",
      "2026-11-12",
      "2026-11-13",
      "2026-11-14",
      "2026-11-17",
    ]) {
      await planned(`icu-${day.slice(-2)}`, day);
    }
    // The check had got as far as the 12th.
    await db.query(
      "INSERT INTO plan_event_cursor (id, checked_through) VALUES (true, '2026-11-12')",
    );
    const h = make({ now: AFTER_GRACE });
    expect(await h.services.planEvents.detectMissed()).toBe(3);
    expect((await events(h)).map((e) => e.day).sort()).toEqual([
      "2026-11-13",
      "2026-11-14",
      "2026-11-17",
    ]);

    // A cursor from long ago looks back a week and no further: the 10th is out, the 11th is in.
    await db.exec("DELETE FROM plan_events");
    await db.exec("UPDATE plan_event_cursor SET checked_through = '2026-10-01'");
    expect(await h.services.planEvents.detectMissed()).toBe(5);
    expect((await events(h)).map((e) => e.day).sort()).toEqual([
      "2026-11-11",
      "2026-11-12",
      "2026-11-13",
      "2026-11-14",
      "2026-11-17",
    ]);
  });

  it("runs on its own: at start, and every ten minutes afterwards", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = await boot({ now: "2026-11-18T01:59:00Z" });
    expect(await events(h)).toEqual([]);
    expect(h.timers.pending.some((t) => t.kind === "every" && t.ms === 600_000)).toBe(true);
    h.clock.set(AFTER_GRACE);
    h.timers.tick();
    await h.services.planEvents.idle();
    expect(await events(h)).toHaveLength(1);
  });

  it("does not run, and sets no timer, without a plan source", async () => {
    // No intervals.icu key: there is no plan to miss a session of.
    const bare = startIntegrationHarness(db, {});
    started.push(bare);
    bare.start();
    expect(bare.timers.pending).toEqual([]);
    expect(await bare.services.planEvents.list({ status: "all", limit: 5 })).toEqual({
      total: 0,
      events: [],
    });
    // The declarations still work without one.
    expect((await bare.services.planEvents.declare("illness", null)).created).toBe(true);
  });
});

describe("a session the rider moved or deleted in intervals.icu", () => {
  const plan = (date: string, name = "Threshold 4x8") =>
    intervals.addEvent(plannedEvent(date, name, [steady(600, 60), steady(1200, 95)]));

  it("makes no event of what the first refresh finds, or of a session that is added", async () => {
    plan("2026-11-20");
    const h = make();
    await h.services.refresher?.refresh();
    plan("2026-11-21", "Endurance");
    await h.services.refresher?.refresh();
    expect(await events(h)).toEqual([]);
  });

  it("makes one event of a session moved to another day, once, with where it went", async () => {
    const event = plan("2026-11-20");
    const h = make();
    await h.services.refresher?.refresh();
    event.start_date_local = "2026-11-22T00:00:00";
    await h.services.refresher?.refresh();
    await h.services.refresher?.refresh();

    expect(await events(h)).toMatchObject([
      {
        kind: "moved",
        day: "2026-11-20",
        workoutId: `icu-${event.id}`,
        detail: { name: "Threshold 4x8", from: "2026-11-20", to: "2026-11-22" },
        status: "open",
      },
    ]);
    // Moved again: a new event, for the new move.
    event.start_date_local = "2026-11-24T00:00:00";
    await h.services.refresher?.refresh();
    expect((await events(h)).map((e) => [e.detail.from, e.detail.to])).toEqual([
      ["2026-11-22", "2026-11-24"],
      ["2026-11-20", "2026-11-22"],
    ]);
  });

  it("makes one event of a session deleted, once", async () => {
    const event = plan("2026-11-20");
    const h = make();
    await h.services.refresher?.refresh();
    intervals.events.splice(intervals.events.indexOf(event), 1);
    await h.services.refresher?.refresh();
    await h.services.refresher?.refresh();
    expect(await events(h)).toMatchObject([
      {
        kind: "deleted",
        day: "2026-11-20",
        workoutId: `icu-${event.id}`,
        detail: { name: "Threshold 4x8" },
      },
    ]);
  });

  it("reports a session moved out of the window as deleted, since the plan no longer has it", async () => {
    const event = plan("2026-11-20");
    const h = make();
    await h.services.refresher?.refresh();
    event.start_date_local = "2027-02-01T00:00:00";
    await h.services.refresher?.refresh();
    expect(await events(h)).toMatchObject([{ kind: "deleted", day: "2026-11-20" }]);
  });

  it("makes no event of a change the service made itself: the cache already says it", async () => {
    const first = plan("2026-11-20");
    const second = plan("2026-11-21", "Endurance");
    const h = make();
    await h.services.refresher?.refresh();
    // What the MCP tools and the bot's /skip and /move do: write the calendar and the cache together.
    intervals.events.splice(intervals.events.indexOf(first), 1);
    await h.services.plan.remove(`icu-${first.id}`);
    const entry = (await h.services.plan.entries({ from: "2026-11-01", to: "2026-12-31" })).find(
      (e) => e.id === `icu-${second.id}`,
    );
    if (entry === undefined) throw new Error("the second workout is not cached");
    second.start_date_local = "2026-11-23T00:00:00";
    await h.services.plan.upsert({ ...entry, date: "2026-11-23" });
    await h.services.refresher?.refresh();
    expect(await events(h)).toEqual([]);
  });

  it("makes no event of a day that merely passed", async () => {
    plan("2026-11-18", "Today's session");
    const h = make({ now: "2026-11-18T08:00:00Z" });
    await h.services.refresher?.refresh();
    h.clock.set("2026-11-19T08:00:00Z");
    await h.services.refresher?.refresh();
    expect(await events(h)).toEqual([]);
    // ... though the cache did drop it.
    expect(await h.services.plan.entries({ from: "2026-11-01", to: "2026-12-31" })).toEqual([]);
  });

  it("does not fail the refresh when the events cannot be stored", async () => {
    const event = plan("2026-11-20");
    const h = make();
    await h.services.refresher?.refresh();
    intervals.events.splice(intervals.events.indexOf(event), 1);
    await db.exec("ALTER TABLE plan_events RENAME TO plan_events_away");
    try {
      const result = await h.services.refresher?.refresh();
      expect(result?.ok).toBe(true);
      expect(h.logging.lines.some((l) => l.level === "error")).toBe(true);
    } finally {
      await db.exec("ALTER TABLE plan_events_away RENAME TO plan_events");
    }
  });
});

describe("illness and travel, declared by the rider", () => {
  const post = (
    h: IntegrationHarness,
    body: unknown,
    headers: Record<string, string> = bearer(COMPANION),
  ) =>
    h.app.inject({
      method: "POST",
      url: "/v1/plan-events",
      headers: { "content-type": "application/json", ...headers },
      payload: typeof body === "string" ? body : JSON.stringify(body),
    });

  it("is an event for today in the rider's time zone, 201 the first time and 200 the second", async () => {
    // 23:30 UTC on the 18th is already the 19th in Paris.
    const h = make({ now: "2026-11-18T23:30:00Z" });
    const first = await post(h, { kind: "illness" });
    expect(first.statusCode).toBe(201);
    const body = first.json();
    expect(validatePlanEvent(body), JSON.stringify(body)).toMatchObject({ ok: true });
    expect(body).toMatchObject({
      kind: "illness",
      day: "2026-11-19",
      workoutId: null,
      detail: { until: null },
      status: "open",
    });
    const again = await post(h, { kind: "illness", until: null });
    expect(again.statusCode).toBe(200);
    expect((again.json() as { id: number }).id).toBe((body as { id: number }).id);
    expect(await events(h)).toHaveLength(1);
  });

  it("takes an until date, which makes it another event than the open-ended one", async () => {
    const h = make({ now: "2026-11-18T08:00:00Z" });
    const open = (await post(h, { kind: "travel" })).json() as { id: number };
    const ended = await post(h, { kind: "travel", until: "2026-11-22" });
    expect(ended.statusCode).toBe(201);
    expect(ended.json()).toMatchObject({ kind: "travel", detail: { until: "2026-11-22" } });
    expect((ended.json() as { id: number }).id).not.toBe(open.id);
    expect((await post(h, { kind: "travel", until: "2026-11-22" })).statusCode).toBe(200);
    // Today itself is a fine last day.
    expect((await post(h, { kind: "illness", until: "2026-11-18" })).statusCode).toBe(201);
    expect(await events(h)).toHaveLength(3);
  });

  it("refuses what is not a declaration, with the API's own error and creating nothing", async () => {
    const h = make({ now: "2026-11-18T08:00:00Z" });
    const bad: [string, unknown][] = [
      ["a kind that is detected, not declared", { kind: "missed" }],
      ["no kind", {}],
      ["an until that is not a date", { kind: "illness", until: "friday" }],
      ["an until that is not a real day", { kind: "illness", until: "2026-02-30" }],
      ["an until before today", { kind: "travel", until: "2026-11-17" }],
      ["an until more than a year away", { kind: "travel", until: "2028-01-01" }],
      ["an extra field", { kind: "illness", note: "flu" }],
      ["text that is not JSON", "ill"],
    ];
    for (const [what, body] of bad) {
      const res = await post(h, body);
      expectError(res, "invalid_json");
      expect(res.statusCode, what).toBe(400);
    }
    expect(await events(h)).toEqual([]);
  });

  it("needs a device token", async () => {
    const h = make();
    expectError(
      await h.app.inject({ method: "POST", url: "/v1/plan-events", payload: { kind: "illness" } }),
      "unauthorized",
    );
    expect((await post(h, { kind: "illness" }, bearer(TABLET))).statusCode).toBe(201);
  });

  it("is a plain call on the service too: declare says whether it created the event, and refuses a bad until", async () => {
    const h = make({ now: "2026-11-18T08:00:00Z" });
    const { planEvents } = h.services;
    expect((await planEvents.declare("illness", null)).created).toBe(true);
    expect((await planEvents.declare("illness", null)).created).toBe(false);
    await expect(planEvents.declare("travel", "2026-11-17")).rejects.toBeInstanceOf(PlanEventError);
    await expect(planEvents.declare("travel", "soon")).rejects.toThrow(/not a real date/);
  });
});

describe("the easier variant", () => {
  it("has its event type and an emit function, idempotent by a key; the variant itself is a later stage", async () => {
    const h = make();
    const input = {
      kind: "variant" as const,
      day: "2026-11-18",
      workoutId: "icu-5001",
      detail: { name: "Sweet spot 3x10", rideId: "ride-1" },
    };
    const first = await h.services.planEvents.emit(input);
    expect(first.created).toBe(true);
    expect(first.event).toMatchObject({ kind: "variant", status: "open", workoutId: "icu-5001" });
    expect(validatePlanEvent(first.event)).toMatchObject({ ok: true });
    expect((await h.services.planEvents.emit(input)).created).toBe(false);
    // Another ride of the same workout is another event when the caller says so.
    const other = await h.services.planEvents.emit({
      ...input,
      detail: { name: "Sweet spot 3x10", rideId: "ride-2" },
      naturalKey: "variant:ride-2",
    });
    expect(other.created).toBe(true);
    expect(await events(h)).toHaveLength(2);
    await expect(h.services.planEvents.emit({ ...input, day: "2026-02-30" })).rejects.toThrow(
      /not a real date/,
    );
  });
});

describe("resolving, and what the rider is told", () => {
  it("sends one short note, written by code from the fields: a dropped missed session is said to be dropped", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = await boot({ now: "2026-11-18T02:00:00Z" });
    h.timers.tick();
    await h.services.planEvents.idle();
    const [event] = await events(h);
    if (event === undefined) throw new Error("no event");
    // An open event says nothing to the rider.
    h.timers.tick();
    await h.services.telegram?.idle();
    expect(updates()).toEqual([]);

    const resolved = await h.services.planEvents.resolve(event.id, {
      note: "Thursday's threshold stays; the weekend ride is easier.",
      dropped: true,
    });
    expect(resolved).toMatchObject({
      changed: true,
      event: { status: "resolved", dropped: true, resolvedAt: "2026-11-18T02:00:00.000Z" },
    });
    expect(validatePlanEvent(resolved?.event)).toMatchObject({ ok: true });
    await until(() => expect(updates()).toHaveLength(1));
    expect(updates()[0]).toBe(
      "Plan update\n" +
        "Sweet spot 3x10 on Tue 17 Nov was not ridden. It was dropped and not rescheduled.\n" +
        "Why: Thursday's threshold stays; the weekend ride is easier.",
    );

    // Sent once: resolving again changes nothing and says nothing, and neither does a restart's pass.
    const again = await h.services.planEvents.resolve(event.id, {
      note: "Different.",
      dropped: false,
    });
    expect(again).toMatchObject({ changed: false, event: { dropped: true } });
    h.timers.tick();
    await h.services.telegram?.idle();
    expect(updates()).toHaveLength(1);
  });

  it("says what changed when the session was not dropped", async () => {
    await planned("icu-5001", "2026-11-17");
    const h = await boot({ now: "2026-11-18T02:00:00Z" });
    h.timers.tick();
    await h.services.planEvents.idle();
    const [event] = await events(h);
    await h.services.planEvents.resolve(event?.id ?? 0, {
      note: "Moved it to Friday and shortened Saturday.",
      dropped: false,
    });
    await until(() => expect(updates()).toHaveLength(1));
    expect(updates()[0]).toBe(
      "Plan update\n" +
        "Sweet spot 3x10 on Tue 17 Nov was not ridden. The plan has been adjusted.\n" +
        "Why: Moved it to Friday and shortened Saturday.",
    );
  });

  it("tells each kind in its own words", async () => {
    const h = await boot({ now: "2026-11-18T08:00:00Z" });
    const { planEvents } = h.services;
    const make = async (
      input: Parameters<typeof planEvents.emit>[0],
      note: string,
      dropped: boolean,
    ) => {
      const { event } = await planEvents.emit(input);
      await planEvents.resolve(event.id, { note, dropped });
    };
    await make(
      {
        kind: "moved",
        day: "2026-11-20",
        workoutId: "icu-1",
        detail: { name: "Threshold", from: "2026-11-20", to: "2026-11-22" },
      },
      "Fine, the rest of the week shifts by a day.",
      false,
    );
    await make(
      { kind: "deleted", day: "2026-11-21", workoutId: "icu-2", detail: { name: "Endurance" } },
      "Left it out; you are not behind.",
      true,
    );
    await make(
      { kind: "variant", day: "2026-11-18", workoutId: "icu-3", detail: { name: "Sweet spot" } },
      "Counted as done.",
      false,
    );
    await planEvents
      .declare("illness", "2026-11-21")
      .then(({ event }) =>
        planEvents.resolve(event.id, { note: "Two easy days, then we see.", dropped: true }),
      );
    await planEvents.declare("travel", null).then(({ event }) =>
      planEvents.resolve(event.id, {
        note: "Short sessions for the hotel bike.",
        dropped: false,
      }),
    );
    await until(() => expect(updates()).toHaveLength(5));
    expect([...updates()].sort()).toEqual(
      [
        "Plan update\nYou moved Threshold from Fri 20 Nov to Sun 22 Nov. The plan has been adjusted.\nWhy: Fine, the rest of the week shifts by a day.",
        "Plan update\nYou deleted Endurance on Sat 21 Nov from the calendar. It was dropped and not rescheduled.\nWhy: Left it out; you are not behind.",
        "Plan update\nYou rode the easier version of Sweet spot on Wed 18 Nov. The plan has been brought in line with what you rode.\nWhy: Counted as done.",
        "Plan update\nYou said you are ill, from Wed 18 Nov to Sat 21 Nov. The sessions in that time were dropped, not moved.\nWhy: Two easy days, then we see.",
        "Plan update\nYou said you are travelling, from Wed 18 Nov. The plan has been adjusted.\nWhy: Short sessions for the hotel bike.",
      ].sort(),
    );
  });

  it("sends nothing, and still resolves, when the bot is not set up", async () => {
    const h = make({ now: "2026-11-18T08:00:00Z" });
    const { event } = await h.services.planEvents.declare("illness", null);
    const resolved = await h.services.planEvents.resolve(event.id, {
      note: "Rest.",
      dropped: true,
    });
    expect(resolved?.changed).toBe(true);
    expect(h.services.planEvents.canTellRider).toBe(false);
    expect(telegram.sent).toEqual([]);
  });

  it("answers null for an event that does not exist", async () => {
    const h = make();
    expect(await h.services.planEvents.resolve(999, { note: "x", dropped: false })).toBeNull();
  });
});

describe("/ill and /travel in the Telegram bot", () => {
  async function ask(text: string): Promise<string> {
    const before = telegram.sentTo(OWNER_CHAT).length;
    telegram.pushText(OWNER_CHAT, text);
    await until(() => expect(telegram.sentTo(OWNER_CHAT).length).toBeGreaterThan(before));
    return telegram.sentTo(OWNER_CHAT)[before]?.text ?? "";
  }

  it("declare illness or travel, with no end, for some days, or until a date, and say what they noted", async () => {
    const h = await boot({ now: "2026-11-18T08:00:00Z" });
    expect(await ask("/ill")).toBe(
      "Noted: ill from Wed 18 Nov. Your coach will rebuild the days ahead and tell you what changed.",
    );
    expect(await ask("/ill 3")).toBe(
      "Noted: ill until Fri 20 Nov. Your coach will rebuild the days ahead and tell you what changed.",
    );
    expect(await ask("/travel 2026-11-25")).toBe(
      "Noted: travelling until Wed 25 Nov. Your coach will rebuild the days ahead and tell you what changed.",
    );
    // The same again is the same event.
    expect(await ask("/ill 3")).toBe("Already noted: ill until Fri 20 Nov. Your coach has it.");
    expect((await events(h)).map((e) => `${e.kind} ${e.detail.until ?? "no end"}`).sort()).toEqual([
      "illness 2026-11-20",
      "illness no end",
      "travel 2026-11-25",
    ]);
  });

  it("explains what it did not understand, and refuses an end that has passed", async () => {
    const h = await boot({ now: "2026-11-18T08:00:00Z" });
    expect(await ask("/ill soon")).toMatch(/Say \/ill for no end date, \/ill 3 for three days/);
    expect(await ask("/travel 0")).toMatch(/Say \/travel for no end date/);
    expect(await ask("/travel 61")).toMatch(/Say \/travel for no end date/);
    expect(await ask("/ill 2026-11-10")).toBe(
      "I cannot note that: until 2026-11-10 is before today, 2026-11-18.",
    );
    expect(await events(h)).toEqual([]);
  });

  it("are in the menu and in the help", async () => {
    await boot();
    await until(() => expect(telegram.commands.length).toBeGreaterThan(0));
    expect(telegram.commands.map((c) => c.command)).toContain("ill");
    expect(telegram.commands.map((c) => c.command)).toContain("travel");
    const help = await ask("/help");
    expect(help).toMatch(/\/ill: tell your coach you are ill/);
    expect(help).toMatch(/\/travel:/);
  });
});

describe("what the server logs", () => {
  it("keeps what an event says, and the agent's note, out of the log", async () => {
    await planned("icu-5001", "2026-11-17", "SECRET-WORKOUT-NAME");
    const h = await boot({ now: "2026-11-18T02:00:00Z" });
    h.timers.tick();
    await h.services.planEvents.idle();
    const [event] = await events(h);
    await h.services.planEvents.resolve(event?.id ?? 0, {
      note: "SECRET-NOTE-TO-THE-RIDER",
      dropped: true,
    });
    await until(() => expect(updates()).toHaveLength(1));
    const text = h.logging.text();
    expect(text).not.toMatch(/SECRET/);
    expect(text).toMatch(/plan event created/);
  });
});
