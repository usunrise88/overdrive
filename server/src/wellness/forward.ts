import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { type IntervalsClient, IntervalsError } from "../intervals/client.ts";
import { INTERVALS_DESTINATION, retryDelayMs } from "../intervals/uploads.ts";
import type { Logger } from "../log.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { localParts } from "../zoned.ts";
import type { ActivityRow, ActivityStore } from "./activities.ts";
import { nameOf } from "./activities.ts";

/**
 * Forwards the workouts of the watch to intervals.icu as activities of the matching sport (spec §6.7, ADR 0023): the way rides are
 * sent (the `ride_destinations` queue and its worker), through the same client, off without a key. **Nothing goes to Strava.**
 * An outdoor ride imported from a FIT file (spec §11.8, ADR 0026) goes the same way, but as the file itself, unchanged
 * (`POST activities` with the FIT bytes, as a ride from the tablet does), under the external id `overdrive-fit-<hash>`.
 *
 * The call is `POST activities/manual` with the start, the sport, the name, the duration and the average and maximum heart
 * rate, and the activity's id (`overdrive-watch-<record id>`) as `external_id`. It is idempotent here, not there: before
 * posting, the worker looks for an activity with that external id (or one that started within a minute) and counts it as done, and
 * a queue row is posted once. Both the endpoint and its fields are from the public API documentation and could not be tried
 * without an account.
 */

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
/** A ride on the tablet and the same ride on the watch are one ride: it is sent from the tablet only. */
const OVERLAPS_A_RIDE = "the watch recorded a ride that the tablet sends";
/** The same for an imported ride that a ride from the tablet turned out to overlap. */
const FIT_OVERLAPS_A_RIDE = "the same ride is recorded on the tablet, which sends it";
/** A watch workout that overlaps an imported ride: the file is sent, the workout is not. */
const OVERLAPS_A_FIT_RIDE =
  "the watch recorded a ride that was imported from a FIT file, which is sent";

/** The intervals.icu sport of an exercise type (the phone's names, lower-case tokens); unknown ones are a generic workout. */
export function intervalsTypeOf(type: string): string {
  if (type === "biking_stationary" || type === "stationary_biking") return "VirtualRide";
  if (type === "biking" || type === "cycling" || type === "cycling_outdoor") return "Ride";
  if (type === "cycling-outdoor") return "Ride";
  if (type.startsWith("running") || type === "run") return "Run";
  if (type === "walking" || type === "walk") return "Walk";
  if (type === "hiking" || type === "hike") return "Hike";
  if (type.startsWith("swimming") || type === "swim") return "Swim";
  if (type === "rowing" || type === "rowing_machine") return "Rowing";
  if (type === "yoga") return "Yoga";
  if (type === "strength_training" || type === "weightlifting" || type === "calisthenics") {
    return "WeightTraining";
  }
  return "Workout";
}

/** The start as the athlete's wall clock: `2026-11-17T20:05:00`. */
export function localDateTime(instantMs: number, timezone: string): string {
  const parts = localParts(instantMs, timezone);
  const seconds = new Date(instantMs).getUTCSeconds();
  const two = (n: number) => String(n).padStart(2, "0");
  return `${parts.date}T${two(parts.hour)}:${two(parts.minute)}:${two(seconds)}`;
}

export interface ActivityForwarderOptions {
  db: Db;
  client: Pick<IntervalsClient, "createManualActivity" | "findActivityId" | "uploadActivity">;
  bus: EventBus;
  activities: Pick<ActivityStore, "get" | "fitBytes">;
  timezone: string;
  now?: () => Date;
  log: Logger;
  batch?: number;
  pollMs?: number;
  maxAttempts?: number;
  timers?: Timers;
}

export interface ForwardSummary {
  claimed: number;
  done: number;
  skipped: number;
  retry: number;
  failed: number;
}

export interface ActivityForwarder {
  runOnce(): Promise<ForwardSummary>;
  kick(): void;
  idle(): Promise<void>;
  start(): Promise<void>;
  stop(): Promise<void>;
}

interface Claimed {
  activity_id: string;
  attempts: number;
  queued_at: string;
}

const LEASE_MS = 10 * MINUTE;

