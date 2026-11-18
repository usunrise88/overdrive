import { type Logger, silentLogger } from "../log.ts";

/**
 * A small client of the Google Calendar API v3 (ADR 0019), written from its public reference and the
 * OAuth 2.0 refresh-token grant, with no Google account to verify against: what it assumes is listed
 * in the ADR. Plain `fetch`, no SDK. The client secret and the refresh token go into the body of the
 * token request and nowhere else: not into a URL, an error message or a log line. The access token
 * lives in memory for the hour Google gives it.
 */

export type GoogleErrorKind =
  /** The refresh token or the client was refused (`invalid_grant`, `invalid_client`), or a 401 that a fresh token did not fix. */
  | "unauthorized"
  /** 403 that is not a rate limit: the token lacks the scope, or the calendar is not writable. */
  | "forbidden"
  /** 404: no such calendar or event. */
  | "not-found"
  /** 409: an event with that id exists. */
  | "conflict"
  /** 410: the event was deleted. */
  | "gone"
  /** Another 4xx: the request itself was refused, trying again cannot help. */
  | "rejected"
  /** 429, or 403 with a rate-limit reason, on every attempt. */
  | "rate-limited"
  /** 5xx on every attempt. */
  | "server"
  /** No answer: connection refused, DNS, TLS, timeout. */
  | "unreachable"
  /** A success status with a body this client cannot use. */
  | "bad-response";

export class GoogleError extends Error {
  readonly kind: GoogleErrorKind;
  readonly status: number | null;
  /** Google's own one-line reason (`invalid_grant`, `Not Found`), never a secret. */
  readonly detail: string;

  constructor(kind: GoogleErrorKind, message: string, status: number | null, detail = "") {
    super(message);
    this.name = "GoogleError";
    this.kind = kind;
    this.status = status;
    this.detail = detail;
  }

  /** Trying again later cannot help: the request itself is refused. */
  get permanent(): boolean {
    return this.kind === "rejected";
  }

  /** The configuration is wrong (credentials, calendar id): it needs the owner, not just time. */
  get configuration(): boolean {
    return this.kind === "unauthorized" || this.kind === "forbidden" || this.kind === "not-found";
  }
}

/** The part of an event this service writes. */
export interface CalendarEventBody {
  summary: string;
  description: string;
  start: { dateTime: string; timeZone: string };
  end: { dateTime: string; timeZone: string };
  /** `confirmed` brings back an event that was deleted. */
  status: "confirmed";
  extendedProperties: { private: Record<string, string> };
}

export interface CalendarEventRef {
  id: string;
  /** The planned workout id we stored in the event's private properties, if it has one. */
  plannedId: string | null;
  /** `2026-11-20T06:00:00Z` as Google sends it, or null for an all-day event. */
  start: string | null;
}

export interface CalendarClient {
  /** Creates an event with the id the caller chose; a duplicate id is a `conflict`. */
  insertEvent(id: string, body: CalendarEventBody): Promise<void>;
  /** Changes the given fields of an event (and brings it back if it was deleted). */
  patchEvent(id: string, body: Partial<CalendarEventBody>): Promise<void>;
  /** Deleting an event that is already gone succeeds. */
  deleteEvent(id: string): Promise<void>;
  /** Our events (those carrying the `overdrive` marker) that overlap `timeMin` to `timeMax`. */
  listManagedEvents(timeMin: Date, timeMax: Date): Promise<CalendarEventRef[]>;
}

export interface CalendarClientOptions {
  /** `https://www.googleapis.com/calendar/v3`, or the fake of the tests. */
  apiBaseUrl: string;
  /** `https://oauth2.googleapis.com/token`, or the fake of the tests. */
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  refreshToken: string;
  calendarId: string;
  fetch?: typeof fetch;
  timeoutMs?: number;
  /** Attempts in all for a request (default 4). */
  maxAttempts?: number;
  backoffMs?: number;
  maxBackoffMs?: number;
  sleep?: (ms: number) => Promise<void>;
  /** For the access token's expiry. */
  now?: () => Date;
  log?: Logger;
}

/** The property every event of ours carries, so the start-up sweep finds them again. */
export const MANAGED_PROPERTY = "overdrive";
export const PLANNED_ID_PROPERTY = "plannedId";

const DEFAULT_TIMEOUT_MS = 20_000;
const MAX_DETAIL = 200;
const RATE_REASONS = /rateLimitExceeded|userRateLimitExceeded|quotaExceeded|dailyLimitExceeded/i;

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

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

/** Google's reason for a failed API call: `error.errors[0].reason` and `error.message`, as one line. */
function apiDetail(body: unknown, text: string): string {
  if (typeof body === "object" && body !== null && "error" in body) {
    const error = (body as { error: unknown }).error;
    if (typeof error === "object" && error !== null) {
      const message = "message" in error && typeof error.message === "string" ? error.message : "";
      const reason =
        "errors" in error &&
        Array.isArray(error.errors) &&
        typeof (error.errors[0] as { reason?: unknown } | undefined)?.reason === "string"
          ? (error.errors[0] as { reason: string }).reason
          : "";
      return oneLine(`${reason} ${message}`);
    }
    if (typeof error === "string") return oneLine(error);
  }
  return oneLine(text);
}

