import { addDaysToIsoDate, isoDateToEpochDay } from "@overdrive/contracts";

/**
 * Calendar-date arithmetic for the baselines, which walk back 42 days from every day of a range for every metric: the same few
 * hundred dates are asked for over and over, so both directions are remembered.
 */

const epochOf = new Map<string, number>();
const isoOf = new Map<number, string>();

/** Days since 1970-01-01 of a calendar date; throws on one that is not real. */
export function epochDay(date: string): number {
  let day = epochOf.get(date);
  if (day === undefined) {
    const parsed = isoDateToEpochDay(date);
    if (parsed === null) throw new Error(`Not a calendar date: ${date}`);
    day = parsed;
    epochOf.set(date, day);
  }
  return day;
}

/** The calendar date `days` days after day number `day`. */
export function dateOfEpochDay(day: number): string {
  let date = isoOf.get(day);
  if (date === undefined) {
    date = addDaysToIsoDate("1970-01-01", day);
    isoOf.set(day, date);
  }
  return date;
}

/** `date` moved by `days` (negative: back). */
export function shiftDate(date: string, days: number): string {
  return dateOfEpochDay(epochDay(date) + days);
}
