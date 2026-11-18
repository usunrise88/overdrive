import {
  addDaysToIsoDate,
  type DiscussionTopic,
  type Insight,
  type InsightKind,
  type InsightsResponse,
  type TopicStatus,
} from "@overdrive/contracts";
import {
  DEFAULT_ALERT_LOAD_ABOVE_PERCENT,
  DEFAULT_ALERT_LOAD_BELOW_PERCENT,
  DEFAULT_ALERT_MISSED_SESSIONS,
  DEFAULT_ALERT_STREAK_DAYS,
  DEFAULT_INSIGHT_MORNING_TIME,
  DEFAULT_INSIGHTS_LANGUAGE,
  DEFAULT_LLM_TIMEOUT_SECONDS,
  type InsightsSettings,
} from "../config-insights.ts";
import {
  DEFAULT_WEEKLY_SUMMARY_DAY,
  DEFAULT_WEEKLY_SUMMARY_TIME,
  type IntegrationsSettings,
  weeklyDayNumber,
} from "../config-integrations.ts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { canonicalJson, sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import type { MetricsService } from "../metrics/service.ts";
import type {
  NotificationCandidate,
  NotificationMessage,
  NotificationSource,
} from "../notifications.ts";
import type { PlanStore } from "../plan/store.ts";
import type { ProfileStore } from "../profile/store.ts";
import type { RideStore } from "../ride-store.ts";
import type { RideWorkoutResolver } from "../ride-workout.ts";
import type { PlanHistory } from "../telegram/history.ts";
import { createRideQueries, type RideBrief } from "../telegram/rides.ts";
import { dayStartMillis, localDate } from "../time.ts";
import { realTimers, type Timers } from "../timers.ts";
import type { WellnessService } from "../wellness/service.ts";
import { isoWeekday, mondayOf, parseClock, zonedInstant } from "../zoned.ts";
import { type AlertSettings, DEFAULT_ALERT_SETTINGS, deviationCandidates } from "./alerts.ts";
import {
  buildDeviationFacts,
  buildDriftFacts,
  buildMorningFacts,
  buildRideFacts,
  buildWeekFacts,
  type Facts,
} from "./facts.ts";
import { createGatherer, type Gatherer } from "./gather.ts";
import { createLlmClient, type LlmClient } from "./llm.ts";
import { READINGS } from "./readings.ts";
import {
  createInsightStore,
  type InsertResult,
  type InsightRow,
  type InsightStore,
  type NewInsight,
  type TopicRow,
} from "./store.ts";
import {
  DEFAULT_TOPIC_SETTINGS,
  findTopic,
  TOPIC_OPEN_MS,
  TOPIC_WINDOW_MS,
  TOPICS_PER_WEEK,
  type TopicSettings,
} from "./topics.ts";
import { createValidator } from "./validator.ts";
import { createNoteWriter, type NoteWriter } from "./writer.ts";

/**
 * The written insights, the alerts and the discussion topics (spec §6.5, §6.6, §12.3, ADR 0025). The service computes the facts
 * (`facts.ts`, from `gather.ts`), has a note written from them (`writer.ts`: a language model when one is configured, checked by
 * `validator.ts`, else a deterministic template), stores it once (`store.ts`) and tells the Telegram bot through the
 * `insight-stored` event and its notification source. It never changes the plan and never calculates anything a model quotes.
 *
 * Triggers, with the injectable clock and timers of the other loops: the morning note at `INSIGHT_MORNING_TIME` (07:00) when a
 * recovery verdict or a plan exists, the note after a ride on `ride-stored`, the weekly review at the weekly summary's time, and the
 * alerts checked each morning. A pass runs every minute and whenever a ride, the plan or the wellness figures change; every note is
 * idempotent by (kind, key), so a pass that runs twice, or a restart, writes nothing twice.
 *
 * HEALTH DATA IS PRIVATE: the facts and the text are health data. This logs ids, kinds and counts, never a value or a sentence, and
 * never a database error's own message (it may quote a row).
 */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** A ride that began longer ago than this gets a note only when an event announced it (a tablet that was offline). */
