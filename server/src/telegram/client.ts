import { z } from "zod";
import { type Logger, silentLogger } from "../log.ts";
import type { MessageEntity } from "../notifications.ts";

/**
 * A small client of the Telegram Bot API (ADR 0019), written from its public reference with no bot to
 * verify against: plain `fetch`, no framework. The bot token is part of every URL (that is how the API
 * authenticates), so it is kept out of everything else: no error message, log line or thrown value
 * carries a URL, and anything Telegram says back is scrubbed of the token before it is kept.
 */

export type TelegramErrorKind =
  /** 401 (and 404, which is what Telegram answers for a token it does not know). */
  | "unauthorized"
  /** 403: the owner blocked the bot, or removed it from the chat. */
  | "forbidden"
  /** 409: a webhook is set, or another process is polling with this token. */
  | "conflict"
  /** Another 4xx: the request itself was refused, trying again cannot help. */
  | "rejected"
  /** 429. */
  | "rate-limited"
  /** 5xx on every attempt. */
  | "server"
  /** No answer: connection refused, DNS, TLS, timeout. */
  | "unreachable"
  /** A success status with a body this client cannot use. */
  | "bad-response";

export class TelegramError extends Error {
  readonly kind: TelegramErrorKind;
  readonly status: number | null;
  /** Seconds Telegram asked us to wait (429), or null. */
  readonly retryAfter: number | null;

  constructor(
    kind: TelegramErrorKind,
    message: string,
    status: number | null,
    retryAfter: number | null = null,
  ) {
    super(message);
    this.name = "TelegramError";
    this.kind = kind;
    this.status = status;
    this.retryAfter = retryAfter;
  }

  /** The configuration is wrong (token): it needs the owner, not just time. */
  get configuration(): boolean {
    return this.kind === "unauthorized";
  }
}

/** A button under a message; pressing it sends `data` back (at most 64 bytes). */
export interface InlineButton {
  text: string;
  data: string;
}

/** An update, reduced to what the bot reads. */
export interface IncomingUpdate {
  updateId: number;
  /** The chat it came from, whatever the kind of update; null when it has none we understand. */
  chatId: number | null;
  /** Set for a message. `text` is null for photos, stickers and the like. */
  message: { text: string | null } | null;
  /** Set for a press of an inline button. */
  callback: { id: string; messageId: number | null; data: string | null } | null;
}

export interface BotCommandInfo {
  command: string;
  description: string;
}

export interface TelegramClient {
  /** Long poll: waits up to `timeoutSeconds` for updates after `offset`. */
  getUpdates(options: {
    offset: number;
    timeoutSeconds: number;
    signal?: AbortSignal;
  }): Promise<IncomingUpdate[]>;
  /**
   * Sends plain text. `entities` mark spans as monospace (`pre`, a block the reader can tap to copy): with them there is no
   * parse mode and so nothing to escape.
   */
  sendMessage(
    chatId: number | string,
    text: string,
    buttons?: InlineButton[][],
    entities?: readonly MessageEntity[],
  ): Promise<void>;
  /** Replaces a message's text and buttons (no buttons: they are removed). */
  editMessage(
    chatId: number | string,
    messageId: number,
    text: string,
    buttons?: InlineButton[][],
  ): Promise<void>;
  /** Stops the spinner on a pressed button. */
  answerCallback(callbackId: string): Promise<void>;
  /** Long polling and a webhook exclude each other. */
  deleteWebhook(): Promise<void>;
  /** The menu the Telegram client shows. */
  setCommands(commands: BotCommandInfo[]): Promise<void>;
}

export interface TelegramClientOptions {
  /** `https://api.telegram.org`, or the fake of the tests. */
  baseUrl: string;
  token: string;
  fetch?: typeof fetch;
  /** Per request that is not a long poll, in milliseconds (default 20 s). */
  timeoutMs?: number;
  /** Attempts in all for a message (default 3). */
  maxAttempts?: number;
  backoffMs?: number;
  /** Longest wait between attempts, including Telegram's `retry_after` (default 30 s). */
  maxBackoffMs?: number;
  sleep?: (ms: number) => Promise<void>;
  log?: Logger;
}

