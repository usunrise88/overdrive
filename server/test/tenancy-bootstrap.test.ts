import { createHash, randomBytes } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { Db, Queryable } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import { migrate, readMigrations } from "../src/migrate.ts";
import { hashPassphrase } from "../src/oauth/passphrase.ts";
import {
  BootstrapRefused,
  detectLayout,
  EXPECTED_TABLES,
  GUARD_CHECKSUM,
  LEGACY_MIGRATION_COUNT,
  MOVED_TO_SHARED,
  OWNER_SCHEMA,
  parseOwnerEnv,
  runBootstrap,
} from "../src/tenancy/bootstrap.ts";
import { PER_USER_KEYS } from "../src/tenancy/keys.ts";
import { diffSnapshots, listTables, takeSnapshot } from "../src/tenancy/snapshot.ts";
import { COMPANION, TABLET } from "./helpers/app.ts";
import { openTenancyTestDb, resetSchema, type TenancyTestDb } from "./helpers/db.ts";
import {
  independentHash,
  type LegacySecrets,
  makeLegacySecrets,
  migrateLegacyLayout,
  seedLegacy,
} from "./helpers/legacy-fixture.ts";

let t: TenancyTestDb;
let db: Db;

beforeAll(async () => {
  t = await openTenancyTestDb();
  db = t.db;
});

afterAll(async () => {
  await t.close();
});

beforeEach(async () => {
  await resetSchema(db);
});

const token = () => randomBytes(30).toString("base64url");

interface TestEnv {
  env: Record<string, string>;
  /** Every secret value in it, to look for in what must not hold one. */
  secrets: string[];
  tablet: string;
  companion: string;
  mcp: string;
  strava: LegacySecrets;
}

async function makeEnv(overrides: Record<string, string> = {}): Promise<TestEnv> {
  const tablet = token();
  const companion = token();
  const mcp = token();
  const strava = makeLegacySecrets();
  const passphraseHash = await hashPassphrase("a synthetic passphrase", { N: 1024, r: 8, p: 1 });
  const secrets = [
    tablet,
    companion,
    mcp,
    passphraseHash,
    strava.stravaSeed,
    strava.stravaRotated,
    "intervals-key-0123456789",
    "llm-secret-key-9876543210",
    "google-refresh-token-abcdef",
  ];
  const env: Record<string, string> = {
    // The installation's own keys: read from the environment, never imported.
    DATABASE_URL: "postgres://user:pw@db.invalid:5432/overdrive",
    LLM_API_KEY: "llm-secret-key-9876543210",
    PORT: "8080",
    // The owner's.
    DEVICE_TOKENS: `tablet:${tablet},companion:${companion}`,
    MCP_STATIC_TOKENS: `claude:${mcp}`,
    OWNER_PASSPHRASE_HASH: passphraseHash,
    TELEGRAM_OWNER_CHAT_ID: "123456789",
    OWNER_HANDLE: "rider",
    TIMEZONE: " Europe/Belgrade ",
    INTERVALS_API_KEY: "intervals-key-0123456789",
    INTERVALS_ATHLETE_ID: "i4242",
    WEEKLY_SUMMARY_DAY: "Sunday",
    REMINDER_LEAD_MINUTES: "45",
    GOOGLE_REFRESH_TOKEN: "google-refresh-token-abcdef",
    STRAVA_CLIENT_ID: "12345",
    STRAVA_CLIENT_SECRET: "strava-client-secret",
    STRAVA_REFRESH_TOKEN: strava.stravaSeed,
    STRAVA_BACKFILL: "yes",
    INSIGHT_DENY_WORDS: " alpha , beta ",
    GCAL_WORKOUT_START: "06:30",
    ...overrides,
  };
  return { env, secrets, tablet, companion, mcp, strava };
}

async function legacyDb(setup: TestEnv, seed = true): Promise<void> {
  await migrateLegacyLayout(db);
  if (seed) await seedLegacy(db, setup.strava);
}

const publicTables = (): Promise<string[]> => listTables(db, "public");
const schemasNow = async (): Promise<string[]> =>
  (
    await db.query<{ nspname: string }>(
      "SELECT nspname FROM pg_namespace WHERE nspname = 'shared' OR nspname ~ '^t_[0-9]+$' ORDER BY 1",
    )
  ).rows.map((r) => r.nspname);

