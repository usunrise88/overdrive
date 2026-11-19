import { z } from "zod";
import { coachConfigShape } from "./config-coach.ts";
import { insightsConfigShape } from "./config-insights.ts";
import { integrationsShape } from "./config-integrations.ts";
import { stravaConfigShape } from "./config-strava.ts";
import { wellnessConfigShape } from "./config-wellness.ts";
import { parsePassphraseHash } from "./oauth/passphrase.ts";
import { isTimeZone } from "./time.ts";

/** A device allowed to call the API: its name (for logs and `rides.uploaded_by`) and its secret. */
export interface DeviceToken {
  name: string;
  token: string;
}

export const MIN_TOKEN_LENGTH = 32;
const MAX_TOKEN_LENGTH = 256;
const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/;
// Base64 and base64url, which is what `openssl rand -base64 36` makes: nothing that could be taken
// for the separators of the list.
const TOKEN = /^[A-Za-z0-9+/=._~-]+$/;

/**
 * Parses `DEVICE_TOKENS` (or `MCP_STATIC_TOKENS`, which has the same form): comma-separated
 * `name:token` entries, for example `tablet:…,companion:…`. Every token has at least
 * {@link MIN_TOKEN_LENGTH} characters and names and tokens are unique. An empty value means no token
 * is allowed (for `DEVICE_TOKENS` every `/v1` request is then 401). The errors name the entry by
 * position and never repeat a token.
 */
export function parseDeviceTokens(raw: string, variable = "DEVICE_TOKENS"): DeviceToken[] {
  const devices: DeviceToken[] = [];
  const entries = raw
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry !== "");
  for (const [index, entry] of entries.entries()) {
    const at = `${variable} entry ${index + 1}`;
    const colon = entry.indexOf(":");
    if (colon < 0) throw new Error(`${at} is not name:token`);
    const name = entry.slice(0, colon);
    const token = entry.slice(colon + 1);
    if (!NAME.test(name)) {
      throw new Error(`${at}: the name must be 1 to 32 characters from A-Z a-z 0-9 . _ -`);
    }
    if (token.length < MIN_TOKEN_LENGTH) {
      throw new Error(
        `${at} (${name}): the token must be at least ${MIN_TOKEN_LENGTH} characters, make one with: openssl rand -base64 36`,
      );
    }
    if (token.length > MAX_TOKEN_LENGTH || !TOKEN.test(token)) {
      throw new Error(
        `${at} (${name}): the token must be at most ${MAX_TOKEN_LENGTH} characters from A-Z a-z 0-9 + / = . _ ~ -`,
      );
    }
    if (devices.some((d) => d.name === name))
      throw new Error(`${at}: the name ${name} is used twice`);
    if (devices.some((d) => d.token === token)) {
      throw new Error(`${at} (${name}): the token is the same as another device's`);
    }
    devices.push({ name, token });
  }
  return devices;
}

/** Environment variables that are set but empty (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const DEFAULT_INTERVALS_BASE_URL = "https://intervals.icu";

/** intervals.icu athlete ids are `i12345` (or a number); `0` means the athlete the API key belongs to. */
const ATHLETE_ID = /^[A-Za-z0-9_-]{1,32}$/;
// Visible ASCII, no spaces: the key goes into an Authorization header.
const API_KEY = /^[\x21-\x7e]{1,256}$/;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * Why `raw` cannot be the service's public address, or null when it can: https (http only on this
 * machine, for development and the tests), just an origin, nothing that could carry a secret.
 */
function baseUrlProblem(raw: string): string | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return "must be an address such as https://overdrive.example.com";
  }
  const local = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
  if (url.protocol !== "https:" && !local) {
    return "must start with https:// (http only for localhost)";
  }
  if (url.username !== "" || url.password !== "") return "must not contain credentials";
  if (url.search !== "" || url.hash !== "") return "must not have a query or a fragment";
  if (url.pathname !== "/") return "must be the address alone, with no path";
  return null;
}

