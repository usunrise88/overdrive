import type { RecoveryFact, RecoveryResponse, Workout } from "@overdrive/contracts";
import type {
  DeviationInput,
  DriftInput,
  MorningInput,
  PriorBest,
  RideInput,
  WeekInput,
} from "../../src/insights/facts.ts";
import { rideCompliance } from "../../src/ride-stats.ts";
import { constant, syntheticRide } from "./synthetic-rides.ts";

/**
 * Synthetic inputs for the facts builders (ADR 0025): every number here is invented. The names say the situation; the golden files
 * under `test/fixtures/insights-facts-*.json` are what the builders make of them.
 */

export const TODAY = "2026-11-18";
export const FTP = 250;

export const sweetSpot: Workout = {
  schema: 1,
  id: "icu-1001",
  name: "Sweet spot 3x10",
  steps: [
    { type: "steady", duration: 600, target: 0.6, label: "Warm-up" },
    {
      type: "repeat",
      count: 3,
      steps: [
        { type: "steady", duration: 600, target: 0.88, label: "Sweet spot" },
        { type: "steady", duration: 300, target: 0.5, label: "Recover" },
      ],
    },
    { type: "free", duration: 300, label: "Cool-down" },
  ],
};

export const rampTest: Workout = {
  schema: 1,
  id: "icu-1002",
  name: "Ramp 5 min",
  steps: [{ type: "ramp", duration: 300, from: 0.5, to: 0.9 }],
};

export const fact = (
  metric: RecoveryFact["metric"],
  extra: Partial<RecoveryFact>,
): RecoveryFact => {
  const base: Record<RecoveryFact["metric"], RecoveryFact> = {
    restingHr: {
      metric: "restingHr",
      label: "Resting heart rate",
      value: 52,
      unit: "bpm",
      baselineLow: 50,
      baselineHigh: 54,
      deviation: "inside",
      streak: 0,
    },
    sleepSeconds: {
      metric: "sleepSeconds",
      label: "Sleep",
      value: 27000,
      unit: "s",
      baselineLow: 25200,
      baselineHigh: 28800,
      deviation: "inside",
      streak: 0,
    },
    bedtimeMinutes: {
      metric: "bedtimeMinutes",
      label: "Sleep timing",
      value: -45,
      unit: "min",
      baselineLow: -75,
      baselineHigh: -15,
      deviation: "inside",
      streak: 0,
    },
    respiratoryRate: {
      metric: "respiratoryRate",
      label: "Respiratory rate",
      value: 14.2,
      unit: "/min",
      baselineLow: 13.8,
      baselineHigh: 14.6,
      deviation: "inside",
      streak: 0,
    },
    spo2: {
      metric: "spo2",
      label: "Oxygen saturation",
      value: 96.1,
      unit: "%",
      baselineLow: 95.2,
      baselineHigh: 97,
      deviation: "inside",
      streak: 0,
    },
    sleepingHr: {
      metric: "sleepingHr",
      label: "Heart rate in sleep",
      value: 48,
      unit: "bpm",
      baselineLow: 46,
      baselineHigh: 50,
      deviation: "inside",
      streak: 0,
    },
    hrvRmssd: {
      metric: "hrvRmssd",
      label: "Heart rate variability",
      value: 62,
      unit: "ms",
      baselineLow: 55,
      baselineHigh: 71,
      deviation: "inside",
      streak: 0,
    },
  };
  return { ...base[metric], ...extra };
};

const recovery = (extra: Partial<RecoveryResponse>): RecoveryResponse => ({
  date: TODAY,
  state: "ready",
  reason: "All four signals are in your usual range.",
  guidance: "Train as planned.",
  rule: "OK",
  nightsLogged: 58,
  nightsNeeded: 14,
  generatedAt: "2026-11-18T07:00:00Z",
  stale: false,
  facts: [
    fact("restingHr", {}),
    fact("sleepSeconds", {}),
    fact("respiratoryRate", {}),
    fact("sleepingHr", {}),
  ],
  ...extra,
});

