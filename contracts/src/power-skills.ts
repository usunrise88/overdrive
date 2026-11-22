import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isoDateToEpochDay, rangeDays } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: power skills (spec §6.3, §8.3, ADR 0042) -------------------------------------------------------------
// Hand-written mirror of schema/power-skills-response.schema.json, which is the source of truth; the fixtures test keeps the
// two honest. Kotlin's `PowerSkillsApiJson` reads the same bodies against the same fixtures.

/** The twelve durations of the power skills, in seconds: 15 s, 30 s, 1, 2, 3, 5, 10, 15, 20, 30, 45 and 60 minutes. */
export const POWER_SKILL_DURATIONS = [
  15, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600,
] as const;
export type PowerSkillDuration = (typeof POWER_SKILL_DURATIONS)[number];

export type PowerSkillId = "sprint" | "attack" | "climb" | "time-trial" | "endurance";

export interface PowerSkillDefinition {
  id: PowerSkillId;
  /** Our own names. */
  name: string;
  /** The shortest and the longest of its durations, in seconds; it spans every one of the twelve in between. */
  fromSeconds: PowerSkillDuration;
  toSeconds: PowerSkillDuration;
}

/** The five groups of durations, in the order they are shown. */
export const POWER_SKILLS: readonly PowerSkillDefinition[] = [
  { id: "sprint", name: "Sprint", fromSeconds: 15, toSeconds: 30 },
  { id: "attack", name: "Attack", fromSeconds: 60, toSeconds: 180 },
  { id: "climb", name: "Climb", fromSeconds: 300, toSeconds: 600 },
  { id: "time-trial", name: "Time trial", fromSeconds: 900, toSeconds: 1800 },
  { id: "endurance", name: "Endurance", fromSeconds: 2700, toSeconds: 3600 },
];

/** The nine levels' names, level 1 first: our own scale (ADR 0042), not Strava's. */
export const POWER_LEVEL_NAMES = [
  "Starting",
  "Casual",
  "Regular",
  "Capable",
  "Strong",
  "Very strong",
  "Competitive",
  "Excellent",
  "Elite",
] as const;

/** The window of `GET /v1/power-skills` when none is asked for: the last 56 days (eight weeks), today included. */
export const POWER_SKILLS_DEFAULT_DAYS = 56;
/** The longest window, in days with both ends included: three years. */
export const POWER_SKILLS_MAX_DAYS = 1096;
/** The most watts per kilogram that any figure may be (a plausibility bound of the schema). */
export const POWER_SKILLS_MAX_WATTS_PER_KG = 100;

/** The best power at a duration with where it came from. */
export interface PowerSkillEffort {
  watts: number;
  /** Present exactly when the response has `riderWeight`. */
  wattsPerKg?: number;
  /** The rider's local day of the ride. */
  date: string;
  rideId: string;
}

export interface PowerSkillDurationRow {
  seconds: PowerSkillDuration;
  /** The best among the rides of the window; null when no ride is long enough. */
  window: PowerSkillEffort | null;
  /** The best among all rides up to the end of the window. */
  allTime: PowerSkillEffort | null;
}

export interface PowerSkillLevel {
  /** 1 to 9. */
  index: number;
  name: string;
  /** The watts per kilogram at {@link atSeconds}. */
  bestWattsPerKg: number;
  /** What the same duration needs for the next level; absent at level 9. */
  nextLevelWattsPerKg?: number;
  /** The duration of the skill that gave the level. */
  atSeconds: PowerSkillDuration;
}

export interface PowerSkill {
  id: PowerSkillId;
  name: string;
  fromSeconds: PowerSkillDuration;
  toSeconds: PowerSkillDuration;
  /** Null when the weight is not known or the window has no effort at any of the skill's durations. */
  level: PowerSkillLevel | null;
}

/** The answer to `GET /v1/power-skills`. */
export interface PowerSkillsResponse {
  /** UTC instant. */
  generatedAt: string;
  window: { from: string; to: string; rideCount: number };
  /** The twelve durations in {@link POWER_SKILL_DURATIONS} order. */
  durations: PowerSkillDurationRow[];
  /** The 7-day average weight; absent when not known. */
  riderWeight?: { kg: number; asOf: string };
  skills: PowerSkill[];
  levelsNote: string;
  outdoorNote: string;
}

// ---- Validation ------------------------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

const checkPowerSkills: ValidateFunction<PowerSkillsResponse> = ajv.compile<PowerSkillsResponse>(
  JSON.parse(
    readFileSync(new URL("../schema/power-skills-response.schema.json", import.meta.url), "utf8"),
  ),
);

const MAX_REPORTED = 20;

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

const round2 = (value: number): number => Math.round(value * 100) / 100;

/**
 * What the schema cannot say about the answer (see its description): real dates and instants, a window of at most 1096 days, the
 * twelve durations in order, where each effort's day lies, `wattsPerKg` against the weight, the five skills in order and each level's
 * name, duration and thresholds' order.
 */
