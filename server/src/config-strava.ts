import { z } from "zod";

/**
 * The Strava upload's configuration (ADR 0020). Strava is write-only here: the service posts
 * finished rides and reads nothing back but the answer to its own upload. The feature is off unless
 * the three credentials are all set. Messages never repeat a value.
 */

/** Variables that are set but empty (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const DEFAULT_STRAVA_BASE_URL = "https://www.strava.com";

// Strava's client ids are whole numbers; its secrets and tokens are 40 hex characters today. Only the
// shape that could go wrong is checked (a pasted space or quote), not the length Strava happens to use.
const CLIENT_ID = /^[0-9]{1,12}$/;
const SECRET = /^[A-Za-z0-9._~+/=:-]{1,256}$/;

/** `true`/`false`, `1`/`0`, `yes`/`no`, any case; blank is false. */
const booleanFlag = (name: string) =>
  z.preprocess(
    (value) => {
      const text = typeof value === "string" ? value.trim().toLowerCase() : value;
      if (text === undefined || text === "") return false;
      if (text === "true" || text === "1" || text === "yes") return true;
      if (text === "false" || text === "0" || text === "no") return false;
      return value;
    },
    z.boolean({ error: `${name} must be true or false` }),
  );

/** The fields `config.ts` adds to its schema. */
export const stravaConfigShape = {
  STRAVA_CLIENT_ID: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(
        CLIENT_ID,
        "STRAVA_CLIENT_ID must be the whole number shown as Client ID on strava.com/settings/api",
      )
      .optional(),
  ),
  STRAVA_CLIENT_SECRET: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(
        SECRET,
        "STRAVA_CLIENT_SECRET must be 1 to 256 letters, digits or . _ ~ + / = : - with no spaces or quotes (the Client Secret on strava.com/settings/api)",
      )
      .optional(),
  ),
  // Seeds the first run only: Strava rotates the token, and the rotated one lives in the database
  // (table strava_auth). A new value here, after the owner ran `strava-auth` again, replaces it.
  STRAVA_REFRESH_TOKEN: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .regex(
        SECRET,
        "STRAVA_REFRESH_TOKEN must be 1 to 256 letters, digits or . _ ~ + / = : - with no spaces or quotes (printed by: pnpm --filter @overdrive/server run strava-auth)",
      )
      .optional(),
  ),
  // Off by default: turning Strava on must not post the whole history.
  STRAVA_BACKFILL: booleanFlag("STRAVA_BACKFILL"),
  STRAVA_BASE_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .default(DEFAULT_STRAVA_BASE_URL)
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
              "STRAVA_BASE_URL must be an http(s) address with no credentials, query or fragment",
          });
          return z.NEVER;
        }
      }),
  ),
};

/** What the services read of the configuration; all optional, with the defaults of {@link stravaConfigShape}. */
export interface StravaConfig {
  STRAVA_CLIENT_ID?: string | undefined;
  STRAVA_CLIENT_SECRET?: string | undefined;
  STRAVA_REFRESH_TOKEN?: string | undefined;
  STRAVA_BACKFILL?: boolean | undefined;
  STRAVA_BASE_URL?: string | undefined;
}

export interface StravaSettings {
  clientId: string;
  clientSecret: string;
  /** Seeds the first run; the rotated token is kept in the database. */
  refreshToken: string;
  backfill: boolean;
  /** `https://www.strava.com`, or the fake of the tests. No trailing slash. */
  baseUrl: string;
}

export type StravaMode =
  | { on: StravaSettings }
  /** `partial` is true when some, not all, of the credentials are set: probably a mistake. */
  | { off: string; partial: boolean };

/** The upload is on only when the client id, the secret and the refresh token are all set. */
export function stravaSettings(config: StravaConfig): StravaMode {
  const { STRAVA_CLIENT_ID: clientId, STRAVA_CLIENT_SECRET: clientSecret } = config;
  const refreshToken = config.STRAVA_REFRESH_TOKEN;
  if (clientId !== undefined && clientSecret !== undefined && refreshToken !== undefined) {
    return {
      on: {
        clientId,
        clientSecret,
        refreshToken,
        backfill: config.STRAVA_BACKFILL ?? false,
        baseUrl: (config.STRAVA_BASE_URL ?? DEFAULT_STRAVA_BASE_URL).replace(/\/+$/, ""),
      },
    };
  }
  const missing = [
    clientId === undefined ? "STRAVA_CLIENT_ID" : null,
    clientSecret === undefined ? "STRAVA_CLIENT_SECRET" : null,
    refreshToken === undefined ? "STRAVA_REFRESH_TOKEN" : null,
  ].filter((name): name is string => name !== null);
  return {
    off:
      missing.length === 3
        ? "STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET and STRAVA_REFRESH_TOKEN are not set"
        : `${missing.join(" and ")} ${missing.length === 1 ? "is" : "are"} not set`,
    partial: missing.length < 3,
  };
}
