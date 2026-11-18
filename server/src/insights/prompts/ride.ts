import type { PromptDefinition } from "./index.ts";

/** The note after a ride (spec §6.5, §12.3): how the session went against the plan. Bump `version` when the words change. */
export const RIDE_PROMPT: PromptDefinition = {
  kind: "ride",
  version: 1,
  limit: "at most 5 sentences, about 90 words",
  instructions: `Write the note after the ride.
- Say how the ride went against its plan: how much of the planned length was ridden, how close the steps were to their target power (plan.stepsOnTarget, plan.deltaPercent), and the load against the planned load.
- Mention a new best (efforts with newBest true), the aerobic drift, or a deep dip of W' (wPrime.low true) only if the facts have it.
- For a free ride (plan is null) describe the ride and its load and say there was no plan to compare with.
- End with one recommendation for the next session.`,
};
