import { LIVE_CUE_AUDIO_MAX_BYTES, type LiveSnapshot } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createLlmClient } from "../src/insights/llm.ts";
import { createValidator } from "../src/insights/validator.ts";
import { buildCueFacts, type CueFacts } from "../src/live-coach/cue-facts.ts";
import { type CueRequest, createCueMaker } from "../src/live-coach/cue-maker.ts";
import { messagesFor, type PromptContext } from "../src/live-coach/prompt.ts";
import { createTtsClient } from "../src/live-coach/tts.ts";
import { FAKE_LLM_KEY, FAKE_LLM_MODEL, type FakeLlm, startFakeLlm } from "./helpers/fake-llm.ts";
import {
  audioOf,
  FAKE_TTS_KEY,
  FAKE_TTS_MODEL,
  FAKE_TTS_VOICE,
  type FakeTts,
  startFakeTts,
  textOfAudio,
} from "./helpers/fake-tts.ts";
import { liveLine, livePlan } from "./helpers/live.ts";
import { collectingLogger, manualTimers } from "./helpers/support.ts";

/**
 * The speech service's client and the making of one cue (spec §12.1, ADR 0038), against the fakes of the model and of the speech service:
 * what is sent, what is refused, the 5 s deadline, the timings that are logged and what never is. No network beyond the local fakes, no
 * real timer: the deadline is the injected timers', moved by hand.
 */

let llm: FakeLlm;
let tts: FakeTts;

const plan = livePlan();
const FACTS_SNAPSHOT: LiveSnapshot = {
  seq: 3,
  clock: 1,
  elapsedSeconds: 300,
  paused: false,
  muted: false,
  step: {
    index: 1,
    type: "steady",
    label: "Threshold",
    durationSeconds: 600,
    timeLeftSeconds: 300,
    targetWatts: 285,
  },
  stepCount: 6,
  power: 262,
  heartRate: 161,
  cadence: 91,
  compliancePercent: 92,
};

const facts = (trigger: "step-start" | "two-minutes" | "power-drift"): CueFacts =>
  buildCueFacts({
    trigger:
      trigger === "step-start"
        ? { trigger, step: plan.steps[3] as never }
        : trigger === "two-minutes"
          ? { trigger }
          : { trigger, direction: "below", averageWatts: 258, targetWatts: 285, seconds: 20 },
    workoutName: "Threshold progression",
    snapshot: FACTS_SNAPSHOT,
    plan,
    ftp: 300,
  });

const PROMPT: PromptContext = {
  language: "en",
  maxChars: 80,
  workoutName: "Threshold progression",
  workoutDescription: null,
  brief: "Calm tone.",
  notebook: [],
};

beforeAll(async () => {
  llm = await startFakeLlm(liveLine);
  tts = await startFakeTts();
});

beforeEach(() => {
  llm.reset();
  tts.reset();
});

afterAll(async () => {
  await llm.close();
  await tts.close();
});

/** Lets the event loop turn until `done` holds: no timer, no sleep. */
async function until(done: () => boolean): Promise<void> {
  for (let i = 0; i < 5000 && !done(); i++) await new Promise((resolve) => setImmediate(resolve));
  if (!done()) throw new Error("the condition did not come true");
}

