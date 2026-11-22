import {
  addDaysToIsoDate,
  isoDateToEpochDay,
  PROGRESS_HEART_RATE_MATCH_BPM,
  PROGRESS_MIN_NIGHTS,
  PROGRESS_MIN_POINTS,
  PROGRESS_MIN_WEIGHINGS,
  PROGRESS_POWER_MATCH_PERCENT,
  PROGRESS_REFERENCE_HEART_RATE_PERCENT,
  PROGRESS_REFERENCE_HEART_RATE_ROUND,
  PROGRESS_REFERENCE_POWER_PERCENT,
  PROGRESS_REFERENCE_POWER_ROUND,
  PROGRESS_ROLLING_RIDES,
  PROGRESS_STEADY_MIN_SECONDS,
  type ProgressBestEffort,
  type ProgressDriftRide,
  type ProgressEfficiencyRide,
  type ProgressEfficiencyWeek,
  type ProgressMonth,
  type ProgressResponse,
  type ProgressRestingWeek,
  type ProgressRolling,
  type ProgressWeightWeek,
} from "@overdrive/contracts";
import type { DriftResult } from "../metrics/drift.ts";
import { mondayOf } from "../zoned.ts";
import type { RideProgressStats, SteadyStretch } from "./steady.ts";
import {
  clarityOf,
  type HeadlineCandidate,
  overallBestPower,
  pickHeadline,
  round,
  type TrendPoint,
  type TrendSpec,
  trendOf,
} from "./trend.ts";

/**
 * Everything the Progress screen shows, worked out from what the service has stored (spec §6.3 "Progress over time", ADR 0043). Pure:
 * the rides, the watch's daily figures and the FTP history come in, the answer of `GET /v1/progress` goes out. Which local day a ride
 * or a night belongs to was decided by the caller (`TIMEZONE`), so week and month boundaries here are plain calendar arithmetic and a
 * clock change cannot move one.
 */

/** A stored tablet ride, with what the service has worked out of it. */
export interface ProgressRide {
  rideId: string;
  startMs: number;
  /** The rider's local day (`TIMEZONE`). */
  date: string;
  name: string;
  /** The best 300, 1200 and 3600 s mean-maximal power of the ride (the power curve), absent where the ride is shorter or has no power. */
  best: { d300?: number; d1200?: number; d3600?: number };
  drift: DriftResult;
  /** Null while the ride's steady stretches are not worked out (the service works them out before it asks). */
  stats: RideProgressStats | null;
}

export interface ProgressInputs {
  generatedAt: Date;
  stale: boolean;
  /** The rider's local day now: the current month and week are partial. */
  today: string;
  range: { from: string; to: string };
  /**
   * Rides that started on a day of the periods the range touches (whole weeks and whole months), in any order. The ride measures use
   * the rides whose day is in the range.
   */
  rides: readonly ProgressRide[];
  /** The FTP over time, oldest first (a null FTP is the profile's FTP being cleared). */
  ftpHistory: readonly { date: string; ftp: number | null }[];
  /** The heart rates the references are taken from. */
  heartRates: { threshold: number | null; maximum: number | null };
  /** The watch's nightly resting heart rate and the daily body weight by local day, at least over the weeks and months of the range and the 7 days before. */
  restingHr: ReadonlyMap<string, number>;
  weightKg: ReadonlyMap<string, number>;
}

const ROLLING = PROGRESS_ROLLING_RIDES;
const clip = (text: string): string => (text.length <= 100 ? text : `${text.slice(0, 99)}…`);
const mean = (values: readonly number[]): number =>
  values.reduce((a, b) => a + b, 0) / values.length;

function median(values: readonly number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1
    ? (sorted[middle] as number)
    : ((sorted[middle - 1] as number) + (sorted[middle] as number)) / 2;
}

// ---- Calendar ---------------------------------------------------------------------------------------------------

/** The Mondays of the weeks that overlap `from` to `to`. */
export function weeksOf(from: string, to: string): string[] {
  const weeks: string[] = [];
  for (let monday = mondayOf(from); monday <= to; monday = addDaysToIsoDate(monday, 7)) {
    weeks.push(monday);
  }
  return weeks;
}

