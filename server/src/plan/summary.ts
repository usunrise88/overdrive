import type { LeafStep, Workout } from "@overdrive/contracts";
import { formatDuration } from "../intervals/workout-text.ts";

/**
 * Numbers and one-line descriptions of a native workout for the MCP tools, computed here so that a
 * model reading them never has to add up steps (CLAUDE.md: the numbers come from code).
 */

const leaves = (workout: Workout): { step: LeafStep; times: number }[] =>
  workout.steps.flatMap((step) =>
    step.type === "repeat"
      ? step.steps.map((inner) => ({ step: inner, times: step.count }))
      : [{ step, times: 1 }],
  );

/** The workout's length in seconds, repeats counted. */
export function workoutDurationSeconds(workout: Workout): number {
  return leaves(workout).reduce((sum, { step, times }) => sum + step.duration * times, 0);
}

export interface PlannedLoad {
  /**
   * The training stress score the steps with a power target would give if ridden exactly as planned
   * (each step at its target, a ramp as the mean square of its two ends): the sum over steps of
   * `seconds × fraction² / 36`. A free step has no target and counts for nothing here.
   */
  estimatedTss: number;
  /** Seconds in free steps, which `estimatedTss` leaves out. */
  freeSeconds: number;
  /**
   * Seconds in heart-rate steps (spec §11.2), only when there are some: their power is the controller's, so `estimatedTss` leaves
   * them out too and the number is only the load of the rest of the workout.
   */
  heartRateSeconds?: number;
}

export function plannedLoad(workout: Workout): PlannedLoad {
  let tss = 0;
  let freeSeconds = 0;
  let heartRateSeconds = 0;
  for (const { step, times } of leaves(workout)) {
    if (step.type === "free") {
      freeSeconds += step.duration * times;
    } else if (step.type === "hr") {
      heartRateSeconds += step.duration * times;
    } else if (step.type === "steady") {
      tss += (step.duration * times * step.target ** 2) / 36;
    } else {
      const meanSquare = (step.from ** 2 + step.from * step.to + step.to ** 2) / 3;
      tss += (step.duration * times * meanSquare) / 36;
    }
  }
  return {
    estimatedTss: Math.round(tss),
    freeSeconds,
    ...(heartRateSeconds > 0 ? { heartRateSeconds } : {}),
  };
}

/** 0.875 is `87.5`, 0.45 is `45`. */
const percent = (fraction: number): string => String(Math.round(fraction * 1000) / 10);

function leafOutline(step: LeafStep): string {
  const length = formatDuration(step.duration);
  if (step.type === "steady") return `${length} ${percent(step.target)}%`;
  if (step.type === "ramp") return `${length} ${percent(step.from)}→${percent(step.to)}%`;
  if (step.type === "hr") {
    const target =
      step.target_bpm !== undefined
        ? `${step.target_bpm}bpm`
        : `${percent(step.target_lthr ?? 0)}%LTHR`;
    return `${length} hr ${target} (${step.power_min}-${step.power_max}W)`;
  }
  return `${length} free`;
}

/**
 * `10m 45→70%, 3×(10m 88% + 5m 50%), 5m free`: the shape of the workout in one line, targets as % of FTP. A heart-rate step is
 * `40m hr 135bpm (80-220W)`: the target heart rate and the limits of the power the controller may choose.
 */
export function workoutOutline(workout: Workout): string {
  return workout.steps
    .map((step) =>
      step.type === "repeat"
        ? `${step.count}×(${step.steps.map(leafOutline).join(" + ")})`
        : leafOutline(step),
    )
    .join(", ");
}
