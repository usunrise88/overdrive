import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  hasLiveCoachFields,
  type MustSay,
  type RideRecord,
  type Step,
  validateWorkout,
  validateWorkoutsResponse,
  type Workout,
  type WorkoutsResponse,
  withoutLiveCoach,
  workoutHash,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { buildRideFacts } from "../src/insights/facts.ts";
import { createLiveCueStore, type NewLiveCue } from "../src/live-coach/store.ts";
import { applyExtras, stepsFingerprint } from "../src/plan/extras.ts";
import {
  asRide,
  bearer,
  fitFixture,
  putFit,
  putRecord,
  rideFixture,
  TABLET,
} from "./helpers/app.ts";
import { clearPlan, clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { rideInput } from "./helpers/insights.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/**
 * What the agent writes for the live coach and what is kept of the ride (spec §12.1, §8.4, ADR 0038): `live_coach`, `coach_brief` and
 * `must_say` through `create_workout` and `update_workout`, kept only in the service (the calendar never sees them) and served to the tablet;
 * the log of every cue on the ride, in `get_ride` and in the facts the planning agent sees afterwards.
 */

let db: Db;
let fake: FakeIntervals;
/** The service with the live coach on (a model and a speech service are set: nothing is ever called). */
let on: McpHarness;
let onClient: Client;
/** The service with neither: the coach is off. */
let off: McpHarness;
let offClient: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  on = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
    config: {
      LLM_BASE_URL: "http://127.0.0.1:1/v1",
      LLM_MODEL: "m",
      TTS_BASE_URL: "http://127.0.0.1:1/v1",
      TTS_MODEL: "t",
      TTS_VOICE: "v",
    },
  });
  onClient = await connectWithToken(on.mcpUrl, STATIC_TOKEN);
  off = await startMcpHarness(db, fake, { staticTokens: [{ name: "coach", token: STATIC_TOKEN }] });
  offClient = await connectWithToken(off.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await onClient.close();
  await offClient.close();
  await on.close();
  await off.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearPlan(db);
  await clearRides(db);
  await db.exec(
    "TRUNCATE planned_workout_extras, live_cues, ride_workouts, ride_metrics, insights",
  );
  fake.reset();
  on.clock.set("2026-11-18T08:00:00Z");
  off.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(onClient, name, args);
const callOff = (name: string, args: Record<string, unknown> = {}) =>
  callTool(offClient, name, args);

const BRIEF =
  "Threshold progression. He tends to start intervals too hard and fade. Keep him smooth in the first two minutes. Calm, technical tone.";

const STEPS = (lines?: { start?: MustSay[]; later?: MustSay[] }): Step[] => [
  { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
  {
    type: "repeat",
    count: 2,
    steps: [
      {
        type: "steady",
        duration: 600,
        target: 0.95,
        label: "Threshold",
        ...(lines?.start === undefined ? {} : { must_say: lines.start }),
      },
      { type: "steady", duration: 300, target: 0.5, label: "Recover" },
    ],
  },
  {
    type: "free",
    duration: 300,
    label: "Cool-down",
    ...(lines?.later === undefined ? {} : { must_say: lines.later }),
  },
];

const FIRST: MustSay[] = [{ at: "start", text: "First one. Ease into it." }];

interface Stored {
  id: string;
  date: string;
  workout: Workout;
  warnings?: string[];
}

async function create(
  extra: Partial<Workout> = {},
  steps: Step[] = STEPS({ start: FIRST }),
  caller = call,
): Promise<{ result: Awaited<ReturnType<typeof call>>; stored: Stored }> {
  const result = await caller("create_workout", {
    date: "2026-11-20",
    workout: { name: "Threshold 2x10", steps, ...extra },
  });
  return { result, stored: result.json as Stored };
}

/** What the tablet reads. */
async function tablet(): Promise<WorkoutsResponse> {
  const response = await on.app.inject({
    method: "GET",
    url: "/v1/workouts?from=2026-11-18&to=2026-11-24",
    headers: bearer(TABLET),
  });
  expect(response.statusCode, response.body).toBe(200);
  return response.json() as WorkoutsResponse;
}

const extrasRow = async (id: string) =>
  (
    await db.query<{
      live_coach: boolean | null;
      coach_brief: string | null;
      must_say: Record<string, MustSay[]> | null;
      steps_hash: string | null;
      plan_date: string;
    }>(
      "SELECT live_coach, coach_brief, must_say, steps_hash, plan_date::text AS plan_date FROM planned_workout_extras WHERE workout_id = $1",
      [id],
    )
  ).rows[0];

describe("create_workout with the live coach", () => {
  it("keeps the flag, the brief and the lines beside the plan and returns them as stored, with no warning when the coach is on", async () => {
    const { result, stored } = await create({ live_coach: true, coach_brief: BRIEF });
    expect(result.isError, result.text).toBe(false);
    expect(stored.warnings).toBeUndefined();
    expect(stored.workout).toMatchObject({ live_coach: true, coach_brief: BRIEF });
    const repeat = stored.workout.steps[1];
    if (repeat?.type !== "repeat") throw new Error("expected a repeat");
    expect(repeat.steps[0]).toMatchObject({ label: "Threshold", must_say: FIRST });
    expect(repeat.steps[1]).not.toHaveProperty("must_say");
    expect(validateWorkout(stored.workout)).toMatchObject({ ok: true });
    const row = await extrasRow(stored.id);
    expect(row).toMatchObject({ live_coach: true, coach_brief: BRIEF, plan_date: "2026-11-20" });
    expect(row?.must_say).toEqual({ "1.0": FIRST });
    expect(row?.steps_hash).toBe(stepsFingerprint(stored.workout.steps));
  });

  it("never writes them to the calendar: intervals.icu holds the steps and nothing of the coach", async () => {
    await create({ live_coach: true, coach_brief: BRIEF });
    expect(fake.events).toHaveLength(1);
    const written = JSON.stringify(fake.events[0]);
    for (const word of [
      "First one",
      "Ease into it",
      "Calm, technical tone",
      "must_say",
      "live_coach",
      "coach_brief",
    ]) {
      expect(written, word).not.toContain(word);
    }
    expect(fake.events[0]?.description).toMatch(/Threshold/);
  });

  it("gives them to the tablet, valid by the contract, with the same structure hash as without them", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const body = await tablet();
    expect(validateWorkoutsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
    const entry = body.workouts[0];
    expect(entry?.id).toBe(stored.id);
    expect(entry?.workout).toMatchObject({ live_coach: true, coach_brief: BRIEF });
    expect(JSON.stringify(entry?.workout.steps)).toContain("First one. Ease into it.");
    const plain = { ...(entry?.workout as Workout), steps: STEPS() };
    expect(entry?.structureHash).toBe(workoutHash(plain));
  });

  it("serves a workout nobody gave anything for the coach exactly as before: none of the three keys", async () => {
    await create({}, STEPS());
    const body = await tablet();
    const text = JSON.stringify(body.workouts[0]);
    expect(text).not.toMatch(/live_coach|coach_brief|must_say/);
    expect(hasLiveCoachFields(body.workouts[0]?.workout as Workout)).toBe(false);
    expect(await extrasRow(body.workouts[0]?.id ?? "")).toBeUndefined();
  });

  it("survives a refresh of the plan from the calendar, which cannot hold it", async () => {
    await create({ live_coach: true, coach_brief: BRIEF });
    expect((await on.services.refresher?.refresh())?.ok).toBe(true);
    const entry = (await tablet()).workouts[0];
    expect(entry?.workout).toMatchObject({ live_coach: true, coach_brief: BRIEF });
    expect(JSON.stringify(entry?.workout.steps)).toContain("First one. Ease into it.");
  });

  it("loses the lines, not the session's flag and brief, when the rider rewrites the steps in the calendar", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const planned = await on.services.plan.get(stored.id);
    if (planned === null) throw new Error("not planned");
    // What the plan cache holds after a refresh in which the steps are no longer those the lines were written for.
    const rewritten: Workout = {
      ...withoutLiveCoach(planned.workout),
      steps: withoutLiveCoach(planned.workout).steps.map((step) =>
        step.type === "ramp" ? { ...step, duration: 660 } : step,
      ),
    };
    await on.services.plan.upsert({
      id: stored.id,
      source: "intervals.icu",
      externalId: stored.id.replace(/^icu-/, ""),
      date: "2026-11-20",
      name: "Threshold 2x10",
      workout: {
        schema: 1,
        id: stored.id,
        name: "Threshold 2x10",
        steps: rewritten.steps.map((s) => JSON.parse(JSON.stringify(s))),
      },
      unsupportedReason: null,
    });
    const entry = (await tablet()).workouts[0];
    expect(entry?.workout).toMatchObject({ live_coach: true, coach_brief: BRIEF });
    expect(JSON.stringify(entry?.workout.steps)).not.toContain("First one.");
    expect(entry?.workout.steps[0]).toMatchObject({ type: "ramp", duration: 660 });
  });

  it("says in a warning when the coach is off on this server, and keeps the settings", async () => {
    const { result, stored } = await create(
      { live_coach: true, coach_brief: BRIEF },
      STEPS(),
      callOff,
    );
    expect(result.isError, result.text).toBe(false);
    expect(stored.warnings?.join(" ")).toMatch(
      /live coach is off on this server \(no speech service is configured/,
    );
    expect((await extrasRow(stored.id))?.live_coach).toBe(true);
  });

  it("does not write a flag that is false, and refuses a brief that is blank before anything is written", async () => {
    const blank = await create({ coach_brief: "  \n " }, STEPS());
    expect(blank.result.isError).toBe(true);
    expect(blank.result.text).toMatch(/coach_brief/);
    expect(fake.events).toHaveLength(0);
    const ok = await create({ live_coach: false }, STEPS());
    expect(await extrasRow(ok.stored.id)).toBeUndefined();
  });

  it("refuses what the contract refuses, with the path of the mistake, and writes nothing", async () => {
    const cases: [Partial<Workout>, Step[], RegExp][] = [
      [{ coach_brief: "x".repeat(1001) }, STEPS(), /coach_brief/],
      [{ live_coach: "yes" as never }, STEPS(), /live_coach/],
      [{}, STEPS({ start: [{ at: "start", text: "y".repeat(81) }] }), /must_say\/0\/text/],
      [
        {},
        STEPS({
          start: [
            { at: "start", text: "One." },
            { at: 100, text: "Two." },
            { at: 200, text: "Three." },
            { at: 300, text: "Four." },
          ],
        }),
        /must_say/,
      ],
      [{}, STEPS({ start: [{ at: 0 as never, text: "Go." }] }), /must_say\/0\/at/],
      [{}, STEPS({ start: [{ at: 900, text: "Go." }] }), /the step lasts 600 s/],
      [
        {},
        STEPS({
          start: [
            { at: 100, text: "Go." },
            { at: 100, text: "Again." },
          ],
        }),
        /same moment/,
      ],
    ];
    for (const [extra, steps, message] of cases) {
      const { result } = await create(extra, steps);
      expect(result.isError, JSON.stringify(extra)).toBe(true);
      expect(result.text).toMatch(message);
    }
    expect(fake.events).toHaveLength(0);
  });

  it("refuses a line that would never be spoken: inside the final 15 seconds of its step, where the coach is silent", async () => {
    for (const lines of [
      [{ at: 590, text: "Last push." }],
      [{ at: 585, text: "Last push." }],
    ] as MustSay[][]) {
      const { result } = await create({ live_coach: true }, STEPS({ start: lines }));
      expect(result.isError).toBe(true);
      expect(result.text).toMatch(/would never be spoken/);
      expect(result.text).toMatch(/inside the final 15 s of a step/);
      expect(result.text).toMatch(/nothing was changed/);
    }
    // 584 s is the last second that can be spoken (a step of 600 s has 16 s left).
    const { result, stored } = await create(
      { live_coach: true },
      STEPS({ start: [{ at: 584, text: "Last push." }] }),
    );
    expect(result.isError, result.text).toBe(false);
    expect(stored.workout.steps[1]).toBeDefined();
    expect(fake.events).toHaveLength(1);
  });

  it("refuses a step too short to speak in: a line at its start is inside its final 15 seconds", async () => {
    const short: Step[] = [
      { type: "steady", duration: 15, target: 1.2, must_say: [{ at: "start", text: "Go!" }] },
    ];
    const { result } = await create({ live_coach: true }, short);
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/the step is inside the final 15 s/);
  });

  it("lists in the summary which sessions have the coach", async () => {
    await create({ live_coach: true, coach_brief: BRIEF });
    await create({}, STEPS());
    const { json } = await call("list_planned_workouts", { from: "2026-11-18", to: "2026-11-24" });
    const workouts = (json as { workouts: { liveCoach?: true; workout?: Workout }[] }).workouts;
    expect(workouts.map((w) => w.liveCoach)).toEqual([true, undefined]);
    const full = await call("list_planned_workouts", {
      from: "2026-11-18",
      to: "2026-11-24",
      detail: "full",
    });
    const first = (full.json as { workouts: { workout: Workout }[] }).workouts[0];
    expect(first?.workout.coach_brief).toBe(BRIEF);
  });
});

describe("update_workout and the live coach", () => {
  it("turns the flag off and on, replaces the brief and removes it with an empty one, without touching the calendar", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const writes = () => fake.requests.filter((r) => r.method === "PUT").length;
    const before = writes();
    let result = await call("update_workout", { id: stored.id, coach_brief: "A shorter brief." });
    expect(result.isError, result.text).toBe(false);
    expect((result.json as Stored).workout).toMatchObject({
      live_coach: true,
      coach_brief: "A shorter brief.",
    });
    result = await call("update_workout", { id: stored.id, live_coach: false });
    expect((result.json as Stored).workout).not.toHaveProperty("live_coach");
    expect((result.json as Stored).workout.coach_brief).toBe("A shorter brief.");
    result = await call("update_workout", { id: stored.id, coach_brief: "" });
    expect((result.json as Stored).workout).not.toHaveProperty("coach_brief");
    expect(await extrasRow(stored.id)).toMatchObject({ live_coach: null, coach_brief: null });
    result = await call("update_workout", { id: stored.id, live_coach: true });
    expect((result.json as Stored).workout.live_coach).toBe(true);
    // None of it reached the calendar.
    expect(writes()).toBe(before);
  });

  it("keeps the lines when only the name or the day changes, and moves them with the day", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const renamed = await call("update_workout", { id: stored.id, name: "Threshold, renamed" });
    expect((renamed.json as Stored).workout).toMatchObject({
      live_coach: true,
      coach_brief: BRIEF,
    });
    expect(JSON.stringify((renamed.json as Stored).workout.steps)).toContain("First one.");
    const moved = await call("update_workout", { id: stored.id, date: "2026-11-21" });
    expect((moved.json as Stored).date).toBe("2026-11-21");
    expect(JSON.stringify((moved.json as Stored).workout.steps)).toContain("First one.");
    expect(await extrasRow(stored.id)).toMatchObject({ plan_date: "2026-11-21", live_coach: true });
  });

  it("replaces the lines with those of new steps, and drops them when the new steps carry none", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const newer = await call("update_workout", {
      id: stored.id,
      steps: STEPS({
        start: [{ at: 300, text: "Halfway. Stay relaxed." }],
        later: [{ at: "start", text: "Spin it out." }],
      }),
    });
    expect(newer.isError, newer.text).toBe(false);
    const text = JSON.stringify((newer.json as Stored).workout.steps);
    expect(text).toContain("Halfway. Stay relaxed.");
    expect(text).toContain("Spin it out.");
    expect(text).not.toContain("First one.");
    expect((await extrasRow(stored.id))?.must_say).toEqual({
      "1.0": [{ at: 300, text: "Halfway. Stay relaxed." }],
      "2": [{ at: "start", text: "Spin it out." }],
    });
    // The brief and the flag are the session's, not the steps': they stay.
    expect((newer.json as Stored).workout).toMatchObject({ live_coach: true, coach_brief: BRIEF });
    const bare = await call("update_workout", { id: stored.id, steps: STEPS() });
    expect(JSON.stringify((bare.json as Stored).workout.steps)).not.toContain("must_say");
    expect((await extrasRow(stored.id))?.must_say).toBeNull();
    expect((bare.json as Stored).workout.live_coach).toBe(true);
  });

  it("refuses a line that would never be spoken, and a brief that is too long, and changes nothing", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const bad = await call("update_workout", {
      id: stored.id,
      steps: STEPS({ start: [{ at: 595, text: "Late." }] }),
    });
    expect(bad.isError).toBe(true);
    expect(bad.text).toMatch(/would never be spoken/);
    const long = await call("update_workout", { id: stored.id, coach_brief: "x".repeat(1001) });
    expect(long.isError).toBe(true);
    expect((await extrasRow(stored.id))?.coach_brief).toBe(BRIEF);
  });

  it("says nothing to change when it is given nothing, and names the new fields", async () => {
    const { stored } = await create({});
    const result = await call("update_workout", { id: stored.id });
    expect(result.isError).toBe(true);
    expect(result.text).toBe(
      "nothing to change: give a name, a date, steps, a variant, live_coach or coach_brief",
    );
  });

  it("warns, when it turns the flag on, that the coach is off on this server", async () => {
    const { stored } = await create({}, STEPS(), callOff);
    const result = await callOff("update_workout", {
      id: stored.id,
      live_coach: true,
      coach_brief: BRIEF,
    });
    expect((result.json as Stored).warnings?.join(" ")).toMatch(/live coach is off on this server/);
  });

  it("goes with the workout when it is deleted", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    expect(await extrasRow(stored.id)).toBeDefined();
    expect((await call("delete_workout", { id: stored.id })).isError).toBe(false);
    expect(await extrasRow(stored.id)).toBeUndefined();
  });
});

