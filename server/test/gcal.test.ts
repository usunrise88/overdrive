import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { googleEventId } from "../src/gcal/event.ts";
import { retryDelayMs } from "../src/gcal/mirror.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import {
  FAKE_CALENDAR_ID,
  FAKE_CLIENT_ID,
  FAKE_CLIENT_SECRET,
  FAKE_REFRESH_TOKEN,
  type FakeGoogle,
  startFakeGoogle,
} from "./helpers/fake-google.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  clearIntegrations,
  type IntegrationHarness,
  startIntegrationHarness,
} from "./helpers/integrations.ts";

let db: Db;
let intervals: FakeIntervals;
let google: FakeGoogle;
let h: IntegrationHarness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  google = await startFakeGoogle();
});
afterAll(async () => {
  await google.close();
  await intervals.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  await clearIntegrations(db);
  intervals.reset();
  google.reset();
  h = startIntegrationHarness(db, { intervals, google });
});

const MINUTE = 60_000;

const calendar = (harness: IntegrationHarness = h) => {
  if (harness.services.calendar === null) throw new Error("the calendar is off");
  return harness.services.calendar;
};

const refresh = async (harness: IntegrationHarness = h) => {
  const result = await harness.services.refresher?.refresh();
  expect(result?.ok).toBe(true);
};

/** A 40-minute workout planned on `date`. */
function plan(date: string, name = "Sweet spot"): number {
  return intervals.addEvent(
    plannedEvent(date, name, [steady(600, 60), steady(1200, 88), steady(600, 50)]),
  ).id;
}

interface Row {
  planned_id: string;
  google_event_id: string | null;
  plan_date: string;
  state: string;
  attempts: number;
  last_error: string | null;
  next_attempt_at: string | null;
}

async function rows(): Promise<Row[]> {
  const result = await db.query<Row>(
    `SELECT planned_id, google_event_id, plan_date::text AS plan_date, state, attempts, last_error,
            next_attempt_at FROM calendar_events ORDER BY planned_id`,
  );
  return result.rows;
}

describe("creating events", () => {
  it("pushes a planned workout as an event with its name, start, length and steps", async () => {
    const id = plan("2026-11-19");
    await refresh();
    const summary = await calendar().runOnce();
    expect(summary).toMatchObject({ created: 1, updated: 0, deleted: 0, retry: 0, failed: 0 });

    const [event] = google.live();
    expect(event).toMatchObject({
      id: googleEventId(`icu-${id}`),
      summary: "Sweet spot",
      description: "10 min at 60% FTP, 20 min at 88% FTP, 10 min at 50% FTP. Total 40 min.",
      // 07:00 in Paris in November is 06:00 UTC; the workout lasts 40 minutes.
      start: { dateTime: "2026-11-19T06:00:00.000Z", timeZone: "Europe/Paris" },
      end: { dateTime: "2026-11-19T06:40:00.000Z", timeZone: "Europe/Paris" },
      extendedProperties: { private: { overdrive: "1", plannedId: `icu-${id}` } },
    });
    // The reminders are the calendar's own.
    expect(event).not.toHaveProperty("reminders");
    expect(event?.id).toMatch(/^[a-v0-9]{5,1024}$/);
    expect(await rows()).toMatchObject([
      {
        planned_id: `icu-${id}`,
        google_event_id: event?.id,
        plan_date: "2026-11-19",
        state: "synced",
        attempts: 0,
        last_error: null,
      },
    ]);
  });

  it("uses GCAL_WORKOUT_START for the start time", async () => {
    const custom = startIntegrationHarness(db, {
      intervals,
      google,
      config: { GCAL_WORKOUT_START: "18:30" },
    });
    plan("2026-11-19");
    await refresh(custom);
    await calendar(custom).runOnce();
    expect(google.live()[0]?.start?.dateTime).toBe("2026-11-19T17:30:00.000Z");
  });

  it("is idempotent: a second pass sends nothing", async () => {
    plan("2026-11-19");
    plan("2026-11-20", "Endurance");
    await refresh();
    await calendar().runOnce();
    const sent = google.requests.length;
    expect(await calendar().runOnce()).toEqual({
      created: 0,
      updated: 0,
      deleted: 0,
      retry: 0,
      failed: 0,
      waiting: 0,
    });
    expect(google.requests.length).toBe(sent);
    expect(google.live()).toHaveLength(2);
    expect(google.tokensIssued).toBe(1);
  });

  it("leaves out a workout the tablet cannot play", async () => {
    intervals.addEvent(
      plannedEvent("2026-11-19", "Odd", [
        { reps: 2, steps: [{ reps: 2, steps: [steady(60, 100)] }] },
      ]),
    );
    plan("2026-11-20");
    await refresh();
    await calendar().runOnce();
    expect(google.live().map((e) => e.summary)).toEqual(["Sweet spot"]);
  });
});

