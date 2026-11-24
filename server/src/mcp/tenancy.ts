import type { DeviceToken } from "../config.ts";
import type { Db } from "../db.ts";
import { sha256Hex } from "../hash.ts";
import type { LoginUsers } from "../oauth/users.ts";
import type { Services } from "../services.ts";
import type { Tenant, TenantDirectory, TokenKind } from "../tenancy/directory.ts";

/**
 * What the MCP endpoint and the OAuth server need to know about the users: the `shared` Db (the OAuth tables and
 * `users`), the directory that finds a user by token or id, and the services of each user's data.
 */
export interface McpTenancy {
  /** The Db of the `shared` schema: OAuth tables, `oauth_families`, `users`. */
  shared: Db;
  directory: TenantDirectory;
  /** The running services of a user, or null when there are none (the user is gone or not started). */
  servicesOf(userId: number): Services | null;
  /** Who can sign in on the authorization page. */
  users: LoginUsers;
}

/** The handle of the one user in single-user mode (no directory): the owner. */
export const OWNER_HANDLE = "owner";

/**
 * Single-user mode (`buildApp` without a directory): one tenant made from the given services. Its row in
 * `shared.users` is made on first use (the OAuth families refer to it); the passphrase is the configured
 * `OWNER_PASSPHRASE_HASH`, and the handle is not asked for.
 */
export function singleTenancy(input: {
  shared: Db;
  services: Services;
  staticTokens: readonly DeviceToken[];
  passphraseHash: string | undefined;
}): McpTenancy {
  const { shared, services } = input;
  const digests = new Map(input.staticTokens.map((t) => [sha256Hex(t.token), t.name]));
  let owner: Promise<Tenant> | null = null;
  const ownerTenant = (): Promise<Tenant> => {
    owner ??= (async () => {
      await shared.query("INSERT INTO users (handle) VALUES ($1) ON CONFLICT (handle) DO NOTHING", [
        OWNER_HANDLE,
      ]);
      const row = (
        await shared.query<{ id: string }>("SELECT id::text AS id FROM users WHERE handle = $1", [
          OWNER_HANDLE,
        ])
      ).rows[0];
      const id = Number(row?.id);
      return { userId: id, handle: OWNER_HANDLE, schema: `t_${id}` };
    })();
    owner.catch(() => {
      owner = null;
    });
    return owner;
  };
  const directory: TenantDirectory = {
    async byToken(sha256: string, kind: TokenKind) {
      const name = digests.get(sha256);
      if (kind !== "mcp" || name === undefined) return null;
      return { tenant: await ownerTenant(), tokenName: name };
    },
    async byUserId(id) {
      const tenant = await ownerTenant();
      return tenant.userId === id ? tenant : null;
    },
    byChat: async () => null,
    list: async () => [await ownerTenant()],
  };
  const users: LoginUsers = {
    asksHandle: false,
    async find() {
      const tenant = await ownerTenant();
      return { userId: tenant.userId, passphraseHash: input.passphraseHash ?? null };
    },
  };
  return { shared, directory, servicesOf: () => services, users };
}
