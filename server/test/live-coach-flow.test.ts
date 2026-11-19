import {
  type LiveCue,
  validateLiveCue,
  validateLiveSessionStartResponse,
  workoutHash,
} from "@overdrive/contracts";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { createLiveCoach } from "../src/live-coach/service.ts";
import { createLiveCueStore } from "../src/live-coach/store.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { FAKE_LLM_KEY } from "./helpers/fake-llm.ts";
import { audioOf, FAKE_TTS_KEY, textOfAudio } from "./helpers/fake-tts.ts";
import {
  flush,
  heard,
  LIVE_DATE,
  LIVE_RIDE_ID,
  LIVE_WORKOUT_ID,
  type LiveEnv,
  type LiveHarness,
  liveWorkout,
  openStream,
  type Spec,
  startLive,
  startLiveEnv,
  stopLiveEnv,
} from "./helpers/live.ts";

/**
 * The live coach end to end (spec §12.1, ADR 0038): the real service over a real database, a fake language model and a fake speech service
 * on local ports, the clock and the timers moved by hand, the cue stream read as the tablet reads it. Every scenario is a table of what the
 * tablet sends and what is heard. Every number and every line is invented; nothing waits on a real timer.
 *
 * The workout: flat steps 0 warm-up (600 s), 1 Threshold (600 s, 285 W at the test FTP of 300), 2 Recover (300 s), 3 Threshold, 4 Recover,
 * 5 cool-down. The tablet's snapshots come every 5 s, so a trigger is found within 5 s of the moment it holds.
 */

let db: Db;
let env: LiveEnv;
const open: LiveHarness[] = [];

beforeAll(async () => {
  db = await openMigratedTestDb();
  env = await startLiveEnv();
});
afterAll(async () => {
  await stopLiveEnv(env);
  await db.close();
});
beforeEach(async () => {
  env.llm.reset();
  env.tts.reset();
  await db.exec(
    "TRUNCATE live_cues, planned_workouts, planned_workout_extras, notebook_versions, ride_workouts, rides, ride_destinations, profile",
  );
});
afterEach(async () => {
  for (const h of open.splice(0)) await h.close();
});

async function boot(
  options: {
    seed?: Parameters<LiveHarness["seed"]>[0] | false;
    config?: Parameters<typeof startLive>[2];
  } = {},
): Promise<LiveHarness> {
  const h = await startLive(db, env, options.config);
  open.push(h);
  if (options.seed !== false) await h.seed(options.seed);
  return h;
}

/** Lets the event loop turn until `done` holds: no timer, no sleep. */
async function until(done: () => boolean): Promise<void> {
  for (let i = 0; i < 5000 && !done(); i++) await new Promise((resolve) => setImmediate(resolve));
  if (!done()) throw new Error("the condition did not come true");
}

/** What the services logged at the level `debug`, as `trigger rule` for the cues that were held back. */
const heldBack = (h: LiveHarness): string[] =>
  h.logging.lines
    .filter((l) => l.message === "a live cue was held back")
    .map((l) => `${String(l.fields.trigger)}:${String(l.fields.rule)}`);

/** `n` snapshots of one step, 5 s apart, counting down from `left`. */
const stepRun = (step: number, left: number, n: number, extra: Partial<Spec> = {}): Spec[] =>
  Array.from({ length: n }, (_v, i) => ({ step, left: left - i * 5, ...extra }));

