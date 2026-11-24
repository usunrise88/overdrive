import type { FastifyInstance } from "fastify";
import type { Config, DeviceToken } from "../config.ts";
import type { Logger } from "../log.ts";
import { type PassphraseVerifier, serialVerifier } from "../oauth/passphrase.ts";
import { oauthRoutes } from "../oauth/routes.ts";
import { createOAuthStore, DEFAULT_LIFETIMES, type Lifetimes } from "../oauth/store.ts";
import {
  createHandleLockouts,
  createRateLimiter,
  DEFAULT_GLOBAL_FAILURE_CAP,
  DEFAULT_LOCKOUT,
  type GlobalFailureCap,
  type LockoutPolicy,
} from "../oauth/throttle.ts";
import { mcpRoutes } from "./routes.ts";
import type { McpTenancy } from "./tenancy.ts";

export type { McpTenancy } from "./tenancy.ts";
export { singleTenancy } from "./tenancy.ts";

/** The part of the configuration the MCP server reads (ADR 0018). */
export type McpConfig = Partial<
  Pick<Config, "PUBLIC_BASE_URL" | "OWNER_PASSPHRASE_HASH" | "MCP_STATIC_TOKENS">
>;

export interface McpSettings {
  baseUrl: string;
  staticTokens: DeviceToken[];
}

/**
 * The settings when the MCP server is on, or the reason it is off. It needs only `PUBLIC_BASE_URL`: the passphrase
 * is each user's own (`shared.users.passphrase_hash`).
 */
export function mcpSettings(config: McpConfig): { on: McpSettings } | { off: string } {
  if (config.PUBLIC_BASE_URL === undefined) return { off: "PUBLIC_BASE_URL is not set" };
  return {
    on: {
      baseUrl: config.PUBLIC_BASE_URL,
      staticTokens: config.MCP_STATIC_TOKENS ?? [],
    },
  };
}

/** What the tests change: the clock, the lifetimes, how fast the page locks. */
export interface McpTestOptions {
  now?: () => Date;
  lifetimes?: Partial<Lifetimes>;
  lockout?: Partial<LockoutPolicy>;
  /** The cap on wrong passphrases over all handles. */
  globalFailures?: Partial<GlobalFailureCap>;
  /** Replaces the scrypt check (a test counts the runs); it is still run one at a time. */
  verifier?: PassphraseVerifier;
}

export interface McpModuleOptions {
  settings: McpSettings;
  tenancy: McpTenancy;
  log: Logger;
  test?: McpTestOptions;
}

/**
 * The MCP server and the OAuth server in front of it: one plugin, mounted at the root of the
 * service. Everything it needs from the rest of the service comes through `tenancy`: the OAuth tables are in
 * `shared`, and each request acts on the services of the user its token belongs to.
 */
export function mcpModule(app: FastifyInstance, options: McpModuleOptions): void {
  const { settings, tenancy, log } = options;
  const now = options.test?.now ?? (() => new Date());
  const store = createOAuthStore(tenancy.shared, {
    ...DEFAULT_LIFETIMES,
    ...options.test?.lifetimes,
  });

  app.register(oauthRoutes, {
    baseUrl: settings.baseUrl,
    store,
    users: tenancy.users,
    verifyPassphrase: serialVerifier(options.test?.verifier),
    lockout: createHandleLockouts(
      { ...DEFAULT_LOCKOUT, ...options.test?.lockout },
      { ...DEFAULT_GLOBAL_FAILURE_CAP, ...options.test?.globalFailures },
    ),
    // Thirty registrations an hour is far more than a few people connecting a few apps need.
    registrations: createRateLimiter(30, 3_600_000),
    now,
    log,
  });
  app.register(mcpRoutes, {
    baseUrl: settings.baseUrl,
    store,
    directory: tenancy.directory,
    servicesOf: tenancy.servicesOf,
    now,
    log,
  });
}
