import { randomBytes } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { Db } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import { silentLogger } from "../src/log.ts";
import { migrate, SHARED_MIGRATIONS_DIR } from "../src/migrate.ts";
import {
  createTenantRegistry,
  createTenantServices as createTenantServicesForTest,
  type TenantRegistry,
} from "../src/tenancy/registry.ts";
import { asRide, rideFixture } from "./helpers/app.ts";
import { openTenancyTestDb, type TenancyTestDb } from "./helpers/db.ts";

let t: TenancyTestDb;
let shared: Db;

/** Synthetic: random per run. */
const token = () => randomBytes(24).toString("base64url");
const TOKEN_A = token();
const TOKEN_B = token();
const TOKEN_C = token();
const TOKEN_D = token();

const INSTALLATION = {
  DATABASE_URL: "postgres://user:password@localhost:5432/db",
  LOG_LEVEL: "silent",
};

/** The registry's tenant databases must not be closed twice (the helper closes its own pools at the end). */
const schemaDb = (schema: string): Db => ({ ...t.schemaDb(schema), close: async () => {} });

async function addUser(
  id: number,
  handle: string,
  deviceToken: string,
  deviceName = "tablet",
  extra: { disabled?: boolean; settings?: Record<string, string>; chat?: string } = {},
): Promise<void> {
  await shared.query(
    "INSERT INTO users (handle, telegram_chat_id, disabled_at) VALUES ($1, $2, $3)",
    [handle, extra.chat ?? null, extra.disabled === true ? new Date() : null],
  );
  await shared.query(
    "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, 'device', $2, $3)",
    [sha256Hex(deviceToken), deviceName, id],
  );
  for (const [key, value] of Object.entries(extra.settings ?? {})) {
    await shared.query("INSERT INTO user_settings (user_id, key, value) VALUES ($1, $2, $3)", [
      id,
      key,
      value,
    ]);
  }
  await t.db.exec(`CREATE SCHEMA IF NOT EXISTS t_${id}`);
  await migrate(t.schemaDb(`t_${id}`));
}

beforeAll(async () => {
  t = await openTenancyTestDb();
  await t.db.exec("CREATE SCHEMA shared");
  shared = t.schemaDb("shared");
  await migrate(shared, SHARED_MIGRATIONS_DIR);
});

afterAll(async () => {
  await t.close();
});

let registry: TenantRegistry | null = null;
let app: FastifyInstance | null = null;

afterEach(async () => {
  await app?.close();
  await registry?.close();
  app = null;
  registry = null;
  await t.db.exec(
    "DROP SCHEMA IF EXISTS t_1 CASCADE; DROP SCHEMA IF EXISTS t_2 CASCADE; DROP SCHEMA IF EXISTS t_3 CASCADE; DROP SCHEMA IF EXISTS t_4 CASCADE",
  );
  await t.db.exec("TRUNCATE shared.users RESTART IDENTITY CASCADE");
});

async function start(): Promise<FastifyInstance> {
  registry = await createTenantRegistry({
    sharedDb: shared,
    schemaDb,
    installation: INSTALLATION,
    log: silentLogger,
    startJobs: false,
  });
  app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [] },
    tenants: registry,
  });
  return app;
}

const auth = (value: string) => ({ authorization: `Bearer ${value}` });

function put(a: FastifyInstance, value: string, id: string, startEpochMillis: number) {
  const record = asRide(rideFixture("minimal-free-ride.json"), id, startEpochMillis);
  return a.inject({
    method: "PUT",
    url: `/v1/rides/${id}`,
    headers: { "content-type": "application/json", ...auth(value) },
    payload: JSON.stringify(record),
  });
}

const rideIds = async (a: FastifyInstance, value: string): Promise<string[]> => {
  const res = await a.inject({ method: "GET", url: "/v1/rides", headers: auth(value) });
  expect(res.statusCode).toBe(200);
  return (res.json() as { items: { id: string }[] }).items.map((r) => r.id).sort();
};