describe("mirroring changes", () => {
  it("updates the same event when the workout changes, without making another", async () => {
    const id = plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    const [before] = google.live();

    const event = intervals.events.find((e) => e.id === id);
    if (event === undefined) throw new Error("no event");
    event.name = "Sweet spot, longer";
    event.workout_doc = { ftp: 250, steps: [steady(600, 60), steady(2400, 88)] };
    await refresh();
    expect(await calendar().runOnce()).toMatchObject({ created: 0, updated: 1 });

    expect(google.live()).toHaveLength(1);
    const [after] = google.live();
    expect(after?.id).toBe(before?.id);
    expect(after).toMatchObject({
      summary: "Sweet spot, longer",
      description: "10 min at 60% FTP, 40 min at 88% FTP. Total 50 min.",
      end: { dateTime: "2026-11-19T06:50:00.000Z" },
    });
    expect(google.requestsTo("POST", "/events")).toHaveLength(1);
    expect(google.requestsTo("PATCH")).toHaveLength(1);
  });

  it("moves the event when the workout moves to another day", async () => {
    const id = plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    const event = intervals.events.find((e) => e.id === id);
    if (event === undefined) throw new Error("no event");
    event.start_date_local = "2026-11-21T00:00:00";
    await refresh();
    await calendar().runOnce();
    expect(google.live()).toHaveLength(1);
    expect(google.live()[0]?.start?.dateTime).toBe("2026-11-21T06:00:00.000Z");
    expect((await rows())[0]).toMatchObject({ plan_date: "2026-11-21", state: "synced" });
  });

  it("deletes the event when the workout is removed from the plan, and forgets it", async () => {
    plan("2026-11-19");
    const second = plan("2026-11-20", "Endurance");
    await refresh();
    await calendar().runOnce();
    expect(google.live()).toHaveLength(2);

    intervals.events.splice(
      intervals.events.findIndex((e) => e.id === second),
      1,
    );
    await refresh();
    expect(await calendar().runOnce()).toMatchObject({ deleted: 1 });
    expect(google.live().map((e) => e.summary)).toEqual(["Sweet spot"]);
    expect((await rows()).map((r) => r.planned_id)).not.toContain(`icu-${second}`);
    // Deleting again is not attempted.
    expect(await calendar().runOnce()).toMatchObject({ deleted: 0 });
    expect(google.requestsTo("DELETE")).toHaveLength(1);
  });

  it("leaves a day that has passed alone: the plan cache drops it, the calendar keeps it", async () => {
    const id = plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    h.clock.set("2026-11-20T08:00:00Z");
    await refresh(); // the window now starts on the 20th: the cache loses the 19th
    expect(await h.services.plan.get(`icu-${id}`)).toBeNull();
    expect(await calendar().runOnce()).toMatchObject({ deleted: 0 });
    expect(google.live()).toHaveLength(1);
    expect(await rows()).toHaveLength(1);
    // A week later the row is tidied away, the event stays.
    h.clock.set("2026-11-28T08:00:00Z");
    await calendar().runOnce();
    expect(await rows()).toHaveLength(0);
    expect(google.live()).toHaveLength(1);
  });

  it("never reads an event back: a change made in Google is left until the workout changes", async () => {
    const id = plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    const [event] = google.live();
    if (event === undefined) throw new Error("no event");
    google.events.set(event.id, { ...event, summary: "Renamed by hand" });
    await calendar().runOnce();
    expect(google.live()[0]?.summary).toBe("Renamed by hand");
    expect(google.requestsTo("GET")).toHaveLength(1); // only the start-up sweep's list

    // The workout changes: the mirror says what the plan says again.
    const planned = intervals.events.find((e) => e.id === id);
    if (planned === undefined) throw new Error("no event");
    planned.name = "Sweet spot v2";
    await refresh();
    await calendar().runOnce();
    expect(google.live()[0]?.summary).toBe("Sweet spot v2");
  });

  it("brings back an event that was deleted by hand when the workout next changes", async () => {
    const id = plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    const [event] = google.live();
    if (event === undefined) throw new Error("no event");
    google.events.set(event.id, { ...event, status: "cancelled" });

    const planned = intervals.events.find((e) => e.id === id);
    if (planned === undefined) throw new Error("no event");
    planned.name = "Sweet spot v2";
    await refresh();
    await calendar().runOnce();
    expect(google.live()).toHaveLength(1);
    expect(google.live()[0]).toMatchObject({ id: event.id, summary: "Sweet spot v2" });
  });
});

