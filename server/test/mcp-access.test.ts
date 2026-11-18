import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { Db } from "../src/db.ts";
import { bearer, expectError, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  OWNER_HASH,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

let db: Db;
let fake: FakeIntervals;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});

afterAll(async () => {
  await fake.close();
  await db.close();
});

const PATHS: [string, string][] = [
  ["POST", "/mcp"],
  ["GET", "/mcp"],
  ["GET", "/.well-known/oauth-protected-resource"],
  ["GET", "/.well-known/oauth-protected-resource/mcp"],
  ["GET", "/.well-known/oauth-authorization-server"],
  ["POST", "/oauth/register"],
  ["GET", "/oauth/authorize"],
  ["POST", "/oauth/authorize"],
  ["POST", "/oauth/token"],
  ["POST", "/oauth/revoke"],
];

describe("the MCP server is off unless it is configured", () => {
  async function appWith(config: Record<string, unknown>) {
    const logs: string[] = [];
    const app = buildApp({
      config: { LOG_LEVEL: "info", DEVICE_TOKENS: [TABLET], ...config },
      db,
      logStream: { write: (line) => logs.push(line) },
    });
    await app.ready();
    return { app, logs: () => logs.join("\n") };
  }

  it.each([
    ["nothing is set", {}, "PUBLIC_BASE_URL and OWNER_PASSPHRASE_HASH are not set"],
    [
      "only the address is set",
      { PUBLIC_BASE_URL: "https://overdrive.example.com" },
      "OWNER_PASSPHRASE_HASH is not set",
    ],
    ["only the hash is set", { OWNER_PASSPHRASE_HASH: "set-later" }, "PUBLIC_BASE_URL is not set"],
    [
      "only static tokens are set",
      { MCP_STATIC_TOKENS: [{ name: "claude", token: STATIC_TOKEN }] },
      "PUBLIC_BASE_URL and OWNER_PASSPHRASE_HASH are not set",
    ],
  ] as [string, Record<string, unknown>, string][])(
    "has no routes and says so in the log when %s",
    async (_what, config, reason) => {
      const hash =
        config.OWNER_PASSPHRASE_HASH === undefined
          ? {}
          : { OWNER_PASSPHRASE_HASH: await OWNER_HASH };
      const { app, logs } = await appWith({ ...config, ...hash });
      try {
        for (const [method, url] of PATHS) {
          const res = await app.inject({ method: method as "GET" | "POST", url });
          expectError(res, "not_found");
        }
        expect(logs()).toContain(`MCP server is off: ${reason}`);
        expect(logs()).not.toContain("MCP server is on");
        // The device API is untouched.
        expectError(await app.inject({ method: "GET", url: "/v1/profile" }), "unauthorized");
        expect(
          (await app.inject({ method: "GET", url: "/v1/profile", headers: bearer() })).statusCode,
        ).toBe(200);
        expect((await app.inject({ method: "GET", url: "/healthz" })).statusCode).toBe(200);
      } finally {
        await app.close();
      }
    },
  );

  it("says it is on, where, and how many static tokens it has", async () => {
    const { app, logs } = await appWith({
      PUBLIC_BASE_URL: "https://overdrive.example.com",
      OWNER_PASSPHRASE_HASH: await OWNER_HASH,
      MCP_STATIC_TOKENS: [{ name: "claude", token: STATIC_TOKEN }],
    });
    try {
      const line = logs()
        .split("\n")
        .filter((l) => l.includes("MCP server is on"))
        .map((l) => JSON.parse(l) as Record<string, unknown>)[0];
      expect(line).toMatchObject({
        endpoint: "https://overdrive.example.com/mcp",
        staticTokens: 1,
      });
      // The log line carries no secret.
      expect(logs()).not.toContain(STATIC_TOKEN);
      expect(logs()).not.toContain(await OWNER_HASH);
      for (const [method, url] of PATHS) {
        const res = await app.inject({ method: method as "GET" | "POST", url });
        expect(res.statusCode, `${method} ${url}`).not.toBe(404);
      }
    } finally {
      await app.close();
    }
  });
});

describe("static tokens", () => {
  it("open /mcp for a client that can only send a fixed header", async () => {
    const h = await startMcpHarness(db, fake, {
      staticTokens: [{ name: "claude-desktop", token: STATIC_TOKEN }],
    });
    try {
      const client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
      const result = await callTool(client, "get_profile");
      expect(result.isError).toBe(false);
      await client.close();
    } finally {
      await h.close();
    }
  });

  it("are refused when wrong, and when none is configured", async () => {
    const withToken = await startMcpHarness(db, fake, {
      staticTokens: [{ name: "claude-desktop", token: STATIC_TOKEN }],
    });
    try {
      const wrong = await fetch(withToken.mcpUrl, {
        method: "POST",
        headers: { authorization: `Bearer ${STATIC_TOKEN}x`, "content-type": "application/json" },
        body: "{}",
      });
      expect(wrong.status).toBe(401);
      expect(wrong.headers.get("www-authenticate")).toContain('error="invalid_token"');
    } finally {
      await withToken.close();
    }
    const without = await startMcpHarness(db, fake);
    try {
      const res = await fetch(without.mcpUrl, {
        method: "POST",
        headers: { authorization: `Bearer ${STATIC_TOKEN}`, "content-type": "application/json" },
        body: "{}",
      });
      expect(res.status).toBe(401);
    } finally {
      await without.close();
    }
  });

  it("are not device tokens, and device tokens are not MCP tokens", async () => {
    const h = await startMcpHarness(db, fake, {
      staticTokens: [{ name: "claude-desktop", token: STATIC_TOKEN }],
    });
    try {
      const asDevice = await h.app.inject({
        method: "GET",
        url: "/v1/profile",
        headers: { authorization: `Bearer ${STATIC_TOKEN}` },
      });
      expectError(asDevice, "unauthorized");
      const mcpWithDeviceToken = await fetch(h.mcpUrl, {
        method: "POST",
        headers: { ...bearer(), "content-type": "application/json" },
        body: "{}",
      });
      expect(mcpWithDeviceToken.status).toBe(401);
    } finally {
      await h.close();
    }
  });
});
