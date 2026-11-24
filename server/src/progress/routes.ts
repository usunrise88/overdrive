import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { Accessor } from "../tenancy/context.ts";
import type { ProgressService } from "./service.ts";

export interface ProgressRoutesOptions {
  service: Accessor<ProgressService>;
}

/**
 * Progress over time (spec §6.3 "Progress over time", §8.3, `contracts/schema/progress-response.schema.json`, ADR 0043), mounted under
 * `/v1`:
 *
 * - `GET /progress?from=YYYY-MM-DD&to=YYYY-MM-DD`: six measures of power and heart rate over the range, each with its series and the
 *   service's verdict. Without the parameters the last six months; at most five years. `400 invalid_range` naming the parameter for a
 *   value that is not a calendar day, a range that is reversed, in the future or longer than five years.
 */
export function progressRoutes(app: FastifyInstance, options: ProgressRoutesOptions): void {
  const serviceOf = options.service;

  app.get<{ Querystring: { from?: unknown; to?: unknown } }>(
    "/progress",
    async (request, reply) => {
      const { from, to } = request.query;
      for (const [name, value] of [
        ["from", from],
        ["to", to],
      ] as const) {
        if (value !== undefined && typeof value !== "string") {
          throw new HttpError("invalid_range", `${name} must be a calendar date, YYYY-MM-DD`);
        }
      }
      const range = serviceOf(request).rangeFor(
        from as string | undefined,
        to as string | undefined,
      );
      if (!range.ok) throw new HttpError("invalid_range", range.message);
      reply.header("cache-control", "no-store");
      return serviceOf(request).progress(range.from, range.to);
    },
  );
}
