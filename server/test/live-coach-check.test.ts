import type { LiveSnapshot } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { createValidator } from "../src/insights/validator.ts";
import { checkCue, cleanCue, isRepeat, NO_CUE } from "../src/live-coach/cue-check.ts";
import { buildCueFacts, type CueFacts, type TriggerDetail } from "../src/live-coach/cue-facts.ts";
import { messagesFor, type PromptContext } from "../src/live-coach/prompt.ts";
import { livePlan } from "./helpers/live.ts";

/**
 * What a cue is made of and checked against (spec §12.1, ADR 0038): the facts the model is given, the prompt, and the check that enforces
 * the rules the model may not be trusted with: length, numbers it was not given (it never calculates), words that are not allowed, repeats,
 * and the answer that nothing is worth saying. Every number is invented.
 */

const plan = livePlan();
const FTP = 300;

function snapshot(over: Partial<LiveSnapshot> = {}): LiveSnapshot {
  return {
    seq: 12,
    clock: 1_795_000_060_000,
    elapsedSeconds: 600,
    paused: false,
    muted: false,
    step: {
      index: 1,
      type: "steady",
      label: "Threshold",
      durationSeconds: 600,
      timeLeftSeconds: 120,
      targetWatts: 285,
    },
    stepCount: 6,
    power: 262,
    heartRate: 161,
    cadence: 91,
    compliancePercent: 92,
    stallGuardActive: false,
    ...over,
  };
}

const DETAILS: Record<string, TriggerDetail> = {
  "step-start": { trigger: "step-start", step: plan.steps[3] as never },
  "two-minutes": { trigger: "two-minutes" },
  "power-drift": {
    trigger: "power-drift",
    direction: "below",
    averageWatts: 258,
    targetWatts: 285,
    seconds: 20,
  },
  "cadence-sag": { trigger: "cadence-sag", averageRpm: 63, belowRpm: 70 },
  "heart-rate-climb": {
    trigger: "heart-rate-climb",
    averageBpm: 165,
    previousAverageBpm: 150,
    previousDate: "2026-11-12",
  },
  "strong-finish": { trigger: "strong-finish" },
};

const factsFor = (trigger: string, snap: LiveSnapshot = snapshot()): CueFacts =>
  buildCueFacts({
    trigger: DETAILS[trigger] as TriggerDetail,
    workoutName: "Threshold progression",
    snapshot: snap,
    plan,
    ftp: FTP,
  });

/** Every number in a document, with the key it sits under. */
function numbers(node: unknown, key = ""): { key: string; value: number }[] {
  if (typeof node === "number") return [{ key, value: node }];
  if (Array.isArray(node)) return node.flatMap((item) => numbers(item, key));
  if (typeof node === "object" && node !== null) {
    return Object.entries(node).flatMap(([k, v]) => (k === "quotable" ? [] : numbers(v, k)));
  }
  return [];
}

describe("the facts of a cue", () => {
  it.each(Object.keys(DETAILS))(
    "holds only numbers that are quotable, for the trigger %s",
    (trigger) => {
      const facts = factsFor(trigger);
      const quotable = new Set(facts.quotable.map((q) => q.value));
      for (const { key, value } of numbers(facts)) {
        if (key === "schema") continue;
        expect(quotable.has(value), `${trigger}: ${key} = ${value}`).toBe(true);
      }
    },
  );

  it("works out the watts of a step from the FTP, in whole watts, and leaves them out without one", () => {
    const facts = factsFor("step-start");
    expect(facts.detail).toMatchObject({
      label: "Threshold",
      targetWatts: 285,
      targetPercentFtp: 95,
      minutes: 10,
      hardStepNumber: 2,
      hardStepsInSession: 2,
      isLastHardStep: true,
    });
    const without = buildCueFacts({
      trigger: DETAILS["step-start"] as TriggerDetail,
      workoutName: "W",
      snapshot: snapshot(),
      plan,
      ftp: null,
    });
    expect((without.detail as { targetWatts: unknown }).targetWatts).toBeNull();
    expect((without.detail as { targetPercentFtp: unknown }).targetPercentFtp).toBe(95);
  });

  it("does the arithmetic itself: how far off target, how much higher than last time", () => {
    expect(factsFor("power-drift").detail).toMatchObject({
      direction: "below",
      averageWatts: 258,
      targetWatts: 285,
      percentOff: 9,
    });
    expect(factsFor("heart-rate-climb").detail).toMatchObject({
      averageBpm: 165,
      previousAverageBpm: 150,
      higherByBpm: 15,
      previousDate: "2026-11-12",
    });
  });

  it("says what the rider is doing now and which step it is", () => {
    const facts = factsFor("two-minutes");
    expect(facts.now).toMatchObject({
      stepNumber: 2,
      stepsInSession: 6,
      label: "Threshold",
      minutesLeft: 2,
      secondsLeft: 120,
      targetWatts: 285,
      powerWatts: 262,
      heartRateBpm: 161,
      cadenceRpm: 91,
      compliancePercent: 92,
      stallGuardActive: false,
    });
  });

  it("is deterministic: the same inputs give the same bytes", () => {
    expect(JSON.stringify(factsFor("power-drift"))).toBe(JSON.stringify(factsFor("power-drift")));
  });
});

