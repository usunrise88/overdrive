import type { RideHeader, Workout } from "@overdrive/contracts";
import { type IntervalsClient, IntervalsError } from "./intervals/client.ts";
import { eventIdOf } from "./intervals/ids.ts";
import type { Logger } from "./log.ts";
import { readPlanEvent } from "./plan/entries.ts";
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
   * plan moves on or the event is deleted. Never throws.
   */
  resolve(ride: Pick<RideHeader, "id" | "workoutId">): Promise<RideWorkout>;
}

export interface RideWorkoutDeps {
  rides: RideStore;
  plan: Pick<PlanStore, "get">;
  /** null when no plan source is configured. */
  intervals: Pick<IntervalsClient, "getEvent"> | null;
  now: () => Date;
  log: Logger;
}

export function createRideWorkoutResolver(deps: RideWorkoutDeps): RideWorkoutResolver {
  const { rides, plan, intervals, now, log } = deps;
  const none = (reason: string): RideWorkout => ({ workout: null, reason });

  return {
    async resolve(ride) {
      const workoutId = ride.workoutId;
      if (workoutId === undefined || workoutId === null) {
        return none("this was a free ride: no workout was linked");
      }
      try {
        const kept = await rides.workoutSnapshot(ride.id);
        if (kept !== null) return { workout: kept };

        const cached = await plan.get(workoutId);
        if (cached !== null) {
          await rides.saveWorkoutSnapshot(ride.id, cached.workout, now());
          return { workout: cached.workout };
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
        await rides.saveWorkoutSnapshot(ride.id, reading.entry.workout, now());
        return { workout: reading.entry.workout };
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
