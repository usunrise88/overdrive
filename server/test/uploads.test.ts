import { createHash } from "node:crypto";
import type { RideRecord } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import type { RideStoredEvent } from "../src/events.ts";
import { INTERVALS_DESTINATION, retryDelayMs } from "../src/intervals/uploads.ts";
import {
  asRide,
  expectError,
  expectUpload,
  putFit,
  putRecord,
  rideFixture,
  startApp,
} from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { FAKE_API_KEY, type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { buildFit } from "./helpers/fit.ts";
import { type Harness, plannedEvent, startHarness, steady } from "./helpers/harness.ts";

let db: Db;
let fake: FakeIntervals;
let h: Harness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});
afterAll(async () => {
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await clearRides(db);
  await clearPlan(db);
  fake.reset();
  h = startHarness(db, fake); // 2026-11-18 08:00 UTC, rider in Paris
});

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const START = Date.parse("2026-11-18T07:00:00Z");

const worker = () => {
  if (h.services.uploads === null) throw new Error("not configured");
  return h.services.uploads;
};

/** A distinct FIT file per `n`: the fake tells duplicates by content. */
const fitOf = (n: number) => buildFit({ data: Uint8Array.from([n, 1, 2, 3, 4, 5, 6, 7]) });

interface StoreOptions {
  /** Hours after START. */
  hours?: number;
  workoutId?: string;
  app?: Harness["app"];
}

/** Stores a ride and its FIT through the API, the way the tablet does. */
async function store(id: string, n: number, options: StoreOptions = {}): Promise<Buffer> {
  const app = options.app ?? h.app;
  const record: RideRecord = asRide(
    rideFixture("minimal-free-ride.json"),
    id,
    START + (options.hours ?? n) * HOUR,
  );
  if (options.workoutId !== undefined) record.ride.workoutId = options.workoutId;
  expectUpload(await putRecord(app, id, record), id, "stored");
  const fit = fitOf(n);
  expectUpload(await putFit(app, id, fit), id, "stored");
  return fit;
}

interface Destination {
  state: string;
  attempts: number;
  last_error: string | null;
  external_id: string | null;
  next: string | null;
}

async function destination(rideId: string): Promise<Destination | undefined> {
  const result = await db.query<Destination>(
    `SELECT state, attempts, last_error, external_id, next_attempt_at AS next
       FROM ride_destinations WHERE ride_id = $1 AND destination = $2`,
    [rideId, INTERVALS_DESTINATION],
  );
  const row = result.rows[0];
  return row === undefined
    ? undefined
    : { ...row, next: row.next === null ? null : new Date(row.next).toISOString() };
}

const nextAfter = (ms: number): string => new Date(h.clock.now().getTime() + ms).toISOString();

