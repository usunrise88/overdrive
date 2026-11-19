import { randomBytes } from "node:crypto";
import {
  LIVE_HEARTBEAT_SECONDS,
  LIVE_SESSION_IDLE_SECONDS,
  LIVE_SNAPSHOT_EVERY_SECONDS,
  type LiveCoachOffReason,
  type LiveSessionEnd,
  type LiveSessionEndResponse,
  type LiveSessionStart,
  type LiveSessionStartResponse,
  type LiveSnapshot,
  type Workout,
  workoutHash,
} from "@overdrive/contracts";
import {
  DEFAULT_INSIGHTS_LANGUAGE,
  DEFAULT_LLM_TIMEOUT_SECONDS,
  type InsightsSettings,
} from "../config-insights.ts";
import {
  DEFAULT_LIVE_COACH_DEADLINE_SECONDS,
  DEFAULT_LIVE_COACH_LEAD_SECONDS,
  DEFAULT_LIVE_COACH_RECOVERY_BELOW_PERCENT,
  DEFAULT_TTS_FORMAT,
  DEFAULT_TTS_SPEED,
  DEFAULT_TTS_TIMEOUT_SECONDS,
  type LiveCoachSettings,
} from "../config-live-coach.ts";
import { createLlmClient, type LlmClient } from "../insights/llm.ts";
import { createValidator } from "../insights/validator.ts";
import type { Logger } from "../log.ts";
import type { NotebookService } from "../notebook/service.ts";
import { resolveHeartRateTargets, thresholdHeartRate } from "../plan/heart-rate.ts";
import type { PlanStore } from "../plan/store.ts";
import type { ProfileStore } from "../profile/store.ts";
import type { RepeatsService } from "../repeats/service.ts";
import { realTimers, type Timers } from "../timers.ts";
import { type CueMaker, createCueMaker } from "./cue-maker.ts";
import { type LiveLimits, limitsFrom } from "./limits.ts";
import type { PromptContext } from "./prompt.ts";
import { createSession, type Listener, type LiveSession } from "./session.ts";
import type { LiveCueRow, LiveCueStore } from "./store.ts";
import { buildSessionPlan, DEFAULT_PLAN_SETTINGS, type PlanSettings } from "./structure.ts";
import {
  DEFAULT_TRIGGER_SETTINGS,
  type PreviousAttempt,
  type TriggerSettings,
} from "./triggers.ts";
import { createTtsClient, type TtsClient } from "./tts.ts";

/**
 * The live coach (spec §12.1, ADR 0038): while a workout that asks for it is ridden, the tablet streams a snapshot every 5 seconds, rules
 * on the server decide when a cue may happen, a fast model writes the line (or the agent's own words are used), the rider's speech service
 * speaks it and the audio is pushed to the tablet over a Server-Sent Events stream. The coach talks and shows text and nothing else: it
 * never changes a target, the intensity or the workout. It needs a connection; without one the feature is simply off and the ride is
 * unaffected. The service never calls the tablet.
 *
 * One live session exists at a time (the service has one rider): a start ends the session before it. Sessions live in memory; what
 * outlives them is the cue log on the ride (`store.ts`).
 *
 * HEALTH DATA IS PRIVATE: the snapshots are the rider's readings and the cues are written from them. They go to the language model and the
 * speech service the owner configured and nowhere else; nothing here logs a reading, a line, a key or an address.
 */

export interface LiveCoachAvailability {
  on: boolean;
  reason: LiveCoachOffReason | null;
  /** In words, for the owner and the agent. */
  message: string;
}

export interface LiveCoachService {
  /** The limits on talking in force (spec §12.1). */
  readonly limits: LiveLimits;
  /** Whether the feature can speak at all on this server, whatever the workout: the switch, the speech service and the model. */
  availability(): LiveCoachAvailability;
  /** `POST /v1/live/sessions`: ends the session before it and starts one when the coach is on for this ride. */
  startSession(body: LiveSessionStart): Promise<LiveSessionStartResponse>;
  /** `POST /v1/live/sessions/{id}/snapshots`: `unknown` for a session that is not the live one. */
  snapshot(sessionId: string, snapshot: LiveSnapshot): "accepted" | "stale" | "unknown";
  /** `GET /v1/live/sessions/{id}/cues`: listens; null for a session that is not the live one. Returns the function that stops listening. */
  listen(sessionId: string, listener: Listener, lastEventId: number | null): (() => void) | null;
  /** `POST /v1/live/sessions/{id}/end`; null for a session the service does not know. */
  endSession(sessionId: string, body: LiveSessionEnd): LiveSessionEndResponse | null;
  /** `POST /v1/live/sessions/{id}/cues/{cueId}/played`; false for a cue the service does not know. */
  played(sessionId: string, cueId: number, played: boolean): Promise<boolean>;
  /** The cues the coach made in a ride, in order, with whether the tablet said they were played. */
  cuesOfRide(rideId: string): Promise<LiveCueRow[]>;
  start(): void;
  stop(): Promise<void>;
  /** Resolves when no cue is being made or logged. For the tests. */
  idle(): Promise<void>;
}

