import type { RideRecord } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import {
  asRide,
  bearer,
  expectError,
  expectUpload,
  fitFixture,
  putFit,
  putRecord,
  rideFixture,
  startApp,
} from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { buildFit, buildFitOfSize } from "./helpers/fit.ts";

let db: Db;
let app: FastifyInstance;
let tiny: FastifyInstance;

beforeAll(async () => {
  db = await openMigratedTestDb();
  app = startApp(db).app;
  tiny = startApp(db, { limits: { recordBytes: 2048 } }).app;
});

afterAll(async () => {
  await app.close();
  await tiny.close();
  await db.close();
});

beforeEach(async () => {
  await clearRides(db);
});

const START = 1_800_000_000_000;
const base = (): RideRecord => rideFixture("minimal-free-ride.json");
/** The three-second fixture ride under an id and a start time. */
const ride = (id = "ride-1", start = START): RideRecord => asRide(base(), id, start);

async function rows(): Promise<
  {
    id: string;
    start: string;
    end: string | null;
    sha: string;
    fit_bytes: number | null;
    fit_sha: string | null;
    by: string;
  }[]
> {
  const result = await db.query<{
    id: string;
    start: string;
    end: string | null;
    sha: string;
    fit_bytes: number | null;
    fit_sha: string | null;
    by: string;
  }>(
    `SELECT id, start_epoch_millis::text AS start, end_epoch_millis::text AS "end",
            record_sha256 AS sha, length(fit)::int AS fit_bytes, fit_sha256 AS fit_sha,
            uploaded_by AS by
       FROM rides ORDER BY id`,
  );
  return result.rows;
}