describe("the easier version of a session with the coach", () => {
  it("may carry its own lines: they are the variant's steps as written, served and checked", async () => {
    const variant: Step[] = [
      {
        type: "steady",
        duration: 1800,
        target: 0.75,
        label: "Tempo",
        must_say: [{ at: "start", text: "Easier today. Same smooth start." }],
      },
    ];
    const { result, stored } = await create(
      { live_coach: true, coach_brief: BRIEF },
      STEPS({ start: FIRST }),
    );
    expect(result.isError).toBe(false);
    const updated = await call("update_workout", { id: stored.id, variant });
    expect(updated.isError, updated.text).toBe(false);
    const entry = (await tablet()).workouts[0];
    expect(entry?.variant?.steps).toEqual(variant);
    expect(validateWorkoutsResponse(await tablet())).toMatchObject({ ok: true });
    // Its own hash is the hash of the primary with these steps: what the tablet sends when the rider chooses it.
    expect(entry?.variant?.structureHash).toBe(
      workoutHash({ ...(entry?.workout as Workout), steps: variant }),
    );
  });
});

describe("what a ride keeps of the workout", () => {
  it("has none of what is for the coach: the copy is what was ridden", async () => {
    const { stored } = await create({ live_coach: true, coach_brief: BRIEF });
    const resolved = await on.services.rideWorkouts.resolve({
      id: "ride-copy-1",
      workoutId: stored.id,
    });
    if (resolved.workout === null) throw new Error(resolved.reason);
    expect(JSON.stringify(resolved.workout)).not.toMatch(
      /live_coach|coach_brief|must_say|First one|Calm, technical/,
    );
    expect(resolved.workout.steps).toHaveLength(3);
    const kept = await on.services.rides.workoutSnapshot("ride-copy-1");
    expect(JSON.stringify(kept)).not.toMatch(/live_coach|coach_brief|must_say/);
    // The plan still has them.
    expect((await on.services.plan.get(stored.id))?.workout).toMatchObject({ live_coach: true });
  });
});

