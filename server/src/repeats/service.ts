import {
  REPEAT_MIN_STEP_SECONDS,
  REPEAT_MIN_TREND_ATTEMPTS,
  REPEAT_NOTE,
  REPEAT_POWER_TOLERANCE_PERCENT,
  REPEAT_SKIP_SECONDS,
  type RepeatAttempt,
  type RepeatAttemptRef,
  type RepeatChange,
  type RepeatDetailResponse,
  type RepeatPreviousResponse,
  type RepeatPreviousStep,
  type RepeatSessionStep,
  type RepeatSummary,
  type RepeatsResponse,
  type Workout,
} from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import type { MetricsService } from "../metrics/service.ts";
import { type FlatStep, flattenWorkout } from "../ride-stats.ts";
import type { RideStore } from "../ride-store.ts";
import type { RoutesService } from "../routes/service.ts";
import { localDate } from "../time.ts";
import {
  type AttemptSteps,
  changeOf,
  comparedSteps,
  type Overall,
  overallHeartRate,
  type StepWeight,
  trendOf,
} from "./compare.ts";
import { completionPercent, type RideRepeatStats, rideRepeatStats } from "./figures.ts";
import { type AttemptRow, createRepeatStore, type RepeatStore } from "./store.ts";

/**
 * Repeat-session comparison (spec §11.7, ADR 0026). Two rides are the same session when the workouts they were played from have
 * the same structure (`workoutHash` of the contracts); this module lists the sessions ridden more than once, puts every attempt at
 * one side by side with the heart rate at the same power, the aerobic drift and the compliance and their trend, and gives the
 * attempt before a ride (the tablet's overlay line itself is stage v0.4.0). Every number is computed here from the stored rides,
 * never by a model. A ride has no hash without a workout snapshot, and then never matches another.
 *
 * Health data is private: nothing here logs a value, only counts and ids.
 */

/** A change of the fitted heart rate smaller than this many bpm over the attempts is steady: day-to-day noise is a few bpm. */
export const HEART_RATE_STEADY_BELOW = 2;
/** The same for the aerobic drift, in percentage points. */
export const DRIFT_STEADY_BELOW = 1;
/** The same for compliance, in percentage points of the planned time ridden. */
export const COMPLIANCE_STEADY_BELOW = 5;

/** What `get_ride` says about the ride's place among the attempts at its session. */
export interface RideRepeatOf {
  hash: string;
  /** 1 for the first time the session was ridden. */
  attemptNumber: number;
  /** How many attempts the session has now. */
  attempts: number;
  /** The attempt before this one; null for the first. */
  previous: {
    rideId: string;
    date: string;
    heartRateAtSamePower: {
      thisRide: number | null;
      previous: number | null;
      /** This ride minus the previous attempt, bpm: lower at the same power is fitter. */
      difference: number | null;
      /** Why there is no figure; null when there is one. */
      reason: string | null;
    };
  } | null;
  note: string;
}

export interface RepeatsService {
  /** `GET /v1/repeats`. */
  list(): Promise<RepeatsResponse>;
  /** `GET /v1/repeats/{hash}`; null when no session with this hash has been ridden at least twice. */
  detail(hash: string): Promise<RepeatDetailResponse | null>;
  /**
   * `GET /v1/repeats/{hash}/previous[?before=rideId]`: the attempt before a ride (the latest of all without `before`), with its
   * step boundaries and heart rates. `"unknown-ride"` when `before` names a ride that is not stored and does not carry its start.
   */
  previous(hash: string, before: string | null): Promise<RepeatPreviousResponse | "unknown-ride">;
  /** The ride's place among the attempts at its session; null when it has no hash or the session has been ridden once. */
  repeatOf(rideId: string): Promise<RideRepeatOf | null>;
  /** Works out the hashes of the stored rides' workouts that have none; the number done. */
  ensureHashes(): Promise<number>;
  /** Backfills the hashes of the rides stored before this existed, without waiting for it. */
  start(): void;
  /** Resolves when the backfill has finished. */
  whenIdle(): Promise<void>;
  readonly store: RepeatStore;
}

