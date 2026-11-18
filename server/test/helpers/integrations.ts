import type { FastifyInstance } from "fastify";
import { buildApp } from "../../src/app.ts";
import type { Db } from "../../src/db.ts";
import { createServices, type Services, type ServicesConfig } from "../../src/services.ts";
import { COMPANION, TABLET } from "./app.ts";
import {
  FAKE_CALENDAR_ID,
  FAKE_CLIENT_ID,
  FAKE_CLIENT_SECRET,
  FAKE_REFRESH_TOKEN,
  type FakeGoogle,
} from "./fake-google.ts";
import { FAKE_API_KEY, FAKE_ATHLETE_ID, type FakeIntervals } from "./fake-intervals.ts";
import { FAKE_BOT_TOKEN, type FakeTelegram, OWNER_CHAT } from "./fake-telegram.ts";
import { plannedEvent, steady } from "./harness.ts";
import {
  collectingLogger,
  type ManualTimers,
  manualTimers,
  recordedSleep,
  type TestClock,
  testClock,
} from "./support.ts";

export interface IntegrationHarness {
  app: FastifyInstance;
  services: Services;
  clock: TestClock;
  logging: ReturnType<typeof collectingLogger>;
  timers: ManualTimers;
  /** Starts every loop (`services.start()`); the test must `await h.app.close()` (or `stop`) when done. */
  start(): void;
}

export interface IntegrationOptions {
  /** The starting instant; default 2026-11-18 08:00 UTC (09:00 in Paris, a Wednesday). */
  now?: string;
  timezone?: string;
  /** A fake intervals.icu: the plan source is on. Without it there is no plan source. */
  intervals?: FakeIntervals;
  /** A fake Google: the calendar is on. */
  google?: FakeGoogle;
  /** A fake Telegram: the bot is on; `owner: false` leaves out TELEGRAM_OWNER_CHAT_ID (setup mode). */
  telegram?: FakeTelegram;
  owner?: boolean;
  /** More settings, overriding the above (REMINDER_*, GCAL_WORKOUT_START, …). */
  config?: ServicesConfig;
}

/** Plans a 40-minute workout (10 min at 60%, 20 at 88%, 10 at 50% of FTP) on `date` in the fake intervals.icu; returns the event id. */
export function planWorkout(fake: FakeIntervals, date: string, name = "Sweet spot"): number {
  return fake.addEvent(
    plannedEvent(date, name, [steady(600, 60), steady(1200, 88), steady(600, 50)]),
  ).id;
}

/** Empties what the calendar mirror and the bot keep, leaving the schema. */
export async function clearIntegrations(db: Db): Promise<void> {
  await db.exec("TRUNCATE calendar_events, plan_history, bot_notifications, bot_state");
}

/**
 * The whole service against the fakes: real services and routes over the given database, the clients
 * pointed at the fakes, a clock and loops' ticks the test controls. Nothing runs in the background
 * until `start()`.
 */
export function startIntegrationHarness(
  db: Db,
  options: IntegrationOptions = {},
): IntegrationHarness {
  const clock = testClock(options.now ?? "2026-11-18T08:00:00Z");
  const logging = collectingLogger();
  const sleeping = recordedSleep();
  const timers = manualTimers();
  const config: ServicesConfig = {
    // The written insights have their own tests (insights-*.test.ts, which turn them on); the others must not hear their notes.
    INSIGHTS_ENABLED: false,
    TIMEZONE: options.timezone ?? "Europe/Paris",
    ...(options.intervals === undefined
      ? {}
      : {
          INTERVALS_API_KEY: FAKE_API_KEY,
          INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
          INTERVALS_BASE_URL: options.intervals.url,
        }),
    ...(options.google === undefined
      ? {}
      : {
          GOOGLE_CLIENT_ID: FAKE_CLIENT_ID,
          GOOGLE_CLIENT_SECRET: FAKE_CLIENT_SECRET,
          GOOGLE_REFRESH_TOKEN: FAKE_REFRESH_TOKEN,
          GOOGLE_CALENDAR_ID: FAKE_CALENDAR_ID,
          GOOGLE_API_BASE_URL: options.google.apiUrl,
          GOOGLE_TOKEN_URL: options.google.tokenUrl,
        }),
    ...(options.telegram === undefined
      ? {}
      : {
          TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
          TELEGRAM_BASE_URL: options.telegram.url,
          ...(options.owner === false ? {} : { TELEGRAM_OWNER_CHAT_ID: String(OWNER_CHAT) }),
        }),
    ...options.config,
  };
  const services = createServices({
    config,
    db,
    log: logging.log,
    now: clock.now,
    sleep: sleeping.sleep,
    timers,
  });
  const app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET, COMPANION] },
    db,
    services,
  });
  return { app, services, clock, logging, timers, start: () => services.start() };
}
