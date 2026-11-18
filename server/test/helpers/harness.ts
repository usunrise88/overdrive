import type { FastifyInstance } from "fastify";
import { buildApp } from "../../src/app.ts";
import type { Db } from "../../src/db.ts";
import { createServices, type Services } from "../../src/services.ts";
import { COMPANION, TABLET } from "./app.ts";
import {
  FAKE_API_KEY,
  FAKE_ATHLETE_ID,
  type FakeEvent,
  type FakeIntervals,
} from "./fake-intervals.ts";
import {
  collectingLogger,
  type ManualTimers,
  recordedSleep,
  type TestClock,
  testClock,
} from "./support.ts";

export interface Harness {
  app: FastifyInstance;
  services: Services;
  clock: TestClock;
  /** What the services logged (not the HTTP server). */
  logging: ReturnType<typeof collectingLogger>;
  /** The waits the intervals.icu client asked for between retries (it does not wait). */
  waits: number[];
}

export interface HarnessOptions {
  /** The starting instant; default 2026-11-18 08:00 UTC. */
  now?: string;
  timezone?: string;
  /** false: no intervals.icu key, the integration is off. */
  configured?: boolean;
  /** The loops' ticks by hand; without it they use the real timers (and are not started anyway). */
  timers?: ManualTimers;
  /** `PLAN_REFRESH_MINUTES`. */
  refreshMinutes?: number;
}

/**
 * The whole service against the fake intervals.icu: real services and routes over the given
 * database, the client pointed at `fake`, a clock and a sleep the test controls. Background loops
 * are not started; the tests call the worker and the refresher themselves.
 */
export function startHarness(db: Db, fake: FakeIntervals, options: HarnessOptions = {}): Harness {
  const clock = testClock(options.now ?? "2026-11-18T08:00:00Z");
  const logging = collectingLogger();
  const sleeping = recordedSleep();
  const services = createServices({
    config: {
      // The written insights have their own tests; these harnesses test other things and must not hear their notes.
      INSIGHTS_ENABLED: false,
      ...(options.configured === false ? {} : { INTERVALS_API_KEY: FAKE_API_KEY }),
      INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
      INTERVALS_BASE_URL: fake.url,
      TIMEZONE: options.timezone ?? "Europe/Paris",
      ...(options.refreshMinutes === undefined
        ? {}
        : { PLAN_REFRESH_MINUTES: options.refreshMinutes }),
    },
    db,
    log: logging.log,
    now: clock.now,
    sleep: sleeping.sleep,
    ...(options.timers === undefined ? {} : { timers: options.timers }),
  });
  const app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET, COMPANION] },
    db,
    services,
  });
  return { app, services, clock, logging, waits: sleeping.waits };
}

/** A calendar event for a workout with these parsed steps, as the owner would have planned it. */
export function plannedEvent(
  date: string,
  name: string,
  steps: unknown[],
  extra: Record<string, unknown> = {},
): Omit<FakeEvent, "id"> {
  return {
    category: "WORKOUT",
    type: "Ride",
    start_date_local: `${date}T00:00:00`,
    name,
    workout_doc: { ftp: 250, steps },
    ...extra,
  };
}

/** One steady step at a percentage of FTP. */
export const steady = (seconds: number, percent: number, text?: string): unknown => ({
  duration: seconds,
  power: { value: percent, units: "%ftp" },
  ...(text === undefined ? {} : { text }),
});
