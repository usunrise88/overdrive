import {
  DEFAULT_RIDES_LIMIT,
  type FtpSuggestionDismissResponse,
  isValidRideId,
  MAX_METRICS_RANGE_DAYS,
  MAX_RIDES_LIMIT,
  rangeDays,
  validateFtpSuggestionDismissRequest,
} from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { Accessor } from "../tenancy/context.ts";
import type { MetricsService } from "./service.ts";

export interface MetricsRoutesOptions {
  metrics: Accessor<MetricsService>;
}

interface MetricsQuery {
  Querystring: { from?: unknown; to?: unknown };
}
interface RidesQuery {
  Querystring: { before?: unknown; limit?: unknown };
}
interface RideParams {
  Params: { rideId: string };
}
interface RideDetailQuery extends RideParams {
  Querystring: { series?: unknown };
}
interface RideMetricsQuery extends RideParams {
  Querystring: { wbal?: unknown; series?: unknown; cp?: unknown; wPrimeKj?: unknown };
}

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/;
const WHOLE_NUMBER = /^\d{1,6}$/;
const NUMBER = /^\d{1,6}(\.\d{1,3})?$/;

function dateParam(name: string, value: unknown): string {
  if (typeof value !== "string" || value === "") {
    throw new HttpError("invalid_range", `Give ${name} as a date, YYYY-MM-DD`);
  }
  return value;
}

function flagParam(name: string, value: unknown): boolean {
  if (value === undefined || value === "false" || value === "0") return false;
  if (value === "true" || value === "1") return true;
  throw new HttpError("invalid_query", `${name} must be true or false`);
}

function numberParam(name: string, value: unknown, min: number, max: number): number | undefined {
  if (value === undefined) return undefined;
  if (
    typeof value !== "string" ||
    !NUMBER.test(value) ||
    Number(value) < min ||
    Number(value) > max
  ) {
    throw new HttpError("invalid_query", `${name} must be a number from ${min} to ${max}`);
  }
  return Number(value);
}

function rideIdOf(request: { params: { rideId: string } }): string {
  const { rideId } = request.params;
  if (!isValidRideId(rideId)) {
    throw new HttpError(
      "invalid_ride_id",
      "The ride id must be 1 to 128 characters from A-Z a-z 0-9 . _ -, starting with a letter or digit",
    );
  }
  return rideId;
}

/**
 * The training metrics and the companion's rides (spec §6.3, §8.3, ADR 0022), mounted under `/v1`:
 *
 * - `GET /metrics?from&to`: load, fitness, fatigue, form, weeks, power curve, FTP and aerobic drift of
 *   the local days from `from` to `to` (at most 366), as `metrics-response.schema.json`. `400 invalid_range`
 *   for a missing, malformed or reversed range, or one that is too long.
 * - `GET /rides?before&limit`: the rider's rides (and activities), newest first, by cursor.
 * - `GET /rides/{id}`: one ride with its laps, plan and time in zones; `?series=true` adds the series.
 * - `GET /rides/{id}/metrics[?wbal=true[&series=true][&cp=][&wPrimeKj=]]`: what the service computed
 *   from the ride: power curve, aerobic drift and, when asked, the W′ balance.
 * - `POST /ftp-suggestion/dismiss`: the rider keeps their FTP and hides a suggestion. Accepting one is
 *   `PATCH /profile` with its value.
 *
 * A malformed query parameter is `400 invalid_query`, an unknown ride `404 ride_not_found`.
 */
