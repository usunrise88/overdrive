import type { PGlite } from "@electric-sql/pglite";
import pg from "pg";

/** The rows a statement returned. Use `RETURNING` to learn what an INSERT or UPDATE changed. */
export interface QueryResult<R> {
  rows: R[];
}

export interface Queryable {
  /**
   * Runs one statement with `$1`, `$2`, … parameters. Cast what you read to text in SQL
   * (`start_epoch_millis::text`) when the driver's native type would differ (bigint is a string
   * in `pg` and may be a number or a bigint in PGlite): the same SQL must read the same in both.
   */
  query<R extends object = Record<string, unknown>>(
    sql: string,
    params?: readonly unknown[],
  ): Promise<QueryResult<R>>;

  /** Runs a script of several statements, without parameters. Used by the migrations. */
  exec(sql: string): Promise<void>;
}

/**
 * The only thing the service knows about its database. Production uses `pg` ({@link createPgDb});
 * the tests run the same SQL on PGlite (an in-process PostgreSQL) through the same interface.
 */
export interface Db extends Queryable {
  /** Runs `work` in one transaction: committed when it returns, rolled back when it throws. */
  transaction<T>(work: (tx: Queryable) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

function wrap(runner: pg.Pool | pg.PoolClient): Queryable {
  return {
    async query<R extends object>(sql: string, params: readonly unknown[] = []) {
      const result = await runner.query<R & pg.QueryResultRow>(sql, [...params]);
      return { rows: result.rows };
    },
    async exec(sql: string) {
      await runner.query(sql);
    },
  };
}

/** Connects to PostgreSQL with a pool. `onError` hears about errors of idle connections. */
export function createPgDb(connectionString: string, onError: (err: Error) => void = () => {}): Db {
  const pool = new pg.Pool({ connectionString, max: 5 });
  pool.on("error", onError);
  return {
    ...wrap(pool),
    async transaction(work) {
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const result = await work(wrap(client));
        await client.query("COMMIT");
        return result;
      } catch (err) {
        await client.query("ROLLBACK").catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
  };
}

/** The schemas the service creates: one `shared` and one `t_<id>` per tenant. Never `public`. */
const SCHEMA_NAME = /^(?:shared|t_[0-9]+)$/;

/** Returns `schema` when it is `shared` or `t_<digits>`; throws for anything else (so it is safe to quote into SQL). */
export function assertSchemaName(schema: string): string {
  if (!SCHEMA_NAME.test(schema)) {
    throw new Error(
      `Invalid schema name ${JSON.stringify(schema)}: expected "shared" or "t_<digits>"`,
    );
  }
  return schema;
}

export interface PgSchemaDbOptions {
  /** Connections in the pool; default 2. */
  max?: number;
  /** Idle connections are closed after this many milliseconds; default 30000. */
  idleTimeoutMillis?: number;
  /** Hears about errors of idle connections. */
  onError?: (err: Error) => void;
}

/**
 * Like {@link createPgDb}, but every connection starts with `search_path` set to `schema` alone (no `public`), so
 * unqualified table names mean that schema's tables.
 */
export function createPgSchemaDb(
  connectionString: string,
  schema: string,
  opts: PgSchemaDbOptions = {},
): Db {
  assertSchemaName(schema);
  const pool = new pg.Pool({
    connectionString,
    max: opts.max ?? 2,
    idleTimeoutMillis: opts.idleTimeoutMillis ?? 30_000,
    options: `-c search_path=${schema}`,
  });
  pool.on("error", opts.onError ?? (() => {}));
  return {
    ...wrap(pool),
    async transaction(work) {
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const result = await work(wrap(client));
        await client.query("COMMIT");
        return result;
      } catch (err) {
        await client.query("ROLLBACK").catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
  };
}

type PgliteRunner = Pick<PGlite, "query" | "exec">;

function adaptPglite(runner: PgliteRunner): Queryable {
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

/**
 * A `Db` for one schema on a shared PGlite (one session). Every statement and every transaction runs inside its
 * own PGlite transaction that starts with `SET LOCAL search_path`, so schemas never leak into each other; PGlite
 * runs transactions one at a time, so interleaved callers take turns. `close` does not close the PGlite.
 */
export function createPgliteSchemaDb(pglite: PGlite, schema: string): Db {
  assertSchemaName(schema);
  const setPath = `SET LOCAL search_path TO "${schema}"`;
  const inSchema = <T>(work: (tx: PgliteRunner) => Promise<T>): Promise<T> =>
    pglite.transaction(async (tx) => {
      await tx.exec(setPath);
      return work(tx);
    });
  return {
    query: (sql, params = []) => inSchema((tx) => adaptPglite(tx).query(sql, params)),
    exec: (sql) => inSchema((tx) => adaptPglite(tx).exec(sql)),
    transaction: (work) => inSchema((tx) => work(adaptPglite(tx))),
    close: async () => {},
  };
}
