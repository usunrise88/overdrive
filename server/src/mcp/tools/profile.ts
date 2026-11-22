import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  COGGAN_ZONE_UPPER_PERCENT,
  type Profile,
  powerZoneRanges,
  WEEKDAYS,
} from "@overdrive/contracts";
import { z } from "zod";
import { ProfilePatchError } from "../../profile/store.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/** The profile as the tools show it: the stored profile, with the numbers derived from it. */
export function describeProfile(profile: Profile) {
  const known = WEEKDAYS.flatMap((day) => {
    const minutes = profile.weeklyAvailability[day];
    return minutes === null ? [] : [minutes];
  });
  const bounds = profile.zones.upperPercentOfFtp;
  return {
    ftp: profile.ftp,
    wPrimeKj: profile.wPrimeKj,
    maxHeartRate: profile.maxHeartRate ?? null,
    thresholdHeartRate: profile.thresholdHeartRate ?? null,
    estimatedMaxHeartRate: profile.estimatedMaxHeartRate ?? null,
    estimatedThresholdHeartRate: profile.estimatedThresholdHeartRate ?? null,
    // The weight keys are shown only when they have a value (spec §12.5, ADR 0034).
    ...(profile.bikeWeightKg == null ? {} : { bikeWeightKg: profile.bikeWeightKg }),
    ...(profile.riderWeight == null ? {} : { riderWeight: profile.riderWeight }),
    // Which table the power skills' levels are read against: men unless the owner chose the women's (ADR 0042).
    powerProfileTable: profile.powerProfileTable ?? "men",
    zones: {
      upperPercentOfFtp: bounds,
      isDefault: bounds.every((bound, i) => bound === COGGAN_ZONE_UPPER_PERCENT[i]),
      // Null until there is an FTP to take the percentages of.
      watts: profile.ftp === null ? null : powerZoneRanges(profile.ftp, bounds),
    },
    weeklyAvailability: profile.weeklyAvailability,
    weeklyAvailabilityTotalMinutes: known.reduce((a, b) => a + b, 0),
    weekdaysWithUnknownAvailability: WEEKDAYS.length - known.length,
    goals: profile.goals,
    physics: profile.physics,
    updatedAt: profile.updatedAt,
  };
}

const minutes = z.number().int().min(0).max(1440).nullable().optional();

