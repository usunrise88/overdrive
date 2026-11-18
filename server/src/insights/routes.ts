import { MAX_INSIGHTS_RANGE_DAYS, rangeDays } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { InsightsService } from "./service.ts";

export interface InsightsRoutesOptions {
  insights: InsightsService;
}

interface RangeQuery {
  Querystring: { from?: unknown; to?: unknown };
}

function dateParam(name: string, value: unknown): string {
  if (typeof value !== "string" || value === "") {
    throw new HttpError("invalid_range", `Give ${name} as a date, YYYY-MM-DD`);
  }
  return value;
}

/**
 * The written notes (spec §6.5, §6.6, §8.3, `contracts/schema/insights-response.schema.json`), mounted under `/v1`:
 *
 * - `GET /insights?from&to`: the notes created on the local days from `from` to `to` (at most 366), newest first, each with the
 *   discussion topic its ride note carries. `400 invalid_range` for a missing, malformed or reversed range, or one that is too long.
 *
 * The notes are health data: the answer is not cached, and nothing here logs it.
 */
export function insightsRoutes(app: FastifyInstance, options: InsightsRoutesOptions): void {
  const { insights } = options;

  app.get<RangeQuery>("/insights", async (request, reply) => {
    const from = dateParam("from", request.query.from);
    const to = dateParam("to", request.query.to);
    const days = rangeDays(from, to);
    if (days === null) {
      throw new HttpError("invalid_range", "from and to must be real dates, YYYY-MM-DD");
    }
    if (days < 1) throw new HttpError("invalid_range", `from ${from} is after to ${to}`);
    if (days > MAX_INSIGHTS_RANGE_DAYS) {
      throw new HttpError(
        "invalid_range",
        `The range spans ${days} days; at most ${MAX_INSIGHTS_RANGE_DAYS} are served`,
      );
    }
    reply.header("cache-control", "no-store");
    return insights.list(from, to);
  });
}
