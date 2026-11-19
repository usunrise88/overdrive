import type { LeafStep, Workout } from "@overdrive/contracts";

/**
 * What a planned workout comes to if it is ridden exactly as planned, worked out by code (never by a model): its length, the
 * training stress it would give, its intensity factor and its hardest step. Targets are fractions of FTP, so the load and the
 * intensity do not depend on the FTP; the watts of a step do, and the caller multiplies.
 */
export interface PlannedStats {
  /** The whole workout, repeats counted. */
  seconds: number;
  /** Seconds in free steps: no target, counted for nothing in the load. */
  freeSeconds: number;
  /**
   * Seconds in heart-rate steps (spec §11.2): their power is chosen by the controller while the ride is ridden, so the plan has
   * no power for them and they count for nothing in the load (a planned load with such steps is only part of the session).
   */
  heartRateSeconds: number;
  /** The training stress score: the sum over the steps of `seconds x fraction^2 / 36`; a ramp as the mean square of its ends. */
  load: number;
  /** The intensity factor of the steps that have a target: `sqrt(load x 36 / seconds with a target)`; null with none. */
  intensityFactor: number | null;
  /** The highest target of any step, as a fraction of FTP; null with none. */
  topFraction: number | null;
}

function leaves(workout: Workout): { step: LeafStep; times: number }[] {
  return workout.steps.flatMap((step) =>
    step.type === "repeat"
      ? step.steps.map((inner) => ({ step: inner, times: step.count }))
      : [{ step, times: 1 }],
  );
}

export function plannedStats(workout: Workout): PlannedStats {
  let seconds = 0;
  let freeSeconds = 0;
  let heartRateSeconds = 0;
  let load = 0;
  let top: number | null = null;
  for (const { step, times } of leaves(workout)) {
    const length = step.duration * times;
    seconds += length;
    if (step.type === "free") {
      freeSeconds += length;
      continue;
    }
    if (step.type === "hr") {
      heartRateSeconds += length;
      continue;
    }
    const high = step.type === "steady" ? step.target : Math.max(step.from, step.to);
    top = top === null ? high : Math.max(top, high);
    const meanSquare =
      step.type === "steady"
        ? step.target ** 2
        : (step.from ** 2 + step.from * step.to + step.to ** 2) / 3;
    load += (length * meanSquare) / 36;
  }
  const targeted = seconds - freeSeconds - heartRateSeconds;
  return {
    seconds,
    freeSeconds,
    heartRateSeconds,
    load,
    intensityFactor: targeted > 0 ? Math.sqrt((load * 36) / targeted) : null,
    topFraction: top,
  };
}
