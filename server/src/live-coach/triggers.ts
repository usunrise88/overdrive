import type { LiveCueTrigger, LiveSnapshot } from "@overdrive/contracts";
import type { TriggerDetail } from "./cue-facts.ts";
import type { SessionPlan, SessionStep } from "./structure.ts";

/**
 * The trigger rules (spec §12.1, ADR 0038): when a cue MAY happen. Pure and deterministic: the same snapshots in the same virtual time give
 * the same candidates, with no real clock and no randomness. A candidate is only a reason to speak; whether the limits allow it is
 * `limits.ts` and what is said is the model's (or, for `must_say`, the agent's own words). The rules read the snapshots the tablet
 * streams every 5 seconds, the workout's structure (what comes next) and, for the heart rate, the attempt before this one.
 *
 * Time is in milliseconds on one clock the caller supplies (the service's receipt time of each snapshot); the step's time left comes
 * from the snapshot. A line that is known beforehand (a step about to begin, a `must_say` at a second of the step, "two minutes remain") is
 * triggered `leadSeconds` early to absorb the delay from trigger to sound.
 */

/** One snapshot with the time the service received it. */
export interface SnapshotEntry {
  /** Milliseconds on the service's clock. */
  at: number;
  snap: LiveSnapshot;
}

/** What the previous attempt at this session says about a step (from the repeats service). */
export interface PreviousStep {
  /** The mean heart rate in the step at the power it was ridden at, or null when it had no figure. */
  averageHeartRate: number | null;
}

export interface PreviousAttempt {
  /** The rider's local day of that attempt. */
  date: string | null;
  /** By the flattened step's index (from 0). */
  steps: ReadonlyMap<number, PreviousStep>;
}

export interface TriggerSettings {
  /** Seconds early a line that is known beforehand is triggered. */
  leadSeconds: number;
  /** A line is still worth saying this long after its moment (a snapshot came late). */
  lateSeconds: number;
  twoMinutes: { remainSeconds: number; minStepSeconds: number };
  powerDrift: {
    /** The power is off target when it is more than this percent away from it. */
    percent: number;
    windowSeconds: number;
    minSnapshots: number;
    /** Not judged in the first seconds of a step: the trainer needs time to answer a new target. */
    graceSeconds: number;
  };
  cadenceSag: {
    /** The cadence has sagged below this, when the step asks for none. */
    belowRpm: number;
    /** ... or this many rpm below the cadence the step asks for. */
    belowTargetRpm: number;
    windowSeconds: number;
    minSnapshots: number;
    graceSeconds: number;
  };
  heartRateClimb: {
    /** The heart rate is this much above what the previous attempt averaged in this step. */
    marginBpm: number;
    /** Heart rate needs time to settle: not judged before this many seconds into the step. */
    minElapsedSeconds: number;
    windowSeconds: number;
    minSnapshots: number;
    /** ... and still rising: this much higher than it was at the start of a minute-long look back. */
    risingBpm: number;
  };
  strongFinish: {
    /** The last hard step's final stretch begins this many seconds from its end. */
    remainSeconds: number;
    minCompliancePercent: number;
    /** At least this share of the step must be behind the rider. */
    minShare: number;
  };
}

export const DEFAULT_TRIGGER_SETTINGS: TriggerSettings = {
  leadSeconds: 6,
  lateSeconds: 10,
  twoMinutes: { remainSeconds: 120, minStepSeconds: 300 },
  powerDrift: { percent: 10, windowSeconds: 20, minSnapshots: 4, graceSeconds: 30 },
  cadenceSag: {
    belowRpm: 70,
    belowTargetRpm: 12,
    windowSeconds: 20,
    minSnapshots: 3,
    graceSeconds: 30,
  },
  heartRateClimb: {
    marginBpm: 7,
    minElapsedSeconds: 150,
    windowSeconds: 30,
    minSnapshots: 4,
    risingBpm: 1,
  },
  strongFinish: { remainSeconds: 180, minCompliancePercent: 80, minShare: 0.5 },
};

