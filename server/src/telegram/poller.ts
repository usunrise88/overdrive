import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import type { TenantDirectory } from "../tenancy/directory.ts";
import { OFFSET_KEY, pause, type TelegramBot } from "./bot.ts";
import { type IncomingUpdate, type TelegramClient, TelegramError } from "./client.ts";

/**
 * The one long-polling loop of the shared bot (multi-tenancy, spec §5.4): Telegram allows a single `getUpdates` consumer
 * per bot token, so every user's messages arrive here. Each update is handed to the bot of the user whose chat it
 * came from (`directory.byChat`), which answers it with its own commands over its own data. An update from a chat no
 * user has is logged with the chat id (so a new user's id can be found and entered) and ignored: its content is never
 * logged. The next `offset` is kept in `shared.bot_state`, so a restart neither replays commands nor skips new ones.
 */
export interface TelegramPollerOptions {
  client: TelegramClient;
  /** The `shared` Db: `bot_state` is there. */
  shared: Db;
  directory: TenantDirectory;
  /** The bot of a user (built with `poll: false`); null when that user has none running. */
  botOf: (userId: number) => TelegramBot | null;
  log: Logger;
  /** The command menu, set once at the start. */
  commandList: { command: string; description: string }[];
  /** Seconds each long poll waits (default 25). */
  pollSeconds?: number;
  /** Waits `ms` between failed polls; resolves early when `signal` aborts. For the tests: no waiting. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

export interface TelegramPoller {
  start(): void;
  stop(): Promise<void>;
  /** Routes one update as the loop would. For the tests. */
  dispatch(update: IncomingUpdate): Promise<void>;
}

const reason = (error: unknown): string =>
  error instanceof TelegramError ? error.message : "an unexpected error";

export function createTelegramPoller(options: TelegramPollerOptions): TelegramPoller {
  const { client, shared, directory, botOf, log } = options;
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

  async function dispatch(update: IncomingUpdate): Promise<void> {
    if (update.chatId === null) return;
    const tenant = await directory.byChat(String(update.chatId));
    if (tenant === null) {
      log.warn(
        { updateId: update.updateId, chatId: update.chatId },
        "Telegram message from a chat no user has: it was ignored. To make this chat a user's, set the user's Telegram chat id to this one",
      );
      return;
    }
    const bot = botOf(tenant.userId);
    if (bot === null) {
      log.warn(
        { updateId: update.updateId, userId: tenant.userId },
        "Telegram update for a user whose bot is not running ignored",
      );
      return;
    }
    await bot.handleUpdate(update);
  }

  async function loadOffset(): Promise<number> {
    const result = await shared.query<{ value: string }>(
      "SELECT value FROM bot_state WHERE key = $1",
      [OFFSET_KEY],
    );
    const value = Number(result.rows[0]?.value);
    return Number.isInteger(value) && value > 0 ? value : 0;
  }

  async function saveOffset(offset: number): Promise<void> {
    await shared.query(
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
    // Long polling and a webhook exclude each other, and a menu of commands helps everyone.
    await client.deleteWebhook().catch((error: unknown) => {
      log.warn({ reason: reason(error) }, "Telegram: deleteWebhook failed");
    });
    await client.setCommands(options.commandList).catch((error: unknown) => {
      log.warn({ reason: reason(error) }, "Telegram: the command menu could not be set");
    });
    log.info({}, "Telegram poller started");

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
          await dispatch(update);
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

  return {
    dispatch,
    start() {
      if (!stopped) return;
      stopped = false;
      abort = new AbortController();
      loop = run(abort.signal).catch((error: unknown) => {
        log.error({ err: error }, "the Telegram poller stopped unexpectedly");
      });
    },
    async stop() {
      stopped = true;
      abort?.abort();
      await loop;
      loop = null;
    },
  };
}