/** Where the application table lives after the cutover. */
const newHome = (table: string): string =>
  table === "schema_migrations" || !MOVED_TO_SHARED.includes(table) ? OWNER_SCHEMA : "shared";

describe("the expected tables", () => {
  it("are the tables that tenant migrations 0001 to 0013 create", async () => {
    await migrateLegacyLayout(db);
    const made = await publicTables();
    expect(made.filter((n) => n !== "schema_migrations")).toEqual([...EXPECTED_TABLES].sort());
    expect(EXPECTED_TABLES).toHaveLength(37);
    expect(made).toContain("schema_migrations");
    expect(LEGACY_MIGRATION_COUNT).toBe(13);
    expect((await readMigrations()).length).toBeGreaterThanOrEqual(LEGACY_MIGRATION_COUNT);
  });
});

describe("the owner's data in the environment", () => {
  it("is read with the configuration's own rules, and holds nothing in the report", async () => {
    const { env } = await makeEnv();
    const owner = parseOwnerEnv(env);
    expect(owner.handle).toBe("rider");
    expect(owner.chatId).toBe("123456789");
    expect(owner.devices.map((d) => d.name)).toEqual(["tablet", "companion"]);
    expect(owner.mcpTokens.map((d) => d.name)).toEqual(["claude"]);
    const settings = Object.fromEntries(owner.settings.map((s) => [s.key, s.value]));
    expect(settings).toMatchObject({
      TIMEZONE: "Europe/Belgrade",
      WEEKLY_SUMMARY_DAY: "sun",
      REMINDER_LEAD_MINUTES: "45",
      STRAVA_BACKFILL: "true",
      INSIGHT_DENY_WORDS: "alpha,beta",
      GCAL_WORKOUT_START: "06:30",
      INTERVALS_ATHLETE_ID: "i4242",
    });
    // Keys that are not set, and the installation's keys, are not imported.
    expect(Object.keys(settings)).not.toContain("WELLNESS_PULL_HOURS");
    expect(Object.keys(settings)).not.toContain("LLM_API_KEY");
    expect(Object.keys(settings)).not.toContain("DATABASE_URL");
    expect(Object.keys(settings)).not.toContain("DEVICE_TOKENS");
    expect(owner.importedKeys).toContain("DEVICE_TOKENS");
    expect(owner.importedKeys).toContain("STRAVA_REFRESH_TOKEN");
    expect(owner.importedKeys).not.toContain("LLM_API_KEY");
  });

  it("falls back to the handle owner, and treats blank values as not set", () => {
    const owner = parseOwnerEnv({
      DEVICE_TOKENS: `tablet:${token()}`,
      TIMEZONE: "  ",
      INTERVALS_API_KEY: "",
      OWNER_HANDLE: "",
    });
    expect(owner.handle).toBe("owner");
    expect(owner.settings).toEqual([]);
    expect(owner.passphraseHash).toBeNull();
    expect(owner.chatId).toBeNull();
  });

  it.each([
    ["a bad handle", { OWNER_HANDLE: "Not A Handle" }],
    ["a bad time zone", { TIMEZONE: "Mars/Olympus" }],
    ["a bad passphrase hash", { OWNER_PASSPHRASE_HASH: "nope" }],
    ["a bad chat id", { TELEGRAM_OWNER_CHAT_ID: "abc" }],
    ["a short device token", { DEVICE_TOKENS: "tablet:short" }],
    ["a bad per-user number", { WELLNESS_PULL_HOURS: "0" }],
  ])("refuses %s, naming the key and never the value", (_name, change) => {
    const base = { DEVICE_TOKENS: `tablet:${token()}`, ...change };
    let message = "";
    try {
      parseOwnerEnv(base);
    } catch (err) {
      expect(err).toBeInstanceOf(BootstrapRefused);
      message = (err as Error).message;
    }
    expect(message).not.toBe("");
    for (const value of Object.values(change)) {
      if (value.length > 3) expect(message).not.toContain(value);
    }
  });
});

