import {
  DEFAULT_GOOGLE_API_BASE_URL,
  DEFAULT_GOOGLE_TOKEN_URL,
  DEFAULT_WORKOUT_START,
  type IntegrationsSettings,
} from "../config-integrations.ts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import type { PlanStore } from "../plan/store.ts";
import type { Timers } from "../timers.ts";
import { parseClock } from "../zoned.ts";
import { createCalendarClient } from "./client.ts";
import { type CalendarMirror, createCalendarMirror } from "./mirror.ts";

export type { CalendarMirror } from "./mirror.ts";

export interface CalendarDeps {
  config: IntegrationsSettings;
  db: Db;
  plan: PlanStore;
  bus: EventBus;
  log: Logger;
  timezone: string;
  /** Whether a plan source exists. */
  planSource: boolean;
  now?: (() => Date) | undefined;
  fetch?: typeof fetch | undefined;
  sleep?: ((ms: number) => Promise<void>) | undefined;
  timers?: Timers | undefined;
}

const NAMES = [
  "GOOGLE_CLIENT_ID",
  "GOOGLE_CLIENT_SECRET",
  "GOOGLE_REFRESH_TOKEN",
  "GOOGLE_CALENDAR_ID",
] as const;

/**
 * The Google Calendar mirror, or null when it is off (spec §5.3, ADR 0019). It is on only when all four
 * credentials are set; with some set and some not it stays off and the log names the missing ones (never a
 * value), because half a setup is usually a typo.
 */
export function createCalendar(deps: CalendarDeps): CalendarMirror | null {
  const { config, log } = deps;
  const missing = NAMES.filter((name) => config[name] === undefined);
  if (missing.length === NAMES.length) {
    log.info({}, "Google Calendar is not configured (GOOGLE_* are not set): nothing is pushed");
    return null;
  }
  if (missing.length > 0) {
    log.warn(
      { missing },
      "Google Calendar is off: some GOOGLE_* variables are set and these are not",
    );
    return null;
  }
  const { GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN, GOOGLE_CALENDAR_ID } =
    config;
  if (
    GOOGLE_CLIENT_ID === undefined ||
    GOOGLE_CLIENT_SECRET === undefined ||
    GOOGLE_REFRESH_TOKEN === undefined ||
    GOOGLE_CALENDAR_ID === undefined
  ) {
    return null;
  }
  const client = createCalendarClient({
    apiBaseUrl: config.GOOGLE_API_BASE_URL ?? DEFAULT_GOOGLE_API_BASE_URL,
    tokenUrl: config.GOOGLE_TOKEN_URL ?? DEFAULT_GOOGLE_TOKEN_URL,
    clientId: GOOGLE_CLIENT_ID,
    clientSecret: GOOGLE_CLIENT_SECRET,
    refreshToken: GOOGLE_REFRESH_TOKEN,
    calendarId: GOOGLE_CALENDAR_ID,
    log,
    ...(deps.fetch === undefined ? {} : { fetch: deps.fetch }),
    ...(deps.sleep === undefined ? {} : { sleep: deps.sleep }),
    ...(deps.now === undefined ? {} : { now: deps.now }),
  });
  return createCalendarMirror({
    db: deps.db,
    plan: deps.plan,
    bus: deps.bus,
    client,
    timezone: deps.timezone,
    workoutStart: parseClock(config.GCAL_WORKOUT_START ?? DEFAULT_WORKOUT_START) ?? {
      hour: 7,
      minute: 0,
    },
    planSource: deps.planSource,
    log,
    ...(deps.now === undefined ? {} : { now: deps.now }),
    ...(deps.timers === undefined ? {} : { timers: deps.timers }),
  });
}
