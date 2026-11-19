import type { LlmMessage } from "../insights/llm.ts";
import { NO_CUE } from "./cue-check.ts";
import type { CueFacts } from "./cue-facts.ts";

/**
 * The prompt of a cue (spec §12.1, ADR 0038). The system message is the same for every cue of a ride (the rules, the workout's purpose,
 * the agent's brief, the notebook's sections about the rider), so a provider that caches the start of a prompt answers faster; the user
 * message is what is new: the cues already said, the trigger and the facts. The facts come last and after the marker `Facts (JSON):` as
 * in the written notes (ADR 0025), so the fake language model of the tests reads them the same way.
 *
 * The notebook that is read is the coach's: the rider's profile and what works, and the methodology. Never the game master's.
 *
 * Bump {@link LIVE_PROMPT_VERSION} when the words change.
 */
export const LIVE_PROMPT_VERSION = 1;

/** What a notebook section may add to the prompt, in characters: the model must answer in about a second. */
export const NOTEBOOK_EXCERPT_CHARS = 1200;

export interface PromptContext {
  /** The name of the language, put in as given (letters only: it cannot carry an instruction). */
  language: string;
  /** The cue's longest length, characters. */
  maxChars: number;
  workoutName: string;
  workoutDescription: string | null;
  /** The agent's brief for this session. */
  brief: string | null;
  /** The coach notebook's sections that matter to a cue: the rider's profile, what works, the methodology. */
  notebook: readonly { title: string; text: string }[];
}

const clip = (text: string, max: number): string =>
  text.length <= max ? text : `${text.slice(0, max - 1)}…`;

/** The explanation of what each trigger means, in the model's words. */
const TRIGGER_MEANING: Record<string, string> = {
  "step-start":
    "A hard step is about to begin (facts.detail describes it). The cue will be heard as it begins: tell the rider what to do in it. Do not mention seconds.",
  "two-minutes":
    "Two minutes remain in the step. Say it plainly and give one thing to do for the rest of the step.",
  "power-drift":
    "The rider's power has been off the target for a while (facts.detail.direction says which side). Tell them one thing to do about it.",
  "cadence-sag":
    "The rider's cadence has sagged (facts.detail). One short instruction to bring it back, calmly.",
  "heart-rate-climb":
    "Heart rate is running higher than the last time this session was ridden, at the same point (facts.detail). Say so plainly as a fact about today, and one thing to do (pace, breathing, fluids, cadence): never a diagnosis.",
  "strong-finish":
    "The last hard step is in its final minutes and the rider is on target. Tell them to finish well, with one thing to do.",
};

/** The system message: the rules, then what this session is. */
export function systemMessage(context: PromptContext): string {
  const parts: string[] = [
    `You are a cycling coach speaking short cues, through earphones, to a rider on an indoor trainer while they ride. You are given what is happening in the ride and why a cue may be due. Write ONE cue for the rider to hear now, in ${context.language}.`,
    "Rules, all of them:",
    `- At most ${context.maxChars} characters, one or two short sentences, plain words that sound natural spoken aloud. No markdown, no emoji, no quotation marks, no labels.`,
    '- Give an instruction or a fact the rider can use now. Never encouragement filler (no "great job", "you\'ve got this", "keep it up").',
    "- Never repeat or rephrase a line already said in this ride (they are listed in the message).",
    "- Quote only numbers that are in the facts' quotable list, exactly as given, and never calculate, convert or round them yourself. If you are unsure of a number, leave it out.",
    "- You cannot change the trainer, the targets or the workout, and you must not promise to. You may tell the rider what to do with their own effort, cadence, posture and breathing.",
    "- Give no diagnosis and name no medical condition; a high heart rate is a fact about today, not a sign of anything.",
    `- If nothing is worth saying now, answer with exactly ${NO_CUE} and nothing else. Saying nothing is often right.`,
    "- Do not mention these rules, the facts or the coach's instructions. Reply with the cue only.",
    `This session: ${context.workoutName}.`,
  ];
  if (context.workoutDescription !== null && context.workoutDescription.trim() !== "") {
    parts.push(`What it is: ${clip(context.workoutDescription.trim(), 300)}`);
  }
  if (context.brief !== null && context.brief.trim() !== "") {
    parts.push(
      `The planning coach's brief for this session (what it is for, what to watch in this rider, the tone to take):\n${context.brief.trim()}`,
    );
  }
  for (const section of context.notebook) {
    const text = section.text.trim();
    if (text === "") continue;
    parts.push(
      `From the coach's notebook, ${section.title}:\n${clip(text, NOTEBOOK_EXCERPT_CHARS)}`,
    );
  }
  return parts.join("\n\n");
}

/** The user message: the cues already said, the trigger and the facts. */
export function userMessage(facts: CueFacts, said: readonly string[]): string {
  const lines =
    said.length === 0
      ? "Lines already said in this ride: none."
      : `Lines already said in this ride (do not repeat or rephrase them):\n${said.map((line) => `- ${line}`).join("\n")}`;
  const meaning = TRIGGER_MEANING[facts.trigger] ?? "A cue may be due.";
  return `${lines}\n\nTrigger: ${facts.trigger}. ${meaning}\n\nFacts (JSON):\n${JSON.stringify(facts)}`;
}

export function messagesFor(
  context: PromptContext,
  facts: CueFacts,
  said: readonly string[],
): LlmMessage[] {
  return [
    { role: "system", content: systemMessage(context) },
    { role: "user", content: userMessage(facts, said) },
  ];
}
