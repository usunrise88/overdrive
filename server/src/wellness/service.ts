import type { RideItem } from "@overdrive/contracts";
import {
  type Activity,
  type ActivitySummary,
  addDaysToIsoDate,
  type RecoveryResponse,
  type WellnessResponse,
  type WellnessUpload,
  type WellnessUploadResponse,
} from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { IntervalsClient } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import type { MetricsService } from "../metrics/service.ts";
import type { ProfileStore } from "../profile/store.ts";
import type { RideStore } from "../ride-store.ts";
import { dayStartMillis, localDate } from "../time.ts";
import type { Timers } from "../timers.ts";
import {
  type ActivityLoad,
  type ActivitySource,
  type ActivityStore,
  activityLoad,
  createActivityStore,
  describeActivity,
  limitsInForce,
  type RestingTable,
  summaryOf,
} from "./activities.ts";
import { type ActivityForwarder, createActivityForwarder } from "./forward.ts";
import { createRideHeartRateIndex, type RideHeartRateIndex } from "./heart-rate-index.ts";
import { DEFAULT_WELLNESS_SETTINGS, type WellnessSettings } from "./metrics.ts";
import { createWellnessPuller, type PullResult, type WellnessPuller } from "./puller.ts";
import { evaluateRecovery, type RecoveryOutcome } from "./recovery.ts";
import { buildDays, nightsLogged, STREAK_LOOKBACK_DAYS } from "./series.ts";
import {
  createIntervalsWellnessSource,
  DEFAULT_SOURCE_ORDER,
  type PushWellnessSource,
  parseSourceOrder,
  type SourceOrder,
} from "./sources.ts";
import { createWellnessStore, HEALTH_CONNECT, INTERVALS, type WellnessStore } from "./store.ts";

/**
 * The wellness module (spec §6.2, §6.4, §6.7, ADR 0023): the Health Connect upload, the intervals.icu
 * pull, the daily figures, baselines and recovery, and the watch's other activities. `routes.ts`
 * and the MCP tools are thin readers of it. Health data: nothing here logs a value, only counts and ids.
 */

export interface OtherLoad {
  date: string;
  kind: "other";
  /** Heart-rate load on the scale of the power-based score; sessions that overlap a ride and sessions without a load are not in it. */
  load: number;
}

export interface ActivityListQuery {
  fromMs?: number;
  toMs?: number;
  /** Leave out the sessions that overlap a ride on the tablet. */
  excludeOverlapping?: boolean;
  /** Only the watch's workouts, or only the outdoor rides imported from FIT files (ADR 0026). */
  source?: ActivitySource;
  limit: number;
  offset: number;
}

export interface WellnessService {
  /** Where Health Connect batches come in (`POST /v1/wellness`). */
  readonly upload: PushWellnessSource;
  /** The intervals.icu pull; null without an API key. */
  readonly puller: WellnessPuller | null;
  /** The upload of the watch's activities to intervals.icu; null without an API key. */
  readonly forwarder: ActivityForwarder | null;
  readonly heartRates: RideHeartRateIndex;
  /** The activities table: the watch's workouts and the outdoor rides imported from FIT files (ADR 0026). */
  readonly activities: ActivityStore;
  readonly store: WellnessStore;
  readonly settings: WellnessSettings;
  /** The daily figures from `from` to `to` with the baselines in force each day. */
  wellness(from: string, to: string): Promise<WellnessResponse>;
  /** The state of this morning (or yesterday's while this morning's data has not arrived). */
  recovery(): Promise<RecoveryResponse>;
  /**
   * The recovery outcome of each day from `from` to `to` as that morning would have seen it (the weekly review counts them):
   * `outcome.date` is the morning the state is for, which is an earlier day when that day had no reading.
   */
  recoveryDays(from: string, to: string): Promise<{ date: string; outcome: RecoveryOutcome }[]>;
  /** A watch activity, or null. */
  activity(id: string): Promise<Activity | null>;
  /** Watch activities newest first, as list rows. */
  listActivities(query: ActivityListQuery): Promise<{ total: number; items: ActivitySummary[] }>;
  /**
   * The heart-rate load of the watch's activities by local day: the `other` part of the fatigue and form curves. Only the days
   * that have some.
   */
  otherLoadByDay(from: string, to: string): Promise<OtherLoad[]>;
  /** Pulls intervals.icu now; null without an API key. */
  refresh(): Promise<PullResult> | null;
  start(): void;
  stop(): Promise<void>;
}

