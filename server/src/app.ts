import Fastify, { type FastifyInstance } from "fastify";
import { bearerAuth } from "./auth.ts";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { installErrorHandling, sendError } from "./errors.ts";
import { DEFAULT_LIMITS, type Limits, ridesRoutes } from "./rides.ts";

export interface AppOptions {
  config: Pick<Config, "LOG_LEVEL" | "DEVICE_TOKENS">;
  db: Db;
  /** Body size limits; the defaults are the API's (ADR 0014). Tests shrink them. */
  limits?: Partial<Limits>;
  /** Where the log lines go instead of stdout. For tests. */
  logStream?: { write(line: string): void };
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
      v1.register(ridesRoutes, { db, limits });
    },
    { prefix: "/v1" },
  );

  return app;
}
