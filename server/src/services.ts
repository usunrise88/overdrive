import type { Config } from "./config.ts";
import { DEFAULT_INTERVALS_BASE_URL } from "./config.ts";
import type { CoachSettings } from "./config-coach.ts";
import type { InsightsSettings } from "./config-insights.ts";
import type { IntegrationsSettings } from "./config-integrations.ts";
import type { LiveCoachSettings } from "./config-live-coach.ts";
import type { RoutesSettings } from "./config-routes.ts";
import { type StravaConfig, stravaSettings } from "./config-strava.ts";
import type { Db } from "./db.ts";
import { createEffort, type EffortService } from "./effort/service.ts";
import { createEventBus, type EventBus } from "./events.ts";
import { type CalendarMirror, createCalendar } from "./gcal/index.ts";
import { createInsights, type InsightsService } from "./insights/service.ts";
import { createIntervalsClient, type IntervalsClient } from "./intervals/client.ts";
import {
  createUploadWorker,
  INTERVALS_DESTINATION,
  type UploadWorker,
} from "./intervals/uploads.ts";
import { createLiveCoach, type LiveCoachService } from "./live-coach/service.ts";
import { createLiveCueStore } from "./live-coach/store.ts";
import type { Logger } from "./log.ts";
import { createMetricsService, type MetricsService } from "./metrics/service.ts";
import { createNotebookService, type NotebookService } from "./notebook/service.ts";
import { createNotebookStore } from "./notebook/store.ts";
import { createOutdoor, type OutdoorService } from "./outdoor/service.ts";
import { createExtrasStore, type ExtrasStore } from "./plan/extras.ts";
import { createPlanRefresher, type PlanRefresher } from "./plan/refresher.ts";
import { createPlanStore, type PlanStore } from "./plan/store.ts";
import { createPlanEvents, type PlanEventsService } from "./plan-events/service.ts";
import { createPlanEventStore } from "./plan-events/store.ts";
import { createPowerSkills, type PowerSkillsService } from "./power-skills/service.ts";
import { createProfileStore, type ProfileStore, type RiderWeightSource } from "./profile/store.ts";
import { createProgress, type ProgressService } from "./progress/service.ts";
import { createRepeats, type RepeatsService } from "./repeats/service.ts";
import { createRideStore, type RideStore } from "./ride-store.ts";
import { createRideWorkoutResolver, type RideWorkoutResolver } from "./ride-workout.ts";
import { createRoutes, type RoutesService } from "./routes/service.ts";
import { createStrava, STRAVA_DESTINATION, type StravaUploads } from "./strava/index.ts";
import { createPlanHistory } from "./telegram/history.ts";
import { createTelegram, type TelegramBot } from "./telegram/index.ts";
import { createRideQueries } from "./telegram/rides.ts";
import type { Timers } from "./timers.ts";
import { createVariants, type VariantsService } from "./variants/service.ts";
import { createRideHeartRateIndex } from "./wellness/heart-rate-index.ts";
import { createWellness, type WellnessService } from "./wellness/service.ts";

/** The part of the configuration the integrations read; all optional, with the defaults of `config.ts`. */
export type ServicesConfig = Partial<
  Pick<
    Config,
    | "INTERVALS_API_KEY"
    | "INTERVALS_ATHLETE_ID"
    | "INTERVALS_BASE_URL"
    | "PLAN_REFRESH_MINUTES"
    | "TIMEZONE"
    | "WELLNESS_SOURCE_ORDER"
    | "WELLNESS_PULL_HOURS"
    | "WELLNESS_BASELINE_DAYS"
    | "WELLNESS_NIGHTS_NEEDED"
  >
