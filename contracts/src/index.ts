import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";

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