describe("the legacy cutover (T1)", () => {
  it("moves every table with its content, imports the owner, leaves the guard, and is then a no-op", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);

    // The fixture is dense: at least two rows wherever the table allows it.
    const tables = await publicTables();
    expect(tables).toHaveLength(EXPECTED_TABLES.length + 1);
    const single = new Set(["profile", "strava_auth", "plan_event_cursor"]);
    for (const table of tables) {
      const n = Number(
        (await db.query<{ n: string }>(`SELECT count(*)::text AS n FROM public.${table}`)).rows[0]
          ?.n,
      );
      expect(n, table).toBeGreaterThanOrEqual(single.has(table) ? 1 : 2);
    }

    // What the tables hold, worked out here and not by the bootstrap.
    const before: Record<string, string> = {};
    for (const table of tables) before[table] = await independentHash(db, `public.${table}`);
    const sequences = (
      await db.query<{ relname: string }>(
        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'S' ORDER BY 1",
      )
    ).rows.map((r) => r.relname);
    expect(sequences).toHaveLength(7);
    const lastValues: Record<string, string> = {};
    for (const seq of sequences) {
      lastValues[seq] = (
        await db.query<{ v: string }>(`SELECT last_value::text AS v FROM public.${seq}`)
      ).rows[0]?.v as string;
    }

    const report = await runBootstrap({ db, env: setup.env });

    expect(report.layout).toBe("legacy");
    expect(report.owner).toEqual({ id: 1, handle: "rider" });
    expect(report.migrations).toEqual({ shared: [1], t_1: [14] });
    expect(report.before).not.toBeNull();
    expect(report.after).toEqual(report.before);
    expect(Object.keys(report.after?.tables ?? {})).toHaveLength(EXPECTED_TABLES.length + 1);
    expect(report.perUserKeysStillInEnv).toEqual(
      PER_USER_KEYS.filter((k) => (setup.env[k] ?? "") !== ""),
    );
    expect(report.perUserKeysStillInEnv).toContain("STRAVA_CLIENT_SECRET");
    // No value of the environment is in the report.
    const text = JSON.stringify(report);
    for (const secret of setup.secrets) expect(text).not.toContain(secret);

    // The same content, in the new places (`schema_migrations` gains the row of 0014, checked below).
    for (const table of tables.filter((t) => t !== "schema_migrations")) {
      const after = await independentHash(db, `${newHome(table)}.${table}`);
      expect(after, table).toBe(before[table]);
    }
    expect(await schemasNow()).toEqual(["shared", "t_1"]);
    for (const seq of sequences) {
      const next = await db.query<{ v: string }>(`SELECT nextval('t_1.${seq}')::text AS v`);
      expect(next.rows[0]?.v, seq).toBe(String(BigInt(lastValues[seq] as string) + 1n));
    }

    // The owner.
    const users = await db.query<{
      id: string;
      handle: string;
      passphrase_hash: string | null;
      telegram_chat_id: string | null;
    }>("SELECT id::text AS id, handle, passphrase_hash, telegram_chat_id FROM shared.users");
    expect(users.rows).toEqual([
      {
        id: "1",
        handle: "rider",
        passphrase_hash: setup.env.OWNER_PASSPHRASE_HASH,
        telegram_chat_id: "123456789",
      },
    ]);
    const tokens = await db.query<{
      token_sha256: string;
      kind: string;
      name: string;
      user_id: string;
      revoked_at: unknown;
    }>(
      "SELECT token_sha256, kind, name, user_id::text AS user_id, revoked_at FROM shared.user_tokens ORDER BY kind, name",
    );
    // The digest is the one auth.ts computes (SHA-256 of the token), written as lowercase hex like the OAuth tokens'.
    const digest = (secret: string) => createHash("sha256").update(secret).digest();
    expect(tokens.rows).toEqual([
      {
        token_sha256: digest(setup.companion).toString("hex"),
        kind: "device",
        name: "companion",
        user_id: "1",
        revoked_at: null,
      },
      {
        token_sha256: digest(setup.tablet).toString("hex"),
        kind: "device",
        name: "tablet",
        user_id: "1",
        revoked_at: null,
      },
      {
        token_sha256: digest(setup.mcp).toString("hex"),
        kind: "mcp",
        name: "claude",
        user_id: "1",
        revoked_at: null,
      },
    ]);
    for (const row of tokens.rows) {
      for (const plain of [setup.tablet, setup.companion, setup.mcp])
        expect(row.token_sha256).not.toBe(plain);
    }
    const settings = Object.fromEntries(
      (
        await db.query<{ key: string; value: string }>(
          "SELECT key, value FROM shared.user_settings",
        )
      ).rows.map((r) => [r.key, r.value]),
    );
    expect(settings.TIMEZONE).toBe("Europe/Belgrade");
    expect(settings.WEEKLY_SUMMARY_DAY).toBe("sun");
    expect(settings.STRAVA_REFRESH_TOKEN).toBe(setup.strava.stravaSeed);
    expect(Object.keys(settings).sort()).toEqual(
      report.importedKeys.filter((k) => PER_USER_KEYS.includes(k)).sort(),
    );
    const families = await db.query<{ family_id: string; user_id: string }>(
      "SELECT family_id, user_id::text AS user_id FROM shared.oauth_families ORDER BY 1",
    );
    expect(families.rows).toEqual([
      { family_id: "family-a", user_id: "1" },
      { family_id: "family-b", user_id: "1" },
      { family_id: "family-c", user_id: "1" },
    ]);

    // The Strava seed: what strava/auth.ts computes from the stored STRAVA_REFRESH_TOKEN is the moved row's seed hash,
    // so the rotated token is used and not overwritten by the environment's.
    const strava = await db.query<{ refresh_token: string; seed_sha256: string }>(
      "SELECT refresh_token, seed_sha256 FROM t_1.strava_auth",
    );
    expect(sha256Hex(settings.STRAVA_REFRESH_TOKEN as string)).toBe(strava.rows[0]?.seed_sha256);
    expect(strava.rows[0]?.refresh_token).toBe(setup.strava.stravaRotated);

    // t_1 has the tables and the 14 migration rows; shared has its own bookkeeping.
    const rows = await db.query<{ version: number }>(
      "SELECT version FROM t_1.schema_migrations ORDER BY 1",
    );
    expect(rows.rows.map((r) => r.version)).toEqual(Array.from({ length: 14 }, (_, i) => i + 1));
    expect(
      (await db.query<{ version: number }>("SELECT version FROM shared.schema_migrations")).rows,
    ).toEqual([{ version: 1 }]);
    expect((await listTables(db, "t_1")).length).toBe(
      EXPECTED_TABLES.length - MOVED_TO_SHARED.length + 1,
    );

    // Cascades still work where the tables are now.
    await db.exec("DELETE FROM t_1.rides WHERE id = 'ride-synth-1'");
    expect(
      (await db.query("SELECT 1 FROM t_1.ride_destinations WHERE ride_id = 'ride-synth-1'")).rows,
    ).toHaveLength(0);
    expect((await db.query("SELECT 1 FROM t_1.ride_destinations")).rows.length).toBeGreaterThan(0);
    await db.exec("DELETE FROM shared.oauth_clients WHERE client_id = 'client-public'");
    expect(
      (await db.query("SELECT 1 FROM shared.oauth_tokens WHERE client_id = 'client-public'")).rows,
    ).toHaveLength(0);
    expect((await db.query("SELECT 1 FROM shared.oauth_tokens")).rows.length).toBeGreaterThan(0);
    // A second user gets id 2.
    const next = await db.query<{ id: string }>(
      "INSERT INTO shared.users (handle) VALUES ('second') RETURNING id::text AS id",
    );
    expect(next.rows[0]?.id).toBe("2");
    await db.exec("DELETE FROM shared.users WHERE handle = 'second'");

    // The guard: public holds only the guard row, and the old migrate() refuses it.
    expect(await publicTables()).toEqual(["schema_migrations"]);
    const guard = await db.query<{ version: number; name: string; checksum: string }>(
      "SELECT version, name, checksum FROM public.schema_migrations",
    );
    expect(guard.rows).toEqual([{ version: 1, name: "rides", checksum: GUARD_CHECKSUM }]);
    await expect(migrate(db)).rejects.toThrow(/Migration 1 \(rides\) changed after it was applied/);

    // Again: nothing to do.
    const again = await runBootstrap({ db, env: setup.env });
    expect(again.layout).toBe("multi");
    expect(again.migrations).toEqual({});
    expect(again.owner).toBeNull();
    expect(again.before).toBeNull();
    expect(await db.query("SELECT 1 FROM shared.users")).toMatchObject({ rows: [{}] });
  }, 60_000);

  it("works through a Db the caller made for each schema, and with the default view, on a database two instances start on at once", async () => {
    const setup = await makeEnv();
    await legacyDb(setup, false);
    const [a, b] = await Promise.all([
      runBootstrap({ db, env: setup.env, schemaDb: t.schemaDb }),
      runBootstrap({ db, env: setup.env }),
    ]);
    expect([a.layout, b.layout].sort()).toEqual(["legacy", "multi"]);
    expect(await schemasNow()).toEqual(["shared", "t_1"]);
    expect((await db.query("SELECT 1 FROM shared.users")).rows).toHaveLength(1);
  }, 60_000);
});

