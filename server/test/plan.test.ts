import {
  validateWorkoutsResponse,
  type Workout,
  type WorkoutsResponse,
} from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { createEventBus, type PlanChangedEvent } from "../src/events.ts";
import { createPlanStore, type PlanEntry, type PlanStore } from "../src/plan/store.ts";
import { bearer, expectError, startApp } from "./helpers/app.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { type Harness, plannedEvent, startHarness, steady } from "./helpers/harness.ts";

let db: Db;
let fake: FakeIntervals;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});
afterAll(async () => {
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  fake.reset();
});

const workout = (id: string, name = "W", seconds = 600): Workout => ({
  schema: 1,
  id,
  name,
  steps: [{ type: "steady", duration: seconds, target: 0.6 }],
});

const entry = (id: string, date: string, extra: Partial<PlanEntry> = {}): PlanEntry => ({
  id,
  source: "intervals.icu",
  externalId: id.replace("icu-", ""),
  date,
  name: `Workout ${id}`,
  workout: workout(id, `Workout ${id}`),
  unsupportedReason: null,
  ...extra,
});

describe("PlanStore", () => {
  let store: PlanStore;
  let changes: PlanChangedEvent[];

  beforeEach(() => {
    const bus = createEventBus();
    changes = [];
    bus.on("plan-changed", (event) => {
      changes.push(event);
    });
    store = createPlanStore(db, bus);
  });

  it("adds, finds, lists by date then id, and says what changed", async () => {
    expect(await store.upsert(entry("icu-2", "2026-11-21"))).toBe("added");
    expect(await store.upsert(entry("icu-1", "2026-11-21"))).toBe("added");
    expect(await store.upsert(entry("icu-3", "2026-11-19"))).toBe("added");
    expect(changes).toEqual([
      { added: ["icu-2"], updated: [], removed: [] },
      { added: ["icu-1"], updated: [], removed: [] },
      { added: ["icu-3"], updated: [], removed: [] },
    ]);

    const list = await store.list({ from: "2026-11-19", to: "2026-11-21" });
    expect(list.map((p) => [p.date, p.id])).toEqual([
      ["2026-11-19", "icu-3"],
      ["2026-11-21", "icu-1"],
      ["2026-11-21", "icu-2"],
    ]);
    expect(list[0]).toEqual({
      id: "icu-3",
      date: "2026-11-19",
      name: "Workout icu-3",
      source: "intervals.icu",
      workout: workout("icu-3", "Workout icu-3"),
    });
    expect(await store.get("icu-1")).toMatchObject({ id: "icu-1", date: "2026-11-21" });
    expect(await store.get("icu-404")).toBeNull();
    expect(await store.list({ from: "2026-11-20", to: "2026-11-20" })).toEqual([]);
  });

  it("an identical upsert changes nothing and announces nothing; a different one updates", async () => {
    await store.upsert(entry("icu-1", "2026-11-21"));
    changes.length = 0;
    expect(await store.upsert(entry("icu-1", "2026-11-21"))).toBe("unchanged");
    expect(changes).toEqual([]);
    expect(await store.upsert(entry("icu-1", "2026-11-22"))).toBe("updated");
    expect(await store.upsert(entry("icu-1", "2026-11-22", { name: "Renamed" }))).toBe("updated");
    expect(changes).toEqual([
      { added: [], updated: ["icu-1"], removed: [] },
      { added: [], updated: ["icu-1"], removed: [] },
    ]);
    expect(await store.get("icu-1")).toMatchObject({ date: "2026-11-22", name: "Renamed" });
  });

  it("keeps an unconvertible workout with its reason, but does not list or get it", async () => {
    await store.upsert(
      entry("icu-9", "2026-11-21", { workout: null, unsupportedReason: "repeats inside repeats" }),
    );
    expect(await store.list({ from: "2026-11-21", to: "2026-11-21" })).toEqual([]);
    expect(await store.get("icu-9")).toBeNull();
    expect(await store.entries({ from: "2026-11-21", to: "2026-11-21" })).toMatchObject([
      { id: "icu-9", workout: null, unsupportedReason: "repeats inside repeats" },
    ]);
  });

  it("removes one entry and says so", async () => {
    await store.upsert(entry("icu-1", "2026-11-21"));
    changes.length = 0;
    expect(await store.remove("icu-1")).toBe(true);
    expect(await store.remove("icu-1")).toBe(false);
    expect(changes).toEqual([{ added: [], updated: [], removed: ["icu-1"] }]);
  });

  it("mirrors a window: upserts, removes what vanished and what is in the past, leaves what is beyond", async () => {
    await store.upsert(entry("icu-1", "2026-11-17")); // past
    await store.upsert(entry("icu-2", "2026-11-19")); // stays
    await store.upsert(entry("icu-3", "2026-11-20")); // vanished
    await store.upsert(entry("icu-4", "2026-12-20")); // beyond the window
    changes.length = 0;

    const change = await store.mirror({ from: "2026-11-18", to: "2026-12-02" }, "intervals.icu", [
      entry("icu-2", "2026-11-19"),
      entry("icu-5", "2026-11-25"),
    ]);
    expect(change).toEqual({ added: ["icu-5"], updated: [], removed: ["icu-1", "icu-3"] });
    expect(changes).toEqual([change]);
    const left = await store.entries({ from: "2026-01-01", to: "2026-12-31" });
    expect(left.map((e) => e.id)).toEqual(["icu-2", "icu-5", "icu-4"]);
  });

  it("mirrors nothing into an empty window without announcing", async () => {
    const change = await store.mirror(
      { from: "2026-11-18", to: "2026-12-02" },
      "intervals.icu",
      [],
    );
    expect(change).toEqual({ added: [], updated: [], removed: [] });
    expect(changes).toEqual([]);
  });

  it("remembers how refreshes went, across restarts of the store", async () => {
    expect(await store.syncState("intervals.icu")).toEqual({
      lastSuccessAt: null,
      lastAttemptAt: null,
      lastError: null,
    });
    await store.recordSuccess("intervals.icu", new Date("2026-11-18T08:00:00Z"));
    await store.recordFailure("intervals.icu", new Date("2026-11-18T08:15:00Z"), "it was down");
    const again = createPlanStore(db, createEventBus());
    expect(await again.syncState("intervals.icu")).toEqual({
      lastSuccessAt: new Date("2026-11-18T08:00:00Z"),
      lastAttemptAt: new Date("2026-11-18T08:15:00Z"),
      lastError: "it was down",
    });
    await store.recordSuccess("intervals.icu", new Date("2026-11-18T08:30:00Z"));
    expect((await again.syncState("intervals.icu")).lastError).toBeNull();
  });
});