describe("PUT /v1/rides/{rideId}", () => {
  it("stores a record: 201, and the row holds the record as uploaded", async () => {
    const record = ride();
    expectUpload(await putRecord(app, "ride-1", record), "ride-1", "stored");

    const [row] = await rows();
    expect(row).toMatchObject({
      id: "ride-1",
      start: String(START),
      end: String(START + 3000),
      fit_bytes: null,
      fit_sha: null,
      by: "tablet",
    });
    const stored = await db.query<{ record: unknown }>("SELECT record FROM rides");
    expect(stored.rows[0]?.record).toEqual(record);
  });

  it("stores a record of a ride that has no end time yet", async () => {
    const record = asRide(rideFixture("empty-ride.json"), "ride-open", START);
    expectUpload(await putRecord(app, "ride-open", record), "ride-open", "stored");
    expect((await rows())[0]?.end).toBeNull();
  });

  it("answers 200 and changes nothing when the same record comes again", async () => {
    const record = ride();
    expectUpload(await putRecord(app, "ride-1", record), "ride-1", "stored");
    const before = await rows();
    expectUpload(await putRecord(app, "ride-1", record), "ride-1", "already-stored");
    expectUpload(await putRecord(app, "ride-1", record), "ride-1", "already-stored");
    expect(await rows()).toEqual(before);
  });

  it("takes the same content however it is laid out", async () => {
    const record = ride();
    await putRecord(app, "ride-1", record);
    const pretty = JSON.stringify(record, null, 4);
    const reversed = JSON.stringify(
      Object.fromEntries(Object.entries(record).reverse()),
      null,
      "\t",
    );
    expectUpload(await putRecord(app, "ride-1", pretty), "ride-1", "already-stored");
    expectUpload(await putRecord(app, "ride-1", reversed), "ride-1", "already-stored");
  });

  it("answers 200 to two identical uploads that arrive at once, and stores one", async () => {
    const record = ride();
    const answers = await Promise.all([1, 2, 3, 4].map(() => putRecord(app, "ride-1", record)));
    expect(answers.map((r) => r.statusCode).sort()).toEqual([200, 200, 200, 201]);
    expect(await rows()).toHaveLength(1);
  });

  it("answers 409 ride_conflict for another record under a stored id, and keeps the first", async () => {
    const first = ride();
    await putRecord(app, "ride-1", first);
    const before = await rows();

    const changed = ride();
    changed.summary.workKilojoules += 1;
    const error = expectError(await putRecord(app, "ride-1", changed), "ride_conflict");
    expect(error.message).toContain("ride-1");

    // A reading that is null in one body and absent in the other is another body.
    const withNulls = rideFixture("explicit-nulls.json");
    expectError(
      await putRecord(app, "ride-1", asRide(withNulls, "ride-1", START)),
      "ride_conflict",
    );
    expect(await rows()).toEqual(before);
  });

  it("answers 409 start_time_conflict for the same start under another id, naming the stored one", async () => {
    await putRecord(app, "ride-1", ride("ride-1"));
    const error = expectError(
      await putRecord(app, "ride-2", ride("ride-2")),
      "start_time_conflict",
    );
    expect(error.message).toContain("ride-1");
    expect(error.message).toContain(String(START));
    expect((await rows()).map((r) => r.id)).toEqual(["ride-1"]);
  });

  it("answers 201 to two uploads of one start time at once for one id and 409 for the other", async () => {
    const answers = await Promise.all([
      putRecord(app, "ride-a", ride("ride-a")),
      putRecord(app, "ride-b", ride("ride-b")),
    ]);
    expect(answers.map((r) => r.statusCode).sort()).toEqual([201, 409]);
    expect(await rows()).toHaveLength(1);
  });

  it("answers 422 ride_id_mismatch when the path and the record say different ids", async () => {
    const error = expectError(await putRecord(app, "ride-2", ride("ride-1")), "ride_id_mismatch");
    expect(error.message).toContain("ride-1");
    expect(error.message).toContain("ride-2");
    expect(await rows()).toEqual([]);
  });

  it.each([
    ["a record that breaks the schema", (r: RideRecord) => ({ ...r, schema: 2 })],
    ["an unknown property", (r: RideRecord) => ({ ...r, extra: true })],
    ["samples that skip a second", (r: RideRecord) => ({ ...r, samples: r.samples.slice(1) })],
    [
      "laps that leave a gap",
      (r: RideRecord) => ({ ...r, laps: [{ startSeconds: 1, endSeconds: 3 }] }),
    ],
    [
      "a summary of another length",
      (r: RideRecord) => ({ ...r, summary: { ...r.summary, durationSeconds: 9 } }),
    ],
  ])("answers 422 invalid_record for %s", async (_what, break_) => {
    const error = expectError(await putRecord(app, "ride-1", break_(ride())), "invalid_record");
    expect(error.message.length).toBeGreaterThan(0);
    expect(await rows()).toEqual([]);
  });

  it.each([
    ["an array", "[]"],
    ["a string", '"ride-1"'],
    ["a number", "7"],
    ["null", "null"],
  ])("answers 422 invalid_record for JSON that is %s", async (_what, body) => {
    expectError(await putRecord(app, "ride-1", body), "invalid_record");
  });

  it("answers 422 for a record whose text a database cannot hold", async () => {
    const record = ride();
    record.ride.devices = ["Trainer\u0000"];
    expectError(await putRecord(app, "ride-1", record), "invalid_record");
    expect(await rows()).toEqual([]);
  });

  it("reports the first few errors only, with a count of the rest", async () => {
    const record = ride();
    record.samples = record.samples.map((s) => ({ ...s, power: -1, heartRate: -1 }));
    const error = expectError(await putRecord(app, "ride-1", record), "invalid_record");
    expect(error.message).toMatch(/\(and \d+ more\)$/);
  });

  it.each([
    ["not JSON at all", "{not json"],
    ["truncated JSON", '{"schema": 1, "ride": '],
    ["an empty body", ""],
  ])("answers 400 invalid_json for %s", async (_what, body) => {
    expectError(await putRecord(app, "ride-1", body), "invalid_json");
  });

  it("answers 400 invalid_json when there is no body at all", async () => {
    const res = await app.inject({ method: "PUT", url: "/v1/rides/ride-1", headers: bearer() });
    expectError(res, "invalid_json");
  });

  it.each([
    ["a dot first", ".hidden"],
    ["a dash first", "-ride"],
    ["a space", "a%20b"],
    ["an encoded slash", "a%2Fb"],
    ["a non-ASCII letter", "%C3%A9"],
    ["129 characters", "x".repeat(129)],
  ])("answers 400 invalid_ride_id for an id with %s", async (_what, id) => {
    expectError(await putRecord(app, id, ride()), "invalid_ride_id");
    expectError(await putFit(app, id, buildFit()), "invalid_ride_id");
  });

  it("takes an id of 128 characters", async () => {
    const id = `r${"x".repeat(127)}`;
    expectUpload(await putRecord(app, id, ride(id)), id, "stored");
  });

  it("takes the content type with a charset, as an HTTP client writes it", async () => {
    const res = await app.inject({
      method: "PUT",
      url: "/v1/rides/ride-1",
      headers: { "content-type": "application/json; charset=utf-8", ...bearer() },
      payload: JSON.stringify(ride()),
    });
    expectUpload(res, "ride-1", "stored");
  });

  it("answers 500 internal_error, saying nothing of the cause, when the database fails", async () => {
    const broken: Db = {
      ...db,
      query: () => Promise.reject(new Error("connection to 10.0.0.5:5432 refused")),
    };
    const { app: failing, logs } = startApp(broken);
    const error = expectError(await putRecord(failing, "ride-1", ride()), "internal_error");
    expect(error.message).not.toContain("10.0.0.5");
    expect(logs.join("\n")).toContain("10.0.0.5"); // the operator can see it
    await failing.close();
  });

  it("answers 415 to a body that is not declared as JSON", async () => {
    for (const type of [
      "text/plain",
      "application/vnd.ant.fit",
      "application/x-www-form-urlencoded",
    ]) {
      const res = await app.inject({
        method: "PUT",
        url: "/v1/rides/ride-1",
        headers: { "content-type": type, ...bearer() },
        payload: JSON.stringify(ride()),
      });
      expectError(res, "unsupported_media_type");
    }
    const none = await app.inject({
      method: "PUT",
      url: "/v1/rides/ride-1",
      headers: bearer(),
      payload: Buffer.from(JSON.stringify(ride())),
    });
    expectError(none, "unsupported_media_type");
  });

  it("answers 413 payload_too_large above the limit, before it stores or parses anything", async () => {
    const big = rideFixture("synthetic-5min.json");
    const text = JSON.stringify(asRide(big, "ride-big", START));
    expect(text.length).toBeGreaterThan(2048);
    expectError(await putRecord(tiny, "ride-big", text), "payload_too_large");
    expect(await rows()).toEqual([]);
    // The same body is fine for the service with the real limit.
    expectUpload(await putRecord(app, "ride-big", text), "ride-big", "stored");
  });

  it("routes only PUT: other methods are 404", async () => {
    for (const method of ["GET", "POST", "DELETE", "PATCH"] as const) {
      const res = await app.inject({ method, url: "/v1/rides/ride-1", headers: bearer() });
      expectError(res, "not_found");
    }
  });
});

