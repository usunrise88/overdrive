import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  validateWorkout,
  validateWorkoutsResponse,
  type Workout,
  type WorkoutsResponse,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { NO_THRESHOLD_MESSAGE } from "../src/plan/heart-rate.ts";
import { bearer, TABLET } from "./helpers/app.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

// Stage A1b of v0.4.0 (spec §11.2): an agent plans a heart-rate step through the MCP, it reaches the calendar in
// intervals.icu's text and comes back whole, and the tablet is served the beats for a target given as a fraction of the threshold.

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "test", token: STATIC_TOKEN }] });
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
  fake.reset();
  h.clock.set("2026-11-18T08:00:00Z");
  await h.services.profile.update({ thresholdHeartRate: null, maxHeartRate: null });
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const BASE = {
  name: "Base, zone 2 by heart rate",
  description: "Aerobic base, the power follows the heart rate.",
  steps: [
    { type: "ramp", duration: 600, from: 0.45, to: 0.6, label: "Warm-up" },
    {
      type: "hr",
      duration: 2400,
      target_bpm: 135,
      band: 4,
      power_min: 80,
      power_max: 220,
      label: "Zone 2 by heart rate",
    },
    { type: "free", duration: 300, label: "Cool-down" },
  ],
};

const TEMPO = {
  name: "Tempo by heart rate",
  steps: [
    {
      type: "hr",
      duration: 900,
      target_lthr: 0.88,
      power_min: 100,
      power_max: 260,
      label: "Tempo",
    },
  ],
};

const served = async (): Promise<WorkoutsResponse> => {
  const res = await h.app.inject({
    method: "GET",
    url: "/v1/workouts?from=2026-11-18&to=2026-12-01",
    headers: bearer(TABLET),
  });
  expect(res.statusCode).toBe(200);
  const body = res.json();
  expect(validateWorkoutsResponse(body)).toMatchObject({ ok: true });
  return body as WorkoutsResponse;
};

describe("create_workout with a heart-rate step", () => {
  it("describes the step to the model, and when to use it", async () => {
    const { tools } = await client.listTools();
    const create = tools.find((t) => t.name === "create_workout");
    expect(create?.description).toMatch(/hr \(a heart-rate hold\)/);
    expect(create?.description).toMatch(/base training/);
    expect(create?.description).toMatch(/needs no FTP/);
    expect(create?.description).toMatch(/power_min and power_max/);
    const properties = create?.inputSchema.properties as Record<string, unknown>;
    const workout = properties.workout as {
      properties: { steps: { items: { oneOf: { properties: Record<string, unknown> }[] } } };
    };
    const hr = workout.properties.steps.items.oneOf.find((s) => "target_bpm" in s.properties);
    expect(Object.keys(hr?.properties ?? {})).toEqual(
      expect.arrayContaining(["target_bpm", "target_lthr", "band", "power_min", "power_max"]),
    );
  });

  it("writes the step to the calendar as a power range and a note, and returns it as stored, with no warning", async () => {
    const { json, isError, text } = await call("create_workout", {
      date: "2026-11-20",
      workout: BASE,
    });
    expect(isError, text).toBe(false);
    const stored = json as {
      id: string;
      outline: string;
      heartRateSeconds?: number;
      workout: Workout;
      warnings?: string[];
      estimatedTss: number;
    };
    expect(stored.warnings).toBeUndefined();
    expect(stored.workout.steps).toEqual(BASE.steps);
    expect(validateWorkout(stored.workout).ok).toBe(true);
    expect(stored.outline).toBe("10m 45→60%, 40m hr 135bpm (80-220W), 5m free");
    expect(stored.heartRateSeconds).toBe(2400);

    expect(fake.events[0]?.description).toBe(
      [
        "Aerobic base, the power follows the heart rate.",
        "",
        "- Warm-up 10m ramp 45-60%",
        "- Zone by heart rate 40m 80-220w",
        "- Cool-down 5m freeride",
        "",
        "Overdrive heart-rate step 2: 135 bpm, band 4, 80-220 W, label Zone 2 by heart rate",
      ].join("\n"),
    );
  });

  it("is still the same workout after the plan is refreshed from the calendar", async () => {
    await call("create_workout", { date: "2026-11-20", workout: BASE });
    const refreshed = await h.services.refresher?.refresh();
    expect(refreshed?.ok).toBe(true);
    const listed = (await call("list_planned_workouts", { detail: "full" })).json as {
      workouts: { workout: Workout }[];
    };
    expect(listed.workouts[0]?.workout.steps).toEqual(BASE.steps);
  });

  it("refuses a target as a fraction of the threshold when no threshold is known, in plain words, and writes nothing", async () => {
    const result = await call("create_workout", { date: "2026-11-20", workout: TEMPO });
    expect(result.isError).toBe(true);
    expect(result.text).toBe(NO_THRESHOLD_MESSAGE);
    expect(fake.requestsTo("POST", "/events")).toHaveLength(0);
  });

  it("accepts it once the profile has a threshold, and keeps the fraction in the plan", async () => {
    expect(
      (await call("update_profile", { maxHeartRate: 190, thresholdHeartRate: 170 })).isError,
    ).toBe(false);
    const { json, isError, text } = await call("create_workout", {
      date: "2026-11-20",
      workout: TEMPO,
    });
    expect(isError, text).toBe(false);
    const stored = (json as { workout: Workout }).workout;
    expect(stored.steps[0]).toMatchObject({ type: "hr", target_lthr: 0.88 });
    expect(fake.events[0]?.description).toContain(
      "Overdrive heart-rate step 1: 88% of threshold heart rate, 100-260 W, label Tempo",
    );
  });

  it("refuses limits the wrong way round with the path of the mistake", async () => {
    const result = await call("create_workout", {
      date: "2026-11-20",
      workout: {
        name: "Bad",
        steps: [{ type: "hr", duration: 600, target_bpm: 135, power_min: 220, power_max: 80 }],
      },
    });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/\/steps\/0 power_min must be below power_max/);
  });
});