describe("the start of a session", () => {
  it("is on for a workout that asks for the coach, and the answer is the contract's", async () => {
    const h = await boot();
    const answer = await h.begin();
    expect(answer).toMatchObject({ coach: { on: true, reason: null }, snapshotEverySeconds: 5 });
    expect(answer.sessionId).toMatch(/^ls-[0-9a-f]{16}$/);
    expect(answer.serverTime).toBe(h.clock.now().getTime());
    expect(validateLiveSessionStartResponse(answer)).toMatchObject({ ok: true });
    // Nothing was asked of the model or the speech service just to start.
    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls).toHaveLength(0);
  });

  it("is off, saying why, and asks nobody anything", async () => {
    const reasonOf = async (h: LiveHarness, body: Parameters<LiveHarness["begin"]>[0] = {}) => {
      const answer = await h.begin(body);
      expect(validateLiveSessionStartResponse(answer)).toMatchObject({ ok: true });
      expect(answer.sessionId).toBeNull();
      expect(answer.coach.on).toBe(false);
      return answer.coach.reason;
    };

    // The owner turned it off.
    const off = await boot({ config: { config: { LIVE_COACH_ENABLED: false } } });
    expect(await reasonOf(off)).toBe("feature-off");
    // No speech service, and no model.
    const noSpeech = await boot({ config: { speech: false } });
    expect(await reasonOf(noSpeech)).toBe("no-tts");
    const noModel = await boot({ config: { model: false } });
    expect(await reasonOf(noModel)).toBe("no-llm");
    // No speech and no model: the speech service is named first.
    const neither = await boot({ config: { speech: false, model: false } });
    expect(await reasonOf(neither)).toBe("no-tts");
    // A half-configured speech service is no speech service.
    const half = await boot({ config: { config: { TTS_VOICE: undefined as never } } });
    expect(await reasonOf(half)).toBe("no-tts");

    // The workout: none, one the service does not have, one that does not ask for the coach, one that is not the one played.
    const h = await boot();
    expect(await reasonOf(h, { workoutId: null })).toBe("no-workout");
    expect(await reasonOf(h, { workoutId: "icu-99999" })).toBe("no-workout");
    expect(await reasonOf(h, { structureHash: "0123456789abcdef" })).toBe("structure-changed");
    const plain = await boot({ seed: { liveCoach: false } });
    expect(await reasonOf(plain)).toBe("no-live-coach");

    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls).toHaveLength(0);
  });

  it("accepts the hash of the variant when the rider chose the easier version, and a start with no hash", async () => {
    const h = await boot();
    const variantSteps = [{ type: "steady" as const, duration: 1800, target: 0.7, label: "Easy" }];
    await h.services.extras.setVariant(LIVE_WORKOUT_ID, LIVE_DATE, { steps: variantSteps });
    const planned = await h.services.plan.get(LIVE_WORKOUT_ID);
    const variantHash = planned?.variant?.structureHash ?? "";
    expect(variantHash).toMatch(/^[0-9a-f]{16}$/);
    expect(variantHash).not.toBe(workoutHash(liveWorkout()));

    expect((await h.begin({ structureHash: variantHash, variantRidden: true })).coach.on).toBe(
      true,
    );
    expect((await h.begin({ structureHash: null })).coach.on).toBe(true);
    expect((await h.begin({ structureHash: workoutHash(liveWorkout()) })).coach.on).toBe(true);
  });

  it("is judged on the structure, not on what the workout is called: a heart-rate step in beats is the one the tablet plays", async () => {
    const workout = {
      ...liveWorkout(),
      steps: [
        { type: "hr" as const, duration: 1200, target_lthr: 0.9, power_min: 90, power_max: 200 },
        ...liveWorkout().steps,
      ],
    };
    const h = await boot({ seed: { workout } });
    await h.services.profile.update({ thresholdHeartRate: 170 });
    // The tablet is served the beats worked out from the threshold, and sends the hash of that.
    const served = {
      ...workout,
      steps: [
        { ...workout.steps[0], target_lthr: undefined, target_bpm: 153 },
        ...workout.steps.slice(1),
      ],
    };
    const hash = workoutHash(JSON.parse(JSON.stringify(served)));
    expect((await h.begin({ structureHash: hash })).coach.on).toBe(true);
  });
});

describe("a hard step begins", () => {
  it("is triggered a few seconds early, written by the model from the facts and heard on the stream", async () => {
    const h = await boot({ seed: { brief: "Threshold progression. Calm, technical tone." } });
    await h.begin();
    const stream = await openStream(h);
    expect(stream.status).toBe(200);
    // Five seconds apart in the warm-up: nothing is due, so nobody is asked.
    await h.sendAll(stepRun(0, 20, 3));
    expect(env.llm.calls).toHaveLength(0);
    // 5 s left in the step before a hard one: inside the lead of 6 s.
    await h.sendAll([{ step: 0, left: 5 }]);
    const { frame, cue } = await stream.nextCue();

    expect(cue).toMatchObject({
      id: 1,
      trigger: "step-start",
      text: "Interval 1: hold 285 watts, start smooth.",
      ttlMs: 8000,
    });
    expect(frame.id).toBe("1");
    expect(validateLiveCue(cue)).toMatchObject({ ok: true });
    expect(cue.audio.format).toBe("mp3");
    expect(textOfAudio(Buffer.from(cue.audio.base64, "base64"))).toBe(cue.text);
    expect(new Date(cue.expiresAt).getTime() - new Date(cue.createdAt).getTime()).toBe(8000);
    expect(cue.createdAt).toBe(h.clock.now().toISOString());

    // What the model was told: the rules and the session in the system message, the trigger and the facts in the user message.
    expect(env.llm.calls).toHaveLength(1);
    const [system, user] = env.llm.calls[0]?.body.messages ?? [];
    expect(system?.content).toContain("Threshold progression");
    expect(system?.content).toContain("Calm, technical tone.");
    expect(system?.content).toContain("Two ten-minute efforts at threshold");
    expect(user?.content).toContain("Trigger: step-start");
    expect(user?.content).toContain('"targetWatts":285');
    // …and the speech service was asked for exactly the line, with the owner's settings.
    expect(env.tts.calls).toHaveLength(1);
    expect(env.tts.calls[0]?.body).toMatchObject({
      input: cue.text,
      response_format: "mp3",
      speed: 1,
    });
    expect(env.tts.calls[0]?.authorization).toBe(`Bearer ${FAKE_TTS_KEY}`);
    expect(env.llm.calls[0]?.authorization).toBe(`Bearer ${FAKE_LLM_KEY}`);
  });

  it("is said again for the next hard step, with its own number", async () => {
    const h = await boot({
      config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0, LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0 } },
    });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 5 }]);
    expect((await stream.nextCue()).cue.text).toBe("Interval 1: hold 285 watts, start smooth.");
    await h.sendAll([{ step: 2, left: 5 }]);
    expect((await stream.nextCue()).cue.text).toBe("Interval 2: hold 285 watts, start smooth.");
  });

  it("is not triggered for a step that is not hard", async () => {
    const h = await boot();
    await h.begin();
    // The end of the first Threshold, a Recover step follows.
    await h.sendAll([
      { step: 1, left: 12 },
      { step: 1, left: 7 },
      { step: 1, left: 2 },
    ]);
    expect(env.llm.calls).toHaveLength(0);
  });
});

