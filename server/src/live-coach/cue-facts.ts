import type { LiveCueTrigger, LiveSnapshot } from "@overdrive/contracts";
import { type Quantity, Quotes } from "../insights/quotes.ts";
import type { SessionPlan, SessionStep } from "./structure.ts";

/**
 * The facts a cue is written from (spec §12.1, ADR 0038): structured JSON the service computes deterministically, that the model is
 * given and may quote but never calculate from. The rules of the insights hold (ADR 0025): every number that goes in was registered with
 * {@link Quotes}, so it is in `quotable`, and the validator rejects a cue that says a number that is not. Pure.
 *
 * HEALTH DATA IS PRIVATE: these are the rider's readings (power, heart rate, cadence). They go to the owner's language model and
 * nowhere else; nothing here, or in the callers, logs one.
 */

/** What a trigger found, computed by code: the numbers a cue may say. */
export type TriggerDetail =
  | {
      trigger: "step-start";
      /** The step that is about to begin (or has just begun). */
      step: SessionStep;
    }
  | { trigger: "two-minutes" }
  | {
      trigger: "power-drift";
      direction: "below" | "above";
      averageWatts: number;
      targetWatts: number;
      seconds: number;
    }
  | { trigger: "cadence-sag"; averageRpm: number; belowRpm: number }
  | {
      trigger: "heart-rate-climb";
      averageBpm: number;
      previousAverageBpm: number;
      previousDate: string | null;
    }
  | { trigger: "strong-finish" };

export interface CueFacts {
  schema: 1;
  kind: "live-cue";
  trigger: LiveCueTrigger;
  workout: { name: string };
  /** The step the rider is in now. */
  now: {
    stepNumber: number;
    stepsInSession: number;
    label: string;
    type: string;
    /** Whole minutes left in the step, and the seconds it was at the last snapshot. */
    minutesLeft: number | null;
    secondsLeft: number | null;
    targetWatts: number | null;
    targetBpm: number | null;
    powerWatts: number | null;
    heartRateBpm: number | null;
    cadenceRpm: number | null;
    /** The share of the step's judged seconds on target so far. */
    compliancePercent: number | null;
    stallGuardActive: boolean;
  };
  /** What the trigger found. */
  detail: Record<string, unknown>;
  quotable: Quantity[];
}

const round1 = (value: number): number => Math.round(value * 10) / 10;

/** A length of time in the way a coach would say it: whole minutes when it is, else minutes and a tenth. */
function minutesOf(seconds: number): number {
  return round1(seconds / 60);
}

export interface FactsInput {
  trigger: TriggerDetail;
  workoutName: string;
  snapshot: LiveSnapshot;
  plan: SessionPlan;
  /** The rider's FTP, or null while the profile has none (then no step is given in watts). */
  ftp: number | null;
}

/** The watts a fraction of FTP is, whole; null without an FTP. */
export function wattsOf(fraction: number | null, ftp: number | null): number | null {
  return fraction === null || ftp === null ? null : Math.round(fraction * ftp);
}

