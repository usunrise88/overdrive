import { dayAndMonth } from "../zoned.ts";
import type {
  DeviationFacts,
  DriftFacts,
  Facts,
  MorningFacts,
  PlannedFact,
  RideFacts,
  SignalFact,
  WeekFacts,
} from "./facts.ts";

/**
 * The deterministic text of a note, written from the facts by code (ADR 0025). It is what is stored (`source: "template"`) when no
 * language model is configured, when the endpoint fails, or when the model's text was rejected twice, so that Telegram and the
 * companion are never empty. It quotes only numbers of the facts, uses no word of the deny list and states a conclusion and a
 * recommendation like any note; it is English whatever `INSIGHTS_LANGUAGE` says. The tests run every text it can produce through
 * the same validator as a model's.
 */

/** The template's own version, stored as `prompt_version` of a template note. */
export const TEMPLATE_VERSION = "template.v1";

const MAX_TEXT = 1500;

const plural = (count: number, one: string, many: string): string => (count === 1 ? one : many);
const abs = (value: number): number => Math.abs(value);
const sentence = (text: string): string => (/[.!?]$/.test(text) ? text : `${text}.`);

function clip(text: string): string {
  return text.length <= MAX_TEXT ? text : `${text.slice(0, MAX_TEXT - 1).trimEnd()}…`;
}

function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names.join("");
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

/** The reading of a signal in words: `58 bpm, against a usual 50 to 54`. */
function readingOf(signal: SignalFact): string {
  if (signal.metric === "bedtimeMinutes") {
    if (signal.clock === null) return "no reading";
    return signal.usualClock === null
      ? `bedtime ${signal.clock}`
      : `bedtime ${signal.clock}, against a usual ${signal.usualClock}`;
  }
  if (signal.value === null) return "no reading";
  const value = `${signal.value} ${signal.unit}`;
  return signal.usualLow === null || signal.usualHigh === null
    ? value
    : `${value}, against a usual ${signal.usualLow} to ${signal.usualHigh}`;
}

function plannedLine(planned: readonly PlannedFact[]): string {
  const open = planned.filter((p) => !p.done);
  const [first] = open;
  if (first === undefined) return "";
  const extra = open.length - 1;
  const parts = [first.name];
  if (first.minutes !== null) parts.push(`${first.minutes} min`);
  if (first.plannedLoad !== null) parts.push(`planned load ${first.plannedLoad}`);
  const more = extra > 0 ? ` (and ${extra} more)` : "";
  return `Planned today: ${parts.join(", ")}${more}.`;
}

// ---- Morning --------------------------------------------------------------------------------------

export function morningText(facts: MorningFacts): string {
  const lines: string[] = [];
  const r = facts.recovery;
  const verdict = r?.forToday === true && ["ready", "caution", "rest"].includes(r.state);
  if (r !== null && verdict) {
    if (r.state === "ready") lines.push(sentence(`Ready this morning. ${r.reason}`));
    else if (r.state === "caution") lines.push(sentence(`Caution this morning. ${r.reason}`));
    else lines.push(sentence(`Rest is the call this morning. ${r.reason}`));
  } else if (r !== null && r.state === "learning") {
    lines.push(sentence(`Recovery is not rated yet. ${r.reason}`));
  } else {
    lines.push("There is no watch data from this morning yet, so recovery is not rated.");
  }

  const planned = facts.today.planned;
  const open = planned.filter((p) => !p.done);
  const line = plannedLine(planned);
  if (line !== "") lines.push(line);
  else if (planned.length > 0) lines.push("Today's planned workout is already ridden.");
  else lines.push("Nothing is planned today.");

  // The recommendation: said in words, and the plan stays as it is.
  if (open.length > 0) {
    if (facts.adjustment === "easy-or-off") {
      lines.push(
        "Swap it for an easy spin or take the day off. The plan itself stays as it is; ask the coach if you want it moved.",
      );
    } else if (facts.adjustment === "keep-hard-parts-short") {
      lines.push(
        "Do it, but keep the hard parts short and stop early if it feels heavy. The plan itself stays as it is.",
      );
    } else if (verdict) {
      lines.push("Train as planned.");
    } else {
      lines.push("Ride it as planned and judge by feel.");
    }
  } else if (facts.adjustment === "easy-or-off") {
    lines.push("Keep today easy or take it off.");
  } else if (facts.adjustment === "keep-hard-parts-short") {
    lines.push("If you ride, keep it easy.");
  }

  const form = facts.form;
  if (form !== null && form.form !== null && form.formWord !== null) {
    lines.push(`Form is ${form.form} (${form.formWord}).`);
  }
  return clip(lines.join(" "));
}

// ---- After a ride ---------------------------------------------------------------------------------

