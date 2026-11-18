import { createHash } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo, Socket } from "node:net";

/**
 * A fake Strava for the tests: a Node HTTP server on a free local port that speaks the parts of
 * the API the service uses (the OAuth token endpoint, the upload and its status), as Strava's
 * public documentation describes them (ADR 0020). It is written from the same notes as the client,
 * so it proves the client agrees with the notes, not with the real service. Everything it holds is
 * invented. It records EVERY request it receives, which is how a test proves the service reads
 * nothing from Strava: whatever else is asked is answered 404 and shows up in `requests`.
 */

export const FAKE_CLIENT_ID = "424242";
export const FAKE_CLIENT_SECRET = "fake-client-secret-0123456789abcdef0123";
export const FAKE_REFRESH_TOKEN = "fake-refresh-token-seed-0123456789abcdef";
/** The code the owner pastes in `strava-auth`: the fake accepts it for the authorization-code grant. */
export const FAKE_AUTH_CODE = "fake-authorization-code-0123456789";

export interface RecordedRequest {
  method: string;
  path: string;
  query: Record<string, string>;
  authorization: string | undefined;
  contentType: string | undefined;
  /** The form fields of a multipart or urlencoded body; a file is `{ filename, bytes, sha256 }`. */
  form: Record<string, string | { filename: string; bytes: number; sha256: string }>;
}

export interface FakeActivity {
  id: string;
  uploadId: string;
  externalId: string | null;
  filename: string;
  sha256: string;
  name: string | null;
  description: string | null;
  sportType: string | null;
  trainer: string | null;
  commute: string | null;
}

/** How the fake reports a file it already has. */
export type DuplicateStyle = "in-post-reply" | "on-poll" | "post-400";

export interface FakeStrava {
  /** `http://127.0.0.1:port` */
  url: string;
  requests: RecordedRequest[];
  activities: FakeActivity[];
  /** The refresh token that works now. */
  validRefreshToken: string;
  /** false: the refresh token never changes. Default true, as Strava's documentation says it may. */
  rotateRefreshTokens: boolean;
  /** The scope the token grants; an upload needs `activity:write`. */
  scope: string;
  /** Uploads are answered 403, as for an account without the subscription. */
  uploadsForbidden: boolean;
  duplicateStyle: DuplicateStyle;
  /** Status checks answered "still being processed" before the upload is ready (default 0). */
  pollsBeforeReady: number;
  /** The next upload fails in processing with this message (as for a malformed file). */
  nextUploadError: string | null;
  /** The clock the access tokens expire by; tests point it at their own. */
  now: () => number;
  /** Overrides the usage headers: the 15-minute and the daily count (default: what was really received). */
  usage: { usage15: number; usageDay: number } | null;
  limits: { limit15: number; limitDay: number };
  /** The access tokens that are valid now. */
  accessTokens: string[];
  /** Makes every access token handed out so far stop working. */
  revokeAccessTokens(): void;
  /**
   * The next `count` requests (of `method` and whose path ends with `path`, if given) are answered with `status`.
   */
  failNext(
    count: number,
    status: number,
    options?: { retryAfter?: number; body?: string; method?: string; path?: string },
  ): void;
  /** Every connection is dropped without an answer until reset. */
  setUnreachable(unreachable: boolean): void;
  requestsTo(method: string, pathSuffix: string): RecordedRequest[];
  /** Forgets everything: requests, activities, tokens, faults and settings. */
  reset(): void;
  close(): Promise<void>;
}

async function readBody(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks);
}

function send(response: ServerResponse, status: number, body: unknown, headers = {}): void {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  response.writeHead(status, { "content-type": "application/json", ...headers });
  response.end(text);
}

interface UploadRecord {
  id: string;
  externalId: string | null;
  polls: number;
  error: string | null;
  activityId: string | null;
  /** What the upload turns into once `pollsBeforeReady` status checks have been answered. */
  pendingError: string | null;
  pendingActivity: string | null;
}

