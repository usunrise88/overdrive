import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";

// ---- Sync API: ride intake (spec §8.3, ADR 0014) ---------------------------------------------
// Hand-written mirrors of schema/sync-upload-response.schema.json and schema/api-error.schema.json,
// which are the source of truth; the fixtures test keeps the two honest. The request bodies are the
// ride record (see index.ts) and the FIT bytes (not JSON, so no schema).

/** What a successful upload answers: 201 "stored" the first time, 200 "already-stored" on a repeat. */
export type UploadStatus = "stored" | "already-stored";

export interface UploadResponse {
  rideId: string;
  status: UploadStatus;
}

/** The stable error codes of the /v1 API, and the HTTP status each one comes with. */
export const API_ERROR_STATUS = {
  unauthorized: 401,
  invalid_json: 400,
  invalid_ride_id: 400,
  invalid_range: 400,
  invalid_query: 400,
  invalid_record: 422,
  invalid_profile: 422,
  ride_id_mismatch: 422,
  invalid_fit: 422,
  unsupported_sport: 422,
  empty_activity: 422,
  ride_not_found: 404,
  ride_conflict: 409,
  start_time_conflict: 409,
  fit_conflict: 409,
  payload_too_large: 413,
  unsupported_media_type: 415,
  not_found: 404,
  internal_error: 500,
} as const;

export type ApiErrorCode = keyof typeof API_ERROR_STATUS;

/** `{ "error": { "code", "message" } }`: the body of every non-2xx answer of /v1. */
export interface ApiError {
  error: { code: ApiErrorCode; message: string };
}

/**
 * Ride ids as the service accepts them in a path: 1 to 128 characters from `A-Z a-z 0-9 . _ -`,
 * starting with a letter or a digit. The ride engine's ids (`ride-<start millis>`) all qualify.
 */
export const RIDE_ID_PATTERN: RegExp = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

export function isValidRideId(id: string): boolean {
  return RIDE_ID_PATTERN.test(id);
}

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkUploadResponse = ajv.compile<UploadResponse>(load("sync-upload-response.schema.json"));
const checkApiError = ajv.compile<ApiError>(load("api-error.schema.json"));

function errorsOf(errors: typeof checkApiError.errors): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** Validates an upload answer, for a client reading one. */
export function validateUploadResponse(input: unknown): Validation<UploadResponse> {
  return checkUploadResponse(input)
    ? { ok: true, value: input }
    : { ok: false, errors: errorsOf(checkUploadResponse.errors) };
}

/** Validates an error body, for a client reading one or a test checking the service's. */
export function validateApiError(input: unknown): Validation<ApiError> {
  return checkApiError(input)
    ? { ok: true, value: input }
    : { ok: false, errors: errorsOf(checkApiError.errors) };
}