describe("the prompt", () => {
  const context: PromptContext = {
    language: "French",
    maxChars: 80,
    workoutName: "Threshold progression",
    workoutDescription: "Two ten-minute efforts at threshold.",
    brief: "He starts too hard and fades. Calm, technical tone.",
    notebook: [
      {
        title: "Rider profile and constraints",
        text: "Works early, rides before the children wake.",
      },
      { title: "What works", text: "Short, calm cues." },
      { title: "Methodology", text: "" },
    ],
  };

  it("puts what is the same all ride in the system message and what is new in the user message", () => {
    const [system, user] = messagesFor(context, factsFor("two-minutes"), ["First line."]);
    expect(system?.role).toBe("system");
    expect(user?.role).toBe("user");
    expect(system?.content).toContain("French");
    expect(system?.content).toMatch(/at most 80 characters/i);
    expect(system?.content).toContain("Threshold progression");
    expect(system?.content).toContain("Two ten-minute efforts at threshold.");
    expect(system?.content).toContain("He starts too hard and fades");
    expect(system?.content).toContain("Rider profile and constraints");
    expect(system?.content).toContain("rides before the children wake");
    expect(system?.content).toContain("What works");
    // An empty section adds nothing.
    expect(system?.content).not.toContain("Methodology");
    expect(user?.content).toContain("- First line.");
    expect(user?.content).toContain("Trigger: two-minutes.");
    expect(user?.content).toContain("Facts (JSON):");
  });

  it("tells the model the rules: instruction not filler, no repeats, no arithmetic, nothing to change, and the way to say nothing", () => {
    const [system] = messagesFor(context, factsFor("two-minutes"), []);
    const text = system?.content ?? "";
    expect(text).toMatch(/never encouragement filler/i);
    expect(text).toMatch(/never repeat or rephrase/i);
    expect(text).toMatch(/never calculate/i);
    expect(text).toMatch(/cannot change the trainer, the targets or the workout/i);
    expect(text).toMatch(/no diagnosis/i);
    expect(text).toContain(`exactly ${NO_CUE}`);
  });

  it("lists no cue when none was said, and puts the facts last as JSON", () => {
    const [, user] = messagesFor(context, factsFor("power-drift"), []);
    expect(user?.content).toContain("Lines already said in this ride: none.");
    const json = (user?.content ?? "").split("Facts (JSON):\n")[1] ?? "";
    expect(JSON.parse(json)).toMatchObject({ kind: "live-cue", trigger: "power-drift" });
  });

  it("clips a long notebook section: the model must answer in about a second", () => {
    const long = { ...context, notebook: [{ title: "What works", text: "x".repeat(5000) }] };
    const [system] = messagesFor(long, factsFor("two-minutes"), []);
    expect((system?.content ?? "").length).toBeLessThan(3500);
  });
});

