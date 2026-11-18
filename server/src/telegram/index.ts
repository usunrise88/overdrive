import {
  DEFAULT_REMINDER_EVENING_TIME,
  DEFAULT_REMINDER_LEAD_MINUTES,
  DEFAULT_TELEGRAM_BASE_URL,
  DEFAULT_WEEKLY_SUMMARY_DAY,
  DEFAULT_WEEKLY_SUMMARY_TIME,
  DEFAULT_WORKOUT_START,
  type IntegrationsSettings,
  weeklyDayNumber,
} from "../config-integrations.ts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { IntervalsClient } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import type { NotificationSource } from "../notifications.ts";
import type { PlanRefresher } from "../plan/refresher.ts";
import type { PlanStore } from "../plan/store.ts";
import type { Timers } from "../timers.ts";
import { type Clock, parseClock } from "../zoned.ts";
import { createTelegramBot, type TelegramBot } from "./bot.ts";
import { createTelegramClient } from "./client.ts";
import { type Commands, createCommands } from "./commands.ts";
import type { BotContext } from "./context.ts";
import { createPlanHistory, type PlanHistory } from "./history.ts";
import { createNotifier, type Notifier } from "./notifier.ts";
import { createRideQueries } from "./rides.ts";

export type { TelegramBot } from "./bot.ts";

export interface TelegramDeps {
  config: IntegrationsSettings;
  db: Db;
  plan: PlanStore;
  bus: EventBus;
  /** null when no plan source is set up. */
  intervals: IntervalsClient | null;
  /** null when no plan source is set up. */
  refresher: PlanRefresher | null;
  log: Logger;
  timezone: string;
  now?: (() => Date) | undefined;
  fetch?: typeof fetch | undefined;
  sleep?: ((ms: number) => Promise<void>) | undefined;
  /** For the tests. */
  botSleep?: ((ms: number, signal: AbortSignal) => Promise<void>) | undefined;
  timers?: Timers | undefined;
  /** Seconds each long poll waits; the tests use a short one. */
  pollSeconds?: number | undefined;
  /** The plan history, when the service shares one with the insights (ADR 0025); otherwise the bot keeps its own. */
  history?: PlanHistory | undefined;
  /** Other modules' messages for the owner: the written insights and alerts (ADR 0025). */
  sources?: readonly NotificationSource[] | undefined;
}

const COMMANDS = [
  { command: "today", description: "Today's workout" },
  { command: "week", description: "The next 7 days" },
  { command: "skip", description: "Skip the next workout" },
  { command: "move", description: "Move the next workout to another day" },
  { command: "help", description: "What I can do" },
];

const clockOf = (text: string | undefined, fallback: string): Clock =>
  parseClock(text ?? fallback) ?? { hour: 0, minute: 0 };

/**
 * The Telegram bot, or null when it is off (spec §5.4, ADR 0019). It is on when `TELEGRAM_BOT_TOKEN` is
 * set. With `TELEGRAM_OWNER_CHAT_ID` as well it answers that chat and sends the reminders and summaries;
 * without it the bot only listens and logs the chat ids that write to it, so the owner can find theirs.
 */
export function createTelegram(deps: TelegramDeps): TelegramBot | null {
  const { config, log } = deps;
  const token = config.TELEGRAM_BOT_TOKEN;
  if (token === undefined) {
    if (config.TELEGRAM_OWNER_CHAT_ID !== undefined) {
      log.warn({}, "Telegram is off: TELEGRAM_OWNER_CHAT_ID is set but TELEGRAM_BOT_TOKEN is not");
    } else {
      log.info(
        {},
        "Telegram is not configured (TELEGRAM_BOT_TOKEN is not set): no bot, no reminders",
      );
    }
    return null;
  }
  const ownerChatId = config.TELEGRAM_OWNER_CHAT_ID;
  const now = deps.now ?? (() => new Date());

  const client = createTelegramClient({
    baseUrl: config.TELEGRAM_BASE_URL ?? DEFAULT_TELEGRAM_BASE_URL,
    token,
    log,
    ...(deps.fetch === undefined ? {} : { fetch: deps.fetch }),
    ...(deps.sleep === undefined ? {} : { sleep: deps.sleep }),
  });

  let commands: Commands | null = null;
  let notifier: Notifier | null = null;
  if (ownerChatId !== undefined) {
    const history =
      deps.history ??
      createPlanHistory({
        db: deps.db,
        plan: deps.plan,
        timezone: deps.timezone,
        now,
      });
    const ctx: BotContext = {
      plan: deps.plan,
      history,
      rides: createRideQueries(deps.db),
      intervals: deps.intervals,
      refresher: deps.refresher,
      timezone: deps.timezone,
      workoutStart: clockOf(config.GCAL_WORKOUT_START, DEFAULT_WORKOUT_START),
      now,
      log,
    };
    commands = createCommands(ctx);
    const evening = config.REMINDER_EVENING_TIME ?? DEFAULT_REMINDER_EVENING_TIME;
    const weekly = config.WEEKLY_SUMMARY_TIME ?? DEFAULT_WEEKLY_SUMMARY_TIME;
    notifier = createNotifier({
      ctx,
      db: deps.db,
      bus: deps.bus,
      deliver: (text, buttons, entities) =>
        client.sendMessage(ownerChatId, text, buttons, entities),
      ...(deps.sources === undefined ? {} : { sources: deps.sources }),
      config: {
        eveningTime: evening === "off" ? null : clockOf(evening, DEFAULT_REMINDER_EVENING_TIME),
        leadMinutes: config.REMINDER_LEAD_MINUTES ?? DEFAULT_REMINDER_LEAD_MINUTES,
        weeklyDay: weeklyDayNumber(config.WEEKLY_SUMMARY_DAY ?? DEFAULT_WEEKLY_SUMMARY_DAY),
        weeklyTime: weekly === "off" ? null : clockOf(weekly, DEFAULT_WEEKLY_SUMMARY_TIME),
      },
      ...(deps.timers === undefined ? {} : { timers: deps.timers }),
    });
  }

  return createTelegramBot({
    client,
    db: deps.db,
    log,
    ownerChatId,
    commands,
    notifier,
    commandList: COMMANDS,
    ...(deps.pollSeconds === undefined ? {} : { pollSeconds: deps.pollSeconds }),
    ...(deps.botSleep === undefined ? {} : { sleep: deps.botSleep }),
  });
}