describe("the extras of a workout", () => {
  it("lay the lines on the steps they were set for and not on others", () => {
    const workout: Workout = { schema: 1, id: "icu-1", name: "W", steps: STEPS() };
    const lines = { "1.0": FIRST };
    const hash = stepsFingerprint(workout.steps);
    const laid = applyExtras(workout, {
      mustSay: lines,
      stepsHash: hash,
      liveCoach: true,
      coachBrief: "B",
    });
    expect(laid.workout.live_coach).toBe(true);
    expect(laid.workout.coach_brief).toBe("B");
    expect(JSON.stringify(laid.workout.steps)).toContain("First one.");
    // Steps that are not those the lines were set for: no lines, but the session's flag and brief still stand.
    const changed: Workout = {
      ...workout,
      steps: STEPS().map((s) => (s.type === "ramp" ? { ...s, duration: 601 } : s)),
    };
    const other = applyExtras(changed, {
      mustSay: lines,
      stepsHash: hash,
      liveCoach: true,
      coachBrief: "B",
    });
    expect(JSON.stringify(other.workout.steps)).not.toContain("First one.");
    expect(other.workout.live_coach).toBe(true);
  });

  it("do not change the fingerprint of the steps: a line is not part of what a workout asks for", () => {
    expect(stepsFingerprint(STEPS())).toBe(stepsFingerprint(STEPS({ start: FIRST, later: FIRST })));
  });
});

