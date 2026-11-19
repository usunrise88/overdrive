import type { LiveCueTrigger } from "@overdrive/contracts";
import {
  DEFAULT_LIVE_COACH_CUE_MAX_CHARS,
  DEFAULT_LIVE_COACH_MAX_CUES_PER_HOUR,
  DEFAULT_LIVE_COACH_MAX_PER_TRIGGER_PER_STEP,
  DEFAULT_LIVE_COACH_MIN_GAP_SECONDS,
  DEFAULT_LIVE_COACH_RECOVERY_MAX_CUES,
  DEFAULT_LIVE_COACH_SILENT_FINAL_SECONDS,
  DEFAULT_LIVE_COACH_TRIGGER_COOLDOWN_SECONDS,
  type LiveCoachSettings,
} from "../config-live-coach.ts";
import type { Candidate } from "./triggers.ts";

/**
 * The limits on talking (spec §12.1, ADR 0038), enforced by the server and settings (`LIVE_COACH_*`): the model is asked to write a
 * line only when every one of them allows a cue, so cost and chatter are bounded by code and not by the model's good manners. Pure: the
 * state and the clock are arguments.
 *
 * The order in which they are checked, which is also the reason reported for a cue that is held back:
 *
 *  1. `muted`            the rider muted the coach: nothing is said, the model is not asked
 *  2. `busy`             a cue is being made: one at a time
 *  3. `final-seconds`    the final seconds of a step are silent (the countdown stays clear)
 *  4. `recovery-step`    a recovery step gets at most one cue
 *  5. `trigger-per-step` the same trigger speaks at most twice in a step
 *  6. `trigger-cooldown` ... and not within three minutes of its last cue
 *  7. `gap`              the least time between any two cues
 *  8. `per-hour`         the most cues in a rolling hour
 *  9. `attempt-cooldown` a trigger whose last try produced no cue (the model had nothing to say, or its line was refused) is not tried
 *                        again for a minute: the model is not asked every five seconds
 *
 * After the model has answered the line itself is checked (`cue-check.ts`: length, numbers, repeats), and a cue that is not ready in
 * time is dropped (`cue-maker.ts`).
 *
 * `must-say` lines (the agent's own words) skip 4 to 6, 8 and 9, which exist to bound a model's chatter, and use a short fixed gap
 * ({@link MUST_SAY_MIN_GAP_SECONDS}) in place of 7 so that two voices never overlap; they are subject to 1 to 3.
 */

export type LimitRule =
  | "muted"
  | "busy"
  | "final-seconds"
  | "recovery-step"
  | "trigger-per-step"
  | "trigger-cooldown"
  | "gap"
  | "per-hour"
  | "attempt-cooldown";

/** The order in which the rules are checked. */
export const LIMIT_ORDER: readonly LimitRule[] = [
  "muted",
  "busy",
  "final-seconds",
  "recovery-step",
  "trigger-per-step",
  "trigger-cooldown",
  "gap",
  "per-hour",
  "attempt-cooldown",
];

export interface LiveLimits {
  cueMaxChars: number;
  silentFinalSeconds: number;
  recoveryMaxCues: number;
  maxPerTriggerPerStep: number;
  triggerCooldownSeconds: number;
  minGapSeconds: number;
  maxCuesPerHour: number;
}

export const DEFAULT_LIVE_LIMITS: LiveLimits = {
  cueMaxChars: DEFAULT_LIVE_COACH_CUE_MAX_CHARS,
  silentFinalSeconds: DEFAULT_LIVE_COACH_SILENT_FINAL_SECONDS,
  recoveryMaxCues: DEFAULT_LIVE_COACH_RECOVERY_MAX_CUES,
  maxPerTriggerPerStep: DEFAULT_LIVE_COACH_MAX_PER_TRIGGER_PER_STEP,
  triggerCooldownSeconds: DEFAULT_LIVE_COACH_TRIGGER_COOLDOWN_SECONDS,
  minGapSeconds: DEFAULT_LIVE_COACH_MIN_GAP_SECONDS,
  maxCuesPerHour: DEFAULT_LIVE_COACH_MAX_CUES_PER_HOUR,
};

