import type { FastifyInstance } from "fastify";
import { buildAdminApp } from "./admin/app.ts";
import { buildApp } from "./app.ts";
import { type Config, loadConfig } from "./config.ts";
import { DEFAULT_TELEGRAM_BASE_URL } from "./config-integrations.ts";
import { createPgDb, createPgSchemaDb, type Db } from "./db.ts";
import { consoleLogger, type Logger } from "./log.ts";
import { MIGRATIONS_DIR } from "./migrate.ts";
import { sharedLoginUsers } from "./oauth/users.ts";
import { createServices, type Services } from "./services.ts";
import { createTelegramClient, type TelegramClient } from "./telegram/client.ts";
import { COMMANDS } from "./telegram/index.ts";
import { createTelegramPoller, type TelegramPoller } from "./telegram/poller.ts";
import { type BootstrapReport, type Env, runBootstrap } from "./tenancy/bootstrap.ts";
import {
  createTenantRegistry,
  createTenantServices,
  type TenantRegistry,
  type TenantServicesInput,
} from "./tenancy/registry.ts";

/** The databases the server runs on: a root connection and a factory of per-schema connections (`shared`, `t_<id>`). */
export interface Databases {
  root: Db;
  schemaDb: (schema: string) => Db;
}

export interface StartOptions {
  /** The environment: `process.env` in production. */
  env: Env;
  /** Where start-up lines go; default: JSON lines on stdout at `LOG_LEVEL`. */
  log?: Logger;
  /** The connections to use. Default: PostgreSQL pools on `DATABASE_URL`, which the server then also closes. Whoever passes them owns them. */
  databases?: Databases | undefined;
  /** Whether the tenants' background jobs run (default true). */
  startJobs?: boolean;
  /** The pause between one tenant's jobs starting and the next's (the registry's default when unset). */
  startGapMs?: number;
}

/** `MIGRATE_ONLY=1`: the database was brought to the layout and nothing else was started. */
export interface Migrated {
  mode: "migrate-only";
  report: BootstrapReport;
  close(): Promise<void>;
}

export interface Serving {
  mode: "serving";
  report: BootstrapReport;
  app: FastifyInstance;
  registry: TenantRegistry;
  /** The admin API's listener; null when `ADMIN_TOKEN` is not set. */
  admin: FastifyInstance | null;
  /** The shared bot's poller; null when `TELEGRAM_BOT_TOKEN` is not set. */
  poller: TelegramPoller | null;
  /** Stops in this order: the admin listener, the poller, the app, the registry, then the shared and root pools. */
  close(): Promise<void>;
}

const isOn = (value: string | undefined): boolean =>
  value === "1" || value?.toLowerCase() === "true";

/** The text of an error for the log: its name and message only (a driver error's details may hold a connection string). */
export const messageOf = (err: unknown): string =>
  err instanceof Error ? `${err.name}: ${err.message}` : "an unexpected error";

/**
 * How a tenant's services get their Telegram bot. The registry builds tenants with no bot; with a bot token the server owns
 * one client and one poller, and each tenant gets a bot made with `poll: false` that shares the client: it answers and
 * sends for its own chat, and only the poller reads updates (so Telegram sees a single `getUpdates` consumer).
 */
function tenantServicesWith(
  client: TelegramClient | null,
  sharedDb: Db,
): (input: TenantServicesInput) => Services {
  if (client === null) return createTenantServices;
  return (input) =>
    createServices({
      config: input.config,
      db: input.db,
      log: input.log,
      sharedDb,
      telegram: true,
      telegramPoll: false,
      telegramClient: client,
    });
}

/**
 * Starts the multi-tenant server, in this order: bootstrap (the one-time move of a single-user database, or an empty
 * database's first owner, then every pending migration), then either the report and a return (`MIGRATE_ONLY=1`) or the
 * registry, the HTTP app, the shared Telegram poller, the admin API and the tenants' jobs. Throws when the bootstrap refuses
 * or a listener cannot bind; whatever was opened by then is closed first.
 */
