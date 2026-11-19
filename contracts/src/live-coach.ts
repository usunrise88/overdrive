import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Step, Validation, Workout } from "./index.ts";
import { isValidRideId } from "./sync.ts";
import { parseInstant } from "./wellness.ts";

// ---- The live coach (spec §12.1, ADR 0038) ------------------------------------------------------------------------
// Hand-written mirrors of schema/live-*.schema.json, which are the source of truth; the fixtures test keeps the two honest.
// The ride app starts a session, streams a snapshot of the ride every 5 seconds with plain POSTs and listens on a Server-Sent
// Events stream for cues, each with its speech inline. The workout's own additions (`live_coach`, `coach_brief`, the steps'
// `must_say`) are in schema/workout.schema.json and `plan.ts`.
//
// THE SNAPSHOT IS THE RIDER'S BODY: its numbers (power, heart rate, cadence) are never in an error message built here, only
// paths and rules.

/** The longest cue, in characters (Unicode code points): about 5 seconds of speech. Also the longest `must_say` line. */
export const LIVE_CUE_MAX_CHARS = 80;
/** The longest `coach_brief`, in characters. */
export const LIVE_COACH_BRIEF_MAX_CHARS = 1000;
/** At most this many `must_say` lines on a step. */
export const LIVE_MUST_SAY_PER_STEP = 3;
/** The app sends a snapshot this often, in seconds. */
export const LIVE_SNAPSHOT_EVERY_SECONDS = 5;
/** The service writes a comment into the event stream this often, in seconds, so that no proxy closes a quiet connection. */
export const LIVE_HEARTBEAT_SECONDS = 15;
/** A session with no snapshot for this long is ended by the service, in seconds. */
export const LIVE_SESSION_IDLE_SECONDS = 600;
/** The largest speech of one cue, in bytes (before base64). */
export const LIVE_CUE_AUDIO_MAX_BYTES = 400_000;

/** Why a cue was made. A reader treats a name it does not know as a plain string. */
export const LIVE_CUE_TRIGGERS = [
  "step-start",
  "two-minutes",
  "power-drift",
  "cadence-sag",
  "heart-rate-climb",
  "strong-finish",
  "must-say",
] as const;
export type LiveCueTrigger = (typeof LIVE_CUE_TRIGGERS)[number];

/** Why the live coach is off for a ride. A reader treats a reason it does not know as "off". */
export const LIVE_COACH_OFF_REASONS = [
  "feature-off",
  "no-tts",
  "no-llm",
  "no-workout",
  "no-live-coach",
  "structure-changed",
] as const;
export type LiveCoachOffReason = (typeof LIVE_COACH_OFF_REASONS)[number];

export const LIVE_AUDIO_FORMATS = ["mp3", "opus", "aac", "flac", "wav"] as const;
export type LiveAudioFormat = (typeof LIVE_AUDIO_FORMATS)[number];

export const LIVE_SESSION_END_REASONS = ["finished", "stopped", "error"] as const;
export type LiveSessionEndReason = (typeof LIVE_SESSION_END_REASONS)[number];

export const LIVE_STREAM_END_REASONS = ["ended", "superseded", "shutdown"] as const;
export type LiveStreamEndReason = (typeof LIVE_STREAM_END_REASONS)[number];

export const LIVE_EVENT_TYPES = ["stall-guard", "strap-lost", "paused", "resumed"] as const;
export type LiveEventType = (typeof LIVE_EVENT_TYPES)[number];

// ---- Types ----------------------------------------------------------------------------------------------------------

/** A line the live coach speaks exactly as written, tied to a step (spec §12.1). */
export interface MustSay {
  /** `"start"`: as the step begins; or the second of the step to speak it at (at least 1, below the step's duration). */
  at: "start" | number;
  /** At most {@link LIVE_CUE_MAX_CHARS} characters. */
  text: string;
}

