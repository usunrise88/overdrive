import type { Workout } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import type { RideStore } from "../ride-store.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import type { StravaClient, UploadReply } from "./client.ts";
import { describeRide, type RideText } from "./description.ts";
import { StravaError } from "./errors.ts";
import { exhaustedUntil, pauseAfter429, type RateInfo } from "./rate-limit.ts";

/** The `ride_destinations.destination` of this worker. */
export const STRAVA_DESTINATION = "strava";

const SECOND = 1000;
const MINUTE = 60 * SECOND;

/** The wait before the next try after `attempts` tries have failed: 1, 2, 4 … minutes, at most 6 hours. */
export function retryDelayMs(attempts: number): number {
  return Math.min(MINUTE * 2 ** Math.max(0, attempts - 1), 6 * 60 * MINUTE);
}

/** A ride taken for a try for this long is not taken again: it covers a crash in the middle of one. */
const LEASE_MS = 10 * MINUTE;
/** How long a wrong credential or a refused permission is left alone before one more try. */
const BLOCK_MS = 6 * 60 * MINUTE;

export interface StravaUploadsOptions {
  db: Db;
  client: StravaClient;
  bus: EventBus;
  rides: Pick<RideStore, "getRecord">;
  rideWorkouts: RideWorkoutResolver;
  /** Which day a free ride is named for. */
  timezone: string;
  /** Queue the rides stored before Strava was turned on (`STRAVA_BACKFILL`). */
  backfill: boolean;
  /** The clock; the persisted waits are computed from it. */
  now?: () => Date;
  /** Waits between two status checks; the tests do not wait. */
  sleep?: (ms: number) => Promise<void>;
  log: Logger;
  /** How often the loop looks for rows that are due (default 30 s). */
  pollMs?: number;
  /** Failed tries after which a ride is given up (default 20), unless the cause is the configuration. */
  maxAttempts?: number;
  /** Status checks made in a row after the upload, a second or two apart (default 4). */
  pollsPerAttempt?: number;
  /** Status checks in all before the upload is given up as stuck (default 20). */
  maxPolls?: number;
  /** The wait before each status check (default 2 s: Strava asks for at least one second, and takes about two to finish). */
  pollWaitMs?: number;
  /** Rides handled per pass (default 25); a pass that used them all is followed by another. */
  perPass?: number;
  /** For the tests: ticks by hand. */
  timers?: Timers;
}

export interface UploadSummary {
  /** Rows taken. */
  claimed: number;
  done: number;
  /** Not finished: failed and will be tried again, or waiting for Strava's processing, a limit or the owner. */
  retry: number;
  /** Given up. */
  failed: number;
}

export interface StravaUploads {
  /** One pass over the rides that are due. Never throws for an upload that fails. */
  runOnce(): Promise<UploadSummary>;
  /** Looks for work now, if a pass is not already running, and again when one finishes if asked meanwhile. */
  kick(): void;
  /** Resolves when no pass is running. */
  idle(): Promise<void>;
  /**
   * Queues the backfill if asked, makes every waiting ride due, then listens for `ride-stored` and
   * ticks every `pollMs`. Resolves when that is done and the first pass has been started.
   */
  start(): Promise<void>;
  stop(): Promise<void>;
}

/** What an upload's answer means for us. */
export type Verdict =
  /** Strava made an activity: its id. */
  | { kind: "ready"; activityId: string }
  /** Strava already has this activity (the same file, or the same start time): nothing to do. */
  | { kind: "duplicate"; activityId: string | null }
  /** Strava could not make an activity from the file: why. */
  | { kind: "refused"; reason: string }
  /** The activity was made and then deleted on Strava; it is not made again. */
  | { kind: "deleted" }
  | { kind: "processing" };

/** Strava reports a repeat as `<file> duplicate of activity 123` in the upload's `error` (and, perhaps, as a 400). */
const DUPLICATE =
  /duplicate of activity|duplicate activity|already (?:been )?(?:uploaded|exists?)/i;

