import { randomBytes, timingSafeEqual } from "node:crypto";
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { z } from "zod";
import { HANDLE } from "../config.ts";
import type { Db, Queryable } from "../db.ts";
import { installErrorHandling, sendError } from "../errors.ts";
import { sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import { type Migration, migrate } from "../migrate.ts";
import { parsePassphraseHash } from "../oauth/passphrase.ts";
import { PER_USER_KEYS } from "../tenancy/keys.ts";
import type { TenantRegistry } from "../tenancy/registry.ts";
import { loadUserConfig } from "../tenancy/user-config.ts";

/**
 * The admin API: how the owner's server-side tooling creates, changes and removes tenants. It is a separate Fastify app
 * on its own port, bound to 127.0.0.1 by `main.ts`, never published by compose and never proxied by Caddy. Every request
 * needs `Authorization: Bearer <ADMIN_TOKEN>`. Passphrases are accepted only as hashes made by `hash-passphrase`. Nothing
 * here logs or answers with a token, a passphrase or a setting's value.
 */
export interface AdminAppOptions {
  registry: TenantRegistry;
  /** The `shared` schema's database. */
  sharedDb: Db;
  /** The database of one schema (`t_<id>`); the admin closes the ones it opens. */
  schemaDb: (schema: string) => Db;
  /** The tenant migrations a new user's schema gets: a directory, or files already read. */
  migrations: Migration[] | URL;
  log: Logger;
  config: {
    /** The bearer token of the admin API. Required: an admin app without one would be open. */
    ADMIN_TOKEN: string | undefined;
    /** The environment the server started with, from which each user's configuration gets its installation keys. */
    installation: Readonly<Record<string, string | undefined>>;
  };
}

/** The owner is user 1; deleting them needs an extra word in `confirm`. */
const OWNER_ID = 1;
const ALLOW_OWNER = "allow-owner";
const TOKEN_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/;
const SECRET_KEY = /TOKEN|SECRET|KEY|PASSWORD/;
const STATUS = { invalid_request: 400, conflict: 409 } as const;

/** Same body shape as every other error of the service: `{ error: { code, message } }`. */
function fail(
  reply: FastifyReply,
  code: "invalid_request" | "conflict",
  message: string,
): FastifyReply {
  return reply.code(STATUS[code]).send({ error: { code, message } });
}

const settingValue = z.union([z.string(), z.number(), z.boolean(), z.null()]);

const createBody = z.object({
  handle: z.string(),
  passphraseHash: z.string().optional(),
  telegramChatId: z.union([z.string(), z.number()]).optional(),
  tokenName: z.string().optional(),
  settings: z.record(z.string(), settingValue).optional(),
});
const tokenBody = z.object({ kind: z.enum(["device", "mcp"]), name: z.string() });
const telegramBody = z.object({ chatId: z.union([z.string(), z.number(), z.null()]) });
const deleteBody = z.object({ confirm: z.string() });

const newToken = (): string => randomBytes(32).toString("base64url");

const digest = (value: string): Buffer => Buffer.from(sha256Hex(value), "hex");

/** A key name from a request, safe to echo: cut and stripped of anything but name characters. */
const safeName = (key: string): string => key.replace(/[^A-Za-z0-9_.-]/g, "?").slice(0, 64);

interface UserRow {
  id: string;
  handle: string;
  telegram_chat_id: string | null;
  disabled: boolean;
}

/** PostgreSQL's unique violation, as `pg` and PGlite both report it. */
const isUniqueViolation = (err: unknown): boolean =>
  typeof err === "object" && err !== null && (err as { code?: unknown }).code === "23505";

export function buildAdminApp(options: AdminAppOptions): FastifyInstance {
  const { registry, sharedDb, log, config } = options;
  if (config.ADMIN_TOKEN === undefined) throw new Error("The admin API needs an ADMIN_TOKEN");
  const adminDigest = digest(config.ADMIN_TOKEN);

  const app = Fastify({ logger: false, bodyLimit: 64 * 1024 });
  installErrorHandling(app);

  app.addHook("onRequest", async (request: FastifyRequest, reply: FastifyReply) => {
    const header = request.headers.authorization;
    const match = typeof header === "string" ? /^Bearer (\S+)$/.exec(header) : null;
    // Digests have the same length, so the comparison neither throws nor leaks how much of the token matched.
    const ok = match !== null && timingSafeEqual(digest(match[1] as string), adminDigest);
    if (!ok) return sendError(reply, "unauthorized", "A valid admin token is required");
    return undefined;
  });

  // ---- helpers ------------------------------------------------------------------------------------------------------

  async function readUser(id: number): Promise<UserRow | null> {
    const rows = await sharedDb.query<UserRow>(
      "SELECT id::text AS id, handle, telegram_chat_id, disabled_at IS NOT NULL AS disabled FROM users WHERE id = $1",
      [id],
    );
    return rows.rows[0] ?? null;
  }

  async function readSettings(id: number): Promise<Record<string, string>> {
    const rows = await sharedDb.query<{ key: string; value: string }>(
      "SELECT key, value FROM user_settings WHERE user_id = $1 ORDER BY key",
      [id],
    );
    return Object.fromEntries(rows.rows.map((r) => [r.key, r.value]));
  }

  /** The user from `:id`, or null after the answer to send instead has been sent. */
  async function userOf(request: FastifyRequest, reply: FastifyReply): Promise<UserRow | null> {
    const raw = (request.params as { id?: string }).id ?? "";
    if (!/^[1-9]\d{0,15}$/.test(raw)) {
      fail(reply, "invalid_request", "The user id must be a positive whole number");
      return null;
    }
    const user = await readUser(Number(raw));
    if (user === null) {
      sendError(reply, "not_found", "No such user");
      return null;
    }
    return user;
  }

  /**
   * Checks settings by building the tenant's configuration from them, the way the registry will. Returns the names of the
   * keys that are wrong (never their values), or null when the configuration is valid.
   */
  function invalidKeys(
    settings: Record<string, string>,
    telegramChatId: string | null,
  ): string[] | null {
    try {
      loadUserConfig({ installation: config.installation, settings, telegramChatId });
      return null;
    } catch (err) {
      if (err instanceof z.ZodError) {
        return [...new Set(err.issues.map((i) => String(i.path[0] ?? "configuration")))];
      }
      return ["configuration"];
    }
  }

  const tokenNameProblem = (name: string): string | null =>
    TOKEN_NAME.test(name)
      ? null
      : "The token name must be 1 to 32 characters: letters, digits, . _ -, starting with a letter or digit";

  const dropSchema = (db: Queryable, id: number): Promise<void> =>
    db.exec(`DROP SCHEMA IF EXISTS t_${Math.trunc(id)} CASCADE`);

  /** Reloads a user after a change was stored; a failure (a live coach session) is the answer of the request. */
  async function reloadOrConflict(
    user: UserRow,
    reply: FastifyReply,
    what: string,
  ): Promise<boolean> {
    try {
      await registry.reload(Number(user.id));
      return true;
    } catch (err) {
      fail(
        reply,
        "conflict",
        `${what} saved, but the user was not reloaded: ${err instanceof Error ? err.message : "reload failed"}`,
      );
      return false;
    }
  }

  const summary = (user: UserRow) => ({
    id: Number(user.id),
    handle: user.handle,
    state: registry.runtime(Number(user.id))?.state ?? "unhosted",
  });

  const logAction = (user: UserRow, action: string, extra: object = {}) =>
    log.info(
      { userId: Number(user.id), handle: user.handle, action, ...extra },
      `admin: ${action}`,
    );

  // ---- users --------------------------------------------------------------------------------------------------------

  app.post("/admin/users", async (request, reply) => {
    const body = createBody.safeParse(request.body);
    if (!body.success) {
      return fail(
        reply,
        "invalid_request",
        "Expected { handle, passphraseHash?, telegramChatId?, tokenName?, settings? }",
      );
    }
    const { handle, passphraseHash, tokenName = "device-1" } = body.data;
    if (!HANDLE.test(handle)) {
      return fail(
        reply,
        "invalid_request",
        "The handle must be 2 to 32 characters: a lowercase letter, then lowercase letters, digits, _ or -",
      );
    }
    const nameProblem = tokenNameProblem(tokenName);
    if (nameProblem !== null) return fail(reply, "invalid_request", nameProblem);
    if (passphraseHash !== undefined) {
      try {
        parsePassphraseHash(passphraseHash);
      } catch (err) {
        return fail(
          reply,
          "invalid_request",
          `passphraseHash ${err instanceof Error ? err.message : "is not valid"}`,
        );
      }
    }
    const chat = body.data.telegramChatId === undefined ? null : String(body.data.telegramChatId);
    const settings: Record<string, string> = {};
    for (const [key, value] of Object.entries(body.data.settings ?? {})) {
      if (!PER_USER_KEYS.includes(key)) {
        return fail(reply, "invalid_request", `${safeName(key)} is not a per-user setting`);
      }
      if (value !== null) settings[key] = String(value);
    }
    const bad = invalidKeys(settings, chat);
    if (bad !== null) return fail(reply, "invalid_request", `Invalid value for: ${bad.join(", ")}`);

    const used = "That handle or Telegram chat is already used by a user";
    const taken = await sharedDb.query(
      "SELECT 1 FROM users WHERE handle = $1 OR ($2::text IS NOT NULL AND telegram_chat_id = $2)",
      [handle, chat],
    );
    if (taken.rows.length > 0) return fail(reply, "conflict", used);

    const deviceToken = newToken();
    let id: number;
    try {
      id = await sharedDb.transaction(async (tx) => {
        const inserted = await tx.query<{ id: string }>(
          "INSERT INTO users (handle, passphrase_hash, telegram_chat_id) VALUES ($1, $2, $3) RETURNING id::text AS id",
          [handle, passphraseHash ?? null, chat],
        );
        const userId = Number(inserted.rows[0]?.id);
        await tx.query(
          "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, 'device', $2, $3)",
          [sha256Hex(deviceToken), tokenName, userId],
        );
        for (const [key, value] of Object.entries(settings)) {
          await tx.query("INSERT INTO user_settings (user_id, key, value) VALUES ($1, $2, $3)", [
            userId,
            key,
            value,
          ]);
        }
        return userId;
      });
    } catch (err) {
      if (isUniqueViolation(err)) return fail(reply, "conflict", used);
      throw err;
    }

    // The schema cannot be made in the same transaction: the tenant's pool is another connection and must see it. So the
    // row is committed first and taken back if anything below fails.
    try {
      await sharedDb.exec(`CREATE SCHEMA t_${id}`);
      const tenantDb = options.schemaDb(`t_${id}`);
      try {
        await migrate(tenantDb, options.migrations);
      } finally {
        await tenantDb.close().catch(() => {});
      }
      await registry.add(id);
    } catch (err) {
      log.error(
        { userId: id, handle, action: "create-user" },
        "admin: creating the user failed, undone",
      );
      await registry.remove(id).catch(() => {});
      await dropSchema(sharedDb, id).catch(() =>
        log.error({ userId: id }, "admin: could not drop the schema of the failed user"),
      );
      await sharedDb
        .query("DELETE FROM users WHERE id = $1", [id])
        .catch(() =>
          log.error({ userId: id }, "admin: could not delete the row of the failed user"),
        );
      throw err;
    }
    log.info({ userId: id, handle, action: "create-user" }, "admin: create-user");
    return reply
      .code(201)
      .send({ id, handle, deviceToken, state: registry.runtime(id)?.state ?? "unhosted" });
  });

  app.get("/admin/users", async () => {
    const users = await sharedDb.query<UserRow>(
      "SELECT id::text AS id, handle, telegram_chat_id, disabled_at IS NOT NULL AS disabled FROM users ORDER BY id",
    );
    const tokens = await sharedDb.query<{
      user_id: string;
      name: string;
      kind: string;
      revoked: boolean;
    }>(
      "SELECT user_id::text AS user_id, name, kind, revoked_at IS NOT NULL AS revoked FROM user_tokens ORDER BY created_at, name",
    );
    return {
      users: users.rows.map((u) => ({
        ...summary(u),
        telegramChatId: u.telegram_chat_id,
        disabled: u.disabled,
        tokens: tokens.rows
          .filter((t) => t.user_id === u.id)
          .map((t) => ({ name: t.name, kind: t.kind, revoked: t.revoked })),
      })),
    };
  });

  // ---- tokens -------------------------------------------------------------------------------------------------------

  app.post("/admin/users/:id/tokens", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const body = tokenBody.safeParse(request.body);
    if (!body.success) {
      return fail(reply, "invalid_request", "Expected { kind: 'device' | 'mcp', name }");
    }
    const { kind, name } = body.data;
    const problem = tokenNameProblem(name);
    if (problem !== null) return fail(reply, "invalid_request", problem);
    const existing = await sharedDb.query(
      "SELECT 1 FROM user_tokens WHERE user_id = $1 AND name = $2",
      [user.id, name],
    );
    if (existing.rows.length > 0) {
      return fail(
        reply,
        "conflict",
        "The user already has a token with that name (revoked ones count)",
      );
    }
    const token = newToken();
    await sharedDb.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, $2, $3, $4)",
      [sha256Hex(token), kind, name, user.id],
    );
    await registry.refreshTokens();
    logAction(user, "create-token", { kind, name });
    return reply.code(201).send({ name, kind, token });
  });

  app.delete("/admin/users/:id/tokens/:name", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const name = (request.params as { name: string }).name;
    const revoked = await sharedDb.query(
      "UPDATE user_tokens SET revoked_at = now() WHERE user_id = $1 AND name = $2 AND revoked_at IS NULL RETURNING name",
      [user.id, name],
    );
    if (revoked.rows.length === 0) return sendError(reply, "not_found", "No such active token");
    await registry.refreshTokens();
    logAction(user, "revoke-token", { name: safeName(name) });
    return reply.code(204).send();
  });

  // ---- settings -----------------------------------------------------------------------------------------------------

  app.get("/admin/users/:id/settings", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const settings = await readSettings(Number(user.id));
    return {
      settings: Object.fromEntries(
        Object.entries(settings).map(([key, value]) => [key, SECRET_KEY.test(key) ? "***" : value]),
      ),
    };
  });

  app.put("/admin/users/:id/settings", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const body = z.record(z.string(), settingValue).safeParse(request.body);
    if (!body.success) {
      return fail(
        reply,
        "invalid_request",
        "Expected an object of setting names to values (null removes one)",
      );
    }
    const changes = Object.entries(body.data);
    if (changes.length === 0) return fail(reply, "invalid_request", "No settings given");
    for (const [key] of changes) {
      if (!PER_USER_KEYS.includes(key)) {
        return fail(reply, "invalid_request", `${safeName(key)} is not a per-user setting`);
      }
    }
    const next = await readSettings(Number(user.id));
    for (const [key, value] of changes) {
      if (value === null) delete next[key];
      else next[key] = String(value);
    }
    const bad = invalidKeys(next, user.telegram_chat_id);
    if (bad !== null) return fail(reply, "invalid_request", `Invalid value for: ${bad.join(", ")}`);
    await sharedDb.transaction(async (tx) => {
      for (const [key, value] of changes) {
        if (value === null) {
          await tx.query("DELETE FROM user_settings WHERE user_id = $1 AND key = $2", [
            user.id,
            key,
          ]);
        } else {
          await tx.query(
            "INSERT INTO user_settings (user_id, key, value) VALUES ($1, $2, $3) ON CONFLICT (user_id, key) DO UPDATE SET value = EXCLUDED.value",
            [user.id, key, String(value)],
          );
        }
      }
    });
    logAction(user, "set-settings", { keys: changes.map(([key]) => key) });
    if (!(await reloadOrConflict(user, reply, "Settings"))) return reply;
    return summary(user);
  });

  // ---- telegram, disable, enable, reload ---------------------------------------------------------------------------

  app.put("/admin/users/:id/telegram", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const body = telegramBody.safeParse(request.body);
    if (!body.success) {
      return fail(
        reply,
        "invalid_request",
        "Expected { chatId } (a whole number, or null to unset)",
      );
    }
    const chat = body.data.chatId === null ? null : String(body.data.chatId);
    if (invalidKeys(await readSettings(Number(user.id)), chat) !== null) {
      return fail(reply, "invalid_request", "chatId must be a whole number such as 123456789");
    }
    try {
      await sharedDb.query("UPDATE users SET telegram_chat_id = $1 WHERE id = $2", [chat, user.id]);
    } catch (err) {
      if (isUniqueViolation(err)) {
        return fail(reply, "conflict", "That Telegram chat belongs to another user");
      }
      throw err;
    }
    logAction(user, "set-telegram");
    if (!(await reloadOrConflict(user, reply, "The chat"))) return reply;
    return summary(user);
  });

  for (const action of ["disable", "enable"] as const) {
    app.post(`/admin/users/:id/${action}`, async (request, reply) => {
      const user = await userOf(request, reply);
      if (user === null) return reply;
      await sharedDb.query(
        `UPDATE users SET disabled_at = ${action === "disable" ? "COALESCE(disabled_at, now())" : "NULL"} WHERE id = $1`,
        [user.id],
      );
      // Stops the user's services and drops their tokens (disable), or builds them again (enable).
      await registry.refreshTokens();
      logAction(user, action);
      return summary(user);
    });
  }

  app.post("/admin/users/:id/reload", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    try {
      await registry.reload(Number(user.id));
    } catch (err) {
      return fail(reply, "conflict", err instanceof Error ? err.message : "reload failed");
    }
    logAction(user, "reload");
    return summary(user);
  });

  // ---- delete -------------------------------------------------------------------------------------------------------

  app.delete("/admin/users/:id", async (request, reply) => {
    const user = await userOf(request, reply);
    if (user === null) return reply;
    const body = deleteBody.safeParse(request.body);
    if (!body.success) return fail(reply, "invalid_request", "Expected { confirm: '<handle>' }");
    const id = Number(user.id);
    const confirm = body.data.confirm.trim();
    const ownerConfirm = `${user.handle} ${ALLOW_OWNER}`;
    if (confirm !== user.handle && !(id === OWNER_ID && confirm === ownerConfirm)) {
      return fail(reply, "invalid_request", "confirm must be the user's handle exactly");
    }
    if (id === OWNER_ID && confirm !== ownerConfirm) {
      return fail(
        reply,
        "conflict",
        `User ${OWNER_ID} is the owner: confirm must be "<handle> ${ALLOW_OWNER}" to delete them`,
      );
    }
    await registry.remove(id);
    // One transaction: the schema and every row of the user go together or not at all.
    await sharedDb.transaction(async (tx) => {
      // The OAuth tokens and codes name a family, not a user: they are not reached by the cascade.
      await tx.query(
        "DELETE FROM oauth_tokens WHERE family_id IN (SELECT family_id FROM oauth_families WHERE user_id = $1)",
        [id],
      );
      await tx.query(
        "DELETE FROM oauth_codes WHERE family_id IN (SELECT family_id FROM oauth_families WHERE user_id = $1)",
        [id],
      );
      await dropSchema(tx, id);
      // user_tokens, user_settings and oauth_families go with the row (ON DELETE CASCADE).
      await tx.query("DELETE FROM users WHERE id = $1", [id]);
    });
    logAction(user, "delete-user");
    return reply.code(204).send();
  });

  return app;
}