describe("refusals (nothing changes)", () => {
  /** Runs the bootstrap on a database in this state and checks that it refused and changed nothing. */
  async function expectRefused(setup: TestEnv, pattern: RegExp): Promise<void> {
    const beforeTables = await publicTables();
    const beforeSnapshot = await takeSnapshot(db, "public");
    const beforeSchemas = await schemasNow();
    const error = await runBootstrap({ db, env: setup.env }).then(
      () => null,
      (err: unknown) => err,
    );
    expect(error).toBeInstanceOf(BootstrapRefused);
    expect((error as Error).message).toMatch(pattern);
    for (const secret of setup.secrets) expect((error as Error).message).not.toContain(secret);
    expect(await publicTables()).toEqual(beforeTables);
    expect(diffSnapshots(beforeSnapshot, await takeSnapshot(db, "public"))).toEqual([]);
    expect(await schemasNow()).toEqual(beforeSchemas);
    expect(beforeSchemas).toEqual([]);
  }

  it("an unexpected table in public", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);
    await db.exec(
      "CREATE TABLE public.notes (id integer PRIMARY KEY); INSERT INTO public.notes VALUES (1)",
    );
    await expectRefused(setup, /notes/);
  });

  it("the same token in DEVICE_TOKENS and MCP_STATIC_TOKENS", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);
    setup.env.MCP_STATIC_TOKENS = `claude:${token()},again:${setup.tablet}`;
    await expectRefused(
      setup,
      /the same token is in DEVICE_TOKENS \(tablet\) and in MCP_STATIC_TOKENS \(again\)/,
    );
  });

  it("a tampered checksum in public.schema_migrations", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);
    await db.exec("UPDATE public.schema_migrations SET checksum = 'tampered' WHERE version = 7");
    await expectRefused(setup, /migration file 7/);
  });

  it("a missing migration row", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);
    await db.exec("DELETE FROM public.schema_migrations WHERE version = 13");
    await expectRefused(setup, /holds 12 versions/);
  });

  it.each([
    ["a view", "CREATE VIEW public.v_rides AS SELECT id FROM public.rides", /view v_rides/],
    [
      "a materialised view",
      "CREATE MATERIALIZED VIEW public.m_rides AS SELECT id FROM public.rides",
      /materialised view m_rides/,
    ],
    [
      "a function",
      "CREATE FUNCTION public.f() RETURNS integer LANGUAGE sql AS 'SELECT 1'",
      /function or procedure f/,
    ],
    ["an enum type", "CREATE TYPE public.mood AS ENUM ('a', 'b')", /type mood/],
    ["a composite type", "CREATE TYPE public.pair AS (a integer, b integer)", /pair/],
    ["a sequence no column owns", "CREATE SEQUENCE public.loose_seq", /sequence loose_seq/],
    [
      "a trigger",
      "CREATE FUNCTION public.noop() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'; CREATE TRIGGER tg BEFORE INSERT ON public.profile FOR EACH ROW EXECUTE FUNCTION public.noop()",
      /function or procedure noop/,
    ],
  ])("%s in public", async (_name, sql, pattern) => {
    const setup = await makeEnv();
    await migrateLegacyLayout(db);
    await db.exec(sql);
    await expectRefused(setup, pattern);
  });

  it("an invalid environment value", async () => {
    const setup = await makeEnv({ TIMEZONE: "Mars/Olympus" });
    await legacyDb(setup, false);
    await expectRefused(setup, /TIMEZONE/);
  });

  it("an empty DEVICE_TOKENS, which would lock the devices out", async () => {
    const setup = await makeEnv({ DEVICE_TOKENS: "" });
    await legacyDb(setup, false);
    await expectRefused(setup, /DEVICE_TOKENS is empty/);
  });

  it("shared and public.rides both present", async () => {
    const setup = await makeEnv();
    await migrateLegacyLayout(db);
    await db.exec("CREATE SCHEMA shared");
    const before = await takeSnapshot(db, "public");
    await expect(runBootstrap({ db, env: setup.env })).rejects.toThrow(
      /both the schema shared and the table public.rides/,
    );
    expect(diffSnapshots(before, await takeSnapshot(db, "public"))).toEqual([]);
    expect(await listTables(db, "shared")).toEqual([]);
  });

  it("a half-made database: tenant schemas without shared, or objects in an otherwise empty public", async () => {
    const setup = await makeEnv();
    await db.exec("CREATE SCHEMA t_1");
    await expect(runBootstrap({ db, env: setup.env })).rejects.toThrow(/no schema shared/);
    await db.exec("DROP SCHEMA t_1; CREATE TABLE public.stray (id integer)");
    await expect(runBootstrap({ db, env: setup.env })).rejects.toThrow(/not empty/);
    expect(await schemasNow()).toEqual([]);
  });

  it("the old image's guard is not mistaken for something else, and a foreign object next to it is refused", async () => {
    const setup = await makeEnv();
    await legacyDb(setup, false);
    await runBootstrap({ db, env: setup.env });
    await db.exec("CREATE TABLE public.stray (id integer)");
    await expect(runBootstrap({ db, env: setup.env })).rejects.toThrow(/stray/);
    await db.exec("DROP TABLE public.stray; UPDATE public.schema_migrations SET checksum = 'x'");
    await expect(runBootstrap({ db, env: setup.env })).rejects.toThrow(/not the guard table/);
  });

  it("a failure inside the cutover rolls everything back", async () => {
    const setup = await makeEnv();
    await legacyDb(setup);
    const before = await takeSnapshot(db, "public");
    // The move has been made and the data then differs (a row lost): the comparison throws and all of it rolls back.
    await expect(
      runBootstrap({
        db,
        env: setup.env,
        beforeVerify: async (tx) => {
          await tx.exec("DELETE FROM t_1.ride_destinations WHERE ride_id = 'ride-synth-2'");
        },
      }),
    ).rejects.toThrow(/The move changed the data(.|\n)*ride_destinations/);
    expect(diffSnapshots(before, await takeSnapshot(db, "public"))).toEqual([]);
    expect(await schemasNow()).toEqual([]);
    expect(await publicTables()).toEqual([...EXPECTED_TABLES, "schema_migrations"].sort());
    // Any other error after the move rolls back as well.
    await expect(
      runBootstrap({
        db,
        env: setup.env,
        beforeVerify: async () => {
          throw new Error("boom");
        },
      }),
    ).rejects.toThrow("boom");
    expect(diffSnapshots(before, await takeSnapshot(db, "public"))).toEqual([]);
    expect(await schemasNow()).toEqual([]);
    // And the bootstrap works afterwards.
    expect((await runBootstrap({ db, env: setup.env })).layout).toBe("legacy");
  });
});