describe("the cue log on the ride", () => {
  const rideId = "ride-1794984000000";
  const cue = (over: Partial<NewLiveCue>): NewLiveCue => ({
    rideId,
    sessionId: "ls-aaaaaaaaaaaaaaaa",
    seq: 1,
    trigger: "step-start",
    source: "model",
    text: "Interval 1: hold 285 watts, start smooth.",
    stepIndex: 1,
    atSeconds: 610,
    createdAt: new Date("2026-11-17T18:15:10Z"),
    ...over,
  });

  async function storeRide(id = rideId): Promise<RideRecord> {
    const record = asRide(rideFixture("synthetic-5min.json"), id, 1_794_984_000_000);
    expect((await putRecord(on.app, id, record)).statusCode).toBe(201);
    expect((await putFit(on.app, id, fitFixture("minimal.fit"))).statusCode).toBe(201);
    return record;
  }

  it("is in get_ride, in order, with what the tablet reported, and absent from a ride the coach never spoke in", async () => {
    await storeRide();
    const plain = await call("get_ride", { rideId });
    expect(plain.json).not.toHaveProperty("liveCoach");

    const store = createLiveCueStore(db);
    await store.insert(cue({}));
    await store.insert(
      cue({
        seq: 2,
        trigger: "must-say",
        source: "must-say",
        text: "Halfway. Stay relaxed.",
        atSeconds: 905,
        stepIndex: 1,
        createdAt: new Date("2026-11-17T18:20:05Z"),
      }),
    );
    await store.insert(
      cue({
        seq: 3,
        trigger: "power-drift",
        text: "Power is below target. Hold 285 watts.",
        atSeconds: 1500,
        stepIndex: null,
        createdAt: new Date("2026-11-17T18:30:00Z"),
      }),
    );
    await store.markPlayed("ls-aaaaaaaaaaaaaaaa", 1, true, new Date());
    await store.markPlayed("ls-aaaaaaaaaaaaaaaa", 3, false, new Date());

    const { json } = await call("get_ride", { rideId });
    const live = (json as { liveCoach: { count: number; cues: Record<string, unknown>[] } })
      .liveCoach;
    expect(live.count).toBe(3);
    expect(live.cues).toEqual([
      {
        minute: 10.2,
        trigger: "step-start",
        source: "model",
        step: 2,
        text: "Interval 1: hold 285 watts, start smooth.",
        played: true,
      },
      {
        minute: 15.1,
        trigger: "must-say",
        source: "must-say",
        step: 2,
        text: "Halfway. Stay relaxed.",
        played: null,
      },
      {
        minute: 25,
        trigger: "power-drift",
        source: "model",
        step: null,
        text: "Power is below target. Hold 285 watts.",
        played: false,
      },
    ]);
    // get_activity answers as get_ride does for a ride.
    const activity = await call("get_activity", { id: rideId });
    expect((activity.json as { liveCoach?: unknown }).liveCoach).toBeDefined();
  });

  it("is part of the facts the planning agent sees afterwards, with every number quotable", async () => {
    await storeRide();
    const store = createLiveCueStore(db);
    await store.insert(cue({}));
    await store.insert(
      cue({
        seq: 2,
        trigger: "two-minutes",
        text: "Two minutes left. Keep your shoulders loose.",
        atSeconds: 1080,
        stepIndex: 1,
        createdAt: new Date("2026-11-17T18:33:00Z"),
      }),
    );
    const { json } = await call("get_facts", { kind: "ride", rideId });
    const facts = (
      json as {
        facts: {
          liveCoach?: { cues: number; said: Record<string, unknown>[] };
          quotable: { value: number; label: string }[];
        };
      }
    ).facts;
    expect(facts.liveCoach?.cues).toBe(2);
    expect(facts.liveCoach?.said).toEqual([
      {
        trigger: "step-start",
        atMinute: 10.2,
        step: 2,
        text: "Interval 1: hold 285 watts, start smooth.",
      },
      {
        trigger: "two-minutes",
        atMinute: 18,
        step: 2,
        text: "Two minutes left. Keep your shoulders loose.",
      },
    ]);
    const quotable = new Set(facts.quotable.map((q) => q.value));
    for (const value of [2, 10.2, 18]) expect(quotable.has(value), String(value)).toBe(true);
  });

  it("is not in the facts of a ride the coach never spoke in: they are as they were", () => {
    const facts = buildRideFacts(rideInput());
    expect(facts).not.toHaveProperty("liveCoach");
    expect(facts.quotable.some((q) => q.label.startsWith("live coach"))).toBe(false);
    const spoken = buildRideFacts({
      ...rideInput(),
      liveCues: [
        { ...cue({}), createdAt: "2026-11-17T18:15:10Z", played: null },
        {
          ...cue({ seq: 2, text: "Hold it.", trigger: "power-drift", stepIndex: null }),
          createdAt: "2026-11-17T18:20:10Z",
          played: true,
        },
      ],
    });
    expect(spoken.liveCoach).toEqual({
      cues: 2,
      said: [
        {
          trigger: "step-start",
          atMinute: 10.2,
          step: 2,
          text: "Interval 1: hold 285 watts, start smooth.",
        },
        { trigger: "power-drift", atMinute: 10.2, step: null, text: "Hold it.", played: true },
      ],
    });
  });

  it("is told to the ride note's model: the ride prompt names it and says never to quote it", async () => {
    const { RIDE_PROMPT } = await import("../src/insights/prompts/ride.ts");
    expect(RIDE_PROMPT.version).toBe(3);
    expect(RIDE_PROMPT.instructions).toMatch(/liveCoach is present/);
    expect(RIDE_PROMPT.instructions).toMatch(/never quote a line word for word/);
  });

  it("is stored once for a cue: a repeated insert of the same cue changes nothing", async () => {
    const store = createLiveCueStore(db);
    await store.insert(cue({}));
    await store.insert(cue({ text: "Something else entirely." }));
    const rows = await store.forRide(rideId);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.text).toBe("Interval 1: hold 285 watts, start smooth.");
    expect(await store.markPlayed("ls-nobody", 1, true, new Date())).toBe(false);
  });
});