export interface RepeatsOptions {
  db: Db;
  rides: Pick<RideStore, "getRecord" | "workoutSnapshot">;
  metrics: Pick<MetricsService, "ensureComputed" | "store">;
  /** The routes' side of the ride rows: every ride that followed a route is recorded with its route key before a read. */
  routes: Pick<RoutesService, "ensureRideRoutes">;
  timezone: string;
  now: () => Date;
  log: Logger;
}

const iso = (ms: number): string => new Date(ms).toISOString();
const clip = (text: string, max: number): string =>
  text.length <= max ? text : `${text.slice(0, max - 1)}…`;
const round1 = (value: number): number => Math.round(value * 10) / 10;

/** What a route session is played from, for the figures: a workout with no steps, so the session has no fixed-power step. */
const routeWorkout = (name: string): Workout => ({ schema: 1, id: "route", name, steps: [] });

/** The route key every attempt shares, or null when they do not all have the same one. */
function commonRouteKey(attempts: readonly AttemptRow[]): string | null {
  const first = attempts[0]?.routeKey ?? null;
  return first !== null && attempts.every((a) => a.routeKey === first) ? first : null;
}

/** A ride id made by the engine carries its start: `ride-<start millis>`. */
const RIDE_ID_WITH_START = /^ride-(\d{10,14})$/;

/** The session's fixed-power steps and their weights, from one ride's workout. */
function sessionSteps(workout: Workout): {
  flat: FlatStep[];
  steps: RepeatSessionStep[];
  weights: StepWeight[];
} {
  const flat = flattenWorkout(workout);
  const steps: RepeatSessionStep[] = [];
  for (const step of flat) {
    if (step.type !== "steady" || step.fromFraction === null) continue;
    steps.push({
      step: step.index + 1,
      label: clip(step.label, 100),
      durationSeconds: step.durationSeconds,
      // In thousandths, as in the hash: a converter's float noise is not a target.
      targetFraction: Math.round(step.fromFraction * 1000) / 1000,
    });
  }
  return {
    flat,
    steps,
    weights: steps.map((s) => ({ step: s.step, durationSeconds: s.durationSeconds })),
  };
}

