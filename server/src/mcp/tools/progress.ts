import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/**
 * `get_progress` (spec §6.3 "Progress over time", §8.4, ADR 0043): how the rider's power and heart rate have changed over months, with the
 * service's verdict on each trend. Every number and every sentence is computed by the server; the agent quotes them and never calculates.
 */
export function registerProgressTools(server: McpServer, ctx: ToolContext): void {
  const { progress } = ctx.services;

  const dateField = z.string().describe("A calendar day, YYYY-MM-DD, in the rider's time zone.");

  defineTool(
    server,
    ctx,
    {
      name: "get_progress",
      title: "Get progress over time",
      kind: "read",
      description:
        "Whether the rider is getting fitter, over months and with measures other than FTP, worked out by the server (never by a model: quote its numbers and " +
        "its verdictText sentences, do not recalculate or reword the judgement). Six measures, each with a series and a trend {direction (improving, worsening, " +
        "steady, or unknown while there are too few points), betterWhen, points, minPoints, change (the fitted change over the range), from, latest, since, " +
        "steadyBand, verdictText, reason}: bestPower (the best 5, 20 and 60 minute power of each calendar month, with watts per kilogram, from rides on the " +
        "tablet only; the current month is partial and not in the trend), aerobicEfficiency (heart rate at a reference power and power at a reference heart rate, " +
        "per week, from steady stretches of 5 minutes or more in every ride; both references are fixed for the range and named), efficiencyFactor (normalised " +
        "power over average heart rate of the steady parts of each ride of 20 minutes or more, with the mean of the last five), decoupling (the aerobic drift of " +
        "each ride of 40 minutes or more), restingHeartRate (the weekly mean from the watch) and weight (the weekly mean weight and watts per kilogram, the FTP " +
        "in force that week over the weight). headline names the measure with the clearest verdict, or is null. Heart rate also moves with heat, sleep and caffeine, " +
        "so trust a trend over several weeks, not one ride; 'fitter' is said only for heart rate against power, and weight is reported and never judged. A trend " +
        "with direction unknown is not a finding: say that there is not enough data yet. Without dates: the last six months up to today; at most five years. " +
        "stale true means the watch's data failed at its last read.",
      input: {
        from: dateField.optional().describe("First day. Default: six months before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
      },
    },
    async ({ from, to }) => {
      const range = progress.rangeFor(from, to);
      if (!range.ok) throw new ToolError(range.message);
      return progress.progress(range.from, range.to);
    },
  );
}
