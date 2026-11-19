import type { LiveSnapshot, Workout } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildSessionPlan,
  DEFAULT_PLAN_SETTINGS,
  isRecovery,
  type SessionPlan,
} from "../src/live-coach/structure.ts";
import {
  type Candidate,
  DEFAULT_TRIGGER_SETTINGS,
  detectCandidates,
  type PreviousAttempt,
  type SnapshotEntry,
  TRIGGER_PRIORITY,
  type TriggerSettings,
} from "../src/live-coach/triggers.ts";
import { liveWorkout } from "./helpers/live.ts";

/**
 * The trigger rules (spec §12.1, ADR 0038) in virtual time: pure functions of the snapshots and the clock the test supplies, so every
 * case is a table of what the tablet sends and what the rules find. No real clock, no network, no randomness. Every reading is invented.
 *
 * The workout is the one of the helpers: flat steps 0 warm-up (ramp, 600 s), 1 Threshold (steady, 600 s, 95% of FTP), 2 Recover
 * (300 s, 50%), 3 Threshold, 4 Recover, 5 cool-down (ramp, 300 s). At an FTP of 300 a Threshold step asks for 285 W.
 */

const plan = buildSessionPlan(liveWorkout());
const FTP = 300;
const SILENT = 15;

interface Point {
  /** Seconds since the first snapshot of the table. */
  t: number;
  step: number;
  left: number;
  power?: number | null;
  hr?: number | null;
  cad?: number | null;
  compl?: number | null;
  paused?: boolean;
  guard?: boolean;
}

function snapshotOf(p: Point, planned: SessionPlan, seq: number): LiveSnapshot {
  const step = planned.steps[p.step];
  if (step === undefined) throw new Error(`no step ${p.step}`);
  const target =
    step.fromFraction === null
      ? null
      : Math.round(((step.fromFraction + (step.toFraction ?? 0)) / 2) * FTP);
  return {
    seq,
    clock: 1_795_000_000_000 + p.t * 1000,
    elapsedSeconds: p.t,
    paused: p.paused === true,
    muted: false,
    step: {
      index: p.step,
      type: step.type,
      label: step.label,
      durationSeconds: step.durationSeconds,
      timeLeftSeconds: p.left,
      ...(target === null || step.type === "ramp" ? {} : { targetWatts: target }),
    },
    stepCount: planned.steps.length,
    ...(p.power === null ? {} : { power: p.power ?? target ?? 140 }),
    ...(p.hr === null ? {} : { heartRate: p.hr ?? 150 }),
    ...(p.cad === null ? {} : { cadence: p.cad ?? 90 }),
    ...(p.compl === null ? {} : { compliancePercent: p.compl ?? 95 }),
    stallGuardActive: p.guard === true,
  };
}

/** What the rules find after each snapshot of the table, with the snapshots so far as the history. */
function table(
  points: readonly Point[],
  options: {
    plan?: SessionPlan;
    spent?: ReadonlySet<string>;
    previous?: PreviousAttempt | null;
    settings?: Partial<TriggerSettings>;
  } = {},
): Candidate[][] {
  const planned = options.plan ?? plan;
  const history: SnapshotEntry[] = [];
  return points.map((p, i) => {
    history.push({ at: p.t * 1000, snap: snapshotOf(p, planned, i) });
    return detectCandidates({
      history,
      plan: planned,
      previous: options.previous ?? null,
      spent: options.spent ?? new Set(),
      settings: { ...DEFAULT_TRIGGER_SETTINGS, ...options.settings },
      silentFinalSeconds: SILENT,
    });
  });
}

const triggersOf = (found: Candidate[]): string[] => found.map((c) => c.trigger);
const names = (found: Candidate[][]): string[][] => found.map(triggersOf);
/** Only one kind of reason: a snapshot may hold several at once, and a test is about one. */
const kind = (found: Candidate[][], trigger: string): string[][] =>
  found.map((list) => triggersOf(list).filter((name) => name === trigger));

/** A workout of the given steps, flattened. */
const planOf = (steps: Workout["steps"]): SessionPlan =>
  buildSessionPlan({ schema: 1, id: "w", name: "Test", steps });