export function registerProfileTools(server: McpServer, ctx: ToolContext): void {
  const { profile } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "get_profile",
      title: "Get the rider's profile",
      kind: "read",
      description:
        "The rider's profile: FTP in watts (null when not set), W′ in kJ (null: the W′ balance uses 20 kJ), the six upper bounds of power zones 1 to 6 as whole percent of FTP " +
        "(Coggan's 55, 75, 90, 105, 120, 150 unless changed; zone 7 has no upper bound) with the zone ranges in watts worked out " +
        "from the FTP, minutes available on each weekday (null where unknown, 0 for a rest day), the rider's goals as short free-text " +
        "lines, and the physics constants the ride app uses for virtual speed (system mass in kg, CdA, Crr, air density, drivetrain " +
        "efficiency). Also the rider's maximum and threshold heart rate in bpm (null when not set) and the read-only estimates the " +
        "rides give (estimatedMaxHeartRate: the highest heart rate in any ride; estimatedThresholdHeartRate: 95% of the best 20-minute " +
        "average heart rate in any ride; null when the rides do not say), which scale the heart-rate load of workouts from the watch. " +
        "When set, bikeWeightKg (the bike, kit and bottles in kg) and, when the rider has weighed in the last seven days, riderWeight " +
        "(read-only: the 7-day average kg from Health Connect, the date of the newest weighing and the days that had one); with both the " +
        "tablet rides with their sum as the system mass of the virtual speed, otherwise with physics.systemMassKg. " +
        "powerProfileTable (men or women, men by default) says which table of watts per kilogram the levels of get_power_skills are read against. " +
        "Also when it last changed. Use it before planning: workouts are written as fractions of FTP, so the FTP " +
        "turns them into watts.",
      input: {},
    },
    async () => describeProfile(await profile.get()),
  );

  defineTool(
    server,
    ctx,
    {
      name: "update_profile",
      title: "Update the rider's profile",
      kind: "write",
      description:
        "Changes parts of the rider's profile and returns the whole stored profile (as get_profile) and which parts changed. " +
        "Name only what should change; everything not named stays. ftp: watts (30 to 2000), null clears it. " +
        "wPrimeKj: the rider's W′ in kilojoules (1 to 100), used by the W′ balance with the FTP as critical power; null goes back to the default of 20 kJ. " +
        "maxHeartRate (100 to 230) and thresholdHeartRate (80 to 220, below the maximum): whole bpm, null clears one; they scale the " +
        "heart-rate load of the watch's workouts and take the place of the estimates from the rides. " +
        "zones: all six upper bounds in whole percent of FTP, strictly increasing, or null to go back to Coggan's. " +
        "weeklyAvailability: minutes (0 to 1440) for the weekdays named (mon to sun), null makes a day unknown again. " +
        "goals: the complete new list of short lines (at most 10, each at most 200 characters), replacing the old one; [] clears it. " +
        "physics: any of systemMassKg (rider, bike and kit together), cdA, crr, airDensity, drivetrainEfficiency (0 to 1). " +
        "bikeWeightKg: the bike, kit and bottles in kg (above 0, at most 100), null clears it; once it is set the tablet's system mass is the " +
        "rider's 7-day average weight plus this, while the rider has weighed within the week (the rider's weight itself cannot be set here). " +
        "Ask the rider before changing the FTP or the physics: they change what the tablet shows and holds. " +
        "A change that is not valid is refused whole and nothing is changed.",
      input: {
        ftp: z
          .number()
          .int()
          .nullable()
          .optional()
          .describe("Watts, 30 to 2000, or null to clear."),
        wPrimeKj: z
          .number()
          .nullable()
          .optional()
          .describe("W′ in kilojoules, 1 to 100, or null for the default of 20."),
        maxHeartRate: z
          .number()
          .int()
          .nullable()
          .optional()
          .describe("Maximum heart rate in bpm, 100 to 230, or null to clear."),
        thresholdHeartRate: z
          .number()
          .int()
          .nullable()
          .optional()
          .describe(
            "Threshold heart rate in bpm, 80 to 220 and below the maximum, or null to clear.",
          ),
        bikeWeightKg: z
          .number()
          .nullable()
          .optional()
          .describe("The bike, kit and bottles in kg, above 0 and at most 100, or null to clear."),
        powerProfileTable: z
          .enum(["men", "women"])
          .nullable()
          .optional()
          .describe(
            "Which table of watts per kilogram the power skills' levels use: men (the default) or women; null goes back to the default. The rider's own choice: change it only when asked.",
          ),
        zones: z
          .strictObject({
            upperPercentOfFtp: z
              .array(z.number().int())
              .length(6)
              .describe("Six whole percentages of FTP, strictly increasing, 1 to 300."),
          })
          .nullable()
          .optional()
          .describe("Replaces the zones, or null to reset to Coggan's."),
        weeklyAvailability: z
          .strictObject({
            mon: minutes,
            tue: minutes,
            wed: minutes,
            thu: minutes,
            fri: minutes,
            sat: minutes,
            sun: minutes,
          })
          .optional()
          .describe("Minutes per weekday; only the days named change."),
        goals: z.array(z.string()).optional().describe("The whole new list of goals."),
        physics: z
          .strictObject({
            systemMassKg: z.number().optional(),
            cdA: z.number().optional(),
            crr: z.number().optional(),
            airDensity: z.number().optional(),
            drivetrainEfficiency: z.number().optional(),
          })
          .optional()
          .describe("Only the constants named change."),
      },
    },
    async (args) => {
      // Absent keys stay absent: a patch says what to change by naming it.
      const patch = JSON.parse(JSON.stringify(args)) as Record<string, unknown>;
      if (Array.isArray(patch.goals)) {
        patch.goals = patch.goals.map((goal) => (typeof goal === "string" ? goal.trim() : goal));
      }
      try {
        const { profile: stored, changed } = await profile.update(patch);
        ctx.log.info({ tool: "update_profile", changed }, "profile changed through the MCP");
        return { changed, ...describeProfile(stored) };
      } catch (error) {
        if (error instanceof ProfilePatchError) {
          throw new ToolError(`profile does not validate: ${error.errors.slice(0, 5).join("; ")}`);
        }
        throw error;
      }
    },
  );
}
