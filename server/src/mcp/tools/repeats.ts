import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { REPEAT_HASH_PATTERN } from "@overdrive/contracts";
import { z } from "zod";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/**
 * `list_repeats` and `get_repeat` (spec §11.7, §8.4, ADR 0026): the sessions the rider has ridden more than once and every attempt at
 * one side by side. The comparison of a ride with the attempt before it is in `get_ride` (`repeatOf`). Every number is computed by
 * the server; the heart rate at the same power is a fitness marker that also moves with heat, sleep and caffeine, so a trend over
 * several attempts says more than one comparison.
 */
export function registerRepeatTools(server: McpServer, ctx: ToolContext): void {
  const { repeats } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "list_repeats",
      title: "List repeated sessions",
      kind: "read",
      description:
        "The workouts the rider has ridden more than once, most recently ridden first: riding the same session again is the cleanest measure of " +
        "progress. Two rides are the same session when the workouts they were played from have the same structure (the same steps, lengths and " +
        "targets, whatever they are called), so a renamed copy of a workout is the same session; a ride with no planned workout never matches. " +
        "For each: hash (the id for get_repeat), name, how many attempts, the first and last day, the latest attempt and the one before it, and a " +
        "comparison of the latest against the previous: heartRateAtPower (the average heart rate in bpm at the same power over the fixed-power " +
        "steps, lower at the same power is fitter), driftPercent (aerobic drift, where the rides have one) and compliance (percent of the planned " +
        "time ridden), each as latest, previous and difference (latest minus previous) or why there is none. Day-to-day heart rate also moves with " +
        "heat, sleep and caffeine: one comparison proves little, the trend over several attempts matters (get_repeat has it). Every number is " +
        "computed by code: quote it, do not recalculate it.",
      input: {},
    },
    async () => {
      const answer = await repeats.list();
      return {
        generatedAt: answer.generatedAt,
        count: answer.repeats.length,
        repeats: answer.repeats,
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "get_repeat",
      title: "Get one repeated session",
      kind: "read",
      description:
        "Every attempt at one repeated session side by side, oldest first, by the hash list_repeats gives. For each attempt: the ride, its day, length, " +
        "the FTP it was ridden at, average power and heart rate, heartRateAtPower (the average heart rate in bpm at the same power, over the steps in " +
        "comparedSteps, weighted by their planned length, or why there is none), for each fixed-power step the heart rate at that power " +
        "(a second counts when the power is within 3% of the step's target and the ride was not paused, after the first 60 s of the step, and a step " +
        "needs 120 such seconds), the aerobic drift, and compliance (percent of the planned time ridden, steps held for their whole time, " +
        "power against plan). Attempts whose target power differs from the comparison's (the FTP changed) have no heartRateAtPower. trends gives, for " +
        "heartRateAtPower, driftPercent and compliance, the slope per attempt and the fitted change from the first attempt to the last, with a " +
        "direction (improving, worsening, steady, or unknown below 3 attempts with a figure). effortRating is reserved and null. Day-to-day heart rate " +
        "also moves with heat, sleep and caffeine, so read the trend over several attempts, not one comparison. Every number is computed by code.",
      input: {
        hash: z
          .string()
          .describe("The session's hash from list_repeats: 16 lower-case hex characters."),
      },
    },
    async ({ hash }) => {
      if (!REPEAT_HASH_PATTERN.test(hash)) {
        throw new ToolError(`"${hash}" is not a session hash; list_repeats shows them`);
      }
      const detail = await repeats.detail(hash);
      if (detail === null)
        throw new ToolError(`there is no session ${hash} that has been ridden at least twice`);
      return detail;
    },
  );
}
