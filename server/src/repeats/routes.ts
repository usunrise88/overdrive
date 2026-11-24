import { isValidRideId, REPEAT_HASH_PATTERN } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { Accessor } from "../tenancy/context.ts";
import type { RepeatsService } from "./service.ts";

export interface RepeatsRoutesOptions {
  service: Accessor<RepeatsService>;
}

/**
 * The repeated sessions (spec §11.7, §8.3, `contracts/schema/repeats-response.schema.json`, `repeat-detail-response.schema.json`,
 * `repeat-previous-response.schema.json`, ADR 0026), mounted under `/v1`:
 *
 * - `GET /repeats`: the workouts ridden more than once, most recently ridden first, each with its latest attempt against the
 *   previous one.
 * - `GET /repeats/{hash}`: every attempt side by side (heart rate at the same power, aerobic drift, compliance) with the trend of
 *   each; `404 not_found` for a hash that is not a session ridden at least twice.
 * - `GET /repeats/{hash}/previous[?before=rideId]`: the attempt before a ride, with its step boundaries and heart rates, small
 *   enough for the tablet's overlay (`200` with `previous: null` when there is none; `404 ride_not_found` when `before` is not a
 *   stored ride and does not carry its start; `400 invalid_ride_id` when it is not a ride id).
 */
export function repeatsRoutes(app: FastifyInstance, options: RepeatsRoutesOptions): void {
  const serviceOf = options.service;

  function hashOf(request: { params: { hash: string } }): string {
    const { hash } = request.params;
    if (!REPEAT_HASH_PATTERN.test(hash)) {
      throw new HttpError("not_found", "There is no such repeated session");
    }
    return hash;
  }

  app.get("/repeats", async (request, reply) => {
    reply.header("cache-control", "no-store");
    return serviceOf(request).list();
  });

  app.get<{ Params: { hash: string } }>("/repeats/:hash", async (request, reply) => {
    const hash = hashOf(request);
    const detail = await serviceOf(request).detail(hash);
    if (detail === null) {
      throw new HttpError("not_found", "There is no repeated session with this hash");
    }
    reply.header("cache-control", "no-store");
    return detail;
  });

  app.get<{ Params: { hash: string }; Querystring: { before?: unknown } }>(
    "/repeats/:hash/previous",
    async (request, reply) => {
      const hash = hashOf(request);
      const { before } = request.query;
      if (before !== undefined && (typeof before !== "string" || !isValidRideId(before))) {
        throw new HttpError(
          "invalid_ride_id",
          "before must be a ride id: 1 to 128 characters from A-Z a-z 0-9 . _ -, starting with a letter or digit",
        );
      }
      const answer = await serviceOf(request).previous(hash, before ?? null);
      if (answer === "unknown-ride") {
        throw new HttpError("ride_not_found", `Ride ${before} is not stored`);
      }
      reply.header("cache-control", "no-store");
      return answer;
    },
  );
}