describe("a fresh database", () => {
  it("gets shared, the owner and t_1, and nothing in public; a second run does nothing", async () => {
    const setup = await makeEnv();
    const report = await runBootstrap({ db, env: setup.env });
    expect(report.layout).toBe("fresh");
    expect(report.owner).toEqual({ id: 1, handle: "rider" });
    expect(report.before).toBeNull();
    expect(report.migrations.shared).toEqual([1]);
    expect(report.migrations.t_1).toEqual(Array.from({ length: 14 }, (_, i) => i + 1));
    expect(await schemasNow()).toEqual(["shared", "t_1"]);
    expect(await publicTables()).toEqual([]);
    expect(await listTables(db, "t_1")).toEqual(
      [...EXPECTED_TABLES.filter((t) => !MOVED_TO_SHARED.includes(t)), "schema_migrations"].sort(),
    );
    const users = await db.query<{ id: string; handle: string }>(
      "SELECT id::text AS id, handle FROM shared.users",
    );
    expect(users.rows).toEqual([{ id: "1", handle: "rider" }]);
    expect((await db.query("SELECT 1 FROM shared.user_tokens")).rows).toHaveLength(3);
    expect(
      (await db.query("SELECT 1 FROM shared.user_settings WHERE key = 'TIMEZONE'")).rows,
    ).toHaveLength(1);
    expect((await db.query("SELECT 1 FROM shared.oauth_families")).rows).toHaveLength(0);
    for (const secret of setup.secrets) expect(JSON.stringify(report)).not.toContain(secret);

    const again = await runBootstrap({ db, env: setup.env });
    expect(again).toMatchObject({ layout: "multi", migrations: {}, owner: null });
    expect((await db.query("SELECT 1 FROM shared.users")).rows).toHaveLength(1);
  }, 60_000);

  it("takes the default owner handle and no tokens when the environment has none", async () => {
    const report = await runBootstrap({ db, env: {} });
    expect(report.owner).toEqual({ id: 1, handle: "owner" });
    expect(report.importedKeys).toEqual([]);
    expect((await db.query("SELECT 1 FROM shared.user_tokens")).rows).toHaveLength(0);
  });

  it("refuses an invalid environment and creates nothing", async () => {
    await expect(runBootstrap({ db, env: { OWNER_HANDLE: "UPPER" } })).rejects.toThrow(
      /OWNER_HANDLE/,
    );
    expect(await schemasNow()).toEqual([]);
  });
});

