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
