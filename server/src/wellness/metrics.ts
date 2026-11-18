import type { RecoveryUnit, WellnessMetric, WellnessSourceId } from "@overdrive/contracts";

/**
 * The daily wellness metrics (spec §6.4, ADR 0023): what each one is called, in what unit, which way
 * is the adverse one, and how small a spread is believable. Everything the baselines and the recovery
 * rules know about a metric is in this table, so a threshold is changed in one place.
 */

/** What is kept per day and source: the eight metrics with a baseline and five that only travel along. */
export type StoredMetric =
  | WellnessMetric
  | "hrvSdnn"
  | "steps"
  | "sleepStartMs"
  | "sleepEndMs"
  | "weightKg";

export const STORED_METRICS: readonly StoredMetric[] = [
  "restingHr",
  "sleepSeconds",
  "bedtimeMinutes",
  "wakeMinutes",
  "respiratoryRate",
  "spo2",
  "sleepingHr",
  "hrvRmssd",
  "hrvSdnn",
  "steps",
  "sleepStartMs",
  "sleepEndMs",
  "weightKg",
];

/** A weighing outside this is a typing slip or a broken scale, not a body weight: ignored when the daily figure is worked out. */
export const PLAUSIBLE_WEIGHT_KG: readonly [number, number] = [20, 400];

/** The weight trend is the mean of the daily weights of this many days, the day itself included (spec §12.5). */
export const WEIGHT_AVERAGE_DAYS = 7;

export const SOURCE_IDS: readonly WellnessSourceId[] = ["healthconnect", "intervals"];

/** Which direction of change from the rider's own baseline is the adverse one for recovery; null: not scored. */
export type Adverse = "high" | "low" | null;

export interface MetricSpec {
  key: WellnessMetric;
  /** The metric in words, as a fact is labelled. */
  label: string;
  unit: RecoveryUnit;
  adverse: Adverse;
  /**
   * The smallest spread (one standard deviation) believed, in the unit of the metric (on the natural-log
   * scale for {@link MetricSpec.ln}). A rider whose resting heart rate never moves by more than a beat
   * would otherwise be "outside the usual range" by one beat. For sleep timing it is an hour, so a shift
   * counts only beyond an hour.
   */
  minSpread: number;
  /** Compared on the natural-log scale (heart-rate variability is log-normal; Plews 2013). */
  ln: boolean;
  /** Decimals a value and its range are shown with. */
  digits: number;
  /** A reading outside this is a sensor glitch, not a measurement: ignored when the daily figure is worked out. */
  plausible: readonly [number, number];
  /** The order facts fill in when several are equally decisive: lower first. */
  priority: number;
}

export const METRIC_SPECS: Readonly<Record<WellnessMetric, MetricSpec>> = {
  restingHr: {
    key: "restingHr",
    label: "Resting heart rate",
    unit: "bpm",
    adverse: "high",
    minSpread: 1.5,
    ln: false,
    digits: 1,
    plausible: [25, 200],
    priority: 1,
  },
  sleepSeconds: {
    key: "sleepSeconds",
    label: "Sleep",
    unit: "s",
    adverse: "low",
    minSpread: 1200,
    ln: false,
    digits: 0,
    plausible: [600, 64800],
    priority: 2,
  },
  bedtimeMinutes: {
    key: "bedtimeMinutes",
    label: "Sleep timing",
    unit: "min",
    adverse: "high",
    minSpread: 60,
    ln: false,
    digits: 0,
    plausible: [-720, 720],
    priority: 6,
  },
  wakeMinutes: {
    key: "wakeMinutes",
    label: "Wake-up time",
    unit: "min",
    adverse: null,
    minSpread: 30,
    ln: false,
    digits: 0,
    plausible: [0, 1440],
    priority: 8,
  },
  respiratoryRate: {
    key: "respiratoryRate",
    label: "Respiratory rate",
    unit: "/min",
    adverse: "high",
    minSpread: 0.3,
    ln: false,
    digits: 2,
    plausible: [4, 40],
    priority: 3,
  },
  spo2: {
    key: "spo2",
    label: "Oxygen saturation",
    unit: "%",
    adverse: "low",
    minSpread: 0.5,
    ln: false,
    digits: 2,
    plausible: [70, 100],
    priority: 4,
  },
  sleepingHr: {
    key: "sleepingHr",
    label: "Heart rate in sleep",
    unit: "bpm",
    adverse: "high",
    minSpread: 1.5,
    ln: false,
    digits: 1,
    plausible: [25, 180],
    priority: 5,
  },
  hrvRmssd: {
    key: "hrvRmssd",
    label: "Heart rate variability",
    unit: "ms",
    adverse: "low",
    minSpread: 0.08,
    ln: true,
    digits: 1,
    plausible: [3, 300],
    priority: 7,
  },
};

/** The metrics in the order the companion shows them. */
export const METRIC_KEYS: readonly WellnessMetric[] = [
  "restingHr",
  "sleepSeconds",
  "bedtimeMinutes",
  "wakeMinutes",
  "respiratoryRate",
  "spo2",
  "sleepingHr",
  "hrvRmssd",
];

export function isPlausible(metric: WellnessMetric, value: number): boolean {
  const [low, high] = METRIC_SPECS[metric].plausible;
  return Number.isFinite(value) && value >= low && value <= high;
}

/** `value` rounded to `digits` decimals. */
export function roundTo(value: number, digits: number): number {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

/** The knobs of the baselines and the recovery rules (spec §6.4); the rules themselves are in `recovery.ts`. */
export interface WellnessSettings {
  /** The window a baseline is taken over, in days before the day it is for (spec: 28 to 60). */
  baselineDays: number;
  /** Readings a metric needs inside the window before it has a baseline, and nights before any verdict. */
  nightsNeeded: number;
}

export const DEFAULT_WELLNESS_SETTINGS: WellnessSettings = { baselineDays: 42, nightsNeeded: 14 };
