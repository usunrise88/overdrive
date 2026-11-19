import {
  validateLiveCuePlayed,
  validateLiveSessionEnd,
  validateLiveSessionStart,
  validateLiveSnapshot,
} from "@overdrive/contracts";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { HttpError, sendError } from "../errors.ts";
import type { LiveCoachService } from "./service.ts";
import type { Listener, StreamEvent } from "./session.ts";

/**
 * The live coach's routes (spec §12.1, §8.3, ADR 0038), mounted under `/v1` with the device token like every other:
 *
 * - `POST /live/sessions`: start (`201`): the answer says whether the coach is on and, when not, why.
 * - `POST /live/sessions/{id}/snapshots`: a snapshot of the ride, every 5 s (`204`; `404 not_found` for a session that is not the live one:
 *   it ended, a newer one replaced it, or the service restarted).
 * - `GET /live/sessions/{id}/cues`: Server-Sent Events (`text/event-stream`): `retry:`, a comment on open, a `cue` event per cue with its
 *   `id:` (resume with `Last-Event-ID`), a comment `: hb` every 15 s, and a last `end` event.
 * - `POST /live/sessions/{id}/end`: the ride is over (`200`, also when it had ended already).
 * - `POST /live/sessions/{id}/cues/{cueId}/played`: whether the cue was heard (`204`), optional for the app.
 *
 * Two plain HTTP connections and no WebSocket: the tablet needs nothing but the platform's `HttpURLConnection`. No new error code: a bad
 * body is `400 invalid_json` (the message names the path and the rule, never a value) and an unknown session or cue `404 not_found`.
 */

export interface LiveRoutesOptions {
  service: LiveCoachService;
}

/** A snapshot is about 400 bytes; nothing here is larger than this. */
const BODY_LIMIT = 16 * 1024;
/** What the tablet is told to wait before it reconnects, milliseconds. */
const RETRY_MS = 3000;

const SESSION_ID = /^[A-Za-z0-9_-]{1,64}$/;
const CUE_ID = /^[1-9]\d{0,6}$/;

/** The text of one event, in the format of the Server-Sent Events: lines `field: value`, a blank line ends it. */
export function frameOf(event: StreamEvent): string {
  switch (event.kind) {
    case "cue":
      return `id: ${event.id}\nevent: cue\ndata: ${event.data}\n\n`;
    case "end":
      return `event: end\ndata: ${JSON.stringify({ reason: event.reason })}\n\n`;
    case "heartbeat":
      return ": hb\n\n";
  }
}

/** The SSE id the client last saw, from `Last-Event-ID`; null when it sent none or sent nonsense. */
export function lastEventIdOf(header: string | string[] | undefined): number | null {
  const value = Array.isArray(header) ? header[0] : header;
  if (value === undefined || !/^\d{1,7}$/.test(value.trim())) return null;
  return Number(value.trim());
}

function unknownSession(): HttpError {
  return new HttpError(
    "not_found",
    "There is no live session with this id: it ended, a newer one replaced it, or the service restarted. Start a new one",
  );
}

function sessionIdOf(request: FastifyRequest<{ Params: { id: string } }>): string {
  const { id } = request.params;
  if (!SESSION_ID.test(id)) throw unknownSession();
  return id;
}

/** `400 invalid_json` naming where the body is wrong and the rule, never a value. */
function badBody(what: string, errors: readonly string[]): HttpError {
  return new HttpError("invalid_json", `The body is not ${what}: ${errors.slice(0, 3).join("; ")}`);
}

