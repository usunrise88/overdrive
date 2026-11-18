import { COGGAN_ZONE_UPPER_PERCENT, type RideRecord } from "@overdrive/contracts";
import { rideStatistics } from "../ride-stats.ts";
import { aerobicDrift, type DriftResult } from "./drift.ts";
import { meanMaximalPower, type RidePowerPoint } from "./power-curve.ts";

/**
 * What the service works out once from a stored ride and keeps in `ride_metrics` (migration 0006),
 * so that the metrics of a year of rides are a read of small rows, not a parse of their records.
 * A ride never changes after it is stored, so these never go stale; a change of the formulas is a
 * new {@link RIDE_METRICS_VERSION}, and the rides are recomputed on the next read.
 */

/** Bumped whenever a formula behind `RideMetrics` changes. */
export const RIDE_METRICS_VERSION = 1;

export interface RideMetrics {
  durationSeconds: number;
  movingSeconds: number;
  /** The FTP the ride was recorded with, which its load is based on. */
  ftp: number;
  /** Training stress score (100 is an hour at FTP); null without 30 s of power. */
  load: number | null;
  normalizedWatts: number | null;
  averageWatts: number | null;
  powerCurve: RidePowerPoint[];
  drift: DriftResult;
}

export function computeRideMetrics(record: RideRecord): RideMetrics {
  const stats = rideStatistics(record, COGGAN_ZONE_UPPER_PERCENT);
  return {
    durationSeconds: stats.durationSeconds,
    movingSeconds: stats.movingSeconds,
    ftp: record.ride.ftp,
    load: stats.power.load,
    normalizedWatts: stats.power.normalizedWatts,
    averageWatts: stats.power.averageWatts,
    powerCurve: meanMaximalPower(record.samples),
    drift: aerobicDrift(record.samples, record.laps),
  };
}