/** The calendar months (`YYYY-MM`) that overlap `from` to `to`. */
export function monthsOf(from: string, to: string): string[] {
  const months: string[] = [];
  let year = Number(from.slice(0, 4));
  let month = Number(from.slice(5, 7));
  const last = to.slice(0, 7);
  for (;;) {
    const text = `${year}-${String(month).padStart(2, "0")}`;
    months.push(text);
    if (text >= last) return months;
    if (month === 12) {
      year++;
      month = 1;
    } else {
      month++;
    }
  }
}

const monthNumber = (month: string): number =>
  Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1;
/** The number of a week: consecutive Mondays are consecutive integers. */
const weekNumber = (monday: string): number => ((isoDateToEpochDay(monday) as number) - 4) / 7;

function daysOfWeek(monday: string): string[] {
  return Array.from({ length: 7 }, (_, i) => addDaysToIsoDate(monday, i));
}

/** The FTP in force on `date`: the last entry on or before it (null when that entry cleared it, or there is none). */
export function ftpOn(
  history: readonly { date: string; ftp: number | null }[],
  date: string,
): number | null {
  let found: number | null = null;
  for (const entry of history) {
    if (entry.date > date) break;
    found = entry.ftp;
  }
  return found;
}

// ---- References -------------------------------------------------------------------------------------------------

const roundTo = (value: number, step: number): number => Math.round(value / step) * step;

/**
 * The FTP the reference power is taken from: the one in force at the range's start, else the earliest the history knows (the range
 * began before the profile had an FTP); null without any.
 */
export function ftpAtStart(
  history: readonly { date: string; ftp: number | null }[],
  from: string,
): number | null {
  const inForce = ftpOn(history, from);
  if (inForce !== null) return inForce;
  return history.find((entry) => entry.ftp !== null)?.ftp ?? null;
}

// ---- The measures -----------------------------------------------------------------------------------------------

function weightFor(
  weights: ReadonlyMap<string, number>,
  date: string,
  month: string,
): number | null {
  // The 7-day average at the end of the day, else the month's mean.
  const week: number[] = [];
  for (let back = 0; back < 7; back++) {
    const value = weights.get(addDaysToIsoDate(date, -back));
    if (value !== undefined) week.push(value);
  }
  if (week.length > 0) return mean(week);
  const monthly: number[] = [];
  for (const [day, value] of weights) if (day.slice(0, 7) === month) monthly.push(value);
  return monthly.length === 0 ? null : mean(monthly);
}

function bestPowerBlock(inputs: ProgressInputs): ProgressResponse["bestPower"] {
  const { range, rides, weightKg, today } = inputs;
  const currentMonth = today.slice(0, 7);
  const months: ProgressMonth[] = monthsOf(range.from, range.to).map((month) => {
    const inMonth = rides.filter((ride) => ride.date.slice(0, 7) === month);
    const effort = (key: "d300" | "d1200" | "d3600"): ProgressBestEffort | null => {
      let best: ProgressRide | null = null;
      for (const ride of inMonth) {
        const watts = ride.best[key];
        if (watts === undefined || watts <= 0) continue;
        const held = best?.best[key];
        // The later ride wins a tie.
        if (
          best === null ||
          held === undefined ||
          watts > held ||
          (watts === held && ride.startMs > best.startMs)
        ) {
          best = ride;
        }
      }
      if (best === null) return null;
      const watts = best.best[key] as number;
      const kg = weightFor(weightKg, best.date, month);
      return {
        watts,
        wattsPerKg: kg === null ? null : round(watts / kg, 2),
        weightKg: kg === null ? null : round(kg, 1),
        date: best.date,
        rideId: best.rideId,
        rideName: clip(best.name),
      };
    };
    return {
      month,
      partial: month === currentMonth,
      d300: effort("d300"),
      d1200: effort("d1200"),
      d3600: effort("d3600"),
    };
  });

  const line = (key: "d300" | "d1200" | "d3600", subject: string, minutes: string) => {
    const points: TrendPoint[] = months
      .filter((m) => !m.partial && m[key] !== null)
      .map((m) => ({
        x: monthNumber(m.month),
        y: (m[key] as ProgressBestEffort).watts,
        date: (m[key] as ProgressBestEffort).date,
      }));
    const min = PROGRESS_MIN_POINTS.bestPower;
    const first = points[0];
    // ±2% of the first figure; a nominal 5 W before there is one.
    const steadyBand = first === undefined ? 5 : round(0.02 * first.y, 2);
    const spec: TrendSpec = {
      subject,
      verb: "is",
      unit: "W",
      betterWhen: "higher",
      minPoints: min,
      steadyBand,
      fitter: false,
      heartRate: false,
    };
    return trendOf({
      points,
      spec,
      ends: "points",
      reason: (n) => `${n} of ${min} months have a ${minutes}-minute ride.`,
    });
  };
  const trends = {
    d300: line("d300", "Best 5-minute power", "5"),
    d1200: line("d1200", "Best 20-minute power", "20"),
    d3600: line("d3600", "Best 60-minute power", "60"),
  };
  return {
    months,
    trend: overallBestPower([trends.d300, trends.d1200, trends.d3600], { subject: "Best power" }),
    trends,
  };
}