describe("two tenants in one process", () => {
  it("each token sees only its own rides and profile, and the same ride id is accepted in both", async () => {
    await addUser(1, "alice", TOKEN_A, "tablet");
    await addUser(2, "bob", TOKEN_B, "phone");
    const a = await start();

    expect((await put(a, TOKEN_A, "ride-same", 1_800_000_000_000)).statusCode).toBe(201);
    expect((await put(a, TOKEN_B, "ride-same", 1_800_000_000_000)).statusCode).toBe(201);
    expect((await put(a, TOKEN_A, "ride-only-a", 1_800_100_000_000)).statusCode).toBe(201);

    expect(await rideIds(a, TOKEN_A)).toEqual(["ride-only-a", "ride-same"]);
    expect(await rideIds(a, TOKEN_B)).toEqual(["ride-same"]);

    // The rows are in the owners' schemas.
    const inA = await t
      .schemaDb("t_1")
      .query<{ n: string }>("SELECT count(*)::text AS n FROM rides");
    const inB = await t
      .schemaDb("t_2")
      .query<{ n: string }>("SELECT count(*)::text AS n FROM rides");
    expect([inA.rows[0]?.n, inB.rows[0]?.n]).toEqual(["2", "1"]);
    // The device that uploaded is the token's name.
    const by = await t
      .schemaDb("t_2")
      .query<{ uploaded_by: string }>("SELECT uploaded_by FROM rides");
    expect(by.rows[0]?.uploaded_by).toBe("phone");

    const patch = await a.inject({
      method: "PATCH",
      url: "/v1/profile",
      headers: { "content-type": "application/json", ...auth(TOKEN_A) },
      payload: JSON.stringify({ ftp: 277 }),
    });
    expect(patch.statusCode).toBe(200);
    const profileA = await a.inject({ method: "GET", url: "/v1/profile", headers: auth(TOKEN_A) });
    const profileB = await a.inject({ method: "GET", url: "/v1/profile", headers: auth(TOKEN_B) });
    expect(profileA.json().ftp).toBe(277);
    expect(profileB.json().ftp).toBeNull();
  });

  it("answers GET /v1/me with the caller's handle and device, and nothing secret", async () => {
    await addUser(1, "alice", TOKEN_A, "tablet");
    await addUser(2, "bob", TOKEN_B, "phone");
    const a = await start();
    const res = await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_B) });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ userId: 2, handle: "bob", device: "phone" });
    expect(res.body).not.toContain(TOKEN_B);
  });

  it("answers 401 to an unknown token, a revoked one and a disabled user's", async () => {
    await addUser(1, "alice", TOKEN_A);
    await addUser(2, "bob", TOKEN_B, "tablet", { disabled: true });
    await addUser(3, "carol", TOKEN_C);
    await shared.query("UPDATE user_tokens SET revoked_at = now() WHERE user_id = 3");
    const a = await start();
    for (const value of [token(), TOKEN_B, TOKEN_C]) {
      const res = await a.inject({ method: "GET", url: "/v1/me", headers: auth(value) });
      expect(res.statusCode).toBe(401);
      expect(res.json().error.code).toBe("unauthorized");
    }
    expect((await a.inject({ method: "GET", url: "/v1/me" })).statusCode).toBe(401);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_A) })).statusCode,
    ).toBe(200);
    expect(registry?.runtime(2)?.state).toBe("disabled");
  });

  it("puts a tenant with broken settings out of service and keeps the other answering", async () => {
    await addUser(1, "alice", TOKEN_A);
    await addUser(2, "bob", TOKEN_B, "tablet", { settings: { TIMEZONE: "Not/AZone" } });
    const a = await start();
    expect(registry?.runtime(2)?.state).toBe("failed");
    expect(registry?.runtime(2)?.error).toMatch(/TIMEZONE/);
    expect(registry?.runtime(1)?.state).toBe("ready");
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_A) })).statusCode,
    ).toBe(200);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_B) })).statusCode,
    ).toBe(500);
  });
});

