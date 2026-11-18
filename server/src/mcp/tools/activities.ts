import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { addDaysToIsoDate, isIsoDate, isValidRideId } from "@overdrive/contracts";
import { z } from "zod";
import { dayStartMillis, localDate } from "../../time.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";
import type { RideTools } from "./rides.ts";

const DEFAULT_DAYS = 30;
const MAX_LIMIT = 100;

/** Rides on the tablet are `cycling`; a workout from the watch has the type the phone named it (`other_workout`, `biking`, ...). */
const TABLET_TYPE = "cycling";

/**
 * `list_activities` and `get_activity` (spec §6.7, §11.8, §8.4): the tablet's rides, the watch's workouts and the outdoor rides
 * imported from FIT files in one history, each with a `type` (a ride on the tablet is `cycling`, an imported ride
 * `cycling-outdoor`) and a `source` (`tablet`, `watch`, `fit`). They replace `list_rides` and `get_ride`, which stay for one release.
 * Only rides have power, laps and plan compliance; a watch workout and an outdoor ride have heart rate and a heart-rate load that
 * counts toward fatigue and recovery but never toward the power curve, FTP or aerobic drift.
 */
export function registerActivityTools(server: McpServer, ctx: ToolContext, rides: RideTools): void {
  const { services, now } = ctx;
  const timezone = services.timezone;
  const { wellness } = services;

  defineTool(
    server,
    ctx,
    {
      name: "list_activities",
      title: "List completed activities",
      kind: "read",
      description:
        "Everything the rider has done, newest first, as summaries: the rides recorded on the tablet (type cycling, source tablet; with " +
        "power, intensity factor and load as the tablet computed them, and the planned workout they were ridden from) and the workouts " +
        "recorded on the watch (source watch; type as the watch names it, such as other_workout or biking; with average and maximum heart " +
        "rate, and load). A watch workout's load is worked out from heart rate against the rider's threshold heart rate on the same scale " +
        "as a ride's training stress score (one hour at threshold is 100); it is approximate (loadApproximate says when an estimate was " +
        "used) and tends to under-count short hard efforts. An outdoor ride imported from a FIT file (source fit, type cycling-outdoor, " +
        "from the rider's bike computer; with distance and climb) is counted the same way: from heart rate, and never toward the power curve, " +
        "FTP or aerobic drift. A watch session that overlaps a ride on the tablet or an imported ride is the same ride " +
        "(countsTowardLoad false, overlapsRideId) and is not counted twice. Without dates: the last 30 days. Dates are the rider's local " +
        "days (YYYY-MM-DD), both included. At most 100 per call; total says how many match, offset pages through them. source narrows " +
        "to tablet, watch or fit. Use get_activity for one of them in detail.",
      input: {
        from: dateField.optional().describe("First day. Default: 29 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
        source: z
          .enum(["all", "tablet", "watch", "fit"])
          .optional()
          .describe(
            "all (default), tablet rides only, watch workouts only, or outdoor rides imported from FIT files only.",
          ),
        limit: z.number().int().min(1).max(MAX_LIMIT).optional().describe("1 to 100. Default 20."),
        offset: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe("Items to skip, for the next page. Default 0."),
      },
    },
    async ({ from, to, source, limit, offset }) => {
      const end = to ?? localDate(now(), timezone);
      if (!isIsoDate(end))
        throw new ToolError(`to ${end} is not a real calendar date, write YYYY-MM-DD`);
      const start = from ?? addDaysToIsoDate(end, -(DEFAULT_DAYS - 1));
      if (!isIsoDate(start)) {
        throw new ToolError(`from ${start} is not a real calendar date, write YYYY-MM-DD`);
      }
      if (start > end) throw new ToolError(`to ${end} is before from ${start}`);

      const page = limit ?? 20;
      const skipped = offset ?? 0;
      const need = skipped + page;
      const fromMs = dayStartMillis(start, timezone);
      const toMs = dayStartMillis(addDaysToIsoDate(end, 1), timezone);
      const [tablet, watch] = await Promise.all([
        source === "watch" || source === "fit"
          ? { total: 0, rides: [] }
          : services.rides.list({ fromMillis: fromMs, toMillis: toMs, limit: need, offset: 0 }),
        source === "tablet"
          ? { total: 0, items: [] }
          : wellness.listActivities({
              fromMs,
              toMs,
              limit: need,
              offset: 0,
              ...(source === "watch" || source === "fit" ? { source } : {}),
            }),
      ]);

      // Both lists are newest first; the page is a slice of their merge.
      const merged = [
        ...tablet.rides.map((ride) => ({ startMs: ride.startEpochMillis, ride, item: null })),
        ...watch.items.map((item) => ({ startMs: Date.parse(item.start), ride: null, item })),
      ].sort((a, b) => b.startMs - a.startMs);
      const shown = merged.slice(skipped, skipped + page);
      const total = tablet.total + watch.total;

      const activities = await Promise.all(
        shown.map(async (entry) => {
          if (entry.ride !== null) {
            return {
              type: TABLET_TYPE,
              source: "tablet",
              ...(await rides.describeListing(entry.ride)),
            };
          }
          const item = entry.item as NonNullable<typeof entry.item>;
          return {
            id: item.id,
            type: item.type,
            source: item.source,
            name: item.name,
            startTime: item.start,
            date: item.startDate,
            durationSeconds: item.durationSeconds,
            averageHeartRate: item.averageHeartRate,
            maxHeartRate: item.maxHeartRate,
            load: item.load,
            loadApproximate: item.loadApproximate,
            countsTowardLoad: item.countsTowardLoad,
          };
        }),
      );
      return {
        from: start,
        to: end,
        total,
        offset: skipped,
        returned: activities.length,
        hasMore: skipped + activities.length < total,
        activities,
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "get_activity",
      title: "Get one activity",
      kind: "read",
      description:
        "One activity in detail, by the id list_activities gives. A ride on the tablet (an id like ride-1794996000000; type cycling) is " +
        "answered as get_ride does: power, heart-rate and cadence statistics, laps, compliance with the planned workout, and the time " +
        "series on request. An outdoor ride imported from a FIT file (an id like fit-<16 hex>; type cycling-outdoor, source fit) answers as a " +
        "workout from the watch does, with its distance and climb in metres when the file says them. A workout from the watch (an id like " +
        "watch-<record id>) has: type and name as the watch gave them, start, end " +
        "and duration, average and maximum heart rate, load (heart-rate load on the scale of the training stress score: one hour at " +
        "threshold heart rate is 100; null when the maximum and threshold heart rate are not known) with loadApproximate and loadNote, " +
        "the maximum and threshold heart rate used, how dense the heart-rate samples are (count, median and longest gap in seconds, " +
        "coverage 0 to 1), whether it overlaps a ride on the tablet, and, with series true, the heart rate over the session in at most " +
        "300 points. It counts toward fatigue, form and recovery and never toward the power curve, FTP or aerobic drift. Every number is " +
        "computed by code: quote it, do not recalculate it.",
      input: {
        id: z.string().describe("An id from list_activities: a ride-…, a watch-… or a fit-…"),
        series: z.boolean().optional().describe("Include the time series. Default false."),
        seriesStepSeconds: z
          .number()
          .int()
          .min(1)
          .max(600)
          .optional()
          .describe("For a ride: seconds averaged into each point. Default 10."),
      },
    },
    async ({ id, series, seriesStepSeconds }) => {
      if (id.startsWith("watch-") || id.startsWith("fit-")) {
        const activity = await wellness.activity(id);
        if (activity === null) throw new ToolError(`there is no activity ${id}`);
        const { series: points, ...rest } = activity;
        return { ...rest, ...(series === true ? { series: points } : {}) };
      }
      if (!isValidRideId(id)) {
        throw new ToolError(`"${id}" is not an activity id; list_activities shows them`);
      }
      const ride = await rides.getRide({ rideId: id, series, seriesStepSeconds });
      return { type: TABLET_TYPE, source: "tablet", ...(ride as object) };
    },
  );
}
