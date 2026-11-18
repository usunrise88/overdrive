import { z } from "zod";
import { parseClock } from "./zoned.ts";

/**
 * The settings of the written insights, the alerts and the language-model endpoint (spec §6.5, §6.6, §12.3, ADR 0025), as a zod
 * shape that `config.ts` spreads into its schema. Every variable is optional: without `LLM_BASE_URL` and `LLM_MODEL` the notes are
 * written from templates and no health data leaves the server. Messages never repeat a value (the key is a secret).
 */

/** Empty values (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const DEFAULT_INSIGHT_MORNING_TIME = "07:00";
export const DEFAULT_INSIGHTS_LANGUAGE = "en";
export const DEFAULT_LLM_TIMEOUT_SECONDS = 60;
export const DEFAULT_ALERT_STREAK_DAYS = 3;
export const DEFAULT_ALERT_MISSED_SESSIONS = 2;
export const DEFAULT_ALERT_LOAD_ABOVE_PERCENT = 30;
export const DEFAULT_ALERT_LOAD_BELOW_PERCENT = 40;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
// Visible ASCII, no spaces: the key goes into an Authorization header, the model name into a JSON string.
const API_KEY = /^[\x21-\x7e]{1,512}$/;
const MODEL = /^[\x21-\x7e]{1,128}$/;
// A language name or code goes into the prompt: letters, spaces and hyphens only, so it cannot carry an instruction.
const LANGUAGE = /^[A-Za-z][A-Za-z -]{0,38}[A-Za-z]$|^[A-Za-z]{2}$/;
const DENY_WORD = /^[^\s,;][^,;]{0,38}$/;

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

const TRUE = new Set(["true", "1", "on", "yes"]);
const FALSE = new Set(["false", "0", "off", "no"]);

/** The fields `config.ts` adds to its schema. */
export const insightsConfigShape = {
  // The master switch: with false no note or alert is written and none is sent (the ones already stored are still served).
  INSIGHTS_ENABLED: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default("true")
      .transform((value, ctx): boolean => {
        if (TRUE.has(value)) return true;
        if (FALSE.has(value)) return false;
        ctx.addIssue({ code: "custom", message: "INSIGHTS_ENABLED must be true or false" });
        return z.NEVER;
      }),
  ),
  // The endpoint that writes the text: any OpenAI-compatible chat-completions server. Health data leaves the server only to this
  // address. On only when LLM_BASE_URL and LLM_MODEL are both set; the key is optional (some local servers need none).
  LLM_BASE_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string => {
        let url: URL;
        try {
          url = new URL(raw);
        } catch {
          ctx.addIssue({
            code: "custom",
            message: "LLM_BASE_URL must be an address such as https://llm.example.com/v1",
          });
          return z.NEVER;
        }
        const local = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
        if (url.protocol !== "https:" && !local) {
          ctx.addIssue({
            code: "custom",
            message:
              "LLM_BASE_URL must start with https:// (http only for localhost): the rider's health data is sent there",
          });
          return z.NEVER;
        }
        if (url.username !== "" || url.password !== "" || url.search !== "" || url.hash !== "") {
          ctx.addIssue({
            code: "custom",
            message:
              "LLM_BASE_URL must not contain credentials, a query or a fragment (the key is LLM_API_KEY)",
          });
          return z.NEVER;
        }
        return `${url.origin}${url.pathname.replace(/\/+$/, "")}`;
      })
      .optional(),
  ),
  LLM_API_KEY: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(API_KEY, "LLM_API_KEY must be 1 to 512 visible characters with no spaces")
      .optional(),
  ),
  LLM_MODEL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(MODEL, "LLM_MODEL must be 1 to 128 visible characters with no spaces")
      .optional(),
  ),
  // How long one request to the endpoint may take, in seconds.
  LLM_TIMEOUT_SECONDS: wholeNumber("LLM_TIMEOUT_SECONDS", 5, 600, DEFAULT_LLM_TIMEOUT_SECONDS),
  // The language of the notes: a name or a code ("en", "French", "pt-BR"), put in the prompt. The fallback template is English.
  INSIGHTS_LANGUAGE: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default(DEFAULT_INSIGHTS_LANGUAGE)
      .refine(
        (value) => LANGUAGE.test(value),
        "INSIGHTS_LANGUAGE must be a language name or code made of letters, for example en, French or pt-BR",
      ),
  ),
  // When the morning note is written, in TIMEZONE (HH:MM), or off. The morning alerts are checked at this time too.
  INSIGHT_MORNING_TIME: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default(DEFAULT_INSIGHT_MORNING_TIME)
      .refine(
        (value) => value === "off" || parseClock(value) !== null,
        `INSIGHT_MORNING_TIME must be HH:MM (24 hours) or off, for example ${DEFAULT_INSIGHT_MORNING_TIME}`,
      ),
  ),
  // Words that make a written note unacceptable, added to the built-in list (comma-separated, case-insensitive, whole words).
  INSIGHT_DENY_WORDS: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string[] => {
        const words = raw
          .split(",")
          .map((word) => word.trim())
          .filter((word) => word !== "");
        if (words.length > 100 || words.some((word) => !DENY_WORD.test(word))) {
          ctx.addIssue({
            code: "custom",
            message:
              "INSIGHT_DENY_WORDS must be at most 100 words or phrases of up to 40 characters, separated by commas",
          });
          return z.NEVER;
        }
        return words;
      })
      .optional(),
  ),
  // Deviation alerts: a metric outside its personal baseline, in the adverse direction, for this many mornings in a row.
  ALERT_STREAK_DAYS: wholeNumber("ALERT_STREAK_DAYS", 2, 14, DEFAULT_ALERT_STREAK_DAYS),
  // Plan drift: this many planned sessions missed in the last 7 days, or the load of those days this far above or below the plan.
  ALERT_MISSED_SESSIONS: wholeNumber("ALERT_MISSED_SESSIONS", 1, 7, DEFAULT_ALERT_MISSED_SESSIONS),
  ALERT_LOAD_ABOVE_PERCENT: wholeNumber(
    "ALERT_LOAD_ABOVE_PERCENT",
    5,
    300,
    DEFAULT_ALERT_LOAD_ABOVE_PERCENT,
  ),
  ALERT_LOAD_BELOW_PERCENT: wholeNumber(
    "ALERT_LOAD_BELOW_PERCENT",
    5,
    95,
    DEFAULT_ALERT_LOAD_BELOW_PERCENT,
  ),
};

/** The settings as `loadConfig` returns them (all of them present, defaults applied). */
export type InsightsConfig = z.infer<z.ZodObject<typeof insightsConfigShape>>;

/** What the services read: every field optional, with the defaults above standing in for a missing one. */
export type InsightsSettings = Partial<InsightsConfig>;
