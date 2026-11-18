import {
  parseInstant,
  type SleepStage,
  type WellnessRecord,
  type WellnessRecordKind,
} from "@overdrive/contracts";
import type { HrSample } from "./trimp.ts";

/**
 * The raw Health Connect records as the service keeps them (`wellness_records.payload`, jsonb), and
 * the same records expanded for working out the daily figures. Storage is compact: instants become
 * epoch milliseconds, and heart-rate samples become `[offset from the record's start in ms, bpm]`
 * pairs, which is about a quarter of the size of the objects the phone sends.
 */

/** A record in the form the derivations read: instants as epoch milliseconds, samples as {@link HrSample}s. */
export type Rec =
  | { kind: "restingHeartRate"; id: string; tMs: number; bpm: number }
  | { kind: "heartRateSamples"; id: string; startMs: number; endMs: number; samples: HrSample[] }
  | {
      kind: "sleepSession";
      id: string;
      startMs: number;
      endMs: number;
      stages: { stage: SleepStage; startMs: number; endMs: number }[] | null;
    }
  | { kind: "respiratoryRate"; id: string; tMs: number; rate: number }
  | { kind: "oxygenSaturation"; id: string; tMs: number; percent: number }
  | { kind: "steps"; id: string; startMs: number; endMs: number; count: number }
  | {
      kind: "exerciseSession";
      id: string;
      type: string;
      title: string | null;
      startMs: number;
      endMs: number;
      /** The samples the phone attached to the session; null when it attached none. */
      samples: HrSample[] | null;
    }
  | { kind: "heartRateVariabilityRmssd"; id: string; tMs: number; ms: number }
  | { kind: "weight"; id: string; tMs: number; kg: number };

/** The compact payload as stored; every key is short on purpose. */
type Payload = Record<string, unknown>;

export interface Stored {
  kind: WellnessRecordKind;
  startMs: number;
  endMs: number;
  payload: Payload;
}

const ms = (text: string): number => parseInstant(text) as number;

/** `[offset ms, bpm]` pairs, relative to `originMs`. */
function packSamples(samples: readonly { t: string; bpm: number }[], originMs: number) {
  return samples.map((sample): [number, number] => [ms(sample.t) - originMs, sample.bpm]);
}

function unpackSamples(packed: unknown, originMs: number): HrSample[] {
  return (packed as [number, number][]).map(([offset, bpm]) => ({ tMs: originMs + offset, bpm }));
}

/** The compact form of a validated record, with the time span it covers (a point has start = end). */
export function toStored(record: WellnessRecord): Stored {
  switch (record.kind) {
    case "restingHeartRate": {
      const t = ms(record.time);
      return { kind: record.kind, startMs: t, endMs: t, payload: { t, bpm: record.bpm } };
    }
    case "respiratoryRate": {
      const t = ms(record.time);
      return { kind: record.kind, startMs: t, endMs: t, payload: { t, rate: record.rate } };
    }
    case "oxygenSaturation": {
      const t = ms(record.time);
      return { kind: record.kind, startMs: t, endMs: t, payload: { t, percent: record.percent } };
    }
    case "heartRateVariabilityRmssd": {
      const t = ms(record.time);
      return { kind: record.kind, startMs: t, endMs: t, payload: { t, ms: record.ms } };
    }
    case "weight": {
      const t = ms(record.time);
      return { kind: record.kind, startMs: t, endMs: t, payload: { t, kg: record.kg } };
    }
    case "steps": {
      const start = ms(record.start);
      const end = ms(record.end);
      return {
        kind: record.kind,
        startMs: start,
        endMs: end,
        payload: { s: start, e: end, count: record.count },
      };
    }
    case "heartRateSamples": {
      const start = ms(record.start);
      const end = ms(record.end);
      return {
        kind: record.kind,
        startMs: start,
        endMs: end,
        payload: { s: start, e: end, h: packSamples(record.samples, start) },
      };
    }
    case "sleepSession": {
      const start = ms(record.start);
      const end = ms(record.end);
      const payload: Payload = { s: start, e: end };
      if (record.stages !== undefined) {
        payload.g = record.stages.map((stage) => [stage.stage, ms(stage.start), ms(stage.end)]);
      }
      return { kind: record.kind, startMs: start, endMs: end, payload };
    }
    case "exerciseSession": {
      const start = ms(record.start);
      const end = ms(record.end);
      const payload: Payload = { s: start, e: end, type: record.type };
      if (record.title !== undefined) payload.title = record.title;
      if (record.heartRateSamples !== undefined) {
        payload.h = packSamples(record.heartRateSamples, start);
      }
      return { kind: record.kind, startMs: start, endMs: end, payload };
    }
  }
}

/** The record a stored row holds, for the derivations. */
export function fromStored(kind: string, id: string, payload: Payload): Rec | null {
  const p = payload as Record<string, number | string | unknown[]>;
  switch (kind) {
    case "restingHeartRate":
      return { kind, id, tMs: p.t as number, bpm: p.bpm as number };
    case "respiratoryRate":
      return { kind, id, tMs: p.t as number, rate: p.rate as number };
    case "oxygenSaturation":
      return { kind, id, tMs: p.t as number, percent: p.percent as number };
    case "heartRateVariabilityRmssd":
      return { kind, id, tMs: p.t as number, ms: p.ms as number };
    case "weight":
      return { kind, id, tMs: p.t as number, kg: p.kg as number };
    case "steps":
      return { kind, id, startMs: p.s as number, endMs: p.e as number, count: p.count as number };
    case "heartRateSamples":
      return {
        kind,
        id,
        startMs: p.s as number,
        endMs: p.e as number,
        samples: unpackSamples(p.h, p.s as number),
      };
    case "sleepSession":
      return {
        kind,
        id,
        startMs: p.s as number,
        endMs: p.e as number,
        stages:
          p.g === undefined
            ? null
            : (p.g as [SleepStage, number, number][]).map(([stage, startMs, endMs]) => ({
                stage,
                startMs,
                endMs,
              })),
      };
    case "exerciseSession":
      return {
        kind,
        id,
        type: p.type as string,
        title: typeof p.title === "string" ? p.title : null,
        startMs: p.s as number,
        endMs: p.e as number,
        samples: p.h === undefined ? null : unpackSamples(p.h, p.s as number),
      };
    default:
      return null;
  }
}