describe("reconciling at start", () => {
  it("deletes our events whose workout is gone, and only ours", async () => {
    plan("2026-11-19");
    await refresh();
    const start = { dateTime: "2026-11-21T06:00:00.000Z" };
    const end = { dateTime: "2026-11-21T07:00:00.000Z" };
    google.addEvent({
      id: "ovdorphan1",
      status: "confirmed",
      summary: "Gone",
      start,
      end,
      extendedProperties: { private: { overdrive: "1", plannedId: "icu-999" } },
    });
    google.addEvent({ id: "ovdmine0001", status: "confirmed", summary: "Dentist", start, end });
    // A day before today is history, not an orphan.
    google.addEvent({
      id: "ovdold00001",
      status: "confirmed",
      summary: "Last week",
      start: { dateTime: "2026-11-10T06:00:00.000Z" },
      end: { dateTime: "2026-11-10T07:00:00.000Z" },
      extendedProperties: { private: { overdrive: "1", plannedId: "icu-1" } },
    });

    expect(await calendar().runOnce()).toMatchObject({ created: 1, deleted: 1 });
    expect(
      google
        .live()
        .map((e) => e.summary)
        .sort(),
    ).toEqual(["Dentist", "Last week", "Sweet spot"]);
    // The sweep happens once per start.
    await calendar().runOnce();
    expect(google.requestsTo("GET")).toHaveLength(1);
  });

  it("deletes a second event that shows the same workout, and follows the pages of the list", async () => {
    const id = plan("2026-11-19");
    await refresh();
    google.pageSize = 2;
    const start = { dateTime: "2026-11-25T06:00:00.000Z" };
    const end = { dateTime: "2026-11-25T07:00:00.000Z" };
    for (let n = 1; n <= 5; n++) {
      google.addEvent({
        id: `ovdstray000${n}`,
        status: "confirmed",
        summary: "Stray",
        start,
        end,
        extendedProperties: {
          private: { overdrive: "1", plannedId: n === 1 ? `icu-${id}` : `icu-9${n}` },
        },
      });
    }
    await calendar().runOnce();
    expect(google.live().map((e) => e.summary)).toEqual(["Sweet spot"]);
    expect(google.requestsTo("GET").length).toBeGreaterThan(1);
  });

  it("does not make a second event when the table was lost: the derived id answers 409 and is patched", async () => {
    plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    await db.exec("TRUNCATE calendar_events");
    const fresh = startIntegrationHarness(db, { intervals, google });
    expect(await calendar(fresh).runOnce()).toMatchObject({ created: 0, updated: 1 });
    expect(google.live()).toHaveLength(1);
    expect(await rows()).toMatchObject([{ state: "synced" }]);
  });

  it("does nothing, deletes nothing, until the plan has been read once", async () => {
    google.addEvent({
      id: "ovdorphan1",
      status: "confirmed",
      start: { dateTime: "2026-11-21T06:00:00.000Z" },
      end: { dateTime: "2026-11-21T07:00:00.000Z" },
      extendedProperties: { private: { overdrive: "1", plannedId: "icu-999" } },
    });
    expect(await calendar().runOnce()).toMatchObject({ created: 0, deleted: 0 });
    expect(google.requests).toHaveLength(0);
    expect(google.live()).toHaveLength(1);
    expect(h.logging.text()).toContain("waits for the first read of the plan");
  });
});

