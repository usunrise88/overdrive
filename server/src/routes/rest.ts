import { isValidRouteId, type Route } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { Accessor } from "../tenancy/context.ts";
import type { RoutesService } from "./service.ts";

export interface RoutesRestOptions {
  service: Accessor<RoutesService>;
}

/**
 * The routes for the tablet (spec §11.4, §8.3, `contracts/schema/routes-response.schema.json` and `route.schema.json`), mounted under `/v1`:
 *
 * - `GET /routes`: the routes the agent has created, newest first, without their points.
 * - `GET /routes/{id}`: one route with its points (`404 not_found` for an id that is not a route, whatever the reason). A route never
 *   changes once created, so the answer may be kept for a few minutes.
 *
 * The service sends no grade: the tablet computes it from the points.
 */
export function routesRest(app: FastifyInstance, options: RoutesRestOptions): void {
  const serviceOf = options.service;

  app.get("/routes", async (request, reply) => {
    reply.header("cache-control", "no-store");
    return serviceOf(request).listResponse();
  });

  app.get<{ Params: { id: string } }>("/routes/:id", async (request, reply) => {
    const { id } = request.params;
    const route: Route | null = isValidRouteId(id) ? await serviceOf(request).get(id) : null;
    if (route === null) throw new HttpError("not_found", "No such route");
    reply.header("cache-control", "private, max-age=300");
    return route;
  });
}
