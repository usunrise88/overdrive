import {
  PROGRESS_MEASURES,
  PROGRESS_NOTE,
  PROGRESS_ROLLING_RIDES,
  type ProgressDirection,
  type ProgressHeadline,
  type ProgressMeasure,
  type ProgressOverallTrend,
  type ProgressTrend,
  type ProgressUnit,
  progressDirection,
} from "@overdrive/contracts";

/**
 * The trend of one measure of the Progress screen (spec §6.3 "Progress over time", ADR 0043): the model of the repeat page (ADR 0026,
 * `repeats/compare.ts`) generalised. A least-squares line over the points that have a figure, against where the point is in time (the
 * number of the week or month, so a missing week is a gap and not a squeeze) or, for the measures of single rides, against the ride's
 * position; the fitted change from the first point to the last is the slope times the span. A change smaller than the steady band in
 * size is `steady`, else `improving` or `worsening` by which way is better; below the minimum number of points the direction is
 * `unknown`. Pure functions; the sentences are written here, never by a model.
 */

export const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor + 0;
};

/** One point of a trend. */
export interface TrendPoint {
  /** Where it is in time: a week or month number, or the ride's position. */
  x: number;
  y: number;
  /** The local day the point is dated (a week's Monday, a month's first day, a ride's day). */
  date: string;
}

/** How the sentence of a trend is worded. */
export interface TrendSpec {
  /** The subject of the sentence: "Heart rate at the same power". */
  subject: string;
  /** "is" or "are" (watts per kilogram are). */
  verb: "is" | "are";
  unit: ProgressUnit;
  betterWhen: "higher" | "lower";
  minPoints: number;
  /** In the unit; a fitted change smaller than this in size is steady. */
  steadyBand: number;
  /** Whether an improving trend is "fitter" (heart rate against power: aerobic efficiency and the efficiency factor) and not just "better". */
  fitter: boolean;
  /** Whether the heat, sleep and caffeine caution is the trend's note. */
  heartRate: boolean;
}

/** Which ends of the series `from` and `latest` are. */
export type TrendEnds =
  /** The first and the last figure. */
  | "points"
  /** The mean of the first and of the last five rides (the ride measures); none below five rides. */
  | "rides";

export interface TrendInput {
  points: readonly TrendPoint[];
  spec: TrendSpec;
  /** Why there is no verdict, given the number of points; used while it is unknown. */
  reason: (points: number) => string;
  ends: TrendEnds;
}

const mean = (values: readonly number[]): number =>
  values.reduce((a, b) => a + b, 0) / values.length;

/** The least-squares slope of y on x; null when every x is the same. */
export function slopeOf(points: readonly TrendPoint[]): number | null {
  const meanX = mean(points.map((p) => p.x));
  const meanY = mean(points.map((p) => p.y));
  let covariance = 0;
  let variance = 0;
  for (const point of points) {
    covariance += (point.x - meanX) * (point.y - meanY);
    variance += (point.x - meanX) ** 2;
  }
  return variance === 0 ? null : covariance / variance;
}

/** The words of a verdict: "Heart rate at the same power is trending down: fitter". */
export function verdictText(
  spec: Pick<TrendSpec, "subject" | "verb" | "fitter">,
  direction: ProgressDirection,
  change: number | null,
): string {
  if (direction === "unknown") return "Not enough data yet";
  if (direction === "steady") return `${spec.subject} ${spec.verb} steady`;
  const way = (change ?? 0) < 0 ? "down" : "up";
  const base = `${spec.subject} ${spec.verb} trending ${way}`;
  // "Fitter" or "better" is said of an improving trend only; a worsening trend carries no judging word.
  return direction === "improving" ? `${base}: ${spec.fitter ? "fitter" : "better"}` : base;
}