export function rideText(facts: RideFacts): string {
  const lines: string[] = [];
  const ride = facts.ride;
  const head = [`${ride.name}: ${ride.minutes ?? 0} min`];
  if (ride.averagePower !== null) head.push(`average ${ride.averagePower} W`);
  if (ride.normalizedPower !== null) head.push(`normalised ${ride.normalizedPower} W`);
  if (ride.load !== null) head.push(`load ${ride.load}`);
  lines.push(`${head.join(", ")}.`);

  const plan = facts.plan;
  let recommendation = "";
  if (plan !== null) {
    const planned = plan.plannedMinutes === null ? "" : `${plan.plannedMinutes} min planned`;
    const ridden =
      plan.riddenMinutes === null
        ? ""
        : `${plan.riddenMinutes} min of it ridden${plan.completionPercent === null ? "" : ` (${plan.completionPercent}%)`}`;
    lines.push(`Plan: ${[plan.workoutName, planned, ridden].filter((p) => p !== "").join(", ")}.`);
    if (plan.stepsCompared !== null && plan.stepsCompared > 0) {
      const watts =
        plan.targetWatts !== null && plan.riddenWatts !== null
          ? ` The steps averaged ${plan.riddenWatts} W against a target of ${plan.targetWatts} W.`
          : "";
      lines.push(
        `${plan.stepsOnTarget ?? 0} of ${plan.stepsCompared} ${plural(plan.stepsCompared, "step", "steps")} landed within ${plan.onTargetTolerancePercent ?? 5}% of target.${watts}`,
      );
    }
    const cutShort = plan.completionPercent !== null && plan.completionPercent < 75;
    if (cutShort) {
      recommendation =
        "The session was cut short. If that was not your choice, tell the coach so the week can be adjusted.";
    } else if ((plan.stepsAboveTarget ?? 0) > 0) {
      recommendation =
        "You rode above target in some steps. Hold the target power next time so the session stays what it was meant to be.";
    } else if ((plan.stepsBelowTarget ?? 0) > 0) {
      recommendation =
        "Some steps came in below target. If it felt too hard, take an easier day next and let the coach look at the target.";
    } else if (plan.stepsCompared !== null && plan.stepsCompared > 0) {
      recommendation = "Targets were met: carry on as planned.";
    }
  } else {
    lines.push("This was a free ride, so there is no plan to compare it with.");
  }

  for (const effort of facts.efforts.filter((e) => e.newBest)) {
    lines.push(
      `New best for ${effort.duration}: ${effort.power} W, up ${effort.improvementWatts ?? 0} W on ${effort.previousBest ?? 0} W.`,
    );
  }
  if (facts.drift !== null)
    lines.push(`Aerobic drift over the steady stretch was ${facts.drift.percent}%.`);
  const w = facts.wPrime;
  if (
    w?.low &&
    w.lowestKj !== null &&
    w.capacityKj !== null &&
    w.lowestPercentOfCapacity !== null
  ) {
    lines.push(
      `W' dropped to ${w.lowestKj} kJ of ${w.capacityKj} kJ (${w.lowestPercentOfCapacity}%): a very hard effort.`,
    );
  }
  if (recommendation === "") {
    recommendation =
      facts.form !== null && facts.form.form !== null && facts.form.form < -30
        ? "Fatigue is high: make the next day easy."
        : "Carry on with the plan.";
  }
  lines.push(recommendation);
  return clip(lines.join(" "));
}

// ---- The week -------------------------------------------------------------------------------------

