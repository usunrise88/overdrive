import type { HrStep, LeafStep, Profile, Workout } from "@overdrive/contracts";

/**
 * Heart-rate steps (spec §11.2, docs/adr/0033) and the rider's threshold heart rate.
 *
 * A heart-rate step's target is either `target_bpm` or `target_lthr`, a fraction of the threshold heart rate. The tablet holds a
 * number of beats, so the service resolves the fraction when it serves a workout to the tablet; the stored workout and what the
 * agent reads keep the fraction. **The threshold is `profile.thresholdHeartRate` when the rider set one, else the profile's
 * estimate from the stored rides** (`estimatedThresholdHeartRate`, ADR 0023), the same figure the heart-rate load uses; with
 * neither it is unknown, and then a workout with a fraction is not created (`create_workout`, `update_workout`) and, if it is
 * already in the plan when the threshold goes, it is served as it is: the tablet cannot hold it and plays the step as a free ride.
 */

export function thresholdHeartRate(
  profile: Pick<Profile, "thresholdHeartRate" | "estimatedThresholdHeartRate">,
): number | null {
  return profile.thresholdHeartRate ?? profile.estimatedThresholdHeartRate ?? null;
}

const leavesOf = (workout: Workout): LeafStep[] =>
  workout.steps.flatMap((step) => (step.type === "repeat" ? step.steps : [step]));

/** Whether any step of the workout gives its heart-rate target as a fraction of the threshold. */
export function usesThresholdFraction(workout: Workout): boolean {
  return leavesOf(workout).some((step) => step.type === "hr" && step.target_lthr !== undefined);
}

/** The plain message for a workout that needs a threshold nobody has set. */
export const NO_THRESHOLD_MESSAGE =
  "a heart-rate step gives its target as a fraction of the threshold heart rate (target_lthr), and the profile has no threshold " +
  "heart rate: set thresholdHeartRate with update_profile, or give the target in beats per minute (target_bpm)";

function resolveStep(step: HrStep, threshold: number): HrStep {
  if (step.target_lthr === undefined) return step;
  const { target_lthr: fraction, ...rest } = step;
  // Whole bpm, halves up; inside the bounds of the schema (40 to 230) whatever the threshold.
  const bpm = Math.min(230, Math.max(40, Math.round(fraction * threshold)));
  return { ...rest, target_bpm: bpm };
}

/**
 * The workout as the tablet gets it: every heart-rate step that gives a fraction of the threshold gets that many beats instead
 * (`target_bpm`, no `target_lthr`). Returns the same object when nothing needs resolving, or when the threshold is unknown (the
 * steps are then left as they are).
 */
export function resolveHeartRateTargets(workout: Workout, threshold: number | null): Workout {
  if (threshold === null || !usesThresholdFraction(workout)) return workout;
  return {
    ...workout,
    steps: workout.steps.map((step) =>
      step.type === "repeat"
        ? {
            ...step,
            steps: step.steps.map((inner) =>
              inner.type === "hr" ? resolveStep(inner, threshold) : inner,
            ),
          }
        : step.type === "hr"
          ? resolveStep(step, threshold)
          : step,
    ),
  };
}
