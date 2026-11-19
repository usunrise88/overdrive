import {
  type Lap,
  type RideEvent,
  type RideRecord,
  type RideSample,
  validateRideRecord,
} from "@overdrive/contracts";
import type { Db } from "../../src/db.ts";
import { canonicalJson, sha256Hex } from "../../src/hash.ts";
import { rideStatistics } from "../../src/ride-stats.ts";

/**
 * Synthetic rides for the training-metrics tests: a ride is described by functions of the second
 * (1, 2, 3, …), so a test says "20 minutes at 300 W" and can work out the answer by hand. Nothing
 * here is a real recording.
 */

export interface SyntheticRide {
  id: string;
  startEpochMillis: number;
  /** FTP in watts at the time of the ride. Default 250. */
  ftp?: number;
  seconds: number;
  /** Watts in a second; null is a gap. Default: 150 W. */
  power?: (second: number) => number | null;
  heartRate?: (second: number) => number | null;
  /** The workout step of a second (indexes of the flattened workout), null in a free step. */
  step?: (second: number) => number | null;
  paused?: (second: number) => boolean;
  workoutId?: string | null;
  /** The record's events (spec §11.1), in the order they happened. */
  events?: RideEvent[];
}

export function syntheticRide(spec: SyntheticRide): RideRecord {
  const power = spec.power ?? (() => 150);
  const samples: RideSample[] = [];
  for (let second = 1; second <= spec.seconds; second++) {
    const watts = power(second);
    const heartRate = spec.heartRate?.(second) ?? null;
    const step = spec.step?.(second) ?? null;
    const sample: RideSample = { timeOffsetSeconds: second, distanceMeters: second * 8 };
    if (watts !== null) sample.power = watts;
    if (heartRate !== null) sample.heartRate = heartRate;
    if (step !== null) sample.stepIndex = step;
    if (spec.paused?.(second) === true) sample.paused = true;
    samples.push(sample);
  }

  // One lap per maximal run in the same step.
  const laps: Lap[] = [];
  let begin = 0;
  for (let i = 1; i <= samples.length; i++) {
    const here = samples[i - 1]?.stepIndex ?? null;
    const next = samples[i]?.stepIndex ?? null;
    if (i === samples.length || here !== next) {
      const run = samples.slice(begin, i);
      const watts = run.flatMap((s) => (s.paused === true || s.power == null ? [] : [s.power]));
      const rates = run.flatMap((s) =>
        s.paused === true || s.heartRate == null ? [] : [s.heartRate],
      );
      const lap: Lap = { startSeconds: begin, endSeconds: i };
      if (here !== null) lap.stepIndex = here;
      if (watts.length > 0) lap.averagePower = watts.reduce((a, b) => a + b, 0) / watts.length;
      if (rates.length > 0) lap.averageHeartRate = rates.reduce((a, b) => a + b, 0) / rates.length;
      laps.push(lap);
      begin = i;
    }
  }

  const record: RideRecord = {
    schema: 1,
    ride: {
      id: spec.id,
      startEpochMillis: spec.startEpochMillis,
      endEpochMillis: spec.startEpochMillis + spec.seconds * 1000,
      ftp: spec.ftp ?? 250,
      devices: ["Synthetic trainer"],
      ...(spec.workoutId === undefined || spec.workoutId === null
        ? {}
        : { workoutId: spec.workoutId }),
    },
    samples,
    rrIntervals: [],
    laps,
    summary: {
      durationSeconds: spec.seconds,
      movingSeconds: spec.seconds,
      distanceMeters: 0,
      workKilojoules: 0,
    },
    ...(spec.events === undefined ? {} : { events: spec.events }),
  };

  // The tablet's own summary, from the same definitions, so the record looks as one would.
  const stats = rideStatistics(record, [55, 75, 90, 105, 120, 150]);
  record.summary = {
    durationSeconds: stats.durationSeconds,
    movingSeconds: stats.movingSeconds,
    distanceMeters: spec.seconds * 8,
    workKilojoules: stats.power.workKilojoules,
    ...(stats.power.averageWatts === null ? {} : { averagePower: stats.power.averageWatts }),
    ...(stats.power.normalizedWatts === null
      ? {}
      : { normalizedPower: stats.power.normalizedWatts }),
    ...(stats.power.maxWatts === null ? {} : { maxPower: stats.power.maxWatts }),
    ...(stats.heartRate.averageBpm === null
      ? {}
      : { averageHeartRate: stats.heartRate.averageBpm }),
    ...(stats.heartRate.maxBpm === null ? {} : { maxHeartRate: stats.heartRate.maxBpm }),
    ...(stats.power.intensityFactor === null
      ? {}
      : { intensityFactor: stats.power.intensityFactor }),
    ...(stats.power.load === null ? {} : { trainingStressScore: stats.power.load }),
  };
  const checked = validateRideRecord(record);
  if (!checked.ok)
    throw new Error(`the synthetic ride is not a valid record: ${checked.errors.join("; ")}`);
  return record;
}

/** `minutes` of constant power. */
export const constant = (watts: number) => () => watts;

/** Stores a record as the intake would, without the FIT, straight into the table (fast enough for hundreds of rides). */
export async function insertRide(db: Db, record: RideRecord): Promise<void> {
  const json = canonicalJson(record);
  await db.query(
    `INSERT INTO rides (id, start_epoch_millis, end_epoch_millis, record, record_sha256, uploaded_by)
     VALUES ($1, $2, $3, $4::jsonb, $5, 'test')`,
    [
      record.ride.id,
      record.ride.startEpochMillis,
      record.ride.endEpochMillis ?? null,
      json,
      sha256Hex(json),
    ],
  );
}
