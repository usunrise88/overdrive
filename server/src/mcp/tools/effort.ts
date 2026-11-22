import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { EFFORT_DEFAULT_WEEKS, EFFORT_MAX_WEEKS } from "@overdrive/contracts";
import { z } from "zod";
import { defineTool, type ToolContext } from "../tool.ts";

/**
 * `get_effort` (spec §6.3, §8.4, ADR 0042): the rider's training load per week and per day, their usual range and how each week
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
        "workouts from the watch and outdoor rides (kind activity; sport cycling-outdoor for an outdoor ride), all counted together and a watch session that overlaps a " +
        "tablet ride counted once. weeks are Monday to Sunday in the rider's time zone, oldest first, the last one in progress (current true). Each week has load, " +
        "approximate (some of the load is from heart rate), days (seven, each date and load; days after today are 0) and activities (id, kind, sport, name, date, load, " +
        "approximate, and share of the week; load null when an activity had no heart rate to work it out from, and then it adds nothing). usualLow and usualHigh are the " +
        "rider's usual range for that week: the mean minus and plus one standard deviation of the 8 complete weeks before it, null while the rider has fewer than 8 weeks " +
        "of training (learning; weeksLogged of weeksNeeded). verdict compares the week's load with that range: below, within or above; for the week in progress it " +
        "compares the load so far with the whole range, so below there means below so far, and none means nothing is recorded yet this week; learning means no range yet. " +
        "A load below the usual is not bad news by itself (a rest week, illness, travel): pair it with get_recovery and the plan events before drawing a conclusion. " +
        "Every number is computed by code: quote it, do not recalculate it. " +
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