describe("intake queues a ride in the same transaction as its FIT", () => {
  it("queues one pending row when the FIT is stored, and tells the bus; not for the record alone", async () => {
    const heard: RideStoredEvent[] = [];
    h.services.bus.on("ride-stored", (event) => {
      heard.push(event);
    });
    const record = asRide(rideFixture("minimal-free-ride.json"), "ride-1", START);
    expectUpload(await putRecord(h.app, "ride-1", record), "ride-1", "stored");
    expect(await destination("ride-1")).toBeUndefined();
    expect(heard).toEqual([]);

    const fit = fitOf(1);
    expectUpload(await putFit(h.app, "ride-1", fit), "ride-1", "stored");
    expect(await destination("ride-1")).toMatchObject({
      state: "pending",
      attempts: 0,
      last_error: null,
      external_id: null,
      next: null,
    });
    expect(heard).toEqual([{ rideId: "ride-1", startEpochMillis: START }]);

    // The same file again: nothing new.
    expectUpload(await putFit(h.app, "ride-1", fit), "ride-1", "already-stored");
    expect(heard).toHaveLength(1);
    const rows = await db.query("SELECT 1 FROM ride_destinations");
    expect(rows.rows).toHaveLength(1);
  });

  it("queues nothing for a FIT that was refused", async () => {
    await store("ride-1", 1);
    await db.exec("TRUNCATE ride_destinations");
    expectError(await putFit(h.app, "ride-1", fitOf(2)), "fit_conflict");
    expectError(await putFit(h.app, "ride-unknown", fitOf(3)), "ride_not_found");
    expect((await db.query("SELECT 1 FROM ride_destinations")).rows).toEqual([]);
  });

  it("stores the FIT or queues the ride, never one without the other", async () => {
    const record = asRide(rideFixture("minimal-free-ride.json"), "ride-1", START);
    expectUpload(await putRecord(h.app, "ride-1", record), "ride-1", "stored");

    // A database whose queue insert fails: the FIT must not be stored either.
    const broken: Db = {
      ...db,
      transaction: (work) =>
        db.transaction((tx) =>
          work({
            ...tx,
            query: (sql, params) =>
              sql.includes("INSERT INTO ride_destinations")
                ? Promise.reject(new Error("the queue is broken"))
                : tx.query(sql, params),
          }),
        ),
    };
    const failing = startApp(broken).app;
    expectError(await putFit(failing, "ride-1", fitOf(1)), "internal_error");
    const fit = await db.query<{ fit: unknown }>("SELECT fit FROM rides WHERE id = 'ride-1'");
    expect(fit.rows[0]?.fit).toBeNull();
    expect(await destination("ride-1")).toBeUndefined();
    await failing.close();

    // The tablet retries against a working service.
    expectUpload(await putFit(h.app, "ride-1", fitOf(1)), "ride-1", "stored");
    expect(await destination("ride-1")).toMatchObject({ state: "pending" });
  });
});

