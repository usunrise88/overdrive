import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type RideRecord,
  type Step,
  validatePlanEvent,
  variantStructureHash,
  variantWorkout,
  type Workout,
  workoutHash,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import type { RideFacts } from "../src/insights/facts.ts";
import { templateText } from "../src/insights/template.ts";
import { renderWorkoutText } from "../src/intervals/workout-text.ts";
import { eveningReminderText, leadReminderText } from "../src/telegram/messages.ts";
import {
  bearer,
  expectError,
  expectUpload,
  fitFixture,
  putFit,
  putRecord,
  TABLET,
} from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * Riding the easier variant (spec §11.5, ADR 0036): the ride says so (`ride.variantRidden`), the service changes the intervals.icu event
 * to what was ridden (planned and completed line up), makes a plan event for the coach agent and tells the ride note. Not ridden:
 * nothing happens. The calendar is the fake of `helpers/fake-intervals.ts`; the rides are synthetic.
 */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "coach", token: STATIC_TOKEN }] });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearPlan(db);
  await clearRides(db);
  await db.exec(
    "TRUNCATE routes, ride_routes, planned_workout_extras, variant_rides, plan_events, plan_event_cursor, plan_history, ride_workouts, ride_metrics, ride_repeat_stats",
  );
  fake.reset();
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const PRIMARY_STEPS: Step[] = [
  { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
  {
    type: "repeat",
    count: 2,
    steps: [
      { type: "steady", duration: 1200, target: 0.9, label: "Sweet spot" },
      { type: "steady", duration: 300, target: 0.5, label: "Recover" },
    ],
  },
  { type: "free", duration: 300, label: "Cool-down" },
];
const EASIER_STEPS: Step[] = [
  { type: "ramp", duration: 600, from: 0.45, to: 0.65, label: "Warm-up" },
  { type: "steady", duration: 1200, target: 0.8, label: "Tempo" },
  { type: "free", duration: 300, label: "Cool-down" },
];
const NOTE = "One effort at 80%, shorter.";
const NAME = "Sweet spot 2x20";
const ID = "icu-5001";
const DAY = "2026-11-20";

/** The planned session with its easier version, made through the tool as the agent would. */
async function plan(): Promise<void> {
  const result = await call("create_workout", {
    date: DAY,
    workout: { name: NAME, steps: PRIMARY_STEPS },
    variant: EASIER_STEPS,
    variantNote: NOTE,
  });
  expect(result.isError, result.text).toBe(false);
}

/** A short synthetic ride on the 20th at 17:00 UTC played from the planned workout. */
function ride(id: string, extra: Partial<RideRecord["ride"]> = {}): RideRecord {
  const record = syntheticRide({
    id,
    startEpochMillis: Date.parse("2026-11-20T17:00:00Z"),
    seconds: 90,
    workoutId: ID,
    heartRate: () => 130,
  });
  Object.assign(record.ride, extra);
  return record;
}

/** Stores the ride as the intake would and tells the service, which then does its work after the answer. */
async function stored(record: RideRecord): Promise<void> {
  await insertRide(db, record);
  h.services.bus.emit("ride-stored", {
    rideId: record.ride.id,
    startEpochMillis: record.ride.startEpochMillis,
  });
}

const queueRow = async (rideId: string) =>
  (
    await db.query<{
      state: string;
      attempts: number;
      last_error: string | null;
      next_attempt_at: Date | null;
    }>(
      "SELECT state, attempts, last_error, next_attempt_at FROM variant_rides WHERE ride_id = $1",
      [rideId],
    )
  ).rows[0];

const settled = (rideId: string, state: string) =>
  vi.waitFor(async () => expect((await queueRow(rideId))?.state).toBe(state));

/** The ride-stored handler has finished with a ride that names a route: the route's row is the last thing it writes. */
const handled = (rideId: string) =>
  vi.waitFor(async () => expect(await h.services.routes.rideRoute(rideId)).not.toBeNull());

const variantEvents = () =>
  h.services.planEvents.list({ status: "all", kind: "variant", limit: 10 });

/** What the calendar entry should say once the variant has been ridden. */
function expectedText(note: string | undefined = NOTE): string {
  const primary: Workout = { schema: 1, id: ID, name: NAME, steps: PRIMARY_STEPS };
  return renderWorkoutText({
    ...variantWorkout(primary, { steps: EASIER_STEPS }),
    description: `Easier version ridden${note === undefined ? "" : `: ${note}`}.`,
  });
}

describe("the variant was ridden", () => {
  it("changes the calendar entry to what was ridden, once, and makes a plan event for the agent", async () => {
    await plan();
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");

    // The entry now says the variant's steps, with a line that says why, and nothing else of it changed.
    const puts = fake.requestsTo("PUT", "/events/5001");
    expect(puts).toHaveLength(1);
    expect(puts[0]?.json).toEqual({ description: expectedText() });
    const text = String(fake.events[0]?.description);
    expect(text).toMatch(/^Easier version ridden: One effort at 80%, shorter\./);
    expect(text).toMatch(/- Tempo 20m 80%/);
    expect(text).not.toMatch(/90%/);
    expect(fake.events[0]?.name).toBe(NAME);
    expect(fake.events[0]?.start_date_local).toBe("2026-11-20T00:00:00");

    // The plan event for the coach agent: valid by the contract, about this session and this ride.
    const { events, total } = await variantEvents();
    expect(total).toBe(1);
    expect(events[0]).toMatchObject({
      kind: "variant",
      day: DAY,
      workoutId: ID,
      detail: { name: NAME, rideId: "ride-1" },
      status: "open",
    });
    expect(validatePlanEvent(events[0])).toMatchObject({ ok: true });
    expect((await call("list_plan_events", { kind: "variant" })).json).toMatchObject({
      total: 1,
      events: [{ summary: expect.stringContaining(NAME) }],
    });

    // The ride's copy of its workout is the variant, so planned and ridden line up everywhere.
    const copy = await h.services.rides.workoutSnapshot("ride-1");
    expect(copy?.steps).toEqual(EASIER_STEPS);
    expect(copy?.name).toBe(NAME);
    expect(workoutHash(copy as Workout)).toBe(
      variantStructureHash({ schema: 1, id: ID, name: NAME, steps: PRIMARY_STEPS }, EASIER_STEPS),
    );

    // The variant is consumed; the cache says what the calendar says.
    const planned = await h.services.plan.get(ID);
    expect(planned?.variant).toBeUndefined();
    expect(planned?.workout.steps.map((s) => s.type)).toEqual(["ramp", "steady", "free"]);
    expect(h.logging.text()).not.toContain("130");
  });

  it("is idempotent: the same ride told again changes nothing and makes no second event", async () => {
    await plan();
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    h.services.bus.emit("ride-stored", { rideId: "ride-1", startEpochMillis: 1 });
    await h.services.variants.idle();
    await h.services.variants.onRideStored({
      id: "ride-1",
      workoutId: ID,
      variantRidden: true,
      startEpochMillis: Date.parse("2026-11-20T17:00:00Z"),
    });
    await h.services.variants.runOnce();
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(1);
    expect((await variantEvents()).total).toBe(1);
    expect((await db.query("SELECT 1 FROM variant_rides")).rows).toHaveLength(1);
  });

  it("leaves the event alone when it already says what was ridden", async () => {
    await plan();
    const event = fake.events[0];
    if (event === undefined) throw new Error("no event");
    event.description = expectedText();
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect((await variantEvents()).total).toBe(1);
  });

  it("is done when the calendar entry is gone, with nothing written", async () => {
    await plan();
    fake.events.length = 0;
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect((await variantEvents()).total).toBe(1);
  });

  it("is tried again when intervals.icu cannot be reached, with a growing wait, and then done", async () => {
    await plan();
    fake.failNext(4, 503, { path: "/events/5001" });
    await stored(ride("ride-1", { variantRidden: true }));
    // The row is pending from the start: wait for the first try to have been made and recorded.
    await vi.waitFor(async () => expect((await queueRow("ride-1"))?.attempts).toBe(1));
    const first = await queueRow("ride-1");
    expect(first).toMatchObject({ state: "pending", attempts: 1 });
    expect(first?.last_error).toMatch(/intervals\.icu/);
    expect(first?.next_attempt_at?.getTime()).toBe(Date.parse("2026-11-18T08:01:00Z"));
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    // The plan event does not wait for the calendar.
    expect((await variantEvents()).total).toBe(1);

    // Not yet due: nothing is tried.
    expect(await h.services.variants.runOnce()).toMatchObject({ claimed: 0 });
    h.clock.advance(61_000);
    expect(await h.services.variants.runOnce()).toMatchObject({ claimed: 1, done: 1 });
    expect(await queueRow("ride-1")).toMatchObject({
      state: "done",
      attempts: 2,
      last_error: null,
    });
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(1);
  });

  it("gives up on a refusal that will not change, and says so in the row", async () => {
    await plan();
    fake.failNext(1, 422, { path: "/events/5001", body: '{"error":"bad"}' });
    // The GET is refused (a 422 is not retried): the row fails for good.
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "failed");
    expect((await queueRow("ride-1"))?.last_error).toMatch(/nothing was changed|rejected|422|bad/i);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
  });

  it("uses the variant that was kept for the day when the day has left the plan cache (a late upload)", async () => {
    await plan();
    await clearPlan(db);
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    expect((await h.services.rides.workoutSnapshot("ride-1"))?.steps).toEqual(EASIER_STEPS);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(1);
    // The event says the plan day, taken from what was kept.
    expect((await variantEvents()).events[0]?.day).toBe(DAY);
  });

  it("does not claim an easier version it no longer has: the primary is the ride's copy and the calendar is left alone", async () => {
    await plan();
    await db.exec("TRUNCATE planned_workout_extras");
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    expect((await h.services.rides.workoutSnapshot("ride-1"))?.steps.map((s) => s.type)).toEqual([
      "ramp",
      "repeat",
      "free",
    ]);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect(h.logging.lines.some((l) => /no longer has/.test(l.message ?? ""))).toBe(true);
    // The agent is still told the rider chose the easier version.
    expect((await variantEvents()).total).toBe(1);
  });

  it("is a fact the ride note and the agent read, and the ride's own record shows", async () => {
    await plan();
    await stored(ride("ride-1", { variantRidden: true }));
    await settled("ride-1", "done");
    const facts = (await call("get_facts", { kind: "ride", rideId: "ride-1" })).json as {
      facts: { plan: { workoutName: string; easierVersionRidden?: boolean } };
    };
    expect(facts.facts.plan.workoutName).toBe(NAME);
    expect(facts.facts.plan.easierVersionRidden).toBe(true);
    const got = (await call("get_ride", { rideId: "ride-1" })).json as {
      variantRidden?: boolean;
      workoutId: string;
      compliance: { available: boolean };
    };
    expect(got.variantRidden).toBe(true);
    expect(got.workoutId).toBe(ID);
    expect(got.compliance.available).toBe(false);
    // The note written from a template (no model is configured) says so, in the plan's line.
    const answer = await h.services.insights.facts({ kind: "ride", rideId: "ride-1" });
    expect(templateText(answer?.facts as RideFacts)).toMatch(
      /Plan: Sweet spot 2x20, 35 min planned.* You chose the easier version of this session, so it is compared with that version\./,
    );
  });
});