/** Why a cue may happen. A candidate is only a reason: the limits and the model decide the rest. */
export interface Candidate {
  trigger: LiveCueTrigger;
  /** The agent's own words (`must-say`); every other trigger has the model write the line. */
  text?: string;
  /** What a model trigger found, for the facts. */
  detail?: TriggerDetail;
  /**
   * What makes it once: a key in the spent set is not offered again. Absent for a condition that holds for a while (the limits bound how
   * often it speaks).
   */
  key?: string;
  /** The step the cue is about. */
  stepIndex: number;
  /** The cue is about a step that has not begun: the silence of a step's final seconds does not apply to it. */
  aboutNextStep: boolean;
}

export interface TriggerInput {
  /** Oldest first; the last entry is the snapshot just received. */
  history: readonly SnapshotEntry[];
  plan: SessionPlan;
  /** The previous attempt at this session, or null when there is none. */
  previous: PreviousAttempt | null;
  /** Keys already said, attempted or out of date. */
  spent: ReadonlySet<string>;
  settings: TriggerSettings;
  /** The seconds a step's end is silent (the limit's own setting): a line there would never be said. */
  silentFinalSeconds: number;
}

/** The order in which candidates are offered to the limits: the agent's words first, then the model's reasons by how time-critical they are. */
export const TRIGGER_PRIORITY: readonly LiveCueTrigger[] = [
  "must-say",
  "strong-finish",
  "step-start",
  "two-minutes",
  "power-drift",
  "cadence-sag",
  "heart-rate-climb",
];

const MS = 1000;
/** Snapshots come every 5 s, give or take the network: a window of "the last 20 s" holds the snapshots up to half an interval older. */
const JITTER_MS = 2500;

/** The trailing snapshots of the current step that are not paused, within `seconds` of the newest, newest last. */
function window(history: readonly SnapshotEntry[], seconds: number): SnapshotEntry[] {
  const last = history.at(-1);
  if (last === undefined) return [];
  const taken: SnapshotEntry[] = [];
  for (let i = history.length - 1; i >= 0; i--) {
    const entry = history[i] as SnapshotEntry;
    if (last.at - entry.at > seconds * MS + JITTER_MS) break;
    if (entry.snap.paused || entry.snap.step.index !== last.snap.step.index) break;
    taken.unshift(entry);
  }
  return taken;
}

/** Whether the snapshots of a window reach back over the whole of `seconds` (to within half an interval): the condition held that long. */
function reaches(seen: readonly SnapshotEntry[], seconds: number): boolean {
  const first = seen[0];
  const last = seen.at(-1);
  return (
    first !== undefined && last !== undefined && last.at - first.at >= seconds * MS - JITTER_MS
  );
}

const mean = (values: readonly number[]): number =>
  values.reduce((sum, value) => sum + value, 0) / values.length;

const key = (...parts: (string | number)[]): string => parts.join(":");

function mustSayCandidates(
  step: SessionStep,
  elapsed: number,
  timeLeft: number,
  settings: TriggerSettings,
  spent: ReadonlySet<string>,
  next: SessionStep | undefined,
): Candidate[] {
  const found: Candidate[] = [];
  const lines = (target: SessionStep, moment: number): void => {
    for (const [i, line] of target.mustSay.entries()) {
      const at = line.at === "start" ? 0 : line.at;
      if (at !== moment) continue;
      const k = key("must-say", target.index, line.at, i);
      if (spent.has(k)) continue;
      found.push({
        trigger: "must-say",
        text: line.text,
        key: k,
        stepIndex: target.index,
        aboutNextStep: target.index !== step.index,
      });
    }
  };
  // The lines of the step that is about to begin, spoken as it does.
  if (next !== undefined && timeLeft > 0 && timeLeft <= settings.leadSeconds) {
    lines(next, 0);
  }
  // The lines of this step at a second of it, or at its start when the app joined late.
  for (const [i, line] of step.mustSay.entries()) {
    const at = line.at === "start" ? 0 : line.at;
    if (elapsed < at - settings.leadSeconds || elapsed > at + settings.lateSeconds) continue;
    const k = key("must-say", step.index, line.at, i);
    if (spent.has(k) || found.some((c) => c.key === k)) continue;
    found.push({
      trigger: "must-say",
      text: line.text,
      key: k,
      stepIndex: step.index,
      aboutNextStep: false,
    });
  }
  return found;
}