describe("the limits, end to end", () => {
  it("hold a second cue back for 60 s from the first, then let it through", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue(
      { text: "Interval 1: hold 285 watts, start smooth." },
      { text: "Power is below target. Hold 285 watts." },
    );
    await h.sendAll([{ step: 0, left: 5 }]); // the cue, at T
    await stream.nextCue();
    // In the step, 5 s per snapshot, power 12% under the target from its first second: after the 30 s of grace and 20 s of snapshots the drift
    // is found at 30 s into the step (T + 35 s).
    await h.sendAll(stepRun(1, 595, 12, { power: 250 })); // T+10 … T+65
    const cues = await heard(h, stream);
    // The step began at T+5; the drift (30 s grace, then 15 s of snapshots) is found at 30 s into the step, T+35, and is held back until T+60.
    expect(cues.map((c) => c.trigger)).toEqual(["step-start", "power-drift"]);
    expect(cues[1]?.text).toBe("Power is below target. Hold 285 watts.");
    expect(heldBack(h).filter((x) => x === "power-drift:gap").length).toBeGreaterThanOrEqual(4);
    // The second cue is exactly 60 s after the first: the time of the snapshot, not 5 s sooner.
    expect(
      new Date(cues[1]?.createdAt ?? 0).getTime() - new Date(cues[0]?.createdAt ?? 0).getTime(),
    ).toBe(60_000);
  });

  it("let the same trigger speak twice in a step, three minutes apart, and not a third time", async () => {
    const h = await boot({ config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0 } } });
    await h.begin();
    env.llm.queue(
      { text: "Power is below target. Hold 285 watts." },
      { text: "Still under target. Push toward 285 watts." },
      { text: "Third reminder. Aim for 285 watts." },
    );
    // 40 s into the Threshold step, the power 12% under the target for the next 7 minutes (before the two-minute warning): the first cue
    // is made 20 s later, the second after the cooldown, and the third is held back for good.
    await h.sendAll(stepRun(1, 560, 85, { power: 250 }));
    const said = env.tts.calls.map((c) => c.body.input);
    expect(said).toEqual([
      "Power is below target. Hold 285 watts.",
      "Still under target. Push toward 285 watts.",
    ]);
    const held = heldBack(h);
    expect(held).toContain("power-drift:trigger-cooldown");
    expect(held).toContain("power-drift:trigger-per-step");
    // Asked twice only: a trigger that the limits hold back never costs a request.
    expect(env.llm.calls).toHaveLength(2);
  });

  it("allow the same trigger again in the next step", async () => {
    const h = await boot({
      config: {
        config: {
          LIVE_COACH_MIN_GAP_SECONDS: 0,
          LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0,
          LIVE_COACH_MAX_PER_TRIGGER_PER_STEP: 1,
        },
      },
    });
    await h.begin();
    env.llm.queue(
      { text: "Power is below target. Hold 285 watts." },
      { text: "Back under again. Hold 285 watts." },
    );
    await h.sendAll(stepRun(1, 560, 9, { power: 250 }));
    expect(env.tts.calls).toHaveLength(1);
    await h.sendAll(stepRun(3, 560, 9, { power: 250 }));
    expect(env.tts.calls).toHaveLength(2);
  });

  it("stop at the hourly cap", async () => {
    const h = await boot({
      config: {
        config: {
          LIVE_COACH_MAX_CUES_PER_HOUR: 2,
          LIVE_COACH_MIN_GAP_SECONDS: 0,
          LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0,
        },
      },
    });
    await h.begin();
    // Three hard steps' worth of reasons in a row: a step-start, a two-minutes and then another step-start.
    await h.sendAll([{ step: 0, left: 5 }]);
    await h.sendAll([{ step: 1, left: 125 }]);
    await h.sendAll([{ step: 2, left: 5 }]);
    expect(env.tts.calls.map((c) => c.body.input)).toEqual([
      "Interval 1: hold 285 watts, start smooth.",
      "Two minutes left. Keep your shoulders loose.",
    ]);
    expect(heldBack(h)).toContain("step-start:per-hour");
  });

  it("let a model's cue wait for a recovery step's only cue to be the only one", async () => {
    const h = await boot({
      config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0, LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0 } },
    });
    await h.begin();
    // In the Recover step the power is far under the target and the cadence has sagged: two reasons, one cue.
    env.llm.queue({ text: "Power is below target. Hold 150 watts." });
    await h.sendAll(stepRun(2, 270, 6, { power: 100, cad: 60 }));
    expect(env.tts.calls).toHaveLength(1);
    expect(heldBack(h)).toContain("cadence-sag:recovery-step");
  });

  it("keep the final seconds of a step silent", async () => {
    const h = await boot();
    await h.begin();
    // The power is fine, then goes under the target in the last 25 s of the step: the drift is found with 10 s left.
    await h.sendAll([
      { step: 1, left: 40 },
      { step: 1, left: 35 },
      { step: 1, left: 30 },
      ...stepRun(1, 25, 5, { power: 250 }),
    ]);
    expect(env.llm.calls).toHaveLength(0);
    expect(heldBack(h)).toContain("power-drift:final-seconds");
    // The strong finish for the last hard step is said before them, not inside them.
    const strong = await boot();
    await strong.begin();
    await strong.sendAll([{ step: 3, left: 100, compliance: 90 }]);
    expect(env.llm.calls).toHaveLength(1);
    expect(heldBack(strong)).toEqual([]);
    const inside = await boot();
    await inside.begin();
    await inside.sendAll([{ step: 3, left: 15, compliance: 90 }]);
    // Inside the silent final seconds it is not even a reason to speak.
    expect(env.llm.calls).toHaveLength(1);
  });

  it("say nothing for a muted rider, and keep saying nothing", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 5, muted: true }]);
    // The rider unmutes in the overlay? The coach stays muted for the rest of the ride.
    await h.sendAll([{ step: 2, left: 5, muted: false }]);
    await flush(h, stream);
    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls).toHaveLength(0);
    expect(stream.cues()).toEqual([]);
    expect(heldBack(h)).toEqual(expect.arrayContaining(["step-start:muted"]));
  });

  it("abandon a cue that is being made when the rider mutes the coach", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ hang: true });
    await h.send({ step: 0, left: 5 });
    await until(() => env.llm.calls.length === 1);
    await h.send({ step: 0, left: 0, muted: true });
    await h.idle();
    await flush(h, stream);
    expect(env.tts.calls).toHaveLength(0);
    expect(stream.cues()).toEqual([]);
  });
});

