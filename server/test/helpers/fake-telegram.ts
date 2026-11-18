import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

/**
 * A fake Telegram Bot API for the tests: long polling `getUpdates`, `sendMessage`, `editMessageText`,
 * `answerCallbackQuery`, `deleteWebhook`, `setMyCommands` and, for a file sent to the bot (ADR 0026), `getFile` and the download
 * at `/file/bot<token>/<path>`, as the public reference describes them (ADR 0019), on a free local port. Everything in it is
 * invented.
 */

export const FAKE_BOT_TOKEN = "123456789:AAFakeTokenForTestsOnly_0123456789abcdefgh";
export const OWNER_CHAT = 424242;
export const STRANGER_CHAT = 999001;

export interface SentMessage {
  id: number;
  chatId: number;
  text: string;
  buttons: { text: string; data: string }[][];
  /** The monospace spans of the message (`entities` of sendMessage), as sent. */
  entities: { type: string; offset: number; length: number }[];
}

export interface Edit {
  chatId: number;
  messageId: number;
  text: string;
  buttons: { text: string; data: string }[][];
}

export interface FakeTelegram {
  /** `http://127.0.0.1:port`. */
  url: string;
  /** Every API call, in order. */
  calls: { method: string; body: Record<string, unknown> }[];
  sent: SentMessage[];
  edits: Edit[];
  answered: string[];
  commands: { command: string; description: string }[];
  /** How many times the webhook was deleted. */
  webhookDeleted: number;
  /** Updates that are still waiting to be confirmed by an offset. */
  pending: number;
  /** The `offset` of every getUpdates, in order. */
  offsets: number[];
  /** Queues a text message from a chat; returns its update id. */
  pushText(chatId: number, text: string): number;
  /** Queues a press of a button under message `messageId`; returns its update id. */
  pushCallback(chatId: number, messageId: number, data: string): number;
  /**
   * Queues a document (a file) from a chat; returns its update id. `size` is what Telegram says the file is (default the bytes').
   * The bytes are served by the download until `reset()`.
   */
  pushDocument(
    chatId: number,
    file: { bytes: Uint8Array; fileName?: string; mimeType?: string; size?: number },
  ): number;
  /** The paths downloaded so far. */
  downloads: string[];
  /** The next `count` calls (of `method`, when given) are answered with `status`. */
  failNext(
    count: number,
    status: number,
    options?: { method?: string; description?: string; retryAfter?: number },
  ): void;
  setUnreachable(unreachable: boolean): void;
  /** Messages sent to a chat. */
  sentTo(chatId: number): SentMessage[];
  callsTo(method: string): { method: string; body: Record<string, unknown> }[];
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
  response.end(JSON.stringify(body));
}

type Buttons = { text: string; callback_data: string }[][];

const toButtons = (markup: unknown): { text: string; data: string }[][] => {
  const rows = (markup as { inline_keyboard?: Buttons } | undefined)?.inline_keyboard ?? [];
  return rows.map((row) => row.map((b) => ({ text: b.text, data: b.callback_data })));
};

