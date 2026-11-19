import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  hasLiveCoachFields,
  LIVE_COACH_OFF_REASONS,
  LIVE_CUE_AUDIO_MAX_BYTES,
  LIVE_CUE_MAX_CHARS,
  LIVE_CUE_TRIGGERS,
  LIVE_STREAM_END_REASONS,
  type LiveCue,
  type LiveSnapshot,
  liveTextLength,
  mustSayOf,
  validateLiveCue,
  validateLiveCuePlayed,
  validateLiveSessionEnd,
  validateLiveSessionEndResponse,
  validateLiveSessionStart,
  validateLiveSessionStartResponse,
  validateLiveSnapshot,
  validateLiveStreamEnd,
  validateWorkout,
  validateWorkoutsResponse,
  type Workout,
  withoutLiveCoach,
  workoutHash,
  workoutStructure,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/live-coach/", import.meta.url).pathname;

function load(kind: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, kind, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

function valid<T>(kind: string, name: string): T {
  return JSON.parse(readFileSync(join(fixtures, kind, "valid", `${name}.json`), "utf8")) as T;
}

type Check = (input: unknown) => { ok: boolean; errors?: string[] };

/** Every directory of fixtures that is a pair of valid/ and invalid/ with the validator that reads it, and how many it must hold. */
const KINDS: [string, Check, number, number][] = [
  ["workout", validateWorkout, 6, 25],
  ["workouts-response", validateWorkoutsResponse, 2, 2],
  ["session-start", validateLiveSessionStart, 4, 12],
  ["session-start-response", validateLiveSessionStartResponse, 7, 10],
  ["snapshot", validateLiveSnapshot, 9, 35],
  ["cue", validateLiveCue, 6, 30],
  ["stream-end", validateLiveStreamEnd, 3, 3],
  ["session-end", validateLiveSessionEnd, 5, 4],
  ["session-end-response", validateLiveSessionEndResponse, 2, 5],
  ["cue-played", validateLiveCuePlayed, 2, 4],
];

describe.each(KINDS)("live coach fixtures: %s", (kind, check, minValid, minInvalid) => {
  it("has fixtures in both directories", () => {
    expect(load(kind, "valid").length).toBeGreaterThanOrEqual(minValid);
    expect(load(kind, "invalid").length).toBeGreaterThanOrEqual(minInvalid);
  });

  it.each(load(kind, "valid"))("accepts valid/%s", (_name, body) => {
    const result = check(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load(kind, "invalid"))("rejects invalid/%s", (_name, body) => {
    const result = check(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors?.length).toBeGreaterThan(0);
  });
});

describe("the workout's live-coach fields", () => {
  const spoken = valid<Workout>("workout", "brief-and-must-say-in-a-repeat");

  it("carries the example of the spec: a brief and a line at the start of a step", () => {
    expect(spoken.live_coach).toBe(true);
    expect(spoken.coach_brief).toMatch(/^Threshold progression/);
    const repeat = spoken.steps[1];
    expect(repeat?.type).toBe("repeat");
    if (repeat?.type !== "repeat") return;
    expect(mustSayOf(repeat.steps[0] as never)).toEqual([
      { at: "start", text: "First one. Ease into it." },
    ]);
    expect(mustSayOf(repeat)).toEqual([]);
  });

  it("is additive: a workout with none of the fields is as valid as before", () => {
    const plain: Workout = {
      schema: 1,
      id: "w",
      name: "Plain",
      steps: [{ type: "free", duration: 60 }],
    };
    expect(validateWorkout(plain).ok).toBe(true);
    expect(hasLiveCoachFields(plain)).toBe(false);
    expect(hasLiveCoachFields(spoken)).toBe(true);
  });

  it("measures a line and the brief in characters, not UTF-16 units", () => {
    expect(liveTextLength("😀".repeat(80))).toBe(80);
    const emoji: Workout = {
      schema: 1,
      id: "w",
      name: "Emoji",
      live_coach: true,
      coach_brief: "😀".repeat(1000),
      steps: [
        {
          type: "steady",
          duration: 300,
          target: 0.9,
          must_say: [{ at: "start", text: "😀".repeat(LIVE_CUE_MAX_CHARS) }],
        },
      ],
    };
    expect(validateWorkout(emoji)).toMatchObject({ ok: true });
    const tooLong = structuredClone(emoji);
    const line = (tooLong.steps[0] as { must_say: { text: string }[] }).must_say[0];
    if (line === undefined) throw new Error("expected a line");
    line.text = "😀".repeat(81);
    expect(validateWorkout(tooLong).ok).toBe(false);
  });

  it("says which rule a consistent-looking workout breaks, with the path and no text of a line", () => {
    const errors = (name: string): string => {
      const found = load("workout", "invalid").find(([file]) => file === `${name}.json`);
      const result = validateWorkout(found?.[1]);
      return result.ok ? "" : result.errors.join("; ");
    };
    expect(errors("must-say-at-the-end-of-the-step")).toMatch(
      /\/steps\/0\/must_say\/0\/at is 600, the step lasts 600 s/,
    );
    expect(errors("must-say-in-a-repeat-beyond-its-step")).toMatch(
      /\/steps\/0\/steps\/0\/must_say\/0\/at is 650/,
    );
    expect(errors("must-say-two-lines-at-the-same-moment")).toMatch(/same moment/);
    expect(errors("must-say-two-lines-at-start")).toMatch(/same moment/);
    // A line's words are the agent's; no message quotes them.
    for (const name of [
      "must-say-at-the-end-of-the-step",
      "must-say-two-lines-at-the-same-moment",
    ]) {
      expect(errors(name)).not.toMatch(/Go\.|Again\./);
    }
  });

  it("is not part of a workout's structure: the hash ignores the brief, the flag and every line", () => {
    const stripped = withoutLiveCoach(spoken);
    expect(workoutHash(spoken)).toBe(workoutHash(stripped));
    expect(workoutStructure(spoken)).toBe(workoutStructure(stripped));
  });

  it("is taken off for the copy a ride keeps, and nothing else changes", () => {
    const stripped = withoutLiveCoach(spoken);
    expect(stripped.live_coach).toBeUndefined();
    expect(stripped.coach_brief).toBeUndefined();
    expect(hasLiveCoachFields(stripped)).toBe(false);
    expect(JSON.stringify(stripped)).not.toMatch(/must_say|live_coach|coach_brief/);
    expect(stripped.name).toBe(spoken.name);
    expect(stripped.description).toBe(spoken.description);
    // The input is not changed.
    expect(spoken.live_coach).toBe(true);
    // Labels, cadence and every other field of the steps are kept.
    const repeat = stripped.steps[1];
    if (repeat?.type !== "repeat") throw new Error("expected a repeat");
    expect(repeat.steps[0]).toEqual({
      type: "steady",
      duration: 600,
      target: 0.95,
      label: "Threshold",
    });
    expect(validateWorkout(stripped).ok).toBe(true);
  });
});

describe("the answer to a start", () => {
  it("has a fixture for every reason the coach can be off and the constants list them", () => {
    const reasons = new Set(
      load("session-start-response", "valid")
        .map(([, body]) => (body as { coach: { reason: string | null } }).coach.reason)
        .filter((reason) => reason !== null),
    );
    expect([...reasons].sort()).toEqual([...LIVE_COACH_OFF_REASONS].sort());
  });
});

describe("the snapshot", () => {
  const snapshot = valid<LiveSnapshot>("snapshot", "steady-step");

  it("is small: a typical snapshot is well under a kilobyte", () => {
    expect(JSON.stringify(snapshot).length).toBeLessThan(500);
  });

  it("never puts a reading in an error message", () => {
    const bad = { ...snapshot, power: 987654, heartRate: 1234567, cadence: 7654321 };
    const result = validateLiveSnapshot(bad);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.errors.join(" ")).not.toMatch(/987654|1234567|7654321/);
  });

  it("is judged on what its step can have", () => {
    expect(
      validateLiveSnapshot({ ...snapshot, step: { ...snapshot.step, timeLeftSeconds: 600 } }).ok,
    ).toBe(true);
    expect(
      validateLiveSnapshot({ ...snapshot, step: { ...snapshot.step, timeLeftSeconds: 601 } }).ok,
    ).toBe(false);
  });
});

describe("the cue", () => {
  const cue = valid<LiveCue>("cue", "a-model-line");
  const audioOf = (bytes: number): LiveCue["audio"] => ({
    format: "mp3",
    base64: Buffer.alloc(bytes, 7).toString("base64"),
  });

  it("holds a speech of the largest size and refuses one byte more", () => {
    expect(validateLiveCue({ ...cue, audio: audioOf(LIVE_CUE_AUDIO_MAX_BYTES) })).toMatchObject({
      ok: true,
    });
    const over = validateLiveCue({ ...cue, audio: audioOf(LIVE_CUE_AUDIO_MAX_BYTES + 1) });
    expect(over.ok).toBe(false);
  });

  it("is about 80 KB as base64 for a cue of 80 characters at the owner's TTS rate", () => {
    // 27 KB for 34 characters (checked on the owner's server): 80 characters is about 64 KB, 86 KB as base64.
    const bytes = Math.ceil((27_000 / 34) * 80);
    const base64Length = Math.ceil(bytes / 3) * 4;
    expect(bytes).toBeLessThan(LIVE_CUE_AUDIO_MAX_BYTES / 4);
    expect(base64Length).toBeLessThan(100_000);
  });

  it("names the triggers the service has today", () => {
    expect([...LIVE_CUE_TRIGGERS].sort()).toEqual(
      [
        "cadence-sag",
        "heart-rate-climb",
        "must-say",
        "power-drift",
        "step-start",
        "strong-finish",
        "two-minutes",
      ].sort(),
    );
    for (const trigger of LIVE_CUE_TRIGGERS) {
      expect(validateLiveCue({ ...cue, trigger }).ok, trigger).toBe(true);
    }
  });
});

describe("the event stream as it goes over the wire", () => {
  const text = readFileSync(join(fixtures, "stream", "cues.sse.txt"), "utf8");

  /** The events of a stream: blocks separated by a blank line, `field: value` lines, comments (`:`) are not events. */
  function parse(stream: string): { id?: string; event?: string; data?: string; retry?: string }[] {
    return stream
      .split("\n\n")
      .map((block) => block.split("\n").filter((line) => line !== "" && !line.startsWith(":")))
      .filter((lines) => lines.length > 0)
      .map((lines) => {
        const event: Record<string, string> = {};
        for (const line of lines) {
          const colon = line.indexOf(":");
          event[line.slice(0, colon)] = line.slice(colon + 1).replace(/^ /, "");
        }
        return event;
      });
  }

  const events = parse(text);

  it("starts with the reconnection delay, has comments, ids on its cues and ends with `end`", () => {
    expect(events[0]).toEqual({ retry: "3000" });
    expect(text).toContain(": open\n");
    expect(text).toContain(": hb\n");
    expect(events.at(-1)?.event).toBe("end");
    expect(text.endsWith("\n\n")).toBe(true);
  });

  it("carries cues that validate, with the SSE id equal to the cue's own and increasing", () => {
    const cues = events.filter((event) => event.event === "cue");
    expect(cues.length).toBeGreaterThanOrEqual(2);
    let last = 0;
    for (const event of cues) {
      const body = JSON.parse(event.data ?? "null") as LiveCue;
      expect(validateLiveCue(body), JSON.stringify(validateLiveCue(body))).toMatchObject({
        ok: true,
      });
      expect(event.id).toBe(String(body.id));
      expect(body.id).toBeGreaterThan(last);
      last = body.id;
    }
  });

  it("ends with a valid `end`, which has no id (it is not a cue to resume from)", () => {
    const end = events.at(-1);
    expect(end?.id).toBeUndefined();
    const body = JSON.parse(end?.data ?? "null") as { reason: string };
    expect(validateLiveStreamEnd(body)).toMatchObject({ ok: true });
    expect(LIVE_STREAM_END_REASONS).toContain(body.reason);
  });
});