describe("the structure the rules read", () => {
  it("flattens repeats as the ride record does and marks hard and recovery steps", () => {
    expect(plan.steps.map((s) => [s.index, s.label, s.hard, s.recovery])).toEqual([
      [0, "Warm-up", false, false],
      [1, "Threshold", true, false],
      [2, "Recover", false, true],
      [3, "Threshold", true, false],
      [4, "Recover", false, true],
      [5, "Cool-down", false, true],
    ]);
    expect(plan.hardIndexes).toEqual([1, 3]);
    expect(plan.lastHardIndex).toBe(3);
  });

  it("defines a recovery step by a target below 60% of FTP or by a marker in its label", () => {
    expect(isRecovery("Spin", "steady", 0.59, 0.59, 0.6)).toBe(true);
    expect(isRecovery("Spin", "steady", 0.6, 0.6, 0.6)).toBe(false);
    expect(isRecovery("Easy", "steady", 0.7, 0.7, 0.6)).toBe(false);
    expect(isRecovery("Recovery spin", "steady", 0.7, 0.7, 0.6)).toBe(true);
    expect(isRecovery("Rest", "free", null, null, 0.6)).toBe(true);
    expect(isRecovery("Free ride", "free", null, null, 0.6)).toBe(false);
    // A ramp that begins low and ends at 70% is a warm-up, not a recovery; one that stays under 60% is.
    expect(isRecovery("Warm-up", "ramp", 0.45, 0.7, 0.6)).toBe(false);
    expect(isRecovery("Cool-down", "ramp", 0.55, 0.4, 0.6)).toBe(true);
  });

  it("calls a step hard from 85% of FTP and not a short one", () => {
    const planned = planOf([
      { type: "steady", duration: 300, target: 0.85 },
      { type: "steady", duration: 300, target: 0.849 },
      { type: "steady", duration: 29, target: 1.2 },
      { type: "ramp", duration: 300, from: 0.5, to: 1.3 },
      { type: "hr", duration: 600, target_bpm: 150, power_min: 100, power_max: 250 },
    ]);
    expect(planned.steps.map((s) => s.hard)).toEqual([true, false, false, true, false]);
    expect(DEFAULT_PLAN_SETTINGS.minHardSeconds).toBe(30);
  });
});

describe("a hard step begins (triggered early, from the step before)", () => {
  it("fires in the last seconds of the step before, a few seconds early", () => {
    const found = table([
      { t: 0, step: 0, left: 20 },
      { t: 5, step: 0, left: 15 },
      { t: 10, step: 0, left: 10 },
      { t: 15, step: 0, left: 5 },
    ]);
    expect(names(found)).toEqual([[], [], [], ["step-start"]]);
    const [start] = found[3] as Candidate[];
    expect(start).toMatchObject({
      trigger: "step-start",
      key: "step-start:1",
      stepIndex: 1,
      aboutNextStep: true,
    });
    expect(start?.detail).toMatchObject({ trigger: "step-start", step: { label: "Threshold" } });
  });

  it("fires at the first snapshot inside the lead, whichever second that is", () => {
    // 5 s apart, a lead of 6 s: 7 s left is outside, 2 s left is the first inside.
    expect(
      names(
        table([
          { t: 0, step: 0, left: 12 },
          { t: 5, step: 0, left: 7 },
          { t: 10, step: 0, left: 2 },
        ]),
      ),
    ).toEqual([[], [], ["step-start"]]);
    // A lead of 10 s moves it a snapshot earlier.
    expect(
      names(
        table(
          [
            { t: 0, step: 0, left: 12 },
            { t: 5, step: 0, left: 7 },
          ],
          { settings: { leadSeconds: 10 } },
        ),
      ),
    ).toEqual([[], ["step-start"]]);
  });

  it("is offered once: a key that is spent is not offered again", () => {
    const found = table([{ t: 15, step: 0, left: 5 }], { spent: new Set(["step-start:1"]) });
    expect(names(found)).toEqual([[]]);
  });

  it("is not offered for a step that is not hard, and not for a hard step too short to announce", () => {
    // Step 1 is followed by a recovery step (2).
    expect(names(table([{ t: 0, step: 1, left: 5 }]))).toEqual([[]]);
    const planned = planOf([
      { type: "steady", duration: 300, target: 0.6 },
      { type: "steady", duration: 20, target: 1.5 },
    ]);
    expect(names(table([{ t: 0, step: 0, left: 5 }], { plan: planned }))).toEqual([[]]);
  });

  it("fires at the very start of a hard first step, and a little late for an app that joined late", () => {
    const first = planOf([{ type: "steady", duration: 600, target: 0.95, label: "Go" }]);
    expect(names(table([{ t: 0, step: 0, left: 600 }], { plan: first }))).toEqual([["step-start"]]);
    const [a] = table([{ t: 0, step: 0, left: 600 }], { plan: first })[0] as Candidate[];
    expect(a).toMatchObject({ stepIndex: 0, aboutNextStep: false });
    // Ten seconds in is still the start; eleven is not.
    expect(names(table([{ t: 10, step: 1, left: 590 }]))).toEqual([["step-start"]]);
    expect(names(table([{ t: 11, step: 1, left: 589 }]))).toEqual([[]]);
  });

  it("gives the facts of the step that begins: its place among the hard steps", () => {
    const [candidates] = table([{ t: 0, step: 2, left: 5 }]);
    const [start] = candidates as Candidate[];
    expect(start).toMatchObject({ trigger: "step-start", stepIndex: 3, key: "step-start:3" });
    expect(plan.hardIndexes.indexOf(3) + 1).toBe(2);
  });
});

