import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { BrouterError, createBrouterClient } from "../src/routes/brouter.ts";
import { type FakeBrouter, startFakeBrouter } from "./helpers/fake-brouter.ts";

/**
 * The BRouter client (spec §11.4, ADR 0036) against a fake server that answers as the real BRouter 1.7.10 did in the probe: GeoJSON
 * with string properties on success, plain text or an empty body on failure.
 */

let fake: FakeBrouter;

beforeAll(async () => {
  fake = await startFakeBrouter();
});

afterAll(async () => {
  await fake.close();
});

beforeEach(() => {
  fake.reset();
});

const A = { lon: 7.0, lat: 45.0 };
const B = { lon: 7.01, lat: 45.01 };

const client = (timeoutMs = 5000) => createBrouterClient({ baseUrl: fake.url, timeoutMs });

async function failure(promise: Promise<unknown>): Promise<BrouterError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(BrouterError);
    return error as BrouterError;
  }
  throw new Error("expected a BrouterError");
}

describe("the request", () => {
  it("is a GET of /brouter with the waypoints in one parameter, the pipe percent-encoded, the profile and geojson", async () => {
    await client().route([A, B, { lon: 7.02, lat: 45.0 }], "fastbike");
    const [request] = fake.requests;
    expect(request?.path).toBe("/brouter");
    expect(request?.query).toEqual({
      lonlats: "7.000000,45.000000|7.010000,45.010000|7.020000,45.000000",
      profile: "fastbike",
      format: "geojson",
    });
    // The pipe is not sent raw: it is one query parameter, never repeated.
    expect(request?.rawQuery).toContain("%7C");
    expect(request?.rawQuery).not.toContain("|");
    expect(request?.rawQuery.match(/lonlats=/g)).toHaveLength(1);
  });

  it("puts the longitude first and keeps six decimals", async () => {
    await client().route([{ lon: 7.123456789, lat: 45.5 }, B], "trekking");
    expect(fake.requests[0]?.query.lonlats?.startsWith("7.123457,45.500000|")).toBe(true);
  });

  it("works with a base address that has a trailing slash", async () => {
    await createBrouterClient({ baseUrl: `${fake.url}/` }).route([A, B], "gravel");
    expect(fake.requests[0]?.path).toBe("/brouter");
  });
});

describe("the answer", () => {
  it("is the line of [lon, lat, ele] and the engine's length, which BRouter sends as a string", async () => {
    const answer = await client().route([A, B], "trekking");
    expect(answer.track.length).toBeGreaterThan(50);
    expect(answer.track[0]).toEqual({ lon: 7, lat: 45, ele: expect.any(Number) });
    const last = answer.track[answer.track.length - 1];
    expect(last?.lon).toBeCloseTo(7.01, 6);
    expect(last?.lat).toBeCloseTo(45.01, 6);
    // The engine said "track-length": "1234" (a string); the client hands it over as a number.
    expect(typeof answer.engineLengthMeters).toBe("number");
    expect(answer.engineLengthMeters).toBeGreaterThan(1000);
  });

  it("joins the legs of a route with more than two waypoints", async () => {
    const two = await client().route([A, B], "trekking");
    const three = await client().route([A, B, { lon: 7.02, lat: 45.0 }], "trekking");
    expect(three.track.length).toBeGreaterThan(two.track.length);
  });
});

describe("the failures, in plain words", () => {
  it("a waypoint outside the map data: 400 datafile … not found", async () => {
    const error = await failure(client().route([{ lon: 3.0, lat: 45.0 }, B], "trekking"));
    expect(error.kind).toBe("no-map-data");
    expect(error.status).toBe(400);
    expect(error.message).toMatch(/there is no map data for this area/);
    expect(error.message).toMatch(/E0_N45\.rd5/);
    expect(error.message).toMatch(/docs\/deploy\.md/);
    expect(error.message).toMatch(/GPX file or a profile needs no map data/);
  });

  it("an unknown profile: 500 with an empty body", async () => {
    const error = await failure(client().route([A, B], "mtb" as never));
    expect(error.kind).toBe("bad-request");
    expect(error.status).toBe(500);
    expect(error.message).toMatch(/could not use the request/);
    expect(error.message).toMatch(/trekking, fastbike or gravel/);
  });

  it("BRouter's own words about a route it cannot find: another 400", async () => {
    fake.failNext(400, "target island detected for section 0");
    const error = await failure(client().route([A, B], "trekking"));
    expect(error.kind).toBe("bad-request");
    expect(error.message).toMatch(
      /could not route these waypoints \(it said: target island detected for section 0\)/,
    );
  });

  it("anything else that is not a 2xx: the service is unavailable", async () => {
    for (const status of [404, 502, 503, 504]) {
      fake.failNext(status);
      const error = await failure(client().route([A, B], "trekking"));
      expect(error.kind, String(status)).toBe("unavailable");
      expect(error.message).toMatch(new RegExp(`unavailable \\(it answered ${status}\\)`));
    }
    // A 500 that says something is not "a bad request": the body is never parsed as JSON.
    fake.failNext(500, '{"error":"oops"}');
    expect((await failure(client().route([A, B], "trekking"))).kind).toBe("unavailable");
  });

  it("a connection that is refused", async () => {
    const dead = createBrouterClient({ baseUrl: "http://127.0.0.1:1", timeoutMs: 2000 });
    const error = await failure(dead.route([A, B], "trekking"));
    expect(error.kind).toBe("unavailable");
    expect(error.message).toMatch(/did not answer/);
    expect(error.message).toMatch(/GPX file or a profile/);
  });

  it("a server that never answers, after the timeout", async () => {
    fake.setHanging(true);
    const error = await failure(client(60).route([A, B], "trekking"));
    expect(error.kind).toBe("unavailable");
    expect(error.message).toMatch(/timed out/);
  });

  it("a 200 that is not a route", async () => {
    fake.garbageNext();
    const error = await failure(client().route([A, B], "trekking"));
    expect(error.kind).toBe("unavailable");
    expect(error.message).toMatch(/something that is not a route/);
  });

  it("never puts the address of the routing server in a message", async () => {
    fake.failNext(503);
    const error = await failure(client().route([A, B], "trekking"));
    expect(error.message).not.toContain(fake.url);
    expect(error.message).not.toContain("127.0.0.1");
  });
});