/** The limits as the configuration gives them: a setting that is not given is the spec's default. */
export function limitsFrom(config: LiveCoachSettings): LiveLimits {
  return {
    cueMaxChars: config.LIVE_COACH_CUE_MAX_CHARS ?? DEFAULT_LIVE_LIMITS.cueMaxChars,
    silentFinalSeconds:
      config.LIVE_COACH_SILENT_FINAL_SECONDS ?? DEFAULT_LIVE_LIMITS.silentFinalSeconds,
    recoveryMaxCues: config.LIVE_COACH_RECOVERY_MAX_CUES ?? DEFAULT_LIVE_LIMITS.recoveryMaxCues,
    maxPerTriggerPerStep:
      config.LIVE_COACH_MAX_PER_TRIGGER_PER_STEP ?? DEFAULT_LIVE_LIMITS.maxPerTriggerPerStep,
    triggerCooldownSeconds:
      config.LIVE_COACH_TRIGGER_COOLDOWN_SECONDS ?? DEFAULT_LIVE_LIMITS.triggerCooldownSeconds,
    minGapSeconds: config.LIVE_COACH_MIN_GAP_SECONDS ?? DEFAULT_LIVE_LIMITS.minGapSeconds,
    maxCuesPerHour: config.LIVE_COACH_MAX_CUES_PER_HOUR ?? DEFAULT_LIVE_LIMITS.maxCuesPerHour,
  };
}

/** Two cues are never closer than this, the agent's own lines included: an 80-character cue is about five seconds of speech. */
export const MUST_SAY_MIN_GAP_SECONDS = 8;
/** A trigger that produced no cue is not tried again for this long. */
export const ATTEMPT_COOLDOWN_SECONDS = 60;
const HOUR_MS = 3_600_000;

/** A cue that was made. */
export interface CueRecord {
  /** The service's clock when it was triggered, milliseconds. */
  at: number;
  trigger: LiveCueTrigger;
  /** The flattened step it was about. */
  stepIndex: number;
}

export interface LimitState {
  /** The rider muted the coach (sticky for the session). */
  muted: boolean;
  /** A cue is being made. */
  busy: boolean;
  /** Every cue made in this session, oldest first. */
  cues: readonly CueRecord[];
  /** When a trigger's last try produced no cue, by trigger name, milliseconds. */
  lastUnproductive: ReadonlyMap<string, number>;
}

export interface LimitContext {
  /** The service's clock now, milliseconds. */
  now: number;
  /** The current step's time left in seconds, at the last snapshot. */
  timeLeftSeconds: number;
  /** Whether the step a candidate is about is a recovery step. */
  isRecovery: (stepIndex: number) => boolean;
}

export type LimitDecision = { allowed: true } | { allowed: false; rule: LimitRule };

const blocked = (rule: LimitRule): LimitDecision => ({ allowed: false, rule });

/** The first rule, in order, that holds a candidate back; or that it is allowed. */
export function checkLimits(
  candidate: Pick<Candidate, "trigger" | "stepIndex" | "aboutNextStep">,
  state: LimitState,
  context: LimitContext,
  limits: LiveLimits,
): LimitDecision {
  const must = candidate.trigger === "must-say";
  const { now } = context;

  if (state.muted) return blocked("muted");
  if (state.busy) return blocked("busy");

  // The final seconds of a step are silent. A cue about the step that is about to begin is not a cue about this one: it is triggered
  // early, in the last seconds of the step before, on purpose.
  if (!candidate.aboutNextStep && context.timeLeftSeconds <= limits.silentFinalSeconds) {
    return blocked("final-seconds");
  }

  if (!must) {
    // A recovery step gets at most this many cues, of any trigger.
    if (
      context.isRecovery(candidate.stepIndex) &&
      state.cues.filter((c) => c.stepIndex === candidate.stepIndex).length >= limits.recoveryMaxCues
    ) {
      return blocked("recovery-step");
    }
    // The same trigger at most this many times in a step, and not again within the cooldown.
    const same = state.cues.filter((c) => c.trigger === candidate.trigger);
    if (
      same.filter((c) => c.stepIndex === candidate.stepIndex).length >= limits.maxPerTriggerPerStep
    ) {
      return blocked("trigger-per-step");
    }
    const lastSame = same.at(-1);
    if (lastSame !== undefined && now - lastSame.at < limits.triggerCooldownSeconds * 1000) {
      return blocked("trigger-cooldown");
    }
  }

  // The least time between two cues. The agent's own lines use a short floor, so that nothing overlaps.
  const last = state.cues.at(-1);
  const gap = must ? MUST_SAY_MIN_GAP_SECONDS : limits.minGapSeconds;
  if (last !== undefined && now - last.at < gap * 1000) return blocked("gap");

  if (!must) {
    if (state.cues.filter((c) => now - c.at < HOUR_MS).length >= limits.maxCuesPerHour) {
      return blocked("per-hour");
    }
    const tried = state.lastUnproductive.get(candidate.trigger);
    if (tried !== undefined && now - tried < ATTEMPT_COOLDOWN_SECONDS * 1000) {
      return blocked("attempt-cooldown");
    }
  }

  return { allowed: true };
}
