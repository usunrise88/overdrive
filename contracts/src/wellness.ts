import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isIsoDate, isoDateToEpochDay, rangeDays } from "./plan.ts";

// ---- Sync API: wellness, recovery and watch activities (spec §6.2, §6.4, §6.7, §8.3, ADR 0023) ----
// Hand-written mirrors of schema/wellness-upload.schema.json, wellness-upload-response.schema.json,
// wellness-response.schema.json, recovery-response.schema.json and activity.schema.json, which are the
// source of truth; the fixtures test keeps the two honest. Kotlin's `WellnessApiJson` reads and writes
// the same bodies against the same fixtures.
//
// HEALTH DATA IS PRIVATE: no error message built here names a measured value, only a record's index and
// a field's path and what rule it breaks.

/** At most this many records in one `POST /v1/wellness` (413 `payload_too_large` above). */
export const WELLNESS_MAX_RECORDS = 2000;
/** At most this many bytes in one `POST /v1/wellness` (413 `payload_too_large` above). */
export const WELLNESS_MAX_BYTES: number = 4 * 1024 * 1024;
/** The longest range `GET /v1/wellness` serves, in days, both ends included. */
export const WELLNESS_MAX_RANGE_DAYS = 186;

// ---- The upload ---------------------------------------------------------------------------------

export const WELLNESS_RECORD_KINDS = [
  "restingHeartRate",
  "heartRateSamples",
  "sleepSession",
  "respiratoryRate",
  "oxygenSaturation",
  "steps",
  "exerciseSession",
  "heartRateVariabilityRmssd",
  "weight",
] as const;

export type WellnessRecordKind = (typeof WELLNESS_RECORD_KINDS)[number];

export const SLEEP_STAGES = [
  "awake",
  "awake_in_bed",
  "out_of_bed",
  "sleeping",
  "light",
  "deep",
  "rem",
  "unknown",
] as const;

export type SleepStage = (typeof SLEEP_STAGES)[number];

interface RecordBase {
  /** The Health Connect record id. */
  id: string;
  /** metadata.lastModifiedTime (UTC). */
  lastModified?: string;
  zoneOffsetSeconds?: number;
}

export interface HeartRateSample {
  t: string;
  bpm: number;
}

export interface RestingHeartRateRecord extends RecordBase {
  kind: "restingHeartRate";
  time: string;
  bpm: number;
}

export interface HeartRateSamplesRecord extends RecordBase {
  kind: "heartRateSamples";
  start: string;
  end: string;
  samples: HeartRateSample[];
}

export interface SleepSessionRecord extends RecordBase {
  kind: "sleepSession";
  start: string;
  end: string;
  title?: string;
  stages?: { stage: SleepStage; start: string; end: string }[];
}

export interface RespiratoryRateRecord extends RecordBase {
  kind: "respiratoryRate";
  time: string;
  rate: number;
}

export interface OxygenSaturationRecord extends RecordBase {
  kind: "oxygenSaturation";
  time: string;
  percent: number;
}

export interface StepsRecord extends RecordBase {
  kind: "steps";
  start: string;
  end: string;
  count: number;
}

export interface ExerciseSessionRecord extends RecordBase {
  kind: "exerciseSession";
  /** The exercise type as a lower-case token: `biking`, `running`, `other_workout`, … */
  type: string;
  title?: string;
  start: string;
  end: string;
  heartRateSamples?: HeartRateSample[];
}

export interface HeartRateVariabilityRmssdRecord extends RecordBase {
  kind: "heartRateVariabilityRmssd";
  time: string;
  ms: number;
}

/** WeightRecord: body weight in kilograms at a time (spec §12.5). */
export interface WeightRecord extends RecordBase {
  kind: "weight";
  time: string;
  kg: number;
}

export type WellnessRecord =
  | RestingHeartRateRecord
  | HeartRateSamplesRecord
  | SleepSessionRecord
  | RespiratoryRateRecord
  | OxygenSaturationRecord
  | StepsRecord
  | ExerciseSessionRecord
  | HeartRateVariabilityRmssdRecord
  | WeightRecord;

