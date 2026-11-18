import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { type Migration, migrate, readMigrations } from "../src/migrate.ts";
import { openTestDb, resetSchema } from "./helpers/db.ts";

let db: Db;

beforeAll(async () => {
  db = await openTestDb();
});

afterAll(async () => {
  await db.close();
});

beforeEach(async () => {
  await resetSchema(db);
});

async function tables(): Promise<string[]> {
  const result = await db.query<{ table_name: string }>(
    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1",
  );
  return result.rows.map((r) => r.table_name);
}

const migration = (version: number, sql: string): Migration => ({
  version,
  name: `m${version}`,
  sql,
  checksum: `checksum-${sql}`,
});

/** The versions of the repository's own migrations: the tests do not hard-code how many there are. */
const versions = async (): Promise<number[]> => (await readMigrations()).map((m) => m.version);

describe("the real migrations", () => {
  it("create the ride, plan, profile, ride workout and OAuth tables", async () => {
    const applied = await migrate(db);
    expect(applied).toEqual(await versions());
    expect(await tables()).toEqual(
      expect.arrayContaining([
        "plan_sync_state",
        "planned_workouts",
        "ride_destinations",
        "rides",
        "schema_migrations",
      ]),
    );
  });

  it("queue the rides that already have a FIT file for intervals.icu (0002), and only those", async () => {
    const all = await readMigrations();
    await migrate(db, all.slice(0, 1));
    const insert = (id: string, start: number, withFit: boolean) =>
      db.query(
        `INSERT INTO rides (id, start_epoch_millis, record, record_sha256, uploaded_by, fit, fit_sha256)
         VALUES ($1, $2, '{}'::jsonb, 'x', 'tablet', $3, $4)`,
        [id, start, withFit ? Buffer.from([1, 2, 3]) : null, withFit ? "f" : null],
      );
    await insert("with-fit", 1, true);
    await insert("record-only", 2, false);
    await db.query(
      "INSERT INTO ride_destinations (ride_id, destination, state) VALUES ('with-fit', 'strava', 'done')",
    );

    expect(await migrate(db, all)).toEqual((await versions()).slice(1));
    const rows = await db.query<{ ride_id: string; destination: string; state: string }>(
      "SELECT ride_id, destination, state FROM ride_destinations ORDER BY destination",
    );
    expect(rows.rows).toEqual([
      { ride_id: "with-fit", destination: "intervals.icu", state: "pending" },
      { ride_id: "with-fit", destination: "strava", state: "done" },
    ]);
  });

  it("give the plan cache its constraints (0002)", async () => {
    await migrate(db);
    const insert = (workout: string | null, reason: string | null, id = "icu-1", external = "1") =>
      db.query(
        `INSERT INTO planned_workouts
           (id, source, external_id, plan_date, name, workout, unsupported_reason, content_sha256)
         VALUES ($1, 'intervals.icu', $2, '2026-11-20', 'x', $3::jsonb, $4, 'h')`,
        [id, external, workout, reason],
      );
    await expect(insert(null, null)).rejects.toThrow(); // converted or explained
    await expect(insert("{}", "both")).rejects.toThrow();
    await insert("{}", null);
    await insert(null, "why not", "icu-2", "2");
    await expect(insert("{}", null, "icu-3", "1")).rejects.toThrow(); // one row per source event
    const dates = await db.query<{ plan_date: string }>(
      "SELECT plan_date::text AS plan_date FROM planned_workouts ORDER BY id",
    );
    expect(dates.rows.map((r) => r.plan_date)).toEqual(["2026-11-20", "2026-11-20"]);
  });

  it("are idempotent: a second run changes nothing", async () => {
    await migrate(db);
    await db.query(
      `INSERT INTO rides (id, start_epoch_millis, record, record_sha256, uploaded_by)
       VALUES ('ride-1', 1, '{}'::jsonb, 'x', 'tablet')`,
    );
    expect(await migrate(db)).toEqual([]);
    expect(await migrate(db)).toEqual([]);
    const rows = await db.query<{ version: number }>("SELECT version FROM schema_migrations");
    expect(rows.rows).toEqual((await versions()).map((version) => ({ version })));
    const rides = await db.query("SELECT id FROM rides");
    expect(rides.rows).toHaveLength(1);
  });

  it("run together once each when two starts race", async () => {
    const results = await Promise.all([migrate(db), migrate(db)]);
    expect(results.flat()).toEqual(await versions());
  });

  it("give the profile one row and the OAuth tables their constraints (0003)", async () => {
    await migrate(db);
    const profile = (id: boolean, data: string) =>
      db.query("INSERT INTO profile (id, data, updated_at) VALUES ($1, $2::jsonb, now())", [
        id,
        data,
      ]);
    await expect(profile(false, "{}")).rejects.toThrow(); // the row is the only row
    await expect(profile(true, "[]")).rejects.toThrow(); // an object
    await profile(true, "{}");
    await expect(profile(true, "{}")).rejects.toThrow(); // and there is one

    const client = (id: string, method: string, secret: string | null) =>
      db.query(
        `INSERT INTO oauth_clients (client_id, client_secret_sha256, client_name, redirect_uris,
                                    token_endpoint_auth_method, created_at)
         VALUES ($1, $3, 'c', '[]'::jsonb, $2, now())`,
        [id, method, secret],
      );
    await client("public", "none", null);
    await client("confidential", "client_secret_post", "digest");
    await expect(client("bad1", "none", "digest")).rejects.toThrow(); // a public client has no secret
    await expect(client("bad2", "client_secret_basic", null)).rejects.toThrow(); // a confidential one has
    await expect(client("bad3", "private_key_jwt", "digest")).rejects.toThrow(); // only the methods we do

    // Tokens and codes belong to a client, and go with it.
    await db.query(
      `INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at)
       VALUES ('t', 'access', 'f', 'public', 'mcp', 'r', now(), now())`,
    );
    await expect(
      db.query(
        `INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at)
         VALUES ('u', 'id', 'f', 'public', 'mcp', 'r', now(), now())`,
      ),
    ).rejects.toThrow(); // access or refresh
    await expect(
      db.query(
        `INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at)
         VALUES ('v', 'access', 'f', 'nobody', 'mcp', 'r', now(), now())`,
      ),
    ).rejects.toThrow();
    await db.query("DELETE FROM oauth_clients WHERE client_id = 'public'");
    expect((await db.query("SELECT 1 FROM oauth_tokens")).rows).toHaveLength(0);
  });

  it("make a table the data constraints hold", async () => {
    await migrate(db);
    const insert = (id: string, start: number) =>
      db.query(
        `INSERT INTO rides (id, start_epoch_millis, record, record_sha256, uploaded_by)
         VALUES ($1, $2, '{}'::jsonb, 'x', 'tablet')`,
        [id, start],
      );
    await insert("a", 1);
    await expect(insert("a", 2)).rejects.toThrow(); // one row per id
    await expect(insert("b", 1)).rejects.toThrow(); // and per start time
    await expect(
      db.query("UPDATE rides SET fit = '\\x00'::bytea WHERE id = 'a'"),
    ).rejects.toThrow(); // a file needs its hash
    await expect(
      db.query(
        "INSERT INTO ride_destinations (ride_id, destination) VALUES ('nobody', 'intervals')",
      ),
    ).rejects.toThrow(); // a destination needs its ride
    await db.query(
      "INSERT INTO ride_destinations (ride_id, destination) VALUES ('a', 'intervals')",
    );
    const row = await db.query<{ state: string; attempts: number; next: string | null }>(
      "SELECT state, attempts, next_attempt_at AS next FROM ride_destinations",
    );
    // A new row is pending and due at once: no next attempt is set.
    expect(row.rows).toEqual([{ state: "pending", attempts: 0, next: null }]);
  });
});

