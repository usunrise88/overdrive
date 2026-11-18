import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo, Socket } from "node:net";

/**
 * A fake OpenAI-compatible chat-completions server for the tests (ADR 0025): `POST /v1/chat/completions` answers as the public
 * description of the API says, on a free local port. What it replies is the test's to say: a queue of replies used one by one, then
 * a default that behaves well (it reads the facts out of the prompt and writes a note from them). Everything in it is invented.
 */

export const FAKE_LLM_KEY = "sk-fake-key-for-tests-only-0123456789abcdef";
export const FAKE_LLM_MODEL = "fake-model-1";

export interface LlmCall {
  path: string;
  authorization: string | undefined;
  contentType: string | undefined;
  body: {
    model?: string;
    messages: { role: string; content: string }[];
    temperature?: number;
    max_tokens?: number;
    stream?: boolean;
  };
}

/** What the server does with one request. */
export type Reply =
  /** 200 with this text as the assistant's message. */
  | { text: string }
  /** This status and body (an object is sent as JSON, a string as it is). */
  | { status: number; body?: unknown; headers?: Record<string, string> }
  /** Never answers (until the server is closed or released). */
  | { hang: true }
  /** Closes the connection without an answer. */
  | { drop: true };

export interface FakeLlm {
  /** `http://127.0.0.1:port/v1`: what LLM_BASE_URL is. */
  url: string;
  calls: LlmCall[];
  /** The next replies, used one per request, in order; then {@link FakeLlm.fallback}. */
  queue(...replies: Reply[]): void;
  /** What answers when the queue is empty (default: a well-behaved note). */
  fallback: (call: LlmCall) => Reply;
  /** Lets the requests that hang go (with a 500). */
  release(): void;
  reset(): void;
  close(): Promise<void>;
}

const completion = (text: string) => ({
  id: "chatcmpl-fake",
  object: "chat.completion",
  created: 1_790_000_000,
  model: FAKE_LLM_MODEL,
  choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
  usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
});

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf8");
}

function send(response: ServerResponse, status: number, body: unknown, headers = {}): void {
  response.writeHead(status, {
    "content-type": typeof body === "string" ? "text/plain" : "application/json",
    ...headers,
  });
  response.end(typeof body === "string" ? body : JSON.stringify(body));
}

/** The facts JSON the service put in the user message. */
export function factsOf(call: LlmCall): Record<string, unknown> | null {
  const user = [...call.body.messages]
    .reverse()
    .find((m) => m.role === "user" && m.content.includes("Facts (JSON):"));
  if (user === undefined) return null;
  const json = user.content
    .slice(user.content.indexOf("Facts (JSON):") + "Facts (JSON):".length)
    .trim();
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export async function startFakeLlm(
  wellBehaved: (facts: Record<string, unknown> | null) => string,
): Promise<FakeLlm> {
  const queued: Reply[] = [];
  const hanging = new Set<() => void>();
  const sockets = new Set<Socket>();

  const state: FakeLlm = {
    url: "",
    calls: [],
    queue: (...replies) => {
      queued.push(...replies);
    },
    fallback: (call) => ({ text: wellBehaved(factsOf(call)) }),
    release() {
      for (const release of [...hanging]) release();
    },
    reset() {
      queued.length = 0;
      state.calls.length = 0;
      state.fallback = (call) => ({ text: wellBehaved(factsOf(call)) });
      state.release();
    },
    close: () => closeServer(),
  };

  const server: Server = createServer(async (request, response) => {
    const raw = await readBody(request);
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
      return send(response, 404, { error: { message: "not found" } });
    }
    let body: LlmCall["body"];
    try {
      body = JSON.parse(raw) as LlmCall["body"];
    } catch {
      return send(response, 400, { error: { message: "bad json" } });
    }
    const call: LlmCall = {
      path: request.url,
      authorization: request.headers.authorization,
      contentType: request.headers["content-type"],
      body,
    };
    state.calls.push(call);
    if (call.authorization !== undefined && call.authorization !== `Bearer ${FAKE_LLM_KEY}`) {
      return send(response, 401, { error: { message: "bad key" } });
    }
    const reply = queued.shift() ?? state.fallback(call);
    if ("text" in reply) return send(response, 200, completion(reply.text));
    if ("hang" in reply) {
      await new Promise<void>((resolve) => {
        const release = (): void => {
          hanging.delete(release);
          resolve();
        };
        hanging.add(release);
        response.on("close", release);
      });
      if (!response.destroyed && !response.writableEnded)
        send(response, 500, { error: { message: "released" } });
      return;
    }
    if ("drop" in reply) {
      request.socket.destroy();
      return;
    }
    return send(
      response,
      reply.status,
      reply.body ?? { error: { message: "injected fault" } },
      reply.headers,
    );
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
