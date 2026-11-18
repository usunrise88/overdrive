import type {
  RecoveryFact,
  RecoveryRule,
  RecoveryState,
  WellnessMetric,
} from "@overdrive/contracts";
import { baselineBefore, shownRange } from "./baseline.ts";
import { shiftDate } from "./dates.ts";
import { METRIC_KEYS, METRIC_SPECS, roundTo, type WellnessSettings } from "./metrics.ts";
import {
  type DailyTable,
  type DailyValue,
  nightsLogged,
  plainSeries,
  STREAK_LOOKBACK_DAYS,
  type Tracked,
  trackMetric,
} from "./series.ts";

/**
 * The recovery state of a morning (spec §6.4, ADR 0023): ready, caution or rest from an explicit
 * rule table over how far each metric is from the rider's own baseline, in the direction that
 * matters. No opaque score; the rule that decided is named. Everything here is deterministic.
 *
 * Each scored metric gets an **adverse score**: how many spreads from its mean the reading is,
 * counted positive in the adverse direction (resting heart rate, respiratory rate, heart rate in
 * sleep and a late bedtime above the mean; sleep, oxygen saturation and heart-rate variability
 * below). A score above 1 is outside the usual range in the adverse direction. The rules, first
 * match wins:
 *
 * | Rule | Condition | State |
 * |---|---|---|
 * | R1 | three or more metrics with a score above {@link STRONG} (1.5) | rest |
 * | R2 | a metric other than heart-rate variability with a score above {@link EXTREME} (3) this morning and the morning before | rest |
 * | C1 | two or more metrics with a score above 1 | caution |
 * | C2 | one metric with a score above {@link SEVERE} (2) | caution |
 * | C3 | one metric with a score above 1 on this morning and the morning before (a run of two or more) | caution |
 * | OK | none of the above | ready |
 *
 * Heart-rate variability, where a source writes it, is one more metric in the table: its value is the
 * 7-day mean of ln rMSSD (Plews et al. 2013) over at least four of the last seven days, compared with
 * the baseline of that same 7-day mean. It is never required: without it the table has one metric fewer.
 */

export const STRONG = 1.5;
export const SEVERE = 2;
export const EXTREME = 3;
/** A run of this many mornings outside the range makes caution (rule C3). */
export const RUN_FOR_CAUTION = 2;
/** The 7-day mean of Plews et al. needs this many readings in the 7 days. */
export const HRV_WINDOW_DAYS = 7;
export const HRV_MIN_READINGS = 4;

/** What the state is built from. */
export interface RecoveryOutcome {
  /** The morning the state is for. */
  date: string;
  state: RecoveryState;
  reason: string;
  guidance: string | null;
  rule: RecoveryRule | null;
  nightsLogged: number;
  facts: RecoveryFact[];
}

const SCORED: readonly WellnessMetric[] = METRIC_KEYS.filter(
  (key) => METRIC_SPECS[key].adverse !== null,
);

/** How each scored metric is named in a sentence, and what its adverse side is called. */
const PHRASES: Record<string, { name: string; plain: string; strong: string }> = {
  restingHr: {
    name: "resting heart rate",
    plain: "Resting heart rate is above your usual range",
    strong: "Resting heart rate is well above your usual range",
  },
  sleepSeconds: {
    name: "sleep",
    plain: "Sleep was shorter than usual",
    strong: "Sleep was much shorter than usual",
  },
  bedtimeMinutes: {
    name: "bedtime",
    plain: "Bedtime was later than usual",
    strong: "Bedtime was much later than usual",
  },
  respiratoryRate: {
    name: "respiratory rate",
    plain: "Respiratory rate is above your usual range",
    strong: "Respiratory rate is well above your usual range",
  },
  spo2: {
    name: "oxygen saturation",
    plain: "Oxygen saturation is below your usual range",
    strong: "Oxygen saturation is well below your usual range",
  },
  sleepingHr: {
    name: "heart rate in sleep",
    plain: "Heart rate in sleep is above your usual range",
    strong: "Heart rate in sleep is well above your usual range",
  },
  hrvRmssd: {
    name: "heart rate variability",
    plain: "Heart rate variability is below your usual range",
    strong: "Heart rate variability is well below your usual range",
  },
};