const schema = z.object({
  HOST: z.string().default("127.0.0.1"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]).default("info"),
  DATABASE_URL: z
    .string({
      error: "DATABASE_URL is required, for example postgres://user:password@host:5432/db",
    })
    .min(1, "DATABASE_URL must not be empty"),
  DEVICE_TOKENS: z
    .string()
    .default("")
    .transform((raw, ctx): DeviceToken[] => {
      try {
        return parseDeviceTokens(raw);
      } catch (err) {
        ctx.addIssue({ code: "custom", message: err instanceof Error ? err.message : String(err) });
        return z.NEVER;
      }
    }),
  // The MCP server and its OAuth server (ADR 0018): off unless both PUBLIC_BASE_URL and
  // OWNER_PASSPHRASE_HASH are set. Messages never repeat a value.
  PUBLIC_BASE_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string => {
        const reason = baseUrlProblem(raw);
        if (reason === null) return new URL(raw).origin;
        ctx.addIssue({ code: "custom", message: `PUBLIC_BASE_URL ${reason}` });
        return z.NEVER;
      })
      .optional(),
  ),
  OWNER_PASSPHRASE_HASH: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string => {
        try {
          parsePassphraseHash(raw);
          return raw;
        } catch (err) {
          ctx.addIssue({
            code: "custom",
            message: `OWNER_PASSPHRASE_HASH ${err instanceof Error ? err.message : "is invalid"}`,
          });
          return z.NEVER;
        }
      })
      .optional(),
  ),
  MCP_STATIC_TOKENS: z
    .string()
    .default("")
    .transform((raw, ctx): DeviceToken[] => {
      try {
        return parseDeviceTokens(raw, "MCP_STATIC_TOKENS");
      } catch (err) {
        ctx.addIssue({ code: "custom", message: err instanceof Error ? err.message : String(err) });
        return z.NEVER;
      }
    }),
  // intervals.icu (ADR 0016). The integration is off unless the key is set. Messages never repeat a value.
  INTERVALS_API_KEY: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(
        API_KEY,
        "INTERVALS_API_KEY must be 1 to 256 visible characters with no spaces (Settings, Developer Settings, API key in intervals.icu)",
      )
      .optional(),
  ),
  INTERVALS_ATHLETE_ID: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(ATHLETE_ID, "INTERVALS_ATHLETE_ID must be 1 to 32 characters from A-Z a-z 0-9 _ -")
      .default("0"),
  ),
  INTERVALS_BASE_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default(DEFAULT_INTERVALS_BASE_URL)
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
              "INTERVALS_BASE_URL must be an http(s) address with no credentials, query or fragment",
          });
          return z.NEVER;
        }
      }),
  ),
  PLAN_REFRESH_MINUTES: z.preprocess(
    blankIsUnset,
    z.coerce
      .number({ error: "PLAN_REFRESH_MINUTES must be a whole number of minutes" })
      .int("PLAN_REFRESH_MINUTES must be a whole number of minutes")
      .min(1, "PLAN_REFRESH_MINUTES must be at least 1")
      .max(1440, "PLAN_REFRESH_MINUTES must be at most 1440 (a day)")
      .default(15),
  ),
  ...stravaConfigShape, // Strava upload (ADR 0020): config-strava.ts
  ...wellnessConfigShape, // Wellness and recovery (ADR 0023): config-wellness.ts
  ...insightsConfigShape, // Insights, alerts and the language-model endpoint (ADR 0025): config-insights.ts
  ...coachConfigShape, // The coach notebook's caps and the plan events' day end (ADR 0035): config-coach.ts
  // The rider's time zone: which day "today" is for the plan, and which day a ride belongs to.
  TIMEZONE: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default("UTC")
      .refine(isTimeZone, "TIMEZONE must be an IANA time zone such as Europe/Paris or UTC"),
  ),
  // Google Calendar and Telegram (ADR 0019): see config-integrations.ts.
  ...integrationsShape,
});

export type Config = z.infer<typeof schema>;

/**
 * Reads configuration from the environment. Secrets (the device tokens, the database password,
 * the intervals.icu key, and later Strava, Google, Telegram and the LLM endpoint) live in the server
 * environment only (spec §7.2). Throws on anything invalid, so a bad configuration stops the
 * start instead of running half-open.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return schema.parse(env);
}

/** What to print when {@link loadConfig} throws: one line per problem, never a value. */
export function describeConfigError(err: unknown): string {
  if (err instanceof z.ZodError) {
    return err.issues
      .map((issue) => `${issue.path.join(".") || "configuration"}: ${issue.message}`)
      .join("\n");
  }
  return err instanceof Error ? err.message : String(err);
}
