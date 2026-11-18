import type { RateInfo } from "./rate-limit.ts";

export type StravaErrorKind =
  /** The refresh token or the client id and secret were refused: the owner must run strava-auth again. */
  | "auth-failed"
  /** 401 to a request with a fresh access token: the scope is missing or the application was deauthorised. */
  | "unauthorized"
  /** 403: the account or the application may not upload (a missing subscription is the likely reason). */
  | "forbidden"
  /** 429: a limit is used up; `retryAfter` and `rate` say when to come back. */
  | "rate-limited"
  /** Another 4xx: the request itself was refused, repeating it cannot help. */
  | "rejected"
  /** 5xx: Strava failed; try again later. */
  | "server"
  /** No answer: connection refused, DNS, TLS, timeout. */
  | "unreachable"
  /** A success status with a body this client cannot use. */
  | "bad-response";

export class StravaError extends Error {
  readonly kind: StravaErrorKind;
  /** The HTTP status, or null when there was no answer. */
  readonly status: number | null;
  /** The start of the answer's body (one line, no markup), for telling a duplicate from another refusal. */
  readonly detail: string;
  /** `Retry-After` of a 429, as sent. */
  readonly retryAfter: string | null;
  /** The rate-limit headers of the answer. */
  readonly rate: RateInfo | null;

  constructor(
    kind: StravaErrorKind,
    message: string,
    options: {
      status?: number | null;
      detail?: string;
      retryAfter?: string | null;
      rate?: RateInfo | null;
    } = {},
  ) {
    super(message);
    this.name = "StravaError";
    this.kind = kind;
    this.status = options.status ?? null;
    this.detail = options.detail ?? "";
    this.retryAfter = options.retryAfter ?? null;
    this.rate = options.rate ?? null;
  }

  /** Needs the owner (credentials, scope, subscription): time alone will not fix it, and asking again is pointless. */
  get configuration(): boolean {
    return this.kind === "auth-failed" || this.kind === "unauthorized" || this.kind === "forbidden";
  }

  /** The request itself is refused: trying again later cannot help. */
  get permanent(): boolean {
    return this.kind === "rejected";
  }
}

const MAX_DETAIL = 200;

/** One line of plain text from an answer's body: markup removed (Strava's errors may carry HTML), cut short. */
export function oneLine(text: string): string {
  return text
    .replace(/<[^>]*>/g, " ")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_DETAIL);
}

/**
 * What an error answer says, for the log and `last_error`: the `message` and the `errors` of Strava's
 * JSON (`resource`, `field`, `code`) when there are some, otherwise the text itself.
 */
export function describeBody(text: string): string {
  try {
    const body: unknown = JSON.parse(text);
    if (typeof body === "object" && body !== null) {
      const parts: string[] = [];
      if ("message" in body && typeof body.message === "string") parts.push(body.message);
      if ("error" in body && typeof body.error === "string") parts.push(body.error);
      if ("errors" in body && Array.isArray(body.errors)) {
        for (const item of body.errors.slice(0, 3)) {
          if (typeof item !== "object" || item === null) continue;
          const fields = ["resource", "field", "code"]
            .map((key) => (key in item ? (item as Record<string, unknown>)[key] : undefined))
            .filter((value): value is string => typeof value === "string");
          if (fields.length > 0) parts.push(fields.join(" "));
        }
      }
      if (parts.length > 0) return oneLine(parts.join("; "));
    }
  } catch {
    // Not JSON: the text itself.
  }
  return oneLine(text);
}

/** Why a request got no answer, in words that name no address and no secret. */
export function describeFailure(cause: unknown): string {
  if (cause instanceof Error) {
    if (cause.name === "TimeoutError" || cause.name === "AbortError") return "timed out";
    const code = (cause.cause as { code?: unknown } | undefined)?.code;
    return typeof code === "string" ? code : "the connection failed";
  }
  return "the connection failed";
}