export const RECOVERY = {
  ready: recovery({}),
  caution: recovery({
    state: "caution",
    reason: "Resting heart rate is above your usual range for the second morning.",
    guidance: "Train, but keep the hard parts short.",
    rule: "C3",
    facts: [
      fact("restingHr", { value: 58, deviation: "above", streak: 2 }),
      fact("sleepSeconds", {}),
      fact("respiratoryRate", {}),
      fact("sleepingHr", {}),
    ],
  }),
  rest: recovery({
    state: "rest",
    reason:
      "Three signals are well outside your usual range: sleep, resting heart rate and respiratory rate.",
    guidance: "Keep today easy or take it off.",
    rule: "R1",
    facts: [
      fact("sleepSeconds", { value: 20400, deviation: "below", streak: 1 }),
      fact("restingHr", { value: 60, deviation: "above", streak: 1 }),
      fact("respiratoryRate", { value: 15.9, deviation: "above", streak: 1 }),
      fact("bedtimeMinutes", { value: 40, deviation: "above", streak: 1 }),
    ],
  }),
  learning: recovery({
    state: "learning",
    reason: "Overdrive rates recovery after 14 nights with the watch. 9 nights so far.",
    guidance: null,
    rule: null,
    nightsLogged: 9,
    facts: [fact("restingHr", { baselineLow: null, baselineHigh: null, deviation: null })],
  }),
  noData: recovery({
    state: "noData",
    reason:
      "No reading from last night. Wear the watch to sleep, then open Overdrive in the morning.",
    guidance: null,
    rule: null,
    facts: [],
  }),
  yesterday: recovery({ date: "2026-11-17" }),
} as const;

export const form = {
  date: TODAY,
  ctl: 61.7,
  atl: 70.3,
  tsb: -8.6,
  ctlWeekAgo: 58.9,
};

export function morningInput(
  state: keyof typeof RECOVERY,
  options: { planned?: "sweet-spot" | "done" | "none"; withForm?: boolean } = {},
): MorningInput {
  const planned = options.planned ?? "sweet-spot";
  return {
    date: TODAY,
    recovery: RECOVERY[state],
    planned:
      planned === "none"
        ? []
        : [{ name: sweetSpot.name, workout: sweetSpot, done: planned === "done" }],
    ftp: FTP,
    form: options.withForm === false ? null : form,
    lastRide: {
      name: "Endurance",
      date: "2026-11-16",
      durationSeconds: 3_900,
      load: 54.3,
      averagePower: 168.4,
    },
  };
}

/** A ride of the sweet-spot workout: `seconds` long, the 88% steps ridden at `watts`. */
export function rideInput(
  options: {
    seconds?: number;
    watts?: number;
    free?: boolean;
    id?: string;
    withRecords?: boolean;
    drift?: boolean;
    wPrimeLow?: boolean;
  } = {},
): RideInput {
  const seconds = options.seconds ?? 3_600;
  const watts = options.watts ?? 220;
  const stepOf = (s: number): number | null => {
    // Flattened sweet-spot workout: warm-up 600, then 3 x (600 + 300), then a free cool-down of 300.
    if (s <= 600) return 0;
    const t = s - 600;
    if (t <= 2_700) {
      const within = (t - 1) % 900;
      return 1 + Math.floor((t - 1) / 900) * 2 + (within < 600 ? 0 : 1);
    }
    return 7;
  };
  const record = syntheticRide({
    id: options.id ?? "ride-1794984000000",
    startEpochMillis: 1_794_984_000_000,
    seconds,
    power: (s) => {
      const step = stepOf(s);
      if (step === 0) return 150;
      if (step === null || step === 7) return 100;
      return step % 2 === 1 ? watts : 125;
    },
    step: options.free === true ? () => null : stepOf,
    workoutId: options.free === true ? null : "icu-1001",
    heartRate: () => 140,
  });
  const compliance = options.free === true ? null : rideCompliance(record, sweetSpot);
  const prior: PriorBest[] = [
    { durationSec: 5, power: 640, date: "2026-10-20", rides: 9 },
    { durationSec: 60, power: 410, date: "2026-10-27", rides: 9 },
    { durationSec: 300, power: 290, date: "2026-11-03", rides: 9 },
    { durationSec: 1200, power: 235, date: "2026-11-03", rides: 8 },
    { durationSec: 3600, power: null, date: null, rides: 0 },
  ];
  const curve =
    options.withRecords === true
      ? [
          { durationSec: 5, power: 655 },
          { durationSec: 60, power: 405 },
          { durationSec: 300, power: 301 },
          { durationSec: 1200, power: 241 },
        ]
      : [
          { durationSec: 5, power: 300 },
          { durationSec: 60, power: 260 },
          { durationSec: 300, power: 235 },
          { durationSec: 1200, power: 214 },
        ];
  return {
    rideId: record.ride.id,
    name: options.free === true ? "Free ride" : sweetSpot.name,
    date: "2026-11-17",
    startClock: "18:05",
    durationSeconds: seconds,
    movingSeconds: seconds,
    averagePower: record.summary.averagePower ?? null,
    normalizedPower: record.summary.normalizedPower ?? null,
    intensityFactor: record.summary.intensityFactor ?? null,
    load: record.summary.trainingStressScore ?? null,
    averageHeartRate: 140,
    maxHeartRate: 158,
    workKilojoules: record.summary.workKilojoules,
    ftp: FTP,
    plan: compliance === null ? null : { workout: sweetSpot, compliance },
    curve,
    priorBests: prior,
    drift:
      options.drift === true
        ? {
            available: true,
            reason: null,
            driftPercent: 3.46,
            efFirst: 1.21,
            efSecond: 1.17,
            startSec: 0,
            endSec: seconds,
          }
        : null,
    wPrime:
      options.wPrimeLow === true
        ? { capacityKj: 20, lowestKj: 4.2, atSec: 1_830 }
        : { capacityKj: 20, lowestKj: 14.6, atSec: 1_830 },
    form: { date: "2026-11-17", ctl: 60.4, atl: 69.8, tsb: -9.4, ctlWeekAgo: 57.2 },
  };
}

