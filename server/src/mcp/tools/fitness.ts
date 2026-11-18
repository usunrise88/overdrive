import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  addDaysToIsoDate,
  isIsoDate,
  MAX_METRICS_RANGE_DAYS,
  rangeDays,
} from "@overdrive/contracts";
import { z } from "zod";
import { EFTP_WINDOW_DAYS } from "../../metrics/ftp.ts";
import { localDate } from "../../time.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const DEFAULT_DAYS = EFTP_WINDOW_DAYS;
/** The durations shown without `series`: 5 s, 1 min, 5 min, 20 min, 1 h. */
const HEADLINE_DURATIONS = new Set([5, 60, 300, 1200, 3600]);

export function registerFitnessTools(server: McpServer, ctx: ToolContext): void {
  const { services, now } = ctx;
  const timezone = services.timezone;

  defineTool(
    server,
    ctx,
    {
      name: "get_fitness",
      title: "Get fitness, fatigue and form",
      kind: "read",
      description:
        "How fit, how tired and how fresh the rider is, worked out by the server from the recorded rides (never by a model: quote the numbers, do not recalculate). " +
        "load is the training stress of a day (100 is one hour at the rider's FTP; rides count by power, other activities from the watch by heart rate). " +
        "fitness (CTL) is the exponentially weighted average of the daily load over 42 days, fatigue (ATL) the same over 7 days, form (TSB) is fitness minus fatigue " +
        "on the same day: well below 0 is hard training, around 0 neutral, above about 5 fresh. All three start at 0 on the first day with any load and run day by day, " +
        "rest days included, so they are small in the first weeks of data. The result has today (the last day of the range) with the change of fitness over 7 and " +
        "28 days where the range is long enough, the weekly loads (Monday-first weeks, the current one partial), the best average power over 5 s, 1, 5, 20 and " +
        "60 minutes in the range and ever, the aerobic drift of steady rides (percent the heart rate rose against power from the first half to the second; lower is better), " +
        "and the FTP: the profile's value, the day it was set, and a suggestion. The FTP suggestion (eFTP) is ONLY a suggestion estimated from the best efforts of " +
        "the last 42 days (95% of the best 20-minute power, or the best 60-minute power, whichever is higher, needing similar efforts in at least two rides); " +
        "it is made only when it differs from the FTP by 3% or more, and nothing changes until the rider accepts it in the app or tells you to: never call " +
        "update_profile with it unasked. ftp.note says why there is no suggestion. Without dates: the last 42 days up to today. Dates are the rider's local days " +
        "(YYYY-MM-DD), both included, at most 366 days. By default the result is compact (no day-by-day series); series true adds every day's load, fitness, " +
        "fatigue and form, the full power curve at 25 durations and the weekly FTP estimates. stale true means a source of load failed and the numbers leave it out.",
      input: {
        from: dateField.optional().describe("First day. Default: 41 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
        series: z
          .boolean()
          .optional()
          .describe(
            "Add the daily series, the full power curve and the FTP estimates. Default false.",
          ),
      },
    },
    async ({ from, to, series }) => {
      const end = to ?? localDate(now(), timezone);
      if (!isIsoDate(end))
        throw new ToolError(`to ${end} is not a real calendar date, write YYYY-MM-DD`);
      const start = from ?? addDaysToIsoDate(end, -(DEFAULT_DAYS - 1));
      if (!isIsoDate(start)) {
        throw new ToolError(`from ${start} is not a real calendar date, write YYYY-MM-DD`);
      }
      const days = rangeDays(start, end) ?? 0;
      if (days < 1) throw new ToolError(`to ${end} is before from ${start}`);
      if (days > MAX_METRICS_RANGE_DAYS) {
        throw new ToolError(
          `the range spans ${days} days; at most ${MAX_METRICS_RANGE_DAYS} are served`,
        );
      }

      const metrics = await services.metrics.metrics(start, end);
      const advice = await services.metrics.ftpAdvice();
      const last = metrics.daily[metrics.daily.length - 1];
      const fitnessAgo = (ago: number): number | null =>
        metrics.daily[metrics.daily.length - 1 - ago]?.ctl ?? null;
      const change = (ago: number): number | null => {
        const before = fitnessAgo(ago);
        return before === null || last === undefined
          ? null
          : Math.round((last.ctl - before) * 10) / 10;
      };
      const headline = <T extends { durationSec: number }>(points: T[]): T[] =>
        series === true ? points : points.filter((p) => HEADLINE_DURATIONS.has(p.durationSec));

      return {
        from: metrics.from,
        to: metrics.to,
        generatedAt: metrics.generatedAt,
        stale: metrics.stale,
        timezone,
        today:
          last === undefined
            ? null
            : {
                date: last.date,
                load: last.load,
                fitness: last.ctl,
                fatigue: last.atl,
                form: last.tsb,
                fitnessChange7Days: change(7),
                fitnessChange28Days: change(28),
              },
        firstLoadDate: metrics.firstLoadDate,
        weeks: metrics.weekly,
        powerCurve: {
          range: headline(metrics.powerCurve.range),
          allTime: headline(metrics.powerCurve.allTime),
        },
        drift: metrics.drift,
        ftp: {
          current: metrics.ftp.current,
          setAt: metrics.ftp.setAt,
          suggestion:
            advice.suggestion === null
              ? null
              : {
                  suggestedWatts: advice.suggestion.value,
                  onlyASuggestion: true,
                  basedOnRides: advice.suggestion.basedOnRides,
                  windowDays: advice.suggestion.windowDays,
                  confidence: advice.suggestion.confidence,
                  basis: advice.suggestion.basis,
                  madeAt: advice.suggestion.createdAt.toISOString(),
                },
          latestEstimate: metrics.ftp.latestEstimate,
          note: advice.note,
          history: metrics.ftp.history,
          ...(series === true ? { estimates: metrics.ftp.estimates } : {}),
        },
        ...(series === true ? { daily: metrics.daily } : {}),
      };
    },
  );
}
