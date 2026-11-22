import {
  addDaysToIsoDate,
  EFFORT_WEEKS_NEEDED,
  type EffortActivity,
  type EffortDay,
  type EffortKind,
  type EffortResponse,
  type EffortVerdict,
  type EffortWeek,
} from "@overdrive/contracts";
import { sampleStats } from "../wellness/baseline.ts";
import { mondayOf } from "../zoned.ts";

/**
 * Weekly effort (spec §6.3, ADR 0042) from the activities' own training loads: pure functions, no database and no clock.
 *
 * Effort is the training load the service already computes, not a second formula: the activities arrive with their loads (a ride's
 * power-based score, a watch workout's or an outdoor ride's heart-rate score) and this only sums them, per local day and per local
 * week (Monday to Sunday). Every sum is of the rounded figures below it, so what the phone adds up is what is said here.
 *
 * THE USUAL RANGE of a week is the mean minus and plus one sample standard deviation of the loads of the {@link EFFORT_WEEKS_NEEDED}
 * (8) complete weeks before it (the low edge held at 0, as a load cannot be negative), the way the wellness usual range is made. The
 * weeks count from the rider's first week with any activity, so the weeks before the rider started are not zeros that drag the
 * range down, while a week off after that counts as the 0 it was. A week with fewer than 8 such weeks before it has no range: it is
 * *learning*. A standard deviation moves more than a quartile with one odd week; it is what the design asks for and what the
 * wellness figures already use, so the two usual ranges mean the same thing.
 *
 * THE VERDICT compares a week's load with that week's own range: `below` under the low edge, `above` over the high edge, `within`
 * between them (the edges included). For the week in progress it is the load so far against the whole range, never scaled to the
 * weekday: `above` and `within` can only stay or rise as the week goes on, and `below` there means "below so far". `learning`
 * while there is no range; `none` for the current week when nothing at all has been recorded in it yet.
 */

/** An activity as the weeks take it: where it falls, what it was and its load. */
export interface EffortInput {
  id: string;
  kind: EffortKind;
  sport: string | null;
  name: string;
  /** The rider's local day it started on. */
  date: string;
  startEpochMillis: number;
  /** One decimal; null when it could not be worked out. */
  load: number | null;
  /** The load comes from heart rate. */
  approximate: boolean;
}

const round1 = (value: number): number => Math.round(value * 10) / 10;
const round3 = (value: number): number => Math.round(value * 1000) / 1000;

export interface EffortInputs {
  activities: readonly EffortInput[];
  /** Today, the rider's local day. */
  today: string;
  /** How many weeks to show, 1 to 52. */
  weeks: number;
  generatedAt: string;
}

/** The first Monday the answer needs data from: the shown weeks, and the 8 complete weeks before the oldest of them. */
export function firstMondayNeeded(today: string, weeks: number): string {
  return addDaysToIsoDate(mondayOf(today), -7 * (weeks - 1 + EFFORT_WEEKS_NEEDED));
}

/** The usual range from the loads of the 8 weeks before a week. */
export function usualRange(loads: readonly number[]): { low: number; high: number } {
  const { mean, sd } = sampleStats(loads);
  return { low: Math.max(0, round1(mean - sd)), high: round1(mean + sd) };
}

/** The verdict for a week with a usual range (see the comment of this file). */
export function verdictOf(
  load: number,
  range: { low: number; high: number } | null,
  nothingYet: boolean,
): EffortVerdict {
  if (range === null) return "learning";
  if (nothingYet) return "none";
  return load < range.low ? "below" : load > range.high ? "above" : "within";
}

export function buildEffort(input: EffortInputs): EffortResponse {
  const { today } = input;
  const thisMonday = mondayOf(today);
  const first = firstMondayNeeded(today, input.weeks);

  const byWeek = new Map<string, EffortInput[]>();
  const ordered = [...input.activities].sort((a, b) => a.startEpochMillis - b.startEpochMillis);
  for (const activity of ordered) {
    if (activity.date < first || activity.date > today) continue;
    const monday = mondayOf(activity.date);
    const list = byWeek.get(monday) ?? [];
    list.push(activity);
    byWeek.set(monday, list);
  }

  const weeks: EffortWeek[] = [];
  for (let monday = first; monday <= thisMonday; monday = addDaysToIsoDate(monday, 7)) {
    const inputs = byWeek.get(monday) ?? [];
    const days: EffortDay[] = [];
    for (let index = 0; index < 7; index++) {
      const date = addDaysToIsoDate(monday, index);
      const load = inputs
        .filter((a) => a.date === date)
        .reduce((sum, a) => sum + (a.load === null ? 0 : round1(a.load)), 0);
      days.push({ date, load: round1(load) });
    }
    const load = round1(days.reduce((sum, day) => sum + day.load, 0));
    const activities: EffortActivity[] = inputs.map((a) => ({
      id: a.id,
      kind: a.kind,
      sport: a.sport,
      name: a.name,
      date: a.date,
      load: a.load === null ? null : round1(a.load),
      approximate: a.approximate,
      share: a.load === null ? null : round3(load === 0 ? 0 : round1(a.load) / load),
    }));
    weeks.push({
      weekStart: monday,
      load,
      approximate: inputs.some((a) => a.approximate),
      current: monday === thisMonday,
      usualLow: null,
      usualHigh: null,
      weeksLogged: 0,
      verdict: "learning",
      days,
      activities,
    });
  }

  // Complete weeks of training before each week, from the first week with any activity; the usual range needs 8.
  const firstActive = weeks.findIndex((week) => week.activities.length > 0);
  for (const [index, week] of weeks.entries()) {
    week.weeksLogged =
      firstActive < 0 ? 0 : Math.min(EFFORT_WEEKS_NEEDED, Math.max(0, index - firstActive));
    let range: { low: number; high: number } | null = null;
    if (week.weeksLogged >= EFFORT_WEEKS_NEEDED) {
      range = usualRange(weeks.slice(index - EFFORT_WEEKS_NEEDED, index).map((w) => w.load));
      week.usualLow = range.low;
      week.usualHigh = range.high;
    }
    week.verdict = verdictOf(week.load, range, week.current && week.activities.length === 0);
  }

  const shown = weeks.slice(-input.weeks);
  return {
    generatedAt: input.generatedAt,
    weeksNeeded: EFFORT_WEEKS_NEEDED,
    weeksLogged: (shown[shown.length - 1] as EffortWeek).weeksLogged,
    weeks: shown,
  };
}
