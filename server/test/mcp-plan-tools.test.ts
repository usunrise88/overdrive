import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { validateWorkout, type Workout } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import type { PlanChangedEvent } from "../src/events.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;
let changes: PlanChangedEvent[];

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "test", token: STATIC_TOKEN }] });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
  h.services.bus.on("plan-changed", (event) => {
    changes.push(event);
  });
});

afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearPlan(db);
  fake.reset();
  changes = [];
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

interface Listed {
  from: string;
  to: string;
  source: string;
  generatedAt: string | null;
  stale: boolean;
  totals: { workouts: number; durationSeconds: number; estimatedTss: number };
  workouts: {
    id: string;
    date: string;
    name: string;
    durationSeconds: number;
    estimatedTss: number;
    freeSeconds?: number;
    outline: string;
    workout?: Workout;
  }[];
  unsupported?: { id: string; date: string; name: string; reason: string }[];
}

/** Plans events in the fake calendar and has the server read them into its cache. */
async function planned(...events: Parameters<FakeIntervals["addEvent"]>[0][]): Promise<void> {
  for (const event of events) fake.addEvent(event);
  const result = await h.services.refresher?.refresh();
  expect(result?.ok).toBe(true);
  changes = [];
}

const sweetSpot = (date: string, name = "Sweet spot") =>
  plannedEvent(date, name, [
    { duration: 600, power: { start: 45, end: 70, units: "%ftp" }, ramp: true, text: "Warm-up" },
    {
      reps: 2,
      steps: [steady(1200, 90, "Sweet spot"), steady(300, 50, "Recover")],
    },
    { duration: 300, freeride: true, text: "Cool-down" },
  ]);

const NEW_WORKOUT = {
  name: "Over-unders",
  description: "Three rounds of two minutes over and two under.",
  steps: [
    { type: "ramp", duration: 600, from: 0.5, to: 0.75, label: "Warm-up" },
    {
      type: "repeat",
      count: 3,
      steps: [
        { type: "steady", duration: 120, target: 1.05, cadence: 90 },
        { type: "steady", duration: 120, target: 0.9, label: "Under" },
      ],
    },
    { type: "free", duration: 300, label: "Cool-down" },
  ],
};