export function trendOf(input: TrendInput): ProgressTrend {
  const { points, spec } = input;
  const base = {
    betterWhen: spec.betterWhen,
    unit: spec.unit,
    points: points.length,
    minPoints: spec.minPoints,
    steadyBand: spec.steadyBand,
    note: spec.heartRate ? PROGRESS_NOTE : null,
  } as const;

  let from: number | null = null;
  let latest: number | null = null;
  let since: string | null = null;
  const first = points[0];
  const last = points[points.length - 1];
  if (first !== undefined && last !== undefined) {
    if (input.ends === "points") {
      from = first.y;
      latest = last.y;
      since = first.date;
    } else if (points.length >= PROGRESS_ROLLING_RIDES) {
      from = mean(points.slice(0, PROGRESS_ROLLING_RIDES).map((p) => p.y));
      latest = mean(points.slice(-PROGRESS_ROLLING_RIDES).map((p) => p.y));
      since = first.date;
    }
  }
  const ends = {
    from: from === null ? null : round(from, 3),
    latest: latest === null ? null : round(latest, 3),
    since,
  };

  const slope = points.length >= spec.minPoints ? slopeOf(points) : null;
  if (points.length < spec.minPoints || first === undefined || last === undefined) {
    return {
      ...base,
      direction: "unknown",
      change: null,
      difference: null,
      ...ends,
      verdictText: verdictText(spec, "unknown", null),
      reason: input.reason(points.length),
    };
  }
  // Every x the same cannot happen for weeks, months or positions; a line with no slope is no change.
  const change = round((slope ?? 0) * (last.x - first.x), 3);
  const direction = progressDirection(change, spec.steadyBand, spec.betterWhen);
  const difference =
    ends.from === null || ends.latest === null ? null : round(ends.latest - ends.from, 3);
  return {
    ...base,
    direction,
    change,
    difference,
    ...ends,
    verdictText: verdictText(spec, direction, change),
    reason: null,
  };
}

/** The overall verdict of best power from its three lines (ADR 0043): at least two improving and none worsening, the reverse, else steady. */
export function overallBestPower(
  lines: readonly ProgressTrend[],
  spec: { subject: string },
): ProgressOverallTrend {
  const known = lines.filter((t) => t.direction !== "unknown");
  const improving = known.filter((t) => t.direction === "improving").length;
  const worsening = known.filter((t) => t.direction === "worsening").length;
  const direction: ProgressDirection =
    known.length < 2
      ? "unknown"
      : improving >= 2 && worsening === 0
        ? "improving"
        : worsening >= 2 && improving === 0
          ? "worsening"
          : "steady";
  const reasons = lines
    .filter((t) => t.direction === "unknown")
    .map((t) => t.reason)
    .filter((r): r is string => r !== null);
  return {
    direction,
    betterWhen: "higher",
    lines: known.length,
    verdictText: verdictText(
      { subject: spec.subject, verb: "is", fitter: false },
      direction,
      direction === "worsening" ? -1 : 1,
    ),
    reason:
      direction === "unknown"
        ? reasons.join(" ").slice(0, 300) || "Fewer than two durations have a verdict yet."
        : null,
    note: null,
  };
}

/** What the headline needs to know of a measure. */
export interface HeadlineCandidate {
  measure: ProgressMeasure;
  direction: ProgressDirection;
  verdictText: string;
  /** How far the change is outside its steady band: `|change| / steadyBand`; 0 for a steady trend. */
  clarity: number;
}

/**
 * The measure the Fitness and Power rows show (ADR 0043): among the measures with a non-steady verdict the one whose change is
 * furthest outside its steady band (a verdict that is clearly outside noise is the most informative), the order of
 * {@link PROGRESS_MEASURES} deciding a tie; with none, the first measure with a steady verdict in that order; with no verdict
 * anywhere, none.
 */
export function pickHeadline(candidates: readonly HeadlineCandidate[]): ProgressHeadline | null {
  const known = candidates.filter((c) => c.direction !== "unknown");
  if (known.length === 0) return null;
  const order = (measure: ProgressMeasure): number => PROGRESS_MEASURES.indexOf(measure);
  const moving = known.filter((c) => c.direction !== "steady");
  const pool = moving.length > 0 ? moving : known;
  const best = [...pool].sort(
    (a, b) => b.clarity - a.clarity || order(a.measure) - order(b.measure),
  )[0];
  if (best === undefined || best.direction === "unknown") return null;
  return { measure: best.measure, direction: best.direction, verdictText: best.verdictText };
}

/** `|change| / steadyBand` of a trend, 0 while it has no change. */
export function clarityOf(trend: ProgressTrend): number {
  return trend.change === null ? 0 : Math.abs(trend.change) / trend.steadyBand;
}