export async function startServer(options: StartOptions): Promise<Migrated | Serving> {
  const { env } = options;
  const config: Config = loadConfig(env);
  const log = options.log ?? consoleLogger(config.LOG_LEVEL);

  let owned = false;
  let databases = options.databases;
  if (databases === undefined) {
    const url = config.DATABASE_URL;
    databases = {
      root: createPgDb(url, (err) => log.error({ err }, "database connection error")),
      schemaDb: (schema) => createPgSchemaDb(url, schema),
    };
    owned = true;
  }
  const { root, schemaDb } = databases;
  // Pools this function opened and has not handed to anything that closes them.
  const closers: (() => Promise<void>)[] = [];
  const closeRoot = async (): Promise<void> => {
    if (owned) await root.close();
  };

  try {
    // 1-2. The migrations run on short-lived pools, which are closed with the bootstrap.
    const scratch: Db[] = [];
    let report: BootstrapReport;
    try {
      report = await runBootstrap({
        db: root,
        env,
        log,
        schemaDb: (schema) => {
          const db = schemaDb(schema);
          scratch.push(db);
          return db;
        },
      });
    } finally {
      if (owned) await Promise.all(scratch.map((db) => db.close().catch(() => {})));
    }

    // 3. These keys now belong to the users' rows; whatever is left in the environment is ignored.
    if (report.perUserKeysStillInEnv.length > 0) {
      log.warn(
        { keys: report.perUserKeysStillInEnv },
        "per-user settings are still set in the server environment and are ignored: remove them from .env (they live in each user's settings now)",
      );
    }

    // 4. Only the migrations: no listener, no jobs, no bot.
    if (isOn(env.MIGRATE_ONLY)) {
      return { mode: "migrate-only", report, close: closeRoot };
    }

    // 5. The tenants.
    const sharedDb = schemaDb("shared");
    if (owned) closers.push(() => sharedDb.close());
    let telegramClient: TelegramClient | null = null;
    if (config.TELEGRAM_BOT_TOKEN !== undefined) {
      telegramClient = createTelegramClient({
        baseUrl: config.TELEGRAM_BASE_URL ?? DEFAULT_TELEGRAM_BASE_URL,
        token: config.TELEGRAM_BOT_TOKEN,
        log,
      });
    } else {
      log.info(
        {},
        "Telegram is off: TELEGRAM_BOT_TOKEN is not set, so there is no bot and no reminders",
      );
    }
    const registry = await createTenantRegistry({
      sharedDb,
      schemaDb,
      installation: env,
      log,
      createServices: tenantServicesWith(telegramClient, sharedDb),
      startJobs: options.startJobs ?? true,
      ...(options.startGapMs === undefined ? {} : { startGapMs: options.startGapMs }),
    });

    // 6. The HTTP app.
    const app = buildApp({
      config,
      tenants: registry,
      sharedDb,
      mcpTenancy: {
        shared: sharedDb,
        directory: registry,
        servicesOf: (userId) => registry.runtime(userId)?.services ?? null,
        users: sharedLoginUsers(sharedDb),
      },
    });
    let poller: TelegramPoller | null = null;
    let admin: FastifyInstance | null = null;
    let adminListening = false;
    const close = async (): Promise<void> => {
      // In this order. A step that fails does not stop the rest.
      const steps: (() => Promise<unknown>)[] = [
        async () => (adminListening ? admin?.close() : undefined),
        async () => poller?.stop(),
        () => app.close(),
        () => registry.close(),
        ...closers,
        closeRoot,
      ];
      for (const step of steps) {
        await step().catch((err: unknown) => log.error({ err }, "shutdown step failed"));
      }
    };
    try {
      await app.listen({ host: config.HOST, port: config.PORT });

      // 7. One poller for the one bot: it hands each update to the bot of the user whose chat it came from.
      if (telegramClient !== null) {
        poller = createTelegramPoller({
          client: telegramClient,
          shared: sharedDb,
          directory: registry,
          botOf: (userId) => registry.runtime(userId)?.services?.telegram ?? null,
          log,
          commandList: COMMANDS,
        });
        poller.start();
      }

      // 8. The admin API listens on loopback only, never on HOST.
      if (config.ADMIN_TOKEN === undefined) {
        log.warn(
          {},
          "ADMIN_TOKEN is not set: the admin API is off, so users cannot be created, changed or removed",
        );
      } else {
        admin = buildAdminApp({
          registry,
          sharedDb,
          schemaDb,
          migrations: MIGRATIONS_DIR,
          log,
          config: { ADMIN_TOKEN: config.ADMIN_TOKEN, installation: env },
        });
        await admin.listen({ host: "127.0.0.1", port: config.ADMIN_PORT });
        adminListening = true;
      }

      // 9. The jobs start a couple of seconds apart per tenant: not awaited, the server is already answering.
      void registry.start().catch((err: unknown) => log.error({ err }, "starting the jobs failed"));
    } catch (err) {
      await close();
      throw err;
    }
    return { mode: "serving", report, app, registry, admin, poller, close };
  } catch (err) {
    for (const close of [...closers, closeRoot]) await close().catch(() => {});
    throw err;
  }
}
