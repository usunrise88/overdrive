import { addDaysToIsoDate, type ProgressResponse, type RideRecord } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import type { MetricsService } from "../metrics/service.ts";
import { thresholdHeartRate } from "../plan/heart-rate.ts";
import type { ProfileStore } from "../profile/store.ts";
import type { RideStore } from "../ride-store.ts";
import { dayStartMillis, localDate } from "../time.ts";
import type { WellnessService } from "../wellness/service.ts";
import { mondayOf } from "../zoned.ts";
import { computeProgress, type ProgressRide } from "./compute.ts";
import { type ProgressRange, resolveRange } from "./range.ts";
import { rideProgressStats } from "./steady.ts";
import { createProgressStore, type ProgressStore } from "./store.ts";

/**
 * Progress over time (spec §6.3 "Progress over time", ADR 0043): reads what the service has stored (the per-ride metrics, the steady
 * stretches kept in `ride_progress_stats`, the watch's daily figures, the FTP history), hands it to the pure computation of
 * `compute.ts` and answers `GET /v1/progress` and the `get_progress` tool. Every number is computed here, never by a model.
 *
 * A ride's steady stretches are worked out from its record once and kept; a read works out the ones the range needs and are missing
 * (the backfill of the rides stored before this existed), and `start()` does all of them in the background.
 *
 * Health data is private: nothing here logs a value, only counts and ids.
 */

export interface ProgressService {
  /**
   * `GET /v1/progress`: the answer for the local days `from` to `to` (the caller checks that they are real days, that `from` is not
   * after `to` and that the range is at most five years). A `to` after today is today.
   */
  progress(from: string, to: string): Promise<ProgressResponse>;
  /** The range a request asks for, with today as the rider's local day now: the defaults filled in, or what is wrong with it. */
  rangeFor(from: string | undefined, to: string | undefined): ProgressRange;
  /** Works out the steady stretches of every stored ride that has none, in the background. */
  start(): void;
  /** Resolves when no computation started by the service is still running. */
  whenIdle(): Promise<void>;
  readonly store: ProgressStore;
}

export interface ProgressOptions {
  db: Db;
  rides: Pick<RideStore, "getRecord">;
  metrics: Pick<MetricsService, "ensureComputed" | "store">;
  profile: Pick<ProfileStore, "get">;
  wellness: Pick<WellnessService, "dailySeries">;
  bus: EventBus;
  timezone: string;
  now: () => Date;
  log: Logger;
}

const FREE_RIDE = "Free ride";
const PLANNED_RIDE = "Planned workout";
const BATCH = 20;

/** The earlier and the later of two calendar dates (`YYYY-MM-DD` sorts as time does). */
const earlier = (a: string, b: string): string => (a < b ? a : b);
const later = (a: string, b: string): string => (a > b ? a : b);

/** The last day of the calendar month of `date`. */
function monthEnd(date: string): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  const next =
    month === 12 ? `${year + 1}-01-01` : `${year}-${String(month + 1).padStart(2, "0")}-01`;
  return addDaysToIsoDate(next, -1);
}

