import type { RecoveryResponse, WellnessDay } from "@overdrive/contracts";
import { addDaysToIsoDate } from "@overdrive/contracts";
import type { MetricsService } from "../metrics/service.ts";
import type { PlanStore } from "../plan/store.ts";
import type { ProfileStore } from "../profile/store.ts";
import { rideCompliance, stallGuardSummary } from "../ride-stats.ts";
import type { RideStore } from "../ride-store.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import type { PlanHistory } from "../telegram/history.ts";
import { pairRides, type RideQueries } from "../telegram/rides.ts";
import { dayStartMillis, localDate } from "../time.ts";
import type { WellnessService } from "../wellness/service.ts";
import { formatClock, localParts } from "../zoned.ts";
import { type AlertSettings, type DriftEvaluation, evaluateDrift } from "./alerts.ts";
import {
  EFFORT_DURATIONS,
  type FormInput,
  type MorningInput,
  type PlannedInput,
  type PriorBest,
  type RideInput,
  type WeekInput,
} from "./facts.ts";
import { plannedStats } from "./planned.ts";

/**
 * Reads what the facts builders need from the services (spec §6.5, ADR 0025). Nothing here calculates a number a model might
 * quote that the services have not already computed: loads, fitness, recovery states and compliance come from the metrics,
 * wellness and ride-statistics code; this only fetches and arranges them as the pure builders' inputs.
 */

export interface GatherDeps {
  timezone: string;
  now: () => Date;
  metrics: MetricsService;
  wellness: WellnessService;
  plan: PlanStore;
  history: PlanHistory;
  rideQueries: RideQueries;
  rides: RideStore;
  rideWorkouts: RideWorkoutResolver;
  profile: ProfileStore;
}

export interface Gatherer {
  morning(date: string): Promise<MorningInput>;
  /** null when there is no such ride. */
  ride(rideId: string): Promise<RideInput | null>;
  week(monday: string, to: string): Promise<WeekInput>;
  /** This morning's wellness day and recovery, for the deviation alerts. */
  wellnessDay(date: string): Promise<WellnessDay | null>;
  recovery(): Promise<RecoveryResponse | null>;
  plannedOn(date: string): Promise<PlannedInput[]>;
  ftp(): Promise<number | null>;
  drift(today: string, settings: AlertSettings): Promise<DriftEvaluation>;
  /** The sessions and the planned load of the 7 days after `date`. */
  nextWeek(date: string): Promise<{ sessions: number; plannedLoad: number | null }>;
}

const WEEK_DAYS = 7;

