import type {
  InsightKind,
  RecoveryFact,
  RecoveryMetric,
  RecoveryResponse,
  RecoveryRule,
  RecoveryState,
  RideDrift,
  Workout,
} from "@overdrive/contracts";
import { addDaysToIsoDate } from "@overdrive/contracts";
import { workoutOutline } from "../plan/summary.ts";
import type { Compliance, StepCompliance } from "../ride-stats.ts";
import { epochDay } from "../wellness/dates.ts";
import { RULE_MEANINGS } from "../wellness/recovery.ts";
import { plannedStats } from "./planned.ts";
import { type Quantity, Quotes, roundTo } from "./quotes.ts";

/**
 * The facts a written note is made from (spec §6.5, ADR 0025): structured JSON the service computes deterministically, that the
 * language model is given and may quote but never calculate from. These builders are pure: the same inputs give the same facts,
 * byte for byte (golden-tested), and every number in them was registered with {@link Quotes}, so it is in the facts' `quotable`
 * list. Gathering the inputs from the services is `gather.ts`.
 *
 * HEALTH DATA IS PRIVATE: the facts are stored, shown to the endpoint the owner configured and returned by `get_facts`. Nothing
 * here, or in the callers, ever logs one.
 */

export interface FactsBase {
  schema: 1;
  kind: InsightKind;
  /** The local day the note is about (`YYYY-MM-DD`): for a week, its Monday. */
  date: string;
  weekday: string;
  /** Every number the text may quote, with what it is and its unit. Nothing else may be quoted. */
  quotable: Quantity[];
}

const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

/** `Wednesday` for a date; English whatever the host's locale. */
export function weekdayOf(date: string): string {
  return WEEKDAYS[(((epochDay(date) + 3) % 7) + 7) % 7] ?? "";
}

/** Whole days from `from` to `to` (negative when `to` is earlier). */
export function daysBetween(from: string, to: string): number {
  return epochDay(to) - epochDay(from);
}

// ---- Form -----------------------------------------------------------------------------------------

/** Fresh above +5, neutral from -10 to +5, training hard from -30 to below -10, very fatigued below -30 (design/companion.md §7.3). */
export type FormWord = "fresh" | "neutral" | "training hard" | "very fatigued";

export function formWord(form: number): FormWord {
  if (form > 5) return "fresh";
  if (form >= -10) return "neutral";
  if (form >= -30) return "training hard";
  return "very fatigued";
}

export interface FormInput {
  /** The day these are for. */
  date: string;
  ctl: number;
  atl: number;
  tsb: number;
  /** Fitness a week before, or null when there is no history that far back. */
  ctlWeekAgo: number | null;
}

export interface FormFact {
  date: string;
  /** Fitness (the 42-day average of the daily load), whole points. */
  fitness: number | null;
  /** Fatigue (the 7-day average), whole points. */
  fatigue: number | null;
  /** Fitness minus fatigue, whole points. */
  form: number | null;
  formWord: FormWord | null;
  /** The change in fitness over the 7 days, whole points, from the rounded values. */
  fitnessChange7Days: number | null;
}

function formFact(q: Quotes, input: FormInput | null): FormFact | null {
  if (input === null) return null;
  const fitness = q.n("fitness (the 42-day average of the daily load)", input.ctl, "load");
  const fatigue = q.n("fatigue (the 7-day average of the daily load)", input.atl, "load");
  const form = q.n("form (fitness minus fatigue)", input.tsb, "load");
  const before = input.ctlWeekAgo === null ? null : roundTo(input.ctlWeekAgo, 0);
  const change =
    fitness === null || before === null
      ? null
      : q.n("change in fitness over 7 days", fitness - before, "load");
  return {
    date: input.date,
    fitness,
    fatigue,
    form,
    formWord: form === null ? null : formWord(form),
    fitnessChange7Days: change,
  };
}

// ---- Recovery signals -----------------------------------------------------------------------------

export interface SignalFact {
  metric: RecoveryMetric;
  name: string;
  /** The reading in `unit`; null with no reading, and for the bedtime, which is `clock`. */
  value: number | null;
  unit: string;
  /** The bedtime as a time of day (`23:10`). */
  clock: string | null;
  usualLow: number | null;
  usualHigh: number | null;
  /** The usual bedtime range, `22:40 to 23:20`. */
  usualClock: string | null;
  position: "above" | "below" | "inside" | null;
  /** Days in a row up to and including this morning outside the usual range on the same side. */
  daysOutsideInARow: number;
}

