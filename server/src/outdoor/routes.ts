import { ACTIVITY_FIT_MAX_BYTES } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import { FIT_CONTENT_TYPE } from "../rides.ts";
import { ImportError } from "./fit-ride.ts";
import type { OutdoorService } from "./service.ts";

export interface OutdoorRoutesOptions {
  service: OutdoorService;
  /** The largest FIT file in bytes (default 16 MiB). For the tests. */
  maxBytes?: number;
}

/**
 * The import of an outdoor ride (spec §11.8, `contracts/schema/activity-import-response.schema.json`, ADR 0026), under `/v1`:
 *
 * - `POST /activities/fit`: the FIT file's bytes as `application/vnd.ant.fit`, up to 16 MiB. `201` imported, `200` duplicate (the
 *   same bytes, the same start and length, or the same ride overlapping a ride, a workout or another imported ride); `413
 *   payload_too_large`, `415 unsupported_media_type`, `422 invalid_fit` (framing, CRC or structure, or not an activity file),
 *   `422 unsupported_sport` (not cycling), `422 empty_activity` (no ride in it).
 *
 * The body is read as bytes and is never parsed as text or logged. Authentication and the error body are the caller's.
 */
export function outdoorRoutes(app: FastifyInstance, options: OutdoorRoutesOptions): void {
  const { service } = options;
  const maxBytes = options.maxBytes ?? ACTIVITY_FIT_MAX_BYTES;

  app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser(FIT_CONTENT_TYPE, { parseAs: "buffer" }, (_request, body, done) =>
      done(null, body),
    );

    scope.post("/activities/fit", { bodyLimit: maxBytes }, async (request, reply) => {
      const body = request.body;
      if (!(body instanceof Uint8Array)) {
        throw new HttpError("invalid_fit", "The body must be the FIT file's bytes");
      }
      try {
        const { response, created } = await service.importFit(body, request.device ?? "unknown");
        reply.header("cache-control", "no-store");
        return reply.code(created ? 201 : 200).send(response);
      } catch (error) {
        if (error instanceof ImportError) throw new HttpError(error.code, error.message);
        throw error;
      }
    });
  });
}
