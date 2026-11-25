import { randomBytes } from "node:crypto";
import type { Db } from "../../src/db.ts";
import { sha256Hex } from "../../src/hash.ts";
import { migrate } from "../../src/migrate.ts";
import { hashPassphrase } from "../../src/oauth/passphrase.ts";
import { sharedLoginUsers } from "../../src/oauth/users.ts";
import { createServices, type Services, type ServicesConfig } from "../../src/services.ts";
import type { Tenant, TenantDirectory, TokenKind } from "../../src/tenancy/directory.ts";
import { migrateShared, openTenancyTestDb, type TenancyTestDb } from "./db.ts";
import { collectingLogger, recordedSleep, type TestClock, testClock } from "./support.ts";

/**
 * A test double of {@link TenantDirectory}: it answers from `shared.users` and `shared.user_tokens` with plain SQL. (The real
 * one is the registry's.) A disabled user and a revoked token are not found.
 */
export function sqlDirectory(shared: Db): TenantDirectory {
  const toTenant = (row: { id: string; handle: string }): Tenant => ({
    userId: Number(row.id),
    handle: row.handle,
    schema: `t_${row.id}`,
  });
  return {
    async byToken(sha256: string, kind: TokenKind) {
      const found = await shared.query<{ id: string; handle: string; name: string }>(
        `SELECT u.id::text AS id, u.handle, t.name
           FROM user_tokens t JOIN users u ON u.id = t.user_id
          WHERE t.token_sha256 = $1 AND t.kind = $2 AND t.revoked_at IS NULL AND u.disabled_at IS NULL`,
        [sha256, kind],
      );
      const row = found.rows[0];
      return row === undefined ? null : { tenant: toTenant(row), tokenName: row.name };
    },
    async byUserId(id) {
      const found = await shared.query<{ id: string; handle: string }>(
        "SELECT id::text AS id, handle FROM users WHERE id = $1 AND disabled_at IS NULL",
        [id],
      );
      const row = found.rows[0];
      return row === undefined ? null : toTenant(row);
    },
    async byChat(chatId) {
      const found = await shared.query<{ id: string; handle: string }>(
        "SELECT id::text AS id, handle FROM users WHERE telegram_chat_id = $1 AND disabled_at IS NULL",
        [chatId],
      );
      const row = found.rows[0];
      return row === undefined ? null : toTenant(row);
    },
    async list() {
      const found = await shared.query<{ id: string; handle: string }>(
        "SELECT id::text AS id, handle FROM users WHERE disabled_at IS NULL ORDER BY id",
      );
      return found.rows.map(toTenant);
    },
  };
}

export interface RigUser {
  userId: number;
  handle: string;
  passphrase: string;
  /** An MCP static token of this user. */
  mcpToken: string;
  chatId: number;
  db: Db;
  services: Services;
}

export interface TenantRig {
  t: TenancyTestDb;
  shared: Db;
  directory: TenantDirectory;
  clock: TestClock;
  users: Record<string, RigUser>;
  logging: ReturnType<typeof collectingLogger>;
  /** The services of a user (what `mcpTenancy.servicesOf` is). */
  servicesOf(userId: number): Services | null;
  /** Makes `shared.users` and the whole `shared` Db usable by the one-user type of option (login lookup). */
  loginUsers: ReturnType<typeof sharedLoginUsers>;
  close(): Promise<void>;
}

export interface RigOptions {
  /** Settings for every user's services (the plan source, a Telegram bot, …); a function gets the user's handle and chat id. */
  config?: ServicesConfig | ((user: { handle: string; chatId: number }) => ServicesConfig);
  /** Passed to `createServices` for each user (the fake network, the clock, …). */
  services?: Partial<Parameters<typeof createServices>[0]>;
  /** The chat ids to give the users, in order (default 7001, 7002, …). */
  chatIds?: number[];
}

const CHEAP = { N: 1024, r: 8, p: 1 };

/**
 * An empty installation with a `shared` schema and one schema per handle (`t_1`, `t_2`, …), each migrated, each with the
 * user's row (passphrase hash, chat id), an MCP static token and its own services. All secrets are made here, at random.
 */
export async function openTenantRig(
  handles: string[],
  options: RigOptions = {},
): Promise<TenantRig> {
  const t = await openTenancyTestDb();
  await t.db.exec("CREATE SCHEMA shared");
  const shared = t.schemaDb("shared");
  await migrateShared(shared);
  const clock = testClock("2026-11-18T08:00:00Z");
  const logging = collectingLogger();
  const users: Record<string, RigUser> = {};
  for (const [index, handle] of handles.entries()) {
    const passphrase = `pass-${randomBytes(9).toString("base64url")}-${handle}`;
    const mcpToken = `mcp-${randomBytes(24).toString("hex")}`;
    const chatId = options.chatIds?.[index] ?? 7001 + index;
    const inserted = await shared.query<{ id: string }>(
      "INSERT INTO users (handle, passphrase_hash, telegram_chat_id) VALUES ($1, $2, $3) RETURNING id::text AS id",
      [handle, await hashPassphrase(passphrase, CHEAP), String(chatId)],
    );
    const userId = Number(inserted.rows[0]?.id);
    await shared.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, 'mcp', $2, $3)",
      [sha256Hex(mcpToken), `claude-${handle}`, userId],
    );
    await t.db.exec(`CREATE SCHEMA t_${userId}`);
    const db = t.schemaDb(`t_${userId}`);
    await migrate(db);
    const services = createServices({
      config: {
        TIMEZONE: "Europe/Paris",
        ...(typeof options.config === "function"
          ? options.config({ handle, chatId })
          : options.config),
      },
      db,
      sharedDb: shared,
      telegramPoll: false,
      log: logging.log,
      now: clock.now,
      sleep: recordedSleep().sleep,
      ...options.services,
    });
    users[handle] = { userId, handle, passphrase, mcpToken, chatId, db, services };
  }
  const byId = new Map(Object.values(users).map((u) => [u.userId, u.services]));
  return {
    t,
    shared,
    directory: sqlDirectory(shared),
    clock,
    users,
    logging,
    servicesOf: (id) => byId.get(id) ?? null,
    loginUsers: sharedLoginUsers(shared),
    async close() {
      await Promise.all(Object.values(users).map((u) => u.services.stop()));
      await t.close();
    },
  };
}
