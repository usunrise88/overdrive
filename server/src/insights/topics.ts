import type { RideFacts } from "./facts.ts";

/**
 * Discussion topics (spec §12.3, ADR 0025): the post-ride note may hand one topic to the coach agent, but only for a real reason.
 * The reasons are the rows of this table, evaluated in order, first match wins; a topic is one sentence on what stood out and a
 * question the rider can paste to open the chat. Both are written by code from the ride's facts, so every number in them was
 * computed by the service and no model is involved.
 *
 * The table is explicit and meant to grow. A row needs a signal; the signals Phase 3 has are the ride's compliance with its plan,
 * its power records, its load against the plan, and repeated plan drift. The rows for signals that come in later stages are
 * listed, named and unavailable, so the day a stage brings its signal, the row is switched on here and nowhere else.
 *
 * Limits (`store.ts`, `service.ts`): a ride has at most one topic, at most two are written in any 7 days, an open topic closes when
 * the coach marks it discussed or after 14 days.
 */

/** The facts of the ride, and what the service knows besides: add a field here when a new signal needs one. */
export interface TopicContext {
  ride: RideFacts;
  /** How many plan-drift alerts were written in the last 28 days, the current one included. */
  driftAlertsLast28Days: number;
  settings: TopicSettings;
}

export interface TopicSettings {
  /** A ride that ridden less than this share of the planned length is cut short. */
  cutShortPercent: number;
  /** Planned workouts shorter than this are not judged on length. */
  cutShortMinPlannedMinutes: number;
  /** The durations whose records count, in seconds. */
  recordDurations: readonly number[];
  /** A record needs this many earlier rides to beat... */
  recordMinPriorRides: number;
  /** ...and to beat the best by this many percent. */
  recordMinImprovementPercent: number;
  /** Load this far above or below the plan (percent) is "far from the plan"; the alerts' thresholds. */
  loadAbovePercent: number;
  loadBelowPercent: number;
  /** A planned load below this is too small to judge a ride against. */
  minPlannedLoad: number;
  /** Plan-drift alerts in 28 days that make drift "repeated". */
  repeatedDriftAlerts: number;
}

export const DEFAULT_TOPIC_SETTINGS: TopicSettings = {
  cutShortPercent: 75,
  cutShortMinPlannedMinutes: 10,
  recordDurations: [300, 1200, 3600],
  recordMinPriorRides: 3,
  recordMinImprovementPercent: 1,
  loadAbovePercent: 30,
  loadBelowPercent: 40,
  minPlannedLoad: 30,
  repeatedDriftAlerts: 2,
};

/** At most this many topics in any 7 days; a topic stays open for 14 days. */
export const TOPICS_PER_WEEK = 2;
export const TOPIC_WINDOW_MS = 7 * 24 * 3_600_000;
export const TOPIC_OPEN_DAYS = 14;
export const TOPIC_OPEN_MS = TOPIC_OPEN_DAYS * 24 * 3_600_000;

export interface TopicCandidate {
  text: string;
  openingQuestion: string;
}

export type TopicRule =
  | { id: string; available: true; evaluate(context: TopicContext): TopicCandidate | null }
  | { id: string; available: false; needs: string };

const MAX_TEXT = 300;
const MAX_QUESTION = 400;
const clip = (text: string, max: number): string =>
  text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;

const candidate = (text: string, question: string): TopicCandidate => ({
  text: clip(text, MAX_TEXT),
  openingQuestion: clip(question, MAX_QUESTION),
});

const cutShort: TopicRule = {
  id: "cut-short",
  available: true,
  evaluate({ ride, settings }) {
    const plan = ride.plan;
    if (
      plan === null ||
      plan.plannedMinutes === null ||
      plan.riddenMinutes === null ||
      plan.completionPercent === null ||
      plan.plannedMinutes < settings.cutShortMinPlannedMinutes ||
      plan.completionPercent >= settings.cutShortPercent
    ) {
      return null;
    }
    return candidate(
      `The ride stopped at ${plan.riddenMinutes} of the planned ${plan.plannedMinutes} minutes (${plan.completionPercent}%).`,
      `I stopped ${plan.workoutName} after ${plan.riddenMinutes} of ${plan.plannedMinutes} minutes. Can we look at why, and at what to change for the rest of the week?`,
    );
  },
};