describe("the directory and its operations", () => {
  it("finds users by token, id and chat", async () => {
    await addUser(1, "alice", TOKEN_A, "tablet", { chat: "111" });
    await addUser(2, "bob", TOKEN_B, "phone", { chat: "222", disabled: true });
    await start();
    const r = registry as TenantRegistry;
    expect((await r.byToken(sha256Hex(TOKEN_A), "device"))?.tenant.handle).toBe("alice");
    expect(await r.byToken(sha256Hex(TOKEN_A), "mcp")).toBeNull();
    expect(await r.byToken(sha256Hex(TOKEN_B), "device")).toBeNull();
    expect((await r.byUserId(1))?.schema).toBe("t_1");
    expect((await r.byChat("111"))?.userId).toBe(1);
    expect(await r.byChat("222")).toBeNull();
    expect((await r.list()).map((x) => x.userId)).toEqual([1, 2]);
  });

  it("add, reload, refreshTokens and remove follow the shared tables", async () => {
    await addUser(1, "alice", TOKEN_A);
    const a = await start();
    const r = registry as TenantRegistry;

    // add: a user created after the start
    await addUser(2, "bob", TOKEN_B);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_B) })).statusCode,
    ).toBe(401);
    await r.add(2);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_B) })).statusCode,
    ).toBe(200);
    await expect(r.add(2)).rejects.toThrow(/already hosted/);

    // reload: new settings take effect
    expect(r.runtime(2)?.services?.timezone).toBe("UTC");
    await shared.query(
      "INSERT INTO user_settings (user_id, key, value) VALUES (2, 'TIMEZONE', 'Asia/Tokyo')",
    );
    await r.reload(2);
    expect(r.runtime(2)?.services?.timezone).toBe("Asia/Tokyo");
    // …and a bad one fails the tenant, not the call
    await shared.query("UPDATE user_settings SET value = 'Nope/Nope' WHERE user_id = 2");
    await r.reload(2);
    expect(r.runtime(2)?.state).toBe("failed");
    await shared.query("UPDATE user_settings SET value = 'Europe/Paris' WHERE user_id = 2");
    await r.reload(2);
    expect(r.runtime(2)?.state).toBe("ready");

    // refreshTokens: a revoked token stops working, a new one starts
    await shared.query("UPDATE user_tokens SET revoked_at = now() WHERE user_id = 2");
    await shared.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, 'device', 'new', 2)",
      [sha256Hex(TOKEN_D)],
    );
    await r.refreshTokens();
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_B) })).statusCode,
    ).toBe(401);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_D) })).json().device,
    ).toBe("new");

    // refreshTokens also follows disabled_at
    await shared.query("UPDATE users SET disabled_at = now() WHERE id = 2");
    await r.refreshTokens();
    expect(r.runtime(2)?.state).toBe("disabled");
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_D) })).statusCode,
    ).toBe(401);
    await shared.query("UPDATE users SET disabled_at = NULL WHERE id = 2");
    await r.refreshTokens();
    expect(r.runtime(2)?.state).toBe("ready");
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_D) })).statusCode,
    ).toBe(200);

    // remove: tokens go, the other tenant stays
    await r.remove(2);
    expect(r.runtime(2)).toBeNull();
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_D) })).statusCode,
    ).toBe(401);
    expect(
      (await a.inject({ method: "GET", url: "/v1/me", headers: auth(TOKEN_A) })).statusCode,
    ).toBe(200);
  });

  it("refuses to reload a tenant that has a live coach session", async () => {
    await addUser(1, "alice", TOKEN_A);
    await start();
    const r = registry as TenantRegistry;
    const coach = r.runtime(1)?.services?.liveCoach;
    expect(coach?.hasLiveSession()).toBe(false);
    if (coach === undefined) throw new Error("no live coach");
    const spy = coach.hasLiveSession;
    coach.hasLiveSession = () => true;
    await expect(r.reload(1)).rejects.toThrow(/live coach session/);
    expect(r.runtime(1)?.state).toBe("ready");
    coach.hasLiveSession = spy;
    await r.reload(1);
    expect(r.runtime(1)?.state).toBe("ready");
  });

  it("starts the jobs of each ready tenant with a gap, and only when asked to", async () => {
    await addUser(1, "alice", TOKEN_A);
    await addUser(2, "bob", TOKEN_B);
    await addUser(3, "carol", TOKEN_C, "tablet", { settings: { TIMEZONE: "Nope/Nope" } });
    const starts: number[] = [];
    const idle = await createTenantRegistry({
      sharedDb: shared,
      schemaDb,
      installation: INSTALLATION,
      log: silentLogger,
      startJobs: false,
    });
    await idle.start();
    await idle.close();

    const timed = await createTenantRegistry({
      sharedDb: shared,
      schemaDb,
      installation: INSTALLATION,
      log: silentLogger,
      startJobs: true,
      startGapMs: 30,
      createServices: (input) => {
        const real = createTenantServicesForTest(input);
        const start = real.start;
        real.start = () => {
          starts.push(Date.now());
          start();
        };
        return real;
      },
    });
    await timed.start();
    expect(starts).toHaveLength(2);
    expect((starts[1] ?? 0) - (starts[0] ?? 0)).toBeGreaterThanOrEqual(25);
    await timed.close();
  });
});
