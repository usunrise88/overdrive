import { randomBytes } from "node:crypto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { startServer } from "../src/start.ts";
import { BootstrapRefused, type Env } from "../src/tenancy/bootstrap.ts";
import { openTenancyTestDb, resetSchema, type TenancyTestDb } from "./helpers/db.ts";
import { FAKE_BOT_TOKEN, type FakeTelegram, startFakeTelegram } from "./helpers/fake-telegram.ts";
import { databasesOf, type StartEnv, type Started, startEnv, startOn } from "./helpers/start.ts";
import { collectingLogger } from "./helpers/support.ts";

/**
 * The start-up order of `main.ts` (`start.ts`): bootstrap, then the registry, the app, the shared bot, the admin API, the
 * jobs; `MIGRATE_ONLY` stops after the bootstrap. Run on PGlite, or on a real PostgreSQL with `TEST_DATABASE_URL`.
 */

let t: TenancyTestDb;
let started: Started | null = null;

beforeAll(async () => {
  t = await openTenancyTestDb();
});

afterAll(async () => {
  await t.close();
});

beforeEach(async () => {
  await resetSchema(t.db);
});

afterEach(async () => {
  await started?.stop();
  started = null;
});

const listening = async (url: string): Promise<boolean> => {
  try {
    await fetch(`${url}/healthz`, { signal: AbortSignal.timeout(2000) });
    return true;
  } catch {
    return false;
  }
};

describe("start-up", () => {
  it("brings an empty database to the multi-tenant layout, serves /v1 with the old device token and keeps the admin on loopback", async () => {
    const config = await startEnv();
    started = await startOn(t, config);
    const { server } = started;
    expect(server.report.layout).toBe("fresh");
    expect(server.report.owner).toEqual({ id: 1, handle: "owner" });

    expect((await fetch(`${config.baseUrl}/healthz`)).status).toBe(200);
    expect((await fetch(`${config.baseUrl}/v1/me`)).status).toBe(401);
    const me = await fetch(`${config.baseUrl}/v1/me`, {
      headers: { authorization: `Bearer ${config.deviceToken}` },
    });
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({ userId: 1, handle: "owner", device: "tablet" });

    expect(server.admin?.server.address()).toMatchObject({ address: "127.0.0.1" });
    const admin = (token: string) =>
      fetch(`${config.adminUrl}/admin/users`, { headers: { authorization: `Bearer ${token}` } });
    expect((await admin("wrong")).status).toBe(401);
    expect((await admin(config.adminToken)).status).toBe(200);
    // The admin API is not on the public port.
    expect((await fetch(`${config.baseUrl}/admin/users`)).status).toBe(404);
  });

  it("MIGRATE_ONLY: migrates, returns the report and listens on nothing", async () => {
    const config = await startEnv({ MIGRATE_ONLY: "1" });
    const logging = collectingLogger();
    const result = await startServer({
      env: config.env as Env,
      log: logging.log,
      databases: databasesOf(t),
    });
    expect(result.mode).toBe("migrate-only");
    expect(result.report.layout).toBe("fresh");
    expect(result.report.migrations.shared).toEqual([1]);
    // Every value in the report is a count, a hash, a version or a key name.
    expect(JSON.stringify(result.report)).not.toContain(config.deviceToken);
    await result.close();
    expect(await listening(config.baseUrl)).toBe(false);
    expect(await listening(config.adminUrl)).toBe(false);

    // The next start finds the layout in place.
    started = await startOn(t, await startEnv());
    expect(started.server.report.layout).toBe("multi");
  });

  it("refuses to start on a database the bootstrap will not touch, and listens on nothing", async () => {
    await t.db.exec("CREATE TABLE public.rides (id integer)");
    const config = await startEnv();
    await expect(
      startServer({
        env: config.env as Env,
        log: collectingLogger().log,
        databases: databasesOf(t),
      }),
    ).rejects.toBeInstanceOf(BootstrapRefused);
    expect(await listening(config.baseUrl)).toBe(false);
    expect(await listening(config.adminUrl)).toBe(false);
  });

  it("refuses an invalid configuration before touching the database", async () => {
    const config = await startEnv({ PORT: "not-a-port" });
    await expect(
      startServer({
        env: config.env as Env,
        log: collectingLogger().log,
        databases: databasesOf(t),
      }),
    ).rejects.toThrow();
    expect(
      (await t.db.query("SELECT 1 FROM pg_namespace WHERE nspname = 'shared'")).rows,
    ).toHaveLength(0);
  });

  it("without ADMIN_TOKEN: no admin listener and a warning", async () => {
    const config = await startEnv({ ADMIN_TOKEN: undefined });
    started = await startOn(t, config);
    expect(started.server.admin).toBeNull();
    expect(await listening(config.adminUrl)).toBe(false);
    expect(
      started.logging.lines.some((l) => l.level === "warn" && /ADMIN_TOKEN/.test(l.message ?? "")),
    ).toBe(true);
  });

  it("without TELEGRAM_BOT_TOKEN: no poller, and the log says so", async () => {
    const config = await startEnv();
    started = await startOn(t, config);
    expect(started.server.poller).toBeNull();
    expect(
      started.logging.lines.some((l) => /TELEGRAM_BOT_TOKEN is not set/.test(l.message ?? "")),
    ).toBe(true);
    expect(started.server.registry.runtime(1)?.services?.telegram ?? null).toBeNull();
  });

  it("warns once, by name only, about per-user keys still in the environment", async () => {
    const secret = `key-${randomBytes(12).toString("hex")}`;
    const config = await startEnv({ INTERVALS_API_KEY: secret, TIMEZONE: "Europe/Paris" });
    started = await startOn(t, config);
    const warned = started.logging.lines.filter((l) => Array.isArray(l.fields.keys));
    expect(warned.length).toBeGreaterThanOrEqual(1);
    const named = warned.flatMap((l) => l.fields.keys as string[]);
    expect(named).toEqual(expect.arrayContaining(["INTERVALS_API_KEY", "TIMEZONE"]));
    expect(started.logging.text()).not.toContain(secret);
    expect(JSON.stringify(started.server.report)).not.toContain(secret);
  });
});

describe("start-up with the Telegram bot", () => {
  let telegram: FakeTelegram;
  let config: StartEnv;

  beforeAll(async () => {
    telegram = await startFakeTelegram();
  });
  afterAll(async () => {
    await telegram.close();
  });

  it("starts one poller and gives each tenant a bot that does not poll", async () => {
    telegram.reset();
    config = await startEnv({
      TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
      TELEGRAM_BASE_URL: telegram.url,
    });
    started = await startOn(t, config);
    expect(started.server.poller).not.toBeNull();
    expect(started.server.registry.runtime(1)?.services?.telegram).not.toBeNull();
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(telegram.webhookDeleted).toBe(1);
    // One getUpdates consumer only: the tenant's bot did not start a second loop.
    await started.stop();
    started = null;
    expect(telegram.callsTo("setMyCommands")).toHaveLength(1);
  });
});