describe("the variant was not ridden", () => {
  it("nothing happens: no event, no change to the calendar, no queue row, and no fact", async () => {
    await plan();
    await stored(ride("ride-1", { routeId: "route-x", routeName: "X" }));
    // The route's row is the last thing the ride-stored handler writes: when it is there the handler has finished.
    await handled("ride-1");
    expect((await h.services.rides.workoutSnapshot("ride-1"))?.steps).toEqual(
      (await h.services.plan.get(ID))?.workout.steps,
    );
    expect((await h.services.rides.workoutSnapshot("ride-1"))?.steps.map((s) => s.type)).toEqual([
      "ramp",
      "repeat",
      "free",
    ]);
    expect((await db.query("SELECT 1 FROM variant_rides")).rows).toHaveLength(0);
    expect((await variantEvents()).total).toBe(0);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    // The variant is still offered for the day.
    expect((await h.services.plan.get(ID))?.variant?.steps).toEqual(EASIER_STEPS);
    const facts = (await call("get_facts", { kind: "ride", rideId: "ride-1" })).json as {
      facts: { plan: Record<string, unknown> };
    };
    expect(facts.facts.plan).not.toHaveProperty("easierVersionRidden");
    expect((await call("get_ride", { rideId: "ride-1" })).json).not.toHaveProperty("variantRidden");
  });

  it("a record that says false is the same as one that does not say", async () => {
    await plan();
    await stored(ride("ride-1", { variantRidden: false, routeId: "route-x", routeName: "X" }));
    await handled("ride-1");
    expect((await db.query("SELECT 1 FROM variant_rides")).rows).toHaveLength(0);
    expect((await variantEvents()).total).toBe(0);
  });

  it("the day passes and the variant goes without a trace in the calendar or the agent's queue", async () => {
    await plan();
    h.clock.set("2026-11-23T10:00:00Z");
    await h.services.variants.sweep();
    expect((await db.query("SELECT 1 FROM planned_workout_extras")).rows).toHaveLength(0);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect((await variantEvents()).total).toBe(0);
  });
});