describe("when Google fails", () => {
  it("keeps the retry in the table: 1, 2, 4 minutes, and waits without calling", async () => {
    expect([1, 2, 3, 4, 5].map((n) => retryDelayMs(n) / MINUTE)).toEqual([1, 2, 4, 8, 16]);
    expect(retryDelayMs(20)).toBe(6 * 60 * MINUTE);

    const id = plan("2026-11-19");
    await refresh();
    // The client's own four attempts all meet a 503.
    google.failNext(4, 503, { method: "POST" });
    expect(await calendar().runOnce()).toMatchObject({ retry: 1, created: 0 });
    expect(await rows()).toMatchObject([
      { planned_id: `icu-${id}`, state: "pending", attempts: 1, google_event_id: null },
    ]);
    const [row] = await rows();
    expect(row?.last_error).toMatch(/503/);
    expect(new Date(row?.next_attempt_at ?? 0).getTime()).toBe(h.clock.now().getTime() + MINUTE);

    // Not yet due: nothing is sent.
    const sent = google.requests.length;
    expect(await calendar().runOnce()).toMatchObject({ waiting: 1, created: 0 });
    expect(google.requests.length).toBe(sent);

    h.clock.advance(MINUTE);
    expect(await calendar().runOnce()).toMatchObject({ created: 1 });
    expect(google.live()).toHaveLength(1);
    expect(await rows()).toMatchObject([{ state: "synced", attempts: 0, last_error: null }]);
  });

  it("survives a restart in the middle of a backoff: the state is in the database, and a restart retries at once", async () => {
    plan("2026-11-19");
    await refresh();
    google.failNext(4, 500, { method: "POST" });
    await calendar().runOnce();
    expect(await rows()).toMatchObject([{ state: "pending", attempts: 1 }]);

    // The next try is a minute away, but the service restarts (the owner may have fixed something).
    const restarted = startIntegrationHarness(db, {
      intervals,
      google,
      now: h.clock.now().toISOString(),
    });
    await calendar(restarted).start();
    await vi.waitFor(() => expect(google.live()).toHaveLength(1));
    await calendar(restarted).stop();
    expect(await rows()).toMatchObject([{ state: "synced" }]);
  });

  it("waits out a 429 as long as Google says, then goes on", async () => {
    plan("2026-11-19");
    await refresh();
    google.failNext(1, 429, { retryAfter: 7 });
    expect(await calendar().runOnce()).toMatchObject({ created: 1 });
    expect(google.live()).toHaveLength(1);
  });

  it("gives up on an event Google refuses (a 400), until the workout changes", async () => {
    const id = plan("2026-11-19");
    await refresh();
    google.failNext(1, 400, { method: "POST" });
    expect(await calendar().runOnce()).toMatchObject({ failed: 1 });
    expect(await rows()).toMatchObject([{ state: "failed", attempts: 1 }]);
    const sent = google.requests.length;
    h.clock.advance(24 * 60 * MINUTE);
    await calendar().runOnce();
    expect(google.requests.length).toBe(sent);

    const planned = intervals.events.find((e) => e.id === id);
    if (planned === undefined) throw new Error("no event");
    planned.name = "Sweet spot v2";
    await refresh();
    expect(await calendar().runOnce()).toMatchObject({ created: 1 });
    expect(google.live()).toHaveLength(1);
  });

  it("gives up after 20 failed tries of a transient kind", async () => {
    plan("2026-12-01");
    await refresh();
    google.failAll(500);
    for (let n = 1; n <= 20; n++) {
      h.clock.advance(7 * 60 * MINUTE);
      await calendar().runOnce();
    }
    expect(await rows()).toMatchObject([{ state: "failed", attempts: 20 }]);
  });

  it("asks for a fresh access token once when Google says the old one is no good", async () => {
    plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    expect(google.tokensIssued).toBe(1);
    google.expireAccessTokens();
    const planned = intervals.events[0];
    if (planned === undefined) throw new Error("no event");
    planned.name = "Sweet spot v2";
    await refresh();
    expect(await calendar().runOnce()).toMatchObject({ updated: 1 });
    expect(google.tokensIssued).toBe(2);
  });

  it("stops everything for a while when the refresh token is refused, says what to do, and retries at once on a restart", async () => {
    plan("2026-11-19");
    plan("2026-11-20", "Endurance");
    await refresh();
    google.revokeRefreshToken();
    expect(await calendar().runOnce()).toMatchObject({ created: 0 });
    // One refusal is enough: the second workout is not even tried.
    expect(google.requestsTo("POST", "/token")).toHaveLength(1);
    const errors = h.logging.lines.filter((l) => l.level === "error");
    expect(errors).toHaveLength(1);
    expect(errors[0]?.fields).toMatchObject({ kind: "unauthorized" });
    expect(JSON.stringify(errors)).toContain("google-auth");

    await calendar().runOnce();
    expect(google.requestsTo("POST", "/token")).toHaveLength(1); // blocked

    google.revokeRefreshToken(false);
    const restarted = startIntegrationHarness(db, {
      intervals,
      google,
      now: h.clock.now().toISOString(),
    });
    await calendar(restarted).start();
    await vi.waitFor(() => expect(google.live()).toHaveLength(2));
    await calendar(restarted).stop();
  });

  it("says a wrong calendar id is a configuration problem, not a reason to retry quickly", async () => {
    const wrong = startIntegrationHarness(db, {
      intervals,
      google,
      config: { GOOGLE_CALENDAR_ID: "nosuchcalendar@group.calendar.google.com" },
    });
    plan("2026-11-19");
    await refresh(wrong);
    await calendar(wrong).runOnce();
    const errors = wrong.logging.lines.filter((l) => l.level === "error");
    expect(errors).toHaveLength(1);
    expect(JSON.stringify(errors)).toContain("GOOGLE_CALENDAR_ID");

    // Nothing is tried again for six hours.
    const sent = google.requests.length;
    wrong.clock.advance(5 * 60 * MINUTE);
    await calendar(wrong).runOnce();
    expect(google.requests.length).toBe(sent);
    wrong.clock.advance(60 * MINUTE);
    await calendar(wrong).runOnce();
    expect(google.requests.length).toBeGreaterThan(sent);
  });

  it("recovers when Google is unreachable and comes back", async () => {
    plan("2026-11-19");
    await refresh();
    google.setUnreachable(true);
    expect(await calendar().runOnce()).toMatchObject({ retry: 1 });
    google.setUnreachable(false);
    h.clock.advance(MINUTE);
    expect(await calendar().runOnce()).toMatchObject({ created: 1 });
  });
});