describe("the model's answer", () => {
  it("may be that nothing is worth saying: no cue, no speech, and the same trigger is not tried again for a minute", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ text: "NO_CUE" }, { text: "NO_CUE" });
    // The drift holds from the 5th snapshot after 30 s of the step: the model is asked at once, says nothing, and is not asked again at the
    // next snapshots; a minute after the first question it is asked again.
    await h.sendAll(stepRun(1, 570, 5, { power: 250 })); // elapsed 30 … 50: asked once, at elapsed 50
    expect(env.llm.calls).toHaveLength(1);
    await h.sendAll(stepRun(1, 545, 11, { power: 250 })); // elapsed 55 … 105: a minute minus a snapshot after the question
    expect(env.llm.calls).toHaveLength(1);
    await h.sendAll(stepRun(1, 490, 1, { power: 250 })); // elapsed 110: a minute after it
    expect(env.llm.calls).toHaveLength(2);
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(env.tts.calls).toHaveLength(0);
    expect(env.llm.calls).toHaveLength(2);
    expect(heldBack(h)).toContain("power-drift:attempt-cooldown");
    // The log says it was silence, with the milliseconds, and nothing of the rider.
    const first = h.logging.lines.find((l) => l.message === "a live cue was not made");
    expect(first?.fields).toMatchObject({ trigger: "power-drift", outcome: "silence" });
  });

  it("is refused when it quotes a number it was not given, and the speech service is not asked", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ text: "Push 310 watts for the whole interval!" });
    await h.sendAll([{ step: 0, left: 5 }]);
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(env.tts.calls).toHaveLength(0);
    const line = h.logging.lines.find((l) => l.message === "a live cue was not made");
    expect(line?.fields).toMatchObject({
      outcome: "rejected",
      rejection: "violations",
      violations: { number: 1 },
    });
    // A trigger that fires once is spent: it is not asked again at the next snapshot.
    await h.sendAll([
      { step: 1, left: 595 },
      { step: 1, left: 590 },
    ]);
    expect(env.llm.calls).toHaveLength(1);
  });

  it("is refused when it is longer than 80 characters", async () => {
    const h = await boot();
    await h.begin();
    env.llm.queue({
      text: `Interval one begins and you should settle into a smooth rhythm right away. ${"Breathe."}`,
    });
    await h.sendAll([{ step: 0, left: 5 }]);
    expect(env.tts.calls).toHaveLength(0);
    expect(
      h.logging.lines.find((l) => l.message === "a live cue was not made")?.fields,
    ).toMatchObject({ rejection: "too-long" });
  });

  it("is refused when it repeats a line already said in the ride", async () => {
    const h = await boot({ config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0 } } });
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue(
      { text: "Smooth and steady, shoulders loose." },
      { text: "Smooth and steady, shoulders loose!" },
    );
    await h.sendAll([{ step: 0, left: 5 }]);
    await stream.nextCue();
    await h.sendAll([{ step: 1, left: 125 }]);
    await flush(h, stream);
    expect(stream.cues().map((c) => c.text)).toEqual(["Smooth and steady, shoulders loose."]);
    expect(env.llm.calls).toHaveLength(2);
    expect(
      h.logging.lines.find((l) => l.message === "a live cue was not made")?.fields,
    ).toMatchObject({ rejection: "repeat" });
    // The second request told the model what had been said.
    expect(env.llm.calls[1]?.body.messages[1]?.content).toContain(
      "- Smooth and steady, shoulders loose.",
    );
  });

  it("is told what it has already said, and the notebook's sections about the rider, never the game master's", async () => {
    const h = await boot({ seed: { brief: "Keep it technical." } });
    const book = h.services.notebook;
    await book.update("coach", {
      section: "profile",
      text: "Rides at six, before work.",
      author: "test",
    });
    await book.update("coach", {
      section: "what_works",
      text: "Short calm cues beat pep talks.",
      author: "test",
    });
    await book.update("coach", {
      section: "methodology",
      text: "Polarised, two hard days.",
      reason: "r",
      author: "test",
    });
    await book.update("coach", { section: "injuries", text: "Left knee niggle.", author: "test" });
    await book.update("game-master", {
      section: "game_plans",
      text: "SECRET game master plan.",
      author: "test",
    });
    await h.begin();
    await h.sendAll([{ step: 0, left: 5 }]);
    const system = env.llm.calls[0]?.body.messages[0]?.content ?? "";
    expect(system).toContain("Rides at six, before work.");
    expect(system).toContain("Short calm cues beat pep talks.");
    expect(system).toContain("Polarised, two hard days.");
    expect(system).not.toContain("Left knee niggle");
    expect(system).not.toContain("SECRET");
    expect(system).not.toContain("game master");
  });

  it("is written in the language the owner chose, by default the language of the insights", async () => {
    const h = await boot({ config: { config: { INSIGHTS_LANGUAGE: "French" } } });
    await h.begin();
    await h.sendAll([{ step: 0, left: 5 }]);
    expect(env.llm.calls[0]?.body.messages[0]?.content).toContain("in French.");
    const g = await boot({
      config: { config: { INSIGHTS_LANGUAGE: "French", LIVE_COACH_LANGUAGE: "Spanish" } },
    });
    await g.begin();
    await g.sendAll([{ step: 0, left: 5 }]);
    expect(env.llm.calls[1]?.body.messages[0]?.content).toContain("in Spanish.");
  });

  it("is asked of the model the owner set for the cues, when there is one", async () => {
    const h = await boot({ config: { config: { LIVE_COACH_LLM_MODEL: "fast-small-1" } } });
    await h.begin();
    await h.sendAll([{ step: 0, left: 5 }]);
    expect(env.llm.calls[0]?.body.model).toBe("fast-small-1");
    // One request, a short answer.
    expect(env.llm.calls[0]?.body.max_tokens).toBe(400);
  });
});

