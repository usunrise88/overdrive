import Fastify, { type FastifyInstance } from "fastify";
import { tenantAuth } from "./auth.ts";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { effortRoutes } from "./effort/routes.ts";
import { installErrorHandling, sendError } from "./errors.ts";
import { insightsRoutes } from "./insights/routes.ts";
import { liveRoutes } from "./live-coach/routes.ts";
import { type McpConfig, type McpTestOptions, mcpModule, mcpSettings } from "./mcp/index.ts";
import { metricsRoutes } from "./metrics/routes.ts";
import { notebookRoutes } from "./notebook/routes.ts";
import { outdoorRoutes } from "./outdoor/routes.ts";
import { thresholdHeartRate } from "./plan/heart-rate.ts";
import { planRoutes } from "./plan/routes.ts";
import { planEventsRoutes } from "./plan-events/routes.ts";
import { powerSkillsRoutes } from "./power-skills/routes.ts";
import { profileRoutes } from "./profile/routes.ts";
import { progressRoutes } from "./progress/routes.ts";
import { repeatsRoutes } from "./repeats/routes.ts";
import { DEFAULT_LIMITS, type Limits, ridesRoutes } from "./rides.ts";
import { routesRest } from "./routes/rest.ts";
import { createServices, type Services, type ServicesConfig } from "./services.ts";
import type { Accessor, TenantContext, TenantRuntimeDirectory } from "./tenancy/context.ts";
import { SINGLE_TENANT_ID, singleTenantDirectory } from "./tenancy/single.ts";
import { wellnessRoutes } from "./wellness/routes.ts";

declare module "fastify" {
  interface FastifyInstance {
    /** The event bus, the plan store and the integrations of the owner tenant (the only one on the single-tenant path); see `services.ts`. */
    services: Services;
  }
}

export interface AppOptions {
  config: Pick<Config, "LOG_LEVEL" | "DEVICE_TOKENS"> & ServicesConfig & McpConfig;
  /** The single-tenant path: the database of the one user (the owner). Not needed with `tenants`. */
  db?: Db;
  /**
   * The multi-tenant path: the users the service hosts (see `tenancy/registry.ts`). The caller owns it: it starts and closes
   * it, the app does not. Until MCP is multi-tenant (stage B2) MCP serves the owner tenant, user 1.
   */
  tenants?: TenantRuntimeDirectory;
  /** Body size limits; the defaults are the API's (ADR 0014). Tests shrink them. */
  limits?: Partial<Limits>;
  /** Where the log lines go instead of stdout. For tests. */
  logStream?: { write(line: string): void };
  /**
   * The integrations. By default they are built from `config` and the app's logger; the tests pass
   * their own (a fake network, a fake clock). The app does not start their background loops:
   * `app.services.start()` does, and closing the app stops them.
   */
  services?: Services;
  /** For the tests: the clock and the limits of the OAuth server (ADR 0018). */
  mcp?: McpTestOptions;
}

/**
 * The web service: `/healthz` is open, everything under `/v1` needs a device's bearer token.
 * Secrets never reach the log: the `authorization` header is redacted wherever it is logged.
 */
