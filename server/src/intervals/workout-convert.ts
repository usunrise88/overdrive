import {
  addDaysToIsoDate,
  type FreeStep,
  type HrStep,
  isIsoDate,
  type LeafStep,
  type RampStep,
  type SteadyStep,
  type Step,
  validateWorkout,
  type Workout,
} from "@overdrive/contracts";
import type { IntervalsEvent, IntervalsEventPayload } from "./client.ts";
import { type DocStep, type DocValue, readWorkoutDoc, Unsupported } from "./workout-doc.ts";
import { type HrNote, parseHrNotes, parseWorkoutText, renderWorkoutText } from "./workout-text.ts";

/**
 * The adapter between intervals.icu workouts and the native workout format (spec §5.1, §8.1;
 * ADR 0016). Only this module and its neighbours in `intervals/` know intervals.icu's shapes.
 *
 * Reading: an event's `workout_doc` (its parsed steps), else its text description, else its planned
 * duration, becomes a native workout. Anything the native format cannot hold is `Unsupported` with
 * a reason in plain words, per workout, never an exception: the caller skips that workout and logs.
 */

export type Conversion =
  | {
      ok: true;
      workout: Workout;
      /** Things that were rounded or simplified, for the log. */
      notes: string[];
    }
  | { ok: false; reason: string };

interface Context {
  /** The FTP in watts the targets are relative to, when intervals.icu says. */
  ftp: number | null;
  notes: string[];
  /** The heart-rate notes of the description, by the number of the step line they are for (see `workout-text.ts`). */
  heartRateNotes: Map<number, HrNote>;
  /** How many step lines have been read so far: the number of the one being read. */
  stepLine: number;
}

/** Fractions of FTP are kept to three decimals: 0.833, not 0.8333333. */
const fraction = (value: number): number => Math.round(value * 1000) / 1000;

/** A value or range of a target as a list of its ends: `[88]`, `[80, 90]`. */
function ends(value: DocValue): number[] {
  if (value.value !== undefined) return [value.value];
  if (value.start !== undefined && value.end !== undefined) return [value.start, value.end];
  return [];
}

type Target = { kind: "steady"; at: number } | { kind: "ramp"; from: number; to: number };

/** The power target of a step as fractions of FTP, or null when the step has none (it is free). */
function powerTarget(step: DocStep, context: Context): Target | null {
  const power = step.power;
  if (power === undefined) return null;

  let source = power;
  let divisor = 100;
  const units = power.units;
  if (units === undefined) throw new Unsupported("a power target with no units");
  if (units === "w") {
    if (context.ftp === null) {
      throw new Unsupported("a target in watts, and the workout does not say which FTP it is for");
    }
    divisor = context.ftp;
  } else if (units !== "%ftp") {
    // Zones, % of a best effort, … depend on the athlete's own settings: use the watts
    // intervals.icu resolved them to, or give up.
    const resolved = step._power;
    if (resolved?.units !== "w" || context.ftp === null || ends(resolved).length === 0) {
      throw new Unsupported(
        `a power target in ${units} that intervals.icu did not resolve to watts (the FTP or the athlete's zones are unknown here)`,
      );
    }
    source = resolved;
    divisor = context.ftp;
  }

  const [first, second] = ends(source);
  if (first === undefined) throw new Unsupported("a power target with no value");
  if (second === undefined) return { kind: "steady", at: fraction(first / divisor) };
  if (step.ramp === true && first !== second) {
    return { kind: "ramp", from: fraction(first / divisor), to: fraction(second / divisor) };
  }
  // A band, not a ramp: ERG needs one number, the middle of the band.
  context.notes.push(`a target of ${first} to ${second} became its middle`);
  return { kind: "steady", at: fraction((first + second) / 2 / divisor) };
}

function cadenceOf(value: DocValue | undefined): number | undefined {
  if (value === undefined) return undefined;
  if (value.units !== undefined && value.units !== "rpm") return undefined;
  const [first, second] = ends(value);
  if (first === undefined) return undefined;
  const rpm = Math.round(second === undefined ? first : (first + second) / 2);
  return rpm > 0 ? rpm : undefined;
}

/**
 * The heart-rate step a note describes, when the step line it numbers is still the one written for it: its duration and the
 * power range that carries the controller's limits agree. Otherwise null, and the step is read as any step.
 */
function heartRateStepOf(
  step: DocStep,
  note: HrNote | undefined,
  extras: { cadence?: number; label?: string },
  duration: number,
): HrStep | null {
  if (note === undefined) return null;
  const power = step.power;
  const [low, high] = power === undefined ? [] : ends(power);
  if (power?.units !== "w" || low !== note.powerMin || high !== note.powerMax) return null;
  // The note keeps the label whole: the step line's cue lost the words that start with a digit.
  const { label: _cue, ...rest } = extras;
  return {
    type: "hr",
    duration,
    ...(note.targetBpm === null
      ? { target_lthr: note.targetLthr ?? 0 }
      : { target_bpm: note.targetBpm }),
    ...(note.band === null ? {} : { band: note.band }),
    power_min: note.powerMin,
    power_max: note.powerMax,
    ...rest,
    ...(note.label === null ? {} : { label: note.label }),
  };
}

