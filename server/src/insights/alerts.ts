import type { RecoveryFact, RecoveryMetric, WellnessDay } from "@overdrive/contracts";
import { addDaysToIsoDate } from "@overdrive/contracts";
import { pairRides, type RideBrief } from "../telegram/rides.ts";
import { METRIC_SPECS } from "../wellness/metrics.ts";
import type { DriftReason } from "./facts.ts";

/**
 * When an alert is due (spec §6.6, ADR 0025): pure rules over the wellness days and the plan history, with the thresholds in one
 * place. The text of an alert is written elsewhere, from the facts these rules find.
 *
 * Deviation alerts: a scored metric outside its personal baseline, on its adverse side, for `streakDays` mornings in a row
 * (ALERT_STREAK_DAYS, default 3), reported once per streak (the day the run began). Plan drift: in the last 7 days (the days
 * before today) at least `missedSessions` planned sessions with no ride (ALERT_MISSED_SESSIONS, default 2), or the ride load of
 * those days more than `loadAbovePercent` above (default 30) or `loadBelowPercent` below (default 40) the load planned for the
 * sessions planned in them, at most once a week.
 */

export interface AlertSettings {
  /** Mornings in a row outside the usual range that make a deviation alert. */
  streakDays: number;
  /** Planned sessions missed in the window that make a drift alert. */
  missedSessions: number;
  loadAbovePercent: number;
  loadBelowPercent: number;
  /** The days looked at, ending yesterday. */
  windowDays: number;
  /** Planned load under which the load comparison is not made: a week of one short ride is noise. */
  minPlannedLoad: number;
}

export const DEFAULT_ALERT_SETTINGS: AlertSettings = {
  streakDays: 3,
  missedSessions: 2,
  loadAbovePercent: 30,
  loadBelowPercent: 40,
  windowDays: 7,
  minPlannedLoad: 50,
};

// ---- Deviation ------------------------------------------------------------------------------------

export interface DeviationCandidate {
  metric: RecoveryMetric;
  fact: RecoveryFact;
  streak: number;
  /** The first day of the run, `YYYY-MM-DD`. */
  streakStart: string;
  /** `metric@startDate`: what "once per streak" is keyed by. */
  streakKey: string;
}

/** The scored metrics, in the order they are looked at (the table's own priority). */
const SCORED = (Object.values(METRIC_SPECS) as (typeof METRIC_SPECS)[RecoveryMetric][])
  .filter((spec) => spec.adverse !== null)
  .sort((a, b) => a.priority - b.priority);

/**
 * The metrics of this morning that have been outside the usual range, on the adverse side, for at least `streakDays` mornings
 * and whose streak has not been reported (`reported` holds `metric@startDate`). Longest run first.
 */
export function deviationCandidates(
  day: WellnessDay,
  settings: AlertSettings,
  reported: ReadonlySet<string>,
): DeviationCandidate[] {
  const found: DeviationCandidate[] = [];
  for (const spec of SCORED) {
    const reading = day[spec.key as RecoveryMetric];
    const side = spec.adverse === "high" ? "above" : "below";
    if (
      reading.value === null ||
      reading.deviation !== side ||
      reading.streak < settings.streakDays
    ) {
      continue;
    }
    const streakStart = addDaysToIsoDate(day.date, -(reading.streak - 1));
    const streakKey = `${spec.key}@${streakStart}`;
    if (reported.has(streakKey)) continue;
    found.push({
      metric: spec.key as RecoveryMetric,
      fact: {
        metric: spec.key as RecoveryMetric,
        label: spec.label,
        value: reading.value,
        unit: spec.unit,
        baselineLow: reading.baselineLow,
        baselineHigh: reading.baselineHigh,
        deviation: reading.deviation,
        streak: reading.streak,
      },
      streak: reading.streak,
      streakStart,
      streakKey,
    });
  }
  return found.sort((a, b) => b.streak - a.streak);
}

// ---- Plan drift -----------------------------------------------------------------------------------

export interface PlannedRow {
  id: string;
  date: string;
  name: string;
  plannedLoad: number | null;
  status: "planned" | "skipped";
}

export interface DriftEvaluation {
  reasons: DriftReason[];
  missed: { name: string; date: string }[];
  /** Null when the load cannot be compared (no sessions planned, too little planned, or a planned load is unknown). */
  load: { planned: number; ridden: number } | null;
}

/** The sessions of `planned` that no ride completed, by the rule the Telegram summaries use (`pairRides`). The plan events' "missed" uses it too (ADR 0035). */
export function findMissed<P extends { id: string; date: string; name: string }>(
  planned: readonly P[],
  rides: readonly RideBrief[],
  timezone: string,
): P[] {
  const paired = pairRides(planned, rides, timezone);
  return planned.filter((row) => !paired.has(row.id));
}

/**
 * Plan drift over the `windowDays` days before `today`. `rows` are the plan history of those days and `rides` the rides that
 * started in them. Skipped sessions (the rider's own choice) are neither missed nor planned.
 */
export function evaluateDrift(
  today: string,
  rows: readonly PlannedRow[],
  rides: readonly RideBrief[],
  timezone: string,
  settings: AlertSettings,
): DriftEvaluation {
  const from = addDaysToIsoDate(today, -settings.windowDays);
  const to = addDaysToIsoDate(today, -1);
  const planned = rows.filter(
    (row) => row.status === "planned" && row.date >= from && row.date <= to,
  );
  const missed = findMissed(planned, rides, timezone).map((row) => ({
    name: row.name,
    date: row.date,
  }));

  const reasons: DriftReason[] = [];
  if (missed.length >= settings.missedSessions) reasons.push("missed");

  let load: DriftEvaluation["load"] = null;
  const known = planned.every((row) => row.plannedLoad !== null);
  const plannedLoad = planned.reduce((sum, row) => sum + (row.plannedLoad ?? 0), 0);
  if (planned.length > 0 && known && plannedLoad >= settings.minPlannedLoad) {
    const ridden = rides.reduce((sum, ride) => sum + (ride.trainingStressScore ?? 0), 0);
    load = { planned: plannedLoad, ridden };
    const difference = ((ridden - plannedLoad) / plannedLoad) * 100;
    if (difference > settings.loadAbovePercent) reasons.push("load-above");
    else if (difference < -settings.loadBelowPercent) reasons.push("load-below");
  }
  return { reasons, missed, load };
}