describe("the tools tell a model how to use the live coach", () => {
  it("in create_workout and update_workout, and in the server's own instructions", async () => {
    const tools = (await onClient.listTools()).tools;
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    const create = by.create_workout?.description ?? "";
    expect(create).toMatch(/set live_coach true for key or strict sessions/);
    expect(create).toMatch(/WRITE A BRIEF/);
    expect(create).toMatch(
      /what the session is for, what to watch in this rider, what tone to take/,
    );
    expect(create).toMatch(/must_say on a step is up to 3 lines/);
    expect(create).toMatch(/use them sparingly/);
    expect(create).toMatch(/never see them/);
    const update = by.update_workout?.description ?? "";
    expect(update).toMatch(/live_coach true turns the live coach on/);
    expect(update).toMatch(/coach_brief/);
    const props = (by.update_workout?.inputSchema.properties ?? {}) as Record<string, unknown>;
    expect(Object.keys(props)).toEqual(expect.arrayContaining(["live_coach", "coach_brief"]));
    // The workout's own schema, as advertised, has the three fields.
    const workoutSchema = JSON.stringify(by.create_workout?.inputSchema);
    expect(workoutSchema).toMatch(/must_say/);
    expect(workoutSchema).toMatch(/coach_brief/);
    expect(onClient.getInstructions()).toMatch(/LIVE COACH: for key or strict sessions/);
    expect(onClient.getInstructions()).toMatch(/only sparingly/);
  });
});

describe("the migration", () => {
  it("makes the cue log and refuses lines with no fingerprint and a brief over 1000 characters", async () => {
    const cols = await db.query<{ column_name: string }>(
      "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'live_cues' ORDER BY ordinal_position",
    );
    expect(cols.rows.map((r) => r.column_name)).toEqual([
      "id",
      "ride_id",
      "session_id",
      "seq",
      "trigger",
      "source",
      "text",
      "step_index",
      "at_seconds",
      "created_at",
      "played",
      "played_at",
    ]);
    await expect(
      db.query(
        "INSERT INTO planned_workout_extras (workout_id, plan_date, must_say, updated_at) VALUES ('icu-9', '2026-11-20', '{}'::jsonb, now())",
      ),
    ).rejects.toThrow();
    await expect(
      db.query(
        "INSERT INTO planned_workout_extras (workout_id, plan_date, coach_brief, updated_at) VALUES ('icu-9', '2026-11-20', $1, now())",
        ["x".repeat(1001)],
      ),
    ).rejects.toThrow();
  });
});