export function powerSkillsConsistencyErrors(response: PowerSkillsResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const { from, to } = response.window;
  let windowOk = true;
  for (const key of ["from", "to"] as const) {
    if (isoDateToEpochDay(response.window[key]) === null) {
      errors.push(`/window/${key} is not a real calendar date`);
      windowOk = false;
    }
  }
  if (windowOk) {
    const days = rangeDays(from, to) as number;
    if (days < 1) errors.push("/window/from is after /window/to");
    if (days > POWER_SKILLS_MAX_DAYS) {
      errors.push(`/window is longer than ${POWER_SKILLS_MAX_DAYS} days`);
    }
  }
  const weight = response.riderWeight;
  if (weight !== undefined && isoDateToEpochDay(weight.asOf) === null) {
    errors.push("/riderWeight/asOf is not a real calendar date");
  }

  const efforts = new Map<number, PowerSkillDurationRow>();
  for (const [index, row] of response.durations.entries()) {
    const at = `/durations/${index}`;
    if (row.seconds !== POWER_SKILL_DURATIONS[index]) {
      errors.push(`${at}/seconds is not the duration the contract puts here`);
    }
    efforts.set(row.seconds, row);
    for (const key of ["window", "allTime"] as const) {
      const effort = row[key];
      if (effort === null) continue;
      const where = `${at}/${key}`;
      if (isoDateToEpochDay(effort.date) === null) {
        errors.push(`${where}/date is not a real calendar date`);
      } else if (windowOk) {
        if (key === "window" && (effort.date < from || effort.date > to)) {
          errors.push(`${where}/date is outside the window`);
        }
        if (key === "allTime" && effort.date > to) {
          errors.push(`${where}/date is after the end of the window`);
        }
      }
      if (weight === undefined) {
        if (effort.wattsPerKg !== undefined) errors.push(`${where}/wattsPerKg needs riderWeight`);
      } else if (effort.wattsPerKg === undefined) {
        errors.push(`${where}/wattsPerKg is needed when riderWeight is given`);
      } else if (Math.abs(effort.wattsPerKg - round2(effort.watts / weight.kg)) > 0.0051) {
        errors.push(`${where}/wattsPerKg is not watts over the weight`);
      }
    }
    if (row.window !== null) {
      if (row.allTime === null) {
        errors.push(`${at}/allTime is needed when there is a window effort`);
      } else if (row.allTime.watts < row.window.watts) {
        errors.push(`${at}/allTime is lower than the window's best`);
      }
    }
    if (response.window.rideCount === 0 && row.window !== null) {
      errors.push(`${at}/window has an effort with no ride in the window`);
    }
  }

  for (const [index, skill] of response.skills.entries()) {
    const at = `/skills/${index}`;
    const definition = POWER_SKILLS[index];
    if (
      definition === undefined ||
      skill.id !== definition.id ||
      skill.name !== definition.name ||
      skill.fromSeconds !== definition.fromSeconds ||
      skill.toSeconds !== definition.toSeconds
    ) {
      errors.push(`${at} is not the skill the contract puts here`);
      continue;
    }
    const inSkill = POWER_SKILL_DURATIONS.filter(
      (d) => d >= definition.fromSeconds && d <= definition.toSeconds,
    );
    const reached = inSkill.some((d) => efforts.get(d)?.window != null);
    const judged = weight !== undefined && reached;
    const { level } = skill;
    if (level === null) {
      if (judged) errors.push(`${at}/level is needed: there is a weight and a window effort`);
      continue;
    }
    if (!judged)
      errors.push(`${at}/level needs a weight and a window effort at one of its durations`);
    if (level.name !== POWER_LEVEL_NAMES[level.index - 1]) {
      errors.push(`${at}/level/name is not the name of level ${level.index}`);
    }
    if (!(inSkill as readonly number[]).includes(level.atSeconds)) {
      errors.push(`${at}/level/atSeconds is not one of the skill's durations`);
    } else {
      const effort = efforts.get(level.atSeconds)?.window;
      if (effort == null) {
        errors.push(`${at}/level/atSeconds has no effort in the window`);
      } else if (effort.wattsPerKg !== level.bestWattsPerKg) {
        errors.push(`${at}/level/bestWattsPerKg is not the watts per kilogram at atSeconds`);
      }
    }
    if (level.index === 9) {
      if (level.nextLevelWattsPerKg !== undefined) {
        errors.push(`${at}/level/nextLevelWattsPerKg must be absent at level 9`);
      }
    } else if (level.nextLevelWattsPerKg === undefined) {
      errors.push(`${at}/level/nextLevelWattsPerKg is needed below level 9`);
    } else if (level.nextLevelWattsPerKg <= level.bestWattsPerKg) {
      errors.push(`${at}/level/nextLevelWattsPerKg is not above bestWattsPerKg`);
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validatePowerSkillsResponse(input: unknown): Validation<PowerSkillsResponse> {
  if (!checkPowerSkills(input)) return { ok: false, errors: plain(checkPowerSkills.errors) };
  const errors = powerSkillsConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