/** The body of `POST /v1/wellness`. */
export interface WellnessUpload {
  source: "healthconnect";
  device?: string;
  records: WellnessRecord[];
}

export interface RecordCounts {
  accepted: number;
  duplicates: number;
  rejected: number;
}

/** What `POST /v1/wellness` answers: counts only, never a value. */
export interface WellnessUploadResponse extends RecordCounts {
  byType: Partial<Record<WellnessRecordKind, RecordCounts>>;
  generatedAt: string;
}

// ---- Daily figures, baselines, recovery -----------------------------------------------------------

/** The metrics with a baseline, in the order the companion shows them. */
export const WELLNESS_METRICS = [
  "restingHr",
  "sleepSeconds",
  "bedtimeMinutes",
  "wakeMinutes",
  "respiratoryRate",
  "spo2",
  "sleepingHr",
  "hrvRmssd",
] as const;

export type WellnessMetric = (typeof WELLNESS_METRICS)[number];

/** Where a daily figure came from. */
export type WellnessSourceId = "healthconnect" | "intervals";

export type Deviation = "inside" | "above" | "below";

/** One metric on one day, with the personal baseline in force that day. */
export interface Reading {
  value: number | null;
  source: WellnessSourceId | null;
  baselineLow: number | null;
  baselineHigh: number | null;
  deviation: Deviation | null;
  /** Days in a row up to and including this one outside the range on the same side; 0 otherwise. */
  streak: number;
}

export interface WellnessDay {
  /** The local day; a night belongs to the date of the morning it ends. */
  date: string;
  restingHr: Reading;
  sleepSeconds: Reading;
  bedtimeMinutes: Reading;
  wakeMinutes: Reading;
  respiratoryRate: Reading;
  spo2: Reading;
  sleepingHr: Reading;
  hrvRmssd: Reading;
  sleepStart: string | null;
  sleepEnd: string | null;
  steps: number | null;
  hrvSdnn: number | null;
  /** The day's body weight in kilograms (spec §12.5). */
  weightKg: number | null;
  /** The mean of the daily weights of the seven days up to and including this one; null with no weighing in them. */
  weightKg7d: number | null;
}

export interface UsualRange {
  low: number;
  high: number;
}

/** The answer to `GET /v1/wellness?from&to`. */
export interface WellnessResponse {
  from: string;
  to: string;
  generatedAt: string;
  stale: boolean;
  nightsLogged: number;
  nightsNeeded: number;
  baselineDays: number;
  usual: { bedtime: UsualRange | null; wake: UsualRange | null };
  days: WellnessDay[];
}

export type RecoveryState = "ready" | "caution" | "rest" | "learning" | "noData";
export type RecoveryRule = "R1" | "R2" | "C1" | "C2" | "C3" | "OK";

export type RecoveryMetric =
  | "restingHr"
  | "sleepSeconds"
  | "bedtimeMinutes"
  | "respiratoryRate"
  | "spo2"
  | "sleepingHr"
  | "hrvRmssd";

export type RecoveryUnit = "bpm" | "s" | "min" | "/min" | "%" | "ms";

export interface RecoveryFact {
  metric: RecoveryMetric;
  label: string;
  value: number | null;
  unit: RecoveryUnit;
  baselineLow: number | null;
  baselineHigh: number | null;
  deviation: Deviation | null;
  streak: number;
}

/** The answer to `GET /v1/recovery`. */
export interface RecoveryResponse {
  date: string;
  state: RecoveryState;
  reason: string;
  guidance: string | null;
  rule: RecoveryRule | null;
  nightsLogged: number;
  nightsNeeded: number;
  generatedAt: string;
  stale: boolean;
  facts: RecoveryFact[];
}

// ---- Activities from the watch ---------------------------------------------------------------------

export interface HeartRateStats {
  samples: number;
  medianGapSeconds: number | null;
  maxGapSeconds: number | null;
  coverage: number;
}

