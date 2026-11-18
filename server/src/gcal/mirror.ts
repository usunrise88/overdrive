import { addDaysToIsoDate } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import { PLAN_SOURCE } from "../plan/refresher.ts";
import type { PlanStore } from "../plan/store.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { type Clock, zonedInstant } from "../zoned.ts";
import { type CalendarClient, GoogleError } from "./client.ts";
import { type DesiredEvent, desiredEvent, googleEventId } from "./event.ts";

const MINUTE = 60_000;

/** A row taken for a try for this long is not taken again: it covers a crash in the middle of one. */
const LEASE_MS = 10 * MINUTE;

/** How far ahead the mirror looks: the plan cache holds 15 days, a write beyond it is still mirrored. */
const HORIZON_DAYS = 366;

/** Rows of days that passed are removed after this many days; until then they only sit there. */
const KEEP_PAST_DAYS = 7;

/** The wait before the next try after `attempts` tries have failed: 1, 2, 4 … minutes, at most 6 hours. */
export function retryDelayMs(attempts: number): number {
  return Math.min(MINUTE * 2 ** Math.max(0, attempts - 1), 6 * 60 * MINUTE);
}

export interface CalendarMirrorOptions {
  db: Db;
  plan: PlanStore;
  bus: EventBus;
  client: CalendarClient;
  /** The rider's time zone: which day is "today" and what 07:00 means. */
  timezone: string;
  /** The time of day a workout starts (`GCAL_WORKOUT_START`). */
  workoutStart: Clock;
  /** Whether a plan source (intervals.icu) is set up. Without one there is nothing to mirror. */
  planSource: boolean;
  now?: () => Date;
  log: Logger;
  /** How often the loop looks for work that is due (default 60 s). */
  pollMs?: number;
  /** Failed tries after which an event is given up (default 20), unless the cause is the configuration. */
  maxAttempts?: number;
  /** For the tests: ticks by hand. */
  timers?: Timers;
}

export interface PassSummary {
  created: number;
  updated: number;
  deleted: number;
  /** Failed and will be tried again. */
  retry: number;
  /** Given up. */
  failed: number;
  /** Left for later: waiting out a backoff. */
  waiting: number;
}

export interface CalendarMirror {
  /** One pass: makes Google Calendar say what the plan says, as far as it can. Never throws for a call that fails. */
  runOnce(): Promise<PassSummary>;
  /** Looks for work now, if a pass is not already running, and again when one finishes if asked meanwhile. */
  kick(): void;
  /** Resolves when no pass is running. */
  idle(): Promise<void>;
  /** Reconciles at once, then on `plan-changed` and every `pollMs`. Resolves when the first pass has been started. */
  start(): Promise<void>;
  stop(): Promise<void>;
}

interface Row {
  planned_id: string;
  google_event_id: string | null;
  plan_date: string;
  synced_sha: string | null;
  target_sha: string | null;
  state: "pending" | "synced" | "failed";
  attempts: number;
  /** Epoch milliseconds, as text, or null. */
  next_ms: string | null;
}

type Outcome = "created" | "updated" | "deleted" | "retry" | "failed" | "waiting" | "unchanged";

/**
 * One-way push of the planned workouts to a dedicated Google Calendar (spec §5.3, ADR 0019). The
 * plan cache is the only input: every pass compares what it holds (today onwards) with what the
 * `calendar_events` table says Google shows, and creates, updates or deletes the events that differ.
 * That makes the pass idempotent and the `plan-changed` event just a nudge. Edits made in Google
 * Calendar are never read back; the one read is the start-up sweep, which lists our own events (those
 * with the `overdrive` marker) to delete the ones whose workout is gone.
 *
 * Failures are kept in the table, like the upload worker's: `pending` again after 1, 2, 4 … minutes up
 * to 6 hours, `failed` after 20 tries or when Google refuses the content; wrong credentials stop the
 * whole mirror for 6 hours (a restart tries at once). A day that has passed is never touched: the plan
 * cache drops it, the calendar keeps it.
 */