describe("the refresher", () => {
  let h: Harness;
  let changes: PlanChangedEvent[];

  beforeEach(() => {
    h = startHarness(db, fake);
    changes = [];
    h.services.bus.on("plan-changed", (event) => {
      changes.push(event);
    });
  });

  const refresher = () => {
    if (h.services.refresher === null) throw new Error("not configured");
    return h.services.refresher;
  };

  it("reads today and the next 14 days, WORKOUT events only, and stores them converted", async () => {
    const event = fake.addEvent(
      plannedEvent("2026-11-20", "Sweet spot", [steady(600, 88, "Hard"), steady(300, 50)]),
    );
    const result = await refresher().refresh();
    expect(result).toMatchObject({ ok: true, change: { added: [`icu-${event.id}`] } });

    const [request] = fake.requestsTo("GET", "/events");
    expect(request?.query).toMatchObject({
      oldest: "2026-11-18",
      newest: "2026-12-02",
      category: "WORKOUT",
    });
    expect(await h.services.plan.get(`icu-${event.id}`)).toMatchObject({
      date: "2026-11-20",
      name: "Sweet spot",
      workout: {
        id: `icu-${event.id}`,
        steps: [
          { type: "steady", duration: 600, target: 0.88, label: "Hard" },
          { type: "steady", duration: 300, target: 0.5 },
        ],
      },
    });
    expect(changes).toEqual([{ added: [`icu-${event.id}`], updated: [], removed: [] }]);
  });

  it("takes today in the rider's time zone, not the server's", async () => {
    // 23:30 UTC on the 18th is already the 19th in Paris.
    h.clock.set("2026-11-18T23:30:00Z");
    await refresher().refresh();
    expect(fake.requestsTo("GET", "/events")[0]?.query).toMatchObject({
      oldest: "2026-11-19",
      newest: "2026-12-03",
    });
  });

  it("skips a workout it cannot convert, logs why once, and keeps the reason", async () => {
    const good = fake.addEvent(plannedEvent("2026-11-20", "Fine", [steady(600, 60)]));
    const bad = fake.addEvent(
      plannedEvent("2026-11-21", "Distance", [
        { distance: 5000, power: { value: 80, units: "%ftp" } },
      ]),
    );
    const result = await refresher().refresh();
    expect(result).toMatchObject({
      ok: true,
      unsupported: [{ id: `icu-${bad.id}`, name: "Distance", date: "2026-11-21" }],
    });
    expect(
      (await h.services.plan.list({ from: "2026-11-18", to: "2026-12-02" })).map((p) => p.id),
    ).toEqual([`icu-${good.id}`]);
    expect(await h.services.plan.entries({ from: "2026-11-21", to: "2026-11-21" })).toMatchObject([
      {
        id: `icu-${bad.id}`,
        workout: null,
        unsupportedReason: expect.stringContaining("distance"),
      },
    ]);

    const warnings = () => h.logging.lines.filter((l) => l.message?.includes("skipped"));
    expect(warnings()).toHaveLength(1);
    expect(warnings()[0]?.fields).toMatchObject({
      eventId: bad.id,
      date: "2026-11-21",
      reason: expect.stringContaining("distance"),
    });
    await refresher().refresh();
    expect(warnings()).toHaveLength(1); // not repeated every refresh

    // Fixed in the calendar: it appears, and the reason is gone.
    bad.workout_doc = { steps: [steady(300, 70)] };
    await refresher().refresh();
    expect(
      (await h.services.plan.list({ from: "2026-11-21", to: "2026-11-21" })).map((p) => p.id),
    ).toEqual([`icu-${bad.id}`]);
  });

  it("ignores other sports and other categories", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "Run", [steady(600, 60)], { type: "Run" }));
    fake.addEvent(plannedEvent("2026-11-20", "Note", [], { category: "NOTE" }));
    const ride = fake.addEvent(
      plannedEvent("2026-11-20", "Gravel", [steady(600, 60)], { type: "GravelRide" }),
    );
    await refresher().refresh();
    const stored = await h.services.plan.entries({ from: "2026-11-18", to: "2026-12-02" });
    expect(stored.map((e) => e.id)).toEqual([`icu-${ride.id}`]);
  });

  it("mirrors deletions and changes, and announces only what changed", async () => {
    const a = fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    const b = fake.addEvent(plannedEvent("2026-11-21", "B", [steady(600, 60)]));
    await refresher().refresh();
    changes.length = 0;

    await refresher().refresh(); // nothing happened in the calendar
    expect(changes).toEqual([]);

    fake.events.splice(fake.events.indexOf(b), 1); // deleted in intervals.icu
    a.name = "A renamed"; // changed
    const c = fake.addEvent(plannedEvent("2026-11-22", "C", [steady(600, 60)])); // added
    const result = await refresher().refresh();
    expect(result).toMatchObject({
      ok: true,
      change: { added: [`icu-${c.id}`], updated: [`icu-${a.id}`], removed: [`icu-${b.id}`] },
    });
    expect(changes).toEqual([
      { added: [`icu-${c.id}`], updated: [`icu-${a.id}`], removed: [`icu-${b.id}`] },
    ]);
    expect(
      (await h.services.plan.list({ from: "2026-11-18", to: "2026-12-02" })).map((p) => p.name),
    ).toEqual(["A renamed", "C"]);
  });

  it("moves the window with the clock: yesterday's workout leaves the cache", async () => {
    fake.addEvent(plannedEvent("2026-11-18", "Today", [steady(600, 60)]));
    fake.addEvent(plannedEvent("2026-11-19", "Tomorrow", [steady(600, 60)]));
    await refresher().refresh();
    h.clock.advance(24 * 3600 * 1000);
    await refresher().refresh();
    expect(
      (await h.services.plan.entries({ from: "2026-01-01", to: "2026-12-31" })).map((e) => e.name),
    ).toEqual(["Tomorrow"]);
  });

  it("keeps the last known plan and is stale when intervals.icu cannot be reached, then recovers", async () => {
    const event = fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    await refresher().refresh();
    h.clock.advance(15 * 60 * 1000);
    fake.setUnreachable(true);
    const failed = await refresher().refresh();
    expect(failed).toMatchObject({ ok: false, error: expect.stringContaining("did not answer") });
    expect(await h.services.plan.get(`icu-${event.id}`)).not.toBeNull();
    expect(await h.services.plan.syncState("intervals.icu")).toMatchObject({
      lastSuccessAt: new Date("2026-11-18T08:00:00Z"),
      lastAttemptAt: new Date("2026-11-18T08:15:00Z"),
      lastError: expect.stringContaining("did not answer"),
    });

    fake.setUnreachable(false);
    h.clock.advance(15 * 60 * 1000);
    await refresher().refresh();
    expect((await h.services.plan.syncState("intervals.icu")).lastError).toBeNull();
  });

  it("an error from intervals.icu does not wipe the cache either (a 500, a wrong key)", async () => {
    const event = fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    await refresher().refresh();
    fake.failAll(401);
    expect(await refresher().refresh()).toMatchObject({ ok: false });
    fake.failAll(500);
    expect(await refresher().refresh()).toMatchObject({ ok: false });
    expect(await h.services.plan.get(`icu-${event.id}`)).not.toBeNull();
  });

  it("joins a refresh that is already running instead of asking twice", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    const results = await Promise.all([
      refresher().refresh(),
      refresher().refresh(),
      refresher().refresh(),
    ]);
    expect(results.every((r) => r.ok)).toBe(true);
    expect(fake.requestsTo("GET", "/events")).toHaveLength(1);
  });

  it("an event with a date it cannot read is ignored with a warning, not stored", async () => {
    fake.failNext(1, 200, {
      body: JSON.stringify([
        { id: 77, category: "WORKOUT", type: "Ride", start_date_local: "garbage", name: "Odd" },
        {
          id: 78,
          category: "WORKOUT",
          type: "Ride",
          start_date_local: "2027-05-01T00:00:00",
          name: "Far",
        },
      ]),
    });
    expect(await refresher().refresh()).toMatchObject({ ok: true });
    expect(await h.services.plan.entries({ from: "2026-01-01", to: "2030-12-31" })).toEqual([]);
    expect(h.logging.lines.filter((l) => l.level === "warn")).toHaveLength(2);
  });
});