describe("running", () => {
  it("mirrors on the plan-changed event and on a tick, and stops cleanly", async () => {
    plan("2026-11-19");
    h.start();
    await vi.waitFor(async () =>
      expect((await h.services.plan.syncState("intervals.icu")).lastSuccessAt).not.toBeNull(),
    );
    await vi.waitFor(() => expect(google.live()).toHaveLength(1));

    plan("2026-11-20", "Endurance");
    await h.services.refresher?.refresh(); // announces plan-changed
    await vi.waitFor(() => expect(google.live()).toHaveLength(2));

    // A tick also looks for work that has come due.
    google.failNext(4, 503, { method: "POST" });
    plan("2026-11-21", "Tempo");
    await h.services.refresher?.refresh();
    await calendar().idle();
    await vi.waitFor(async () => expect(await rows()).toHaveLength(3));
    h.clock.advance(MINUTE);
    h.timers.tick();
    await vi.waitFor(() => expect(google.live()).toHaveLength(3));
    await h.app.close();
  });
});

describe("off", () => {
  it("is off when no GOOGLE_* variable is set: no object, no request, and the log says so", async () => {
    const off = startIntegrationHarness(db, { intervals });
    expect(off.services.calendar).toBeNull();
    expect(off.logging.text()).toContain("Google Calendar is not configured");
    plan("2026-11-19");
    await refresh(off);
    off.start();
    await off.app.close();
    expect(google.requests).toHaveLength(0);
    expect(await rows()).toHaveLength(0);
  });

  it("stays off with half a setup and names what is missing, never a value", async () => {
    const half = startIntegrationHarness(db, {
      intervals,
      config: { GOOGLE_CLIENT_ID: FAKE_CLIENT_ID, GOOGLE_CLIENT_SECRET: FAKE_CLIENT_SECRET },
    });
    expect(half.services.calendar).toBeNull();
    const line = half.logging.lines.find((l) => l.message?.includes("Google Calendar is off"));
    expect(line?.fields).toEqual({ missing: ["GOOGLE_REFRESH_TOKEN", "GOOGLE_CALENDAR_ID"] });
    expect(half.logging.text()).not.toContain(FAKE_CLIENT_SECRET);
    expect(half.logging.text()).not.toContain(FAKE_CLIENT_ID);
  });

  it("does nothing without a plan source, and says so once", async () => {
    const noPlan = startIntegrationHarness(db, { google });
    expect(noPlan.services.calendar).not.toBeNull();
    await calendar(noPlan).runOnce();
    await calendar(noPlan).runOnce();
    expect(google.requests).toHaveLength(0);
    expect(noPlan.logging.lines.filter((l) => l.message?.includes("no plan source"))).toHaveLength(
      1,
    );
  });
});

