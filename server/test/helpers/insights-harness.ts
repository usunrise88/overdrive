import {
  addDaysToIsoDate,
  type InsightsResponse,
  validateInsightsResponse,
} from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { expect, vi } from "vitest";
import type { Db } from "../../src/db.ts";
import type { ServicesConfig } from "../../src/services.ts";
import { asRide, bearer, COMPANION, expectUpload, putFit, putRecord } from "./app.ts";
import { sharedDbOf } from "./db.ts";
import type { FakeIntervals } from "./fake-intervals.ts";
import type { FakeLlm } from "./fake-llm.ts";
import { FAKE_LLM_KEY, FAKE_LLM_MODEL } from "./fake-llm.ts";
import { type FakeTelegram, OWNER_CHAT } from "./fake-telegram.ts";
import { buildFit } from "./fit.ts";
import { type IntegrationHarness, startIntegrationHarness } from "./integrations.ts";
import { type SyntheticRide, syntheticRide } from "./synthetic-rides.ts";
import { TODAY } from "./wellness.ts";
import { batch, history, type NightPatterns, post, uploadAll } from "./wellness-data.ts";

/** What the insights tests share: a service with the insights on, the data to feed it, and ways to wait for what it does. */

export interface InsightsEnv {
  db: Db;
  intervals: FakeIntervals;
  telegram: FakeTelegram;
  llm: FakeLlm;
  started: IntegrationHarness[];
}

/** Empties everything the insights read or write. */
export async function clearAll(db: Db): Promise<void> {
  await db.exec(
    `TRUNCATE insights, discussion_topics, alert_streaks RESTART IDENTITY;
     TRUNCATE calendar_events, plan_history, bot_notifications;
     TRUNCATE planned_workouts, plan_sync_state;
     TRUNCATE wellness_records, wellness_daily, wellness_sync_state, activities, activity_destinations, ride_heart_rate;
     TRUNCATE ride_destinations, rides, ride_metrics, ride_workouts, ftp_suggestions, ftp_history;`,
  );
  await sharedDbOf(db).exec("TRUNCATE bot_state");
}

export interface BootOptions {
  now?: string;
  llm?: boolean;
  config?: ServicesConfig;
  /** false: no Telegram bot. */
  telegram?: boolean;
  /** false: no plan source. */
  plan?: boolean;
}

/** The service with the insights on, started, over the given fakes. Times are UTC so the synthetic nights line up with their days. */
export async function boot(
  env: InsightsEnv,
  options: BootOptions = {},
): Promise<IntegrationHarness> {
  const h = startIntegrationHarness(env.db, {
    now: options.now ?? "2026-11-18T05:30:00Z",
    timezone: "UTC",
    ...(options.plan === false ? {} : { intervals: env.intervals }),
    ...(options.telegram === false ? {} : { telegram: env.telegram }),
    config: {
      INSIGHTS_ENABLED: true,
      // The bot's own reminders are tested elsewhere: here only the notes and the bot's summaries of rides are heard.
      REMINDER_EVENING_TIME: "off",
      REMINDER_LEAD_MINUTES: 0,
      ...(options.llm === true
        ? { LLM_BASE_URL: env.llm.url, LLM_MODEL: FAKE_LLM_MODEL, LLM_API_KEY: FAKE_LLM_KEY }
        : {}),
      ...options.config,
    },
  });
  env.started.push(h);
  h.start();
  await h.services.refresher?.refresh();
  await pass(h);
  return h;
}

/** Closes a running service and starts another over the same database and the same instant. */
export async function restart(
  env: InsightsEnv,
  h: IntegrationHarness,
  options: BootOptions = {},
): Promise<IntegrationHarness> {
  await h.app.close();
  env.started.splice(env.started.indexOf(h), 1);
  return boot(env, { now: h.clock.now().toISOString(), ...options });
}

/** One pass of the insights and then of the bot's scheduled messages, at the clock's instant. */
export async function pass(h: IntegrationHarness): Promise<void> {
  h.services.insights.kick();
  await h.services.insights.idle();
  await h.services.telegram?.idle();
}

/** Moves the clock to `at` and runs a pass. */
export async function at(h: IntegrationHarness, instant: string): Promise<void> {
  h.clock.set(instant);
  await pass(h);
}

/** What the owner's Telegram chat has been told, in order. */
export const said = (env: InsightsEnv): string[] =>
  env.telegram.sentTo(OWNER_CHAT).map((m) => m.text);

