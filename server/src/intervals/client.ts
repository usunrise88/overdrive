import { z } from "zod";
import { type Logger, silentLogger } from "../log.ts";

/**
 * A small client of the intervals.icu API (ADR 0016), written from its public OpenAPI description
 * and forum notes, with no account to verify against: what it assumes is listed in the ADR.
 * Authentication is HTTP Basic with the user name `API_KEY` and the personal API key as password.
 * The key goes into one header and nowhere else: not into a URL, an error message or a log line.
 */

// ---- Errors ----------------------------------------------------------------------------------

export type IntervalsErrorKind =
  /** 401 or 403: the key is wrong or lacks the right. */
  | "unauthorized"
  /** 404: no such athlete, event or path. */
  | "not-found"
  /** Another 4xx: the request itself was refused, trying again cannot help. */
  | "rejected"
  /** 429 on every attempt. */
  | "rate-limited"
  /** 5xx on every attempt. */
  | "server"
  /** No answer: connection refused, DNS, TLS, timeout. */
  | "unreachable"
  /** A success status with a body this client cannot use. */
  | "bad-response";

export class IntervalsError extends Error {
  readonly kind: IntervalsErrorKind;
  /** The HTTP status, or null when there was no answer. */
  readonly status: number | null;
  /** The start of the answer's body (one line), for telling a duplicate from another refusal. */
  readonly detail: string;

  constructor(kind: IntervalsErrorKind, message: string, status: number | null, detail = "") {
    super(message);
    this.name = "IntervalsError";
    this.kind = kind;
    this.status = status;
    this.detail = detail;
  }

  /** Trying again later cannot help: the request itself is refused. */
  get permanent(): boolean {
    return this.kind === "rejected";
  }

  /** The configuration is wrong (key, athlete id): it needs the owner, not just time. */
  get configuration(): boolean {
    return this.kind === "unauthorized" || this.kind === "not-found";
  }
}

// ---- Shapes ----------------------------------------------------------------------------------

/**
 * A calendar event as the list returns it, as far as this service reads it. Everything else the
 * API sends is kept (`looseObject`) and ignored.
 */
export const intervalsEventSchema = z.looseObject({
  id: z.number().int(),
  /** Local date and time, `2026-11-20T00:00:00`. */
  start_date_local: z.string(),
  category: z.string().nullish(),
  name: z.string().nullish(),
  description: z.string().nullish(),
  /** Sport: `Ride`, `VirtualRide`, `Run`, … */
  type: z.string().nullish(),
  /** Planned duration in seconds. */
  moving_time: z.number().nullish(),
  /** The athlete's FTP in watts when the workout was resolved. */
  icu_ftp: z.number().nullish(),
  workout_doc: z.unknown().optional(),
  external_id: z.string().nullish(),
});

export type IntervalsEvent = z.infer<typeof intervalsEventSchema>;

/** What create and update send (the fields of the OpenAPI `Event` this service writes). */
export interface IntervalsEventPayload {
  category: "WORKOUT";
  /** `2026-11-20T00:00:00`: the day, in the athlete's time. */
  start_date_local: string;
  type: string;
  name: string;
  /** The workout in intervals.icu's text syntax (the steps are parsed from it). */
  description: string;
  indoor?: boolean;
  external_id?: string;
}

export interface UploadInput {
  fit: Uint8Array;
  filename: string;
  /** Our id of the ride, stored with the activity. */
  externalId: string;
  name?: string;
  description?: string;
  /** The planned workout (calendar event) to pair the activity with. */
  pairedEventId?: number;
}

export type UploadOutcome =
  /** intervals.icu made an activity from the file. */
  | { status: "uploaded"; activityId: string }
  /** intervals.icu already has this activity (same file, or same start time): nothing was created. */
  | { status: "duplicate"; activityId: string | null };

export interface FindActivityInput {
  externalId: string;
  startEpochMillis: number;
  /** Local dates (`YYYY-MM-DD`) that bracket the start. */
  oldest: string;
  newest: string;
}

