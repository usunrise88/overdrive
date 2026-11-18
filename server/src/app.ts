import Fastify, { type FastifyInstance } from "fastify";
import { bearerAuth } from "./auth.ts";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { installErrorHandling, sendError } from "./errors.ts";
import { planRoutes } from "./plan/routes.ts";
import { DEFAULT_LIMITS, type Limits, ridesRoutes } from "./rides.ts";
import { createServices, type Services, type ServicesConfig } from "./services.ts";

declare module "fastify" {
  interface FastifyInstance {
    /** The event bus, the plan store and the integrations; see `services.ts`. */
    services: Services;
  }
}

export interface AppOptions {
  config: Pick<Config, "LOG_LEVEL" | "DEVICE_TOKENS"> & ServicesConfig;
  db: Db;
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
}

/**
 * The web service: `/healthz` is open, everything under `/v1` needs a device's bearer token.
 * Secrets never reach the log: the `authorization` header is redacted wherever it is logged.
 */
export function buildApp(options: AppOptions): FastifyInstance {
  const { config, db } = options;
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

  const services = options.services ?? createServices({ config, db, log: app.log });
  app.decorate("services", services);
  app.addHook("onClose", () => services.stop());

  installErrorHandling(app);
  app.get("/healthz", () => ({ status: "ok" }));

  if (config.DEVICE_TOKENS.length === 0) {
    app.log.warn("DEVICE_TOKENS is empty: every /v1 request will be answered 401");
  }

  app.register(
    async (v1) => {
      v1.decorateRequest("device", null);
      v1.addHook("onRequest", bearerAuth(config.DEVICE_TOKENS));
      // Inside /v1 an unknown path is answered after authentication like every other request.
      v1.setNotFoundHandler((_request, reply) => sendError(reply, "not_found", "No such endpoint"));
      v1.register(ridesRoutes, {
        db,
        limits,
        events: services.bus,
        destinations: services.destinations,
      });
      v1.register(planRoutes, { store: services.plan, refresher: services.refresher });
    },
    { prefix: "/v1" },
  );

  return app;
}
