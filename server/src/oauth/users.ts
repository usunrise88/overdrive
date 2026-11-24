import type { Queryable } from "../db.ts";

/** Who can sign in on the authorization page, and with which stored passphrase hash. */
export interface LoginUser {
  userId: number;
  /** `scrypt:…` as `hash-passphrase` writes it; null when the user has set no passphrase (they cannot sign in). */
  passphraseHash: string | null;
}

/**
 * Finds the user a typed handle names. The handle is lower-cased and trimmed first (handles are lowercase), and
 * `null` stands for "no handle typed". An answer of null means nobody can sign in with it; the page treats that
 * exactly like a wrong passphrase.
 */
export interface LoginUsers {
  /** Whether the page asks for a handle (multi-tenant) or knows the one owner (single-tenant). */
  readonly asksHandle: boolean;
  find(handle: string | null): Promise<LoginUser | null>;
}

/** The users of `shared.users` (`db` is the `shared` Db); a disabled user cannot sign in. */
export function sharedLoginUsers(db: Queryable): LoginUsers {
  return {
    asksHandle: true,
    async find(handle) {
      if (handle === null || handle === "") return null;
      const result = await db.query<{ id: string; passphrase_hash: string | null }>(
        "SELECT id::text AS id, passphrase_hash FROM users WHERE handle = $1 AND disabled_at IS NULL",
        [handle],
      );
      const row = result.rows[0];
      return row === undefined
        ? null
        : { userId: Number(row.id), passphraseHash: row.passphrase_hash };
    },
  };
}

/** Normalises what was typed in the handle field. */
export function normalizeHandle(typed: string | null): string | null {
  if (typed === null) return null;
  const handle = typed.trim().toLowerCase();
  return handle === "" ? null : handle;
}
