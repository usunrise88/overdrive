import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type PlannedWorkout,
  type Step,
  VARIANT_NOTE_MAX_CHARS,
  validateWorkout,
  validateWorkoutsResponse,
  variantStructureHash,
  type Workout,
  type WorkoutsResponse,
  workoutHash,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, TABLET } from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/**
 * Routes on free steps (spec §11.4) and the easier variant (spec §11.5, ADR 0036) in the planning tools and in what the tablet reads:
 * a `route_id` and a variant live only in the Overdrive service, laid over the calendar's primary, and the calendar is never sent them.
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
    "TRUNCATE routes, ride_routes, planned_workout_extras, variant_rides, plan_events, plan_event_cursor, plan_history, ride_workouts",
  );
  fake.reset();
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

/** A route made through the tool, so the steps can name it. */
async function makeRoute(name = "Rolling hill"): Promise<string> {
  const result = await call("create_route", {
    mode: "profile",
    name,
    segments: [
      { lengthMeters: 1000, gradePercent: 4 },
      { lengthMeters: 1000, gradePercent: -4 },
    ],
  });
  expect(result.isError, result.text).toBe(false);
  return (result.json as { id: string }).id;
}

const SWEET_SPOT_STEPS: Step[] = [
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

/** The easier version: one effort at 80%. */
const EASIER_STEPS: Step[] = [
  { type: "ramp", duration: 600, from: 0.45, to: 0.65, label: "Warm-up" },
  { type: "steady", duration: 1200, target: 0.8, label: "Tempo" },
  { type: "free", duration: 300, label: "Cool-down" },
];

interface Stored {
  id: string;
  date: string;
  name: string;
  playable: true;
  workout: Workout;
  variant?: {
    note?: string;
    durationSeconds: number;
    estimatedTss: number;
    outline: string;
    structureHash: string;
    steps?: Step[];
  };
  warnings?: string[];
}

const create = async (args: Record<string, unknown>) => {
  const result = await call("create_workout", { date: "2026-11-20", ...args });
  return { result, stored: result.json as Stored };
};

/** What the tablet reads. */
async function tablet(from = "2026-11-18", to = "2026-11-24"): Promise<WorkoutsResponse> {
  const response = await h.app.inject({
    method: "GET",
    url: `/v1/workouts?from=${from}&to=${to}`,
    headers: bearer(TABLET),
  });
  expect(response.statusCode, response.body).toBe(200);
  return response.json() as WorkoutsResponse;
}

const extrasRow = async (id: string) =>
  (
    await db.query<{ route_ids: unknown; variant: unknown; plan_date: string }>(
      "SELECT route_ids, variant, plan_date::text AS plan_date FROM planned_workout_extras WHERE workout_id = $1",
      [id],
    )
  ).rows[0];

describe("a route on a free step", () => {
  it("is kept beside the calendar's primary and given to the tablet, and the calendar is never sent it", async () => {
    const routeId = await makeRoute();
    const steps = [
      { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
      { type: "free", duration: 3600, label: "The hills", route_id: routeId },
      { type: "free", duration: 300, label: "Flat cool-down" },
    ];
    const { result, stored } = await create({ workout: { name: "Hills", steps } });
    expect(result.isError, result.text).toBe(false);
    expect(stored.warnings).toBeUndefined();
    expect(stored.workout.steps[1]).toMatchObject({
      type: "free",
      duration: 3600,
      route_id: routeId,
    });
    expect(stored.workout.steps[2]).not.toHaveProperty("route_id");

    // The calendar got a text, with nothing of the route in it.
    expect(fake.events).toHaveLength(1);
    expect(JSON.stringify(fake.events[0])).not.toContain(routeId);
    expect(fake.events[0]?.description).toMatch(/freeride/);

    // The tablet's answer carries it and is valid by the contract.
    const body = await tablet();
    expect(validateWorkoutsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
    expect(body.workouts[0]?.workout.steps[1]).toMatchObject({ route_id: routeId });
    expect(validateWorkout(body.workouts[0]?.workout)).toMatchObject({ ok: true });
  });

  it("survives a refresh of the plan from the calendar, which cannot hold it", async () => {
    const routeId = await makeRoute();
    const { stored } = await create({
      workout: {
        name: "Hills",
        steps: [
          { type: "steady", duration: 600, target: 0.6 },
          { type: "free", duration: 3600, route_id: routeId },
        ],
      },
    });
    expect((await h.services.refresher?.refresh())?.ok).toBe(true);
    const body = await tablet();
    expect(body.workouts.map((w) => w.id)).toEqual([stored.id]);
    expect(body.workouts[0]?.workout.steps[1]).toMatchObject({ type: "free", route_id: routeId });
  });

  it("is kept on a free step inside a repeat, by position", async () => {
    const routeId = await makeRoute();
    await create({
      workout: {
        name: "Out and back",
        steps: [
          {
            type: "repeat",
            count: 2,
            steps: [
              { type: "free", duration: 900, route_id: routeId },
              { type: "steady", duration: 300, target: 0.5 },
            ],
          },
        ],
      },
    });
    const row = await extrasRow("icu-5001");
    expect(row?.route_ids).toEqual({ "0.0": routeId });
    const step = (await tablet()).workouts[0]?.workout.steps[0];
    expect(step).toMatchObject({ type: "repeat" });
    expect((step as { steps: Step[] }).steps[0]).toMatchObject({ type: "free", route_id: routeId });
  });

  it("is refused when the route does not exist, in plain words, and nothing is created", async () => {
    const { result } = await create({
      workout: {
        name: "Hills",
        steps: [{ type: "free", duration: 600, route_id: "route-nothere" }],
      },
    });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/the workout names a route that does not exist: route-nothere/);
    expect(result.text).toMatch(/list_routes shows the routes, create_route makes one/);
    expect(fake.events).toHaveLength(0);
    const two = await create({
      workout: {
        name: "Hills",
        steps: [
          { type: "free", duration: 600, route_id: "route-a" },
          { type: "free", duration: 600, route_id: "route-b" },
        ],
      },
    });
    expect(two.result.text).toMatch(/routes that do not exist: route-a, route-b/);
  });

  it("is refused on a step that is not free, and with an id that is not an id, by the contract's own validator", async () => {
    const onSteady = await create({
      workout: {
        name: "x",
        steps: [{ type: "steady", duration: 600, target: 0.5, route_id: "route-a" }],
      },
    });
    expect(onSteady.result.text).toMatch(/workout does not validate/);
    const badId = await create({
      workout: { name: "x", steps: [{ type: "free", duration: 600, route_id: "a/b" }] },
    });
    expect(badId.result.text).toMatch(/workout does not validate/);
  });

  it("is checked again by update_workout, kept when only the name or the day changes, and replaced with the steps", async () => {
    const hills = await makeRoute("Hills");
    const lake = await makeRoute("Lake");
    const { stored } = await create({
      workout: { name: "Ride", steps: [{ type: "free", duration: 3600, route_id: hills }] },
    });

    const missing = await call("update_workout", {
      id: stored.id,
      steps: [{ type: "free", duration: 3600, route_id: "route-nothere" }],
    });
    expect(missing.isError).toBe(true);
    expect(missing.text).toMatch(/the steps names a route that does not exist/);
    expect((await extrasRow(stored.id))?.route_ids).toEqual({ "0": hills });

    const renamed = (await call("update_workout", { id: stored.id, name: "Ride out" }))
      .json as Stored;
    expect(renamed.workout.steps[0]).toMatchObject({ route_id: hills });
    const moved = (await call("update_workout", { id: stored.id, date: "2026-11-21" }))
      .json as Stored;
    expect(moved.workout.steps[0]).toMatchObject({ route_id: hills });
    expect((await extrasRow(stored.id))?.plan_date).toBe("2026-11-21");

    const swapped = (
      await call("update_workout", {
        id: stored.id,
        steps: [{ type: "free", duration: 3600, route_id: lake }],
      })
    ).json as Stored;
    expect(swapped.workout.steps[0]).toMatchObject({ route_id: lake });
    expect((await tablet()).workouts[0]?.workout.steps[0]).toMatchObject({ route_id: lake });

    // New steps with no route drop it: the free step is the flat free ride again.
    const flat = (
      await call("update_workout", {
        id: stored.id,
        steps: [{ type: "free", duration: 1800 }],
      })
    ).json as Stored;
    expect(flat.workout.steps[0]).not.toHaveProperty("route_id");
    expect(await extrasRow(stored.id)).toBeUndefined();
  });

  it("is dropped when the rider changes the steps in the calendar, instead of landing on the wrong step", async () => {
    const routeId = await makeRoute();
    const { stored } = await create({
      workout: {
        name: "Ride",
        steps: [
          { type: "free", duration: 1800, route_id: routeId },
          { type: "steady", duration: 600, target: 0.6 },
        ],
      },
    });
    // The rider rewrites the description in intervals.icu: a steady step first.
    const event = fake.events[0];
    if (event === undefined) throw new Error("no event");
    event.description = "- 10m 60%\n- 30m freeride";
    delete event.workout_doc;
    expect((await h.services.refresher?.refresh())?.ok).toBe(true);
    const steps = (await tablet()).workouts[0]?.workout.steps;
    expect(steps?.map((s) => s.type)).toEqual(["steady", "free"]);
    expect(steps?.[1]).not.toHaveProperty("route_id");
    expect(stored.id).toBe("icu-5001");
  });

  it("is taken off the workouts that name it when the route is deleted, and the answer says which", async () => {
    const routeId = await makeRoute();
    const keep = await makeRoute("Another");
    const { stored } = await create({
      workout: {
        name: "Ride",
        steps: [
          { type: "free", duration: 1800, route_id: routeId },
          { type: "free", duration: 900, route_id: keep },
        ],
      },
      variant: [{ type: "free", duration: 1800, route_id: routeId }],
    });
    const deleted = await call("delete_route", { id: routeId });
    expect(deleted.json).toMatchObject({ deleted: true, workoutsChanged: [stored.id] });
    expect((deleted.json as { note: string }).note).toMatch(/flat free ride/);
    const body = await tablet();
    expect(body.workouts[0]?.workout.steps).toMatchObject([
      { type: "free", duration: 1800 },
      { type: "free", duration: 900, route_id: keep },
    ]);
    expect(body.workouts[0]?.workout.steps[0]).not.toHaveProperty("route_id");
    expect(body.workouts[0]?.variant?.steps[0]).not.toHaveProperty("route_id");
    expect(validateWorkoutsResponse(body)).toMatchObject({ ok: true });
  });

  it("is shown by list_planned_workouts in the full detail", async () => {
    const routeId = await makeRoute();
    await create({
      workout: { name: "Ride", steps: [{ type: "free", duration: 1800, route_id: routeId }] },
    });
    const listed = (
      await call("list_planned_workouts", { detail: "full", from: "2026-11-20", to: "2026-11-20" })
    ).json as {
      workouts: { workout: Workout }[];
    };
    expect(listed.workouts[0]?.workout.steps[0]).toMatchObject({ route_id: routeId });
  });
});

describe("the easier variant", () => {
  it("is stored only in the service: the calendar gets the primary, the tablet gets both", async () => {
    const { result, stored } = await create({
      workout: { name: "Sweet spot 2x20", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
      variantNote: "One effort at 80%, about 25 minutes shorter.",
    });
    expect(result.isError, result.text).toBe(false);
    expect(stored.warnings).toBeUndefined();
    // The tool says what the variant is, with numbers worked out by code: 600 + 1200 + 300 s, and the load of its steps.
    expect(stored.variant).toMatchObject({
      note: "One effort at 80%, about 25 minutes shorter.",
      durationSeconds: 2100,
      outline: "10m 45→65%, 20m 80%, 5m free",
    });
    // The calendar got one write, of the primary, and nothing of the variant.
    expect(fake.requestsTo("POST", "/events")).toHaveLength(1);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect(JSON.stringify(fake.events[0])).not.toMatch(/Tempo|80%|easier/i);

    const body = await tablet();
    expect(validateWorkoutsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
    const entry = body.workouts[0] as PlannedWorkout;
    expect(entry.workout.steps).toHaveLength(3);
    expect(entry.variant?.note).toBe("One effort at 80%, about 25 minutes shorter.");
    expect(entry.variant?.steps).toEqual(EASIER_STEPS);
    // Its own structure hash: the primary's workout with the variant's steps.
    expect(entry.variant?.structureHash).toBe(variantStructureHash(entry.workout, EASIER_STEPS));
    expect(entry.variant?.structureHash).not.toBe(workoutHash(entry.workout));
  });

  it("is not in the answer at all when there is none: the tablet's old shape is unchanged", async () => {
    await create({ workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS } });
    const body = await tablet();
    // Only the structure hash (ADR 0034) is added to the tablet's old shape: no `variant` key.
    expect(Object.keys(body.workouts[0] as object).sort()).toEqual([
      "date",
      "id",
      "name",
      "source",
      "structureHash",
      "workout",
    ]);
  });

  it("is shown by list_planned_workouts: its length, load and outline in the summary, its steps in the full detail", async () => {
    await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
      variantNote: "Shorter.",
    });
    const summary = (await call("list_planned_workouts", { from: "2026-11-20", to: "2026-11-20" }))
      .json as {
      workouts: Stored[];
    };
    const variant = summary.workouts[0]?.variant;
    expect(variant).toMatchObject({
      note: "Shorter.",
      durationSeconds: 2100,
      outline: "10m 45→65%, 20m 80%, 5m free",
    });
    // 600 s ramp 0.45 to 0.65 (mean square 0.3058) and 1200 s at 0.8: (600 * 0.3058 + 1200 * 0.64) / 36 = 26.4.
    expect(variant?.estimatedTss).toBe(26);
    expect(variant).not.toHaveProperty("steps");
    const full = (
      await call("list_planned_workouts", { detail: "full", from: "2026-11-20", to: "2026-11-20" })
    ).json as {
      workouts: Stored[];
    };
    expect(full.workouts[0]?.variant?.steps).toEqual(EASIER_STEPS);
  });

  it("can be given later, changed and removed without touching the calendar", async () => {
    const { stored } = await create({ workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS } });
    const puts = () => fake.requestsTo("PUT", `/events/${stored.id.slice(4)}`).length;

    const added = (
      await call("update_workout", {
        id: stored.id,
        variant: EASIER_STEPS,
        variantNote: "Shorter.",
      })
    ).json as Stored;
    expect(added.variant?.note).toBe("Shorter.");
    expect(puts()).toBe(0);
    expect((await tablet()).workouts[0]?.variant?.steps).toEqual(EASIER_STEPS);

    const changed = (
      await call("update_workout", {
        id: stored.id,
        variant: [{ type: "steady", duration: 1800, target: 0.6 }],
      })
    ).json as Stored;
    // A new variant with no note has none: the old note is not kept for other steps.
    expect(changed.variant).toMatchObject({ durationSeconds: 1800 });
    expect(changed.variant).not.toHaveProperty("note");
    expect(puts()).toBe(0);

    const removed = (await call("update_workout", { id: stored.id, removeVariant: true }))
      .json as Stored;
    expect(removed.variant).toBeUndefined();
    expect((await tablet()).workouts[0]).not.toHaveProperty("variant");
    expect(await extrasRow(stored.id)).toBeUndefined();
    expect(puts()).toBe(0);
  });

  it("is kept when only the steps change (the agent may send a new one), and when the same day is sent again", async () => {
    const { stored } = await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
    });
    const changed = (
      await call("update_workout", {
        id: stored.id,
        steps: [{ type: "steady", duration: 3600, target: 0.7 }],
      })
    ).json as Stored;
    expect(changed.variant?.steps).toEqual(EASIER_STEPS);
    const sameDay = (await call("update_workout", { id: stored.id, date: "2026-11-20" }))
      .json as Stored;
    expect(sameDay.variant).toBeDefined();
    expect(sameDay.warnings).toBeUndefined();
  });

  it("is dropped when the session is moved to another day, and said so; a new variant in the same call stays", async () => {
    const { stored } = await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
    });
    const moved = (await call("update_workout", { id: stored.id, date: "2026-11-22" }))
      .json as Stored;
    expect(moved.variant).toBeUndefined();
    expect(moved.warnings?.[0]).toMatch(/the session moved, so its easier version was dropped/);
    expect((await tablet()).workouts[0]).toMatchObject({ date: "2026-11-22" });
    expect((await tablet()).workouts[0]).not.toHaveProperty("variant");

    const again = (
      await call("update_workout", {
        id: stored.id,
        date: "2026-11-23",
        variant: EASIER_STEPS,
      })
    ).json as Stored;
    expect(again.variant?.steps).toEqual(EASIER_STEPS);
    expect(again.warnings).toBeUndefined();
  });

  it("is removed with the workout", async () => {
    const { stored } = await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
    });
    expect(await extrasRow(stored.id)).toBeDefined();
    expect((await call("delete_workout", { id: stored.id })).json).toMatchObject({ deleted: true });
    expect(await extrasRow(stored.id)).toBeUndefined();
    // And when the calendar already lost it.
    const second = await create({
      workout: { name: "Again", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
    });
    fake.events.length = 0;
    expect((await call("delete_workout", { id: second.stored.id })).json).toMatchObject({
      deleted: false,
    });
    expect(await extrasRow(second.stored.id)).toBeUndefined();
  });

  it("is dropped when the rider moves or deletes the session in the calendar itself", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "Sweet spot", [steady(1200, 90)]));
    fake.addEvent(plannedEvent("2026-11-21", "Endurance", [steady(3600, 60)]));
    await h.services.refresher?.refresh();
    expect(
      (
        await call("update_workout", {
          id: "icu-5001",
          variant: EASIER_STEPS,
          variantNote: "Shorter.",
        })
      ).isError,
    ).toBe(false);
    expect((await call("update_workout", { id: "icu-5002", variant: EASIER_STEPS })).isError).toBe(
      false,
    );
    const sixty = await call("create_route", {
      mode: "profile",
      name: "R",
      segments: [{ lengthMeters: 500, gradePercent: 1 }],
    });
    expect(sixty.isError).toBe(false);

    // The rider moves the first to the 22nd and deletes the second, in intervals.icu.
    const [first, second] = fake.events;
    if (first === undefined || second === undefined) throw new Error("no events");
    first.start_date_local = "2026-11-22T00:00:00";
    fake.events.splice(fake.events.indexOf(second), 1);
    expect((await h.services.refresher?.refresh())?.ok).toBe(true);

    expect(await extrasRow("icu-5001")).toBeUndefined();
    expect(await extrasRow("icu-5002")).toBeUndefined();
    const body = await tablet();
    expect(body.workouts.map((w) => [w.id, w.date, w.variant === undefined])).toEqual([
      ["icu-5001", "2026-11-22", true],
    ]);
  });

  it("goes a few days after its day has passed, and not before", async () => {
    const { stored } = await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      variant: EASIER_STEPS,
    });
    h.clock.set("2026-11-22T10:00:00Z"); // two days after the 20th
    expect(await h.services.variants.sweep()).toBe(0);
    expect(await extrasRow(stored.id)).toBeDefined();
    h.clock.set("2026-11-23T10:00:00Z"); // three days after
    expect(await h.services.variants.sweep()).toBe(1);
    expect(await extrasRow(stored.id)).toBeUndefined();
    // The workout itself is not the variant's to touch.
    expect(fake.events).toHaveLength(1);
  });
});

