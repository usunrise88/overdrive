import {
  POWER_CURVE_DURATIONS,
  type PowerCurveDuration,
  type RideSample,
} from "@overdrive/contracts";

/**
 * The mean-maximal power curve (spec §6.3): for each duration, the best average power over any
 * window of that many consecutive seconds of a ride.
 *
 * Decision on gaps (ADR 0022): a window is a stretch of the ride's own clock, one sample per second,
 * and **a paused second or a second with no power reading counts as 0 W**. The same definition as
 * the best efforts of `get_ride` (`ride-stats.ts`), so one number means one thing. It can only
 * lower a value, never raise it: a rider who stops, or whose power meter drops out, is not credited
 * for the seconds nothing was measured, so the curve and the eFTP made from it are conservative.
 * The alternative, joining the pieces on both sides of a gap, would count a rest as work done.
 *
 * A ride shorter than a duration has no value for it, and a ride with no power reading at all has
 * no curve (not a row of zeros). The curve is the raw best of each duration: it is not forced to fall
 * with the duration (a window of 10 s can average more than the best 8 s when its two extra seconds are
 * hard ones), though on a real ride it nearly always does.
 */

export interface RidePowerPoint {
  durationSec: PowerCurveDuration;
  /** Watts, one decimal. */
  power: number;
}

const round1 = (value: number): number => Math.round(value * 10) / 10;

/** The power of each second of the ride on its own clock: pauses and missing readings are 0. */
export function powerTimeline(samples: readonly RideSample[]): number[] {
  return samples.map((s) => (s.paused === true || s.power == null ? 0 : s.power));
}

/** Whether any second of the ride (not paused) has a power reading. */
export function hasPower(samples: readonly RideSample[]): boolean {
  return samples.some((s) => s.paused !== true && s.power != null);
}

/**
 * The durations kept for each ride in addition to the 25 of the contract: the power skills (spec §6.3, ADR 0042) judge 45 minutes,
 * which the companion's curve does not draw. They live in the same stored curve under their own keys, which {@link curveFromJson}
 * leaves out, so no answer that lists the 25 changes.
 */
export const EXTRA_CURVE_DURATIONS: readonly number[] = [2700];

/** A point of the curve at a duration the contract's 25 do not have. */
export interface ExtraPowerPoint {
  durationSec: number;
  /** Watts, one decimal. */
  power: number;
}

/** The best average power of a ride over each of `durations` (ascending) that it is long enough for; see {@link meanMaximalPower}. */
function bestAverages(
  samples: readonly RideSample[],
  durations: readonly number[],
): { durationSec: number; power: number }[] {
  if (!hasPower(samples)) return [];
  const timeline = powerTimeline(samples);
  const prefix = new Float64Array(timeline.length + 1);
  for (const [index, watts] of timeline.entries()) {
    prefix[index + 1] = (prefix[index] as number) + watts;
  }
  const curve: { durationSec: number; power: number }[] = [];
  for (const durationSec of durations) {
    if (timeline.length < durationSec) break;
    let best = 0;
    for (let end = durationSec; end <= timeline.length; end++) {
      const sum = (prefix[end] as number) - (prefix[end - durationSec] as number);
      if (sum > best) best = sum;
    }
    curve.push({ durationSec, power: round1(best / durationSec) });
  }
  return curve;
}

/**
 * The best average power over each duration of {@link POWER_CURVE_DURATIONS} that the ride is long
 * enough for, ascending by duration. Empty when no power was measured. Prefix sums of whole watts
 * are exact, so the only rounding is the one decimal of the result.
 */
export function meanMaximalPower(samples: readonly RideSample[]): RidePowerPoint[] {
  return bestAverages(samples, POWER_CURVE_DURATIONS) as RidePowerPoint[];
}

/** The same for the durations of {@link EXTRA_CURVE_DURATIONS}. */
export function extraMeanMaximalPower(samples: readonly RideSample[]): ExtraPowerPoint[] {
  return bestAverages(samples, EXTRA_CURVE_DURATIONS);
}

/** One ride's curve with where it comes from, as the aggregation takes it. */
export interface RideCurve {
  rideId: string;
  /** The rider's local day of the ride. */
  date: string;
  /** Epoch milliseconds of the start: ties go to the later ride. */
  startEpochMillis: number;
  curve: readonly RidePowerPoint[];
}

export interface BestEffort {
  durationSec: PowerCurveDuration;
  power: number;
  rideId: string;
  date: string;
}

/**
 * The best power over each duration across several rides, ascending by duration: for each duration
 * the highest value any ride has, and when two rides share it, the later one. A duration no ride is
 * long enough for is left out.
 */
export function bestEfforts(rides: readonly RideCurve[]): BestEffort[] {
  const best = new Map<PowerCurveDuration, BestEffort & { start: number }>();
  for (const ride of rides) {
    for (const point of ride.curve) {
      const held = best.get(point.durationSec);
      if (
        held === undefined ||
        point.power > held.power ||
        (point.power === held.power && ride.startEpochMillis > held.start)
      ) {
        best.set(point.durationSec, {
          durationSec: point.durationSec,
          power: point.power,
          rideId: ride.rideId,
          date: ride.date,
          start: ride.startEpochMillis,
        });
      }
    }
  }
  return POWER_CURVE_DURATIONS.flatMap((durationSec) => {
    const held = best.get(durationSec);
    if (held === undefined) return [];
    const { start: _start, ...effort } = held;
    return [effort];
  });
}

/** A curve as the table `ride_metrics` keeps it: `{"60": 312.5, …}` by duration in seconds, the extra durations beside the 25. */
export function curveToJson(
  curve: readonly RidePowerPoint[],
  extra: readonly ExtraPowerPoint[] = [],
): Record<string, number> {
  return Object.fromEntries([...curve, ...extra].map((p) => [String(p.durationSec), p.power]));
}

/** The extra durations of a stored curve; the ones the code does not know are dropped. */
export function extraCurveFromJson(json: Record<string, number>): ExtraPowerPoint[] {
  return EXTRA_CURVE_DURATIONS.flatMap((durationSec) => {
    const power = json[String(durationSec)];
    return power === undefined ? [] : [{ durationSec, power }];
  });
}

/** The inverse of {@link curveToJson}; durations the code does not know are dropped. */
export function curveFromJson(json: Record<string, number>): RidePowerPoint[] {
  return POWER_CURVE_DURATIONS.flatMap((durationSec) => {
    const power = json[String(durationSec)];
    return power === undefined ? [] : [{ durationSec, power }];
  });
}
