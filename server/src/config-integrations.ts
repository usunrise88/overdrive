import { z } from "zod";
import { parseClock } from "./zoned.ts";

/**
 * The settings of the Google Calendar mirror and the Telegram bot (ADR 0019), as a zod shape that
 * `config.ts` spreads into its schema. Every variable is optional: the calendar is off unless all
 * four `GOOGLE_*` credentials are set, the bot is off without `TELEGRAM_BOT_TOKEN`. Messages never
 * repeat a value, because most of these are secrets.
 */

/** Empty values (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const DEFAULT_GOOGLE_API_BASE_URL = "https://www.googleapis.com/calendar/v3";
export const DEFAULT_GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
export const DEFAULT_TELEGRAM_BASE_URL = "https://api.telegram.org";

export const DEFAULT_WORKOUT_START = "07:00";
export const DEFAULT_REMINDER_EVENING_TIME = "20:00";
export const DEFAULT_REMINDER_LEAD_MINUTES = 60;
export const DEFAULT_WEEKLY_SUMMARY_DAY = "sun";
export const DEFAULT_WEEKLY_SUMMARY_TIME = "18:00";

// Visible ASCII, no spaces: these go into a form body, an Authorization header or a URL path.
const SECRET = /^[\x21-\x7e]{1,512}$/;
const CALENDAR_ID = /^[A-Za-z0-9._@%+-]{1,256}$/;
const BOT_TOKEN = /^\d{3,20}:[A-Za-z0-9_-]{20,100}$/;
const CHAT_ID = /^-?\d{1,18}$/;

function secret(name: string, hint: string) {
  return z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(SECRET, `${name} must be 1 to 512 visible characters with no spaces (${hint})`)
      .optional(),
  );
}

function baseUrl(name: string, fallback: string) {
  return z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default(fallback)
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
            message: `${name} must be an http(s) address with no credentials, query or fragment`,
          });
          return z.NEVER;
        }
      }),
  );
}

function timeOfDay(name: string, fallback: string, allowOff: boolean) {
  const what = allowOff ? "HH:MM (24 hours) or off" : "HH:MM (24 hours)";
  return z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default(fallback)
      .refine(
        (value) => (allowOff && value === "off") || parseClock(value) !== null,
        `${name} must be ${what}, for example ${fallback}`,
      ),
  );
}

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
const DAY_NAMES: Record<string, (typeof DAYS)[number]> = {
  mon: "mon",
  monday: "mon",
  tue: "tue",
  tuesday: "tue",
  wed: "wed",
  wednesday: "wed",
  thu: "thu",
  thursday: "thu",
  fri: "fri",
  friday: "fri",
  sat: "sat",
  saturday: "sat",
  sun: "sun",
  sunday: "sun",
};

export const integrationsShape = {
  // Google Calendar (spec §5.3). On only when all four are set.
  GOOGLE_CLIENT_ID: secret(
    "GOOGLE_CLIENT_ID",
    "Google Cloud console, APIs and services, Credentials",
  ),
  GOOGLE_CLIENT_SECRET: secret(
    "GOOGLE_CLIENT_SECRET",
    "Google Cloud console, APIs and services, Credentials",
  ),
  GOOGLE_REFRESH_TOKEN: secret(
    "GOOGLE_REFRESH_TOKEN",
    "printed by: pnpm --filter @overdrive/server run google-auth",
  ),
  GOOGLE_CALENDAR_ID: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(
        CALENDAR_ID,
        "GOOGLE_CALENDAR_ID must be 1 to 256 characters from A-Z a-z 0-9 . _ @ % + - (Google Calendar, the calendar's settings, Integrate calendar)",
      )
      .optional(),
  ),
  GOOGLE_API_BASE_URL: baseUrl("GOOGLE_API_BASE_URL", DEFAULT_GOOGLE_API_BASE_URL),
  GOOGLE_TOKEN_URL: baseUrl("GOOGLE_TOKEN_URL", DEFAULT_GOOGLE_TOKEN_URL),
  // The time a planned workout starts, in TIMEZONE: the calendar event's start and the reference of the lead reminder.
  GCAL_WORKOUT_START: timeOfDay("GCAL_WORKOUT_START", DEFAULT_WORKOUT_START, false),

  // Telegram (spec §5.4). The bot is on when the token is set; it answers only TELEGRAM_OWNER_CHAT_ID.
  TELEGRAM_BOT_TOKEN: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(BOT_TOKEN, "TELEGRAM_BOT_TOKEN must look like 123456:ABC... (BotFather gives it)")
      .optional(),
  ),
  TELEGRAM_OWNER_CHAT_ID: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(CHAT_ID, "TELEGRAM_OWNER_CHAT_ID must be a whole number such as 123456789")
      .optional(),
  ),
  TELEGRAM_BASE_URL: baseUrl("TELEGRAM_BASE_URL", DEFAULT_TELEGRAM_BASE_URL),
  REMINDER_EVENING_TIME: timeOfDay("REMINDER_EVENING_TIME", DEFAULT_REMINDER_EVENING_TIME, true),
  REMINDER_LEAD_MINUTES: z.preprocess(
    blankIsUnset,
    z.coerce
      .number({ error: "REMINDER_LEAD_MINUTES must be a whole number of minutes" })
      .int("REMINDER_LEAD_MINUTES must be a whole number of minutes")
      .min(0, "REMINDER_LEAD_MINUTES must be 0 (no reminder) or more")
      .max(1440, "REMINDER_LEAD_MINUTES must be at most 1440 (a day)")
      .default(DEFAULT_REMINDER_LEAD_MINUTES),
  ),
  WEEKLY_SUMMARY_DAY: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .toLowerCase()
      .default(DEFAULT_WEEKLY_SUMMARY_DAY)
      .transform((value, ctx): (typeof DAYS)[number] => {
        const day = DAY_NAMES[value];
        if (day === undefined) {
          ctx.addIssue({
            code: "custom",
            message: "WEEKLY_SUMMARY_DAY must be a day of the week, for example sun or sunday",
          });
          return z.NEVER;
        }
        return day;
      }),
  ),
  WEEKLY_SUMMARY_TIME: timeOfDay("WEEKLY_SUMMARY_TIME", DEFAULT_WEEKLY_SUMMARY_TIME, true),
};

/** The settings as `loadConfig` returns them (all of them present, defaults applied). */
export type IntegrationsConfig = z.infer<z.ZodObject<typeof integrationsShape>>;

/** What the services read: every field optional, with the defaults above standing in for a missing one. */
export type IntegrationsSettings = Partial<IntegrationsConfig>;

/** 1 (Monday) to 7 (Sunday) for `WEEKLY_SUMMARY_DAY`. */
export function weeklyDayNumber(day: (typeof DAYS)[number]): number {
  return DAYS.indexOf(day) + 1;
}
