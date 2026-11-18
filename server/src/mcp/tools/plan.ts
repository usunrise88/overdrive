import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  addDaysToIsoDate,
  isIsoDate,
  MAX_WORKOUTS_RANGE_DAYS,
  rangeDays,
  validateWorkout,
  type Workout,
} from "@overdrive/contracts";
import { z } from "zod";
import { canonicalJson } from "../../hash.ts";
import type {
  IntervalsClient,
  IntervalsEvent,
  IntervalsEventPayload,
} from "../../intervals/client.ts";
import { eventIdOf, plannedIdFor } from "../../intervals/ids.ts";
import { convertIntervalsEvent, workoutToEventPayload } from "../../intervals/workout-convert.ts";
import { renderWorkoutText } from "../../intervals/workout-text.ts";
import { PLAN_SOURCE, readPlanEvent } from "../../plan/entries.ts";
import { PLAN_HORIZON_DAYS } from "../../plan/refresher.ts";
import type { PlanEntry } from "../../plan/store.ts";
import { plannedLoad, workoutDurationSeconds, workoutOutline } from "../../plan/summary.ts";
import { localDate } from "../../time.ts";
import { dateField, newWorkoutField, stepsField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/** How long `refresh: true` waits for intervals.icu before answering from the cache. */
const REFRESH_WAIT_MS = 10_000;

const NOT_CONFIGURED =
  "plan source not configured: this server has no intervals.icu API key (INTERVALS_API_KEY), so there is no calendar to read or change";

/** One planned workout as the tools describe it: numbers worked out by code from its steps. */
function describeEntry(entry: PlanEntry, detail: "summary" | "full") {
  const { workout } = entry;
  if (workout === null) {
    return {
      id: entry.id,
      date: entry.date,
      name: entry.name,
      playable: false as const,
      reason: entry.unsupportedReason ?? "it cannot be converted",
    };
  }
  const load = plannedLoad(workout);
  return {
    id: entry.id,
    date: entry.date,
    name: entry.name,
    playable: true as const,
    durationSeconds: workoutDurationSeconds(workout),
    estimatedTss: load.estimatedTss,
    ...(load.freeSeconds > 0 ? { freeSeconds: load.freeSeconds } : {}),
    outline: workoutOutline(workout),
    ...(detail === "full" ? { workout } : {}),
  };
}

/** The steps without their cues: a cue's words may be changed on the way through the calendar's text. */
function withoutLabels(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(withoutLabels);
  if (typeof node !== "object" || node === null) return node;
  return Object.fromEntries(
    Object.entries(node)
      .filter(([key]) => key !== "label")
      .map(([key, value]) => [key, withoutLabels(value)]),
  );
}

/** Whether two workouts ask for the same thing, step by step. */
function sameSteps(a: Workout, b: Workout): boolean {
  return canonicalJson(withoutLabels(a.steps)) === canonicalJson(withoutLabels(b.steps));
}

function checkDate(name: string, value: string): void {
  if (!isIsoDate(value))
    throw new ToolError(`${name} ${value} is not a real calendar date, write YYYY-MM-DD`);
}

export function registerPlanTools(server: McpServer, ctx: ToolContext): void {
  const { services, now } = ctx;

  const client = (): IntervalsClient => {
    if (services.intervals === null) throw new ToolError(NOT_CONFIGURED);
    return services.intervals;
  };

  /** Makes the plan cache say what intervals.icu now says about one event, and tells who is listening. */
  async function remember(
    event: IntervalsEvent,
    fallback: Workout | null,
  ): Promise<{ entry: PlanEntry; warnings: string[] }> {
    const id = plannedIdFor(event.id);
    const warnings: string[] = [];
    const date = event.start_date_local.slice(0, 10);
    const name = (event.name ?? "").trim() || fallback?.name || "Workout";
    const converted = convertIntervalsEvent(event, id);
    let workout: Workout | null;
    let reason: string | null = null;
    if (converted.ok) {
      workout = converted.workout;
      if (fallback !== null && !sameSteps(converted.workout, fallback)) {
        warnings.push(
          "intervals.icu has the steps differently from what was sent; the workout shown is what it has, so check the steps below",
        );
      }
    } else if (fallback !== null) {
      workout = { ...fallback, id };
      warnings.push(
        `intervals.icu stored the workout, but the server could not read it back (${converted.reason}); the workout shown is the one you sent, and the next plan refresh may differ`,
      );
    } else {
      workout = null;
      reason = converted.reason;
      warnings.push(
        `the workout is in the calendar but cannot be played on the tablet: ${converted.reason}`,
      );
    }
    const entry: PlanEntry = {
      id,
      source: PLAN_SOURCE,
      externalId: String(event.id),
      date,
      name,
      workout,
      unsupportedReason: reason,
    };
    try {
      await services.plan.upsert(entry);
    } catch (error) {
      ctx.log.error({ err: error, id }, "the plan cache could not be updated after a write");
      throw new ToolError(
        `${id} was saved in intervals.icu, but the server's own copy could not be updated; it catches up at the next plan refresh`,
      );
    }
    return { entry, warnings };
  }

  const stored = (entry: PlanEntry, warnings: string[]) => ({
    ...describeEntry(entry, "full"),
    ...(warnings.length > 0 ? { warnings } : {}),
  });

  const requireEventId = (id: string): number => {
    const eventId = eventIdOf(id);
    if (eventId === null) {
      throw new ToolError(
        `"${id}" is not a planned workout id; they look like icu-123 (list_planned_workouts shows them)`,
      );
    }
    return eventId;
  };

  /** The calendar event behind a planned workout id, which must be a workout. */
  async function workoutEvent(id: string): Promise<IntervalsEvent> {
    const event = await client().getEvent(requireEventId(id));
    if (event === null) throw new ToolError(`there is no workout ${id} in the calendar`);
    if (event.category !== undefined && event.category !== null && event.category !== "WORKOUT") {
      throw new ToolError(`${id} is a ${event.category} entry, not a workout; it was not changed`);
    }
    return event;
  }

  defineTool(
    server,
    ctx,
    {
      name: "list_planned_workouts",
      title: "List planned workouts",
      kind: "read",
      external: true,
      description:
        "The workouts planned in the calendar between two dates, both included, at most 62 days. Without dates: today and the next 13 days. " +
        "Dates are the rider's local days (YYYY-MM-DD). Each workout has an id (icu-123, used by update_workout and delete_workout), its " +
        "date, name, length in seconds, an estimated training stress score (what the steps with a power target would give if ridden exactly " +
        "as planned; free steps add nothing and are counted in freeSeconds), and an outline in one line (targets are % of FTP, 10m is 10 minutes). " +
        'detail "full" adds each workout\'s steps in the native format. Workouts the tablet cannot play (nested repeats, steps measured in distance, …) ' +
        "are listed under unsupported with the reason. totals add up what is listed. " +
        "The server keeps today and the next 14 days in a cache and answers from it (stale says the last refresh failed, generatedAt when the plan " +
        "was last read); for a range reaching past that it asks intervals.icu directly. refresh true reads intervals.icu first.",
      input: {
        from: dateField.optional().describe("First day. Default: today."),
        to: dateField.optional().describe("Last day, included. Default: 13 days after from."),
        detail: z
          .enum(["summary", "full"])
          .optional()
          .describe("summary (default) or full, with the steps."),
        refresh: z
          .boolean()
          .optional()
          .describe("Read the calendar from intervals.icu first (waits at most 10 seconds)."),
      },
    },
    async ({ from, to, detail, refresh }) => {
      const calendar = client();
      const today = localDate(now(), services.timezone);
      const start = from ?? today;
      checkDate("from", start);
      const end = to ?? addDaysToIsoDate(start, 13);
      checkDate("to", end);
      const days = rangeDays(start, end) ?? 0;
      if (days < 1) throw new ToolError(`to ${end} is before from ${start}`);
      if (days > MAX_WORKOUTS_RANGE_DAYS) {
        throw new ToolError(
          `the range spans ${days} days; at most ${MAX_WORKOUTS_RANGE_DAYS} are served`,
        );
      }

      const horizon = { from: today, to: addDaysToIsoDate(today, PLAN_HORIZON_DAYS) };
      const cached = start >= horizon.from && end <= horizon.to;
      if (refresh === true && services.refresher !== null) {
        let timer: ReturnType<typeof setTimeout> | undefined;
        await Promise.race([
          services.refresher.refresh(),
          new Promise<void>((resolve) => {
            timer = setTimeout(resolve, REFRESH_WAIT_MS);
          }),
        ]);
        clearTimeout(timer);
      }

      let entries: PlanEntry[];
      let source: "cache" | "intervals.icu";
      let generatedAt: string | null;
      let stale: boolean;
      if (cached) {
        source = "cache";
        entries = await services.plan.entries({ from: start, to: end });
        const state = await services.plan.syncState(PLAN_SOURCE);
        generatedAt = state.lastSuccessAt === null ? null : state.lastSuccessAt.toISOString();
        stale = state.lastAttemptAt === null || state.lastError !== null;
      } else {
        // Beyond what the cache holds: ask the calendar itself, and keep nothing.
        source = "intervals.icu";
        const events = await calendar.listEvents(start, end);
        entries = events.flatMap((event) => {
          const reading = readPlanEvent(event);
          return reading.kind === "entry" &&
            reading.entry.date >= start &&
            reading.entry.date <= end
            ? [reading.entry]
            : [];
        });
        entries.sort((a, b) =>
          a.date === b.date ? a.id.localeCompare(b.id) : a.date.localeCompare(b.date),
        );
        generatedAt = now().toISOString();
        stale = false;
      }

      const level = detail ?? "summary";
      const described = entries.map((entry) => describeEntry(entry, level));
      const playable = described.flatMap((item) => (item.playable ? [item] : []));
      return {
        from: start,
        to: end,
        source,
        generatedAt,
        stale,
        totals: {
          workouts: playable.length,
          durationSeconds: playable.reduce((sum, item) => sum + item.durationSeconds, 0),
          estimatedTss: playable.reduce((sum, item) => sum + item.estimatedTss, 0),
        },
        workouts: playable,
        ...(described.length > playable.length
          ? { unsupported: described.flatMap((item) => (item.playable ? [] : [item])) }
          : {}),
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "create_workout",
      title: "Plan a workout",
      kind: "write",
      external: true,
      description:
        "Adds a workout to the calendar on a date and returns it as stored, with its id (icu-123). The workout is the native format: " +
        "a name and a list of steps; each step has a duration in whole seconds and a power target as a FRACTION OF FTP (0.88 is 88%), as " +
        "steady (target), ramp (from, to) or free (no target: the rider rides as they like); a repeat (count, steps) repeats leaf steps and " +
        "cannot nest. Optional per step: label (a cue shown to the rider) and cadence (rpm, shown only). Optional for the workout: " +
        'description. Example: {"name":"Sweet spot 2x20","steps":[{"type":"ramp","duration":600,"from":0.45,"to":0.7,' +
        '"label":"Warm-up"},{"type":"repeat","count":2,"steps":[{"type":"steady","duration":1200,"target":0.9},{"type":"steady",' +
        '"duration":300,"target":0.5}]},{"type":"free","duration":300,"label":"Cool-down"}]}. ' +
        "A workout that does not validate is refused with the path of the mistake. The calendar entry appears on the tablet and in " +
        "the rider's other calendars within minutes. It writes to the real calendar: do not create a workout the rider did not ask for.",
      input: {
        date: dateField.describe("The day to ride it, YYYY-MM-DD, the rider's local day."),
        workout: newWorkoutField,
      },
    },
    async ({ date, workout }) => {
      const calendar = client();
      checkDate("date", date);
      // The server supplies what a model has no way to know: the format's version, and the id (the calendar's, later).
      const candidate = {
        ...workout,
        schema: workout.schema ?? 1,
        id: typeof workout.id === "string" && workout.id !== "" ? workout.id : "new",
      };
      const valid = validateWorkout(candidate);
      if (!valid.ok)
        throw new ToolError(`workout does not validate: ${valid.errors.slice(0, 5).join("; ")}`);

      const created = await calendar.createEvent(workoutToEventPayload(valid.value, date));
      const { entry, warnings } = await remember(created, valid.value);
      ctx.log.info(
        { tool: "create_workout", id: entry.id, date },
        "workout planned through the MCP",
      );
      return stored(entry, warnings);
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "update_workout",
      title: "Change a planned workout",
      kind: "write",
      external: true,
      description:
        "Changes a planned workout in the calendar: its name, its date (moving it to another day), its steps, or any of them, and returns " +
        "it as stored. Give at least one. id is the workout's id from list_planned_workouts (icu-123). steps replaces all the steps " +
        "(same format as create_workout: fractions of FTP, whole seconds) and drops the old description. Only workouts can be changed here, " +
        "not other calendar entries. Does not ask for confirmation: change only what the rider asked for.",
      input: {
        id: z.string().describe("The planned workout's id, icu-123."),
        name: z.string().optional().describe("The new name."),
        date: dateField.optional().describe("Move it to this day, YYYY-MM-DD."),
        steps: stepsField.optional(),
      },
    },
    async ({ id, name, date, steps }) => {
      const calendar = client();
      const eventId = requireEventId(id);
      if (name === undefined && date === undefined && steps === undefined) {
        throw new ToolError("nothing to change: give a name, a date or steps");
      }
      if (date !== undefined) checkDate("date", date);
      const newName = name?.trim();
      if (newName === "") throw new ToolError("the name must not be blank");

      const existing = await workoutEvent(id);
      const payload: Partial<IntervalsEventPayload> = {};
      if (newName !== undefined) payload.name = newName;
      if (date !== undefined) {
        const time = /^T\d{2}:\d{2}:\d{2}$/.test(existing.start_date_local.slice(10))
          ? existing.start_date_local.slice(10)
          : "T00:00:00";
        payload.start_date_local = `${date}${time}`;
      }
      let fallback: Workout | null = null;
      if (steps !== undefined) {
        const draft = {
          schema: 1,
          id,
          name: newName ?? ((existing.name ?? "").trim() || "Workout"),
          steps,
        };
        const valid = validateWorkout(draft);
        if (!valid.ok)
          throw new ToolError(`workout does not validate: ${valid.errors.slice(0, 5).join("; ")}`);
        fallback = valid.value;
        payload.description = renderWorkoutText(valid.value);
      }

      const updated = await calendar.updateEvent(eventId, payload);
      const { entry, warnings } = await remember(updated, fallback);
      ctx.log.info({ tool: "update_workout", id }, "workout changed through the MCP");
      return stored(entry, warnings);
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "delete_workout",
      title: "Remove a planned workout",
      kind: "write",
      destructive: true,
      external: true,
      description:
        "Removes a planned workout from the calendar (and so from the tablet and the rider's other calendars). id is the workout's id " +
        "from list_planned_workouts (icu-123). Rides already recorded from it are kept. Deleting a workout that is already gone succeeds. " +
        "It cannot be undone, except by creating the workout again: delete only what the rider asked to remove.",
      input: { id: z.string().describe("The planned workout's id, icu-123.") },
    },
    async ({ id }) => {
      const calendar = client();
      const eventId = requireEventId(id);
      const existing = await calendar.getEvent(eventId);
      if (existing === null) {
        await services.plan.remove(id);
        return { id, deleted: false, note: "it was already gone from the calendar" };
      }
      if (
        existing.category !== undefined &&
        existing.category !== null &&
        existing.category !== "WORKOUT"
      ) {
        throw new ToolError(
          `${id} is a ${existing.category} entry, not a workout; it was not deleted`,
        );
      }
      await calendar.deleteEvent(eventId);
      try {
        await services.plan.remove(id);
      } catch (error) {
        ctx.log.error({ err: error, id }, "the plan cache could not be updated after a delete");
        throw new ToolError(
          `${id} was deleted from intervals.icu, but the server's own copy could not be updated; it catches up at the next plan refresh`,
        );
      }
      ctx.log.info({ tool: "delete_workout", id }, "workout deleted through the MCP");
      return { id, deleted: true };
    },
  );
}
