import type { LeafStep, Step, Workout } from "@overdrive/contracts";

/**
 * Plain words for a native workout, computed by code (never by a model): how long it is and what
 * the steps are. The Google Calendar event, the Telegram reminders and `/today` all use these, so
 * the same session reads the same everywhere.
 */

function stepSeconds(step: Step): number {
  return step.type === "repeat"
    ? step.count * step.steps.reduce((sum, inner) => sum + inner.duration, 0)
    : step.duration;
}

/** The workout's length in seconds, repeats counted. */
export function workoutDurationSeconds(workout: Workout): number {
  return workout.steps.reduce((sum, step) => sum + stepSeconds(step), 0);
}

/** `45 s`, `10 min`, `1 min 30 s`, `1 h`, `1 h 02 min`. */
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  if (hours > 0) {
    if (minutes === 0 && rest === 0) return `${hours} h`;
    const mm = String(minutes).padStart(2, "0");
    return rest === 0 ? `${hours} h ${mm} min` : `${hours} h ${mm} min ${rest} s`;
  }
  if (minutes > 0) return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
  return `${rest} s`;
}

/** `88` for 0.88, `87.5` for 0.875. */
function percent(fraction: number): string {
  return String(Math.round(fraction * 1000) / 10);
}

/** A label in the middle of a sentence: `Warm-up` reads `warm-up`, `Z2 endurance` stays as it is. */
function inSentence(label: string): string {
  const text = label.replace(/\s+/g, " ").trim();
  return /^[A-Z][a-z-]*(\s|$)/.test(text)
    ? `${text.charAt(0).toLowerCase()}${text.slice(1)}`
    : text;
}

function leaf(step: LeafStep): string {
  const length = formatDuration(step.duration);
  const label = step.label === undefined ? "" : inSentence(step.label);
  switch (step.type) {
    case "steady":
      return `${length} ${label === "" ? "" : `${label} `}at ${percent(step.target)}% FTP`;
    case "ramp": {
      const range = `${percent(step.from)}% to ${percent(step.to)}% FTP`;
      return label === "" ? `${length} ramp ${range}` : `${length} ${label} (${range})`;
    }
    case "free":
      return `${length} ${label === "" ? "free ride" : label}`;
  }
}

function describeStep(step: Step): string {
  if (step.type !== "repeat") return leaf(step);
  const inner = step.steps.map(leaf);
  return inner.length === 1
    ? `${step.count} x ${inner[0]}`
    : `${step.count} x (${inner.join(", ")})`;
}

/**
 * The steps in one sentence, for example
 * `10 min warm-up (45% to 70% FTP), 3 x (10 min sweet spot at 88% FTP, 5 min recover at 50% FTP), 5 min free ride`.
 * No total and no full stop: the caller adds what it needs. Cut at `maxLength` characters.
 */
export function describeSteps(workout: Workout, maxLength = 600): string {
  const text = workout.steps.map(describeStep).join(", ");
  return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1).trimEnd()}…`;
}
