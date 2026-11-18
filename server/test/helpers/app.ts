import { readdirSync, readFileSync } from "node:fs";
import {
  API_ERROR_STATUS,
  type ApiErrorCode,
  type RideRecord,
  validateApiError,
  validateUploadResponse,
} from "@overdrive/contracts";
import type { FastifyInstance, LightMyRequestResponse } from "fastify";
import { expect } from "vitest";
import { buildApp } from "../../src/app.ts";
import type { DeviceToken } from "../../src/config.ts";
import type { Db } from "../../src/db.ts";
import type { Limits } from "../../src/rides.ts";

export const TABLET: DeviceToken = {
  name: "tablet",
  token: "tablet-0123456789abcdefghijklmnopqrstuvwxyz+/=",
};
export const COMPANION: DeviceToken = {
  name: "companion",
  token: "companion-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
};

export interface TestApp {
  app: FastifyInstance;
  /** Every log line written so far. */
  logs: string[];
}

export function startApp(
  db: Db,
  options: { devices?: DeviceToken[]; limits?: Partial<Limits> } = {},
): TestApp {
  const logs: string[] = [];
  const app = buildApp({
    config: { LOG_LEVEL: "debug", DEVICE_TOKENS: options.devices ?? [TABLET, COMPANION] },
    db,
    ...(options.limits === undefined ? {} : { limits: options.limits }),
    logStream: { write: (line) => logs.push(line) },
  });
  return { app, logs };
}

export function bearer(device: DeviceToken = TABLET): Record<string, string> {
  return { authorization: `Bearer ${device.token}` };
}

export function putRecord(
  app: FastifyInstance,
  rideId: string,
  body: unknown,
  headers: Record<string, string> = bearer(),
): Promise<LightMyRequestResponse> {
  return app.inject({
    method: "PUT",
    url: `/v1/rides/${rideId}`,
    headers: { "content-type": "application/json", ...headers },
    payload: typeof body === "string" ? body : JSON.stringify(body),
  });
}

export function putFit(
  app: FastifyInstance,
  rideId: string,
  body: Uint8Array,
  headers: Record<string, string> = bearer(),
): Promise<LightMyRequestResponse> {
  return app.inject({
    method: "PUT",
    url: `/v1/rides/${rideId}/fit`,
    headers: { "content-type": "application/vnd.ant.fit", ...headers },
    payload: Buffer.from(body),
  });
}

/** The answer is the error of the contract with this status and code. */
export function expectError(
  res: LightMyRequestResponse,
  code: ApiErrorCode,
): { code: string; message: string } {
  expect(res.statusCode, res.body).toBe(API_ERROR_STATUS[code]);
  expect(res.headers["content-type"]).toMatch(/^application\/json/);
  const body: unknown = res.json();
  const checked = validateApiError(body);
  expect(checked, JSON.stringify(body)).toMatchObject({ ok: true });
  const error = (body as { error: { code: string; message: string } }).error;
  expect(error.code).toBe(code);
  return error;
}

/** The answer is the success body of the contract with this status. */
export function expectUpload(
  res: LightMyRequestResponse,
  rideId: string,
  status: "stored" | "already-stored",
): void {
  expect(res.statusCode, res.body).toBe(status === "stored" ? 201 : 200);
  const body: unknown = res.json();
  expect(validateUploadResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  expect(body).toEqual({ rideId, status });
}

// ---- Fixtures --------------------------------------------------------------------------------

const fixtures = new URL("../../../contracts/fixtures/", import.meta.url);

export function validRideFixtures(): [string, RideRecord][] {
  const dir = new URL("rides/valid/", fixtures);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(new URL(f, dir), "utf8")) as RideRecord]);
}

export function rideFixture(name: string): RideRecord {
  return JSON.parse(readFileSync(new URL(`rides/valid/${name}`, fixtures), "utf8")) as RideRecord;
}

export function fitFixture(name: string): Buffer {
  return readFileSync(new URL(`fit/${name}`, fixtures));
}

/** The same ride under another id and start time (the fixtures all share one start time). */
export function asRide(record: RideRecord, id: string, startEpochMillis: number): RideRecord {
  const copy = structuredClone(record);
  const duration =
    copy.ride.endEpochMillis === undefined || copy.ride.endEpochMillis === null
      ? null
      : copy.ride.endEpochMillis - copy.ride.startEpochMillis;
  copy.ride.id = id;
  copy.ride.startEpochMillis = startEpochMillis;
  if (duration !== null) copy.ride.endEpochMillis = startEpochMillis + duration;
  return copy;
}
