import type { StravaSettings } from "../config-strava.ts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import type { Logger } from "../log.ts";
import type { RideStore } from "../ride-store.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import type { Timers } from "../timers.ts";
import { createRefreshTokenStore, createTokenManager } from "./auth.ts";
import { createStravaClient } from "./client.ts";
import { createStravaUploads, type StravaUploads } from "./uploads.ts";

export { STRAVA_DESTINATION, type StravaUploads } from "./uploads.ts";

export interface StravaOptions {
  settings: StravaSettings;
  db: Db;
  bus: EventBus;
  rides: Pick<RideStore, "getRecord">;
  rideWorkouts: RideWorkoutResolver;
  timezone: string;
  log: Logger;
  /** For the tests: the clock, the network, no waiting, the ticks by hand. */
  now?: () => Date;
  fetch?: typeof fetch;
  sleep?: (ms: number) => Promise<void>;
  timers?: Timers;
}

/** The Strava upload (ADR 0020): token, client and worker, wired together. Nothing runs until `start()`. */
export function createStrava(options: StravaOptions): StravaUploads {
  const { settings, db, log } = options;
  const shared = {
    ...(options.now === undefined ? {} : { now: options.now }),
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  };
  const tokens = createTokenManager({
    store: createRefreshTokenStore(db),
    clientId: settings.clientId,
    clientSecret: settings.clientSecret,
    seedRefreshToken: settings.refreshToken,
    baseUrl: settings.baseUrl,
    log,
    ...shared,
  });
  const client = createStravaClient({
    baseUrl: settings.baseUrl,
    tokens,
    log,
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
  return createStravaUploads({
    db,
    client,
    bus: options.bus,
    rides: options.rides,
    rideWorkouts: options.rideWorkouts,
    timezone: options.timezone,
    backfill: settings.backfill,
    log,
    ...(options.now === undefined ? {} : { now: options.now }),
    ...(options.sleep === undefined ? {} : { sleep: options.sleep }),
    ...(options.timers === undefined ? {} : { timers: options.timers }),
  });
}
