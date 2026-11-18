import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { RideSummary, Validation, Workout } from "./index.ts";
import { addDaysToIsoDate, isoDateToEpochDay, rangeDays } from "./plan.ts";

// ---- Sync API: training metrics and the companion's rides (spec §6.3, §6.7, §8.3, ADR 0022) ----
// Hand-written mirrors of schema/metrics-response.schema.json, ride-metrics-response.schema.json,
// rides-response.schema.json, ride-detail-response.schema.json and the two schemas of the FTP
// suggestion's dismissal, which are the source of truth; the fixtures test keeps the two honest.
// Kotlin's `MetricsApiJson` reads the same bodies against the same fixtures.

/**
 * The durations of the power curve, in seconds: 1, 2, 3, 5, 8, 10, 15, 20, 30, 45 s;
 * 1, 1.5, 2, 3, 4, 5, 7, 10, 15, 20, 30, 40, 60, 90, 120 min (design/companion.md §6.4).
 */
export const POWER_CURVE_DURATIONS = [
  1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420, 600, 900, 1200, 1800, 2400,
  3600, 5400, 7200,
] as const;
export type PowerCurveDuration = (typeof POWER_CURVE_DURATIONS)[number];

/** The longest range `GET /v1/metrics` serves, in days, both ends included. */
export const MAX_METRICS_RANGE_DAYS = 366;
/** `GET /v1/rides`: the most items in one page, and how many without `limit`. */
export const MAX_RIDES_LIMIT = 100;
export const DEFAULT_RIDES_LIMIT = 50;

export interface MetricsDay {
  date: string;
  /** Training stress of the day, one decimal. */
  load: number;
  loadCycling: number;
  loadOther: number;
  /** Fitness: exponentially weighted average of the daily load over 42 days. */
  ctl: number;
  /** Fatigue: the same over 7 days. */
  atl: number;
  /** Form: `ctl - atl`. */
  tsb: number;
}

export interface MetricsWeek {
  /** The Monday. */
  weekStart: string;
  load: number;
  rideLoad: number;
  otherLoad: number;
  rides: number;
  /** Other activities, not rides. */
  activities: number;
}

export interface CurvePoint {
  durationSec: PowerCurveDuration;
  power: number;
  date: string;
  rideId: string;
}

export type Confidence = "low" | "medium" | "high";

export interface FtpSuggestion {
  id: number;
  watts: number;
  basedOnRides: number;
  windowDays: number;
  createdAt: string;
  confidence: Confidence;
  basis: { durationSec: PowerCurveDuration; power: number; rideId: string; date: string };
}

export interface FtpHistoryEntry {
  date: string;
  ftp: number | null;
}

export interface FtpEstimate {
  date: string;
  eftp: number;
}

export interface DriftPoint {
  date: string;
  rideId: string;
  name: string;
  driftPercent: number;
  efFirst: number;
  efSecond: number;
}

/** The answer to `GET /v1/metrics?from&to`. */
export interface MetricsResponse {
  from: string;
  to: string;
  generatedAt: string;
  stale: boolean;
  firstLoadDate: string | null;
  daily: MetricsDay[];
  weekly: MetricsWeek[];
  powerCurve: { range: CurvePoint[]; allTime: CurvePoint[] };
  ftp: {
    current: number | null;
    setAt: string | null;
    suggestion: FtpSuggestion | null;
    latestEstimate: number | null;
    history: FtpHistoryEntry[];
    estimates: FtpEstimate[];
  };
  drift: DriftPoint[];
}

export interface RideDrift {
  available: boolean;
  reason: string | null;
  driftPercent: number | null;
  efFirst: number | null;
  efSecond: number | null;
  startSec: number | null;
  endSec: number | null;
}

export interface WPrimeBalance {
  cpWatts: number;
  cpSource: "profile" | "ride" | "request";
  wPrimeKj: number;
  wPrimeSource: "profile" | "default" | "request";
  tauSeconds: number;
  dCpWatts: number;
  minBalanceKj: number;
  minAtSec: number;
  maxDepletionPercent: number;
  series?: { stepSeconds: number; balanceKj: number[] } | null;
}