export function interpretReply(
  reply: Pick<UploadReply, "activityId" | "error" | "status">,
): Verdict {
  if (reply.activityId !== null) return { kind: "ready", activityId: reply.activityId };
  if (reply.error !== null) {
    if (DUPLICATE.test(reply.error)) {
      const id = /duplicate of activity\s+#?(\d+)/i.exec(reply.error)?.[1];
      return { kind: "duplicate", activityId: id ?? null };
    }
    return { kind: "refused", reason: reply.error };
  }
  if (/deleted/i.test(reply.status ?? "")) return { kind: "deleted" };
  return { kind: "processing" };
}

interface Claimed {
  ride_id: string;
  attempts: number;
}

interface RideRow {
  id: string;
  start: string;
  fit: Uint8Array | null;
}

type Result = "done" | "retry" | "failed";

type Outcome =
  | { state: "done"; activityId: string | null }
  | { state: "failed"; error: string }
  /** `counted`: a failed try (it counts toward giving up); otherwise a wait that is no one's fault. */
  | { state: "pending"; error: string | null; at: number; counted: boolean };

/**
 * Sends the rides in the `ride_destinations` queue to Strava (spec §5.5, ADR 0020), the way the
 * intervals.icu worker does (ADR 0016): intake only queues a row, this takes rows that are due one
 * at a time, posts the stored FIT as a virtual ride with its name and description, then checks the
 * upload's status until Strava says which activity it became. Strava is write-only here: the only
 * thing read from it is the answer about our own upload.
 *
 * - ready, or Strava already had the activity: `done`, with the activity's id when Strava says it;
 * - refused (the file is bad, a 4xx): `failed`, with the reason, never retried;
 * - no answer, 5xx, still processing: `pending` again after a persisted wait (1, 2, 4 … minutes for a
 *   failure, up to {@link retryDelayMs}); `failed` after `maxAttempts` failures or `maxPolls` checks;
 * - a limit reached (429, or the headers say so): everything waits, nothing is asked meanwhile, and
 *   the wait is not held against the ride;
 * - a wrong credential, a missing scope or subscription (401, 403, a refused refresh token): one
 *   line in the log, the reason in `last_error` of every waiting ride, no request for six hours or
 *   until the server restarts, which is when the owner has fixed it.
 */