describe("two minutes remain", () => {
  const at = (left: number) => table([{ t: 0, step: 1, left }]);

  it("fires from 126 s left (120 s and the lead) and up to 110 s, not outside", () => {
    expect(names(at(130))).toEqual([[]]);
    expect(names(at(126))).toEqual([["two-minutes"]]);
    expect(names(at(120))).toEqual([["two-minutes"]]);
    expect(names(at(110))).toEqual([["two-minutes"]]);
    expect(names(at(109))).toEqual([[]]);
  });

  it("is offered once per step, and the next pass through a repeat is its own step", () => {
    expect(
      names(table([{ t: 0, step: 1, left: 120 }], { spent: new Set(["two-minutes:1"]) })),
    ).toEqual([[]]);
    expect(
      kind(
        table([{ t: 0, step: 3, left: 120 }], { spent: new Set(["two-minutes:1"]) }),
        "two-minutes",
      ),
    ).toEqual([["two-minutes"]]);
  });

  it("is not said in a step too short for a warning, nor in a free ride", () => {
    const planned = planOf([
      { type: "steady", duration: 240, target: 0.9 },
      { type: "free", duration: 900 },
    ]);
    expect(kind(table([{ t: 0, step: 0, left: 120 }], { plan: planned }), "two-minutes")).toEqual([
      [],
    ]);
    expect(
      kind(table([{ t: 0, step: 1, left: 120, compl: null }], { plan: planned }), "two-minutes"),
    ).toEqual([[]]);
  });

  it("gives way to the strong finish on the last hard step once that has been said", () => {
    expect(
      names(table([{ t: 0, step: 3, left: 120 }], { spent: new Set(["strong-finish"]) })),
    ).toEqual([[]]);
  });
});