const powerRecord: TopicRule = {
  id: "power-record",
  available: true,
  evaluate({ ride, settings }) {
    // The longest duration with a record is the most telling one.
    const records = ride.efforts
      .filter(
        (e) =>
          e.newBest &&
          settings.recordDurations.includes(e.durationSec) &&
          e.priorRides >= settings.recordMinPriorRides &&
          e.previousBest !== null &&
          e.improvementWatts !== null &&
          (e.improvementWatts / e.previousBest) * 100 >= settings.recordMinImprovementPercent,
      )
      .sort((a, b) => b.durationSec - a.durationSec);
    const best = records[0];
    if (best === undefined || best.previousBest === null || best.improvementWatts === null)
      return null;
    return candidate(
      `A new best for ${best.duration}: ${best.power} W, up ${best.improvementWatts} W on ${best.previousBest} W.`,
      `I rode ${best.power} W for ${best.duration}, my best so far (${best.previousBest} W before). Does that change my FTP or my plan?`,
    );
  },
};

const loadOffPlan: TopicRule = {
  id: "load-off-plan",
  available: true,
  evaluate({ ride, settings }) {
    const plan = ride.plan;
    if (
      plan === null ||
      plan.plannedLoad === null ||
      plan.plannedLoad < settings.minPlannedLoad ||
      plan.loadVsPlanPercent === null ||
      ride.ride.load === null
    ) {
      return null;
    }
    const above = plan.loadVsPlanPercent >= settings.loadAbovePercent;
    const below = plan.loadVsPlanPercent <= -settings.loadBelowPercent;
    if (!above && !below) return null;
    const way = above ? "above" : "below";
    return candidate(
      `The ride's load was ${ride.ride.load} against ${plan.plannedLoad} planned, ${Math.abs(plan.loadVsPlanPercent)}% ${way} the plan.`,
      `My ${plan.workoutName} came to a load of ${ride.ride.load} against ${plan.plannedLoad} planned. Should the rest of the week change?`,
    );
  },
};

const repeatedDrift: TopicRule = {
  id: "repeated-drift",
  available: true,
  evaluate({ ride, driftAlertsLast28Days, settings }) {
    if (driftAlertsLast28Days < settings.repeatedDriftAlerts) return null;
    return candidate(
      `The plan has drifted more than once in recent weeks, and ${ride.ride.name} is the next ride after that.`,
      "My plan has drifted more than once in the last few weeks. Should we change the weekly structure to fit what I can really ride?",
    );
  },
};

/** The rows, in the order they are tried. The rows with `available: false` are the hooks of later stages. */
export const TOPIC_RULES: readonly TopicRule[] = [
  cutShort,
  powerRecord,
  loadOffPlan,
  repeatedDrift,
  // Hooks: the signal does not exist in Phase 3. Each is switched on by writing its `evaluate` here when its stage lands.
  {
    id: "stall-guard",
    available: false,
    needs: "the ERG stall guard firing repeatedly in a ride (spec §11.1, v0.4.0 stage A1)",
  },
  {
    id: "effort-rating",
    available: false,
    needs:
      "the rider's effort rating far above what the load predicts (needs the rating of a ride, spec §12.3)",
  },
  {
    id: "tag-streak",
    available: false,
    needs: "the same tag on a third ride in a row (needs tags on rides, spec §12.3)",
  },
  {
    id: "repeat-trend",
    available: false,
    needs:
      "heart rate at fixed power trending the wrong way over repeats of a session (spec §11.7, stage P3-7)",
  },
];

export interface FoundTopic extends TopicCandidate {
  /** The row of the table that found it. */
  rule: string;
}

/** The first row of the table that has a reason for this ride, or null: most rides have none. */
export function findTopic(
  context: TopicContext,
  rules: readonly TopicRule[] = TOPIC_RULES,
): FoundTopic | null {
  for (const rule of rules) {
    if (!rule.available) continue;
    const found = rule.evaluate(context);
    if (found !== null) return { ...found, rule: rule.id };
  }
  return null;
}
