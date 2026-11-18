import {
  addDaysToIsoDate,
  type CurvePoint,
  type DetailLap,
  type DetailPoint,
  type FtpEstimate,
  type FtpHistoryEntry,
  type FtpSuggestion,
  type MetricsResponse,
  type MetricsWeek,
  type Profile,
  type RideDetailResponse,
  type RideDrift,
  type RideItem,
  type RideMetricsResponse,
  type RidePlan,
  type RideRecord,
  type RidesResponse,
} from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import type { ProfileStore } from "../profile/store.ts";
import {
  type Compliance,
  type FlatStep,
  flattenWorkout,
  rideCompliance,
  rideSeries,
  rideStatistics,
} from "../ride-stats.ts";
import type { RideStore } from "../ride-store.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import { dayStartMillis, localDate } from "../time.ts";
import { mondayOf } from "../zoned.ts";
import {
  differencePercent,
  differsEnough,
  EFTP_WINDOW_DAYS,
  type EftpResult,
  estimateFtp,
  SUGGESTION_THRESHOLD_PERCENT,
} from "./ftp.ts";
import { fitnessSeries, type LoadEntry, type LoadSourceProvider } from "./load.ts";
import { bestEfforts, type RideCurve } from "./power-curve.ts";
import { computeRideMetrics } from "./ride-metrics.ts";
import { describeWBalance, rideWBalance } from "./ride-wbal.ts";
import {
  createMetricsStore,
  type MetricsStore,
  type RideMetricsRow,
  type SuggestionRow,
} from "./store.ts";

/**
 * The training metrics service (spec §6.3, ADR 0022): it computes what the rides say about load,
 * fitness, the power curve, the FTP and aerobic drift, remembers it per ride in `ride_metrics`, and
 * answers the API and the MCP tools. Every number is computed here, from the stored rides, never by a
 * model; a year of rides is a read of small rows, only a ride's detail reads its whole record.
 */

/** Where the list of rides gets its activities from, besides the rides themselves (spec §6.7). */
export interface ActivitySourceProvider {
  readonly name: string;
  /** Up to `limit` items that started before `beforeMillis` (no bound when null), newest first. */
  list(query: { beforeMillis: number | null; limit: number }): Promise<RideItem[]>;
}

export interface MetricsServiceDeps {
  db: Db;
  rides: RideStore;
  rideWorkouts: RideWorkoutResolver;
  profile: ProfileStore;
  bus: EventBus;
  timezone: string;
  now: () => Date;
  log: Logger;
}

export interface FtpAdvice {
  /** The profile's FTP. */
  current: number | null;
  /** The pending suggestion, if any. */
  suggestion: SuggestionRow | null;
  /** The estimate of the last 42 days, whether or not it is worth suggesting. */
  estimate: EftpResult;
  /** In words: why there is no pending suggestion; null while there is one. */
  note: string | null;
}

export interface RideMetricsOptions {
  wbal?: boolean;
  /** With `wbal`: the balance at every `step` seconds as well. */
  series?: boolean;
  /** Critical power in watts, instead of the profile's FTP. */
  cp?: number;
  /** W′ in kilojoules, instead of the profile's. */
  wPrimeKj?: number;
}

