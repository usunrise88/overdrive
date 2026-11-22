import {
  addDaysToIsoDate,
  DEFAULT_POWER_PROFILE_TABLE,
  isIsoDate,
  POWER_SKILL_DURATIONS,
  POWER_SKILLS_DEFAULT_DAYS,
  POWER_SKILLS_MAX_DAYS,
  type PowerSkillsResponse,
  rangeDays,
} from "@overdrive/contracts";
import { type MetricsService, rideNameOf } from "../metrics/service.ts";
import type { RideMetricsRow } from "../metrics/store.ts";
import type { ProfileStore } from "../profile/store.ts";
import { dayStartMillis, localDate } from "../time.ts";
import type { WellnessService } from "../wellness/service.ts";
import { buildPowerSkills, type SkillRide } from "./compute.ts";

/**
 * The power skills (spec §6.3, ADR 0042): the best power at twelve durations over a window of local days and over all time, and a
 * level for each of five groups of durations. It reads the stored curves of the rides on the tablet (the metrics' `ride_metrics`),
 * never a record, and the rider's weight from the watch; nothing is stored of its own. Outdoor rides and the watch's workouts are
 * not rides on the tablet and never feed it (spec §11.8).
 */

/** A window that cannot be served; the message names the parameter and the rule. */
export class PowerSkillsRangeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PowerSkillsRangeError";
  }
}

export interface PowerSkillsService {
  /**
   * The window for the optional `from` and `to`: `to` defaults to today and `from` to the 56th day back from `to`.
   * @throws PowerSkillsRangeError for dates that are not real, `from` after `to`, or more than 1096 days.
   */
  resolveWindow(from?: string, to?: string): { from: string; to: string };
  /** The answer for a window as {@link resolveWindow} gives it. */
  powerSkills(window: { from: string; to: string }): Promise<PowerSkillsResponse>;
}

export interface PowerSkillsOptions {
  metrics: Pick<MetricsService, "store" | "ensureComputed" | "whenIdle">;
  wellness: Pick<WellnessService, "riderWeight">;
  /** The profile: which table of watts per kilogram the levels use. */
  profile: Pick<ProfileStore, "get">;
  timezone: string;
  now: () => Date;
}

/** What a ride gives the skills: its best power at the twelve durations, from the curve stored beside the 25 of the contract. */
export function skillRideOf(row: RideMetricsRow, timezone: string): SkillRide {
  const powers = new Map<number, number>();
  const wanted = new Set<number>(POWER_SKILL_DURATIONS);
  for (const point of [...row.powerCurve, ...row.extraCurve]) {
    if (wanted.has(point.durationSec)) powers.set(point.durationSec, point.power);
  }
  return {
    rideId: row.rideId,
    rideName: rideNameOf(row),
    date: localDate(row.startEpochMillis, timezone),
    startEpochMillis: row.startEpochMillis,
    powers,
  };
}

export function createPowerSkills(options: PowerSkillsOptions): PowerSkillsService {
  const { metrics, wellness, profile, timezone, now } = options;

  function resolveWindow(from?: string, to?: string): { from: string; to: string } {
    if (to !== undefined && !isIsoDate(to)) {
      throw new PowerSkillsRangeError("to must be a real date, YYYY-MM-DD");
    }
    if (from !== undefined && !isIsoDate(from)) {
      throw new PowerSkillsRangeError("from must be a real date, YYYY-MM-DD");
    }
    const end = to ?? localDate(now(), timezone);
    const start = from ?? addDaysToIsoDate(end, 1 - POWER_SKILLS_DEFAULT_DAYS);
    const days = rangeDays(start, end) as number;
    if (days < 1) throw new PowerSkillsRangeError(`from ${start} is after to ${end}`);
    if (days > POWER_SKILLS_MAX_DAYS) {
      throw new PowerSkillsRangeError(
        `the window spans ${days} days; at most ${POWER_SKILLS_MAX_DAYS} (three years) are served`,
      );
    }
    return { from: start, to: end };
  }

  return {
    resolveWindow,

    async powerSkills(window) {
      await metrics.whenIdle();
      await metrics.ensureComputed();
      const generated = now();
      const rows = await metrics.store.listUntil(
        dayStartMillis(addDaysToIsoDate(window.to, 1), timezone),
      );
      const [weight, current] = await Promise.all([wellness.riderWeight(), profile.get()]);
      return buildPowerSkills({
        rides: rows.map((row) => skillRideOf(row, timezone)),
        from: window.from,
        to: window.to,
        weight,
        table: current.powerProfileTable ?? DEFAULT_POWER_PROFILE_TABLE,
        generatedAt: generated.toISOString(),
      });
    },
  };
}
