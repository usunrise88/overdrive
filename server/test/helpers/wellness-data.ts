import type { HeartRateSamplesRecord, WellnessRecord, WellnessUpload } from "@overdrive/contracts";
import { addDaysToIsoDate } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { bearer, COMPANION } from "./app.ts";
import type { Pattern } from "./wellness.ts";
import { TODAY } from "./wellness.ts";

/** A synthetic Health Connect upload: every number in it is invented. */
export const batch = (...records: WellnessRecord[]): WellnessUpload => ({
  source: "healthconnect",
  device: "Synthetic phone",
  records,
});

export const resting = (id: string, time: string, bpm: number): WellnessRecord => ({
  kind: "restingHeartRate",
  id,
  time,
  bpm,
});

export const sleep = (id: string, start: string, end: string, extra = {}): WellnessRecord => ({
  kind: "sleepSession",
  id,
  start,
  end,
  ...extra,
});

export const respiratory = (id: string, time: string, rate: number): WellnessRecord => ({
  kind: "respiratoryRate",
  id,
  time,
  rate,
});

export const oxygen = (id: string, time: string, percent: number): WellnessRecord => ({
  kind: "oxygenSaturation",
  id,
  time,
  percent,
});

export const hrv = (id: string, time: string, ms: number): WellnessRecord => ({
  kind: "heartRateVariabilityRmssd",
  id,
  time,
  ms,
});

export const steps = (id: string, start: string, end: string, count: number): WellnessRecord => ({
  kind: "steps",
  id,
  start,
  end,
  count,
});

/** Heart-rate samples every `everyMinutes` from `start` to `end` (UTC instants), `bpm(i)` for the i-th. */
export function heartRate(
  id: string,
  start: string,
  end: string,
  everyMinutes: number,
  bpm: (i: number) => number,
): WellnessRecord {
  const from = Date.parse(start);
  const to = Date.parse(end);
  const samples = [];
  for (let t = from, i = 0; t <= to; t += everyMinutes * 60_000, i++) {
    samples.push({ t: new Date(t).toISOString().replace(/\.000Z$/, "Z"), bpm: bpm(i) });
  }
  return { kind: "heartRateSamples", id, start, end, samples };
}

/** Heart-rate samples every `everySeconds` seconds from `start` for `seconds` seconds. */
export function heartRateSeconds(
  id: string,
  start: string,
  seconds: number,
  everySeconds: number,
  bpm: (i: number) => number,
): HeartRateSamplesRecord {
  const from = Date.parse(start);
  const samples = [];
  for (let s = 0, i = 0; s <= seconds; s += everySeconds, i++) {
    samples.push({
      t: new Date(from + s * 1000).toISOString().replace(/\.000Z$/, "Z"),
      bpm: bpm(i),
    });
  }
  return {
    kind: "heartRateSamples",
    id,
    start,
    end: new Date(from + seconds * 1000).toISOString().replace(/\.000Z$/, "Z"),
    samples,
  };
}

export const exercise = (
  id: string,
  type: string,
  start: string,
  end: string,
  extra: Record<string, unknown> = {},
): WellnessRecord =>
  ({ kind: "exerciseSession", id, type, start, end, ...extra }) as WellnessRecord;

export async function post(
  app: FastifyInstance,
  body: unknown,
  headers: Record<string, string> = bearer(COMPANION),
) {
  return app.inject({
    method: "POST",
    url: "/v1/wellness",
    headers: { "content-type": "application/json", ...headers },
    payload: typeof body === "string" ? body : JSON.stringify(body),
  });
}

export const get = (app: FastifyInstance, url: string) =>
  app.inject({ method: "GET", url, headers: bearer(COMPANION) });

/**
 * The records of one night ending on the morning of `date` (UTC, so the local date is the same): sleep from `bedtime` the evening
 * before to `wake`, a resting heart rate, respiratory and oxygen readings during the night and heart-rate samples every ten
 * minutes in it. Values are invented; `values` says them.
 */
export function nightRecords(
  date: string,
  values: {
    restingHr?: number;
    respiratoryRate?: number;
    spo2?: number;
    sleepingHr?: number;
    hrv?: number;
    bedtime?: string;
    wake?: string;
    stages?: boolean;
  },
): WellnessRecord[] {
  const bedtime = values.bedtime ?? "22:45";
  const wake = values.wake ?? "06:15";
  const evening = addDaysToIsoDate(date, -1);
  const start = `${evening}T${bedtime}:00Z`;
  const end = `${date}T${wake}:00Z`;
  const records: WellnessRecord[] = [sleep(`sleep-${date}`, start, end)];
  if (values.restingHr !== undefined) {
    records.push(resting(`rhr-${date}`, `${date}T${wake}:00Z`, values.restingHr));
  }
  if (values.respiratoryRate !== undefined) {
    records.push(respiratory(`resp-${date}`, `${date}T02:00:00Z`, values.respiratoryRate));
  }
  if (values.spo2 !== undefined) {
    records.push(oxygen(`spo2-${date}`, `${date}T03:00:00Z`, values.spo2));
  }
  if (values.hrv !== undefined) {
    records.push(hrv(`hrv-${date}`, `${date}T03:30:00Z`, values.hrv));
  }
  if (values.sleepingHr !== undefined) {
    const sleepingHr = values.sleepingHr;
    records.push(heartRate(`hr-${date}`, start, end, 10, () => sleepingHr));
  }
  return records;
}

/** Patterns for each metric, by days before `TODAY`. */
export type NightPatterns = {
  [K in "restingHr" | "respiratoryRate" | "spo2" | "sleepingHr" | "hrv"]?: Pattern;
};

/** A history of nights from `days` days before TODAY up to TODAY, one batch of records, from patterns. */
export function history(patterns: NightPatterns, days: number, firstBack = 0): WellnessRecord[] {
  const records: WellnessRecord[] = [];
  for (let back = days; back >= firstBack; back--) {
    const date = addDaysToIsoDate(TODAY, -back);
    const values: Parameters<typeof nightRecords>[1] = {};
    for (const key of ["restingHr", "respiratoryRate", "spo2", "sleepingHr", "hrv"] as const) {
      const value = patterns[key]?.(back);
      if (value !== undefined) values[key] = value;
    }
    records.push(...nightRecords(date, values));
  }
  return records;
}

/** Uploads records in batches of at most 1000. */
export async function uploadAll(app: FastifyInstance, records: WellnessRecord[]): Promise<void> {
  for (let i = 0; i < records.length; i += 1000) {
    const res = await post(app, batch(...records.slice(i, i + 1000)));
    if (res.statusCode !== 200) throw new Error(`upload failed: ${res.statusCode} ${res.body}`);
  }
}