describe("what a bad variant is refused for", () => {
  const refused = async (args: Record<string, unknown>) => {
    const { result } = await create({
      workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS },
      ...args,
    });
    expect(result.isError, JSON.stringify(args)).toBe(true);
    // Nothing was written to the calendar.
    expect(fake.events).toHaveLength(0);
    return result.text;
  };

  it("steps that do not validate, with the path of the mistake", async () => {
    expect(await refused({ variant: [{ type: "steady", duration: 0, target: 0.5 }] })).toMatch(
      /variant does not validate: .*\/steps\/0/,
    );
    expect(await refused({ variant: [] })).toMatch(/variant does not validate/);
    expect(await refused({ variant: [{ type: "interval", duration: 600 }] })).toMatch(
      /variant does not validate/,
    );
  });

  it("a route that does not exist", async () => {
    expect(
      await refused({ variant: [{ type: "free", duration: 600, route_id: "route-nothere" }] }),
    ).toMatch(/variant names a route that does not exist: route-nothere/);
  });

  it("a note with no variant, a blank note and a note that is too long", async () => {
    expect(await refused({ variantNote: "Shorter." })).toMatch(/variantNote goes with a variant/);
    expect(await refused({ variant: EASIER_STEPS, variantNote: "   " })).toMatch(
      /variantNote must not be blank/,
    );
    expect(
      await refused({ variant: EASIER_STEPS, variantNote: "x".repeat(VARIANT_NOTE_MAX_CHARS + 1) }),
    ).toMatch(
      new RegExp(
        `variantNote is ${VARIANT_NOTE_MAX_CHARS + 1} characters; at most ${VARIANT_NOTE_MAX_CHARS}`,
      ),
    );
  });

  it("update_workout: both a variant and removeVariant, and nothing to change", async () => {
    const { stored } = await create({ workout: { name: "Sweet spot", steps: SWEET_SPOT_STEPS } });
    expect(
      (await call("update_workout", { id: stored.id, variant: EASIER_STEPS, removeVariant: true }))
        .text,
    ).toMatch(/a variant or removeVariant, not both/);
    expect((await call("update_workout", { id: stored.id })).text).toMatch(
      /nothing to change: give a name, a date, steps or a variant/,
    );
    expect((await call("update_workout", { id: stored.id, variantNote: "Shorter." })).text).toMatch(
      /variantNote goes with a variant/,
    );
    expect(await extrasRow(stored.id)).toBeUndefined();
  });
});