export function liveRoutes(app: FastifyInstance, options: LiveRoutesOptions): void {
  const { service } = options;

  // An event stream keeps the HTTP server from closing: end the session (and so tell and close every listener) before the server waits for
  // its connections, not after (the service's own stop runs once the server is closed, which would never come).
  app.addHook("preClose", () => service.stop());

  // Inside this scope an empty body is fine (the end of a session has none to give) and anything else must be JSON.
  app.removeContentTypeParser("application/json");
  app.addContentTypeParser(
    "application/json",
    { parseAs: "string", bodyLimit: BODY_LIMIT },
    (_request, body, done) => {
      const text = typeof body === "string" ? body : body.toString("utf8");
      if (text.trim() === "") return done(null, undefined);
      try {
        return done(null, JSON.parse(text));
      } catch {
        return done(
          Object.assign(new Error("The body is not valid JSON"), {
            statusCode: 400,
            code: "FST_ERR_CTP_INVALID_JSON_BODY",
          }),
          undefined,
        );
      }
    },
  );

  app.post("/live/sessions", { bodyLimit: BODY_LIMIT }, async (request, reply) => {
    const checked = validateLiveSessionStart(request.body ?? null);
    if (!checked.ok) throw badBody("a live session start", checked.errors);
    const answer = await service.startSession(checked.value);
    return reply.code(201).header("cache-control", "no-store").send(answer);
  });

  app.post<{ Params: { id: string } }>(
    "/live/sessions/:id/snapshots",
    { bodyLimit: BODY_LIMIT },
    async (request, reply) => {
      const id = sessionIdOf(request);
      const checked = validateLiveSnapshot(request.body ?? null);
      if (!checked.ok) throw badBody("a live snapshot", checked.errors);
      // A repeat or an older snapshot is ignored without a word: the app does not need to know.
      if (service.snapshot(id, checked.value) === "unknown") throw unknownSession();
      return reply.code(204).send();
    },
  );

  app.get<{ Params: { id: string } }>("/live/sessions/:id/cues", async (request, reply) => {
    const id = sessionIdOf(request);
    const raw = reply.raw;
    // Until the headers are out, what the session replays or announces waits here.
    const waiting: string[] = [];
    let open = false;
    let closed = false;
    const write = (chunk: string): void => {
      if (closed) return;
      if (!open) {
        waiting.push(chunk);
        return;
      }
      if (raw.destroyed || raw.writableEnded) return;
      raw.write(chunk);
    };
    const listener: Listener = {
      send(event) {
        write(frameOf(event));
        // The end is the last word: the connection is closed after it.
        if (event.kind === "end") closing();
      },
    };
    function closing(): void {
      if (closed) return;
      if (!open) {
        // The end came before the headers: let them go out, then close.
        queueMicrotask(closing);
        return;
      }
      closed = true;
      if (!raw.destroyed && !raw.writableEnded) raw.end();
    }
    const stop = service.listen(id, listener, lastEventIdOf(request.headers["last-event-id"]));
    if (stop === null) return sendError(reply, "not_found", unknownSession().message);

    reply.hijack();
    raw.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      // No cache and no transformation (a proxy that compresses would hold the events back); nginx-style proxies honour the last one.
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    });
    raw.socket?.setNoDelay(true);
    raw.write(`retry: ${RETRY_MS}\n\n: open\n\n`);
    open = true;
    for (const chunk of waiting.splice(0)) raw.write(chunk);
    request.raw.on("close", () => {
      closed = true;
      stop();
    });
    request.log.debug({ sessionId: id }, "a live stream was opened");
    return reply;
  });

  app.post<{ Params: { id: string } }>(
    "/live/sessions/:id/end",
    { bodyLimit: BODY_LIMIT },
    async (request, reply) => {
      const id = sessionIdOf(request);
      const checked = validateLiveSessionEnd(request.body ?? {});
      if (!checked.ok) throw badBody("the end of a live session", checked.errors);
      const answer = service.endSession(id, checked.value);
      if (answer === null) throw unknownSession();
      return reply.code(200).header("cache-control", "no-store").send(answer);
    },
  );

  app.post<{ Params: { id: string; cueId: string } }>(
    "/live/sessions/:id/cues/:cueId/played",
    { bodyLimit: BODY_LIMIT },
    async (request, reply) => {
      const id = sessionIdOf(request);
      const { cueId } = request.params;
      if (!CUE_ID.test(cueId)) throw new HttpError("not_found", "There is no such cue");
      const checked = validateLiveCuePlayed(request.body ?? null);
      if (!checked.ok) throw badBody("a live cue report", checked.errors);
      if (!(await service.played(id, Number(cueId), checked.value.played))) {
        throw new HttpError("not_found", "There is no such cue in this session");
      }
      return reply.code(204).send();
    },
  );
}