function efficiencyBlock(inputs: ProgressInputs): ProgressResponse["aerobicEfficiency"] {
  const { range, rides, ftpHistory, heartRates } = inputs;
  const ftp = ftpAtStart(ftpHistory, range.from);
  const referencePower =
    ftp === null
      ? null
      : Math.max(
          PROGRESS_REFERENCE_POWER_ROUND,
          roundTo((PROGRESS_REFERENCE_POWER_PERCENT / 100) * ftp, PROGRESS_REFERENCE_POWER_ROUND),
        );
  const basis =
    heartRates.threshold !== null ? "threshold" : heartRates.maximum !== null ? "maximum" : null;
  const heartRateBase = heartRates.threshold ?? heartRates.maximum;
  const referenceHeartRate =
    heartRateBase === null
      ? null
      : roundTo(
          (PROGRESS_REFERENCE_HEART_RATE_PERCENT / 100) * heartRateBase,
          PROGRESS_REFERENCE_HEART_RATE_ROUND,
        );

  const weeks: ProgressEfficiencyWeek[] = weeksOf(range.from, range.to).map((weekStart) => {
    const end = addDaysToIsoDate(weekStart, 6);
    const stretches: SteadyStretch[] = [];
    for (const ride of rides) {
      if (ride.date < weekStart || ride.date > end || ride.stats === null) continue;
      stretches.push(...ride.stats.stretches);
    }
    const atPower =
      referencePower === null
        ? []
        : stretches.filter(
            (s) =>
              Math.abs(s.meanPower - referencePower) <=
              (PROGRESS_POWER_MATCH_PERCENT / 100) * referencePower,
          );
    const atHeartRate =
      referenceHeartRate === null
        ? []
        : stretches.filter(
            (s) => Math.abs(s.meanHeartRate - referenceHeartRate) <= PROGRESS_HEART_RATE_MATCH_BPM,
          );
    return {
      weekStart,
      heartRateAtPower:
        atPower.length === 0 ? null : round(median(atPower.map((s) => s.meanHeartRate)), 1),
      powerAtHeartRate:
        atHeartRate.length === 0 ? null : round(median(atHeartRate.map((s) => s.meanPower)), 1),
      atPower: {
        stretches: atPower.length,
        seconds: atPower.reduce((sum, s) => sum + s.seconds, 0),
      },
      atHeartRate: {
        stretches: atHeartRate.length,
        seconds: atHeartRate.reduce((sum, s) => sum + s.seconds, 0),
      },
    };
  });

  const min = PROGRESS_MIN_POINTS.aerobicEfficiency;
  const enoughToAdd = (n: number): string =>
    `${n} of ${min} weeks have a figure. Ride steady for ${PROGRESS_STEADY_MIN_SECONDS / 60} minutes or more to add one.`;
  const hrPoints: TrendPoint[] = weeks
    .filter((w) => w.heartRateAtPower !== null)
    .map((w) => ({
      x: weekNumber(w.weekStart),
      y: w.heartRateAtPower as number,
      date: w.weekStart,
    }));
  const powerPoints: TrendPoint[] = weeks
    .filter((w) => w.powerAtHeartRate !== null)
    .map((w) => ({
      x: weekNumber(w.weekStart),
      y: w.powerAtHeartRate as number,
      date: w.weekStart,
    }));
  return {
    ftpAtStart: ftp,
    referencePower,
    referenceHeartRate,
    referenceHeartRateBasis: basis,
    weeks,
    trend: trendOf({
      points: hrPoints,
      ends: "points",
      spec: {
        subject: "Heart rate at the same power",
        verb: "is",
        unit: "bpm",
        betterWhen: "lower",
        minPoints: min,
        steadyBand: 2,
        fitter: true,
        heartRate: true,
      },
      reason: (n) =>
        referencePower === null ? "There is no FTP to set a reference power from." : enoughToAdd(n),
    }),
    powerTrend: trendOf({
      points: powerPoints,
      ends: "points",
      spec: {
        subject: "Power at the same heart rate",
        verb: "is",
        unit: "W",
        betterWhen: "higher",
        minPoints: min,
        steadyBand: 4,
        fitter: true,
        heartRate: true,
      },
      reason: (n) =>
        referenceHeartRate === null
          ? "There is no threshold or maximum heart rate to set a reference heart rate from."
          : enoughToAdd(n),
    }),
  };
}

