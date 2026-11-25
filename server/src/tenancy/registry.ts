import { type Config, describeConfigError } from "../config.ts";
import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import { createServices, type Services } from "../services.ts";
import type { TenantRuntime, TenantRuntimeDirectory, TenantState } from "./context.ts";
import type { Tenant, TokenKind } from "./directory.ts";
import { TokenTable } from "./tokens.ts";
import { loadUserConfig } from "./user-config.ts";

/** What the registry gives the function that builds a tenant's services. */
export interface TenantServicesInput {
  config: Config;
  db: Db;
  log: Logger;
  tenant: Tenant;
}

/**
 * The services of one tenant: the same graph as the single-user service, on the tenant's database, with no Telegram bot of
 * its own (the shared poller of stage B2 owns the bot token and hands each user's updates to that user's services).
 */
export function createTenantServices(input: TenantServicesInput): Services {
  return createServices({ config: input.config, db: input.db, log: input.log, telegram: false });
}

export interface TenantRegistryOptions {
  /** The `shared` schema's database: users, tokens, settings. */
  sharedDb: Db;
  /** The database of one schema (`t_<id>`): `createPgSchemaDb` in production, the PGlite schema Db in the tests. */
  schemaDb: (schema: string) => Db;
  /** The environment the server started with: where the installation keys come from. */
  installation: Readonly<Record<string, string | undefined>>;
  log: Logger;
  /** Builds a tenant's services; {@link createTenantServices} unless a test brings its own. */
  createServices?: (input: TenantServicesInput) => Services;
  /** Whether {@link TenantRegistry.start} really starts the background jobs. Tests pass false. */
  startJobs: boolean;
  /** The pause between one tenant's jobs starting and the next's, so the backfills do not start together. Default 2000 ms. */
  startGapMs?: number;
}

interface Entry {
  tenant: Tenant;
  chatId: string | null;
  state: TenantState;
  error: string | null;
  services: Services | null;
  db: Db | null;
  /** Whether this tenant's background jobs are running. */
  running: boolean;
}

interface UserRow {
  id: string;
  handle: string;
  telegram_chat_id: string | null;
  disabled: boolean;
}

export interface TenantRegistry extends TenantRuntimeDirectory {
  /** Rebuilds the user's services from their current settings. Refuses while the user has a live coach session. */
  reload(userId: number): Promise<void>;
  /** Hosts a user (new, or one that is in `shared.users` and was not hosted yet): builds their config and services. */
  add(userId: number): Promise<void>;
  /** Stops the user's services, closes their pool and forgets them and their tokens (their data stays where it is). */
  remove(userId: number): Promise<void>;
  /** Reads the tokens and the users' disabled flags from `shared` again. */
  refreshTokens(): Promise<void>;
  /** Starts every ready tenant's jobs, one after another with a gap (does nothing when `startJobs` is false). */
  start(): Promise<void>;
  /** Stops every tenant and closes their pools (not the shared Db: the caller owns it). */
  close(): Promise<void>;
}

const toTenant = (id: number, handle: string): Tenant => ({
  userId: id,
  handle,
  schema: `t_${id}`,
});

/** A logger that adds the tenant's handle to every line. */
function tenantLog(log: Logger, handle: string): Logger {
  const tag = (fields: object) => ({ tenant: handle, ...fields });
  return {
    debug: (fields, message) => log.debug(tag(fields), message),
    info: (fields, message) => log.info(tag(fields), message),
    warn: (fields, message) => log.warn(tag(fields), message),
    error: (fields, message) => log.error(tag(fields), message),
  };
}

/**
 * Hosts the tenants: for each user of `shared.users` a configuration (installation keys from the environment, per-user
 * keys from that user's settings, never mixed), a database pool on the user's schema and a services graph. A user whose
 * configuration or services fail to build is `failed` (the error is kept and logged, key names only) and does not stop the
 * others. A user with `disabled_at` is `disabled`: no services, their tokens do not authenticate.
 */