const DEFAULT_TIMEOUT_MS = 20_000;
const MAX_TEXT = 4000;
const MAX_DETAIL = 200;

const updateSchema = z.looseObject({
  update_id: z.number().int(),
  message: z
    .looseObject({
      chat: z.looseObject({ id: z.number() }),
      text: z.string().optional(),
    })
    .optional(),
  callback_query: z
    .looseObject({
      id: z.string(),
      from: z.looseObject({ id: z.number() }).optional(),
      message: z
        .looseObject({
          message_id: z.number().int(),
          chat: z.looseObject({ id: z.number() }),
        })
        .optional(),
      data: z.string().optional(),
    })
    .optional(),
});

function describeFailure(cause: unknown): string {
  if (cause instanceof Error) {
    if (cause.name === "TimeoutError" || cause.name === "AbortError") return "timed out";
    const code = (cause.cause as { code?: unknown } | undefined)?.code;
    return typeof code === "string" ? code : "the connection failed";
  }
  return "the connection failed";
}

function keyboard(buttons: InlineButton[][] | undefined): object | undefined {
  if (buttons === undefined || buttons.length === 0) return undefined;
  return {
    inline_keyboard: buttons.map((row) =>
      row.map((button) => ({ text: button.text, callback_data: button.data })),
    ),
  };
}