export interface MetricsService {
  /** Registers where load other than rides comes from (stage P3-2's activities). */
  registerLoadSource(provider: LoadSourceProvider): void;
  /** Registers where the list of rides gets activities from. */
  registerActivitySource(provider: ActivitySourceProvider): void;
  /** Works out the metrics of the stored rides that have none (or only an out-of-date set); the number done. Joins a run in progress. */
  ensureComputed(): Promise<number>;
  /** `GET /v1/metrics`: the dates are real, `from` is not after `to` and the range is at most 366 days (the caller checks). */
  metrics(from: string, to: string): Promise<MetricsResponse>;
  /** `GET /v1/rides/{id}/metrics`; null when there is no such ride. */
  rideMetrics(
    rideId: string,
    options?: RideMetricsOptions,
    record?: RideRecord,
  ): Promise<RideMetricsResponse | null>;
  /** `GET /v1/rides`. */
  rideList(query: { beforeMillis: number | null; limit: number }): Promise<RidesResponse>;
  /** `GET /v1/rides/{id}`; null when there is no such ride. */
  rideDetail(rideId: string, options?: { series?: boolean }): Promise<RideDetailResponse | null>;
  /** The FTP suggestion and the estimate behind it, in words for the planning agent. */
  ftpAdvice(): Promise<FtpAdvice>;
  /**
   * `POST /v1/ftp-suggestion/dismiss`: hides a pending suggestion. Returns what is left of it
   * ('dismissed' also when it was already; 'accepted' or 'superseded' when it had left that way), or
   * null when there is no such suggestion.
   */
  dismissSuggestion(id: number): Promise<"dismissed" | "accepted" | "superseded" | null>;
  /** Every suggestion kept, newest first. */
  suggestions(limit?: number): Promise<SuggestionRow[]>;
  /** The store, for the stage that accepts suggestions and for tests. */
  readonly store: MetricsStore;
  /** Resolves when no event listener or computation started by the service is still running. */
  whenIdle(): Promise<void>;
  /** Starts the backfill of the rides stored before the metrics existed, without waiting for it. */
  start(): void;
  /** Waits for what is in flight; the listeners are not removed (the bus lives as long as the service). */
  stop(): Promise<void>;
}

/** A suggestion that moves less than this many watts is the same suggestion (and a dismissed one stays hidden). */
export const SUGGESTION_MOVE_WATTS = 3;

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

const FREE_RIDE = "Free ride";
const PLANNED_RIDE = "Planned workout";
const SERIES_STEP_SECONDS = 10;
/** The W′ balance series of `GET /v1/rides/{id}/metrics?wbal=true&series=true`. */
const WBAL_STEP_SECONDS = 5;
const SERIES_MAX_POINTS = 600;
/** The effort steps of a workout: steady steps at this share of FTP or more. */
const EFFORT_SHARE = 0.9;

function rideNameOf(row: Pick<RideMetricsRow, "workoutId" | "workoutName">): string {
  return row.workoutName ?? (row.workoutId === null ? FREE_RIDE : PLANNED_RIDE);
}

