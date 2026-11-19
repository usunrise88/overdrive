import type { LeafStep, MustSay, Workout } from "@overdrive/contracts";

/**
 * The workout as the live coach reads it (spec §12.1, ADR 0038): flattened as the ride app and the ride record do it (repeats expanded,
 * index from 0), each step with what the rules need to know beforehand: whether it is a hard step (a cue about its start), a recovery
 * step (at most one cue), and the `must_say` lines the agent wrote for it. Pure.
 */

export interface SessionStep {
  index: number;
  type: "steady" | "ramp" | "free" | "hr";
  /** The step's own label, else its repeat's, else the type's word (as `flattenWorkout` does). */
  label: string;
  durationSeconds: number;
  /** Fractions of FTP at the start and end; null in a free step and a heart-rate step. */
  fromFraction: number | null;
  toFraction: number | null;
  /** The cadence the workout asks for, rpm; null when it asks for none. */
  cadenceTarget: number | null;
  /** The step is hard: a fixed power target at or above the threshold of `PlanSettings.hardFromFraction`. */
  hard: boolean;
  /** The step is a recovery step: a low target, or an explicit marker in its label. */
  recovery: boolean;
  /** The agent's own words for this step. */
  mustSay: readonly MustSay[];
}

export interface PlanSettings {
  /** A step whose target (its end, in a ramp) is at or above this fraction of FTP is hard. */
  hardFromFraction: number;
  /** A step whose target is below this fraction of FTP is a recovery step. */
  recoveryBelowFraction: number;
  /** A hard step shorter than this is not announced. */
  minHardSeconds: number;
}

export const DEFAULT_PLAN_SETTINGS: PlanSettings = {
  hardFromFraction: 0.85,
  recoveryBelowFraction: 0.6,
  minHardSeconds: 30,
};

/** The explicit marker of a recovery step: the label says so. */
const RECOVERY_LABEL = /\b(recover(y|ing)?|rest)\b/i;

const DEFAULT_LABEL = {
  steady: "Steady",
  ramp: "Ramp",
  free: "Free ride",
  hr: "Heart rate",
} as const;

export interface SessionPlan {
  steps: readonly SessionStep[];
  /** The index of the last hard step, or null when there is none. */
  lastHardIndex: number | null;
  /** The indexes of the hard steps in order: a hard step's place among them is "interval n of m". */
  hardIndexes: readonly number[];
}

/** Whether a step with these targets is a recovery step by the rule of spec §12.1: a target below the fraction, or a marker in the label. */
export function isRecovery(
  label: string,
  type: SessionStep["type"],
  from: number | null,
  to: number | null,
  below: number,
): boolean {
  if (RECOVERY_LABEL.test(label)) return true;
  if ((type === "steady" || type === "ramp") && from !== null && to !== null) {
    return Math.max(from, to) < below;
  }
  return false;
}

export function buildSessionPlan(
  workout: Workout,
  settings: PlanSettings = DEFAULT_PLAN_SETTINGS,
): SessionPlan {
  const steps: SessionStep[] = [];
  const add = (step: LeafStep, outer?: string): void => {
    const from = step.type === "steady" ? step.target : step.type === "ramp" ? step.from : null;
    const to = step.type === "steady" ? step.target : step.type === "ramp" ? step.to : null;
    const label = step.label ?? outer ?? DEFAULT_LABEL[step.type];
    steps.push({
      index: steps.length,
      type: step.type,
      label,
      durationSeconds: step.duration,
      fromFraction: from,
      toFraction: to,
      cadenceTarget: step.cadence ?? null,
      hard:
        (step.type === "steady" || step.type === "ramp") &&
        to !== null &&
        Math.max(from ?? 0, to) >= settings.hardFromFraction &&
        step.duration >= settings.minHardSeconds,
      recovery: isRecovery(label, step.type, from, to, settings.recoveryBelowFraction),
      mustSay: step.must_say ?? [],
    });
  };
  for (const step of workout.steps) {
    if (step.type === "repeat") {
      for (let i = 0; i < step.count; i++) for (const inner of step.steps) add(inner, step.label);
    } else {
      add(step);
    }
  }
  const hardIndexes = steps.filter((step) => step.hard).map((step) => step.index);
  return { steps, lastHardIndex: hardIndexes.at(-1) ?? null, hardIndexes };
}