describe("update_workout with a heart-rate step", () => {
  it("replaces the steps with one, and refuses a fraction without a threshold", async () => {
    const created = (
      await call("create_workout", {
        date: "2026-11-20",
        workout: { name: "Easy", steps: [{ type: "steady", duration: 1800, target: 0.6 }] },
      })
    ).json as { id: string };

    const refused = await call("update_workout", { id: created.id, steps: TEMPO.steps });
    expect(refused.isError).toBe(true);
    expect(refused.text).toBe(NO_THRESHOLD_MESSAGE);

    const updated = await call("update_workout", { id: created.id, steps: BASE.steps });
    expect(updated.isError, updated.text).toBe(false);
    expect((updated.json as { workout: Workout; warnings?: string[] }).warnings).toBeUndefined();
    expect((updated.json as { workout: Workout }).workout.steps).toEqual(BASE.steps);
  });
});

describe("what the tablet is served", () => {
  it("gets a target in beats as it is, and a fraction of the threshold worked out from the profile", async () => {
    await call("update_profile", { maxHeartRate: 190, thresholdHeartRate: 170 });
    await call("create_workout", { date: "2026-11-20", workout: BASE });
    await call("create_workout", { date: "2026-11-21", workout: TEMPO });

    const body = await served();
    const [first, second] = body.workouts;
    expect(first?.workout.steps[1]).toMatchObject({ type: "hr", target_bpm: 135, band: 4 });
    // 0.88 of 170 is 149.6: 150 beats; no fraction is left for the tablet to resolve.
    expect(second?.workout.steps[0]).toMatchObject({ type: "hr", target_bpm: 150, power_min: 100 });
    expect(JSON.stringify(second?.workout)).not.toContain("target_lthr");

    // The plan itself, and what the agent reads, keep the fraction.
    const listed = (await call("list_planned_workouts", { detail: "full" })).json as {
      workouts: { id: string; workout: Workout }[];
    };
    expect(
      listed.workouts.find((w) => w.workout.name === TEMPO.name)?.workout.steps[0],
    ).toMatchObject({
      target_lthr: 0.88,
    });
  });

  it("serves a fraction as it is when the threshold has gone from the profile", async () => {
    await call("update_profile", { maxHeartRate: 190, thresholdHeartRate: 170 });
    await call("create_workout", { date: "2026-11-21", workout: TEMPO });
    // The rider clears the threshold again: the plan keeps the fraction, and nothing is known to resolve it with.
    await call("update_profile", { thresholdHeartRate: null });
    const body = await served();
    const step = body.workouts[0]?.workout.steps[0];
    expect(step).toMatchObject({ type: "hr" });
    expect(step && "target_lthr" in step ? step.target_lthr : undefined).toBe(0.88);
  });
});
