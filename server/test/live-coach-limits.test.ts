import { describe, expect, it } from "vitest";
import {
  ATTEMPT_COOLDOWN_SECONDS,
  type CueRecord,
  checkLimits,
  DEFAULT_LIVE_LIMITS,
  LIMIT_ORDER,
  type LimitContext,
  type LimitRule,
  type LimitState,
  type LiveLimits,
  limitsFrom,
  MUST_SAY_MIN_GAP_SECONDS,
} from "../src/live-coach/limits.ts";

/**
 * The limits on talking (spec §12.1, ADR 0038): one test for each row of the table, and the order they are checked in. Pure functions of
 * a state and a clock the test supplies: nothing here is real time.
 */

const NOW = 10_000_000;
const sec = (n: number): number => n * 1000;

interface Scenario {
  state: {
    muted: boolean;
    busy: boolean;
    cues: CueRecord[];
    lastUnproductive: Map<string, number>;
  };
  context: LimitContext & { recoverySteps: Set<number> };
  candidate: {
    trigger: "power-drift" | "must-say" | "two-minutes";
    stepIndex: number;
    aboutNextStep: boolean;
  };
  limits: LiveLimits;
}

/** A rider in step 1 with nothing said yet: every rule allows a power-drift cue. */
function scenario(): Scenario {
  const recoverySteps = new Set<number>();
  return {
    state: { muted: false, busy: false, cues: [], lastUnproductive: new Map() },
    context: {
      now: NOW,
      timeLeftSeconds: 300,
      isRecovery: (index) => recoverySteps.has(index),
      recoverySteps,
    },
    candidate: { trigger: "power-drift", stepIndex: 1, aboutNextStep: false },
    limits: { ...DEFAULT_LIVE_LIMITS },
  };
}

const decide = (s: Scenario): string => {
  // The cues are kept oldest first, as the session keeps them.
  s.state.cues.sort((a, b) => a.at - b.at);
  const result = checkLimits(s.candidate, s.state as LimitState, s.context, s.limits);
  return result.allowed ? "allowed" : result.rule;
};

const cue = (secondsAgo: number, trigger: CueRecord["trigger"], stepIndex: number): CueRecord => ({
  at: NOW - sec(secondsAgo),
  trigger,
  stepIndex,
});

/** What makes each rule hold a power-drift cue in step 1 back, and nothing else. */
const BREAKERS: Record<LimitRule, (s: Scenario) => void> = {
  muted: (s) => {
    s.state.muted = true;
  },
  busy: (s) => {
    s.state.busy = true;
  },
  "final-seconds": (s) => {
    s.context.timeLeftSeconds = 10;
  },
  "recovery-step": (s) => {
    s.context.recoverySteps.add(1);
    s.state.cues.push(cue(200, "two-minutes", 1));
  },
  "trigger-per-step": (s) => {
    s.state.cues.push(cue(1200, "power-drift", 1), cue(800, "power-drift", 1));
  },
  "trigger-cooldown": (s) => {
    s.state.cues.push(cue(120, "power-drift", 0));
  },
  gap: (s) => {
    s.state.cues.push(cue(30, "cadence-sag", 0));
  },
  "per-hour": (s) => {
    for (let i = 0; i < 12; i++) s.state.cues.push(cue(100 + i * 200, "two-minutes", 7));
  },
  "attempt-cooldown": (s) => {
    s.state.lastUnproductive.set("power-drift", NOW - sec(30));
  },
};

