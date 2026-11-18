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
    expect(loadConfig({ DATABASE_URL })).toEqual({
      HOST: "127.0.0.1",
      PORT: 8080,
      LOG_LEVEL: "info",
      DATABASE_URL,
      DEVICE_TOKENS: [],
    });
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
