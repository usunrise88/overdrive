import { describe, expect, it } from "vitest";
import {
  describeConfigError,
  loadConfig,
  MIN_TOKEN_LENGTH,
  parseDeviceTokens,
} from "../src/config.ts";

const DATABASE_URL = "postgres://overdrive:secret@db:5432/overdrive";
const token = (seed: string) => seed.repeat(MIN_TOKEN_LENGTH).slice(0, MIN_TOKEN_LENGTH);

describe("loadConfig", () => {
  it("applies defaults: no devices, so the API is closed", () => {
    expect(loadConfig({ DATABASE_URL })).toMatchObject({
      HOST: "127.0.0.1",
      PORT: 8080,
      LOG_LEVEL: "info",
      DATABASE_URL,
      DEVICE_TOKENS: [],
      INTERVALS_ATHLETE_ID: "0",
      INTERVALS_BASE_URL: "https://intervals.icu",
      PLAN_REFRESH_MINUTES: 15,
      TIMEZONE: "UTC",
    });
  });

  it("leaves intervals.icu off without a key", () => {
    expect(loadConfig({ DATABASE_URL }).INTERVALS_API_KEY).toBeUndefined();
    // compose passes `${INTERVALS_API_KEY:-}`: empty is unset, and so is a blank one
    expect(loadConfig({ DATABASE_URL, INTERVALS_API_KEY: "" }).INTERVALS_API_KEY).toBeUndefined();
    expect(
      loadConfig({ DATABASE_URL, INTERVALS_API_KEY: "   " }).INTERVALS_API_KEY,
    ).toBeUndefined();
  });

  it("reads the intervals.icu settings, and blank ones mean the defaults", () => {
    const config = loadConfig({
      DATABASE_URL,
      INTERVALS_API_KEY: " abc123XYZ ",
      INTERVALS_ATHLETE_ID: "i12345",
      INTERVALS_BASE_URL: "http://127.0.0.1:9999/",
      PLAN_REFRESH_MINUTES: "5",
      TIMEZONE: "Europe/Paris",
    });
    expect(config).toMatchObject({
      INTERVALS_API_KEY: "abc123XYZ",
      INTERVALS_ATHLETE_ID: "i12345",
      INTERVALS_BASE_URL: "http://127.0.0.1:9999",
      PLAN_REFRESH_MINUTES: 5,
      TIMEZONE: "Europe/Paris",
    });
    expect(
      loadConfig({
        DATABASE_URL,
        INTERVALS_ATHLETE_ID: "",
        INTERVALS_BASE_URL: "",
        PLAN_REFRESH_MINUTES: "",
        TIMEZONE: "",
      }),
    ).toMatchObject({
      INTERVALS_ATHLETE_ID: "0",
      INTERVALS_BASE_URL: "https://intervals.icu",
      PLAN_REFRESH_MINUTES: 15,
      TIMEZONE: "UTC",
    });
  });

  it("keeps a path in the base URL and drops trailing slashes", () => {
    expect(
      loadConfig({ DATABASE_URL, INTERVALS_BASE_URL: "https://proxy.example.com/intervals//" })
        .INTERVALS_BASE_URL,
    ).toBe("https://proxy.example.com/intervals");
  });

  it.each([
    ["INTERVALS_API_KEY", { INTERVALS_API_KEY: "has a space" }],
    ["INTERVALS_API_KEY", { INTERVALS_API_KEY: "tab\there" }],
    ["INTERVALS_ATHLETE_ID", { INTERVALS_ATHLETE_ID: "i 123" }],
    ["INTERVALS_BASE_URL", { INTERVALS_BASE_URL: "intervals.icu" }],
    ["INTERVALS_BASE_URL", { INTERVALS_BASE_URL: "ftp://intervals.icu" }],
    ["INTERVALS_BASE_URL", { INTERVALS_BASE_URL: "https://user:pass@intervals.icu" }],
    ["INTERVALS_BASE_URL", { INTERVALS_BASE_URL: "https://intervals.icu/?a=b" }],
    ["PLAN_REFRESH_MINUTES", { PLAN_REFRESH_MINUTES: "0" }],
    ["PLAN_REFRESH_MINUTES", { PLAN_REFRESH_MINUTES: "1441" }],
    ["PLAN_REFRESH_MINUTES", { PLAN_REFRESH_MINUTES: "2.5" }],
    ["PLAN_REFRESH_MINUTES", { PLAN_REFRESH_MINUTES: "soon" }],
    ["TIMEZONE", { TIMEZONE: "Mars/Olympus_Mons" }],
  ])("rejects an invalid %s, naming it and not repeating the value", (name, env) => {
    const value = Object.values(env)[0] as string;
    let text = "";
    try {
      loadConfig({ DATABASE_URL, ...env });
    } catch (err) {
      text = describeConfigError(err);
    }
    expect(text).toContain(`${name}:`);
    // The URL's credentials and the key's contents must never be echoed.
    if (name === "INTERVALS_API_KEY" || value.includes("pass")) {
      expect(text).not.toContain(value);
    }
  });

  it("needs a database", () => {
    expect(() => loadConfig({})).toThrow(/DATABASE_URL/);
    expect(() => loadConfig({ DATABASE_URL: "" })).toThrow(/DATABASE_URL/);
  });

  it("rejects an invalid port", () => {
    expect(() => loadConfig({ DATABASE_URL, PORT: "70000" })).toThrow();
  });

  it("reads the device tokens", () => {
    const config = loadConfig({
      DATABASE_URL,
      DEVICE_TOKENS: `tablet:${token("a")},companion:${token("b")}`,
    });
    expect(config.DEVICE_TOKENS).toEqual([
      { name: "tablet", token: token("a") },
      { name: "companion", token: token("b") },
    ]);
  });

  it("refuses to start on a short token, without printing it", () => {
    const secret = "short-secret-token";
    let message = "";
    try {
      loadConfig({ DATABASE_URL, DEVICE_TOKENS: `tablet:${secret}` });
    } catch (err) {
      message = String(err);
    }
    expect(message).toMatch(/at least 32 characters/);
    expect(message).toContain("tablet");
    expect(message).not.toContain(secret);
  });
});

