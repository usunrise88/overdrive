import {
  isValidRideId,
  type RideRecord,
  type UploadResponse,
  type UploadStatus,
  validateRideRecord,
} from "@overdrive/contracts";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import type { Db } from "./db.ts";
import { HttpError } from "./errors.ts";
import type { EventBus } from "./events.ts";
import { checkFit } from "./fit.ts";
import { canonicalJson, sha256Hex } from "./hash.ts";
import type { Accessor } from "./tenancy/context.ts";

/** The content type of the FIT upload. */
export const FIT_CONTENT_TYPE = "application/vnd.ant.fit";

export interface Limits {
  /** Largest ride record JSON, in bytes. A day-long ride is about 20 MB; an hour is under 1 MB. */
  recordBytes: number;
  /** Largest FIT file, in bytes (an hour is about 100 KB). */
  fitBytes: number;
}

export const DEFAULT_LIMITS: Limits = { recordBytes: 16 * 1024 * 1024, fitBytes: 8 * 1024 * 1024 };

export interface RidesOptions {
  db: Accessor<Db>;
  limits: Limits;
  /** Told when a ride is complete (record and FIT stored), after the transaction committed. */
  events: Accessor<EventBus>;
  /**
   * The destinations (ADR 0014 section 5, `ride_destinations.destination`) a ride is queued for in
   * the transaction that stores its FIT. The workers that drain the queue live elsewhere.
   */
  destinations: Accessor<readonly string[]>;
}

interface RideParams {
  Params: { rideId: string };
}

const MAX_REPORTED_ERRORS = 5;
/** PostgreSQL's code for text a jsonb cannot hold (a \u0000 escape). */
const UNTRANSLATABLE_CHARACTER = "22P05";

function rideIdOf(request: FastifyRequest<RideParams>): string {
  const { rideId } = request.params;
  if (!isValidRideId(rideId)) {
    throw new HttpError(
      "invalid_ride_id",
      "The ride id must be 1 to 128 characters from A-Z a-z 0-9 . _ -, starting with a letter or digit",
    );
  }
  return rideId;
}

function summarize(errors: string[]): string {
  const shown = errors.slice(0, MAX_REPORTED_ERRORS).join("; ");
  const more = errors.length - MAX_REPORTED_ERRORS;
  return more > 0 ? `${shown} (and ${more} more)` : shown;
}

function answer(reply: FastifyReply, rideId: string, status: UploadStatus): FastifyReply {
  const body: UploadResponse = { rideId, status };
  return reply.code(status === "stored" ? 201 : 200).send(body);
}

/**
 * The ride intake (ADR 0014 section 3), mounted under `/v1`:
 *
 * - `PUT /rides/{rideId}`: the ride record JSON. 201 stored, 200 the identical record was stored
 *   before, 409 another record under this id (a stored ride is immutable) or the same start time
 *   under another id (spec §5.5), 422 not a valid record or not this id's.
 * - `PUT /rides/{rideId}/fit`: the FIT bytes. 201 stored, 200 identical repeat, 404 no such
 *   record yet, 409 another file stored, 422 not a FIT file.
 *
 * Authentication, body limits and the error body are the caller's (`app.ts`, `errors.ts`).
 */
export function ridesRoutes(app: FastifyInstance, options: RidesOptions): void {
  const { limits } = options;

  // The record: JSON only.
  app.register(async (scope) => {
    scope.removeContentTypeParser("text/plain");

    scope.put<RideParams>(
      "/rides/:rideId",
      { bodyLimit: limits.recordBytes },
      async (request, reply) => {
        const rideId = rideIdOf(request);
        if (request.body === undefined) {
          throw new HttpError("invalid_json", "The body must be the ride record as JSON");
        }
        const result = validateRideRecord(request.body);
        if (!result.ok) throw new HttpError("invalid_record", summarize(result.errors));
        const record = result.value;
        if (record.ride.id !== rideId) {
          throw new HttpError(
            "ride_id_mismatch",
            `The path says ${rideId}, the record says ${record.ride.id}`,
          );
        }
        const status = await storeRecord(options.db(request), record, request.device ?? "unknown");
        request.log.info({ device: request.device, rideId, status }, "ride record");
        return answer(reply, rideId, status);
      },
    );
  });

  // The FIT file: raw bytes only.
  app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser(FIT_CONTENT_TYPE, { parseAs: "buffer" }, (_request, body, done) =>
      done(null, body),
    );

    scope.put<RideParams>(
      "/rides/:rideId/fit",
      { bodyLimit: limits.fitBytes },
      async (request, reply) => {
        const rideId = rideIdOf(request);
        const body = request.body;
        if (!(body instanceof Uint8Array)) {
          throw new HttpError("invalid_fit", "The body must be the FIT file's bytes");
        }
        const check = checkFit(body);
        if (!check.ok) throw new HttpError("invalid_fit", check.reason);
        const stored = await storeFit(
          options.db(request),
          rideId,
          body,
          options.destinations(request),
        );
        request.log.info(
          { device: request.device, rideId, status: stored.status, bytes: body.length },
          "ride fit file",
        );
        // The ride is complete now. Listeners (the upload worker, later the bot) are not waited for
        // and cannot fail this request.
        if (stored.startEpochMillis !== undefined) {
          options
            .events(request)
            .emit("ride-stored", { rideId, startEpochMillis: stored.startEpochMillis });
        }
        return answer(reply, rideId, stored.status);
      },
    );
  });
}

