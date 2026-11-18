import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isIsoDate } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: outdoor rides from a FIT file (spec §11.8, §8.3, ADR 0026) ------------------------------------------
// A hand-written mirror of schema/activity-import-response.schema.json, which is the source of truth; the fixtures test keeps
// the two honest. Kotlin's `ActivityImportJson` reads and writes the same bodies against the same fixtures. The request is the
// FIT file's bytes (`application/vnd.ant.fit`), not JSON, so it has no schema.
//
// HEALTH DATA IS PRIVATE: no message built here quotes a figure, only paths and rules.

/** The largest FIT file `POST /v1/activities/fit` takes, in bytes (413 `payload_too_large` above). */
export const ACTIVITY_FIT_MAX_BYTES: number = 16 * 1024 * 1024;

/** The `sport` of an outdoor ride in the rides list and the activity's `type`. */
export const OUTDOOR_RIDE_SPORT = "cycling-outdoor";

export type ActivityImportStatus = "imported" | "duplicate";
export type ActivityImportReason = "same-file" | "same-start-and-duration" | "overlap";

export interface ActivityImportSummary {
  name: string;
  /** UTC instant. */
  start: string;
  end: string;
  /** The rider's local day of `start`. */
  startDate: string;
  durationSeconds: number;
  distanceMeters: number | null;
  elevationGainMeters: number | null;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
  /** Only for an imported ride; null when it cannot be worked out, and always null for a duplicate. */
  load: number | null;
  loadApproximate: boolean;
}

/** The answer to `POST /v1/activities/fit`: 201 `imported`, 200 `duplicate`. */
export interface ActivityImportResponse {
  status: ActivityImportStatus;
  /** The stored activity, or the ride, activity or watch session this file duplicates. */
  id: string;
  /** What `id` is: an `activity` (imported ride or watch workout) or a `ride` recorded on the tablet. */
  kind: "activity" | "ride";
  /** Null for `imported`; why it is the same ride for a `duplicate`. */
  reason: ActivityImportReason | null;
  summary: ActivityImportSummary;
}

const MAX_REPORTED = 20;

const ajv = new Ajv2020({ allErrors: true });
const checkImport = ajv.compile<ActivityImportResponse>(
  JSON.parse(
    readFileSync(
      new URL("../schema/activity-import-response.schema.json", import.meta.url),
      "utf8",
    ),
  ),
);

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** What the schema cannot say (see its description). */
export function activityImportConsistencyErrors(response: ActivityImportResponse): string[] {
  const errors: string[] = [];
  const { summary } = response;
  const start = parseInstant(summary.start);
  const end = parseInstant(summary.end);
  if (start === null) errors.push("/summary/start is not a real date and time");
  if (end === null) errors.push("/summary/end is not a real date and time");
  if (start !== null && end !== null) {
    if (end <= start) errors.push("/summary/end is not after /summary/start");
    else if (Math.abs(summary.durationSeconds - (end - start) / 1000) > 1) {
      errors.push("/summary/durationSeconds is not the difference of start and end");
    }
  }
  if (!isIsoDate(summary.startDate)) errors.push("/summary/startDate is not a real calendar date");
  if (response.status === "imported") {
    if (response.kind !== "activity") errors.push("/kind must be activity for an imported ride");
    if (response.reason !== null) errors.push("/reason must be null for an imported ride");
    if (!response.id.startsWith("fit-"))
      errors.push("/id must start with fit- for an imported ride");
  } else {
    if (response.reason === null) errors.push("/reason is needed for a duplicate");
    if (summary.load !== null) errors.push("/summary/load must be null for a duplicate");
    if (summary.loadApproximate)
      errors.push("/summary/loadApproximate must be false for a duplicate");
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateActivityImportResponse(input: unknown): Validation<ActivityImportResponse> {
  if (!checkImport(input)) return { ok: false, errors: plain(checkImport.errors) };
  const errors = activityImportConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