export function buildCueFacts(input: FactsInput): CueFacts {
  const { snapshot, trigger, plan, ftp } = input;
  const q = new Quotes();
  const { step } = snapshot;
  const planned = plan.steps[step.index];
  const num = (
    label: string,
    value: number | null | undefined,
    unit: string,
    digits = 0,
  ): number | null => q.n(label, value ?? null, unit, digits);

  const now: CueFacts["now"] = {
    stepNumber:
      num("the step the rider is in, its number", step.index + 1, "step") ?? step.index + 1,
    stepsInSession: num("steps in the session", snapshot.stepCount, "steps") ?? snapshot.stepCount,
    label: step.label ?? planned?.label ?? "",
    type: step.type,
    minutesLeft: num("time left in this step", Math.round(step.timeLeftSeconds / 60), "min"),
    secondsLeft: num("time left in this step, seconds", step.timeLeftSeconds, "s"),
    targetWatts: num("target power now", step.targetWatts, "W"),
    targetBpm: num("target heart rate now", step.targetBpm, "bpm"),
    powerWatts: num("power now (mean of the last 5 s)", snapshot.power, "W"),
    heartRateBpm: num("heart rate now (mean of the last 5 s)", snapshot.heartRate, "bpm"),
    cadenceRpm: num("cadence now (mean of the last 5 s)", snapshot.cadence, "rpm"),
    compliancePercent: num(
      "share of this step ridden on target so far",
      snapshot.compliancePercent,
      "%",
    ),
    stallGuardActive: snapshot.stallGuardActive === true,
  };

  let detail: Record<string, unknown>;
  switch (trigger.trigger) {
    case "step-start": {
      const next = trigger.step;
      const hardNumber = plan.hardIndexes.indexOf(next.index) + 1;
      const startWatts = wattsOf(next.fromFraction, ftp);
      const endWatts = wattsOf(next.toFraction, ftp);
      detail = {
        beginsNow: true,
        stepNumber: num("the next step's number", next.index + 1, "step"),
        label: next.label,
        type: next.type,
        minutes: num("the next step's length", minutesOf(next.durationSeconds), "min", 1),
        seconds: num("the next step's length, seconds", next.durationSeconds, "s"),
        // A ramp goes from one power to another; a steady step holds one.
        targetWatts: num("the next step's target power", startWatts, "W"),
        ...(next.type === "ramp"
          ? { endsAtWatts: num("the ramp's final power", endWatts, "W") }
          : {}),
        targetPercentFtp: num(
          "the next step's target, percent of FTP",
          next.fromFraction === null ? null : next.fromFraction * 100,
          "%",
        ),
        cadenceTargetRpm: num("the next step's cadence target", next.cadenceTarget, "rpm"),
        hardStepNumber: num("which of the hard steps this is", hardNumber, "of the hard steps"),
        hardStepsInSession: num("hard steps in the session", plan.hardIndexes.length, "steps"),
        isLastHardStep: next.index === plan.lastHardIndex,
      };
      break;
    }
    case "two-minutes":
      detail = { minutesRemaining: num("minutes remaining, as announced", 2, "min") };
      break;
    case "power-drift":
      detail = {
        direction: trigger.direction,
        seconds: num("how long the power has been off target", trigger.seconds, "s"),
        averageWatts: num("mean power over those seconds", trigger.averageWatts, "W"),
        targetWatts: num("target power over those seconds", trigger.targetWatts, "W"),
        percentOff: num(
          "percent off target",
          (Math.abs(trigger.averageWatts - trigger.targetWatts) / trigger.targetWatts) * 100,
          "%",
        ),
      };
      break;
    case "cadence-sag":
      detail = {
        averageRpm: num("mean cadence over the last 20 s", trigger.averageRpm, "rpm"),
        belowRpm: num("the cadence it fell below", trigger.belowRpm, "rpm"),
      };
      break;
    case "heart-rate-climb":
      detail = {
        averageBpm: num("mean heart rate over the last 30 s", trigger.averageBpm, "bpm"),
        previousAverageBpm: num(
          "heart rate in this step the last time the session was ridden",
          trigger.previousAverageBpm,
          "bpm",
        ),
        higherByBpm: num(
          "how much higher the heart rate is than last time",
          trigger.averageBpm - trigger.previousAverageBpm,
          "bpm",
        ),
        previousDate: trigger.previousDate,
      };
      break;
    case "strong-finish":
      detail = {
        isLastHardStep: true,
        minutesRemaining: num(
          "minutes remaining in the last hard step",
          Math.round(step.timeLeftSeconds / 60),
          "min",
        ),
      };
      break;
  }

  return {
    schema: 1,
    kind: "live-cue",
    trigger: trigger.trigger,
    workout: { name: input.workoutName },
    now,
    detail,
    quotable: q.list,
  };
}
