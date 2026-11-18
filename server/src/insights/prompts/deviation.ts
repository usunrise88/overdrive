import type { PromptDefinition } from "./index.ts";

/** The alert for a metric outside the cyclist's own baseline for several days (spec §6.6). Bump `version` when the words change. */
export const DEVIATION_PROMPT: PromptDefinition = {
  kind: "deviation",
  version: 1,
  limit: "at most 4 sentences, about 70 words",
  instructions: `Write a short heads-up.
- Say which signal has been outside the cyclist's usual range and for how many mornings in a row (daysOutsideInARow), with the reading and the usual range from the facts.
- Say what that usually means for training, using the "reading" and "trainingHint" sentences of the facts in your own words. Do not say why it happened and do not name a cause.
- Say what to do in the next days, in training terms. If a workout is planned today, say how to treat it without changing the plan.`,
};
