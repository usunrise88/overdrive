import type { Workout } from "@overdrive/contracts";
import { addDaysToIsoDate } from "@overdrive/contracts";
import { describeSteps, formatDuration } from "../plan/workout-summary.ts";
import { type Clock, dayAndMonth, formatClock, shortDate } from "../zoned.ts";
import type { RideBrief } from "./rides.ts";

/**
 * Every sentence the bot says, built here from numbers the service computed: no model writes any of it
 * (an "insight" in words is Phase 3). Plain text, plain words, English.
 */

/** A planned workout as the messages need it. */
export interface PlannedView {
  id: string;
  /** `YYYY-MM-DD`. */
  date: string;
  name: string;
  /** null: the tablet cannot play it. */
  workout: Workout | null;
  /** The planned length; null when there is no native workout. */
  plannedSeconds: number | null;
}

const round = (value: number): number => Math.round(value);

function dayWord(date: string, today: string): string {
  if (date === today) return "Today";
  if (date === addDaysToIsoDate(today, 1)) return "Tomorrow";
  return shortDate(date);
}

function lengthOf(view: Pick<PlannedView, "plannedSeconds">): string {
  return view.plannedSeconds === null ? "" : `, ${formatDuration(view.plannedSeconds)}`;
}

function stepsLine(view: PlannedView): string {
  return view.workout === null
    ? "The tablet cannot play this workout."
    : `${describeSteps(view.workout, 400)}.`;
}

// ---- Reminders ---------------------------------------------------------------------------------

/** "Tomorrow at 07:00: Sweet spot 3x10, 1 h 02 min." and the steps. */
export function eveningReminderText(view: PlannedView, start: Clock, today: string): string {
  return `${dayWord(view.date, today)} at ${formatClock(start)}: ${view.name}${lengthOf(view)}.\n${stepsLine(view)}`;
}

/** "Starts at 07:00, in 58 min: Sweet spot 3x10, 1 h 02 min." and the steps. */
export function leadReminderText(view: PlannedView, start: Clock, minutesLeft: number): string {
  const wait = formatDuration(Math.max(0, minutesLeft) * 60);
  return `Starts at ${formatClock(start)}, in ${wait}: ${view.name}${lengthOf(view)}.\n${stepsLine(view)}`;
}

// ---- After a ride ------------------------------------------------------------------------------

/** The ride's own numbers, and how it compared with the plan when it is linked to one. */
export function rideSummaryText(
  ride: RideBrief,
  planned: { name: string; plannedSeconds: number | null } | null,
): string {
  const lines = [`Ride saved: ${formatDuration(ride.durationSeconds)}.`];
  if (ride.averagePower !== null) {
    lines.push(
      ride.normalizedPower === null
        ? `Power: ${round(ride.averagePower)} W average.`
        : `Power: ${round(ride.averagePower)} W average, ${round(ride.normalizedPower)} W normalised.`,
    );
  }
  if (ride.averageHeartRate !== null) {
    lines.push(
      ride.maxHeartRate === null
        ? `Heart rate: ${round(ride.averageHeartRate)} bpm average.`
        : `Heart rate: ${round(ride.averageHeartRate)} bpm average, ${round(ride.maxHeartRate)} bpm max.`,
    );
  }
  if (ride.trainingStressScore !== null) lines.push(`Load: ${round(ride.trainingStressScore)}.`);
  if (planned !== null) {
    if (planned.plannedSeconds === null) {
      lines.push(`Plan: ${planned.name}.`);
    } else {
      const diff = ride.durationSeconds - planned.plannedSeconds;
      const against =
        Math.abs(diff) < 60
          ? ""
          : diff < 0
            ? ` (${formatDuration(-diff)} short)`
            : ` (${formatDuration(diff)} over)`;
      lines.push(
        `Plan: ${planned.name}. Planned ${formatDuration(planned.plannedSeconds)}, ridden ${formatDuration(ride.durationSeconds)}${against}.`,
      );
    }
  }
  return lines.join("\n");
}

