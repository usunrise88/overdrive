import type { LeafStep, RideRecord, Workout } from "@overdrive/contracts";
import { rideCompliance } from "../ride-stats.ts";
import { localDate } from "../time.ts";

/**
 * The name and the text description of a ride on Strava (spec §5.5, §5.6, ADR 0020), written by
 * code from our own ride record and the workout it was played from. No model writes it and nothing
 * is read from Strava. Plain text, short, every number from the record, no emoji and no claim about
 * health or fitness: what was planned and what was done, and nothing about what it means.
 */

export interface RideText {
  /** The activity's title: the planned workout's name, or `Virtual ride` and the date for a free ride. */
  name: string;
  /** Lines separated by a newline. */
  description: string;
}

/** Strava's title field is short; a longer name is cut. */
const MAX_NAME = 100;

/** `45 s`, `10 min`, `12 min 30 s`, `1 h 05 min`, `2 h`. */
export function spokenDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  if (total < 60) return `${total} s`;
  if (total < 3600) {
    const minutes = Math.floor(total / 60);
    const rest = total % 60;
    return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
  }
  const hours = Math.floor(total / 3600);
  const minutes = Math.round((total % 3600) / 60);
  if (minutes === 60) return `${hours + 1} h`;
  return minutes === 0 ? `${hours} h` : `${hours} h ${String(minutes).padStart(2, "0")} min`;
}

/** The length of a whole ride: whole minutes from a minute up (a ride is not timed to the second). */
function rideDuration(seconds: number): string {
  return seconds < 60 ? spokenDuration(seconds) : spokenDuration(Math.round(seconds / 60) * 60);
}

/** 0.875 is `87.5`, 0.45 is `45`. */
const percent = (fraction: number): string => String(Math.round(fraction * 1000) / 10);

const whole = (value: number): string => String(Math.round(value));

/** Whole numbers from 10 up; below, one decimal, so that a short ride does not read 0 kJ or load 0. */
const compact = (value: number): string =>
  value < 10 ? String(Math.round(value * 10) / 10) : whole(value);

function leaf(step: LeafStep): string {
  const length = spokenDuration(step.duration);
  if (step.type === "steady") return `${length} at ${percent(step.target)}% FTP`;
  if (step.type === "ramp")
    return `${length} ramp ${percent(step.from)} to ${percent(step.to)}% FTP`;
  if (step.type === "hr") {
    const target =
      step.target_bpm !== undefined
        ? `${step.target_bpm} bpm`
        : `${percent(step.target_lthr ?? 0)}% of threshold heart rate`;
    return `${length} by heart rate at ${target}, ${step.power_min} to ${step.power_max} W`;
  }
  return `${length} free`;
}

/** The steps in one line: `10 min ramp 45 to 70% FTP, 3 x (10 min at 88% FTP + 5 min at 50% FTP), 5 min free`. */
export function sessionOutline(workout: Workout): string {
  return workout.steps
    .map((step) =>
      step.type === "repeat" ? `${step.count} x (${step.steps.map(leaf).join(" + ")})` : leaf(step),
    )
    .join(", ");
}

const MAX_OUTLINE = 200;

function totalSeconds(workout: Workout): number {
  return workout.steps.reduce(
    (sum, step) =>
      sum +
      (step.type === "repeat"
        ? step.count * step.steps.reduce((inner, s) => inner + s.duration, 0)
        : step.duration),
    0,
  );
}

/** The session in one line. A workout whose steps would not fit a line is said by its size. */
function sessionLine(workout: Workout): string {
  const outline = sessionOutline(workout);
  if (outline.length <= MAX_OUTLINE) return `${workout.name}: ${outline}.`;
  const count = workout.steps.length;
  return `${workout.name}: ${count} ${count === 1 ? "step" : "steps"}, ${spokenDuration(totalSeconds(workout))}.`;
}

const present = (value: number | null | undefined): value is number =>
  value !== null && value !== undefined && Number.isFinite(value);

function plannedVersusDone(record: RideRecord, workout: Workout): string | null {
  const compliance = rideCompliance(record, workout);
  if ("problem" in compliance) return null;
  const parts = [
    `Planned ${spokenDuration(compliance.plannedSeconds)}, ridden ${spokenDuration(compliance.actualSeconds)}`,
    `${compliance.stepsCompleted} of ${compliance.stepsPlanned} ${compliance.stepsPlanned === 1 ? "step" : "steps"} completed`,
  ];
  let text = `${parts.join("; ")}.`;
  const { plannedWatts, actualWatts, deltaPercent } = compliance.targetSteps;
  if (present(plannedWatts) && present(actualWatts)) {
    const delta =
      present(deltaPercent) && Math.round(deltaPercent) !== 0
        ? ` (${deltaPercent > 0 ? "+" : "-"}${Math.abs(Math.round(deltaPercent))}%)`
        : "";
    text += ` Target power planned ${whole(plannedWatts)} W, ridden ${whole(actualWatts)} W${delta}.`;
  }
  return text;
}

/**
 * The title and description of a ride. `workout` is the workout it was played from (null for a free
 * ride, or when it can no longer be found); `timezone` decides which day a free ride is named for.
 */
export function describeRide(input: {
  record: RideRecord;
  workout: Workout | null;
  timezone: string;
}): RideText {
  const { record, workout, timezone } = input;
  const { summary, ride } = record;
  const lines: string[] = [];

  lines.push(workout === null ? "Free ride." : sessionLine(workout));

  const paused = summary.durationSeconds - summary.movingSeconds;
  let duration = `Duration ${rideDuration(summary.durationSeconds)}`;
  if (paused >= 60) duration += `, moving ${rideDuration(summary.movingSeconds)}`;
  lines.push(`${duration}.`);

  const power: string[] = [];
  if (present(summary.averagePower)) power.push(`Average power ${whole(summary.averagePower)} W`);
  if (present(summary.normalizedPower)) {
    power.push(`normalised power ${whole(summary.normalizedPower)} W`);
  }
  if (power.length > 0) lines.push(`${power.join(", ")}.`);

  if (present(summary.averageHeartRate)) {
    lines.push(`Average heart rate ${whole(summary.averageHeartRate)} bpm.`);
  }

  const work: string[] = [];
  if (summary.workKilojoules > 0) work.push(`Work ${compact(summary.workKilojoules)} kJ`);
  if (present(summary.trainingStressScore)) {
    work.push(`${work.length === 0 ? "Load" : "load"} ${compact(summary.trainingStressScore)}`);
  }
  if (work.length > 0) lines.push(`${work.join(", ")}.`);

  if (workout !== null) {
    const versus = plannedVersusDone(record, workout);
    if (versus !== null) lines.push(versus);
  }

  lines.push(`FTP ${whole(ride.ftp)} W.`);

  const name =
    workout === null
      ? `Virtual ride ${localDate(ride.startEpochMillis, timezone)}`
      : workout.name.trim() === ""
        ? `Virtual ride ${localDate(ride.startEpochMillis, timezone)}`
        : workout.name.trim().slice(0, MAX_NAME);
  return { name, description: lines.join("\n") };
}
