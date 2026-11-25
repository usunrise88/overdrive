import { randomBytes } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildAdminApp } from "../src/admin/app.ts";
import { buildApp } from "../src/app.ts";
import type { Db } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import { silentLogger } from "../src/log.ts";
import { MIGRATIONS_DIR, type Migration, migrate, SHARED_MIGRATIONS_DIR } from "../src/migrate.ts";
import { hashPassphrase } from "../src/oauth/passphrase.ts";
import { createTenantRegistry, type TenantRegistry } from "../src/tenancy/registry.ts";
import { openTenancyTestDb, type TenancyTestDb } from "./helpers/db.ts";
import { collectingLogger } from "./helpers/support.ts";

let t: TenancyTestDb;
let shared: Db;

/** Synthetic: random per run. */
const ADMIN_TOKEN = randomBytes(36).toString("base64url");
const INSTALLATION = {
  DATABASE_URL: "postgres://user:password@localhost:5432/db",
  LOG_LEVEL: "silent",
  ADMIN_TOKEN,
};

/** The registry's and the admin's tenant databases must not be closed twice (the helper closes its own pools at the end). */
const schemaDb = (schema: string): Db => ({ ...t.schemaDb(schema), close: async () => {} });

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
let admin: FastifyInstance | null = null;
let tenantApp: FastifyInstance | null = null;
let logging = collectingLogger();

afterEach(async () => {
  await admin?.close();
  await tenantApp?.close();
  await registry?.close();
  admin = null;
  tenantApp = null;
  registry = null;
  const schemas = await t.db.query<{ schema_name: string }>(
    "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 't\\_%'",
  );
  for (const row of schemas.rows) await t.db.exec(`DROP SCHEMA "${row.schema_name}" CASCADE`);
  await t.db.exec("TRUNCATE shared.users RESTART IDENTITY CASCADE");
});

async function start(migrations: Migration[] | URL = MIGRATIONS_DIR): Promise<FastifyInstance> {
  logging = collectingLogger();
  registry = await createTenantRegistry({
    sharedDb: shared,
    schemaDb,
    installation: INSTALLATION,
    log: silentLogger,
    startJobs: false,
  });
  tenantApp = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [] },
    tenants: registry,
  });
  admin = buildAdminApp({
    registry,
    sharedDb: shared,
    schemaDb,
    migrations,
    log: logging.log,
    config: { ADMIN_TOKEN, installation: INSTALLATION },
  });
  return admin;
}

const asAdmin = { authorization: `Bearer ${ADMIN_TOKEN}` };

function call(method: string, url: string, payload?: unknown, headers: object = asAdmin) {
  return (admin as FastifyInstance).inject({
    method: method as "GET",
    url,
    headers: {
      ...headers,
      ...(payload === undefined ? {} : { "content-type": "application/json" }),
    },
    ...(payload === undefined ? {} : { payload: JSON.stringify(payload) }),
  });
}

const me = (token: string) =>
  (tenantApp as FastifyInstance).inject({
    method: "GET",
    url: "/v1/me",
    headers: { authorization: `Bearer ${token}` },
  });

async function create(handle: string, extra: object = {}) {
  const res = await call("POST", "/admin/users", { handle, ...extra });
  expect(res.statusCode).toBe(201);
  return res.json() as { id: number; handle: string; deviceToken: string; state: string };
}

const schemaExists = async (name: string): Promise<boolean> =>
  (await t.db.query("SELECT 1 FROM information_schema.schemata WHERE schema_name = $1", [name]))
    .rows.length > 0;

