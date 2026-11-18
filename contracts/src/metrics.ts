import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Lap, RideSummary, Validation } from "./index.ts";
import { addDaysToIsoDate, isoDateToEpochDay, rangeDays } from "./plan.ts";

// ---- Sync API: training metrics and the rides of the companion (spec §6.3, §8.3, ADR 0022) ---
// Hand-written mirrors of schema/metrics-response.schema.json, ride-metrics-response.schema.json,
// rides-response.schema.json and ride-detail-response.schema.json, which are the source of truth; the
// fixtures test keeps the two honest. Kotlin's `MetricsApiJson` reads the same bodies against the
// same fixtures.

/** The durations of the power curve, in seconds. */
export const POWER_CURVE_DURATIONS = [
  1, 2, 5, 10, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600,
] as const;
export type PowerCurveDuration = (typeof POWER_CURVE_DURATIONS)[number];

/** The longest range `GET /v1/metrics` serves, in days, both ends included. */
export const MAX_METRICS_RANGE_DAYS = 400;
/** `GET /v1/rides`: the most rides in one page, and how many without `limit`. */
export const MAX_RIDES_LIMIT = 100;
export const DEFAULT_RIDES_LIMIT = 20;

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
  rides: number;
  /** Moving time of the rides, seconds. */
  durationSec: number;
}

export interface CurvePoint {
  durationSec: PowerCurveDuration;
  power: number;
  rideId: string;
  date: string;
}

export type Confidence = "low" | "medium" | "high";

export interface FtpSuggestion {
  id: number;
  value: number;
  basis: { durationSec: PowerCurveDuration; power: number; rideId: string; date: string };
  confidence: Confidence;
  currentFtp: number | null;
  differencePercent: number | null;
  status: "pending";
  createdAt: string;
}

export interface FtpHistoryEntry {
  date: string;
  ftp: number | null;
}

export interface DriftPoint {
  date: string;
  rideId: string;
  driftPercent: number;
  efFirst: number;
  efSecond: number;
}

