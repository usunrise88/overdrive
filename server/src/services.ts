import type { Config } from "./config.ts";
import { DEFAULT_INTERVALS_BASE_URL } from "./config.ts";
import type { IntegrationsSettings } from "./config-integrations.ts";
import { type StravaConfig, stravaSettings } from "./config-strava.ts";
import type { Db } from "./db.ts";
import { createEventBus, type EventBus } from "./events.ts";
import { type CalendarMirror, createCalendar } from "./gcal/index.ts";
import { createIntervalsClient, type IntervalsClient } from "./intervals/client.ts";
import {
  createUploadWorker,
  INTERVALS_DESTINATION,
  type UploadWorker,
} from "./intervals/uploads.ts";
import type { Logger } from "./log.ts";
import { createMetricsService, type MetricsService } from "./metrics/service.ts";
import { createPlanRefresher, type PlanRefresher } from "./plan/refresher.ts";
import { createPlanStore, type PlanStore } from "./plan/store.ts";
import { createProfileStore, type ProfileStore } from "./profile/store.ts";
import { createRideStore, type RideStore } from "./ride-store.ts";
import { createRideWorkoutResolver, type RideWorkoutResolver } from "./ride-workout.ts";
import { createStrava, STRAVA_DESTINATION, type StravaUploads } from "./strava/index.ts";
import { createTelegram, type TelegramBot } from "./telegram/index.ts";
import type { Timers } from "./timers.ts";

/** The part of the configuration the integrations read; all optional, with the defaults of `config.ts`. */
export type ServicesConfig = Partial<
  Pick<
    Config,
    | "INTERVALS_API_KEY"
    | "INTERVALS_ATHLETE_ID"
    | "INTERVALS_BASE_URL"
    | "PLAN_REFRESH_MINUTES"
    | "TIMEZONE"
  >
> &
  IntegrationsSettings &
  StravaConfig;

/**
 * What the other modules (MCP tools, Telegram, Google Calendar, Strava) are given: the event bus,
 * the plan store, and the intervals.icu client when there is a key. The loops do not run until
 * {@link Services.start}, so tests and tools can build the service without background work.
 */
export interface Services {
  bus: EventBus;
  plan: PlanStore;
  /** The rider's profile: FTP, zones, availability, goals, physics (ADR 0018). */
  profile: ProfileStore;
  /** Reads of the stored rides. */
  rides: RideStore;
  /** The workout a stored ride was played from, kept when the ride is stored (ADR 0018). */
  rideWorkouts: RideWorkoutResolver;
  /** Training metrics: load, fitness, power curve, FTP suggestion, aerobic drift, W′ balance (ADR 0022). */
  metrics: MetricsService;
  /** The intervals.icu client; null when `INTERVALS_API_KEY` is not set (the integration is off). */
  intervals: IntervalsClient | null;
  /** null when the integration is off: no plan is read and the API says so. */
  refresher: PlanRefresher | null;
  /** null when the integration is off: rides are queued, nothing is sent. */
  uploads: UploadWorker | null;
  /** The Strava upload (ADR 0020); null when the three STRAVA_* credentials are not all set (the feature is off). */
  strava: StravaUploads | null;
  /** The destinations a stored ride is queued for (`ride_destinations.destination`). */
  destinations: readonly string[];
  /** The rider's time zone (IANA). */
  timezone: string;
  /** The Google Calendar mirror (ADR 0019); null when the GOOGLE_* settings are not all set. */
  calendar: CalendarMirror | null;
  /** The Telegram bot (ADR 0019); null when TELEGRAM_BOT_TOKEN is not set. */
  telegram: TelegramBot | null;
  /** Starts the refresher, the upload worker, the calendar mirror and the bot. */
  start(): void;
  /** Stops them and waits for what is in flight. */
  stop(): Promise<void>;
}

export interface ServicesOptions {
  config: ServicesConfig;
  db: Db;
  log: Logger;
  bus?: EventBus;
  /** For the tests: the fake clock, the fake network, no waiting between retries. */
  now?: () => Date;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  /** For the tests: the loops' ticks by hand. */
  timers?: Timers;
}