describe("the check of what the model wrote", () => {
  const validator = createValidator();
  const check = (text: string, said: string[] = [], trigger = "step-start", maxChars = 80) =>
    checkCue(text, { facts: factsFor(trigger), said, maxChars, validator });

  it("lets a good cue through, with the numbers it was given", () => {
    expect(check("Interval 2: hold 285 watts, start smooth.")).toEqual({
      ok: true,
      text: "Interval 2: hold 285 watts, start smooth.",
    });
    expect(check("Ten minutes at 285 watts. Settle in.").ok).toBe(true);
  });

  describe("nothing worth saying", () => {
    it.each([NO_CUE, "no_cue", "NO CUE", "no cue.", '"NO_CUE"', "NO_CUE\n", "Nothing: NO_CUE"])(
      "is the answer %j",
      (answer) => {
        expect(check(answer)).toEqual({ ok: false, reason: "silence" });
      },
    );

    it("is not a cue that merely contains the words", () => {
      expect(check("There is no cue to follow, only the road.").ok).toBe(true);
    });
  });

  describe("length", () => {
    it("allows 80 characters and refuses 81", () => {
      const eighty = `${"a".repeat(79)}.`;
      expect(check(eighty, [], "two-minutes").ok).toBe(true);
      expect(check(`${eighty}x`, [], "two-minutes")).toEqual({ ok: false, reason: "too-long" });
    });

    it("counts characters, not UTF-16 units", () => {
      expect(check("é".repeat(80), [], "two-minutes").ok).toBe(true);
      expect(check("😀".repeat(80), [], "two-minutes").ok).toBe(true);
    });

    it("is a setting that can be lowered", () => {
      expect(check("Keep your shoulders loose and breathe.", [], "two-minutes", 20)).toEqual({
        ok: false,
        reason: "too-long",
      });
    });

    it("refuses an empty answer", () => {
      expect(check("  ")).toEqual({ ok: false, reason: "empty" });
      expect(check('""')).toEqual({ ok: false, reason: "empty" });
    });
  });

  describe("numbers the model was not given", () => {
    it("refuses a number that is not in the facts, and says how many, never which", () => {
      const result = check("Push 310 watts now.");
      expect(result).toMatchObject({ ok: false, reason: "violations" });
      expect(JSON.stringify(result)).not.toContain("310");
      expect((result as { violations: Record<string, number> }).violations.number).toBe(1);
    });

    it("refuses a number it worked out itself: 95% of 300 is not 285.5, and 10 minutes is not 600 seconds said as 6 hundred", () => {
      expect(check("Hold 286 watts.").ok).toBe(false);
      expect(check("Hold 28 watts.").ok).toBe(false);
    });

    it("refuses a number word that is not given too", () => {
      expect(check("Seven minutes to go.", [], "two-minutes").ok).toBe(false);
      expect(check("Two minutes to go.", [], "two-minutes").ok).toBe(true);
    });

    it("lets through a number from the workout's own name", () => {
      const facts: CueFacts = { ...factsFor("two-minutes"), workout: { name: "Sweet spot 3x10" } };
      expect(
        checkCue("Sweet spot 3x10, two minutes left.", { facts, said: [], maxChars: 80, validator })
          .ok,
      ).toBe(true);
    });
  });

  describe("words that are not allowed", () => {
    it("refuses a diagnosis and an illness, counting them", () => {
      const result = check("That heart rate suggests an infection.", [], "heart-rate-climb");
      expect(result).toMatchObject({ ok: false, reason: "violations" });
      expect((result as { violations: Record<string, number> }).violations.deny).toBeGreaterThan(0);
    });

    it("does not mistake a pedal stroke for a medical word", () => {
      expect(check("Smooth pedal strokes, shoulders loose.", [], "two-minutes").ok).toBe(true);
      expect(check("Smooth pedalling strokes.", [], "two-minutes").ok).toBe(true);
    });

    it("refuses a link", () => {
      expect(check("See https://example.com for form.", [], "two-minutes").ok).toBe(false);
    });
  });

  describe("repeats", () => {
    it("refuses the same line again, however it is written", () => {
      const said = ["Interval 2: hold 285 watts, start smooth."];
      expect(check("Interval 2: hold 285 watts, start smooth.", said)).toEqual({
        ok: false,
        reason: "repeat",
      });
      expect(check("interval 2 - HOLD 285 watts; start smooth!", said)).toEqual({
        ok: false,
        reason: "repeat",
      });
    });

    it("refuses a line that says almost the same", () => {
      expect(
        isRepeat("Keep your shoulders loose and relaxed now", [
          "Keep your shoulders loose and relaxed",
        ]),
      ).toBe(true);
    });

    it("lets a line through that says something else, or only shares a few words", () => {
      expect(isRepeat("Spin it up a little", ["Keep your shoulders loose and relaxed"])).toBe(
        false,
      );
      expect(isRepeat("Hold 285 watts, shoulders down", ["Hold 285 watts, start smooth"])).toBe(
        false,
      );
      expect(isRepeat("Smooth", ["Smooth"])).toBe(true);
      expect(isRepeat("", ["Smooth"])).toBe(false);
    });

    it("compares with the agent's own lines too, which are in the list of what was said", () => {
      expect(
        check("First one. Ease into it.", ["First one. Ease into it."], "two-minutes").ok,
      ).toBe(false);
    });
  });

  describe("how a cue is spoken", () => {
    it("is one line, with no markdown and no quotation marks around it", () => {
      expect(cleanCue("**Hold it.**\n\nSteady now.")).toBe("Hold it. Steady now.");
      expect(cleanCue('"Hold it steady."')).toBe("Hold it steady.");
      expect(cleanCue("“Hold it steady.”")).toBe("Hold it steady.");
      expect(cleanCue("# Header\nText")).toBe("Header Text");
    });
  });
});
