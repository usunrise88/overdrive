import { addDaysToIsoDate, isoDateToEpochDay } from "@overdrive/contracts";
import { localDate } from "../time.ts";
import { localParts, zonedInstant } from "../zoned.ts";
import { isPlausible, PLAUSIBLE_WEIGHT_KG, type StoredMetric } from "./metrics.ts";
import type { Rec } from "./records.ts";

/**
 * The daily figures worked out from the raw Health Connect records (spec §6.2, ADR 0023). A night
 * belongs to the local date (the service's `TIMEZONE`) of the morning it ends. Everything here is a
 * pure function of the records, so a record that arrives late, or is edited, just makes the day be
 * worked out again.
 *
 * - **Night.** The sleep sessions that end on the date and start between 18:00 the evening before and
 *   noon that day (later ones are naps and are not part of the night). Sleep is the sum of their
 *   asleep time (stages `sleeping`, `light`, `deep`, `rem` and `unknown` count, `awake`, `awake_in_bed`
 *   and `out_of_bed` do not; without stages the whole session counts). Bedtime is the earliest start and
 *   waking the latest end, as minutes from local midnight (a bedtime before midnight is negative: 23:05
 *   is -55). A night shorter than ten minutes is not a night.
 * - **Resting heart rate.** The latest `restingHeartRate` record of the local date.
 * - **Respiratory rate, oxygen saturation, rMSSD.** The mean of the plausible records inside the night
 *   when there is one, else of those of the local date.
 * - **Heart rate in sleep.** The mean of the heart-rate samples inside the night, **only when they are
 *   dense enough**: at least {@link SLEEP_HR_MIN_SAMPLES} samples and one every
 *   {@link SLEEP_HR_MAX_MEAN_GAP_MINUTES} minutes or better on average over the night. Otherwise none:
 *   a mean of a handful of samples would be a guess.
 * - **Steps.** The sum of the records that start on the local date.
 * - **Weight.** The mean of the plausible weighings of the local date (spec §12.5); the 7-day average is worked out when the
 *   days are read (`series.ts`). Nothing in the service changes because of it.
 *
 * A reading outside the plausible range of its metric (`metrics.ts`) is a sensor glitch and is ignored.
 */

/** A night's heart rate needs this many samples ... */
export const SLEEP_HR_MIN_SAMPLES = 20;
/** ... and at least one every this many minutes on average over the night. */
export const SLEEP_HR_MAX_MEAN_GAP_MINUTES = 20;
/** A night shorter than this is not a night. */
export const MIN_NIGHT_SECONDS = 600;

const ASLEEP = new Set(["sleeping", "light", "deep", "rem", "unknown"]);

export type DerivedDay = Partial<Record<StoredMetric, number>>;

/** The local-date window that holds the sleep sessions of the night ending on `date`. */
export function nightWindow(date: string, timezone: string): { fromMs: number; toMs: number } {
  return {
    fromMs: zonedInstant(addDaysToIsoDate(date, -1), { hour: 18, minute: 0 }, timezone).getTime(),
    toMs: zonedInstant(date, { hour: 12, minute: 0 }, timezone).getTime(),
  };
}

/** The span of instants whose records can matter for `date`: from the evening before to the end of the day. */
export function recordsWindow(date: string, timezone: string): { fromMs: number; toMs: number } {
  return {
    fromMs: nightWindow(date, timezone).fromMs,
    toMs: zonedInstant(addDaysToIsoDate(date, 1), { hour: 0, minute: 0 }, timezone).getTime(),
  };
}

const mean = (values: readonly number[]): number =>
  values.reduce((a, b) => a + b, 0) / values.length;

/** Minutes of local wall-clock time from midnight at the start of `date`; negative for the evening before. */
function minutesFromMidnight(instantMs: number, date: string, timezone: string): number {
  const parts = localParts(instantMs, timezone);
  const days = (isoDateToEpochDay(parts.date) as number) - (isoDateToEpochDay(date) as number);
  return days * 1440 + parts.hour * 60 + parts.minute;
}

function asleepSeconds(session: Extract<Rec, { kind: "sleepSession" }>): number {
  if (session.stages === null || session.stages.length === 0) {
    return (session.endMs - session.startMs) / 1000;
  }
  let total = 0;
  for (const stage of session.stages) {
    if (!ASLEEP.has(stage.stage)) continue;
    const from = Math.max(stage.startMs, session.startMs);
    const to = Math.min(stage.endMs, session.endMs);
    if (to > from) total += (to - from) / 1000;
  }
  return total;
}

