import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import type { Db, Queryable } from "./db.ts";

/** Where the numbered SQL files live, next to `src/` (and `dist/`, in the image). */
export const MIGRATIONS_DIR: URL = new URL("../migrations/", import.meta.url);

/** Where the migrations of the `shared` schema live. */
export const SHARED_MIGRATIONS_DIR: URL = new URL("../migrations/shared/", import.meta.url);

/** Held while migrations run, so two instances starting together take turns. */
export const MIGRATION_LOCK_ID = 727_001;

export interface Migration {
  version: number;
  name: string;
  sql: string;
  checksum: string;
}

const FILE = /^(\d{4})_([a-z0-9_]+)\.sql$/;

/** Reads `NNNN_name.sql` files, in order. Versions must be 1, 2, 3, … with no gap or repeat. */
export async function readMigrations(dir: URL = MIGRATIONS_DIR): Promise<Migration[]> {
  const names = (await readdir(dir)).filter((n) => n.endsWith(".sql")).sort();
  const migrations: Migration[] = [];
  for (const file of names) {
    const match = FILE.exec(file);
    if (match === null) throw new Error(`Migration file ${file} is not named NNNN_name.sql`);
    const sql = await readFile(new URL(file, dir), "utf8");
    migrations.push({
      version: Number(match[1]),
      name: match[2] ?? file,
      sql,
      checksum: createHash("sha256").update(sql).digest("hex"),
    });
  }
  for (const [index, migration] of migrations.entries()) {
    if (migration.version !== index + 1) {
      throw new Error(
        `Migrations must be numbered 1, 2, 3, …: ${names[index]} is at position ${index + 1}`,
      );
    }
  }
  return migrations;
}

/** Creates `schema_migrations` in the schema the `search_path` names, when it is not there yet. */
export async function ensureMigrationsTable(tx: Queryable): Promise<void> {
  await tx.exec(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version integer PRIMARY KEY,
        name text NOT NULL,
        checksum text NOT NULL,
        applied_at timestamptz NOT NULL DEFAULT now()
      )`);
}

/**
 * Runs one migration inside the caller's transaction (which holds the migration lock) and records it in
 * `schema_migrations` of the `search_path`'s schema. Returns false when it had run already; throws when its file
 * changed since.
 */
export async function applyMigration(tx: Queryable, migration: Migration): Promise<boolean> {
  const done = await tx.query<{ checksum: string }>(
    "SELECT checksum FROM schema_migrations WHERE version = $1",
    [migration.version],
  );
  const row = done.rows[0];
  if (row !== undefined) {
    if (row.checksum !== migration.checksum) {
      throw new Error(
        `Migration ${migration.version} (${migration.name}) changed after it was applied; add a new migration instead`,
      );
    }
    return false;
  }
  await tx.exec(migration.sql);
  await tx.query("INSERT INTO schema_migrations (version, name, checksum) VALUES ($1, $2, $3)", [
    migration.version,
    migration.name,
    migration.checksum,
  ]);
  return true;
}

/**
 * Applies the migrations that have not run yet, each in its own transaction, and records them in
 * `schema_migrations`. Safe to run on every start: what has run is skipped, and a migration whose
 * file changed after it ran is an error (a change to the schema is a new file). Returns the
 * versions applied by this call. `source` is a directory of migration files (default: the tenant migrations) or an
 * already read list. `schema_migrations` is unqualified: it lives in the schema the connection's `search_path` names.
 */
export async function migrate(
  db: Db,
  source: Migration[] | URL = MIGRATIONS_DIR,
): Promise<number[]> {
  const all = Array.isArray(source) ? source : await readMigrations(source);
  await db.transaction(async (tx) => {
    await tx.query("SELECT pg_advisory_xact_lock($1)", [MIGRATION_LOCK_ID]);
    await ensureMigrationsTable(tx);
  });
  const applied: number[] = [];
  for (const migration of all) {
    await db.transaction(async (tx) => {
      await tx.query("SELECT pg_advisory_xact_lock($1)", [MIGRATION_LOCK_ID]);
      if (await applyMigration(tx, migration)) applied.push(migration.version);
    });
  }
  return applied;
}
