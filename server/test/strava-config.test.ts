import { describe, expect, it } from "vitest";
import { describeConfigError, loadConfig } from "../src/config.ts";
import { stravaSettings } from "../src/config-strava.ts";

const DATABASE_URL = "postgres://overdrive:secret@db:5432/overdrive";
const full = {
  STRAVA_CLIENT_ID: "424242",
  STRAVA_CLIENT_SECRET: "client-secret-abcdef0123456789",
  STRAVA_REFRESH_TOKEN: "refresh-token-abcdef0123456789",
};

describe("the Strava settings (ADR 0020)", () => {
  it("is off by default, and blank values (what compose passes for an unset variable) count as unset", () => {
    const config = loadConfig({ DATABASE_URL });
    expect(config.STRAVA_CLIENT_ID).toBeUndefined();
    expect(config.STRAVA_BACKFILL).toBe(false);
    expect(stravaSettings(config)).toEqual({
      off: "STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET and STRAVA_REFRESH_TOKEN are not set",
      partial: false,
    });
    const blank = loadConfig({
      DATABASE_URL,
      STRAVA_CLIENT_ID: "",
      STRAVA_CLIENT_SECRET: "  ",
      STRAVA_REFRESH_TOKEN: "",
      STRAVA_BACKFILL: "",
      STRAVA_BASE_URL: "",
    });
    expect(stravaSettings(blank)).toMatchObject({ partial: false });
    expect(blank.STRAVA_BASE_URL).toBe("https://www.strava.com");
  });

  it("is on only when the client id, the secret and the refresh token are all set; backfill stays off", () => {
    const mode = stravaSettings(loadConfig({ DATABASE_URL, ...full }));
    expect(mode).toEqual({
      on: {
        clientId: "424242",
        clientSecret: full.STRAVA_CLIENT_SECRET,
        refreshToken: full.STRAVA_REFRESH_TOKEN,
        backfill: false,
        baseUrl: "https://www.strava.com",
      },
    });
  });

  it("says which variables are missing when only some are set, which is probably a mistake", () => {
    const some = stravaSettings(
      loadConfig({ DATABASE_URL, STRAVA_CLIENT_ID: "1", STRAVA_CLIENT_SECRET: "x" }),
    );
    expect(some).toEqual({ off: "STRAVA_REFRESH_TOKEN is not set", partial: true });
    const one = stravaSettings(loadConfig({ DATABASE_URL, STRAVA_REFRESH_TOKEN: "x" }));
    expect(one).toEqual({
      off: "STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET are not set",
      partial: true,
    });
  });

  it.each([
    ["true", true],
    ["TRUE", true],
    ["1", true],
    ["yes", true],
    ["false", false],
    ["0", false],
    ["no", false],
  ])("reads STRAVA_BACKFILL=%s as %s", (raw, expected) => {
    expect(loadConfig({ DATABASE_URL, STRAVA_BACKFILL: raw }).STRAVA_BACKFILL).toBe(expected);
  });

  it("refuses bad values by name and never prints them", () => {
    const attempts: [Record<string, string>, string][] = [
      [{ STRAVA_CLIENT_ID: "not a number 1234" }, "STRAVA_CLIENT_ID"],
      [{ STRAVA_CLIENT_SECRET: "has a space" }, "STRAVA_CLIENT_SECRET"],
      [{ STRAVA_REFRESH_TOKEN: 'quote"inside' }, "STRAVA_REFRESH_TOKEN"],
      [{ STRAVA_BACKFILL: "maybe" }, "STRAVA_BACKFILL"],
      [{ STRAVA_BASE_URL: "ftp://strava.example" }, "STRAVA_BASE_URL"],
      [{ STRAVA_BASE_URL: "https://user:pw@strava.example" }, "STRAVA_BASE_URL"],
    ];
    for (const [env, name] of attempts) {
      let message = "";
      try {
        loadConfig({ DATABASE_URL, ...env });
      } catch (error) {
        message = describeConfigError(error);
      }
      expect(message, name).toContain(name);
      for (const value of Object.values(env)) expect(message).not.toContain(value);
      expect(message).not.toContain("pw@");
    }
  });

  it("reads a base address without a trailing slash, for the tests' fake", () => {
    expect(
      loadConfig({ DATABASE_URL, STRAVA_BASE_URL: "http://127.0.0.1:9999/" }).STRAVA_BASE_URL,
    ).toBe("http://127.0.0.1:9999");
  });
});