/**
 * Every reason to speak that holds now, in priority order. Nothing is offered while the ride is paused, and a step that is not in the
 * workout (the structure changed) offers nothing.
 */
export function detectCandidates(input: TriggerInput): Candidate[] {
  const { history, plan, previous, spent, settings } = input;
  const current = history.at(-1);
  if (current === undefined) return [];
  const { snap } = current;
  if (snap.paused) return [];
  const step = plan.steps[snap.step.index];
  if (step === undefined) return [];
  const next = plan.steps[snap.step.index + 1];
  const timeLeft = snap.step.timeLeftSeconds;
  const elapsed = snap.step.durationSeconds - timeLeft;
  const candidates: Candidate[] = [];

  // 1. The agent's own words.
  candidates.push(...mustSayCandidates(step, elapsed, timeLeft, settings, spent, next));

  // 2. A strong finish: the last hard step's last minutes, with the rider on target.
  if (
    plan.lastHardIndex === step.index &&
    (step.type === "steady" || step.type === "ramp") &&
    timeLeft <= settings.strongFinish.remainSeconds &&
    timeLeft > input.silentFinalSeconds &&
    elapsed / snap.step.durationSeconds >= settings.strongFinish.minShare &&
    (snap.compliancePercent ?? 0) >= settings.strongFinish.minCompliancePercent &&
    !spent.has("strong-finish")
  ) {
    candidates.push({
      trigger: "strong-finish",
      detail: { trigger: "strong-finish" },
      key: "strong-finish",
      stepIndex: step.index,
      aboutNextStep: false,
    });
  }

  // 3. A hard step begins: triggered early, from the step before; or at its start when the app joined late or it is the first step.
  const startKey = (index: number): string => key("step-start", index);
  if (
    next?.hard === true &&
    timeLeft > 0 &&
    timeLeft <= settings.leadSeconds &&
    !spent.has(startKey(next.index))
  ) {
    candidates.push({
      trigger: "step-start",
      detail: { trigger: "step-start", step: next },
      key: startKey(next.index),
      stepIndex: next.index,
      aboutNextStep: true,
    });
  }
  if (
    step.hard &&
    elapsed <= settings.lateSeconds &&
    !spent.has(startKey(step.index)) &&
    snap.step.durationSeconds >= 1
  ) {
    candidates.push({
      trigger: "step-start",
      detail: { trigger: "step-start", step },
      key: startKey(step.index),
      stepIndex: step.index,
      aboutNextStep: false,
    });
  }

  // 4. Two minutes remain: triggered early; not in a step too short for a warning, not in a free ride, not after a strong finish.
  const two = settings.twoMinutes;
  if (
    step.type !== "free" &&
    snap.step.durationSeconds >= two.minStepSeconds &&
    timeLeft <= two.remainSeconds + settings.leadSeconds &&
    timeLeft >= two.remainSeconds - settings.lateSeconds &&
    timeLeft > input.silentFinalSeconds &&
    !spent.has(key("two-minutes", step.index)) &&
    !(plan.lastHardIndex === step.index && spent.has("strong-finish"))
  ) {
    candidates.push({
      trigger: "two-minutes",
      detail: { trigger: "two-minutes" },
      key: key("two-minutes", step.index),
      stepIndex: step.index,
      aboutNextStep: false,
    });
  }

  // 5. Power has been off target for the whole window, on one side.
  const drift = settings.powerDrift;
  if (
    snap.step.targetWatts != null &&
    snap.step.targetWatts > 0 &&
    !snap.stallGuardActive &&
    elapsed >= drift.graceSeconds
  ) {
    const seen = window(history, drift.windowSeconds);
    if (
      seen.length >= drift.minSnapshots &&
      reaches(seen, drift.windowSeconds) &&
      seen.every((e) => e.snap.power != null && e.snap.step.targetWatts != null)
    ) {
      const sides = seen.map((e) => {
        const target = e.snap.step.targetWatts as number;
        const off = (((e.snap.power as number) - target) / target) * 100;
        return off < -drift.percent ? -1 : off > drift.percent ? 1 : 0;
      });
      const side = sides[0];
      if (side !== undefined && side !== 0 && sides.every((s) => s === side)) {
        candidates.push({
          trigger: "power-drift",
          detail: {
            trigger: "power-drift",
            direction: side < 0 ? "below" : "above",
            averageWatts: mean(seen.map((e) => e.snap.power as number)),
            targetWatts: mean(seen.map((e) => e.snap.step.targetWatts as number)),
            seconds: drift.windowSeconds,
          },
          stepIndex: step.index,
          aboutNextStep: false,
        });
      }
    }
  }

  // 6. Cadence has sagged (not stopped: a stop is the auto-pause's and the stall guard's business, ADR 0033).
  const sag = settings.cadenceSag;
  if (step.type !== "free" && elapsed >= sag.graceSeconds) {
    const seen = window(history, sag.windowSeconds);
    const threshold =
      step.cadenceTarget === null ? sag.belowRpm : step.cadenceTarget - sag.belowTargetRpm;
    if (
      seen.length >= sag.minSnapshots &&
      reaches(seen, sag.windowSeconds) &&
      seen.every((e) => e.snap.cadence != null && e.snap.cadence > 0 && e.snap.cadence < threshold)
    ) {
      candidates.push({
        trigger: "cadence-sag",
        detail: {
          trigger: "cadence-sag",
          averageRpm: mean(seen.map((e) => e.snap.cadence as number)),
          belowRpm: threshold,
        },
        stepIndex: step.index,
        aboutNextStep: false,
      });
    }
  }

  // 7. Heart rate is running higher than the last time this session was ridden, at the same point, and still rising.
  const climb = settings.heartRateClimb;
  const before = previous?.steps.get(step.index)?.averageHeartRate ?? null;
  if (
    previous !== null &&
    before !== null &&
    step.type === "steady" &&
    elapsed >= climb.minElapsedSeconds
  ) {
    const seen = window(history, climb.windowSeconds);
    const lookBack = window(history, 60);
    const earliest = lookBack[0];
    const rates = seen.flatMap((e) => (e.snap.heartRate == null ? [] : [e.snap.heartRate]));
    const onTarget = seen.every(
      (e) =>
        e.snap.power == null ||
        e.snap.step.targetWatts == null ||
        Math.abs(e.snap.power - e.snap.step.targetWatts) <= 0.1 * e.snap.step.targetWatts,
    );
    if (
      rates.length >= climb.minSnapshots &&
      earliest?.snap.heartRate != null &&
      onTarget &&
      mean(rates) >= before + climb.marginBpm &&
      (snap.heartRate ?? 0) >= earliest.snap.heartRate + climb.risingBpm
    ) {
      candidates.push({
        trigger: "heart-rate-climb",
        detail: {
          trigger: "heart-rate-climb",
          averageBpm: mean(rates),
          previousAverageBpm: before,
          previousDate: previous.date,
        },
        stepIndex: step.index,
        aboutNextStep: false,
      });
    }
  }

  return candidates.sort(
    (a, b) => TRIGGER_PRIORITY.indexOf(a.trigger) - TRIGGER_PRIORITY.indexOf(b.trigger),
  );
}
