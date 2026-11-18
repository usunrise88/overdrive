import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

/**
 * A fake Google for the tests: the OAuth token endpoint and the parts of the Calendar API v3 this service
 * uses, as the public reference describes them (ADR 0019), on a free local port. Written from the same
 * notes as the client, so it proves the client is consistent with the notes, not with the real service.
 * Everything in it is invented.
 */

export const FAKE_CLIENT_ID = "fake-client-id-123.apps.googleusercontent.com";
export const FAKE_CLIENT_SECRET = "GOCSPX-fake-client-secret-0123456789";
export const FAKE_REFRESH_TOKEN = "1//0fake-refresh-token-abcdefghijklmnopqrstuvwxyz0123456789";
export const FAKE_CALENDAR_ID = "fakecalendar0123456789@group.calendar.google.com";

export interface FakeGoogleEvent {
  id: string;
  /** Deleted events keep their id, as in Google. */
  status: "confirmed" | "cancelled";
  summary?: string;
  description?: string;
  start?: { dateTime: string; timeZone?: string };
  end?: { dateTime: string; timeZone?: string };
  extendedProperties?: { private?: Record<string, string> };
  [key: string]: unknown;
}

export interface GoogleRequest {
  method: string;
  path: string;
  query: Record<string, string>;
  authorization: string | undefined;
  json: unknown;
  form: Record<string, string> | undefined;
}

export interface FakeGoogle {
  /** `http://127.0.0.1:port/calendar/v3`, the API base. */
  apiUrl: string;
  /** `http://127.0.0.1:port/token`. */
  tokenUrl: string;
  requests: GoogleRequest[];
  events: Map<string, FakeGoogleEvent>;
  /** How many access tokens were issued. */
  tokensIssued: number;
  /** How many events a list page holds (default 250). */
  pageSize: number;
  /** Seconds an access token lives (default 3600). */
  expiresIn: number;
  /** Events as the calendar shows them: not deleted. */
  live(): FakeGoogleEvent[];
  /** Adds a managed event the way an earlier run would have left it. */
  addEvent(event: FakeGoogleEvent): void;
  /** The next `count` calendar requests (whose path ends with `path`, when given) are answered with `status`. */
  failNext(
    count: number,
    status: number,
    options?: { body?: unknown; path?: string; method?: string; retryAfter?: number },
  ): void;
  failAll(status: number): void;
  setUnreachable(unreachable: boolean): void;
  /** The token endpoint refuses the refresh token (`invalid_grant`), as after it was revoked or expired; `false` undoes it. */
  revokeRefreshToken(revoked?: boolean): void;
  /** Every access token issued so far stops working (401), as if Google had revoked them. */
  expireAccessTokens(): void;
  requestsTo(method: string, pathSuffix?: string): GoogleRequest[];
  reset(): void;
  close(): Promise<void>;
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf8");
}

function send(response: ServerResponse, status: number, body: unknown, headers = {}): void {
  response.writeHead(status, { "content-type": "application/json", ...headers });
  response.end(body === undefined ? "" : JSON.stringify(body));
}

const googleError = (code: number, reason: string, message: string): object => ({
  error: { code, message, errors: [{ reason, message }] },
});

