import {
  POWER_SKILL_DURATIONS,
  POWER_SKILLS,
  type PowerSkill,
  type PowerSkillDuration,
  type PowerSkillDurationRow,
  type PowerSkillEffort,
  type PowerSkillLevel,
  type PowerSkillsResponse,
} from "@overdrive/contracts";
import { levelAt } from "./levels.ts";

/**
 * The power skills (spec §6.3, ADR 0042) from the rides' stored curves: pure functions, no database and no clock.
 *
 * A window's best at a duration is the best mean-maximal power among the rides of the window; a tie goes to the later ride (as the
 * metrics' own best efforts do). A skill's level is the best level the window's efforts reach at any of its durations, each against
 * that duration's own thresholds.
 */

/** What one tablet ride gives: its day, its start (to settle a tie) and its best power at the durations of the skills. */
export interface SkillRide {
  rideId: string;
  /** The rider's local day of the ride. */
  date: string;
  startEpochMillis: number;
  /** Watts by duration in seconds, only the durations the ride is long enough for; empty for a ride with no power. */
  powers: ReadonlyMap<number, number>;
}

export const LEVELS_NOTE =
  "Levels are a rough guide from a table of watts per kilogram for men; they are not tied to your age or sex, so read them as a trend of your own numbers.";
export const LEVELS_NEED_WEIGHT_NOTE =
  "Levels need your body weight, which comes from your watch: until it has been sent, only the powers are shown.";
export const OUTDOOR_NOTE =
  "Only rides on the tablet count here: outdoor rides and workouts from your watch are not in the power curve.";

const round2 = (value: number): number => Math.round(value * 100) / 100;

interface Best {
  watts: number;
  date: string;
  rideId: string;
  start: number;
}

function bestAt(rides: readonly SkillRide[], seconds: number): Best | null {
  let best: Best | null = null;
  for (const ride of rides) {
    const watts = ride.powers.get(seconds);
    if (watts === undefined) continue;
    if (
      best === null ||
      watts > best.watts ||
      (watts === best.watts && ride.startEpochMillis > best.start)
    ) {
      best = { watts, date: ride.date, rideId: ride.rideId, start: ride.startEpochMillis };
    }
  }
  return best;
}

export interface PowerSkillsInput {
  /** Every tablet ride that started on or before `to` (local day), in any order. */
  rides: readonly SkillRide[];
  from: string;
  to: string;
  /** The rider's 7-day average weight, or null when not known. */
  weight: { kg: number; asOf: string } | null;
  generatedAt: string;
}

export function buildPowerSkills(input: PowerSkillsInput): PowerSkillsResponse {
  const { from, to, weight } = input;
  const all = input.rides.filter((ride) => ride.date <= to && ride.powers.size > 0);
  const inWindow = all.filter((ride) => ride.date >= from);

  const effortOf = (best: Best | null): PowerSkillEffort | null =>
    best === null
      ? null
      : {
          watts: best.watts,
          ...(weight === null ? {} : { wattsPerKg: round2(best.watts / weight.kg) }),
          date: best.date,
          rideId: best.rideId,
        };

  const durations: PowerSkillDurationRow[] = POWER_SKILL_DURATIONS.map((seconds) => ({
    seconds,
    window: effortOf(bestAt(inWindow, seconds)),
    allTime: effortOf(bestAt(all, seconds)),
  }));
  const byDuration = new Map(durations.map((row) => [row.seconds, row]));

  const skills: PowerSkill[] = POWER_SKILLS.map((definition) => {
    let level: PowerSkillLevel | null = null;
    if (weight !== null) {
      for (const seconds of POWER_SKILL_DURATIONS) {
        if (seconds < definition.fromSeconds || seconds > definition.toSeconds) continue;
        const effort = byDuration.get(seconds)?.window;
        if (effort == null || effort.wattsPerKg === undefined) continue;
        const reached = levelAt(seconds as PowerSkillDuration, effort.wattsPerKg);
        // The highest level wins; a tie goes to the shorter duration (the first met).
        if (level === null || reached.index > level.index) {
          level = {
            index: reached.index,
            name: reached.name,
            bestWattsPerKg: effort.wattsPerKg,
            ...(reached.nextLevelWattsPerKg === null
              ? {}
              : { nextLevelWattsPerKg: reached.nextLevelWattsPerKg }),
            atSeconds: seconds,
          };
        }
      }
    }
    return { ...definition, level };
  });

  return {
    generatedAt: input.generatedAt,
    window: { from, to, rideCount: inWindow.length },
    durations,
    ...(weight === null ? {} : { riderWeight: { kg: weight.kg, asOf: weight.asOf } }),
    skills,
    levelsNote: weight === null ? LEVELS_NEED_WEIGHT_NOTE : LEVELS_NOTE,
    outdoorNote: OUTDOOR_NOTE,
  };
}