export function buildApp(options: AppOptions): FastifyInstance {
  const { config } = options;
  const limits: Limits = { ...DEFAULT_LIMITS, ...options.limits };

  const app = Fastify({
    logger: {
      level: config.LOG_LEVEL,
      redact: {
        paths: [
          "req.headers.authorization",
          "request.headers.authorization",
          "headers.authorization",
        ],
        censor: "[redacted]",
      },
      ...(options.logStream === undefined ? {} : { stream: options.logStream }),
    },
    // Ride ids are up to 128 characters; longer ones should reach the handler and be refused there.
    routerOptions: { maxParamLength: 256 },
  });

  // One tenant (the owner) built from `config` and `db`, or the registry's tenants. MCP serves the owner either way.
  let tenants: TenantRuntimeDirectory;
  let services: Services | null;
  let db: Db | null;
  if (options.tenants !== undefined) {
    tenants = options.tenants;
    const owner = tenants.runtime(SINGLE_TENANT_ID);
    services = owner?.services ?? null;
    db = owner?.db ?? null;
  } else {
    if (options.db === undefined) throw new Error("buildApp needs `db` or `tenants`");
    db = options.db;
    services = options.services ?? createServices({ config, db, log: app.log });
    tenants = singleTenantDirectory({ devices: config.DEVICE_TOKENS, db, services });
    const own = services;
    app.addHook("onClose", () => own.stop());
  }
  // The first tenant's services stay reachable for the code that is not tenant-aware yet.
  if (services !== null) app.decorate("services", services);

  installErrorHandling(app);
  app.get("/healthz", () => ({ status: "ok" }));

  if (options.tenants === undefined && config.DEVICE_TOKENS.length === 0) {
    app.log.warn("DEVICE_TOKENS is empty: every /v1 request will be answered 401");
  }

  app.register(
    async (v1) => {
      v1.decorateRequest("device", null);
      v1.decorateRequest("tenant", null as never);
      v1.addHook("onRequest", tenantAuth(tenants));
      // Inside /v1 an unknown path is answered after authentication like every other request.
      v1.setNotFoundHandler((_request, reply) => sendError(reply, "not_found", "No such endpoint"));
      // Every route module takes what it works on from the request's tenant (set by `tenantAuth`), never from the startup.
      const of =
        <T>(pick: (tenant: TenantContext) => T): Accessor<T> =>
        (request) =>
          pick(request.tenant);
      v1.get("/me", async (request, reply) => {
        const { userId, handle, device } = request.tenant;
        reply.header("cache-control", "no-store");
        return { userId, handle, device };
      });
      v1.register(ridesRoutes, {
        db: of((t) => t.db),
        limits,
        events: of((t) => t.services.bus),
        destinations: of((t) => t.services.destinations),
      });
      v1.register(planRoutes, {
        store: of((t) => t.services.plan),
        refresher: of((t) => t.services.refresher),
        thresholdHeartRate: of(async (t) => thresholdHeartRate(await t.services.profile.get())),
      });
      v1.register(profileRoutes, { store: of((t) => t.services.profile) });
      v1.register(metricsRoutes, { metrics: of((t) => t.services.metrics) });
      v1.register(wellnessRoutes, { service: of((t) => t.services.wellness) });
      v1.register(outdoorRoutes, { service: of((t) => t.services.outdoor) });
      v1.register(repeatsRoutes, { service: of((t) => t.services.repeats) });
      v1.register(powerSkillsRoutes, { service: of((t) => t.services.powerSkills) });
      v1.register(effortRoutes, { service: of((t) => t.services.effort) });
      v1.register(progressRoutes, { service: of((t) => t.services.progress) });
      v1.register(insightsRoutes, { insights: of((t) => t.services.insights) });
      v1.register(notebookRoutes, { service: of((t) => t.services.notebook) });
      v1.register(planEventsRoutes, { service: of((t) => t.services.planEvents) });
      v1.register(routesRest, { service: of((t) => t.services.routes) });
      v1.register(liveRoutes, {
        service: of((t) => t.services.liveCoach),
        stopAll: async () => {
          await Promise.all(tenants.runtimes().map((r) => r.services?.liveCoach.stop()));
        },
      });
    },
    { prefix: "/v1" },
  );

  // The MCP server and its OAuth server (ADR 0018) exist only when they are configured; otherwise their
  // paths are plain 404s.
  const mcp = mcpSettings(config);
  if ("off" in mcp) {
    app.log.info(`MCP server is off: ${mcp.off} (see docs/deploy.md to turn it on)`);
  } else if (services === null || db === null) {
    app.log.warn("MCP server is off: the owner tenant (user 1) is not ready");
  } else {
    app.register(mcpModule, {
      settings: mcp.on,
      services,
      db,
      log: app.log,
      ...(options.mcp === undefined ? {} : { test: options.mcp }),
    });
    app.log.info(
      { endpoint: `${mcp.on.baseUrl}/mcp`, staticTokens: mcp.on.staticTokens.length },
      "MCP server is on",
    );
  }

  return app;
}