export function createCalendarClient(options: CalendarClientOptions): CalendarClient {
  const apiBase = options.apiBaseUrl.replace(/\/+$/, "");
  const eventsUrl = `${apiBase}/calendars/${encodeURIComponent(options.calendarId)}/events`;
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const maxAttempts = options.maxAttempts ?? 4;
  const backoffMs = options.backoffMs ?? 500;
  const maxBackoffMs = options.maxBackoffMs ?? 30_000;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const now = options.now ?? (() => new Date());
  const log = options.log ?? silentLogger;

  let accessToken: { value: string; expiresAt: number } | null = null;
  let refreshing: Promise<string> | null = null;

  /** One attempt at a request; resolves with status and text, throws `unreachable` when there is no answer. */
  async function exchange(
    url: string,
    init: RequestInit,
  ): Promise<{ status: number; text: string; retryAfter: number }> {
    let response: Response;
    try {
      response = await doFetch(url, {
        ...init,
        // A redirect would carry the token somewhere we did not choose.
        redirect: "error",
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (cause) {
      throw new GoogleError(
        "unreachable",
        `Google did not answer (${describeFailure(cause)})`,
        null,
      );
    }
    const text = await response.text().catch(() => "");
    return {
      status: response.status,
      text,
      retryAfter: Number(response.headers.get("retry-after")),
    };
  }

  async function wait(attempt: number, retryAfter: number): Promise<void> {
    const backoff = Math.min(backoffMs * 2 ** (attempt - 1), maxBackoffMs);
    await sleep(
      Number.isFinite(retryAfter) && retryAfter > 0
        ? Math.min(retryAfter * 1000, maxBackoffMs)
        : backoff,
    );
  }

  async function fetchAccessToken(): Promise<string> {
    const form = new URLSearchParams({
      client_id: options.clientId,
      client_secret: options.clientSecret,
      refresh_token: options.refreshToken,
      grant_type: "refresh_token",
    });
    for (let attempt = 1; ; attempt++) {
      const last = attempt >= maxAttempts;
      let answer: Awaited<ReturnType<typeof exchange>>;
      try {
        answer = await exchange(options.tokenUrl, {
          method: "POST",
          headers: {
            "content-type": "application/x-www-form-urlencoded",
            accept: "application/json",
          },
          body: form.toString(),
        });
      } catch (error) {
        if (last) throw error;
        await wait(attempt, 0);
        continue;
      }
      const body = parseJson(answer.text);
      if (answer.status === 200) {
        const token =
          typeof body === "object" && body !== null && "access_token" in body
            ? (body as { access_token: unknown; expires_in?: unknown })
            : null;
        if (token === null || typeof token.access_token !== "string" || token.access_token === "") {
          throw new GoogleError(
            "bad-response",
            "Google's token endpoint answered without an access token",
            200,
          );
        }
        const seconds = typeof token.expires_in === "number" ? token.expires_in : 3600;
        // Renewed a minute early, so a request never starts with a token about to lapse.
        accessToken = {
          value: token.access_token,
          expiresAt: now().getTime() + Math.max(60, seconds - 60) * 1000,
        };
        return token.access_token;
      }
      const detail = apiDetail(body, answer.text);
      if (answer.status === 429 || answer.status >= 500) {
        if (last) {
          throw new GoogleError(
            answer.status === 429 ? "rate-limited" : "server",
            `Google's token endpoint failed (HTTP ${answer.status})`,
            answer.status,
            detail,
          );
        }
        await wait(attempt, answer.retryAfter);
        continue;
      }
      // 400 invalid_grant (revoked, expired, or the 7 days of an app still in testing), 401 invalid_client.
      throw new GoogleError(
        "unauthorized",
        `Google refused the credentials (${detail === "" ? `HTTP ${answer.status}` : detail}): run google-auth again, or check GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET`,
        answer.status,
        detail,
      );
    }
  }

  function token(forceNew = false): Promise<string> {
    if (!forceNew && accessToken !== null && accessToken.expiresAt > now().getTime()) {
      return Promise.resolve(accessToken.value);
    }
    refreshing ??= fetchAccessToken().finally(() => {
      refreshing = null;
    });
    return refreshing;
  }

  /** A calendar call with the access token, retrying what may be retried; returns the parsed JSON ('' body: null). */
  async function call(
    method: "GET" | "POST" | "PATCH" | "DELETE",
    url: string,
    json?: unknown,
  ): Promise<unknown> {
    let renewed = false;
    for (let attempt = 1; ; attempt++) {
      const last = attempt >= maxAttempts;
      const bearer = await token(renewed);
      renewed = false;
      let answer: Awaited<ReturnType<typeof exchange>>;
      try {
        answer = await exchange(url, {
          method,
          headers: {
            authorization: `Bearer ${bearer}`,
            accept: "application/json",
            ...(json === undefined ? {} : { "content-type": "application/json" }),
          },
          ...(json === undefined ? {} : { body: JSON.stringify(json) }),
        });
      } catch (error) {
        if (last) throw error;
        log.debug({ attempt }, "Google Calendar request will be repeated");
        await wait(attempt, 0);
        continue;
      }

      const { status, text } = answer;
      if (status >= 200 && status < 300) {
        if (text.trim() === "") return null;
        const body = parseJson(text);
        if (body === undefined) {
          throw new GoogleError(
            "bad-response",
            `Google answered ${status} with something that is not JSON`,
            status,
          );
        }
        return body;
      }

      const body = parseJson(text);
      const detail = apiDetail(body, text);
      if (status === 401 && attempt === 1) {
        // The access token may have been revoked early: one fresh token, then the answer is final.
        accessToken = null;
        renewed = true;
        continue;
      }
      if (status === 429 || (status === 403 && RATE_REASONS.test(detail))) {
        if (last) {
          throw new GoogleError(
            "rate-limited",
            `Google is limiting requests (HTTP ${status})`,
            status,
            detail,
          );
        }
        await wait(attempt, answer.retryAfter);
        continue;
      }
      if (status >= 500 || status === 408) {
        if (last) {
          throw new GoogleError(
            "server",
            `Google failed to answer (HTTP ${status})`,
            status,
            detail,
          );
        }
        log.debug({ attempt, status }, "Google Calendar request will be repeated");
        await wait(attempt, answer.retryAfter);
        continue;
      }
      if (status === 401) {
        throw new GoogleError(
          "unauthorized",
          "Google did not accept the access token: run google-auth again",
          status,
          detail,
        );
      }
      if (status === 403) {
        throw new GoogleError(
          "forbidden",
          `Google refused access to the calendar (HTTP 403${detail === "" ? "" : `: ${detail}`}): check that the account owns GOOGLE_CALENDAR_ID and that google-auth was run with that account`,
          status,
          detail,
        );
      }
      if (status === 404) {
        throw new GoogleError(
          "not-found",
          "Google answered 404: check GOOGLE_CALENDAR_ID (or the event is gone)",
          status,
          detail,
        );
      }
      if (status === 409)
        throw new GoogleError("conflict", "An event with that id exists", status, detail);
      if (status === 410) throw new GoogleError("gone", "The event was deleted", status, detail);
      throw new GoogleError(
        "rejected",
        `Google refused the request (HTTP ${status}${detail === "" ? "" : `: ${detail}`})`,
        status,
        detail,
      );
    }
  }

  const eventUrl = (id: string): string => `${eventsUrl}/${encodeURIComponent(id)}`;

  return {
    async insertEvent(id, body) {
      await call("POST", eventsUrl, { id, ...body });
    },

    async patchEvent(id, body) {
      await call("PATCH", eventUrl(id), body);
    },

    async deleteEvent(id) {
      try {
        await call("DELETE", eventUrl(id));
      } catch (error) {
        if (error instanceof GoogleError && (error.kind === "gone" || error.kind === "not-found")) {
          return;
        }
        throw error;
      }
    },

    async listManagedEvents(timeMin, timeMax) {
      const found: CalendarEventRef[] = [];
      let pageToken: string | undefined;
      do {
        const query = new URLSearchParams({
          timeMin: timeMin.toISOString(),
          timeMax: timeMax.toISOString(),
          privateExtendedProperty: `${MANAGED_PROPERTY}=1`,
          singleEvents: "true",
          showDeleted: "false",
          maxResults: "250",
        });
        if (pageToken !== undefined) query.set("pageToken", pageToken);
        const body = await call("GET", `${eventsUrl}?${query.toString()}`);
        const page = body as { items?: unknown; nextPageToken?: unknown } | null;
        if (page === null || typeof page !== "object") {
          throw new GoogleError("bad-response", "Google did not answer with a list of events", 200);
        }
        for (const item of Array.isArray(page.items) ? page.items : []) {
          if (typeof item !== "object" || item === null || !("id" in item)) continue;
          if (typeof item.id !== "string") continue;
          const priv = (item as { extendedProperties?: { private?: Record<string, unknown> } })
            .extendedProperties?.private;
          const planned = priv?.[PLANNED_ID_PROPERTY];
          const start = (item as { start?: { dateTime?: unknown } }).start?.dateTime;
          found.push({
            id: item.id,
            plannedId: typeof planned === "string" ? planned : null,
            start: typeof start === "string" ? start : null,
          });
        }
        pageToken = typeof page.nextPageToken === "string" ? page.nextPageToken : undefined;
      } while (pageToken !== undefined);
      return found;
    },
  };
}