describe("power drifts off target for 20 s", () => {
  // Step 1 asks for 285 W; 10% is 28.5 W: below 256.5 W or above 313.5 W is off.
  const run = (power: number | null, extra: Partial<Point> = {}, from = 100): Point[] =>
    Array.from({ length: 5 }, (_v, i) => ({
      t: from + i * 5,
      step: 1,
      left: 500 - i * 5,
      power,
      ...extra,
    }));

  it("fires when the whole window is off on one side: 20 s of snapshots, the fifth", () => {
    const found = table(run(250));
    expect(names(found)).toEqual([[], [], [], [], ["power-drift"]]);
    const [drift] = found[4] as Candidate[];
    expect(drift?.detail).toMatchObject({
      trigger: "power-drift",
      direction: "below",
      averageWatts: 250,
      targetWatts: 285,
      seconds: 20,
    });
    // It is a condition, not an event: it has no key, and the limits decide how often it speaks.
    expect(drift?.key).toBeUndefined();
  });

  it("says above when it is above", () => {
    const [last] = table(run(330)).at(-1) as Candidate[];
    expect(last?.detail).toMatchObject({ direction: "above", averageWatts: 330 });
  });

  it("holds its tongue inside the tolerance of 10%, at its edge and just outside it", () => {
    expect(names(table(run(260))).at(-1)).toEqual([]); // 8.8% below
    expect(names(table(run(257))).at(-1)).toEqual([]); // 9.8% below
    expect(names(table(run(256))).at(-1)).toEqual(["power-drift"]); // 10.2% below
  });

  it("wants the whole window on the same side: one good reading breaks it", () => {
    const points = run(250);
    points[2] = { ...(points[2] as Point), power: 285 };
    expect(names(table(points)).at(-1)).toEqual([]);
    const flipped = run(250);
    flipped[3] = { ...(flipped[3] as Point), power: 330 };
    expect(names(table(flipped)).at(-1)).toEqual([]);
  });

  it("wants the whole 20 s: four snapshots 5 s apart cover 15 s and are not enough", () => {
    expect(names(table(run(250).slice(0, 4))).at(-1)).toEqual([]);
    // Four snapshots 4 s apart cover 12 s only.
    const quick = Array.from({ length: 4 }, (_v, i) => ({
      t: 100 + i * 4,
      step: 1,
      left: 500 - i * 4,
      power: 250,
    }));
    expect(names(table(quick)).at(-1)).toEqual([]);
  });

  it("does not mind a network that delivers snapshots a little early or late", () => {
    const spaced = (gap: number) =>
      Array.from({ length: 5 }, (_v, i) => ({
        t: 100 + i * gap,
        step: 1,
        left: 500 - Math.round(i * gap),
        power: 250,
      }));
    expect(names(table(spaced(5.1))).at(-1)).toEqual(["power-drift"]);
    expect(names(table(spaced(4.9))).at(-1)).toEqual(["power-drift"]);
    // An on-target snapshot that is 25 s old has left the window; one that is 20 s old has not.
    const recovered = [
      { t: 75, step: 1, left: 525, power: 285 },
      ...Array.from({ length: 5 }, (_v, i) => ({
        t: 100 + i * 5,
        step: 1,
        left: 500 - i * 5,
        power: 250,
      })),
    ];
    expect(names(table(recovered)).at(-1)).toEqual(["power-drift"]);
    const notYet = [
      { t: 80, step: 1, left: 520, power: 285 },
      ...Array.from({ length: 4 }, (_v, i) => ({
        t: 105 + i * 5,
        step: 1,
        left: 495 - i * 5,
        power: 250,
      })),
    ];
    // At t=120 the window is 98 to 120: the on-target snapshot at 80 has left it, but only four snapshots are off.
    expect(names(table(notYet)).at(-1)).toEqual([]);
  });

  it("does not judge the first 30 s of a step: the trainer needs time to answer a new target", () => {
    expect(
      names(table(run(250, {}, 0).map((p, i) => ({ ...p, left: 600 - 5 * i })))).at(-1),
    ).toEqual([]);
    const late = Array.from({ length: 5 }, (_v, i) => ({
      t: 20 + i * 5,
      step: 1,
      left: 600 - (30 + i * 5),
      power: 250,
    }));
    expect(names(table(late)).at(-1)).toEqual(["power-drift"]);
  });

  it("is not judged while the stall guard holds the target down, nor when a reading is missing, nor while paused", () => {
    expect(names(table(run(250, { guard: true }))).at(-1)).toEqual([]);
    expect(names(table(run(null))).at(-1)).toEqual([]);
    const paused = run(250);
    paused[2] = { ...(paused[2] as Point), paused: true };
    expect(names(table(paused)).at(-1)).toEqual([]);
    expect(names(table(run(250, { paused: true })).slice(-1))).toEqual([[]]);
  });

  it("looks only at the steps it is in: a step change starts the window again", () => {
    const points = [
      { t: 100, step: 0, left: 300, power: 250 },
      { t: 105, step: 0, left: 295, power: 250 },
      { t: 110, step: 1, left: 480, power: 250 },
      { t: 115, step: 1, left: 475, power: 250 },
    ];
    expect(names(table(points)).at(-1)).toEqual([]);
  });

  it("has no target in a step without one", () => {
    expect(
      names(table(run(250).map((p) => ({ ...p, step: 0, left: 300 - (p.t - 100) })))).at(-1),
    ).toEqual([]);
  });
});

