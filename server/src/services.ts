import type { Config } from "./config.ts";
import { DEFAULT_INTERVALS_BASE_URL } from "./config.ts";
import type { Db } from "./db.ts";
import { createEventBus, type EventBus } from "./events.ts";
import { createIntervalsClient, type IntervalsClient } from "./intervals/client.ts";
import {
  createUploadWorker,
  INTERVALS_DESTINATION,
  type UploadWorker,
} from "./intervals/uploads.ts";
import type { Logger } from "./log.ts";
import { createPlanRefresher, type PlanRefresher } from "./plan/refresher.ts";
import { createPlanStore, type PlanStore } from "./plan/store.ts";
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
>;

/**
 * What the other modules (MCP tools, Telegram, Google Calendar, Strava) are given: the event bus,
 * the plan store, and the intervals.icu client when there is a key. The loops do not run until
 * {@link Services.start}, so tests and tools can build the service without background work.
 */
export interface Services {
  bus: EventBus;
  plan: PlanStore;
  /** The intervals.icu client; null when `INTERVALS_API_KEY` is not set (the integration is off). */
  intervals: IntervalsClient | null;
  /** null when the integration is off: no plan is read and the API says so. */
  refresher: PlanRefresher | null;
  /** null when the integration is off: rides are queued, nothing is sent. */
  uploads: UploadWorker | null;
  /** The destinations a stored ride is queued for (`ride_destinations.destination`). */
  destinations: readonly string[];
  /** The rider's time zone (IANA). */
  timezone: string;
  /** Starts the refresher and the upload worker. */
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

  return {
    bus,
    plan,
    intervals,
    refresher,
    uploads,
    // Queued either way, so a key added later also sends the rides that came before it.
    destinations: [INTERVALS_DESTINATION],
    timezone,
    start() {
      void uploads?.start();
      refresher?.start(config.PLAN_REFRESH_MINUTES ?? 15);
    },
    async stop() {
      await Promise.all([uploads?.stop(), refresher?.stop()]);
    },
  };
}