describe("a database already in the new layout", () => {
  it("runs the pending migrations of shared and of each user, and nothing else", async () => {
    const setup = await makeEnv();
    await runBootstrap({ db, env: setup.env });
    await db.exec("INSERT INTO shared.users (handle) VALUES ('second'); CREATE SCHEMA t_2");
    // Pending: tenant migration 13 of the owner (its table is gone too), the whole tenant set of user 2, and shared 1.
    await db.exec(
      "DROP TABLE t_1.ride_progress_stats; DELETE FROM t_1.schema_migrations WHERE version = 13",
    );
    await db.exec("DELETE FROM shared.schema_migrations WHERE version = 1");
    const usersBefore = await db.query("SELECT * FROM shared.users ORDER BY id");
    const report = await runBootstrap({ db, env: {} });
    expect(report).toMatchObject({ layout: "multi", owner: null, importedKeys: [] });
    expect(report.migrations.shared).toEqual([1]);
    expect(report.migrations.t_1).toEqual([13]);
    expect(report.migrations.t_2).toEqual(Array.from({ length: 14 }, (_, i) => i + 1));
    expect((await db.query("SELECT * FROM shared.users ORDER BY id")).rows).toEqual(
      usersBefore.rows,
    );
    expect(await listTables(db, "t_2")).toContain("rides");
    expect(await publicTables()).toEqual([]);
  }, 60_000);

  it("fails clearly for a user without a schema", async () => {
    await runBootstrap({ db, env: {} });
    await db.exec("INSERT INTO shared.users (handle) VALUES ('ghost')");
    await expect(runBootstrap({ db, env: {} })).rejects.toThrow(/User 2 has no schema t_2/);
  });

  it("is detected as multi only while shared has its users table", async () => {
    await db.exec("CREATE SCHEMA shared");
    await expect(db.transaction((tx: Queryable) => detectLayout(tx))).rejects.toThrow(
      /no users table/,
    );
  });
});

