import { z } from "zod";

const schema = z.object({
  HOST: z.string().default("127.0.0.1"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]).default("info"),
});

export type Config = z.infer<typeof schema>;

/**
 * Reads configuration from the environment. Secrets (intervals.icu, Strava, Google, Telegram,
 * the LLM endpoint) live in the server environment only (spec §7.2) and get added here as the
 * integrations that need them land.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  return schema.parse(env);
}