describe("the speech service's client", () => {
  const client = (over: Partial<Parameters<typeof createTtsClient>[0]> = {}) =>
    createTtsClient({
      baseUrl: tts.url,
      apiKey: FAKE_TTS_KEY,
      model: FAKE_TTS_MODEL,
      voice: FAKE_TTS_VOICE,
      speed: 1.1,
      format: "mp3",
      timeoutMs: 10_000,
      ...over,
    });

  it("asks POST {base}/audio/speech with the bearer key and exactly the fields of the API, and returns the audio", async () => {
    const result = await client().synthesize("Hold 285 watts.");
    expect(result).toMatchObject({ ok: true, format: "mp3" });
    if (!result.ok) return;
    expect(textOfAudio(result.audio)).toBe("Hold 285 watts.");
    expect(tts.calls).toHaveLength(1);
    expect(tts.calls[0]).toMatchObject({
      path: "/v1/audio/speech",
      authorization: `Bearer ${FAKE_TTS_KEY}`,
      contentType: "application/json",
      body: {
        model: FAKE_TTS_MODEL,
        voice: FAKE_TTS_VOICE,
        input: "Hold 285 watts.",
        response_format: "mp3",
        speed: 1.1,
      },
    });
    expect(Object.keys(tts.calls[0]?.body ?? {}).sort()).toEqual([
      "input",
      "model",
      "response_format",
      "speed",
      "voice",
    ]);
  });

  it("asks for the format it was set to", async () => {
    const result = await client({ format: "opus" }).synthesize("Go.");
    expect(result).toMatchObject({ ok: true, format: "opus" });
    expect(tts.calls[0]?.body.response_format).toBe("opus");
  });

  it("sends no Authorization header without a key", async () => {
    // The fake accepts a request without one (as a local server of the owner might).
    expect(await client({ apiKey: undefined }).synthesize("Go.")).toMatchObject({ ok: true });
    expect(tts.calls[0]?.authorization).toBeUndefined();
  });

  it.each([
    [401, "unauthorized"],
    [403, "unauthorized"],
    [408, "rate-limited"],
    [429, "rate-limited"],
    [400, "rejected"],
    [404, "rejected"],
    [500, "server"],
    [503, "server"],
  ] as const)("answers %i as %s and never repeats it", async (status, kind) => {
    tts.queue({ status, body: "the server's own words, which may echo the text" });
    const result = await client().synthesize("Hold 285 watts.");
    expect(result).toEqual({ ok: false, kind, status });
  });

  it("is not satisfied by an answer that is not audio, or has none", async () => {
    tts.queue({
      status: 200,
      body: "<html>sorry</html>",
      headers: { "content-type": "text/html" },
    });
    expect(await client().synthesize("Go.")).toMatchObject({ ok: false, kind: "bad-response" });
    tts.queue({ audio: new Uint8Array(0) });
    expect(await client().synthesize("Go.")).toMatchObject({ ok: false, kind: "bad-response" });
  });

  it("refuses more audio than a cue can carry, declared or not", async () => {
    tts.queue({ audio: new Uint8Array(LIVE_CUE_AUDIO_MAX_BYTES + 1) });
    expect(await client().synthesize("Go.")).toMatchObject({ ok: false, kind: "too-large" });
    tts.queue({ audio: new Uint8Array(LIVE_CUE_AUDIO_MAX_BYTES) });
    expect(await client().synthesize("Go.")).toMatchObject({ ok: true });
  });

  it("does not follow a redirect: the key and the text would go with it", async () => {
    tts.queue({ status: 302, headers: { location: "http://127.0.0.1:1/elsewhere" } });
    expect(await client().synthesize("Go.")).toMatchObject({ ok: false, kind: "unreachable" });
  });

  it("gives up when the caller does, and says timeout", async () => {
    tts.queue({ hang: true });
    const controller = new AbortController();
    const pending = client().synthesize("Go.", { signal: controller.signal });
    await until(() => tts.calls.length === 1);
    controller.abort();
    expect(await pending).toEqual({ ok: false, kind: "timeout", status: null });
  });

  it("says unreachable for a service that is not there, and puts nothing in the answer but a kind and a status", async () => {
    const result = await client({ baseUrl: "http://127.0.0.1:1/v1" }).synthesize("Hold 285 watts.");
    expect(result).toEqual({ ok: false, kind: "unreachable", status: null });
    expect(JSON.stringify(result)).not.toContain(FAKE_TTS_KEY);
    expect(JSON.stringify(result)).not.toContain("285");
  });
});

