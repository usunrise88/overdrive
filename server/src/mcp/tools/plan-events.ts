import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { PLAN_EVENT_KINDS } from "@overdrive/contracts";
import { z } from "zod";
import { describeEvent } from "../../plan-events/messages.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const MAX_EVENTS = 100;
/** The note goes to the rider in Telegram: a short one. */
const MAX_NOTE = 600;

/**
 * `list_plan_events` and `resolve_plan_event` (spec §12.2, §8.4, ADR 0035): the queue of facts that tell the coach agent the plan may
 * need rebuilding, and how it says it has. The service only detects and queues; nothing here moves a workout, the agent does that
 * with the planning tools, and a missed session need not be rescheduled: dropping it is a valid outcome.
 */
export function registerPlanEventTools(server: McpServer, ctx: ToolContext): void {
  const { planEvents } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "list_plan_events",
      title: "List the plan events",
      kind: "read",
      description:
        "The plan events: facts that tell you the plan may need rebuilding. START EVERY SESSION BY CHECKING THEM (open ones are the default), " +
        "after reading the notebook. kind is missed (a planned day ended with no matching activity; the server looked after the day was over, " +
        "so a late upload is counted, but an activity that is not a tablet ride, such as a watch or outdoor ride, is not matched: check " +
        "list_activities for that day before assuming nothing was ridden), moved (the rider moved a planned session to another day in " +
        "intervals.icu: detail has from and to), deleted (the rider deleted a planned session there), variant (the easier version of a " +
        "session was ridden), illness or travel (the rider declared it: detail.until is the last day or null for no end given). Each has " +
        "day (the rider's local day it is about), workoutId (the planned workout, null for illness and travel), detail, status, a summary " +
        "in plain words written by code, and, once resolved, resolvedAt, resolutionNote and dropped. Open events come oldest first (the order " +
        "to deal with them in); resolved ones newest first; status all lists both. Rebuild the days that are left with the normal planning " +
        "tools (list_planned_workouts, update_workout, create_workout, delete_workout), checking the change against the methodology section " +
        "of the notebook and the load and recovery picture (get_fitness, get_recovery), then call resolve_plan_event. The service moves " +
        "nothing by itself.",
      input: {
        status: z.enum(["open", "resolved", "all"]).optional().describe("Default open."),
        kind: z.enum(PLAN_EVENT_KINDS).optional().describe("Only events of this kind."),
        limit: z
          .number()
          .int()
          .min(1)
          .max(MAX_EVENTS)
          .optional()
          .describe(`At most this many, default 20, at most ${MAX_EVENTS}.`),
      },
    },
    async ({ status, kind, limit }) => {
      const wanted = status ?? "open";
      const { events, total } = await planEvents.list({
        status: wanted,
        kind,
        limit: limit ?? 20,
      });
      return {
        status: wanted,
        total,
        count: events.length,
        events: events.map((event) => ({ ...event, summary: describeEvent(event) })),
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "resolve_plan_event",
      title: "Resolve a plan event",
      kind: "write",
      description:
        "Marks a plan event resolved, once you have dealt with it: you rebuilt the days that are left with the planning tools, or decided " +
        "the plan stands as it is. resolutionNote is sent to the rider in Telegram by the server as it is, together with a plain line " +
        "saying what the event was and what became of it: write it for the rider, in one or two sentences, what changed and why (a number is " +
        "yours to quote only if the server gave it). dropped is required and explicit: true when the session was dropped and not " +
        "rescheduled (a valid outcome for a missed session, and the rider is told plainly that it was dropped), false when the plan was " +
        "rebuilt or left as it was around it. This changes nothing in the plan, the profile or the notebook. Resolving an event that is " +
        "already resolved changes nothing and sends nothing (the answer says so). The rider is told only if the Telegram bot is set up; " +
        "the answer says whether it was.",
      input: {
        id: z.number().int().min(1).describe("The event's id (list_plan_events)."),
        resolutionNote: z
          .string()
          .min(1)
          .max(MAX_NOTE * 2)
          .describe(
            `What changed and why, for the rider, one or two sentences, at most ${MAX_NOTE} characters.`,
          ),
        dropped: z
          .boolean()
          .describe("true: the session was dropped and not rescheduled. false: otherwise."),
      },
    },
    async ({ id, resolutionNote, dropped }) => {
      const note = resolutionNote.replace(/[ \t]+/g, " ").trim();
      if (note === "") throw new ToolError("resolutionNote is empty: say what changed and why");
      if (note.length > MAX_NOTE) {
        throw new ToolError(
          `resolutionNote is ${note.length} characters; at most ${MAX_NOTE}: the rider is sent it, say it shorter`,
        );
      }
      const result = await planEvents.resolve(id, { note, dropped });
      if (result === null) {
        throw new ToolError(`there is no plan event ${id}; list_plan_events shows them`);
      }
      const event = { ...result.event, summary: describeEvent(result.event) };
      if (!result.changed) {
        return {
          resolved: false,
          alreadyResolved: true,
          message: `Plan event ${id} was already resolved; nothing was changed and nothing was sent.`,
          event,
        };
      }
      return {
        resolved: true,
        toldToRider: planEvents.canTellRider,
        ...(planEvents.canTellRider
          ? {}
          : {
              note: "The rider was NOT told in Telegram (the bot is not set up). Tell them in the chat what changed and why.",
            }),
        event,
      };
    },
  );
}
