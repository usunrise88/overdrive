import {
  EFFORT_FIRST_VERDICT_WEEKDAY,
  EFFORT_MIN_USUAL_WEEKS,
  EFFORT_USUAL_WEEKS,
  type EffortBand,
  type EffortUsual,
  type EffortVerdict,
} from "@overdrive/contracts";

/**
 * The rider's usual week and how this week compares with it (spec §6.3, ADR 0042): pure functions on weekly loads.
 *
 * THE RULE. The usual range comes from the previous completed weeks, at most the last {@link EFFORT_USUAL_WEEKS} (8), counted from
 * the first of them that had any activity, so the weeks before the rider started do not drag it down while a rest week after that
 * counts as the 0 it was. It exists with at least {@link EFFORT_MIN_USUAL_WEEKS} (4) such weeks. It is the median and the
 * interquartile band: the lower and the upper quartile, with linear interpolation between the sorted values. Quartiles are
 * robust: one big week or one week off moves them little, which a mean and a standard deviation would not be.
 *
 * THIS WEEK is compared with what the same weeks had reached **by the same weekday**: the sum of their days from Monday up to
 * today's weekday, taken as a band the same way. The week in progress is never compared with a whole week, which would call every
 * Wednesday "below". From Wednesday on ({@link EFFORT_FIRST_VERDICT_WEEKDAY}) the verdict is `below` under the lower edge, `above`
 * over the upper edge and `within` between them, the edges themselves included; on Monday and Tuesday, and with no usual range, it
 * is `unknown`.
 */

const round1 = (value: number): number => Math.round(value * 10) / 10;

/** The p-quantile (0 to 1) of numbers, by linear interpolation between the sorted values (the "type 7" rule of most software). */
export function quantile(values: readonly number[], p: number): number {
  if (values.length === 0) throw new Error("no values");
  const sorted = [...values].sort((a, b) => a - b);
  const position = (sorted.length - 1) * p;
  const below = Math.floor(position);
  const above = Math.ceil(position);
  const low = sorted[below] as number;
  const high = sorted[above] as number;
  return low + (high - low) * (position - below);
}

/** The interquartile band of loads, to one decimal. */
export function bandOf(values: readonly number[]): EffortBand {
  return { low: round1(quantile(values, 0.25)), high: round1(quantile(values, 0.75)) };
}

/** One completed week as the rule needs it. */
export interface CompletedWeek {
  load: number;
  activityCount: number;
  /** The load of each day from Monday, seven of them. */
  dayLoads: readonly number[];
}

/**
 * The completed weeks the usual range is taken from: the last 8 of `completed` (oldest first), from the first one with any activity;
 * possibly fewer than the 4 that are needed.
 */
export function weeksBehindUsual(completed: readonly CompletedWeek[]): CompletedWeek[] {
  const recent = completed.slice(-EFFORT_USUAL_WEEKS);
  const first = recent.findIndex((week) => week.activityCount > 0);
  return first < 0 ? [] : recent.slice(first);
}

/** The usual range of these weeks (see {@link weeksBehindUsual}), or null with fewer than 4. */
export function usualOf(weeks: readonly CompletedWeek[]): EffortUsual | null {
  if (weeks.length < EFFORT_MIN_USUAL_WEEKS) return null;
  const loads = weeks.map((week) => week.load);
  const band = bandOf(loads);
  return { ...band, median: round1(quantile(loads, 0.5)), basedOnWeeks: weeks.length };
}

export interface ThisWeekVerdict {
  verdict: EffortVerdict;
  usualSoFar: EffortBand | null;
  verdictText: string;
}

const WEEKDAYS = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
] as const;

/** A load as the sentences write it: whole numbers plain, otherwise one decimal. */
const say = (value: number): string => (Number.isInteger(value) ? String(value) : value.toFixed(1));

/**
 * The verdict for the week in progress.
 * @param weeks the completed weeks behind the usual range, as {@link weeksBehindUsual} gives them
 * @param load this week's load so far
 * @param weekday today's weekday, 1 (Monday) to 7 (Sunday)
 */
export function verdictFor(
  weeks: readonly CompletedWeek[],
  load: number,
  weekday: number,
): ThisWeekVerdict {
  if (weeks.length < EFFORT_MIN_USUAL_WEEKS) {
    return {
      verdict: "unknown",
      usualSoFar: null,
      verdictText: `Not enough weeks yet to say what is usual: it needs ${EFFORT_MIN_USUAL_WEEKS} completed weeks of training, and there ${weeks.length === 1 ? "is" : "are"} ${weeks.length} so far.`,
    };
  }
  if (weekday < EFFORT_FIRST_VERDICT_WEEKDAY) {
    return {
      verdict: "unknown",
      usualSoFar: null,
      verdictText: `Too early in the week to compare: ${say(load)} so far. A comparison with your usual starts on ${WEEKDAYS[EFFORT_FIRST_VERDICT_WEEKDAY - 1]}.`,
    };
  }
  const soFar = weeks.map((week) =>
    round1(week.dayLoads.slice(0, weekday).reduce((sum, day) => sum + day, 0)),
  );
  const band = bandOf(soFar);
  const verdict: EffortVerdict = load < band.low ? "below" : load > band.high ? "above" : "within";
  const start = { below: "Below", within: "Within", above: "Above" }[verdict];
  return {
    verdict,
    usualSoFar: band,
    verdictText: `${start} your usual for this point of the week: ${say(load)} so far, usually ${say(band.low)} to ${say(band.high)} by ${WEEKDAYS[weekday - 1]}.`,
  };
}