/** What each row of the rule table means, in words (for a reader of the answer who wants to know why). */
export const RULE_MEANINGS: Record<RecoveryRule, string> = {
  R1: "three or more signals are more than 1.5 spreads outside their usual range in the direction that matters",
  R2: "a signal has been more than 3 spreads outside its usual range in the direction that matters on two mornings in a row",
  C1: "two or more signals are outside their usual range in the direction that matters",
  C2: "one signal is more than 2 spreads outside its usual range in the direction that matters",
  C3: "one signal has been outside its usual range in the direction that matters on two mornings in a row",
  OK: "no rule for caution or rest applies",
};

export const GUIDANCE = {
  ready: "Train as planned.",
  caution: "Train, but keep the hard parts short.",
  rest: "Keep today easy or take it off.",
} as const;

const NUMBER_WORDS = ["no", "one", "two", "three", "four", "five", "six", "seven", "eight"];
const ORDINALS = ["", "first", "second", "third", "fourth", "fifth", "sixth", "seventh"];

const yesterdayOf = (date: string): string => shiftDate(date, -1);

const capitalize = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1);

function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names.join("");
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

/** The 7-day rolling geometric mean of the daily rMSSD (the exponent of the mean of ln), on days that have a reading. */
export function rollingHrv(
  raw: ReadonlyMap<string, DailyValue> | undefined,
): Map<string, DailyValue> | undefined {
  if (raw === undefined) return undefined;
  const rolling = new Map<string, DailyValue>();
  for (const [date, entry] of raw) {
    const logs: number[] = [];
    for (let back = 0; back < HRV_WINDOW_DAYS; back++) {
      const value = raw.get(shiftDate(date, -back))?.value;
      if (value !== undefined && value > 0) logs.push(Math.log(value));
    }
    if (logs.length >= HRV_MIN_READINGS) {
      rolling.set(date, {
        value: Math.exp(logs.reduce((a, b) => a + b, 0) / logs.length),
        source: entry.source,
      });
    }
  }
  return rolling;
}

interface Score {
  metric: WellnessMetric;
  /** Spreads from the mean in the adverse direction this morning. */
  now: number;
  /** The same the morning before, or null without one. */
  before: number | null;
  /** Mornings in a row up to this one with a score above 1. */
  run: number;
}

function adverseScore(tracked: Tracked | undefined, metric: WellnessMetric): number | null {
  if (tracked === undefined || tracked.z === null) return null;
  return METRIC_SPECS[metric].adverse === "low" ? -tracked.z : tracked.z;
}

function phraseList(scores: readonly Score[]): string {
  return joinNames(scores.map((score) => PHRASES[score.metric]?.name ?? score.metric));
}

function bySeverity(a: Score, b: Score): number {
  return b.now - a.now || METRIC_SPECS[a.metric].priority - METRIC_SPECS[b.metric].priority;
}

interface Verdict {
  rule: RecoveryRule;
  state: "ready" | "caution" | "rest";
  reason: string;
  /** The metrics that fired it, strongest first. */
  triggers: WellnessMetric[];
}