/** The messages that are written insights, not the bot's own reminders and summaries. */
export const notes = (env: InsightsEnv) =>
  env.telegram
    .sentTo(OWNER_CHAT)
    .filter((m) => !/^(Ride saved|Week \d|Tomorrow at|Starts at|Today at)/.test(m.text));

export async function rows(db: Db): Promise<
  {
    id: number;
    kind: string;
    key: string;
    source: string;
    text: string;
    prompt_version: string;
    ride_id: string | null;
  }[]
> {
  const result = await db.query<{
    id: number;
    kind: string;
    key: string;
    source: string;
    text: string;
    prompt_version: string;
    ride_id: string | null;
  }>("SELECT id, kind, key, source, text, prompt_version, ride_id FROM insights ORDER BY id");
  return result.rows;
}

export async function notifications(
  db: Db,
): Promise<{ kind: string; key: string; state: string }[]> {
  const result = await db.query<{ kind: string; key: string; state: string }>(
    "SELECT kind, key, state FROM bot_notifications WHERE kind LIKE 'insight-%' ORDER BY kind, key",
  );
  return result.rows;
}

/** `GET /v1/insights` as the companion calls it, checked against the contract. */
export async function getInsights(
  h: IntegrationHarness,
  from: string,
  to: string,
): Promise<InsightsResponse> {
  const res = await h.app.inject({
    method: "GET",
    url: `/v1/insights?from=${from}&to=${to}`,
    headers: bearer(COMPANION),
  });
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  expect(validateInsightsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  return body as InsightsResponse;
}

// ---- Data -----------------------------------------------------------------------------------------

/** Resting heart rate 50 and 54 on alternating days (mean 52) and respiratory rate 13.8 and 14.6: a steady rider. */
export const STEADY: NightPatterns = {
  restingHr: (back) => (back % 2 === 0 ? 50 : 54),
  respiratoryRate: (back) => (back % 2 === 0 ? 13.8 : 14.6),
};

/** Uploads 60 nights up to today from patterns (the steady rider by default). */
export async function uploadNights(
  h: IntegrationHarness,
  patterns: NightPatterns = STEADY,
): Promise<void> {
  await uploadAll(h.app, history(patterns, 60, 0));
}

/** The same, with resting heart rate planted on some days before today. */
export function withRestingHr(planted: Record<number, number>): NightPatterns {
  return {
    ...STEADY,
    restingHr: (back) => planted[back] ?? (back % 2 === 0 ? 50 : 54),
  };
}

/** One more night of data, as the companion would upload it in the morning. */
export async function uploadMorning(h: IntegrationHarness, patterns: NightPatterns): Promise<void> {
  const res = await post(h.app, batch(...history(patterns, 0, 0)));
  expect(res.statusCode, res.body).toBe(200);
}

/** A ride of the 40-minute planned workout (10 min at 60%, 20 at 88%, 10 at 50% of 250 W): `seconds` long, the middle step ridden at `watts`. */
export function sweetSpotRide(
  id: string,
  startIso: string,
  seconds: number,
  watts: number,
  workoutId: string | null,
): SyntheticRide {
  const stepOf = (s: number): number => (s <= 600 ? 0 : s <= 1800 ? 1 : 2);
  return {
    id,
    startEpochMillis: Date.parse(startIso),
    seconds,
    power: (s) => (stepOf(s) === 0 ? 150 : stepOf(s) === 1 ? watts : 125),
    step: workoutId === null ? () => null : stepOf,
    heartRate: () => 140,
    workoutId,
  };
}

/** Stores a ride through the intake (record, then FIT), which announces it. */
export async function uploadRide(h: { app: FastifyInstance }, spec: SyntheticRide): Promise<void> {
  const record = syntheticRide(spec);
  expectUpload(await putRecord(h.app, record.ride.id, record), record.ride.id, "stored");
  expectUpload(await putFit(h.app, record.ride.id, buildFit()), record.ride.id, "stored");
}

export const rideIdAt = (iso: string): string => `ride-${Date.parse(iso)}`;

/** The plan has a 40-minute workout on each of these days; returns the plan ids (`icu-123`). */
export async function planDays(
  env: InsightsEnv,
  h: IntegrationHarness,
  dates: string[],
  name = "Sweet spot",
): Promise<string[]> {
  const { planWorkout } = await import("./integrations.ts");
  const ids = dates.map((date) => `icu-${planWorkout(env.intervals, date, name)}`);
  await h.services.refresher?.refresh();
  return ids;
}

export { addDaysToIsoDate, asRide, TODAY, vi };
