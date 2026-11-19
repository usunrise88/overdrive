import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { addDaysToIsoDate, isIsoDate, isValidRideId, powerZoneRanges } from "@overdrive/contracts";
import { z } from "zod";
import {
  flattenWorkout,
  type RideStatistics,
  rideCompliance,
  rideSeries,
  rideStatistics,
  stallGuardSummary,
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

/** What the ride tools expose to the tools that build on them (`list_activities`, `get_activity`). */
export interface RideTools {
  /** A ride's row of `list_rides`. */
  describeListing: (ride: RideListing) => Promise<Record<string, unknown>>;
  /** What `get_ride` answers. */
  getRide: (args: {
    rideId: string;
    series?: boolean | undefined;
    seriesStepSeconds?: number | undefined;
  }) => Promise<unknown>;
}

export function registerRideTools(server: McpServer, ctx: ToolContext): RideTools {
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
        "DEPRECATED, kept for one more release: list_activities answers the same and also lists the workouts recorded on the watch. " +
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

  const getRide = defineTool(
    server,
    ctx,
    {
      name: "get_ride",
      title: "Get one ride",
      kind: "read",
      description:
        "DEPRECATED, kept for one more release: get_activity answers the same for a ride (and for a workout from the watch). " +
        "One ride in detail, worked out by the server from the recorded samples. statistics: power (average, normalised, maximum, minimum, " +
        "variability index, intensity factor, load, work in kJ, best average power over 5 s, 1 min, 5 min and 20 min, and seconds in each of " +
        "the seven power zones by the FTP of the ride and the profile's zone bounds), heart rate (average, maximum, minimum, over the " +
        "seconds not paused) and cadence. laps: one per stretch of the ride in one workout step, with its average power and heart rate. " +
        "compliance: when the ride was played from a planned workout, for each step the planned length and power (the target times the " +
        "ride's FTP) against the time actually spent and the average power ridden, with the difference in watts and percent, plus totals " +
        "over the steps with a power target; a heart-rate hold step (type hr) has no planned power and instead says how long the heart rate was inside its band " +
        "(heartRate: the target, the band, the seconds judged and inside it, the percent); otherwise it says why there is nothing to compare. " +
        "stallGuard: how many times the ERG stall guard took hold (cadence fell below its floor and the trainer was held at 70% of the target until the rider was " +
        "pedalling again), in which steps and at which minutes; 0 when it never did. By default no time series is included. " +
        "series true adds power, heart rate, cadence and target power averaged over steps of seriesStepSeconds (default 10, longer for a " +
        "very long ride so that no series has more than 1200 points); ask for it only when the numbers above are not enough, it is large. " +
        "powerCurve: the best average power over each of 25 durations from 1 s to 2 h in which the ride is long enough, a pause or a missing reading counting as 0 W. " +
        "aerobicDrift: for a steady stretch of at least 40 minutes with power and heart rate (the whole ride or one long steady step), the percent the efficiency " +
        "factor (normalised power over average heart rate) fell from the first half to the second, positive when heart rate rose against power; otherwise it says why there is none. " +
        "wPrimeBalance: the W′ balance model (Skiba 2012) with critical power taken as the FTP and W′ from the profile (20 kJ when unset): the lowest balance in kJ, when, and " +
        "how much of W′ it used; below 0 means CP or W′ are set too low for this rider. " +
        "variantRidden (present and true only when the rider chose the session's easier version at the start: the laps and compliance are then against that version) and " +
        "route (the route's id and name, present only when the ride followed one). " +
        "repeatOf: when the ride was played from a workout that has been ridden more than once (same structure, whatever it is called), its attempt number among the " +
        "attempts, how many there are, and the attempt before it with the difference in average heart rate at the same power (this ride minus that one, bpm: " +
        "lower at the same power is fitter); null otherwise. Heart rate also moves with heat, sleep and caffeine: one comparison proves little, list_repeats and get_repeat give the trend.",
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

      const resolved = await services.rideWorkouts.resolve(record.ride);
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

      const computed = await services.metrics.rideMetrics(rideId, { wbal: true }, record);
      const repeatOf = await services.repeats.repeatOf(rideId);

      const startedAt = record.ride.startEpochMillis;
      return {
        id: record.ride.id,
        startTime: new Date(startedAt).toISOString(),
        date: localDate(startedAt, timezone),
        ftp: record.ride.ftp,
        devices: record.ride.devices ?? [],
        workoutId: record.ride.workoutId ?? null,
        // The easier version of the session was ridden (spec §11.5), and the route followed (spec §11.4): facts about the day.
        ...(record.ride.variantRidden === true ? { variantRidden: true } : {}),
        ...(record.ride.routeId == null
          ? {}
          : { route: { id: record.ride.routeId, name: record.ride.routeName ?? null } }),
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
        stallGuard: stallGuardSummary(record, resolved.workout),
        powerCurve: computed?.powerCurve ?? [],
        aerobicDrift: computed?.drift ?? null,
        wPrimeBalance: computed?.wPrimeBalance ?? null,
        repeatOf,
        ...(series === true ? { series: rideSeries(record.samples, seriesStepSeconds ?? 10) } : {}),
      };
    },
  );

  return { describeListing, getRide };
}