const PROCESSING = "Your activity is still being processed.";
const READY = "Your activity is ready.";
const FAILED = "There was an error processing your activity.";

export async function startFakeStrava(): Promise<FakeStrava> {
  const queued: {
    status: number;
    retryAfter?: number;
    body?: string;
    method?: string;
    path?: string;
  }[] = [];
  let unreachable = false;
  let refreshCounter = 0;
  let accessCounter = 0;
  let nextActivityId = 9_000_001;
  let nextUploadId = 7_000_001;
  const accessIssued = new Map<string, number>();
  const revoked = new Set<string>();
  const uploads = new Map<string, UploadRecord>();
  let received15 = 0;
  let receivedDay = 0;
  let receivedRead15 = 0;
  let receivedReadDay = 0;

  const state: FakeStrava = {
    url: "",
    requests: [],
    activities: [],
    validRefreshToken: FAKE_REFRESH_TOKEN,
    rotateRefreshTokens: true,
    scope: "activity:write",
    uploadsForbidden: false,
    duplicateStyle: "on-poll",
    pollsBeforeReady: 0,
    nextUploadError: null,
    now: () => Date.now(),
    usage: null,
    limits: { limit15: 200, limitDay: 2000 },
    get accessTokens() {
      return [...accessIssued.entries()]
        .filter(([token, expiresAt]) => !revoked.has(token) && expiresAt > state.now())
        .map(([token]) => token);
    },
    revokeAccessTokens() {
      for (const token of accessIssued.keys()) revoked.add(token);
    },
    failNext(count, status, options = {}) {
      for (let i = 0; i < count; i++) queued.push({ status, ...options });
    },
    setUnreachable(value) {
      unreachable = value;
    },
    requestsTo(method, pathSuffix) {
      return state.requests.filter((r) => r.method === method && r.path.endsWith(pathSuffix));
    },
    reset() {
      state.requests.length = 0;
      state.activities.length = 0;
      state.validRefreshToken = FAKE_REFRESH_TOKEN;
      state.rotateRefreshTokens = true;
      state.scope = "activity:write";
      state.uploadsForbidden = false;
      state.duplicateStyle = "on-poll";
      state.pollsBeforeReady = 0;
      state.nextUploadError = null;
      state.now = () => Date.now();
      state.usage = null;
      state.limits = { limit15: 200, limitDay: 2000 };
      queued.length = 0;
      unreachable = false;
      refreshCounter = 0;
      accessCounter = 0;
      nextActivityId = 9_000_001;
      nextUploadId = 7_000_001;
      accessIssued.clear();
      revoked.clear();
      uploads.clear();
      received15 = receivedDay = receivedRead15 = receivedReadDay = 0;
    },
    close: () => closeServer(),
  };

  function rateHeaders(): Record<string, string> {
    const usage = state.usage ?? { usage15: received15, usageDay: receivedDay };
    const read = state.usage ?? { usage15: receivedRead15, usageDay: receivedReadDay };
    return {
      "x-ratelimit-limit": `${state.limits.limit15},${state.limits.limitDay}`,
      "x-ratelimit-usage": `${usage.usage15},${usage.usageDay}`,
      "x-readratelimit-limit": `${state.limits.limit15 / 2},${state.limits.limitDay / 2}`,
      "x-readratelimit-usage": `${read.usage15},${read.usageDay}`,
    };
  }

  function uploadBody(upload: UploadRecord): Record<string, unknown> {
    const failed = upload.error !== null;
    return {
      id: Number(upload.id),
      id_str: upload.id,
      external_id: upload.externalId,
      error: upload.error ?? null,
      status: failed ? FAILED : upload.activityId === null ? PROCESSING : READY,
      activity_id: upload.activityId === null ? null : Number(upload.activityId),
    };
  }

  const sockets = new Set<Socket>();

  const server: Server = createServer(async (request, response) => {
    if (unreachable) {
      request.socket.destroy();
      return;
    }
    const url = new URL(request.url ?? "/", "http://fake");
    const raw = await readBody(request);
    const contentType = request.headers["content-type"];
    const method = request.method ?? "GET";
    const form: RecordedRequest["form"] = {};
    let parsedForm: FormData | null = null;
    if (raw.length > 0 && contentType !== undefined) {
      if (contentType.startsWith("multipart/form-data")) {
        parsedForm = await new Request("http://fake/", {
          method: "POST",
          headers: { "content-type": contentType },
          body: raw,
        })
          .formData()
          .catch(() => null);
      } else if (contentType.startsWith("application/x-www-form-urlencoded")) {
        parsedForm = new FormData();
        for (const [key, value] of new URLSearchParams(raw.toString("utf8")))
          parsedForm.append(key, value);
      }
    }
    if (parsedForm !== null) {
      for (const [key, value] of parsedForm.entries()) {
        // A multipart body carries a newline as CRLF (the fetch standard); Strava hands back the text as it likes, the tests read LF.
        if (typeof value === "string") form[key] = value.replace(/\r\n/g, "\n");
        else {
          const bytes = Buffer.from(await value.arrayBuffer());
          form[key] = {
            filename: value.name,
            bytes: bytes.length,
            sha256: createHash("sha256").update(bytes).digest("hex"),
          };
        }
      }
    }
    state.requests.push({
      method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      authorization: request.headers.authorization,
      contentType,
      form,
    });

    const isUpload = method === "POST" && url.pathname === "/api/v3/uploads";
    if (url.pathname.startsWith("/api/")) {
      received15++;
      receivedDay++;
      if (!isUpload) {
        receivedRead15++;
        receivedReadDay++;
      }
    }

    const queuedIndex = queued.findIndex(
      (fault) =>
        (fault.method === undefined || fault.method === method) &&
        (fault.path === undefined || url.pathname.endsWith(fault.path)),
    );
    const fault = queuedIndex < 0 ? undefined : queued.splice(queuedIndex, 1)[0];
    if (fault !== undefined) {
      return send(response, fault.status, fault.body ?? { message: "injected fault" }, {
        ...rateHeaders(),
        ...(fault.retryAfter === undefined ? {} : { "retry-after": String(fault.retryAfter) }),
      });
    }

    // ---- OAuth --------------------------------------------------------------------------------
    if (method === "POST" && url.pathname === "/oauth/token") {
      if (form.client_id !== FAKE_CLIENT_ID || form.client_secret !== FAKE_CLIENT_SECRET) {
        return send(response, 401, {
          message: "Authorization Error",
          errors: [{ resource: "Application", field: "client_id", code: "invalid" }],
        });
      }
      const byCode = form.grant_type === "authorization_code";
      if (
        byCode
          ? form.code !== FAKE_AUTH_CODE
          : form.grant_type !== "refresh_token" || form.refresh_token !== state.validRefreshToken
      ) {
        return send(response, 400, {
          message: "Bad Request",
          errors: [
            byCode
              ? { resource: "AuthorizationCode", field: "code", code: "invalid" }
              : { resource: "RefreshToken", field: "refresh_token", code: "invalid" },
          ],
        });
      }
      if (byCode || state.rotateRefreshTokens)
        state.validRefreshToken = `fake-refresh-token-${++refreshCounter}-rotated`;
      const token = `fake-access-token-${++accessCounter}`;
      const expiresAt = Math.floor(state.now() / 1000) + 21600;
      accessIssued.set(token, expiresAt * 1000);
      return send(response, 200, {
        token_type: "Bearer",
        access_token: token,
        refresh_token: state.validRefreshToken,
        expires_at: expiresAt,
        expires_in: 21600,
        ...(byCode ? { scope: state.scope } : {}),
        // Strava may add the athlete; the service must not read it.
        athlete: { id: 1, firstname: "Invented", lastname: "Rider" },
      });
    }

    // ---- The API: a valid access token is needed -------------------------------------------------
    const bearer = /^Bearer (.+)$/.exec(request.headers.authorization ?? "")?.[1];
    const expiry = bearer === undefined ? undefined : accessIssued.get(bearer);
    if (
      url.pathname.startsWith("/api/") &&
      (bearer === undefined || expiry === undefined || revoked.has(bearer) || expiry <= state.now())
    ) {
      return send(
        response,
        401,
        {
          message: "Authorization Error",
          errors: [{ resource: "Athlete", field: "access_token", code: "invalid" }],
        },
        rateHeaders(),
      );
    }

    if (isUpload) {
      if (!state.scope.split(/[\s,]+/).includes("activity:write")) {
        return send(
          response,
          401,
          {
            message: "Authorization Error",
            errors: [{ resource: "Athlete", field: "activity:write_permission", code: "missing" }],
          },
          rateHeaders(),
        );
      }
      if (state.uploadsForbidden) {
        return send(
          response,
          403,
          {
            message: "Forbidden",
            errors: [{ resource: "Subscription", field: "uploads", code: "required" }],
          },
          rateHeaders(),
        );
      }
      const file = form.file;
      if (file === undefined || typeof file === "string" || form.data_type !== "fit") {
        return send(
          response,
          400,
          {
            message: "Bad Request",
            errors: [{ resource: "Upload", field: "file", code: "missing" }],
          },
          rateHeaders(),
        );
      }
      const externalId = typeof form.external_id === "string" ? form.external_id : null;
      const original = state.activities.find(
        (a) => a.sha256 === file.sha256 || (externalId !== null && a.externalId === externalId),
      );
      const id = String(nextUploadId++);
      const upload: UploadRecord = {
        id,
        externalId,
        polls: 0,
        error: null,
        activityId: null,
        pendingError: null,
        pendingActivity: null,
      };
      uploads.set(id, upload);
      if (original !== undefined) {
        const message = `${file.filename} duplicate of activity ${original.id}`;
        if (state.duplicateStyle === "post-400") {
          return send(response, 400, { message: "Bad Request", error: message }, rateHeaders());
        }
        upload.error = state.duplicateStyle === "in-post-reply" ? message : null;
        upload.pendingError = message;
        return send(response, 201, uploadBody(upload), rateHeaders());
      }
      if (state.nextUploadError !== null) {
        upload.pendingError = state.nextUploadError;
        state.nextUploadError = null;
        return send(response, 201, uploadBody(upload), rateHeaders());
      }
      const activityId = String(nextActivityId++);
      state.activities.push({
        id: activityId,
        uploadId: id,
        externalId,
        filename: file.filename,
        sha256: file.sha256,
        name: typeof form.name === "string" ? form.name : null,
        description: typeof form.description === "string" ? form.description : null,
        sportType: typeof form.sport_type === "string" ? form.sport_type : null,
        trainer: typeof form.trainer === "string" ? form.trainer : null,
        commute: typeof form.commute === "string" ? form.commute : null,
      });
      upload.pendingActivity = activityId;
      return send(response, 201, uploadBody(upload), rateHeaders());
    }

    const poll = /^\/api\/v3\/uploads\/(\d+)$/.exec(url.pathname);
    if (method === "GET" && poll !== null) {
      const upload = uploads.get(poll[1] as string);
      if (upload === undefined) {
        return send(
          response,
          404,
          {
            message: "Resource Not Found",
            errors: [{ resource: "Upload", field: "id", code: "not found" }],
          },
          rateHeaders(),
        );
      }
      upload.polls++;
      if (upload.polls > state.pollsBeforeReady) {
        upload.error = upload.pendingError ?? upload.error;
        upload.activityId = upload.pendingActivity ?? upload.activityId;
      }
      return send(response, 200, uploadBody(upload), rateHeaders());
    }

    return send(response, 404, { message: "Resource Not Found" }, rateHeaders());
  });

  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  state.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

  function closeServer(): Promise<void> {
    return new Promise((resolve) => {
      for (const socket of sockets) socket.destroy();
      server.close(() => resolve());
    });
  }
  return state;
}