function leafOf(step: DocStep, context: Context): LeafStep {
  context.stepLine += 1;
  const note = context.heartRateNotes.get(context.stepLine);
  if (step.until_lap_press === true) {
    throw new Unsupported("a step that lasts until the lap button is pressed");
  }
  if (step.duration === undefined || !(step.duration > 0)) {
    throw new Unsupported(
      step.distance !== undefined
        ? "a step measured in distance, not in time"
        : "a step with no duration",
    );
  }
  const duration = Math.round(step.duration);
  if (duration < 1) throw new Unsupported("a step shorter than a second");
  const extras: { cadence?: number; label?: string } = {};
  const cadence = cadenceOf(step.cadence);
  if (cadence !== undefined) extras.cadence = cadence;
  if (step.text !== undefined) extras.label = step.text;

  if (step.freeride === true) return { type: "free", duration, ...extras } satisfies FreeStep;
  const held = heartRateStepOf(step, note, extras, duration);
  if (held !== null) return held;
  if (note !== undefined) {
    context.notes.push(
      `the heart-rate note for step ${context.stepLine} does not match the step as it stands, so it was read as a power step`,
    );
  }
  const target = powerTarget(step, context);
  if (target === null) {
    // No power target (nothing, or a heart-rate or pace target a trainer cannot hold): ride free.
    if (step.hr !== undefined || step.pace !== undefined) {
      context.notes.push("a heart-rate or pace step became a free step");
    }
    return { type: "free", duration, ...extras } satisfies FreeStep;
  }
  if (target.kind === "ramp") {
    return {
      type: "ramp",
      duration,
      from: target.from,
      to: target.to,
      ...extras,
    } satisfies RampStep;
  }
  return { type: "steady", duration, target: target.at, ...extras } satisfies SteadyStep;
}

/** The leaf steps of a repeat's body; a nested group that runs once is spliced in, a real nesting is refused. */
function leavesOf(steps: DocStep[], context: Context): LeafStep[] {
  const leaves: LeafStep[] = [];
  for (const step of steps) {
    if (isGroup(step)) {
      if ((step.reps ?? 1) !== 1) throw new Unsupported("repeats inside repeats");
      leaves.push(...leavesOf(step.steps ?? [], context));
    } else {
      leaves.push(leafOf(step, context));
    }
  }
  return leaves;
}

const isGroup = (step: DocStep): boolean => step.steps !== undefined || step.reps !== undefined;

function stepsOf(steps: DocStep[], context: Context): Step[] {
  const out: Step[] = [];
  for (const step of steps) {
    if (!isGroup(step)) {
      out.push(leafOf(step, context));
      continue;
    }
    const reps = step.reps ?? 1;
    if (!Number.isInteger(reps) || reps < 1) throw new Unsupported(`a repeat of ${reps} times`);
    if (step.steps === undefined || step.steps.length === 0)
      throw new Unsupported("a repeat with no steps");
    const body = leavesOf(step.steps, context);
    if (reps === 1) out.push(...body);
    else
      out.push({
        type: "repeat",
        count: reps,
        steps: body,
        ...(step.text === undefined ? {} : { label: step.text }),
      });
  }
  return out;
}

/**
 * Converts a planned workout event into a native workout with the given id (the planned workout's:
 * `icu-123`). Never throws for a workout it cannot convert: that is `{ ok: false, reason }`.
 */
export function convertIntervalsEvent(event: IntervalsEvent, id: string): Conversion {
  try {
    const name = (event.name ?? "").trim() || "Workout";
    const doc = readWorkoutDoc(event.workout_doc);
    // A description with heart-rate notes was written by this service from a native workout: its own text is read, not the
    // parsed steps intervals.icu made of it (which cannot hold a heart-rate step), so the step lines and the notes agree.
    const heartRateNotes = parseHrNotes(event.description);
    const context: Context = {
      ftp:
        doc?.ftp ??
        (event.icu_ftp !== undefined && event.icu_ftp !== null && event.icu_ftp > 0
          ? event.icu_ftp
          : null),
      notes: [],
      heartRateNotes,
      stepLine: 0,
    };

    let docSteps: DocStep[] = heartRateNotes.size > 0 ? [] : (doc?.steps ?? []);
    if (docSteps.length === 0 && (event.description ?? "").trim() !== "") {
      docSteps = parseWorkoutText(event.description ?? "");
    }
    let steps: Step[];
    if (docSteps.length > 0) {
      steps = stepsOf(docSteps, context);
    } else if (typeof event.moving_time === "number" && event.moving_time >= 1) {
      // Planned by duration alone: a ride of that length, no targets.
      steps = [{ type: "free", duration: Math.round(event.moving_time) }];
      context.notes.push("no steps, only a planned duration: a free ride of that length");
    } else {
      throw new Unsupported("the workout has no steps and no planned duration");
    }

    const workout: Workout = { schema: 1, id, name, steps };
    const valid = validateWorkout(workout);
    if (!valid.ok) {
      throw new Unsupported(
        `it does not fit the native workout format (${valid.errors.slice(0, 3).join("; ")})`,
      );
    }
    return { ok: true, workout: valid.value, notes: context.notes };
  } catch (error) {
    if (error instanceof Unsupported) return { ok: false, reason: error.message };
    return {
      ok: false,
      reason: `conversion failed unexpectedly: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
}

/**
 * The event to send to intervals.icu to plan `workout` on `date` (`YYYY-MM-DD`): the steps travel as
 * the text description, which intervals.icu parses itself. For create and update (the MCP tools).
 */
export function workoutToEventPayload(workout: Workout, date: string): IntervalsEventPayload {
  if (!isIsoDate(date)) throw new Error(`Not a calendar date: ${date}`);
  return {
    category: "WORKOUT",
    start_date_local: `${addDaysToIsoDate(date, 0)}T00:00:00`,
    type: "Ride",
    name: workout.name,
    description: renderWorkoutText(workout),
    indoor: true,
  };
}