describe("cadence sags", () => {
  const run = (cad: number | null, from = 100): Point[] =>
    Array.from({ length: 5 }, (_v, i) => ({ t: from + i * 5, step: 1, left: 500 - i * 5, cad }));

  it("fires when it has been below 70 rpm for the whole window of 20 s", () => {
    const found = table(run(62));
    expect(names(found)).toEqual([[], [], [], [], ["cadence-sag"]]);
    const [sag] = found[4] as Candidate[];
    expect(sag?.detail).toMatchObject({ trigger: "cadence-sag", averageRpm: 62, belowRpm: 70 });
  });

  it("holds its tongue at 70 rpm, for a stop and for a missing reading", () => {
    expect(names(table(run(70))).at(-1)).toEqual([]);
    expect(names(table(run(69))).at(-1)).toEqual(["cadence-sag"]);
    expect(names(table(run(0))).at(-1)).toEqual([]);
    expect(names(table(run(null))).at(-1)).toEqual([]);
  });

  it("uses the cadence the step asks for, 12 rpm below it, when there is one", () => {
    const planned = planOf([{ type: "steady", duration: 600, target: 0.9, cadence: 95 }]);
    const points = (cad: number) =>
      Array.from({ length: 5 }, (_v, i) => ({ t: 100 + i * 5, step: 0, left: 500 - i * 5, cad }));
    // 95 rpm asked for: 83 rpm is the line.
    expect(names(table(points(82), { plan: planned })).at(-1)).toEqual(["cadence-sag"]);
    expect(names(table(points(83), { plan: planned })).at(-1)).toEqual([]);
    expect(names(table(points(86), { plan: planned })).at(-1)).toEqual([]);
  });

  it("is not judged in the first 30 s of a step, nor in a free step", () => {
    const early = Array.from({ length: 5 }, (_v, i) => ({
      t: i * 5,
      step: 1,
      left: 600 - i * 5,
      cad: 60,
    }));
    expect(names(table(early)).at(-1)).toEqual([]);
    const planned = planOf([{ type: "free", duration: 900 }]);
    const free = Array.from({ length: 5 }, (_v, i) => ({
      t: 100 + i * 5,
      step: 0,
      left: 500 - i * 5,
      cad: 60,
      compl: null,
    }));
    expect(names(table(free, { plan: planned })).at(-1)).toEqual([]);
  });
});

describe("heart rate climbs faster than in the attempt before", () => {
  const previous: PreviousAttempt = {
    date: "2026-11-12",
    steps: new Map([
      [1, { averageHeartRate: 150 }],
      [3, { averageHeartRate: null }],
    ]),
  };
  // 150 s into step 1, then a minute of snapshots; the heart rate rises from 156 to 166.
  const climb = (rates: number[], extra: Partial<Point> = {}) =>
    rates.map((hr, i) => ({ t: 200 + i * 5, step: 1, left: 400 - i * 5, hr, ...extra }));
  const RISING = [156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168];

  it("fires when the heart rate is 7 bpm above last time's average for this step and still rising", () => {
    const found = table(climb(RISING), { previous });
    const last = found.at(-1) as Candidate[];
    expect(triggersOf(last)).toEqual(["heart-rate-climb"]);
    expect(last[0]?.detail).toMatchObject({
      trigger: "heart-rate-climb",
      previousAverageBpm: 150,
      previousDate: "2026-11-12",
    });
    const detail = last[0]?.detail as { averageBpm: number };
    expect(detail.averageBpm).toBeCloseTo(165, 0);
  });

  it("needs a previous attempt that has a figure for this step", () => {
    expect(names(table(climb(RISING))).at(-1)).toEqual([]);
    expect(
      names(table(climb(RISING), { previous: { date: null, steps: new Map() } })).at(-1),
    ).toEqual([]);
    const other = table(
      climb(RISING).map((p) => ({ ...p, step: 3, left: 400 - (p.t - 200) })),
      { previous },
    );
    expect(names(other).at(-1)).toEqual([]);
  });

  it("is not said before the heart rate has had time to settle, 150 s into the step", () => {
    const early = RISING.map((hr, i) => ({ t: 100 + i * 5, step: 1, left: 600 - i * 5, hr }));
    expect(names(table(early, { previous })).at(-1)).toEqual([]);
  });

  it("needs the margin: 6 bpm above is not enough, 7 is", () => {
    const flat = (level: number) =>
      Array.from({ length: 13 }, (_v, i) => level + (i === 12 ? 1 : 0));
    expect(names(table(climb(flat(155)), { previous })).at(-1)).toEqual([]);
    expect(names(table(climb(flat(158)), { previous })).at(-1)).toEqual(["heart-rate-climb"]);
  });

  it("needs it still rising: a heart rate that has levelled off is not climbing", () => {
    const level = Array.from({ length: 13 }, () => 165);
    expect(names(table(climb(level), { previous })).at(-1)).toEqual([]);
  });

  it("is not said when the power is off target (the heart rate follows the effort), nor in a ramp or a free step", () => {
    const offTarget = table(climb(RISING, { power: 330 }), { previous }).at(-1) as Candidate[];
    expect(triggersOf(offTarget)).not.toContain("heart-rate-climb");
    const planned = planOf([{ type: "ramp", duration: 900, from: 0.5, to: 0.9 }]);
    const ramp = RISING.map((hr, i) => ({ t: 200 + i * 5, step: 0, left: 400 - i * 5, hr }));
    expect(
      names(
        table(ramp, {
          plan: planned,
          previous: { date: null, steps: new Map([[0, { averageHeartRate: 120 }]]) },
        }),
      ).at(-1),
    ).toEqual([]);
  });
});

