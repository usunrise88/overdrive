import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import { type IncomingUpdate, type TelegramClient, TelegramError } from "./client.ts";
import type { ChatIo, Commands } from "./commands.ts";
import type { Notifier } from "./notifier.ts";

/**
 * The bot's ears (spec §5.4): long polling with `getUpdates`, so no inbound webhook is needed. It
 * answers **only** the owner's chat: an update from any other chat is ignored without a word and logged
 * with its chat id and update id, never its content. The next `offset` is kept in `bot_state`, so a
 * restart neither replays commands nor skips the ones that arrived meanwhile.
 *
 * With no owner chat id configured the bot is in setup mode: it still polls, so that a first message
 * from the owner shows up in the log with the chat id to configure, but it replies to nobody.
 */

export interface BotLoopOptions {
  client: TelegramClient;
  db: Db;
  log: Logger;
  /** The one chat id the bot answers; undefined: setup mode. */
  ownerChatId: string | undefined;
  /** Handlers for the owner's messages and button presses; not used in setup mode. */
  commands: Commands | null;
  /** Runs while the loop runs: the scheduled messages. */
  notifier: Notifier | null;
  /** Seconds each long poll waits (default 25). */
  pollSeconds?: number;
  /** Waits `ms` between failed polls; resolves early when `signal` aborts. For the tests: no waiting. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  commandList: { command: string; description: string }[];
}

export interface TelegramBot {
  start(): void;
  stop(): Promise<void>;
  /** Handles one update as the loop would. For the tests. */
  handleUpdate(update: IncomingUpdate): Promise<void>;
}

const OFFSET_KEY = "telegram.offset";

/** 1, 2, 4 … seconds up to a minute; a refused token or a second poller waits longer. */
function pause(error: unknown, failures: number): number {
  if (error instanceof TelegramError && error.kind === "unauthorized") return 5 * 60_000;
  if (error instanceof TelegramError && error.kind === "conflict") return 30_000;
  return Math.min(1000 * 2 ** Math.max(0, failures - 1), 60_000);
}

export function createTelegramBot(options: BotLoopOptions): TelegramBot {
  const { client, db, log, ownerChatId, commands, notifier } = options;
  const pollSeconds = options.pollSeconds ?? 25;
  const sleep =
    options.sleep ??
    ((ms: number, signal: AbortSignal) =>
      new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, ms);
        signal.addEventListener(
          "abort",
          () => {
            clearTimeout(timer);
            resolve();
          },
          { once: true },
        );
      }));

  let loop: Promise<void> | null = null;
  let abort: AbortController | null = null;
  let stopped = true;

  function ioFor(chatId: number, messageId: number | null): ChatIo {
    return {
      reply: (text, buttons) => client.sendMessage(chatId, text, buttons),
      async edit(text, buttons) {
        if (messageId === null) return client.sendMessage(chatId, text, buttons);
        try {
          await client.editMessage(chatId, messageId, text, buttons);
        } catch (error) {
          // The message is too old to edit, or already says this: a new one is the safe fallback.
          if (error instanceof TelegramError && error.kind === "rejected") {
            if (/not modified/i.test(error.message)) return;
            return client.sendMessage(chatId, text, buttons);
          }
          throw error;
        }
      },
    };
  }

  async function handleUpdate(update: IncomingUpdate): Promise<void> {
    if (ownerChatId === undefined) {
      if (update.chatId !== null) {
        log.warn(
          { updateId: update.updateId, chatId: update.chatId },
          "Telegram message received but TELEGRAM_OWNER_CHAT_ID is not set: it was ignored. To make this chat the owner's, set TELEGRAM_OWNER_CHAT_ID to this chat id",
        );
      }
      return;
    }
    if (update.chatId === null || String(update.chatId) !== ownerChatId) {
      log.warn(
        { updateId: update.updateId, chatId: update.chatId },
        "Telegram update from another chat ignored",
      );
      return;
    }
    if (commands === null) return;
    const chatId = update.chatId;
    try {
      if (update.callback !== null) {
        await client.answerCallback(update.callback.id).catch(() => {});
        const io = ioFor(chatId, update.callback.messageId);
        if (update.callback.data !== null) await commands.onButton(update.callback.data, io);
        return;
      }
      const io = ioFor(chatId, null);
      await commands.onText(update.message?.text ?? "", io);
    } catch (error) {
      log.error(
        { err: error instanceof TelegramError ? error.message : error, updateId: update.updateId },
        "a Telegram command failed",
      );
      await client
        .sendMessage(chatId, "Something went wrong. Try again in a moment.")
        .catch(() => {});
    }
  }

  async function loadOffset(): Promise<number> {
    const result = await db.query<{ value: string }>("SELECT value FROM bot_state WHERE key = $1", [
      OFFSET_KEY,
    ]);
    const value = Number(result.rows[0]?.value);
    return Number.isInteger(value) && value > 0 ? value : 0;
  }

  async function saveOffset(offset: number): Promise<void> {
    await db.query(
      `INSERT INTO bot_state (key, value) VALUES ($1, $2)
       ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
      [OFFSET_KEY, String(offset)],
    );
  }

  async function run(signal: AbortSignal): Promise<void> {
    let offset = 0;
    try {
      offset = await loadOffset();
    } catch (error) {
      log.error({ err: error }, "could not read the Telegram offset");
    }
    // Long polling and a webhook exclude each other, and a menu of commands helps the owner.
    await client.deleteWebhook().catch((error: unknown) => {
      log.warn({ reason: reason(error) }, "Telegram: deleteWebhook failed");
    });
    if (ownerChatId !== undefined) {
      await client.setCommands(options.commandList).catch((error: unknown) => {
        log.warn({ reason: reason(error) }, "Telegram: the command menu could not be set");
      });
    }
    log.info(
      { mode: ownerChatId === undefined ? "setup" : "owner" },
      ownerChatId === undefined
        ? "Telegram bot started in setup mode: it replies to nobody; message it and read the chat id from the log"
        : "Telegram bot started",
    );

    let failures = 0;
    while (!stopped) {
      let updates: IncomingUpdate[];
      try {
        updates = await client.getUpdates({ offset, timeoutSeconds: pollSeconds, signal });
      } catch (error) {
        if (stopped) return;
        failures++;
        // The first failure and then every tenth: a long outage is one line a few minutes, not a flood.
        if (failures === 1 || failures % 10 === 0) {
          log.warn(
            { failures, reason: reason(error) },
            "Telegram polling failed; will keep trying",
          );
        }
        await sleep(pause(error, failures), signal);
        continue;
      }
      if (failures > 0) log.info({ failures }, "Telegram polling works again");
      failures = 0;
      for (const update of updates) {
        if (stopped) return;
        try {
          await handleUpdate(update);
        } catch (error) {
          log.error({ err: error, updateId: update.updateId }, "a Telegram update failed");
        }
        offset = update.updateId + 1;
        await saveOffset(offset).catch((error: unknown) => {
          log.error({ err: error }, "could not save the Telegram offset");
        });
      }
    }
  }

  function reason(error: unknown): string {
    return error instanceof TelegramError ? error.message : "an unexpected error";
  }

  return {
    handleUpdate,
    start() {
      if (!stopped) return;
      stopped = false;
      abort = new AbortController();
      notifier?.start();
      loop = run(abort.signal).catch((error: unknown) => {
        log.error({ err: error }, "the Telegram loop stopped unexpectedly");
      });
    },
    async stop() {
      stopped = true;
      abort?.abort();
      await notifier?.stop();
      await loop;
      loop = null;
    },
  };
}
