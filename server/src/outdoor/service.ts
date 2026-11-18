import {
  type ActivityImportResponse,
  type ActivityImportSummary,
  OUTDOOR_RIDE_SPORT,
} from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { checkFit } from "../fit.ts";
import { decodeFit, FitDecodeError } from "../fit-decode.ts";
import { sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import { localDate } from "../time.ts";
import { roundTo } from "../wellness/metrics.ts";
import type { WellnessService } from "../wellness/service.ts";
import { FIT_HOLD_CAP_SECONDS, summarizeSamples } from "../wellness/trimp.ts";
import { ImportError, readOutdoorRide, thinSamples } from "./fit-ride.ts";
import { createOutdoorStore, type DuplicateOf, type OutdoorStore } from "./store.ts";

/**
 * Outdoor rides from the bike computer (spec §11.8, ADR 0026). `importFit` is the one function both doors use: the HTTP route
 * (`POST /v1/activities/fit`, the companion's share target) and the Telegram bot (a FIT document sent to it). It checks the
 * file's framing and CRC, decodes it, reads the cycling ride out of it, recognises the same ride arriving twice, stores the ride
 * (heart rate thinned to at most one reading in 5 s, the raw FIT bytes whole) and queues it for intervals.icu. The load is the
 * heart-rate load of the watch's workouts (ADR 0023): `other` in the metrics, so it feeds fatigue, form and recovery and never the
 * power curve, the FTP estimate or aerobic drift, whatever the file holds.
 *
 * Health data is private: nothing here logs a value, only the id, the status and sizes.
 */

/** The name of an imported ride: a file from a bike computer has no title worth keeping. */
export const OUTDOOR_RIDE_NAME = "Outdoor ride";
/** Heart rate is stored at most this often (seconds). */
export const STORED_HEART_RATE_STEP_SECONDS = 5;

export interface ImportResult {
  response: ActivityImportResponse;
  /** True when this call stored the ride (HTTP 201), false for a duplicate (HTTP 200). */
  created: boolean;
}

export interface OutdoorService {
  /** Throws {@link ImportError} for a file that is not an outdoor cycling ride the service can use. */
  importFit(bytes: Uint8Array, device: string): Promise<ImportResult>;
}

export interface OutdoorOptions {
  db: Db;
  bus: EventBus;
  wellness: Pick<WellnessService, "activity">;
  timezone: string;
  log: Logger;
  store?: OutdoorStore;
}

export function createOutdoor(options: OutdoorOptions): OutdoorService {
  const { bus, wellness, timezone, log } = options;
  const store = options.store ?? createOutdoorStore(options.db);

  // One import at a time: the duplicate check and the insert must not interleave with another file of the same ride.
  let queue: Promise<unknown> = Promise.resolve();
  function exclusive<T>(work: () => Promise<T>): Promise<T> {
    const next = queue.then(work, work);
    queue = next.catch(() => {});
    return next;
  }

  return {
    async importFit(bytes, device) {
      const framing = checkFit(bytes);
      if (!framing.ok) throw new ImportError("invalid_fit", framing.reason);
      let ride: ReturnType<typeof readOutdoorRide>;
      try {
        ride = readOutdoorRide(decodeFit(bytes));
      } catch (error) {
        if (error instanceof FitDecodeError) throw new ImportError("invalid_fit", error.message);
        throw error;
      }

      const sha256 = sha256Hex(bytes);
      const id = `fit-${sha256.slice(0, 16)}`;
      const stats = summarizeSamples(ride.samples, ride.startMs, ride.endMs, FIT_HOLD_CAP_SECONDS);
      const averageHeartRate = stats.averageHeartRate ?? ride.sessionAverageHeartRate;
      const maxHeartRate = stats.samples > 0 ? stats.maxHeartRate : ride.sessionMaxHeartRate;
      const summary = (load: number | null, approximate: boolean): ActivityImportSummary => ({
        name: OUTDOOR_RIDE_NAME,
        start: new Date(ride.startMs).toISOString(),
        end: new Date(ride.endMs).toISOString(),
        startDate: localDate(ride.startMs, timezone),
        durationSeconds: Math.max(1, Math.round((ride.endMs - ride.startMs) / 1000)),
        distanceMeters: ride.distanceMeters === null ? null : roundTo(ride.distanceMeters, 1),
        elevationGainMeters:
          ride.elevationGainMeters === null ? null : roundTo(ride.elevationGainMeters, 1),
        averageHeartRate: averageHeartRate === null ? null : roundTo(averageHeartRate, 1),
        maxHeartRate: maxHeartRate === null ? null : Math.round(maxHeartRate),
        load,
        loadApproximate: approximate,
      });
      const duplicate = (of: DuplicateOf): ImportResult => ({
        created: false,
        response: {
          status: "duplicate",
          id: of.id,
          kind: of.kind,
          reason: of.reason,
          summary: summary(null, false),
        },
      });

      const outcome = await exclusive(async (): Promise<DuplicateOf | "stored"> => {
        const existing = await store.findDuplicate({
          sha256,
          startMs: ride.startMs,
          endMs: ride.endMs,
        });
        if (existing !== null) return existing;
        const stored = await store.insert({
          id,
          sha256,
          startMs: ride.startMs,
          endMs: ride.endMs,
          title: OUTDOOR_RIDE_NAME,
          type: OUTDOOR_RIDE_SPORT,
          hr: thinSamples(ride.samples, ride.startMs, STORED_HEART_RATE_STEP_SECONDS),
          samples: stats.samples,
          medianGapSeconds: stats.medianGapSeconds,
          maxGapSeconds: stats.maxGapSeconds,
          coverage: stats.coverage,
          averageHeartRate: stats.averageHeartRate ?? ride.sessionAverageHeartRate,
          maxHeartRate: maxHeartRate === null ? null : Math.round(maxHeartRate),
          distanceMeters: ride.distanceMeters,
          elevationGainMeters: ride.elevationGainMeters,
          fit: bytes,
        });
        if (stored) return "stored";
        // Lost a race with the same bytes (another process): it is a duplicate of the winner.
        return (
          (await store.findDuplicate({ sha256, startMs: ride.startMs, endMs: ride.endMs })) ?? {
            id,
            kind: "activity",
            reason: "same-file",
          }
        );
      });

      if (outcome !== "stored") {
        log.info(
          {
            device,
            id: outcome.id,
            status: "duplicate",
            reason: outcome.reason,
            bytes: bytes.length,
          },
          "outdoor ride import",
        );
        return duplicate(outcome);
      }

      bus.emit("activity-stored", { activityId: id });
      // The load is worked out when it is read (the thresholds in force change), as for the watch's workouts.
      const stored = await wellness.activity(id);
      log.info(
        { device, id, status: "imported", bytes: bytes.length, samples: stats.samples },
        "outdoor ride import",
      );
      return {
        created: true,
        response: {
          status: "imported",
          id,
          kind: "activity",
          reason: null,
          summary: summary(stored?.load ?? null, stored?.loadApproximate ?? false),
        },
      };
    },
  };
}
