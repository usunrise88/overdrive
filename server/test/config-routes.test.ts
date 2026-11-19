import { describe, expect, it } from "vitest";
import { describeConfigError, loadConfig } from "../src/config.ts";

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

describe("the route settings (ADR 0036)", () => {
  it("are all optional: routing is off, a 30 second timeout and a 20 m step", () => {
    const config = load({});
    expect(config.BROUTER_URL).toBeUndefined();
    expect(config).toMatchObject({ BROUTER_TIMEOUT_SECONDS: 30, ROUTE_STEP_METERS: 20 });
  });

  it("treat empty values as not set (compose passes an empty VAR)", () => {
    const config = load({ BROUTER_URL: "", BROUTER_TIMEOUT_SECONDS: " ", ROUTE_STEP_METERS: "" });
    expect(config.BROUTER_URL).toBeUndefined();
    expect(config).toMatchObject({ BROUTER_TIMEOUT_SECONDS: 30, ROUTE_STEP_METERS: 20 });
  });

  it("take the address of BRouter as an origin, with or without a trailing slash", () => {
    expect(load({ BROUTER_URL: "http://brouter:17777" }).BROUTER_URL).toBe("http://brouter:17777");
    expect(load({ BROUTER_URL: "http://brouter:17777/" }).BROUTER_URL).toBe("http://brouter:17777");
    expect(load({ BROUTER_URL: "https://routing.example.com/brouter/" }).BROUTER_URL).toBe(
      "https://routing.example.com/brouter",
    );
  });

  it("take whole numbers within bounds", () => {
    expect(load({ BROUTER_TIMEOUT_SECONDS: "120", ROUTE_STEP_METERS: "50" })).toMatchObject({
      BROUTER_TIMEOUT_SECONDS: 120,
      ROUTE_STEP_METERS: 50,
    });
  });

  it.each([
    ["BROUTER_URL", "brouter:17777"],
    ["BROUTER_URL", "ftp://brouter"],
    ["BROUTER_URL", "http://user:pass@brouter:17777"],
    ["BROUTER_URL", "http://brouter:17777/?x=1"],
    ["BROUTER_TIMEOUT_SECONDS", "0"],
    ["BROUTER_TIMEOUT_SECONDS", "301"],
    ["BROUTER_TIMEOUT_SECONDS", "soon"],
    ["ROUTE_STEP_METERS", "4"],
    ["ROUTE_STEP_METERS", "201"],
    ["ROUTE_STEP_METERS", "20.5"],
  ])("refuse %s=%s with a message that names it", (name, value) => {
    expect(problem({ [name]: value })).toContain(name);
  });

  it("never repeat a credential in a message", () => {
    expect(problem({ BROUTER_URL: "http://user:hunter2@brouter:17777" })).not.toContain("hunter2");
  });
});