/** The answer to `GET /v1/rides/{rideId}/metrics`. */
export interface RideMetricsResponse {
  generatedAt: string;
  stale: boolean;
  rideId: string;
  startTime: string;
  date: string;
  ftp: number;
  durationSec: number;
  movingSec: number;
  load: number | null;
  powerCurve: { durationSec: PowerCurveDuration; power: number }[];
  drift: RideDrift;
  wPrimeBalance?: WPrimeBalance | null;
}

/** One item of `GET /v1/rides`: a ride, or (from the wellness part of the service) an activity of the watch. */
export interface RideItem {
  id: string;
  kind: "ride" | "activity";
  name: string;
  start: string;
  date: string;
  durationSeconds: number;
  load: number | null;
  loadApproximate: boolean;
  sport: string | null;
  averagePower: number | null;
  averageHeartRate: number | null;
}

/** The answer to `GET /v1/rides?before&limit`. */
export interface RidesResponse {
  generatedAt: string;
  stale: boolean;
  before: string | null;
  limit: number;
  hasMore: boolean;
  nextBefore: string | null;
  items: RideItem[];
}

export interface DetailLap {
  stepIndex: number | null;
  label: string | null;
  type: "steady" | "ramp" | "free" | null;
  startSeconds: number;
  endSeconds: number;
  averagePower: number | null;
  averageHeartRate: number | null;
  targetPower: number | null;
}

export interface RidePlan {
  workout: Workout;
  stepsRidden: number;
  stepsPlanned: number;
  effortRange: { minWatts: number; maxWatts: number } | null;
  effortTarget: number | null;
}

export interface DetailPoint {
  /** The second at the end of the step. */
  t: number;
  power: number | null;
  heartRate: number | null;
  /** W′ balance in kilojoules at second `t`. */
  wBal: number;
}

/** The answer to `GET /v1/rides/{rideId}`. */
export interface RideDetailResponse {
  generatedAt: string;
  stale: boolean;
  id: string;
  name: string;
  startTime: string;
  endTime: string | null;
  date: string;
  workoutId: string | null;
  devices: string[];
  ftp: number;
  cp: number;
  wPrime: number;
  wPrimeLowest: { kilojoules: number; atSec: number };
  summary: RideSummary;
  secondsInZones: number[];
  laps: DetailLap[];
  plan: RidePlan | null;
  series?: DetailPoint[] | null;
}

/** The body of `POST /v1/ftp-suggestion/dismiss`. */
export interface FtpSuggestionDismissRequest {
  suggestionId: number;
}

/** What is left of the suggestion after the dismissal: dismissed, or the state it had already left to. */
export interface FtpSuggestionDismissResponse {
  suggestionId: number;
  status: "dismissed" | "accepted" | "superseded";
}

// ---- Validation ------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

// The other schemas point into these by their file names.
ajv.addSchema(load("ride-record.schema.json"), "ride-record.schema.json");
ajv.addSchema(load("workout.schema.json"), "workout.schema.json");
ajv.addSchema(load("metrics-response.schema.json"), "metrics-response.schema.json");
const checkMetrics = ajv.getSchema<MetricsResponse>("metrics-response.schema.json") as ReturnType<
  typeof ajv.compile<MetricsResponse>
>;
const checkRideMetrics = ajv.compile<RideMetricsResponse>(
  load("ride-metrics-response.schema.json"),
);
const checkRides = ajv.compile<RidesResponse>(load("rides-response.schema.json"));
const checkRideDetail = ajv.compile<RideDetailResponse>(load("ride-detail-response.schema.json"));
const checkDismissRequest = ajv.compile<FtpSuggestionDismissRequest>(
  load("ftp-suggestion-dismiss-request.schema.json"),
);
const checkDismissResponse = ajv.compile<FtpSuggestionDismissResponse>(
  load("ftp-suggestion-dismiss-response.schema.json"),
);