/** The body of `POST /v1/live/sessions`. */
export interface LiveSessionStart {
  rideId: string;
  /** The planned workout's id (`icu-123`); absent or null for a ride with no workout. */
  workoutId?: string | null;
  /** The structure hash the service sent with the workout (the variant's, when that is ridden). */
  structureHash?: string | null;
  variantRidden?: boolean;
  /** The app's clock, milliseconds since the Unix epoch. */
  clock: number;
}

/** The answer to `POST /v1/live/sessions`. */
export interface LiveSessionStartResponse {
  /** Null exactly when the coach is off. */
  sessionId: string | null;
  coach: { on: boolean; reason: LiveCoachOffReason | null };
  /** The service's clock, milliseconds since the Unix epoch. */
  serverTime: number;
  snapshotEverySeconds: 5;
}

/** The step being ridden, in a snapshot. */
export interface LiveSnapshotStep {
  /** In the flattened workout, from 0, as the ride record's `stepIndex`. */
  index: number;
  type: "steady" | "ramp" | "free" | "hr";
  label?: string;
  durationSeconds: number;
  /** Pauses do not count. */
  timeLeftSeconds: number;
  /** Watts the step asks for now (steady and ramp). */
  targetWatts?: number | null;
  /** Beats per minute the step holds (heart-rate steps). */
  targetBpm?: number | null;
}

export interface LiveSnapshotEvent {
  type: LiveEventType;
  secondsAgo: number;
}

/** The body of `POST /v1/live/sessions/{id}/snapshots`, sent every 5 seconds. */
export interface LiveSnapshot {
  /** From 0; a repeat or an older one is ignored. */
  seq: number;
  /** The app's clock, milliseconds since the Unix epoch. */
  clock: number;
  /** The ride record's sense: pauses included. */
  elapsedSeconds: number;
  paused: boolean;
  /** The rider muted the coach for the rest of the ride. */
  muted: boolean;
  step: LiveSnapshotStep;
  /** The number of steps of the flattened workout. */
  stepCount: number;
  /** Means over the last 5 seconds; absent (or null) when not measured. */
  power?: number | null;
  heartRate?: number | null;
  cadence?: number | null;
  /** Share of the judged seconds of the step within 5% of target (or inside the heart-rate band), 0 to 100. */
  compliancePercent?: number | null;
  stallGuardActive?: boolean;
  /** Newest first, at most 8. */
  events?: LiveSnapshotEvent[];
}

/** The speech of a cue, inline. */
export interface LiveCueAudio {
  format: LiveAudioFormat;
  /** The audio bytes in base64: at most {@link LIVE_CUE_AUDIO_MAX_BYTES} bytes before encoding. */
  base64: string;
}

/** The data of a `cue` event on `GET /v1/live/sessions/{id}/cues`; `id` is also the event's SSE id. */
export interface LiveCue {
  id: number;
  text: string;
  /** A {@link LiveCueTrigger}, or a name a later service adds. */
  trigger: string;
  audio: LiveCueAudio;
  /** UTC instant. */
  createdAt: string;
  /** UTC instant. */
  expiresAt: string;
  /** `expiresAt` minus `createdAt`, in milliseconds. */
  ttlMs: number;
}

/** The data of the `end` event. */
export interface LiveStreamEnd {
  reason: LiveStreamEndReason;
}

/** The optional body of `POST /v1/live/sessions/{id}/end`. */
export interface LiveSessionEnd {
  reason?: LiveSessionEndReason;
  clock?: number;
}

/** The answer to `POST /v1/live/sessions/{id}/end`. */
export interface LiveSessionEndResponse {
  sessionId: string;
  status: "ended" | "already-ended";
  cues: number;
}

/** The optional body of `POST /v1/live/sessions/{id}/cues/{cueId}/played`. */
export interface LiveCuePlayed {
  played: boolean;
  clock?: number;
}

// ---- Validation -----------------------------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