describe("what goes wrong, and what the rider hears of it: nothing", () => {
  it("drops a cue that is not ready in 5 s, never plays it late, and the next one is made", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ hang: true });
    await h.send({ step: 0, left: 5 });
    await until(() => env.llm.calls.length === 1);
    // The deadline is the timers' one-shot of 5 s from the trigger; the clock does not matter to it.
    expect(h.timers.pending).toContainEqual({ kind: "after", ms: 5000 });
    h.timers.tick();
    await h.idle();
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(env.tts.calls).toHaveLength(0);
    expect(
      h.logging.lines.find((l) => l.message === "a live cue was not made")?.fields,
    ).toMatchObject({
      trigger: "step-start",
      outcome: "deadline",
    });
    // The step began long ago when the answer would have come; the trigger is spent and the ride goes on.
    await h.sendAll([{ step: 1, left: 125 }]);
    expect((await stream.nextCue()).cue.trigger).toBe("two-minutes");
  });

  it("drops a cue whose speech is slow", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    env.tts.queue({ hang: true });
    await h.send({ step: 0, left: 5 });
    await until(() => env.tts.calls.length === 1);
    h.timers.tick();
    await h.idle();
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(
      h.logging.lines.find((l) => l.message === "a live cue was not made")?.fields.outcome,
    ).toBe("deadline");
  });

  it("makes no cue when the speech service refuses, and none when the model fails", async () => {
    const h = await boot({ config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0 } } });
    await h.begin();
    const stream = await openStream(h);
    env.tts.queue({ status: 500 });
    await h.sendAll([{ step: 0, left: 5 }]);
    env.llm.queue({ status: 503 });
    await h.sendAll([{ step: 1, left: 125 }]);
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    const outcomes = h.logging.lines
      .filter((l) => l.message === "a live cue was not made")
      .map((l) => l.fields.outcome);
    expect(outcomes).toEqual(["tts-failed", "llm-failed"]);
    // The ride is unaffected: snapshots are still taken.
    expect((await h.send({ step: 1, left: 100 })).statusCode).toBe(204);
  });

  it("is silent, and the ride is unaffected, when the service is not configured for the coach", async () => {
    const h = await boot({ config: { speech: false } });
    const answer = await h.begin();
    expect(answer).toMatchObject({ sessionId: null, coach: { on: false, reason: "no-tts" } });
    // The tablet has nothing to stream to: a snapshot to any id is a plain 404, and the ride's own upload is untouched.
    expect((await h.post("/sessions/ls-0000000000000000/end", {})).statusCode).toBe(404);
    expect((await openStream(h, { sessionId: "ls-0000000000000000" })).status).toBe(404);
    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls).toHaveLength(0);
  });
});

