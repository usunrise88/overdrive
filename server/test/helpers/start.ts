import { randomBytes } from "node:crypto";
import type { Db } from "../../src/db.ts";
import { type Serving, type StartOptions, startServer } from "../../src/start.ts";
import type { Env } from "../../src/tenancy/bootstrap.ts";
import type { TenancyTestDb } from "./db.ts";
import { freePort } from "./mcp.ts";
import { collectingLogger } from "./support.ts";

/** What a test of the whole start-up needs: random secrets and free ports, made per run. */
export interface StartEnv {
  env: Record<string, string>;
  adminToken: string;
  deviceToken: string;
  port: number;
  adminPort: number;
  baseUrl: string;
  adminUrl: string;
}

export async function startEnv(extra: Record<string, string | undefined> = {}): Promise<StartEnv> {
  const [port, adminPort] = [await freePort(), await freePort()];
  const adminToken = randomBytes(36).toString("base64url");
  const deviceToken = randomBytes(32).toString("base64url");
  const env: Record<string, string> = {
    DATABASE_URL: "postgres://user:password@localhost:5432/db",
    LOG_LEVEL: "silent",
    HOST: "127.0.0.1",
    PORT: String(port),
    ADMIN_PORT: String(adminPort),
    ADMIN_TOKEN: adminToken,
    DEVICE_TOKENS: `tablet:${deviceToken}`,
    PUBLIC_BASE_URL: `http://127.0.0.1:${port}`,
  };
  for (const [key, value] of Object.entries(extra)) {
    if (value === undefined) delete env[key];
    else env[key] = value;
  }
  return {
    env,
    adminToken,
    deviceToken,
    port,
    adminPort,
    baseUrl: `http://127.0.0.1:${port}`,
    adminUrl: `http://127.0.0.1:${adminPort}`,
  };
}

/** The test database's connections as `startServer` takes them: the pools are the test's, so closing one is a no-op. */
export function databasesOf(t: TenancyTestDb): StartOptions["databases"] {
  const keep = (db: Db): Db => ({ ...db, close: async () => {} });
  return { root: t.db, schemaDb: (schema) => keep(t.schemaDb(schema)) };
}

export interface Started {
  server: Serving;
  logging: ReturnType<typeof collectingLogger>;
  stop(): Promise<void>;
}

/** Starts the server on the test database with jobs off; it must be `serving`. */
export async function startOn(
  t: TenancyTestDb,
  config: StartEnv,
  options: Partial<StartOptions> = {},
): Promise<Started> {
  const logging = collectingLogger();
  const server = await startServer({
    env: config.env as Env,
    log: logging.log,
    databases: databasesOf(t),
    startJobs: false,
    ...options,
  });
  if (server.mode !== "serving") throw new Error("expected a serving server");
  return { server, logging, stop: () => server.close() };
}