/**
 * Stores the record of a ride the first time, and recognises the same record arriving again. One
 * INSERT decides it, so two uploads of the same ride at once cannot both store it.
 */
async function storeRecord(db: Db, record: RideRecord, device: string): Promise<UploadStatus> {
  const { id, startEpochMillis, endEpochMillis } = record.ride;
  const json = canonicalJson(record);
  const sha = sha256Hex(json);

  const inserted = await db
    .query<{ id: string }>(
      `INSERT INTO rides (id, start_epoch_millis, end_epoch_millis, record, record_sha256, uploaded_by)
       VALUES ($1, $2, $3, $4::jsonb, $5, $6)
       ON CONFLICT DO NOTHING
       RETURNING id`,
      [id, startEpochMillis, endEpochMillis ?? null, json, sha, device],
    )
    .catch((err: unknown) => {
      if ((err as { code?: string }).code === UNTRANSLATABLE_CHARACTER) {
        throw new HttpError("invalid_record", "The record contains a NUL character");
      }
      throw err;
    });
  if (inserted.rows.length > 0) return "stored";

  // Refused by a unique constraint: the same id, or the same start time under another id.
  const sameId = await db.query<{ record_sha256: string }>(
    "SELECT record_sha256 FROM rides WHERE id = $1",
    [id],
  );
  const stored = sameId.rows[0];
  if (stored !== undefined) {
    if (stored.record_sha256 === sha) return "already-stored";
    throw new HttpError(
      "ride_conflict",
      `Ride ${id} is already stored with different content; a stored ride is never changed`,
    );
  }
  const sameStart = await db.query<{ id: string }>(
    "SELECT id FROM rides WHERE start_epoch_millis = $1",
    [startEpochMillis],
  );
  const other = sameStart.rows[0];
  if (other !== undefined) {
    throw new HttpError(
      "start_time_conflict",
      `A ride that started at ${startEpochMillis} is already stored as ${other.id}`,
    );
  }
  // Neither row exists any more: it was removed between the INSERT and the lookups.
  throw new Error(`Ride ${id} was refused by a constraint that no row explains`);
}

/**
 * Stores the FIT file of a stored ride the first time, and recognises the same file arriving again.
 * The first time, the ride is queued for each destination in the same transaction: a ride with its
 * FIT is never without its queue rows, and nothing is queued for a file that was not stored.
 * `startEpochMillis` is set only when this request stored the file.
 */
async function storeFit(
  db: Db,
  rideId: string,
  fit: Uint8Array,
  destinations: readonly string[],
): Promise<{ status: UploadStatus; startEpochMillis?: number }> {
  const sha = sha256Hex(fit);
  const start = await db.transaction(async (tx) => {
    const updated = await tx.query<{ start: string }>(
      `UPDATE rides SET fit = $2, fit_sha256 = $3 WHERE id = $1 AND fit IS NULL
       RETURNING start_epoch_millis::text AS start`,
      [rideId, fit, sha],
    );
    const row = updated.rows[0];
    if (row === undefined) return null;
    for (const destination of destinations) {
      await tx.query(
        "INSERT INTO ride_destinations (ride_id, destination) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        [rideId, destination],
      );
    }
    return row.start;
  });
  if (start !== null) return { status: "stored", startEpochMillis: Number(start) };

  const found = await db.query<{ fit_sha256: string | null }>(
    "SELECT fit_sha256 FROM rides WHERE id = $1",
    [rideId],
  );
  const ride = found.rows[0];
  if (ride === undefined) {
    throw new HttpError(
      "ride_not_found",
      `Ride ${rideId} is not stored yet; upload its record first`,
    );
  }
  if (ride.fit_sha256 === sha) return { status: "already-stored" };
  throw new HttpError(
    "fit_conflict",
    `Ride ${rideId} already has a different FIT file; a stored ride is never changed`,
  );
}
