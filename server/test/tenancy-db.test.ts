import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  assertSchemaName,
  createPgDb,
  createPgliteSchemaDb,
  createPgSchemaDb,
  type Db,
} from "../src/db.ts";
import { migrate, SHARED_MIGRATIONS_DIR } from "../src/migrate.ts";
import { resetSchema } from "./helpers/db.ts";

let pglite: PGlite;
let admin: Db;

beforeAll(async () => {
  pglite = await PGlite.create();
  admin = createPgliteSchemaDb(pglite, "shared");
  await pglite.exec(
    "CREATE SCHEMA shared; CREATE SCHEMA t_1; CREATE SCHEMA t_2; CREATE SCHEMA t_3",
  );
});

afterAll(async () => {
  await pglite.close();
});

const names = async (db: Db, schema: string): Promise<string[]> =>
  (
    await db.query<{ table_name: string }>(
      "SELECT table_name FROM information_schema.tables WHERE table_schema = $1 ORDER BY 1",
      [schema],
    )
  ).rows.map((r) => r.table_name);

describe("the schema name guard", () => {
  it("accepts shared and t_<digits>", () => {
    expect(assertSchemaName("shared")).toBe("shared");
    expect(assertSchemaName("t_1")).toBe("t_1");
    expect(assertSchemaName("t_0042")).toBe("t_0042");
  });

  it.each([
    "public",
    "",
    "t_",
    "t_1; DROP",
    " shared",
    "Shared",
    "t_1 ",
    "t_a",
    'shared"',
    "t_1,public",
  ])("rejects %j, in every constructor", (schema) => {
    expect(() => assertSchemaName(schema)).toThrow();
    expect(() => createPgliteSchemaDb(pglite, schema)).toThrow();
    expect(() => createPgSchemaDb("postgres://localhost/none", schema)).toThrow();
  });
});

describe("two schemas on one PGlite", () => {
  it("hold the same table name with different rows, and interleaved queries never cross", async () => {
    const one = createPgliteSchemaDb(pglite, "t_1");
    const two = createPgliteSchemaDb(pglite, "t_2");
    await one.exec("CREATE TABLE rides (id text PRIMARY KEY)");
    await two.exec("CREATE TABLE rides (id text PRIMARY KEY)");
    await Promise.all(
      Array.from({ length: 20 }, (_, i) => [
        one.query("INSERT INTO rides (id) VALUES ($1)", [`one-${i}`]),
        two.query("INSERT INTO rides (id) VALUES ($1)", [`two-${i}`]),
        one.transaction((tx) => tx.query("INSERT INTO rides (id) VALUES ($1)", [`one-tx-${i}`])),
      ]).flat(),
    );
    const mine = async (db: Db) =>
      (await db.query<{ id: string }>("SELECT id FROM rides")).rows.map((r) => r.id);
    const idsOne = await mine(one);
    const idsTwo = await mine(two);
    expect(idsOne).toHaveLength(40);
    expect(idsTwo).toHaveLength(20);
    expect(idsOne.every((id) => id.startsWith("one-"))).toBe(true);
    expect(idsTwo.every((id) => id.startsWith("two-"))).toBe(true);
    // Another schema, with the table not created there, sees nothing of it.
    await expect(
      createPgliteSchemaDb(pglite, "t_3").query("SELECT 1 FROM rides"),
    ).rejects.toThrow();
  });

  it("roll back a transaction through the wrapper", async () => {
    const one = createPgliteSchemaDb(pglite, "t_1");
    await expect(
      one.transaction(async (tx) => {
        await tx.query("INSERT INTO rides (id) VALUES ('rolled-back')");
        throw new Error("no");
      }),
    ).rejects.toThrow("no");
    const rows = await one.query("SELECT 1 FROM rides WHERE id = 'rolled-back'");
    expect(rows.rows).toHaveLength(0);
  });

  it("do not leave a search_path behind", async () => {
    const shown = await pglite.query<{ search_path: string }>("SHOW search_path");
    expect(shown.rows[0]?.search_path).not.toMatch(/t_1|t_2|shared/);
  });
});

