import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { type IntervalsClient, IntervalsError } from "./client.ts";
import { eventIdOf, isCyclingType } from "./ids.ts";

/** The `ride_destinations.destination` of this worker. */
export const INTERVALS_DESTINATION = "intervals.icu";

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/** The wait before the next try after `attempts` tries have failed: 1, 2, 4 … minutes, at most 6 hours. */
export function retryDelayMs(attempts: number): number {
  return Math.min(MINUTE * 2 ** Math.max(0, attempts - 1), 6 * 60 * MINUTE);
}

export interface UploadWorkerOptions {
  db: Db;
  client: IntervalsClient;
  bus: EventBus;
  /** Which day a ride belongs to, for pairing it with the plan. */
  timezone: string;
  now?: () => Date;
  log: Logger;
  /** Rows taken per pass (default 5). */
  batch?: number;
  /** How often the loop looks for rows that are due (default 30 s). */
  pollMs?: number;
  /** Failed tries after which a ride is given up (default 20), unless the cause is the configuration. */
  maxAttempts?: number;
  /** For the tests: ticks by hand. */
  timers?: Timers;
}

export interface UploadSummary {
  /** Rows taken. */
  claimed: number;
  done: number;
  /** Failed and will be tried again. */
  retry: number;
  /** Given up. */
  failed: number;
}

export interface UploadWorker {
  /** One pass over the rows that are due, up to the batch size. Never throws for an upload that fails. */
  runOnce(): Promise<UploadSummary>;
  /** Looks for work now, if a pass is not already running, and again when one finishes if asked meanwhile. */
  kick(): void;
  /** Resolves when no pass is running. */
  idle(): Promise<void>;
  /**
   * Retries everything at once, then on the `ride-stored` event and every `pollMs`. Resolves when
   * the queue has been reset and the first pass has been started (use `idle()` to wait for it).
   */
  start(): Promise<void>;
  stop(): Promise<void>;
}

interface Claimed {
  ride_id: string;
  attempts: number;
  /** When the row was last changed, sortable text: the order rides are sent in. */
  queued_at: string;
}

interface RideRow {
  id: string;
  start: string;
  record: unknown;
  fit: Uint8Array | null;
}

/** A ride taken for a try for this long is not taken again: it covers a crash in the middle of one. */
const LEASE_MS = 10 * MINUTE;

/**
 * Sends the rides in the `ride_destinations` queue to intervals.icu (spec §5.5, ADR 0016). The
 * tablet's request never waits for it: intake only queues a row, this worker takes rows that are
 * due, uploads the stored FIT, and records the outcome:
 *
 * - uploaded, or intervals.icu already had the activity: `done`, with the activity's id if known;
 * - refused (a 4xx that says the file is wrong): `failed`, with the reason;
 * - no answer, 429, 5xx, too many tries: `pending` again after a growing wait, up to
 *   {@link retryDelayMs}; after `maxAttempts` failures `failed`. A wrong key or athlete id waits
 *   at the longest interval and is never given up: it needs the owner, and a restart retries at once.
 *
 * The activity is paired with that day's planned workout when it can be told which one it is.
 */
