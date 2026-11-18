import { describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import { loadConfig } from "../src/config.ts";

describe("GET /healthz", () => {
  it("reports ok", async () => {
    const app = buildApp({ LOG_LEVEL: "silent" });
    const res = await app.inject({ method: "GET", url: "/healthz" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "ok" });
    await app.close();
  });
});

describe("loadConfig", () => {
  it("applies defaults", () => {
    expect(loadConfig({})).toEqual({ HOST: "127.0.0.1", PORT: 8080, LOG_LEVEL: "info" });
  });

  it("rejects an invalid port", () => {
    expect(() => loadConfig({ PORT: "70000" })).toThrow();
  });
});
