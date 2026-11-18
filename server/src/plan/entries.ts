import { isIsoDate } from "@overdrive/contracts";
import type { IntervalsEvent } from "../intervals/client.ts";
import { isCyclingType, plannedIdFor } from "../intervals/ids.ts";
import { convertIntervalsEvent } from "../intervals/workout-convert.ts";
import type { PlanEntry } from "./store.ts";

/** Where the planned workouts come from. */
export const PLAN_SOURCE = "intervals.icu";

export type EventReading =
  /** Not a cycling workout (a note, a run, a race): not part of the plan. */
  | { kind: "ignored" }
  /** The event's day cannot be read. */
  | { kind: "no-date" }
  /** The event as a cache entry: converted, or kept with the reason it cannot be played. */
  | { kind: "entry"; entry: PlanEntry };

/**
 * Reads one calendar event of intervals.icu as a plan entry. The rules are one place's: the
 * refresher uses them for the cache and the MCP tools for ranges the cache does not hold.
 */
export function readPlanEvent(event: IntervalsEvent): EventReading {
  if (event.category !== undefined && event.category !== null && event.category !== "WORKOUT") {
    return { kind: "ignored" };
  }
  if (!isCyclingType(event.type)) return { kind: "ignored" };
  const date = event.start_date_local.slice(0, 10);
  if (!isIsoDate(date)) return { kind: "no-date" };
  const id = plannedIdFor(event.id);
  const name = (event.name ?? "").trim() || "Workout";
  const converted = convertIntervalsEvent(event, id);
  return {
    kind: "entry",
    entry: {
      id,
      source: PLAN_SOURCE,
      externalId: String(event.id),
      date,
      name,
      workout: converted.ok ? converted.workout : null,
      unsupportedReason: converted.ok ? null : converted.reason,
    },
  };
}