describe("PUT /v1/rides/{rideId}/fit", () => {
  const fit = fitFixture("fit-golden-3s.fit");

  it("stores the file of a stored record: 201, byte for byte", async () => {
    await putRecord(app, "ride-1", ride());
    expectUpload(await putFit(app, "ride-1", fit), "ride-1", "stored");

    const [row] = await rows();
    expect(row?.fit_bytes).toBe(fit.length);
    const stored = await db.query<{ fit: Uint8Array; sha: string }>(
      "SELECT fit, fit_sha256 AS sha FROM rides WHERE id = 'ride-1'",
    );
    expect(Buffer.from(stored.rows[0]?.fit ?? []).equals(fit)).toBe(true);
    expect(stored.rows[0]?.sha).toBe(row?.fit_sha);
  });

  it("answers 200 and changes nothing when the same file comes again", async () => {
    await putRecord(app, "ride-1", ride());
    await putFit(app, "ride-1", fit);
    const before = await rows();
    expectUpload(await putFit(app, "ride-1", fit), "ride-1", "already-stored");
    expect(await rows()).toEqual(before);
  });

  it("answers 201 once for identical uploads that arrive at once", async () => {
    await putRecord(app, "ride-1", ride());
    const answers = await Promise.all([1, 2, 3].map(() => putFit(app, "ride-1", fit)));
    expect(answers.map((r) => r.statusCode).sort()).toEqual([200, 200, 201]);
  });

  it("answers 404 ride_not_found when the record is not stored yet", async () => {
    const error = expectError(await putFit(app, "ride-1", fit), "ride_not_found");
    expect(error.message).toContain("ride-1");
    expect(await rows()).toEqual([]);
  });

  it("answers 409 fit_conflict for another file under a stored one, and keeps the first", async () => {
    await putRecord(app, "ride-1", ride());
    await putFit(app, "ride-1", fit);
    const before = await rows();
    expectError(await putFit(app, "ride-1", buildFit()), "fit_conflict");
    expect(await rows()).toEqual(before);
  });

  it("does not touch the record, and the record's repeat still answers 200 after the file", async () => {
    const record = ride();
    await putRecord(app, "ride-1", record);
    await putFit(app, "ride-1", fit);
    expectUpload(await putRecord(app, "ride-1", record), "ride-1", "already-stored");
  });

  it("keeps the files of two rides apart", async () => {
    await putRecord(app, "ride-1", ride("ride-1", START));
    await putRecord(app, "ride-2", ride("ride-2", START + 86_400_000));
    const other = buildFit({ headerSize: 12 });
    expectUpload(await putFit(app, "ride-1", fit), "ride-1", "stored");
    expectUpload(await putFit(app, "ride-2", other), "ride-2", "stored");
    expect((await rows()).map((r) => r.fit_bytes)).toEqual([fit.length, other.length]);
  });

  it.each([
    ["an empty body", () => Buffer.alloc(0)],
    ["a body that is not a FIT file", () => Buffer.from(JSON.stringify(ride()))],
    ["a file cut short", () => fit.subarray(0, fit.length - 10)],
    [
      "a file with a damaged byte",
      () => {
        const bytes = Buffer.from(fit);
        bytes[100] = (bytes[100] ?? 0) ^ 0xff;
        return bytes;
      },
    ],
  ])("answers 422 invalid_fit for %s", async (_what, make) => {
    await putRecord(app, "ride-1", ride());
    expectError(await putFit(app, "ride-1", make()), "invalid_fit");
    expect((await rows())[0]?.fit_bytes).toBeNull();
  });

  it("answers 422 invalid_fit when there is no body at all", async () => {
    await putRecord(app, "ride-1", ride());
    const res = await app.inject({
      method: "PUT",
      url: "/v1/rides/ride-1/fit",
      headers: { "content-type": "application/vnd.ant.fit", ...bearer() },
    });
    expectError(res, "invalid_fit");
  });

  it("answers 415 to a body that is not declared as a FIT file", async () => {
    await putRecord(app, "ride-1", ride());
    for (const type of ["application/json", "application/octet-stream", "text/plain"]) {
      const res = await app.inject({
        method: "PUT",
        url: "/v1/rides/ride-1/fit",
        headers: { "content-type": type, ...bearer() },
        payload: fit,
      });
      expectError(res, "unsupported_media_type");
    }
    const none = await app.inject({
      method: "PUT",
      url: "/v1/rides/ride-1/fit",
      headers: bearer(),
      payload: fit,
    });
    expectError(none, "unsupported_media_type");
  });

  it("takes a file of exactly 8 MiB and answers 413 for one byte more", async () => {
    await putRecord(app, "ride-1", ride());
    const eightMiB = 8 * 1024 * 1024;
    expectError(await putFit(app, "ride-1", Buffer.alloc(eightMiB + 1)), "payload_too_large");
    expect((await rows())[0]?.fit_bytes).toBeNull();
    expectUpload(await putFit(app, "ride-1", buildFitOfSize(eightMiB)), "ride-1", "stored");
    expect((await rows())[0]?.fit_bytes).toBe(eightMiB);
  });

  it("routes only PUT: other methods are 404", async () => {
    for (const method of ["GET", "POST", "DELETE"] as const) {
      const res = await app.inject({ method, url: "/v1/rides/ride-1/fit", headers: bearer() });
      expectError(res, "not_found");
    }
  });
});