/** The mean of the last five values, for each ride from the fifth on. */
function rollingOf(
  rides: readonly { date: string; rideId: string; value: number }[],
): ProgressRolling[] {
  const out: ProgressRolling[] = [];
  for (let i = ROLLING - 1; i < rides.length; i++) {
    const ride = rides[i] as { date: string; rideId: string };
    out.push({
      date: ride.date,
      rideId: ride.rideId,
      value: round(mean(rides.slice(i - ROLLING + 1, i + 1).map((r) => r.value)), 3),
    });
  }
  return out;
}

const byStart = (a: ProgressRide, b: ProgressRide): number =>
  a.startMs - b.startMs || a.rideId.localeCompare(b.rideId);

function efficiencyFactorBlock(inputs: ProgressInputs): ProgressResponse["efficiencyFactor"] {
  const { range } = inputs;
  const rides: ProgressEfficiencyRide[] = inputs.rides
    .filter((r) => r.date >= range.from && r.date <= range.to && r.stats?.efficiency != null)
    .sort(byStart)
    .map((r) => {
      const ef = (r.stats as RideProgressStats).efficiency as NonNullable<
        RideProgressStats["efficiency"]
      >;
      return {
        date: r.date,
        rideId: r.rideId,
        name: clip(r.name),
        value: ef.value,
        normalizedPower: ef.normalizedPower,
        averageHeartRate: ef.averageHeartRate,
        seconds: ef.seconds,
      };
    });
  const min = PROGRESS_MIN_POINTS.efficiencyFactor;
  return {
    rides,
    rolling5: rollingOf(rides),
    trend: trendOf({
      points: rides.map((r, i) => ({ x: i, y: r.value, date: r.date })),
      ends: "rides",
      spec: {
        subject: "Efficiency factor",
        verb: "is",
        unit: "W/bpm",
        betterWhen: "higher",
        minPoints: min,
        steadyBand: 0.02,
        fitter: true,
        heartRate: true,
      },
      reason: (n) => `${n} of ${min} steady rides so far.`,
    }),
  };
}

function decouplingBlock(inputs: ProgressInputs): ProgressResponse["decoupling"] {
  const { range } = inputs;
  const rides: ProgressDriftRide[] = inputs.rides
    .filter((r) => r.date >= range.from && r.date <= range.to && r.drift.available)
    .sort(byStart)
    .map((r) => ({
      date: r.date,
      rideId: r.rideId,
      name: clip(r.name),
      driftPercent: (r.drift as { driftPercent: number }).driftPercent,
    }));
  const min = PROGRESS_MIN_POINTS.decoupling;
  return {
    rides,
    rolling5: rollingOf(
      rides.map((r) => ({ date: r.date, rideId: r.rideId, value: r.driftPercent })),
    ),
    trend: trendOf({
      points: rides.map((r, i) => ({ x: i, y: r.driftPercent, date: r.date })),
      ends: "rides",
      spec: {
        subject: "Aerobic drift",
        verb: "is",
        unit: "%",
        betterWhen: "lower",
        minPoints: min,
        steadyBand: 1,
        fitter: false,
        heartRate: true,
      },
      reason: (n) => `${n} of ${min} rides of 40 minutes or more so far.`,
    }),
  };
}

function restingBlock(inputs: ProgressInputs): ProgressResponse["restingHeartRate"] {
  const { range, restingHr } = inputs;
  const weeks: ProgressRestingWeek[] = weeksOf(range.from, range.to).map((weekStart) => {
    const nights = daysOfWeek(weekStart)
      .map((day) => restingHr.get(day))
      .filter((v): v is number => v !== undefined);
    return {
      weekStart,
      mean: nights.length >= PROGRESS_MIN_NIGHTS ? round(mean(nights), 1) : null,
      nights: nights.length,
    };
  });
  const min = PROGRESS_MIN_POINTS.restingHeartRate;
  return {
    weeks,
    trend: trendOf({
      points: weeks
        .filter((w) => w.mean !== null)
        .map((w) => ({ x: weekNumber(w.weekStart), y: w.mean as number, date: w.weekStart })),
      ends: "points",
      spec: {
        subject: "Resting heart rate",
        verb: "is",
        unit: "bpm",
        betterWhen: "lower",
        minPoints: min,
        steadyBand: 1,
        fitter: false,
        heartRate: true,
      },
      reason: (n) => `${n} of ${min} weeks have ${PROGRESS_MIN_NIGHTS} nights with the watch.`,
    }),
  };
}