/** The row of a history list: the first part of an {@link Activity}. */
export interface ActivitySummary {
  /** `watch-<record id>`, or `fit-<16 hex>` for an imported outdoor ride (spec §11.8). */
  id: string;
  kind: "activity";
  /** `watch`: a workout from Health Connect; `fit`: a FIT file imported by the companion or the Telegram bot. */
  source: "watch" | "fit";
  /** The exercise type as the phone named it, or `cycling-outdoor` for an imported ride. */
  type: string;
  name: string;
  start: string;
  end: string;
  /** The local day of `start`. */
  startDate: string;
  durationSeconds: number;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  load: number | null;
  loadApproximate: boolean;
  countsTowardLoad: boolean;
}

/** The answer to `GET /v1/activities/{id}`. */
export interface Activity extends ActivitySummary {
  overlapsRideId: string | null;
  loadNote: string;
  thresholdHr: number | null;
  maxHr: number | null;
  heartRate: HeartRateStats;
  series: { t: number; heartRate: number }[];
  generatedAt: string;
  /** Only an imported FIT file whose file says them has them (an absent key, or null on read, is not known); a watch workout has neither. */
  distanceMeters?: number | null;
  elevationGainMeters?: number | null;
}

// ---- Validation -----------------------------------------------------------------------------------

const MAX_REPORTED = 20;

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): Record<string, unknown> {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

/** The upload's JSON Schema, whole (the records are a `oneOf` of the kinds). */
export const wellnessUploadJsonSchema: Readonly<Record<string, unknown>> = load(
  "wellness-upload.schema.json",
);

const uploadSchema = wellnessUploadJsonSchema as {
  $defs: Record<string, unknown>;
  properties: Record<string, unknown>;
};
ajv.addSchema(uploadSchema, "wellness-upload.schema.json");

// The batch is checked in two steps so that an error names the field of the record that is wrong, not
// the dozen branches of a `oneOf`: first the envelope with each record only needing a known kind, then
// each record against the schema of its own kind (the same definitions the whole schema refers to).
const checkEnvelope = ajv.compile({
  ...uploadSchema,
  $id: "wellness-upload-envelope",
  properties: {
    ...uploadSchema.properties,
    records: {
      type: "array",
      maxItems: WELLNESS_MAX_RECORDS,
      items: {
        type: "object",
        required: ["kind"],
        properties: { kind: { enum: [...WELLNESS_RECORD_KINDS] } },
      },
    },
  },
});

const checkKind = new Map<WellnessRecordKind, ValidateFunction>(
  WELLNESS_RECORD_KINDS.map((kind) => [
    kind,
    ajv.compile({ $ref: `wellness-upload.schema.json#/$defs/${kind}` }),
  ]),
);

/** A property name that is safe to repeat in a message: an identifier, which cannot be a reading. */
const IDENTIFIER = /^[A-Za-z][A-Za-z0-9_]{0,40}$/;

function describe(
  prefix: string,
  errors: readonly {
    instancePath: string;
    keyword: string;
    message?: string | undefined;
    params: Record<string, unknown>;
  }[],
): string[] {
  return errors.map((error) => {
    let message = error.message ?? "is invalid";
    if (error.keyword === "additionalProperties") {
      const name = error.params.additionalProperty;
      message =
        typeof name === "string" && IDENTIFIER.test(name)
          ? `must not have the property ${name}`
          : "must not have additional properties";
    }
    return `${prefix}${error.instancePath || ""} ${message}`.trim();
  });
}

const INSTANT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

/**
 * The epoch milliseconds of a UTC instant as the contracts write it (`2026-11-18T06:45:30.123456Z`,
 * fractions beyond milliseconds are dropped), or null when it is not a real date and time.
 */
export function parseInstant(text: string): number | null {
  const match = INSTANT.exec(text);
  if (match === null) return null;
  const [, year, month, day, hour, minute, second, fraction] = match;
  if (!isIsoDate(`${year}-${month}-${day}`)) return null;
  if (Number(hour) > 23 || Number(minute) > 59 || Number(second) > 59) return null;
  return (
    Date.UTC(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second),
    ) + Number((fraction ?? "").padEnd(3, "0").slice(0, 3))
  );
}

function span(record: WellnessRecord): { start: string; end: string } | null {
  switch (record.kind) {
    case "heartRateSamples":
    case "sleepSession":
    case "steps":
    case "exerciseSession":
      return { start: record.start, end: record.end };
    default:
      return null;
  }
}