export function createTelegramClient(options: TelegramClientOptions): TelegramClient {
  const base = `${options.baseUrl.replace(/\/+$/, "")}/bot${options.token}`;
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const maxAttempts = options.maxAttempts ?? 3;
  const backoffMs = options.backoffMs ?? 500;
  const maxBackoffMs = options.maxBackoffMs ?? 30_000;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const log = options.log ?? silentLogger;

  const scrub = (text: string): string =>
    text.split(options.token).join("[token]").replace(/\s+/g, " ").trim().slice(0, MAX_DETAIL);

  /** One request. Resolves with the `result` of an `ok` answer. */
  async function request(
    method: string,
    params: Record<string, unknown>,
    attempt: { signal?: AbortSignal; timeoutMs: number },
  ): Promise<unknown> {
    let response: Response;
    try {
      response = await doFetch(`${base}/${method}`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        body: JSON.stringify(params),
        // A redirect would carry the token somewhere we did not choose.
        redirect: "error",
        signal:
          attempt.signal === undefined
            ? AbortSignal.timeout(attempt.timeoutMs)
            : AbortSignal.any([attempt.signal, AbortSignal.timeout(attempt.timeoutMs)]),
      });
    } catch (cause) {
      throw new TelegramError(
        "unreachable",
        `Telegram did not answer (${describeFailure(cause)})`,
        null,
      );
    }
    const text = await response.text().catch(() => "");
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      body = undefined;
    }
    const fields = (typeof body === "object" && body !== null ? body : {}) as {
      ok?: unknown;
      result?: unknown;
      description?: unknown;
      parameters?: { retry_after?: unknown };
    };
    if (response.status === 200 && fields.ok === true) return fields.result;

    const status = response.status;
    const detail = scrub(typeof fields.description === "string" ? fields.description : text);
    const retryAfter =
      typeof fields.parameters?.retry_after === "number" ? fields.parameters.retry_after : null;
    const why = detail === "" ? "" : `: ${detail}`;
    if (status === 200) {
      throw new TelegramError("bad-response", "Telegram answered with something unreadable", 200);
    }
    if (status === 401 || status === 404) {
      throw new TelegramError(
        "unauthorized",
        `Telegram did not accept TELEGRAM_BOT_TOKEN (HTTP ${status})`,
        status,
      );
    }
    if (status === 403) {
      throw new TelegramError("forbidden", `Telegram refused the request (HTTP 403${why})`, status);
    }
    if (status === 409) {
      throw new TelegramError("conflict", `Telegram reports a conflict (HTTP 409${why})`, status);
    }
    if (status === 429) {
      throw new TelegramError(
        "rate-limited",
        "Telegram is limiting requests (HTTP 429)",
        status,
        retryAfter,
      );
    }
    if (status >= 500) {
      throw new TelegramError("server", `Telegram failed to answer (HTTP ${status})`, status);
    }
    throw new TelegramError(
      "rejected",
      `Telegram refused the request (HTTP ${status}${why})`,
      status,
    );
  }

  /** A request that may be repeated: waits out a 429, and repeats after a network error or a 5xx. */
  async function send(method: string, params: Record<string, unknown>): Promise<unknown> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await request(method, params, { timeoutMs });
      } catch (error) {
        const known = error instanceof TelegramError ? error : null;
        const retriable =
          known !== null &&
          (known.kind === "rate-limited" ||
            known.kind === "server" ||
            known.kind === "unreachable");
        if (!retriable || attempt >= maxAttempts) throw error;
        const wait =
          known.retryAfter !== null
            ? Math.min(known.retryAfter * 1000, maxBackoffMs)
            : Math.min(backoffMs * 2 ** (attempt - 1), maxBackoffMs);
        log.debug({ attempt, kind: known.kind }, "Telegram request will be repeated");
        await sleep(wait);
      }
    }
  }

  const clip = (text: string): string =>
    text.length <= MAX_TEXT ? text : `${text.slice(0, MAX_TEXT - 1)}…`;

  return {
    async getUpdates({ offset, timeoutSeconds, signal }) {
      const result = await request(
        "getUpdates",
        {
          offset,
          timeout: timeoutSeconds,
          allowed_updates: ["message", "callback_query"],
        },
        {
          timeoutMs: (timeoutSeconds + 15) * 1000,
          ...(signal === undefined ? {} : { signal }),
        },
      );
      if (!Array.isArray(result)) {
        throw new TelegramError(
          "bad-response",
          "Telegram did not answer with a list of updates",
          200,
        );
      }
      const updates: IncomingUpdate[] = [];
      for (const raw of result) {
        const parsed = updateSchema.safeParse(raw);
        if (!parsed.success) {
          // Keep the position: the offset still has to move past it.
          const id = (raw as { update_id?: unknown } | null)?.update_id;
          if (typeof id === "number") {
            updates.push({ updateId: id, chatId: null, message: null, callback: null });
          }
          continue;
        }
        const update = parsed.data;
        updates.push({
          updateId: update.update_id,
          chatId:
            update.message?.chat.id ??
            update.callback_query?.message?.chat.id ??
            update.callback_query?.from?.id ??
            null,
          message: update.message === undefined ? null : { text: update.message.text ?? null },
          callback:
            update.callback_query === undefined
              ? null
              : {
                  id: update.callback_query.id,
                  messageId: update.callback_query.message?.message_id ?? null,
                  data: update.callback_query.data ?? null,
                },
        });
      }
      return updates;
    },

    async sendMessage(chatId, text, buttons, entities) {
      const markup = keyboard(buttons);
      const sent = clip(text);
      // A span cut off by the clip would be refused: leave out what no longer fits.
      const spans = (entities ?? []).filter((e) => e.offset + e.length <= sent.length);
      await send("sendMessage", {
        chat_id: chatId,
        text: sent,
        ...(spans.length === 0
          ? {}
          : { entities: spans.map((e) => ({ type: e.type, offset: e.offset, length: e.length })) }),
        // Plain text: names of workouts contain `_` and `*`, which Markdown would swallow.
        disable_web_page_preview: true,
        ...(markup === undefined ? {} : { reply_markup: markup }),
      });
    },

    async editMessage(chatId, messageId, text, buttons) {
      const markup = keyboard(buttons);
      await send("editMessageText", {
        chat_id: chatId,
        message_id: messageId,
        text: clip(text),
        disable_web_page_preview: true,
        // Without buttons the old ones must be removed explicitly.
        reply_markup: markup ?? { inline_keyboard: [] },
      });
    },

    async answerCallback(callbackId) {
      await send("answerCallbackQuery", { callback_query_id: callbackId });
    },

    async deleteWebhook() {
      await send("deleteWebhook", { drop_pending_updates: false });
    },

    async setCommands(commands) {
      await send("setMyCommands", { commands });
    },
  };
}
