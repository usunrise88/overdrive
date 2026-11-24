import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { Accessor } from "../tenancy/context.ts";
import { PowerSkillsRangeError, type PowerSkillsService } from "./service.ts";

export interface PowerSkillsRoutesOptions {
  service: Accessor<PowerSkillsService>;
}

function dateParam(name: string, value: unknown): string | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== "string" || value === "") {
    throw new HttpError("invalid_range", `${name} must be a date, YYYY-MM-DD`);
  }
  return value;
}

/**
 * `GET /v1/power-skills[?from=YYYY-MM-DD][&to=YYYY-MM-DD]` (spec §6.3, §8.3, `contracts/schema/power-skills-response.schema.json`, ADR
 * 0042), mounted under `/v1`: the best power at twelve durations over the window and over all time, and a level for each of five
 * skills. Without parameters the window is the last 56 days up to today in the rider's time zone. A parameter that is not a real
 * date, `from` after `to` or a window of more than 1096 days is `400 invalid_range` and the message names the parameter.
 */
export function powerSkillsRoutes(app: FastifyInstance, options: PowerSkillsRoutesOptions): void {
  const serviceOf = options.service;

  app.get<{ Querystring: { from?: unknown; to?: unknown } }>(
    "/power-skills",
    async (request, reply) => {
      const from = dateParam("from", request.query.from);
      const to = dateParam("to", request.query.to);
      let window: { from: string; to: string };
      try {
        window = serviceOf(request).resolveWindow(from, to);
      } catch (error) {
        if (error instanceof PowerSkillsRangeError)
          throw new HttpError("invalid_range", error.message);
        throw error;
      }
      reply.header("cache-control", "no-store");
      return serviceOf(request).powerSkills(window);
    },
  );
}
