import {
  type ActivityImportResponse,
  validateActivityImportResponse,
  validateApiError,
} from "@overdrive/contracts";
import type { FastifyInstance, LightMyRequestResponse } from "fastify";
import { expect } from "vitest";
import { bearer, COMPANION } from "./app.ts";
import { type SyntheticFitRide, syntheticRide } from "./fit-encoder.ts";

/** A morning in March 2027, when the outdoor season starts (spec §11.8). */
export const OUTDOOR_START = Date.UTC(2027, 2, 14, 9, 0, 0);

/** `POST /v1/activities/fit` as the companion does it. */
export function postFit(
  app: FastifyInstance,
  bytes: Uint8Array | string,
  options: { headers?: Record<string, string>; contentType?: string } = {},
): Promise<LightMyRequestResponse> {
  return app.inject({
    method: "POST",
    url: "/v1/activities/fit",
    headers: {
      "content-type": options.contentType ?? "application/vnd.ant.fit",
      ...(options.headers ?? bearer(COMPANION)),
    },
    payload: typeof bytes === "string" ? bytes : Buffer.from(bytes),
  });
}

/** The answer is a valid import answer with this status code and status. */
export function expectImport(
  res: LightMyRequestResponse,
  status: "imported" | "duplicate",
): ActivityImportResponse {
  expect(res.statusCode, res.body).toBe(status === "imported" ? 201 : 200);
  const body: unknown = res.json();
  expect(validateActivityImportResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  const answer = body as ActivityImportResponse;
  expect(answer.status).toBe(status);
  return answer;
}

/** The answer is an API error with this status and code, and the error body of the contract. */
export function expectApiError(
  res: LightMyRequestResponse,
  statusCode: number,
  code: string,
): string {
  expect(res.statusCode, res.body).toBe(statusCode);
  const body: unknown = res.json();
  expect(validateApiError(body), JSON.stringify(body)).toMatchObject({ ok: true });
  const error = (body as { error: { code: string; message: string } }).error;
  expect(error.code).toBe(code);
  return error.message;
}

/** One hour of an outdoor ride (3600 records at 1 Hz, a session of 3600 s) at a steady heart rate, optionally with power. */
export function hourRide(overrides: Partial<SyntheticFitRide> = {}): Buffer {
  return syntheticRide({
    startMs: OUTDOOR_START,
    seconds: 3600,
    heartRate: () => 150,
    ...overrides,
  });
}
