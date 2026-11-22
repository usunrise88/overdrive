import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { PowerProfileTable, RiderWeight, Validation } from "./index.ts";
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

/** The nine levels' words, level 1 first: our own ordinal scale (ADR 0042, design/companion.md §6.11), not Strava's. */
export const POWER_LEVEL_NAMES = [
  "Starting",
  "Casual",
  "Regular",
  "Fit",
  "Strong",
  "Very strong",
  "Racer",
  "Elite",
  "World class",
] as const;

/** The window of `GET /v1/power-skills` when none is asked for: the last 56 days (eight weeks), today included. */
export const POWER_SKILLS_DEFAULT_DAYS = 56;
/** The longest window, in days with both ends included: three years. */
export const POWER_SKILLS_MAX_DAYS = 1096;

/** The best power at a duration with where it came from. */
export interface PowerSkillEffort {
  watts: number;
  /** Null exactly when the response's `riderWeight` is. */
  wattsPerKg: number | null;
  /** The watts per kilogram on this duration's level scale, 0 to 9, two decimals; null exactly when `riderWeight` is. */
  score: number | null;
  /** The rider's local day of the ride. */
  date: string;
  rideId: string;
  /** The name of the workout the ride was played from, or "Free ride". */
  rideName: string;
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
  /** The table the levels were read against. */
  table: PowerProfileTable;
  window: { from: string; to: string; rideCount: number };
  /** The twelve durations in {@link POWER_SKILL_DURATIONS} order. */
  durations: PowerSkillDurationRow[];
  /** The 7-day average weight, or null when not known. */
  riderWeight: RiderWeight | null;
  skills: PowerSkill[];
  levelsNote: string;
  outdoorNote: string;
}

/** The level of a skill from the scores of its durations: their mean rounded down, held between 1 and 9. */
export function skillLevelIndex(scores: readonly number[]): number {
  const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
  return Math.min(9, Math.max(1, Math.floor(mean + 1e-9)));
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
 * twelve durations in order, where each effort's day lies, `wattsPerKg` and `score` against the weight, the five skills in order and
 * each level against the scores it is the mean of.
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
  if (weight !== null && isoDateToEpochDay(weight.date) === null) {
    errors.push("/riderWeight/date is not a real calendar date");
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
      if (weight === null) {
        if (effort.wattsPerKg !== null) errors.push(`${where}/wattsPerKg needs riderWeight`);
        if (effort.score !== null) errors.push(`${where}/score needs riderWeight`);
      } else {
        if (effort.wattsPerKg === null) {
          errors.push(`${where}/wattsPerKg is needed when riderWeight is given`);
        } else if (Math.abs(effort.wattsPerKg - round2(effort.watts / weight.kg)) > 0.0051) {
          errors.push(`${where}/wattsPerKg is not watts over the weight`);
        }
        if (effort.score === null) {
          errors.push(`${where}/score is needed when riderWeight is given`);
        }
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
    const scores = POWER_SKILL_DURATIONS.filter(
      (d) => d >= definition.fromSeconds && d <= definition.toSeconds,
    ).flatMap((d) => {
      const effort = efforts.get(d)?.window;
      return effort == null || effort.score === null ? [] : [effort.score];
    });
    const judged = weight !== null && scores.length > 0;
    const { level } = skill;
    if (level === null) {
      if (judged) errors.push(`${at}/level is needed: there is a weight and a window effort`);
      continue;
    }
    if (!judged) {
      errors.push(`${at}/level needs a weight and a window effort at one of its durations`);
      continue;
    }
    if (level.index !== skillLevelIndex(scores)) {
      errors.push(`${at}/level/index is not the mean of the scores of its durations, rounded down`);
    }
    if (level.name !== POWER_LEVEL_NAMES[level.index - 1]) {
      errors.push(`${at}/level/name is not the name of level ${level.index}`);
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validatePowerSkillsResponse(input: unknown): Validation<PowerSkillsResponse> {
  if (!checkPowerSkills(input)) return { ok: false, errors: plain(checkPowerSkills.errors) };
  const errors = powerSkillsConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
