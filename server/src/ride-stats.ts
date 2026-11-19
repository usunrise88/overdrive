import {
  DEFAULT_HR_BAND_BPM,
  type LeafStep,
  powerZoneOf,
  type RideRecord,
  type RideSample,
  type Workout,
} from "@overdrive/contracts";

/**
 * What `get_ride` says about a stored ride (spec §8.4), computed here from the record and never by
 * a model (CLAUDE.md). The definitions are the ride app's (`SummaryAccumulator` in core): power
 * statistics are over the seconds that were not paused, normalised power is the fourth-power mean
 * of 30-sample rolling averages of the powers that were measured. A test checks that the
 * numbers agree with the summary the tablet recorded.
 */

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

const mean = (values: number[]): number | null =>
  values.length === 0 ? null : values.reduce((a, b) => a + b, 0) / values.length;

const NP_WINDOW = 30;
/** The best efforts reported, in seconds: 5 s, 1 min, 5 min, 20 min. */
export const BEST_EFFORT_SECONDS = [5, 60, 300, 1200] as const;

export interface PowerStatistics {
  averageWatts: number | null;
  normalizedWatts: number | null;
  maxWatts: number | null;
  minWatts: number | null;
  /** Normalised over average power. */
  variabilityIndex: number | null;
  /** Normalised power over the FTP of the ride. */
  intensityFactor: number | null;
  /** Training stress score, from the ride's own FTP. */
  load: number | null;
  workKilojoules: number;
  /** Seconds in zones 1 to 7 by the ride's FTP and the profile's zone bounds. */
  secondsInZone: number[];
  /** The best average power over each duration, where the ride is long enough; a pause or a gap counts as 0 W. */
  bestEfforts: { seconds: number; watts: number }[];
}

export interface HeartRateStatistics {
  averageBpm: number | null;
  maxBpm: number | null;
  minBpm: number | null;
}

export interface CadenceStatistics {
  /** Over the seconds spent pedalling (above 0 rpm). */
  averageRpm: number | null;
  maxRpm: number | null;
}

export interface RideStatistics {
  durationSeconds: number;
  movingSeconds: number;
  pausedSeconds: number;
  distanceKilometers: number;
  power: PowerStatistics;
  heartRate: HeartRateStatistics;
  cadence: CadenceStatistics;
}

function powerStatistics(record: RideRecord, zoneUpperPercent: readonly number[]): PowerStatistics {
  const { ftp } = record.ride;
  const powers: number[] = [];
  const secondsInZone = new Array<number>(zoneUpperPercent.length + 1).fill(0);
  for (const sample of record.samples) {
    if (sample.paused === true || sample.power === undefined || sample.power === null) continue;
    powers.push(sample.power);
    const zone = powerZoneOf(sample.power, ftp, zoneUpperPercent);
    secondsInZone[zone - 1] = (secondsInZone[zone - 1] ?? 0) + 1;
  }

  // Normalised power over a window of the last 30 measured powers, as the tablet does.
  let windowSum = 0;
  let fourthPowers = 0;
  let windows = 0;
  for (const [index, power] of powers.entries()) {
    windowSum += power;
    if (index >= NP_WINDOW) windowSum -= powers[index - NP_WINDOW] as number;
    if (index >= NP_WINDOW - 1) {
      const average = windowSum / NP_WINDOW;
      fourthPowers += average ** 4;
      windows++;
    }
  }
  const sum = powers.reduce((a, b) => a + b, 0);
  const average = powers.length === 0 ? null : sum / powers.length;
  const normalized = windows === 0 ? null : (fourthPowers / windows) ** 0.25;
  const intensity = normalized === null ? null : normalized / ftp;

  // Best efforts over the whole timeline, with a pause or a missing reading as 0 W.
  const timeline = record.samples.map((s) =>
    s.paused === true || s.power === undefined || s.power === null ? 0 : s.power,
  );
  const prefix = [0];
  for (const watts of timeline) prefix.push((prefix[prefix.length - 1] as number) + watts);
  const bestEfforts: { seconds: number; watts: number }[] = [];
  for (const seconds of BEST_EFFORT_SECONDS) {
    // A ride with no power measured at all has no best efforts, not zeros.
    if (timeline.length < seconds || powers.length === 0) continue;
    let best = 0;
    for (let end = seconds; end <= timeline.length; end++) {
      best = Math.max(best, (prefix[end] as number) - (prefix[end - seconds] as number));
    }
    bestEfforts.push({ seconds, watts: round(best / seconds, 1) });
  }

  return {
    averageWatts: average === null ? null : round(average, 1),
    normalizedWatts: normalized === null ? null : round(normalized, 1),
    maxWatts: powers.length === 0 ? null : Math.max(...powers),
    minWatts: powers.length === 0 ? null : Math.min(...powers),
    variabilityIndex:
      normalized === null || average === null || average === 0
        ? null
        : round(normalized / average, 2),
    intensityFactor: intensity === null ? null : round(intensity, 2),
    load:
      normalized === null || intensity === null
        ? null
        : round(((powers.length * normalized * intensity) / (ftp * 3600)) * 100, 1),
    workKilojoules: round(sum / 1000, 1),
    secondsInZone,
    bestEfforts,
  };
}