> &
  IntegrationsSettings &
  InsightsSettings &
  CoachSettings &
  RoutesSettings &
  LiveCoachSettings &
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
  /**
   * Wellness, recovery and the watch's other activities (ADR 0023). Health Connect uploads need no key; the intervals.icu pull and
   * the forwarding of the watch's activities run only with one.
   */
  wellness: WellnessService;
  /** The power skills: the best power at twelve durations over a window and over all time, and a level per skill (spec §6.3, ADR 0042). */
  powerSkills: PowerSkillsService;
  /** Weekly effort: the training load per week and day, the usual range and how this week compares (spec §6.3, ADR 0042). */
  effort: EffortService;
  /** Repeat-session comparison: the workouts ridden more than once, their attempts side by side and their trend (spec §11.7, ADR 0026). */
  repeats: RepeatsService;
  /** Progress over time: best power, aerobic efficiency, efficiency factor, drift, resting heart rate and weight, each with a trend (spec §6.3, ADR 0043). */
  progress: ProgressService;
  /** Outdoor rides from FIT files, shared by the companion and sent to the Telegram bot (spec §11.8, ADR 0026). */
  outdoor: OutdoorService;
  /**
   * The written insights, the alerts and the discussion topics (ADR 0025). Always present: without a language model the notes are
   * written from templates. The Telegram bot sends what it stores.
   */
  insights: InsightsService;
  /**
   * The coach notebook and the game master's (spec §11.6, §12.4, ADR 0035): versioned sections, the caps, the announcement of a
   * methodology change. The REST route serves only the coach one.
   */
  notebook: NotebookService;
  /** Plan events: what tells the coach agent the plan needs rebuilding (spec §12.2, ADR 0035). */
  planEvents: PlanEventsService;
  /** Routes with elevation, created by the agent and ridden on the tablet (spec §11.4, ADR 0036). */
  routes: RoutesService;
  /** What the service keeps about a planned workout that intervals.icu cannot hold: its easier variant, its route ids (ADR 0036), and what is for the live coach (ADR 0038). */
  extras: ExtrasStore;
  /**
   * The live coach (spec §12.1, ADR 0038): sessions, snapshots, the trigger rules, the model's line, the speech service and the stream of
   * cues to the tablet. Always present: without a model and a speech service a session start says why the coach is off.
   */
  liveCoach: LiveCoachService;
  /** The easier variant's life: the plan event and the intervals.icu update when it is ridden, its removal when the session moves (spec §11.5). */
  variants: VariantsService;
  /** Starts the refresher, the upload worker, the calendar mirror, the bot, the wellness pull and its forwarding, the insights. */
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
  const extras = createExtrasStore(db);
  const now = options.now;
  const rides = createRideStore(db);
  // The plan history (what was planned, kept after the cache drops a day) is shared by the bot, the insights and the plan events.
  const planHistory = createPlanHistory({
    db,
    plan,
    timezone,
    now: now ?? (() => new Date()),
  });
  // The rider is told of a methodology change and of a resolved plan event through the bot, which needs its token and the owner's chat.
  const riderReachable =
    config.TELEGRAM_BOT_TOKEN !== undefined && config.TELEGRAM_OWNER_CHAT_ID !== undefined;
  const planEvents = createPlanEvents({
    store: createPlanEventStore(db),
    history: planHistory,
    rides: createRideQueries(db),
    bus,
    timezone,
    now: now ?? (() => new Date()),
    log,
    dayEndGraceMinutes: config.PLAN_EVENT_DAY_END_GRACE_MINUTES,
    timers: options.timers,
    canTellRider: riderReachable,
    watchesPlan: config.INTERVALS_API_KEY !== undefined,
  });
  const notebook = createNotebookService({
    store: createNotebookStore(db),
    bus,
    timezone,
    now: now ?? (() => new Date()),
    limits: {
      ...(config.NOTEBOOK_MAX_CHARS === undefined ? {} : { maxChars: config.NOTEBOOK_MAX_CHARS }),
      ...(config.NOTEBOOK_LOG_MAX_CHARS === undefined
        ? {}
        : { logMaxChars: config.NOTEBOOK_LOG_MAX_CHARS }),
    },
    canAnnounce: riderReachable,
    log,
  });
  // The profile carries heart-rate estimates from the stored rides (ADR 0023).
  const heartRates = createRideHeartRateIndex(db, rides, log);
  // …and the rider's 7-day average weight from Health Connect (spec §12.5), which the wellness module below answers: it is created
  // after the profile (it reads the profile), so the profile reaches it through this late-bound function.
  let riderWeightOf: RiderWeightSource = async () => null;
  const profile = createProfileStore(
    db,
    bus,
    now ?? (() => new Date()),
    () => heartRates.estimates(),
    () => riderWeightOf(),
  );

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
    refresher = createPlanRefresher({
      client: intervals,
      store: plan,
      // What the rider moved or deleted in the calendar becomes a plan event for the coach agent (ADR 0035), and its easier variant goes.
      onCalendarChanges: (change) =>
        Promise.all([planEvents.notePlanChanges(change), variants.onCalendarChanges(change)]),
      ...shared,
    });
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
    extras,
    now: now ?? (() => new Date()),
    log,
  });
  const routes = createRoutes({
    db,
    settings: config,
    now: now ?? (() => new Date()),
    log,
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
  const variants = createVariants({
    db,
    extras,
    plan,
    intervals,
    planEvents,
    rideWorkouts,
    timezone,
    now: now ?? (() => new Date()),
    log,
    timers: options.timers,
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
  // A ride keeps the workout it was played from (the variant, when that was ridden), whatever the plan does later, and the route it
  // followed with the route's key; a ride that played the variant queues the update of its intervals.icu event.
  bus.on("ride-stored", async ({ rideId }) => {
    const header = await rides.getHeader(rideId);
    if (header === null) return;
    if (header.workoutId != null) await rideWorkouts.resolve(header);
    // Independent of each other: one failing does not keep the other from running.
    const done = await Promise.allSettled([variants.onRideStored(header), routes.noteRide(header)]);
    for (const result of done) if (result.status === "rejected") throw result.reason;
  });

  const repeats = createRepeats({
    db,
    rides,
    metrics,
    routes,
    timezone,
    now: now ?? (() => new Date()),
    log,
  });

  const wellness = createWellness({
    db,
    bus,
    profile,
    rides,
    intervals,
    timezone,
    log,
    heartRates,
    metrics,
    ...(now === undefined ? {} : { now }),
    ...(options.timers === undefined ? {} : { timers: options.timers }),
    ...(config.WELLNESS_SOURCE_ORDER === undefined
      ? {}
      : { sourceOrder: config.WELLNESS_SOURCE_ORDER }),
    ...(config.WELLNESS_PULL_HOURS === undefined ? {} : { pullHours: config.WELLNESS_PULL_HOURS }),
    settings: {
      ...(config.WELLNESS_BASELINE_DAYS === undefined
        ? {}
        : { baselineDays: config.WELLNESS_BASELINE_DAYS }),
      ...(config.WELLNESS_NIGHTS_NEEDED === undefined
        ? {}
        : { nightsNeeded: config.WELLNESS_NIGHTS_NEEDED }),
    },
  });
  riderWeightOf = () => wellness.riderWeight();
  const powerSkills = createPowerSkills({
    metrics,
    wellness,
    profile,
    timezone,
    now: now ?? (() => new Date()),
  });
  const effort = createEffort({ metrics, wellness, timezone, now: now ?? (() => new Date()) });
  const progress = createProgress({
    db,
    rides,
    metrics,
    profile,
    wellness,
    bus,
    timezone,
    now: now ?? (() => new Date()),
    log,
  });
  const outdoor = createOutdoor({ db, bus, wellness, timezone, log });
  // The cue log of the live coach is part of what a ride's note and the planning agent know (spec §12.1).
  const liveCues = createLiveCueStore(db);
  const liveCoach = createLiveCoach({
    config,
    store: liveCues,
    plan,
    profile,
    notebook,
    repeats,
    log,
    now,
    fetch: options.fetch,
    timers: options.timers,
  });
  const insights = createInsights({
    config,
    db,
    bus,
    log,
    timezone,
    plan,
    history: planHistory,
    rides,
    rideWorkouts,
    metrics,
    wellness,
    profile,
    liveCues,
    now,
    fetch: options.fetch,
    sleep: options.sleep,
    timers: options.timers,
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
    history: planHistory,
    sources: [insights.notifications, notebook.notifications, planEvents.notifications],
    outdoor,
    planEvents,
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
    wellness,
    powerSkills,
    effort,
    repeats,
    progress,
    outdoor,
    insights,
    notebook,
    planEvents,
    routes,
    extras,
    variants,
    liveCoach,
    start() {
      void uploads?.start();
      void strava?.start();
      refresher?.start(config.PLAN_REFRESH_MINUTES ?? 15);
      void calendar?.start();
      telegram?.start();
      wellness.start();
      metrics.start();
      repeats.start();
      progress.start();
      insights.start();
      planEvents.start();
      variants.start();
      liveCoach.start();
    },
    async stop() {
      await Promise.all([
        uploads?.stop(),
        strava?.stop(),
        refresher?.stop(),
        calendar?.stop(),
        telegram?.stop(),
        wellness.stop(),
        metrics.stop(),
        repeats.whenIdle(),
        progress.whenIdle(),
        insights.stop(),
        planEvents.stop(),
        variants.stop(),
        liveCoach.stop(),
      ]);
    },
  };
}
