import {
  isIsoDate,
  rangeDays,
  validateWellnessUpload,
  WELLNESS_MAX_BYTES,
  WELLNESS_MAX_RANGE_DAYS,
  WELLNESS_MAX_RECORDS,
} from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import type { WellnessService } from "./service.ts";

export interface WellnessRoutesOptions {
  service: WellnessService;
  /** The largest request body in bytes (default 4 MiB). For the tests. */
  maxBytes?: number;
}

interface RangeQuery {
  Querystring: { from?: unknown; to?: unknown; refresh?: unknown };
}

/** How long `?refresh=true` waits for intervals.icu before answering with what it has. */
const REFRESH_WAIT_MS = 10_000;
const MAX_REPORTED_ERRORS = 5;
/** Activity ids: `watch-` and a Health Connect record id, or `fit-` and 16 hex characters of an imported ride (ADR 0026). */
const ACTIVITY_ID = /^(watch|fit)-[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;

function summarize(errors: readonly string[]): string {
  const shown = errors.slice(0, MAX_REPORTED_ERRORS).join("; ");
  const more = errors.length - MAX_REPORTED_ERRORS;
  return more > 0 ? `${shown} (and ${more} more)` : shown;
}

function dateParam(name: string, value: unknown): string {
  if (typeof value !== "string" || value === "") {
    throw new HttpError("invalid_range", `Give ${name} as a date, YYYY-MM-DD`);
  }
  return value;
}

/** Waits for a refresh for at most `REFRESH_WAIT_MS`, then goes on with what there is. */
async function refreshFirst(service: WellnessService, query: unknown): Promise<void> {
  if (query !== "true" && query !== "1") return;
  const refresh = service.refresh();
  if (refresh === null) return;
  let timer: ReturnType<typeof setTimeout> | undefined;
  await Promise.race([
    refresh,
    new Promise<void>((resolve) => {
      timer = setTimeout(resolve, REFRESH_WAIT_MS);
    }),
  ]);
  clearTimeout(timer);
}

/**
 * The wellness API (spec §8.3, `contracts/schema/wellness-*.schema.json`, `recovery-response.schema.json`,
 * `activity.schema.json`), mounted under `/v1`:
 *
 * - `POST /wellness`: a batch of Health Connect records from the companion. The whole batch is checked first: one bad record
 *   refuses it (`422 invalid_record`, the message names the record's index and field, **never a value**), more than 2000
 *   records or 4 MiB is `413 payload_too_large`. Answers counts only. Idempotent by record id.
 * - `GET /wellness?from&to[&refresh=true]`: the daily figures with the baselines in force each day (at most 186 days).
 * - `GET /recovery[?refresh=true]`: the state of this morning and the facts behind it.
 * - `GET /activities/{id}`: one workout from the watch, or one outdoor ride imported from a FIT file.
 *
 * **Health data is private**: the body is parsed here so that a malformed one is answered without a snippet of it, and
 * nothing the routes log names a value.
 */
export function wellnessRoutes(app: FastifyInstance, options: WellnessRoutesOptions): void {
  const { service } = options;
  const maxBytes = options.maxBytes ?? WELLNESS_MAX_BYTES;

  // The upload: JSON only, read as text and parsed by hand (the default parser's message quotes the body).
  app.register(async (scope) => {
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser(
      "application/json",
      { parseAs: "string", bodyLimit: maxBytes },
      (_request, body, done) => {
        try {
          done(null, JSON.parse(body as string));
        } catch {
          done(new HttpError("invalid_json", "The body is not valid JSON"));
        }
      },
    );

    scope.post("/wellness", { bodyLimit: maxBytes }, async (request, reply) => {
      const body = request.body as { records?: unknown } | null | undefined;
      if (typeof body !== "object" || body === null || Array.isArray(body)) {
        throw new HttpError("invalid_record", "The body must be an object with source and records");
      }
      if (Array.isArray(body.records) && body.records.length > WELLNESS_MAX_RECORDS) {
        throw new HttpError(
          "payload_too_large",
          `At most ${WELLNESS_MAX_RECORDS} records per request`,
        );
      }
      const checked = validateWellnessUpload(body);
      if (!checked.ok) throw new HttpError("invalid_record", summarize(checked.errors));
      const device = request.device ?? "unknown";
      const answer = await service.upload.ingest(checked.value, device);
      // Counts and the device's name: never a value.
      request.log.info(
        {
          device,
          records: checked.value.records.length,
          accepted: answer.accepted,
          duplicates: answer.duplicates,
          rejected: answer.rejected,
        },
        "wellness batch",
      );
      reply.header("cache-control", "no-store");
      return answer;
    });
  });

  app.get<RangeQuery>("/wellness", async (request, reply) => {
    const from = dateParam("from", request.query.from);
    const to = dateParam("to", request.query.to);
    const days = rangeDays(from, to);
    if (days === null || !isIsoDate(from) || !isIsoDate(to)) {
      throw new HttpError("invalid_range", "from and to must be real dates, YYYY-MM-DD");
    }
    if (days < 1) throw new HttpError("invalid_range", `from ${from} is after to ${to}`);
    if (days > WELLNESS_MAX_RANGE_DAYS) {
      throw new HttpError(
        "invalid_range",
        `The range spans ${days} days; at most ${WELLNESS_MAX_RANGE_DAYS} are served`,
      );
    }
    reply.header("cache-control", "no-store");
    await refreshFirst(service, request.query.refresh);
    return service.wellness(from, to);
  });

  app.get<{ Querystring: { refresh?: unknown } }>("/recovery", async (request, reply) => {
    reply.header("cache-control", "no-store");
    await refreshFirst(service, request.query.refresh);
    return service.recovery();
  });

  app.get<{ Params: { id: string } }>("/activities/:id", async (request, reply) => {
    const { id } = request.params;
    const activity = ACTIVITY_ID.test(id) ? await service.activity(id) : null;
    if (activity === null) throw new HttpError("not_found", "There is no such activity");
    reply.header("cache-control", "no-store");
    return activity;
  });
}