const DIGITS: Record<RecoveryMetric, number> = {
  restingHr: 0,
  sleepSeconds: 1,
  bedtimeMinutes: 0,
  respiratoryRate: 1,
  spo2: 1,
  sleepingHr: 0,
  hrvRmssd: 0,
};

const UNITS: Record<RecoveryMetric, string> = {
  restingHr: "bpm",
  sleepSeconds: "h",
  bedtimeMinutes: "clock",
  respiratoryRate: "/min",
  spo2: "%",
  sleepingHr: "bpm",
  hrvRmssd: "ms",
};

const pad = (n: number): string => String(n).padStart(2, "0");

/** Minutes from local midnight (a bedtime before midnight negative) as a time of day. */
export function clockOf(minutes: number): string {
  const wrapped = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${pad(Math.floor(wrapped / 60))}:${pad(wrapped % 60)}`;
}

export function signalFact(q: Quotes, fact: RecoveryFact): SignalFact {
  const metric = fact.metric;
  const hours = metric === "sleepSeconds";
  const digits = DIGITS[metric];
  const scale = (value: number | null): number | null =>
    value === null ? null : hours ? value / 3600 : value;
  const num = (suffix: string, value: number | null): number | null =>
    metric === "bedtimeMinutes"
      ? null
      : q.n(`${fact.label}${suffix}`, scale(value), UNITS[metric], digits);
  const value = num("", fact.value);
  const low = num(" (usual range, low end)", fact.baselineLow);
  const high = num(" (usual range, high end)", fact.baselineHigh);
  const clock = metric === "bedtimeMinutes" && fact.value !== null ? clockOf(fact.value) : null;
  const usualClock =
    metric === "bedtimeMinutes" && fact.baselineLow !== null && fact.baselineHigh !== null
      ? `${clockOf(fact.baselineLow)} to ${clockOf(fact.baselineHigh)}`
      : null;
  const outside = fact.deviation === "above" || fact.deviation === "below";
  return {
    metric,
    name: fact.label,
    value,
    unit: UNITS[metric],
    clock,
    usualLow: low,
    usualHigh: high,
    usualClock,
    position: fact.deviation,
    daysOutsideInARow:
      q.n(
        `${fact.label}: days outside the usual range in a row`,
        outside ? fact.streak : 0,
        "days",
      ) ?? 0,
  };
}

// ---- Planned workouts -----------------------------------------------------------------------------

export interface PlannedInput {
  name: string;
  /** null: the tablet cannot play it. */
  workout: Workout | null;
  /** Ridden already. */
  done: boolean;
}

export interface PlannedFact {
  name: string;
  minutes: number | null;
  /** The training stress if ridden as planned. */
  plannedLoad: number | null;
  /** The intensity factor if ridden as planned. */
  intensityFactor: number | null;
  /** The hardest step's target in watts at the current FTP. */
  topStepWatts: number | null;
  /** The steps in one line, targets as percent of FTP. */
  outline: string | null;
  done: boolean;
}

export function plannedFact(q: Quotes, input: PlannedInput, ftp: number | null): PlannedFact {
  if (input.workout === null) {
    return {
      name: input.name,
      minutes: null,
      plannedLoad: null,
      intensityFactor: null,
      topStepWatts: null,
      outline: null,
      done: input.done,
    };
  }
  const stats = plannedStats(input.workout);
  const label = `planned workout "${input.name}"`;
  return {
    name: input.name,
    minutes: q.minutes(`${label}: length`, stats.seconds),
    plannedLoad: q.n(`${label}: planned load if ridden as planned`, stats.load, "load"),
    intensityFactor: q.n(`${label}: intensity factor`, stats.intensityFactor, "IF", 2),
    topStepWatts:
      ftp === null || stats.topFraction === null
        ? null
        : q.n(`${label}: hardest step target`, stats.topFraction * ftp, "W"),
    outline: workoutOutline(input.workout),
    done: input.done,
  };
}

// ---- Morning --------------------------------------------------------------------------------------

export interface MorningInput {
  date: string;
  /** null: the wellness data could not be read. */
  recovery: RecoveryResponse | null;
  planned: PlannedInput[];
  ftp: number | null;
  form: FormInput | null;
  lastRide: {
    name: string;
    date: string;
    durationSeconds: number;
    load: number | null;
    averagePower: number | null;
  } | null;
}

export type Adjustment = "none" | "keep-hard-parts-short" | "easy-or-off";

export interface MorningFacts extends FactsBase {
  kind: "morning";
  recovery: {
    state: RecoveryState;
    /** The state is for this morning (false: for yesterday, or there is none). */
    forToday: boolean;
    date: string;
    reason: string;
    guidance: string | null;
    rule: RecoveryRule | null;
    ruleMeaning: string | null;
    nightsLogged: number;
    nightsNeeded: number;
    signals: SignalFact[];
  } | null;
  /** How the workout should be treated: the rider's recovery says so; the note says it in words and never changes the plan. */
  adjustment: Adjustment;
  today: { ftp: number | null; planned: PlannedFact[] };
  form: FormFact | null;
  lastRide: {
    name: string;
    date: string;
    daysAgo: number;
    minutes: number | null;
    load: number | null;
    averagePower: number | null;
  } | null;
  /** Plain statements about what is missing, with no numbers. */
  notes: string[];
}

export function buildMorningFacts(input: MorningInput): MorningFacts {
  const q = new Quotes();
  const notes: string[] = [];
  const verdict =
    input.recovery !== null &&
    (input.recovery.state === "ready" ||
      input.recovery.state === "caution" ||
      input.recovery.state === "rest");
  const forToday = input.recovery !== null && input.recovery.date === input.date;
  let recovery: MorningFacts["recovery"] = null;
  if (input.recovery !== null) {
    const r = input.recovery;
    const signals = r.facts.map((fact) => signalFact(q, fact));
    const nightsLogged = q.n("nights with the watch so far", r.nightsLogged, "nights") ?? 0;
    const nightsNeeded =
      q.n("nights needed before recovery is rated", r.nightsNeeded, "nights") ?? 0;
    recovery = {
      state: r.state,
      forToday,
      date: r.date,
      reason: r.reason,
      guidance: r.guidance,
      rule: r.rule,
      ruleMeaning: r.rule === null ? null : RULE_MEANINGS[r.rule],
      nightsLogged,
      nightsNeeded,
      signals,
    };
    if (!forToday && r.state !== "noData") {
      notes.push(
        "This morning's watch data has not arrived: the state shown is from the morning before.",
      );
    }
    if (r.state === "noData") {
      notes.push("There is no reading from last night, so recovery is not rated this morning.");
    }
  } else {
    notes.push("Recovery could not be read.");
  }
  const adjustment: Adjustment =
    !verdict || !forToday
      ? "none"
      : input.recovery?.state === "caution"
        ? "keep-hard-parts-short"
        : input.recovery?.state === "rest"
          ? "easy-or-off"
          : "none";

  const planned = input.planned.map((entry) => plannedFact(q, entry, input.ftp));
  if (planned.length === 0) notes.push("Nothing is planned for today.");
  const ftp = q.n("FTP", input.ftp, "W");
  const last =
    input.lastRide === null
      ? null
      : {
          name: input.lastRide.name,
          date: input.lastRide.date,
          daysAgo:
            q.n("days since the last ride", daysBetween(input.lastRide.date, input.date), "days") ??
            0,
          minutes: q.minutes("last ride: length", input.lastRide.durationSeconds),
          load: q.n("last ride: load", input.lastRide.load, "load"),
          averagePower: q.n("last ride: average power", input.lastRide.averagePower, "W"),
        };
  return {
    schema: 1,
    kind: "morning",
    date: input.date,
    weekday: weekdayOf(input.date),
    recovery,
    adjustment,
    today: { ftp, planned },
    form: formFact(q, input.form),
    lastRide: last,
    notes,
    quotable: q.list,
  };
}

// ---- After a ride ---------------------------------------------------------------------------------

/** The best power at a standard duration before this ride. */
export interface PriorBest {
  durationSec: number;
  /** The best average power over that duration in any earlier ride; null when no earlier ride is long enough. */
  power: number | null;
  date: string | null;
  /** How many earlier rides have a value at that duration. */
  rides: number;
}

export interface RideInput {
  rideId: string;
  name: string;
  date: string;
  /** The local start time of day, `07:12`. */
  startClock: string;
  durationSeconds: number;
  movingSeconds: number;
  averagePower: number | null;
  normalizedPower: number | null;
  intensityFactor: number | null;
  load: number | null;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  workKilojoules: number | null;
  ftp: number;
  /**
   * The workout the ride was played from and how it went, or null for a free ride. `easierVersionRidden`: the rider chose the session's
   * easier variant (spec §11.5), and `workout` is that version: an implicit feedback fact about the day.
   */
  plan: {
    workout: Workout;
    compliance: Compliance | { problem: string };
    easierVersionRidden?: boolean;
  } | null;
  /** This ride's best power at each duration. */
  curve: { durationSec: number; power: number }[];
  priorBests: PriorBest[];
  drift: RideDrift | null;
  wPrime: { capacityKj: number; lowestKj: number; atSec: number } | null;
  /** Fitness, fatigue and form at the end of the ride's day. */
  form: FormInput | null;
}

/** The standard durations whose best power a ride note compares, in seconds. */
export const EFFORT_DURATIONS = [5, 60, 300, 1200, 3600] as const;

const EFFORT_LABEL: Record<number, string> = {
  5: "5 s",
  60: "1 min",
  300: "5 min",
  1200: "20 min",
  3600: "60 min",
};

/** A step is on target within this many percent of the planned power. */
export const ON_TARGET_PERCENT = 5;
/** A ride note lists at most this many of the plan's steps. */
const MAX_STEPS = 20;
/** The W′ balance is "low" when it fell below this share of W′. */
export const W_PRIME_LOW_PERCENT = 30;

export interface StepFact {
  step: number;
  label: string;
  minutes: number | null;
  plannedWatts: number | null;
  actualWatts: number | null;
  deltaPercent: number | null;
}

export interface EffortFact {
  duration: string;
  durationSec: number;
  power: number;
  previousBest: number | null;
  previousBestDate: string | null;
  /** How many earlier rides have a best at that duration. */
  priorRides: number;
  /** Higher than any earlier ride's best at that duration (and there is an earlier ride to beat). */
  newBest: boolean;
  improvementWatts: number | null;
}

export interface RideFacts extends FactsBase {
  kind: "ride";
  ride: {
    id: string;
    name: string;
    startClock: string;
    minutes: number | null;
    movingMinutes: number | null;
    averagePower: number | null;
    normalizedPower: number | null;
    intensityFactor: number | null;
    load: number | null;
    averageHeartRate: number | null;
    maxHeartRate: number | null;
    workKilojoules: number | null;
    ftp: number | null;
  };
  /** null: a free ride, or the workout it was played from is no longer known. */
  plan: {
    workoutName: string;
    /**
     * Present, and true, only when the rider chose the easier version of the planned session at the start (spec §11.5): the plan below is
     * that version, and the choice is itself a fact about how the rider felt that day. Absent otherwise.
     */
    easierVersionRidden?: true;
    plannedMinutes: number | null;
    plannedLoad: number | null;
    plannedIntensityFactor: number | null;
    /** The minutes of the workout that were ridden (without pauses); null when the ride recorded no step. */
    riddenMinutes: number | null;
    /** The minutes ridden as a share of the planned length; null when it cannot be told. */
    completionPercent: number | null;
    /** The ride's load against the planned load, in percent above (positive) or below (negative); null when it cannot be told. */
    loadVsPlanPercent: number | null;
    stepsPlanned: number | null;
    stepsCompleted: number | null;
    /** Over the steps with a target: the planned average power, the ridden one and the difference in percent. */
    targetWatts: number | null;
    riddenWatts: number | null;
    deltaPercent: number | null;
    /** A step is on target within this many percent of its planned power. */
    onTargetTolerancePercent: number | null;
    /** How many steps with a target were compared, and how they came out. */
    stepsCompared: number | null;
    stepsOnTarget: number | null;
    stepsAboveTarget: number | null;
    stepsBelowTarget: number | null;
    steps: StepFact[];
    /** Why the ride could not be compared step by step, when it could not. */
    comparisonProblem: string | null;
  } | null;
  efforts: EffortFact[];
  drift: { percent: number } | null;
  wPrime: {
    capacityKj: number | null;
    lowestKj: number | null;
    lowestPercentOfCapacity: number | null;
    atMinute: number | null;
    low: boolean;
  } | null;
  form: FormFact | null;
}

export function effortLabel(durationSec: number): string {
  return EFFORT_LABEL[durationSec] ?? `${durationSec} s`;
}

function classify(steps: readonly StepCompliance[]): {
  on: number;
  above: number;
  below: number;
} {
  let on = 0;
  let above = 0;
  let below = 0;
  for (const step of steps) {
    if (step.deltaPercent === null || step.actualSeconds < 60) continue;
    if (Math.abs(step.deltaPercent) <= ON_TARGET_PERCENT) on++;
    else if (step.deltaPercent > 0) above++;
    else below++;
  }
  return { on, above, below };
}

export function buildRideFacts(input: RideInput): RideFacts {
  const q = new Quotes();
  const name = input.name;
  const ride = {
    id: input.rideId,
    name,
    startClock: input.startClock,
    minutes: q.minutes("ride: length", input.durationSeconds),
    movingMinutes: q.minutes("ride: moving time", input.movingSeconds),
    averagePower: q.n("ride: average power", input.averagePower, "W"),
    normalizedPower: q.n("ride: normalised power", input.normalizedPower, "W"),
    intensityFactor: q.n("ride: intensity factor", input.intensityFactor, "IF", 2),
    load: q.n("ride: load (training stress)", input.load, "load"),
    averageHeartRate: q.n("ride: average heart rate", input.averageHeartRate, "bpm"),
    maxHeartRate: q.n("ride: maximum heart rate", input.maxHeartRate, "bpm"),
    workKilojoules: q.n("ride: work", input.workKilojoules, "kJ"),
    ftp: q.n("FTP when ridden", input.ftp, "W"),
  };

  let plan: RideFacts["plan"] = null;
  if (input.plan !== null) {
    const { workout, compliance } = input.plan;
    const stats = plannedStats(workout);
    const label = "plan";
    const compared = "problem" in compliance ? null : compliance;
    const riddenSeconds = compared === null ? input.movingSeconds : compared.actualSeconds;
    const completion = stats.seconds > 0 ? (riddenSeconds / stats.seconds) * 100 : null;
    const plannedLoadValue = stats.load > 0 ? stats.load : null;
    const loadVsPlan =
      plannedLoadValue === null || input.load === null
        ? null
        : ((input.load - plannedLoadValue) / plannedLoadValue) * 100;
    const targeted =
      compared === null
        ? []
        : compared.steps.filter((s) => s.type !== "free" && s.plannedWatts !== null);
    const classes = classify(targeted);
    const stepFacts: StepFact[] = targeted.slice(0, MAX_STEPS).map((s) => ({
      step: q.n(`plan step number`, s.step, "step") ?? s.step,
      label: s.label,
      minutes: q.minutes(`plan step ${s.step}: planned length`, s.plannedSeconds),
      plannedWatts: q.n(`plan step ${s.step}: planned power`, s.plannedWatts, "W"),
      actualWatts: q.n(`plan step ${s.step}: ridden power`, s.actualWatts, "W"),
      deltaPercent: q.n(`plan step ${s.step}: ridden against planned`, s.deltaPercent, "%"),
    }));
    plan = {
      workoutName: workout.name,
      ...(input.plan.easierVersionRidden === true ? { easierVersionRidden: true as const } : {}),
      plannedMinutes: q.minutes(`${label}: planned length`, stats.seconds),
      plannedLoad: q.n(`${label}: planned load`, plannedLoadValue, "load"),
      plannedIntensityFactor: q.n(
        `${label}: planned intensity factor`,
        stats.intensityFactor,
        "IF",
        2,
      ),
      riddenMinutes: q.minutes(`${label}: minutes of the workout ridden`, riddenSeconds),
      completionPercent: q.n(`${label}: share of the planned length ridden`, completion, "%"),
      loadVsPlanPercent: q.n(`${label}: ride load against planned load`, loadVsPlan, "%"),
      stepsPlanned: q.n(`${label}: steps planned`, compared?.stepsPlanned, "steps"),
      stepsCompleted: q.n(`${label}: steps completed in full`, compared?.stepsCompleted, "steps"),
      targetWatts: q.n(
        `${label}: planned average power over the steps with a target`,
        compared?.targetSteps.plannedWatts,
        "W",
      ),
      riddenWatts: q.n(
        `${label}: ridden average power over those steps`,
        compared?.targetSteps.actualWatts,
        "W",
      ),
      deltaPercent: q.n(
        `${label}: ridden against planned power over those steps`,
        compared?.targetSteps.deltaPercent,
        "%",
      ),
      onTargetTolerancePercent: q.n(`${label}: a step is on target within`, ON_TARGET_PERCENT, "%"),
      stepsCompared:
        compared === null
          ? null
          : q.n(
              `${label}: steps compared with their target`,
              classes.on + classes.above + classes.below,
              "steps",
            ),
      stepsOnTarget:
        compared === null
          ? null
          : q.n(`${label}: steps within ${ON_TARGET_PERCENT}% of target`, classes.on, "steps"),
      stepsAboveTarget:
        compared === null
          ? null
          : q.n(
              `${label}: steps more than ${ON_TARGET_PERCENT}% above target`,
              classes.above,
              "steps",
            ),
      stepsBelowTarget:
        compared === null
          ? null
          : q.n(
              `${label}: steps more than ${ON_TARGET_PERCENT}% below target`,
              classes.below,
              "steps",
            ),
      steps: stepFacts,
      comparisonProblem: "problem" in compliance ? compliance.problem : null,
    };
  }

  const efforts: EffortFact[] = [];
  for (const duration of EFFORT_DURATIONS) {
    const point = input.curve.find((p) => p.durationSec === duration);
    if (point === undefined) continue;
    const prior = input.priorBests.find((p) => p.durationSec === duration);
    const label = effortLabel(duration);
    const previous = prior?.power ?? null;
    const power = q.n(`best ${label} power in this ride`, point.power, "W") ?? point.power;
    const newBest = previous !== null && point.power > previous;
    efforts.push({
      duration: label,
      durationSec: q.n(`${label} effort: duration in seconds`, duration, "s") ?? duration,
      power,
      previousBest: q.n(`best ${label} power in earlier rides`, previous, "W"),
      previousBestDate: previous === null ? null : (prior?.date ?? null),
      priorRides: q.n(`earlier rides with a best ${label} power`, prior?.rides ?? 0, "rides") ?? 0,
      newBest,
      improvementWatts:
        newBest && previous !== null
          ? q.n(`${label} best: improvement`, point.power - previous, "W")
          : null,
    });
  }

  const driftPercent = input.drift?.available === true ? input.drift.driftPercent : null;
  const drift =
    driftPercent === null
      ? null
      : { percent: q.n("aerobic drift over the steady stretch", driftPercent, "%", 1) ?? 0 };

  let wPrime: RideFacts["wPrime"] = null;
  if (input.wPrime !== null && input.wPrime.capacityKj > 0) {
    const percent = (input.wPrime.lowestKj / input.wPrime.capacityKj) * 100;
    const low = percent < W_PRIME_LOW_PERCENT;
    wPrime = {
      capacityKj: q.n("W' (the anaerobic reserve)", input.wPrime.capacityKj, "kJ", 1),
      lowestKj: q.n("W' balance, lowest point", input.wPrime.lowestKj, "kJ", 1),
      lowestPercentOfCapacity: q.n("W' balance, lowest point as a share of W'", percent, "%"),
      atMinute: q.n("W' balance, minute of the lowest point", input.wPrime.atSec / 60, "min"),
      low,
    };
  }

  return {
    schema: 1,
    kind: "ride",
    date: input.date,
    weekday: weekdayOf(input.date),
    ride,
    plan,
    efforts,
    drift,
    wPrime,
    form: formFact(q, input.form),
    quotable: q.list,
  };
}

// ---- The week -------------------------------------------------------------------------------------

export interface WeekInput {
  /** The Monday. */
  from: string;
  /** The last day counted: the Sunday, or the weekly summary's day. */
  to: string;
  plannedSessions: number;
  doneSessions: number;
  skippedSessions: number;
  missed: { name: string; date: string }[];
  /** Rides in the week and how long they were moving. */
  rides: number;
  movingSeconds: number;
  /** Load of the week (rides plus the watch's activities), and its two parts. */
  load: number;
  rideLoad: number;
  otherLoad: number;
  /** The previous week up to the same weekday; null with no ride the week before. */
  previousLoad: number | null;
  plannedLoad: number | null;
  form: FormInput | null;
  /** Fitness on the Monday's eve (the end of the week before). */
  ctlAtStart: number | null;
  recoveryDays: { ready: number; caution: number; rest: number; noVerdict: number };
  wellness: {
    metric: RecoveryMetric;
    name: string;
    weekAverage: number | null;
    previousWeekAverage: number | null;
    unit: string;
    daysOutsideUsualRange: number;
    daysWithReading: number;
    position: "above" | "below" | null;
  }[];
  newBests: { durationSec: number; power: number; previous: number; date: string }[];
  nextWeek: { sessions: number; plannedLoad: number | null };
}

export interface WeekFacts extends FactsBase {
  kind: "week";
  week: { from: string; to: string };
  sessions: {
    planned: number | null;
    done: number | null;
    skipped: number | null;
    missed: { name: string; date: string }[];
    rides: number | null;
    minutes: number | null;
  };
  load: {
    week: number | null;
    rides: number | null;
    otherActivities: number | null;
    previousWeek: number | null;
    changeVsPreviousWeek: number | null;
    changePercent: number | null;
    planned: number | null;
  };
  form: FormFact | null;
  fitnessChangeOverWeek: number | null;
  recoveryDays: {
    ready: number | null;
    caution: number | null;
    rest: number | null;
    noVerdict: number | null;
  };
  wellness: {
    name: string;
    unit: string;
    weekAverage: number | null;
    previousWeekAverage: number | null;
    daysOutsideUsualRange: number | null;
    daysWithReading: number | null;
    position: "above" | "below" | null;
  }[];
  newBests: { duration: string; power: number | null; previous: number | null; date: string }[];
  nextWeek: { sessions: number | null; plannedLoad: number | null };
}

export function buildWeekFacts(input: WeekInput): WeekFacts {
  const q = new Quotes();
  const previous = input.previousLoad;
  const changePercent =
    previous === null || previous <= 0 ? null : ((input.load - previous) / previous) * 100;
  const form = formFact(q, input.form);
  const fitnessAtEnd = form?.fitness ?? null;
  const startFitness = input.ctlAtStart === null ? null : roundTo(input.ctlAtStart, 0);
  const sessions = {
    planned: q.n("sessions planned this week", input.plannedSessions, "sessions"),
    done: q.n("planned sessions done", input.doneSessions, "sessions"),
    skipped: q.n("sessions skipped by the rider", input.skippedSessions, "sessions"),
    missed: input.missed.map((m) => ({ name: m.name, date: m.date })),
    rides: q.n("rides this week", input.rides, "rides"),
    minutes: q.minutes("time ridden this week", input.movingSeconds),
  };
  if (input.missed.length > 0) q.n("sessions missed", input.missed.length, "sessions");
  const load = {
    week: q.n("load this week", input.load, "load"),
    rides: q.n("load this week, rides", input.rideLoad, "load"),
    otherActivities: q.n(
      "load this week, other activities from the watch",
      input.otherLoad,
      "load",
    ),
    previousWeek: q.n("load the week before, up to the same weekday", previous, "load"),
    changeVsPreviousWeek:
      previous === null
        ? null
        : q.n(
            "change in load against the week before",
            roundTo(input.load, 0) - roundTo(previous, 0),
            "load",
          ),
    changePercent: q.n("change in load against the week before, percent", changePercent, "%"),
    planned: q.n("load planned for the days of this week", input.plannedLoad, "load"),
  };
  const wellness = input.wellness.map((entry) => ({
    name: entry.name,
    unit: entry.unit,
    weekAverage: q.n(
      `${entry.name}: average this week`,
      entry.weekAverage,
      entry.unit,
      entry.unit === "h" || entry.unit === "/min" || entry.unit === "%" ? 1 : 0,
    ),
    previousWeekAverage: q.n(
      `${entry.name}: average the week before`,
      entry.previousWeekAverage,
      entry.unit,
      entry.unit === "h" || entry.unit === "/min" || entry.unit === "%" ? 1 : 0,
    ),
    daysOutsideUsualRange: q.n(
      `${entry.name}: days outside the usual range`,
      entry.daysOutsideUsualRange,
      "days",
    ),
    daysWithReading: q.n(`${entry.name}: days with a reading`, entry.daysWithReading, "days"),
    position: entry.position,
  }));
  return {
    schema: 1,
    kind: "week",
    date: input.from,
    weekday: weekdayOf(input.from),
    week: { from: input.from, to: input.to },
    sessions,
    load,
    form,
    fitnessChangeOverWeek:
      fitnessAtEnd === null || startFitness === null
        ? null
        : q.n("change in fitness over the week", fitnessAtEnd - startFitness, "load"),
    recoveryDays: {
      ready: q.n("mornings rated ready", input.recoveryDays.ready, "days"),
      caution: q.n("mornings rated caution", input.recoveryDays.caution, "days"),
      rest: q.n("mornings rated rest", input.recoveryDays.rest, "days"),
      noVerdict: q.n("mornings with no recovery rating", input.recoveryDays.noVerdict, "days"),
    },
    wellness,
    newBests: input.newBests.map((best) => ({
      duration: effortLabel(best.durationSec),
      power: q.n(`new best ${effortLabel(best.durationSec)} power`, best.power, "W"),
      previous: q.n(`previous best ${effortLabel(best.durationSec)} power`, best.previous, "W"),
      date: best.date,
    })),
    nextWeek: {
      sessions: q.n("sessions planned for the next 7 days", input.nextWeek.sessions, "sessions"),
      plannedLoad: q.n("load planned for the next 7 days", input.nextWeek.plannedLoad, "load"),
    },
    quotable: q.list,
  };
}

// ---- Deviation alerts -----------------------------------------------------------------------------

export interface DeviationInput {
  date: string;
  /** The days in a row that make an alert. */
  thresholdDays: number;
  /** The metrics that crossed it, strongest first. */
  signals: { fact: RecoveryFact; reading: string; trainingHint: string }[];
  recoveryState: RecoveryState | null;
  planned: PlannedInput[];
  ftp: number | null;
}

export interface DeviationFacts extends FactsBase {
  kind: "deviation";
  thresholdDays: number | null;
  signals: (SignalFact & { reading: string; trainingHint: string })[];
  recoveryState: RecoveryState | null;
  today: { planned: PlannedFact[] };
}

export function buildDeviationFacts(input: DeviationInput): DeviationFacts {
  const q = new Quotes();
  const signals = input.signals.map((s) => ({
    ...signalFact(q, s.fact),
    reading: s.reading,
    trainingHint: s.trainingHint,
  }));
  return {
    schema: 1,
    kind: "deviation",
    date: input.date,
    weekday: weekdayOf(input.date),
    thresholdDays: q.n("days in a row that make an alert", input.thresholdDays, "days"),
    signals,
    recoveryState: input.recoveryState,
    today: { planned: input.planned.map((p) => plannedFact(q, p, input.ftp)) },
    quotable: q.list,
  };
}

// ---- Plan drift -----------------------------------------------------------------------------------

export type DriftReason = "missed" | "load-above" | "load-below";

export interface DriftInput {
  date: string;
  windowDays: number;
  missed: { name: string; date: string }[];
  /** Ride load of the window against the planned load of the sessions planned in it; null when it cannot be told. */
  load: { planned: number; ridden: number } | null;
  reasons: DriftReason[];
  thresholds: { missedSessions: number; loadAbovePercent: number; loadBelowPercent: number };
  nextWeek: { sessions: number; plannedLoad: number | null };
}

export interface DriftFacts extends FactsBase {
  kind: "drift";
  windowDays: number | null;
  reasons: DriftReason[];
  missed: { count: number | null; sessions: { name: string; date: string }[] } | null;
  load: { planned: number | null; ridden: number | null; differencePercent: number | null } | null;
  thresholds: {
    missedSessions: number | null;
    loadAbovePercent: number | null;
    loadBelowPercent: number | null;
  };
  nextWeek: { sessions: number | null; plannedLoad: number | null };
}

export function buildDriftFacts(input: DriftInput): DriftFacts {
  const q = new Quotes();
  const windowDays = q.n("days looked at", input.windowDays, "days");
  const missed =
    input.missed.length === 0
      ? null
      : {
          count: q.n("planned sessions missed in the window", input.missed.length, "sessions"),
          sessions: input.missed.map((m) => ({ name: m.name, date: m.date })),
        };
  const load =
    input.load === null
      ? null
      : {
          planned: q.n("load planned for the window", input.load.planned, "load"),
          ridden: q.n("load ridden in the window", input.load.ridden, "load"),
          differencePercent:
            input.load.planned > 0
              ? q.n(
                  "load ridden against planned, in percent (negative: below the plan)",
                  ((input.load.ridden - input.load.planned) / input.load.planned) * 100,
                  "%",
                )
              : null,
        };
  return {
    schema: 1,
    kind: "drift",
    date: input.date,
    weekday: weekdayOf(input.date),
    windowDays,
    reasons: input.reasons,
    missed,
    load,
    thresholds: {
      missedSessions: q.n(
        "missed sessions that make an alert",
        input.thresholds.missedSessions,
        "sessions",
      ),
      loadAbovePercent: q.n(
        "load above plan that makes an alert",
        input.thresholds.loadAbovePercent,
        "%",
      ),
      loadBelowPercent: q.n(
        "load below plan that makes an alert",
        input.thresholds.loadBelowPercent,
        "%",
      ),
    },
    nextWeek: {
      sessions: q.n("sessions planned for the next 7 days", input.nextWeek.sessions, "sessions"),
      plannedLoad: q.n("load planned for the next 7 days", input.nextWeek.plannedLoad, "load"),
    },
    quotable: q.list,
  };
}

export type Facts = MorningFacts | RideFacts | WeekFacts | DeviationFacts | DriftFacts;

/** The date a week's note is about, `from` shifted for display; the Monday of a week. */
export const weekEnd = (monday: string): string => addDaysToIsoDate(monday, 6);