describe("the agent's own lines (must_say)", () => {
  const lines = {
    "1.0": [
      { at: "start" as const, text: "First one. Ease into it." },
      { at: 300, text: "Halfway. Stay relaxed." },
    ],
  };

  it("are spoken exactly as written, with no model, a few seconds before the step they belong to", async () => {
    const h = await boot({ seed: { mustSay: lines } });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([
      { step: 0, left: 12 },
      { step: 0, left: 7 },
    ]);
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    await h.sendAll([{ step: 0, left: 2 }]);
    const { cue } = await stream.nextCue();
    expect(cue).toMatchObject({ id: 1, trigger: "must-say", text: "First one. Ease into it." });
    expect(validateLiveCue(cue)).toMatchObject({ ok: true });
    // No model at all, and the speech service got the words as written.
    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls.map((c) => c.body.input)).toEqual(["First one. Ease into it."]);
    expect(textOfAudio(Buffer.from(cue.audio.base64, "base64"))).toBe("First one. Ease into it.");
  });

  it("take the place of the model's cue for the start of the same step, and the second line comes at its second", async () => {
    const h = await boot({ seed: { mustSay: lines } });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 2 }]); // the line for the start of step 1, and the model's step-start: the line wins
    await h.sendAll(stepRun(1, 595, 12)); // the first minute of the step
    await h.sendAll(stepRun(1, 302, 4)); // 298 s into the step: 2 s before the second line is due … and after it
    await flush(h, stream);
    expect(stream.cues().map((c) => [c.trigger, c.text])).toEqual([
      ["must-say", "First one. Ease into it."],
      ["must-say", "Halfway. Stay relaxed."],
    ]);
    // The model was never asked: the step-start for step 1 was held back by the gap of the line before it, until its moment passed.
    expect(env.llm.calls).toHaveLength(0);
  });

  it("are spoken at every pass through a repeat, though they say the same thing", async () => {
    const h = await boot({
      seed: { mustSay: { "1.0": [{ at: "start" as const, text: "Same line each time." }] } },
      config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0 } },
    });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll(
      [
        { step: 0, left: 2 },
        { step: 2, left: 2 },
      ],
      30,
    );
    const said = await heard(h, stream);
    expect(said.map((c) => c.text)).toEqual(["Same line each time.", "Same line each time."]);
  });

  it("are not spoken when they fall in the silent final seconds of their step", async () => {
    const h = await boot({ seed: { mustSay: { "1.0": [{ at: 595, text: "Last push!" }] } } });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll(stepRun(1, 14, 3));
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(heldBack(h)).toContain("must-say:final-seconds");
  });

  it("are spoken when a muted rider has not muted: and never when muted", async () => {
    const h = await boot({ seed: { mustSay: lines } });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 2, muted: true }]);
    await flush(h, stream);
    expect(stream.cues()).toEqual([]);
    expect(env.tts.calls).toHaveLength(0);
  });

  it("do not need the model to be reachable, only the speech service, once the coach is on", async () => {
    const h = await boot({ seed: { mustSay: lines } });
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ status: 500 });
    await h.sendAll([{ step: 0, left: 2 }]);
    expect((await stream.nextCue()).cue.text).toBe("First one. Ease into it.");
  });
});