/** The rule table, first match wins. `outsideRange` are the metrics outside their usual range on either side. */
function decide(
  scores: readonly Score[],
  readings: number,
  outsideRange: readonly WellnessMetric[],
): Verdict {
  const above = (limit: number) => scores.filter((s) => s.now > limit).sort(bySeverity);
  const names = (list: readonly Score[]) => list.map((s) => s.metric);

  const strong = above(STRONG);
  if (strong.length >= 3) {
    return {
      rule: "R1",
      state: "rest",
      reason: `${capitalize(NUMBER_WORDS[strong.length] ?? "several")} signals are well outside your usual range: ${phraseList(strong)}.`,
      triggers: names(strong),
    };
  }

  // Heart-rate variability refines the state but never decides rest on its own.
  const extreme = scores
    .filter(
      (s) => s.metric !== "hrvRmssd" && s.now > EXTREME && s.before !== null && s.before > EXTREME,
    )
    .sort(bySeverity);
  if (extreme.length > 0) {
    const plural = extreme.length > 1;
    return {
      rule: "R2",
      state: "rest",
      reason: `${capitalize(phraseList(extreme))} ${plural ? "have" : "has"} been far outside your usual range for two mornings.`,
      triggers: names(extreme),
    };
  }

  const outside = above(1);
  if (outside.length >= 2) {
    return {
      rule: "C1",
      state: "caution",
      reason: `${capitalize(NUMBER_WORDS[outside.length] ?? "several")} signals are outside your usual range: ${phraseList(outside)}.`,
      triggers: names(outside),
    };
  }

  const severe = above(SEVERE);
  const [worst] = severe;
  if (worst !== undefined) {
    return {
      rule: "C2",
      state: "caution",
      reason: `${PHRASES[worst.metric]?.strong ?? worst.metric}.`,
      triggers: names(severe),
    };
  }

  // Two metrics on a run are both beyond 1 spread this morning, which rule C1 has already caught:
  // what is left here is one metric.
  const [longest] = scores.filter((s) => s.run >= RUN_FOR_CAUTION).sort(bySeverity);
  if (longest !== undefined) {
    const ordinal = ORDINALS[longest.run] ?? `${longest.run}th`;
    return {
      rule: "C3",
      state: "caution",
      reason: `${PHRASES[longest.metric]?.plain ?? longest.metric} for the ${ordinal} morning.`,
      triggers: [longest.metric],
    };
  }

  // Ready. The sentence must be true: a metric a little outside its range, for one morning, is said so.
  const [adverse] = outsideRange.length === 0 ? [] : above(1);
  const reason =
    outsideRange.length === 0
      ? readings === 1
        ? "Your one signal is in your usual range."
        : readings === 2
          ? "Both signals are in your usual range."
          : `All ${NUMBER_WORDS[readings] ?? "several"} signals are in your usual range.`
      : adverse !== undefined
        ? `${PHRASES[adverse.metric]?.plain ?? adverse.metric}, but nothing else is.`
        : "No signal is outside your usual range in the wrong direction.";
  return {
    rule: "OK",
    state: "ready",
    reason,
    triggers: adverse === undefined ? [] : [adverse.metric],
  };
}

function factOf(
  metric: WellnessMetric,
  tracked: Tracked | undefined,
  table: DailyTable,
  date: string,
  settings: WellnessSettings,
  withRange: boolean,
): RecoveryFact {
  const spec = METRIC_SPECS[metric];
  let range = withRange ? (tracked?.range ?? null) : null;
  if (withRange && tracked === undefined) {
    // No reading this morning: the range that was in force is still worth showing.
    const baseline = baselineBefore(plainSeries(table[metric]), date, spec, settings);
    range = baseline === null ? null : shownRange(baseline, spec);
  }
  return {
    metric: metric as RecoveryFact["metric"],
    label: spec.label,
    value: tracked === undefined ? null : roundTo(tracked.value, spec.digits),
    unit: spec.unit,
    baselineLow: range?.low ?? null,
    baselineHigh: range?.high ?? null,
    deviation: withRange ? (tracked?.deviation ?? null) : null,
    streak: withRange ? (tracked?.streak ?? 0) : 0,
  };
}

export const MAX_FACTS = 4;

/**
 * The state of the morning of `today` (the rider's local date), or of yesterday while this morning's
 * readings have not arrived, or noData when neither has one. `table` holds the resolved daily values
 * from at least `baselineDays + STREAK_LOOKBACK_DAYS + 7` days before `today`.
 */
