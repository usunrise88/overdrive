import {
  addDaysToIsoDate,
  type DeclaredPlanEventKind,
  isIsoDate,
  type PlanEvent,
  type PlanEventKind,
  type PlanEventStatus,
  rangeDays,
} from "@overdrive/contracts";
import { DEFAULT_PLAN_EVENT_DAY_END_GRACE_MINUTES } from "../config-coach.ts";
import type { EventBus } from "../events.ts";
import { findMissed } from "../insights/alerts.ts";
import type { Logger } from "../log.ts";
import type { NotificationCandidate, NotificationSource } from "../notifications.ts";
import type { PlanHistory } from "../telegram/history.ts";
import type { RideQueries } from "../telegram/rides.ts";
import { dayStartMillis, localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { resolutionText } from "./messages.ts";
import type { PlanEventRow, PlanEventStore } from "./store.ts";

/**
 * Plan events (spec §12.2, ADR 0035): the service **detects and queues**, the coach agent rebuilds the week with the normal planning
 * tools and resolves the event, and the rider is sent one short note. No algorithm here moves a workout.
 *
 * Detected: a planned day that ended with no matching activity (the end-of-day check, by the rule the missed-session alert uses), a
 * planned session the rider moved or deleted in intervals.icu (told by the plan refresh), the easier variant being ridden (the type
 * and {@link PlanEventsService.emit} exist; stage A2b calls it), and the rider's own declaration of illness or travel. Each has a
 * natural key, so finding the same thing again is not a second event.
 */

/** A mistake the caller can understand and fix: a bad `until`, an event that does not exist. */
export class PlanEventError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PlanEventError";
  }
}

/** What the plan refresh found changed in the calendar since the last refresh, outside the service's own tools. */
export interface PlanDiff {
  moved: { id: string; name: string; from: string; to: string }[];
  deleted: { id: string; name: string; date: string }[];
}

export interface EmitInput {
  kind: PlanEventKind;
  /** The rider's local day the event is about. */
  day: string;
  workoutId: string | null;
  detail: Record<string, unknown>;
  /** What makes it the same event when found again; by default the kind, the workout and the day. */
  naturalKey?: string | undefined;
}

export interface PlanEventsService {
  /** Creates an event unless one with the same natural key exists. The easier variant (stage A2b) is `emit({ kind: "variant", … })`. */
  emit(input: EmitInput): Promise<{ event: PlanEvent; created: boolean }>;
  /** The rider declares illness or travel from today to `until` (a date), or with no end given. */
  declare(
    kind: DeclaredPlanEventKind,
    until: string | null,
  ): Promise<{ event: PlanEvent; created: boolean }>;
  list(query: {
    status: PlanEventStatus | "all";
    kind?: PlanEventKind | undefined;
    limit: number;
  }): Promise<{ total: number; events: PlanEvent[] }>;
  /** Resolves an open event; null when there is none with this id, `changed: false` when it was resolved before. */
  resolve(
    id: number,
    resolution: { note: string; dropped: boolean },
  ): Promise<{ event: PlanEvent; changed: boolean } | null>;
  /** The plan refresh tells what it found moved or deleted; each becomes an event (once). Returns how many are new. */
  notePlanChanges(diff: PlanDiff): Promise<number>;
  /** Looks at every local day that is over and has not been looked at, and makes an event of each planned session no ride completed. */
  detectMissed(): Promise<number>;
  /** The resolutions the rider is to be told of, for the Telegram bot. */
  readonly notifications: NotificationSource;
  /** Whether a note to the rider can be sent at all (the Telegram bot and the owner's chat are set up). */
  readonly canTellRider: boolean;
  start(): void;
  stop(): Promise<void>;
  /** Resolves when no check is running. For the tests. */
  idle(): Promise<void>;
}