export function createActivityForwarder(options: ActivityForwarderOptions): ActivityForwarder {
  const { db, client, bus, activities, timezone, log } = options;
  const now = options.now ?? (() => new Date());
  const batch = options.batch ?? 5;
  const pollMs = options.pollMs ?? 30_000;
  const maxAttempts = options.maxAttempts ?? 20;
  const timers = options.timers ?? realTimers;

  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  let unsubscribe: (() => void) | null = null;

  const iso = (ms: number): string => new Date(ms).toISOString();

  async function claim(): Promise<Claimed[]> {
    const at = now().getTime();
    const result = await db.query<Claimed>(
      `UPDATE activity_destinations SET next_attempt_at = $3::timestamptz, last_attempt_at = $2::timestamptz
        WHERE destination = $1 AND activity_id IN (
          SELECT activity_id FROM activity_destinations
           WHERE destination = $1 AND state = 'pending'
             AND (next_attempt_at IS NULL OR next_attempt_at <= $2::timestamptz)
           ORDER BY updated_at, activity_id LIMIT $4
           FOR UPDATE SKIP LOCKED)
        RETURNING activity_id, attempts,
          to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') AS queued_at`,
      [INTERVALS_DESTINATION, iso(at), iso(at + LEASE_MS), batch],
    );
    return result.rows.sort(
      (a, b) =>
        a.queued_at.localeCompare(b.queued_at) || a.activity_id.localeCompare(b.activity_id),
    );
  }

  async function finish(
    activityId: string,
    attempts: number,
    outcome:
      | { state: "done"; externalId: string | null }
      | { state: "skipped" | "failed"; error: string }
      | { state: "pending"; error: string; delayMs: number },
  ): Promise<void> {
    const at = now().getTime();
    await db.query(
      `UPDATE activity_destinations
          SET state = $3, attempts = $4, last_error = $5, external_id = $6,
              next_attempt_at = $7::timestamptz, last_attempt_at = $2::timestamptz, updated_at = now()
        WHERE activity_id = $1 AND destination = $8`,
      [
        activityId,
        iso(at),
        outcome.state,
        attempts,
        outcome.state === "done" ? null : outcome.error,
        outcome.state === "done" ? outcome.externalId : null,
        iso(outcome.state === "pending" ? at + outcome.delayMs : at),
        INTERVALS_DESTINATION,
      ],
    );
  }

  const externalIdOf = (activityId: string): string => `overdrive-${activityId}`;

  async function post(
    row: ActivityRow,
    fit: Uint8Array | null,
  ): Promise<{ id: string | null; result: "created" | "existing" }> {
    const externalId = externalIdOf(row.id);
    const lookup = {
      externalId,
      startEpochMillis: row.startMs,
      oldest: localDate(row.startMs - DAY, timezone),
      newest: localDate(row.startMs + DAY, timezone),
    };
    const found = await client.findActivityId(lookup).catch(() => null);
    if (found !== null) return { id: found, result: "existing" };
    if (fit !== null) {
      // An outdoor ride from a FIT file: the file as it came, which intervals.icu knows how to read.
      const outcome = await client.uploadActivity({
        fit,
        filename: `${row.id}.fit`,
        externalId,
        name: nameOf(row),
      });
      if (outcome.status === "uploaded") return { id: outcome.activityId, result: "created" };
      const known = outcome.activityId ?? (await client.findActivityId(lookup).catch(() => null));
      return { id: known, result: "existing" };
    }
    const id = await client.createManualActivity({
      externalId,
      startDateLocal: localDateTime(row.startMs, timezone),
      type: intervalsTypeOf(row.type),
      name: nameOf(row),
      durationSeconds: Math.round((row.endMs - row.startMs) / 1000),
      ...(row.averageHeartRate === null
        ? {}
        : { averageHeartRate: Math.round(row.averageHeartRate) }),
      ...(row.maxHeartRate === null ? {} : { maxHeartRate: row.maxHeartRate }),
    });
    return { id, result: "created" };
  }

  async function attempt(row: Claimed): Promise<keyof Omit<ForwardSummary, "claimed">> {
    const attempts = row.attempts + 1;
    const activity = await activities.get(row.activity_id);
    if (activity === null) {
      await finish(row.activity_id, attempts, {
        state: "failed",
        error: "The activity is not stored",
      });
      log.error(
        { activityId: row.activity_id },
        "intervals.icu forwarding given up: the activity is missing",
      );
      return "failed";
    }
    if (activity.overlapsRideId !== null) {
      const reason =
        activity.source === "fit"
          ? FIT_OVERLAPS_A_RIDE
          : activity.overlapsRideId.startsWith("fit-")
            ? OVERLAPS_A_FIT_RIDE
            : OVERLAPS_A_RIDE;
      await finish(row.activity_id, attempts, { state: "skipped", error: reason });
      log.info(
        { activityId: activity.id },
        "activity not forwarded: it overlaps another record of the ride",
      );
      return "skipped";
    }
    let fit: Uint8Array | null = null;
    if (activity.source === "fit") {
      fit = await activities.fitBytes(activity.id);
      if (fit === null) {
        await finish(row.activity_id, attempts, {
          state: "failed",
          error: "The FIT file is not stored",
        });
        log.error(
          { activityId: activity.id },
          "intervals.icu forwarding given up: the FIT file is missing",
        );
        return "failed";
      }
    }
    let sent: { id: string | null; result: "created" | "existing" };
    try {
      sent = await post(activity, fit);
    } catch (error) {
      const known = error instanceof IntervalsError ? error : null;
      const reason = known?.message ?? "the forwarding failed unexpectedly";
      if (known === null) {
        log.error({ activityId: activity.id }, "intervals.icu forwarding failed unexpectedly");
      }
      const giveUp =
        known?.permanent === true || (known?.configuration !== true && attempts >= maxAttempts);
      if (giveUp) {
        await finish(activity.id, attempts, { state: "failed", error: reason });
        log.error(
          { activityId: activity.id, attempts, kind: known?.kind, reason },
          "intervals.icu forwarding given up",
        );
        return "failed";
      }
      const delayMs = retryDelayMs(known?.configuration === true ? maxAttempts : attempts);
      await finish(activity.id, attempts, { state: "pending", error: reason, delayMs });
      log.warn(
        {
          activityId: activity.id,
          attempts,
          kind: known?.kind,
          retryInMinutes: Math.round(delayMs / MINUTE),
          reason,
        },
        "intervals.icu forwarding failed; will be tried again",
      );
      return "retry";
    }
    await finish(activity.id, attempts, { state: "done", externalId: sent.id });
    log.info(
      { activityId: activity.id, attempts, result: sent.result, source: activity.source },
      "activity sent to intervals.icu",
    );
    return "done";
  }

  async function runOnce(): Promise<ForwardSummary> {
    const summary: ForwardSummary = { claimed: 0, done: 0, skipped: 0, retry: 0, failed: 0 };
    for (const row of await claim()) {
      summary.claimed++;
      try {
        summary[await attempt(row)]++;
      } catch (error) {
        const code = (error as { code?: unknown } | null)?.code;
        log.error(
          { activityId: row.activity_id, code: typeof code === "string" ? code : undefined },
          "could not record the outcome of a forwarding",
        );
        summary.retry++;
      }
    }
    return summary;
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
          let summary: ForwardSummary;
          do {
            summary = await runOnce();
          } while (summary.claimed >= batch);
        } catch (error) {
          const code = (error as { code?: unknown } | null)?.code;
          log.error(
            { code: typeof code === "string" ? code : undefined },
            "activity forwarding pass failed",
          );
        }
      } while (again);
    })().finally(() => {
      current = null;
    });
  }

  return {
    runOnce,
    kick,
    idle: () => current ?? Promise.resolve(),
    start() {
      if (stopTicking !== null) return Promise.resolve();
      unsubscribe = bus.on("activity-stored", () => kick());
      stopTicking = timers.every(pollMs, kick);
      return db
        .query(
          `UPDATE activity_destinations SET next_attempt_at = $2::timestamptz
            WHERE destination = $1 AND state = 'pending' AND next_attempt_at > $2::timestamptz`,
          [INTERVALS_DESTINATION, now().toISOString()],
        )
        .then(
          () => {},
          () => log.error({}, "could not reset the activity queue at start"),
        )
        .then(kick);
    },
    async stop() {
      stopTicking?.();
      stopTicking = null;
      unsubscribe?.();
      unsubscribe = null;
      await current;
    },
  };
}