export interface WellnessOptions {
  db: Db;
  bus: EventBus;
  profile: ProfileStore;
  rides: RideStore;
  /** null: no key, nothing is pulled or forwarded. */
  intervals: IntervalsClient | null;
  /** The rider's IANA time zone: which local day a night, a reading and a workout belong to. */
  timezone: string;
  log: Logger;
  now?: () => Date;
  timers?: Timers;
  /** `WELLNESS_SOURCE_ORDER`. */
  sourceOrder?: string;
  /** `WELLNESS_PULL_HOURS`. */
  pullHours?: number;
  /** `WELLNESS_BASELINE_DAYS` and `WELLNESS_NIGHTS_NEEDED`. */
  settings?: Partial<WellnessSettings>;
  /** The heart-rate index, when the profile store was already given one (it must be the same). */
  heartRates?: RideHeartRateIndex;
  /**
   * The training metrics (stage P3-1): the watch's load is registered as its `other` load source and its activities as a source
   * of the rides list.
   */
  metrics?: Pick<MetricsService, "registerLoadSource" | "registerActivitySource">;
}

/** What went wrong in the database, without the row it may quote. */
function storageError(error: unknown): Error {
  const code = (error as { code?: unknown } | null)?.code;
  return new Error(`wellness storage failed${typeof code === "string" ? ` (${code})` : ""}`);
}

