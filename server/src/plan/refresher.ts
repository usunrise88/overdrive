import { addDaysToIsoDate } from "@overdrive/contracts";
import type { IntervalsClient } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import { PLAN_SOURCE, readPlanEvent } from "./entries.ts";
import type { MirrorChange, PlanChange, PlanEntry, PlanStore } from "./store.ts";

export { PLAN_SOURCE };

/** Today and this many days after it are kept in the cache (spec §5.1). */
export const PLAN_HORIZON_DAYS = 14;

export type RefreshResult =
  | {
      ok: true;
      change: PlanChange;
      /** Workouts that were read but cannot be played, with the reason. */
      unsupported: { id: string; name: string; date: string; reason: string }[];
    }
  | { ok: false; error: string };

export interface PlanRefresher {
  /**
   * Reads the plan from intervals.icu and makes the cache say the same: upserts, removes what has
   * vanished, records how it went. Never throws. A refresh already running is joined, not repeated.
   */
  refresh(): Promise<RefreshResult>;
  /** Refreshes now and then every `minutes`. */
  start(minutes: number): void;
  /** Stops the loop and waits for a refresh in flight. */
  stop(): Promise<void>;
}

export interface PlanRefresherOptions {
  client: Pick<IntervalsClient, "listEvents">;
  store: PlanStore;
  timezone: string;
  now?: () => Date;
  log: Logger;
  /** For the tests: ticks by hand. */
  timers?: Timers;
  /**
   * Told what a refresh found moved or deleted in the calendar behind the service's back (the rider's own doing: plan events,
   * ADR 0035). Never called when there is nothing; a failure is logged and does not fail the refresh.
   */
  onCalendarChanges?: (change: Pick<MirrorChange, "moved" | "deleted">) => Promise<unknown>;
}

export function createPlanRefresher(options: PlanRefresherOptions): PlanRefresher {
  const { client, store, timezone, log } = options;
  const now = options.now ?? (() => new Date());
  const timers = options.timers ?? realTimers;
  let inFlight: Promise<RefreshResult> | null = null;
  let cancelTimer: (() => void) | null = null;
  let stopped = true;
  // What was last said about each workout that cannot be played, so a refresh every few minutes
  // does not repeat it.
  const reported = new Map<string, string>();

  async function run(): Promise<RefreshResult> {
    const today = localDate(now(), timezone);
    const window = { from: today, to: addDaysToIsoDate(today, PLAN_HORIZON_DAYS) };

    let events: Awaited<ReturnType<IntervalsClient["listEvents"]>>;
    try {
      events = await client.listEvents(window.from, window.to);
    } catch (error) {
      const reason = error instanceof Error ? error.message : "the plan could not be read";
      log.warn({ reason }, "plan refresh failed; the cache keeps serving the last known plan");
      await store.recordFailure(PLAN_SOURCE, now(), reason).catch(() => {});
      return { ok: false, error: reason };
    }

    const entries: PlanEntry[] = [];
    const unsupported: { id: string; name: string; date: string; reason: string }[] = [];
    for (const event of events) {
      const reading = readPlanEvent(event);
      if (reading.kind === "ignored") continue;
      if (
        reading.kind === "no-date" ||
        reading.entry.date < window.from ||
        reading.entry.date > window.to
      ) {
        log.warn(
          { eventId: event.id },
          "plan event with a date outside the window or unreadable was ignored",
        );
        continue;
      }
      const { entry } = reading;
      entries.push(entry);
      if (entry.workout !== null) {
        reported.delete(entry.id);
        continue;
      }
      const reason = entry.unsupportedReason ?? "it cannot be converted";
      unsupported.push({ id: entry.id, name: entry.name, date: entry.date, reason });
      if (reported.get(entry.id) !== reason) {
        reported.set(entry.id, reason);
        log.warn(
          { eventId: event.id, date: entry.date, reason },
          "planned workout skipped: not supported on the tablet",
        );
      }
    }
    for (const id of [...reported.keys()]) {
      if (!entries.some((entry) => entry.id === id)) reported.delete(id);
    }

    try {
      const change = await store.mirror(window, PLAN_SOURCE, entries);
      await store.recordSuccess(PLAN_SOURCE, now());
      if (change.added.length + change.updated.length + change.removed.length > 0) {
        log.info(
          {
            added: change.added.length,
            updated: change.updated.length,
            removed: change.removed.length,
          },
          "plan refreshed",
        );
      }
      if (change.moved.length + change.deleted.length > 0) {
        await options
          .onCalendarChanges?.({ moved: change.moved, deleted: change.deleted })
          .catch((error: unknown) => {
            log.error({ err: error }, "plan events for the calendar changes could not be stored");
          });
      }
      return { ok: true, change, unsupported };
    } catch (error) {
      // The database failed, not intervals.icu: say so, the cache is as it was.
      log.error({ err: error }, "plan refresh could not be stored");
      return { ok: false, error: "the plan could not be stored" };
    }
  }

  function refresh(): Promise<RefreshResult> {
    inFlight ??= run()
      .catch((error: unknown): RefreshResult => {
        log.error({ err: error }, "plan refresh failed unexpectedly");
        return { ok: false, error: "the plan refresh failed unexpectedly" };
      })
      .finally(() => {
        inFlight = null;
      });
    return inFlight;
  }

  function schedule(minutes: number): void {
    if (stopped) return;
    cancelTimer = timers.after(minutes * 60_000, () => {
      void refresh().finally(() => schedule(minutes));
    });
  }

  return {
    refresh,
    start(minutes) {
      if (!stopped) return;
      stopped = false;
      void refresh().finally(() => schedule(minutes));
    },
    async stop() {
      stopped = true;
      cancelTimer?.();
      cancelTimer = null;
      await inFlight;
    },
  };
}
