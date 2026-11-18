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

describe("the real migrations", () => {
  it("create the ride tables and the plan tables", async () => {
    const applied = await migrate(db);
    expect(applied).toEqual([1, 2]);
    expect(await tables()).toEqual([
      "plan_sync_state",
      "planned_workouts",
      "ride_destinations",
      "rides",
      "schema_migrations",
    ]);
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

    expect(await migrate(db, all)).toEqual([2]);
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
    expect(rows.rows).toEqual([{ version: 1 }, { version: 2 }]);
    const rides = await db.query("SELECT id FROM rides");
    expect(rides.rows).toHaveLength(1);
  });

  it("run together once each when two starts race", async () => {
    const results = await Promise.all([migrate(db), migrate(db)]);
    expect(results.flat()).toEqual([1, 2]);
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