export function createUploadWorker(options: UploadWorkerOptions): UploadWorker {
  const { db, client, bus, timezone, log } = options;
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
      `UPDATE ride_destinations SET next_attempt_at = $3::timestamptz, last_attempt_at = $2::timestamptz
        WHERE destination = $1 AND ride_id IN (
          SELECT ride_id FROM ride_destinations
           WHERE destination = $1 AND state = 'pending'
             AND (next_attempt_at IS NULL OR next_attempt_at <= $2::timestamptz)
           ORDER BY updated_at, ride_id LIMIT $4
           FOR UPDATE SKIP LOCKED)
        RETURNING ride_id, attempts,
          to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') AS queued_at`,
      [INTERVALS_DESTINATION, iso(at), iso(at + LEASE_MS), batch],
    );
    // UPDATE ... RETURNING comes in no particular order: oldest first.
    return result.rows.sort(
      (a, b) => a.queued_at.localeCompare(b.queued_at) || a.ride_id.localeCompare(b.ride_id),
    );
  }

  async function finish(
    rideId: string,
    attempts: number,
    outcome:
      | { state: "done"; externalId: string | null }
      | { state: "failed"; error: string }
      | { state: "pending"; error: string; delayMs: number },
  ): Promise<void> {
    const at = now().getTime();
    await db.query(
      `UPDATE ride_destinations
          SET state = $3, attempts = $4, last_error = $5, external_id = $6,
              next_attempt_at = $7::timestamptz, last_attempt_at = $2::timestamptz, updated_at = now()
        WHERE ride_id = $1 AND destination = $8`,
      [
        rideId,
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

  /** The planned workout this ride was ridden for, if the day's calendar says so. Never throws. */
  async function pairedEventFor(ride: RideRow, startMs: number): Promise<number | null> {
    const date = localDate(startMs, timezone);
    try {
      const events = (await client.listEvents(date, date)).filter(
        (event) =>
          (event.category === undefined ||
            event.category === null ||
            event.category === "WORKOUT") &&
          isCyclingType(event.type) &&
          event.start_date_local.startsWith(date),
      );
      const workoutId = (ride.record as { ride?: { workoutId?: unknown } }).ride?.workoutId;
      const wanted = typeof workoutId === "string" ? eventIdOf(workoutId) : null;
      if (wanted !== null && events.some((event) => event.id === wanted)) return wanted;
      const [only] = events;
      return events.length === 1 && only !== undefined ? only.id : null;
    } catch (error) {
      log.warn(
        { rideId: ride.id, reason: error instanceof Error ? error.message : "unknown" },
        "could not look up the day's planned workout; uploading without pairing",
      );
      return null;
    }
  }

  async function attempt(row: Claimed): Promise<"done" | "retry" | "failed"> {
    const attempts = row.attempts + 1;
    const found = await db.query<RideRow & { start: string }>(
      "SELECT id, start_epoch_millis::text AS start, record, fit FROM rides WHERE id = $1",
      [row.ride_id],
    );
    const ride = found.rows[0];
    if (ride === undefined || ride.fit === null) {
      await finish(row.ride_id, attempts, {
        state: "failed",
        error: "The ride or its FIT file is not stored",
      });
      log.error(
        { rideId: row.ride_id },
        "intervals.icu upload given up: the ride or its FIT file is missing",
      );
      return "failed";
    }

    const startMs = Number(ride.start);
    let sent: { result: "uploaded" | "duplicate"; activityId: string | null; paired: boolean };
    try {
      const pairedEventId = await pairedEventFor(ride, startMs);
      const outcome = await client.uploadActivity({
        fit: ride.fit,
        filename: `${ride.id}.fit`,
        externalId: ride.id,
        ...(pairedEventId === null ? {} : { pairedEventId }),
      });
      let activityId = outcome.activityId;
      if (outcome.status === "duplicate" && activityId === null) {
        // Already there: find its id, if it can be found, for the record. Not finding it is fine.
        activityId = await client
          .findActivityId({
            externalId: ride.id,
            startEpochMillis: startMs,
            oldest: localDate(startMs - DAY, timezone),
            newest: localDate(startMs + DAY, timezone),
          })
          .catch(() => null);
      }
      sent = {
        result: outcome.status,
        activityId,
        paired: outcome.status === "uploaded" && pairedEventId !== null,
      };
    } catch (error) {
      const known = error instanceof IntervalsError ? error : null;
      const reason = known?.message ?? "the upload failed unexpectedly";
      if (known === null)
        log.error({ err: error, rideId: ride.id }, "intervals.icu upload failed unexpectedly");
      const giveUp =
        known?.permanent === true || (known?.configuration !== true && attempts >= maxAttempts);
      if (giveUp) {
        await finish(ride.id, attempts, { state: "failed", error: reason });
        log.error(
          { rideId: ride.id, attempts, kind: known?.kind, reason },
          "intervals.icu upload given up",
        );
        return "failed";
      }
      const delayMs = retryDelayMs(known?.configuration === true ? maxAttempts : attempts);
      await finish(ride.id, attempts, { state: "pending", error: reason, delayMs });
      log.warn(
        {
          rideId: ride.id,
          attempts,
          kind: known?.kind,
          retryInMinutes: Math.round(delayMs / MINUTE),
          reason,
        },
        "intervals.icu upload failed; will be tried again",
      );
      return "retry";
    }

    // Recorded outside the try: if the database fails here the upload is not mistaken for a failed
    // one. The row's lease runs out and the ride is tried again, which intervals.icu answers as a
    // duplicate.
    await finish(ride.id, attempts, { state: "done", externalId: sent.activityId });
    log.info(
      {
        rideId: ride.id,
        attempts,
        result: sent.result,
        activityId: sent.activityId,
        paired: sent.paired,
      },
      "ride sent to intervals.icu",
    );
    return "done";
  }

  async function runOnce(): Promise<UploadSummary> {
    const summary: UploadSummary = { claimed: 0, done: 0, retry: 0, failed: 0 };
    for (const row of await claim()) {
      summary.claimed++;
      try {
        summary[await attempt(row)]++;
      } catch (error) {
        // The database failed while recording an outcome: the row's lease runs out and it is tried
        // again (a ride intervals.icu already has is then a duplicate, which is success).
        log.error({ err: error, rideId: row.ride_id }, "could not record the outcome of an upload");
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
          let summary: UploadSummary;
          do {
            summary = await runOnce();
          } while (summary.claimed >= batch);
        } catch (error) {
          log.error({ err: error }, "intervals.icu upload pass failed");
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
      unsubscribe = bus.on("ride-stored", () => kick());
      stopTicking = timers.every(pollMs, kick);
      // A restart is a chance to try again at once: the owner may have fixed the key meanwhile.
      return db
        .query(
          `UPDATE ride_destinations SET next_attempt_at = $2::timestamptz
            WHERE destination = $1 AND state = 'pending' AND next_attempt_at > $2::timestamptz`,
          [INTERVALS_DESTINATION, now().toISOString()],
        )
        .then(
          () => {},
          (error: unknown) =>
            log.error({ err: error }, "could not reset the upload queue at start"),
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
