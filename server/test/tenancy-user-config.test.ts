import { describe, expect, it } from "vitest";
import { INSTALLATION_KEYS, PER_USER_KEYS } from "../src/tenancy/keys.ts";
import { loadUserConfig } from "../src/tenancy/user-config.ts";

const DB = "postgres://user:password@localhost:5432/db";

/** The owner's environment as it is today: installation keys and every integration of the owner, synthetic values. */
const OWNER_ENV: Record<string, string> = {
  DATABASE_URL: DB,
  LLM_BASE_URL: "https://llm.example.test/v1",
  LLM_MODEL: "some-model",
  TELEGRAM_BOT_TOKEN: "123456:ABCdef-synthetic-bot-token",
  TELEGRAM_OWNER_CHAT_ID: "424242",
  TIMEZONE: "Europe/Belgrade",
  INTERVALS_API_KEY: "synthetic-intervals-key",
  INTERVALS_ATHLETE_ID: "i1",
  GOOGLE_CLIENT_ID: "synthetic-client",
  GOOGLE_CLIENT_SECRET: "synthetic-secret",
  GOOGLE_REFRESH_TOKEN: "synthetic-refresh",
  GOOGLE_CALENDAR_ID: "owner@example.test",
  STRAVA_CLIENT_ID: "1",
  STRAVA_CLIENT_SECRET: "synthetic-strava-secret",
  STRAVA_REFRESH_TOKEN: "synthetic-strava-refresh",
};

describe("loadUserConfig", () => {
  it("gives a user with no settings none of the owner's per-user keys, whatever the environment holds", () => {
    let heard: { installationPerUser: string[]; settingsNotPerUser: string[] } | null = null;
    const config = loadUserConfig({
      installation: OWNER_ENV,
      settings: {},
      onIgnored: (ignored) => {
        heard = ignored;
      },
    });
    expect(config.INTERVALS_API_KEY).toBeUndefined();
    expect(config.INTERVALS_ATHLETE_ID).toBe("0");
    expect(config.GOOGLE_REFRESH_TOKEN).toBeUndefined();
    expect(config.GOOGLE_CALENDAR_ID).toBeUndefined();
    expect(config.STRAVA_CLIENT_ID).toBeUndefined();
    expect(config.STRAVA_REFRESH_TOKEN).toBeUndefined();
    expect(config.TELEGRAM_OWNER_CHAT_ID).toBeUndefined();
    expect(config.TIMEZONE).toBe("UTC");
    // The installation's own keys do arrive.
    expect(config.LLM_MODEL).toBe("some-model");
    expect(config.TELEGRAM_BOT_TOKEN).toBe("123456:ABCdef-synthetic-bot-token");
    // The ignored keys are reported by name, never by value.
    expect(heard).not.toBeNull();
    const names = (heard as unknown as { installationPerUser: string[] }).installationPerUser;
    expect(names).toEqual(
      expect.arrayContaining(["INTERVALS_API_KEY", "TIMEZONE", "STRAVA_CLIENT_ID"]),
    );
    expect(JSON.stringify(heard)).not.toContain("synthetic-intervals-key");
  });

  it("takes per-user keys from the settings and the chat from the user's row", () => {
    const config = loadUserConfig({
      installation: { DATABASE_URL: DB, TELEGRAM_BOT_TOKEN: OWNER_ENV.TELEGRAM_BOT_TOKEN },
      settings: { TIMEZONE: "Asia/Tokyo", INTERVALS_API_KEY: "key-of-the-user" },
      telegramChatId: "777",
    });
    expect(config.TIMEZONE).toBe("Asia/Tokyo");
    expect(config.INTERVALS_API_KEY).toBe("key-of-the-user");
    expect(config.TELEGRAM_OWNER_CHAT_ID).toBe("777");
  });

  it("never reads an installation key from the settings", () => {
    let heard: string[] = [];
    const config = loadUserConfig({
      installation: { DATABASE_URL: DB },
      settings: { LLM_MODEL: "smuggled", TELEGRAM_BOT_TOKEN: "123456:smuggled-token" },
      onIgnored: (ignored) => {
        heard = ignored.settingsNotPerUser;
      },
    });
    expect(config.LLM_MODEL).toBeUndefined();
    expect(config.TELEGRAM_BOT_TOKEN).toBeUndefined();
    expect(heard.sort()).toEqual(["LLM_MODEL", "TELEGRAM_BOT_TOKEN"]);
  });

  it("fails on an invalid setting, naming the key and not the value", () => {
    expect(() =>
      loadUserConfig({ installation: { DATABASE_URL: DB }, settings: { TIMEZONE: "Not/AZone" } }),
    ).toThrow(/TIMEZONE/);
  });

  it("keeps the two key lists apart", () => {
    expect(INSTALLATION_KEYS.filter((k) => PER_USER_KEYS.includes(k))).toEqual([]);
  });
});