describe("the table of limits, one row at a time", () => {
  it("lets a cue through when nothing holds it back", () => {
    expect(decide(scenario())).toBe("allowed");
  });

  it("muted: a muted session says nothing", () => {
    const s = scenario();
    s.state.muted = true;
    expect(decide(s)).toBe("muted");
  });

  it("one at a time: a cue is being made", () => {
    const s = scenario();
    s.state.busy = true;
    expect(decide(s)).toBe("busy");
  });

  describe("final 15 s of a step: silent", () => {
    it("holds a cue back at 15 s left and lets one through at 16 s", () => {
      const s = scenario();
      s.context.timeLeftSeconds = 15;
      expect(decide(s)).toBe("final-seconds");
      s.context.timeLeftSeconds = 16;
      expect(decide(s)).toBe("allowed");
      s.context.timeLeftSeconds = 0;
      expect(decide(s)).toBe("final-seconds");
    });

    it("does not hold back a cue about the step that is about to begin: it is triggered early on purpose", () => {
      const s = scenario();
      s.context.timeLeftSeconds = 3;
      s.candidate = { trigger: "two-minutes", stepIndex: 2, aboutNextStep: true };
      expect(decide(s)).toBe("allowed");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_SILENT_FINAL_SECONDS: 30 });
      s.context.timeLeftSeconds = 25;
      expect(decide(s)).toBe("final-seconds");
      s.limits = limitsFrom({ LIVE_COACH_SILENT_FINAL_SECONDS: 0 });
      s.context.timeLeftSeconds = 1;
      expect(decide(s)).toBe("allowed");
    });
  });

  describe("recovery steps: one cue at most", () => {
    it("lets the first cue of a recovery step through and holds the second back, whatever its trigger", () => {
      const s = scenario();
      s.context.recoverySteps.add(1);
      expect(decide(s)).toBe("allowed");
      s.state.cues.push(cue(400, "two-minutes", 1));
      expect(decide(s)).toBe("recovery-step");
    });

    it("counts the cues of this step only", () => {
      const s = scenario();
      s.context.recoverySteps.add(1);
      s.state.cues.push(cue(400, "two-minutes", 0));
      expect(decide(s)).toBe("allowed");
    });

    it("does not touch a step that is not a recovery", () => {
      const s = scenario();
      s.state.cues.push(cue(400, "two-minutes", 1));
      expect(decide(s)).toBe("allowed");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_RECOVERY_MAX_CUES: 2 });
      s.context.recoverySteps.add(1);
      s.state.cues.push(cue(400, "two-minutes", 1));
      expect(decide(s)).toBe("allowed");
      s.state.cues.push(cue(250, "cadence-sag", 1));
      expect(decide(s)).toBe("recovery-step");
    });
  });

  describe("the same trigger: twice per step", () => {
    it("holds the third back and lets the second through", () => {
      const s = scenario();
      s.state.cues.push(cue(1200, "power-drift", 1));
      expect(decide(s)).toBe("allowed");
      s.state.cues.push(cue(800, "power-drift", 1));
      expect(decide(s)).toBe("trigger-per-step");
    });

    it("is counted for each step on its own, and for each trigger on its own", () => {
      const s = scenario();
      s.state.cues.push(cue(1200, "power-drift", 0), cue(800, "power-drift", 0));
      expect(decide(s)).toBe("allowed");
      const t = scenario();
      t.state.cues.push(cue(1200, "cadence-sag", 1), cue(800, "cadence-sag", 1));
      expect(decide(t)).toBe("allowed");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_MAX_PER_TRIGGER_PER_STEP: 1 });
      s.state.cues.push(cue(1200, "power-drift", 1));
      expect(decide(s)).toBe("trigger-per-step");
    });
  });

  describe("the same trigger: a cooldown of 3 minutes", () => {
    it("holds it back at 179 s and lets it through at 180 s, in another step too", () => {
      const s = scenario();
      s.state.cues.push(cue(179, "power-drift", 0));
      expect(decide(s)).toBe("trigger-cooldown");
      s.state.cues.length = 0;
      s.state.cues.push(cue(180, "power-drift", 0));
      expect(decide(s)).toBe("allowed");
    });

    it("looks at the last cue of the trigger, not at the last cue", () => {
      const s = scenario();
      s.state.cues.push(cue(150, "power-drift", 0), cue(100, "cadence-sag", 0));
      expect(decide(s)).toBe("trigger-cooldown");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0 });
      s.state.cues.push(cue(100, "power-drift", 0));
      expect(decide(s)).toBe("allowed");
    });
  });

  describe("the gap between any two cues: 60 s", () => {
    it("holds a cue back at 59 s and lets it through at 60 s, whatever the triggers", () => {
      const s = scenario();
      s.state.cues.push(cue(59, "cadence-sag", 0));
      expect(decide(s)).toBe("gap");
      s.state.cues.length = 0;
      s.state.cues.push(cue(60, "cadence-sag", 0));
      expect(decide(s)).toBe("allowed");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_MIN_GAP_SECONDS: 120 });
      s.state.cues.push(cue(100, "cadence-sag", 0));
      expect(decide(s)).toBe("gap");
      s.limits = limitsFrom({ LIVE_COACH_MIN_GAP_SECONDS: 0 });
      expect(decide(s)).toBe("allowed");
    });
  });

  describe("cues per hour: 12", () => {
    const spaced = (count: number, firstAgo = 100): CueRecord[] =>
      Array.from({ length: count }, (_v, i) => cue(firstAgo + i * 200, "two-minutes", 7 + i));

    it("lets the twelfth through and holds the thirteenth back", () => {
      const s = scenario();
      s.state.cues.push(...spaced(11));
      expect(decide(s)).toBe("allowed");
      const t = scenario();
      t.state.cues.push(...spaced(12));
      expect(decide(t)).toBe("per-hour");
    });

    it("is a rolling hour: a cue older than 3600 s no longer counts", () => {
      const s = scenario();
      // Twelve cues, the oldest 3601 s ago.
      s.state.cues.push(...spaced(11), cue(3601, "two-minutes", 99));
      expect(decide(s)).toBe("allowed");
      const t = scenario();
      t.state.cues.push(...spaced(11), cue(3599, "two-minutes", 99));
      expect(decide(t)).toBe("per-hour");
    });

    it("is a setting", () => {
      const s = scenario();
      s.limits = limitsFrom({ LIVE_COACH_MAX_CUES_PER_HOUR: 2 });
      s.state.cues.push(...spaced(2));
      expect(decide(s)).toBe("per-hour");
    });
  });

  describe("a trigger that produced no cue is not tried again for a minute", () => {
    it("holds it back at 59 s and lets it through at 60 s, for that trigger only", () => {
      const s = scenario();
      s.state.lastUnproductive.set("power-drift", NOW - sec(ATTEMPT_COOLDOWN_SECONDS - 1));
      expect(decide(s)).toBe("attempt-cooldown");
      s.state.lastUnproductive.set("power-drift", NOW - sec(ATTEMPT_COOLDOWN_SECONDS));
      expect(decide(s)).toBe("allowed");
      s.state.lastUnproductive.clear();
      s.state.lastUnproductive.set("cadence-sag", NOW - sec(5));
      expect(decide(s)).toBe("allowed");
    });
  });
});