export function evaluateRecovery(
  input: DailyTable,
  today: string,
  settings: WellnessSettings,
): RecoveryOutcome {
  const table: DailyTable = { ...input };
  const rolling = rollingHrv(input.hrvRmssd);
  if (rolling !== undefined) table.hrvRmssd = rolling;

  const hasReading = (date: string) => SCORED.some((key) => table[key]?.has(date) === true);
  const yesterday = shiftDate(today, -1);
  const date = hasReading(today) ? today : hasReading(yesterday) ? yesterday : null;
  if (date === null) {
    return {
      date: today,
      state: "noData",
      reason:
        "No reading from last night. Wear the watch to sleep, then open Overdrive in the morning.",
      guidance: null,
      rule: null,
      nightsLogged: nightsLogged(table, today, settings),
      facts: [],
    };
  }

  const first = shiftDate(date, -STREAK_LOOKBACK_DAYS);
  const tracks = new Map<WellnessMetric, Map<string, Tracked>>();
  for (const key of SCORED) {
    tracks.set(key, trackMetric(table[key], METRIC_SPECS[key], settings, first, date));
  }
  const nights = nightsLogged(table, date, settings);

  // Scores of the metrics with a reading and a range this morning.
  const scores: Score[] = [];
  for (const key of SCORED) {
    const track = tracks.get(key);
    const now = adverseScore(track?.get(date), key);
    if (now === null) continue;
    let run = 0;
    for (let back = 0; back <= STREAK_LOOKBACK_DAYS; back++) {
      const score = adverseScore(track?.get(shiftDate(date, -back)), key);
      if (score === null || score <= 1) break;
      run++;
    }
    scores.push({
      metric: key,
      now,
      before: adverseScore(track?.get(yesterdayOf(date)), key),
      run,
    });
  }

  const readingsToday = SCORED.filter((key) => tracks.get(key)?.has(date) === true);
  const priority = (a: WellnessMetric, b: WellnessMetric) =>
    METRIC_SPECS[a].priority - METRIC_SPECS[b].priority;

  if (nights < settings.nightsNeeded || scores.length === 0) {
    const shown = [...readingsToday].sort(priority).slice(0, MAX_FACTS);
    return {
      date,
      state: "learning",
      reason:
        nights < settings.nightsNeeded
          ? `Overdrive rates recovery after ${settings.nightsNeeded} nights with the watch. ${nights} ${nights === 1 ? "night" : "nights"} so far.`
          : "Overdrive is still working out your usual range from the readings it has.",
      guidance: null,
      rule: null,
      nightsLogged: nights,
      facts: shown.map((key) =>
        factOf(key, tracks.get(key)?.get(date), table, date, settings, false),
      ),
    };
  }

  const outside = scores
    .map((score) => score.metric)
    .filter((key) => {
      const deviation = tracks.get(key)?.get(date)?.deviation;
      return deviation === "above" || deviation === "below";
    });
  const verdict = decide(scores, scores.length, outside);
  // Facts: what decided it, then the metrics outside their usual range, then those inside it, then those with
  // a reading and no range yet, then those without a reading but with a usual range (shown as "no reading"),
  // up to four, each group in the order of the table.
  const rank = (key: WellnessMetric): number | null => {
    const tracked = tracks.get(key)?.get(date);
    if (tracked?.range != null) return tracked.deviation === "inside" ? 1 : 0;
    if (tracked !== undefined) return 2;
    const had = baselineBefore(plainSeries(table[key]), date, METRIC_SPECS[key], settings);
    return had === null ? null : 3;
  };
  const rest = SCORED.filter((key) => !verdict.triggers.includes(key))
    .map((key) => ({ key, rank: rank(key) }))
    .filter((entry): entry is { key: WellnessMetric; rank: number } => entry.rank !== null)
    .sort((a, b) => a.rank - b.rank || priority(a.key, b.key))
    .map((entry) => entry.key);
  const order: WellnessMetric[] = [...verdict.triggers, ...rest];

  return {
    date,
    state: verdict.state,
    reason: verdict.reason,
    guidance: GUIDANCE[verdict.state],
    rule: verdict.rule,
    nightsLogged: nights,
    facts: order
      .slice(0, MAX_FACTS)
      .map((key) => factOf(key, tracks.get(key)?.get(date), table, date, settings, true)),
  };
}