// ---- The week ----------------------------------------------------------------------------------

export interface WeekTotals {
  rides: number;
  movingSeconds: number;
  load: number;
}

export interface WeekStats {
  /** The Monday of the week. */
  from: string;
  /** The day the summary is for (the Sunday, by default). */
  to: string;
  planned: number;
  done: number;
  skipped: number;
  missed: { name: string; date: string }[];
  now: WeekTotals;
  /** The previous week up to the same weekday. */
  previous: WeekTotals;
}

export function weeklySummaryText(stats: WeekStats): string {
  const lines = [`Week ${dayAndMonth(stats.from)} to ${dayAndMonth(stats.to)}`];
  if (stats.planned + stats.skipped === 0) {
    lines.push("Sessions: nothing was planned.");
  } else {
    const skipped = stats.skipped > 0 ? `, ${stats.skipped} skipped` : "";
    lines.push(`Sessions: ${stats.done} of ${stats.planned} planned done${skipped}.`);
  }
  if (stats.missed.length > 0) {
    lines.push(
      `Missed: ${stats.missed.map((m) => `${m.name} (${shortDate(m.date)})`).join(", ")}.`,
    );
  }
  lines.push(
    stats.now.rides === 0
      ? "Rides: none."
      : `Rides: ${stats.now.rides}, ${formatDuration(stats.now.movingSeconds)}.`,
  );
  const load = round(stats.now.load);
  const before = round(stats.previous.load);
  if (stats.previous.rides === 0) {
    lines.push(`Load: ${load} (no rides the week before).`);
  } else if (load === before) {
    lines.push(`Load: ${load}, the same as the week before.`);
  } else {
    const way = load > before ? "up" : "down";
    lines.push(`Load: ${load}, ${way} ${Math.abs(load - before)} on the week before (${before}).`);
  }
  return lines.join("\n");
}

// ---- Commands ----------------------------------------------------------------------------------

export interface DayEntry extends PlannedView {
  /** The ride that completed it, if any. */
  ride: RideBrief | null;
}

function doneNote(entry: DayEntry): string {
  return entry.ride === null ? "" : `. Done: ridden ${formatDuration(entry.ride.durationSeconds)}`;
}

export function todayText(entries: DayEntry[], today: string, start: Clock): string {
  if (entries.length === 0) return "Nothing is planned for today.";
  const lines = [`Today, ${shortDate(today)}, from ${formatClock(start)}`];
  for (const entry of entries) {
    lines.push(`- ${entry.name}${lengthOf(entry)}${doneNote(entry)}.`);
    lines.push(`  ${stepsLine(entry)}`);
  }
  return lines.join("\n");
}

export function weekText(days: { date: string; entries: DayEntry[] }[]): string {
  const lines = ["The next 7 days"];
  for (const day of days) {
    const what =
      day.entries.length === 0
        ? "nothing planned"
        : day.entries
            .map(
              (e) =>
                `${e.name}${e.plannedSeconds === null ? "" : ` (${formatDuration(e.plannedSeconds)})`}${e.ride === null ? "" : ", done"}`,
            )
            .join("; ");
    lines.push(`${shortDate(day.date)}: ${what}`);
  }
  return lines.join("\n");
}

export const HELP_TEXT = [
  "Overdrive",
  "/today: today's workout",
  "/week: the next 7 days",
  "/skip: skip the next workout (asks first)",
  "/move: move the next workout to another day",
  "/help: this list",
  "",
  "I also send a reminder the evening before and shortly before a workout, a summary after each ride, and a summary of the week.",
].join("\n");

export const NOT_CONNECTED =
  "The plan is not connected: INTERVALS_API_KEY is not set on the server, so there are no workouts to show or change.";

export const READ_ONLY = "I cannot change the plan: INTERVALS_API_KEY is not set on the server.";

export const UNKNOWN_COMMAND = "I do not know that one. /help lists what I can do.";
