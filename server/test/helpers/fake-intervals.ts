import { createHash } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

/**
 * A fake intervals.icu for the tests: a Node HTTP server on a free local port that speaks the
 * parts of the API this service uses, as the public OpenAPI description and forum notes describe
 * them (ADR 0016). It is written from the same notes as the client, so it proves the client is
 * consistent with the notes, not with the real service. Everything it holds is invented.
 */

export const FAKE_API_KEY = "fake-api-key-0123456789abcdef";
export const FAKE_ATHLETE_ID = "i424242";

export interface FakeActivity {
  id: string;
  external_id: string | null;
  start_date: string | null;
  name: string | null;
  paired_event_id: number | null;
  sha256: string;
  filename: string;
  bytes: number;
}

/** An event as the fake stores and lists it: whatever was sent, plus an id. */
export type FakeEvent = Record<string, unknown> & { id: number; start_date_local: string };

export interface RecordedRequest {
  method: string;
  path: string;
  query: Record<string, string>;
  authorization: string | undefined;
  contentType: string | undefined;
  /** The parsed JSON body of a JSON request. */
  json: unknown;
}

/** What an upload of a file intervals.icu already has answers (the real behaviour is not known). */
export type DuplicateStyle = "empty-200" | "conflict-409" | "message-422";

export interface FakeIntervals {
  /** `http://127.0.0.1:port` */
  url: string;
  requests: RecordedRequest[];
  activities: FakeActivity[];
  events: FakeEvent[];
  /** The `start_date` the next created activity gets. */
  nextActivityStart: string | null;
  duplicateStyle: DuplicateStyle;
  /** The status an upload that created an activity answers with: 200 or 201. */
  uploadStatus: 200 | 201;
  /** Adds a calendar event the way the owner would have in intervals.icu; returns it with its id. */
  addEvent(event: Omit<FakeEvent, "id"> & { id?: number }): FakeEvent;
  /**
   * The next `count` requests are answered with `status` (with `path`: the next `count` requests
   * whose path ends with it, others pass).
   */
  failNext(
    count: number,
    status: number,
    options?: { retryAfter?: number; body?: string; path?: string },
  ): void;
  /** Every request is answered with `status` until reset with 0. */
  failAll(status: number): void;
  /** Every connection is dropped without an answer (the host is unreachable) until reset. */
  setUnreachable(unreachable: boolean): void;
  /** Requests are accepted and never answered until reset. */
  setHanging(hanging: boolean): void;
  /** The requests that reached the given method and path suffix, in order. */
  requestsTo(method: string, pathSuffix: string): RecordedRequest[];
  /** Forgets everything: requests, activities, events, faults, counters and settings. */
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

export async function startFakeIntervals(): Promise<FakeIntervals> {
  const expectedAuth = `Basic ${Buffer.from(`API_KEY:${FAKE_API_KEY}`).toString("base64")}`;
  const queued: { status: number; retryAfter?: number; body?: string; path?: string }[] = [];
  let failAllStatus = 0;
  let unreachable = false;
  let hanging = false;
  let nextEventId = 5001;
  let nextActivityId = 1_000_001;

  const state: FakeIntervals = {
    url: "",
    requests: [],
    activities: [],
    events: [],
    nextActivityStart: null,
    duplicateStyle: "empty-200",
    uploadStatus: 201,
    addEvent(event) {
      const stored = { ...event, id: event.id ?? nextEventId++ } as FakeEvent;
      state.events.push(stored);
      return stored;
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
    setHanging(value) {
      hanging = value;
    },
    requestsTo(method, pathSuffix) {
      return state.requests.filter((r) => r.method === method && r.path.endsWith(pathSuffix));
    },
    reset() {
      state.requests.length = 0;
      state.activities.length = 0;
      state.events.length = 0;
      state.nextActivityStart = null;
      state.duplicateStyle = "empty-200";
      state.uploadStatus = 201;
      queued.length = 0;
      failAllStatus = 0;
      unreachable = false;
      hanging = false;
      nextEventId = 5001;
      nextActivityId = 1_000_001;
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
    const contentType = request.headers["content-type"];
    let json: unknown;
    if (contentType?.startsWith("application/json") && raw.length > 0) {
      json = JSON.parse(raw.toString("utf8"));
    }
    state.requests.push({
      method: request.method ?? "GET",
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      authorization: request.headers.authorization,
      contentType,
      json,
    });
    if (hanging) return;

    const queuedIndex = queued.findIndex(
      (fault) => fault.path === undefined || url.pathname.endsWith(fault.path),
    );
    const queuedFault = queuedIndex < 0 ? undefined : queued.splice(queuedIndex, 1)[0];
    if (queuedFault !== undefined) {
      return send(
        response,
        queuedFault.status,
        queuedFault.body ?? { status: queuedFault.status, error: "injected fault" },
        queuedFault.retryAfter === undefined
          ? {}
          : { "retry-after": String(queuedFault.retryAfter) },
      );
    }
    if (failAllStatus !== 0) {
      return send(response, failAllStatus, { status: failAllStatus, error: "injected fault" });
    }
    if (request.headers.authorization !== expectedAuth) {
      return send(response, 401, { status: 401, error: "Unauthorized" });
    }

    const match = /^\/api\/v1\/athlete\/([^/]+)\/(activities|events)(?:\/(\d+))?$/.exec(
      url.pathname,
    );
    if (match === null) return send(response, 404, { status: 404, error: "Not found" });
    const [, athlete, collection, idText] = match;
    if (athlete !== FAKE_ATHLETE_ID && athlete !== "0") {
      return send(response, 404, { status: 404, error: "Athlete not found" });
    }
    const method = request.method;

    if (collection === "activities" && method === "POST") {
      const form = await new Request("http://fake/", {
        method: "POST",
        headers: { "content-type": contentType ?? "" },
        body: raw,
      })
        .formData()
        .catch(() => null);
      const file = form?.get("file");
      if (!(file instanceof File)) return send(response, 422, { status: 422, error: "No file" });
      const bytes = Buffer.from(await file.arrayBuffer());
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      if (state.activities.some((a) => a.sha256 === sha256)) {
        switch (state.duplicateStyle) {
          case "empty-200":
            return send(response, 200, {
              icu_athlete_id: FAKE_ATHLETE_ID,
              id: `upload-${nextActivityId}`,
              activities: [],
            });
          case "conflict-409":
            return send(response, 409, { status: 409, error: "Duplicate activity" });
          case "message-422":
            return send(response, 422, { status: 422, error: "Activity already exists" });
        }
      }
      const id = `i${nextActivityId++}`;
      state.activities.push({
        id,
        external_id: url.searchParams.get("external_id"),
        start_date: state.nextActivityStart,
        name: url.searchParams.get("name"),
        paired_event_id: url.searchParams.has("paired_event_id")
          ? Number(url.searchParams.get("paired_event_id"))
          : null,
        sha256,
        filename: file.name,
        bytes: bytes.length,
      });
      return send(response, state.uploadStatus, {
        icu_athlete_id: FAKE_ATHLETE_ID,
        id: `upload-${id}`,
        activities: [{ icu_athlete_id: FAKE_ATHLETE_ID, id }],
      });
    }

    if (collection === "activities" && method === "GET") {
      return send(
        response,
        200,
        state.activities.map((a) => ({
          id: a.id,
          external_id: a.external_id,
          start_date: a.start_date,
          name: a.name,
        })),
      );
    }

    if (collection === "events" && method === "GET" && idText === undefined) {
      const oldest = url.searchParams.get("oldest") ?? "0000-00-00";
      const newest = url.searchParams.get("newest") ?? "9999-99-99";
      const categories = url.searchParams.get("category")?.split(",");
      return send(
        response,
        200,
        state.events.filter((event) => {
          const day = event.start_date_local.slice(0, 10);
          const category = typeof event.category === "string" ? event.category : "WORKOUT";
          return (
            day >= oldest &&
            day <= newest &&
            (categories === undefined || categories.includes(category))
          );
        }),
      );
    }

    if (collection === "events" && method === "POST" && idText === undefined) {
      const body = json as Record<string, unknown> | undefined;
      if (body === undefined || typeof body.start_date_local !== "string") {
        return send(response, 422, { status: 422, error: "start_date_local is required" });
      }
      const event = state.addEvent({ ...body, start_date_local: body.start_date_local });
      return send(response, 200, event);
    }

    if (collection === "events" && idText !== undefined) {
      const index = state.events.findIndex((event) => event.id === Number(idText));
      const found = state.events[index];
      if (found === undefined)
        return send(response, 404, { status: 404, error: "Event not found" });
      if (method === "GET") return send(response, 200, found);
      if (method === "PUT") {
        const updated = { ...found, ...(json as object) } as FakeEvent;
        // A new description is parsed afresh by the real service (assumed, ADR 0018): the steps it had
        // parsed from the old text no longer stand.
        if (typeof (json as { description?: unknown } | undefined)?.description === "string") {
          delete updated.workout_doc;
        }
        state.events[index] = updated;
        return send(response, 200, updated);
      }
      if (method === "DELETE") {
        state.events.splice(index, 1);
        return send(response, 200, {});
      }
    }
    return send(response, 405, { status: 405, error: "Method not allowed" });
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