export function createCalendarMirror(options: CalendarMirrorOptions): CalendarMirror {
  const { db, plan, bus, client, timezone, workoutStart, log } = options;
  const now = options.now ?? (() => new Date());
  const pollMs = options.pollMs ?? 60_000;
  const maxAttempts = options.maxAttempts ?? 20;
  const timers = options.timers ?? realTimers;

  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  let unsubscribe: (() => void) | null = null;
  // The start-up sweep has not succeeded yet.
  let swept = false;
  // Wrong credentials: nothing is tried before this instant (epoch ms). A restart clears it.
  let blockedUntil = 0;
  let announcedWaiting = false;

  const iso = (ms: number): string => new Date(ms).toISOString();

  async function loadRows(): Promise<Map<string, Row>> {
    const result = await db.query<Row>(
      `SELECT planned_id, google_event_id, plan_date::text AS plan_date, synced_sha, target_sha, state, attempts,
              floor(extract(epoch FROM next_attempt_at) * 1000)::bigint::text AS next_ms
         FROM calendar_events`,
    );
    return new Map(result.rows.map((row) => [row.planned_id, row]));
  }

  /** Takes the row for a try: nobody else tries it for the lease, and a crash leaves that lease behind. */
  async function lease(plannedId: string, date: string, target: string | null): Promise<void> {
    const at = now().getTime();
    await db.query(
      `INSERT INTO calendar_events (planned_id, plan_date, target_sha, state, next_attempt_at, last_attempt_at)
       VALUES ($1, $2::date, $3, 'pending', $4::timestamptz, $5::timestamptz)
       ON CONFLICT (planned_id) DO UPDATE SET
         plan_date = EXCLUDED.plan_date, target_sha = EXCLUDED.target_sha, state = 'pending',
         next_attempt_at = EXCLUDED.next_attempt_at, last_attempt_at = EXCLUDED.last_attempt_at`,
      [plannedId, date, target, iso(at + LEASE_MS), iso(at)],
    );
  }

  async function recordSynced(
    plannedId: string,
    googleId: string,
    date: string,
    sha: string,
  ): Promise<void> {
    await db.query(
      `UPDATE calendar_events
          SET google_event_id = $2, plan_date = $3::date, synced_sha = $4, target_sha = $4, state = 'synced',
              attempts = 0, next_attempt_at = NULL, last_error = NULL, updated_at = now()
        WHERE planned_id = $1`,
      [plannedId, googleId, date, sha],
    );
  }

  /** Records a failed try and says what happens next; a configuration error also stops the mirror for a while. */
  async function recordFailure(
    plannedId: string,
    attempts: number,
    error: unknown,
  ): Promise<"retry" | "failed"> {
    const known = error instanceof GoogleError ? error : null;
    const reason = known?.message ?? "the calendar sync failed unexpectedly";
    if (known === null) log.error({ err: error, plannedId }, "calendar sync failed unexpectedly");
    const giveUp =
      known?.permanent === true || (known?.configuration !== true && attempts >= maxAttempts);
    const at = now().getTime();
    const delayMs = giveUp
      ? 0
      : retryDelayMs(known?.configuration === true ? maxAttempts : attempts);
    await db.query(
      `UPDATE calendar_events
          SET state = $2, attempts = $3, last_error = $4, next_attempt_at = $5::timestamptz,
              last_attempt_at = $6::timestamptz, updated_at = now()
        WHERE planned_id = $1`,
      [
        plannedId,
        giveUp ? "failed" : "pending",
        attempts,
        reason.slice(0, 500),
        iso(at + delayMs),
        iso(at),
      ],
    );
    if (known?.configuration === true) {
      blockedUntil = at + retryDelayMs(maxAttempts);
      log.error(
        { kind: known.kind, reason },
        "Google Calendar was refused: nothing more is tried until this is fixed (a restart tries again at once)",
      );
    } else if (giveUp) {
      log.error({ plannedId, attempts, kind: known?.kind, reason }, "calendar event given up");
    } else {
      log.warn(
        {
          plannedId,
          attempts,
          kind: known?.kind,
          retryInMinutes: Math.round(delayMs / MINUTE),
          reason,
        },
        "calendar event failed; will be tried again",
      );
    }
    return giveUp ? "failed" : "retry";
  }

  /** Creates or updates the event; returns the Google id and what was done. */
  async function write(
    event: DesiredEvent,
    row: Row | undefined,
  ): Promise<{ id: string; op: "created" | "updated" }> {
    const id = row?.google_event_id ?? googleEventId(event.plannedId);
    if (row?.google_event_id) {
      try {
        await client.patchEvent(id, event.body);
        return { id, op: "updated" };
      } catch (error) {
        const gone =
          error instanceof GoogleError && (error.kind === "not-found" || error.kind === "gone");
        if (!gone) throw error;
        // Deleted by hand in Google Calendar: it comes back below, a mirror says what the plan says.
      }
    }
    try {
      await client.insertEvent(id, event.body);
      return { id, op: "created" };
    } catch (error) {
      if (!(error instanceof GoogleError) || error.kind !== "conflict") throw error;
      // The id exists: an earlier create whose answer was lost, a database restored from a backup, or a deleted
      // event (Google keeps its id). Patching brings it to what we want and confirms it.
      await client.patchEvent(id, event.body);
      return { id, op: "updated" };
    }
  }

  async function push(event: DesiredEvent, row: Row | undefined): Promise<Outcome> {
    if (row !== undefined && row.google_event_id !== null && row.synced_sha === event.sha) {
      if (row.state !== "synced" || row.attempts > 0) {
        await recordSynced(event.plannedId, row.google_event_id, event.date, event.sha);
      }
      return "unchanged";
    }
    const sameTarget = row?.target_sha === event.sha;
    if (row !== undefined && sameTarget) {
      if (row.state === "failed") return "failed";
      if (row.next_ms !== null && Number(row.next_ms) > now().getTime()) return "waiting";
    }
    const attempts = (sameTarget && row !== undefined ? row.attempts : 0) + 1;
    await lease(event.plannedId, event.date, event.sha);
    try {
      const { id, op } = await write(event, row);
      await recordSynced(event.plannedId, id, event.date, event.sha);
      log.info(
        { plannedId: event.plannedId, date: event.date, googleEventId: id },
        op === "created" ? "calendar event created" : "calendar event updated",
      );
      return op;
    } catch (error) {
      return recordFailure(event.plannedId, attempts, error);
    }
  }

  async function removeEvent(row: Row): Promise<Outcome> {
    if (row.google_event_id === null) {
      // Never reached Google: nothing to delete there.
      await db.query("DELETE FROM calendar_events WHERE planned_id = $1", [row.planned_id]);
      return "unchanged";
    }
    const sameTarget = row.target_sha === null;
    if (sameTarget) {
      if (row.state === "failed") return "failed";
      if (row.next_ms !== null && Number(row.next_ms) > now().getTime()) return "waiting";
    }
    const attempts = (sameTarget ? row.attempts : 0) + 1;
    await lease(row.planned_id, row.plan_date, null);
    try {
      await client.deleteEvent(row.google_event_id);
      await db.query("DELETE FROM calendar_events WHERE planned_id = $1", [row.planned_id]);
      log.info({ plannedId: row.planned_id, date: row.plan_date }, "calendar event deleted");
      return "deleted";
    } catch (error) {
      return recordFailure(row.planned_id, attempts, error);
    }
  }

  /**
   * Deletes the events of ours that Google shows for a workout that is no longer planned (the database
   * could have been restored from a backup, or a delete may have been lost). Returns how many it deleted,
   * or null when it could not look.
   */
  async function sweep(desired: Map<string, DesiredEvent>, today: string): Promise<number | null> {
    try {
      const from = zonedInstant(today, { hour: 0, minute: 0 }, timezone);
      const to = zonedInstant(
        addDaysToIsoDate(today, HORIZON_DAYS + 1),
        { hour: 0, minute: 0 },
        timezone,
      );
      let deleted = 0;
      for (const event of await client.listManagedEvents(from, to)) {
        if (event.start !== null && Date.parse(event.start) < from.getTime()) continue;
        const wanted = event.plannedId !== null && desired.has(event.plannedId);
        // A second event for a planned workout (not the one with the id we derive) is a stray as well.
        if (wanted && event.id === googleEventId(event.plannedId ?? "")) continue;
        await client.deleteEvent(event.id);
        deleted++;
        log.info(
          { plannedId: event.plannedId, googleEventId: event.id },
          "stray calendar event deleted",
        );
      }
      swept = true;
      return deleted;
    } catch (error) {
      const known = error instanceof GoogleError ? error : null;
      if (known?.configuration === true) {
        blockedUntil = now().getTime() + retryDelayMs(maxAttempts);
        log.error(
          { kind: known.kind, reason: known.message },
          "Google Calendar was refused: nothing more is tried until this is fixed (a restart tries again at once)",
        );
      } else {
        log.warn(
          { reason: known?.message ?? "unknown" },
          "calendar sweep failed; it will be tried again",
        );
      }
      return null;
    }
  }

  let announced: "no-source" | "no-plan" | null = null;

  async function runOnce(): Promise<PassSummary> {
    const summary: PassSummary = {
      created: 0,
      updated: 0,
      deleted: 0,
      retry: 0,
      failed: 0,
      waiting: 0,
    };
    const count = (outcome: Outcome): void => {
      if (outcome === "created") summary.created++;
      else if (outcome === "updated") summary.updated++;
      else if (outcome === "deleted") summary.deleted++;
      else if (outcome === "retry") summary.retry++;
      else if (outcome === "failed") summary.failed++;
      else if (outcome === "waiting") summary.waiting++;
    };

    if (!options.planSource) {
      if (announced !== "no-source") {
        announced = "no-source";
        log.info(
          {},
          "Google Calendar is set up but there is no plan source (INTERVALS_API_KEY is not set): nothing to push",
        );
      }
      return summary;
    }
    // Until the plan has been read once, an empty cache means "not known yet", not "nothing planned":
    // deleting events on that would be wrong.
    // (The refresher announces what it stored before it records the success, so a cache with entries counts as read.)
    const syncState = await plan.syncState(PLAN_SOURCE);
    const known =
      syncState.lastSuccessAt !== null ||
      (await plan.entries({ from: "2000-01-01", to: "2999-12-31" })).length > 0;
    if (!known) {
      if (announced !== "no-plan") {
        announced = "no-plan";
        log.info({}, "Google Calendar waits for the first read of the plan");
      }
      return summary;
    }
    announced = null;
    if (now().getTime() < blockedUntil) {
      if (!announcedWaiting) {
        announcedWaiting = true;
        log.debug({}, "Google Calendar is waiting out a refusal");
      }
      return summary;
    }
    announcedWaiting = false;

    const today = localDate(now(), timezone);
    const desired = new Map<string, DesiredEvent>();
    for (const planned of await plan.list({
      from: today,
      to: addDaysToIsoDate(today, HORIZON_DAYS),
    })) {
      desired.set(planned.id, desiredEvent(planned, timezone, workoutStart));
    }
    const rows = await loadRows();

    if (!swept) {
      const deleted = await sweep(desired, today);
      if (deleted !== null) summary.deleted += deleted;
    }

    for (const event of desired.values()) {
      if (now().getTime() < blockedUntil) break;
      count(await push(event, rows.get(event.plannedId)));
    }
    for (const row of rows.values()) {
      if (now().getTime() < blockedUntil) break;
      // A day that has passed is history: the plan cache dropped it, the calendar keeps it.
      if (desired.has(row.planned_id) || row.plan_date < today) continue;
      count(await removeEvent(row));
    }
    await db.query("DELETE FROM calendar_events WHERE plan_date < $1::date", [
      addDaysToIsoDate(today, -KEEP_PAST_DAYS),
    ]);
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
          await runOnce();
        } catch (error) {
          log.error({ err: error }, "calendar pass failed");
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
      swept = false;
      blockedUntil = 0;
      unsubscribe = bus.on("plan-changed", () => kick());
      stopTicking = timers.every(pollMs, kick);
      // A restart is a chance to try again at once: the owner may have fixed the credentials meanwhile.
      return db
        .query(
          `UPDATE calendar_events SET next_attempt_at = $1::timestamptz
            WHERE state = 'pending' AND next_attempt_at > $1::timestamptz`,
          [now().toISOString()],
        )
        .then(
          () => {},
          (error: unknown) =>
            log.error({ err: error }, "could not reset the calendar queue at start"),
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