function timesOf(record: WellnessRecord): { path: string; value: string }[] {
  const times: { path: string; value: string }[] = [];
  const add = (path: string, value: string | undefined) => {
    if (value !== undefined) times.push({ path, value });
  };
  add("/lastModified", record.lastModified);
  switch (record.kind) {
    case "restingHeartRate":
    case "respiratoryRate":
    case "oxygenSaturation":
    case "heartRateVariabilityRmssd":
    case "weight":
      add("/time", record.time);
      break;
    case "heartRateSamples":
      add("/start", record.start);
      add("/end", record.end);
      for (const [i, sample] of record.samples.entries()) add(`/samples/${i}/t`, sample.t);
      break;
    case "sleepSession":
      add("/start", record.start);
      add("/end", record.end);
      for (const [i, stage] of (record.stages ?? []).entries()) {
        add(`/stages/${i}/start`, stage.start);
        add(`/stages/${i}/end`, stage.end);
      }
      break;
    case "steps":
      add("/start", record.start);
      add("/end", record.end);
      break;
    case "exerciseSession":
      add("/start", record.start);
      add("/end", record.end);
      for (const [i, sample] of (record.heartRateSamples ?? []).entries()) {
        add(`/heartRateSamples/${i}/t`, sample.t);
      }
      break;
  }
  return times;
}

/**
 * What the schema cannot say about one record: every instant is a real date and time, and the end is
 * not before the start (after it for an exercise session; a stage ends after it starts too).
 */
export function wellnessRecordConsistencyErrors(record: WellnessRecord, prefix = ""): string[] {
  const errors: string[] = [];
  for (const { path, value } of timesOf(record)) {
    if (parseInstant(value) === null) errors.push(`${prefix}${path} is not a real date and time`);
  }
  if (errors.length > 0) return errors;
  const bounds = span(record);
  if (bounds !== null) {
    const start = parseInstant(bounds.start) as number;
    const end = parseInstant(bounds.end) as number;
    if (record.kind === "exerciseSession" ? end <= start : end < start) {
      errors.push(
        `${prefix}/end is ${record.kind === "exerciseSession" ? "not after" : "before"} /start`,
      );
    }
  }
  if (record.kind === "sleepSession") {
    for (const [i, stage] of (record.stages ?? []).entries()) {
      if ((parseInstant(stage.end) as number) < (parseInstant(stage.start) as number)) {
        errors.push(`${prefix}/stages/${i}/end is before /stages/${i}/start`);
      }
    }
  }
  return errors;
}

/**
 * Validates the body of `POST /v1/wellness`: the envelope, then every record against the schema of its
 * kind, then the rules a schema cannot state (real instants, end not before start, ids unique inside
 * the batch). The first {@link MAX_REPORTED} problems come back, each as `/records/<index>/<field> <rule>`;
 * **never a value**.
 */
export function validateWellnessUpload(input: unknown): Validation<WellnessUpload> {
  if (!checkEnvelope(input)) {
    return { ok: false, errors: describe("", checkEnvelope.errors ?? []).slice(0, MAX_REPORTED) };
  }
  const batch = input as unknown as WellnessUpload;
  const errors: string[] = [];
  const ids = new Set<string>();
  for (const [index, record] of batch.records.entries()) {
    const prefix = `/records/${index}`;
    const check = checkKind.get(record.kind) as ValidateFunction;
    if (!check(record)) {
      errors.push(...describe(prefix, check.errors ?? []));
    } else {
      errors.push(...wellnessRecordConsistencyErrors(record, prefix));
      if (ids.has(record.id)) errors.push(`${prefix}/id is used by an earlier record of the batch`);
      ids.add(record.id);
    }
    if (errors.length >= MAX_REPORTED) break;
  }
  return errors.length === 0
    ? { ok: true, value: batch }
    : { ok: false, errors: errors.slice(0, MAX_REPORTED) };
}

/** Validates an upload against the whole schema (a `oneOf` of the kinds), for the fixtures' sake. */
export const checkWellnessUploadSchema: ValidateFunction<WellnessUpload> =
  ajv.compile<WellnessUpload>({
    $ref: "wellness-upload.schema.json",
  });