export async function startFakeGoogle(): Promise<FakeGoogle> {
  const issued = new Set<string>();
  const queued: {
    status: number;
    body?: unknown;
    path?: string;
    method?: string;
    retryAfter?: number;
  }[] = [];
  let failAllStatus = 0;
  let unreachable = false;
  let revoked = false;
  let counter = 0;

  const state: FakeGoogle = {
    apiUrl: "",
    tokenUrl: "",
    requests: [],
    events: new Map(),
    tokensIssued: 0,
    pageSize: 250,
    expiresIn: 3600,
    live: () => [...state.events.values()].filter((event) => event.status === "confirmed"),
    addEvent(event) {
      state.events.set(event.id, event);
    },
    failNext(count, status, options = {}) {
      for (let i = 0; i < count; i++) queued.push({ status, ...options });
    },
    failAll(status) {
      failAllStatus = status;
    },
    setUnreachable(value) {
      unreachable = value;
    },
    revokeRefreshToken(value = true) {
      revoked = value;
    },
    expireAccessTokens() {
      issued.clear();
    },
    requestsTo(method, pathSuffix) {
      return state.requests.filter(
        (r) => r.method === method && (pathSuffix === undefined || r.path.endsWith(pathSuffix)),
      );
    },
    reset() {
      state.requests.length = 0;
      state.events.clear();
      state.tokensIssued = 0;
      state.pageSize = 250;
      state.expiresIn = 3600;
      queued.length = 0;
      failAllStatus = 0;
      unreachable = false;
      revoked = false;
      issued.clear();
      counter = 0;
    },
    close: () => closeServer(),
  };

  const sockets = new Set<import("node:net").Socket>();

  const server: Server = createServer(async (request, response) => {
    if (unreachable) {
      request.socket.destroy();
      return;
    }
    const url = new URL(request.url ?? "/", "http://fake");
    const raw = await readBody(request);
    const contentType = request.headers["content-type"] ?? "";
    let json: unknown;
    let form: Record<string, string> | undefined;
    if (contentType.startsWith("application/json") && raw !== "") json = JSON.parse(raw);
    if (contentType.startsWith("application/x-www-form-urlencoded")) {
      form = Object.fromEntries(new URLSearchParams(raw));
    }
    const method = request.method ?? "GET";
    state.requests.push({
      method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      authorization: request.headers.authorization,
      json,
      form,
    });

    // ---- OAuth token endpoint ----
    if (url.pathname === "/token") {
      if (
        method !== "POST" ||
        form?.grant_type !== "refresh_token" ||
        form.client_id !== FAKE_CLIENT_ID ||
        form.client_secret !== FAKE_CLIENT_SECRET
      ) {
        return send(response, 401, { error: "invalid_client", error_description: "Unauthorized" });
      }
      if (revoked || form.refresh_token !== FAKE_REFRESH_TOKEN) {
        return send(response, 400, {
          error: "invalid_grant",
          error_description: "Token has been expired or revoked.",
        });
      }
      const accessToken = `fake-access-token-${++counter}`;
      issued.add(accessToken);
      state.tokensIssued++;
      return send(response, 200, {
        access_token: accessToken,
        expires_in: state.expiresIn,
        token_type: "Bearer",
        scope: "https://www.googleapis.com/auth/calendar.events",
      });
    }

    // ---- Calendar API ----
    const queuedIndex = queued.findIndex(
      (fault) =>
        (fault.path === undefined || url.pathname.endsWith(fault.path)) &&
        (fault.method === undefined || fault.method === method),
    );
    const fault = queuedIndex < 0 ? undefined : queued.splice(queuedIndex, 1)[0];
    if (fault !== undefined) {
      return send(
        response,
        fault.status,
        fault.body ?? googleError(fault.status, "injectedFault", "injected fault"),
        fault.retryAfter === undefined ? {} : { "retry-after": String(fault.retryAfter) },
      );
    }
    if (failAllStatus !== 0) {
      return send(
        response,
        failAllStatus,
        googleError(failAllStatus, "injectedFault", "injected fault"),
      );
    }
    const bearer = /^Bearer (.+)$/.exec(request.headers.authorization ?? "")?.[1];
    if (bearer === undefined || !issued.has(bearer)) {
      return send(response, 401, googleError(401, "authError", "Invalid Credentials"));
    }

    const match = /^\/calendar\/v3\/calendars\/([^/]+)\/events(?:\/([^/]+))?$/.exec(url.pathname);
    if (match === null) return send(response, 404, googleError(404, "notFound", "Not Found"));
    const calendar = decodeURIComponent(match[1] ?? "");
    const eventId = match[2] === undefined ? undefined : decodeURIComponent(match[2]);
    if (calendar !== FAKE_CALENDAR_ID) {
      return send(response, 404, googleError(404, "notFound", "Not Found"));
    }

    if (eventId === undefined && method === "POST") {
      const body = (json ?? {}) as FakeGoogleEvent;
      if (typeof body.id !== "string" || !/^[a-v0-9]{5,1024}$/.test(body.id)) {
        return send(response, 400, googleError(400, "invalid", "Invalid resource id value."));
      }
      if (state.events.has(body.id)) {
        return send(
          response,
          409,
          googleError(409, "duplicate", "The requested identifier already exists."),
        );
      }
      const created: FakeGoogleEvent = { ...body, status: "confirmed" };
      state.events.set(created.id, created);
      return send(response, 200, created);
    }

    if (eventId === undefined && method === "GET") {
      const filter = url.searchParams.get("privateExtendedProperty");
      const [key, value] = (filter ?? "").split("=");
      const timeMin = Date.parse(url.searchParams.get("timeMin") ?? "") || Number.NEGATIVE_INFINITY;
      const timeMax = Date.parse(url.searchParams.get("timeMax") ?? "") || Number.POSITIVE_INFINITY;
      const matching = state
        .live()
        .filter((event) => {
          if (filter !== null && event.extendedProperties?.private?.[key ?? ""] !== value) {
            return false;
          }
          const start = Date.parse(event.start?.dateTime ?? "");
          const end = Date.parse(event.end?.dateTime ?? "");
          return end > timeMin && start < timeMax;
        })
        .sort((a, b) => a.id.localeCompare(b.id));
      const from = Number(url.searchParams.get("pageToken") ?? 0);
      const page = matching.slice(from, from + state.pageSize);
      const next = from + state.pageSize;
      return send(response, 200, {
        kind: "calendar#events",
        items: page,
        ...(next < matching.length ? { nextPageToken: String(next) } : {}),
      });
    }

    if (eventId !== undefined) {
      const found = state.events.get(eventId);
      if (method === "PATCH") {
        if (found === undefined)
          return send(response, 404, googleError(404, "notFound", "Not Found"));
        const body = (json ?? {}) as Partial<FakeGoogleEvent>;
        // A deleted event can only be brought back by saying it is confirmed.
        if (found.status === "cancelled" && body.status !== "confirmed") {
          return send(response, 410, googleError(410, "deleted", "Resource has been deleted"));
        }
        const updated = { ...found, ...body } as FakeGoogleEvent;
        state.events.set(eventId, updated);
        return send(response, 200, updated);
      }
      if (method === "DELETE") {
        if (found === undefined)
          return send(response, 404, googleError(404, "notFound", "Not Found"));
        if (found.status === "cancelled") {
          return send(response, 410, googleError(410, "deleted", "Resource has been deleted"));
        }
        state.events.set(eventId, { ...found, status: "cancelled" });
        response.writeHead(204);
        return response.end();
      }
    }
    return send(response, 405, googleError(405, "methodNotAllowed", "Method not allowed"));
  });

  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  state.apiUrl = `${origin}/calendar/v3`;
  state.tokenUrl = `${origin}/token`;

  function closeServer(): Promise<void> {
    return new Promise((resolve) => {
      for (const socket of sockets) socket.destroy();
      server.close(() => resolve());
    });
  }
  return state;
}