/** Power, heart-rate and cadence statistics of a ride, from its samples. */
export function rideStatistics(
  record: RideRecord,
  zoneUpperPercent: readonly number[],
): RideStatistics {
  const moving = record.samples.filter((s) => s.paused !== true);
  const heartRates = moving.flatMap((s) => (s.heartRate == null ? [] : [s.heartRate]));
  const pedalling = moving.flatMap((s) => (s.cadence != null && s.cadence > 0 ? [s.cadence] : []));
  const last = record.samples[record.samples.length - 1];
  const average = mean(heartRates);
  const cadence = mean(pedalling);
  return {
    durationSeconds: record.samples.length,
    movingSeconds: moving.length,
    pausedSeconds: record.samples.length - moving.length,
    distanceKilometers: round((last?.distanceMeters ?? 0) / 1000, 2),
    power: powerStatistics(record, zoneUpperPercent),
    heartRate: {
      averageBpm: average === null ? null : round(average, 1),
      maxBpm: heartRates.length === 0 ? null : Math.max(...heartRates),
      minBpm: heartRates.length === 0 ? null : Math.min(...heartRates),
    },
    cadence: {
      averageRpm: cadence === null ? null : round(cadence, 1),
      maxRpm: pedalling.length === 0 ? null : Math.max(...pedalling),
    },
  };
}

// ---- Series ----------------------------------------------------------------------------------

export interface RideSeries {
  /** Each value is the mean of this many seconds; the first covers seconds 1 to `stepSeconds`. */
  stepSeconds: number;
  /** Whole watts; null where nothing was measured in the whole step. */
  power: (number | null)[];
  heartRate: (number | null)[];
  cadence: (number | null)[];
  /** The workout's target power, null in free steps and without a workout. */
  targetPower: (number | null)[];
}

/** The most points a series has: a longer ride gets a longer step. */
export const MAX_SERIES_POINTS = 1200;

/** Averages the samples in steps of `stepSeconds` (raised if the ride would give more than {@link MAX_SERIES_POINTS} points). */
export function rideSeries(samples: RideSample[], requestedStepSeconds: number): RideSeries {
  const stepSeconds = Math.max(
    requestedStepSeconds,
    Math.ceil(samples.length / MAX_SERIES_POINTS),
    1,
  );
  const series: RideSeries = {
    stepSeconds,
    power: [],
    heartRate: [],
    cadence: [],
    targetPower: [],
  };
  const pick = (chunk: RideSample[], key: "power" | "heartRate" | "cadence" | "targetPower") => {
    const values = chunk.flatMap((s) => (s[key] == null ? [] : [s[key] as number]));
    const average = mean(values);
    return average === null ? null : Math.round(average);
  };
  for (let start = 0; start < samples.length; start += stepSeconds) {
    const chunk = samples.slice(start, start + stepSeconds);
    series.power.push(pick(chunk, "power"));
    series.heartRate.push(pick(chunk, "heartRate"));
    series.cadence.push(pick(chunk, "cadence"));
    series.targetPower.push(pick(chunk, "targetPower"));
  }
  return series;
}

// ---- Compliance with the plan ----------------------------------------------------------------

/** What a heart-rate step (spec §11.2) asks for, as the workout says it. */
export interface FlatHeartRate {
  /** The target in bpm; null when the workout gives it as a fraction of the threshold (which the ride resolved itself). */
  targetBpm: number | null;
  /** The fraction of the threshold heart rate, when that is how the workout says it. */
  targetLthr: number | null;
  /** Half-width of the band in bpm. */
  band: number;
  powerMin: number;
  powerMax: number;
}

/** One step of a workout with its repeats expanded, as the ride app's `flatSteps` makes them. */
export interface FlatStep {
  index: number;
  label: string;
  type: "steady" | "ramp" | "free" | "hr";
  durationSeconds: number;
  /** Fraction of FTP at the start and end of the step; null in a free step and in a heart-rate step. */
  fromFraction: number | null;
  toFraction: number | null;
  /** Set for a heart-rate step: there is no fixed power, the target is a heart rate. */
  heartRate: FlatHeartRate | null;
}

const DEFAULT_LABEL = {
  steady: "Steady",
  ramp: "Ramp",
  free: "Free ride",
  hr: "Heart rate",
} as const;

