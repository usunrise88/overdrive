import { MAX_WORKOUTS_RANGE_DAYS, rangeDays, type WorkoutsResponse } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import { PLAN_SOURCE, type PlanRefresher } from "./refresher.ts";
import type { PlanStore } from "./store.ts";

export interface PlanRoutesOptions {
  store: PlanStore;
  /** null when no plan source is configured. */
  refresher: PlanRefresher | null;
}

interface Query {
  Querystring: { from?: unknown; to?: unknown; refresh?: unknown };
}

/** How long `?refresh=true` waits for intervals.icu before answering with the cache. */
const REFRESH_WAIT_MS = 10_000;

function dateParam(name: string, value: unknown): string {
  if (typeof value !== "string" || value === "") {
    throw new HttpError("invalid_range", `Give ${name} as a date, YYYY-MM-DD`);
  }
  return value;
}

/**
 * The planned workouts (spec §8.3, `contracts/schema/workouts-response.schema.json`), mounted under `/v1`:
 *
 * - `GET /workouts?from=YYYY-MM-DD&to=YYYY-MM-DD[&refresh=true]`: what the plan cache holds for
 *   those days (at most 62), converted to the native format. It never waits for intervals.icu
 *   unless `refresh=true`, and then for at most ten seconds; if the source cannot be reached the
 *   answer is the cache with `stale: true`. With no plan source configured it is an empty list
 *   with `configured: false`.
 */
export function planRoutes(app: FastifyInstance, options: PlanRoutesOptions): void {
  const { store, refresher } = options;

  app.get<Query>("/workouts", async (request, reply) => {
    const from = dateParam("from", request.query.from);
    const to = dateParam("to", request.query.to);
    const days = rangeDays(from, to);
    if (days === null) {
      throw new HttpError("invalid_range", "from and to must be real dates, YYYY-MM-DD");
    }
    if (days < 1) throw new HttpError("invalid_range", `from ${from} is after to ${to}`);
    if (days > MAX_WORKOUTS_RANGE_DAYS) {
      throw new HttpError(
        "invalid_range",
        `The range spans ${days} days; at most ${MAX_WORKOUTS_RANGE_DAYS} are served`,
      );
    }

    reply.header("cache-control", "no-store");
    if (refresher === null) {
      const body: WorkoutsResponse = {
        from,
        to,
        generatedAt: null,
        stale: false,
        configured: false,
        workouts: [],
      };
      return body;
    }

    const refresh = request.query.refresh;
    if (refresh === "true" || refresh === "1") {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([
        refresher.refresh(),
        new Promise<void>((resolve) => {
          timer = setTimeout(resolve, REFRESH_WAIT_MS);
        }),
      ]);
      clearTimeout(timer);
    }

    const state = await store.syncState(PLAN_SOURCE);
    const workouts = await store.list({ from, to });
    const body: WorkoutsResponse = {
      from,
      to,
      generatedAt: state.lastSuccessAt === null ? null : state.lastSuccessAt.toISOString(),
      // Stale: the last refresh failed, or none has finished yet.
      stale: state.lastAttemptAt === null || state.lastError !== null,
      configured: true,
      workouts,
    };
    return body;
  });
}
