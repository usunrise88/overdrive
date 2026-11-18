import type { InsightKind } from "@overdrive/contracts";
import type { Facts } from "../facts.ts";
import { DEVIATION_PROMPT } from "./deviation.ts";
import { DRIFT_PROMPT } from "./drift.ts";
import { MORNING_PROMPT } from "./morning.ts";
import { RIDE_PROMPT } from "./ride.ts";
import { SYSTEM_VERSION, systemPrompt } from "./system.ts";
import { WEEK_PROMPT } from "./week.ts";

/** One kind's prompt: what to write, how long, and the version that is stored with every note it wrote. */
export interface PromptDefinition {
  kind: InsightKind;
  version: number;
  /** How long the note may be, in words the model reads ("at most 4 sentences, about 70 words"). */
  limit: string;
  /** What the note says, after the rules every note is written under (`system.ts`). */
  instructions: string;
}

export const PROMPTS: Readonly<Record<InsightKind, PromptDefinition>> = {
  morning: MORNING_PROMPT,
  ride: RIDE_PROMPT,
  week: WEEK_PROMPT,
  deviation: DEVIATION_PROMPT,
  drift: DRIFT_PROMPT,
};

/** What is stored with a note a model wrote: the kind's prompt version and the version of the shared rules. */
export function promptVersion(kind: InsightKind): string {
  return `${kind}.v${PROMPTS[kind].version}+rules.v${SYSTEM_VERSION}`;
}

/** The system message: the shared rules for this kind and language. */
export function systemMessage(kind: InsightKind, language: string): string {
  return systemPrompt(language, PROMPTS[kind].limit);
}

/** The user message: the facts as compact JSON, and what to write. */
export function userMessage(kind: InsightKind, facts: Facts): string {
  return `${PROMPTS[kind].instructions}\n\nFacts (JSON):\n${JSON.stringify(facts)}`;
}

/** What the model is told when its text was rejected: the violations, never the rider's data beyond the offending tokens. */
export function retryMessage(violations: string): string {
  return `Your note was rejected: ${violations}. Write the note again from the same facts. Quote only numbers from the "quotable" list or from the facts' own sentences, use none of the words that are not allowed, and follow every rule. Reply with the note only.`;
}