export async function createTenantRegistry(
  options: TenantRegistryOptions,
): Promise<TenantRegistry> {
  const { sharedDb, log } = options;
  const build = options.createServices ?? createTenantServices;
  const gapMs = options.startGapMs ?? 2000;
  const entries = new Map<number, Entry>();
  const tokens = new TokenTable();
  let started = false;
  let closed = false;
  let startChain: Promise<void> = Promise.resolve();
  let wake: (() => void) | null = null;

  async function readSettings(userId: number): Promise<Record<string, string>> {
    const rows = await sharedDb.query<{ key: string; value: string }>(
      "SELECT key, value FROM user_settings WHERE user_id = $1",
      [userId],
    );
    return Object.fromEntries(rows.rows.map((r) => [r.key, r.value]));
  }

  async function readUser(userId: number): Promise<UserRow | null> {
    const rows = await sharedDb.query<UserRow>(
      "SELECT id::text AS id, handle, telegram_chat_id, disabled_at IS NOT NULL AS disabled FROM users WHERE id = $1",
      [userId],
    );
    return rows.rows[0] ?? null;
  }

  async function readTokens(userId?: number): Promise<void> {
    const rows = await sharedDb.query<{
      token_sha256: string;
      kind: string;
      name: string;
      user_id: string;
    }>(
      userId === undefined
        ? "SELECT token_sha256, kind, name, user_id::text AS user_id FROM user_tokens WHERE revoked_at IS NULL"
        : "SELECT token_sha256, kind, name, user_id::text AS user_id FROM user_tokens WHERE revoked_at IS NULL AND user_id = $1",
      userId === undefined ? [] : [userId],
    );
    for (const row of rows.rows) {
      if (row.kind !== "device" && row.kind !== "mcp") continue;
      const id = Number(row.user_id);
      // A disabled user's tokens are not served at all.
      if (entries.get(id)?.state === "disabled") continue;
      tokens.add(row.token_sha256, { userId: id, kind: row.kind, name: row.name });
    }
  }

  /** Reads the user and builds their entry: never throws for a bad configuration or services (the entry is `failed`). */
  async function buildEntry(userId: number): Promise<Entry> {
    const user = await readUser(userId);
    if (user === null) throw new Error(`There is no user ${userId} in shared.users`);
    const tenant = toTenant(userId, user.handle);
    const entry: Entry = {
      tenant,
      chatId: user.telegram_chat_id,
      state: "ready",
      error: null,
      services: null,
      db: null,
      running: false,
    };
    if (user.disabled) {
      entry.state = "disabled";
      return entry;
    }
    const tlog = tenantLog(log, user.handle);
    try {
      const settings = await readSettings(userId);
      const config = loadUserConfig({
        installation: options.installation,
        settings,
        telegramChatId: user.telegram_chat_id,
        onIgnored: ({ installationPerUser, settingsNotPerUser }) => {
          if (installationPerUser.length > 0) {
            tlog.warn(
              { keys: installationPerUser },
              "per-user settings found in the server environment are ignored: set them for the user instead",
            );
          }
          if (settingsNotPerUser.length > 0) {
            tlog.warn(
              { keys: settingsNotPerUser },
              "settings that are not per-user keys are ignored",
            );
          }
        },
      });
      entry.db = options.schemaDb(tenant.schema);
      entry.services = build({ config, db: entry.db, log: tlog, tenant });
    } catch (err) {
      const message = describeConfigError(err);
      tlog.error({ userId }, `the tenant failed to start and is out of service: ${message}`);
      if (entry.db !== null) await entry.db.close().catch(() => {});
      entry.state = "failed";
      entry.error = message;
      entry.services = null;
      entry.db = null;
    }
    return entry;
  }

  async function teardown(entry: Entry): Promise<void> {
    const { services, db } = entry;
    entry.running = false;
    entry.services = null;
    entry.db = null;
    try {
      await services?.stop();
    } catch (err) {
      log.error({ err, tenant: entry.tenant.handle }, "a tenant's services did not stop cleanly");
    }
    await db?.close().catch(() => {});
  }

  function startNow(entry: Entry): void {
    if (!options.startJobs || entry.running || entry.services === null) return;
    entry.running = true;
    entry.services.start();
  }

  const pause = (ms: number): Promise<void> =>
    new Promise((resolve) => {
      const timer = setTimeout(() => {
        wake = null;
        resolve();
      }, ms);
      timer.unref();
      wake = () => {
        clearTimeout(timer);
        wake = null;
        resolve();
      };
    });

  const users = await sharedDb.query<{ id: string }>(
    "SELECT id::text AS id FROM users ORDER BY id",
  );
  for (const row of users.rows) {
    const id = Number(row.id);
    entries.set(id, await buildEntry(id));
  }
  await readTokens();

  const runtimeOf = (entry: Entry): TenantRuntime => ({
    tenant: entry.tenant,
    state: entry.state,
    error: entry.error,
    services: entry.state === "ready" ? entry.services : null,
    db: entry.state === "ready" ? entry.db : null,
  });

  return {
    async byToken(sha256: string, kind: TokenKind) {
      const hit = tokens.find(sha256, kind);
      if (hit === null) return null;
      const entry = entries.get(hit.userId);
      if (entry === undefined || entry.state === "disabled") return null;
      return { tenant: entry.tenant, tokenName: hit.name };
    },
    async byUserId(id) {
      return entries.get(id)?.tenant ?? null;
    },
    async byChat(chatId) {
      for (const entry of entries.values()) {
        if (entry.chatId === chatId && entry.state !== "disabled") return entry.tenant;
      }
      return null;
    },
    async list() {
      return [...entries.values()].map((e) => e.tenant);
    },
    runtime(userId) {
      const entry = entries.get(userId);
      return entry === undefined ? null : runtimeOf(entry);
    },
    runtimes() {
      return [...entries.values()].map(runtimeOf);
    },

    async reload(userId) {
      const old = entries.get(userId);
      if (old === undefined) throw new Error(`User ${userId} is not hosted: add it first`);
      if (old.services?.liveCoach.hasLiveSession() === true) {
        throw new Error(
          `User ${old.tenant.handle} has a live coach session: reload when the ride is over`,
        );
      }
      await teardown(old);
      const fresh = await buildEntry(userId);
      entries.set(userId, fresh);
      tokens.removeUser(userId);
      await readTokens(userId);
      if (started) startNow(fresh);
    },

    async add(userId) {
      if (entries.has(userId))
        throw new Error(`User ${userId} is already hosted: reload it instead`);
      const fresh = await buildEntry(userId);
      entries.set(userId, fresh);
      await readTokens(userId);
      if (started) startNow(fresh);
    },

    async remove(userId) {
      const entry = entries.get(userId);
      if (entry === undefined) return;
      entries.delete(userId);
      tokens.removeUser(userId);
      await teardown(entry);
    },

    async refreshTokens() {
      // The disabled flags first (a user newly disabled stops, one newly enabled is built), then the tokens.
      for (const [userId, entry] of [...entries]) {
        const user = await readUser(userId);
        if (user === null) continue;
        entry.chatId = user.telegram_chat_id;
        if (user.disabled && entry.state !== "disabled") {
          entries.set(userId, {
            ...entry,
            state: "disabled",
            error: null,
            services: null,
            db: null,
            running: false,
          });
          await teardown(entry);
        } else if (!user.disabled && entry.state === "disabled") {
          const fresh = await buildEntry(userId);
          entries.set(userId, fresh);
          if (started) startNow(fresh);
        }
      }
      tokens.clear();
      await readTokens();
    },

    async start() {
      if (started || closed) return;
      started = true;
      if (!options.startJobs) return;
      startChain = (async () => {
        let first = true;
        for (const entry of [...entries.values()]) {
          if (closed) return;
          if (entry.state !== "ready" || entry.running) continue;
          if (!first) await pause(gapMs);
          if (closed) return;
          startNow(entry);
          first = false;
        }
      })();
      await startChain;
    },

    async close() {
      closed = true;
      wake?.();
      await startChain.catch(() => {});
      const all = [...entries.values()];
      entries.clear();
      tokens.clear();
      await Promise.all(all.map((entry) => teardown(entry)));
    },
  };
}