/** The daily figures of one local date from the records around it. */
export function deriveDay(date: string, records: readonly Rec[], timezone: string): DerivedDay {
  const derived: DerivedDay = {};
  const window = nightWindow(date, timezone);

  // The night.
  const sessions = records.filter(
    (r): r is Extract<Rec, { kind: "sleepSession" }> =>
      r.kind === "sleepSession" &&
      r.startMs >= window.fromMs &&
      r.startMs < window.toMs &&
      localDate(r.endMs, timezone) === date,
  );
  let night: { fromMs: number; toMs: number } | null = null;
  if (sessions.length > 0) {
    const seconds = sessions.reduce((sum, s) => sum + asleepSeconds(s), 0);
    if (seconds >= MIN_NIGHT_SECONDS) {
      const fromMs = Math.min(...sessions.map((s) => s.startMs));
      const toMs = Math.max(...sessions.map((s) => s.endMs));
      night = { fromMs, toMs };
      if (isPlausible("sleepSeconds", seconds)) derived.sleepSeconds = Math.round(seconds);
      const bedtime = minutesFromMidnight(fromMs, date, timezone);
      if (isPlausible("bedtimeMinutes", bedtime)) derived.bedtimeMinutes = bedtime;
      const wake = minutesFromMidnight(toMs, date, timezone);
      if (isPlausible("wakeMinutes", wake) && wake < 1440) derived.wakeMinutes = wake;
      derived.sleepStartMs = fromMs;
      derived.sleepEndMs = toMs;
    }
  }

  const inScope = (tMs: number): boolean =>
    night === null ? localDate(tMs, timezone) === date : tMs >= night.fromMs && tMs <= night.toMs;

  const resting = records
    .filter(
      (r): r is Extract<Rec, { kind: "restingHeartRate" }> =>
        r.kind === "restingHeartRate" &&
        localDate(r.tMs, timezone) === date &&
        isPlausible("restingHr", r.bpm),
    )
    .sort((a, b) => a.tMs - b.tMs || a.id.localeCompare(b.id));
  const latest = resting[resting.length - 1];
  if (latest !== undefined) derived.restingHr = latest.bpm;

  const respiratory = records.flatMap((r) =>
    r.kind === "respiratoryRate" && inScope(r.tMs) && isPlausible("respiratoryRate", r.rate)
      ? [r.rate]
      : [],
  );
  if (respiratory.length > 0) derived.respiratoryRate = mean(respiratory);

  const oxygen = records.flatMap((r) =>
    r.kind === "oxygenSaturation" && inScope(r.tMs) && isPlausible("spo2", r.percent)
      ? [r.percent]
      : [],
  );
  if (oxygen.length > 0) derived.spo2 = mean(oxygen);

  const variability = records.flatMap((r) =>
    r.kind === "heartRateVariabilityRmssd" && inScope(r.tMs) && isPlausible("hrvRmssd", r.ms)
      ? [r.ms]
      : [],
  );
  if (variability.length > 0) derived.hrvRmssd = mean(variability);

  if (night !== null) {
    const bpm: number[] = [];
    for (const r of records) {
      if (r.kind !== "heartRateSamples") continue;
      for (const sample of r.samples) {
        if (
          sample.tMs >= (night as { fromMs: number }).fromMs &&
          sample.tMs <= (night as { toMs: number }).toMs &&
          isPlausible("sleepingHr", sample.bpm)
        ) {
          bpm.push(sample.bpm);
        }
      }
    }
    const minutes = (night.toMs - night.fromMs) / 60_000;
    if (
      bpm.length >= SLEEP_HR_MIN_SAMPLES &&
      minutes / bpm.length <= SLEEP_HR_MAX_MEAN_GAP_MINUTES
    ) {
      derived.sleepingHr = mean(bpm);
    }
  }

  const steps = records.filter(
    (r): r is Extract<Rec, { kind: "steps" }> =>
      r.kind === "steps" && localDate(r.startMs, timezone) === date,
  );
  if (steps.length > 0) derived.steps = steps.reduce((sum, r) => sum + r.count, 0);

  const weighings = records.flatMap((r) =>
    r.kind === "weight" &&
    localDate(r.tMs, timezone) === date &&
    r.kg >= PLAUSIBLE_WEIGHT_KG[0] &&
    r.kg <= PLAUSIBLE_WEIGHT_KG[1]
      ? [r.kg]
      : [],
  );
  if (weighings.length > 0) derived.weightKg = mean(weighings);

  return derived;
}

/**
 * The local dates whose figures a record can change: the dates it covers and the day after its end
 * (a record late in the evening belongs to the next morning's night). A record that spans more than
 * three days counts for its first and last date only.
 */
export function datesAffectedBy(startMs: number, endMs: number, timezone: string): string[] {
  const first = localDate(startMs, timezone);
  const last = addDaysToIsoDate(localDate(endMs, timezone), 1);
  const span = (isoDateToEpochDay(last) as number) - (isoDateToEpochDay(first) as number);
  if (span > 4) return [first, addDaysToIsoDate(first, 1), localDate(endMs, timezone), last];
  const dates: string[] = [];
  for (let day = first; day <= last; day = addDaysToIsoDate(day, 1)) dates.push(day);
  return dates;
}
