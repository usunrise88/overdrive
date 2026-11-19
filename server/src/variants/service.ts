import { addDaysToIsoDate, type RideHeader, workoutHash } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { type IntervalsClient, IntervalsError } from "../intervals/client.ts";
import { eventIdOf } from "../intervals/ids.ts";
import { retryDelayMs } from "../intervals/uploads.ts";
import { renderWorkoutText } from "../intervals/workout-text.ts";
import type { Logger } from "../log.ts";
import { readPlanEvent } from "../plan/entries.ts";
import type { ExtrasStore } from "../plan/extras.ts";
import type { MirrorChange, PlanStore } from "../plan/store.ts";
import type { PlanEventsService } from "../plan-events/service.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";

/**
 * The easier variant's life after it is written (spec §11.5, ADR 0036). The variant itself is kept by `plan/extras.ts` and lives only in
 * this service. Here is what happens around it:
 *
 * - **Ridden.** A ride whose record says `variantRidden` makes a plan event (`planEvents.emit({ kind: "variant" })`, once per ride) and
 *   queues the update of its intervals.icu event: the event is changed to the steps that were ridden, so that planned and completed line
 *   up. The update is a durable queue row (`variant_rides`) with retries, idempotent (an event that already has the text is not written
 *   again) and it consumes the variant once done. The ride's workout copy is the variant, by the resolver.
 * - **Not ridden.** Nothing is sent anywhere. The variant is dropped when the session is moved or deleted (the plan refresh and the
 *   tools tell), and the whole row a few days after its day has passed.
 */

/** A row of extras is kept this many days after its planned day: a ride uploaded late still finds its variant. */
export const VARIANT_KEEP_DAYS = 2;
/** How often the queue is looked at and old rows are swept. */
const TICK_MS = 5 * 60_000;
const MAX_ATTEMPTS = 20;
const BATCH = 5;

export interface VariantsService {
  /** A ride that says it played the variant was stored: queues the update of the event and makes the plan event. Idempotent. */
  onRideStored(
    ride: Pick<RideHeader, "id" | "workoutId" | "variantRidden" | "startEpochMillis">,
  ): Promise<void>;
  /** The plan refresh found sessions moved or deleted in the calendar: their variants go. */
  onCalendarChanges(change: Pick<MirrorChange, "moved" | "deleted">): Promise<void>;
  /** Removes the extras of days that are more than {@link VARIANT_KEEP_DAYS} behind; how many. */
  sweep(): Promise<number>;
  /** One pass over the updates that are due. */
  runOnce(): Promise<{ claimed: number; done: number; retry: number; failed: number }>;
  kick(): void;
  idle(): Promise<void>;
  start(): void;
  stop(): Promise<void>;
}

export interface VariantsOptions {
  db: Db;
  extras: ExtrasStore;
  plan: Pick<PlanStore, "get" | "upsert">;
  /** null when no plan source is configured: there is no event to change. */
  intervals: Pick<IntervalsClient, "getEvent" | "updateEvent"> | null;
  planEvents: Pick<PlanEventsService, "emit">;
  rideWorkouts: RideWorkoutResolver;
  bus: EventBus;
  timezone: string;
  now: () => Date;
  log: Logger;
  timers?: Timers | undefined;
}

interface QueueRow {
  ride_id: string;
  workout_id: string;
  day: string;
  attempts: number;
}

const iso = (date: Date): string => date.toISOString();