export interface PlanEventsOptions {
  store: PlanEventStore;
  history: PlanHistory;
  rides: RideQueries;
  bus: EventBus;
  timezone: string;
  now: () => Date;
  log: Logger;
  /** Minutes after local midnight a day is given before it is checked (default 180). */
  dayEndGraceMinutes?: number | undefined;
  timers?: Timers | undefined;
  /** Whether the rider can be sent a message (the bot's token and the owner's chat are set). */
  canTellRider: boolean;
  /** Whether there is a plan source: the end-of-day check runs only then. */
  watchesPlan: boolean;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
/** How often the end-of-day check looks whether a day has ended. */
const CHECK_EVERY_MS = 10 * MINUTE;
/** A server that was down catches up on at most this many days. */
const CATCH_UP_DAYS = 7;
/** A declaration can look at most this far ahead. */
const MAX_UNTIL_DAYS = 365;
/** A resolution is worth telling the rider of for a day after it was made. */
const TELL_WINDOW_MS = 24 * HOUR;

const iso = (date: Date): string => date.toISOString();

export function toPlanEvent(row: PlanEventRow): PlanEvent {
  return {
    id: row.id,
    kind: row.kind,
    day: row.day,
    workoutId: row.workoutId,
    detail: row.detail,
    status: row.status,
    createdAt: iso(row.createdAt),
    resolvedAt: row.resolvedAt === null ? null : iso(row.resolvedAt),
    resolutionNote: row.resolutionNote,
    dropped: row.dropped,
  };
}

export function createPlanEvents(options: PlanEventsOptions): PlanEventsService {
  const { store, history, rides, bus, timezone, now, log } = options;
  const timers = options.timers ?? realTimers;
  const graceMs = (options.dayEndGraceMinutes ?? DEFAULT_PLAN_EVENT_DAY_END_GRACE_MINUTES) * MINUTE;

  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  const unsubscribe: (() => void)[] = [];

  /** Keeps the plan history up to date, so a day's sessions are known when it ends even with the insights and the bot off. */
  function syncHistory(): void {
    void history.sync().catch((error: unknown) => {
      const code = (error as { code?: unknown } | null)?.code;
      log.error(
        { code: typeof code === "string" ? code : undefined },
        "could not bring the plan history up to date",
      );
    });
  }

  async function emit(input: EmitInput): Promise<{ event: PlanEvent; created: boolean }> {
    if (!isIsoDate(input.day)) throw new PlanEventError(`day ${input.day} is not a real date`);
    const naturalKey = input.naturalKey ?? `${input.kind}:${input.workoutId ?? ""}:${input.day}`;
    const { event, created } = await store.insert({
      naturalKey,
      kind: input.kind,
      day: input.day,
      workoutId: input.workoutId,
      detail: input.detail,
      at: now(),
    });
    if (created) {
      // Kinds and ids only: what a plan event says about the rider is not logged.
      log.info({ eventId: event.id, kind: event.kind }, "plan event created");
      bus.emit("plan-event-changed", { eventId: event.id, status: "open" });
    }
    return { event: toPlanEvent(event), created };
  }

  async function declare(kind: DeclaredPlanEventKind, until: string | null) {
    const today = localDate(now(), timezone);
    if (until !== null) {
      if (!isIsoDate(until)) throw new PlanEventError(`until ${until} is not a real date`);
      if (until < today) {
        throw new PlanEventError(`until ${until} is before today, ${today}`);
      }
      if ((rangeDays(today, until) ?? 0) > MAX_UNTIL_DAYS) {
        throw new PlanEventError(`until ${until} is more than a year away`);
      }
    }
    return emit({
      kind,
      day: today,
      workoutId: null,
      detail: { until },
      naturalKey: `${kind}:${today}:${until ?? "open"}`,
    });
  }

  async function notePlanChanges(diff: PlanDiff): Promise<number> {
    let fresh = 0;
    for (const moved of diff.moved) {
      const result = await emit({
        kind: "moved",
        day: moved.from,
        workoutId: moved.id,
        detail: { name: moved.name, from: moved.from, to: moved.to },
        naturalKey: `moved:${moved.id}:${moved.from}:${moved.to}`,
      });
      if (result.created) fresh++;
    }
    for (const gone of diff.deleted) {
      const result = await emit({
        kind: "deleted",
        day: gone.date,
        workoutId: gone.id,
        detail: { name: gone.name },
        naturalKey: `deleted:${gone.id}:${gone.date}`,
      });
      if (result.created) fresh++;
    }
    return fresh;
  }

  /** The planned sessions of a day that ended with no ride, each made an event. */
  async function checkDay(day: string): Promise<number> {
    const planned = (await history.between(day, day)).filter((row) => row.status === "planned");
    if (planned.length === 0) return 0;
    const day0 = dayStartMillis(day, timezone);
    const day1 = dayStartMillis(addDaysToIsoDate(day, 1), timezone);
    const ridden = await rides.between(day0, day1);
    let fresh = 0;
    for (const row of findMissed(planned, ridden, timezone)) {
      const result = await emit({
        kind: "missed",
        day,
        workoutId: row.id,
        detail: {
          name: row.name,
          plannedSeconds: row.plannedSeconds,
          plannedLoad: row.plannedLoad,
        },
        naturalKey: `missed:${row.id}:${day}`,
      });
      if (result.created) fresh++;
    }
    return fresh;
  }

  async function detectMissed(): Promise<number> {
    // The last day that is over, with the grace: at 02:00 on the 19th, with 180 minutes of grace, the 17th is the last to be looked at.
    const latest = addDaysToIsoDate(localDate(now().getTime() - graceMs, timezone), -1);
    const done = await store.checkedThrough();
    // The very first check looks only at the last day: the history before the feature was switched on is not made into events.
    let from = done === null ? latest : addDaysToIsoDate(done, 1);
    const earliest = addDaysToIsoDate(latest, -(CATCH_UP_DAYS - 1));
    if (from < earliest) from = earliest;
    if (from > latest) return 0;
    // The history must say what the plan cache says before it is read.
    await history.sync();
    let fresh = 0;
    for (let day = from; day <= latest; day = addDaysToIsoDate(day, 1)) {
      fresh += await checkDay(day);
    }
    await store.setCheckedThrough(latest);
    return fresh;
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
          await detectMissed();
        } catch (error) {
          const code = (error as { code?: unknown } | null)?.code;
          log.error(
            { code: typeof code === "string" ? code : undefined },
            "the end-of-day check for missed sessions failed; it is tried again",
          );
        }
      } while (again);
    })().finally(() => {
      current = null;
    });
  }

  const notifications: NotificationSource = {
    async candidates(nowMs) {
      const rows = await store.resolvedSince(nowMs - TELL_WINDOW_MS);
      return rows.map((row): NotificationCandidate => {
        const at = (row.resolvedAt ?? row.createdAt).getTime();
        return {
          kind: "plan-event-resolved",
          key: String(row.id),
          dueAt: at,
          expiresAt: at + TELL_WINDOW_MS,
          build: async () => ({ text: resolutionText(row) }),
        };
      });
    },
  };

  return {
    emit,
    declare,
    canTellRider: options.canTellRider,

    async list(query) {
      const { events, total } = await store.list(query);
      return { total, events: events.map(toPlanEvent) };
    },

    async resolve(id, resolution) {
      const result = await store.resolve(id, { ...resolution, at: now() });
      if (result === null) return null;
      if (result.changed) {
        log.info({ eventId: id, kind: result.event.kind }, "plan event resolved");
        bus.emit("plan-event-changed", { eventId: id, status: "resolved" });
      }
      return { event: toPlanEvent(result.event), changed: result.changed };
    },

    notePlanChanges,
    detectMissed,
    notifications,

    start() {
      // Without a plan source there is no plan to miss a session of; declarations and the tools need no loop.
      if (stopTicking !== null || !options.watchesPlan) return;
      unsubscribe.push(bus.on("plan-changed", syncHistory));
      stopTicking = timers.every(CHECK_EVERY_MS, kick);
      syncHistory();
      kick();
    },

    async stop() {
      stopTicking?.();
      stopTicking = null;
      for (const off of unsubscribe.splice(0)) off();
      await current;
    },

    idle: () => current ?? Promise.resolve(),
  };
}