describe("GET /v1/workouts", () => {
  let h: Harness;
  let app: FastifyInstance;

  beforeEach(() => {
    h = startHarness(db, fake);
    app = h.app;
  });

  const get = (query: string, headers: Record<string, string> = bearer()) =>
    app.inject({ method: "GET", url: `/v1/workouts${query}`, headers });

  const answer = async (query: string): Promise<WorkoutsResponse> => {
    const res = await get(query);
    expect(res.statusCode, res.body).toBe(200);
    expect(res.headers["content-type"]).toMatch(/^application\/json/);
    expect(res.headers["cache-control"]).toBe("no-store");
    const body: unknown = res.json();
    const checked = validateWorkoutsResponse(body);
    expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
    return body as WorkoutsResponse;
  };

  it("needs a device token", async () => {
    const res = await get("?from=2026-11-18&to=2026-11-25", {});
    expect(res.statusCode).toBe(401);
  });

  it("serves the cache without asking intervals.icu", async () => {
    const event = fake.addEvent(plannedEvent("2026-11-20", "Sweet spot", [steady(600, 88)]));
    await h.services.refresher?.refresh();
    fake.requests.length = 0;

    const body = await answer("?from=2026-11-18&to=2026-11-25");
    expect(body).toEqual({
      from: "2026-11-18",
      to: "2026-11-25",
      generatedAt: "2026-11-18T08:00:00.000Z",
      stale: false,
      configured: true,
      workouts: [
        {
          id: `icu-${event.id}`,
          date: "2026-11-20",
          name: "Sweet spot",
          source: "intervals.icu",
          workout: {
            schema: 1,
            id: `icu-${event.id}`,
            name: "Sweet spot",
            steps: [{ type: "steady", duration: 600, target: 0.88 }],
          },
        },
      ],
    });
    expect(fake.requests).toEqual([]);
  });

  it("answers only the days asked for", async () => {
    fake.addEvent(plannedEvent("2026-11-19", "Early", [steady(600, 60)]));
    fake.addEvent(plannedEvent("2026-11-22", "Late", [steady(600, 60)]));
    await h.services.refresher?.refresh();
    expect((await answer("?from=2026-11-19&to=2026-11-21")).workouts.map((w) => w.name)).toEqual([
      "Early",
    ]);
    expect((await answer("?from=2026-11-22&to=2026-11-22")).workouts.map((w) => w.name)).toEqual([
      "Late",
    ]);
    expect((await answer("?from=2026-11-23&to=2026-11-23")).workouts).toEqual([]);
  });

  it("serves the last known plan with stale: true when intervals.icu is unreachable", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    await h.services.refresher?.refresh();
    h.clock.advance(15 * 60 * 1000);
    fake.setUnreachable(true);
    await h.services.refresher?.refresh();

    const body = await answer("?from=2026-11-18&to=2026-11-25");
    expect(body.stale).toBe(true);
    expect(body.configured).toBe(true);
    expect(body.generatedAt).toBe("2026-11-18T08:00:00.000Z"); // the last good read
    expect(body.workouts.map((w) => w.name)).toEqual(["A"]);
  });

  it("is stale with nothing and no generatedAt before the first refresh has finished", async () => {
    expect(await answer("?from=2026-11-18&to=2026-11-25")).toMatchObject({
      stale: true,
      generatedAt: null,
      configured: true,
      workouts: [],
    });
  });

  it("is stale with an empty list when the very first read fails", async () => {
    fake.setUnreachable(true);
    await h.services.refresher?.refresh();
    expect(await answer("?from=2026-11-18&to=2026-11-25")).toMatchObject({
      stale: true,
      generatedAt: null,
      workouts: [],
    });
  });

  it("refresh=true reads intervals.icu first, and still answers from the cache if that fails", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    expect((await answer("?from=2026-11-18&to=2026-11-25&refresh=true")).workouts).toHaveLength(1);
    expect(fake.requestsTo("GET", "/events")).toHaveLength(1);

    fake.addEvent(plannedEvent("2026-11-21", "B", [steady(600, 60)]));
    fake.setUnreachable(true);
    const body = await answer("?from=2026-11-18&to=2026-11-25&refresh=1");
    expect(body.stale).toBe(true);
    expect(body.workouts.map((w) => w.name)).toEqual(["A"]);
  });

  it("says it is not configured, with an empty list and no staleness, and never calls out", async () => {
    const off = startHarness(db, fake, { configured: false });
    fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));
    // Whatever the cache holds from before the key was removed is not served.
    await off.services.plan.upsert(entry("icu-1", "2026-11-20"));
    const res = await off.app.inject({
      method: "GET",
      url: "/v1/workouts?from=2026-11-18&to=2026-11-25&refresh=true",
      headers: bearer(),
    });
    expect(res.json()).toEqual({
      from: "2026-11-18",
      to: "2026-11-25",
      generatedAt: null,
      stale: false,
      configured: false,
      workouts: [],
    });
    expect(validateWorkoutsResponse(res.json())).toMatchObject({ ok: true });
    expect(fake.requests).toEqual([]);
  });

  it("the default app (no integration at all) is not configured either", async () => {
    const plain = startApp(db).app;
    const res = await plain.inject({
      method: "GET",
      url: "/v1/workouts?from=2026-11-18&to=2026-11-18",
      headers: bearer(),
    });
    expect(res.json()).toMatchObject({ configured: false, stale: false, workouts: [] });
    await plain.close();
  });

  it.each([
    ["no range", "", /from as a date/],
    ["no to", "?from=2026-11-18", /to as a date/],
    ["no from", "?to=2026-11-18", /from as a date/],
    ["an empty from", "?from=&to=2026-11-18", /from as a date/],
    ["a repeated from", "?from=2026-11-18&from=2026-11-19&to=2026-11-20", /from as a date/],
    ["a date in the wrong layout", "?from=18-11-2026&to=2026-11-20", /real dates/],
    ["a date that does not exist", "?from=2026-02-30&to=2026-03-04", /real dates/],
    ["a datetime", "?from=2026-11-18T00:00:00&to=2026-11-20", /real dates/],
    ["a reversed range", "?from=2026-11-20&to=2026-11-18", /after/],
    ["a range of 63 days", "?from=2026-11-01&to=2027-01-02", /63 days; at most 62/],
  ])("answers 400 invalid_range for %s", async (_what, query, message) => {
    const res = await get(query);
    expect(expectError(res, "invalid_range").message).toMatch(message);
  });

  it("serves a range of exactly 62 days, and of one day", async () => {
    expect((await answer("?from=2026-11-01&to=2027-01-01")).workouts).toEqual([]);
    expect((await answer("?from=2026-11-18&to=2026-11-18")).workouts).toEqual([]);
  });
});
