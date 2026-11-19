import type { Db, Queryable } from "../db.ts";
import type { NotebookScope } from "./sections.ts";

/**
 * The notebooks in PostgreSQL (`notebook_versions`, migration 0010, ADR 0035). Every edit of a section is a new row; nothing is
 * updated or deleted, so history is never rewritten and a rollback is only a new version. The current text of a section is its
 * row with the highest version; a section with no row is empty.
 */

export type VersionKind = "edit" | "append" | "rollback";

export interface VersionRow {
  id: number;
  scope: NotebookScope;
  section: string;
  version: number;
  text: string;
  reason: string | null;
  kind: VersionKind;
  /** The version whose text a rollback carries; null for the others. */
  rolledBackTo: number | null;
  /** The MCP client that wrote it. */
  author: string;
  createdAt: Date;
}

/** What to write as the next version of a section. */
export interface NewVersion {
  section: string;
  text: string;
  reason: string | null;
  kind: VersionKind;
  rolledBackTo: number | null;
  author: string;
  at: Date;
}

/** What {@link NotebookStore.mutate} is told by the code that decides, looking at the document as it is under the lock. */
export type Decision<R> = { refuse: R } | { write: NewVersion };

export interface NotebookStore {
  /** The current version of every section that has been written, by section. */
  current(scope: NotebookScope): Promise<Map<string, VersionRow>>;
  /** The versions of one section, newest first. */
  versions(scope: NotebookScope, section: string, limit: number): Promise<VersionRow[]>;
  version(scope: NotebookScope, section: string, version: number): Promise<VersionRow | null>;
  /** The versions of one section written at or after `sinceMs`, oldest first. */
  since(scope: NotebookScope, section: string, sinceMs: number): Promise<VersionRow[]>;
  /**
   * Looks at the whole document under a lock (one writer at a time per notebook), asks `decide` what to do, and writes the next
   * version in the same transaction, or returns what `decide` refused with. The version number is the current one plus one.
   */
  mutate<R>(
    scope: NotebookScope,
    decide: (current: ReadonlyMap<string, VersionRow>) => Decision<R>,
  ): Promise<{ written: VersionRow } | { refused: R }>;
}

interface Row {
  id: number | string;
  scope: NotebookScope;
  section: string;
  version: number;
  text: string;
  reason: string | null;
  kind: VersionKind;
  rolled_back_to: number | null;
  author: string;
  created_at: Date | string;
}

const COLUMNS =
  "id, scope, section, version, text, reason, kind, rolled_back_to, author, created_at";

const toRow = (row: Row): VersionRow => ({
  id: Number(row.id),
  scope: row.scope,
  section: row.section,
  version: row.version,
  text: row.text,
  reason: row.reason,
  kind: row.kind,
  rolledBackTo: row.rolled_back_to,
  author: row.author,
  createdAt: new Date(row.created_at),
});

/** Held while one notebook is written, so two sessions editing at once take turns (migrations hold 727001). */
const LOCK_BASE = 727_100;
const LOCK_OFFSET: Record<NotebookScope, number> = { coach: 0, "game-master": 1 };

async function readCurrent(db: Queryable, scope: NotebookScope): Promise<Map<string, VersionRow>> {
  const result = await db.query<Row>(
    `SELECT DISTINCT ON (section) ${COLUMNS} FROM notebook_versions
      WHERE scope = $1 ORDER BY section, version DESC`,
    [scope],
  );
  return new Map(result.rows.map((row) => [row.section, toRow(row)]));
}

export function createNotebookStore(db: Db): NotebookStore {
  return {
    current: (scope) => readCurrent(db, scope),

    async versions(scope, section, limit) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM notebook_versions
          WHERE scope = $1 AND section = $2 ORDER BY version DESC LIMIT $3`,
        [scope, section, limit],
      );
      return result.rows.map(toRow);
    },

    async version(scope, section, version) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM notebook_versions WHERE scope = $1 AND section = $2 AND version = $3`,
        [scope, section, version],
      );
      const row = result.rows[0];
      return row === undefined ? null : toRow(row);
    },

    async since(scope, section, sinceMs) {
      const result = await db.query<Row>(
        `SELECT ${COLUMNS} FROM notebook_versions
          WHERE scope = $1 AND section = $2 AND created_at >= $3::timestamptz
          ORDER BY version`,
        [scope, section, new Date(sinceMs).toISOString()],
      );
      return result.rows.map(toRow);
    },

    async mutate(scope, decide) {
      return db.transaction(async (tx) => {
        await tx.query("SELECT pg_advisory_xact_lock($1)", [LOCK_BASE + LOCK_OFFSET[scope]]);
        const current = await readCurrent(tx, scope);
        const decision = decide(current);
        if ("refuse" in decision) return { refused: decision.refuse };
        const next = decision.write;
        const version = (current.get(next.section)?.version ?? 0) + 1;
        const result = await tx.query<Row>(
          `INSERT INTO notebook_versions
             (scope, section, version, text, reason, kind, rolled_back_to, author, created_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz)
           RETURNING ${COLUMNS}`,
          [
            scope,
            next.section,
            version,
            next.text,
            next.reason,
            next.kind,
            next.rolledBackTo,
            next.author,
            next.at.toISOString(),
          ],
        );
        const row = result.rows[0];
        if (row === undefined) throw new Error("the notebook version was not stored");
        return { written: toRow(row) };
      });
    },
  };
}
