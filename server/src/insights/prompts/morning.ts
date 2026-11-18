import type { PromptDefinition } from "./index.ts";

/** The morning note (spec §6.5): state, why, what to do with today's workout. Bump `version` when the words change. */
export const MORNING_PROMPT: PromptDefinition = {
  kind: "morning",
  version: 1,
  limit: "at most 4 sentences, about 70 words",
  instructions: `Write the morning note.
- Say how recovered the cyclist is today: the recovery state and the reason given (recovery.reason), in your own words.
- Then say what to do with today's planned workout, in words. If "adjustment" is "keep-hard-parts-short" say to keep the hard parts short and to stop early if it feels heavy; if it is "easy-or-off" say to make the workout an easy spin or take the day off. If the state is ready, say to train as planned.
- If nothing is planned today, say what kind of day it should be (easy, or off, or free).
- If recovery is not rated or its data is from yesterday, say so in one short clause and lean on the plan and on form.
- Mention form (today.form) only if it changes the advice.`,
};
