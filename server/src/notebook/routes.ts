import type { FastifyInstance } from "fastify";
import type { Accessor } from "../tenancy/context.ts";
import type { NotebookService } from "./service.ts";

export interface NotebookRoutesOptions {
  service: Accessor<NotebookService>;
}

/**
 * The coach notebook for the companion (spec §11.6, §8.3, `contracts/schema/notebook-response.schema.json`), mounted under `/v1`:
 *
 * - `GET /notebook`: the notebook of the coach scope, all seven sections with their text, version, time and reason. Read-only: the
 *   notebook is written by the coach agent over MCP. **The game master's notebook is never served here**: the route asks the
 *   service for the coach scope and has no parameter that could name another (ADR 0035).
 *
 * The notebook is private (training, injuries): the answer is not cached, and nothing here logs it.
 */
export function notebookRoutes(app: FastifyInstance, options: NotebookRoutesOptions): void {
  const serviceOf = options.service;

  app.get("/notebook", async (request, reply) => {
    reply.header("cache-control", "no-store");
    return serviceOf(request).coachResponse();
  });
}