const checkUploadResponse = ajv.compile<WellnessUploadResponse>(
  load("wellness-upload-response.schema.json"),
);
const checkWellnessResponse = ajv.compile<WellnessResponse>(load("wellness-response.schema.json"));
const checkRecoveryResponse = ajv.compile<RecoveryResponse>(load("recovery-response.schema.json"));
const checkActivity = ajv.compile<Activity>(load("activity.schema.json"));

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** The counts of an upload answer add up: the by-type entries sum to the totals. */
export function uploadResponseConsistencyErrors(response: WellnessUploadResponse): string[] {
  const sum: RecordCounts = { accepted: 0, duplicates: 0, rejected: 0 };
  for (const counts of Object.values(response.byType)) {
    sum.accepted += counts.accepted;
    sum.duplicates += counts.duplicates;
    sum.rejected += counts.rejected;
  }
  const errors: string[] = [];
  for (const key of ["accepted", "duplicates", "rejected"] as const) {
    if (sum[key] !== response[key]) {
      errors.push(`/${key} is ${response[key]}, the byType entries add up to ${sum[key]}`);
    }
  }
  return errors;
}

export function validateWellnessUploadResponse(input: unknown): Validation<WellnessUploadResponse> {
  if (!checkUploadResponse(input)) return { ok: false, errors: plain(checkUploadResponse.errors) };
  const errors = uploadResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

function readingErrors(at: string, reading: Reading): string[] {
  const errors: string[] = [];
  if (reading.value === null) {
    if (reading.source !== null) errors.push(`${at}/source must be null without a value`);
    if (reading.deviation !== null) errors.push(`${at}/deviation must be null without a value`);
    if (reading.streak !== 0) errors.push(`${at}/streak must be 0 without a value`);
  } else if (reading.source === null) {
    errors.push(`${at}/source is needed with a value`);
  }
  if ((reading.baselineLow === null) !== (reading.baselineHigh === null)) {
    errors.push(`${at}: baselineLow and baselineHigh are both null or both set`);
  } else if (
    reading.baselineLow !== null &&
    reading.baselineHigh !== null &&
    reading.baselineLow > reading.baselineHigh
  ) {
    errors.push(`${at}/baselineLow is above baselineHigh`);
  }
  if (reading.deviation !== null && reading.baselineLow === null) {
    errors.push(`${at}/deviation needs a range`);
  }
  if (reading.deviation === "inside" && reading.streak !== 0) {
    errors.push(`${at}/streak must be 0 inside the range`);
  }
  if ((reading.deviation === "above" || reading.deviation === "below") && reading.streak < 1) {
    errors.push(`${at}/streak must be at least 1 outside the range`);
  }
  return errors;
}

/**
 * What the schema cannot say about a wellness answer (see its description): a real range of at most
 * {@link WELLNESS_MAX_RANGE_DAYS} days, one entry for every date in it in order, and readings whose
 * parts agree.
 */
export function wellnessResponseConsistencyErrors(response: WellnessResponse): string[] {
  const days = rangeDays(response.from, response.to);
  if (days === null) return ["/from and /to must be real calendar dates"];
  if (days < 1) return [`/from ${response.from} is after /to ${response.to}`];
  if (days > WELLNESS_MAX_RANGE_DAYS) {
    return [`The range spans ${days} days, at most ${WELLNESS_MAX_RANGE_DAYS} are served`];
  }
  const errors: string[] = [];
  const first = isoDateToEpochDay(response.from) as number;
  if (response.days.length !== days) {
    errors.push(`/days has ${response.days.length} entries, the range has ${days} days`);
  }
  for (const [index, day] of response.days.entries()) {
    const at = `/days/${index}`;
    const expected = first + index;
    if (isoDateToEpochDay(day.date) !== expected) {
      errors.push(`${at}/date ${day.date} is not day ${index + 1} of the range`);
      continue;
    }
    for (const metric of WELLNESS_METRICS)
      errors.push(...readingErrors(`${at}/${metric}`, day[metric]));
    for (const key of ["sleepStart", "sleepEnd"] as const) {
      const value = day[key];
      if (value !== null && parseInstant(value) === null) {
        errors.push(`${at}/${key} is not a real date and time`);
      }
    }
  }
  for (const key of ["bedtime", "wake"] as const) {
    const range = response.usual[key];
    if (range !== null && range.low > range.high) errors.push(`/usual/${key}/low is above high`);
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateWellnessResponse(input: unknown): Validation<WellnessResponse> {
  if (!checkWellnessResponse(input))
    return { ok: false, errors: plain(checkWellnessResponse.errors) };
  const errors = wellnessResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about a recovery answer (see its description). */
export function recoveryResponseConsistencyErrors(response: RecoveryResponse): string[] {
  const errors: string[] = [];
  if (!isIsoDate(response.date)) errors.push("/date is not a real calendar date");
  const verdict =
    response.state === "ready" || response.state === "caution" || response.state === "rest";
  if (verdict) {
    if (response.rule === null) errors.push("/rule is needed for a verdict");
    if (response.guidance === null) errors.push("/guidance is needed for a verdict");
    if (response.facts.length === 0) errors.push("/facts needs at least one fact for a verdict");
    if (response.nightsLogged < response.nightsNeeded) {
      errors.push("/nightsLogged is below /nightsNeeded: that is learning, not a verdict");
    }
  } else {
    if (response.rule !== null) errors.push("/rule must be null without a verdict");
    if (response.guidance !== null) errors.push("/guidance must be null without a verdict");
  }
  if (response.state === "noData" && response.facts.length > 0) {
    errors.push("/facts must be empty for noData");
  }
  if (response.state === "ready" && response.rule !== "OK")
    errors.push("/rule must be OK for ready");
  if (response.state === "caution" && !["C1", "C2", "C3"].includes(response.rule ?? "")) {
    errors.push("/rule must be C1, C2 or C3 for caution");
  }
  if (response.state === "rest" && !["R1", "R2"].includes(response.rule ?? "")) {
    errors.push("/rule must be R1 or R2 for rest");
  }
  const seen = new Set<string>();
  for (const [index, fact] of response.facts.entries()) {
    if (seen.has(fact.metric)) errors.push(`/facts/${index}/metric ${fact.metric} is listed twice`);
    seen.add(fact.metric);
    errors.push(
      ...readingErrors(`/facts/${index}`, {
        ...fact,
        source: fact.value === null ? null : "healthconnect",
      }),
    );
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateRecoveryResponse(input: unknown): Validation<RecoveryResponse> {
  if (!checkRecoveryResponse(input))
    return { ok: false, errors: plain(checkRecoveryResponse.errors) };
  const errors = recoveryResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about a watch activity (see its description). */
export function activityConsistencyErrors(activity: Activity): string[] {
  const errors: string[] = [];
  const start = parseInstant(activity.start);
  const end = parseInstant(activity.end);
  if (start === null) errors.push("/start is not a real date and time");
  if (end === null) errors.push("/end is not a real date and time");
  if (start !== null && end !== null) {
    if (end <= start) errors.push("/end is not after /start");
    else if (activity.durationSeconds !== Math.round((end - start) / 1000)) {
      errors.push("/durationSeconds is not the difference of /start and /end");
    }
  }
  if (!isIsoDate(activity.startDate)) errors.push("/startDate is not a real calendar date");
  if (!activity.id.startsWith(`${activity.source}-`)) {
    errors.push(`/id must start with ${activity.source}-`);
  }
  if (!activity.countsTowardLoad && activity.load !== null) {
    errors.push("/load must be null when the activity does not count toward load");
  }
  if (activity.countsTowardLoad === (activity.overlapsRideId !== null)) {
    errors.push("/overlapsRideId is set exactly when /countsTowardLoad is false");
  }
  for (let i = 1; i < activity.series.length; i++) {
    const before = activity.series[i - 1];
    const here = activity.series[i];
    if (before !== undefined && here !== undefined && here.t <= before.t) {
      errors.push(`/series/${i}/t is not after the point before it`);
      break;
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateActivity(input: unknown): Validation<Activity> {
  if (!checkActivity(input)) return { ok: false, errors: plain(checkActivity.errors) };
  const errors = activityConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
