import { describe, expect, it } from "vitest";
import { describeConfigError, loadConfig } from "../src/config.ts";
import { weeklyDayNumber } from "../src/config-integrations.ts";

const DATABASE_URL = "postgres://overdrive:secret@db:5432/overdrive";
const load = (env: Record<string, string>) => loadConfig({ DATABASE_URL, ...env });

function problem(env: Record<string, string>): string {
  try {
    load(env);
  } catch (error) {
    return describeConfigError(error);
  }
  throw new Error("the configuration was accepted");
}

describe("Google Calendar and Telegram settings", () => {
  it("are all optional: the calendar and the bot are off, and the defaults are the brief's", () => {
    const config = load({});
    expect(config).toMatchObject({
      GOOGLE_API_BASE_URL: "https://www.googleapis.com/calendar/v3",
      GOOGLE_TOKEN_URL: "https://oauth2.googleapis.com/token",
      TELEGRAM_BASE_URL: "https://api.telegram.org",
      GCAL_WORKOUT_START: "07:00",
      REMINDER_EVENING_TIME: "20:00",
      REMINDER_LEAD_MINUTES: 60,
      WEEKLY_SUMMARY_DAY: "sun",
      WEEKLY_SUMMARY_TIME: "18:00",
    });
    for (const name of [
      "GOOGLE_CLIENT_ID",
      "GOOGLE_CLIENT_SECRET",
      "GOOGLE_REFRESH_TOKEN",
      "GOOGLE_CALENDAR_ID",
      "TELEGRAM_BOT_TOKEN",
      "TELEGRAM_OWNER_CHAT_ID",
    ] as const) {
      expect(config[name], name).toBeUndefined();
    }
  });

  it("treat empty values as not set (compose passes an empty VAR) and trim the rest", () => {
    const empty = load({
      GOOGLE_CLIENT_ID: "",
      GOOGLE_REFRESH_TOKEN: "   ",
      TELEGRAM_BOT_TOKEN: "",
      TELEGRAM_OWNER_CHAT_ID: "",
      GCAL_WORKOUT_START: "",
      REMINDER_EVENING_TIME: "",
      REMINDER_LEAD_MINUTES: "",
      WEEKLY_SUMMARY_DAY: "",
      WEEKLY_SUMMARY_TIME: "",
    });
    expect(empty.GOOGLE_CLIENT_ID).toBeUndefined();
    expect(empty.TELEGRAM_BOT_TOKEN).toBeUndefined();
    expect(empty.REMINDER_LEAD_MINUTES).toBe(60);
    expect(empty.WEEKLY_SUMMARY_DAY).toBe("sun");
    expect(load({ GOOGLE_CALENDAR_ID: " abc@group.calendar.google.com " }).GOOGLE_CALENDAR_ID).toBe(
      "abc@group.calendar.google.com",
    );
  });

  it("read what the owner sets", () => {
    const config = load({
      GOOGLE_CLIENT_ID: "id.apps.googleusercontent.com",
      GOOGLE_CLIENT_SECRET: "GOCSPX-abc",
      GOOGLE_REFRESH_TOKEN: "1//0abc",
      GOOGLE_CALENDAR_ID: "abc123@group.calendar.google.com",
      GOOGLE_API_BASE_URL: "http://127.0.0.1:9000/calendar/v3/",
      GOOGLE_TOKEN_URL: "http://127.0.0.1:9000/token",
      GCAL_WORKOUT_START: "06:30",
      TELEGRAM_BOT_TOKEN: "123456:ABCdef_ghi-JKLmnopqrstuvwxyz0123456789",
      TELEGRAM_OWNER_CHAT_ID: "-100123456",
      TELEGRAM_BASE_URL: "http://127.0.0.1:9001",
      REMINDER_EVENING_TIME: "21:15",
      REMINDER_LEAD_MINUTES: "90",
      WEEKLY_SUMMARY_DAY: "Friday",
      WEEKLY_SUMMARY_TIME: "OFF",
    });
    expect(config).toMatchObject({
      GOOGLE_API_BASE_URL: "http://127.0.0.1:9000/calendar/v3",
      GCAL_WORKOUT_START: "06:30",
      TELEGRAM_OWNER_CHAT_ID: "-100123456",
      REMINDER_EVENING_TIME: "21:15",
      REMINDER_LEAD_MINUTES: 90,
      WEEKLY_SUMMARY_DAY: "fri",
      WEEKLY_SUMMARY_TIME: "off",
    });
    expect(load({ REMINDER_EVENING_TIME: "off", REMINDER_LEAD_MINUTES: "0" })).toMatchObject({
      REMINDER_EVENING_TIME: "off",
      REMINDER_LEAD_MINUTES: 0,
    });
  });

  it("numbers the weekly day from Monday", () => {
    expect(["mon", "wed", "sun"].map((d) => weeklyDayNumber(d as "mon"))).toEqual([1, 3, 7]);
  });

  it("are refused when invalid, naming the variable and never repeating the value", () => {
    const cases: [string, string][] = [
      ["GCAL_WORKOUT_START", "7am"],
      ["GCAL_WORKOUT_START", "25:00"],
      ["GCAL_WORKOUT_START", "off"],
      ["REMINDER_EVENING_TIME", "later"],
      ["REMINDER_LEAD_MINUTES", "-5"],
      ["REMINDER_LEAD_MINUTES", "1441"],
      ["REMINDER_LEAD_MINUTES", "an hour"],
      ["WEEKLY_SUMMARY_DAY", "someday"],
      ["WEEKLY_SUMMARY_TIME", "6pm"],
      ["TELEGRAM_BOT_TOKEN", "not a token with spaces"],
      ["TELEGRAM_OWNER_CHAT_ID", "me"],
      ["GOOGLE_CLIENT_SECRET", "secret with spaces"],
      ["GOOGLE_REFRESH_TOKEN", "x".repeat(600)],
      ["GOOGLE_CALENDAR_ID", "has space@example.com"],
      ["GOOGLE_API_BASE_URL", "ftp://example.com"],
      ["GOOGLE_TOKEN_URL", "https://user:pass@example.com/token"],
      ["TELEGRAM_BASE_URL", "https://api.telegram.org/?x=1"],
    ];
    for (const [name, value] of cases) {
      const message = problem({ [name]: value });
      expect(message, `${name}=${value}`).toContain(name);
      if (value.length > 3) expect(message, `${name}=${value}`).not.toContain(value);
    }
  });
});
