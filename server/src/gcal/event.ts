import type { PlannedWorkout } from "@overdrive/contracts";
import { canonicalJson, sha256Hex } from "../hash.ts";
import { describeSteps, formatDuration, workoutDurationSeconds } from "../plan/workout-summary.ts";
import { type Clock, zonedInstant } from "../zoned.ts";
import { type CalendarEventBody, MANAGED_PROPERTY, PLANNED_ID_PROPERTY } from "./client.ts";

/** The shortest event we write, so a workout of a few seconds still shows. */
const MIN_EVENT_SECONDS = 5 * 60;

export interface DesiredEvent {
  /** `icu-123`. */
  plannedId: string;
  /** `YYYY-MM-DD`, the rider's local day. */
  date: string;
  body: CalendarEventBody;
  /** What "changed" means: the same text means Google already says this. */
  sha: string;
}

/**
 * The Google Calendar id of the event that shows planned workout `plannedId`. Google lets the caller
 * choose the id (5 to 1024 characters from `a-v` and `0-9`), which makes a create idempotent: sending
 * it twice is answered `409` the second time instead of making two events.
 */
export function googleEventId(plannedId: string): string {
  return `ovd${sha256Hex(plannedId).slice(0, 40)}`;
}

/**
 * What the event for `planned` says: the workout's name as the title, the steps in plain words as the
 * description, starting at `start` (a time of day in `timeZone`) on its date for as long as the
 * workout lasts. Computed by code from the native workout; the reminders are left to the calendar's
 * defaults.
 */
export function desiredEvent(
  planned: PlannedWorkout,
  timeZone: string,
  start: Clock,
): DesiredEvent {
  const seconds = Math.max(MIN_EVENT_SECONDS, workoutDurationSeconds(planned.workout));
  const startsAt = zonedInstant(planned.date, start, timeZone);
  const endsAt = new Date(startsAt.getTime() + seconds * 1000);
  const total = formatDuration(workoutDurationSeconds(planned.workout));
  const steps = describeSteps(planned.workout);
  const body: CalendarEventBody = {
    summary: planned.name,
    description: steps === "" ? `Total ${total}.` : `${steps}. Total ${total}.`,
    start: { dateTime: startsAt.toISOString(), timeZone },
    end: { dateTime: endsAt.toISOString(), timeZone },
    status: "confirmed",
    extendedProperties: {
      private: { [MANAGED_PROPERTY]: "1", [PLANNED_ID_PROPERTY]: planned.id },
    },
  };
  return {
    plannedId: planned.id,
    date: planned.date,
    body,
    sha: sha256Hex(canonicalJson(body)),
  };
}
