import { addDaysToIsoDate, isoDateToEpochDay } from "@overdrive/contracts";

/**
 * Wall-clock arithmetic in the rider's time zone (`TIMEZONE`), for the things that happen "at 07:00"
 * or "on Sunday": the calendar's start time, the reminders, the weekly summary. `time.ts` only knows
 * which date an instant falls on.
 */

export interface Clock {
  /** 0 to 23. */
  hour: number;
  /** 0 to 59. */
  minute: number;
}

export interface LocalParts extends Clock {
  /** `YYYY-MM-DD`. */
  date: string;
  /** 1 (Monday) to 7 (Sunday). */
  weekday: number;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let found = formatters.get(timeZone);
  if (found === undefined) {
    found = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      weekday: "short",
    });
    formatters.set(timeZone, found);
  }
  return found;
}

const WEEKDAY_NUMBER: Record<string, number> = {
  Mon: 1,
  Tue: 2,
  Wed: 3,
  Thu: 4,
  Fri: 5,
  Sat: 6,
  Sun: 7,
};

function fields(instant: number, timeZone: string): Record<string, string> {
  return Object.fromEntries(
    formatter(timeZone)
      .formatToParts(instant)
      .map((part) => [part.type, part.value]),
  );
}

/** The date, time of day and weekday that `instant` is on the wall clocks of `timeZone`. */
export function localParts(instant: Date | number, timeZone: string): LocalParts {
  const p = fields(new Date(instant).getTime(), timeZone);
  return {
    date: `${p.year}-${p.month}-${p.day}`,
    hour: Number(p.hour),
    minute: Number(p.minute),
    weekday: WEEKDAY_NUMBER[p.weekday ?? ""] ?? 1,
  };
}

/** How far the wall clocks of `timeZone` are ahead of UTC at `instant`, in milliseconds. */
function offsetMs(instant: number, timeZone: string): number {
  const p = fields(instant, timeZone);
  const wall = Date.UTC(
    Number(p.year),
    Number(p.month) - 1,
    Number(p.day),
    Number(p.hour),
    Number(p.minute),
    Number(p.second),
  );
  return wall - Math.floor(instant / 1000) * 1000;
}

/**
 * The instant at which the wall clocks of `timeZone` read `date` (`YYYY-MM-DD`) at `time`. A time
 * that does not exist (the hour skipped when the clocks go forward) or exists twice (when they go
 * back) maps to one of the two neighbouring instants.
 */
export function zonedInstant(date: string, time: Clock, timeZone: string): Date {
  const [year, month, day] = date.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined) {
    throw new Error(`Not a calendar date: ${date}`);
  }
  const wall = Date.UTC(year, month - 1, day, time.hour, time.minute);
  const first = wall - offsetMs(wall, timeZone);
  return new Date(wall - offsetMs(first, timeZone));
}

const CLOCK = /^([01]\d|2[0-3]):([0-5]\d)$/;

/** `07:00` as a time of day, or null if it is not one. */
export function parseClock(text: string): Clock | null {
  const match = CLOCK.exec(text);
  return match === null ? null : { hour: Number(match[1]), minute: Number(match[2]) };
}

export function formatClock(time: Clock): string {
  return `${String(time.hour).padStart(2, "0")}:${String(time.minute).padStart(2, "0")}`;
}

/** 1 (Monday) to 7 (Sunday) for a calendar date. */
export function isoWeekday(date: string): number {
  const day = isoDateToEpochDay(date);
  if (day === null) throw new Error(`Not a calendar date: ${date}`);
  // 1970-01-01 was a Thursday.
  return ((((day + 3) % 7) + 7) % 7) + 1;
}

/** The Monday of the week that contains `date`. */
export function mondayOf(date: string): string {
  return addDaysToIsoDate(date, 1 - isoWeekday(date));
}

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;
const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
] as const;

/** `Wed` for a date; English whatever the host's locale, so the messages read the same everywhere. */
export function weekdayName(date: string): string {
  return WEEKDAYS[isoWeekday(date) - 1] ?? "";
}

/** `18 Nov` for `2026-11-18`. */
export function dayAndMonth(date: string): string {
  const month = Number(date.slice(5, 7));
  return `${Number(date.slice(8, 10))} ${MONTHS[month - 1] ?? ""}`;
}

/** `Wed 18 Nov`. */
export function shortDate(date: string): string {
  return `${weekdayName(date)} ${dayAndMonth(date)}`;
}