describe("a strong finish", () => {
  const last = (left: number, compl = 90, step = 3) => table([{ t: 0, step, left, compl }]);

  it("fires in the last hard step's final 3 minutes, past half of it, with the rider on target", () => {
    expect(names(last(180))).toEqual([["strong-finish"]]);
    expect(names(last(120))).toEqual([["strong-finish", "two-minutes"]]);
    expect(names(last(181))).toEqual([[]]);
    // Not before half of the step is behind the rider.
    const planned = planOf([{ type: "steady", duration: 300, target: 0.95 }]);
    expect(names(table([{ t: 0, step: 0, left: 150 }], { plan: planned }))).toEqual([
      ["strong-finish"],
    ]);
    expect(names(table([{ t: 0, step: 0, left: 160 }], { plan: planned }))).toEqual([[]]);
  });

  it("needs the compliance to be good: 80% or more", () => {
    expect(names(last(150, 79))).toEqual([[]]);
    expect(names(last(150, 80))).toEqual([["strong-finish"]]);
    expect(names(table([{ t: 0, step: 3, left: 150, compl: null }]))).toEqual([[]]);
  });

  it("is said before the silent final seconds, not inside them", () => {
    expect(names(last(16))).toEqual([["strong-finish"]]);
    expect(names(last(15))).toEqual([[]]);
  });

  it("belongs to the last hard step only: an earlier one is an ordinary interval", () => {
    expect(names(last(150, 90, 1))).toEqual([[]]);
    expect(plan.lastHardIndex).toBe(3);
  });

  it("is said once", () => {
    expect(
      names(table([{ t: 0, step: 3, left: 150 }], { spent: new Set(["strong-finish"]) })),
    ).toEqual([[]]);
  });

  it("has no last hard step in a workout without one", () => {
    const easy = planOf([{ type: "steady", duration: 1800, target: 0.7 }]);
    expect(easy.lastHardIndex).toBeNull();
    expect(names(table([{ t: 0, step: 0, left: 150 }], { plan: easy }))).toEqual([[]]);
  });
});

