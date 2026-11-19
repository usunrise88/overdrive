import type { LiveAudioFormat } from "@overdrive/contracts";
import type { LlmClient, LlmMessage } from "../insights/llm.ts";
import type { TextValidator } from "../insights/validator.ts";
import type { Logger } from "../log.ts";
import type { Timers } from "../timers.ts";
import { checkCue } from "./cue-check.ts";
import type { CueFacts } from "./cue-facts.ts";
import type { TtsClient } from "./tts.ts";

/**
 * Makes one cue (spec §12.1, ADR 0038): the model writes the line (or the agent's own words are taken as they are), the check enforces the
 * rules the model may not be trusted with, the rider's speech service speaks it, and a cue that is not ready within the deadline
 * (default 5 s from the trigger) is dropped: a late cue about a moment that has passed is worse than none. The aim is under 3 s from
 * trigger to sound; the timings of each stage are logged.
 *
 * LOGGING: the trigger's name, the outcome and the milliseconds each stage took, the failure's kind and status code, the number of
 * violations of each kind. Never the text, never a fact, never a reading, never a key or an address.
 */

export type DropReason =
  /** The model said nothing was worth saying. */
  | "silence"
  /** The model's line broke a rule (too long, a number it was not given, a repeat, a word that is not allowed). */
  | "rejected"
  | "llm-failed"
  | "tts-failed"
  /** Not ready in time. */
  | "deadline"
  /** The session ended or the rider muted the coach while the cue was being made. */
  | "aborted";

export interface Timings {
  /** null when the stage did not run (an agent's own line has no model). */
  llmMs: number | null;
  ttsMs: number | null;
  totalMs: number;
}

export type CueResult =
  | { ok: true; text: string; format: LiveAudioFormat; audio: Uint8Array; timings: Timings }
  | { ok: false; reason: DropReason; timings: Timings };

export type CueRequest =
  | {
      source: "model";
      messages: readonly LlmMessage[];
      facts: CueFacts;
      /** The lines already said in this ride. */
      said: readonly string[];
    }
  | { source: "must-say"; text: string };

export interface CueMakerOptions {
  llm: LlmClient;
  tts: TtsClient;
  validator: TextValidator;
  timers: Timers;
  /** A clock in milliseconds for the timings: the service's `now`. */
  now: () => number;
  log: Logger;
  /** Not ready within this many milliseconds of the trigger: dropped. */
  deadlineMs: number;
  /** The longest cue the model may write, characters. */
  maxChars: number;
}

export interface CueMaker {
  /** Never throws. `signal` aborts the work (the session ended, the rider muted the coach). */
  make(
    context: { sessionId: string; trigger: string },
    request: CueRequest,
    signal: AbortSignal,
  ): Promise<CueResult>;
}

/** What the work found before the deadline and the log have their say. */
type Raw =
  | { ok: true; text: string; format: LiveAudioFormat; audio: Uint8Array }
  | { ok: false; reason: DropReason };

export function createCueMaker(options: CueMakerOptions): CueMaker {
  const { llm, tts, validator, timers, now, log } = options;

  return {
    async make(context, request, signal) {
      const startedAt = now();
      const work = new AbortController();
      const stop = (): void => work.abort();
      if (signal.aborted) work.abort();
      else signal.addEventListener("abort", stop, { once: true });
      const timings: Timings = { llmMs: null, ttsMs: null, totalMs: 0 };
      /** What the log line says besides the timings: kinds and counts. */
      let detail: Record<string, unknown> = {};

      const run = async (): Promise<Raw> => {
        let text: string;
        if (request.source === "model") {
          const asked = now();
          const answer = await llm.complete(request.messages, { signal: work.signal });
          timings.llmMs = Math.max(0, now() - asked);
          if (!answer.ok) {
            detail = { failure: answer.kind, status: answer.status };
            return { ok: false, reason: "llm-failed" };
          }
          const checked = checkCue(answer.text, {
            facts: request.facts,
            said: request.said,
            maxChars: options.maxChars,
            validator,
          });
          if (!checked.ok) {
            detail = {
              rejection: checked.reason,
              ...(checked.violations === undefined ? {} : { violations: checked.violations }),
            };
            return { ok: false, reason: checked.reason === "silence" ? "silence" : "rejected" };
          }
          text = checked.text;
        } else {
          text = request.text;
        }
        const spoken = now();
        const speech = await tts.synthesize(text, { signal: work.signal });
        timings.ttsMs = Math.max(0, now() - spoken);
        if (!speech.ok) {
          detail = { failure: speech.kind, status: speech.status };
          return { ok: false, reason: "tts-failed" };
        }
        return { ok: true, text, format: speech.format, audio: speech.audio };
      };

      // The deadline is a timer from the injected timers, so a test moves it by hand.
      let cancelDeadline: () => void = () => {};
      const deadline = new Promise<"deadline">((resolve) => {
        cancelDeadline = timers.after(options.deadlineMs, () => resolve("deadline"));
      });
      const settled: Promise<Raw> = run().catch((): Raw => ({ ok: false, reason: "aborted" }));
      let raw: Raw;
      try {
        const winner = await Promise.race([settled, deadline]);
        if (winner === "deadline") {
          // The work still running ends on its own, aborted; whatever it finds is not used.
          work.abort();
          raw = { ok: false, reason: "deadline" };
        } else {
          raw = winner;
        }
      } finally {
        cancelDeadline();
        signal.removeEventListener("abort", stop);
      }
      // The session ended or the rider muted the coach while it was made: the cue is not wanted any more.
      if (signal.aborted) raw = { ok: false, reason: "aborted" };

      timings.totalMs = Math.max(0, now() - startedAt);
      const outcome = raw.ok ? "made" : raw.reason;
      log.info(
        { ...context, outcome, ...timings, ...detail },
        raw.ok ? "a live cue was made" : "a live cue was not made",
      );
      return raw.ok ? { ...raw, timings } : { ...raw, timings };
    },
  };
}