describe("the tools and what they say about themselves", () => {
  it("lists the tools, each described with its units and its limits", async () => {
    const { tools } = await client.listTools();
    expect(tools.map((t) => t.name).sort()).toEqual([
      "append_notebook_log",
      "create_route",
      "create_workout",
      "delete_route",
      "delete_workout",
      "get_activity",
      "get_facts",
      "get_fitness",
      "get_notebook",
      "get_profile",
      "get_recovery",
      "get_repeat",
      "get_ride",
      "get_wellness",
      "list_activities",
      "list_discussion_topics",
      "list_insights",
      "list_notebook_versions",
      "list_plan_events",
      "list_planned_workouts",
      "list_repeats",
      "list_rides",
      "list_routes",
      "mark_discussion_topic",
      "resolve_plan_event",
      "rollback_notebook_section",
      "update_notebook_section",
      "update_profile",
      "update_workout",
    ]);
    for (const tool of tools) {
      expect(tool.description?.length, tool.name).toBeGreaterThan(200);
      expect(tool.inputSchema.type).toBe("object");
      expect(tool.title).toBeTruthy();
    }
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    // Reads and writes are marked, and the delete says it destroys.
    for (const name of [
      "get_profile",
      "list_planned_workouts",
      "list_rides",
      "get_ride",
      "list_activities",
      "get_activity",
      "get_wellness",
      "get_recovery",
      "get_fitness",
      "get_facts",
      "list_insights",
      "list_discussion_topics",
      "list_repeats",
      "get_repeat",
    ]) {
      expect(by[name]?.annotations?.readOnlyHint, name).toBe(true);
    }
    // Marking a topic changes only its status: a write, and not a destructive one.
    expect(by.mark_discussion_topic?.annotations?.readOnlyHint).toBe(false);
    expect(by.mark_discussion_topic?.annotations?.destructiveHint).toBe(false);
    // The ride tools stay for one release and say they are replaced.
    expect(by.list_rides?.description).toMatch(/^DEPRECATED.*list_activities/);
    expect(by.get_ride?.description).toMatch(/^DEPRECATED.*get_activity/);
    for (const name of ["create_workout", "update_workout", "update_profile", "delete_workout"]) {
      expect(by[name]?.annotations?.readOnlyHint, name).toBe(false);
    }
    expect(by.delete_workout?.annotations?.destructiveHint).toBe(true);
    expect(by.create_workout?.annotations?.destructiveHint).toBe(false);
    expect(by.get_ride?.description).toMatch(/seconds in each of the seven power zones/);
    expect(by.create_workout?.description).toMatch(/FRACTION OF FTP/);
    expect(by.list_planned_workouts?.description).toMatch(/62 days/);
  });

  it("advertises the contract's workout schema to the model, and not an id it must make up", async () => {
    const { tools } = await client.listTools();
    const create = tools.find((t) => t.name === "create_workout");
    if (create === undefined) throw new Error("create_workout is not listed");
    const properties = create.inputSchema.properties as Record<string, unknown>;
    const workout = properties.workout as {
      required: string[];
      properties: { steps: { items: { oneOf: { properties: { type: { const: string } } }[] } } };
    };
    expect(workout.required).toEqual(["name", "steps"]);
    expect(workout.properties.steps.items.oneOf.map((s) => s.properties.type.const)).toEqual([
      "steady",
      "ramp",
      "free",
      "hr",
      "repeat",
    ]);
    expect(create?.inputSchema.required).toEqual(["date", "workout"]);
    const update = tools.find((t) => t.name === "update_workout");
    expect(update?.inputSchema.required).toEqual(["id"]);
  });

  it("tells the model how to use the server", () => {
    const instructions = client.getInstructions();
    expect(instructions).toMatch(/fraction of the rider's FTP/);
    expect(instructions).toMatch(/computed by the server/);
  });
});

describe("with no plan source", () => {
  let bare: McpHarness;
  let bareClient: Client;

  beforeAll(async () => {
    bare = await startMcpHarness(db, fake, {
      configured: false,
      staticTokens: [{ name: "test", token: STATIC_TOKEN }],
    });
    bareClient = await connectWithToken(bare.mcpUrl, STATIC_TOKEN);
  });
  afterAll(async () => {
    await bareClient.close();
    await bare.close();
  });

  it.each([
    ["list_planned_workouts", {}],
    ["create_workout", { date: "2026-11-20", workout: NEW_WORKOUT }],
    ["update_workout", { id: "icu-1", name: "x" }],
    ["delete_workout", { id: "icu-1" }],
  ])("%s says the plan source is not configured, in plain words", async (name, args) => {
    const result = await callTool(bareClient, name, args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/^plan source not configured: .*INTERVALS_API_KEY/);
  });

  it("leaves the profile and the rides alone", async () => {
    expect((await callTool(bareClient, "get_profile")).isError).toBe(false);
    expect((await callTool(bareClient, "list_rides")).isError).toBe(false);
    expect(fake.requests).toHaveLength(0);
  });
});

describe("list_planned_workouts", () => {
  it("lists the next days from the cache, compactly, with numbers worked out by the server", async () => {
    await planned(sweetSpot("2026-11-19"), sweetSpot("2026-11-21", "Sweet spot again"));
    const { json, isError } = await call("list_planned_workouts", { refresh: true });
    expect(isError).toBe(false);
    const listed = json as Listed;
    expect(listed).toMatchObject({
      from: "2026-11-18",
      to: "2026-12-01",
      source: "cache",
      stale: false,
    });
    expect(listed.generatedAt).toBe("2026-11-18T08:00:00.000Z");
    expect(listed.workouts.map((w) => [w.id, w.date, w.name])).toEqual([
      ["icu-5001", "2026-11-19", "Sweet spot"],
      ["icu-5002", "2026-11-21", "Sweet spot again"],
    ]);
    const first = listed.workouts[0];
    // 10 + 2 x (20 + 5) + 5 minutes
    expect(first?.durationSeconds).toBe(600 + 2 * 1500 + 300);
    expect(first?.freeSeconds).toBe(300);
    expect(first?.outline).toBe("10m 45→70%, 2×(20m 90% + 5m 50%), 5m free");
    // 2 x (1200 s at 0.9^2 + 300 s at 0.5^2) / 36 plus the ramp's mean square for 600 s
    const expectedTss = Math.round(
      (2 * (1200 * 0.81 + 300 * 0.25) + 600 * ((0.45 ** 2 + 0.45 * 0.7 + 0.7 ** 2) / 3)) / 36,
    );
    expect(first?.estimatedTss).toBe(expectedTss);
    expect(first && "workout" in first).toBe(false);
    expect(listed.totals).toEqual({
      workouts: 2,
      durationSeconds: 2 * (600 + 2 * 1500 + 300),
      estimatedTss: 2 * expectedTss,
    });
    expect(listed.unsupported).toBeUndefined();
  });

  it("gives the steps in the native format when asked for the full detail", async () => {
    await planned(sweetSpot("2026-11-19"));
    const listed = (await call("list_planned_workouts", { detail: "full" })).json as Listed;
    const workout = listed.workouts[0]?.workout;
    expect(validateWorkout(workout)).toMatchObject({ ok: true });
    expect(workout?.id).toBe("icu-5001");
    expect(workout?.steps).toHaveLength(3);
  });

  it("says which workouts the tablet cannot play, and why", async () => {
    await planned(
      sweetSpot("2026-11-19"),
      plannedEvent("2026-11-20", "By distance", [
        { distance: 20000, power: { value: 80, units: "%ftp" } },
      ]),
    );
    const listed = (await call("list_planned_workouts")).json as Listed;
    expect(listed.workouts.map((w) => w.name)).toEqual(["Sweet spot"]);
    expect(listed.unsupported).toEqual([
      {
        id: "icu-5002",
        date: "2026-11-20",
        name: "By distance",
        playable: false,
        reason: expect.stringMatching(/distance/),
      },
    ]);
  });

  it("asks the calendar for a range the cache does not reach, and keeps nothing", async () => {
    fake.addEvent(sweetSpot("2026-12-20", "Far off"));
    fake.addEvent({ ...sweetSpot("2026-12-21", "A note"), category: "NOTE" });
    fake.addEvent({ ...sweetSpot("2026-12-22", "A run"), type: "Run" });
    fake.addEvent(sweetSpot("2027-01-30", "Beyond the range"));
    const listed = (await call("list_planned_workouts", { from: "2026-12-15", to: "2026-12-31" }))
      .json as Listed;
    expect(listed).toMatchObject({ source: "intervals.icu", stale: false });
    expect(listed.workouts.map((w) => w.name)).toEqual(["Far off"]);
    expect(await h.services.plan.entries({ from: "2026-12-01", to: "2027-02-28" })).toEqual([]);
    expect(fake.requestsTo("GET", "/events")).toHaveLength(1);
  });

  it("also reaches back to days that have passed", async () => {
    fake.addEvent(sweetSpot("2026-11-10", "Last week"));
    const listed = (await call("list_planned_workouts", { from: "2026-11-09", to: "2026-11-11" }))
      .json as Listed;
    expect(listed.source).toBe("intervals.icu");
    expect(listed.workouts.map((w) => w.name)).toEqual(["Last week"]);
  });

  it("reads the calendar first on request, and says what it found", async () => {
    fake.addEvent(sweetSpot("2026-11-19"));
    // Nothing is cached yet; refresh brings it in.
    expect(((await call("list_planned_workouts")).json as Listed).workouts).toHaveLength(0);
    expect(
      ((await call("list_planned_workouts", { refresh: true })).json as Listed).workouts,
    ).toHaveLength(1);
  });

  it("says the cache is stale when the last refresh failed, and is empty and not fresh before any", async () => {
    const before = (await call("list_planned_workouts")).json as Listed;
    expect(before).toMatchObject({ stale: true, generatedAt: null });
    await planned(sweetSpot("2026-11-19"));
    fake.failAll(500);
    const failed = (await call("list_planned_workouts", { refresh: true })).json as Listed;
    expect(failed.stale).toBe(true);
    expect(failed.workouts).toHaveLength(1);
    expect(failed.generatedAt).toBe("2026-11-18T08:00:00.000Z");
  });

  it("uses the rider's today, not the server's", async () => {
    // 23:30 UTC on the 18th is already the 19th in Paris.
    h.clock.set("2026-11-18T23:30:00Z");
    const listed = (await call("list_planned_workouts")).json as Listed;
    expect(listed).toMatchObject({ from: "2026-11-19", to: "2026-12-02" });
  });

  it.each([
    [
      "a range that runs backwards",
      { from: "2026-11-20", to: "2026-11-19" },
      /to 2026-11-19 is before from 2026-11-20/,
    ],
    ["more than 62 days", { from: "2026-11-01", to: "2027-01-02" }, /spans 63 days; at most 62/],
    ["a date that does not exist", { from: "2026-02-30" }, /not a real calendar date/],
    ["a date in the wrong form", { from: "18/11/2026" }, /YYYY-MM-DD/],
    ["a detail it does not have", { detail: "everything" }, /detail/],
  ])("refuses %s in plain words", async (_what, args, message) => {
    const result = await call("list_planned_workouts", args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
  });

  it("tells the owner what to do when intervals.icu cannot be reached for a far range", async () => {
    fake.setUnreachable(true);
    const result = await call("list_planned_workouts", { from: "2026-12-15", to: "2026-12-31" });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/intervals\.icu did not answer.*try again later/);
  });
});

describe("create_workout", () => {
  it("writes the workout to the calendar, stores it and returns it as stored", async () => {
    const { json, isError, text } = await call("create_workout", {
      date: "2026-11-20",
      workout: NEW_WORKOUT,
    });
    expect(isError, text).toBe(false);
    const stored = json as Listed["workouts"][number] & { workout: Workout };
    expect(stored).toMatchObject({
      id: "icu-5001",
      date: "2026-11-20",
      name: "Over-unders",
      playable: true,
    });
    expect(stored.durationSeconds).toBe(600 + 3 * 240 + 300);
    expect(stored.workout.id).toBe("icu-5001");
    expect(stored.workout.steps).toEqual(NEW_WORKOUT.steps);
    expect(validateWorkout(stored.workout)).toMatchObject({ ok: true });
    expect("warnings" in stored).toBe(false);

    // The calendar got the event, in intervals.icu's own text.
    const event = fake.events[0];
    expect(event).toMatchObject({
      id: 5001,
      category: "WORKOUT",
      type: "Ride",
      indoor: true,
      name: "Over-unders",
      start_date_local: "2026-11-20T00:00:00",
    });
    expect(event?.description).toBe(
      [
        "Three rounds of two minutes over and two under.",
        "",
        "- Warm-up 10m ramp 50-75%",
        "",
        "3x",
        "- 2m 105% 90rpm",
        "- Under 2m 90%",
        "",
        "- Cool-down 5m freeride",
      ].join("\n"),
    );
    expect(fake.requestsTo("POST", "/events")).toHaveLength(1);

    // The server's own copy matches, and the others are told.
    expect(await h.services.plan.get("icu-5001")).toMatchObject({
      date: "2026-11-20",
      name: "Over-unders",
    });
    expect(changes).toEqual([{ added: ["icu-5001"], updated: [], removed: [] }]);
    const listed = (await call("list_planned_workouts", { detail: "full" })).json as Listed;
    expect(listed.workouts.map((w) => w.id)).toEqual(["icu-5001"]);
  });

  it("ignores an id the model made up: the calendar entry names the workout", async () => {
    const { json } = await call("create_workout", {
      date: "2026-11-20",
      workout: { ...NEW_WORKOUT, id: "my-own-id" },
    });
    expect((json as { id: string; workout: Workout }).id).toBe("icu-5001");
    expect((json as { workout: Workout }).workout.id).toBe("icu-5001");
  });

  it("lets a workout be planned any day, the past and the far future included", async () => {
    expect(
      (await call("create_workout", { date: "2027-03-01", workout: NEW_WORKOUT })).isError,
    ).toBe(false);
    expect(
      (await call("create_workout", { date: "2026-11-01", workout: NEW_WORKOUT })).isError,
    ).toBe(false);
    expect(await h.services.plan.get("icu-5001")).not.toBeNull();
  });

  it.each([
    ["no steps", { name: "x", steps: [] }, /steps/],
    ["no name", { steps: NEW_WORKOUT.steps }, /name|required/],
    [
      "a duration of zero",
      { name: "x", steps: [{ type: "steady", duration: 0, target: 0.5 }] },
      /\/steps\/0\/duration/,
    ],
    [
      "a duration with a fraction",
      { name: "x", steps: [{ type: "steady", duration: 10.5, target: 0.5 }] },
      /\/steps\/0\/duration must be integer/,
    ],
    [
      "a target above 300% of FTP",
      { name: "x", steps: [{ type: "steady", duration: 60, target: 3.5 }] },
      /\/steps\/0\/target must be <= 3/,
    ],
    [
      "a steady step with no target",
      { name: "x", steps: [{ type: "steady", duration: 60 }] },
      /\/steps\/0/,
    ],
    [
      "a step of an unknown type",
      { name: "x", steps: [{ type: "sprint", duration: 60 }] },
      /\/steps\/0/,
    ],
    [
      "a repeat inside a repeat",
      {
        name: "x",
        steps: [
          {
            type: "repeat",
            count: 2,
            steps: [{ type: "repeat", count: 2, steps: [{ type: "free", duration: 60 }] }],
          },
        ],
      },
      /\/steps\/0/,
    ],
    [
      "a repeat with no steps",
      { name: "x", steps: [{ type: "repeat", count: 2, steps: [] }] },
      /\/steps\/0/,
    ],
    [
      "another schema version",
      { schema: 2, name: "x", steps: [{ type: "free", duration: 60 }] },
      /\/schema/,
    ],
    [
      "an unknown property",
      { name: "x", steps: [{ type: "free", duration: 60 }], tags: ["a"] },
      /additional properties/,
    ],
  ])("refuses a workout with %s, names where, and sends nothing", async (_what, workout, path) => {
    const result = await call("create_workout", {
      date: "2026-11-20",
      workout: { schema: 1, ...workout },
    });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/^workout does not validate: /);
    expect(result.text).toMatch(path);
    expect(fake.requests).toHaveLength(0);
    expect(await h.services.plan.entries({ from: "2026-01-01", to: "2027-12-31" })).toEqual([]);
    expect(changes).toEqual([]);
  });

  it("refuses a date that does not exist and a workout that is not an object", async () => {
    const date = await call("create_workout", { date: "2026-02-30", workout: NEW_WORKOUT });
    expect(date.text).toMatch(/not a real calendar date/);
    const notObject = await call("create_workout", { date: "2026-11-20", workout: "Sweet spot" });
    expect(notObject.isError).toBe(true);
    expect(fake.requests).toHaveLength(0);
  });

  it.each([
    [422, /intervals\.icu refused the request \(HTTP 422.*nothing was changed/],
    [401, /did not accept the server's API key/],
    [404, /answered 404.*INTERVALS_ATHLETE_ID/],
    [500, /failed to answer \(HTTP 500\); nothing was changed, try again later/],
  ])("explains an intervals.icu answer of %i and changes nothing", async (status, message) => {
    fake.failNext(1, status, { path: "/events" });
    const result = await call("create_workout", { date: "2026-11-20", workout: NEW_WORKOUT });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
    expect(await h.services.plan.entries({ from: "2026-01-01", to: "2027-12-31" })).toEqual([]);
    expect(changes).toEqual([]);
    // A create is not repeated on a 5xx: it could make two events.
    expect(fake.requestsTo("POST", "/events").length).toBeLessThanOrEqual(1);
  });

  it("says so when intervals.icu cannot be reached", async () => {
    fake.setUnreachable(true);
    const result = await call("create_workout", { date: "2026-11-20", workout: NEW_WORKOUT });
    expect(result.text).toMatch(/did not answer.*nothing was changed/);
  });

  it("returns the workout it sent, with a warning, when intervals.icu's copy cannot be read back", async () => {
    const intervals = h.services.intervals;
    if (intervals === null) throw new Error("no client");
    const real = intervals.createEvent;
    intervals.createEvent = async (payload) => ({
      ...(await real(payload)),
      description: "just a note",
      workout_doc: undefined,
    });
    try {
      const result = await call("create_workout", { date: "2026-11-20", workout: NEW_WORKOUT });
      expect(result.isError).toBe(false);
      const stored = result.json as { workout: Workout; warnings: string[] };
      expect(stored.workout.steps).toEqual(NEW_WORKOUT.steps);
      expect(stored.warnings).toHaveLength(1);
      expect(stored.warnings[0]).toMatch(/could not read it back.*the one you sent/);
    } finally {
      intervals.createEvent = real;
    }
  });

  it("says the server's copy could not be updated when only that fails, naming the id", async () => {
    const real = h.services.plan.upsert;
    h.services.plan.upsert = async () => {
      throw new Error("database is down");
    };
    try {
      const result = await call("create_workout", { date: "2026-11-20", workout: NEW_WORKOUT });
      expect(result.isError).toBe(true);
      expect(result.text).toMatch(
        /icu-5001 was saved in intervals\.icu, but the server's own copy could not be updated/,
      );
      expect(result.text).not.toContain("database is down");
    } finally {
      h.services.plan.upsert = real;
    }
  });
});

describe("update_workout", () => {
  it("renames a workout, in the calendar and in the cache, and keeps its steps", async () => {
    await planned(sweetSpot("2026-11-19"));
    const result = await call("update_workout", { id: "icu-5001", name: "  Threshold day  " });
    expect(result.isError, result.text).toBe(false);
    const stored = result.json as { name: string; workout: Workout; durationSeconds: number };
    expect(stored.name).toBe("Threshold day");
    expect(stored.durationSeconds).toBe(600 + 2 * 1500 + 300);
    expect(stored.workout.steps).toHaveLength(3);
    expect(fake.events[0]?.name).toBe("Threshold day");
    expect((await h.services.plan.get("icu-5001"))?.name).toBe("Threshold day");
    // Only the name was sent.
    expect(fake.requestsTo("PUT", "/events/5001")[0]?.json).toEqual({ name: "Threshold day" });
    expect(changes).toEqual([{ added: [], updated: ["icu-5001"], removed: [] }]);
  });

  it("moves a workout to another day, keeping the time of day it had", async () => {
    await planned({ ...sweetSpot("2026-11-19"), start_date_local: "2026-11-19T07:30:00" });
    const result = await call("update_workout", { id: "icu-5001", date: "2026-11-24" });
    expect(result.isError, result.text).toBe(false);
    expect((result.json as { date: string }).date).toBe("2026-11-24");
    expect(fake.events[0]?.start_date_local).toBe("2026-11-24T07:30:00");
    expect((await h.services.plan.get("icu-5001"))?.date).toBe("2026-11-24");
    const listed = (await call("list_planned_workouts", { from: "2026-11-24", to: "2026-11-24" }))
      .json as Listed;
    expect(listed.workouts.map((w) => w.id)).toEqual(["icu-5001"]);
  });

  it("replaces the steps, writing them to the calendar in its own text", async () => {
    await planned(sweetSpot("2026-11-19"));
    const steps = [
      { type: "steady", duration: 1800, target: 0.65 },
      { type: "free", duration: 600 },
    ];
    const result = await call("update_workout", { id: "icu-5001", steps });
    expect(result.isError, result.text).toBe(false);
    const stored = result.json as { workout: Workout; durationSeconds: number; outline: string };
    expect(stored.workout.steps).toEqual(steps);
    expect(stored.durationSeconds).toBe(2400);
    expect(stored.outline).toBe("30m 65%, 10m free");
    const sent = fake.requestsTo("PUT", "/events/5001")[0]?.json as { description: string };
    expect(sent.description).toBe("- 30m 65%\n- 10m freeride");
    expect((await h.services.plan.get("icu-5001"))?.workout.steps).toEqual(steps);
  });

  it("warns when intervals.icu answers with other steps than were sent, and shows what it has", async () => {
    await planned(sweetSpot("2026-11-19"));
    const intervals = h.services.intervals;
    if (intervals === null) throw new Error("no client");
    const real = intervals.updateEvent;
    // The answer still carries the steps it had parsed from the old description.
    intervals.updateEvent = async (id, payload) => {
      const answer = await real(id, payload);
      return { ...answer, workout_doc: { ftp: 250, steps: [steady(600, 55)] } };
    };
    try {
      const result = await call("update_workout", {
        id: "icu-5001",
        steps: [{ type: "steady", duration: 1800, target: 0.65 }],
      });
      expect(result.isError, result.text).toBe(false);
      const stored = result.json as { workout: Workout; warnings: string[] };
      expect(stored.workout.steps).toEqual([{ type: "steady", duration: 600, target: 0.55 }]);
      expect(stored.warnings).toEqual([
        expect.stringMatching(/has the steps differently from what was sent/),
      ]);
    } finally {
      intervals.updateEvent = real;
    }
  });

  it("changes the name, the day and the steps together", async () => {
    await planned(sweetSpot("2026-11-19"));
    const result = await call("update_workout", {
      id: "icu-5001",
      name: "Endurance",
      date: "2026-11-22",
      steps: [{ type: "steady", duration: 3600, target: 0.6 }],
    });
    expect(result.isError, result.text).toBe(false);
    expect(result.json).toMatchObject({
      id: "icu-5001",
      name: "Endurance",
      date: "2026-11-22",
      durationSeconds: 3600,
    });
  });

  it("can make a workout the tablet could not play into one it can", async () => {
    await planned(
      plannedEvent("2026-11-19", "By distance", [
        { distance: 20000, power: { value: 80, units: "%ftp" } },
      ]),
    );
    expect(await h.services.plan.get("icu-5001")).toBeNull();
    const result = await call("update_workout", {
      id: "icu-5001",
      steps: [{ type: "steady", duration: 1200, target: 0.8 }],
    });
    expect(result.isError, result.text).toBe(false);
    expect((await h.services.plan.get("icu-5001"))?.workout.steps).toHaveLength(1);
  });

  it.each([
    [
      "nothing to change",
      { id: "icu-5001" },
      /nothing to change: give a name, a date, steps, a variant, live_coach or coach_brief/,
    ],
    ["a blank name", { id: "icu-5001", name: "   " }, /name must not be blank/],
    [
      "a date that does not exist",
      { id: "icu-5001", date: "2026-13-01" },
      /not a real calendar date/,
    ],
    [
      "steps that do not validate",
      { id: "icu-5001", steps: [{ type: "steady", duration: 60 }] },
      /^workout does not validate: \/steps\/0/,
    ],
    [
      "an id that is not a planned workout's",
      { id: "my-workout", name: "x" },
      /not a planned workout id.*icu-123/,
    ],
    [
      "a workout that is not in the calendar",
      { id: "icu-999", name: "x" },
      /there is no workout icu-999 in the calendar/,
    ],
  ])("refuses %s in plain words and changes nothing", async (_what, args, message) => {
    await planned(sweetSpot("2026-11-19"));
    fake.requests.length = 0;
    const result = await call("update_workout", args);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(message);
    expect(fake.requestsTo("PUT", "/events/5001")).toHaveLength(0);
    expect(changes).toEqual([]);
  });

  it("will not touch a calendar entry that is not a workout", async () => {
    const note = fake.addEvent({ ...sweetSpot("2026-11-19", "Race day"), category: "RACE_A" });
    const result = await call("update_workout", { id: `icu-${note.id}`, name: "Changed" });
    expect(result.isError).toBe(true);
    expect(result.text).toBe(`icu-${note.id} is a RACE_A entry, not a workout; it was not changed`);
    expect(fake.requestsTo("PUT", `/events/${note.id}`)).toHaveLength(0);
    expect(fake.events[0]?.name).toBe("Race day");
  });

  it("explains an intervals.icu refusal and leaves the cache as it was", async () => {
    await planned(sweetSpot("2026-11-19"));
    fake.failNext(1, 422, { path: "/events/5001" });
    const result = await call("update_workout", { id: "icu-5001", name: "New name" });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/refused the request.*nothing was changed/);
    expect((await h.services.plan.get("icu-5001"))?.name).toBe("Sweet spot");
    expect(changes).toEqual([]);
  });
});

describe("delete_workout", () => {
  it("removes the workout from the calendar and the cache, and tells the others", async () => {
    await planned(sweetSpot("2026-11-19"), sweetSpot("2026-11-20", "Keep me"));
    const result = await call("delete_workout", { id: "icu-5001" });
    expect(result.isError, result.text).toBe(false);
    expect(result.json).toEqual({ id: "icu-5001", deleted: true });
    expect(fake.events.map((e) => e.id)).toEqual([5002]);
    expect(await h.services.plan.get("icu-5001")).toBeNull();
    expect(changes).toEqual([{ added: [], updated: [], removed: ["icu-5001"] }]);
    const listed = (await call("list_planned_workouts")).json as Listed;
    expect(listed.workouts.map((w) => w.name)).toEqual(["Keep me"]);
  });

  it("succeeds for a workout that is already gone, and forgets it", async () => {
    await planned(sweetSpot("2026-11-19"));
    fake.events.length = 0;
    const result = await call("delete_workout", { id: "icu-5001" });
    expect(result.isError).toBe(false);
    expect(result.json).toEqual({
      id: "icu-5001",
      deleted: false,
      note: "it was already gone from the calendar",
    });
    expect(await h.services.plan.get("icu-5001")).toBeNull();
  });

  it("will not delete a calendar entry that is not a workout", async () => {
    const note = fake.addEvent({ ...sweetSpot("2026-11-19", "Holiday"), category: "HOLIDAY" });
    const result = await call("delete_workout", { id: `icu-${note.id}` });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/is a HOLIDAY entry, not a workout; it was not deleted/);
    expect(fake.requestsTo("DELETE", `/events/${note.id}`)).toHaveLength(0);
    expect(fake.events).toHaveLength(1);
  });

  it("refuses an id that is not a planned workout's", async () => {
    const result = await call("delete_workout", { id: "../icu-1" });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/not a planned workout id/);
    expect(fake.requests).toHaveLength(0);
  });

  it("changes nothing when intervals.icu fails", async () => {
    await planned(sweetSpot("2026-11-19"));
    fake.failNext(1, 500, { path: "/events/5001" });
    fake.failNext(3, 500, { path: "/events/5001" });
    const result = await call("delete_workout", { id: "icu-5001" });
    expect(result.isError).toBe(true);
    expect(await h.services.plan.get("icu-5001")).not.toBeNull();
    expect(changes).toEqual([]);
  });
});

describe("what the tools do not do", () => {
  it("never writes the API key or a bearer token into the server's log", async () => {
    await call("create_workout", { date: "2026-11-20", workout: NEW_WORKOUT });
    await call("update_workout", { id: "icu-5001", name: "Renamed" });
    await call("delete_workout", { id: "icu-5001" });
    const text = h.appLogs.join("\n") + h.logging.text();
    expect(text).not.toContain(STATIC_TOKEN);
    expect(text).not.toContain("fake-api-key");
  });

  it("answers an unexpected failure without detail, and logs it", async () => {
    const real = h.services.plan.entries;
    h.services.plan.entries = async () => {
      throw new Error("relation planned_workouts is on fire");
    };
    try {
      const result = await call("list_planned_workouts");
      expect(result.isError).toBe(true);
      expect(result.text).toMatch(/^Something went wrong on the server/);
      expect(result.text).not.toContain("on fire");
      expect(h.appLogs.join("\n")).toContain("mcp tool failed");
    } finally {
      h.services.plan.entries = real;
    }
  });
});
