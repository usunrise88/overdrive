import type { PromptDefinition } from "./index.ts";

/** The weekly review (spec §6.5). Bump `version` when the words change. */
export const WEEK_PROMPT: PromptDefinition = {
  kind: "week",
  version: 1,
  limit: "at most 6 sentences, about 110 words",
  instructions: `Write the weekly review.
- Sessions done against planned, and any that were missed.
- Load against the week before, and what happened to fitness and form.
- How recovery went (the mornings rated ready, caution and rest) and any wellness figure outside its usual range for several days.
- Any new best.
- End with one recommendation for the coming week, using what is planned for the next 7 days (nextWeek).`,
};