describe("the worker", () => {
  it("uploads the stored FIT with the ride id, and records the activity", async () => {
    const fit = await store("ride-1", 1);
    const summary = await worker().runOnce();
    expect(summary).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });

    expect(fake.activities).toMatchObject([
      { external_id: "ride-1", filename: "ride-1.fit", bytes: fit.length },
    ]);
    expect(fake.activities[0]?.sha256).toBe(createHash("sha256").update(fit).digest("hex"));
    expect(await destination("ride-1")).toMatchObject({
      state: "done",
      attempts: 1,
      last_error: null,
      external_id: "i1000001",
    });
    // Done is done: the next pass has nothing to do.
    expect(await worker().runOnce()).toMatchObject({ claimed: 0 });
    expect(fake.requestsTo("POST", "/activities")).toHaveLength(1);
  });

  it("takes rides oldest first, a batch at a time", async () => {
    for (let n = 1; n <= 7; n++) {
      await store(`ride-${n}`, n);
      h.clock.advance(1000);
    }
    expect(await worker().runOnce()).toMatchObject({ claimed: 5, done: 5 });
    expect(await worker().runOnce()).toMatchObject({ claimed: 2, done: 2 });
    expect(fake.activities.map((a) => a.external_id)).toEqual([
      "ride-1",
      "ride-2",
      "ride-3",
      "ride-4",
      "ride-5",
      "ride-6",
      "ride-7",
    ]);
  });

  it.each([
    ["a 200 that created nothing", "empty-200"],
    ["a 409", "conflict-409"],
    ["a 422 that says it already exists", "message-422"],
  ] as const)("counts %s as done: the activity is already there", async (_label, style) => {
    // The owner imported this ride by hand before (no external id); same file, same start.
    const fit = await store("ride-1", 1);
    fake.activities.push({
      id: "i-by-hand",
      external_id: null,
      start_date: new Date(START + HOUR).toISOString(),
      name: "Imported by hand",
      paired_event_id: null,
      sha256: createHash("sha256").update(fit).digest("hex"),
      filename: "x.fit",
      bytes: fit.length,
    });
    fake.duplicateStyle = style;

    expect(await worker().runOnce()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });
    // Its id is found by start time for the record (the 409 and 422 styles say nothing about it).
    expect(await destination("ride-1")).toMatchObject({
      state: "done",
      attempts: 1,
      last_error: null,
      external_id: "i-by-hand",
    });
    expect(fake.activities).toHaveLength(1);
  });

  it("is done even when the existing activity cannot be found", async () => {
    const fit = await store("ride-1", 1);
    fake.activities.push({
      id: "i-else",
      external_id: null,
      start_date: "2020-01-01T00:00:00Z",
      name: null,
      paired_event_id: null,
      sha256: createHash("sha256").update(fit).digest("hex"),
      filename: "x.fit",
      bytes: fit.length,
    });
    expect(await worker().runOnce()).toMatchObject({ done: 1 });
    expect(await destination("ride-1")).toMatchObject({ state: "done", external_id: null });
  });

  it("two passes at once upload a ride once", async () => {
    await store("ride-1", 1);
    const [a, b] = await Promise.all([worker().runOnce(), worker().runOnce()]);
    expect(a.claimed + b.claimed).toBe(1);
    expect(fake.requestsTo("POST", "/activities")).toHaveLength(1);
  });

  describe("failures", () => {
    it("retries with a growing wait, tested against the clock", async () => {
      await store("ride-1", 1);
      fake.failAll(503);

      expect(await worker().runOnce()).toEqual({ claimed: 1, done: 0, retry: 1, failed: 0 });
      expect(await destination("ride-1")).toMatchObject({
        state: "pending",
        attempts: 1,
        last_error: expect.stringContaining("HTTP 503"),
        next: nextAfter(1 * MINUTE),
      });
      // Not due yet: nothing is tried, however often it is asked.
      expect(await worker().runOnce()).toMatchObject({ claimed: 0 });
      h.clock.advance(MINUTE - 1);
      expect(await worker().runOnce()).toMatchObject({ claimed: 0 });

      h.clock.advance(1);
      await worker().runOnce();
      expect(await destination("ride-1")).toMatchObject({
        attempts: 2,
        next: nextAfter(2 * MINUTE),
      });
      h.clock.advance(2 * MINUTE);
      await worker().runOnce();
      expect(await destination("ride-1")).toMatchObject({
        attempts: 3,
        next: nextAfter(4 * MINUTE),
      });

      // intervals.icu is back.
      fake.failAll(0);
      h.clock.advance(4 * MINUTE);
      expect(await worker().runOnce()).toMatchObject({ done: 1 });
      expect(await destination("ride-1")).toMatchObject({
        state: "done",
        attempts: 4,
        last_error: null,
        external_id: "i1000001",
      });
    });

    it("waits through an outage: unreachable is retried", async () => {
      await store("ride-1", 1);
      fake.setUnreachable(true);
      await worker().runOnce();
      expect(await destination("ride-1")).toMatchObject({
        state: "pending",
        attempts: 1,
        last_error: expect.stringContaining("did not answer"),
      });
      fake.setUnreachable(false);
      h.clock.advance(MINUTE);
      expect(await worker().runOnce()).toMatchObject({ done: 1 });
    });

    it("gives up on a file intervals.icu refuses, with the reason, and never asks again", async () => {
      await store("ride-1", 1);
      fake.failNext(1, 422, {
        path: "/activities",
        body: JSON.stringify({ status: 422, error: "Unsupported file" }),
      });
      expect(await worker().runOnce()).toEqual({ claimed: 1, done: 0, retry: 0, failed: 1 });
      expect(await destination("ride-1")).toMatchObject({
        state: "failed",
        attempts: 1,
        last_error: expect.stringContaining("Unsupported file"),
      });
      h.clock.advance(30 * 24 * HOUR);
      expect(await worker().runOnce()).toMatchObject({ claimed: 0 });
    });

    it("gives up after 20 failed tries, with the last reason", async () => {
      await store("ride-1", 1);
      fake.failAll(500);
      for (let i = 1; i <= 19; i++) {
        h.clock.advance(7 * HOUR);
        expect(await worker().runOnce()).toMatchObject({ retry: 1 });
      }
      expect(await destination("ride-1")).toMatchObject({ state: "pending", attempts: 19 });
      h.clock.advance(7 * HOUR);
      expect(await worker().runOnce()).toMatchObject({ failed: 1 });
      expect(await destination("ride-1")).toMatchObject({
        state: "failed",
        attempts: 20,
        last_error: expect.stringContaining("HTTP 500"),
      });
    });

    it("a wrong key waits at the longest interval and is never given up", async () => {
      await store("ride-1", 1);
      fake.failAll(401);
      for (let i = 1; i <= 25; i++) {
        h.clock.advance(7 * HOUR);
        expect(await worker().runOnce()).toMatchObject({ retry: 1, failed: 0 });
      }
      expect(await destination("ride-1")).toMatchObject({
        state: "pending",
        attempts: 25,
        last_error: expect.stringContaining("did not accept the API key"),
        next: nextAfter(6 * HOUR),
      });
      // The owner fixes the key; the next pass that is due succeeds.
      fake.failAll(0);
      h.clock.advance(6 * HOUR);
      expect(await worker().runOnce()).toMatchObject({ done: 1 });
    });

    it("a restart tries again at once, whatever the wait was", async () => {
      await store("ride-1", 1);
      fake.failAll(401);
      for (let i = 0; i < 6; i++) {
        h.clock.advance(7 * HOUR);
        await worker().runOnce();
      }
      expect((await destination("ride-1"))?.next).toBe(nextAfter(6 * HOUR));
      fake.failAll(0);

      // Same database, new process: the loop's start resets the waits and takes what is due.
      const restarted = startHarness(db, fake, { now: h.clock.now().toISOString() });
      await restarted.services.uploads?.start();
      await restarted.services.uploads?.idle();
      expect(await destination("ride-1")).toMatchObject({ state: "done", external_id: "i1000001" });
      await restarted.app.close();
    });

    it("the waits are 1, 2, 4 … minutes up to six hours", () => {
      expect([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20].map((n) => retryDelayMs(n) / MINUTE)).toEqual([
        1, 2, 4, 8, 16, 32, 64, 128, 256, 360, 360,
      ]);
      expect(retryDelayMs(0)).toBe(MINUTE);
    });
  });

  describe("never in the tablet's way", () => {
    it("uploads after the request has been answered, on the ride-stored event", async () => {
      await worker().start();
      await worker().idle();
      const started = Date.now();
      const record = asRide(rideFixture("minimal-free-ride.json"), "ride-1", START);
      expectUpload(await putRecord(h.app, "ride-1", record), "ride-1", "stored");
      const res = await putFit(h.app, "ride-1", fitOf(1));
      expectUpload(res, "ride-1", "stored");
      expect(Date.now() - started).toBeLessThan(5000);

      await worker().idle();
      expect(await destination("ride-1")).toMatchObject({ state: "done", external_id: "i1000001" });
    });

    it("answers 201 and keeps the ride pending when intervals.icu is down", async () => {
      fake.setUnreachable(true);
      await worker().start();
      await worker().idle();
      await store("ride-1", 1);
      await worker().idle();
      expect(await destination("ride-1")).toMatchObject({ state: "pending", attempts: 1 });
      const row = await db.query<{ fit: unknown }>("SELECT fit FROM rides WHERE id = 'ride-1'");
      expect(row.rows[0]?.fit).not.toBeNull();
    });

    it("a listener that throws does not fail the request", async () => {
      h.services.bus.on("ride-stored", () => {
        throw new Error("a listener is broken");
      });
      h.services.bus.on("ride-stored", async () => {
        throw new Error("an async listener is broken");
      });
      await store("ride-1", 1);
      expect(await destination("ride-1")).toMatchObject({ state: "pending" });
    });

    it("rides queued while the integration is off are sent when it is turned on", async () => {
      const off = startHarness(db, fake, { configured: false });
      expect(off.services.uploads).toBeNull();
      await store("ride-1", 1, { app: off.app });
      expect(await destination("ride-1")).toMatchObject({ state: "pending", attempts: 0 });
      expect(fake.requests).toEqual([]);
      await off.app.close();

      expect(await worker().runOnce()).toMatchObject({ claimed: 1, done: 1 });
    });
  });

  describe("pairing with the planned workout of the day", () => {
    const sendAndGetPairing = async (): Promise<number | null> => {
      expect(await worker().runOnce()).toMatchObject({ done: 1 });
      return fake.activities[0]?.paired_event_id ?? null;
    };

    it("pairs with the one workout planned for the day the ride was ridden", async () => {
      const planned = fake.addEvent(plannedEvent("2026-11-18", "Sweet spot", [steady(600, 88)]));
      fake.addEvent(plannedEvent("2026-11-19", "Tomorrow", [steady(600, 88)]));
      await store("ride-1", 1);
      expect(await sendAndGetPairing()).toBe(planned.id);
      expect(h.logging.lines.some((l) => l.fields.paired === true)).toBe(true);
    });

    it("pairs with the workout the ride was started from, when there are several", async () => {
      fake.addEvent(plannedEvent("2026-11-18", "Morning", [steady(600, 60)]));
      const evening = fake.addEvent(plannedEvent("2026-11-18", "Evening", [steady(600, 88)]));
      await store("ride-1", 1, { workoutId: `icu-${evening.id}` });
      expect(await sendAndGetPairing()).toBe(evening.id);
    });

    it("does not guess between several workouts when the ride does not say", async () => {
      fake.addEvent(plannedEvent("2026-11-18", "Morning", [steady(600, 60)]));
      fake.addEvent(plannedEvent("2026-11-18", "Evening", [steady(600, 88)]));
      await store("ride-1", 1);
      expect(await sendAndGetPairing()).toBeNull();
    });

    it("does not pair a workout of another day, another sport or another kind of event", async () => {
      fake.addEvent(plannedEvent("2026-11-17", "Yesterday", [steady(600, 60)]));
      fake.addEvent(plannedEvent("2026-11-18", "Run", [steady(600, 60)], { type: "Run" }));
      fake.addEvent(plannedEvent("2026-11-18", "Note", [], { category: "NOTE" }));
      await store("ride-1", 1);
      expect(await sendAndGetPairing()).toBeNull();
    });

    it("ignores a workoutId that is not in the day's calendar, and falls back to the one workout", async () => {
      const only = fake.addEvent(plannedEvent("2026-11-18", "Only", [steady(600, 60)]));
      await store("ride-1", 1, { workoutId: "icu-999999" });
      expect(await sendAndGetPairing()).toBe(only.id);
      // and a workout id of another source is no event at all
      await clearRides(db);
      fake.reset();
      fake.addEvent(plannedEvent("2026-11-18", "Only", [steady(600, 60)]));
      await store("ride-2", 2, { workoutId: "free-60" });
      expect(await sendAndGetPairing()).not.toBeNull();
    });

    it("takes the day in the rider's time zone", async () => {
      // 23:30 UTC on the 18th is 00:30 on the 19th in Paris.
      fake.addEvent(plannedEvent("2026-11-18", "The 18th", [steady(600, 60)]));
      const the19th = fake.addEvent(plannedEvent("2026-11-19", "The 19th", [steady(600, 60)]));
      await store("ride-1", 1, { hours: 16.5 });
      expect(await sendAndGetPairing()).toBe(the19th.id);
    });

    it("uploads without pairing, and says so, when the calendar cannot be read", async () => {
      fake.addEvent(plannedEvent("2026-11-18", "Sweet spot", [steady(600, 88)]));
      await store("ride-1", 1);
      fake.failNext(4, 500); // the day's lookup uses up its four tries
      expect(await sendAndGetPairing()).toBeNull();
      expect(
        h.logging.lines.some((l) => l.level === "warn" && l.message?.includes("without pairing")),
      ).toBe(true);
      expect(await destination("ride-1")).toMatchObject({ state: "done" });
    });
  });

  it("never logs the API key", async () => {
    await store("ride-1", 1);
    await store("ride-2", 2);
    fake.failNext(4, 500);
    await worker().runOnce();
    fake.failAll(401);
    h.clock.advance(HOUR);
    await worker().runOnce();
    fake.failAll(0);
    h.clock.advance(7 * HOUR);
    await worker().runOnce();
    expect(h.logging.lines.length).toBeGreaterThan(0);
    expect(h.logging.text()).not.toContain(FAKE_API_KEY);
    const stored = await db.query("SELECT last_error FROM ride_destinations");
    expect(JSON.stringify(stored.rows)).not.toContain(FAKE_API_KEY);
  });
});
