import type {
  LiveCue,
  LiveSessionStart,
  LiveSessionStartResponse,
  LiveSnapshot,
  Workout,
} from "@overdrive/contracts";
import { workoutHash } from "@overdrive/contracts";
import type { LightMyRequestResponse } from "fastify";
import type { DeviceToken } from "../../src/config.ts";
import type { Db } from "../../src/db.ts";
import { buildSessionPlan, type SessionPlan } from "../../src/live-coach/structure.ts";
import type { ServicesConfig } from "../../src/services.ts";
import { bearer, TABLET } from "./app.ts";
import { FAKE_LLM_KEY, FAKE_LLM_MODEL, type FakeLlm, startFakeLlm } from "./fake-llm.ts";
import {
  FAKE_TTS_KEY,
  FAKE_TTS_MODEL,
  FAKE_TTS_VOICE,
  type FakeTts,
  startFakeTts,
} from "./fake-tts.ts";
import { type IntegrationHarness, startIntegrationHarness } from "./integrations.ts";

/**
 * The live coach against its fakes (ADR 0038): the real services and routes over the given database, a fake language model and a fake
 * speech service on local ports, a clock and timers the test moves by hand, and a way to read the cue stream as the tablet would. Every
 * number is invented. Nothing waits on a real timer.
 */

export const LIVE_WORKOUT_ID = "icu-7001";
export const LIVE_FTP = 300;
export const LIVE_DATE = "2026-11-20";
export const LIVE_NOW = "2026-11-20T07:00:00Z";
export const LIVE_RIDE_ID = "ride-1795000000000";

/**
 * The session the tests ride, as the plan stores it (no coach fields: those are laid on from the extras). Flat steps:
 * 0 warm-up ramp 10 min, 1 Threshold 10 min at 95%, 2 Recover 5 min at 50%, 3 Threshold, 4 Recover, 5 cool-down 5 min.
 * At an FTP of 300 a Threshold step asks for 285 W.
 */
export function liveWorkout(): Workout {
  return {
    schema: 1,
    id: LIVE_WORKOUT_ID,
    name: "Threshold progression",
    description: "Two ten-minute efforts at threshold with a short recovery between.",
    steps: [
      { type: "ramp", duration: 600, from: 0.45, to: 0.7, label: "Warm-up" },
      {
        type: "repeat",
        count: 2,
        steps: [
          { type: "steady", duration: 600, target: 0.95, label: "Threshold" },
          { type: "steady", duration: 300, target: 0.5, label: "Recover" },
        ],
      },
      { type: "ramp", duration: 300, from: 0.55, to: 0.4, label: "Cool-down" },
    ],
  };
}

/** The flattened plan of {@link liveWorkout}, as the live coach reads it. */
export function livePlan(workout: Workout = liveWorkout()): SessionPlan {
  return buildSessionPlan(workout);
}

export interface LiveEnv {
  llm: FakeLlm;
  tts: FakeTts;
}

/** What the fake model says to each trigger: a line made only of numbers the facts hold, so the check lets it through. */
export function liveLine(facts: Record<string, unknown> | null): string {
  if (facts === null || facts.kind !== "live-cue") return "NO_CUE";
  const detail = facts.detail as Record<string, number | string | null>;
  switch (facts.trigger) {
    case "step-start":
      return `Interval ${detail.hardStepNumber}: hold ${detail.targetWatts} watts, start smooth.`;
    case "two-minutes":
      return "Two minutes left. Keep your shoulders loose.";
    case "power-drift":
      return `Power is ${detail.direction} target. Hold ${detail.targetWatts} watts.`;
    case "cadence-sag":
      return `Cadence is ${detail.averageRpm}. Spin it up a little.`;
    case "heart-rate-climb":
      return `Heart rate is ${detail.higherByBpm} higher than last time. Settle your breathing.`;
    case "strong-finish":
      return `${detail.minutesRemaining} minutes to go. Finish smooth and strong.`;
    default:
      return "NO_CUE";
  }
}

export async function startLiveEnv(): Promise<LiveEnv> {
  return { llm: await startFakeLlm(liveLine), tts: await startFakeTts() };
}

export async function stopLiveEnv(env: LiveEnv): Promise<void> {
  await env.llm.close();
  await env.tts.close();
}