describe("the admin API", () => {
  it("answers 401 without the admin token, with a wrong one and with a user's token", async () => {
    await start();
    const user = await create("alice");
    for (const headers of [
      {},
      { authorization: "Bearer nope" },
      { authorization: `Bearer ${ADMIN_TOKEN}x` },
      { authorization: ADMIN_TOKEN },
      { authorization: `Bearer ${user.deviceToken}` },
    ]) {
      const res = await call("GET", "/admin/users", undefined, headers);
      expect(res.statusCode).toBe(401);
      expect(res.json()).toEqual({
        error: { code: "unauthorized", message: expect.any(String) },
      });
    }
    expect((await call("GET", "/admin/users")).statusCode).toBe(200);
  });

  it("refuses to be built without an admin token", async () => {
    await start();
    expect(() =>
      buildAdminApp({
        registry: registry as TenantRegistry,
        sharedDb: shared,
        schemaDb,
        migrations: MIGRATIONS_DIR,
        log: silentLogger,
        config: { ADMIN_TOKEN: undefined, installation: INSTALLATION },
      }),
    ).toThrow(/ADMIN_TOKEN/);
  });

  it("creates a user whose token works on /v1/me, and never shows the token again", async () => {
    await start();
    const owner = await create("rider");
    expect(owner).toMatchObject({ id: 1, handle: "rider", state: "ready" });
    expect(owner.deviceToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(await schemaExists("t_1")).toBe(true);

    const res = await me(owner.deviceToken);
    expect(res.statusCode).toBe(200);
    expect(JSON.stringify(res.json())).toContain("rider");

    // Stored only as a digest, named device-1.
    const rows = await shared.query<{ token_sha256: string; name: string; kind: string }>(
      "SELECT token_sha256, name, kind FROM user_tokens",
    );
    expect(rows.rows).toEqual([
      { token_sha256: sha256Hex(owner.deviceToken), name: "device-1", kind: "device" },
    ]);

    const listing = await call("GET", "/admin/users");
    expect(listing.json()).toEqual({
      users: [
        {
          id: 1,
          handle: "rider",
          state: "ready",
          telegramChatId: null,
          disabled: false,
          tokens: [{ name: "device-1", kind: "device", revoked: false }],
        },
      ],
    });
    expect(listing.body).not.toContain(owner.deviceToken);
    expect(listing.body).not.toContain(sha256Hex(owner.deviceToken));
    expect((await call("GET", "/admin/users/1/settings")).body).not.toContain(owner.deviceToken);
  });

  it("rejects a duplicate handle (409), a bad handle (400), a plain passphrase (400) and unknown settings (400)", async () => {
    await start();
    await create("alice", { telegramChatId: "4242" });
    expect((await call("POST", "/admin/users", { handle: "alice" })).statusCode).toBe(409);
    expect(
      (await call("POST", "/admin/users", { handle: "bob", telegramChatId: 4242 })).statusCode,
    ).toBe(409);
    for (const handle of ["Alice", "a", "1abc", "has space", ""]) {
      expect((await call("POST", "/admin/users", { handle })).statusCode).toBe(400);
    }
    const plain = await call("POST", "/admin/users", {
      handle: "bob",
      passphraseHash: "correct horse battery staple",
    });
    expect(plain.statusCode).toBe(400);
    expect(plain.body).not.toContain("correct horse");
    const unknown = await call("POST", "/admin/users", {
      handle: "bob",
      settings: { LLM_API_KEY: "x" },
    });
    expect(unknown.statusCode).toBe(400);
    expect(unknown.body).toContain("LLM_API_KEY");
    // Nothing was left behind by the refusals.
    expect((await shared.query("SELECT 1 FROM users")).rows).toHaveLength(1);
  });

  it("stores a passphrase hash, a chat and settings given at creation", async () => {
    await start();
    const hash = await hashPassphrase("a synthetic passphrase", { N: 1024, r: 8, p: 1 });
    const user = await create("carol", {
      passphraseHash: hash,
      telegramChatId: 777,
      tokenName: "tablet",
      settings: { TIMEZONE: "Europe/Paris", INTERVALS_API_KEY: "synthetic-key" },
    });
    const row = await shared.query<{ passphrase_hash: string; telegram_chat_id: string }>(
      "SELECT passphrase_hash, telegram_chat_id FROM users WHERE id = $1",
      [user.id],
    );
    expect(row.rows[0]).toEqual({ passphrase_hash: hash, telegram_chat_id: "777" });
    expect((await call("GET", `/admin/users/${user.id}`)).statusCode).toBe(404);
    const listed = (await call("GET", "/admin/users")).json() as {
      users: { tokens: { name: string }[] }[];
    };
    expect(listed.users[0]?.tokens[0]?.name).toBe("tablet");
    expect((await call("GET", `/admin/users/${user.id}/settings`)).statusCode).toBe(200);
  });

  it("leaves no user row and no schema when the migration fails", async () => {
    const bad: Migration[] = [
      { version: 1, name: "broken", sql: "SELECT * FROM table_that_does_not_exist", checksum: "x" },
    ];
    await start(bad);
    const res = await call("POST", "/admin/users", { handle: "dave" });
    expect(res.statusCode).toBe(500);
    expect((await shared.query("SELECT 1 FROM users")).rows).toHaveLength(0);
    expect((await shared.query("SELECT 1 FROM user_tokens")).rows).toHaveLength(0);
    expect(await schemaExists("t_1")).toBe(false);
    expect(registry?.runtime(1)).toBeNull();
  });

  it("issues and revokes tokens: the new one works, the revoked one stops", async () => {
    await start();
    const user = await create("erin");
    const second = await call("POST", `/admin/users/${user.id}/tokens`, {
      kind: "device",
      name: "phone",
    });
    expect(second.statusCode).toBe(201);
    const token = (second.json() as { token: string }).token;
    expect((await me(token)).statusCode).toBe(200);
    expect(
      (await call("POST", `/admin/users/${user.id}/tokens`, { kind: "device", name: "phone" }))
        .statusCode,
    ).toBe(409);
    expect(
      (await call("POST", `/admin/users/${user.id}/tokens`, { kind: "bad", name: "x" })).statusCode,
    ).toBe(400);
    expect((await call("DELETE", `/admin/users/${user.id}/tokens/phone`)).statusCode).toBe(204);
    expect((await me(token)).statusCode).toBe(401);
    expect((await me(user.deviceToken)).statusCode).toBe(200);
    expect((await call("DELETE", `/admin/users/${user.id}/tokens/phone`)).statusCode).toBe(404);
    const listed = (await call("GET", "/admin/users")).json() as {
      users: { tokens: { name: string; revoked: boolean }[] }[];
    };
    expect(listed.users[0]?.tokens).toContainEqual({
      name: "phone",
      kind: "device",
      revoked: true,
    });
  });

  it("validates settings and names only the key, masks secrets on read", async () => {
    await start();
    const user = await create("frank");
    const secretValue = "super-secret-value-123";
    const bad = await call("PUT", `/admin/users/${user.id}/settings`, {
      TIMEZONE: "Not/AZone",
      INTERVALS_API_KEY: secretValue,
    });
    expect(bad.statusCode).toBe(400);
    expect(bad.body).toContain("TIMEZONE");
    expect(bad.body).not.toContain("Not/AZone");
    expect(bad.body).not.toContain(secretValue);
    expect((await call("GET", `/admin/users/${user.id}/settings`)).json()).toEqual({
      settings: {},
    });

    const unknown = await call("PUT", `/admin/users/${user.id}/settings`, {
      LLM_API_KEY: secretValue,
    });
    expect(unknown.statusCode).toBe(400);
    expect(unknown.body).not.toContain(secretValue);

    const ok = await call("PUT", `/admin/users/${user.id}/settings`, {
      TIMEZONE: "Europe/Paris",
      INTERVALS_API_KEY: secretValue,
      INTERVALS_ATHLETE_ID: "i1234",
      STRAVA_CLIENT_SECRET: "strava-secret",
    });
    expect(ok.statusCode).toBe(200);
    const read = await call("GET", `/admin/users/${user.id}/settings`);
    expect(read.json()).toEqual({
      settings: {
        INTERVALS_API_KEY: "***",
        INTERVALS_ATHLETE_ID: "i1234",
        STRAVA_CLIENT_SECRET: "***",
        TIMEZONE: "Europe/Paris",
      },
    });
    expect(read.body).not.toContain(secretValue);
    // The stored value is the real one, and a null removes it.
    expect(
      (
        await shared.query<{ value: string }>(
          "SELECT value FROM user_settings WHERE key = 'INTERVALS_API_KEY'",
        )
      ).rows[0]?.value,
    ).toBe(secretValue);
    await call("PUT", `/admin/users/${user.id}/settings`, { STRAVA_CLIENT_SECRET: null });
    expect(
      (await shared.query("SELECT 1 FROM user_settings WHERE key = 'STRAVA_CLIENT_SECRET'")).rows,
    ).toHaveLength(0);
    // The log has the user and the key names, never a value.
    expect(JSON.stringify(logging.lines)).not.toContain(secretValue);
  });

  it("sets a Telegram chat, refuses one that another user has, and unsets it", async () => {
    await start();
    const a = await create("gina");
    const b = await create("hank");
    expect((await call("PUT", `/admin/users/${a.id}/telegram`, { chatId: 555 })).statusCode).toBe(
      200,
    );
    expect((await registry?.byChat("555"))?.handle).toBe("gina");
    expect((await call("PUT", `/admin/users/${b.id}/telegram`, { chatId: "555" })).statusCode).toBe(
      409,
    );
    expect((await call("PUT", `/admin/users/${b.id}/telegram`, { chatId: "abc" })).statusCode).toBe(
      400,
    );
    expect((await call("PUT", `/admin/users/${a.id}/telegram`, { chatId: null })).statusCode).toBe(
      200,
    );
    expect(await registry?.byChat("555")).toBeNull();
    expect((await call("PUT", `/admin/users/${b.id}/telegram`, { chatId: "555" })).statusCode).toBe(
      200,
    );
  });

  it("disables a user so their token gets 401, and enables them again", async () => {
    await start();
    const user = await create("ivan");
    expect((await me(user.deviceToken)).statusCode).toBe(200);
    const off = await call("POST", `/admin/users/${user.id}/disable`);
    expect(off.json()).toMatchObject({ state: "disabled" });
    expect((await me(user.deviceToken)).statusCode).toBe(401);
    expect(
      ((await call("GET", "/admin/users")).json() as { users: { disabled: boolean }[] }).users[0]
        ?.disabled,
    ).toBe(true);
    const on = await call("POST", `/admin/users/${user.id}/enable`);
    expect(on.json()).toMatchObject({ state: "ready" });
    expect((await me(user.deviceToken)).statusCode).toBe(200);
    expect((await call("POST", `/admin/users/${user.id}/reload`)).statusCode).toBe(200);
    expect((await call("POST", "/admin/users/99/reload")).statusCode).toBe(404);
  });

  it("deletes a user only with their handle: schema, rows and token are gone; the owner needs more", async () => {
    await start();
    const owner = await create("owner");
    const user = await create("judy");
    // An OAuth authorization of the user, which the cascade does not reach.
    await shared.query(
      "INSERT INTO oauth_clients (client_id, client_name, redirect_uris, token_endpoint_auth_method, created_at) VALUES ('c1', 'c', '[]', 'none', now())",
    );
    await shared.query("INSERT INTO oauth_families (family_id, user_id) VALUES ('fam-1', $1)", [
      user.id,
    ]);
    await shared.query(
      "INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at) VALUES ('h1', 'access', 'fam-1', 'c1', 's', 'r', now(), now())",
    );
    await call("PUT", `/admin/users/${user.id}/settings`, { TIMEZONE: "UTC" });

    expect((await call("DELETE", `/admin/users/${user.id}`, { confirm: "nope" })).statusCode).toBe(
      400,
    );
    expect((await call("DELETE", `/admin/users/${user.id}`, {})).statusCode).toBe(400);
    expect(await schemaExists(`t_${user.id}`)).toBe(true);

    expect((await call("DELETE", `/admin/users/${user.id}`, { confirm: "judy" })).statusCode).toBe(
      204,
    );
    expect(await schemaExists(`t_${user.id}`)).toBe(false);
    for (const table of [
      "users",
      "user_tokens",
      "user_settings",
      "oauth_families",
      "oauth_tokens",
    ]) {
      const left = await shared.query(
        table === "users"
          ? "SELECT 1 FROM users WHERE handle = 'judy'"
          : table === "oauth_tokens"
            ? "SELECT 1 FROM oauth_tokens WHERE family_id = 'fam-1'"
            : `SELECT 1 FROM ${table} WHERE ${table === "oauth_families" ? "family_id = 'fam-1'" : `user_id = ${user.id}`}`,
      );
      expect(left.rows, table).toHaveLength(0);
    }
    expect((await me(user.deviceToken)).statusCode).toBe(401);
    expect((await call("GET", `/admin/users/${user.id}/settings`)).statusCode).toBe(404);

    // The owner is protected.
    const refused = await call("DELETE", "/admin/users/1", { confirm: "owner" });
    expect(refused.statusCode).toBe(409);
    expect(await schemaExists("t_1")).toBe(true);
    expect((await me(owner.deviceToken)).statusCode).toBe(200);
    expect(
      (await call("DELETE", "/admin/users/1", { confirm: "owner allow-owner" })).statusCode,
    ).toBe(204);
    expect(await schemaExists("t_1")).toBe(false);
  });

  it("logs the user and the action, never a token or a hash", async () => {
    await start();
    const hash = await hashPassphrase("another synthetic passphrase", { N: 1024, r: 8, p: 1 });
    const user = await create("kate", { passphraseHash: hash });
    const text = JSON.stringify(logging.lines);
    expect(text).toContain("kate");
    expect(text).not.toContain(user.deviceToken);
    expect(text).not.toContain(hash);
    expect(text).not.toContain(sha256Hex(user.deviceToken));
  });
});
