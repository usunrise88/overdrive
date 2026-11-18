import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { addDaysToIsoDate, isIsoDate, isValidRideId, powerZoneRanges } from "@overdrive/contracts";
import { z } from "zod";
import {
  flattenWorkout,
  type RideStatistics,
  rideCompliance,
  rideSeries,
  rideStatistics,
} from "../../ride-stats.ts";
import type { RideListing } from "../../ride-store.ts";
import { dayStartMillis, localDate } from "../../time.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const round = (value: number | null | undefined, digits: number): number | null => {
  if (value === null || value === undefined) return null;
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

const DEFAULT_DAYS = 30;
const MAX_LIMIT = 100;

export function registerRideTools(server: McpServer, ctx: ToolContext): void {
  const { services, now } = ctx;
  const timezone = services.timezone;

  async function describeListing(ride: RideListing) {
    const { summary } = ride;
    let workoutName: string | null = null;
    if (ride.workoutId !== null) {
      workoutName =
        (await services.plan.get(ride.workoutId))?.name ??
        (await services.rides.workoutSnapshot(ride.id))?.name ??
        null;
    }
    return {
      id: ride.id,
      startTime: new Date(ride.startEpochMillis).toISOString(),
      date: localDate(ride.startEpochMillis, timezone),
      durationSeconds: summary.durationSeconds,
      movingSeconds: summary.movingSeconds,
      distanceKilometers: round(summary.distanceMeters / 1000, 2),
      averageWatts: round(summary.averagePower, 1),
      normalizedWatts: round(summary.normalizedPower, 1),
      maxWatts: summary.maxPower ?? null,
      averageHeartRate: round(summary.averageHeartRate, 1),
      maxHeartRate: summary.maxHeartRate ?? null,
      workKilojoules: round(summary.workKilojoules, 1),
      intensityFactor: round(summary.intensityFactor, 2),
      load: round(summary.trainingStressScore, 1),
      ftp: ride.ftp,
      workoutId: ride.workoutId,
      workoutName,
    };
  }

  defineTool(
    server,
    ctx,
    {
      name: "list_rides",
      title: "List completed rides",
      kind: "read",
      description:
        "The rides recorded on the tablet and uploaded to the server, newest first, as summaries: when it started, length and moving time in " +
        "seconds, distance in km (virtual), average, normalised and maximum power in watts, average and maximum heart rate, work in kJ, " +
        "intensity factor, load (training stress score), the FTP used, and the planned workout it was ridden from (workoutId, workoutName; " +
        "null for a free ride). These are the numbers the tablet computed when the ride ended. Without dates: the last 30 days. Dates are the " +
        "rider's local days (YYYY-MM-DD), both included. At most 100 rides per call; total says how many match, offset pages through them. " +
        "Use get_ride for one ride's laps, statistics and comparison with the plan.",
      input: {
        from: dateField.optional().describe("First day. Default: 29 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
        limit: z
          .number()
          .int()
          .min(1)
          .max(MAX_LIMIT)
          .optional()
          .describe("Rides to return, 1 to 100. Default 20."),
        offset: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe("Rides to skip, for the next page. Default 0."),
      },
    },
    async ({ from, to, limit, offset }) => {
      const end = to ?? localDate(now(), timezone);
      if (!isIsoDate(end))
        throw new ToolError(`to ${end} is not a real calendar date, write YYYY-MM-DD`);
      const start = from ?? addDaysToIsoDate(end, -(DEFAULT_DAYS - 1));
      if (!isIsoDate(start))
        throw new ToolError(`from ${start} is not a real calendar date, write YYYY-MM-DD`);
      if (start > end) throw new ToolError(`to ${end} is before from ${start}`);

      const page = limit ?? 20;
      const skipped = offset ?? 0;
      const { total, rides } = await services.rides.list({
        fromMillis: dayStartMillis(start, timezone),
        toMillis: dayStartMillis(addDaysToIsoDate(end, 1), timezone),
        limit: page,
        offset: skipped,
      });
      return {
        from: start,
        to: end,
        total,
        offset: skipped,
        returned: rides.length,
        hasMore: skipped + rides.length < total,
        rides: await Promise.all(rides.map(describeListing)),
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "get_ride",
      title: "Get one ride",
      kind: "read",
      description:
        "One ride in detail, worked out by the server from the recorded samples. statistics: power (average, normalised, maximum, minimum, " +
        "variability index, intensity factor, load, work in kJ, best average power over 5 s, 1 min, 5 min and 20 min, and seconds in each of " +
        "the seven power zones by the FTP of the ride and the profile's zone bounds), heart rate (average, maximum, minimum, over the " +
        "seconds not paused) and cadence. laps: one per stretch of the ride in one workout step, with its average power and heart rate. " +
        "compliance: when the ride was played from a planned workout, for each step the planned length and power (the target times the " +
        "ride's FTP) against the time actually spent and the average power ridden, with the difference in watts and percent, plus totals " +
        "over the steps with a power target; otherwise it says why there is nothing to compare. By default no time series is included. " +
        "series true adds power, heart rate, cadence and target power averaged over steps of seriesStepSeconds (default 10, longer for a " +
        "very long ride so that no series has more than 1200 points); ask for it only when the numbers above are not enough, it is large.",
      input: {
        rideId: z.string().describe("The ride's id from list_rides, such as ride-1794996000000."),
        series: z.boolean().optional().describe("Include the time series. Default false."),
        seriesStepSeconds: z
          .number()
          .int()
          .min(1)
          .max(600)
          .optional()
          .describe("Seconds averaged into each point of the series. Default 10."),
      },
    },
    async ({ rideId, series, seriesStepSeconds }) => {
      if (!isValidRideId(rideId))
        throw new ToolError(`"${rideId}" is not a ride id; list_rides shows them`);
      const record = await services.rides.getRecord(rideId);
      if (record === null) throw new ToolError(`there is no ride ${rideId}`);

      const zones = (await services.profile.get()).zones.upperPercentOfFtp;
      const statistics: RideStatistics = rideStatistics(record, zones);
      const ranges = powerZoneRanges(record.ride.ftp, zones);
      const { secondsInZone, ...power } = statistics.power;

      const resolved = await services.rideWorkouts.resolve(record);
      const flat = resolved.workout === null ? [] : flattenWorkout(resolved.workout);
      const compliance =
        resolved.workout === null
          ? { available: false as const, reason: resolved.reason }
          : (() => {
              const result = rideCompliance(record, resolved.workout);
              return "problem" in result
                ? { available: false as const, reason: result.problem }
                : { available: true as const, ...result };
            })();

      const startedAt = record.ride.startEpochMillis;
      return {
        id: record.ride.id,
        startTime: new Date(startedAt).toISOString(),
        date: localDate(startedAt, timezone),
        ftp: record.ride.ftp,
        devices: record.ride.devices ?? [],
        workoutId: record.ride.workoutId ?? null,
        durationSeconds: statistics.durationSeconds,
        movingSeconds: statistics.movingSeconds,
        pausedSeconds: statistics.pausedSeconds,
        distanceKilometers: statistics.distanceKilometers,
        statistics: {
          power: {
            ...power,
            timeInZones: ranges.map((range, index) => ({
              zone: range.zone,
              minWatts: range.minWatts,
              maxWatts: range.maxWatts,
              seconds: secondsInZone[index] ?? 0,
            })),
          },
          heartRate: statistics.heartRate,
          cadence: statistics.cadence,
        },
        laps: record.laps.map((lap, index) => ({
          lap: index + 1,
          step: lap.stepIndex == null ? null : lap.stepIndex + 1,
          label: lap.stepIndex == null ? null : (flat[lap.stepIndex]?.label ?? null),
          startSeconds: lap.startSeconds,
          endSeconds: lap.endSeconds,
          durationSeconds: lap.endSeconds - lap.startSeconds,
          averageWatts: round(lap.averagePower, 1),
          averageHeartRate: round(lap.averageHeartRate, 1),
        })),
        compliance,
        ...(series === true ? { series: rideSeries(record.samples, seriesStepSeconds ?? 10) } : {}),
      };
    },
  );
}
