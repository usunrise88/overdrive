import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { EFFORT_DEFAULT_WEEKS, EFFORT_MAX_WEEKS } from "@overdrive/contracts";
import { z } from "zod";
import { defineTool, type ToolContext } from "../tool.ts";

/**
 * `get_effort` (spec §6.3, §8.4, ADR 0042): the rider's training load per week and per day, their usual range and how this week
 * compares with it. Effort is the training load of `get_fitness` (not a second formula), so the two always agree.
 */
export function registerEffortTools(server: McpServer, ctx: ToolContext): void {
  const { effort } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "get_effort",
      title: "Get weekly effort",
      kind: "read",
      description:
        "The rider's training load per week, the same load as get_fitness (100 is about one hour at threshold): from power for rides on the tablet, from heart rate for " +
        "workouts from the watch and outdoor rides, all kinds counted together and a watch session that overlaps a tablet ride counted once. weeks are Monday to Sunday " +
        "in the rider's time zone, oldest first, the last one in progress (inProgress true, days up to today); each has load, activityCount, durationSeconds, approximate " +
        "(the load rests on an estimated threshold or a thin heart rate, or a workout's load could not be worked out and counts 0) and days with each day's activities " +
        "(id, kind ride, watch or outdoor, name, load). usual is the rider's usual week: the median and the interquartile band (low to high) of the previous completed " +
        "weeks, at most eight, from the first week with any activity, and null with fewer than four. thisWeek says how the week in progress compares with what the same " +
        "weeks had reached by the same weekday (usualSoFar): verdict below, within or above, or unknown on Monday and Tuesday and without a usual range; verdictText is " +
        "the sentence the rider's app shows, so quote it rather than rewording the numbers. A rider's load below the usual is not bad news by itself (a rest week, " +
        "illness, travel): pair it with get_recovery and the plan events before drawing a conclusion. Every number is computed by code: quote it, do not recalculate it. " +
        `weeks: how many weeks, 1 to ${EFFORT_MAX_WEEKS}; default ${EFFORT_DEFAULT_WEEKS}.`,
      input: {
        weeks: z
          .number()
          .int()
          .min(1)
          .max(EFFORT_MAX_WEEKS)
          .optional()
          .describe(
            `How many weeks, ending with the current one. Default ${EFFORT_DEFAULT_WEEKS}.`,
          ),
      },
    },
    async ({ weeks }) => effort.effort(weeks ?? EFFORT_DEFAULT_WEEKS),
  );
}
