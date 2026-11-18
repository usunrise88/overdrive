import { addDaysToIsoDate } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { type Clock, isoWeekday, mondayOf, zonedInstant } from "../zoned.ts";
import { type InlineButton, TelegramError } from "./client.ts";
import { reminderButtons } from "./commands.ts";
import { type BotContext, loadDays, plannedView, todayOf } from "./context.ts";
import {
  eveningReminderText,
  leadReminderText,
  rideSummaryText,
  type WeekStats,
  type WeekTotals,
  weeklySummaryText,
} from "./messages.ts";
import { pairRides, type RideBrief } from "./rides.ts";

/**
 * The messages the bot sends on its own (spec §5.4): the reminders, the summary after a ride, the weekly
 * summary. A tick (every 30 s, and when a ride is stored or the plan changes) works out from the plan,
 * the rides and the clock what is due *now*, and sends each thing once: a row in `bot_notifications`
 * per (kind, key) is claimed before the send and marked after it, so a restart neither repeats nor loses
 * one. The text is built at the moment of sending, from the plan as it is then.
 *
 * A try is claimed for ten minutes (a lease, like the upload worker's), so a crash between the send and
 * the mark sends the message once more after the lease: at-least-once, never silently lost.
 */

export interface NotifierConfig {
  /** The evening-before reminder's time of day, or null for none. */
  eveningTime: Clock | null;
  /** Minutes before the workout for the second reminder; 0 for none. */
  leadMinutes: number;
  /** 1 (Monday) to 7 (Sunday). */
  weeklyDay: number;
  /** The weekly summary's time of day, or null for none. */
  weeklyTime: Clock | null;
}

export interface NotifierOptions {
  ctx: BotContext;
  db: Db;
  bus: EventBus;
  /** Sends one message to the owner; throws {@link TelegramError} when it cannot. */
  deliver(text: string, buttons?: InlineButton[][]): Promise<void>;
  config: NotifierConfig;
  /** How often the loop looks for what is due (default 30 s). */
  pollMs?: number;
  /** Failed tries after which a message is given up (default 10). */
  maxAttempts?: number;
  timers?: Timers;
}

export interface TickSummary {
  sent: number;
  dropped: number;
  retry: number;
}

export interface Notifier {
  tick(): Promise<TickSummary>;
  kick(): void;
  idle(): Promise<void>;
  start(): void;
  stop(): Promise<void>;
}

interface Message {
  text: string;
  buttons?: InlineButton[][];
}