describe("migrations into schemas", () => {
  it("shared/0001 applies into schema shared, twice, and creates nothing in public", async () => {
    const db = createPgliteSchemaDb(pglite, "shared");
    const applied = await migrate(db, SHARED_MIGRATIONS_DIR);
    expect(applied).toEqual([1]);
    expect(await migrate(db, SHARED_MIGRATIONS_DIR)).toEqual([]);
    expect(await names(db, "shared")).toEqual(
      expect.arrayContaining([
        "bot_state",
        "oauth_clients",
        "oauth_codes",
        "oauth_families",
        "oauth_tokens",
        "schema_migrations",
        "user_settings",
        "user_tokens",
        "users",
      ]),
    );
    expect(await names(db, "public")).toEqual([]);
  });

  it("enforce the users' constraints", async () => {
    const db = createPgliteSchemaDb(pglite, "shared");
    await db.query("INSERT INTO users (handle) VALUES ('alice')");
    await expect(db.query("INSERT INTO users (handle) VALUES ('alice')")).rejects.toThrow();
    for (const bad of ["Bob", "1bob", "a", "has space", `a${"b".repeat(32)}`]) {
      await expect(db.query("INSERT INTO users (handle) VALUES ($1)", [bad])).rejects.toThrow();
    }
    const user = await db.query<{ id: string }>(
      "SELECT id::text FROM users WHERE handle = 'alice'",
    );
    const id = user.rows[0]?.id;
    await db.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ('h', 'device', 'tablet', $1)",
      [id],
    );
    await db.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ('h', 'mcp', 'chat', $1)",
      [id],
    );
    await expect(
      db.query(
        "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ('h', 'other', 'x', $1)",
        [id],
      ),
    ).rejects.toThrow();
    await db.query("DELETE FROM users WHERE handle = 'alice'");
    expect((await db.query("SELECT 1 FROM user_tokens")).rows).toHaveLength(0);
  });

  it("apply tenant migrations 0001 to 0013 into t_1 through the wrapper, and nothing lands in public", async () => {
    const fresh = await PGlite.create();
    try {
      await fresh.exec("CREATE SCHEMA t_1");
      const db = createPgliteSchemaDb(fresh, "t_1");
      const applied = await migrate(db);
      expect(applied.length).toBeGreaterThanOrEqual(13);
      expect(await names(db, "t_1")).toEqual(
        expect.arrayContaining(["rides", "schema_migrations"]),
      );
      expect(await names(db, "public")).toEqual([]);
      expect(await migrate(db)).toEqual([]);
    } finally {
      await fresh.close();
    }
  });

  it("keep the shared copies of the OAuth and bot tables identical to the tenant migrations'", async () => {
    const tenant = createPgliteSchemaDb(pglite, "t_3");
    await migrate(tenant);
    const tables = ["oauth_clients", "oauth_codes", "oauth_tokens", "bot_state"];
    const strip = (text: string, schema: string) => text.split(`${schema}.`).join("");
    const describeIn = async (schema: string) => {
      const columns = await admin.query(
        `SELECT table_name, column_name, ordinal_position, column_default, is_nullable, data_type
           FROM information_schema.columns
          WHERE table_schema = $1 AND table_name = ANY($2) ORDER BY table_name, ordinal_position`,
        [schema, tables],
      );
      const indexes = await admin.query<{ indexname: string; indexdef: string }>(
        "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = $1 AND tablename = ANY($2) ORDER BY indexname",
        [schema, tables],
      );
      const constraints = await admin.query<{ conname: string; def: string }>(
        `SELECT c.conname, pg_get_constraintdef(c.oid) AS def
           FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace
          WHERE n.nspname = $1 AND r.relname = ANY($2) ORDER BY c.conname`,
        [schema, tables],
      );
      return {
        columns: columns.rows,
        indexes: indexes.rows.map((i) => ({ ...i, indexdef: strip(i.indexdef, schema) })),
        constraints: constraints.rows.map((c) => ({ ...c, def: strip(c.def, schema) })),
      };
    };
    const inTenant = await describeIn("t_3");
    const inShared = await describeIn("shared");
    expect(inTenant.columns.length).toBeGreaterThan(20);
    expect(inShared.columns).toEqual(inTenant.columns);
    expect(inTenant.indexes.length).toBeGreaterThanOrEqual(7);
    expect(inShared.indexes).toEqual(inTenant.indexes);
    expect(inShared.constraints).toEqual(inTenant.constraints);
  });
});

describe("migrate with a directory", () => {
  it("reads the files of a custom directory, in its own schema", async () => {
    const dir = await mkdtemp(join(tmpdir(), "migrations-"));
    try {
      await mkdir(dir, { recursive: true });
      await writeFile(join(dir, "0001_a.sql"), "CREATE TABLE a (id integer);");
      await writeFile(join(dir, "0002_b.sql"), "CREATE TABLE b (id integer);");
      const url = pathToFileURL(`${dir}/`);
      const db = createPgliteSchemaDb(pglite, "t_2");
      expect(await migrate(db, url)).toEqual([1, 2]);
      expect(await migrate(db, url)).toEqual([]);
      expect(await names(db, "t_2")).toEqual(expect.arrayContaining(["a", "b"]));
      await writeFile(join(dir, "0001_a.sql"), "CREATE TABLE a (id bigint);");
      await expect(migrate(db, url)).rejects.toThrow(/changed after it was applied/);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});

const realUrl = process.env.TEST_DATABASE_URL;

describe.skipIf(realUrl === undefined || realUrl === "")(
  "pg schema pools on real PostgreSQL",
  () => {
    it("keep two schemas apart, with no public in the search_path", async () => {
      const url = realUrl ?? "";
      const plain = createPgDb(url);
      await resetSchema(plain);
      await plain.exec("CREATE SCHEMA t_1; CREATE SCHEMA t_2");
      const one = createPgSchemaDb(url, "t_1");
      const two = createPgSchemaDb(url, "t_2");
      try {
        await one.exec("CREATE TABLE rides (id text)");
        await two.exec("CREATE TABLE rides (id text)");
        await Promise.all([
          one.query("INSERT INTO rides VALUES ('a')"),
          two.query("INSERT INTO rides VALUES ('b')"),
          two.query("INSERT INTO rides VALUES ('c')"),
        ]);
        expect((await one.query("SELECT id FROM rides")).rows).toHaveLength(1);
        expect((await two.query("SELECT id FROM rides")).rows).toHaveLength(2);
        const shown = await one.query<{ search_path: string }>("SHOW search_path");
        expect(shown.rows[0]?.search_path).toBe("t_1");
        await expect(
          one.transaction(async (tx) => {
            await tx.query("INSERT INTO rides VALUES ('x')");
            throw new Error("no");
          }),
        ).rejects.toThrow("no");
        expect((await one.query("SELECT id FROM rides")).rows).toHaveLength(1);
        expect(await names(plain, "public")).toEqual([]);
      } finally {
        await one.close();
        await two.close();
        await resetSchema(plain);
        await plain.close();
      }
    });
  },
);