export function createServices(options: ServicesOptions): Services {
  const { config, db, log } = options;
  const bus =
    options.bus ??
    createEventBus((name, error) =>
      log.error({ err: error, event: name }, "an event listener failed"),
    );
  const timezone = config.TIMEZONE ?? "UTC";
  const plan = createPlanStore(db, bus);
  const now = options.now;
  const profile = createProfileStore(db, bus, now ?? (() => new Date()));
  const rides = createRideStore(db);

  let intervals: IntervalsClient | null = null;
  let refresher: PlanRefresher | null = null;
  let uploads: UploadWorker | null = null;
  if (config.INTERVALS_API_KEY !== undefined) {
    intervals = createIntervalsClient({
      baseUrl: config.INTERVALS_BASE_URL ?? DEFAULT_INTERVALS_BASE_URL,
      athleteId: config.INTERVALS_ATHLETE_ID ?? "0",
      apiKey: config.INTERVALS_API_KEY,
      log,
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
      ...(options.sleep === undefined ? {} : { sleep: options.sleep }),
    });
    const shared = {
      log,
      timezone,
      ...(now === undefined ? {} : { now }),
      ...(options.timers === undefined ? {} : { timers: options.timers }),
    };
    refresher = createPlanRefresher({ client: intervals, store: plan, ...shared });
    uploads = createUploadWorker({ db, client: intervals, bus, ...shared });
  } else {
    log.info(
      {},
      "intervals.icu is not configured (INTERVALS_API_KEY is not set): rides are queued, nothing is sent and no plan is read",
    );
  }

  const rideWorkouts = createRideWorkoutResolver({
    rides,
    plan,
    intervals,
    now: now ?? (() => new Date()),
    log,
  });
  const metrics = createMetricsService({
    db,
    rides,
    rideWorkouts,
    profile,
    bus,
    timezone,
    now: now ?? (() => new Date()),
    log,
  });
  // A ride keeps the workout it was played from, whatever the plan does later.
  bus.on("ride-stored", async ({ rideId }) => {
    const header = await rides.getHeader(rideId);
    if (header?.workoutId != null) await rideWorkouts.resolve(header);
  });

  const planSource = refresher !== null;
  const calendar = createCalendar({
    config,
    db,
    plan,
    bus,
    log,
    timezone,
    planSource,
    now,
    fetch: options.fetch,
    sleep: options.sleep,
    timers: options.timers,
  });
  const telegram = createTelegram({
    config,
    db,
    plan,
    bus,
    intervals,
    refresher,
    log,
    timezone,
    now,
    fetch: options.fetch,
    sleep: options.sleep,
    timers: options.timers,
  });
  let strava: StravaUploads | null = null;
  const stravaMode = stravaSettings(config);
  if ("on" in stravaMode) {
    strava = createStrava({
      settings: stravaMode.on,
      db,
      bus,
      rides,
      rideWorkouts,
      timezone,
      log,
      ...(now === undefined ? {} : { now }),
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
      ...(options.sleep === undefined ? {} : { sleep: options.sleep }),
      ...(options.timers === undefined ? {} : { timers: options.timers }),
    });
  } else if (stravaMode.partial) {
    log.warn({}, `Strava upload is off: ${stravaMode.off}`);
  } else {
    log.info({}, "Strava upload is off (STRAVA_* is not set)");
  }

  return {
    bus,
    plan,
    profile,
    rides,
    rideWorkouts,
    metrics,
    intervals,
    refresher,
    uploads,
    strava,
    // Queued either way, so a key added later also sends the rides that came before it. Strava is queued
    // only while it is on: turning it on later must not post the history (STRAVA_BACKFILL says otherwise).
    destinations:
      strava === null ? [INTERVALS_DESTINATION] : [INTERVALS_DESTINATION, STRAVA_DESTINATION],
    timezone,
    calendar,
    telegram,
    start() {
      void uploads?.start();
      void strava?.start();
      refresher?.start(config.PLAN_REFRESH_MINUTES ?? 15);
      void calendar?.start();
      telegram?.start();
      metrics.start();
    },
    async stop() {
      await Promise.all([
        uploads?.stop(),
        strava?.stop(),
        refresher?.stop(),
        calendar?.stop(),
        telegram?.stop(),
        metrics.stop(),
      ]);
    },
  };
}