export function weekInput(options: { quiet?: boolean } = {}): WeekInput {
  return {
    from: "2026-11-09",
    to: "2026-11-15",
    plannedSessions: 4,
    doneSessions: 3,
    skippedSessions: 0,
    missed: [{ name: "Endurance 90", date: "2026-11-14" }],
    rides: options.quiet === true ? 0 : 4,
    movingSeconds: 17_940,
    load: 212.4,
    rideLoad: 212.4,
    otherLoad: 0,
    previousLoad: 188.9,
    plannedLoad: 240,
    form: { date: "2026-11-15", ctl: 59.2, atl: 66.8, tsb: -7.6, ctlWeekAgo: 56.1 },
    ctlAtStart: 56.1,
    recoveryDays: { ready: 4, caution: 2, rest: 0, noVerdict: 1 },
    wellness: [
      {
        metric: "restingHr",
        name: "resting heart rate",
        weekAverage: 53.4,
        previousWeekAverage: 52.1,
        unit: "bpm",
        daysOutsideUsualRange: 2,
        daysWithReading: 7,
        position: "above",
      },
      {
        metric: "sleepSeconds",
        name: "sleep",
        weekAverage: 7.1,
        previousWeekAverage: 7.4,
        unit: "h",
        daysOutsideUsualRange: 0,
        daysWithReading: 7,
        position: null,
      },
    ],
    newBests: [{ durationSec: 1200, power: 241, previous: 235, date: "2026-11-12" }],
    nextWeek: { sessions: 4, plannedLoad: 236.5 },
  };
}

export function deviationInput(): DeviationInput {
  return {
    date: TODAY,
    thresholdDays: 3,
    signals: [
      {
        fact: fact("restingHr", { value: 58, deviation: "above", streak: 3 }),
        reading:
          "A resting heart rate above your usual range for days in a row usually goes with accumulated fatigue or stress, and hard sessions tend to feel heavier.",
        trainingHint:
          "Keep the next day or two easy and leave the hardest session until it comes back into range.",
      },
      {
        fact: fact("sleepSeconds", { value: 21600, deviation: "below", streak: 4 }),
        reading:
          "Several short nights in a row usually mean recovery is lagging, so hard sessions are less productive and feel harder.",
        trainingHint:
          "Protect sleep first; ride easy until the nights are back to your usual length.",
      },
    ],
    recoveryState: "caution",
    planned: [{ name: sweetSpot.name, workout: sweetSpot, done: false }],
    ftp: FTP,
  };
}

export function driftInput(options: { reasons?: DriftInput["reasons"] } = {}): DriftInput {
  const reasons = options.reasons ?? ["missed", "load-below"];
  return {
    date: TODAY,
    windowDays: 7,
    missed: reasons.includes("missed")
      ? [
          { name: "Sweet spot 3x10", date: "2026-11-12" },
          { name: "Endurance 90", date: "2026-11-14" },
        ]
      : [],
    load: reasons.some((r) => r.startsWith("load")) ? { planned: 240, ridden: 118.6 } : null,
    reasons,
    thresholds: { missedSessions: 2, loadAbovePercent: 30, loadBelowPercent: 40 },
    nextWeek: { sessions: 4, plannedLoad: 236.5 },
  };
}

export { constant };