export function metricsRoutes(app: FastifyInstance, options: MetricsRoutesOptions): void {
  const metricsOf = options.metrics;

  app.get<MetricsQuery>("/metrics", async (request, reply) => {
    const from = dateParam("from", request.query.from);
    const to = dateParam("to", request.query.to);
    const days = rangeDays(from, to);
    if (days === null) {
      throw new HttpError("invalid_range", "from and to must be real dates, YYYY-MM-DD");
    }
    if (days < 1) throw new HttpError("invalid_range", `from ${from} is after to ${to}`);
    if (days > MAX_METRICS_RANGE_DAYS) {
      throw new HttpError(
        "invalid_range",
        `The range spans ${days} days; at most ${MAX_METRICS_RANGE_DAYS} are served`,
      );
    }
    reply.header("cache-control", "no-store");
    return metricsOf(request).metrics(from, to);
  });

  app.get<RidesQuery>("/rides", async (request, reply) => {
    const { before, limit } = request.query;
    let beforeMillis: number | null = null;
    if (before !== undefined) {
      if (typeof before !== "string" || !INSTANT.test(before) || Number.isNaN(Date.parse(before))) {
        throw new HttpError(
          "invalid_query",
          "before must be a UTC instant, like 2026-11-12T18:40:00Z",
        );
      }
      beforeMillis = Date.parse(before);
    }
    let wanted = DEFAULT_RIDES_LIMIT;
    if (limit !== undefined) {
      if (
        typeof limit !== "string" ||
        !WHOLE_NUMBER.test(limit) ||
        Number(limit) < 1 ||
        Number(limit) > MAX_RIDES_LIMIT
      ) {
        throw new HttpError(
          "invalid_query",
          `limit must be a whole number from 1 to ${MAX_RIDES_LIMIT}`,
        );
      }
      wanted = Number(limit);
    }
    reply.header("cache-control", "no-store");
    return metricsOf(request).rideList({ beforeMillis, limit: wanted });
  });

  app.get<RideDetailQuery>("/rides/:rideId", async (request, reply) => {
    const rideId = rideIdOf(request);
    const series = flagParam("series", request.query.series);
    const detail = await metricsOf(request).rideDetail(rideId, { series });
    if (detail === null) throw new HttpError("ride_not_found", `Ride ${rideId} is not stored`);
    reply.header("cache-control", "no-store");
    return detail;
  });

  app.get<RideMetricsQuery>("/rides/:rideId/metrics", async (request, reply) => {
    const rideId = rideIdOf(request);
    const wbal = flagParam("wbal", request.query.wbal);
    const series = flagParam("series", request.query.series);
    const cp = numberParam("cp", request.query.cp, 1, 2000);
    const wPrimeKj = numberParam("wPrimeKj", request.query.wPrimeKj, 1, 100);
    if (series && !wbal) throw new HttpError("invalid_query", "series needs wbal=true");
    if ((cp !== undefined || wPrimeKj !== undefined) && !wbal) {
      throw new HttpError("invalid_query", "cp and wPrimeKj need wbal=true");
    }
    const response = await metricsOf(request).rideMetrics(rideId, {
      wbal,
      series,
      ...(cp === undefined ? {} : { cp }),
      ...(wPrimeKj === undefined ? {} : { wPrimeKj }),
    });
    if (response === null) throw new HttpError("ride_not_found", `Ride ${rideId} is not stored`);
    reply.header("cache-control", "no-store");
    return response;
  });

  app.post("/ftp-suggestion/dismiss", async (request, reply) => {
    const body = validateFtpSuggestionDismissRequest(request.body);
    if (!body.ok) {
      throw new HttpError(
        "invalid_json",
        `The body must be {"suggestionId": number}: ${body.errors.slice(0, 3).join("; ")}`,
      );
    }
    const status = await metricsOf(request).dismissSuggestion(body.value.suggestionId);
    if (status === null) {
      throw new HttpError("not_found", `There is no FTP suggestion ${body.value.suggestionId}`);
    }
    request.log.info(
      { device: request.device, suggestionId: body.value.suggestionId, status },
      "ftp suggestion dismissed",
    );
    reply.header("cache-control", "no-store");
    const answer: FtpSuggestionDismissResponse = {
      suggestionId: body.value.suggestionId,
      status,
    };
    return answer;
  });
}
