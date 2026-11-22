import { addDaysToIsoDate, type EffortKind, type EffortResponse } from "@overdrive/contracts";
import { type MetricsService, rideNameOf } from "../metrics/service.ts";
import { dayStartMillis, localDate } from "../time.ts";
import type { WellnessService } from "../wellness/service.ts";
import { buildEffort, type EffortInput, firstMondayNeeded } from "./compute.ts";

/**
 * Weekly effort (spec §6.3, ADR 0042): the training load of the rides on the tablet and of the watch's workouts and outdoor rides,
 * summed per local week and day, with the rider's usual range and how this week compares. It takes the loads the metrics already
 * work out (a ride's stored power score, a workout's heart-rate score as `GET /v1/activities` gives it) and adds nothing of its own;
 * nothing is stored for it.
 */

export interface EffortService {
  /** `GET /v1/effort`: the last `weeks` weeks (1 to 52, the caller checks) ending with the current one. */
  effort(weeks: number): Promise<EffortResponse>;
}

export interface EffortOptions {
  metrics: Pick<MetricsService, "store" | "ensureComputed" | "whenIdle">;
  wellness: Pick<WellnessService, "activitiesInRange">;
  timezone: string;
  now: () => Date;
}

export function createEffort(options: EffortOptions): EffortService {
  const { metrics, wellness, timezone, now } = options;

  return {
    async effort(weeks) {
      await metrics.whenIdle();
      await metrics.ensureComputed();
      const generated = now();
      const today = localDate(generated, timezone);
      const first = firstMondayNeeded(today, weeks);

      const rows = await metrics.store.listBetween(
        dayStartMillis(first, timezone),
        dayStartMillis(addDaysToIsoDate(today, 1), timezone),
      );
      const inputs: EffortInput[] = rows.map((row) => ({
        id: row.rideId,
        kind: "ride",
        name: rideNameOf(row),
        date: localDate(row.startEpochMillis, timezone),
        startEpochMillis: row.startEpochMillis,
        // A ride with under 30 s of power has no load: it is a ride and counts 0 (ADR 0022).
        load: row.load ?? 0,
        approximate: false,
        durationSeconds: row.durationSeconds,
      }));

      for (const item of await wellness.activitiesInRange(first, today)) {
        // A session the watch recorded while the tablet recorded the ride is the same ride, not counted twice (ADR 0023).
        if (!item.countsTowardLoad) continue;
        const kind: EffortKind = item.source === "fit" ? "outdoor" : "watch";
        inputs.push({
          id: item.id,
          kind,
          name: item.name,
          date: item.startDate,
          startEpochMillis: Date.parse(item.start),
          // No load without the heart-rate limits or samples: counted 0 and flagged, so the week says it is approximate.
          load: item.load ?? 0,
          approximate: item.load === null || item.loadApproximate,
          durationSeconds: item.durationSeconds,
        });
      }

      return buildEffort({
        activities: inputs,
        today,
        weeks,
        generatedAt: generated.toISOString(),
      });
    },
  };
}