describe("the shared schema is the same after a fresh install and after the cutover (T3)", () => {
  async function describeShared(): Promise<unknown> {
    const strip = (text: string) => text.split("shared.").join("");
    const columns = await db.query(
      `SELECT table_name, column_name, ordinal_position, data_type, is_nullable, column_default, is_identity
         FROM information_schema.columns WHERE table_schema = 'shared' ORDER BY table_name, ordinal_position`,
    );
    const constraints = await db.query<{ tbl: string; conname: string; def: string }>(
      `SELECT r.relname AS tbl, c.conname, pg_get_constraintdef(c.oid) AS def
         FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace
        WHERE n.nspname = 'shared' ORDER BY r.relname, c.conname`,
    );
    const indexes = await db.query<{ indexname: string; indexdef: string }>(
      "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'shared' ORDER BY indexname",
    );
    return {
      columns: columns.rows.map((r) => ({
        ...r,
        column_default:
          typeof r.column_default === "string" ? strip(r.column_default) : r.column_default,
      })),
      constraints: constraints.rows.map((r) => ({ ...r, def: strip(r.def) })),
      indexes: indexes.rows.map((r) => ({ ...r, indexdef: strip(r.indexdef) })),
    };
  }

  it("describes alike", async () => {
    const setup = await makeEnv();
    await runBootstrap({ db, env: setup.env });
    const fresh = await describeShared();
    await resetSchema(db);
    await legacyDb(setup);
    await runBootstrap({ db, env: setup.env });
    const moved = await describeShared();
    expect((fresh as { columns: unknown[] }).columns.length).toBeGreaterThan(40);
    expect((fresh as { indexes: unknown[] }).indexes.length).toBeGreaterThan(8);
    expect(moved).toEqual(fresh);
  }, 60_000);
});

