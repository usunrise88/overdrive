import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import {
  asRide,
  bearer,
  COMPANION,
  expectError,
  expectUpload,
  putRecord,
  rideFixture,
  startApp,
  TABLET,
} from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";

let db: Db;

beforeAll(async () => {
  db = await openMigratedTestDb();
});

afterAll(async () => {
  await db.close();
});

beforeEach(async () => {
  await clearRides(db);
});

const ride = () => asRide(rideFixture("minimal-free-ride.json"), "ride-1", 1_800_000_000_000);

describe("bearer authentication of /v1", () => {
  it.each([
    ["no Authorization header", {}],
    ["an empty bearer token", { authorization: "Bearer " }],
    ["the wrong scheme", { authorization: `Basic ${TABLET.token}` }],
    ["the token without a scheme", { authorization: TABLET.token }],
    ["a wrong token", { authorization: "Bearer not-the-token-not-the-token-not-the-token" }],
    ["a prefix of the token", { authorization: `Bearer ${TABLET.token.slice(0, -1)}` }],
    ["the token with something appended", { authorization: `Bearer ${TABLET.token}x` }],
    ["two tokens", { authorization: `Bearer ${TABLET.token} ${COMPANION.token}` }],
  ])("answers 401 to %s", async (_what, headers) => {
    const { app } = startApp(db);
    const res = await putRecord(app, "ride-1", ride(), headers);
    expectError(res, "unauthorized");
    expect(res.headers["www-authenticate"]).toMatch(/^Bearer/);
    await app.close();
  });

  it("stores nothing for a request it refused", async () => {
    const { app } = startApp(db);
    await putRecord(app, "ride-1", ride(), {});
    const rows = await db.query("SELECT id FROM rides");
    expect(rows.rows).toEqual([]);
    await app.close();
  });

  it("accepts each configured device and records which one uploaded", async () => {
    const { app } = startApp(db);
    expectUpload(await putRecord(app, "ride-1", ride(), bearer(TABLET)), "ride-1", "stored");
    const other = asRide(ride(), "ride-2", 1_800_000_100_000);
    expectUpload(await putRecord(app, "ride-2", other, bearer(COMPANION)), "ride-2", "stored");
    const rows = await db.query<{ id: string; uploaded_by: string }>(
      "SELECT id, uploaded_by FROM rides ORDER BY id",
    );
    expect(rows.rows).toEqual([
      { id: "ride-1", uploaded_by: "tablet" },
      { id: "ride-2", uploaded_by: "companion" },
    ]);
    await app.close();
  });

  it("takes the scheme in any case", async () => {
    const { app } = startApp(db);
    const res = await putRecord(app, "ride-1", ride(), {
      authorization: `bearer ${TABLET.token}`,
    });
    expectUpload(res, "ride-1", "stored");
    await app.close();
  });

  it("answers 401 to everything under /v1 when no token is configured, even a guess", async () => {
    const { app } = startApp(db, { devices: [] });
    for (const headers of [{}, bearer(TABLET), { authorization: "Bearer " }]) {
      expectError(await putRecord(app, "ride-1", ride(), headers), "unauthorized");
    }
    const unknown = await app.inject({ method: "GET", url: "/v1/anything", headers: bearer() });
    expectError(unknown, "unauthorized");
    await app.close();
  });

  it("answers 401 before it looks at the body, so an unauthenticated client gets no parsing", async () => {
    const { app } = startApp(db, { limits: { recordBytes: 10 } });
    const res = await putRecord(app, "ride-1", ride(), {});
    expectError(res, "unauthorized");
    await app.close();
  });

  it("keeps /healthz open", async () => {
    const { app } = startApp(db);
    expect((await app.inject({ method: "GET", url: "/healthz" })).statusCode).toBe(200);
    await app.close();
  });

  it("answers 404 in the error body for an unknown path, after authentication", async () => {
    const { app } = startApp(db);
    const unauthenticated = await app.inject({ method: "GET", url: "/v1/nothing" });
    expectError(unauthenticated, "unauthorized");
    const authenticated = await app.inject({
      method: "GET",
      url: "/v1/nothing",
      headers: bearer(),
    });
    expectError(authenticated, "not_found");
    const outside = await app.inject({ method: "GET", url: "/nothing" });
    expectError(outside, "not_found");
    await app.close();
  });
});

describe("the log", () => {
  it("never holds a token, whatever the request or the line", async () => {
    const { app, logs } = startApp(db);
    await putRecord(app, "ride-1", ride(), bearer(TABLET));
    await putRecord(app, "ride-1", ride(), {
      authorization: "Bearer guess-guess-guess-guess-guess-guess-x",
    });
    // Even a handler that logs the headers it was given:
    app.log.info({ req: { headers: bearer(TABLET) } }, "request");
    app.log.info({ headers: bearer(COMPANION) }, "headers");
    await app.close();

    const text = logs.join("\n");
    expect(text.length).toBeGreaterThan(0);
    expect(text).not.toContain(TABLET.token);
    expect(text).not.toContain(COMPANION.token);
    expect(text).not.toContain("guess-guess");
    expect(text).toContain("[redacted]");
  });

  it("names the device of an upload", async () => {
    const { app, logs } = startApp(db);
    await putRecord(app, "ride-1", ride(), bearer(COMPANION));
    await app.close();
    expect(logs.join("\n")).toMatch(/"device":"companion"/);
  });
});
