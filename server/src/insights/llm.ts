import { type Logger, silentLogger } from "../log.ts";

/**
 * A client of an OpenAI-compatible chat-completions endpoint (spec §3, §6.5, ADR 0025): `POST {LLM_BASE_URL}/chat/completions` with a
 * bearer key, plain `fetch`, no SDK. It is the ONLY place health data leaves the server, and only to the address the owner set.
 *
 * What it never does: log a URL, a header, a prompt or a reply (the prompt is the rider's health data and the reply is written
 * from it; a server's error body may echo either), put any of them in an error, or follow a redirect (the key would go with it).
 * A failure is a kind and a status code and nothing else. It retries what may pass (a network error, a timeout, 429, 5xx, an
 * unreadable answer) with a growing wait, and gives up at once on what will not (a refused key, a refused request).
 *
 * Written from the public description of the API and tested against a fake server that follows it; whether the owner's endpoint
 * (MiniMax M3.1 or another) accepts every field is for the first real note to settle (ADR 0025, what only a real endpoint settles).
 */

export interface LlmMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export type LlmFailureKind =
  /** No answer in time. */
  | "timeout"
  /** Connection refused, DNS, TLS. */
  | "unreachable"
  /** 401 or 403: the key is not accepted. */
  | "unauthorized"
  /** Another 4xx (404 for a wrong address or model, 400 or 422 for a request it refuses): trying again cannot help. */
  | "rejected"
  /** 408 or 429. */
  | "rate-limited"
  /** 5xx. */
  | "server"
  /** A success status with an answer that has no text. */
  | "bad-response";

export type LlmResult =
  | { ok: true; text: string }
  | { ok: false; kind: LlmFailureKind; status: number | null };

export interface LlmClient {
  /** The model name the requests carry. */
  readonly model: string;
  /** One completion; never throws. Retries what may pass, up to `maxAttempts` requests in all. */
  complete(messages: readonly LlmMessage[]): Promise<LlmResult>;
}

export interface LlmClientOptions {
  /** `https://api.example.com/v1`: the address the path `/chat/completions` is added to. */
  baseUrl: string;
  /** Sent as `Authorization: Bearer …` when set. */
  apiKey?: string | undefined;
  model: string;
  /** Per request, in milliseconds. */
  timeoutMs: number;
  /** Requests in all for one completion (default 3). */
  maxAttempts?: number;
  /** The first wait between requests (default 1 s), doubled each time. */
  backoffMs?: number;
  /** The longest wait, including a `Retry-After` (default 20 s). */
  maxBackoffMs?: number;
  maxTokens?: number;
  temperature?: number;
  fetch?: typeof fetch | undefined;
  sleep?: ((ms: number) => Promise<void>) | undefined;
  log?: Logger | undefined;
}

const MAX_BODY = 1_000_000;
const THINK = /<think>[\s\S]*?<\/think>/gi;
const OPEN_THINK = /^[\s\S]*<\/think>/i;

/** A reasoning model may put its thinking before the answer; it is not the note. */
export function stripReasoning(text: string): string {
  const closed = text.replace(THINK, "");
  // A reply cut off after its reasoning, or one that starts inside it, leaves a stray closing tag.
  return closed.replace(OPEN_THINK, "").trim();
}

/** The text of a chat-completions answer, or null when it has none. */
export function textOfCompletion(body: unknown): string | null {
  if (typeof body !== "object" || body === null) return null;
  const choices = (body as { choices?: unknown }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const message = (choices[0] as { message?: { content?: unknown } } | null)?.message;
  const content = message?.content;
  let text: string | null = null;
  if (typeof content === "string") text = content;
  else if (Array.isArray(content)) {
    text = content
      .map((part) =>
        typeof part === "object" &&
        part !== null &&
        typeof (part as { text?: unknown }).text === "string"
          ? (part as { text: string }).text
          : "",
      )
      .join("");
  }
  if (text === null) return null;
  const answer = stripReasoning(text);
  return answer === "" ? null : answer;
}

function retryAfterMs(header: string | null, cap: number): number | null {
  if (header === null) return null;
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds < 0) return null;
  return Math.min(seconds * 1000, cap);
}

export function createLlmClient(options: LlmClientOptions): LlmClient {
  const endpoint = `${options.baseUrl.replace(/\/+$/, "")}/chat/completions`;
  const doFetch = options.fetch ?? fetch;
  const maxAttempts = Math.max(1, options.maxAttempts ?? 3);
  const backoffMs = options.backoffMs ?? 1000;
  const maxBackoffMs = options.maxBackoffMs ?? 20_000;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const log = options.log ?? silentLogger;

  /** One request. */
  async function attempt(
    messages: readonly LlmMessage[],
  ): Promise<LlmResult & { retryAfterMs?: number | null }> {
    let response: Response;
    try {
      response = await doFetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json",
          ...(options.apiKey === undefined ? {} : { authorization: `Bearer ${options.apiKey}` }),
        },
        body: JSON.stringify({
          model: options.model,
          messages,
          temperature: options.temperature ?? 0.3,
          max_tokens: options.maxTokens ?? 800,
          stream: false,
        }),
        // A redirect would carry the key and the health data somewhere the owner did not choose.
        redirect: "error",
        signal: AbortSignal.timeout(options.timeoutMs),
      });
    } catch (cause) {
      const name = cause instanceof Error ? cause.name : "";
      return {
        ok: false,
        kind: name === "TimeoutError" || name === "AbortError" ? "timeout" : "unreachable",
        status: null,
      };
    }
    const status = response.status;
    if (status === 401 || status === 403) return { ok: false, kind: "unauthorized", status };
    if (status === 408 || status === 429) {
      return {
        ok: false,
        kind: "rate-limited",
        status,
        retryAfterMs: retryAfterMs(response.headers.get("retry-after"), maxBackoffMs),
      };
    }
    if (status >= 500) return { ok: false, kind: "server", status };
    if (status < 200 || status >= 300) return { ok: false, kind: "rejected", status };
    let raw: string;
    try {
      raw = await response.text();
    } catch {
      return { ok: false, kind: "unreachable", status };
    }
    if (raw.length > MAX_BODY) return { ok: false, kind: "bad-response", status };
    let body: unknown;
    try {
      body = JSON.parse(raw);
    } catch {
      return { ok: false, kind: "bad-response", status };
    }
    const text = textOfCompletion(body);
    return text === null ? { ok: false, kind: "bad-response", status } : { ok: true, text };
  }

  const permanent = new Set<LlmFailureKind>(["unauthorized", "rejected"]);

  return {
    model: options.model,
    async complete(messages) {
      let last: LlmResult = { ok: false, kind: "unreachable", status: null };
      for (let n = 1; n <= maxAttempts; n++) {
        const result = await attempt(messages);
        if (result.ok) return result;
        last = { ok: false, kind: result.kind, status: result.status };
        if (permanent.has(result.kind) || n === maxAttempts) {
          log.warn(
            { kind: result.kind, status: result.status, attempts: n },
            "the language model did not answer",
          );
          return last;
        }
        const wait = Math.min(result.retryAfterMs ?? backoffMs * 2 ** (n - 1), maxBackoffMs);
        log.debug(
          { kind: result.kind, status: result.status, attempt: n },
          "the language model request will be repeated",
        );
        await sleep(wait);
      }
      return last;
    },
  };
}
