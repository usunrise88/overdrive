/**
 * intervals.icu's parsed workout (`workout_doc` of an event), as far as the converter reads it
 * (ADR 0016). It is the shape documented on the forum, not by the OpenAPI file (which calls it "an
 * object"): what could not be checked without an account is listed in the ADR. A text
 * description is parsed into the same shape (`workout-text.ts`), so one converter serves both.
 */

/** Something the native format cannot hold. The message is for the rider and the log. */
export class Unsupported extends Error {
  constructor(message: string) {
    super(message);
    this.name = "Unsupported";
  }
}

/** A target: a value, or a range (`start` to `end`), in `units` (`%ftp`, `w`, `power_zone`, `rpm`, …). */
export interface DocValue {
  value?: number;
  start?: number;
  end?: number;
  units?: string;
}

export interface DocStep {
  /** The cue shown for the step. */
  text?: string;
  /** Seconds. */
  duration?: number;
  /** Set when the step is measured in distance; there is no duration to play. */
  distance?: number;
  /** A repeat block: `reps` times `steps`. */
  reps?: number;
  steps?: DocStep[];
  /** The power target changes steadily from `start` to `end` over the step. */
  ramp?: boolean;
  /** ERG off: ride as you like. */
  freeride?: boolean;
  until_lap_press?: boolean;
  power?: DocValue;
  hr?: DocValue;
  pace?: DocValue;
  cadence?: DocValue;
  /** The power target resolved to watts for the athlete (`resolve=true`). */
  _power?: DocValue;
}

export interface WorkoutDoc {
  steps: DocStep[];
  /** The FTP in watts the targets were resolved with. */
  ftp?: number;
}

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const finite = (value: unknown): number | undefined =>
  typeof value === "number" && Number.isFinite(value) ? value : undefined;

function readValue(raw: unknown): DocValue | undefined {
  if (!isObject(raw)) return undefined;
  const value: DocValue = {};
  const v = finite(raw.value);
  const start = finite(raw.start);
  const end = finite(raw.end);
  if (v !== undefined) value.value = v;
  if (start !== undefined) value.start = start;
  if (end !== undefined) value.end = end;
  if (typeof raw.units === "string") value.units = raw.units;
  return value;
}

function readStep(raw: unknown, depth: number): DocStep {
  if (!isObject(raw)) throw new Unsupported("a step that is not an object");
  if (depth > 8) throw new Unsupported("steps nested too deeply");
  const step: DocStep = {};
  if (typeof raw.text === "string" && raw.text.trim() !== "") step.text = raw.text.trim();
  const duration = finite(raw.duration);
  if (duration !== undefined) step.duration = duration;
  const distance = finite(raw.distance);
  if (distance !== undefined && distance > 0) step.distance = distance;
  const reps = finite(raw.reps);
  if (reps !== undefined) step.reps = reps;
  if (Array.isArray(raw.steps)) step.steps = raw.steps.map((s) => readStep(s, depth + 1));
  if (raw.ramp === true) step.ramp = true;
  if (raw.freeride === true) step.freeride = true;
  if (raw.until_lap_press === true) step.until_lap_press = true;
  for (const key of ["power", "hr", "pace", "cadence", "_power"] as const) {
    const value = readValue(raw[key]);
    if (value !== undefined) step[key] = value;
  }
  return step;
}

/**
 * Reads the `workout_doc` of an event. Returns null when there is none (an event written as text
 * that intervals.icu has not parsed, or a note). Throws {@link Unsupported} for a step that is not
 * a step at all.
 */
export function readWorkoutDoc(raw: unknown): WorkoutDoc | null {
  if (!isObject(raw) || !Array.isArray(raw.steps)) return null;
  const doc: WorkoutDoc = { steps: raw.steps.map((s) => readStep(s, 1)) };
  const ftp = finite(raw.ftp);
  if (ftp !== undefined && ftp > 0) doc.ftp = ftp;
  return doc;
}
