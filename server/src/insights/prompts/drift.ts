import type { PromptDefinition } from "./index.ts";

/** The alert for a plan that is drifting: missed sessions, or load far from the plan (spec §6.6). Bump `version` when the words change. */
export const DRIFT_PROMPT: PromptDefinition = {
  kind: "drift",
  version: 1,
  limit: "at most 4 sentences, about 70 words",
  instructions: `Write a short heads-up about the plan.
- Say what has drifted: the sessions that were missed (missed.sessions), and the load ridden against the load planned (load.ridden, load.planned, load.differencePercent), whichever the facts' "reasons" name.
- Recommend what to do: for missed sessions or a load below the plan, pick the plan up with the next session or ask the coach to reshape the week; for a load above the plan, make the next day easy.
- Say that you have not changed the plan.`,
};