export function createVariants(options: VariantsOptions): VariantsService {
  const { db, extras, plan, intervals, planEvents, rideWorkouts, timezone, now, log } = options;
  const timers = options.timers ?? realTimers;
  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;

  async function onRideStored(
    ride: Pick<RideHeader, "id" | "workoutId" | "variantRidden" | "startEpochMillis">,
  ): Promise<void> {
    if (ride.variantRidden !== true || ride.workoutId === undefined || ride.workoutId === null) {
      return;
    }
    const workoutId = ride.workoutId;
    const planned = await plan.get(workoutId);
    const kept = await extras.get(workoutId);
    const day = planned?.date ?? kept?.date ?? localDate(ride.startEpochMillis, timezone);
    // The ride's workout copy is the variant: take it now, while the variant is still here.
    await rideWorkouts.resolve(ride);
    await db.query(
      `INSERT INTO variant_rides (ride_id, workout_id, day, created_at, updated_at)
       VALUES ($1, $2, $3::date, $4::timestamptz, $4::timestamptz) ON CONFLICT (ride_id) DO NOTHING`,
      [ride.id, workoutId, day, iso(now())],
    );
    await planEvents.emit({
      kind: "variant",
      day,
      workoutId,
      detail: { name: planned?.name ?? workoutId, rideId: ride.id },
      naturalKey: `variant:${ride.id}`,
    });
    log.info({ rideId: ride.id, workoutId }, "the easier variant was ridden");
    kick();
  }

  async function claim(): Promise<QueueRow[]> {
    const result = await db.query<QueueRow>(
      `SELECT ride_id, workout_id, day::text AS day, attempts FROM variant_rides
        WHERE state = 'pending' AND (next_attempt_at IS NULL OR next_attempt_at <= $1::timestamptz)
        ORDER BY created_at, ride_id LIMIT $2`,
      [iso(now()), BATCH],
    );
    return result.rows;
  }

  async function finish(
    rideId: string,
    attempts: number,
    outcome:
      | { state: "done" }
      | { state: "failed"; error: string }
      | { state: "pending"; error: string; delayMs: number },
  ): Promise<void> {
    await db.query(
      `UPDATE variant_rides SET state = $2, attempts = $3, last_error = $4, next_attempt_at = $5::timestamptz, updated_at = $6::timestamptz
        WHERE ride_id = $1`,
      [
        rideId,
        outcome.state,
        attempts,
        outcome.state === "done" ? null : outcome.error,
        outcome.state === "pending" ? iso(new Date(now().getTime() + outcome.delayMs)) : null,
        iso(now()),
      ],
    );
  }

  /**
   * Changes the event to the steps that were ridden. `already`: it was so; `gone`: there is no event; `same-as-primary`: the ride's copy
   * is the primary itself (the variant was no longer known when the ride was stored), so nothing is said about an easier version.
   */
  async function updateEvent(
    row: QueueRow,
  ): Promise<"written" | "already" | "gone" | "same-as-primary" | "no-copy"> {
    const eventId = eventIdOf(row.workout_id);
    if (intervals === null || eventId === null) return "gone";
    const header = { id: row.ride_id, workoutId: row.workout_id, variantRidden: true as const };
    const copy = await rideWorkouts.resolve(header);
    if (copy.workout === null) return "no-copy";
    const event = await intervals.getEvent(eventId);
    if (event === null) return "gone";
    const note = (await extras.get(row.workout_id))?.variant?.note;
    const text = renderWorkoutText({
      ...copy.workout,
      description: `Easier version ridden${note === undefined ? "" : `: ${note}`}.`,
    });
    if ((event.description ?? "").trim() === text.trim()) return "already";
    const before = readPlanEvent(event);
    if (
      before.kind === "entry" &&
      before.entry.workout !== null &&
      workoutHash(before.entry.workout) === workoutHash(copy.workout)
    ) {
      return "same-as-primary";
    }
    const updated = await intervals.updateEvent(eventId, { description: text });
    // The cache says what the calendar now says, without waiting for the next refresh.
    const after = readPlanEvent(updated);
    if (after.kind === "entry") await plan.upsert(after.entry);
    return "written";
  }

  async function attempt(row: QueueRow): Promise<"done" | "retry" | "failed"> {
    const attempts = row.attempts + 1;
    try {
      const outcome = await updateEvent(row);
      if (outcome === "no-copy") {
        await finish(row.ride_id, attempts, {
          state: "failed",
          error: "the workout copy of the ride is not known",
        });
        log.warn(
          { rideId: row.ride_id },
          "the intervals.icu event was not updated: no workout copy",
        );
        return "failed";
      }
      // The session is done: the variant is consumed, and is not offered again.
      await extras.setVariant(row.workout_id, row.day, null).catch((error: unknown) => {
        log.warn(
          { err: error, workoutId: row.workout_id },
          "the variant could not be removed after it was ridden",
        );
      });
      await finish(row.ride_id, attempts, { state: "done" });
      log.info(
        { rideId: row.ride_id, result: outcome },
        "the intervals.icu event matches what was ridden",
      );
      return "done";
    } catch (error) {
      const known = error instanceof IntervalsError ? error : null;
      const reason = known?.message ?? "the update failed unexpectedly";
      if (known === null)
        log.error({ err: error, rideId: row.ride_id }, "the event update failed unexpectedly");
      const giveUp =
        known?.permanent === true || (known?.configuration !== true && attempts >= MAX_ATTEMPTS);
      if (giveUp) {
        await finish(row.ride_id, attempts, { state: "failed", error: reason });
        log.error(
          { rideId: row.ride_id, attempts, reason },
          "the intervals.icu event update was given up",
        );
        return "failed";
      }
      const delayMs = retryDelayMs(known?.configuration === true ? MAX_ATTEMPTS : attempts);
      await finish(row.ride_id, attempts, { state: "pending", error: reason, delayMs });
      log.warn(
        { rideId: row.ride_id, attempts, reason },
        "the intervals.icu event update will be tried again",
      );
      return "retry";
    }
  }

  async function runOnce() {
    const summary = { claimed: 0, done: 0, retry: 0, failed: 0 };
    for (const row of await claim()) {
      summary.claimed++;
      try {
        summary[await attempt(row)]++;
      } catch (error) {
        // The database failed while recording an outcome: the row is still pending and is tried again; the update is idempotent.
        log.error(
          { err: error, rideId: row.ride_id },
          "could not record the outcome of an event update",
        );
        summary.retry++;
      }
    }
    return summary;
  }

  async function sweep(): Promise<number> {
    const today = localDate(now(), timezone);
    return extras.sweep(addDaysToIsoDate(today, -VARIANT_KEEP_DAYS));
  }

  function kick(): void {
    if (current !== null) {
      again = true;
      return;
    }
    current = (async () => {
      do {
        again = false;
        try {
          let summary: Awaited<ReturnType<typeof runOnce>>;
          do {
            summary = await runOnce();
          } while (summary.claimed >= BATCH);
          await sweep();
        } catch (error) {
          log.error({ err: error }, "the variant pass failed");
        }
      } while (again);
    })().finally(() => {
      current = null;
    });
  }

  return {
    onRideStored,
    async onCalendarChanges(change) {
      for (const moved of change.moved) {
        if (await extras.moved(moved.id, moved.to)) {
          log.info(
            { workoutId: moved.id },
            "the easier variant was dropped: the session was moved",
          );
        }
      }
      for (const deleted of change.deleted) {
        if (await extras.remove(deleted.id)) {
          log.info(
            { workoutId: deleted.id },
            "the easier variant was dropped: the session was deleted",
          );
        }
      }
    },
    sweep,
    runOnce,
    kick,
    idle: () => current ?? Promise.resolve(),
    start() {
      // Without a plan source there are no planned workouts, so no variants and no events to change.
      if (stopTicking !== null || intervals === null) return;
      stopTicking = timers.every(TICK_MS, kick);
      kick();
    },
    async stop() {
      stopTicking?.();
      stopTicking = null;
      await current;
    },
  };
}
