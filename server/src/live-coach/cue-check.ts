import { liveTextLength } from "@overdrive/contracts";
import { countViolations, type TextValidator, type ViolationKind } from "../insights/validator.ts";
import { plainText } from "../insights/writer.ts";
import type { CueFacts } from "./cue-facts.ts";

/**
 * The check of a cue the model wrote (spec §12.1, ADR 0038): the rule is enforced by code and not trusted to the model, as for the
 * written notes (ADR 0025, whose validator this reuses): the cue is short enough, says no number that is not in the facts it was given
 * (the model never calculates), uses none of the words that are not allowed, has no link, and is not a repeat of a line already said in
 * this ride. The model may also answer that nothing is worth saying, with the sentinel {@link NO_CUE}.
 */

/** What the model answers when nothing is worth saying now. */
export const NO_CUE = "NO_CUE";

export type CueRejection =
  /** The model had nothing worth saying (the sentinel): not a failure. */
  "silence" | "empty" | "too-long" | "repeat" | "violations";

export type CueCheck =
  | { ok: true; text: string }
  | {
      ok: false;
      reason: CueRejection;
      /** How many violations of each kind (only for `violations`): counts, never the offending words or numbers. */
      violations?: Record<ViolationKind, number>;
    };

/** The sentinel opens the answer or closes it, in any case and with any punctuation: "NO_CUE", "no cue.", "Nothing to add: NO_CUE". */
const SENTINEL = /^[^\p{L}\p{N}_]*no[_\s-]?cue\b|\bno[_\s-]?cue[^\p{L}\p{N}]*$/iu;
/** Quotes a model puts around a line it was asked to give plain. */
const WRAPPING_QUOTES = /^["'“”‘’«»`]+|["'“”‘’«»`]+$/g;
/** A cue is spoken: "pedal stroke" is coaching, not a medical word, and must not meet the deny list's "stroke". */
const PEDAL_STROKE = /\bpedal(?:l?ing)?\s+strokes?\b/gi;

/** The cue as it is spoken: one line, no markdown, no quotes around it. */
export function cleanCue(raw: string): string {
  const oneLine = plainText(raw)
    .replace(/\s*\n+\s*/g, " ")
    .replace(/\s{2,}/g, " ")
    .trim();
  return oneLine.replace(WRAPPING_QUOTES, "").trim();
}

/** The words of a line, lower case, without punctuation: what two lines are compared on. */
function wordsOf(text: string): string[] {
  return text
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((word) => word !== "");
}

/** Whether `text` says what a line already said: the same words, or nearly the same set of them. */
export function isRepeat(text: string, said: readonly string[]): boolean {
  const words = wordsOf(text);
  if (words.length === 0) return false;
  const joined = words.join(" ");
  const set = new Set(words);
  for (const line of said) {
    const other = wordsOf(line);
    if (other.join(" ") === joined) return true;
    if (words.length < 3 || other.length < 3) continue;
    const otherSet = new Set(other);
    let shared = 0;
    for (const word of set) if (otherSet.has(word)) shared++;
    const union = set.size + otherSet.size - shared;
    if (union > 0 && shared / union >= 0.8) return true;
  }
  return false;
}

export interface CheckOptions {
  facts: CueFacts;
  /** The lines already said in this ride, the agent's own included. */
  said: readonly string[];
  /** The longest cue, characters. */
  maxChars: number;
  validator: TextValidator;
}

/** Checks what the model answered. The sentinel is `silence`; a cue that passes is the cleaned text. */
export function checkCue(raw: string, options: CheckOptions): CueCheck {
  if (SENTINEL.test(raw)) return { ok: false, reason: "silence" };
  const text = cleanCue(raw);
  if (text === "") return { ok: false, reason: "empty" };
  if (liveTextLength(text) > options.maxChars) return { ok: false, reason: "too-long" };
  const result = options.validator.check(text.replace(PEDAL_STROKE, "pedalling"), options.facts);
  if (!result.ok)
    return { ok: false, reason: "violations", violations: countViolations(result.violations) };
  if (isRepeat(text, options.said)) return { ok: false, reason: "repeat" };
  return { ok: true, text };
}