describe("the agent's own lines (must_say)", () => {
  const withLines = planOf([
    { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
    {
      type: "steady",
      duration: 600,
      target: 0.95,
      label: "Threshold",
      must_say: [
        { at: "start", text: "First one. Ease into it." },
        { at: 300, text: "Halfway. Stay relaxed." },
      ],
    },
  ]);

  it("speaks a line at the start of a step from the step before, a few seconds early", () => {
    const found = table(
      [
        { t: 0, step: 0, left: 12 },
        { t: 5, step: 0, left: 7 },
        { t: 10, step: 0, left: 2 },
      ],
      { plan: withLines },
    );
    expect(names(found)).toEqual([[], [], ["must-say", "step-start"]]);
    const [line] = found[2] as Candidate[];
    expect(line).toMatchObject({
      trigger: "must-say",
      text: "First one. Ease into it.",
      key: "must-say:1:start:0",
      stepIndex: 1,
      aboutNextStep: true,
    });
  });

  it("speaks it at the start of a first step and when the app joined a few seconds late", () => {
    const first = planOf([
      {
        type: "steady",
        duration: 600,
        target: 0.6,
        must_say: [{ at: "start", text: "Settle in." }],
      },
    ]);
    expect(names(table([{ t: 0, step: 0, left: 600 }], { plan: first }))).toEqual([["must-say"]]);
    expect(names(table([{ t: 8, step: 1, left: 592 }], { plan: withLines }))).toEqual([
      ["must-say", "step-start"],
    ]);
    expect(names(table([{ t: 11, step: 1, left: 589 }], { plan: withLines }))).toEqual([[]]);
  });

  it("speaks a line at a second of the step: from the lead before it to ten seconds after it", () => {
    const at = (elapsed: number) =>
      table([{ t: 0, step: 1, left: 600 - elapsed }], { plan: withLines });
    expect(names(at(293))).toEqual([[]]);
    expect(names(at(294))).toEqual([["must-say"]]);
    expect(names(at(300))).toEqual([["must-say"]]);
    expect(names(at(310))).toEqual([["must-say"]]);
    expect(names(at(311))).toEqual([[]]);
    expect((at(300)[0] as Candidate[])[0]).toMatchObject({
      text: "Halfway. Stay relaxed.",
      key: "must-say:1:300:1",
      aboutNextStep: false,
    });
  });

  it("is offered until it is spent", () => {
    expect(
      names(
        table([{ t: 0, step: 1, left: 300 }], {
          plan: withLines,
          spent: new Set(["must-say:1:300:1"]),
        }),
      ),
    ).toEqual([[]]);
  });

  it("is offered ahead of every other reason", () => {
    const found = table([{ t: 0, step: 0, left: 120 }], {
      plan: planOf([
        {
          type: "steady",
          duration: 300,
          target: 0.95,
          must_say: [{ at: 180, text: "Breathe." }],
        },
      ]),
    });
    expect(names(found)).toEqual([["must-say", "strong-finish", "two-minutes"]]);
  });
});

describe("what the rules never do", () => {
  it("offer nothing while the ride is paused", () => {
    expect(names(table([{ t: 0, step: 1, left: 120, paused: true }]))).toEqual([[]]);
  });

  it("offer nothing for a step that is not in the workout", () => {
    const history: SnapshotEntry[] = [
      {
        at: 0,
        snap: {
          ...snapshotOf({ t: 0, step: 1, left: 120 }, plan, 0),
          step: { index: 40, type: "steady", durationSeconds: 600, timeLeftSeconds: 120 },
        },
      },
    ];
    expect(
      detectCandidates({
        history,
        plan,
        previous: null,
        spent: new Set(),
        settings: DEFAULT_TRIGGER_SETTINGS,
        silentFinalSeconds: SILENT,
      }),
    ).toEqual([]);
    expect(
      detectCandidates({
        history: [],
        plan,
        previous: null,
        spent: new Set(),
        settings: DEFAULT_TRIGGER_SETTINGS,
        silentFinalSeconds: SILENT,
      }),
    ).toEqual([]);
  });

  it("are deterministic: the same snapshots in the same time give the same answer", () => {
    const points: Point[] = [
      { t: 0, step: 3, left: 125 },
      { t: 5, step: 3, left: 120 },
    ];
    expect(table(points)).toEqual(table(points));
  });

  it("offer in the priority order of the table", () => {
    expect(TRIGGER_PRIORITY).toEqual([
      "must-say",
      "strong-finish",
      "step-start",
      "two-minutes",
      "power-drift",
      "cadence-sag",
      "heart-rate-climb",
    ]);
  });

  it("read no real clock: the time is the history's", () => {
    const real = Date.now;
    Date.now = () => {
      throw new Error("the rules must not read the clock");
    };
    try {
      expect(() => table([{ t: 0, step: 1, left: 120 }])).not.toThrow();
    } finally {
      Date.now = real;
    }
  });
});