export function flattenWorkout(workout: Workout): FlatStep[] {
  const flat: FlatStep[] = [];
  const add = (step: LeafStep, outer?: string): void => {
    flat.push({
      index: flat.length,
      label: step.label ?? outer ?? DEFAULT_LABEL[step.type],
      type: step.type,
      durationSeconds: step.duration,
      fromFraction: step.type === "steady" ? step.target : step.type === "ramp" ? step.from : null,
      toFraction: step.type === "steady" ? step.target : step.type === "ramp" ? step.to : null,
      heartRate:
        step.type === "hr"
          ? {
              targetBpm: step.target_bpm ?? null,
              targetLthr: step.target_lthr ?? null,
              band: step.band ?? DEFAULT_HR_BAND_BPM,
              powerMin: step.power_min,
              powerMax: step.power_max,
            }
          : null,
    });
  };
  for (const step of workout.steps) {
    if (step.type === "repeat") {
      for (let i = 0; i < step.count; i++) for (const inner of step.steps) add(inner, step.label);
    } else {
      add(step);
    }
  }
  return flat;
}

/**
 * How a heart-rate step (spec §11.2) went: the time inside the band. A second is judged when the ride was not paused, the
 * controller was in charge (the sample has `targetHeartRate`: not the start ramp) and there was a heart rate; it is inside the
 * band when the heart rate is within `band` bpm of that second's target (the step's, moved by the rider's gear buttons).
 */
export interface HeartRateCompliance {
  /** The target in force: the one most of the judged seconds had (the later on a tie), so the rider's adjustments show; null when none was judged. */
  targetBpm: number | null;
  /** The workout's own target in bpm; null when it is a fraction of the threshold. */
  plannedTargetBpm: number | null;
  band: number;
  powerMin: number;
  powerMax: number;
  /** Seconds judged: the controller was in charge and the strap was heard. */
  judgedSeconds: number;
  /** Of those, the seconds inside the band. */
  secondsInBand: number;
  /** `secondsInBand` over `judgedSeconds` in percent, one decimal; null when nothing was judged. */
  inBandPercent: number | null;
  /** The mean heart rate over the judged seconds. */
  averageHeartRate: number | null;
}

export interface StepCompliance {
  step: number;
  label: string;
  type: "steady" | "ramp" | "free" | "hr";
  plannedSeconds: number;
  /** Seconds the ride spent in this step, not counting pauses. */
  actualSeconds: number;
  /** The planned average power in watts, from the target and the ride's FTP; null in a free step. */
  plannedWatts: number | null;
  actualWatts: number | null;
  deltaWatts: number | null;
  deltaPercent: number | null;
  /** Set for a heart-rate step, which has no planned power: how long the heart rate was inside the band. */
  heartRate: HeartRateCompliance | null;
}

export interface Compliance {
  workout: { id: string; name: string };
  plannedSeconds: number;
  /** Seconds spent in steps of the workout, not counting pauses. */
  actualSeconds: number;
  stepsPlanned: number;
  /** Steps the ride stayed in for their whole planned time. */
  stepsCompleted: number;
  /** Over the steps with a power target: what was planned, and what was ridden in those steps. */
  targetSteps: {
    plannedWatts: number | null;
    actualWatts: number | null;
    deltaPercent: number | null;
  };
  steps: StepCompliance[];
}

function heartRateCompliance(planned: FlatHeartRate, samples: RideSample[]): HeartRateCompliance {
  const judged = samples.filter(
    (s) => s.targetHeartRate != null && s.heartRate != null && s.heartRate > 0,
  );
  // The target the step spent most of its judged time at (the later one on a tie), so the rider's adjustments show.
  const seconds = new Map<number, number>();
  for (const s of judged) {
    const target = s.targetHeartRate as number;
    seconds.set(target, (seconds.get(target) ?? 0) + 1);
  }
  let targetBpm: number | null = null;
  let most = 0;
  for (const target of judged.map((s) => s.targetHeartRate as number)) {
    const count = seconds.get(target) ?? 0;
    if (count >= most) {
      most = count;
      targetBpm = target;
    }
  }
  const inBand = judged.filter(
    (s) => Math.abs((s.heartRate as number) - (s.targetHeartRate as number)) <= planned.band,
  ).length;
  const average = mean(judged.map((s) => s.heartRate as number));
  return {
    targetBpm,
    plannedTargetBpm: planned.targetBpm,
    band: planned.band,
    powerMin: planned.powerMin,
    powerMax: planned.powerMax,
    judgedSeconds: judged.length,
    secondsInBand: inBand,
    inBandPercent: judged.length === 0 ? null : round((inBand / judged.length) * 100, 1),
    averageHeartRate: average === null ? null : round(average, 1),
  };
}

/**
 * The ride against the workout it was played from. Returns the reason instead when they cannot be
 * compared: the ride has no steps, or steps the workout does not have.
 */
