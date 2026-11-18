import type {
  WellnessSourceId,
  WellnessUpload,
  WellnessUploadResponse,
} from "@overdrive/contracts";
import type { IntervalsClient, IntervalsWellness } from "../intervals/client.ts";
import {
  isPlausible,
  METRIC_KEYS,
  PLAUSIBLE_WEIGHT_KG,
  SOURCE_IDS,
  STORED_METRICS,
  type StoredMetric,
} from "./metrics.ts";

/**
 * Wellness input sits behind one interface (spec §6.2) so the wearable can change without touching
 * the analysis. A source either **pushes** (the companion app uploads Health Connect batches,
 * {@link PushWellnessSource}) or is **pulled** (the service reads intervals.icu,
 * {@link PullWellnessSource}); both end as daily figures per source in `wellness_daily`, and the
 * analysis reads them through the configured preference.
 */

export interface WellnessSource {
  readonly id: WellnessSourceId;
  readonly mode: "push" | "pull";
}

/** The figures a pull source holds for one local day. */
export interface DailyObservation {
  /** `YYYY-MM-DD`. */
  date: string;
  values: Partial<Record<StoredMetric, number>>;
}

export interface PullWellnessSource extends WellnessSource {
  readonly mode: "pull";
  /** The days from `from` to `to` (local dates, both included) the source has anything for. */
  read(from: string, to: string): Promise<DailyObservation[]>;
}

export interface PushWellnessSource extends WellnessSource {
  readonly mode: "push";
  /** Stores a validated batch, works the daily figures out and answers with counts. */
  ingest(upload: WellnessUpload, device: string): Promise<WellnessUploadResponse>;
}

// ---- Which source wins ---------------------------------------------------------------------------------

/** For a metric, the sources to take its value from, the preferred first; one not listed is not used for it. */
export type SourceOrder = (metric: StoredMetric) => readonly WellnessSourceId[];

export const DEFAULT_SOURCE_ORDER = "healthconnect,intervals";

function parseSources(text: string, at: string): WellnessSourceId[] {
  const names = text
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name !== "");
  if (names.length === 0) throw new Error(`${at} needs at least one source`);
  const sources: WellnessSourceId[] = [];
  for (const name of names) {
    if (!(SOURCE_IDS as readonly string[]).includes(name)) {
      throw new Error(`${at}: ${JSON.stringify(name)} is not a source (${SOURCE_IDS.join(", ")})`);
    }
    if (sources.includes(name as WellnessSourceId))
      throw new Error(`${at}: ${name} is listed twice`);
    sources.push(name as WellnessSourceId);
  }
  return sources;
}

/**
 * Parses `WELLNESS_SOURCE_ORDER`: the default order of the sources, `healthconnect,intervals`, and
 * optionally a different order for some metrics after semicolons: `healthconnect,intervals;sleepingHr=intervals`
 * (more sources for one metric separated by commas, `restingHr=intervals,healthconnect`). Every
 * metric takes its value from the first source in its order that has one; a source left out of the
 * list of a metric is not used for it. Throws with a message that names the problem, never a value.
 */
export function parseSourceOrder(text: string): SourceOrder {
  const [base, ...overrides] = text.split(";");
  const defaults = parseSources(base ?? "", "WELLNESS_SOURCE_ORDER");
  const perMetric = new Map<StoredMetric, WellnessSourceId[]>();
  for (const override of overrides) {
    if (override.trim() === "") continue;
    const equals = override.indexOf("=");
    if (equals < 0) {
      throw new Error("WELLNESS_SOURCE_ORDER: a per-metric part is metric=source[,source]");
    }
    const metric = override.slice(0, equals).trim();
    if (!(STORED_METRICS as readonly string[]).includes(metric)) {
      throw new Error(
        `WELLNESS_SOURCE_ORDER: ${JSON.stringify(metric)} is not a metric (${METRIC_KEYS.join(", ")}, steps, hrvSdnn, weightKg)`,
      );
    }
    perMetric.set(
      metric as StoredMetric,
      parseSources(override.slice(equals + 1), `WELLNESS_SOURCE_ORDER ${metric}`),
    );
  }
  return (metric) => perMetric.get(metric) ?? defaults;
}

// ---- intervals.icu -------------------------------------------------------------------------------------

/** What of one intervals.icu day is a usable figure: present, a number, and plausible for its metric. */
export function observationFromIntervals(day: IntervalsWellness): DailyObservation {
  const values: Partial<Record<StoredMetric, number>> = {};
  const take = (
    metric: StoredMetric,
    value: number | null | undefined,
    plausible: (n: number) => boolean,
  ): void => {
    if (typeof value === "number" && Number.isFinite(value) && plausible(value)) {
      values[metric] = value;
    }
  };
  take("restingHr", day.restingHR, (n) => isPlausible("restingHr", n));
  take("hrvRmssd", day.hrv, (n) => isPlausible("hrvRmssd", n));
  take("hrvSdnn", day.hrvSDNN, (n) => n > 0 && n <= 500);
  take("weightKg", day.weight, (n) => n >= PLAUSIBLE_WEIGHT_KG[0] && n <= PLAUSIBLE_WEIGHT_KG[1]);
  take("sleepSeconds", day.sleepSecs, (n) => isPlausible("sleepSeconds", n));
  take("spo2", day.spO2, (n) => isPlausible("spo2", n));
  take("respiratoryRate", day.respiration, (n) => isPlausible("respiratoryRate", n));
  take("sleepingHr", day.avgSleepingHR, (n) => isPlausible("sleepingHr", n));
  take("steps", day.steps, (n) => n > 0 && n <= 1_000_000);
  return { date: day.id, values };
}

/**
 * The athlete's wellness in intervals.icu, read through the same client as the plan (the owner's own
 * data, the purpose of the API key): a natively integrated device sends its resting heart rate, sleep, HRV,
 * oxygen saturation and respiration there. What it sends is the device's choice; a day or a field that is
 * missing is just not there. Which field means what is as intervals.icu documents it (`hrv` is rMSSD) and
 * could not be checked without an account (ADR 0023).
 */
export function createIntervalsWellnessSource(
  client: Pick<IntervalsClient, "listWellness">,
): PullWellnessSource {
  return {
    id: "intervals",
    mode: "pull",
    async read(from, to) {
      const days = await client.listWellness(from, to);
      return days
        .filter((day) => day.id >= from && day.id <= to)
        .map(observationFromIntervals)
        .filter((observation) => Object.keys(observation.values).length > 0);
    },
  };
}
