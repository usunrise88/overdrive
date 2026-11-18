import { z } from "zod";

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
 * Parses `DEVICE_TOKENS`: comma-separated `name:token` entries, for example `tablet:…,companion:…`.
 * Every token has at least {@link MIN_TOKEN_LENGTH} characters and names and tokens are unique.
 * An empty value means no device is allowed (every `/v1` request is then 401). The errors name
 * the entry by position and never repeat a token.
 */
export function parseDeviceTokens(raw: string): DeviceToken[] {
  const devices: DeviceToken[] = [];
  const entries = raw
    .split(",")
    .map((entry) => entry.trim())
    .filter((entry) => entry !== "");
  for (const [index, entry] of entries.entries()) {
    const at = `DEVICE_TOKENS entry ${index + 1}`;
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
});

export type Config = z.infer<typeof schema>;

/**
 * Reads configuration from the environment. Secrets (the device tokens, the database password,
 * and later intervals.icu, Strava, Google, Telegram and the LLM endpoint) live in the server
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