export function rideCompliance(
  record: RideRecord,
  workout: Workout,
): Compliance | { problem: string } {
  const flat = flattenWorkout(workout);
  const { ftp } = record.ride;
  const bySteps = new Map<number, RideSample[]>();
  for (const sample of record.samples) {
    if (sample.stepIndex == null) continue;
    if (sample.stepIndex >= flat.length) {
      return {
        problem: `the ride has a step ${sample.stepIndex + 1} and the workout has only ${flat.length}`,
      };
    }
    const list = bySteps.get(sample.stepIndex) ?? [];
    list.push(sample);
    bySteps.set(sample.stepIndex, list);
  }
  if (bySteps.size === 0) return { problem: "the ride recorded no workout step" };

  const steps: StepCompliance[] = flat.map((step) => {
    const samples = (bySteps.get(step.index) ?? []).filter((s) => s.paused !== true);
    const watts = mean(samples.flatMap((s) => (s.power == null ? [] : [s.power])));
    const planned =
      step.fromFraction === null || step.toFraction === null
        ? null
        : ((step.fromFraction + step.toFraction) / 2) * ftp;
    const heartRate = step.heartRate === null ? null : heartRateCompliance(step.heartRate, samples);
    return {
      step: step.index + 1,
      label: step.label,
      type: step.type,
      plannedSeconds: step.durationSeconds,
      actualSeconds: samples.length,
      plannedWatts: planned === null ? null : round(planned, 1),
      actualWatts: watts === null ? null : round(watts, 1),
      deltaWatts: planned === null || watts === null ? null : round(watts - planned, 1),
      deltaPercent:
        planned === null || watts === null || planned === 0
          ? null
          : round(((watts - planned) / planned) * 100, 1),
      heartRate,
    };
  });

  // The steps with a power target: a free step has none, and a heart-rate step's power is the controller's.
  const targeted = flat.filter((s) => s.fromFraction !== null && s.toFraction !== null);
  const plannedTotal = targeted.reduce((a, s) => a + s.durationSeconds, 0);
  const plannedWeighted = targeted.reduce(
    (a, s) =>
      a + (((s.fromFraction as number) + (s.toFraction as number)) / 2) * ftp * s.durationSeconds,
    0,
  );
  const targetSamples = targeted.flatMap((s) =>
    (bySteps.get(s.index) ?? []).filter((x) => x.paused !== true && x.power != null),
  );
  const actualTargetWatts = mean(targetSamples.map((s) => s.power as number));
  const plannedTargetWatts = plannedTotal === 0 ? null : plannedWeighted / plannedTotal;

  return {
    workout: { id: workout.id, name: workout.name },
    plannedSeconds: flat.reduce((a, s) => a + s.durationSeconds, 0),
    actualSeconds: steps.reduce((a, s) => a + s.actualSeconds, 0),
    stepsPlanned: flat.length,
    stepsCompleted: steps.filter((s) => s.actualSeconds >= s.plannedSeconds).length,
    targetSteps: {
      plannedWatts: plannedTargetWatts === null ? null : round(plannedTargetWatts, 1),
      actualWatts: actualTargetWatts === null ? null : round(actualTargetWatts, 1),
      deltaPercent:
        plannedTargetWatts === null || actualTargetWatts === null || plannedTargetWatts === 0
          ? null
          : round(((actualTargetWatts - plannedTargetWatts) / plannedTargetWatts) * 100, 1),
    },
    steps,
  };
}

// ---- Events: the stall guard ------------------------------------------------------------------------

/** What the ride record says about the ERG stall guard (spec §11.1): how often it took hold, and where. */
export interface StallGuardSummary {
  /** How many times the guard took hold in the ride. */
  activations: number;
  /** By workout step (1-based), with the label when the workout is known; in the order of the steps. */
  steps: { step: number; label: string | null; activations: number }[];
  /** The ride minute (from the start, pauses included) of each activation. */
  minutes: number[];
}

/**
 * The stall-guard events of a ride, counted by code. `workout` is only for the labels. A ride from before the events existed has
 * none, which is the same as a ride where the guard never fired.
 */
export function stallGuardSummary(record: RideRecord, workout: Workout | null): StallGuardSummary {
  const flat = workout === null ? [] : flattenWorkout(workout);
  const events = (record.events ?? []).filter((e) => e.type === "stall-guard");
  const counts = new Map<number, number>();
  for (const event of events) {
    if (event.stepIndex == null) continue;
    counts.set(event.stepIndex, (counts.get(event.stepIndex) ?? 0) + 1);
  }
  return {
    activations: events.length,
    steps: [...counts.entries()]
      .sort(([a], [b]) => a - b)
      .map(([index, activations]) => ({
        step: index + 1,
        label: flat[index]?.label ?? null,
        activations,
      })),
    minutes: events.map((e) => Math.round((e.timeOffsetSeconds / 60) * 10) / 10),
  };
}
