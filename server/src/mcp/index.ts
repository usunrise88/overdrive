import type { FastifyInstance } from "fastify";
import type { Config, DeviceToken } from "../config.ts";
import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import { type PassphraseHash, parsePassphraseHash, serialVerifier } from "../oauth/passphrase.ts";
import { oauthRoutes } from "../oauth/routes.ts";
import { createOAuthStore, DEFAULT_LIFETIMES, type Lifetimes } from "../oauth/store.ts";
import {
  createLoginLockout,
  createRateLimiter,
  DEFAULT_LOCKOUT,
  type LockoutPolicy,
} from "../oauth/throttle.ts";
import type { Services } from "../services.ts";
import { mcpRoutes } from "./routes.ts";

/** The part of the configuration the MCP server reads (ADR 0018). */
export type McpConfig = Partial<
  Pick<Config, "PUBLIC_BASE_URL" | "OWNER_PASSPHRASE_HASH" | "MCP_STATIC_TOKENS">
>;

export interface McpSettings {
  baseUrl: string;
  passphrase: PassphraseHash;
  staticTokens: DeviceToken[];
}

/** The settings when the MCP server is on, or the reason it is off. */
export function mcpSettings(config: McpConfig): { on: McpSettings } | { off: string } {
  const missing: string[] = [];
  if (config.PUBLIC_BASE_URL === undefined) missing.push("PUBLIC_BASE_URL");
  if (config.OWNER_PASSPHRASE_HASH === undefined) missing.push("OWNER_PASSPHRASE_HASH");
  if (config.PUBLIC_BASE_URL === undefined || config.OWNER_PASSPHRASE_HASH === undefined) {
    return {
      off: `${missing.join(" and ")} ${missing.length === 1 ? "is" : "are"} not set`,
    };
  }
  return {
    on: {
      baseUrl: config.PUBLIC_BASE_URL,
      passphrase: parsePassphraseHash(config.OWNER_PASSPHRASE_HASH),
      staticTokens: config.MCP_STATIC_TOKENS ?? [],
    },
  };
}

/** What the tests change: the clock, the lifetimes, how fast the page locks. */
export interface McpTestOptions {
  now?: () => Date;
  lifetimes?: Partial<Lifetimes>;
  lockout?: Partial<LockoutPolicy>;
}

export interface McpModuleOptions {
  settings: McpSettings;
  services: Services;
  db: Db;
  log: Logger;
  test?: McpTestOptions;
}

/**
 * The MCP server and the OAuth server in front of it: one plugin, mounted at the root of the
 * service. Everything it needs from the rest of the service comes through `services`.
 */
export function mcpModule(app: FastifyInstance, options: McpModuleOptions): void {
  const { settings, services, db, log } = options;
  const now = options.test?.now ?? (() => new Date());
  const store = createOAuthStore(db, { ...DEFAULT_LIFETIMES, ...options.test?.lifetimes });

  app.register(oauthRoutes, {
    baseUrl: settings.baseUrl,
    store,
    verifyPassphrase: serialVerifier(settings.passphrase),
    lockout: createLoginLockout({ ...DEFAULT_LOCKOUT, ...options.test?.lockout }),
    // Thirty registrations an hour is far more than one owner connecting a few apps needs.
    registrations: createRateLimiter(30, 3_600_000),
    now,
    log,
  });
  app.register(mcpRoutes, {
    baseUrl: settings.baseUrl,
    store,
    staticTokens: settings.staticTokens,
    tools: { services, now, log },
  });
}