export function weekText(facts: WeekFacts): string {
  const lines: string[] = [];
  const s = facts.sessions;
  const from = dayAndMonth(facts.week.from);
  const to = dayAndMonth(facts.week.to);
  let sessions = `Week of ${from} to ${to}: ${s.done ?? 0} of ${s.planned ?? 0} planned ${plural(s.planned ?? 0, "session", "sessions")} done`;
  if ((s.skipped ?? 0) > 0) sessions += `, ${s.skipped} skipped`;
  sessions += ".";
  lines.push(sessions);
  if (s.missed.length > 0) {
    lines.push(`Missed: ${joinNames(s.missed.map((m) => `${m.name} on ${dayAndMonth(m.date)}`))}.`);
  }
  if (s.rides !== null && s.rides > 0) {
    lines.push(`${s.rides} ${plural(s.rides, "ride", "rides")}, ${s.minutes ?? 0} min.`);
  } else {
    lines.push("No rides this week.");
  }
  const l = facts.load;
  if (l.week !== null) {
    if (l.previousWeek !== null && l.changeVsPreviousWeek !== null) {
      const way =
        l.changeVsPreviousWeek === 0
          ? "the same as"
          : `${l.changeVsPreviousWeek > 0 ? "up" : "down"} ${abs(l.changeVsPreviousWeek)} on`;
      lines.push(`Load ${l.week}, ${way} the week before (${l.previousWeek}).`);
    } else {
      lines.push(`Load ${l.week}.`);
    }
  }
  const f = facts.form;
  if (f !== null && f.fitness !== null && f.form !== null && f.formWord !== null) {
    const change =
      facts.fitnessChangeOverWeek === null
        ? ""
        : facts.fitnessChangeOverWeek === 0
          ? ", unchanged on the week"
          : `, ${facts.fitnessChangeOverWeek > 0 ? "up" : "down"} ${abs(facts.fitnessChangeOverWeek)} on the week`;
    lines.push(`Fitness ${f.fitness}${change}; form ${f.form} (${f.formWord}).`);
  }
  const d = facts.recoveryDays;
  const rated = (d.ready ?? 0) + (d.caution ?? 0) + (d.rest ?? 0);
  if (rated > 0) {
    lines.push(
      `Recovery: ${d.ready ?? 0} ready, ${d.caution ?? 0} caution, ${d.rest ?? 0} rest ${plural(rated, "morning", "mornings")}.`,
    );
  }
  for (const best of facts.newBests) {
    lines.push(
      `New best for ${best.duration}: ${best.power ?? 0} W, up from ${best.previous ?? 0} W.`,
    );
  }
  const next = facts.nextWeek;
  if ((next.sessions ?? 0) > 0) {
    lines.push(
      `Next 7 days: ${next.sessions} ${plural(next.sessions ?? 0, "session", "sessions")} planned${next.plannedLoad === null ? "" : `, load ${next.plannedLoad}`}.`,
    );
  }
  // The recommendation.
  const hard = (d.rest ?? 0) >= 2 || (f !== null && f.form !== null && f.form < -30);
  if (hard) lines.push("Start next week easy and let form come back before the hard sessions.");
  else if (s.missed.length > 0) {
    lines.push("Pick up with the next planned session rather than making up the missed ones.");
  } else lines.push("Carry on with the plan.");
  return clip(lines.join(" "));
}

// ---- Alerts ---------------------------------------------------------------------------------------

export function deviationText(facts: DeviationFacts): string {
  const lines: string[] = [];
  for (const signal of facts.signals) {
    const side = signal.position === "below" ? "below" : "above";
    const days = signal.daysOutsideInARow;
    const subject =
      signal.metric === "bedtimeMinutes"
        ? "Your bedtime has been later than usual"
        : signal.metric === "sleepSeconds"
          ? "Your sleep has been shorter than usual"
          : `Your ${signal.name.toLowerCase()} has been ${side} your usual range`;
    lines.push(
      `${subject} for ${days} ${plural(days, "morning", "mornings")} in a row (${readingOf(signal)}). ${signal.reading} ${signal.trainingHint}`,
    );
  }
  const line = plannedLine(facts.today.planned);
  if (line !== "") lines.push(`${line} The plan itself stays as it is.`);
  return clip(lines.join(" "));
}

export function driftText(facts: DriftFacts): string {
  const lines: string[] = [];
  const window = facts.windowDays ?? 7;
  if (facts.missed !== null) {
    const count = facts.missed.count ?? facts.missed.sessions.length;
    const names = joinNames(
      facts.missed.sessions.map((m) => `${m.name} on ${dayAndMonth(m.date)}`),
    );
    lines.push(
      `${count} planned ${plural(count, "session was", "sessions were")} not ridden in the last ${window} days: ${names}.`,
    );
  }
  const load = facts.load;
  if (
    load !== null &&
    load.planned !== null &&
    load.ridden !== null &&
    load.differencePercent !== null
  ) {
    if (facts.reasons.includes("load-above")) {
      lines.push(
        `You rode a load of ${load.ridden} against ${load.planned} planned over the last ${window} days, ${abs(load.differencePercent)}% above the plan.`,
      );
    } else if (facts.reasons.includes("load-below")) {
      lines.push(
        `You rode a load of ${load.ridden} against ${load.planned} planned over the last ${window} days, ${abs(load.differencePercent)}% below the plan.`,
      );
    }
  }
  if (facts.reasons.includes("load-above")) {
    lines.push("Make the next day easy so the extra load does not pile up.");
  } else if (facts.reasons.includes("missed") || facts.reasons.includes("load-below")) {
    lines.push(
      "If the plan still fits your week, pick it up with the next session; if it does not, ask the coach to reshape the week.",
    );
  }
  lines.push("The plan itself has not been changed.");
  return clip(lines.join(" "));
}

/** The template text for any kind of facts. */
export function templateText(facts: Facts): string {
  switch (facts.kind) {
    case "morning":
      return morningText(facts);
    case "ride":
      return rideText(facts);
    case "week":
      return weekText(facts);
    case "deviation":
      return deviationText(facts);
    case "drift":
      return driftText(facts);
  }
}
