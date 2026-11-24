import type { Queryable } from "../db.ts";

/** What one table holds, and how it is defined, with the schema's name left out so two schemas can be compared. */
export interface TableSnapshot {
  rows: number;
  /** `md5` of the per-row md5s, in primary-key order (in row-hash order for a table with no primary key). */
  hash: string;
  /** `name type [NOT NULL] [identity] [DEFAULT expr]`, in column order. */
  columns: string[];
  /** `pg_get_indexdef` of every index, sorted. */
  indexes: string[];
  /** `name definition` of every constraint, sorted. */
  constraints: string[];
}

export interface SequenceSnapshot {
  lastValue: string;
  isCalled: boolean;
}

export interface Snapshot {
  tables: Record<string, TableSnapshot>;
  /** Sequences owned by a column of one of the tables, by name. */
  sequences: Record<string, SequenceSnapshot>;
}

/** The part of a snapshot a report may carry: counts and hashes, nothing of the rows. */
export interface SnapshotSummary {
  tables: Record<string, { rows: number; hash: string }>;
  sequences: Record<string, SequenceSnapshot>;
}

/** Quotes an SQL identifier. */
export function quoteIdent(name: string): string {
  return `"${name.replaceAll('"', '""')}"`;
}

/** Removes the schema qualifiers the service's schemas can print (`public.`, `shared.`, `t_<n>.`, also quoted). */
export function stripSchemaNames(text: string): string {
  return text.replace(/(?<![\w"])"?(?:public|shared|t_[0-9]+)"?\./g, "");
}

/** The names of the ordinary tables in `schema`, sorted. */
export async function listTables(q: Queryable, schema: string): Promise<string[]> {
  const res = await q.query<{ relname: string }>(
    `SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = $1 AND c.relkind IN ('r', 'p') ORDER BY c.relname`,
    [schema],
  );
  return res.rows.map((r) => r.relname);
}

async function snapshotTable(q: Queryable, schema: string, table: string): Promise<TableSnapshot> {
  const qualified = `${quoteIdent(schema)}.${quoteIdent(table)}`;
  const keys = await q.query<{ attname: string }>(
    `SELECT a.attname
       FROM pg_index i
       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
      WHERE i.indrelid = $1::regclass AND i.indisprimary
      ORDER BY array_position(i.indkey::int2[], a.attnum)`,
    [qualified],
  );
  const order =
    keys.rows.length === 0
      ? "md5(t::text)"
      : keys.rows.map((k) => quoteIdent(k.attname)).join(", ");
  const content = await q.query<{ n: string; h: string }>(
    `SELECT count(*)::text AS n, coalesce(md5(string_agg(md5(t::text), '' ORDER BY ${order})), 'empty') AS h
       FROM ${qualified} t`,
  );
  const columns = await q.query<{
    attname: string;
    type: string;
    attnotnull: boolean;
    attidentity: string;
    def: string | null;
  }>(
    `SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull, a.attidentity::text AS attidentity,
            pg_get_expr(d.adbin, d.adrelid) AS def
       FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
      WHERE a.attrelid = $1::regclass AND a.attnum > 0 AND NOT a.attisdropped
      ORDER BY a.attnum`,
    [qualified],
  );
  const indexes = await q.query<{ def: string }>(
    "SELECT pg_get_indexdef(indexrelid) AS def FROM pg_index WHERE indrelid = $1::regclass",
    [qualified],
  );
  const constraints = await q.query<{ def: string }>(
    "SELECT conname || ' ' || pg_get_constraintdef(oid) AS def FROM pg_constraint WHERE conrelid = $1::regclass",
    [qualified],
  );
  const row = content.rows[0];
  return {
    rows: Number(row?.n ?? 0),
    hash: row?.h ?? "",
    columns: columns.rows.map((c) =>
      stripSchemaNames(
        [
          c.attname,
          c.type,
          c.attnotnull ? "NOT NULL" : "",
          c.attidentity === "" ? "" : `identity:${c.attidentity}`,
          c.def === null ? "" : `DEFAULT ${c.def}`,
        ]
          .filter((p) => p !== "")
          .join(" "),
      ),
    ),
    indexes: indexes.rows.map((r) => stripSchemaNames(r.def)).sort(),
    constraints: constraints.rows.map((r) => stripSchemaNames(r.def)).sort(),
  };
}

/**
 * Describes the tables of `schema` (all of them, or only `only`) and the sequences their columns own. The same function
 * runs on `public` before the cutover and on `t_1` and `shared` after it; the result does not name the schema.
 */
export async function takeSnapshot(
  q: Queryable,
  schema: string,
  only?: readonly string[],
): Promise<Snapshot> {
  const present = await listTables(q, schema);
  const names = only === undefined ? present : present.filter((t) => only.includes(t));
  const tables: Record<string, TableSnapshot> = {};
  for (const name of names) tables[name] = await snapshotTable(q, schema, name);
  const owned = await q.query<{ relname: string }>(
    `SELECT DISTINCT s.relname
       FROM pg_class s
       JOIN pg_namespace n ON n.oid = s.relnamespace
       JOIN pg_depend d ON d.objid = s.oid AND d.classid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
       JOIN pg_class t ON t.oid = d.refobjid
      WHERE s.relkind = 'S' AND n.nspname = $1 AND t.relname = ANY ($2::text[])
      ORDER BY s.relname`,
    [schema, names],
  );
  const sequences: Record<string, SequenceSnapshot> = {};
  for (const { relname } of owned.rows) {
    const value = await q.query<{ last_value: string; is_called: boolean }>(
      `SELECT last_value::text AS last_value, is_called FROM ${quoteIdent(schema)}.${quoteIdent(relname)}`,
    );
    const v = value.rows[0];
    sequences[relname] = { lastValue: v?.last_value ?? "", isCalled: v?.is_called ?? false };
  }
  return { tables, sequences };
}

/** Joins snapshots of different schemas into one (the tables must not repeat). */
export function mergeSnapshots(...parts: Snapshot[]): Snapshot {
  const merged: Snapshot = { tables: {}, sequences: {} };
  for (const part of parts) {
    for (const [name, table] of Object.entries(part.tables)) {
      if (name in merged.tables) throw new Error(`Table ${name} is in two snapshots`);
      merged.tables[name] = table;
    }
    for (const [name, seq] of Object.entries(part.sequences)) {
      if (name in merged.sequences) throw new Error(`Sequence ${name} is in two snapshots`);
      merged.sequences[name] = seq;
    }
  }
  return merged;
}

/** What differs between two snapshots, one line each; empty when they are equal. */
export function diffSnapshots(before: Snapshot, after: Snapshot): string[] {
  const problems: string[] = [];
  const names = new Set([...Object.keys(before.tables), ...Object.keys(after.tables)]);
  for (const name of [...names].sort()) {
    const b = before.tables[name];
    const a = after.tables[name];
    if (b === undefined || a === undefined) {
      problems.push(`table ${name}: ${b === undefined ? "appeared" : "disappeared"}`);
      continue;
    }
    if (b.rows !== a.rows) problems.push(`table ${name}: ${b.rows} rows before, ${a.rows} after`);
    if (b.hash !== a.hash) problems.push(`table ${name}: the content hash differs`);
    for (const part of ["columns", "indexes", "constraints"] as const) {
      if (JSON.stringify(b[part]) !== JSON.stringify(a[part])) {
        problems.push(`table ${name}: the ${part} differ`);
      }
    }
  }
  const seqs = new Set([...Object.keys(before.sequences), ...Object.keys(after.sequences)]);
  for (const name of [...seqs].sort()) {
    const b = before.sequences[name];
    const a = after.sequences[name];
    if (b === undefined || a === undefined) {
      problems.push(`sequence ${name}: ${b === undefined ? "appeared" : "disappeared"}`);
    } else if (b.lastValue !== a.lastValue || b.isCalled !== a.isCalled) {
      problems.push(`sequence ${name}: the value differs`);
    }
  }
  return problems;
}

/** Counts and hashes only, for the bootstrap report. */
export function summarize(snapshot: Snapshot): SnapshotSummary {
  return {
    tables: Object.fromEntries(
      Object.entries(snapshot.tables).map(([name, t]) => [name, { rows: t.rows, hash: t.hash }]),
    ),
    sequences: snapshot.sequences,
  };
}