export interface IntervalsClient {
  /** Idempotent: sending the same ride again answers `duplicate`. */
  uploadActivity(input: UploadInput): Promise<UploadOutcome>;
  /** The id of the activity that is this ride, if intervals.icu has one (best effort). */
  findActivityId(input: FindActivityInput): Promise<string | null>;
  /** The WORKOUT events from `from` to `to` (local dates, both included). */
  listEvents(from: string, to: string): Promise<IntervalsEvent[]>;
  /** One calendar event of any category, or null when there is none with this id. */
  getEvent(id: number): Promise<IntervalsEvent | null>;
  createEvent(payload: IntervalsEventPayload): Promise<IntervalsEvent>;
  updateEvent(id: number, payload: Partial<IntervalsEventPayload>): Promise<IntervalsEvent>;
  /** Deleting an event that is already gone succeeds. */
  deleteEvent(id: number): Promise<void>;
}

// ---- The HTTP client -------------------------------------------------------------------------

export interface IntervalsClientOptions {
  /** `https://intervals.icu`, or the fake of the tests. No trailing slash. */
  baseUrl: string;
  /** `i12345`, or `0` for the athlete the key belongs to. */
  athleteId: string;
  apiKey: string;
  fetch?: typeof fetch;
  /** Per attempt, in milliseconds (default 20 s; an upload gets three times that). */
  timeoutMs?: number;
  /** Attempts in all for a request that may be repeated (default 4). */
  maxAttempts?: number;
  /** First wait between attempts, doubling each time (default 500 ms). */
  backoffMs?: number;
  /** Longest wait, including a server's `Retry-After` (default 30 s). */
  maxBackoffMs?: number;
  /** For the tests: waits without waiting. */
  sleep?: (ms: number) => Promise<void>;
  log?: Logger;
}

interface RequestSpec {
  method: "GET" | "POST" | "PUT" | "DELETE";
  /** After `/api/v1/athlete/{id}/`. */
  path: string;
  query?: Record<string, string | number | boolean | undefined>;
  json?: unknown;
  form?: FormData;
  /**
   * Repeating the request is harmless, so a network error or a 5xx is retried. A request that is
   * not (creating an event) is retried only after a 429, which says it was not processed.
   */
  repeatable: boolean;
  timeoutMs: number;
}

const DEFAULT_TIMEOUT_MS = 20_000;
const MAX_DETAIL = 200;

function oneLine(text: string): string {
  return text.replace(/\s+/g, " ").trim().slice(0, MAX_DETAIL);
}

function describeFailure(cause: unknown): string {
  if (cause instanceof Error) {
    if (cause.name === "TimeoutError" || cause.name === "AbortError") return "timed out";
    const code = (cause.cause as { code?: unknown } | undefined)?.code;
    return typeof code === "string" ? code : "the connection failed";
  }
  return "the connection failed";
}

/** The activity ids of an upload's answer, whichever of the documented shapes it has. */
function activityIdsOf(body: unknown): string[] {
  const refs: unknown[] = Array.isArray(body)
    ? body
    : typeof body === "object" && body !== null && "activities" in body
      ? Array.isArray(body.activities)
        ? body.activities
        : []
      : [];
  const ids: string[] = [];
  for (const ref of refs) {
    if (typeof ref === "object" && ref !== null && "id" in ref) {
      if (typeof ref.id === "string" || typeof ref.id === "number") ids.push(String(ref.id));
    }
  }
  return ids;
}

/** A refusal that means "this activity is already there" rather than "this file is bad". */
function looksLikeDuplicate(error: IntervalsError): boolean {
  if (error.kind !== "rejected") return false;
  if (error.status === 409) return true;
  return /duplicate|already (exist|upload|import)/i.test(error.detail);
}

