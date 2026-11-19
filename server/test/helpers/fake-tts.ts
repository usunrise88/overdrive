import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo, Socket } from "node:net";

/**
 * A fake OpenAI-compatible speech server for the tests (ADR 0038): `POST /v1/audio/speech` answers as the public description of the API
 * and the owner's own server do (200 `audio/mpeg`), on a free local port. What it replies is the test's to say: a queue of replies used one
 * by one, then a default that behaves well. The "audio" is a few bytes that start like an MP3 file and then spell the input text, so a
 * test can tell which line a cue's audio was made from; it is not speech. The real service of the owner is never called from a test.
 */

export const FAKE_TTS_KEY = "tts-fake-key-for-tests-only-0123456789abcdef";
export const FAKE_TTS_MODEL = "fake-tts-1";
export const FAKE_TTS_VOICE = "fake-voice";

export interface TtsCall {
  path: string;
  authorization: string | undefined;
  contentType: string | undefined;
  body: {
    model?: string;
    voice?: string;
    input?: string;
    response_format?: string;
    speed?: number;
  };
}

/** What the server does with one request. */
export type TtsReply =
  /** 200 with these bytes as `audio/mpeg`. */
  | { audio: Uint8Array; contentType?: string }
  /** This status and body. */
  | { status: number; body?: string; headers?: Record<string, string> }
  /** Never answers (until the server is closed or released). */
  | { hang: true }
  /** Closes the connection without an answer. */
  | { drop: true };

export interface FakeTts {
  /** `http://127.0.0.1:port/v1`: what TTS_BASE_URL is. */
  url: string;
  calls: TtsCall[];
  /** The next replies, used one per request, in order; then the default. */
  queue(...replies: TtsReply[]): void;
  /** Lets the requests that hang go (with a 500). */
  release(): void;
  reset(): void;
  close(): Promise<void>;
}

/** The "audio" of a line: the start of an MP3 file's ID3 tag and then the text itself. */
export function audioOf(text: string): Uint8Array {
  return Buffer.concat([
    Buffer.from([0x49, 0x44, 0x33, 0x04, 0x00, 0x00]),
    Buffer.from(text, "utf8"),
  ]);
}

/** The text a fake audio was made of. */
export function textOfAudio(audio: Uint8Array | Buffer): string {
  return Buffer.from(audio).subarray(6).toString("utf8");
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf8");
}

function send(
  response: ServerResponse,
  status: number,
  body: string | Uint8Array,
  headers: Record<string, string> = {},
): void {
  response.writeHead(status, { "content-type": "text/plain", ...headers });
  response.end(body);
}

export async function startFakeTts(): Promise<FakeTts> {
  const queued: TtsReply[] = [];
  const hanging = new Set<() => void>();
  const sockets = new Set<Socket>();

  const state: FakeTts = {
    url: "",
    calls: [],
    queue: (...replies) => {
      queued.push(...replies);
    },
    release() {
      for (const release of [...hanging]) release();
    },
    reset() {
      queued.length = 0;
      state.calls.length = 0;
      state.release();
    },
    close: () => closeServer(),
  };

  const server: Server = createServer(async (request, response) => {
    const raw = await readBody(request);
    if (request.method !== "POST" || request.url !== "/v1/audio/speech") {
      return send(response, 404, "not found");
    }
    let body: TtsCall["body"];
    try {
      body = JSON.parse(raw) as TtsCall["body"];
    } catch {
      return send(response, 400, "bad json");
    }
    const call: TtsCall = {
      path: request.url,
      authorization: request.headers.authorization,
      contentType: request.headers["content-type"],
      body,
    };
    state.calls.push(call);
    if (call.authorization !== undefined && call.authorization !== `Bearer ${FAKE_TTS_KEY}`) {
      return send(response, 401, "bad key");
    }
    const reply = queued.shift() ?? { audio: audioOf(body.input ?? "") };
    if ("audio" in reply) {
      return send(response, 200, reply.audio, {
        "content-type": reply.contentType ?? "audio/mpeg",
      });
    }
    if ("hang" in reply) {
      await new Promise<void>((resolve) => {
        const release = (): void => {
          hanging.delete(release);
          resolve();
        };
        hanging.add(release);
        response.on("close", release);
      });
      if (!response.destroyed && !response.writableEnded) send(response, 500, "released");
      return;
    }
    if ("drop" in reply) {
      request.socket.destroy();
      return;
    }
    return send(response, reply.status, reply.body ?? "injected fault", reply.headers);
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  state.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1`;

  function closeServer(): Promise<void> {
    return new Promise((resolve) => {
      state.release();
      for (const socket of sockets) socket.destroy();
      server.close(() => resolve());
    });
  }
  return state;
}