/** What a snapshot says besides its step: everything is optional and defaults to a rider riding well. */
export interface Spec {
  step: number;
  /** Seconds left in the step. */
  left: number;
  /** null: not measured. Default: the target (or 140 W in a step with none). */
  power?: number | null;
  hr?: number | null;
  cad?: number | null;
  /** Default 95. */
  compliance?: number | null;
  muted?: boolean;
  paused?: boolean;
  guard?: boolean;
  events?: LiveSnapshot["events"];
  /** The ride's elapsed seconds; default: derived from `seq`. */
  elapsed?: number;
}

export interface LiveHarness extends IntegrationHarness {
  env: LiveEnv;
  plan: SessionPlan;
  /** `http://127.0.0.1:port`: the service really listening, for the event stream. */
  baseUrl: string;
  /** The started session's id, after {@link LiveHarness.begin}. */
  sessionId: string;
  /** Plans the session in the plan cache with what the agent kept for it. */
  seed(options?: SeedOptions): Promise<void>;
  /** `POST /v1/live/sessions`. */
  post(path: string, body?: unknown, token?: DeviceToken | null): Promise<LightMyRequestResponse>;
  /** Starts a session for the seeded workout and remembers its id. */
  begin(body?: Partial<LiveSessionStart>): Promise<LiveSessionStartResponse>;
  /** Sends a snapshot of the live session `seconds` after the last one (the clock moves first), and returns the answer. */
  send(spec: Spec, seconds?: number): Promise<LightMyRequestResponse>;
  /** Sends one snapshot after another, `spacing` seconds apart, and waits for what each one made. */
  sendAll(specs: readonly Spec[], spacing?: number): Promise<void>;
  /** Waits for the cue that is being made, and the log of the ones made. */
  idle(): Promise<void>;
  close(): Promise<void>;
}

export interface SeedOptions {
  workout?: Workout;
  /** Default true. */
  liveCoach?: boolean;
  brief?: string | null;
  /** The lines by step position, as the extras keep them. */
  mustSay?: Record<string, { at: "start" | number; text: string }[]>;
  ftp?: number | null;
}

export interface LiveStartOptions {
  config?: ServicesConfig;
  now?: string;
  /** false: the model and the speech service are not configured. */
  model?: boolean;
  speech?: boolean;
}