const checkStart = ajv.compile<LiveSessionStart>(load("live-session-start.schema.json"));
const checkStartResponse = ajv.compile<LiveSessionStartResponse>(
  load("live-session-start-response.schema.json"),
);
const checkSnapshot = ajv.compile<LiveSnapshot>(load("live-snapshot.schema.json"));
const checkCue = ajv.compile<LiveCue>(load("live-cue.schema.json"));
const checkStreamEnd = ajv.compile<LiveStreamEnd>(load("live-stream-end.schema.json"));
const checkEnd = ajv.compile<LiveSessionEnd>(load("live-session-end.schema.json"));
const checkEndResponse = ajv.compile<LiveSessionEndResponse>(
  load("live-session-end-response.schema.json"),
);
const checkPlayed = ajv.compile<LiveCuePlayed>(load("live-cue-played.schema.json"));

/** The number of characters of a text as the contract counts them: Unicode code points. */
export function liveTextLength(text: string): number {
  return [...text].length;
}

/** Validates the body of `POST /v1/live/sessions`. */
export function validateLiveSessionStart(input: unknown): Validation<LiveSessionStart> {
  if (!checkStart(input)) return { ok: false, errors: plain(checkStart.errors) };
  if (!isValidRideId(input.rideId)) return { ok: false, errors: ["/rideId is not a ride id"] };
  return { ok: true, value: input };
}

/** What the schema cannot say about the answer to a start: the session and the reason follow from `coach.on`. */
export function liveSessionStartResponseErrors(response: LiveSessionStartResponse): string[] {
  const errors: string[] = [];
  const on = response.coach.on;
  if (on && response.sessionId === null) errors.push("/sessionId must be set when the coach is on");
  if (on && response.coach.reason !== null) errors.push("/coach/reason must be null when it is on");
  if (!on && response.sessionId !== null) errors.push("/sessionId must be null when it is off");
  if (!on && response.coach.reason === null) errors.push("/coach/reason says why it is off");
  return errors;
}

