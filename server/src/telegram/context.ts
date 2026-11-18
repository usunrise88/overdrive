import { addDaysToIsoDate } from "@overdrive/contracts";
import type { IntervalsClient } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import type { OutdoorService } from "../outdoor/service.ts";
import type { PlanRefresher } from "../plan/refresher.ts";
import type { PlanEntry, PlanStore } from "../plan/store.ts";
import { workoutDurationSeconds } from "../plan/workout-summary.ts";
import { localDate } from "../time.ts";
import { type Clock, zonedInstant } from "../zoned.ts";
import type { PlanHistory } from "./history.ts";
import type { DayEntry, PlannedView } from "./messages.ts";
import { pairRides, type RideQueries } from "./rides.ts";

/** What the commands and the scheduled messages read and write: the plan, the rides and the clock. */
export interface BotContext {
  plan: PlanStore;
  history: PlanHistory;
  rides: RideQueries;
  /** null when no plan source is set up: the plan cannot be changed. */
  intervals: IntervalsClient | null;
  /** null when no plan source is set up. */
  refresher: PlanRefresher | null;
  /** Imports a FIT file sent to the bot as an outdoor ride (spec §11.8); null where the service has no such import (some tests). */
  outdoor: OutdoorService | null;
  timezone: string;
  /** The time of day a workout starts (`GCAL_WORKOUT_START`). */
  workoutStart: Clock;
  now: () => Date;
  log: Logger;
}

export function plannedView(entry: PlanEntry): PlannedView {
  return {
    id: entry.id,
    date: entry.date,
    name: entry.name,
    workout: entry.workout,
    plannedSeconds: entry.workout === null ? null : workoutDurationSeconds(entry.workout),
  };
}

export function todayOf(ctx: BotContext): string {
  return localDate(ctx.now(), ctx.timezone);
}

const MIDNIGHT: Clock = { hour: 0, minute: 0 };

/** The planned workouts from `from` to `to` (both included) grouped by day, each with the ride that completed it. */
export async function loadDays(
  ctx: BotContext,
  from: string,
  to: string,
): Promise<Map<string, DayEntry[]>> {
  const entries = await ctx.plan.entries({ from, to });
  const rides = await ctx.rides.between(
    zonedInstant(from, MIDNIGHT, ctx.timezone).getTime(),
    zonedInstant(addDaysToIsoDate(to, 1), MIDNIGHT, ctx.timezone).getTime(),
  );
  const paired = pairRides(entries, rides, ctx.timezone);
  const days = new Map<string, DayEntry[]>();
  for (const entry of entries) {
    const list = days.get(entry.date) ?? [];
    list.push({ ...plannedView(entry), ride: paired.get(entry.id) ?? null });
    days.set(entry.date, list);
  }
  return days;
}

/** The workouts of the next day that has any left to ride, from today on; empty when there are none. */
export async function nextOpenWorkouts(ctx: BotContext, horizonDays = 14): Promise<DayEntry[]> {
  const today = todayOf(ctx);
  const days = await loadDays(ctx, today, addDaysToIsoDate(today, horizonDays));
  for (const date of [...days.keys()].sort()) {
    const open = (days.get(date) ?? []).filter((entry) => entry.ride === null);
    if (open.length > 0) return open;
  }
  return [];
}

/** One planned workout by id, converted or not; null when the plan no longer has it. */
export async function findEntry(ctx: BotContext, id: string): Promise<PlanEntry | null> {
  const entries = await ctx.plan.entries({ from: "2000-01-01", to: "2999-12-31" });
  return entries.find((entry) => entry.id === id) ?? null;
}