/** The answer to `GET /v1/metrics?from&to`. */
export interface MetricsResponse {
  from: string;
  to: string;
  generatedAt: string;
  firstLoadDate: string | null;
  daily: MetricsDay[];
  weekly: MetricsWeek[];
  powerCurve: { windowDays: number; best: CurvePoint[] };
  ftp: {
    current: number | null;
    suggestion: FtpSuggestion | null;
    suggestionNote: string | null;
    history: FtpHistoryEntry[];
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

/** One ride of `GET /v1/rides`. */
export interface RideListEntry {
  id: string;
  startTime: string;
  endTime: string | null;
  date: string;
  workoutId: string | null;
  workoutName: string | null;
  ftp: number;
  devices: string[];
  summary: RideSummary;
}

/** The answer to `GET /v1/rides`. */
export interface RidesResponse {
  from: string | null;
  to: string | null;
  total: number;
  offset: number;
  limit: number;
  hasMore: boolean;
  rides: RideListEntry[];
}

export interface RideSeriesBody {
  stepSeconds: number;
  power: (number | null)[];
  heartRate: (number | null)[];
  cadence: (number | null)[];
  targetPower: (number | null)[];
}

/** The answer to `GET /v1/rides/{rideId}`. */
export interface RideDetailResponse {
  id: string;
  startTime: string;
  endTime: string | null;
  date: string;
  workoutId: string | null;
  workoutName: string | null;
  ftp: number;
  devices: string[];
  summary: RideSummary;
  laps: Lap[];
  series?: RideSeriesBody | null;
}

// ---- Validation ------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

// The rides' schemas point into the ride record's by its file name.
ajv.addSchema(load("ride-record.schema.json"), "ride-record.schema.json");
const checkMetrics = ajv.compile<MetricsResponse>(load("metrics-response.schema.json"));
const checkRideMetrics = ajv.compile<RideMetricsResponse>(
  load("ride-metrics-response.schema.json"),
);
const checkRides = ajv.compile<RidesResponse>(load("rides-response.schema.json"));
const checkRideDetail = ajv.compile<RideDetailResponse>(load("ride-detail-response.schema.json"));

const MAX_REPORTED = 5;
/** The rounding of one decimal, with room for the representation of the sums. */
const ROUNDING = 0.1 + 1e-9;

function errorsOf(errors: typeof checkMetrics.errors): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

function checked<T>(
  valid: boolean,
  input: unknown,
  schemaErrors: typeof checkMetrics.errors,
  consistency: (value: T) => string[],
): Validation<T> {
  if (!valid) return { ok: false, errors: errorsOf(schemaErrors) };
  const errors = consistency(input as T).slice(0, MAX_REPORTED);
  return errors.length === 0 ? { ok: true, value: input as T } : { ok: false, errors };
}

/** 1 (Monday) to 7 (Sunday) of a real date. */
function weekdayOf(epochDay: number): number {
  return ((((epochDay + 3) % 7) + 7) % 7) + 1;
}

function realDate(errors: string[], at: string, date: string): number | null {
  const day = isoDateToEpochDay(date);
  if (day === null) errors.push(`${at} ${date} is not a real calendar date`);
  return day;
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

  const firstWeek = first - ((((first + 3) % 7) + 7) % 7);
  const lastWeek = last - ((((last + 3) % 7) + 7) % 7);
  const weeks = (lastWeek - firstWeek) / 7 + 1;
  if (response.weekly.length !== weeks) {
    errors.push(`/weekly has ${response.weekly.length} entries, the range touches ${weeks} weeks`);
  } else {
    for (const [index, week] of response.weekly.entries()) {
      const day = realDate(errors, `/weekly/${index}/weekStart`, week.weekStart);
      if (day === null) break;
      if (weekdayOf(day) !== 1) {
        errors.push(`/weekly/${index}/weekStart ${week.weekStart} is not a Monday`);
        break;
      }
      if (day !== firstWeek + 7 * index) {
        errors.push(
          `/weekly/${index}/weekStart ${week.weekStart} is not week ${index + 1} of the range`,
        );
        break;
      }
    }
  }

  if (response.powerCurve.windowDays !== days) {
    errors.push(
      `/powerCurve/windowDays is ${response.powerCurve.windowDays}, the range is ${days} days`,
    );
  }
  let previous: CurvePoint | undefined;
  for (const [index, point] of response.powerCurve.best.entries()) {
    const at = `/powerCurve/best/${index}`;
    if (previous !== undefined) {
      if (point.durationSec <= previous.durationSec) {
        errors.push(
          `${at}/durationSec ${point.durationSec} does not follow ${previous.durationSec}`,
        );
        break;
      }
      if (point.power > previous.power) {
        errors.push(
          `${at}/power ${point.power} is above the ${previous.durationSec} s power ${previous.power}`,
        );
        break;
      }
    }
    const day = realDate(errors, `${at}/date`, point.date);
    if (day !== null && !inRange(day)) {
      errors.push(`${at}/date ${point.date} is outside ${response.from} to ${response.to}`);
      break;
    }
    previous = point;
  }

  const suggestion = response.ftp.suggestion;
  if (suggestion !== null) {
    const { currentFtp, differencePercent, value } = suggestion;
    if (currentFtp === null) {
      if (differencePercent !== null) {
        errors.push("/ftp/suggestion/differencePercent must be null without a currentFtp");
      }
    } else if (
      differencePercent === null ||
      Math.abs(differencePercent - ((value - currentFtp) / currentFtp) * 100) > 0.051
    ) {
      errors.push(
        `/ftp/suggestion/differencePercent ${differencePercent} is not ${value} against ${currentFtp}`,
      );
    }
    if (response.ftp.suggestionNote !== null) {
      errors.push("/ftp/suggestionNote must be null while a suggestion is pending");
    }
  } else if (response.ftp.suggestionNote === null) {
    errors.push("/ftp/suggestionNote must say why there is no suggestion");
  }

  let previousDay = -Infinity;
  for (const [index, entry] of response.ftp.history.entries()) {
    const day = realDate(errors, `/ftp/history/${index}/date`, entry.date);
    if (day === null) break;
    if (day < previousDay) {
      errors.push(`/ftp/history/${index}/date ${entry.date} is before the entry above it`);
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
    if (w.minAtSec > response.durationSec)
      errors.push("/wPrimeBalance/minAtSec is after the end of the ride");
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
  for (const key of ["from", "to"] as const) {
    const value = response[key];
    if (value !== null) realDate(errors, `/${key}`, value);
  }
  if (response.from !== null && response.to !== null && response.from > response.to) {
    errors.push(`/from ${response.from} is after /to ${response.to}`);
  }
  if (response.rides.length > response.limit) {
    errors.push(`/rides has ${response.rides.length} entries, the limit is ${response.limit}`);
  }
  if (response.rides.length > Math.max(response.total - response.offset, 0)) {
    errors.push("/rides has more entries than are left after the offset");
  }
  const more = response.offset + response.rides.length < response.total;
  if (response.hasMore !== more) errors.push(`/hasMore must be ${more}`);
  const ids = new Set<string>();
  let previous = Number.POSITIVE_INFINITY;
  for (const [index, ride] of response.rides.entries()) {
    if (ids.has(ride.id)) errors.push(`/rides/${index}/id ${ride.id} is listed twice`);
    ids.add(ride.id);
    realDate(errors, `/rides/${index}/date`, ride.date);
    const start = Date.parse(ride.startTime);
    if (start > previous) errors.push(`/rides/${index} starts after the ride above it`);
    previous = start;
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
  const lastLap = response.laps[response.laps.length - 1];
  if (lastLap !== undefined && lastLap.endSeconds !== response.summary.durationSeconds) {
    errors.push(
      `The last lap ends at ${lastLap.endSeconds}, the ride is ${response.summary.durationSeconds} s long`,
    );
  }
  const series = response.series;
  if (series !== undefined && series !== null) {
    const expected = Math.ceil(response.summary.durationSeconds / series.stepSeconds);
    for (const key of ["power", "heartRate", "cadence", "targetPower"] as const) {
      if (series[key].length !== expected) {
        errors.push(`/series/${key} has ${series[key].length} points, expected ${expected}`);
        break;
      }
    }
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
