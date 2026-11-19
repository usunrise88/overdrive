import type { LiveCue, LiveSnapshot, LiveStreamEndReason } from "@overdrive/contracts";
import type { Logger } from "../log.ts";
import { buildCueFacts } from "./cue-facts.ts";
import type { CueMaker, CueRequest, CueResult } from "./cue-maker.ts";
import {
  type CueRecord,
  checkLimits,
  type LimitRule,
  type LimitState,
  type LiveLimits,
} from "./limits.ts";
import { messagesFor, type PromptContext } from "./prompt.ts";
import type { LiveCueStore } from "./store.ts";
import type { SessionPlan } from "./structure.ts";
import {
  type Candidate,
  detectCandidates,
  type PreviousAttempt,
  type SnapshotEntry,
  type TriggerSettings,
} from "./triggers.ts";

/**
 * One live session (spec §12.1, ADR 0038): the state of one ride's coach in memory, from the start the tablet made to its end. Every
 * snapshot is judged by the trigger rules (`triggers.ts`), what they find by the limits (`limits.ts`), and what the limits allow is made
 * into a cue (`cue-maker.ts`) and pushed to whoever is listening. One cue is made at a time; a snapshot never waits for one.
 *
 * Nothing here calls the tablet: a tablet that is gone means no snapshots, so no cues, and the session ends by itself after a quiet while
 * (`service.ts`). Nothing here logs a reading or a line: ids, trigger names, rule names and counts.
 */

/** How long a cue is worth playing after it is ready, in milliseconds. The deadline for making it is 5 s on top of this at most. */
export const CUE_LIFETIME_MS = 8000;
/** The snapshots kept: enough for the longest look back of the rules (a minute and a little). */
const HISTORY_MS = 130_000;
/** The cues kept to replay to a stream that came back. */
const REPLAY_KEPT = 8;

/** What goes to a listener of the cue stream. */
export type StreamEvent =
  | { kind: "cue"; id: number; data: string }
  | { kind: "end"; reason: LiveStreamEndReason }
  | { kind: "heartbeat" };

export interface Listener {
  send(event: StreamEvent): void;
}

export interface SessionOptions {
  id: string;
  rideId: string;
  workoutName: string;
  plan: SessionPlan;
  ftp: number | null;
  previous: PreviousAttempt | null;
  prompt: PromptContext;
  limits: LiveLimits;
  triggers: TriggerSettings;
  maker: CueMaker;
  store: LiveCueStore;
  /** The service's clock. */
  now: () => Date;
  log: Logger;
}

export interface LiveSession {
  readonly id: string;
  readonly rideId: string;
  /** Applies a snapshot; `stale` for one that is not newer than the last. */
  snapshot(snapshot: LiveSnapshot): "accepted" | "stale";
  /** Listens to the cues. `lastEventId` is the SSE id the listener has seen; the cues after it that are still worth playing are sent first. */
  listen(listener: Listener, lastEventId: number | null): () => void;
  /** Tells every listener, closes them and abandons a cue in the making. Idempotent. */
  end(reason: LiveStreamEndReason): void;
  readonly ended: boolean;
  /** How many cues were made. */
  readonly cueCount: number;
  /** The service's clock at the last snapshot (or the start), milliseconds. */
  readonly lastActivity: number;
  /** Writes a comment to every listener so that no proxy closes a quiet connection. */
  heartbeat(): void;
  /** Reports what the tablet said about a cue; false when this session made no such cue. */
  played(cueId: number, played: boolean, at: Date): Promise<boolean>;
  /** Resolves when no cue is being made and the log has been written. */
  idle(): Promise<void>;
}

interface Replay {
  id: number;
  data: string;
  expiresAt: number;
}

interface MadeCue {
  persisted: Promise<void>;
}