export function createGatherer(deps: GatherDeps): Gatherer {
  const { timezone, metrics, wellness, plan, history, rideQueries, rides, rideWorkouts, profile } =
    deps;

  const startOf = (date: string): number => dayStartMillis(date, timezone);

  async function ridesBetween(from: string, to: string) {
    return rideQueries.between(startOf(from), startOf(addDaysToIsoDate(to, 1)));
  }

  async function ftp(): Promise<number | null> {
    return (await profile.get()).ftp;
  }

  async function plannedOn(date: string): Promise<PlannedInput[]> {
    const entries = await plan.entries({ from: date, to: date });
    if (entries.length === 0) return [];
    const ridden = await ridesBetween(date, date);
    const paired = pairRides(entries, ridden, timezone);
    return entries.map((entry) => ({
      name: entry.name,
      workout: entry.workout,
      done: paired.has(entry.id),
    }));
  }

  /** Fitness, fatigue and form on `date`, and fitness a week before. */
  async function formOn(date: string): Promise<FormInput | null> {
    const response = await metrics.metrics(addDaysToIsoDate(date, -WEEK_DAYS), date);
    if (response.firstLoadDate === null) return null;
    const day = response.daily.find((d) => d.date === date);
    if (day === undefined) return null;
    const before = response.daily.find((d) => d.date === addDaysToIsoDate(date, -WEEK_DAYS));
    return { date, ctl: day.ctl, atl: day.atl, tsb: day.tsb, ctlWeekAgo: before?.ctl ?? null };
  }

  async function nextWeek(date: string): Promise<{ sessions: number; plannedLoad: number | null }> {
    const entries = await plan.entries({
      from: addDaysToIsoDate(date, 1),
      to: addDaysToIsoDate(date, WEEK_DAYS),
    });
    const withLoad = entries.flatMap((entry) =>
      entry.workout === null ? [] : [plannedStats(entry.workout).load],
    );
    return {
      sessions: entries.length,
      plannedLoad: withLoad.length === 0 ? null : withLoad.reduce((a, b) => a + b, 0),
    };
  }

  async function recovery(): Promise<RecoveryResponse | null> {
    try {
      return await wellness.recovery();
    } catch {
      return null;
    }
  }

  return {
    ftp,
    plannedOn,
    nextWeek,
    recovery,

    async wellnessDay(date) {
      const response = await wellness.wellness(date, date);
      return response.days[0] ?? null;
    },

    async morning(date) {
      const [recoveryNow, planned, ftpNow, form, last] = await Promise.all([
        recovery(),
        plannedOn(date),
        ftp(),
        formOn(date),
        metrics.rideList({ beforeMillis: startOf(date), limit: 10 }),
      ]);
      const ride = last.items.find((item) => item.kind === "ride");
      return {
        date,
        recovery: recoveryNow,
        planned,
        ftp: ftpNow,
        form,
        lastRide:
          ride === undefined
            ? null
            : {
                name: ride.name,
                date: ride.date,
                durationSeconds: ride.durationSeconds,
                load: ride.load,
                averagePower: ride.averagePower,
              },
      };
    },

    async ride(rideId) {
      await metrics.whenIdle();
      await metrics.ensureComputed();
      const record = await rides.getRecord(rideId);
      if (record === null) return null;
      const [detail, rideMetrics, resolved] = await Promise.all([
        metrics.rideDetail(rideId),
        metrics.rideMetrics(rideId),
        rideWorkouts.resolve(record.ride),
      ]);
      if (detail === null) return null;
      const start = record.ride.startEpochMillis;

      // The best power at each standard duration in the rides before this one (all time).
      const before = await metrics.store.listUntil(start);
      const priorBests: PriorBest[] = EFFORT_DURATIONS.map((durationSec) => {
        let power: number | null = null;
        let date: string | null = null;
        let count = 0;
        for (const row of before) {
          const point = row.powerCurve.find((p) => p.durationSec === durationSec);
          if (point === undefined) continue;
          count++;
          if (power === null || point.power > power) {
            power = point.power;
            date = localDate(row.startEpochMillis, timezone);
          }
        }
        return { durationSec, power, date, rides: count };
      });

      const workout = resolved.workout;
      const summary = record.summary;
      const clock = localParts(start, timezone);
      return {
        rideId,
        name: detail.name,
        date: detail.date,
        startClock: formatClock(clock),
        durationSeconds: summary.durationSeconds,
        movingSeconds: summary.movingSeconds,
        averagePower: summary.averagePower ?? null,
        normalizedPower: summary.normalizedPower ?? null,
        intensityFactor: summary.intensityFactor ?? null,
        load: rideMetrics?.load ?? summary.trainingStressScore ?? null,
        averageHeartRate: summary.averageHeartRate ?? null,
        maxHeartRate: summary.maxHeartRate ?? null,
        workKilojoules: summary.workKilojoules,
        ftp: record.ride.ftp,
        plan:
          workout === null
            ? null
            : {
                workout,
                compliance: rideCompliance(record, workout),
                ...(record.ride.variantRidden === true ? { easierVersionRidden: true } : {}),
              },
        curve: rideMetrics?.powerCurve ?? [],
        priorBests,
        drift: rideMetrics?.drift ?? null,
        wPrime: {
          capacityKj: detail.wPrime,
          lowestKj: detail.wPrimeLowest.kilojoules,
          atSec: detail.wPrimeLowest.atSec,
        },
        form: await formOn(detail.date),
        stallGuard: stallGuardSummary(record, workout),
      };
    },

    async week(monday, to) {
      await history.sync();
      const today = localDate(deps.now(), timezone);
      const span = Math.round(
        (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${monday}T00:00:00Z`)) / 86_400_000,
      );
      const previousMonday = addDaysToIsoDate(monday, -7);
      const [rows, weekRides, response, days, wellnessResponse, upcoming] = await Promise.all([
        history.between(monday, to),
        ridesBetween(monday, to),
        metrics.metrics(addDaysToIsoDate(monday, -8), to),
        wellness.recoveryDays(monday, to),
        wellness.wellness(previousMonday, to),
        nextWeek(to),
      ]);
      const planned = rows.filter((row) => row.status === "planned");
      const paired = pairRides(planned, weekRides, timezone);
      const missed = planned
        .filter((row) => !paired.has(row.id) && row.date < today)
        .map((row) => ({ name: row.name, date: row.date }));

      const loadOn = (date: string, key: "load" | "loadCycling" | "loadOther"): number =>
        response.daily.find((d) => d.date === date)?.[key] ?? 0;
      const sum = (
        from: string,
        length: number,
        key: "load" | "loadCycling" | "loadOther",
      ): number => {
        let total = 0;
        for (let i = 0; i <= length; i++) total += loadOn(addDaysToIsoDate(from, i), key);
        return total;
      };
      const previousLoad = sum(previousMonday, span, "load");
      const plannedLoads = planned.map((row) => row.plannedLoad);
      const plannedLoad =
        planned.length === 0 || plannedLoads.some((l) => l === null)
          ? null
          : plannedLoads.reduce<number>((a, b) => a + (b ?? 0), 0);

      const endDay = response.daily.find((d) => d.date === to);
      const startDay = response.daily.find((d) => d.date === addDaysToIsoDate(monday, -1));
      const form: FormInput | null =
        response.firstLoadDate === null || endDay === undefined
          ? null
          : {
              date: to,
              ctl: endDay.ctl,
              atl: endDay.atl,
              tsb: endDay.tsb,
              ctlWeekAgo: startDay?.ctl ?? null,
            };

      const recoveryDays = { ready: 0, caution: 0, rest: 0, noVerdict: 0 };
      for (const { date, outcome } of days) {
        const verdict =
          outcome.date === date && ["ready", "caution", "rest"].includes(outcome.state);
        if (!verdict) recoveryDays.noVerdict++;
        else recoveryDays[outcome.state as "ready" | "caution" | "rest"]++;
      }

      // New bests of the week: a best at a standard duration above everything before the week.
      const curves = await metrics.store.listUntil(startOf(addDaysToIsoDate(to, 1)));
      const newBests: WeekInput["newBests"] = [];
      for (const durationSec of [...EFFORT_DURATIONS].reverse()) {
        let previous: number | null = null;
        let best: { power: number; date: string } | null = null;
        for (const row of curves) {
          const point = row.powerCurve.find((p) => p.durationSec === durationSec);
          if (point === undefined) continue;
          const date = localDate(row.startEpochMillis, timezone);
          if (date < monday)
            previous = previous === null ? point.power : Math.max(previous, point.power);
          else if (best === null || point.power > best.power) best = { power: point.power, date };
        }
        if (previous !== null && best !== null && best.power > previous) {
          newBests.push({ durationSec, power: best.power, previous, date: best.date });
        }
      }

      return {
        from: monday,
        to,
        plannedSessions: planned.length,
        doneSessions: paired.size,
        skippedSessions: rows.length - planned.length,
        missed,
        rides: weekRides.length,
        movingSeconds: weekRides.reduce((a, r) => a + r.movingSeconds, 0),
        load: sum(monday, span, "load"),
        rideLoad: sum(monday, span, "loadCycling"),
        otherLoad: sum(monday, span, "loadOther"),
        previousLoad: previousLoad > 0 ? previousLoad : null,
        plannedLoad,
        form,
        ctlAtStart: startDay?.ctl ?? null,
        recoveryDays,
        wellness: weeklyWellness(wellnessResponse.days, monday, to),
        newBests: newBests.slice(0, 3),
        nextWeek: upcoming,
      };
    },

    async drift(today, settings) {
      await history.sync();
      const from = addDaysToIsoDate(today, -settings.windowDays);
      const to = addDaysToIsoDate(today, -1);
      const [rows, ridden] = await Promise.all([history.between(from, to), ridesBetween(from, to)]);
      return evaluateDrift(today, rows, ridden, timezone, settings);
    },
  };
}

// ---- Wellness trends of the week ------------------------------------------------------------------

const TRENDS = [
  { key: "restingHr", name: "resting heart rate", unit: "bpm", scale: 1 },
  { key: "sleepSeconds", name: "sleep", unit: "h", scale: 1 / 3600 },
  { key: "respiratoryRate", name: "respiratory rate", unit: "/min", scale: 1 },
  { key: "sleepingHr", name: "heart rate in sleep", unit: "bpm", scale: 1 },
  { key: "spo2", name: "oxygen saturation", unit: "%", scale: 1 },
  { key: "hrvRmssd", name: "heart rate variability", unit: "ms", scale: 1 },
] as const;

/** The shortest run of readings in a week that makes an average worth quoting. */
const MIN_READINGS = 3;

function weeklyWellness(
  days: readonly WellnessDay[],
  monday: string,
  to: string,
): WeekInput["wellness"] {
  const previousMonday = addDaysToIsoDate(monday, -7);
  const out: WeekInput["wellness"] = [];
  for (const trend of TRENDS) {
    const thisWeek = days.filter((d) => d.date >= monday && d.date <= to);
    const lastWeek = days.filter((d) => d.date >= previousMonday && d.date < monday);
    const values = (list: readonly WellnessDay[]): number[] =>
      list.flatMap((d) => {
        const v = d[trend.key].value;
        return v === null ? [] : [v * trend.scale];
      });
    const now = values(thisWeek);
    if (now.length < MIN_READINGS) continue;
    const before = values(lastWeek);
    const outside = thisWeek.filter((d) => {
      const dev = d[trend.key].deviation;
      return dev === "above" || dev === "below";
    });
    const last = outside[outside.length - 1];
    const side = last === undefined ? null : (last[trend.key].deviation as "above" | "below");
    out.push({
      metric: trend.key,
      name: trend.name,
      weekAverage: now.reduce((a, b) => a + b, 0) / now.length,
      previousWeekAverage:
        before.length < MIN_READINGS ? null : before.reduce((a, b) => a + b, 0) / before.length,
      unit: trend.unit,
      daysOutsideUsualRange: outside.length,
      daysWithReading: now.length,
      position: outside.length >= 2 ? side : null,
    });
  }
  return out;
}