describe("the endpoints answer the same before and after (T1b)", () => {
  // Compared: the GET endpoints of the single-user API that work on the synthetic data. Left out: GET /v1/plan-events
  // (MCP only, a 404 here), /v1/repeats/:hash and /v1/activities/:id (the synthetic rows are not a repeat and the
  // activity ids are namespaced), /v1/live/sessions/:id/cues (live sessions are in memory).
  const PATHS = [
    "/v1/rides",
    "/v1/rides/ride-synth-1",
    "/v1/rides/ride-synth-2/metrics",
    "/v1/metrics?from=2026-11-01&to=2026-11-10",
    "/v1/profile",
    "/v1/workouts?from=2026-11-01&to=2026-11-30",
    "/v1/insights?from=2026-11-01&to=2026-11-30",
    "/v1/notebook",
    "/v1/routes",
    "/v1/routes/route-1",
    "/v1/repeats",
    "/v1/power-skills?from=2026-11-01&to=2026-11-10",
    "/v1/progress?from=2026-11-01&to=2026-11-10",
    "/v1/effort",
    "/v1/wellness?from=2026-11-01&to=2026-11-10",
  ];

  /** Drops the fields that say when the answer was made. */
  const strip = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(strip);
    if (value !== null && typeof value === "object") {
      return Object.fromEntries(
        Object.entries(value)
          .filter(([key]) => !/^(generatedAt|computedAt)$/.test(key))
          .map(([key, child]) => [key, strip(child)]),
      );
    }
    return value;
  };

  async function answers(on: Db): Promise<Record<string, unknown>> {
    const app = buildApp({
      config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET, COMPANION] },
      db: on,
    });
    try {
      const out: Record<string, unknown> = {};
      for (const path of PATHS) {
        const res = await app.inject({
          method: "GET",
          url: path,
          headers: { authorization: `Bearer ${TABLET.token}` },
        });
        expect(res.statusCode, path).toBe(200);
        out[path] = strip(JSON.parse(res.body));
      }
      return out;
    } finally {
      await app.close();
    }
  }

  it("serves the same JSON from public before and from t_1 after, with the same token", async () => {
    const setup = await makeEnv({
      DEVICE_TOKENS: `tablet:${TABLET.token},companion:${COMPANION.token}`,
    });
    await legacyDb(setup);
    // A first pass lets the lazy computations (ride metrics, stats) write their rows; the second is what is compared.
    await answers(db);
    const old = await answers(db);

    const report = await runBootstrap({ db, env: setup.env, schemaDb: t.schemaDb });
    expect(report.layout).toBe("legacy");
    const moved = await answers(t.schemaDb(OWNER_SCHEMA));

    expect(Object.keys(old)).toEqual(PATHS);
    expect(moved).toEqual(old);
    // The answers are not empty: the data was there to be read.
    expect((old["/v1/rides"] as { items: unknown[] }).items).toHaveLength(5);
    expect((old["/v1/profile"] as { ftp: number }).ftp).toBe(255);
    expect((old["/v1/routes"] as { routes: unknown[] }).routes).toHaveLength(2);
  }, 90_000);
});