export function createSession(options: SessionOptions): LiveSession {
  const { id, rideId, plan, limits, maker, store, log } = options;
  const clock = (): number => options.now().getTime();

  const listeners = new Set<Listener>();
  const history: SnapshotEntry[] = [];
  const spent = new Set<string>();
  const cues: CueRecord[] = [];
  const said: string[] = [];
  const replay: Replay[] = [];
  const made = new Map<number, MadeCue>();
  const lastUnproductive = new Map<string, number>();
  let lastSeq = -1;
  let muted = false;
  let busy = false;
  let ended = false;
  let cueSeq = 0;
  let activity = clock();
  let latest: LiveSnapshot | null = null;
  let mismatchLogged = false;
  let working: AbortController | null = null;
  const pending = new Set<Promise<unknown>>();

  const broadcast = (event: StreamEvent): void => {
    for (const listener of [...listeners]) {
      try {
        listener.send(event);
      } catch {
        // A listener that cannot be written to is gone; its connection's close removes it.
        listeners.delete(listener);
      }
    }
  };

  function state(): LimitState {
    return { muted, busy, cues, lastUnproductive };
  }

  /** The first candidate the limits allow, in priority order; the rule that held back each of the others is logged at debug level. */
  function choose(
    candidates: readonly Candidate[],
    at: number,
    snap: LiveSnapshot,
  ): Candidate | null {
    for (const candidate of candidates) {
      const decision = checkLimits(
        candidate,
        state(),
        {
          now: at,
          timeLeftSeconds: snap.step.timeLeftSeconds,
          isRecovery: (index) => plan.steps[index]?.recovery === true,
        },
        limits,
      );
      if (decision.allowed) return candidate;
      log.debug(
        { sessionId: id, trigger: candidate.trigger, rule: decision.rule satisfies LimitRule },
        "a live cue was held back",
      );
    }
    return null;
  }

  function emit(
    candidate: Candidate,
    result: Extract<CueResult, { ok: true }>,
    triggeredAt: number,
    atSeconds: number,
  ): void {
    const seq = ++cueSeq;
    const createdAt = options.now();
    const expiresAt = new Date(createdAt.getTime() + CUE_LIFETIME_MS);
    const cue: LiveCue = {
      id: seq,
      text: result.text,
      trigger: candidate.trigger,
      audio: { format: result.format, base64: Buffer.from(result.audio).toString("base64") },
      createdAt: createdAt.toISOString(),
      expiresAt: expiresAt.toISOString(),
      ttlMs: CUE_LIFETIME_MS,
    };
    const data = JSON.stringify(cue);
    cues.push({ at: triggeredAt, trigger: candidate.trigger, stepIndex: candidate.stepIndex });
    said.push(result.text);
    replay.push({ id: seq, data, expiresAt: expiresAt.getTime() });
    while (replay.length > REPLAY_KEPT) replay.shift();
    broadcast({ kind: "cue", id: seq, data });
    // The log of the ride is written after the cue has gone: the rider does not wait for the database.
    const persisted = store
      .insert({
        rideId,
        sessionId: id,
        seq,
        trigger: candidate.trigger,
        source: candidate.trigger === "must-say" ? "must-say" : "model",
        text: result.text,
        stepIndex: candidate.stepIndex,
        atSeconds,
        createdAt,
      })
      .catch((error: unknown) => {
        // A database error's message may quote the row: only its code.
        const code = (error as { code?: unknown } | null)?.code;
        log.error(
          { sessionId: id, seq, ...(typeof code === "string" ? { code } : {}) },
          "a live cue could not be stored on its ride",
        );
      });
    made.set(seq, { persisted });
    pending.add(persisted);
    void persisted.finally(() => pending.delete(persisted));
  }

  function launch(candidate: Candidate, snap: LiveSnapshot, at: number): void {
    busy = true;
    const controller = new AbortController();
    working = controller;
    let request: CueRequest;
    if (candidate.trigger === "must-say") {
      request = { source: "must-say", text: candidate.text ?? "" };
    } else if (candidate.detail !== undefined) {
      const facts = buildCueFacts({
        trigger: candidate.detail,
        workoutName: options.workoutName,
        snapshot: snap,
        plan,
        ftp: options.ftp,
      });
      request = {
        source: "model",
        messages: messagesFor(options.prompt, facts, said),
        facts,
        said: [...said],
      };
    } else {
      busy = false;
      return;
    }
    const work = maker
      .make({ sessionId: id, trigger: candidate.trigger }, request, controller.signal)
      .then((result) => {
        if (working === controller) working = null;
        busy = false;
        if (ended || muted) return;
        if (result.ok) {
          if (candidate.key !== undefined) spent.add(candidate.key);
          emit(candidate, result, at, snap.elapsedSeconds);
          return;
        }
        if (result.reason === "aborted") return;
        // Nothing was said: a trigger that fires once is spent, and any trigger is not tried again at once.
        if (candidate.key !== undefined) spent.add(candidate.key);
        lastUnproductive.set(candidate.trigger, at);
      })
      .catch((error: unknown) => {
        busy = false;
        log.error(
          {
            sessionId: id,
            trigger: candidate.trigger,
            err: String((error as Error)?.name ?? "error"),
          },
          "a live cue failed",
        );
      });
    pending.add(work);
    void work.finally(() => pending.delete(work));
  }

  function evaluate(): void {
    const snap = latest;
    const current = history.at(-1);
    if (snap === null || current === undefined || ended) return;
    if (plan.steps.length !== snap.stepCount) {
      // The workout the tablet plays is not the one the service has: the lines cannot be put on the right steps, so nothing is said.
      if (!mismatchLogged) {
        mismatchLogged = true;
        log.warn(
          { sessionId: id },
          "the live session's workout does not match the tablet's: the coach stays silent",
        );
      }
      return;
    }
    const candidates = detectCandidates({
      history,
      plan,
      previous: options.previous,
      spent,
      settings: options.triggers,
      silentFinalSeconds: limits.silentFinalSeconds,
    });
    if (candidates.length === 0) return;
    const chosen = choose(candidates, current.at, snap);
    if (chosen !== null) launch(chosen, snap, current.at);
  }

  return {
    id,
    rideId,
    get ended() {
      return ended;
    },
    get cueCount() {
      return cueSeq;
    },
    get lastActivity() {
      return activity;
    },

    snapshot(snapshot) {
      if (ended || snapshot.seq <= lastSeq) return "stale";
      lastSeq = snapshot.seq;
      const at = clock();
      activity = at;
      latest = snapshot;
      history.push({ at, snap: snapshot });
      while (history.length > 1 && at - (history[0] as SnapshotEntry).at > HISTORY_MS)
        history.shift();
      if (snapshot.muted && !muted) {
        // The rider muted the coach for the rest of the ride: whatever is being made is not wanted, and nothing is asked again.
        muted = true;
        working?.abort();
        log.info({ sessionId: id }, "the rider muted the live coach");
      }
      evaluate();
      return "accepted";
    },

    listen(listener, lastEventId) {
      listeners.add(listener);
      const now = clock();
      for (const item of replay) {
        if (item.id > (lastEventId ?? 0) && item.expiresAt > now) {
          listener.send({ kind: "cue", id: item.id, data: item.data });
        }
      }
      return () => {
        listeners.delete(listener);
      };
    },

    end(reason) {
      if (ended) return;
      ended = true;
      working?.abort();
      broadcast({ kind: "end", reason });
      listeners.clear();
    },

    heartbeat() {
      broadcast({ kind: "heartbeat" });
    },

    async played(cueId, played, at) {
      const cue = made.get(cueId);
      if (cue === undefined) return false;
      await cue.persisted;
      return store.markPlayed(id, cueId, played, at);
    },

    async idle() {
      while (pending.size > 0) await Promise.allSettled([...pending]);
    },
  };
}
