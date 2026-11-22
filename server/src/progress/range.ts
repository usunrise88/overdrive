import { isIsoDate, PROGRESS_DEFAULT_MONTHS, PROGRESS_MAX_RANGE_YEARS } from "@overdrive/contracts";

/**
 * The range of `GET /v1/progress` (ADR 0043): `from` and `to` are local days, both included; without them the last six months up to
 * today; at most five years. A pure function: the route and the MCP tool both ask it, and each says what it found in its own way.
 */

/** The calendar date `months` after (before, when negative) `date`, clamped to the last day of a shorter month. */
export function addMonthsToIsoDate(date: string, months: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  const day = Number(date.slice(8, 10));
  const index = year * 12 + (month - 1) + months;
  const targetYear = Math.floor(index / 12);
  const targetMonth = (index % 12) + 1;
  // Day 0 of the next month is the last day of this one.
  const lastDay = new Date(Date.UTC(targetYear, targetMonth, 0)).getUTCDate();
  const prefix = `${String(targetYear).padStart(4, "0")}-${String(targetMonth).padStart(2, "0")}`;
  return `${prefix}-${String(Math.min(day, lastDay)).padStart(2, "0")}`;
}

export type ProgressRange =
  | { ok: true; from: string; to: string }
  | { ok: false; parameter: "from" | "to" | "range"; message: string };

export function resolveRange(
  from: string | undefined,
  to: string | undefined,
  today: string,
): ProgressRange {
  if (to !== undefined && !isIsoDate(to)) {
    return { ok: false, parameter: "to", message: "to must be a calendar date, YYYY-MM-DD" };
  }
  if (from !== undefined && !isIsoDate(from)) {
    return { ok: false, parameter: "from", message: "from must be a calendar date, YYYY-MM-DD" };
  }
  const end = to ?? today;
  const start = from ?? addMonthsToIsoDate(end, -PROGRESS_DEFAULT_MONTHS);
  if (start > today) {
    return { ok: false, parameter: "from", message: "from must not be after today" };
  }
  if (start > end) {
    return { ok: false, parameter: "range", message: "from must not be after to" };
  }
  if (start < addMonthsToIsoDate(end, -12 * PROGRESS_MAX_RANGE_YEARS)) {
    return {
      ok: false,
      parameter: "range",
      message: `the range spans more than ${PROGRESS_MAX_RANGE_YEARS} years`,
    };
  }
  return { ok: true, from: start, to: end };
}
