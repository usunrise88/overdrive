import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { PowerSkillsRangeError } from "../../power-skills/service.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/**
 * `get_power_skills` (spec §6.3, §8.4, ADR 0042): the best power the rider has held at twelve durations, over a window and over all
 * time, and a level for each of five skills. Every number is computed by the server from the rides' stored power curves and the
 * rider's weight; the level table is approximate and a rough guide only.
 */
export function registerPowerSkillsTools(server: McpServer, ctx: ToolContext): void {
  const { powerSkills } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "get_power_skills",
      title: "Get power skills",
      kind: "read",
      description:
        "The best average power the rider has held at twelve durations (15 s, 30 s, 1, 2, 3, 5, 10, 15, 20, 30, 45 and 60 minutes), over a window of days and over " +
        "all time, from the rides on the tablet only (outdoor rides and the watch's workouts are not in it). For each duration: window (the best in the window) and allTime " +
        "(the best of all rides up to the end of the window), each null when no ride is long enough, and each with watts, the ride (rideId, rideName) and its day, and, when " +
        "the weight is known, wattsPerKg and score (the watts per kilogram placed on that duration's scale of levels, 0 to 9: 1 is the floor of level 1, 9 the floor of level 9). " +
        "A missing reading or a pause counts as 0 W, so these are conservative. skills groups the durations: Sprint (15 to 30 s), Attack (1 to 3 min), Climb (5 to 10 min), " +
        "Time trial (15 to 30 min) and Endurance (45 to 60 min); each has a level from 1 to 9 (Starting, Casual, Regular, Fit, Strong, Very strong, Racer, Elite, World class), " +
        "the mean of the scores of its durations rounded down. Levels exist only when the rider's weight is known (riderWeight, the 7-day average from the watch) and are a " +
        "ROUGH GUIDE: the table (men's or women's, as the profile's powerProfileTable says; table in the answer) is approximate, from published power-profile tables for " +
        "young adults, and indoor power differs from outdoor; say so if you quote a level, and prefer a trend of the rider's own watts over a level. Without dates the window " +
        "is the last 56 days up to today; dates are the rider's local days (YYYY-MM-DD), at most 1096 days (three years). window.rideCount is how many rides in the window " +
        "have power. Every number is computed by code: quote it, do not recalculate it.",
      input: {
        from: dateField.optional().describe("First day of the window. Default: 55 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
      },
    },
    async ({ from, to }) => {
      try {
        return await powerSkills.powerSkills(powerSkills.resolveWindow(from, to));
      } catch (error) {
        if (error instanceof PowerSkillsRangeError) throw new ToolError(error.message);
        throw error;
      }
    },
  );
}
