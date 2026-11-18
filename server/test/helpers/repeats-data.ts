import type { RideRecord, Workout } from "@overdrive/contracts";
import type { Db } from "../../src/db.ts";
import type { RideStore } from "../../src/ride-store.ts";
import { insertRide, syntheticRide } from "./synthetic-rides.ts";

/**
 * Synthetic repeated sessions for the tests of the repeat comparison (spec §11.7): one workout, rides played from it with planted
 * heart rates, so a test says "step 2 at 150 bpm" and can work out the answer by hand. Nothing here is a real recording.
 *
 * The workout, flat: 0 ramp 300 s (125 W to 175 W at FTP 250), **1 steady 600 s at 88%**, **2 steady 300 s at 50%**, **3 steady 600 s at
 * 88%**, 4 free 120 s. Its fixed-power steps are the steps 2, 3 and 4 (1-based) with the weights 600, 300 and 600; at FTP 250 their
 * targets are 220 W, 125 W and 220 W. The ride is 1920 s long.
 */

export const SESSION: Workout = {
  schema: 1,
  id: "icu-9001",
  name: "Sweet spot 2x10",
  description: "Two 10-minute efforts with a recovery.",
  steps: [
    { type: "ramp", duration: 300, from: 0.5, to: 0.7, label: "Warm-up" },
    { type: "steady", duration: 600, target: 0.88, label: "Sweet spot" },
    { type: "steady", duration: 300, target: 0.5, label: "Recover" },
    { type: "steady", duration: 600, target: 0.88, label: "Sweet spot" },
    { type: "free", duration: 120, label: "Free ride" },
  ],
};

/** The same session under another name, other labels, cadence targets and ids: a renamed copy. */
export const SESSION_RENAMED: Workout = {
  schema: 1,
  id: "icu-9002",
  name: "Over-unders, copy",
  steps: [
    { type: "ramp", duration: 300, from: 0.5, to: 0.7, cadence: 90 },
    { type: "steady", duration: 600, target: 0.88, cadence: 95, label: "On" },
    { type: "steady", duration: 300, target: 0.5, label: "Off" },
    { type: "steady", duration: 600, target: 0.88000000000001 },
    { type: "free", duration: 120, label: "Cool" },
  ],
};

/** One step different (the recovery is 300 s at 55%): another session. */
export const SESSION_CHANGED: Workout = {
  ...SESSION,
  id: "icu-9003",
  steps: SESSION.steps.map((step, i) =>
    i === 2 && step.type === "steady" ? { ...step, target: 0.55 } : step,
  ),
};

/** The boundaries of the flat steps, in seconds of the ride. */
const ENDS = [300, 900, 1200, 1800, 1920];
export const RIDE_SECONDS = 1920;
export const stepIndexOf = (second: number): number => ENDS.findIndex((end) => second <= end);

/** Where each flat step starts (seconds of the ride). */
const STARTS = [0, 300, 900, 1200, 1800];

export interface AttemptSpec {
  id: string;
  /** ISO instant of the start. */
  start: string;
  ftp?: number;
  /** The heart rate in steps 2, 3 and 4 (flat steps 1, 2, 3); the warm-up and the free step are at 110. */
  heartRates?: readonly [number, number, number];
  /** Seconds at the start of each steady step with a heart rate this much higher (heart rate settling), default 15 for 60 s. */
  transient?: { seconds: number; extra: number };
  /** Only this many seconds are ridden (the ride stops there). */
  seconds?: number;
  /** Watts of the ride in a second, overriding the plan's (a spike, a drift off the target). */
  power?: (second: number, planned: number) => number;
  /** Heart rate in a second overriding everything else; null is no reading. */
  heartRate?: (second: number, planned: number | null) => number | null;
  paused?: (second: number) => boolean;
  workoutId?: string | null;
}

/** The ride record of an attempt at the session. */
export function attempt(spec: AttemptSpec): RideRecord {
  const ftp = spec.ftp ?? 250;
  const rates = spec.heartRates ?? [150, 120, 156];
  const transient = spec.transient ?? { seconds: 60, extra: 15 };
  const plannedWatts = (second: number): number => {
    const index = stepIndexOf(second);
    switch (index) {
      case 0:
        return Math.round(0.5 * ftp + (0.2 * ftp * (second - 1)) / 300);
      case 1:
      case 3:
        return Math.round(0.88 * ftp);
      case 2:
        return Math.round(0.5 * ftp);
      default:
        return 100;
    }
  };
  return syntheticRide({
    id: spec.id,
    startEpochMillis: Date.parse(spec.start),
    ftp,
    seconds: spec.seconds ?? RIDE_SECONDS,
    workoutId: spec.workoutId === undefined ? SESSION.id : spec.workoutId,
    step: stepIndexOf,
    power: (second) => {
      const planned = plannedWatts(second);
      return spec.power === undefined ? planned : spec.power(second, planned);
    },
    heartRate: (second) => {
      const index = stepIndexOf(second);
      const steady = index >= 1 && index <= 3;
      const planned = steady ? plannedWatts(second) : null;
      if (spec.heartRate !== undefined) return spec.heartRate(second, planned);
      if (!steady) return 110;
      const into = second - (STARTS[index] ?? 0);
      return (rates[index - 1] as number) + (into <= transient.seconds ? transient.extra : 0);
    },
    ...(spec.paused === undefined ? {} : { paused: spec.paused }),
  });
}

/** The days of the attempts of the standard history: a week and a bit apart, all at 17:00 UTC. */
export const DAYS = ["2026-10-20", "2026-10-27", "2026-11-03", "2026-11-10", "2026-11-17"] as const;
export const at = (day: string): string => `${day}T17:00:00Z`;

/** Stores a ride and the copy of the workout it was played from, as a ride that went through the intake would have. */
export async function store(
  db: Db,
  rides: Pick<RideStore, "saveWorkoutSnapshot">,
  record: RideRecord,
  workout: Workout | null = SESSION,
): Promise<void> {
  await insertRide(db, record);
  if (workout !== null) {
    await rides.saveWorkoutSnapshot(
      record.ride.id,
      workout,
      new Date(record.ride.startEpochMillis),
    );
  }
}

/** The overall heart rate over steps 2, 3 and 4, weighted by 600, 300 and 600 s: what the comparison says for these step rates. */
export const overall = (h: readonly [number, number, number]): number =>
  Math.round(((600 * h[0] + 300 * h[1] + 600 * h[2]) / 1500) * 10) / 10;
