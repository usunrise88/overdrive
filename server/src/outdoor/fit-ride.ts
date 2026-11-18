import {
  FIT_FILE_ACTIVITY,
  FIT_SPORT_CYCLING,
  FIT_SPORT_E_BIKING,
  type FitFile,
  type FitRecord,
  type FitSession,
} from "../fit-decode.ts";
import type { HrSample } from "../wellness/trimp.ts";

/**
 * What an outdoor ride is, read out of a decoded FIT file (spec §11.8, ADR 0026): when it started and ended, how far, how much it
 * climbed and the heart rate. It reads **cycling** sessions only (sport cycling or e-biking); the power in a file is never read
 * (an outdoor ride feeds neither the power curve, the FTP estimate nor aerobic drift: ADR 0026), and the positions stay in the raw
 * file. Nothing here quotes a value in an error.
 */

/** Why a file is refused: the code is one of the closed list of the API (`api-error.schema.json`). */
export class ImportError extends Error {
  readonly code: "invalid_fit" | "unsupported_sport" | "empty_activity";

  constructor(code: ImportError["code"], message: string) {
    super(message);
    this.name = "ImportError";
    this.code = code;
  }
}

/** A heart rate outside this is the sensor losing the skin (or a strap glitch), not a reading. */
export const MIN_PLAUSIBLE_HEART_RATE = 30;
export const MAX_PLAUSIBLE_HEART_RATE = 250;
/** Altitude changes smaller than this are noise in the climb worked out from the records (a dead band, in metres). */
const CLIMB_DEAD_BAND = 3;

export interface OutdoorRide {
  /** Unix milliseconds. */
  startMs: number;
  endMs: number;
  distanceMeters: number | null;
  elevationGainMeters: number | null;
  /** The plausible heart-rate readings inside the ride, in order, one per instant, at the file's own density. */
  samples: HrSample[];
  /** The session's own averages, for a file with a session but no heart-rate records. */
  sessionAverageHeartRate: number | null;
  sessionMaxHeartRate: number | null;
  /** How many records the ride has, and whether any carries power (which is ignored). */
  records: number;
  hasPower: boolean;
}

const SPORT_NAMES: Record<number, string> = {
  0: "generic",
  1: "running",
  4: "fitness equipment",
  5: "swimming",
  10: "training",
  11: "walking",
  15: "rowing",
  17: "hiking",
  18: "multisport",
  19: "paddling",
  22: "motorcycling",
};

const sportName = (sport: number | null): string =>
  sport === null ? "not given" : (SPORT_NAMES[sport] ?? `number ${sport}`);

const isCycling = (sport: number | null): boolean =>
  sport === FIT_SPORT_CYCLING || sport === FIT_SPORT_E_BIKING;

function notCycling(sport: number | null): ImportError {
  return new ImportError(
    "unsupported_sport",
    `This FIT file is not a cycling ride (its sport is ${sportName(sport)}); only cycling is imported`,
  );
}

/** The climb worked out from altitudes: the sum of rises, ignoring changes smaller than the dead band. */
export function climbOf(altitudes: readonly number[]): number | null {
  const first = altitudes[0];
  if (first === undefined) return null;
  let reference = first;
  let gain = 0;
  for (const altitude of altitudes) {
    if (altitude > reference + CLIMB_DEAD_BAND) {
      gain += altitude - reference;
      reference = altitude;
    } else if (altitude < reference - CLIMB_DEAD_BAND) {
      reference = altitude;
    }
  }
  return gain;
}

