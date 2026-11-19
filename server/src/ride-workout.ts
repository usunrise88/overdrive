import {
  type PlannedVariant,
  type RideHeader,
  variantWorkout,
  type Workout,
  withoutLiveCoach,
} from "@overdrive/contracts";
import { type IntervalsClient, IntervalsError } from "./intervals/client.ts";
import { eventIdOf } from "./intervals/ids.ts";
import type { Logger } from "./log.ts";
import { readPlanEvent } from "./plan/entries.ts";
import type { ExtrasStore } from "./plan/extras.ts";
import type { PlanStore } from "./plan/store.ts";
import type { RideStore } from "./ride-store.ts";

/**
 * The workout a ride was played from, or why there is none to compare with. `failed` is set when a lookup
 * failed (intervals.icu could not be asked, the database failed), not when there is simply no workout.
 */
export type RideWorkout = { workout: Workout } | { workout: null; reason: string; failed?: true };

export interface RideWorkoutResolver {
  /**
   * The workout behind `ride.workoutId`. The copy taken earlier if there is one; otherwise the plan
   * cache, then intervals.icu, and whatever is found is kept, so the ride keeps its workout when the
   * plan moves on or the event is deleted. A ride that says it played the easier variant (`variantRidden`, spec §11.5) gets the
   * planned workout with the variant's steps, while the service still has them, and the primary otherwise. Never throws.
   */
  resolve(ride: Pick<RideHeader, "id" | "workoutId" | "variantRidden">): Promise<RideWorkout>;
}

export interface RideWorkoutDeps {
  rides: RideStore;
  plan: Pick<PlanStore, "get">;
  /** null when no plan source is configured. */
  intervals: Pick<IntervalsClient, "getEvent"> | null;
  /** Where the easier variants are kept; without it a variant ride is resolved to the primary. */
  extras?: Pick<ExtrasStore, "get">;
  now: () => Date;
  log: Logger;
}

export function createRideWorkoutResolver(deps: RideWorkoutDeps): RideWorkoutResolver {
  const { rides, plan, intervals, now, log } = deps;
  const none = (reason: string): RideWorkout => ({ workout: null, reason });

  /** The primary workout from the plan cache, else from intervals.icu, or the reason there is none. */
  async function primaryOf(
    workoutId: string,
  ): Promise<{ workout: Workout; variant?: PlannedVariant } | { workout: null; reason: string }> {
    const cached = await plan.get(workoutId);
    if (cached !== null) {
      return {
        workout: cached.workout,
        ...(cached.variant === undefined ? {} : { variant: cached.variant }),
      };
    }
    const eventId = eventIdOf(workoutId);
    if (intervals === null || eventId === null) {
      return none(`the workout ${workoutId} is no longer in the plan and cannot be looked up`);
    }
    const event = await intervals.getEvent(eventId);
    if (event === null) {
      return none(`the workout ${workoutId} is no longer in the plan (it was deleted)`);
    }
    const reading = readPlanEvent(event);
    if (reading.kind !== "entry" || reading.entry.workout === null) {
      return none(
        `the workout ${workoutId} is in the calendar but cannot be read as a workout${
          reading.kind === "entry" && reading.entry.unsupportedReason !== null
            ? `: ${reading.entry.unsupportedReason}`
            : ""
        }`,
      );
    }
    return { workout: reading.entry.workout };
  }

  return {
    async resolve(ride) {
      const workoutId = ride.workoutId;
      if (workoutId === undefined || workoutId === null) {
        return none("this was a free ride: no workout was linked");
      }
      try {
        const kept = await rides.workoutSnapshot(ride.id);
        if (kept !== null) return { workout: kept };

        const found = await primaryOf(workoutId);
        if (found.workout === null) return found;
        let workout = found.workout;
        if (ride.variantRidden === true) {
          // The cache has the variant laid over its entry; a day that has left the cache still has it in the extras, for a few days.
          const variant = found.variant ?? (await deps.extras?.get(workoutId))?.variant ?? null;
          if (variant !== null) {
            workout = variantWorkout(workout, variant);
          } else {
            log.warn(
              { rideId: ride.id },
              "the ride says it played the easier variant, which the service no longer has: the primary workout is kept as its copy",
            );
          }
        }
        // What the ride keeps is what was ridden: the live coach's brief and lines (spec §12.1) are the agent's words to the coach, not part of
        // the workout, and a reader of the ride that does not know them must not meet them (ADR 0038).
        workout = withoutLiveCoach(workout);
        await rides.saveWorkoutSnapshot(ride.id, workout, now());
        return { workout };
      } catch (error) {
        const reason =
          error instanceof IntervalsError
            ? `intervals.icu could not be asked about the workout (${error.message})`
            : "the workout could not be looked up";
        log.warn({ rideId: ride.id, err: error }, "ride workout lookup failed");
        return { workout: null, reason, failed: true };
      }
    },
  };
}