export interface LiveCoachDeps {
  config: LiveCoachSettings & InsightsSettings;
  store: LiveCueStore;
  plan: PlanStore;
  profile: ProfileStore;
  notebook: Pick<NotebookService, "get">;
  repeats: Pick<RepeatsService, "previous">;
  log: Logger;
  now?: (() => Date) | undefined;
  timers?: Timers | undefined;
  fetch?: typeof fetch | undefined;
  /** For the tests: other trigger thresholds than the defaults. */
  triggers?: Partial<TriggerSettings> | undefined;
}

/** The sections of the coach's notebook a cue is written with: who the rider is, what works for them, the approach in use. */
const NOTEBOOK_SECTIONS = ["profile", "what_works", "methodology"] as const;
/** How many ended sessions are remembered, for a repeated end and a late report that a cue was played. */
const ENDED_KEPT = 8;
/** The most tokens a cue's answer may take: a reasoning model needs room to think before its one sentence. */
const LIVE_MAX_TOKENS = 400;

const HOUR_SECONDS = 3600;

export function createLiveCoach(deps: LiveCoachDeps): LiveCoachService {
  const { config, store, plan, profile, notebook, repeats, log } = deps;
  const now = deps.now ?? (() => new Date());
  const timers = deps.timers ?? realTimers;
  const limits = limitsFrom(config);
  const enabled = config.LIVE_COACH_ENABLED !== false;
  const language =
    config.LIVE_COACH_LANGUAGE ?? config.INSIGHTS_LANGUAGE ?? DEFAULT_INSIGHTS_LANGUAGE;
  const deadlineMs =
    (config.LIVE_COACH_DEADLINE_SECONDS ?? DEFAULT_LIVE_COACH_DEADLINE_SECONDS) * 1000;
  const triggers: TriggerSettings = {
    ...DEFAULT_TRIGGER_SETTINGS,
    leadSeconds: config.LIVE_COACH_LEAD_SECONDS ?? DEFAULT_LIVE_COACH_LEAD_SECONDS,
    ...deps.triggers,
  };
  const planSettings: PlanSettings = {
    ...DEFAULT_PLAN_SETTINGS,
    recoveryBelowFraction:
      (config.LIVE_COACH_RECOVERY_BELOW_PERCENT ?? DEFAULT_LIVE_COACH_RECOVERY_BELOW_PERCENT) / 100,
  };

  // ---- The model and the speech service --------------------------------------------------------------------------------

  const model = config.LIVE_COACH_LLM_MODEL ?? config.LLM_MODEL;
  let llm: LlmClient | null = null;
  if (config.LLM_BASE_URL !== undefined && model !== undefined) {
    // One request, no repeat (a cue has seconds, not minutes), an answer of a sentence.
    llm = createLlmClient({
      baseUrl: config.LLM_BASE_URL,
      apiKey: config.LLM_API_KEY,
      model,
      timeoutMs: (config.LLM_TIMEOUT_SECONDS ?? DEFAULT_LLM_TIMEOUT_SECONDS) * 1000,
      maxAttempts: 1,
      maxTokens: LIVE_MAX_TOKENS,
      temperature: 0.6,
      fetch: deps.fetch,
      log,
    });
  }
  let tts: TtsClient | null = null;
  if (
    config.TTS_BASE_URL !== undefined &&
    config.TTS_MODEL !== undefined &&
    config.TTS_VOICE !== undefined
  ) {
    tts = createTtsClient({
      baseUrl: config.TTS_BASE_URL,
      apiKey: config.TTS_API_KEY,
      model: config.TTS_MODEL,
      voice: config.TTS_VOICE,
      speed: config.TTS_SPEED ?? DEFAULT_TTS_SPEED,
      format: config.TTS_FORMAT ?? DEFAULT_TTS_FORMAT,
      timeoutMs: (config.TTS_TIMEOUT_SECONDS ?? DEFAULT_TTS_TIMEOUT_SECONDS) * 1000,
      fetch: deps.fetch,
    });
  }
  const maker: CueMaker | null =
    llm === null || tts === null
      ? null
      : createCueMaker({
          llm,
          tts,
          validator: createValidator({ denyWords: config.INSIGHT_DENY_WORDS ?? [] }),
          timers,
          now: () => now().getTime(),
          log,
          deadlineMs,
          maxChars: limits.cueMaxChars,
        });

  if (!enabled) {
    log.info({}, "the live coach is off: LIVE_COACH_ENABLED is false");
  } else if (maker === null) {
    log.info(
      { tts: tts !== null, llm: llm !== null },
      "the live coach is off: it needs a language model (LLM_BASE_URL and LLM_MODEL) and a speech service (TTS_BASE_URL, TTS_MODEL and TTS_VOICE); the rides are unaffected",
    );
  } else {
    log.info(
      {},
      "the live coach is on for the workouts that ask for it: the rider's readings go to the language model and the cue's text to the speech service you configured, and nowhere else",
    );
  }

  function availability(): LiveCoachAvailability {
    if (!enabled) {
      return {
        on: false,
        reason: "feature-off",
        message: "the owner turned the live coach off (LIVE_COACH_ENABLED)",
      };
    }
    if (tts === null) {
      return {
        on: false,
        reason: "no-tts",
        message: "no speech service is configured (TTS_BASE_URL, TTS_MODEL and TTS_VOICE)",
      };
    }
    if (llm === null) {
      return {
        on: false,
        reason: "no-llm",
        message: "no language model is configured (LLM_BASE_URL and LLM_MODEL)",
      };
    }
    return { on: true, reason: null, message: "on" };
  }

  // ---- Sessions ---------------------------------------------------------------------------------------------------------------

  let active: LiveSession | null = null;
  let beat: (() => void) | null = null;
  const ended = new Map<string, LiveSession>();
  /** Starts are one at a time: a second start waits for the first, so the old session is ended exactly once and only one is live. */
  let queue: Promise<unknown> = Promise.resolve();

  function endActive(reason: "ended" | "superseded" | "shutdown"): void {
    const session = active;
    if (session === null) return;
    active = null;
    beat?.();
    beat = null;
    session.end(reason);
    ended.set(session.id, session);
    while (ended.size > ENDED_KEPT) {
      const oldest = ended.keys().next().value;
      if (oldest === undefined) break;
      ended.delete(oldest);
    }
    log.info(
      { sessionId: session.id, rideId: session.rideId, cues: session.cueCount, reason },
      "a live session ended",
    );
  }

  function tick(): void {
    const session = active;
    if (session === null) return;
    // A tablet that went quiet (the app was killed, the network is gone for good) leaves a session that nobody ends.
    if (now().getTime() - session.lastActivity > LIVE_SESSION_IDLE_SECONDS * 1000) {
      endActive("ended");
      return;
    }
    session.heartbeat();
  }

  const off = (reason: LiveCoachOffReason): LiveSessionStartResponse => ({
    sessionId: null,
    coach: { on: false, reason },
    serverTime: now().getTime(),
    snapshotEverySeconds: LIVE_SNAPSHOT_EVERY_SECONDS,
  });

  /** The steps of the workout the tablet plays, or why they cannot be known. */
  function playedSteps(
    planned: { workout: Workout; variant?: { steps: Workout["steps"]; structureHash: string } },
    body: LiveSessionStart,
    threshold: number | null,
  ): Workout | null {
    const primary = planned.workout;
    const variant = planned.variant;
    const asked = body.structureHash ?? null;
    const hashes = (workout: Workout): string[] => [
      workoutHash(workout),
      workoutHash(resolveHeartRateTargets(workout, threshold)),
    ];
    if (asked === null) {
      // An app that sent no hash plays what it was told: the variant when it says so and the service still has it.
      return body.variantRidden === true && variant !== undefined
        ? { ...primary, steps: variant.steps }
        : primary;
    }
    if (hashes(primary).includes(asked)) return primary;
    if (variant !== undefined) {
      const asVariant = { ...primary, steps: variant.steps };
      if (asked === variant.structureHash || hashes(asVariant).includes(asked)) return asVariant;
    }
    return null;
  }

  async function begin(body: LiveSessionStart): Promise<LiveSessionStartResponse> {
    // A new ride is a new session, whatever becomes of this one.
    endActive("superseded");

    const state = availability();
    if (!state.on || maker === null) return off(state.reason ?? "feature-off");
    if (body.workoutId === undefined || body.workoutId === null) return off("no-workout");
    const planned = await plan.get(body.workoutId);
    if (planned === null) return off("no-workout");
    if (planned.workout.live_coach !== true) return off("no-live-coach");
    const accountProfile = await profile.get();
    const workout = playedSteps(planned, body, thresholdHeartRate(accountProfile));
    if (workout === null) return off("structure-changed");

    // What a cue is written with, read once: nothing in the hot path waits for the database.
    let sections: PromptContext["notebook"] = [];
    try {
      const book = await notebook.get("coach");
      sections = NOTEBOOK_SECTIONS.flatMap((key) => {
        const found = book.sections.find((section) => section.key === key);
        return found === undefined || found.text.trim() === ""
          ? []
          : [{ title: found.title, text: found.text }];
      });
    } catch {
      log.warn({}, "the live coach could not read the notebook: the cues are written without it");
    }
    let previous: PreviousAttempt | null = null;
    if (body.structureHash !== undefined && body.structureHash !== null) {
      try {
        const answer = await repeats.previous(body.structureHash, body.rideId);
        if (answer !== "unknown-ride" && answer.previous !== null) {
          previous = {
            date: answer.previous.date,
            steps: new Map(
              answer.previous.steps.map((step) => [
                step.step - 1,
                { averageHeartRate: step.averageHeartRate },
              ]),
            ),
          };
        }
      } catch {
        log.warn(
          {},
          "the live coach could not read the previous attempt: the heart rate is not compared",
        );
      }
    }

    const id = `ls-${randomBytes(8).toString("hex")}`;
    const session = createSession({
      id,
      rideId: body.rideId,
      workoutName: planned.name,
      plan: buildSessionPlan(workout, planSettings),
      ftp: accountProfile.ftp,
      previous,
      prompt: {
        language,
        maxChars: limits.cueMaxChars,
        workoutName: planned.name,
        workoutDescription: planned.workout.description ?? null,
        brief: planned.workout.coach_brief ?? null,
        notebook: sections,
      },
      limits,
      triggers,
      maker,
      store,
      now,
      log,
    });
    active = session;
    beat = timers.every(LIVE_HEARTBEAT_SECONDS * 1000, tick);
    log.info(
      {
        sessionId: id,
        rideId: body.rideId,
        workoutId: body.workoutId,
        previousAttempt: previous !== null,
      },
      "a live session started",
    );
    return {
      sessionId: id,
      coach: { on: true, reason: null },
      serverTime: now().getTime(),
      snapshotEverySeconds: LIVE_SNAPSHOT_EVERY_SECONDS,
    };
  }

  return {
    limits,
    availability,

    startSession(body) {
      const run = queue.then(() => begin(body));
      // The next start waits for this one, however it ends.
      queue = run.catch(() => undefined);
      return run;
    },

    snapshot(sessionId, snapshot) {
      if (active === null || active.id !== sessionId) return "unknown";
      return active.snapshot(snapshot);
    },

    listen(sessionId, listener, lastEventId) {
      if (active === null || active.id !== sessionId) return null;
      return active.listen(listener, lastEventId);
    },

    endSession(sessionId, _body) {
      if (active !== null && active.id === sessionId) {
        const session = active;
        endActive("ended");
        return { sessionId, status: "ended", cues: session.cueCount };
      }
      const before = ended.get(sessionId);
      return before === undefined
        ? null
        : { sessionId, status: "already-ended", cues: before.cueCount };
    },

    async played(sessionId, cueId, played) {
      const session = active !== null && active.id === sessionId ? active : ended.get(sessionId);
      if (session === undefined) return false;
      return session.played(cueId, played, now());
    },

    cuesOfRide(rideId) {
      return store.forRide(rideId);
    },

    start() {},

    async stop() {
      endActive("shutdown");
      await queue.catch(() => undefined);
    },

    async idle() {
      await queue.catch(() => undefined);
      if (active !== null) await active.idle();
      for (const session of ended.values()) await session.idle();
    },
  };
}

export { HOUR_SECONDS };
