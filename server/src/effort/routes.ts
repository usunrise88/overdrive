import { EFFORT_DEFAULT_WEEKS, EFFORT_MAX_WEEKS } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { EffortService } from "./service.ts";

export interface EffortRoutesOptions {
  service: EffortService;
}

const WHOLE_NUMBER = /^\d{1,3}$/;

/**
 * `GET /v1/effort[?weeks=N]` (spec §6.3, §8.3, `contracts/schema/effort-response.schema.json`, ADR 0042), mounted under `/v1`: the
 * training load of the last N weeks (1 to 52, twelve without it) per local week and day, the rider's usual range and how this week
 * compares. A `weeks` that is not a whole number from 1 to 52 is `400 invalid_query` and the message names the parameter.
 */
export function effortRoutes(app: FastifyInstance, options: EffortRoutesOptions): void {
  const { service } = options;

  app.get<{ Querystring: { weeks?: unknown } }>("/effort", async (request, reply) => {
    const { weeks } = request.query;
    let wanted = EFFORT_DEFAULT_WEEKS;
    if (weeks !== undefined) {
      if (
        typeof weeks !== "string" ||
        !WHOLE_NUMBER.test(weeks) ||
        Number(weeks) < 1 ||
        Number(weeks) > EFFORT_MAX_WEEKS
      ) {
        throw new HttpError(
          "invalid_query",
          `weeks must be a whole number from 1 to ${EFFORT_MAX_WEEKS}`,
        );
      }
      wanted = Number(weeks);
    }
    reply.header("cache-control", "no-store");
    return service.effort(wanted);
  });
}