export function validateLiveSessionStartResponse(
  input: unknown,
): Validation<LiveSessionStartResponse> {
  if (!checkStartResponse(input)) return { ok: false, errors: plain(checkStartResponse.errors) };
  const errors = liveSessionStartResponseErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about a snapshot: the time left is within the step, the targets belong to the step's type. */
export function liveSnapshotConsistencyErrors(snapshot: LiveSnapshot): string[] {
  const errors: string[] = [];
  const { step } = snapshot;
  if (step.timeLeftSeconds > step.durationSeconds) {
    errors.push("/step/timeLeftSeconds is more than /step/durationSeconds");
  }
  if (step.index >= snapshot.stepCount) {
    errors.push("/step/index is not below /stepCount");
  }
  if (step.type === "free" && (step.targetWatts != null || step.targetBpm != null)) {
    errors.push("/step has no target in a free step");
  }
  if (step.type === "hr" && step.targetWatts != null) {
    errors.push("/step/targetWatts does not belong to a heart-rate step: it holds a heart rate");
  }
  if ((step.type === "steady" || step.type === "ramp") && step.targetBpm != null) {
    errors.push("/step/targetBpm belongs only to a heart-rate step");
  }
  if (snapshot.compliancePercent != null && step.type === "free") {
    errors.push("/compliancePercent has no meaning in a free step");
  }
  return errors;
}

export function validateLiveSnapshot(input: unknown): Validation<LiveSnapshot> {
  if (!checkSnapshot(input)) return { ok: false, errors: plain(checkSnapshot.errors) };
  const errors = liveSnapshotConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about a cue: real instants, `expiresAt` after `createdAt`, `ttlMs` the difference, valid base64. */
export function liveCueConsistencyErrors(cue: LiveCue): string[] {
  const errors: string[] = [];
  const created = parseInstant(cue.createdAt);
  const expires = parseInstant(cue.expiresAt);
  if (created === null) errors.push("/createdAt is not a real date and time");
  if (expires === null) errors.push("/expiresAt is not a real date and time");
  if (created !== null && expires !== null) {
    if (expires <= created) errors.push("/expiresAt is not after /createdAt");
    else if (expires - created !== cue.ttlMs)
      errors.push("/ttlMs is not /expiresAt minus /createdAt");
  }
  const { base64 } = cue.audio;
  if (base64.length % 4 !== 0) {
    errors.push("/audio/base64 is not padded to a multiple of 4 characters");
  } else if (Buffer.from(base64, "base64").length > LIVE_CUE_AUDIO_MAX_BYTES) {
    errors.push(`/audio/base64 is more than ${LIVE_CUE_AUDIO_MAX_BYTES} bytes`);
  }
  if (liveTextLength(cue.text) > LIVE_CUE_MAX_CHARS) {
    errors.push(`/text is more than ${LIVE_CUE_MAX_CHARS} characters`);
  }
  return errors;
}

export function validateLiveCue(input: unknown): Validation<LiveCue> {
  if (!checkCue(input)) return { ok: false, errors: plain(checkCue.errors) };
  const errors = liveCueConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

export function validateLiveStreamEnd(input: unknown): Validation<LiveStreamEnd> {
  return checkStreamEnd(input)
    ? { ok: true, value: input }
    : { ok: false, errors: plain(checkStreamEnd.errors) };
}

/** Validates the body of `POST /v1/live/sessions/{id}/end`. */
export function validateLiveSessionEnd(input: unknown): Validation<LiveSessionEnd> {
  return checkEnd(input)
    ? { ok: true, value: input }
    : { ok: false, errors: plain(checkEnd.errors) };
}

export function validateLiveSessionEndResponse(input: unknown): Validation<LiveSessionEndResponse> {
  return checkEndResponse(input)
    ? { ok: true, value: input }
    : { ok: false, errors: plain(checkEndResponse.errors) };
}

/** Validates the body of `POST /v1/live/sessions/{id}/cues/{cueId}/played`. */
export function validateLiveCuePlayed(input: unknown): Validation<LiveCuePlayed> {
  return checkPlayed(input)
    ? { ok: true, value: input }
    : { ok: false, errors: plain(checkPlayed.errors) };
}

// ---- The workout's live-coach fields ---------------------------------------------------------------------------------

/** The `must_say` lines of a step, or none. A repeat has none of its own. */
export function mustSayOf(step: Step): readonly MustSay[] {
  return step.type === "repeat" ? [] : (step.must_say ?? []);
}

/**
 * The mistakes in the live coach's fields of a workout, said plainly with the path (a nested `oneOf` makes the schema's own messages
 * point at the step and no further): a brief of the wrong length, a flag that is not true or false, more than 3 lines in a step, a line
 * with no moment or no words, or too many. Takes anything, never throws, and never quotes a line or the brief.
 */
export function liveCoachShapeErrors(input: unknown): string[] {
  const errors: string[] = [];
  if (typeof input !== "object" || input === null) return errors;
  const workout = input as Record<string, unknown>;
  if (workout.live_coach !== undefined && typeof workout.live_coach !== "boolean") {
    errors.push("/live_coach must be true or false");
  }
  const brief = workout.coach_brief;
  if (brief !== undefined) {
    if (typeof brief !== "string") errors.push("/coach_brief must be a string");
    else if (brief.trim() === "") errors.push("/coach_brief must not be blank");
    else if (liveTextLength(brief) > LIVE_COACH_BRIEF_MAX_CHARS) {
      errors.push(
        `/coach_brief is ${liveTextLength(brief)} characters, at most ${LIVE_COACH_BRIEF_MAX_CHARS}`,
      );
    }
  }
  const checkStep = (step: unknown, at: string): void => {
    if (typeof step !== "object" || step === null) return;
    const record = step as Record<string, unknown>;
    if (record.type === "repeat") {
      if (record.must_say !== undefined)
        errors.push(`${at}/must_say: a repeat has no lines of its own, put them on its steps`);
      if (Array.isArray(record.steps)) {
        for (const [i, inner] of record.steps.entries()) checkStep(inner, `${at}/steps/${i}`);
      }
      return;
    }
    const lines = record.must_say;
    if (lines === undefined) return;
    if (!Array.isArray(lines)) {
      errors.push(`${at}/must_say must be a list of lines`);
      return;
    }
    if (lines.length === 0) errors.push(`${at}/must_say must have a line or be left out`);
    if (lines.length > LIVE_MUST_SAY_PER_STEP) {
      errors.push(
        `${at}/must_say has ${lines.length} lines, at most ${LIVE_MUST_SAY_PER_STEP} per step`,
      );
    }
    for (const [i, line] of lines.entries()) {
      const here = `${at}/must_say/${i}`;
      if (typeof line !== "object" || line === null) {
        errors.push(`${here} must be { at, text }`);
        continue;
      }
      const item = line as Record<string, unknown>;
      const moment = item.at;
      if (
        moment !== "start" &&
        !(typeof moment === "number" && Number.isInteger(moment) && moment >= 1)
      ) {
        errors.push(`${here}/at must be "start" or a whole second of the step, 1 or more`);
      }
      const text = item.text;
      if (typeof text !== "string") errors.push(`${here}/text must be a string`);
      else if (text.trim() === "") errors.push(`${here}/text must not be blank`);
      else if (liveTextLength(text) > LIVE_CUE_MAX_CHARS) {
        errors.push(
          `${here}/text is ${liveTextLength(text)} characters, at most ${LIVE_CUE_MAX_CHARS}`,
        );
      }
      for (const key of Object.keys(item)) {
        if (key !== "at" && key !== "text") errors.push(`${here} has no property ${key}`);
      }
    }
  };
  if (Array.isArray(workout.steps)) {
    for (const [i, step] of workout.steps.entries()) checkStep(step, `/steps/${i}`);
  }
  return errors;
}

/**
 * What the schema cannot say about the `must_say` lines of a workout: a line's second is below the step's duration, and two
 * lines of a step are not at the same moment. Paths are those of the workout; no message quotes a line's text.
 */
export function mustSayConsistencyErrors(workout: Pick<Workout, "steps">): string[] {
  const errors: string[] = [];
  const check = (step: Step, at: string): void => {
    if (step.type === "repeat") return;
    const seen = new Set<string | number>();
    for (const [index, line] of (step.must_say ?? []).entries()) {
      if (typeof line.at === "number" && line.at >= step.duration) {
        errors.push(
          `${at}/must_say/${index}/at is ${line.at}, the step lasts ${step.duration} s: a line is spoken inside the step`,
        );
      }
      if (seen.has(line.at)) {
        errors.push(`${at}/must_say/${index}/at is the same moment as an earlier line of the step`);
      }
      seen.add(line.at);
    }
  };
  for (const [index, step] of workout.steps.entries()) {
    if (step.type === "repeat") {
      for (const [inner, leaf] of step.steps.entries()) {
        check(leaf, `/steps/${index}/steps/${inner}`);
      }
    } else {
      check(step, `/steps/${index}`);
    }
  }
  return errors;
}

/**
 * The workout without what is only for the live coach: `live_coach`, `coach_brief` and every step's `must_say`. The copy of the
 * workout a ride keeps (what was ridden) has none of them: they are the agent's words to the coach, and a reader of the ride that
 * does not know them must not meet them.
 */
export function withoutLiveCoach(workout: Workout): Workout {
  const strip = <T extends Step>(step: T): T => {
    if (step.type === "repeat") {
      return { ...step, steps: step.steps.map(strip) } as T;
    }
    if (step.must_say === undefined) return step;
    const { must_say: _gone, ...rest } = step;
    return rest as T;
  };
  const { live_coach: _live, coach_brief: _brief, ...rest } = workout;
  return { ...rest, steps: rest.steps.map(strip) };
}

/** Whether a workout carries anything for the live coach. */
export function hasLiveCoachFields(workout: Workout): boolean {
  if (workout.live_coach !== undefined || workout.coach_brief !== undefined) return true;
  return workout.steps.some((step) =>
    step.type === "repeat"
      ? step.steps.some((inner) => inner.must_say !== undefined)
      : step.must_say !== undefined,
  );
}