export function createIntervalsClient(options: IntervalsClientOptions): IntervalsClient {
  const baseUrl = options.baseUrl.replace(/\/+$/, "");
  const athlete = encodeURIComponent(options.athleteId);
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const maxAttempts = options.maxAttempts ?? 4;
  const backoffMs = options.backoffMs ?? 500;
  const maxBackoffMs = options.maxBackoffMs ?? 30_000;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const log = options.log ?? silentLogger;
  const authorization = `Basic ${Buffer.from(`API_KEY:${options.apiKey}`).toString("base64")}`;

  function url(spec: RequestSpec): string {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(spec.query ?? {})) {
      if (value !== undefined) query.set(key, String(value));
    }
    const text = query.toString();
    return `${baseUrl}/api/v1/athlete/${athlete}/${spec.path}${text === "" ? "" : `?${text}`}`;
  }

  /** Sends the request, retrying what may be retried; returns the parsed JSON of a 2xx answer. */
  async function send(spec: RequestSpec): Promise<unknown> {
    const target = url(spec);
    const headers: Record<string, string> = { authorization, accept: "application/json" };
    let body: string | FormData | undefined;
    if (spec.json !== undefined) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(spec.json);
    } else if (spec.form !== undefined) {
      body = spec.form;
    }

    for (let attempt = 1; ; attempt++) {
      const last = attempt >= maxAttempts;
      const wait = Math.min(backoffMs * 2 ** (attempt - 1), maxBackoffMs);
      let response: Response;
      try {
        response = await doFetch(target, {
          method: spec.method,
          headers,
          ...(body === undefined ? {} : { body }),
          // A redirect would carry the key somewhere we did not choose.
          redirect: "error",
          signal: AbortSignal.timeout(spec.timeoutMs),
        });
      } catch (cause) {
        const error = new IntervalsError(
          "unreachable",
          `intervals.icu did not answer (${describeFailure(cause)})`,
          null,
        );
        if (!spec.repeatable || last) throw error;
        log.debug({ attempt, reason: error.message }, "intervals.icu request will be repeated");
        await sleep(wait);
        continue;
      }

      const text = await response.text().catch(() => "");
      const status = response.status;
      if (status >= 200 && status < 300) {
        if (text.trim() === "") return null;
        try {
          return JSON.parse(text) as unknown;
        } catch {
          throw new IntervalsError(
            "bad-response",
            `intervals.icu answered ${status} with something that is not JSON`,
            status,
          );
        }
      }

      const detail = oneLine(text);
      if (status === 429) {
        const error = new IntervalsError(
          "rate-limited",
          "intervals.icu is limiting requests (HTTP 429)",
          status,
          detail,
        );
        if (last) throw error;
        const retryAfter = Number(response.headers.get("retry-after"));
        await sleep(
          Number.isFinite(retryAfter) && retryAfter > 0
            ? Math.min(retryAfter * 1000, maxBackoffMs)
            : wait,
        );
        continue;
      }
      if (status >= 500 || status === 408) {
        const error = new IntervalsError(
          "server",
          `intervals.icu failed to answer (HTTP ${status})`,
          status,
          detail,
        );
        if (!spec.repeatable || last) throw error;
        log.debug({ attempt, status }, "intervals.icu request will be repeated");
        await sleep(wait);
        continue;
      }
      if (status === 401 || status === 403) {
        throw new IntervalsError(
          "unauthorized",
          `intervals.icu did not accept the API key (HTTP ${status})`,
          status,
          detail,
        );
      }
      if (status === 404) {
        throw new IntervalsError(
          "not-found",
          "intervals.icu answered 404: check INTERVALS_ATHLETE_ID and INTERVALS_BASE_URL",
          status,
          detail,
        );
      }
      throw new IntervalsError(
        "rejected",
        `intervals.icu refused the request (HTTP ${status}${detail === "" ? "" : `: ${detail}`})`,
        status,
        detail,
      );
    }
  }

  function parseEvent(raw: unknown): IntervalsEvent {
    const parsed = intervalsEventSchema.safeParse(raw);
    if (!parsed.success) {
      throw new IntervalsError(
        "bad-response",
        "intervals.icu sent an event this service cannot read",
        200,
      );
    }
    return parsed.data;
  }

  return {
    async uploadActivity(input) {
      const form = new FormData();
      form.append(
        "file",
        new Blob([Uint8Array.from(input.fit)], { type: "application/octet-stream" }),
        input.filename,
      );
      let body: unknown;
      try {
        body = await send({
          method: "POST",
          path: "activities",
          query: {
            external_id: input.externalId,
            name: input.name,
            description: input.description,
            paired_event_id: input.pairedEventId,
          },
          form,
          // intervals.icu recognises a file it has seen, so sending again is harmless.
          repeatable: true,
          timeoutMs: timeoutMs * 3,
        });
      } catch (error) {
        if (error instanceof IntervalsError && looksLikeDuplicate(error)) {
          return { status: "duplicate", activityId: null };
        }
        throw error;
      }
      const [first] = activityIdsOf(body);
      // A 200 that created nothing is intervals.icu saying it has the file already.
      return first === undefined
        ? { status: "duplicate", activityId: null }
        : { status: "uploaded", activityId: first };
    },

    async findActivityId(input) {
      const body = await send({
        method: "GET",
        path: "activities",
        query: { oldest: input.oldest, newest: input.newest },
        repeatable: true,
        timeoutMs,
      });
      if (!Array.isArray(body)) return null;
      let byStart: string | null = null;
      for (const item of body) {
        if (typeof item !== "object" || item === null || !("id" in item)) continue;
        const id =
          typeof item.id === "string" || typeof item.id === "number" ? String(item.id) : null;
        if (id === null) continue;
        if ("external_id" in item && item.external_id === input.externalId) return id;
        if ("start_date" in item && typeof item.start_date === "string") {
          // `start_date` is UTC; without a zone suffix it is read as UTC too.
          const text = /(Z|[+-]\d{2}:?\d{2})$/.test(item.start_date)
            ? item.start_date
            : `${item.start_date}Z`;
          const started = Date.parse(text);
          if (Math.abs(started - input.startEpochMillis) <= 60_000) byStart = id;
        }
      }
      return byStart;
    },

    async listEvents(from, to) {
      const body = await send({
        method: "GET",
        path: "events",
        // resolve: zone and %-of-FTP targets also come as watts, which the converter needs for zones.
        query: { oldest: from, newest: to, category: "WORKOUT", resolve: true },
        repeatable: true,
        timeoutMs,
      });
      if (!Array.isArray(body)) {
        throw new IntervalsError(
          "bad-response",
          "intervals.icu did not answer with a list of events",
          200,
        );
      }
      const events: IntervalsEvent[] = [];
      let dropped = 0;
      for (const raw of body) {
        const parsed = intervalsEventSchema.safeParse(raw);
        if (parsed.success) events.push(parsed.data);
        else dropped++;
      }
      if (dropped > 0)
        log.warn({ dropped }, "intervals.icu events without an id or a date were ignored");
      return events;
    },

    async getEvent(id) {
      try {
        return parseEvent(
          await send({ method: "GET", path: `events/${id}`, repeatable: true, timeoutMs }),
        );
      } catch (error) {
        // A 404 here says the event is not there; it is not a sign of a wrong athlete id.
        if (error instanceof IntervalsError && error.kind === "not-found") return null;
        throw error;
      }
    },

    async createEvent(payload) {
      return parseEvent(
        await send({
          method: "POST",
          path: "events",
          json: payload,
          // A repeated POST could make a second event: only a 429 is retried.
          repeatable: false,
          timeoutMs,
        }),
      );
    },

    async updateEvent(id, payload) {
      return parseEvent(
        await send({
          method: "PUT",
          path: `events/${id}`,
          json: payload,
          repeatable: true,
          timeoutMs,
        }),
      );
    },

    async deleteEvent(id) {
      try {
        await send({ method: "DELETE", path: `events/${id}`, repeatable: true, timeoutMs });
      } catch (error) {
        if (error instanceof IntervalsError && error.kind === "not-found") return;
        throw error;
      }
    },
  };
}
