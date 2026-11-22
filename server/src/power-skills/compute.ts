import {
  POWER_LEVEL_NAMES,
  POWER_SKILL_DURATIONS,
  POWER_SKILLS,
  type PowerProfileTable,
  type PowerSkill,
  type PowerSkillDuration,
  type PowerSkillDurationRow,
  type PowerSkillEffort,
  type PowerSkillsResponse,
  type RiderWeight,
  skillLevelIndex,
} from "@overdrive/contracts";
import { scoreAt } from "./levels.ts";

/**
 * The power skills (spec §6.3, ADR 0042) from the rides' stored curves: pure functions, no database and no clock.
 *
 * A window's best at a duration is the best mean-maximal power among the rides of the window; a tie goes to the later ride (as the
 * metrics' own best efforts do). Each best is placed on its duration's level scale (a score, `levels.ts`) when the weight is known,
 * and a skill's level is the floor of the mean of the window's scores at its durations.
 */

/** What one tablet ride gives: its day, its start (to settle a tie), its name and its best power at the durations of the skills. */
export interface SkillRide {
  rideId: string;
  /** The name of the workout it was played from, or "Free ride". */
  rideName: string;
  /** The rider's local day of the ride. */
  date: string;
  startEpochMillis: number;
  /** Watts by duration in seconds, only the durations the ride is long enough for; empty for a ride with no power. */
  powers: ReadonlyMap<number, number>;
}

export const LEVELS_NOTE =
  "Levels are approximate: your watts per kilogram against published power-profile tables, read as a guide to how your own numbers move.";
export const LEVELS_NEED_WEIGHT_NOTE =
  "Levels need your body weight, which comes from your watch: until it has been sent, only the powers are shown.";
export const OUTDOOR_NOTE =
  "Only rides on the tablet count here: outdoor rides and workouts from your watch are not in the power curve.";

const round2 = (value: number): number => Math.round(value * 100) / 100;

interface Best {
  watts: number;
  ride: SkillRide;
}

function bestAt(rides: readonly SkillRide[], seconds: number): Best | null {
  let best: Best | null = null;
  for (const ride of rides) {
    const watts = ride.powers.get(seconds);
    if (watts === undefined) continue;
    if (
      best === null ||
      watts > best.watts ||
      (watts === best.watts && ride.startEpochMillis > best.ride.startEpochMillis)
    ) {
      best = { watts, ride };
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
  weight: RiderWeight | null;
  /** The table the levels are read against. */
  table: PowerProfileTable;
  generatedAt: string;
}

export function buildPowerSkills(input: PowerSkillsInput): PowerSkillsResponse {
  const { from, to, weight, table } = input;
  const all = input.rides.filter((ride) => ride.date <= to && ride.powers.size > 0);
  const inWindow = all.filter((ride) => ride.date >= from);

  const effortOf = (seconds: PowerSkillDuration, best: Best | null): PowerSkillEffort | null => {
    if (best === null) return null;
    const wattsPerKg = weight === null ? null : round2(best.watts / weight.kg);
    return {
      watts: best.watts,
      wattsPerKg,
      score: wattsPerKg === null ? null : scoreAt(table, seconds, wattsPerKg),
      date: best.ride.date,
      rideId: best.ride.rideId,
      rideName: best.ride.rideName,
    };
  };

  const durations: PowerSkillDurationRow[] = POWER_SKILL_DURATIONS.map((seconds) => ({
    seconds,
    window: effortOf(seconds, bestAt(inWindow, seconds)),
    allTime: effortOf(seconds, bestAt(all, seconds)),
  }));
  const byDuration = new Map(durations.map((row) => [row.seconds, row]));

  const skills: PowerSkill[] = POWER_SKILLS.map((definition) => {
    const scores: number[] = [];
    for (const seconds of POWER_SKILL_DURATIONS) {
      if (seconds < definition.fromSeconds || seconds > definition.toSeconds) continue;
      const score = byDuration.get(seconds)?.window?.score;
      if (score !== undefined && score !== null) scores.push(score);
    }
    if (scores.length === 0) return { ...definition, level: null };
    const index = skillLevelIndex(scores);
    return { ...definition, level: { index, name: POWER_LEVEL_NAMES[index - 1] as string } };
  });

  return {
    generatedAt: input.generatedAt,
    table,
    window: { from, to, rideCount: inWindow.length },
    durations,
    riderWeight: weight,
    skills,
    levelsNote: weight === null ? LEVELS_NEED_WEIGHT_NOTE : LEVELS_NOTE,
    outdoorNote: OUTDOOR_NOTE,
  };
}