const MAX_REPORTED = 5;
/** The rounding of one decimal, with room for the representation of the sums. */
const ROUNDING = 0.1 + 1e-9;

interface SchemaErrors {
  instancePath: string;
  message?: string | undefined;
}

function errorsOf(errors: readonly SchemaErrors[] | null | undefined): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

function checked<T>(
  valid: boolean,
  input: unknown,
  schemaErrors: readonly SchemaErrors[] | null | undefined,
  consistency: (value: T) => string[],
): Validation<T> {
  if (!valid) return { ok: false, errors: errorsOf(schemaErrors) };
  const errors = consistency(input as T).slice(0, MAX_REPORTED);
  return errors.length === 0 ? { ok: true, value: input as T } : { ok: false, errors };
}

/** Days from a real Monday back to nothing: 0 on a Monday, 6 on a Sunday (1970-01-01 was a Thursday). */
function daysSinceMonday(epochDay: number): number {
  return (((epochDay + 3) % 7) + 7) % 7;
}

function realDate(errors: string[], at: string, date: string): number | null {
  const day = isoDateToEpochDay(date);
  if (day === null) errors.push(`${at} ${date} is not a real calendar date`);
  return day;
}

function curveErrors(
  at: string,
  points: readonly CurvePoint[],
  range: { first: number; last: number } | null,
  errors: string[],
): void {
  let previous: CurvePoint | undefined;
  for (const [index, point] of points.entries()) {
    const here = `${at}/${index}`;
    if (previous !== undefined) {
      if (point.durationSec <= previous.durationSec) {
        errors.push(
          `${here}/durationSec ${point.durationSec} does not follow ${previous.durationSec}`,
        );
        return;
      }
      if (point.power > previous.power) {
        errors.push(
          `${here}/power ${point.power} is above the ${previous.durationSec} s power ${previous.power}`,
        );
        return;
      }
    }
    const day = realDate(errors, `${here}/date`, point.date);
    if (day !== null && range !== null && (day < range.first || day > range.last)) {
      errors.push(`${here}/date ${point.date} is outside the range`);
      return;
    }
    previous = point;
  }
}

