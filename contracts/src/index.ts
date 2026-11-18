import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";

// The sync API's request and response shapes (spec §8.3) live in their own files.
export * from "./plan.ts";
export * from "./profile.ts";
export * from "./sync.ts";

// ---- Workout (spec §8.1) -------------------------------------------------------------------
// Hand-written mirror of schema/workout.schema.json. The schema is the source of truth; the
// fixtures test keeps the two honest.

interface StepBase {
  /** Target cadence in rpm. Display only. */
  cadence?: number;
  label?: string;
}

export interface SteadyStep extends StepBase {
  type: "steady";
  /** Seconds. */
  duration: number;
  /** Fraction of FTP. */
  target: number;
}

export interface RampStep extends StepBase {
  type: "ramp";
  duration: number;
  from: number;
  to: number;
}

export interface FreeStep extends StepBase {
  type: "free";
  duration: number;
}

export type LeafStep = SteadyStep | RampStep | FreeStep;

export interface RepeatStep {
  type: "repeat";
  count: number;
  steps: LeafStep[];
  label?: string;
}

export type Step = LeafStep | RepeatStep;

export interface Workout {
  schema: 1;
  id: string;
  name: string;
  description?: string;
  steps: Step[];
}

export type Validation<T> = { ok: true; value: T } | { ok: false; errors: string[] };

const workoutSchema: object = JSON.parse(
  readFileSync(new URL("../schema/workout.schema.json", import.meta.url), "utf8"),
);

const ajv = new Ajv2020({ allErrors: true });
const checkWorkout = ajv.compile<Workout>(workoutSchema);

/** Validates untrusted input against the native workout schema. */
export function validateWorkout(input: unknown): Validation<Workout> {
  if (checkWorkout(input)) {
    return { ok: true, value: input };
  }
  const errors = (checkWorkout.errors ?? []).map(
    (e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`,
  );
  return { ok: false, errors };
}

// ---- Ride record (spec §8.2) -----------------------------------------------------------------
// Hand-written mirror of schema/ride-record.schema.json, which is the source of truth. A gap (a
// reading that was not measured) is an absent key; producers must not write null, but readers
// accept it, so every optional field is `| null` too.

export interface RideHeader {
  id: string;
  /** Wall-clock start, milliseconds since the Unix epoch. */
  startEpochMillis: number;
  endEpochMillis?: number | null;
  workoutId?: string | null;
  /** FTP in watts at the time of the ride. */
  ftp: number;
  /** Device names, trainer first. */
  devices?: string[];
}

export interface RideSample {
  /** Seconds since the start, at the end of the second described; at least 1. */
  timeOffsetSeconds: number;
  power?: number | null;
  heartRate?: number | null;
  cadence?: number | null;
  /** Default 0. */
  speedKmh?: number;
  /** Default 0. */
  distanceMeters?: number;
  gear?: number | null;
  targetPower?: number | null;
  stepIndex?: number | null;
  /** Default false. */
  paused?: boolean;
}

export interface RrInterval {
  timeOffsetSeconds: number;
  intervalMs: number;
}

export interface Lap {
  stepIndex?: number | null;
  startSeconds: number;
  endSeconds: number;
  averagePower?: number | null;
  averageHeartRate?: number | null;
}

export interface RideSummary {
  durationSeconds: number;
  movingSeconds: number;
  distanceMeters: number;
  averagePower?: number | null;
  normalizedPower?: number | null;
  maxPower?: number | null;
  averageHeartRate?: number | null;
  maxHeartRate?: number | null;
  workKilojoules: number;
  intensityFactor?: number | null;
  trainingStressScore?: number | null;
}

export interface RideRecord {
  schema: 1;
  ride: RideHeader;
  samples: RideSample[];
  rrIntervals: RrInterval[];
  laps: Lap[];
  summary: RideSummary;
}

const rideRecordSchema: object = JSON.parse(
  readFileSync(new URL("../schema/ride-record.schema.json", import.meta.url), "utf8"),
);

const checkRideRecord = ajv.compile<RideRecord>(rideRecordSchema);

/**
 * What the schema cannot say: the samples are the seconds 1, 2, 3, … in order, the laps follow
 * each other from 0 to the last second, and the summary's duration is the number of samples.
 * Returns the first violation, or an empty list.
 */
export function rideRecordConsistencyErrors(record: RideRecord): string[] {
  const { samples, laps, summary } = record;
  for (const [index, sample] of samples.entries()) {
    if (sample.timeOffsetSeconds !== index + 1) {
      return [`/samples/${index} is second ${sample.timeOffsetSeconds}, expected ${index + 1}`];
    }
  }
  if (summary.durationSeconds !== samples.length) {
    return [
      `/summary/durationSeconds is ${summary.durationSeconds}, there are ${samples.length} samples`,
    ];
  }
  if (samples.length === 0) {
    return laps.length === 0 ? [] : ["/laps must be empty for a ride without samples"];
  }
  const [first] = laps;
  if (first === undefined) return ["/laps needs at least one lap"];
  if (first.startSeconds !== 0) return [`/laps/0 starts at ${first.startSeconds}, not 0`];
  for (const [index, lap] of laps.entries()) {
    if (lap.endSeconds <= lap.startSeconds) {
      return [`/laps/${index} ends at ${lap.endSeconds}, not after its start ${lap.startSeconds}`];
    }
    const next = laps[index + 1];
    if (next !== undefined && next.startSeconds !== lap.endSeconds) {
      return [
        `/laps/${index + 1} starts at ${next.startSeconds}, where lap ${index} ends at ${lap.endSeconds}`,
      ];
    }
  }
  const last = laps[laps.length - 1];
  if (last !== undefined && last.endSeconds !== samples.length) {
    return [`The last lap ends at ${last.endSeconds}, the ride is ${samples.length} s long`];
  }
  return [];
}

/** Validates untrusted input against the ride record schema, and then for self-consistency. */
export function validateRideRecord(input: unknown): Validation<RideRecord> {
  if (!checkRideRecord(input)) {
    const errors = (checkRideRecord.errors ?? []).map(
      (e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`,
    );
    return { ok: false, errors };
  }
  const errors = rideRecordConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