describe("what the agent is told", () => {
  it("the tools say that the easier version lives only in the service and that a route backs a free step", async () => {
    const { tools } = await client.listTools();
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    for (const name of ["create_workout", "update_workout"]) {
      const description = by[name]?.description ?? "";
      expect(description, name).toMatch(/kept only by this server|lives only in this server/);
      expect(description, name).toMatch(/variant/);
    }
    expect(by.create_workout?.description).toMatch(/route_id/);
    expect(by.create_workout?.description).toMatch(/a route that does not exist is refused/);
    expect(by.update_workout?.description).toMatch(
      /MOVING a session to another day drops its easier version/,
    );
    expect(by.list_planned_workouts?.description).toMatch(/easier version/);
    const properties = (by.create_workout?.inputSchema.properties ?? {}) as Record<string, unknown>;
    expect(Object.keys(properties).sort()).toEqual(["date", "variant", "variantNote", "workout"]);
    // The variant's steps are advertised with the contract's own step schema, route_id included.
    expect(JSON.stringify(properties.variant)).toContain("route_id");
  });

  it("the server instructions mention routes and easier versions", async () => {
    const instructions = client.getInstructions() ?? "";
    expect(instructions).toMatch(/ROUTES: create_route/);
    expect(instructions).toMatch(/EASIER VERSIONS/);
    expect(instructions).toMatch(/list_plan_events shows a variant event/);
  });
});