const RECENT_RIDE_MS = 36 * HOUR;
/** A ride this short is an accident, not a session. */
const MIN_RIDE_SECONDS = 60;
/** The morning note may be written from the morning time to this long after it. */
const MORNING_WINDOW_MS = 6 * HOUR;
/** Without this morning's watch data the note waits this long for it before it is written from the plan alone. */
const MORNING_GRACE_MS = 2 * HOUR;
const DRIFT_ALERTS_LOOKBACK_DAYS = 28;

/** How long after it was written a note is still worth sending to Telegram. */
const SEND_WINDOW_MS: Record<InsightKind, number> = {
  morning: 12 * HOUR,
  ride: 36 * HOUR,
  week: 36 * HOUR,
  deviation: DAY,
  drift: DAY,
};

export interface InsightsDeps {
  config: InsightsSettings & IntegrationsSettings;
  db: Db;
  bus: EventBus;
  log: Logger;
  timezone: string;
  now?: (() => Date) | undefined;
  plan: PlanStore;
  history: PlanHistory;
  rides: RideStore;
  rideWorkouts: RideWorkoutResolver;
  metrics: MetricsService;
  wellness: WellnessService;
  profile: ProfileStore;
  fetch?: typeof fetch | undefined;
  sleep?: ((ms: number) => Promise<void>) | undefined;
  timers?: Timers | undefined;
}

export interface TickSummary {
  rideNotes: number;
  morning: number;
  week: number;
  alerts: number;
}

/** A topic as the MCP tools and the notification show it. */
export interface TopicView {
  id: number;
  rideId: string;
  /** The ride's name and day, from its note's facts; null when the note is gone. */
  rideName: string | null;
  rideDate: string | null;
  text: string;
  openingQuestion: string;
  /** The row of the topic rule table that found it. */
  rule: string;
  createdAt: string;
  status: TopicStatus;
  decidedAt: string | null;
}

export type FactsRequest =
  | { kind: "morning"; date?: string }
  | { kind: "ride"; rideId: string }
  | { kind: "week"; weekStart?: string }
  | { kind: "deviation"; date?: string }
  | { kind: "drift"; date?: string };

export interface FactsAnswer {
  facts: Facts;
  /** The note these facts were written into, or null when none was (yet). */
  note: {
    id: number;
    createdAt: string;
    source: "llm" | "template";
    promptVersion: string;
    factsHash: string;
  } | null;
  /** True: the facts are those stored with the note, exactly what the writer saw; false: worked out now. */
  stored: boolean;
}

export interface InsightsService {
  readonly store: InsightStore;
  /** True when a language model is configured: notes are written by it (checked) and fall back to the template. */
  readonly usesModel: boolean;
  /** `GET /v1/insights`: the notes created on the local days `from` to `to`. */
  list(from: string, to: string): Promise<InsightsResponse>;
  /** A stored note with its ride's topic. */
  note(id: number): Promise<{ insight: InsightRow; topic: TopicRow | null } | null>;
  /** The notes of a range, as the MCP tool shows them (the wire shape plus how each was written). */
  listWithSource(
    from: string,
    to: string,
  ): Promise<
    { insight: Insight; source: "llm" | "template"; promptVersion: string; factsHash: string }[]
  >;
  /** The facts a note is (or would be) written from: for `get_facts`. Null when there is nothing to say (no such ride). */
  facts(request: FactsRequest): Promise<FactsAnswer | null>;
  /** Topics, newest first; `statuses` null for all. Closes the ones that are 14 days old first. */
  topics(statuses: readonly TopicStatus[] | null, limit: number): Promise<TopicView[]>;
  /** Marks a topic discussed; null when there is no such topic. */
  markTopicDiscussed(id: number): Promise<TopicView | null>;
  /** What the Telegram bot sends: the notes and alerts not yet sent. */
  readonly notifications: NotificationSource;
  /** One pass over what is due now. */
  tick(): Promise<TickSummary>;
  kick(): void;
  /** Resolves when no pass is running. */
  idle(): Promise<void>;
  start(): void;
  stop(): Promise<void>;
}

