import type { RideRecord } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { asRide, expectUpload, putFit, putRecord, rideFixture } from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { buildFit } from "./helpers/fit.ts";
import { plannedEvent, startHarness, steady } from "./helpers/harness.ts";
import { manualTimers } from "./helpers/support.ts";

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
  await clearRides(db);
  await clearPlan(db);
  fake.reset();
});

describe("the loops", () => {
  it("read the plan at start and again every PLAN_REFRESH_MINUTES, until the app closes", async () => {
    const timers = manualTimers();
    const h = startHarness(db, fake, { timers, refreshMinutes: 7 });
    fake.addEvent(plannedEvent("2026-11-20", "A", [steady(600, 60)]));

    h.services.start();
    await h.services.refresher?.refresh(); // joins the first read, which start() began
    expect(fake.requestsTo("GET", "/events")).toHaveLength(1);
    expect(await h.services.plan.list({ from: "2026-11-18", to: "2026-12-02" })).toHaveLength(1);
    await vi.waitFor(() =>
      expect(timers.pending).toContainEqual({ kind: "after", ms: 7 * 60_000 }),
    );

    fake.addEvent(plannedEvent("2026-11-21", "B", [steady(600, 60)]));
    timers.tick();
    await h.services.refresher?.refresh();
    expect(fake.requestsTo("GET", "/events")).toHaveLength(2);
    expect(await h.services.plan.list({ from: "2026-11-18", to: "2026-12-02" })).toHaveLength(2);
    // And it is scheduled again.
    await vi.waitFor(() =>
      expect(timers.pending.filter((t) => t.kind === "after" && t.ms === 7 * 60_000)).toHaveLength(
        1,
      ),
    );

    await h.app.close();
    expect(timers.pending).toEqual([]);
    timers.tick();
    expect(fake.requestsTo("GET", "/events")).toHaveLength(2);
  });

  it("the upload worker looks for due rides on every tick and stops with the app", async () => {
    const timers = manualTimers();
    const h = startHarness(db, fake, { timers });
    // A ride that failed before and is due again now.
    const record: RideRecord = asRide(
      rideFixture("minimal-free-ride.json"),
      "ride-1",
      Date.parse("2026-11-18T07:00:00Z"),
    );
    expectUpload(await putRecord(h.app, "ride-1", record), "ride-1", "stored");
    expectUpload(await putFit(h.app, "ride-1", buildFit()), "ride-1", "stored");
    await db.query(
      `UPDATE ride_destinations SET attempts = 2, last_error = 'it was down',
              next_attempt_at = '2026-11-18T09:00:00Z'`,
    );

    await h.services.uploads?.start();
    await h.services.uploads?.idle();
    // start() brings the wait forward, so it is tried now.
    const state = await db.query<{ state: string }>("SELECT state FROM ride_destinations");
    expect(state.rows[0]?.state).toBe("done");
    expect(timers.pending).toEqual([{ kind: "every", ms: 30_000 }]);

    // A second ride that failed once and is due in ten minutes: the tick finds it when it is due.
    const second: RideRecord = asRide(
      rideFixture("minimal-free-ride.json"),
      "ride-2",
      Date.parse("2026-11-18T05:00:00Z"),
    );
    await db.query(
      `INSERT INTO rides (id, start_epoch_millis, record, record_sha256, uploaded_by, fit, fit_sha256)
       VALUES ('ride-2', $1, $2::jsonb, 'x', 'tablet', $3, 'y')`,
      [
        second.ride.startEpochMillis,
        JSON.stringify(second),
        buildFit({ data: Uint8Array.from([9, 9, 9, 9]) }),
      ],
    );
    await db.query(
      `INSERT INTO ride_destinations (ride_id, destination, attempts, next_attempt_at)
       VALUES ('ride-2', 'intervals.icu', 1, '2026-11-18T08:10:00Z')`,
    );
    timers.tick();
    await h.services.uploads?.idle();
    expect(fake.activities.map((a) => a.external_id)).toEqual(["ride-1"]);
    h.clock.advance(10 * 60_000);
    timers.tick();
    await h.services.uploads?.idle();
    expect(fake.activities.map((a) => a.external_id)).toEqual(["ride-1", "ride-2"]);

    await h.app.close();
    expect(timers.pending).toEqual([]);
  });

  it("do nothing and say so when no key is set", async () => {
    const timers = manualTimers();
    const h = startHarness(db, fake, { timers, configured: false });
    h.services.start();
    expect(timers.pending).toEqual([]);
    expect(fake.requests).toEqual([]);
    expect(h.services.intervals).toBeNull();
    expect(h.logging.lines.some((l) => l.message?.includes("not configured"))).toBe(true);
    await h.app.close();
  });
});
