import { addDaysToIsoDate } from "@overdrive/contracts";
import { IntervalsError } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import { localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import type { PullWellnessSource } from "./sources.ts";
import type { WellnessStore } from "./store.ts";

/** A pull covers today and this many days before it. */
export const PULL_DAYS = 90;

export type PullResult = { ok: true; days: number } | { ok: false; error: string };

export interface WellnessPuller {
  /**
   * Reads the source's wellness for the last {@link PULL_DAYS} days and makes the daily figures of that source say the same.
   * Never throws. A pull already running is joined, not repeated. Records how it went (the plan refresher's way).
   */
  refresh(): Promise<PullResult>;
  /** Pulls now and then every `hours`. */
  start(hours: number): void;
  stop(): Promise<void>;
}

export interface WellnessPullerOptions {
  source: PullWellnessSource;
  store: WellnessStore;
  timezone: string;
  now?: () => Date;
  log: Logger;
  timers?: Timers;
  /** Called after a pull has stored the daily figures (the insights look again at what changed). */
  onStored?: () => void;
}

/**
 * Pulls the wellness of a pull source (intervals.icu) every N hours and on demand, the way the plan refresher reads the plan. A
 * failure is recorded (the answers then say `stale`) and the figures keep what they had. What the source says is health data:
 * the log gets a count of days and, on failure, the failure's own words, which never contain a value.
 */
export function createWellnessPuller(options: WellnessPullerOptions): WellnessPuller {
  const { source, store, timezone, log } = options;
  const now = options.now ?? (() => new Date());
  const timers = options.timers ?? realTimers;
  let inFlight: Promise<PullResult> | null = null;
  let cancelTimer: (() => void) | null = null;
  let stopped = true;

  async function run(): Promise<PullResult> {
    const to = localDate(now(), timezone);
    const from = addDaysToIsoDate(to, -(PULL_DAYS - 1));
    let observations: Awaited<ReturnType<PullWellnessSource["read"]>>;
    try {
      observations = await source.read(from, to);
    } catch (error) {
      const reason =
        error instanceof IntervalsError ? error.message : "the wellness could not be read";
      log.warn(
        { source: source.id, reason },
        "wellness pull failed; the figures keep what they had",
      );
      await store.recordAttempt(source.id, now(), reason).catch(() => {});
      return { ok: false, error: reason };
    }
    try {
      await store.replaceDaily(source.id, from, to, observations);
      await store.recordAttempt(source.id, now(), null);
    } catch (error) {
      // The database failed, not the source. The error may quote a row: only its code is kept.
      const code = (error as { code?: unknown } | null)?.code;
      log.error(
        { source: source.id, code: typeof code === "string" ? code : undefined },
        "wellness pull could not be stored",
      );
      return { ok: false, error: "the wellness could not be stored" };
    }
    log.info({ source: source.id, days: observations.length }, "wellness pulled");
    options.onStored?.();
    return { ok: true, days: observations.length };
  }

  function refresh(): Promise<PullResult> {
    inFlight ??= run()
      .catch((): PullResult => {
        log.error({ source: source.id }, "wellness pull failed unexpectedly");
        return { ok: false, error: "the wellness pull failed unexpectedly" };
      })
      .finally(() => {
        inFlight = null;
      });
    return inFlight;
  }

  function schedule(hours: number): void {
    if (stopped) return;
    cancelTimer = timers.after(hours * 3_600_000, () => {
      void refresh().finally(() => schedule(hours));
    });
  }

  return {
    refresh,
    start(hours) {
      if (!stopped) return;
      stopped = false;
      void refresh().finally(() => schedule(hours));
    },
    async stop() {
      stopped = true;
      cancelTimer?.();
      cancelTimer = null;
      await inFlight;
    },
  };
}