describe("one session at a time", () => {
  it("ends the session before it when a new one starts: its snapshots are refused and its stream is told", async () => {
    const h = await boot();
    const first = await h.begin();
    const oldId = first.sessionId ?? "";
    const stream = await openStream(h);
    const second = await h.begin();
    expect(second.sessionId).not.toBe(oldId);
    const newId = h.sessionId;
    // The old stream gets `end` and is closed.
    const end = await stream.nextEvent("end");
    expect(JSON.parse(end.data ?? "{}")).toEqual({ reason: "superseded" });
    await stream.whenClosed;
    // The old session's snapshots are a plain 404; the new one's are taken.
    h.sessionId = oldId;
    expect((await h.send({ step: 0, left: 300 })).statusCode).toBe(404);
    h.sessionId = newId;
    expect((await h.send({ step: 0, left: 295 })).statusCode).toBe(204);
  });

  it("ends the old session even when the new ride has no coach: it is a new ride", async () => {
    const h = await boot();
    const first = await h.begin();
    const stream = await openStream(h);
    const answer = await h.begin({ workoutId: null });
    expect(answer.coach.on).toBe(false);
    await stream.nextEvent("end");
    await stream.whenClosed;
    h.sessionId = first.sessionId ?? "";
    expect((await h.send({ step: 0, left: 300 })).statusCode).toBe(404);
  });

  it("ends a session that went quiet for ten minutes, by itself", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    await h.send({ step: 0, left: 300 });
    h.clock.advance(599_000);
    h.timers.tick();
    await flush(h, stream);
    expect(stream.closed).toBe(false);
    h.clock.advance(2000);
    h.timers.tick();
    const end = await stream.nextEvent("end");
    expect(JSON.parse(end.data ?? "{}")).toEqual({ reason: "ended" });
    expect((await h.send({ step: 0, left: 295 })).statusCode).toBe(404);
  });

  it("never calls the tablet: a service with a session and no tablet sends nothing anywhere", async () => {
    const h = await boot();
    await h.begin();
    h.clock.advance(3_600_000);
    h.timers.tick();
    expect(env.llm.calls).toHaveLength(0);
    expect(env.tts.calls).toHaveLength(0);
  });
});

describe("what the previous attempt tells", () => {
  it("lets the heart rate be compared with the same step last time, when the repeats service has one", async () => {
    const h = await boot();
    const asked: unknown[] = [];
    const live = createLiveCoach({
      config: {
        LLM_BASE_URL: env.llm.url,
        LLM_MODEL: "m",
        TTS_BASE_URL: env.tts.url,
        TTS_MODEL: "t",
        TTS_VOICE: "v",
        LIVE_COACH_MIN_GAP_SECONDS: 0,
      },
      store: createLiveCueStore(db),
      plan: h.services.plan,
      profile: h.services.profile,
      notebook: h.services.notebook,
      repeats: {
        previous: async (hash, before) => {
          asked.push([hash, before]);
          return {
            generatedAt: "2026-11-20T07:00:00Z",
            hash,
            before,
            previous: {
              rideId: "ride-1794000000000",
              date: "2026-11-12",
              startTime: "2026-11-12T07:00:00Z",
              durationSeconds: 2400,
              ftp: 300,
              attempt: 1,
              heartRateAtPower: 150,
              steps: [
                {
                  step: 2,
                  label: "Threshold",
                  startSeconds: 600,
                  endSeconds: 1200,
                  targetWatts: 285,
                  averageHeartRate: 150,
                },
              ],
            },
          };
        },
      },
      log: h.logging.log,
      now: h.clock.now,
      timers: h.timers,
    });
    const hash = workoutHash(liveWorkout());
    const started = await live.startSession({
      rideId: LIVE_RIDE_ID,
      workoutId: LIVE_WORKOUT_ID,
      structureHash: hash,
      clock: 0,
    });
    expect(started.coach.on).toBe(true);
    expect(asked).toEqual([[hash, LIVE_RIDE_ID]]);
    // 200 s into the Threshold step, the heart rate climbs from 156 to 168 while the power holds: 15 bpm above last time's average of 150.
    const id = started.sessionId ?? "";
    for (let i = 0; i < 15; i++) {
      h.clock.advance(5000);
      const answer = live.snapshot(id, {
        seq: i,
        clock: h.clock.now().getTime(),
        elapsedSeconds: i * 5,
        paused: false,
        muted: false,
        step: {
          index: 1,
          type: "steady",
          label: "Threshold",
          durationSeconds: 600,
          timeLeftSeconds: 400 - i * 5,
          targetWatts: 285,
        },
        stepCount: 6,
        power: 285,
        heartRate: 156 + i,
        cadence: 90,
        compliancePercent: 95,
      });
      expect(answer).toBe("accepted");
      await live.idle();
    }
    const said = env.tts.calls.map((c) => c.body.input);
    expect(said).toHaveLength(1);
    expect(said[0]).toMatch(/^Heart rate is \d+ higher than last time\./);
    const facts = JSON.parse(
      (env.llm.calls.at(-1)?.body.messages[1]?.content ?? "").split("Facts (JSON):\n")[1] ?? "{}",
    );
    expect(facts.detail).toMatchObject({ previousAverageBpm: 150, previousDate: "2026-11-12" });
    await live.stop();
  });
});