/** The whole service for a live-coach test: started, listening, seeded when asked. */
export async function startLive(
  db: Db,
  env: LiveEnv,
  options: LiveStartOptions = {},
): Promise<LiveHarness> {
  const h = startIntegrationHarness(db, {
    now: options.now ?? LIVE_NOW,
    timezone: "UTC",
    config: {
      INSIGHTS_ENABLED: false,
      ...(options.model === false
        ? {}
        : { LLM_BASE_URL: env.llm.url, LLM_MODEL: FAKE_LLM_MODEL, LLM_API_KEY: FAKE_LLM_KEY }),
      ...(options.speech === false
        ? {}
        : {
            TTS_BASE_URL: env.tts.url,
            TTS_MODEL: FAKE_TTS_MODEL,
            TTS_VOICE: FAKE_TTS_VOICE,
            TTS_API_KEY: FAKE_TTS_KEY,
          }),
      ...options.config,
    },
  });
  await h.app.listen({ host: "127.0.0.1", port: 0 });
  const address = h.app.server.address();
  const port = typeof address === "object" && address !== null ? address.port : 0;
  const plan = livePlan();
  const counters = { seq: 0, elapsed: 0 };

  const live: LiveHarness = {
    ...h,
    env,
    plan,
    baseUrl: `http://127.0.0.1:${port}`,
    sessionId: "",

    async seed(seedOptions = {}) {
      const workout = seedOptions.workout ?? liveWorkout();
      await h.services.plan.upsert({
        id: workout.id,
        source: "intervals.icu",
        externalId: workout.id.replace(/^icu-/, ""),
        date: LIVE_DATE,
        name: workout.name,
        workout,
        unsupportedReason: null,
      });
      const ftp = seedOptions.ftp === undefined ? LIVE_FTP : seedOptions.ftp;
      if (ftp !== null) await h.services.profile.update({ ftp });
      const { stepsFingerprint } = await import("../../src/plan/extras.ts");
      await h.services.extras.setCoach(workout.id, LIVE_DATE, {
        liveCoach: seedOptions.liveCoach ?? true,
        ...(seedOptions.brief === undefined ? {} : { coachBrief: seedOptions.brief }),
        ...(seedOptions.mustSay === undefined
          ? {}
          : {
              mustSay: {
                lines: seedOptions.mustSay,
                stepsHash: stepsFingerprint(workout.steps),
              },
            }),
      });
    },

    post(path, body, token = TABLET) {
      return h.app.inject({
        method: "POST",
        url: `/v1/live${path}`,
        headers: {
          ...(token === null ? {} : bearer(token)),
          ...(body === undefined ? {} : { "content-type": "application/json" }),
        },
        ...(body === undefined
          ? {}
          : { payload: typeof body === "string" ? body : JSON.stringify(body) }),
      });
    },

    async begin(body = {}) {
      const workout = liveWorkout();
      const start: LiveSessionStart = {
        rideId: LIVE_RIDE_ID,
        workoutId: LIVE_WORKOUT_ID,
        structureHash: workoutHash(workout),
        clock: h.clock.now().getTime(),
        ...body,
      };
      const response = await live.post("/sessions", start);
      if (response.statusCode !== 201) throw new Error(`start answered ${response.statusCode}`);
      const answer = response.json() as LiveSessionStartResponse;
      live.sessionId = answer.sessionId ?? "";
      counters.seq = 0;
      counters.elapsed = 0;
      return answer;
    },

    async send(spec, seconds = 5) {
      h.clock.advance(seconds * 1000);
      counters.elapsed += seconds;
      const step = plan.steps[spec.step];
      if (step === undefined) throw new Error(`the test workout has no step ${spec.step}`);
      const target = step.fromFraction === null ? null : Math.round(step.fromFraction * LIVE_FTP);
      const readings = {
        power: spec.power === undefined ? (target ?? 140) : spec.power,
        heartRate: spec.hr === undefined ? 150 : spec.hr,
        cadence: spec.cad === undefined ? 90 : spec.cad,
        compliancePercent:
          step.type === "free" ? null : spec.compliance === undefined ? 95 : spec.compliance,
      };
      const snapshot: LiveSnapshot = {
        seq: counters.seq++,
        clock: h.clock.now().getTime(),
        elapsedSeconds: spec.elapsed ?? counters.elapsed,
        paused: spec.paused === true,
        muted: spec.muted === true,
        step: {
          index: spec.step,
          type: step.type,
          label: step.label,
          durationSeconds: step.durationSeconds,
          timeLeftSeconds: spec.left,
          ...(target === null ? {} : { targetWatts: target }),
        },
        stepCount: plan.steps.length,
        ...(readings.power === null ? {} : { power: readings.power }),
        ...(readings.heartRate === null ? {} : { heartRate: readings.heartRate }),
        ...(readings.cadence === null ? {} : { cadence: readings.cadence }),
        ...(readings.compliancePercent === null
          ? {}
          : { compliancePercent: readings.compliancePercent }),
        stallGuardActive: spec.guard === true,
        ...(spec.events === undefined ? {} : { events: spec.events }),
      };
      return live.post(`/sessions/${live.sessionId}/snapshots`, snapshot);
    },

    async sendAll(specs, spacing = 5) {
      for (const spec of specs) {
        const answer = await live.send(spec, spacing);
        if (answer.statusCode !== 204) throw new Error(`a snapshot answered ${answer.statusCode}`);
        await live.idle();
      }
    },

    idle: () => h.services.liveCoach.idle(),

    async close() {
      await h.app.close();
    },
  };
  return live;
}

// ---- The event stream as the tablet reads it -------------------------------------------------------------------------

export interface Frame {
  id?: string;
  event?: string;
  data?: string;
  retry?: string;
  /** The text of a comment line (`: hb`). */
  comment?: string;
}

export interface Stream {
  status: number;
  headers: Headers;
  /** Every frame received so far. */
  frames: Frame[];
  /** The next frame not yet taken. */
  next(): Promise<Frame>;
  /** The next `cue` event, parsed. */
  nextCue(): Promise<{ frame: Frame; cue: LiveCue }>;
  /** The next frame that is an event of this name (frames before it are taken and dropped). */
  nextEvent(name: string): Promise<Frame>;
  /** Resolves when another frame arrives or the connection closes, without taking anything. */
  changed(): Promise<void>;
  /** The cues received so far. */
  cues(): LiveCue[];
  /** True once the server has closed the connection. */
  readonly closed: boolean;
  /** Resolves when the connection is closed. */
  whenClosed: Promise<void>;
  /** Drops the connection, as a tablet that lost its network does. */
  abort(): void;
}