/** The ride in a decoded file. Throws {@link ImportError} when it is not an outdoor cycling ride the service can use. */
export function readOutdoorRide(file: FitFile): OutdoorRide {
  if (file.fileType !== null && file.fileType !== FIT_FILE_ACTIVITY) {
    throw new ImportError("invalid_fit", "This FIT file is not an activity file");
  }

  let cycling: FitSession[];
  if (file.sessions.length > 0) {
    cycling = file.sessions.filter((s) => isCycling(s.sport));
    if (cycling.length === 0) throw notCycling(file.sessions[0]?.sport ?? null);
  } else {
    // No session message: the sport message must say it is cycling.
    const sport = file.sports.find((s) => s.sport !== null);
    if (sport === undefined) {
      throw new ImportError("unsupported_sport", "This FIT file does not say what sport it is");
    }
    if (!file.sports.some((s) => isCycling(s.sport))) throw notCycling(sport.sport);
    cycling = [];
  }

  const all = file.records;
  // The windows of the cycling sessions; with none, the whole file.
  const windows: { from: number; to: number }[] = [];
  for (const session of cycling) {
    const from = session.startMs ?? all[0]?.timeMs ?? null;
    const byElapsed =
      from !== null && session.elapsedSeconds !== null
        ? from + session.elapsedSeconds * 1000
        : null;
    const to =
      Math.max(session.endMs ?? 0, byElapsed ?? 0) || (all[all.length - 1]?.timeMs ?? null);
    if (from !== null && to !== null && to >= from) windows.push({ from, to });
  }
  const inside = (record: FitRecord): boolean =>
    windows.length === 0 || windows.some((w) => record.timeMs >= w.from && record.timeMs <= w.to);
  const records = all.filter(inside).sort((a, b) => a.timeMs - b.timeMs);
  if (records.length === 0) {
    throw new ImportError(
      "empty_activity",
      "This FIT file holds no ride: it has no records with a time",
    );
  }

  const first = records[0] as FitRecord;
  const last = records[records.length - 1] as FitRecord;
  const startMs = windows.length > 0 ? Math.min(...windows.map((w) => w.from)) : first.timeMs;
  let endMs = windows.length > 0 ? Math.max(...windows.map((w) => w.to)) : last.timeMs;
  if (endMs <= startMs) endMs = last.timeMs;
  if (endMs - startMs < 1000) {
    throw new ImportError("empty_activity", "This FIT file holds no ride: it lasts under a second");
  }

  // Heart rate: plausible readings, one per instant.
  const byTime = new Map<number, HrSample>();
  let hasPower = false;
  for (const record of records) {
    if (record.power !== undefined && record.power > 0) hasPower = true;
    const bpm = record.heartRate;
    if (bpm === undefined || bpm < MIN_PLAUSIBLE_HEART_RATE || bpm > MAX_PLAUSIBLE_HEART_RATE)
      continue;
    byTime.set(record.timeMs, { tMs: record.timeMs, bpm });
  }
  const samples = [...byTime.values()].sort((a, b) => a.tMs - b.tMs);

  // Distance: the sessions' own total, else what the records' odometer says.
  let distanceMeters: number | null = null;
  if (cycling.length > 0 && cycling.every((s) => s.distanceMeters !== null)) {
    distanceMeters = cycling.reduce((a, s) => a + (s.distanceMeters as number), 0);
  } else {
    const odometer = records.flatMap((r) => (r.distance === undefined ? [] : [r.distance]));
    if (odometer.length >= 2) {
      distanceMeters = Math.max(
        0,
        (odometer[odometer.length - 1] as number) - (odometer[0] as number),
      );
    }
  }

  // Climb: the sessions' own total ascent, else worked out from the altitudes.
  let elevationGainMeters: number | null = null;
  if (cycling.length > 0 && cycling.every((s) => s.ascentMeters !== null)) {
    elevationGainMeters = cycling.reduce((a, s) => a + (s.ascentMeters as number), 0);
  } else {
    elevationGainMeters = climbOf(
      records.flatMap((r) => (r.altitude === undefined ? [] : [r.altitude])),
    );
  }

  const sessionAverage = cycling
    .map((s) => s.averageHeartRate)
    .filter((v): v is number => v !== null);
  const sessionMax = cycling.map((s) => s.maxHeartRate).filter((v): v is number => v !== null);
  return {
    startMs,
    endMs,
    distanceMeters,
    elevationGainMeters,
    samples,
    sessionAverageHeartRate:
      sessionAverage.length === 0
        ? null
        : sessionAverage.reduce((a, b) => a + b, 0) / sessionAverage.length,
    sessionMaxHeartRate: sessionMax.length === 0 ? null : Math.max(...sessionMax),
    records: records.length,
    hasPower,
  };
}

/**
 * The samples thinned for storage: at most one every `stepSeconds`, each the mean of the readings in its slot and placed where the
 * slot's first reading was, as `[offset ms from start, bpm]`. A ride's heart rate at 1 Hz would make a row of hundreds of
 * kilobytes that every load computation reads; at 5 s it is a fifth of that and the load differs by a fraction of a percent.
 */
export function thinSamples(
  samples: readonly HrSample[],
  startMs: number,
  stepSeconds: number,
): [number, number][] {
  const slots = new Map<number, { offset: number; sum: number; n: number }>();
  for (const sample of samples) {
    const offset = sample.tMs - startMs;
    const slot = Math.floor(offset / (stepSeconds * 1000));
    const entry = slots.get(slot);
    if (entry === undefined) slots.set(slot, { offset, sum: sample.bpm, n: 1 });
    else {
      entry.sum += sample.bpm;
      entry.n++;
    }
  }
  return [...slots.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, { offset, sum, n }]): [number, number] => [offset, Math.round((sum / n) * 10) / 10]);
}