describe("secrets", () => {
  it("never reach the log, whatever fails", async () => {
    plan("2026-11-19");
    plan("2026-11-20", "Endurance");
    await refresh();
    google.failNext(1, 403, { body: { error: { code: 403, message: "no" } } });
    await calendar().runOnce();
    google.failNext(4, 500);
    h.clock.advance(60 * MINUTE);
    await calendar().runOnce();
    google.revokeRefreshToken();
    h.clock.advance(7 * 60 * MINUTE);
    await calendar().runOnce();
    google.setUnreachable(true);
    google.revokeRefreshToken(false);
    h.clock.advance(7 * 60 * MINUTE);
    await calendar().runOnce();

    const log = h.logging.text();
    expect(log.length).toBeGreaterThan(100);
    for (const secret of [
      FAKE_CLIENT_SECRET,
      FAKE_REFRESH_TOKEN,
      "fake-access-token",
      FAKE_CLIENT_ID,
    ]) {
      expect(log).not.toContain(secret);
    }
    // The calendar id is not a secret but is not logged either: nothing in the log needs it.
    expect(log).not.toContain(FAKE_CALENDAR_ID);
    const stored = JSON.stringify(await rows());
    for (const secret of [FAKE_CLIENT_SECRET, FAKE_REFRESH_TOKEN, "fake-access-token"]) {
      expect(stored).not.toContain(secret);
    }
  });

  it("go only into the body of the token request, never into a URL", async () => {
    plan("2026-11-19");
    await refresh();
    await calendar().runOnce();
    const token = google.requestsTo("POST", "/token")[0];
    expect(token?.form).toMatchObject({
      grant_type: "refresh_token",
      client_id: FAKE_CLIENT_ID,
      client_secret: FAKE_CLIENT_SECRET,
      refresh_token: FAKE_REFRESH_TOKEN,
    });
    for (const request of google.requests) {
      expect(JSON.stringify(request.query)).not.toContain(FAKE_REFRESH_TOKEN);
      expect(request.path).not.toContain(FAKE_CLIENT_SECRET);
      if (request.path !== "/token")
        expect(request.authorization).toMatch(/^Bearer fake-access-token-/);
    }
  });
});