describe("the order the limits are checked in", () => {
  it("is the order of the table", () => {
    expect(LIMIT_ORDER).toEqual([
      "muted",
      "busy",
      "final-seconds",
      "recovery-step",
      "trigger-per-step",
      "trigger-cooldown",
      "gap",
      "per-hour",
      "attempt-cooldown",
    ]);
  });

  it("holds a cue back for each rule on its own", () => {
    for (const rule of LIMIT_ORDER) {
      const s = scenario();
      BREAKERS[rule](s);
      expect(decide(s), rule).toBe(rule);
    }
  });

  it("names the earlier rule when a cue breaks two of them, for every pair", () => {
    for (const [i, first] of LIMIT_ORDER.entries()) {
      for (const second of LIMIT_ORDER.slice(i + 1)) {
        const s = scenario();
        BREAKERS[first](s);
        BREAKERS[second](s);
        expect(decide(s), `${first} before ${second}`).toBe(first);
      }
    }
  });

  it("names the earliest rule when a cue breaks all of them", () => {
    const s = scenario();
    for (const rule of LIMIT_ORDER) BREAKERS[rule](s);
    expect(decide(s)).toBe("muted");
    s.state.muted = false;
    expect(decide(s)).toBe("busy");
    s.state.busy = false;
    expect(decide(s)).toBe("final-seconds");
    s.context.timeLeftSeconds = 300;
    expect(decide(s)).toBe("recovery-step");
    s.context.recoverySteps.clear();
    expect(decide(s)).toBe("trigger-per-step");
  });
});