function weightBlock(inputs: ProgressInputs): ProgressResponse["weight"] {
  const { range, weightKg, ftpHistory, today } = inputs;
  const weeks: ProgressWeightWeek[] = weeksOf(range.from, range.to).map((weekStart) => {
    const weighed = daysOfWeek(weekStart)
      .map((day) => weightKg.get(day))
      .filter((v): v is number => v !== undefined);
    const average = weighed.length >= PROGRESS_MIN_WEIGHINGS ? mean(weighed) : null;
    // The FTP in force at the end of the week (today, for the current week).
    const end = addDaysToIsoDate(weekStart, 6);
    const ftp = ftpOn(ftpHistory, [end, today].sort()[0] as string);
    return {
      weekStart,
      kg: average === null ? null : round(average, 1),
      weighings: weighed.length,
      ftp,
      wattsPerKg: average === null || ftp === null ? null : round(ftp / average, 2),
    };
  });
  const min = PROGRESS_MIN_POINTS.weight;
  const trend = trendOf({
    points: weeks
      .filter((w) => w.wattsPerKg !== null)
      .map((w) => ({ x: weekNumber(w.weekStart), y: w.wattsPerKg as number, date: w.weekStart })),
    ends: "points",
    spec: {
      subject: "Watts per kilogram",
      verb: "are",
      unit: "W/kg",
      betterWhen: "higher",
      minPoints: min,
      steadyBand: 0.05,
      fitter: false,
      heartRate: false,
    },
    reason: (n) => `${n} of ${min} weeks have ${PROGRESS_MIN_WEIGHINGS} weighings and an FTP.`,
  });
  const weighed = weeks.filter((w) => w.kg !== null);
  const first = weighed[0];
  const last = weighed[weighed.length - 1];
  return {
    weeks,
    trend,
    weightChange:
      first === undefined || last === undefined || weighed.length < 2
        ? null
        : {
            points: weighed.length,
            from: first.kg as number,
            latest: last.kg as number,
            difference: round((last.kg as number) - (first.kg as number), 1),
            since: first.weekStart,
          },
  };
}

/** The answer of `GET /v1/progress` for these inputs. */
export function computeProgress(inputs: ProgressInputs): ProgressResponse {
  const bestPower = bestPowerBlock(inputs);
  const aerobicEfficiency = efficiencyBlock(inputs);
  const efficiencyFactor = efficiencyFactorBlock(inputs);
  const decoupling = decouplingBlock(inputs);
  const restingHeartRate = restingBlock(inputs);
  const weight = weightBlock(inputs);

  const bestLines = [bestPower.trends.d300, bestPower.trends.d1200, bestPower.trends.d3600].filter(
    (t) => t.direction !== "unknown",
  );
  const candidates: HeadlineCandidate[] = [
    {
      measure: "bestPower",
      direction: bestPower.trend.direction,
      verdictText: bestPower.trend.verdictText,
      clarity: bestLines.length === 0 ? 0 : mean(bestLines.map(clarityOf)),
    },
    ...(
      [
        ["aerobicEfficiency", aerobicEfficiency.trend],
        ["efficiencyFactor", efficiencyFactor.trend],
        ["decoupling", decoupling.trend],
        ["restingHeartRate", restingHeartRate.trend],
        ["weight", weight.trend],
      ] as const
    ).map(([measure, trend]) => ({
      measure,
      direction: trend.direction,
      verdictText: trend.verdictText,
      clarity: clarityOf(trend),
    })),
  ];
  return {
    generatedAt: inputs.generatedAt.toISOString().replace(/\.\d{3}Z$/, "Z"),
    stale: inputs.stale,
    range: inputs.range,
    headline: pickHeadline(candidates),
    bestPower,
    aerobicEfficiency,
    efficiencyFactor,
    decoupling,
    restingHeartRate,
    weight,
  };
}
