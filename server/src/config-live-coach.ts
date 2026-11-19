import { LIVE_AUDIO_FORMATS, LIVE_CUE_MAX_CHARS } from "@overdrive/contracts";
import { z } from "zod";

/**
 * The settings of the live coach and of the rider's own speech service (spec §12.1, ADR 0038), as a zod shape that `config.ts` spreads
 * into its schema. Every variable is optional. The live coach needs three things to speak: this feature on (the default), a language model
 * (`LLM_*` of ADR 0025, shared with the written insights) and a speech service (`TTS_*`); without the last two a session start says why the
 * coach is off and the ride is unaffected. Messages never repeat a value (the keys are secrets).
 *
 * The limits on talking (spec §12.1) are settings with the spec's defaults, and the server enforces them: the model is only ever asked to
 * write a line when they all allow one.
 */

/** Empty values (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const DEFAULT_LIVE_COACH_CUE_MAX_CHARS = LIVE_CUE_MAX_CHARS;
export const DEFAULT_LIVE_COACH_MIN_GAP_SECONDS = 60;
export const DEFAULT_LIVE_COACH_MAX_CUES_PER_HOUR = 12;
export const DEFAULT_LIVE_COACH_MAX_PER_TRIGGER_PER_STEP = 2;
export const DEFAULT_LIVE_COACH_TRIGGER_COOLDOWN_SECONDS = 180;
export const DEFAULT_LIVE_COACH_SILENT_FINAL_SECONDS = 15;
export const DEFAULT_LIVE_COACH_RECOVERY_MAX_CUES = 1;
export const DEFAULT_LIVE_COACH_RECOVERY_BELOW_PERCENT = 60;
export const DEFAULT_LIVE_COACH_LEAD_SECONDS = 6;
export const DEFAULT_LIVE_COACH_DEADLINE_SECONDS = 5;
export const DEFAULT_TTS_SPEED = 1;
export const DEFAULT_TTS_FORMAT = "mp3";
export const DEFAULT_TTS_TIMEOUT_SECONDS = 4;

// Visible ASCII, no spaces: the key goes into an Authorization header, the model and voice names into a JSON string.
const API_KEY = /^[\x21-\x7e]{1,512}$/;
const NAME = /^[\x21-\x7e]{1,128}$/;
// A language name or code goes into the prompt: letters, spaces and hyphens only, so it cannot carry an instruction.
const LANGUAGE = /^[A-Za-z][A-Za-z -]{0,38}[A-Za-z]$|^[A-Za-z]{2}$/;

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
export const liveCoachConfigShape = {
  // The master switch of the feature. A session start answers `feature-off` while it is false.
  LIVE_COACH_ENABLED: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default("true")
      .transform((value, ctx): boolean => {
        if (TRUE.has(value)) return true;
        if (FALSE.has(value)) return false;
        ctx.addIssue({ code: "custom", message: "LIVE_COACH_ENABLED must be true or false" });
        return z.NEVER;
      }),
  ),
  // The language of the cues: a name or a code. Default: INSIGHTS_LANGUAGE (en).
  LIVE_COACH_LANGUAGE: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .refine(
        (value) => LANGUAGE.test(value),
        "LIVE_COACH_LANGUAGE must be a language name or code made of letters, for example en, French or pt-BR",
      )
      .optional(),
  ),
  // The model that writes the cue, when it should not be LLM_MODEL (a small, fast one is enough for a sentence). Same endpoint and key.
  LIVE_COACH_LLM_MODEL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(NAME, "LIVE_COACH_LLM_MODEL must be 1 to 128 visible characters with no spaces")
      .optional(),
  ),

  // ---- The limits on talking (spec §12.1), in the order the service checks them (docs/adr/0038-live-coach.md) -------------
  // The longest cue, in characters (the contract's own limit is 80: this can only be lowered).
  LIVE_COACH_CUE_MAX_CHARS: wholeNumber(
    "LIVE_COACH_CUE_MAX_CHARS",
    20,
    LIVE_CUE_MAX_CHARS,
    DEFAULT_LIVE_COACH_CUE_MAX_CHARS,
  ),
  // The seconds of the final part of a step in which nothing is said, so that the countdown stays clear.
  LIVE_COACH_SILENT_FINAL_SECONDS: wholeNumber(
    "LIVE_COACH_SILENT_FINAL_SECONDS",
    0,
    120,
    DEFAULT_LIVE_COACH_SILENT_FINAL_SECONDS,
  ),
  // A recovery step (a target below LIVE_COACH_RECOVERY_BELOW_PERCENT of FTP, or labelled recovery) gets at most this many cues.
  LIVE_COACH_RECOVERY_MAX_CUES: wholeNumber(
    "LIVE_COACH_RECOVERY_MAX_CUES",
    0,
    5,
    DEFAULT_LIVE_COACH_RECOVERY_MAX_CUES,
  ),
  LIVE_COACH_RECOVERY_BELOW_PERCENT: wholeNumber(
    "LIVE_COACH_RECOVERY_BELOW_PERCENT",
    10,
    90,
    DEFAULT_LIVE_COACH_RECOVERY_BELOW_PERCENT,
  ),
  // The same trigger may speak at most this many times in one step, and not again within the cooldown.
  LIVE_COACH_MAX_PER_TRIGGER_PER_STEP: wholeNumber(
    "LIVE_COACH_MAX_PER_TRIGGER_PER_STEP",
    1,
    10,
    DEFAULT_LIVE_COACH_MAX_PER_TRIGGER_PER_STEP,
  ),
  LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: wholeNumber(
    "LIVE_COACH_TRIGGER_COOLDOWN_SECONDS",
    0,
    3600,
    DEFAULT_LIVE_COACH_TRIGGER_COOLDOWN_SECONDS,
  ),
  // The least time between any two cues.
  LIVE_COACH_MIN_GAP_SECONDS: wholeNumber(
    "LIVE_COACH_MIN_GAP_SECONDS",
    0,
    900,
    DEFAULT_LIVE_COACH_MIN_GAP_SECONDS,
  ),
  // The most cues in any rolling hour.
  LIVE_COACH_MAX_CUES_PER_HOUR: wholeNumber(
    "LIVE_COACH_MAX_CUES_PER_HOUR",
    1,
    120,
    DEFAULT_LIVE_COACH_MAX_CUES_PER_HOUR,
  ),

  // ---- Timing ---------------------------------------------------------------------------------------------------------
  // A line that is known beforehand (a step that is about to begin, a line at a second of the step) is triggered this many seconds early,
  // to absorb the delay from trigger to sound.
  LIVE_COACH_LEAD_SECONDS: wholeNumber(
    "LIVE_COACH_LEAD_SECONDS",
    0,
    20,
    DEFAULT_LIVE_COACH_LEAD_SECONDS,
  ),
  // A cue that is not ready this many seconds after its trigger is dropped, never played late.
  LIVE_COACH_DEADLINE_SECONDS: wholeNumber(
    "LIVE_COACH_DEADLINE_SECONDS",
    1,
    30,
    DEFAULT_LIVE_COACH_DEADLINE_SECONDS,
  ),

  // ---- The rider's own speech service ---------------------------------------------------------------------------------------
  // An OpenAI-compatible endpoint: POST {TTS_BASE_URL}/audio/speech. The cue's text (at most 80 characters, which may quote the rider's
  // power or heart rate) goes there and nothing else. The address is validated like BROUTER_URL: http or https, no credentials, no query.
  TTS_BASE_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string => {
        try {
          const url = new URL(raw);
          if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("scheme");
          if (url.username !== "" || url.password !== "") throw new Error("credentials");
          if (url.search !== "" || url.hash !== "") throw new Error("query");
          return `${url.origin}${url.pathname.replace(/\/+$/, "")}`;
        } catch {
          ctx.addIssue({
            code: "custom",
            message:
              "TTS_BASE_URL must be an http(s) address with no credentials, query or fragment, such as https://tts.example.com/v1 (the key is TTS_API_KEY)",
          });
          return z.NEVER;
        }
      })
      .optional(),
  ),
  TTS_API_KEY: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(API_KEY, "TTS_API_KEY must be 1 to 512 visible characters with no spaces")
      .optional(),
  ),
  TTS_MODEL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(NAME, "TTS_MODEL must be 1 to 128 visible characters with no spaces")
      .optional(),
  ),
  TTS_VOICE: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(NAME, "TTS_VOICE must be 1 to 128 visible characters with no spaces")
      .optional(),
  ),
  // How fast the voice speaks, 0.25 to 4 (the OpenAI range), 1 for normal.
  TTS_SPEED: z.preprocess(
    blankIsUnset,
    z.coerce
      .number({ error: "TTS_SPEED must be a number such as 1 or 1.1" })
      .min(0.25, "TTS_SPEED must be at least 0.25")
      .max(4, "TTS_SPEED must be at most 4")
      .default(DEFAULT_TTS_SPEED),
  ),
  // The audio the speech service is asked for. The ride app plays mp3, opus, aac, flac and wav.
  TTS_FORMAT: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default(DEFAULT_TTS_FORMAT)
      .refine(
        (value) => (LIVE_AUDIO_FORMATS as readonly string[]).includes(value),
        `TTS_FORMAT must be one of ${LIVE_AUDIO_FORMATS.join(", ")}`,
      )
      .transform((value) => value as (typeof LIVE_AUDIO_FORMATS)[number]),
  ),
  // How long one speech request may take, in seconds (a cue is dropped after LIVE_COACH_DEADLINE_SECONDS in any case).
  TTS_TIMEOUT_SECONDS: wholeNumber("TTS_TIMEOUT_SECONDS", 1, 60, DEFAULT_TTS_TIMEOUT_SECONDS),
};

/** The settings as `loadConfig` returns them (the defaults applied; the address, key, model and voice absent when not set). */
export type LiveCoachConfig = z.infer<z.ZodObject<typeof liveCoachConfigShape>>;

/** What the services read: every field optional, with the defaults above standing in for a missing one. */
export type LiveCoachSettings = Partial<LiveCoachConfig>;
