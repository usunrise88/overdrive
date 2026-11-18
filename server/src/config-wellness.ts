import { z } from "zod";
import { DEFAULT_SOURCE_ORDER, parseSourceOrder } from "./wellness/sources.ts";

/**
 * The wellness module's configuration (spec §6.2, §6.4, ADR 0023). Nothing here is a secret. Messages never repeat a value.
 */

/** Variables that are set but empty (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

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
export const wellnessConfigShape = {
  // Which source a daily figure is taken from: the first in the list that has one. Per-metric orders follow semicolons.
  WELLNESS_SOURCE_ORDER: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default(DEFAULT_SOURCE_ORDER)
      .transform((raw, ctx): string => {
        try {
          parseSourceOrder(raw);
          return raw;
        } catch (error) {
          ctx.addIssue({
            code: "custom",
            message: error instanceof Error ? error.message : "WELLNESS_SOURCE_ORDER is invalid",
          });
          return z.NEVER;
        }
      }),
  ),
  // How often intervals.icu's wellness is read, in hours (with an intervals.icu key only).
  WELLNESS_PULL_HOURS: wholeNumber("WELLNESS_PULL_HOURS", 1, 168, 6),
  // The window a personal baseline is taken over (spec §6.4: 28 to 60 days) and the nights a verdict needs.
  WELLNESS_BASELINE_DAYS: wholeNumber("WELLNESS_BASELINE_DAYS", 28, 60, 42),
  WELLNESS_NIGHTS_NEEDED: wholeNumber("WELLNESS_NIGHTS_NEEDED", 7, 28, 14),
};