describe("the cue log", () => {
  it("keeps every cue on the ride with its trigger, text and time, and what the tablet said about it", async () => {
    const h = await boot();
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 5 }]);
    const { cue } = await stream.nextCue();
    await h.idle();
    const rows = await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      rideId: LIVE_RIDE_ID,
      seq: 1,
      trigger: "step-start",
      source: "model",
      text: cue.text,
      stepIndex: 1,
      played: null,
      createdAt: cue.createdAt,
    });
    // The ride's time at the trigger, in the ride record's seconds (the harness counts 5 s a snapshot).
    expect(rows[0]?.atSeconds).toBe(5);
    expect(
      (await h.post(`/sessions/${h.sessionId}/cues/${cue.id}/played`, { played: true })).statusCode,
    ).toBe(204);
    expect((await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID))[0]?.played).toBe(true);
  });

  it("is kept for the agent's own lines too, marked as theirs", async () => {
    const h = await boot({
      seed: { mustSay: { "1.0": [{ at: "start" as const, text: "First one." }] } },
    });
    await h.begin();
    await h.sendAll([{ step: 0, left: 2 }]);
    expect((await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID))[0]).toMatchObject({
      trigger: "must-say",
      source: "must-say",
      text: "First one.",
    });
  });
});

describe("what is never logged", () => {
  it("holds no line, no reading, no brief, no notebook text, no key and no address", async () => {
    const h = await boot({
      seed: {
        brief: "Brief words that are private.",
        mustSay: { "1.0": [{ at: "start" as const, text: "Private agent words." }] },
      },
    });
    await h.services.notebook.update("coach", {
      section: "profile",
      text: "Private notebook words.",
      author: "test",
    });
    await h.begin();
    const stream = await openStream(h);
    env.llm.queue({ text: "Push 311 watts for the whole interval!" }, { status: 500 });
    await h.sendAll([{ step: 0, left: 2, power: 277, hr: 173, cad: 97 }]);
    await h.sendAll(stepRun(1, 560, 8, { power: 241, hr: 177, cad: 61 }));
    await h.post(`/sessions/${h.sessionId}/end`, {});
    await flush(h, stream).catch(() => undefined);
    const text = h.logging.text();
    expect(text.length).toBeGreaterThan(100);
    for (const secret of [
      "Private agent words",
      "First one",
      "Brief words",
      "Private notebook words",
      "311",
      "173",
      "177",
      FAKE_LLM_KEY,
      FAKE_TTS_KEY,
      env.llm.url,
      env.tts.url,
      "hold 285 watts",
      Buffer.from(audioOf("Private agent words.")).toString("base64"),
    ]) {
      expect(text, secret).not.toContain(secret);
    }
  });
});

describe("a cue is a LiveCue", () => {
  it("is valid whatever made it", async () => {
    const h = await boot({
      seed: { mustSay: { "1.0": [{ at: "start" as const, text: "First one." }] } },
      config: { config: { LIVE_COACH_MIN_GAP_SECONDS: 0 } },
    });
    await h.begin();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 2 }]);
    await h.sendAll([{ step: 1, left: 125 }]);
    const cues: LiveCue[] = await heard(h, stream);
    expect(cues.length).toBe(2);
    for (const cue of cues) expect(validateLiveCue(cue)).toMatchObject({ ok: true });
  });
});
