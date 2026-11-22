import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import { liveCoachShapeErrors, type MustSay, mustSayConsistencyErrors } from "./live-coach.ts";

// The sync API's request and response shapes (spec §8.3) live in their own files.
export * from "./effort.ts";
export * from "./insights.ts";
export * from "./live-coach.ts";
export * from "./metrics.ts";
export * from "./notebook.ts";
export * from "./outdoor.ts";
export * from "./plan.ts";
export * from "./plan-events.ts";
export * from "./power-skills.ts";
export * from "./profile.ts";
export * from "./repeats.ts";
export * from "./routes.ts";
export * from "./sync.ts";
export * from "./wellness.ts";

// ---- Workout (spec §8.1) -------------------------------------------------------------------
// Hand-written mirror of schema/workout.schema.json. The schema is the source of truth; the
// fixtures test keeps the two honest.

interface StepBase {
  /** Target cadence in rpm. Display only. */
  cadence?: number;
  label?: string;
  /**
   * What the live coach says exactly as written (spec §12.1, ADR 0038), at most 3 lines. Kept only by the service and served to the
   * tablet; never in the calendar. Not part of the workout's structure (it changes no hash).
   */
  must_say?: MustSay[];
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
  /** The route (spec §11.4) that backs the step: the tablet rides it and its grade feeds the trainer. Absent: the flat free ride. */
  route_id?: string;
}

/**
 * Heart-rate hold (spec §11.2): ERG with a target power chosen by a slow controller so that the heart rate stays at the
 * target. Exactly one of `target_bpm` and `target_lthr`; `power_min < power_max`, absolute watts (no FTP is needed).
 */
export interface HrStep extends StepBase {
  type: "hr";
  duration: number;
  /** Target heart rate in bpm. */
  target_bpm?: number;
  /** Target heart rate as a fraction of the threshold heart rate; the service resolves it to bpm for the tablet. */
  target_lthr?: number;
  /** Half-width of the band in bpm around the target; default {@link DEFAULT_HR_BAND_BPM}. */
  band?: number;
  /** The least power the controller may command, in watts. */
  power_min: number;
  /** The most power the controller may command, in watts. */
  power_max: number;
}

/** The band of a heart-rate step that does not say: the heart rate is inside it within this many bpm of the target. */
export const DEFAULT_HR_BAND_BPM = 5;

export type LeafStep = SteadyStep | RampStep | FreeStep | HrStep;

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
  /** true: the live coach may speak during this session (spec §12.1, ADR 0038). Absent: it is silent. Kept only by the service. */
  live_coach?: boolean;
  /** The agent's brief to the live coach: what the session is for, what to watch in this rider, what tone. At most 1000 characters. */
  coach_brief?: string;
  steps: Step[];
}

export type Validation<T> = { ok: true; value: T } | { ok: false; errors: string[] };

const workoutSchema: object = JSON.parse(
  readFileSync(new URL("../schema/workout.schema.json", import.meta.url), "utf8"),
);

/** The native workout's JSON Schema, for tools that advertise the format (the MCP tools describe it to a model). */
export const workoutJsonSchema: Readonly<Record<string, unknown>> = workoutSchema as Record<
  string,
  unknown
>;

const ajv = new Ajv2020({ allErrors: true });
const checkWorkout = ajv.compile<Workout>(workoutSchema);

/**
 * What the schema cannot say about a workout: a heart-rate step's `power_min` is below its `power_max`, and the `must_say` lines
 * of the live coach are spoken inside their step, once each.
 */
export function workoutConsistencyErrors(workout: Workout): string[] {
  const errors: string[] = mustSayConsistencyErrors(workout);
  const check = (step: LeafStep, at: string): void => {
    if (step.type === "hr" && step.power_min >= step.power_max) {
      errors.push(`${at} power_min must be below power_max`);
    }
  };
  for (const [index, step] of workout.steps.entries()) {
    if (step.type === "repeat") {
      for (const [inner, leaf] of step.steps.entries()) {
        check(leaf, `/steps/${index}/steps/${inner}`);
      }
    } else {
      check(step, `/steps/${index}`);
    }
  }
  return errors;
}

/** Validates untrusted input against the native workout schema, and then for what the schema cannot say. */
export function validateWorkout(input: unknown): Validation<Workout> {
  if (checkWorkout(input)) {
    const errors = workoutConsistencyErrors(input);
    return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
  }
  // The live coach's fields are said plainly first: a mistake in a nested step is only "must match exactly one schema" to the schema.
  const errors = [
    ...liveCoachShapeErrors(input),
    ...(checkWorkout.errors ?? []).map(
      (e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`,
    ),
  ];
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
  /** The route (spec §11.4) the ride followed; absent for a ride on the flat. A deleted route leaves the ride valid. */
  routeId?: string | null;
  /** The route's name when it was ridden, only with `routeId`. */
  routeName?: string | null;
  /** true when the rider chose the planned workout's easier variant (spec §11.5); only with `workoutId`. */
  variantRidden?: boolean | null;
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
  /**
   * In a heart-rate step: the bpm the controller aimed at during the second (the step's target moved by the rider).
   * Absent in other steps and during the step's start ramp; a second without it is not judged for compliance.
   */
  targetHeartRate?: number | null;
  stepIndex?: number | null;
  /** Default false. */
  paused?: boolean;
}

/** An implicit-feedback event of the ride (spec §11.1): the ERG stall guard took hold. */
export interface RideEvent {
  type: "stall-guard";
  /** The tick at which it was decided (a sample offset, 1 to the number of samples). */
  timeOffsetSeconds: number;
  /** The flattened workout step it happened in. */
  stepIndex?: number | null;
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
  /** Absent or empty: nothing happened. Additive to schema 1. */
  events?: RideEvent[];
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
  if (record.ride.routeName != null && record.ride.routeId == null) {
    return ["/ride/routeName needs a /ride/routeId"];
  }
  if (record.ride.variantRidden === true && record.ride.workoutId == null) {
    return ["/ride/variantRidden needs a /ride/workoutId: a variant belongs to a planned workout"];
  }
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
  let lastEvent = 0;
  for (const [index, event] of (record.events ?? []).entries()) {
    if (event.timeOffsetSeconds > samples.length) {
      return [
        `/events/${index} is at second ${event.timeOffsetSeconds}, the ride is ${samples.length} s long`,
      ];
    }
    if (event.timeOffsetSeconds < lastEvent) {
      return [`/events/${index} is earlier than the event before it: in the order they happened`];
    }
    lastEvent = event.timeOffsetSeconds;
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
