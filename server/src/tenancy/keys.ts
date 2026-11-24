/**
 * Which environment key belongs to whom in the multi-tenant service. Every key of the configuration code is in
 * exactly one of three lists (a test checks it), so a secret of the owner can never become another user's setting by
 * a fallback, and a per-user value can never be read from the installation's environment by mistake.
 */

/** Read from the environment only; never copied into a user's settings. */
export const INSTALLATION_KEYS: readonly string[] = [
  "HOST",
  "PORT",
  "LOG_LEVEL",
  "DATABASE_URL",
  "PUBLIC_BASE_URL",
  "ADMIN_TOKEN",
  "LLM_BASE_URL",
  "LLM_API_KEY",
  "LLM_MODEL",
  "LLM_TIMEOUT_SECONDS",
  "LIVE_COACH_LLM_MODEL",
  "TTS_BASE_URL",
  "TTS_API_KEY",
  "TTS_MODEL",
  "TTS_FORMAT",
  "TTS_TIMEOUT_SECONDS",
  "BROUTER_URL",
  "BROUTER_TIMEOUT_SECONDS",
  "ROUTE_STEP_METERS",
  "TELEGRAM_BOT_TOKEN",
  "TELEGRAM_BASE_URL",
  "INTERVALS_BASE_URL",
  "STRAVA_BASE_URL",
  "GOOGLE_API_BASE_URL",
  "GOOGLE_TOKEN_URL",
  "GOOGLE_CLIENT_ID",
  "GOOGLE_CLIENT_SECRET",
  "PLAN_REFRESH_MINUTES",
  "NOTEBOOK_MAX_CHARS",
  "NOTEBOOK_LOG_MAX_CHARS",
  "LIVE_COACH_CUE_MAX_CHARS",
  "LIVE_COACH_SILENT_FINAL_SECONDS",
  "LIVE_COACH_RECOVERY_MAX_CUES",
  "LIVE_COACH_RECOVERY_BELOW_PERCENT",
  "LIVE_COACH_MAX_PER_TRIGGER_PER_STEP",
  "LIVE_COACH_TRIGGER_COOLDOWN_SECONDS",
  "LIVE_COACH_MIN_GAP_SECONDS",
  "LIVE_COACH_MAX_CUES_PER_HOUR",
  "LIVE_COACH_LEAD_SECONDS",
  "LIVE_COACH_DEADLINE_SECONDS",
];

/** Copied to the owner's `user_settings` at the cutover (after the configuration code's own trim and transform). */
export const PER_USER_KEYS: readonly string[] = [
  "TIMEZONE",
  "INTERVALS_API_KEY",
  "INTERVALS_ATHLETE_ID",
  "GOOGLE_REFRESH_TOKEN",
  "GOOGLE_CALENDAR_ID",
  "GCAL_WORKOUT_START",
  "REMINDER_EVENING_TIME",
  "REMINDER_LEAD_MINUTES",
  "WEEKLY_SUMMARY_DAY",
  "WEEKLY_SUMMARY_TIME",
  "STRAVA_CLIENT_ID",
  "STRAVA_CLIENT_SECRET",
  "STRAVA_REFRESH_TOKEN",
  "STRAVA_BACKFILL",
  "WELLNESS_SOURCE_ORDER",
  "WELLNESS_PULL_HOURS",
  "WELLNESS_BASELINE_DAYS",
  "WELLNESS_NIGHTS_NEEDED",
  "INSIGHTS_ENABLED",
  "INSIGHTS_LANGUAGE",
  "INSIGHT_MORNING_TIME",
  "INSIGHT_DENY_WORDS",
  "ALERT_STREAK_DAYS",
  "ALERT_MISSED_SESSIONS",
  "ALERT_LOAD_ABOVE_PERCENT",
  "ALERT_LOAD_BELOW_PERCENT",
  "LIVE_COACH_ENABLED",
  "LIVE_COACH_LANGUAGE",
  "TTS_VOICE",
  "TTS_SPEED",
  "PLAN_EVENT_DAY_END_GRACE_MINUTES",
];

/** Used by the import into the `users` and `user_tokens` rows; never stored in `user_settings`. */
export const IMPORT_ONLY_KEYS: readonly string[] = [
  "DEVICE_TOKENS",
  "MCP_STATIC_TOKENS",
  "OWNER_PASSPHRASE_HASH",
  "TELEGRAM_OWNER_CHAT_ID",
  // The owner's login handle (default "owner"). Not read by the configuration code yet.
  "OWNER_HANDLE",
];

/** The keys of {@link IMPORT_ONLY_KEYS} and {@link INSTALLATION_KEYS} that the configuration code does not read (yet). */
export const NOT_IN_CONFIG_YET: readonly string[] = ["ADMIN_TOKEN", "OWNER_HANDLE"];

export type KeyKind = "installation" | "per-user" | "import-only";

/** Which list `key` is in, or null when it is in none. */
export function keyKind(key: string): KeyKind | null {
  if (INSTALLATION_KEYS.includes(key)) return "installation";
  if (PER_USER_KEYS.includes(key)) return "per-user";
  if (IMPORT_ONLY_KEYS.includes(key)) return "import-only";
  return null;
}
