import {
  addDaysToIsoDate,
  EFFORT_USUAL_WEEKS,
  type EffortActivity,
  type EffortDay,
  type EffortKind,
  type EffortResponse,
  type EffortWeek,
} from "@overdrive/contracts";
import { isoWeekday, mondayOf } from "../zoned.ts";
import { type CompletedWeek, usualOf, verdictFor, weeksBehindUsual } from "./usual.ts";

/**
 * Weekly effort (spec §6.3, ADR 0042) from the activities' own training loads: pure functions, no database and no clock.
 *
 * Effort is the training load the service already computes, not a second formula: the activities arrive with their loads (a ride's
 * power-based score, a watch workout's or an outdoor ride's heart-rate score) and this only sums them, per local day and per local
 * week (Monday to Sunday). Every sum is of the rounded figures below it, so what the phone adds up is what is said here.
 */

export const EFFORT_NOTE =
  "Effort is your training load: from power for rides with power, from heart rate for watch workouts and outdoor rides, all on one scale where 100 is about an hour at threshold. Your usual range is the middle half of your last eight completed weeks.";

/** An activity as the weeks take it: where it falls, what it was and its load. */
export interface EffortInput {
  id: string;
  kind: EffortKind;
  name: string;
  /** The rider's local day it started on. */
  date: string;
  startEpochMillis: number;
  /** One decimal. A load that could not be worked out is 0 and `approximate`. */
  load: number;
  approximate: boolean;
  durationSeconds: number;
}

const round1 = (value: number): number => Math.round(value * 10) / 10;

export interface EffortInputs {
  activities: readonly EffortInput[];
  /** Today, the rider's local day. */
  today: string;
  /** How many weeks to show, 1 to 52. */
  weeks: number;
  generatedAt: string;
}

/** The first Monday the answer needs data from: the shown weeks, and the eight completed weeks before the current one. */
export function firstMondayNeeded(today: string, weeks: number): string {
  const weeksBack = Math.max(weeks - 1, EFFORT_USUAL_WEEKS);
  return addDaysToIsoDate(mondayOf(today), -7 * weeksBack);
}

function weekOf(
  weekStart: string,
  byDate: ReadonlyMap<string, EffortInput[]>,
  lastDay: number,
  inProgress: boolean,
): EffortWeek {
  const days: EffortDay[] = [];
  let load = 0;
  let count = 0;
  let seconds = 0;
  let approximate = false;
  for (let index = 0; index <= lastDay; index++) {
    const date = addDaysToIsoDate(weekStart, index);
    const activities: EffortActivity[] = (byDate.get(date) ?? []).map((a) => ({
      id: a.id,
      kind: a.kind,
      name: a.name,
      load: round1(a.load),
      approximate: a.approximate,
    }));
    const dayLoad = round1(activities.reduce((sum, a) => sum + a.load, 0));
    days.push({ date, load: dayLoad, activities });
    load += dayLoad;
    count += activities.length;
    for (const a of byDate.get(date) ?? []) {
      seconds += a.durationSeconds;
      if (a.approximate) approximate = true;
    }
  }
  return {
    weekStart,
    load: round1(load),
    activityCount: count,
    durationSeconds: Math.round(seconds),
    approximate,
    inProgress,
    days,
  };
}

export function buildEffort(input: EffortInputs): EffortResponse {
  const { today } = input;
  const thisMonday = mondayOf(today);
  const first = firstMondayNeeded(today, input.weeks);
  const todayIndex = isoWeekday(today) - 1;

  const byDate = new Map<string, EffortInput[]>();
  const ordered = [...input.activities].sort((a, b) => a.startEpochMillis - b.startEpochMillis);
  for (const activity of ordered) {
    if (activity.date < first || activity.date > today) continue;
    const list = byDate.get(activity.date) ?? [];
    list.push(activity);
    byDate.set(activity.date, list);
  }

  const all: EffortWeek[] = [];
  for (let monday = first; monday <= thisMonday; monday = addDaysToIsoDate(monday, 7)) {
    const current = monday === thisMonday;
    all.push(weekOf(monday, byDate, current ? todayIndex : 6, current));
  }

  const current = all[all.length - 1] as EffortWeek;
  const completed: CompletedWeek[] = all.slice(0, -1).map((week) => ({
    load: week.load,
    activityCount: week.activityCount,
    dayLoads: week.days.map((day) => day.load),
  }));
  const behind = weeksBehindUsual(completed);
  const usual = usualOf(behind);
  const verdict = verdictFor(behind, current.load, todayIndex + 1);

  return {
    generatedAt: input.generatedAt,
    weeks: all.slice(-input.weeks),
    usual,
    thisWeek: { load: current.load, ...verdict },
    note: EFFORT_NOTE,
  };
}