/** What the schema cannot say about `GET /v1/metrics` (see its description). Returns the violations. */
export function metricsResponseConsistencyErrors(response: MetricsResponse): string[] {
  const errors: string[] = [];
  const first = isoDateToEpochDay(response.from);
  const last = isoDateToEpochDay(response.to);
  const days = rangeDays(response.from, response.to);
  if (first === null || last === null || days === null) {
    return ["/from and /to must be real calendar dates"];
  }
  if (days < 1) return [`/from ${response.from} is after /to ${response.to}`];
  if (days > MAX_METRICS_RANGE_DAYS) {
    return [`The range spans ${days} days, at most ${MAX_METRICS_RANGE_DAYS} are served`];
  }
  const range = { first, last };
  const inRange = (day: number): boolean => day >= first && day <= last;

  if (response.firstLoadDate !== null) realDate(errors, "/firstLoadDate", response.firstLoadDate);

  if (response.daily.length !== days) {
    errors.push(`/daily has ${response.daily.length} entries, the range has ${days} days`);
  } else {
    for (const [index, day] of response.daily.entries()) {
      const expected = addDaysToIsoDate(response.from, index);
      if (day.date !== expected) {
        errors.push(`/daily/${index}/date is ${day.date}, expected ${expected}`);
        break;
      }
      if (Math.abs(day.tsb - (day.ctl - day.atl)) > ROUNDING) {
        errors.push(`/daily/${index}/tsb ${day.tsb} is not ctl ${day.ctl} minus atl ${day.atl}`);
        break;
      }
      if (Math.abs(day.load - (day.loadCycling + day.loadOther)) > ROUNDING) {
        errors.push(`/daily/${index}/load ${day.load} is not loadCycling plus loadOther`);
        break;
      }
    }
  }

  const firstWeek = first - daysSinceMonday(first);
  const lastWeek = last - daysSinceMonday(last);
  const weeks = (lastWeek - firstWeek) / 7 + 1;
  if (response.weekly.length !== weeks) {
    errors.push(`/weekly has ${response.weekly.length} entries, the range touches ${weeks} weeks`);
  } else {
    for (const [index, week] of response.weekly.entries()) {
      const day = realDate(errors, `/weekly/${index}/weekStart`, week.weekStart);
      if (day === null) break;
      if (daysSinceMonday(day) !== 0) {
        errors.push(`/weekly/${index}/weekStart ${week.weekStart} is not a Monday`);
        break;
      }
      if (day !== firstWeek + 7 * index) {
        errors.push(
          `/weekly/${index}/weekStart ${week.weekStart} is not week ${index + 1} of the range`,
        );
        break;
      }
      if (Math.abs(week.load - (week.rideLoad + week.otherLoad)) > ROUNDING) {
        errors.push(`/weekly/${index}/load ${week.load} is not rideLoad plus otherLoad`);
        break;
      }
    }
  }

  curveErrors("/powerCurve/range", response.powerCurve.range, range, errors);
  curveErrors("/powerCurve/allTime", response.powerCurve.allTime, null, errors);
  for (const [index, point] of response.powerCurve.range.entries()) {
    const best = response.powerCurve.allTime.find((p) => p.durationSec === point.durationSec);
    if (best === undefined || best.power < point.power) {
      errors.push(
        `/powerCurve/range/${index} has no allTime effort of ${point.durationSec} s at least as good`,
      );
      break;
    }
  }

  const { ftp } = response;
  if ((ftp.setAt === null) !== (ftp.current === null)) {
    errors.push("/ftp/setAt must be null exactly when /ftp/current is");
  }
  if (ftp.setAt !== null) realDate(errors, "/ftp/setAt", ftp.setAt);

  let previousDay = -Infinity;
  for (const [index, entry] of ftp.history.entries()) {
    const day = realDate(errors, `/ftp/history/${index}/date`, entry.date);
    if (day === null) break;
    if (day < previousDay) {
      errors.push(`/ftp/history/${index}/date ${entry.date} is before the entry above it`);
      break;
    }
    previousDay = day;
  }

  previousDay = -Infinity;
  for (const [index, entry] of ftp.estimates.entries()) {
    const day = realDate(errors, `/ftp/estimates/${index}/date`, entry.date);
    if (day === null) break;
    if (!inRange(day)) {
      errors.push(
        `/ftp/estimates/${index}/date ${entry.date} is outside ${response.from} to ${response.to}`,
      );
      break;
    }
    if (day < previousDay) {
      errors.push(`/ftp/estimates/${index}/date ${entry.date} is before the entry above it`);
      break;
    }
    previousDay = day;
  }

  previousDay = -Infinity;
  for (const [index, point] of response.drift.entries()) {
    const day = realDate(errors, `/drift/${index}/date`, point.date);
    if (day === null) break;
    if (!inRange(day)) {
      errors.push(
        `/drift/${index}/date ${point.date} is outside ${response.from} to ${response.to}`,
      );
      break;
    }
    if (day < previousDay) {
      errors.push(`/drift/${index}/date ${point.date} is before the entry above it`);
      break;
    }
    previousDay = day;
  }
  return errors;
}

/** Validates a metrics answer: the schema first, then the rules a schema cannot state. */
export function validateMetricsResponse(input: unknown): Validation<MetricsResponse> {
  return checked(checkMetrics(input), input, checkMetrics.errors, metricsResponseConsistencyErrors);
}

