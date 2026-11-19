import { z } from "zod";

/**
 * The settings of the coach notebook and of the plan events (spec §11.6, §12.2, ADR 0035), as a zod shape that `config.ts` spreads into
 * its schema. Every variable is optional; the defaults stand in for a missing one.
 */

/** Empty values (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

/** The cap on the whole notebook, in characters (UTF-16 code units, the length of a string). */
export const DEFAULT_NOTEBOOK_MAX_CHARS = 12_000;
/** The cap on the dated log, one of the sections. */
export const DEFAULT_NOTEBOOK_LOG_MAX_CHARS = 3_000;
/** How long after midnight a day is given before it is checked for a session that was not ridden (a late upload, a tablet offline). */
export const DEFAULT_PLAN_EVENT_DAY_END_GRACE_MINUTES = 180;

const wholeNumber = (name: string, min: number, max: number, fallback: number) =>
  z.preprocess(
    blankIsUnset,
    z.coerce
      .number({ error: `${name} must be a whole number` })
      .int(`${name} must be a whole number`)
      .min(min, `${name} must be at least ${min}`)
      .max(max, `${name} must be at most ${max}`)
      .default(fallback),
  );

/** The fields `config.ts` adds to its schema. */
export const coachConfigShape = {
  // The most the whole coach notebook (all its sections) may hold, in characters. An edit that would pass it is answered with how to
  // condense, not applied; the agent's context is the reason for a cap at all.
  NOTEBOOK_MAX_CHARS: wholeNumber("NOTEBOOK_MAX_CHARS", 2000, 100_000, DEFAULT_NOTEBOOK_MAX_CHARS),
  // The most the dated log may hold: when an entry passes it the agent is asked to condense the old entries into the sections.
  NOTEBOOK_LOG_MAX_CHARS: wholeNumber(
    "NOTEBOOK_LOG_MAX_CHARS",
    500,
    50_000,
    DEFAULT_NOTEBOOK_LOG_MAX_CHARS,
  ),
  // A planned day is checked for a session that was not ridden this many minutes after local midnight, so a ride uploaded late is
  // not taken for a missed one.
  PLAN_EVENT_DAY_END_GRACE_MINUTES: wholeNumber(
    "PLAN_EVENT_DAY_END_GRACE_MINUTES",
    0,
    720,
    DEFAULT_PLAN_EVENT_DAY_END_GRACE_MINUTES,
  ),
};

/** The settings as `loadConfig` returns them (all of them present, defaults applied). */
export type CoachConfig = z.infer<z.ZodObject<typeof coachConfigShape>>;

/** What the services read: every field optional, with the defaults above standing in for a missing one. */
export type CoachSettings = Partial<CoachConfig>;