/**
 * The key of a deviation alert: the day and a short hash of the metrics it names, so that the key (which the Telegram bot logs
 * and the notification table keeps) says nothing about which metric deviated.
 */
function deviationKey(date: string, metrics: readonly string[]): string {
  return `${date}:${sha256Hex([...metrics].sort().join("+")).slice(0, 8)}`;
}

/** What a thrown error may say without quoting a row: only a PostgreSQL error code. */
function failure(error: unknown): { code?: string } {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === "string" ? { code } : {};
}

export function createInsights(deps: InsightsDeps): InsightsService {
  const {
    config,
    db,
    bus,
    log,
    timezone,
    plan,
    history,
    rides,
    rideWorkouts,
    metrics,
    wellness,
    profile,
  } = deps;
  const now = deps.now ?? (() => new Date());
  const timers = deps.timers ?? realTimers;
  const enabled = config.INSIGHTS_ENABLED !== false;
  const store = createInsightStore(db);
  const rideQueries = createRideQueries(db);

  // ---- The writer -----------------------------------------------------------------------------

  let llm: LlmClient | null = null;
  if (config.LLM_BASE_URL !== undefined && config.LLM_MODEL !== undefined) {
    llm = createLlmClient({
      baseUrl: config.LLM_BASE_URL,
      apiKey: config.LLM_API_KEY,
      model: config.LLM_MODEL,
      timeoutMs: (config.LLM_TIMEOUT_SECONDS ?? DEFAULT_LLM_TIMEOUT_SECONDS) * 1000,
      fetch: deps.fetch,
      sleep: deps.sleep,
      log,
    });
    log.info(
      {},
      "written insights use the configured language model; health facts are sent to that endpoint only",
    );
  } else if (config.LLM_BASE_URL !== undefined || config.LLM_MODEL !== undefined) {
    log.warn(
      {},
      `the language model is off: ${config.LLM_BASE_URL === undefined ? "LLM_BASE_URL" : "LLM_MODEL"} is not set; notes are written from templates and no health data leaves the server`,
    );
  } else {
    log.info(
      {},
      "no language model is configured (LLM_BASE_URL and LLM_MODEL are not set): notes are written from templates and no health data leaves the server",
    );
  }
  const writer: NoteWriter = createNoteWriter({
    llm,
    language: config.INSIGHTS_LANGUAGE ?? DEFAULT_INSIGHTS_LANGUAGE,
    validator: createValidator({ denyWords: config.INSIGHT_DENY_WORDS ?? [] }),
    log,
  });

  // ---- Settings -------------------------------------------------------------------------------

  const alertSettings: AlertSettings = {
    ...DEFAULT_ALERT_SETTINGS,
    streakDays: config.ALERT_STREAK_DAYS ?? DEFAULT_ALERT_STREAK_DAYS,
    missedSessions: config.ALERT_MISSED_SESSIONS ?? DEFAULT_ALERT_MISSED_SESSIONS,
    loadAbovePercent: config.ALERT_LOAD_ABOVE_PERCENT ?? DEFAULT_ALERT_LOAD_ABOVE_PERCENT,
    loadBelowPercent: config.ALERT_LOAD_BELOW_PERCENT ?? DEFAULT_ALERT_LOAD_BELOW_PERCENT,
  };
  const topicSettings: TopicSettings = {
    ...DEFAULT_TOPIC_SETTINGS,
    loadAbovePercent: alertSettings.loadAbovePercent,
    loadBelowPercent: alertSettings.loadBelowPercent,
  };
  const morningSetting = config.INSIGHT_MORNING_TIME ?? DEFAULT_INSIGHT_MORNING_TIME;
  const morningClock = morningSetting === "off" ? null : parseClock(morningSetting);
  // The alerts are checked at the morning time too; with the morning note off they keep the default.
  const checkClock = morningClock ?? parseClock(DEFAULT_INSIGHT_MORNING_TIME);
  const weeklyDay = weeklyDayNumber(config.WEEKLY_SUMMARY_DAY ?? DEFAULT_WEEKLY_SUMMARY_DAY);
  const weeklySetting = config.WEEKLY_SUMMARY_TIME ?? DEFAULT_WEEKLY_SUMMARY_TIME;
  const weeklyClock = weeklySetting === "off" ? null : parseClock(weeklySetting);

  const gatherer: Gatherer = createGatherer({
    timezone,
    now,
    metrics,
    wellness,
    plan,
    history,
    rideQueries,
    rides,
    rideWorkouts,
    profile,
  });

  // ---- Writing and storing a note -------------------------------------------------------------

  const startOf = (date: string): number => dayStartMillis(date, timezone);

  async function write(facts: Facts, key: string, rideId: string | null): Promise<NewInsight> {
    const written = await writer.write(facts);
    return {
      kind: facts.kind,
      key,
      createdAt: now(),
      text: written.text,
      rideId,
      source: written.source,
      promptVersion: written.promptVersion,
      factsHash: sha256Hex(canonicalJson(facts)),
      facts,
    };
  }

  function announce(insight: InsightRow): void {
    log.info({ id: insight.id, kind: insight.kind, source: insight.source }, "a note was stored");
    bus.emit("insight-stored", { insightId: insight.id, kind: insight.kind });
  }

  // ---- Morning --------------------------------------------------------------------------------

  /** Whether the morning note of `date` can be written now: this morning's verdict, or (after the grace) a plan. */
  async function morningReady(date: string, dueMs: number): Promise<boolean> {
    const [recovery, planned] = await Promise.all([gatherer.recovery(), gatherer.plannedOn(date)]);
    const verdict =
      recovery !== null &&
      recovery.date === date &&
      (recovery.state === "ready" || recovery.state === "caution" || recovery.state === "rest");
    if (verdict) return true;
    return planned.length > 0 && now().getTime() >= dueMs + MORNING_GRACE_MS;
  }

  async function generateMorning(date: string): Promise<InsightRow | null> {
    if ((await store.find("morning", date)) !== null) return null;
    const facts = buildMorningFacts(await gatherer.morning(date));
    const result = await store.insert(await write(facts, date, null));
    if (!result.inserted || result.insight === null) return null;
    announce(result.insight);
    return result.insight;
  }

  // ---- After a ride ---------------------------------------------------------------------------

  async function generateRide(rideId: string): Promise<InsightRow | null> {
    if ((await store.find("ride", rideId)) !== null) return null;
    const input = await gatherer.ride(rideId);
    if (input === null || input.durationSeconds < MIN_RIDE_SECONDS) return null;
    const facts = buildRideFacts(input);
    const at = now();
    const driftAlerts = await store.countSince(
      "drift",
      at.getTime() - DRIFT_ALERTS_LOOKBACK_DAYS * DAY,
    );
    const topic = findTopic({
      ride: facts,
      driftAlertsLast28Days: driftAlerts,
      settings: topicSettings,
    });
    const note = await write(facts, rideId, rideId);
    const result: InsertResult = await store.insert(
      note,
      topic === null
        ? null
        : {
            topic: {
              rideId,
              rule: topic.rule,
              text: topic.text,
              openingQuestion: topic.openingQuestion,
            },
            limit: { max: TOPICS_PER_WEEK, windowMs: TOPIC_WINDOW_MS },
            now: at,
          },
    );
    if (!result.inserted || result.insight === null) return null;
    if (result.topic !== null) {
      log.info(
        { id: result.topic.id, rideId, rule: result.topic.rule },
        "a discussion topic was written",
      );
    } else if (result.topicSuppressed) {
      log.info({ rideId }, "a discussion topic was found but the weekly limit stopped it");
    }
    announce(result.insight);
    return result.insight;
  }

  // ---- The week -------------------------------------------------------------------------------

  /** The weeks found empty, by the data version they were found empty at: new data makes them be looked at again. */
  const weekSkipped = new Map<string, number>();

  async function generateWeek(monday: string, to: string): Promise<InsightRow | null> {
    if ((await store.find("week", monday)) !== null) return null;
    const facts = buildWeekFacts(await gatherer.week(monday, to));
    // A week with nothing planned and no ride has nothing to review.
    if ((facts.sessions.planned ?? 0) === 0 && (facts.sessions.rides ?? 0) === 0) {
      weekSkipped.set(monday, dataVersion);
      return null;
    }
    const result = await store.insert(await write(facts, monday, null));
    if (!result.inserted || result.insight === null) return null;
    announce(result.insight);
    return result.insight;
  }

  // ---- The alerts -----------------------------------------------------------------------------

  async function checkDeviation(date: string): Promise<number> {
    const day = await gatherer.wellnessDay(date);
    if (day === null) return 0;
    const reported = await store.reportedStreaks();
    const found = deviationCandidates(day, alertSettings, reported).slice(0, 3);
    if (found.length === 0) return 0;
    const [recovery, planned, ftp] = await Promise.all([
      gatherer.recovery(),
      gatherer.plannedOn(date),
      gatherer.ftp(),
    ]);
    const facts = buildDeviationFacts({
      date,
      thresholdDays: alertSettings.streakDays,
      signals: found.map((candidate) => ({
        fact: candidate.fact,
        reading: READINGS[candidate.metric].usually,
        trainingHint: READINGS[candidate.metric].hint,
      })),
      recoveryState: recovery?.state ?? null,
      planned,
      ftp,
    });
    const key = deviationKey(
      date,
      found.map((c) => c.metric),
    );
    const note = await write(facts, key, null);
    const insight = await store.insertAlert(
      note,
      found.map((c) => ({ metric: c.metric, streakStart: c.streakStart })),
    );
    if (insight === null) return 0;
    announce(insight);
    return 1;
  }

  async function checkDrift(date: string): Promise<number> {
    const monday = mondayOf(date);
    if ((await store.find("drift", monday)) !== null) return 0;
    const evaluation = await gatherer.drift(date, alertSettings);
    if (evaluation.reasons.length === 0) return 0;
    const upcoming = await gatherer.nextWeek(date);
    const facts = buildDriftFacts({
      date,
      windowDays: alertSettings.windowDays,
      missed: evaluation.missed,
      load: evaluation.load,
      reasons: evaluation.reasons,
      thresholds: {
        missedSessions: alertSettings.missedSessions,
        loadAbovePercent: alertSettings.loadAbovePercent,
        loadBelowPercent: alertSettings.loadBelowPercent,
      },
      nextWeek: upcoming,
    });
    const result = await store.insert(await write(facts, monday, null));
    if (!result.inserted || result.insight === null) return 0;
    announce(result.insight);
    return 1;
  }

  // ---- The pass -------------------------------------------------------------------------------

  const announced = new Set<string>();
  /** Moves on whenever something the checks read changes (a ride, the plan, the wellness figures), so a check made earlier is made again. */
  let dataVersion = 0;
  let alertsCheckedFor: string | null = null;
  let current: Promise<void> | null = null;
  let again = false;
  let stopTicking: (() => void) | null = null;
  const unsubscribe: (() => void)[] = [];

  /** The rides to write a note about: complete (record and FIT), recent or announced, with no note yet. */
  async function rideCandidates(nowMs: number): Promise<RideBrief[]> {
    const found = new Map<string, RideBrief>();
    for (const ride of await rideQueries.completeSince(nowMs - RECENT_RIDE_MS))
      found.set(ride.id, ride);
    for (const id of [...announced]) {
      if (found.has(id)) continue;
      const ride = await rideQueries.get(id);
      if (ride === null) announced.delete(id);
      else found.set(id, ride);
    }
    const ids = [...found.keys()];
    const have = await store.rideNotes(ids);
    return ids.flatMap((id) => {
      const ride = found.get(id);
      return ride === undefined || have.has(id) || ride.durationSeconds < MIN_RIDE_SECONDS
        ? []
        : [ride];
    });
  }

  async function step<T>(name: string, run: () => Promise<T>, fallback: T): Promise<T> {
    try {
      return await run();
    } catch (error) {
      log.error(
        { step: name, ...failure(error) },
        "an insights step failed; it is tried again next pass",
      );
      return fallback;
    }
  }

  async function tick(): Promise<TickSummary> {
    const summary: TickSummary = { rideNotes: 0, morning: 0, week: 0, alerts: 0 };
    if (!enabled) return summary;
    const at = now();
    const nowMs = at.getTime();
    const today = localDate(at, timezone);

    await step("expire topics", () => store.expireTopics(nowMs - TOPIC_OPEN_MS, at), 0);

    // The notes after rides, oldest first.
    const pending = await step("rides", () => rideCandidates(nowMs), [] as RideBrief[]);
    for (const ride of pending) {
      const written = await step("ride note", () => generateRide(ride.id), null);
      if (written !== null) {
        summary.rideNotes++;
        announced.delete(ride.id);
      }
    }

    // The morning note, and the alerts checked after it.
    if (checkClock !== null) {
      const dueMs = zonedInstant(today, checkClock, timezone).getTime();
      if (nowMs >= dueMs) {
        if (morningClock !== null && nowMs < dueMs + MORNING_WINDOW_MS) {
          const written = await step(
            "morning note",
            async () =>
              (await store.find("morning", today)) === null && (await morningReady(today, dueMs))
                ? await generateMorning(today)
                : null,
            null,
          );
          if (written !== null) summary.morning++;
        }
        const stamp = `${today}:${dataVersion}`;
        if (alertsCheckedFor !== stamp) {
          let checked = true;
          const run = async (name: string, check: () => Promise<number>): Promise<number> => {
            try {
              return await check();
            } catch (error) {
              checked = false;
              log.error(
                { step: name, ...failure(error) },
                "an insights step failed; it is tried again next pass",
              );
              return 0;
            }
          };
          const deviation = await run("deviation alerts", () => checkDeviation(today));
          const drift = await run("drift alert", () => checkDrift(today));
          summary.alerts += deviation + drift;
          if (checked) alertsCheckedFor = stamp;
        }
      }
    }

    // The weekly review, at the weekly summary's time, late rather than never for a day.
    if (weeklyClock !== null) {
      const back = (isoWeekday(today) - weeklyDay + 7) % 7;
      const date = addDaysToIsoDate(today, -back);
      const dueMs = zonedInstant(date, weeklyClock, timezone).getTime();
      const monday = mondayOf(date);
      if (nowMs >= dueMs && nowMs < dueMs + DAY && weekSkipped.get(monday) !== dataVersion) {
        const written = await step("weekly review", () => generateWeek(monday, date), null);
        if (written !== null) summary.week++;
      }
    }
    return summary;
  }

  function kick(): void {
    if (current !== null) {
      again = true;
      return;
    }
    current = (async () => {
      do {
        again = false;
        try {
          await tick();
        } catch (error) {
          log.error({ ...failure(error) }, "the insights pass failed");
        }
      } while (again);
    })().finally(() => {
      current = null;
    });
  }

  // ---- Reading --------------------------------------------------------------------------------

  const iso = (date: Date): string => date.toISOString();

  function wireTopic(row: TopicRow): DiscussionTopic {
    return {
      id: row.id,
      text: row.text,
      openingQuestion: row.openingQuestion,
      rideId: row.rideId,
      createdAt: iso(row.createdAt),
      status: row.status,
    };
  }

  async function between(from: string, to: string) {
    await store.expireTopics(now().getTime() - TOPIC_OPEN_MS, now());
    return store.between(startOf(from), startOf(addDaysToIsoDate(to, 1)));
  }

  function wireInsight(insight: InsightRow, topic: TopicRow | null): Insight {
    return {
      id: insight.id,
      kind: insight.kind,
      createdAt: iso(insight.createdAt),
      text: insight.text,
      rideId: insight.rideId,
      topic: insight.kind === "ride" && topic !== null ? wireTopic(topic) : null,
    };
  }

  async function view(row: TopicRow): Promise<TopicView> {
    const note = await store.find("ride", row.rideId);
    const facts = note?.facts.kind === "ride" ? note.facts : null;
    return {
      id: row.id,
      rideId: row.rideId,
      rideName: facts?.ride.name ?? null,
      rideDate: facts?.date ?? null,
      text: row.text,
      openingQuestion: row.openingQuestion,
      rule: row.rule,
      createdAt: iso(row.createdAt),
      status: row.status,
      decidedAt: row.decidedAt === null ? null : iso(row.decidedAt),
    };
  }

  /** The (kind, key) a note for this request would have, where it is known without working the facts out; null for a deviation alert. */
  function knownKey(request: FactsRequest): { kind: InsightKind; key: string } | null {
    const today = localDate(now(), timezone);
    switch (request.kind) {
      case "morning":
        return { kind: "morning", key: request.date ?? today };
      case "ride":
        return { kind: "ride", key: request.rideId };
      case "week":
        return { kind: "week", key: mondayOf(request.weekStart ?? today) };
      case "drift":
        return { kind: "drift", key: mondayOf(request.date ?? today) };
      case "deviation":
        return null;
    }
  }

  async function buildFacts(
    request: FactsRequest,
  ): Promise<{ facts: Facts; kind: InsightKind; key: string } | null> {
    const today = localDate(now(), timezone);
    switch (request.kind) {
      case "morning": {
        const date = request.date ?? today;
        return {
          facts: buildMorningFacts(await gatherer.morning(date)),
          kind: "morning",
          key: date,
        };
      }
      case "ride": {
        const input = await gatherer.ride(request.rideId);
        return input === null
          ? null
          : { facts: buildRideFacts(input), kind: "ride", key: request.rideId };
      }
      case "week": {
        const monday =
          request.weekStart === undefined ? mondayOf(today) : mondayOf(request.weekStart);
        // A week that has not begun has nothing to say.
        if (monday > today) return null;
        const end = addDaysToIsoDate(monday, 6);
        return {
          // A week still going on is reviewed up to today.
          facts: buildWeekFacts(await gatherer.week(monday, [end, today].sort()[0] ?? today)),
          kind: "week",
          key: monday,
        };
      }
      case "deviation": {
        const date = request.date ?? today;
        const day = await gatherer.wellnessDay(date);
        if (day === null) return null;
        const found = deviationCandidates(day, alertSettings, new Set());
        const [recovery, planned, ftp] = await Promise.all([
          gatherer.recovery(),
          gatherer.plannedOn(date),
          gatherer.ftp(),
        ]);
        return {
          facts: buildDeviationFacts({
            date,
            thresholdDays: alertSettings.streakDays,
            signals: found.map((candidate) => ({
              fact: candidate.fact,
              reading: READINGS[candidate.metric].usually,
              trainingHint: READINGS[candidate.metric].hint,
            })),
            recoveryState: recovery?.state ?? null,
            planned,
            ftp,
          }),
          kind: "deviation",
          key: deviationKey(
            date,
            found.map((c) => c.metric),
          ),
        };
      }
      case "drift": {
        const date = request.date ?? today;
        const evaluation = await gatherer.drift(date, alertSettings);
        return {
          facts: buildDriftFacts({
            date,
            windowDays: alertSettings.windowDays,
            missed: evaluation.missed,
            load: evaluation.load,
            reasons: evaluation.reasons,
            thresholds: {
              missedSessions: alertSettings.missedSessions,
              loadAbovePercent: alertSettings.loadAbovePercent,
              loadBelowPercent: alertSettings.loadBelowPercent,
            },
            nextWeek: await gatherer.nextWeek(date),
          }),
          kind: "drift",
          key: mondayOf(date),
        };
      }
    }
  }

  // ---- Telegram -------------------------------------------------------------------------------

  async function messageFor(row: InsightRow): Promise<NotificationMessage | null> {
    const fresh = await store.get(row.id);
    if (fresh === null) return null;
    if (fresh.kind !== "ride") return { text: fresh.text };
    // The topic is shown with the opening question ready to copy: a monospace block, which Telegram lets the reader tap to copy.
    const topic = fresh.rideId === null ? null : await store.topicForRide(fresh.rideId);
    if (topic === null || topic.status !== "open") return { text: fresh.text };
    const head = `${fresh.text}\n\nWorth a chat with your coach: ${topic.text}\nOpening question, tap to copy:\n`;
    return {
      text: `${head}${topic.openingQuestion}`,
      entities: [{ type: "pre", offset: head.length, length: topic.openingQuestion.length }],
    };
  }

  const notifications: NotificationSource = {
    async candidates(nowMs) {
      if (!enabled) return [];
      const rows = await store.since(nowMs - 2 * DAY);
      return rows.map(
        (row): NotificationCandidate => ({
          kind: `insight-${row.kind}`,
          key: row.key,
          dueAt: row.createdAt.getTime(),
          expiresAt: row.createdAt.getTime() + SEND_WINDOW_MS[row.kind],
          build: () => messageFor(row),
        }),
      );
    },
  };

  // ---- The service ----------------------------------------------------------------------------

  return {
    store,
    usesModel: writer.usesModel,
    notifications,

    async list(from, to) {
      const rows = await between(from, to);
      return {
        from,
        to,
        generatedAt: iso(now()),
        stale: false,
        insights: rows.map(({ insight, topic }) => wireInsight(insight, topic)),
      };
    },

    async note(id) {
      const insight = await store.get(id);
      if (insight === null) return null;
      return {
        insight,
        topic: insight.rideId === null ? null : await store.topicForRide(insight.rideId),
      };
    },

    async listWithSource(from, to) {
      const rows = await between(from, to);
      return rows.map(({ insight, topic }) => ({
        insight: wireInsight(insight, topic),
        source: insight.source,
        promptVersion: insight.promptVersion,
        factsHash: insight.factsHash,
      }));
    },

    async facts(request) {
      const stored = (existing: InsightRow): FactsAnswer => ({
        facts: existing.facts,
        note: {
          id: existing.id,
          createdAt: iso(existing.createdAt),
          source: existing.source,
          promptVersion: existing.promptVersion,
          factsHash: existing.factsHash,
        },
        stored: true,
      });
      // A note that exists has its facts stored with it: those are the ones the writer saw, and nothing needs working out.
      const known = knownKey(request);
      if (known !== null) {
        const existing = await store.find(known.kind, known.key);
        if (existing !== null) return stored(existing);
      }
      const built = await buildFacts(request);
      if (built === null) return null;
      const existing = await store.find(built.kind, built.key);
      return existing === null
        ? { facts: built.facts, note: null, stored: false }
        : stored(existing);
    },

    async topics(statuses, limit) {
      await store.expireTopics(now().getTime() - TOPIC_OPEN_MS, now());
      const rows = await store.topics(statuses, limit);
      return Promise.all(rows.map(view));
    },

    async markTopicDiscussed(id) {
      await store.expireTopics(now().getTime() - TOPIC_OPEN_MS, now());
      const row = await store.markDiscussed(id, now());
      return row === null ? null : view(row);
    },

    tick,
    kick,
    idle: () => current ?? Promise.resolve(),

    start() {
      if (stopTicking !== null) return;
      if (!enabled) {
        log.info(
          {},
          "written insights are off (INSIGHTS_ENABLED=false): no note or alert is written or sent",
        );
        return;
      }
      unsubscribe.push(
        bus.on("ride-stored", (event) => {
          announced.add(event.rideId);
          dataVersion++;
          kick();
        }),
        bus.on("wellness-updated", () => {
          dataVersion++;
          kick();
        }),
        bus.on("plan-changed", () => {
          dataVersion++;
          void history.sync().catch((error: unknown) => {
            log.error({ ...failure(error) }, "could not bring the plan history up to date");
          });
          kick();
        }),
      );
      stopTicking = timers.every(MINUTE, kick);
      void history.sync().catch((error: unknown) => {
        log.error({ ...failure(error) }, "could not bring the plan history up to date");
      });
      kick();
    },

    async stop() {
      stopTicking?.();
      stopTicking = null;
      for (const off of unsubscribe.splice(0)) off();
      await current;
    },
  };
}
