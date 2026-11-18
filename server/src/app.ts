import Fastify, { type FastifyInstance } from "fastify";
import type { Config } from "./config.ts";

export function buildApp(config: Pick<Config, "LOG_LEVEL">): FastifyInstance {
  const app = Fastify({ logger: { level: config.LOG_LEVEL } });

  app.get("/healthz", () => ({ status: "ok" }));

  return app;
}
