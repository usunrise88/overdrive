import type { RideRecord } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { expect } from "vitest";
import { buildApp } from "../../src/app.ts";
import type { Db } from "../../src/db.ts";
import { createServices, type Services } from "../../src/services.ts";
import { STRAVA_DESTINATION, type StravaUploads } from "../../src/strava/index.ts";
import { asRide, COMPANION, expectUpload, putFit, putRecord, rideFixture, TABLET } from "./app.ts";
import {
  FAKE_CLIENT_ID,
  FAKE_CLIENT_SECRET,
  FAKE_REFRESH_TOKEN,
  type FakeStrava,
} from "./fake-strava.ts";
import { buildFit } from "./fit.ts";
import { collectingLogger, type TestClock, testClock } from "./support.ts";

export interface StravaHarness {
  app: FastifyInstance;
  services: Services;
  /** The Strava upload worker; the harness fails when Strava is not configured. */
  uploads: StravaUploads;
  clock: TestClock;
  /** What the services logged (not the HTTP server). */
  logging: ReturnType<typeof collectingLogger>;
  /** The waits the worker asked for between status checks (it does not wait). */
  sleeps: number[];
}

export interface StravaHarnessOptions {
  /** The starting instant; default 2026-11-18 08:00 UTC. */
  now?: string;
  /** Another clock to share (a second "process" on the same database and time). */
  clock?: TestClock;
  timezone?: string;
  backfill?: boolean;
  /** `STRAVA_REFRESH_TOKEN`; default the fake's. */
  refreshToken?: string;
  /** false: no STRAVA_* at all (the feature is off). */
  configured?: boolean;
  /** Extra configuration, for the partial-configuration tests. */
  config?: Record<string, string | boolean | undefined>;
}

/**
 * The whole service against the fake Strava: real services and routes over the given database, the
 * Strava client pointed at `fake`, a clock and a sleep the test controls. intervals.icu is off.
 * Background loops are not started; the tests call the worker themselves.
 */
export function startStravaHarness(
  db: Db,
  fake: FakeStrava,
  options: StravaHarnessOptions = {},
): StravaHarness {
  const clock = options.clock ?? testClock(options.now ?? "2026-11-18T08:00:00Z");
  fake.now = () => clock.now().getTime();
  const logging = collectingLogger();
  const sleeps: number[] = [];
  const services = createServices({
    config: {
      ...(options.configured === false
        ? {}
        : {
            STRAVA_CLIENT_ID: FAKE_CLIENT_ID,
            STRAVA_CLIENT_SECRET: FAKE_CLIENT_SECRET,
            STRAVA_REFRESH_TOKEN: options.refreshToken ?? FAKE_REFRESH_TOKEN,
            STRAVA_BACKFILL: options.backfill ?? false,
            STRAVA_BASE_URL: fake.url,
          }),
      TIMEZONE: options.timezone ?? "Europe/Paris",
      ...options.config,
    },
    db,
    log: logging.log,
    now: clock.now,
    sleep: async (ms) => {
      sleeps.push(ms);
    },
  });
  const app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET, COMPANION] },
    db,
    services,
  });
  const uploads = services.strava;
  if (uploads === null && options.configured !== false) throw new Error("Strava is not configured");
  return { app, services, uploads: uploads as StravaUploads, clock, logging, sleeps };
}

export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;
/** 2026-11-18 07:00 UTC: 08:00 in Paris, the same day. */
export const START = Date.parse("2026-11-18T07:00:00Z");

/** A distinct FIT file per `n`: the fake tells duplicates by content. */
export const fitOf = (n: number) => buildFit({ data: Uint8Array.from([n, 1, 2, 3, 4, 5, 6, 7]) });

export interface StoreOptions {
  /** Hours after START. */
  hours?: number;
  workoutId?: string;
  /** Replaces the ride's record fixture. */
  record?: RideRecord;
}

/** Stores a ride and its FIT through the API, the way the tablet does. */
export async function storeRide(
  h: Pick<StravaHarness, "app">,
  id: string,
  n: number,
  options: StoreOptions = {},
): Promise<Buffer> {
  const record: RideRecord = asRide(
    options.record ?? rideFixture("minimal-free-ride.json"),
    id,
    START + (options.hours ?? n) * HOUR,
  );
  if (options.workoutId !== undefined) record.ride.workoutId = options.workoutId;
  expectUpload(await putRecord(h.app, id, record), id, "stored");
  const fit = fitOf(n);
  expectUpload(await putFit(h.app, id, fit), id, "stored");
  return fit;
}

export interface Destination {
  state: string;
  attempts: number;
  last_error: string | null;
  external_id: string | null;
  next: string | null;
}

export async function destination(db: Db, rideId: string): Promise<Destination | undefined> {
  const result = await db.query<Destination>(
    `SELECT state, attempts, last_error, external_id, next_attempt_at AS next
       FROM ride_destinations WHERE ride_id = $1 AND destination = $2`,
    [rideId, STRAVA_DESTINATION],
  );
  const row = result.rows[0];
  return row === undefined
    ? undefined
    : { ...row, next: row.next === null ? null : new Date(row.next).toISOString() };
}

export async function stravaUpload(
  db: Db,
  rideId: string,
): Promise<{ upload_id: string; polls: number } | undefined> {
  const result = await db.query<{ upload_id: string; polls: number }>(
    "SELECT upload_id, polls FROM strava_uploads WHERE ride_id = $1",
    [rideId],
  );
  return result.rows[0];
}

export async function clearStrava(db: Db): Promise<void> {
  await db.exec("TRUNCATE ride_destinations, rides, strava_uploads, strava_auth, ride_workouts");
}

/** Every secret of the scenarios, anywhere the service might have written one. */
export async function expectNoSecrets(
  db: Db,
  fake: FakeStrava,
  ...places: (string | { text(): string; lines?: unknown })[]
): Promise<void> {
  const text = places.map((place) => (typeof place === "string" ? place : place.text())).join("\n");
  const rows = await db.query<{ last_error: string | null }>(
    "SELECT last_error FROM ride_destinations",
  );
  const everything = `${text}\n${rows.rows.map((r) => r.last_error ?? "").join("\n")}`;
  const secrets = [
    FAKE_CLIENT_SECRET,
    FAKE_REFRESH_TOKEN,
    fake.validRefreshToken,
    ...fake.accessTokens,
    "fake-access-token",
    "fake-refresh-token",
    "Bearer ",
  ];
  for (const secret of secrets) expect(everything).not.toContain(secret);
}