/** What the schema cannot say about `GET /v1/rides/{rideId}/metrics`. */
export function rideMetricsResponseConsistencyErrors(response: RideMetricsResponse): string[] {
  const errors: string[] = [];
  realDate(errors, "/date", response.date);
  if (response.movingSec > response.durationSec) {
    errors.push(
      `/movingSec ${response.movingSec} is more than /durationSec ${response.durationSec}`,
    );
  }
  for (const [index, point] of response.powerCurve.entries()) {
    const before = response.powerCurve[index - 1];
    if (before === undefined) continue;
    if (point.durationSec <= before.durationSec) {
      errors.push(
        `/powerCurve/${index}/durationSec ${point.durationSec} does not follow ${before.durationSec}`,
      );
      break;
    }
    if (point.power > before.power) {
      errors.push(
        `/powerCurve/${index}/power ${point.power} is above the ${before.durationSec} s power`,
      );
      break;
    }
  }
  const drift = response.drift;
  const numbers = [drift.driftPercent, drift.efFirst, drift.efSecond, drift.startSec, drift.endSec];
  if (drift.available) {
    if (numbers.some((n) => n === null) || drift.reason !== null) {
      errors.push("/drift is available: its numbers must all be present and reason null");
    } else if ((drift.endSec as number) <= (drift.startSec as number)) {
      errors.push("/drift/endSec must be after startSec");
    } else if ((drift.endSec as number) > response.durationSec) {
      errors.push("/drift/endSec is after the end of the ride");
    }
  } else if (numbers.some((n) => n !== null) || drift.reason === null) {
    errors.push("/drift is not available: reason must be set and the numbers null");
  }
  const w = response.wPrimeBalance;
  if (w !== undefined && w !== null) {
    if (w.minBalanceKj > w.wPrimeKj + 0.005) {
      errors.push("/wPrimeBalance/minBalanceKj is above wPrimeKj");
    }
    const depletion = ((w.wPrimeKj - w.minBalanceKj) / w.wPrimeKj) * 100;
    if (Math.abs(depletion - w.maxDepletionPercent) > 0.5) {
      errors.push(
        `/wPrimeBalance/maxDepletionPercent ${w.maxDepletionPercent} does not follow from the minimum (${depletion.toFixed(1)})`,
      );
    }
    if (w.minAtSec > response.durationSec) {
      errors.push("/wPrimeBalance/minAtSec is after the end of the ride");
    }
    const series = w.series;
    if (series !== undefined && series !== null) {
      const most = Math.floor(response.durationSec / series.stepSeconds);
      if (series.balanceKj.length > most) {
        errors.push(
          `/wPrimeBalance/series has ${series.balanceKj.length} points, a ride of ${response.durationSec} s has at most ${most}`,
        );
      }
    }
  }
  return errors;
}

/** Validates a ride metrics answer. */
export function validateRideMetricsResponse(input: unknown): Validation<RideMetricsResponse> {
  return checked(
    checkRideMetrics(input),
    input,
    checkRideMetrics.errors,
    rideMetricsResponseConsistencyErrors,
  );
}

/** What the schema cannot say about `GET /v1/rides`. */
export function ridesResponseConsistencyErrors(response: RidesResponse): string[] {
  const errors: string[] = [];
  const before = response.before === null ? null : Date.parse(response.before);
  if (before !== null && Number.isNaN(before)) errors.push("/before is not a real instant");
  if (response.items.length > response.limit) {
    errors.push(`/items has ${response.items.length} entries, the limit is ${response.limit}`);
  }
  const last = response.items[response.items.length - 1];
  if (response.hasMore) {
    if (last === undefined) errors.push("/hasMore needs at least one item");
    else if (response.nextBefore !== last.start) {
      errors.push(`/nextBefore must be the start of the last item, ${last.start}`);
    }
  } else if (response.nextBefore !== null) {
    errors.push("/nextBefore must be null when /hasMore is false");
  }
  const seen = new Set<string>();
  let previous = Number.POSITIVE_INFINITY;
  for (const [index, item] of response.items.entries()) {
    const at = `/items/${index}`;
    const key = `${item.kind}:${item.id}`;
    if (seen.has(key)) errors.push(`${at}/id ${item.id} is listed twice`);
    seen.add(key);
    realDate(errors, `${at}/date`, item.date);
    const start = Date.parse(item.start);
    if (Number.isNaN(start)) {
      errors.push(`${at}/start is not a real instant`);
      continue;
    }
    if (start >= previous) errors.push(`${at} does not start before the item above it`);
    if (before !== null && start >= before) errors.push(`${at} does not start before /before`);
    previous = start;
    if (item.kind === "ride") {
      if (item.sport !== null || item.averageHeartRate !== null || item.loadApproximate) {
        errors.push(`${at} is a ride: no sport, no averageHeartRate and an exact load`);
      }
    } else if (item.averagePower !== null || !item.loadApproximate) {
      errors.push(`${at} is an activity: no averagePower and an approximate load`);
    }
  }
  return errors;
}