export function createMetricsService(deps: MetricsServiceDeps): MetricsService {
  const { db, rides, rideWorkouts, profile, bus, timezone, now, log } = deps;
  const store = createMetricsStore(db);
  const loadSources: LoadSourceProvider[] = [];
  const activitySources: ActivitySourceProvider[] = [];

  // ---- What is in flight -----------------------------------------------------------------------

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

  // ---- Per-ride metrics ------------------------------------------------------------------------

  async function computeMissing(): Promise<number> {
    let computed = 0;
    const failed: string[] = [];
    for (;;) {
      const batch = await store.missing(20, failed);
      if (batch.length === 0) break;
      for (const { id, sha } of batch) {
        try {
          const record = await rides.getRecord(id);
          if (record === null) {
            failed.push(id);
            continue;
          }
          await store.saveRideMetrics(
            id,
            sha,
            record.ride.startEpochMillis,
            record.ride.workoutId ?? null,
            computeRideMetrics(record),
            now(),
          );
          computed++;
        } catch (error) {
          // One ride that cannot be read must not stop the others, nor loop here: it is tried again next time.
          log.error({ err: error, rideId: id }, "ride metrics could not be computed");
          failed.push(id);
        }
      }
    }
    if (computed > 0) await refreshSuggestion();
    return computed;
  }

  let computing: Promise<number> | null = null;
  function ensureComputed(): Promise<number> {
    if (computing === null) {
      computing = track(computeMissing()).finally(() => {
        computing = null;
      });
    }
    return computing;
  }

  // ---- The FTP suggestion ----------------------------------------------------------------------

  function curveOf(row: RideMetricsRow): RideCurve {
    return {
      rideId: row.rideId,
      date: localDate(row.startEpochMillis, timezone),
      startEpochMillis: row.startEpochMillis,
      curve: row.powerCurve,
    };
  }

  async function windowCurves(today: string): Promise<RideCurve[]> {
    const from = dayStartMillis(addDaysToIsoDate(today, 1 - EFTP_WINDOW_DAYS), timezone);
    const until = dayStartMillis(addDaysToIsoDate(today, 1), timezone);
    return (await store.listBetween(from, until)).map(curveOf);
  }

  async function lastFtpChange(current: Profile): Promise<Date | null> {
    const history = await store.ftpHistory();
    const last = history[history.length - 1];
    if (last !== undefined) return last.changedAt;
    return current.updatedAt === null ? null : new Date(current.updatedAt);
  }

  /**
   * Makes the pending suggestion what the efforts of the last 42 days say, and nothing more: a new
   * one when the estimate differs from the FTP by at least 3% (and from the pending one, and from one
   * the rider dismissed since the FTP last changed, by at least 3 W); the pending one ends when it no
   * longer applies. Never touches the profile.
   */
  async function doRefreshSuggestion(): Promise<void> {
    const current = await profile.get();
    const today = localDate(now(), timezone);
    const live = estimateFtp(await windowCurves(today));
    const pending = await store.pendingSuggestion();
    const endPending = async (): Promise<void> => {
      if (pending !== null) await store.decideSuggestion(pending.id, "superseded", now());
    };
    if (!live.ok || !differsEnough(live.estimate.value, current.ftp)) return endPending();
    const { estimate } = live;
    const dismissed = await store.lastDismissedSuggestion();
    if (dismissed !== null && dismissed.decidedAt !== null) {
      const changed = await lastFtpChange(current);
      const dismissedSince = changed === null || dismissed.decidedAt >= changed;
      if (dismissedSince && Math.abs(estimate.value - dismissed.value) < SUGGESTION_MOVE_WATTS) {
        return endPending();
      }
    }
    if (pending !== null && Math.abs(estimate.value - pending.value) < SUGGESTION_MOVE_WATTS)
      return;
    await store.addSuggestion(
      {
        value: estimate.value,
        basis: estimate.basis,
        confidence: estimate.confidence,
        basedOnRides: estimate.supportingRides,
        windowDays: EFTP_WINDOW_DAYS,
        currentFtp: current.ftp,
        differencePercent: differencePercent(estimate.value, current.ftp),
      },
      now(),
    );
  }

  let refreshChain: Promise<unknown> = Promise.resolve();
  function refreshSuggestion(): Promise<void> {
    const next = refreshChain.catch(() => {}).then(doRefreshSuggestion);
    refreshChain = next;
    return track(next);
  }

  // ---- Events ----------------------------------------------------------------------------------

  bus.on("ride-stored", () =>
    track(
      (async () => {
        await ensureComputed();
        await refreshSuggestion();
      })(),
    ),
  );

  bus.on("profile-changed", ({ fields }) => {
    if (!fields.includes("ftp")) return undefined;
    return track(
      (async () => {
        const current = await profile.get();
        const history = await store.ftpHistory();
        const last = history[history.length - 1];
        const at = current.updatedAt === null ? now() : new Date(current.updatedAt);
        if (last === undefined ? current.ftp !== null : last.ftp !== current.ftp) {
          await store.appendFtpHistory(current.ftp, at);
        }
        // Accepting a suggestion is PATCH /v1/profile with its value.
        const pending = await store.pendingSuggestion();
        if (pending !== null && pending.value === current.ftp) {
          await store.decideSuggestion(pending.id, "accepted", now());
        }
        await refreshSuggestion();
      })(),
    );
  });

  // ---- GET /v1/metrics -------------------------------------------------------------------------

  function toSuggestion(row: SuggestionRow): FtpSuggestion {
    return {
      id: row.id,
      watts: row.value,
      basedOnRides: row.basedOnRides,
      windowDays: row.windowDays,
      createdAt: row.createdAt.toISOString(),
      confidence: row.confidence,
      basis: row.basis,
    };
  }

  /** The FTP over time, from the day the profile first had one; the profile's own value when nothing was recorded. */
  async function historyOf(current: Profile): Promise<FtpHistoryEntry[]> {
    const rows = await store.ftpHistory();
    const entries = rows.map((row) => ({
      date: localDate(row.changedAt, timezone),
      ftp: row.ftp,
    }));
    if (entries.length === 0 && current.ftp !== null) {
      const since = current.updatedAt === null ? now() : new Date(current.updatedAt);
      entries.push({ date: localDate(since, timezone), ftp: current.ftp });
    }
    return entries;
  }

  async function metrics(from: string, to: string): Promise<MetricsResponse> {
    await whenIdle();
    await ensureComputed();
    const generated = now();
    const today = localDate(generated, timezone);
    const untilMillis = dayStartMillis(addDaysToIsoDate(to, 1), timezone);
    const rows = await store.listUntil(untilMillis);
    const dated = rows.map((row) => ({ row, date: localDate(row.startEpochMillis, timezone) }));

    // Load: rides, and whatever else has registered.
    let stale = false;
    const entries: LoadEntry[] = dated.map(({ row, date }) => ({
      date,
      kind: "cycling" as const,
      load: row.load ?? 0,
    }));
    for (const source of loadSources) {
      try {
        entries.push(...(await source.entries(to)));
      } catch (error) {
        stale = true;
        log.error({ err: error, source: source.name }, "a load source failed");
      }
    }
    const series = fitnessSeries(entries, from, to);

    // Weeks (ISO, Monday first): whole weeks from the week of `from` to the week of `to`.
    const weeks = new Map<
      string,
      { ride: number; other: number; rides: number; activities: number }
    >();
    const firstWeek = mondayOf(from);
    for (const entry of entries) {
      if (entry.date > to) continue;
      const weekStart = mondayOf(entry.date);
      if (weekStart < firstWeek) continue;
      const week = weeks.get(weekStart) ?? { ride: 0, other: 0, rides: 0, activities: 0 };
      const load = Number.isFinite(entry.load) && entry.load > 0 ? entry.load : 0;
      if (entry.kind === "cycling") {
        week.ride += load;
        week.rides++;
      } else {
        week.other += load;
        week.activities++;
      }
      weeks.set(weekStart, week);
    }
    const weekly: MetricsWeek[] = [];
    for (let monday = firstWeek; monday <= to; monday = addDaysToIsoDate(monday, 7)) {
      const week = weeks.get(monday) ?? { ride: 0, other: 0, rides: 0, activities: 0 };
      weekly.push({
        weekStart: monday,
        load: round(week.ride + week.other, 1),
        rideLoad: round(week.ride, 1),
        otherLoad: round(week.other, 1),
        rides: week.rides,
        activities: week.activities,
      });
    }

    // Power curve, for the range and for all time.
    const point = (effort: ReturnType<typeof bestEfforts>[number]): CurvePoint => ({
      durationSec: effort.durationSec,
      power: effort.power,
      date: effort.date,
      rideId: effort.rideId,
    });
    const curves = dated.map(({ row }) => curveOf(row));
    const powerCurve = {
      range: bestEfforts(curves.filter((c) => c.date >= from && c.date <= to)).map(point),
      allTime: bestEfforts(curves).map(point),
    };

    // FTP.
    const current = await profile.get();
    const estimateAsOf = (asOf: string): number | null => {
      const since = addDaysToIsoDate(asOf, 1 - EFTP_WINDOW_DAYS);
      const result = estimateFtp(curves.filter((c) => c.date >= since && c.date <= asOf));
      return result.ok ? result.estimate.value : null;
    };
    const estimates: FtpEstimate[] = [];
    for (let monday = firstWeek; monday <= to; monday = addDaysToIsoDate(monday, 7)) {
      const sunday = addDaysToIsoDate(monday, 6);
      const asOf = sunday < to ? sunday : to;
      if (asOf > today || asOf < from) continue;
      const eftp = estimateAsOf(asOf);
      if (eftp !== null) estimates.push({ date: asOf, eftp });
    }
    const history = await historyOf(current);
    const lastChange = history[history.length - 1];
    const inForceAtFrom = [...history].reverse().find((entry) => entry.date < from);
    const pending = await store.pendingSuggestion();

    return {
      from,
      to,
      generatedAt: generated.toISOString(),
      stale,
      firstLoadDate: series.firstLoadDate,
      daily: series.days,
      weekly,
      powerCurve,
      ftp: {
        current: current.ftp,
        setAt: current.ftp === null ? null : (lastChange?.date ?? null),
        suggestion: pending === null ? null : toSuggestion(pending),
        latestEstimate: estimateAsOf(to < today ? to : today),
        history: [
          ...(inForceAtFrom === undefined ? [] : [inForceAtFrom]),
          ...history.filter((entry) => entry.date >= from && entry.date <= to),
        ],
        estimates,
      },
      drift: dated
        .filter(({ date, row }) => date >= from && date <= to && row.drift.available)
        .map(({ row, date }) => {
          const drift = row.drift;
          if (!drift.available) throw new Error("unreachable");
          return {
            date,
            rideId: row.rideId,
            name: rideNameOf(row),
            driftPercent: drift.driftPercent,
            efFirst: drift.efFirst,
            efSecond: drift.efSecond,
          };
        }),
    };
  }

  // ---- GET /v1/rides/{id}/metrics --------------------------------------------------------------

  function driftOf(row: RideMetricsRow): RideDrift {
    const drift = row.drift;
    return drift.available
      ? {
          available: true,
          reason: null,
          driftPercent: drift.driftPercent,
          efFirst: drift.efFirst,
          efSecond: drift.efSecond,
          startSec: drift.startSec,
          endSec: drift.endSec,
        }
      : {
          available: false,
          reason: drift.reason,
          driftPercent: null,
          efFirst: null,
          efSecond: null,
          startSec: null,
          endSec: null,
        };
  }

  async function rideMetrics(
    rideId: string,
    options: RideMetricsOptions = {},
    preloaded?: RideRecord,
  ): Promise<RideMetricsResponse | null> {
    await ensureComputed();
    const row = await store.get(rideId);
    if (row === null) return null;
    const response: RideMetricsResponse = {
      generatedAt: now().toISOString(),
      stale: false,
      rideId,
      startTime: new Date(row.startEpochMillis).toISOString(),
      date: localDate(row.startEpochMillis, timezone),
      ftp: row.ftp,
      durationSec: row.durationSeconds,
      movingSec: row.movingSeconds,
      load: row.load === null ? null : round(row.load, 1),
      powerCurve: row.powerCurve.map((p) => ({ durationSec: p.durationSec, power: p.power })),
      drift: driftOf(row),
    };
    if (options.wbal === true) {
      const record = preloaded ?? (await rides.getRecord(rideId));
      if (record !== null) {
        const choice = rideWBalance(record, await profile.get(), {
          cp: options.cp,
          wPrimeKj: options.wPrimeKj,
        });
        response.wPrimeBalance = describeWBalance(
          choice,
          options.series === true ? WBAL_STEP_SECONDS : undefined,
        );
      }
    }
    return response;
  }

  // ---- GET /v1/rides ---------------------------------------------------------------------------

  function itemOf(row: RideMetricsRow): RideItem {
    return {
      id: row.rideId,
      kind: "ride",
      name: rideNameOf(row),
      start: new Date(row.startEpochMillis).toISOString(),
      date: localDate(row.startEpochMillis, timezone),
      durationSeconds: row.durationSeconds,
      load: row.load === null ? null : round(row.load, 1),
      loadApproximate: false,
      sport: null,
      averagePower: row.averageWatts,
      averageHeartRate: null,
    };
  }

  async function rideList(query: {
    beforeMillis: number | null;
    limit: number;
  }): Promise<RidesResponse> {
    await ensureComputed();
    const wanted = query.limit + 1;
    const items = (await store.listPage(query.beforeMillis, wanted)).map(itemOf);
    let stale = false;
    for (const source of activitySources) {
      try {
        items.push(...(await source.list({ beforeMillis: query.beforeMillis, limit: wanted })));
      } catch (error) {
        stale = true;
        log.error({ err: error, source: source.name }, "an activity source failed");
      }
    }
    items.sort((a, b) => Date.parse(b.start) - Date.parse(a.start));
    const hasMore = items.length > query.limit;
    const page = items.slice(0, query.limit);
    return {
      generatedAt: now().toISOString(),
      stale,
      before: query.beforeMillis === null ? null : new Date(query.beforeMillis).toISOString(),
      limit: query.limit,
      hasMore,
      nextBefore: hasMore ? (page[page.length - 1]?.start ?? null) : null,
      items: page,
    };
  }

  // ---- GET /v1/rides/{id} ----------------------------------------------------------------------

  /** The steps of the workout the ride held its effort in, as the plan summary says: see ADR 0022. */
  function effortOf(
    compliance: Compliance,
    flat: readonly FlatStep[],
  ): Pick<RidePlan, "effortRange" | "effortTarget"> {
    const steady = compliance.steps.filter((s) => s.type === "steady" && s.plannedWatts !== null);
    const fractionOf = (step: number): number => flat[step - 1]?.fromFraction ?? 0;
    const hard = steady.filter((s) => fractionOf(s.step) >= EFFORT_SHARE);
    const highest = Math.max(0, ...steady.map((s) => s.plannedWatts as number));
    const effort = hard.length > 0 ? hard : steady.filter((s) => s.plannedWatts === highest);
    const ridden = effort.filter((s) => s.actualWatts !== null && s.actualSeconds > 0);
    if (ridden.length === 0) return { effortRange: null, effortTarget: null };
    const seconds = ridden.reduce((a, s) => a + s.plannedSeconds, 0);
    const target =
      ridden.reduce((a, s) => a + (s.plannedWatts as number) * s.plannedSeconds, 0) / seconds;
    const actual = ridden.map((s) => s.actualWatts as number);
    return {
      effortRange: {
        minWatts: Math.round(Math.min(...actual)),
        maxWatts: Math.round(Math.max(...actual)),
      },
      effortTarget: round(target, 1),
    };
  }

  async function rideDetail(
    rideId: string,
    options: { series?: boolean } = {},
  ): Promise<RideDetailResponse | null> {
    const record = await rides.getRecord(rideId);
    if (record === null) return null;
    const current = await profile.get();
    const resolved = await rideWorkouts.resolve(record.ride);
    const workout = resolved.workout;
    const flat = workout === null ? [] : flattenWorkout(workout);
    const compliance = workout === null ? null : rideCompliance(record, workout);
    const complied = compliance !== null && !("problem" in compliance) ? compliance : null;
    const ftp = record.ride.ftp;

    const stats = rideStatistics(record, current.zones.upperPercentOfFtp);
    const choice = rideWBalance(record, current);
    const duration = record.samples.length;

    const laps: DetailLap[] = record.laps.map((lap) => {
      const step = lap.stepIndex == null ? undefined : flat[lap.stepIndex];
      return {
        stepIndex: lap.stepIndex ?? null,
        label: step?.label ?? null,
        type: step?.type ?? null,
        startSeconds: lap.startSeconds,
        endSeconds: lap.endSeconds,
        averagePower: lap.averagePower == null ? null : round(lap.averagePower, 1),
        averageHeartRate: lap.averageHeartRate == null ? null : round(lap.averageHeartRate, 1),
        targetPower:
          step?.type === "steady" && step.fromFraction !== null
            ? Math.round(step.fromFraction * ftp)
            : null,
      };
    });

    let points: DetailPoint[] | undefined;
    if (options.series === true) {
      const step = Math.max(SERIES_STEP_SECONDS, Math.ceil(duration / SERIES_MAX_POINTS));
      const series = rideSeries(record.samples, step);
      points = series.power.map((power, index) => {
        const t = Math.min((index + 1) * series.stepSeconds, duration);
        return {
          t,
          power,
          heartRate: series.heartRate[index] ?? null,
          wBal: round((choice.result.balanceJoules[t - 1] as number) / 1000, 2),
        };
      });
    }

    const name =
      workout !== null ? workout.name : record.ride.workoutId == null ? FREE_RIDE : PLANNED_RIDE;
    const start = record.ride.startEpochMillis;
    return {
      generatedAt: now().toISOString(),
      stale: "failed" in resolved && resolved.failed === true,
      id: record.ride.id,
      name,
      startTime: new Date(start).toISOString(),
      endTime:
        record.ride.endEpochMillis == null
          ? null
          : new Date(record.ride.endEpochMillis).toISOString(),
      date: localDate(start, timezone),
      workoutId: record.ride.workoutId ?? null,
      devices: record.ride.devices ?? [],
      ftp,
      cp: choice.cpWatts,
      wPrime: choice.wPrimeKj,
      wPrimeLowest: {
        kilojoules: round(choice.result.minBalanceJoules / 1000, 2),
        atSec: choice.result.minAtSeconds,
      },
      summary: record.summary,
      secondsInZones: stats.power.secondsInZone,
      laps,
      plan:
        workout === null || complied === null
          ? null
          : {
              workout,
              stepsRidden: complied.stepsCompleted,
              stepsPlanned: complied.stepsPlanned,
              ...effortOf(complied, flat),
            },
      ...(points === undefined ? {} : { series: points }),
    };
  }

  // ---- The FTP suggestion for the planning agent and the companion -----------------------------

  async function ftpAdvice(): Promise<FtpAdvice> {
    await whenIdle();
    await ensureComputed();
    const current = await profile.get();
    const today = localDate(now(), timezone);
    const estimate = estimateFtp(await windowCurves(today));
    const suggestion = await store.pendingSuggestion();
    let note: string | null = null;
    if (suggestion === null) {
      if (!estimate.ok) note = `${estimate.reason}`;
      else if (!differsEnough(estimate.estimate.value, current.ftp)) {
        note = `The estimate of the last ${EFTP_WINDOW_DAYS} days is ${estimate.estimate.value} W, within ${SUGGESTION_THRESHOLD_PERCENT}% of the FTP of ${current.ftp} W: nothing to suggest`;
      } else {
        const dismissed = await store.lastDismissedSuggestion();
        note =
          dismissed === null
            ? `The estimate of ${estimate.estimate.value} W has not been turned into a suggestion yet`
            : `The rider dismissed a suggestion of ${dismissed.value} W: nothing new is suggested until the estimate (now ${estimate.estimate.value} W) moves ${SUGGESTION_MOVE_WATTS} W from it`;
      }
    }
    return { current: current.ftp, suggestion, estimate, note };
  }

  return {
    registerLoadSource: (provider) => {
      loadSources.push(provider);
    },
    registerActivitySource: (provider) => {
      activitySources.push(provider);
    },
    ensureComputed,
    metrics,
    rideMetrics,
    rideList,
    rideDetail,
    ftpAdvice,
    async dismissSuggestion(id) {
      const row = await store.suggestion(id);
      if (row === null) return null;
      const after =
        row.status === "pending" ? await store.decideSuggestion(id, "dismissed", now()) : row;
      const status = after?.status ?? row.status;
      return status === "pending" ? null : (status as "dismissed" | "accepted" | "superseded");
    },
    suggestions: (limit = 20) => store.suggestions(limit),
    store,
    whenIdle,
    start() {
      track(ensureComputed().then(() => refreshSuggestion())).catch((error: unknown) =>
        log.error({ err: error }, "the first computation of the training metrics failed"),
      );
    },
    async stop() {
      await whenIdle();
    },
  };
}
