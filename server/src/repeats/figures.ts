import type { Workout } from "@overdrive/contracts";
import {
  REPEAT_MIN_STEP_SECONDS,
  REPEAT_POWER_TOLERANCE_PERCENT,
  REPEAT_SKIP_SECONDS,
  type RideRecord,
  type RideSample,
} from "@overdrive/contracts";
import { type Compliance, type FlatStep, rideCompliance } from "../ride-stats.ts";

/**
 * The figures of the repeat comparison that come from one ride (spec §11.7, ADR 0026): for each fixed-power step the average heart
 * rate at the same power, and how the ride went against the plan. Pure functions of a ride record and its workout; computed by
 * code, never by a model.
 *
 * **Heart rate at the same power** of a step (the heart rate at a fixed power is the standard fitness marker of coaching practice;
 * it also moves with heat, sleep and caffeine, which is why the trend over several attempts is what counts):
 *
 * - Only **steady** steps have a fixed power. A ramp or a free step has none.
 * - The step's target is its fraction of FTP times the FTP the ride was recorded with.
 * - A second counts when it is **not paused**, has a power within {@link REPEAT_POWER_TOLERANCE_PERCENT}% of the target and a heart
 *   rate, and is **after the first {@link REPEAT_SKIP_SECONDS} s** of its stretch: a stretch is a run of seconds of the step with no
 *   pause in it, so the first minute after a resume is left out as well (heart rate takes that long to settle).
 * - The step has a figure only with at least {@link REPEAT_MIN_STEP_SECONDS} counted seconds; otherwise it has none and says why.
 * - The figure is the mean heart rate over the counted seconds (one decimal), with their mean power and how many there are.
 */

export const REPEAT_STATS_VERSION = 1;

export interface StepStat {
  /** 1-based position in the flattened workout. */
  step: number;
  /** The step's target at the ride's FTP, one decimal. */
  targetWatts: number;
  /** The counted seconds. */
  seconds: number;
  averagePower: number | null;
  averageHeartRate: number | null;
  /** Why there is no heart rate; null when there is one. */
  reason: string | null;
}

export interface LapBoundary {
  /** 1-based position in the flattened workout. */
  step: number;
  startSeconds: number;
  endSeconds: number;
}

/** What is kept per ride (`ride_repeat_stats`). */
export interface RideRepeatStats {
  ftp: number;
  steps: StepStat[];
  stepsPlanned: number;
  stepsCompleted: number;
  plannedSeconds: number;
  actualSeconds: number;
  powerDeltaPercent: number | null;
  laps: LapBoundary[];
}

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor + 0;
};

const mean = (values: readonly number[]): number =>
  values.reduce((a, b) => a + b, 0) / values.length;

/** The heart rate at the same power of every steady step of the workout, in order. */
export function stepStats(record: RideRecord, flat: readonly FlatStep[]): StepStat[] {
  const tolerance = REPEAT_POWER_TOLERANCE_PERCENT / 100;
  const byStep = new Map<number, RideSample[]>();
  for (const sample of record.samples) {
    if (sample.stepIndex == null) continue;
    const list = byStep.get(sample.stepIndex);
    if (list === undefined) byStep.set(sample.stepIndex, [sample]);
    else list.push(sample);
  }

  const stats: StepStat[] = [];
  for (const step of flat) {
    if (step.type !== "steady" || step.fromFraction === null) continue;
    const target = step.fromFraction * record.ride.ftp;
    const targetWatts = round(target, 1);
    const samples = byStep.get(step.index) ?? [];
    const none = (reason: string): StepStat => ({
      step: step.index + 1,
      targetWatts,
      seconds: 0,
      averagePower: null,
      averageHeartRate: null,
      reason,
    });
    if (samples.length === 0) {
      stats.push(none("the step was not ridden"));
      continue;
    }

    let run = 0;
    let previous: RideSample | null = null;
    let ridden = 0;
    let within = 0;
    const powers: number[] = [];
    const heartRates: number[] = [];
    for (const sample of samples) {
      const paused = sample.paused === true;
      const continues =
        previous !== null &&
        previous.paused !== true &&
        sample.timeOffsetSeconds === previous.timeOffsetSeconds + 1;
      previous = sample;
      if (paused) {
        run = 0;
        continue;
      }
      run = continues ? run + 1 : 1;
      if (run <= REPEAT_SKIP_SECONDS) continue;
      ridden++;
      if (sample.power == null || Math.abs(sample.power - target) > tolerance * target) continue;
      within++;
      if (sample.heartRate == null || sample.heartRate <= 0) continue;
      powers.push(sample.power);
      heartRates.push(sample.heartRate);
    }

    if (heartRates.length >= REPEAT_MIN_STEP_SECONDS) {
      stats.push({
        step: step.index + 1,
        targetWatts,
        seconds: heartRates.length,
        averagePower: round(mean(powers), 1),
        averageHeartRate: round(mean(heartRates), 1),
        reason: null,
      });
      continue;
    }
    let reason: string;
    if (ridden === 0) {
      reason = `less than ${REPEAT_SKIP_SECONDS} s of the step was ridden without a pause (the first ${REPEAT_SKIP_SECONDS} s are left out)`;
    } else if (within < REPEAT_MIN_STEP_SECONDS) {
      reason = `only ${within} s were within ${REPEAT_POWER_TOLERANCE_PERCENT}% of the target power after the first ${REPEAT_SKIP_SECONDS} s; ${REPEAT_MIN_STEP_SECONDS} s are needed`;
    } else {
      reason = "no heart rate was recorded at the target power";
    }
    stats.push({ ...none(reason), seconds: heartRates.length });
  }
  return stats;
}

/** The first lap of each workout step the ride rode: where the step ran, in seconds from the start of the ride. */
export function lapBoundaries(record: RideRecord): LapBoundary[] {
  const seen = new Set<number>();
  const boundaries: LapBoundary[] = [];
  for (const lap of record.laps) {
    if (lap.stepIndex == null || seen.has(lap.stepIndex)) continue;
    seen.add(lap.stepIndex);
    boundaries.push({
      step: lap.stepIndex + 1,
      startSeconds: lap.startSeconds,
      endSeconds: lap.endSeconds,
    });
  }
  return boundaries.sort((a, b) => a.step - b.step);
}

/** Everything the comparison keeps of a ride played from `workout`. */
export function rideRepeatStats(
  record: RideRecord,
  workout: Workout,
  flat: readonly FlatStep[],
): RideRepeatStats {
  const compliance: Compliance | { problem: string } = rideCompliance(record, workout);
  const complied = "problem" in compliance ? null : compliance;
  return {
    ftp: record.ride.ftp,
    steps: stepStats(record, flat),
    stepsPlanned: flat.length,
    stepsCompleted: complied?.stepsCompleted ?? 0,
    plannedSeconds: flat.reduce((a, s) => a + s.durationSeconds, 0),
    actualSeconds: complied?.actualSeconds ?? 0,
    powerDeltaPercent: complied?.targetSteps.deltaPercent ?? null,
    laps: lapBoundaries(record),
  };
}

/** The share of the planned time that was ridden, in percent (one decimal, at most 100); null for a workout of no length. */
export function completionPercent(
  stats: Pick<RideRepeatStats, "plannedSeconds" | "actualSeconds">,
): number | null {
  if (stats.plannedSeconds <= 0) return null;
  return round(Math.min(100, (100 * stats.actualSeconds) / stats.plannedSeconds), 1);
}