describe("making one cue", () => {
  const timers = () => manualTimers();
  const setup = (over: { deadlineMs?: number; maxChars?: number } = {}) => {
    const logging = collectingLogger();
    const clock = { t: 0 };
    const manual = timers();
    const maker = createCueMaker({
      llm: createLlmClient({
        baseUrl: llm.url,
        apiKey: FAKE_LLM_KEY,
        model: FAKE_LLM_MODEL,
        timeoutMs: 20_000,
        maxAttempts: 1,
        maxTokens: 400,
      }),
      tts: createTtsClient({
        baseUrl: tts.url,
        apiKey: FAKE_TTS_KEY,
        model: FAKE_TTS_MODEL,
        voice: FAKE_TTS_VOICE,
        speed: 1,
        format: "mp3",
        timeoutMs: 20_000,
      }),
      validator: createValidator(),
      timers: manual,
      // A clock that moves 100 ms each time it is read: the timings are then exact.
      now: () => {
        clock.t += 100;
        return clock.t;
      },
      log: logging.log,
      deadlineMs: over.deadlineMs ?? 5000,
      maxChars: over.maxChars ?? 80,
    });
    return { maker, logging, manual };
  };
  const model = (
    trigger: "step-start" | "two-minutes" | "power-drift",
    said: string[] = [],
  ): CueRequest => {
    const f = facts(trigger);
    return { source: "model", messages: messagesFor(PROMPT, f, said), facts: f, said };
  };
  const ctx = { sessionId: "ls-test", trigger: "step-start" };
  const never = new AbortController().signal;

  it("has the model write the line, checks it and has it spoken", async () => {
    const { maker } = setup();
    const result = await maker.make(ctx, model("step-start"), never);
    expect(result).toMatchObject({
      ok: true,
      text: "Interval 2: hold 285 watts, start smooth.",
      format: "mp3",
    });
    if (!result.ok) return;
    expect(textOfAudio(result.audio)).toBe(result.text);
    // The model was asked once, with the facts and the brief; the speech service was asked for exactly the line.
    expect(llm.calls).toHaveLength(1);
    expect(llm.calls[0]?.body.model).toBe(FAKE_LLM_MODEL);
    expect(llm.calls[0]?.body.messages[0]?.content).toContain("Calm tone.");
    expect(tts.calls.map((c) => c.body.input)).toEqual([result.text]);
  });

  it("times each stage, from the clock it is given", async () => {
    const { maker } = setup();
    const result = await maker.make(ctx, model("step-start"), never);
    // started 100, asked 200, answered 300, spoken 400, answered 500, finished 600.
    expect(result.timings).toEqual({ llmMs: 100, ttsMs: 100, totalMs: 500 });
  });

  it("speaks the agent's own words as written, with no model at all", async () => {
    const { maker } = setup();
    const result = await maker.make(
      { sessionId: "ls-test", trigger: "must-say" },
      { source: "must-say", text: "First one. Ease into it. 5 seconds, no matter what!" },
      never,
    );
    expect(result).toMatchObject({
      ok: true,
      text: "First one. Ease into it. 5 seconds, no matter what!",
    });
    expect(llm.calls).toHaveLength(0);
    expect(tts.calls.map((c) => c.body.input)).toEqual([
      "First one. Ease into it. 5 seconds, no matter what!",
    ]);
    expect(result.timings.llmMs).toBeNull();
  });

  it("drops the cue when the model says nothing is worth saying, and does not call the speech service", async () => {
    const { maker } = setup();
    llm.queue({ text: "NO_CUE" });
    const result = await maker.make(ctx, model("step-start"), never);
    expect(result).toMatchObject({ ok: false, reason: "silence" });
    expect(tts.calls).toHaveLength(0);
  });

  it("drops a line that breaks a rule, and does not call the speech service", async () => {
    for (const bad of [
      "Push 310 watts for the whole interval now!", // a number it was not given
      `${"Keep going and keep smooth, ".repeat(4)}ok.`, // more than 80 characters
      "A touch of flu would explain it.", // a word that is not allowed
    ]) {
      const { maker, logging } = setup();
      llm.queue({ text: bad });
      const result = await maker.make(ctx, model("step-start"), never);
      expect(result, bad).toMatchObject({ ok: false, reason: "rejected" });
      expect(logging.lines.at(-1)?.fields.outcome).toBe("rejected");
    }
    expect(tts.calls).toHaveLength(0);
  });

  it("drops a line the model has already said", async () => {
    const { maker } = setup();
    const said = ["Interval 2: hold 285 watts, start smooth."];
    const result = await maker.make(ctx, model("step-start", said), never);
    expect(result).toMatchObject({ ok: false, reason: "rejected" });
    expect(tts.calls).toHaveLength(0);
  });

  it("drops the cue when the model cannot be reached, and when the speech service cannot", async () => {
    const one = setup();
    llm.queue({ status: 500 });
    expect(await one.maker.make(ctx, model("step-start"), never)).toMatchObject({
      ok: false,
      reason: "llm-failed",
    });
    expect(one.logging.lines.at(-1)?.fields).toMatchObject({
      outcome: "llm-failed",
      failure: "server",
      status: 500,
    });
    // One request, no repeat: a cue has seconds, not minutes.
    expect(llm.calls).toHaveLength(1);

    const two = setup();
    tts.queue({ status: 503 });
    expect(await two.maker.make(ctx, model("step-start"), never)).toMatchObject({
      ok: false,
      reason: "tts-failed",
    });
    expect(two.logging.lines.at(-1)?.fields).toMatchObject({
      outcome: "tts-failed",
      failure: "server",
      status: 503,
    });
    expect(tts.calls).toHaveLength(1);
  });

  describe("the deadline: a cue that is not ready in 5 s is never played late", () => {
    it("drops it when the model is slow", async () => {
      const { maker, manual, logging } = setup();
      llm.queue({ hang: true });
      const pending = maker.make(ctx, model("step-start"), never);
      await until(() => llm.calls.length === 1);
      // The deadline is the injected timers' one-shot at 5000 ms; nothing has happened yet.
      expect(manual.pending).toEqual([{ kind: "after", ms: 5000 }]);
      manual.tick();
      expect(await pending).toMatchObject({ ok: false, reason: "deadline" });
      expect(tts.calls).toHaveLength(0);
      expect(logging.lines.at(-1)?.fields.outcome).toBe("deadline");
      expect(manual.pending).toEqual([]);
    });

    it("drops it when the speech service is slow", async () => {
      const { maker, manual } = setup();
      tts.queue({ hang: true });
      const pending = maker.make(ctx, model("step-start"), never);
      await until(() => tts.calls.length === 1);
      manual.tick();
      expect(await pending).toMatchObject({ ok: false, reason: "deadline" });
    });

    it("is a setting, and a cue that is ready in time does not leave a timer behind", async () => {
      const { maker, manual } = setup({ deadlineMs: 2500 });
      const pending = maker.make(ctx, model("two-minutes"), never);
      expect(manual.pending).toEqual([{ kind: "after", ms: 2500 }]);
      expect(await pending).toMatchObject({ ok: true });
      expect(manual.pending).toEqual([]);
    });
  });

  it("drops the cue when the session ends or the rider mutes the coach while it is made", async () => {
    const { maker } = setup();
    llm.queue({ hang: true });
    const controller = new AbortController();
    const pending = maker.make(ctx, model("step-start"), controller.signal);
    await until(() => llm.calls.length === 1);
    controller.abort();
    expect(await pending).toMatchObject({ ok: false, reason: "aborted" });
    expect(tts.calls).toHaveLength(0);
    // Already aborted before it starts: nothing is asked of anyone.
    const done = new AbortController();
    done.abort();
    expect(await maker.make(ctx, model("two-minutes"), done.signal)).toMatchObject({
      ok: false,
      reason: "aborted",
    });
  });

  it("logs the trigger, the outcome and the milliseconds, and never the line, a reading or a key", async () => {
    const { maker, logging } = setup();
    const made = await maker.make(ctx, model("step-start"), never);
    llm.queue({ text: "NO_CUE" });
    await maker.make(ctx, model("two-minutes"), never);
    llm.queue({ text: "Push 310 watts for the whole interval now!" });
    await maker.make(ctx, model("power-drift"), never);
    expect(made.ok).toBe(true);
    const text = logging.text();
    expect(logging.lines.map((l) => l.fields.outcome)).toEqual(["made", "silence", "rejected"]);
    expect(logging.lines[0]?.fields).toMatchObject({
      sessionId: "ls-test",
      trigger: "step-start",
      outcome: "made",
      llmMs: 100,
      ttsMs: 100,
      totalMs: 500,
    });
    // How many violations of each kind, not which.
    expect(logging.lines[2]?.fields.violations).toMatchObject({ number: 1 });
    for (const secret of [
      "start smooth",
      "Settle",
      "310",
      "285",
      "262",
      "161",
      FAKE_LLM_KEY,
      FAKE_TTS_KEY,
      llm.url,
      tts.url,
    ]) {
      expect(text, secret).not.toContain(secret);
    }
  });
});

describe("what the audio is", () => {
  it("is a few bytes of a made-up MP3 in the fake, so a test can tell which line it was made from", () => {
    expect(textOfAudio(audioOf("Go."))).toBe("Go.");
  });
});
