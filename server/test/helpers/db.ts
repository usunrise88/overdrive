import { PGlite } from "@electric-sql/pglite";
import { createPgDb, type Db, type Queryable } from "../../src/db.ts";
import { migrate } from "../../src/migrate.ts";

function adapt(runner: PGlite | { query: PGlite["query"]; exec: PGlite["exec"] }): Queryable {
  return {
    async query<R extends object>(sql: string, params: readonly unknown[] = []) {
      const result = await runner.query<R>(sql, [...params]);
      return { rows: result.rows };
    },
    async exec(sql: string) {
      await runner.exec(sql);
    },
  };
}

/** The service's `Db` on PGlite, an in-process PostgreSQL: no server and no Docker needed. */
export async function createPgliteDb(): Promise<Db> {
  const pglite = await PGlite.create();
  return {
    ...adapt(pglite),
    transaction: (work) => pglite.transaction((tx) => work(adapt(tx))),
    close: () => pglite.close(),
  };
}

/**
 * A database for one test file, with no tables. PGlite by default; with `TEST_DATABASE_URL` set
 * (CI does) the same tests run against a real PostgreSQL, whose `public` schema is emptied first.
 */
export async function openTestDb(): Promise<Db> {
  const url = process.env.TEST_DATABASE_URL;
  if (url === undefined || url === "") return createPgliteDb();
  const db = createPgDb(url);
  await resetSchema(db);
  return db;
}

/** Drops every table, so a test starts from a database nothing has touched. */
export async function resetSchema(db: Db): Promise<void> {
  await db.exec("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
}

/** A database with the real migrations applied. */
export async function openMigratedTestDb(): Promise<Db> {
  const db = await openTestDb();
  await migrate(db);
  return db;
}

/** Empties the data, keeping the schema. */
export async function clearRides(db: Db): Promise<void> {
  await db.exec("TRUNCATE ride_destinations, rides");
}