export async function startFakeTelegram(): Promise<FakeTelegram> {
  const updates: { update_id: number; [key: string]: unknown }[] = [];
  const waiting = new Set<() => void>();
  const queued: { status: number; method?: string; description?: string; retryAfter?: number }[] =
    [];
  let nextUpdate = 1;
  let nextMessage = 100;
  let nextFile = 1;
  let unreachable = false;
  const files = new Map<string, { path: string; bytes: Buffer; size: number }>();

  const wake = (): void => {
    for (const release of [...waiting]) release();
  };

  const state: FakeTelegram = {
    url: "",
    calls: [],
    sent: [],
    edits: [],
    answered: [],
    commands: [],
    webhookDeleted: 0,
    offsets: [],
    get pending() {
      return updates.length;
    },
    pushText(chatId, text) {
      const id = nextUpdate++;
      updates.push({
        update_id: id,
        message: {
          message_id: nextMessage++,
          date: 1_790_000_000,
          chat: { id: chatId, type: "private" },
          from: { id: chatId, is_bot: false, first_name: "Test" },
          text,
        },
      });
      wake();
      return id;
    },
    pushDocument(chatId, file) {
      const id = nextUpdate++;
      const fileId = `file-id-${nextFile++}`;
      const path = `documents/${fileId}${file.fileName === undefined ? "" : `/${file.fileName}`}`;
      const bytes = Buffer.from(file.bytes);
      files.set(fileId, { path, bytes, size: file.size ?? bytes.length });
      updates.push({
        update_id: id,
        message: {
          message_id: nextMessage++,
          date: 1_790_000_000,
          chat: { id: chatId, type: "private" },
          from: { id: chatId, is_bot: false, first_name: "Test" },
          document: {
            file_id: fileId,
            file_unique_id: `u-${fileId}`,
            ...(file.fileName === undefined ? {} : { file_name: file.fileName }),
            ...(file.mimeType === undefined ? {} : { mime_type: file.mimeType }),
            file_size: file.size ?? bytes.length,
          },
        },
      });
      wake();
      return id;
    },
    downloads: [],
    pushCallback(chatId, messageId, data) {
      const id = nextUpdate++;
      updates.push({
        update_id: id,
        callback_query: {
          id: `cb-${id}`,
          from: { id: chatId, is_bot: false, first_name: "Test" },
          message: { message_id: messageId, chat: { id: chatId, type: "private" } },
          data,
        },
      });
      wake();
      return id;
    },
    failNext(count, status, options = {}) {
      for (let i = 0; i < count; i++) queued.push({ status, ...options });
    },
    setUnreachable(value) {
      unreachable = value;
    },
    sentTo: (chatId) => state.sent.filter((m) => m.chatId === chatId),
    callsTo: (method) => state.calls.filter((c) => c.method === method),
    reset() {
      updates.length = 0;
      state.calls.length = 0;
      state.sent.length = 0;
      state.edits.length = 0;
      state.answered.length = 0;
      state.commands.length = 0;
      state.offsets.length = 0;
      state.downloads.length = 0;
      files.clear();
      nextFile = 1;
      state.webhookDeleted = 0;
      queued.length = 0;
      unreachable = false;
      nextUpdate = 1;
      nextMessage = 100;
      wake();
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
    const download = /^\/file\/bot([^/]+)\/(.+)$/.exec(url.pathname);
    if (download !== null && request.method === "GET") {
      if (download[1] !== FAKE_BOT_TOKEN) {
        return send(response, 401, { ok: false, error_code: 401, description: "Unauthorized" });
      }
      const path = decodeURIComponent(download[2] ?? "");
      const found = [...files.values()].find((f) => f.path === path);
      state.downloads.push(path);
      if (found === undefined) {
        return send(response, 404, { ok: false, error_code: 404, description: "Not Found" });
      }
      response.writeHead(200, {
        "content-type": "application/octet-stream",
        "content-length": String(found.bytes.length),
      });
      response.end(found.bytes);
      return;
    }
    const raw = await readBody(request);
    const match = /^\/bot([^/]+)\/([A-Za-z]+)$/.exec(url.pathname);
    if (match === null || request.method !== "POST") {
      return send(response, 404, { ok: false, error_code: 404, description: "Not Found" });
    }
    const [, token, method = ""] = match;
    if (token !== FAKE_BOT_TOKEN) {
      return send(response, 401, { ok: false, error_code: 401, description: "Unauthorized" });
    }
    const body = (raw === "" ? {} : JSON.parse(raw)) as Record<string, unknown>;
    state.calls.push({ method, body });

    const queuedIndex = queued.findIndex(
      (fault) => fault.method === undefined || fault.method === method,
    );
    const fault = queuedIndex < 0 ? undefined : queued.splice(queuedIndex, 1)[0];
    if (fault !== undefined) {
      return send(response, fault.status, {
        ok: false,
        error_code: fault.status,
        description: fault.description ?? "injected fault",
        ...(fault.retryAfter === undefined
          ? {}
          : { parameters: { retry_after: fault.retryAfter } }),
      });
    }

    switch (method) {
      case "getUpdates": {
        const offset = Number(body.offset ?? 0);
        state.offsets.push(offset);
        // Updates before the offset are confirmed: Telegram forgets them.
        while (updates.length > 0 && (updates[0]?.update_id ?? Number.POSITIVE_INFINITY) < offset) {
          updates.shift();
        }
        const holdMs = Math.max(0, Number(body.timeout ?? 0)) * 1000;
        if (updates.length === 0 && holdMs > 0) {
          await new Promise<void>((resolve) => {
            const release = (): void => {
              clearTimeout(timer);
              waiting.delete(release);
              resolve();
            };
            const timer = setTimeout(release, holdMs);
            waiting.add(release);
            response.on("close", release);
          });
        }
        if (response.destroyed || response.writableEnded) return;
        return send(response, 200, { ok: true, result: updates.map((u) => u) });
      }
      case "sendMessage": {
        const message: SentMessage = {
          id: nextMessage++,
          chatId: Number(body.chat_id),
          text: String(body.text),
          buttons: toButtons(body.reply_markup),
          entities: (body.entities ?? []) as SentMessage["entities"],
        };
        state.sent.push(message);
        return send(response, 200, { ok: true, result: { message_id: message.id } });
      }
      case "editMessageText": {
        state.edits.push({
          chatId: Number(body.chat_id),
          messageId: Number(body.message_id),
          text: String(body.text),
          buttons: toButtons(body.reply_markup),
        });
        return send(response, 200, { ok: true, result: true });
      }
      case "answerCallbackQuery":
        state.answered.push(String(body.callback_query_id));
        return send(response, 200, { ok: true, result: true });
      case "deleteWebhook":
        state.webhookDeleted++;
        return send(response, 200, { ok: true, result: true });
      case "getFile": {
        const found = files.get(String(body.file_id));
        if (found === undefined) {
          return send(response, 400, {
            ok: false,
            error_code: 400,
            description: "Bad Request: wrong file_id",
          });
        }
        return send(response, 200, {
          ok: true,
          result: { file_id: String(body.file_id), file_size: found.size, file_path: found.path },
        });
      }
      case "setMyCommands":
        state.commands = (body.commands ?? []) as typeof state.commands;
        return send(response, 200, { ok: true, result: true });
      default:
        return send(response, 404, { ok: false, error_code: 404, description: "Not Found" });
    }
  });

  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  state.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

  function closeServer(): Promise<void> {
    return new Promise((resolve) => {
      wake();
      for (const socket of sockets) socket.destroy();
      server.close(() => resolve());
    });
  }
  return state;
}