export function createProgress(options: ProgressOptions): ProgressService {
  const { db, rides, metrics, profile, wellness, bus, timezone, now, log } = options;
  const store = createProgressStore(db);

  const inflight = new Set<Promise<unknown>>();
  function track<T>(promise: Promise<T>): Promise<T> {
    inflight.add(promise);
    promise
      .finally(() => inflight.delete(promise))
      .catch(() => {
        // The caller has the failure.
      });
    return promise;
  }
  async function whenIdle(): Promise<void> {
    while (inflight.size > 0) await Promise.allSettled([...inflight]);
  }

  /** Works out the stats of the stored rides in `[fromMs, untilMs)` (all when null) that have none. One run at a time. */
  let chain: Promise<unknown> = Promise.resolve();
  function ensureStats(fromMs: number | null, untilMs: number | null): Promise<number> {
    const run = async (): Promise<number> => {
      let computed = 0;
      const failed: string[] = [];
      for (;;) {
        const batch = await store.missing(fromMs, untilMs, BATCH, failed);
        if (batch.length === 0) return computed;
        for (const { id, sha, startMs } of batch) {
          try {
            const record: RideRecord | null = await rides.getRecord(id);
            if (record === null) {
              failed.push(id);
              continue;
            }
            await store.save(id, sha, startMs, rideProgressStats(record), now());
            computed++;
          } catch (error) {
            // One ride that cannot be read must not stop the others, nor loop here: it is tried again next time.
            log.error({ err: error, rideId: id }, "ride progress stats could not be computed");
            failed.push(id);
          }
        }
      }
    };
    const next = chain.catch(() => {}).then(run);
    chain = next;
    return track(next);
  }

  bus.on("ride-stored", () => track(ensureStats(null, null).then(() => undefined)));

  const service: ProgressService = {
    store,

    rangeFor(from, to) {
      return resolveRange(from, to, localDate(now(), timezone));
    },

    async progress(from, to) {
      const generated = now();
      const today = localDate(generated, timezone);
      const range = { from, to: later(from, earlier(to, today)) };

      // Whole weeks and whole months: a period is never cut by the range.
      const lastWeekDay = addDaysToIsoDate(mondayOf(range.to), 6);
      const periodsStart = [
        mondayOf(range.from),
        `${range.from.slice(0, 7)}-01`,
      ].sort()[0] as string;
      const periodsEnd = [lastWeekDay, monthEnd(range.to)].sort().reverse()[0] as string;
      const fromMs = dayStartMillis(periodsStart, timezone);
      const untilMs = dayStartMillis(addDaysToIsoDate(periodsEnd, 1), timezone);

      await metrics.ensureComputed();
      await ensureStats(fromMs, untilMs);
      const [rows, current, daily, history] = await Promise.all([
        metrics.store.listBetween(fromMs, untilMs),
        profile.get(),
        wellness.dailySeries(addDaysToIsoDate(periodsStart, -7), periodsEnd),
        metrics.store.ftpHistory(),
      ]);
      const stats = await store.stats(rows.map((row) => row.rideId));

      const progressRides: ProgressRide[] = rows.map((row) => {
        const best = (seconds: number): number | undefined =>
          row.powerCurve.find((point) => point.durationSec === seconds)?.power;
        const d300 = best(300);
        const d1200 = best(1200);
        const d3600 = best(3600);
        return {
          rideId: row.rideId,
          startMs: row.startEpochMillis,
          date: localDate(row.startEpochMillis, timezone),
          name: row.workoutName ?? (row.workoutId === null ? FREE_RIDE : PLANNED_RIDE),
          best: {
            ...(d300 === undefined ? {} : { d300 }),
            ...(d1200 === undefined ? {} : { d1200 }),
            ...(d3600 === undefined ? {} : { d3600 }),
          },
          drift: row.drift,
          stats: stats.get(row.rideId) ?? null,
        };
      });

      // The FTP over time, as the metrics service reads it: with no change recorded, the profile's own FTP since it was set.
      const ftpHistory = history.map((entry) => ({
        date: localDate(entry.changedAt, timezone),
        ftp: entry.ftp,
      }));
      if (ftpHistory.length === 0 && current.ftp !== null) {
        const since = current.updatedAt === null ? generated : new Date(current.updatedAt);
        ftpHistory.push({ date: localDate(since, timezone), ftp: current.ftp });
      }

      return computeProgress({
        generatedAt: generated,
        stale: daily.stale,
        today,
        range,
        rides: progressRides,
        ftpHistory,
        heartRates: {
          threshold: thresholdHeartRate(current),
          maximum: current.maxHeartRate ?? current.estimatedMaxHeartRate ?? null,
        },
        restingHr: daily.restingHr,
        weightKg: daily.weightKg,
      });
    },

    start() {
      void ensureStats(null, null).catch((error) => {
        log.error({ err: error }, "the progress backfill failed");
      });
    },

    whenIdle,
  };
  return service;
}