export function createWellness(options: WellnessOptions): WellnessService {
  const { db, bus, profile, rides, intervals, timezone, log } = options;
  const now = options.now ?? (() => new Date());
  const settings: WellnessSettings = { ...DEFAULT_WELLNESS_SETTINGS, ...options.settings };
  const order: SourceOrder = parseSourceOrder(options.sourceOrder ?? DEFAULT_SOURCE_ORDER);
  const store = createWellnessStore(db, timezone);
  const activities = createActivityStore(db);
  const heartRates = options.heartRates ?? createRideHeartRateIndex(db, rides, log);

  const source = intervals === null ? null : createIntervalsWellnessSource(intervals);
  const puller =
    source === null
      ? null
      : createWellnessPuller({
          source,
          store,
          timezone,
          now,
          log,
          onStored: () => bus.emit("wellness-updated", { source: "intervals" }),
          ...(options.timers === undefined ? {} : { timers: options.timers }),
        });
  const forwarder =
    intervals === null
      ? null
      : createActivityForwarder({
          db,
          client: intervals,
          bus,
          activities,
          timezone,
          now,
          log,
          ...(options.timers === undefined ? {} : { timers: options.timers }),
        });

  /** Is a source we pull from failing? (Nothing configured: nothing can be stale.) */
  async function isStale(): Promise<boolean> {
    if (puller === null) return false;
    const state = await store.syncState(INTERVALS);
    return state.lastAttemptAt === null || state.lastError !== null;
  }

  /** How far back the daily table must reach for a baseline and a streak to be right. */
  const lookbackDays = settings.baselineDays + STREAK_LOOKBACK_DAYS + 8;

  const upload: PushWellnessSource = {
    id: "healthconnect",
    mode: "push",
    async ingest(batch: WellnessUpload, device: string): Promise<WellnessUploadResponse> {
      try {
        const at = now();
        const outcome = await store.ingest(batch.records, device, at.getTime());
        if (outcome.dates.length > 0) {
          await store.deriveDates(outcome.dates);
          bus.emit("wellness-updated", { source: "healthconnect" });
        }

        // Exercise sessions that were stored or changed, and those whose heart-rate records just arrived.
        const sessionIds = new Set(outcome.exerciseIds);
        for (const span of outcome.heartRateSpans) {
          for (const session of await store.exerciseSessionsOverlapping(span.startMs, span.endMs)) {
            sessionIds.add(session.id);
          }
        }
        if (sessionIds.size > 0) {
          const sessions = await store.exerciseSessions([...sessionIds]);
          const rebuilt = await activities.rebuild(sessions, store.heartRateBetween, at);
          for (const activityId of rebuilt.queued) bus.emit("activity-stored", { activityId });
        }
        return { ...outcome.totals, byType: outcome.byType, generatedAt: at.toISOString() };
      } catch (error) {
        throw storageError(error);
      }
    },
  };

  /** Resting heart rate by day, for the heart-rate load of the activities in a range. */
  async function restingTable(throughDate: string): Promise<RestingTable> {
    const from = addDaysToIsoDate(throughDate, -400);
    const table = await store.dailyTable(from, throughDate, order);
    const resting = new Map<string, number>();
    for (const [date, entry] of table.restingHr ?? []) resting.set(date, entry.value);
    return resting;
  }

  const service: WellnessService = {
    upload,
    puller,
    forwarder,
    heartRates,
    activities,
    store,
    settings,

    async wellness(from, to) {
      const table = await store.dailyTable(addDaysToIsoDate(from, -lookbackDays), to, order);
      const { days, usual } = buildDays(table, from, to, settings);
      return {
        from,
        to,
        generatedAt: now().toISOString(),
        stale: await isStale(),
        nightsLogged: nightsLogged(table, to, settings),
        nightsNeeded: settings.nightsNeeded,
        baselineDays: settings.baselineDays,
        usual,
        days,
      };
    },

    async recovery() {
      const today = localDate(now(), timezone);
      const table = await store.dailyTable(addDaysToIsoDate(today, -lookbackDays), today, order);
      const outcome = evaluateRecovery(table, today, settings);
      return {
        ...outcome,
        nightsNeeded: settings.nightsNeeded,
        generatedAt: now().toISOString(),
        stale: await isStale(),
      };
    },

    async recoveryDays(from, to) {
      const table = await store.dailyTable(addDaysToIsoDate(from, -lookbackDays), to, order);
      const days: { date: string; outcome: RecoveryOutcome }[] = [];
      for (let date = from; date <= to; date = addDaysToIsoDate(date, 1)) {
        days.push({ date, outcome: evaluateRecovery(table, date, settings) });
      }
      return days;
    },

    async activity(id) {
      const row = await activities.get(id);
      if (row === null) return null;
      const [current, resting] = await Promise.all([
        profile.get(),
        restingTable(localDate(row.startMs, timezone)),
      ]);
      const load = activityLoad(row, limitsInForce(current), resting, timezone);
      return describeActivity(row, load, timezone, now());
    },

    async listActivities(query) {
      const { total, rows } = await activities.list(query);
      if (rows.length === 0) return { total, items: [] };
      const [current, resting] = await Promise.all([
        profile.get(),
        restingTable(localDate(now(), timezone)),
      ]);
      const limits = limitsInForce(current);
      const items = rows.map((row) =>
        summaryOf(row, activityLoad(row, limits, resting, timezone), timezone),
      );
      return { total, items };
    },

    async otherLoadByDay(from, to) {
      const rows = await activities.inRange(
        dayStartMillis(from, timezone),
        dayStartMillis(addDaysToIsoDate(to, 1), timezone),
      );
      if (rows.length === 0) return [];
      const [current, resting] = await Promise.all([profile.get(), restingTable(to)]);
      const limits = limitsInForce(current);
      const byDay = new Map<string, number>();
      for (const row of rows) {
        const load: ActivityLoad = activityLoad(row, limits, resting, timezone);
        if (load.load === null) continue;
        const date = localDate(row.startMs, timezone);
        byDay.set(date, (byDay.get(date) ?? 0) + load.load);
      }
      return [...byDay.entries()]
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(
          ([date, load]): OtherLoad => ({ date, kind: "other", load: Math.round(load * 10) / 10 }),
        );
    },

    refresh() {
      return puller === null ? null : puller.refresh();
    },

    start() {
      puller?.start(options.pullHours ?? 6);
      void forwarder?.start();
      void heartRates.indexMissing().catch(() => {});
    },

    async stop() {
      await Promise.all([puller?.stop(), forwarder?.stop()]);
    },
  };

  // The metrics service reads the watch's load and activities through these (spec §6.7): the load feeds fatigue and
  // form as the `other` kind, never the power curve, FTP or drift, which read rides only.
  options.metrics?.registerLoadSource({
    name: "watch",
    async entries(untilDate) {
      try {
        return await service.otherLoadByDay("1970-01-01", untilDate);
      } catch (error) {
        throw storageError(error);
      }
    },
  });
  options.metrics?.registerActivitySource({
    name: "watch",
    async list({ beforeMillis, limit }) {
      try {
        const { items } = await service.listActivities({
          ...(beforeMillis === null ? {} : { toMs: beforeMillis }),
          // The same ride seen by the watch is one ride, and the ride is in the list.
          excludeOverlapping: true,
          limit,
          offset: 0,
        });
        return items.map(
          (item): RideItem => ({
            id: item.id,
            kind: "activity",
            name: item.name,
            start: item.start,
            date: item.startDate,
            durationSeconds: item.durationSeconds,
            load: item.load,
            // A heart-rate load is approximate by nature; the rides contract says so of every activity of the list. The
            // detail's loadApproximate is finer: true when an estimate or a coverage under 80% was behind it.
            loadApproximate: true,
            sport: item.type,
            averagePower: null,
            averageHeartRate: item.averageHeartRate,
          }),
        );
      } catch (error) {
        throw storageError(error);
      }
    },
  });

  return service;
}

export { HEALTH_CONNECT };