describe("the runner", () => {
  it("applies new migrations in order and skips the ones that ran", async () => {
    await migrate(db, [migration(1, "CREATE TABLE a (n integer)")]);
    const applied = await migrate(db, [
      migration(1, "CREATE TABLE a (n integer)"),
      migration(2, "CREATE TABLE b (n integer); CREATE TABLE c (n integer)"),
    ]);
    expect(applied).toEqual([2]);
    expect(await tables()).toEqual(["a", "b", "c", "schema_migrations"]);
  });

  it("rolls a failing migration back entirely and records nothing for it", async () => {
    await expect(
      migrate(db, [migration(1, "CREATE TABLE half (n integer); SELECT nothing_like_this()")]),
    ).rejects.toThrow();
    expect(await tables()).toEqual(["schema_migrations"]);
    const rows = await db.query("SELECT version FROM schema_migrations");
    expect(rows.rows).toEqual([]);
  });

  it("refuses a migration that changed after it ran", async () => {
    await migrate(db, [migration(1, "CREATE TABLE a (n integer)")]);
    await expect(migrate(db, [migration(1, "CREATE TABLE a (n bigint)")])).rejects.toThrow(
      /changed after it was applied/,
    );
  });
});

describe("reading the files", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "migrations-"));
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  const url = () => pathToFileURL(`${dir}/`);

  it("reads the repository's own migrations, numbered from 1", async () => {
    const all = await readMigrations();
    expect(all.length).toBeGreaterThan(0);
    expect(all.map((m) => m.version)).toEqual(all.map((_, i) => i + 1));
    expect(all[0]?.name).toBe("rides");
  });

  it("sorts by number and ignores other files", async () => {
    await writeFile(join(dir, "0002_second.sql"), "SELECT 2");
    await writeFile(join(dir, "0001_first.sql"), "SELECT 1");
    await writeFile(join(dir, "notes.txt"), "not a migration");
    await mkdir(join(dir, "0003_dir.d"));
    const all = await readMigrations(url());
    expect(all.map((m) => [m.version, m.name])).toEqual([
      [1, "first"],
      [2, "second"],
    ]);
  });

  it("rejects a gap, a repeat and a badly named file", async () => {
    await writeFile(join(dir, "0001_first.sql"), "SELECT 1");
    await writeFile(join(dir, "0003_third.sql"), "SELECT 3");
    await expect(readMigrations(url())).rejects.toThrow(/numbered 1, 2, 3/);
    await rm(join(dir, "0003_third.sql"));
    await writeFile(join(dir, "0001_again.sql"), "SELECT 1");
    await expect(readMigrations(url())).rejects.toThrow(/numbered 1, 2, 3/);
    await rm(join(dir, "0001_again.sql"));
    await writeFile(join(dir, "first.sql"), "SELECT 1");
    await expect(readMigrations(url())).rejects.toThrow(/not named NNNN_name.sql/);
  });
});