function parseFrame(block: string): Frame {
  const frame: Frame = {};
  for (const line of block.split("\n")) {
    if (line === "") continue;
    if (line.startsWith(":")) {
      frame.comment = line.slice(1).trim();
      continue;
    }
    const colon = line.indexOf(":");
    const field = line.slice(0, colon);
    const value = line.slice(colon + 1).replace(/^ /, "");
    if (field === "id" || field === "event" || field === "data" || field === "retry") {
      frame[field] = value;
    }
  }
  return frame;
}

/** Opens `GET /v1/live/sessions/{id}/cues` over a real connection and reads it frame by frame. */
export async function openStream(
  h: LiveHarness,
  options: { sessionId?: string; lastEventId?: number; token?: DeviceToken | null } = {},
): Promise<Stream> {
  const controller = new AbortController();
  const token = options.token === undefined ? TABLET : options.token;
  const response = await fetch(
    `${h.baseUrl}/v1/live/sessions/${options.sessionId ?? h.sessionId}/cues`,
    {
      headers: {
        accept: "text/event-stream",
        ...(token === null ? {} : bearer(token)),
        ...(options.lastEventId === undefined
          ? {}
          : { "last-event-id": String(options.lastEventId) }),
      },
      signal: controller.signal,
    },
  );
  const frames: Frame[] = [];
  let taken = 0;
  let closed = false;
  const waiters: (() => void)[] = [];
  const wake = (): void => {
    for (const waiter of waiters.splice(0)) waiter();
  };
  let finished: () => void = () => {};
  const whenClosed = new Promise<void>((resolve) => {
    finished = resolve;
  });

  if (response.body !== null && response.status === 200) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    void (async () => {
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let at = buffer.indexOf("\n\n");
          while (at >= 0) {
            frames.push(parseFrame(buffer.slice(0, at)));
            buffer = buffer.slice(at + 2);
            at = buffer.indexOf("\n\n");
            wake();
          }
        }
      } catch {
        // Aborted by the test or closed by the server.
      } finally {
        closed = true;
        wake();
        finished();
      }
    })();
  } else {
    closed = true;
    finished();
  }

  const stream: Stream = {
    status: response.status,
    headers: response.headers,
    frames,
    async next() {
      while (taken >= frames.length) {
        if (closed) throw new Error("the stream is closed");
        await new Promise<void>((resolve) => waiters.push(resolve));
      }
      return frames[taken++] as Frame;
    },
    async nextCue() {
      for (;;) {
        const frame = await stream.next();
        if (frame.event === "cue")
          return { frame, cue: JSON.parse(frame.data ?? "null") as LiveCue };
      }
    },
    async nextEvent(name) {
      for (;;) {
        const frame = await stream.next();
        if (frame.event === name) return frame;
      }
    },
    async changed() {
      if (closed) return;
      await new Promise<void>((resolve) => waiters.push(resolve));
    },
    cues: () =>
      frames.filter((f) => f.event === "cue").map((f) => JSON.parse(f.data ?? "null") as LiveCue),
    get closed() {
      return closed;
    },
    whenClosed,
    abort() {
      controller.abort();
    },
  };
  return stream;
}

/**
 * Makes sure nothing that was going to be written to the stream is still on its way: fires the loops' timers (the heartbeat) and reads
 * until the comment arrives. What the server wrote before it is in `stream.frames` by then, in order.
 */
export async function flush(h: LiveHarness, stream: Stream): Promise<void> {
  const before = stream.frames.filter((f) => f.comment === "hb").length;
  h.timers.tick();
  while (stream.frames.filter((f) => f.comment === "hb").length === before) {
    if (stream.closed) return;
    await stream.changed();
  }
}

/** The cues the stream has carried once everything the server wrote before now has arrived. */
export async function heard(h: LiveHarness, stream: Stream): Promise<LiveCue[]> {
  await flush(h, stream);
  return stream.cues();
}