describe("the agent's own lines (must_say)", () => {
  const mustSay = (s: Scenario): void => {
    s.candidate = { trigger: "must-say", stepIndex: 1, aboutNextStep: false };
  };

  it("are held back by the mute, a cue in the making and the silent final seconds", () => {
    const a = scenario();
    mustSay(a);
    a.state.muted = true;
    expect(decide(a)).toBe("muted");
    const b = scenario();
    mustSay(b);
    b.state.busy = true;
    expect(decide(b)).toBe("busy");
    const c = scenario();
    mustSay(c);
    c.context.timeLeftSeconds = 15;
    expect(decide(c)).toBe("final-seconds");
  });

  it("are not held back by the rules that bound the model's chatter", () => {
    const s = scenario();
    mustSay(s);
    s.context.recoverySteps.add(1);
    s.state.cues.push(cue(1000, "must-say", 1), cue(900, "must-say", 1), cue(800, "must-say", 1));
    for (let i = 0; i < 12; i++) s.state.cues.push(cue(100 + i * 200, "two-minutes", 7));
    s.state.lastUnproductive.set("must-say", NOW - sec(5));
    expect(decide(s)).toBe("allowed");
  });

  it("keep a short gap so that two voices never overlap, not the 60 s of the others", () => {
    const s = scenario();
    mustSay(s);
    s.state.cues.push(cue(MUST_SAY_MIN_GAP_SECONDS - 1, "power-drift", 1));
    expect(decide(s)).toBe("gap");
    s.state.cues.length = 0;
    s.state.cues.push(cue(MUST_SAY_MIN_GAP_SECONDS, "power-drift", 1));
    expect(decide(s)).toBe("allowed");
  });

  it("make a model's cue wait the full gap after them", () => {
    const s = scenario();
    s.state.cues.push(cue(30, "must-say", 1));
    expect(decide(s)).toBe("gap");
  });
});

describe("the settings", () => {
  it("have the defaults of the spec", () => {
    expect(limitsFrom({})).toEqual({
      cueMaxChars: 80,
      silentFinalSeconds: 15,
      recoveryMaxCues: 1,
      maxPerTriggerPerStep: 2,
      triggerCooldownSeconds: 180,
      minGapSeconds: 60,
      maxCuesPerHour: 12,
    });
  });

  it("are read from the configuration", () => {
    expect(
      limitsFrom({
        LIVE_COACH_CUE_MAX_CHARS: 60,
        LIVE_COACH_SILENT_FINAL_SECONDS: 20,
        LIVE_COACH_RECOVERY_MAX_CUES: 0,
        LIVE_COACH_MAX_PER_TRIGGER_PER_STEP: 3,
        LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 90,
        LIVE_COACH_MIN_GAP_SECONDS: 30,
        LIVE_COACH_MAX_CUES_PER_HOUR: 20,
      }),
    ).toEqual({
      cueMaxChars: 60,
      silentFinalSeconds: 20,
      recoveryMaxCues: 0,
      maxPerTriggerPerStep: 3,
      triggerCooldownSeconds: 90,
      minGapSeconds: 30,
      maxCuesPerHour: 20,
    });
  });
});