export function createStravaUploads(options: StravaUploadsOptions): StravaUploads {
  const { db, client, bus, rides, rideWorkouts, timezone, log } = options;
  const now = options.now ?? (() => new Date());
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const pollMs = options.pollMs ?? 30 * SECOND;
  const maxAttempts = options.maxAttempts ?? 20;
  const pollsPerAttempt = options.pollsPerAttempt ?? 4;
  const maxPolls = options.maxPolls ?? 20;
  const pollWaitMs = options.pollWaitMs ?? 2 * SECOND;
  const perPass = options.perPass ?? 25;
  const timers = options.timers ?? realTimers;

  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  let unsubscribe: (() => void) | null = null;

  /** A limit was reached: nothing is asked before this instant (epoch milliseconds). */
  let pausedUntil = 0;
  let consecutive429 = 0;
  /** A credential or permission is wrong: nothing is asked before this instant. */
  let blockedUntil = 0;
  let blockReason = "";
  /** The reason already logged, so a block that comes back is said once. */
  let loggedBlock = "";

  const iso = (ms: number): string => new Date(ms).toISOString();
  const at = (): number => now().getTime();

  async function claim(): Promise<Claimed | undefined> {
    const t = at();
    const result = await db.query<Claimed>(
      `UPDATE ride_destinations SET next_attempt_at = $3::timestamptz, last_attempt_at = $2::timestamptz
        WHERE destination = $1 AND ride_id IN (
          SELECT ride_id FROM ride_destinations
           WHERE destination = $1 AND state = 'pending'
             AND (next_attempt_at IS NULL OR next_attempt_at <= $2::timestamptz)
           ORDER BY updated_at, ride_id LIMIT 1
           FOR UPDATE SKIP LOCKED)
        RETURNING ride_id, attempts`,
      [STRAVA_DESTINATION, iso(t), iso(t + LEASE_MS)],
    );
    return result.rows[0];
  }

  async function finish(rideId: string, attempts: number, outcome: Outcome): Promise<void> {
    const t = at();
    const counted = outcome.state === "pending" && !outcome.counted ? 0 : 1;
    await db.query(
      `UPDATE ride_destinations
          SET state = $3, attempts = $4, last_error = $5, external_id = $6,
              next_attempt_at = $7::timestamptz, last_attempt_at = $2::timestamptz, updated_at = now()
        WHERE ride_id = $1 AND destination = $8`,
      [
        rideId,
        iso(t),
        outcome.state,
        attempts + counted,
        outcome.state === "done" ? null : outcome.error,
        outcome.state === "done" ? outcome.activityId : null,
        iso(outcome.state === "pending" ? outcome.at : t),
        STRAVA_DESTINATION,
      ],
    );
  }

  /** Takes note of what the answer says about our usage: a limit used up pauses everything until its window ends. */
  function noteRate(rate: RateInfo | null): void {
    if (rate === null) return;
    const until = exhaustedUntil(rate, at());
    if (until !== null && until > pausedUntil) {
      pausedUntil = until;
      log.info(
        { until: iso(until) },
        "Strava rate limit reached; nothing is sent until its window ends",
      );
    }
  }

  /** The name and the description, from our own record; a ride is never held back for want of them. */
  async function textFor(ride: RideRow): Promise<RideText | { name: string; description: null }> {
    try {
      const record = await rides.getRecord(ride.id);
      if (record === null) throw new Error("the record is not stored");
      let workout: Workout | null = null;
      if (record.ride.workoutId !== null && record.ride.workoutId !== undefined) {
        workout = (await rideWorkouts.resolve(record.ride)).workout;
      }
      return describeRide({ record, workout, timezone });
    } catch (error) {
      log.warn(
        { rideId: ride.id, reason: error instanceof Error ? error.message : "unknown" },
        "could not write the Strava description; uploading with a plain name",
      );
      return { name: `Virtual ride ${localDate(Number(ride.start), timezone)}`, description: null };
    }
  }

  /** Wrong credentials or no permission: stop asking, and say why on every ride that is waiting. */
  async function block(error: StravaError): Promise<void> {
    blockReason = error.message;
    blockedUntil = at() + BLOCK_MS;
    if (loggedBlock !== blockReason) {
      loggedBlock = blockReason;
      log.error(
        { kind: error.kind, status: error.status, reason: blockReason, retryAt: iso(blockedUntil) },
        "Strava upload stopped: it needs the owner. Nothing more is sent until the server restarts or six hours pass",
      );
    }
    await db.query(
      `UPDATE ride_destinations SET last_error = $2, next_attempt_at = $3::timestamptz
        WHERE destination = $1 AND state = 'pending'`,
      [STRAVA_DESTINATION, blockReason, iso(blockedUntil)],
    );
  }

  async function attempt(row: Claimed): Promise<Result> {
    const found = await db.query<RideRow>(
      "SELECT id, start_epoch_millis::text AS start, fit FROM rides WHERE id = $1",
      [row.ride_id],
    );
    const ride = found.rows[0];
    if (ride === undefined || ride.fit === null) {
      await finish(row.ride_id, row.attempts, {
        state: "failed",
        error: "The ride or its FIT file is not stored",
      });
      log.error(
        { rideId: row.ride_id },
        "Strava upload given up: the ride or its FIT file is missing",
      );
      return "failed";
    }

    let upload: { id: string; polls: number } | null = null;
    try {
      // Whether this ride was already posted: the table says so before Strava is asked anything.
      const posted = await db.query<{ upload_id: string; polls: number }>(
        "SELECT upload_id, polls FROM strava_uploads WHERE ride_id = $1",
        [ride.id],
      );
      const known = posted.rows[0];
      let verdict: Verdict = { kind: "processing" };
      if (known === undefined) {
        const text = await textFor(ride);
        const reply = await client.upload({
          fit: ride.fit,
          filename: `${ride.id}.fit`,
          externalId: ride.id,
          name: text.name,
          description: text.description,
        });
        noteRate(reply.rate);
        consecutive429 = 0;
        // Recorded before anything else can go wrong: from here on the ride is not posted again.
        await db.query(
          `INSERT INTO strava_uploads (ride_id, upload_id, polls, posted_at)
           VALUES ($1, $2, 0, $3::timestamptz) ON CONFLICT (ride_id) DO NOTHING`,
          [ride.id, reply.uploadId, iso(at())],
        );
        upload = { id: reply.uploadId, polls: 0 };
        verdict = interpretReply(reply);
      } else {
        upload = { id: known.upload_id, polls: known.polls };
      }

      let checks = 0;
      while (verdict.kind === "processing") {
        if (upload.polls >= maxPolls) {
          const error = `Strava was still processing upload ${upload.id} after ${upload.polls} checks. It may still appear on Strava; the ride is not posted again`;
          await finish(ride.id, row.attempts, { state: "failed", error });
          log.error(
            { rideId: ride.id, uploadId: upload.id, polls: upload.polls },
            "Strava upload given up: still processing",
          );
          return "failed";
        }
        if (at() < pausedUntil) {
          await finish(ride.id, row.attempts, {
            state: "pending",
            error: null,
            at: pausedUntil,
            counted: false,
          });
          return "retry";
        }
        if (checks >= pollsPerAttempt) {
          const round = Math.max(1, Math.floor(upload.polls / pollsPerAttempt));
          const wait = Math.min(30 * SECOND * 2 ** (round - 1), 10 * MINUTE);
          await finish(ride.id, row.attempts, {
            state: "pending",
            error: null,
            at: at() + wait,
            counted: false,
          });
          log.info(
            { rideId: ride.id, uploadId: upload.id, polls: upload.polls },
            "Strava is still processing the upload; it will be checked again",
          );
          return "retry";
        }
        await sleep(pollWaitMs);
        const reply = await client.uploadStatus(upload.id);
        noteRate(reply.rate);
        consecutive429 = 0;
        checks++;
        upload.polls++;
        await db.query("UPDATE strava_uploads SET polls = $2 WHERE ride_id = $1", [
          ride.id,
          upload.polls,
        ]);
        verdict = interpretReply(reply);
      }

      switch (verdict.kind) {
        case "ready":
        case "duplicate": {
          // If the database fails here the handler below records a retry (or the lease runs out): the next try
          // finds the upload in strava_uploads and checks it again, so the ride is not posted twice.
          await finish(ride.id, row.attempts, { state: "done", activityId: verdict.activityId });
          loggedBlock = "";
          log.info(
            {
              rideId: ride.id,
              uploadId: upload.id,
              result: verdict.kind === "ready" ? "uploaded" : "duplicate",
              activityId: verdict.activityId,
            },
            "ride sent to Strava",
          );
          return "done";
        }
        case "deleted": {
          const error = `The activity made from upload ${upload.id} was deleted on Strava; it is not posted again`;
          await finish(ride.id, row.attempts, { state: "failed", error });
          log.warn(
            { rideId: ride.id, uploadId: upload.id },
            "Strava upload not repeated: the activity was deleted on Strava",
          );
          return "failed";
        }
        case "refused": {
          const error = `Strava could not make an activity from the file: ${verdict.reason}`;
          await finish(ride.id, row.attempts, { state: "failed", error });
          log.error(
            { rideId: ride.id, uploadId: upload.id, reason: verdict.reason },
            "Strava upload refused",
          );
          return "failed";
        }
        default:
          throw new Error("unreachable");
      }
    } catch (error) {
      return handleFailure(row, ride.id, error);
    }
  }

  async function handleFailure(row: Claimed, rideId: string, error: unknown): Promise<Result> {
    const known = error instanceof StravaError ? error : null;
    if (known === null) {
      log.error({ err: error, rideId }, "Strava upload failed unexpectedly");
    } else {
      noteRate(known.rate);
    }
    const reason = known?.message ?? "the upload failed unexpectedly";

    if (known?.kind === "rate-limited") {
      consecutive429++;
      pausedUntil = Math.max(
        pausedUntil,
        pauseAfter429({
          now: at(),
          retryAfter: known.retryAfter,
          info: known.rate ?? { overall: null, read: null },
          consecutive: consecutive429,
        }),
      );
      await finish(rideId, row.attempts, {
        state: "pending",
        error: reason,
        at: pausedUntil,
        counted: false,
      });
      log.warn(
        { rideId, retryAfter: known.retryAfter, until: iso(pausedUntil) },
        "Strava is limiting requests; nothing is sent until the wait ends",
      );
      return "retry";
    }

    if (known?.configuration === true) {
      await block(known);
      return "retry";
    }

    if (known?.kind === "rejected" && DUPLICATE.test(known.detail)) {
      // Strava refused the post itself as a repeat: it has the ride.
      const id = /duplicate of activity\s+#?(\d+)/i.exec(known.detail)?.[1] ?? null;
      await finish(rideId, row.attempts, { state: "done", activityId: id });
      log.info({ rideId, activityId: id, result: "duplicate" }, "ride sent to Strava");
      return "done";
    }

    if (known?.permanent === true) {
      await finish(rideId, row.attempts, { state: "failed", error: reason });
      log.error(
        { rideId, kind: known.kind, status: known.status, reason },
        "Strava upload given up",
      );
      return "failed";
    }

    // No answer, a 5xx, a body we cannot use, a database error: try again later. Strava may or may not
    // have taken the upload; if it did, the next try is told it is a duplicate, which is success.
    const attempts = row.attempts + 1;
    if (attempts >= maxAttempts) {
      await finish(rideId, row.attempts, { state: "failed", error: reason });
      log.error({ rideId, attempts, kind: known?.kind, reason }, "Strava upload given up");
      return "failed";
    }
    const delayMs = retryDelayMs(attempts);
    await finish(rideId, row.attempts, {
      state: "pending",
      error: reason,
      at: at() + delayMs,
      counted: true,
    });
    log.warn(
      { rideId, attempts, kind: known?.kind, retryInMinutes: Math.round(delayMs / MINUTE), reason },
      "Strava upload failed; will be tried again",
    );
    return "retry";
  }

  /** Says on the waiting rides why nothing is happening, once, so a ride stored while blocked shows it too. */
  async function stampBlock(): Promise<void> {
    await db.query(
      `UPDATE ride_destinations SET last_error = $2
        WHERE destination = $1 AND state = 'pending' AND last_error IS DISTINCT FROM $2`,
      [STRAVA_DESTINATION, blockReason],
    );
  }

  async function runOnce(): Promise<UploadSummary> {
    const summary: UploadSummary = { claimed: 0, done: 0, retry: 0, failed: 0 };
    for (let handled = 0; handled < perPass; handled++) {
      if (at() < blockedUntil) {
        await stampBlock();
        break;
      }
      if (at() < pausedUntil) break;
      const row = await claim();
      if (row === undefined) break;
      summary.claimed++;
      try {
        summary[await attempt(row)]++;
      } catch (error) {
        // The database failed while recording an outcome: the row's lease runs out and it is tried
        // again; a ride Strava already took is found again in strava_uploads, or answered as a duplicate.
        log.error(
          { err: error, rideId: row.ride_id },
          "could not record the outcome of a Strava upload",
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
          let summary: UploadSummary;
          do {
            summary = await runOnce();
          } while (summary.claimed >= perPass);
        } catch (error) {
          log.error({ err: error }, "Strava upload pass failed");
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
    async start() {
      if (stopTicking !== null) return;
      unsubscribe = bus.on("ride-stored", () => kick());
      stopTicking = timers.every(pollMs, kick);
      try {
        if (options.backfill) {
          // Only on the owner's say-so: turning Strava on must not post the whole history.
          const queued = await db.query<{ ride_id: string }>(
            `INSERT INTO ride_destinations (ride_id, destination)
               SELECT id, $1 FROM rides WHERE fit IS NOT NULL
               ON CONFLICT DO NOTHING
               RETURNING ride_id`,
            [STRAVA_DESTINATION],
          );
          if (queued.rows.length > 0) {
            log.info(
              { rides: queued.rows.length },
              "STRAVA_BACKFILL: earlier rides queued for Strava",
            );
          }
        }
        // A restart is a chance to try again at once: the owner may have fixed the credentials meanwhile.
        await db.query(
          `UPDATE ride_destinations SET next_attempt_at = $2::timestamptz
            WHERE destination = $1 AND state = 'pending' AND next_attempt_at > $2::timestamptz`,
          [STRAVA_DESTINATION, iso(at())],
        );
      } catch (error) {
        log.error({ err: error }, "could not prepare the Strava upload queue at start");
      }
      kick();
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