/** Validates a rides list answer. */
export function validateRidesResponse(input: unknown): Validation<RidesResponse> {
  return checked(checkRides(input), input, checkRides.errors, ridesResponseConsistencyErrors);
}

/** What the schema cannot say about `GET /v1/rides/{rideId}`. */
export function rideDetailResponseConsistencyErrors(response: RideDetailResponse): string[] {
  const errors: string[] = [];
  realDate(errors, "/date", response.date);
  const [firstLap] = response.laps;
  if (firstLap !== undefined && firstLap.startSeconds !== 0) {
    errors.push(`/laps/0 starts at ${firstLap.startSeconds}, not 0`);
  }
  for (const [index, lap] of response.laps.entries()) {
    const next = response.laps[index + 1];
    if (lap.endSeconds <= lap.startSeconds) {
      errors.push(`/laps/${index} ends at ${lap.endSeconds}, not after its start`);
      break;
    }
    if (next !== undefined && next.startSeconds !== lap.endSeconds) {
      errors.push(`/laps/${index + 1} starts at ${next.startSeconds}, where lap ${index} ends`);
      break;
    }
  }
  const duration = response.summary.durationSeconds;
  const lastLap = response.laps[response.laps.length - 1];
  if (lastLap !== undefined && lastLap.endSeconds !== duration) {
    errors.push(`The last lap ends at ${lastLap.endSeconds}, the ride is ${duration} s long`);
  }
  const plan = response.plan;
  if (plan !== null) {
    if ((plan.effortRange === null) !== (plan.effortTarget === null)) {
      errors.push("/plan/effortRange must be null exactly when effortTarget is");
    } else if (plan.effortRange !== null && plan.effortRange.minWatts > plan.effortRange.maxWatts) {
      errors.push("/plan/effortRange minWatts is above maxWatts");
    }
    if (plan.stepsRidden > plan.stepsPlanned) {
      errors.push(
        `/plan/stepsRidden ${plan.stepsRidden} is more than stepsPlanned ${plan.stepsPlanned}`,
      );
    }
  }
  let previousT = 0;
  for (const [index, point] of (response.series ?? []).entries()) {
    if (point.t <= previousT || point.t > duration) {
      errors.push(
        `/series/${index}/t ${point.t} must ascend and stay within the ${duration} s of the ride`,
      );
      break;
    }
    previousT = point.t;
  }
  return errors;
}

/** Validates a ride detail answer. */
export function validateRideDetailResponse(input: unknown): Validation<RideDetailResponse> {
  return checked(
    checkRideDetail(input),
    input,
    checkRideDetail.errors,
    rideDetailResponseConsistencyErrors,
  );
}

/** Validates the body of `POST /v1/ftp-suggestion/dismiss`. */
export function validateFtpSuggestionDismissRequest(
  input: unknown,
): Validation<FtpSuggestionDismissRequest> {
  return checkDismissRequest(input)
    ? { ok: true, value: input }
    : { ok: false, errors: errorsOf(checkDismissRequest.errors) };
}

/** Validates the answer of `POST /v1/ftp-suggestion/dismiss`. */
export function validateFtpSuggestionDismissResponse(
  input: unknown,
): Validation<FtpSuggestionDismissResponse> {
  return checkDismissResponse(input)
    ? { ok: true, value: input }
    : { ok: false, errors: errorsOf(checkDismissResponse.errors) };
}