describe("what the rider is told on Telegram", () => {
  const view = {
    id: ID,
    date: DAY,
    name: NAME,
    workout: { schema: 1, id: ID, name: NAME, steps: PRIMARY_STEPS } as Workout,
    plannedSeconds: 3900,
  };
  const start = { hour: 7, minute: 0 };

  it("says in one short line that an easier version is ready on the tablet, only when there is one", () => {
    const plain = eveningReminderText(view, start, "2026-11-19");
    const withVariant = eveningReminderText(
      { ...view, hasEasierVersion: true },
      start,
      "2026-11-19",
    );
    expect(withVariant).toBe(`${plain}\nAn easier version is ready on the tablet.`);
    expect(plain).not.toMatch(/easier/);
    expect(leadReminderText({ ...view, hasEasierVersion: true }, start, 58)).toMatch(
      /\nAn easier version is ready on the tablet\.$/,
    );
    expect(leadReminderText(view, start, 58)).not.toMatch(/easier/);
    // A workout the tablet cannot play has no version to offer.
    expect(
      eveningReminderText({ ...view, workout: null, hasEasierVersion: true }, start, "2026-11-19"),
    ).toMatch(/cannot play this workout\.$/);
  });
});

describe("through the ride intake", () => {
  const put = (record: unknown, id: string) => putRecord(h.app, id, record, bearer(TABLET));

  it("takes a record that says it followed a route and played the variant, and the second PUT sets the whole thing going", async () => {
    await plan();
    const record = ride("ride-77", {
      variantRidden: true,
      routeId: "route-gone-0001",
      routeName: "A route that is gone",
    });
    expect((await put(record, "ride-77")).statusCode).toBe(201);
    // The record is stored as it came, with the new fields.
    expect((await h.services.rides.getRecord("ride-77"))?.ride).toMatchObject({
      variantRidden: true,
      routeId: "route-gone-0001",
      routeName: "A route that is gone",
    });
    expectUpload(
      await putFit(h.app, "ride-77", fitFixture("minimal.fit"), bearer(TABLET)),
      "ride-77",
      "stored",
    );
    await settled("ride-77", "done");
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(1);
    expect((await variantEvents()).events[0]?.detail).toMatchObject({ rideId: "ride-77" });
    // The route is gone, so the ride is keyed by the id alone.
    await vi.waitFor(async () =>
      expect(await h.services.routes.rideRoute("ride-77")).toMatchObject({
        routeKey: "route-gone-0001:deleted",
        routeName: "A route that is gone",
      }),
    );
  });

  it("refuses a route name with no route and a variant with no workout, as an invalid record", async () => {
    const noRoute = ride("ride-1", { routeName: "North road" });
    expectError(await put(noRoute, "ride-1"), "invalid_record");
    const noWorkout = ride("ride-2", { workoutId: null, variantRidden: true });
    const error = expectError(await put(noWorkout, "ride-2"), "invalid_record");
    expect(error.message).toMatch(/variantRidden needs/);
    const badId = ride("ride-3", { routeId: "a/b" });
    expectError(await put(badId, "ride-3"), "invalid_record");
    expect((await db.query("SELECT 1 FROM rides")).rows).toHaveLength(0);
  });
});