interface Candidate {
  kind: "reminder-evening" | "reminder-lead" | "ride-summary" | "week-summary";
  key: string;
  /** Epoch milliseconds from which it may be sent, and until which it is still worth sending. */
  dueAt: number;
  expiresAt: number;
  /** The text now, or null when it is no longer true (the workout moved, was ridden, the week was empty). */
  build(): Promise<Message | null>;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const LEASE_MS = 10 * MINUTE;
/** A ride that started longer ago than this is not summarised on a recovery pass (an event still is). */
const RECENT_RIDE_MS = 36 * HOUR;
/** A ride this short is an accident, not a session. */
const MIN_RIDE_SECONDS = 60;
const MIDNIGHT: Clock = { hour: 0, minute: 0 };

/** 30 s, 1 min, 2 min, 4 min, then 5 min between tries of one message. */
function retryDelayMs(attempts: number): number {
  return Math.min(30_000 * 2 ** Math.max(0, attempts - 1), 5 * MINUTE);
}

export function createNotifier(options: NotifierOptions): Notifier {
  const { ctx, db, bus, config, deliver } = options;
  const { log } = ctx;
  const timers = options.timers ?? realTimers;
  const pollMs = options.pollMs ?? 30_000;
  const maxAttempts = options.maxAttempts ?? 10;

  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  const unsubscribe: (() => void)[] = [];
  // The history of the plan must be brought up to date before anything is worked out from it.
  let dirty = true;
  // Rides announced by an event since the start: they are summarised however long ago they began.
  const announced = new Set<string>();

  const iso = (ms: number): string => new Date(ms).toISOString();

  // ---- What is due ----------------------------------------------------------------------------

  async function isDone(planId: string, date: string): Promise<boolean> {
    const days = await loadDays(ctx, date, date);
    return (days.get(date) ?? []).some((entry) => entry.id === planId && entry.ride !== null);
  }

  /** The planned workout as it is now, if it is still on that day. */
  async function stillPlanned(planId: string, date: string) {
    const entries = await ctx.plan.entries({ from: date, to: date });
    return entries.find((entry) => entry.id === planId) ?? null;
  }

  async function reminderCandidates(today: string): Promise<Candidate[]> {
    const found: Candidate[] = [];
    const entries = await ctx.plan.entries({ from: today, to: addDaysToIsoDate(today, 2) });
    for (const entry of entries) {
      const start = zonedInstant(entry.date, ctx.workoutStart, ctx.timezone).getTime();
      const leadDue = config.leadMinutes > 0 ? start - config.leadMinutes * MINUTE : null;
      const key = `${entry.id}@${entry.date}`;

      if (config.eveningTime !== null) {
        const due = zonedInstant(
          addDaysToIsoDate(entry.date, -1),
          config.eveningTime,
          ctx.timezone,
        ).getTime();
        found.push({
          kind: "reminder-evening",
          key,
          dueAt: due,
          // Once the second reminder is due, or the workout starts, "the evening before" is over.
          expiresAt: Math.min(start, leadDue ?? start),
          async build() {
            const view = await stillPlanned(entry.id, entry.date);
            if (view === null) return null;
            const day = todayOf(ctx);
            return {
              text: eveningReminderText(plannedView(view), ctx.workoutStart, day),
              buttons: reminderButtons(entry.id),
            };
          },
        });
      }

      if (leadDue !== null) {
        found.push({
          kind: "reminder-lead",
          key,
          dueAt: leadDue,
          expiresAt: start,
          async build() {
            const view = await stillPlanned(entry.id, entry.date);
            if (view === null) return null;
            if (await isDone(entry.id, entry.date)) return null;
            const minutesLeft = Math.ceil((start - ctx.now().getTime()) / MINUTE);
            return {
              text: leadReminderText(plannedView(view), ctx.workoutStart, minutesLeft),
              buttons: reminderButtons(entry.id),
            };
          },
        });
      }
    }
    return found;
  }

  async function rideCandidates(nowMs: number): Promise<Candidate[]> {
    const rides = new Map<string, RideBrief>();
    for (const ride of await ctx.rides.completeSince(nowMs - RECENT_RIDE_MS))
      rides.set(ride.id, ride);
    for (const id of [...announced]) {
      if (rides.has(id)) continue;
      const ride = await ctx.rides.get(id);
      if (ride === null) announced.delete(id);
      else rides.set(id, ride);
    }
    return [...rides.values()].map(
      (ride): Candidate => ({
        kind: "ride-summary",
        key: ride.id,
        dueAt: 0,
        expiresAt: announced.has(ride.id)
          ? Number.POSITIVE_INFINITY
          : ride.startMs + RECENT_RIDE_MS,
        build: () => buildRideSummary(ride),
      }),
    );
  }

  async function buildRideSummary(ride: RideBrief): Promise<Message | null> {
    if (ride.durationSeconds < MIN_RIDE_SECONDS) return null;
    return { text: rideSummaryText(ride, await plannedFor(ride)) };
  }

  /** The planned workout a ride completed: the one it names, else the only one planned that day. */
  async function plannedFor(
    ride: RideBrief,
  ): Promise<{ name: string; plannedSeconds: number | null } | null> {
    if (ride.workoutId !== null) {
      const named = await ctx.history.get(ride.workoutId);
      if (named !== null) return named;
    }
    const date = localDate(ride.startMs, ctx.timezone);
    const sameDay = (await ctx.history.between(date, date)).filter(
      (row) => row.status === "planned",
    );
    const [only] = sameDay;
    return sameDay.length === 1 && only !== undefined ? only : null;
  }

  function weeklyCandidates(today: string): Candidate[] {
    if (config.weeklyTime === null) return [];
    // The most recent day that is the summary day (today, if it is), counted back from today.
    const back = (isoWeekday(today) - config.weeklyDay + 7) % 7;
    const date = addDaysToIsoDate(today, -back);
    const due = zonedInstant(date, config.weeklyTime, ctx.timezone).getTime();
    return [
      {
        kind: "week-summary",
        key: date,
        dueAt: due,
        // Late is fine for a day: a server that was down at the time still tells the week that just ended.
        expiresAt: due + 24 * HOUR,
        build: async () => {
          const stats = await weekStats(date);
          if (stats.planned + stats.skipped === 0 && stats.now.rides === 0) return null;
          return { text: weeklySummaryText(stats) };
        },
      },
    ];
  }

  async function totals(
    from: string,
    to: string,
  ): Promise<{ totals: WeekTotals; rides: RideBrief[] }> {
    const rides = await ctx.rides.between(
      zonedInstant(from, MIDNIGHT, ctx.timezone).getTime(),
      zonedInstant(addDaysToIsoDate(to, 1), MIDNIGHT, ctx.timezone).getTime(),
    );
    return {
      rides,
      totals: {
        rides: rides.length,
        movingSeconds: rides.reduce((sum, ride) => sum + ride.movingSeconds, 0),
        load: rides.reduce((sum, ride) => sum + (ride.trainingStressScore ?? 0), 0),
      },
    };
  }

  /** The week up to `to` (a Sunday, by default) against the week before up to the same weekday. */
  async function weekStats(to: string): Promise<WeekStats> {
    const from = mondayOf(to);
    const span = isoWeekday(to) - 1;
    const previousFrom = addDaysToIsoDate(from, -7);
    const [thisWeek, lastWeek, plannedRows] = await Promise.all([
      totals(from, to),
      totals(previousFrom, addDaysToIsoDate(previousFrom, span)),
      ctx.history.between(from, to),
    ]);
    const planned = plannedRows.filter((row) => row.status === "planned");
    const paired = pairRides(planned, thisWeek.rides, ctx.timezone);
    const missed = planned
      .filter((row) => !paired.has(row.id))
      .map((row) => ({ name: row.name, date: row.date }));
    return {
      from,
      to,
      planned: planned.length,
      done: paired.size,
      skipped: plannedRows.length - planned.length,
      missed,
      now: thisWeek.totals,
      previous: lastWeek.totals,
    };
  }

  // ---- Sending, once --------------------------------------------------------------------------

  /** Takes the (kind, key) for a try. Null: sent, dropped, waiting out a backoff, or being tried. */
  async function claim(candidate: Candidate): Promise<{ attempts: number } | null> {
    const at = ctx.now().getTime();
    const result = await db.query<{ attempts: number }>(
      `INSERT INTO bot_notifications (kind, key, state, attempts, next_attempt_at, last_attempt_at)
       VALUES ($1, $2, 'pending', 0, $3::timestamptz, $4::timestamptz)
       ON CONFLICT (kind, key) DO UPDATE SET
         next_attempt_at = EXCLUDED.next_attempt_at, last_attempt_at = EXCLUDED.last_attempt_at
        WHERE bot_notifications.state = 'pending'
          AND (bot_notifications.next_attempt_at IS NULL OR bot_notifications.next_attempt_at <= $4::timestamptz)
       RETURNING attempts`,
      [candidate.kind, candidate.key, iso(at + LEASE_MS), iso(at)],
    );
    return result.rows[0] ?? null;
  }

  async function finish(candidate: Candidate, state: "sent" | "dropped"): Promise<void> {
    await db.query(
      `UPDATE bot_notifications
          SET state = $3, next_attempt_at = NULL, last_error = NULL,
              sent_at = CASE WHEN $3 = 'sent' THEN $4::timestamptz ELSE sent_at END
        WHERE kind = $1 AND key = $2`,
      [candidate.kind, candidate.key, state, iso(ctx.now().getTime())],
    );
  }

  async function failed(
    candidate: Candidate,
    attempts: number,
    error: unknown,
  ): Promise<"retry" | "dropped"> {
    const known = error instanceof TelegramError ? error : null;
    const reason = known?.message ?? "the message could not be built";
    if (known === null) {
      log.error({ err: error, kind: candidate.kind }, "a Telegram message could not be built");
    }
    const giveUp = attempts >= maxAttempts;
    const at = ctx.now().getTime();
    await db.query(
      `UPDATE bot_notifications
          SET state = $3, attempts = $4, last_error = $5, next_attempt_at = $6::timestamptz
        WHERE kind = $1 AND key = $2`,
      [
        candidate.kind,
        candidate.key,
        giveUp ? "dropped" : "pending",
        attempts,
        reason.slice(0, 500),
        iso(at + retryDelayMs(attempts)),
      ],
    );
    const level = giveUp ? "error" : "warn";
    log[level](
      { kind: candidate.kind, attempts, reason },
      giveUp ? "a Telegram message was given up" : "a Telegram message failed; will be tried again",
    );
    return giveUp ? "dropped" : "retry";
  }

  async function handle(candidate: Candidate, summary: TickSummary): Promise<"stop" | undefined> {
    const taken = await claim(candidate);
    if (taken === null) return undefined;
    const attempts = taken.attempts + 1;
    let message: Message | null;
    try {
      message = await candidate.build();
    } catch (error) {
      summary[await failed(candidate, attempts, error)]++;
      return undefined;
    }
    if (message === null) {
      await finish(candidate, "dropped");
      summary.dropped++;
      return undefined;
    }
    try {
      await deliver(message.text, message.buttons);
    } catch (error) {
      summary[await failed(candidate, attempts, error)]++;
      // A refused token fails the same way for everything else: leave them for the next tick.
      return error instanceof TelegramError && error.configuration ? "stop" : undefined;
    }
    await finish(candidate, "sent");
    if (candidate.kind === "ride-summary") announced.delete(candidate.key);
    summary.sent++;
    log.info({ kind: candidate.kind, key: candidate.key }, "Telegram message sent");
    return undefined;
  }

  async function tick(): Promise<TickSummary> {
    const summary: TickSummary = { sent: 0, dropped: 0, retry: 0 };
    if (dirty) {
      dirty = false;
      await ctx.history.sync().catch((error: unknown) => {
        dirty = true;
        log.error({ err: error }, "could not bring the plan history up to date");
      });
    }
    const nowMs = ctx.now().getTime();
    const today = todayOf(ctx);
    const candidates = [
      ...(await reminderCandidates(today)),
      ...(await rideCandidates(nowMs)),
      ...weeklyCandidates(today),
    ];
    for (const candidate of candidates) {
      if (nowMs < candidate.dueAt || nowMs >= candidate.expiresAt) continue;
      if ((await handle(candidate, summary)) === "stop") break;
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
          await tick();
        } catch (error) {
          log.error({ err: error }, "the Telegram notifier failed");
        }
      } while (again);
    })().finally(() => {
      current = null;
    });
  }

  return {
    tick,
    kick,
    idle: () => current ?? Promise.resolve(),
    start() {
      if (stopTicking !== null) return;
      dirty = true;
      unsubscribe.push(
        bus.on("ride-stored", (event) => {
          announced.add(event.rideId);
          kick();
        }),
        bus.on("plan-changed", () => {
          dirty = true;
          kick();
        }),
      );
      stopTicking = timers.every(pollMs, kick);
      kick();
    },
    async stop() {
      stopTicking?.();
      stopTicking = null;
      for (const off of unsubscribe.splice(0)) off();
      await current;
    },
  };
}