describe("parseDeviceTokens", () => {
  it("takes whitespace around entries and a trailing comma", () => {
    expect(parseDeviceTokens(` tablet:${token("a")} , companion:${token("b")},`)).toHaveLength(2);
  });

  it("allows what `openssl rand -base64 36` makes", () => {
    const made = "k3J9aZ0rTq+x/Wm1B2cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5a6B7c8D9eF";
    expect(parseDeviceTokens(`tablet:${made}`)).toEqual([{ name: "tablet", token: made }]);
  });

  it("means no devices when empty", () => {
    expect(parseDeviceTokens("")).toEqual([]);
    expect(parseDeviceTokens(" , ")).toEqual([]);
  });

  it.each([
    ["an entry without a colon", `tablet${token("a")}`, /entry 1 is not name:token/],
    ["a token of 31 characters", `tablet:${token("a").slice(1)}`, /at least 32/],
    ["an empty name", `:${token("a")}`, /name must be/],
    ["a name with a space", `my tablet:${token("a")}`, /name must be/],
    ["a token with a space", `tablet:${token("a")} ${token("a")}`, /characters from/],
    ["two entries with one name", `t:${token("a")},t:${token("b")}`, /used twice/],
    ["two entries with one token", `t:${token("a")},u:${token("a")}`, /same as another/],
  ])("rejects %s", (_what, raw, expected) => {
    expect(() => parseDeviceTokens(raw)).toThrow(expected);
  });
});

describe("describeConfigError", () => {
  it("names the variable and says what is wrong, without a value", () => {
    const secret = "short-secret-token";
    let text = "";
    try {
      loadConfig({ DEVICE_TOKENS: `tablet:${secret}` });
    } catch (err) {
      text = describeConfigError(err);
    }
    expect(text).toMatch(/DATABASE_URL: DATABASE_URL is required/);
    expect(text).toMatch(
      /DEVICE_TOKENS: DEVICE_TOKENS entry 1 \(tablet\): the token must be at least 32/,
    );
    expect(text).not.toContain(secret);
  });
});