export function createRepeats(options: RepeatsOptions): RepeatsService {
  const { db, rides, metrics, routes, timezone, now, log } = options;
  const store = createRepeatStore(db);
  let backfill: Promise<unknown> = Promise.resolve();

  /** The rides' route keys, then the workout hashes: what a read needs before it groups the rides. */
  async function ensure(): Promise<number> {
    await routes.ensureRideRoutes();
    return store.ensureHashes();
  }

  /** The workout an attempt was played from (a ride on a route and no workout: one with no steps), or null when it is unknown. */
  async function workoutFor(attempt: AttemptRow): Promise<Workout | null> {
    const snapshot = await rides.workoutSnapshot(attempt.rideId);
    if (snapshot !== null) return snapshot;
    return attempt.kind === "route" ? routeWorkout(attempt.name) : null;
  }

  /** The figures of these rides: the stored ones, and the rest worked out from their records and kept. */
  async function loadStats(attempts: readonly AttemptRow[]): Promise<Map<string, RideRepeatStats>> {
    const known = await store.stats(attempts.map((a) => a.rideId));
    for (const attempt of attempts) {
      if (known.has(attempt.rideId)) continue;
      const [record, workout, sha] = await Promise.all([
        rides.getRecord(attempt.rideId),
        workoutFor(attempt),
        store.recordSha(attempt.rideId),
      ]);
      if (record === null || workout === null || sha === null) continue;
      const stats = rideRepeatStats(record, workout, flattenWorkout(workout));
      await store.saveStats(attempt.rideId, sha, attempt.hash, stats, now());
      known.set(attempt.rideId, stats);
    }
    return known;
  }

  /** The aerobic drift of a ride (spec §6.3), or why there is none. */
  async function driftOf(rideId: string): Promise<{ value: number | null; reason: string | null }> {
    const row = await metrics.store.get(rideId);
    if (row === null) return { value: null, reason: "not worked out yet" };
    return row.drift.available
      ? { value: row.drift.driftPercent, reason: null }
      : { value: null, reason: clip(row.drift.reason, 300) };
  }

  const refOf = (attempt: AttemptRow): RepeatAttemptRef => ({
    rideId: attempt.rideId,
    date: localDate(attempt.startMs, timezone),
    startTime: iso(attempt.startMs),
  });

  const stepsOf = (stats: RideRepeatStats): AttemptSteps => ({ steps: stats.steps });

  // ---- GET /v1/repeats --------------------------------------------------------------------------------------------

  async function list(): Promise<RepeatsResponse> {
    await ensure();
    await metrics.ensureComputed();
    const byHash = new Map<string, AttemptRow[]>();
    for (const attempt of await store.attempts()) {
      const group = byHash.get(attempt.hash);
      if (group === undefined) byHash.set(attempt.hash, [attempt]);
      else group.push(attempt);
    }

    const repeats: RepeatSummary[] = [];
    for (const [hash, attempts] of byHash) {
      if (attempts.length < 2) continue;
      const latest = attempts[attempts.length - 1] as AttemptRow;
      const previous = attempts[attempts.length - 2] as AttemptRow;
      const first = attempts[0] as AttemptRow;
      const workout = await workoutFor(latest);
      if (workout === null) continue;
      const stats = await loadStats([previous, latest]);
      const latestStats = stats.get(latest.rideId);
      const previousStats = stats.get(previous.rideId);
      if (latestStats === undefined || previousStats === undefined) continue;
      const { weights } = sessionSteps(workout);

      const compared = comparedSteps([stepsOf(previousStats), stepsOf(latestStats)]);
      const noFixed = weights.length === 0;
      const overallLatest = overallHeartRate(
        stepsOf(latestStats),
        compared.steps,
        compared.reference,
        weights,
        {
          noFixedPowerStep: noFixed,
        },
      );
      const overallPrevious = overallHeartRate(
        stepsOf(previousStats),
        compared.steps,
        compared.reference,
        weights,
        {
          noFixedPowerStep: noFixed,
        },
      );
      const [driftLatest, driftPrevious] = await Promise.all([
        driftOf(latest.rideId),
        driftOf(previous.rideId),
      ]);
      const percentLatest = completionPercent(latestStats);
      const percentPrevious = completionPercent(previousStats);
      const heartRateAtPower: RepeatChange = changeOf(overallLatest.value, overallPrevious.value, {
        latest: overallLatest.reason,
        previous: overallPrevious.reason,
      });
      repeats.push({
        hash,
        name: clip(latest.name, 100),
        routeKey: commonRouteKey(attempts),
        attempts: attempts.length,
        firstDate: localDate(first.startMs, timezone),
        lastDate: localDate(latest.startMs, timezone),
        latest: refOf(latest),
        previous: refOf(previous),
        comparison: {
          heartRateAtPower,
          driftPercent: changeOf(driftLatest.value, driftPrevious.value, {
            latest: driftLatest.reason,
            previous: driftPrevious.reason,
          }),
          compliance: changeOf(percentLatest, percentPrevious, {
            latest: "the workout has no length",
            previous: "the workout has no length",
          }),
        },
      });
    }
    repeats.sort((a, b) =>
      a.lastDate === b.lastDate ? a.hash.localeCompare(b.hash) : a.lastDate < b.lastDate ? 1 : -1,
    );
    return { generatedAt: now().toISOString(), repeats };
  }

  // ---- GET /v1/repeats/{hash} -------------------------------------------------------------------------------------

  async function detail(hash: string): Promise<RepeatDetailResponse | null> {
    await ensure();
    await metrics.ensureComputed();
    const all = await store.attempts(hash);
    const stats = await loadStats(all);
    const attempts = all.filter((a) => stats.has(a.rideId));
    if (attempts.length < 2) return null;
    const latest = attempts[attempts.length - 1] as AttemptRow;
    const workout = await workoutFor(latest);
    if (workout === null) return null;
    const { steps, weights } = sessionSteps(workout);

    const compared = comparedSteps(
      attempts.map((a) => stepsOf(stats.get(a.rideId) as RideRepeatStats)),
    );
    const built: RepeatAttempt[] = [];
    for (const [index, attempt] of attempts.entries()) {
      const mine = stats.get(attempt.rideId) as RideRepeatStats;
      const overall: Overall = overallHeartRate(
        stepsOf(mine),
        compared.steps,
        compared.reference,
        weights,
        {
          noFixedPowerStep: weights.length === 0,
        },
      );
      const drift = await driftOf(attempt.rideId);
      built.push({
        rideId: attempt.rideId,
        number: index + 1,
        date: localDate(attempt.startMs, timezone),
        startTime: iso(attempt.startMs),
        durationSeconds: attempt.durationSeconds,
        ftp: mine.ftp,
        averagePower: attempt.averagePower === null ? null : round1(attempt.averagePower),
        averageHeartRate:
          attempt.averageHeartRate === null ? null : round1(attempt.averageHeartRate),
        effortRating: null,
        heartRateAtPower: {
          value: overall.value,
          seconds: overall.seconds,
          reason: overall.reason === null ? null : clip(overall.reason, 300),
        },
        stepHeartRates: steps.map((meta) => {
          const found = mine.steps.find((s) => s.step === meta.step);
          return found === undefined
            ? {
                step: meta.step,
                targetWatts: round1(meta.targetFraction * mine.ftp),
                averagePower: null,
                averageHeartRate: null,
                seconds: 0,
                reason: "the step was not ridden",
              }
            : { ...found, reason: found.reason === null ? null : clip(found.reason, 300) };
        }),
        driftPercent: drift,
        compliance: {
          percent: completionPercent(mine),
          stepsCompleted: mine.stepsCompleted,
          stepsPlanned: mine.stepsPlanned,
          powerDeltaPercent: mine.powerDeltaPercent,
        },
      });
    }

    const values = (pick: (a: RepeatAttempt) => number | null): number[] =>
      built.flatMap((a) => {
        const value = pick(a);
        return value === null ? [] : [value];
      });
    return {
      generatedAt: now().toISOString(),
      hash,
      name: clip(latest.name, 100),
      routeKey: commonRouteKey(attempts),
      note: REPEAT_NOTE,
      definition: {
        powerTolerancePercent: REPEAT_POWER_TOLERANCE_PERCENT as 3,
        skipSeconds: REPEAT_SKIP_SECONDS as 60,
        minStepSeconds: REPEAT_MIN_STEP_SECONDS as 120,
        minTrendAttempts: REPEAT_MIN_TREND_ATTEMPTS as 3,
      },
      steps,
      comparedSteps: compared.steps,
      attempts: built,
      trends: {
        heartRateAtPower: trendOf(
          values((a) => a.heartRateAtPower.value),
          {
            unit: "bpm",
            betterWhen: "lower",
            steadyBelow: HEART_RATE_STEADY_BELOW,
          },
        ),
        driftPercent: trendOf(
          values((a) => a.driftPercent.value),
          {
            unit: "%",
            betterWhen: "lower",
            steadyBelow: DRIFT_STEADY_BELOW,
          },
        ),
        compliance: trendOf(
          values((a) => a.compliance.percent),
          {
            unit: "%",
            betterWhen: "higher",
            steadyBelow: COMPLIANCE_STEADY_BELOW,
          },
        ),
      },
    };
  }

  // ---- GET /v1/repeats/{hash}/previous ----------------------------------------------------------------------------

  async function startOf(rideId: string): Promise<number | null> {
    const found = await db.query<{ start: string }>(
      "SELECT start_epoch_millis::text AS start FROM rides WHERE id = $1",
      [rideId],
    );
    if (found.rows[0] !== undefined) return Number(found.rows[0].start);
    const carried = RIDE_ID_WITH_START.exec(rideId);
    return carried === null ? null : Number(carried[1]);
  }

  async function previous(
    hash: string,
    before: string | null,
  ): Promise<RepeatPreviousResponse | "unknown-ride"> {
    await ensure();
    let cutoff: number | null = null;
    if (before !== null) {
      cutoff = await startOf(before);
      if (cutoff === null) return "unknown-ride";
    }
    const all = await store.attempts(hash);
    const candidates = all.filter(
      (a) => a.rideId !== before && (cutoff === null || a.startMs < cutoff),
    );
    const found = candidates[candidates.length - 1];
    const answer = (value: RepeatPreviousResponse["previous"]): RepeatPreviousResponse => ({
      generatedAt: now().toISOString(),
      hash,
      before,
      previous: value,
    });
    if (found === undefined) return answer(null);
    const stats = (await loadStats([found])).get(found.rideId);
    const workout = await workoutFor(found);
    if (stats === undefined || workout === null) return answer(null);
    const { flat, weights } = sessionSteps(workout);

    // The steps in the order the ride rode them: a step ridden out of order is left out, so the boundaries never overlap.
    const steps: RepeatPreviousStep[] = [];
    let lastStep = 0;
    let lastEnd = 0;
    for (const lap of [...stats.laps].sort((a, b) => a.startSeconds - b.startSeconds)) {
      if (lap.step <= lastStep || lap.startSeconds < lastEnd) continue;
      const meta = flat[lap.step - 1];
      const figure = stats.steps.find((s) => s.step === lap.step);
      steps.push({
        step: lap.step,
        label: clip(meta?.label ?? `Step ${lap.step}`, 100),
        startSeconds: lap.startSeconds,
        endSeconds: lap.endSeconds,
        targetWatts: figure?.targetWatts ?? null,
        averageHeartRate: figure?.averageHeartRate ?? null,
      });
      lastStep = lap.step;
      lastEnd = lap.endSeconds;
    }
    // Its own overall figure: the mean over the steps in which it has one, weighted by their planned length.
    const own = comparedSteps([stepsOf(stats)]);
    const overall = overallHeartRate(stepsOf(stats), own.steps, own.reference, weights, {
      noFixedPowerStep: weights.length === 0,
    });
    return answer({
      rideId: found.rideId,
      date: localDate(found.startMs, timezone),
      startTime: iso(found.startMs),
      durationSeconds: found.durationSeconds,
      ftp: stats.ftp,
      attempt: all.findIndex((a) => a.rideId === found.rideId) + 1,
      heartRateAtPower: overall.value,
      steps,
    });
  }

  // ---- For get_ride -----------------------------------------------------------------------------------------------

  async function repeatOf(rideId: string): Promise<RideRepeatOf | null> {
    await ensure();
    const hash = await store.hashOf(rideId);
    if (hash === null) return null;
    const all = await store.attempts(hash);
    const index = all.findIndex((a) => a.rideId === rideId);
    if (index < 0 || all.length < 2) return null;
    const earlier = index > 0 ? (all[index - 1] as AttemptRow) : null;
    const result: RideRepeatOf = {
      hash,
      attemptNumber: index + 1,
      attempts: all.length,
      previous: null,
      note: REPEAT_NOTE,
    };
    if (earlier === null) return result;

    const current = all[index] as AttemptRow;
    const stats = await loadStats([earlier, current]);
    const thisStats = stats.get(current.rideId);
    const earlierStats = stats.get(earlier.rideId);
    const workout = await workoutFor(current);
    const date = localDate(earlier.startMs, timezone);
    if (thisStats === undefined || earlierStats === undefined || workout === null) {
      result.previous = {
        rideId: earlier.rideId,
        date,
        heartRateAtSamePower: {
          thisRide: null,
          previous: null,
          difference: null,
          reason: "not worked out",
        },
      };
      return result;
    }
    const { weights } = sessionSteps(workout);
    // The comparison is taken over the steps this ride has a figure in.
    const compared = comparedSteps([stepsOf(thisStats)]);
    const noFixed = weights.length === 0;
    const mine = overallHeartRate(stepsOf(thisStats), compared.steps, compared.reference, weights, {
      noFixedPowerStep: noFixed,
    });
    const theirs = overallHeartRate(
      stepsOf(earlierStats),
      compared.steps,
      compared.reference,
      weights,
      {
        noFixedPowerStep: noFixed,
      },
    );
    const change = changeOf(
      mine.value,
      theirs.value,
      { latest: mine.reason, previous: theirs.reason },
      { latest: "this ride", previous: "the previous attempt" },
    );
    result.previous = {
      rideId: earlier.rideId,
      date,
      heartRateAtSamePower: {
        thisRide: change.latest,
        previous: change.previous,
        difference: change.difference,
        reason: change.reason,
      },
    };
    return result;
  }

  return {
    list,
    detail,
    previous,
    repeatOf,
    ensureHashes: () => ensure(),
    start() {
      backfill = ensure().then(
        (done) => {
          if (done > 0) log.info({ rides: done }, "workout hashes worked out for the stored rides");
        },
        (error: unknown) => log.error({ err: error }, "could not work out the workout hashes"),
      );
    },
    whenIdle: async () => {
      await backfill;
    },
    store,
  };
}
