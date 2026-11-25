import { type Config, loadConfig } from "../config.ts";
import { INSTALLATION_KEYS, PER_USER_KEYS } from "./keys.ts";

export interface UserConfigInput {
  /** The environment the server started with. Only the installation keys are read from it. */
  installation: Readonly<Record<string, string | undefined>>;
  /** One user's `user_settings` rows (key to value). Only the per-user keys are read from it. */
  settings: Readonly<Record<string, string>>;
  /** The user's Telegram chat (`users.telegram_chat_id`): the value of `TELEGRAM_OWNER_CHAT_ID` for this user. */
  telegramChatId?: string | null;
  /**
   * Hears the names (never the values) of the keys that were set in the wrong place and so ignored: per-user keys found in
   * `installation` (still in the owner's `.env` after the cutover) and keys of `settings` that are not per-user keys.
   */
  onIgnored?: (ignored: { installationPerUser: string[]; settingsNotPerUser: string[] }) => void;
}

const isSet = (value: string | undefined): boolean => value !== undefined && value.trim() !== "";

/** The per-user keys that are set in `installation` (and so would be ignored by {@link loadUserConfig}). */
export function ignoredPerUserKeys(installation: Readonly<Record<string, string | undefined>>) {
  return PER_USER_KEYS.filter((key) => isSet(installation[key]));
}

/**
 * The configuration of one tenant: the same shape and the same validation as {@link loadConfig}, built from two sources
 * that never mix. Installation keys (the models, the bot token, the limits) come only from `installation`; per-user keys
 * (time zone, intervals.icu, Google, Strava, reminders, wellness, insights, alerts, the coach's language and voice) come
 * only from `settings`, so a user with no settings has none of the owner's integrations, whatever the environment holds.
 * There is no fallback from one source to the other. Throws what `loadConfig` throws.
 */
export function loadUserConfig(input: UserConfigInput): Config {
  const env: Record<string, string> = {};
  for (const key of INSTALLATION_KEYS) {
    const value = input.installation[key];
    if (value !== undefined) env[key] = value;
  }
  const settingsNotPerUser: string[] = [];
  for (const [key, value] of Object.entries(input.settings)) {
    if (PER_USER_KEYS.includes(key)) env[key] = value;
    else settingsNotPerUser.push(key);
  }
  if (input.telegramChatId !== undefined && input.telegramChatId !== null) {
    env.TELEGRAM_OWNER_CHAT_ID = input.telegramChatId;
  }
  const installationPerUser = ignoredPerUserKeys(input.installation);
  if (
    input.onIgnored !== undefined &&
    (installationPerUser.length > 0 || settingsNotPerUser.length > 0)
  ) {
    input.onIgnored({ installationPerUser, settingsNotPerUser });
  }
  return loadConfig(env);
}
