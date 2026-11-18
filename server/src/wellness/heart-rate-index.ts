import type { RideRecord } from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import type { RideStore } from "../ride-store.ts";

/**
 * What the tablet's rides say about the rider's heart rates (spec §6.7, ADR 0023), for the estimates
 * in the profile: `estimatedMaxHeartRate` is the highest heart rate in any stored ride;
 * `estimatedThresholdHeartRate` is 95% of the best average heart rate over 20 minutes in a row in any
 * stored ride (the 20-minute test's rule of thumb, as FTP is 95% of the best 20-minute power). Both
 * are null when the rides do not say: no ride with heart rate, or none of 20 minutes with 90% of the
 * seconds measured, or a value outside what a profile can hold. They are estimates: the owner's own
 * maximum and threshold, when set, win, and a load worked out from an estimate is flagged approximate.
 *
 * Each ride is read once (its record is large) and its two numbers kept in `ride_heart_rate`;
 * the rides missing from it are indexed whenever the estimates are asked for.
 */

/** The window of the threshold estimate, in seconds. */
export const THRESHOLD_WINDOW_SECONDS = 1200;
/** A window needs this share of its seconds measured. */
export const MIN_WINDOW_COVERAGE = 0.9;
/** The share of the best 20-minute average heart rate taken as the threshold. */
export const THRESHOLD_SHARE = 0.95;
/** A heart rate above this in a ride is a strap glitch, not a maximum. */
const IMPLAUSIBLE_HEART_RATE = 230;

export interface HeartRateEstimates {
  max: number | null;
  threshold: number | null;
}

export interface RideHeartRates {
  maxHr: number | null;
  best20MinHr: number | null;
}

/** The two numbers of a ride record: the highest heart rate and the best 20-minute average. */
export function heartRatesOfRide(record: RideRecord): RideHeartRates {
  const { samples } = record;
  let max = 0;
  const hr = new Float64Array(samples.length);
  const measured = new Uint8Array(samples.length);
  for (const [i, sample] of samples.entries()) {
    const bpm = sample.heartRate;
    if (bpm == null || bpm <= 0 || bpm > IMPLAUSIBLE_HEART_RATE || sample.paused === true) continue;
    hr[i] = bpm;
    measured[i] = 1;
    if (bpm > max) max = bpm;
  }
  let best: number | null = null;
  if (samples.length >= THRESHOLD_WINDOW_SECONDS) {
    // Sliding window over prefix sums.
    let sum = 0;
    let count = 0;
    for (let i = 0; i < samples.length; i++) {
      sum += hr[i] as number;
      count += measured[i] as number;
      if (i >= THRESHOLD_WINDOW_SECONDS) {
        sum -= hr[i - THRESHOLD_WINDOW_SECONDS] as number;
        count -= measured[i - THRESHOLD_WINDOW_SECONDS] as number;
      }
      if (
        i >= THRESHOLD_WINDOW_SECONDS - 1 &&
        count >= MIN_WINDOW_COVERAGE * THRESHOLD_WINDOW_SECONDS
      ) {
        const average = sum / count;
        if (best === null || average > best) best = average;
      }
    }
  }
  return { maxHr: max > 0 ? max : null, best20MinHr: best };
}

/** The estimates from the best of every ride's numbers, as far as a profile can hold them. */
export function estimatesFrom(
  maxHr: number | null,
  best20MinHr: number | null,
): HeartRateEstimates {
  const max = maxHr === null ? null : Math.round(maxHr);
  const threshold = best20MinHr === null ? null : Math.round(best20MinHr * THRESHOLD_SHARE);
  return {
    max: max !== null && max >= 100 && max <= 230 ? max : null,
    threshold: threshold !== null && threshold >= 80 && threshold <= 220 ? threshold : null,
  };
}

export interface RideHeartRateIndex {
  /** The estimates from the stored rides; indexes the rides that are not yet. */
  estimates(): Promise<HeartRateEstimates>;
  /** Indexes up to `limit` rides that are not, newest first; how many it did. */
  indexMissing(limit?: number): Promise<number>;
}

export function createRideHeartRateIndex(
  db: Db,
  rides: Pick<RideStore, "getRecord">,
  log: Logger,
): RideHeartRateIndex {
  let running: Promise<number> | null = null;

  async function indexSome(limit: number): Promise<number> {
    const missing = await db.query<{ id: string }>(
      `SELECT r.id FROM rides r LEFT JOIN ride_heart_rate h ON h.ride_id = r.id
        WHERE h.ride_id IS NULL ORDER BY r.start_epoch_millis DESC LIMIT $1`,
      [limit],
    );
    for (const { id } of missing.rows) {
      const record = await rides.getRecord(id);
      const numbers =
        record === null ? { maxHr: null, best20MinHr: null } : heartRatesOfRide(record);
      await db.query(
        `INSERT INTO ride_heart_rate (ride_id, max_hr, best_20min_hr) VALUES ($1, $2, $3)
         ON CONFLICT (ride_id) DO NOTHING`,
        [id, numbers.maxHr, numbers.best20MinHr],
      );
    }
    return missing.rows.length;
  }

  async function indexMissing(limit = 50): Promise<number> {
    // One pass at a time: two profile reads at once would read the same rides twice.
    running ??= (async () => {
      let total = 0;
      try {
        for (let n = await indexSome(limit); n > 0; n = await indexSome(limit)) total += n;
      } catch (error) {
        log.error({ err: error }, "could not index the heart rates of the rides");
      }
      return total;
    })().finally(() => {
      running = null;
    });
    return running;
  }

  return {
    indexMissing,
    async estimates() {
      await indexMissing();
      const result = await db.query<{ max_hr: number | null; best: number | null }>(
        "SELECT max(max_hr) AS max_hr, max(best_20min_hr) AS best FROM ride_heart_rate",
      );
      const row = result.rows[0];
      return estimatesFrom(row?.max_hr ?? null, row?.best ?? null);
    },
  };
}
