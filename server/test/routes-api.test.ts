import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  type Route,
  type RouteSummary,
  type RoutesResponse,
  routeAscentMeters,
  validateApiError,
  validateRoute,
  validateRoutesResponse,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeBrouter, startFakeBrouter } from "./helpers/fake-brouter.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/**
 * Routes through MCP and REST (spec §11.4, ADR 0036): `create_route` in its three modes, `list_routes`, `delete_route`, and what the
 * tablet reads (`GET /v1/routes`, `GET /v1/routes/{id}`). The routing engine is the fake of `helpers/fake-brouter.ts`; every
 * coordinate and elevation is invented.
 */

let db: Db;
let fake: FakeIntervals;
let brouter: FakeBrouter;
let h: McpHarness;
let client: Client;
/** A second service with road routing off (no BROUTER_URL). */
let off: McpHarness;
let offClient: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  brouter = await startFakeBrouter();
  h = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
    config: { BROUTER_URL: brouter.url, BROUTER_TIMEOUT_SECONDS: 5 },
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
  off = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
  });
  offClient = await connectWithToken(off.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await offClient.close();
  await h.close();
  await off.close();
  await brouter.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await db.exec("TRUNCATE routes, ride_routes, planned_workout_extras");
  brouter.reset();
  h.clock.set("2026-11-19T09:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

interface Created extends RouteSummary {
  created: true;
  pointCount: number;
  pointSpacingMeters: number;
  notes?: string[];
  next: string;
}

const get = (url: string, headers: Record<string, string> = bearer(TABLET)) =>
  h.app.inject({ method: "GET", url, headers });

/** 6 track points 100.08 m apart along a meridian, rising 2 m each: a steady 2% climb, invented. */
const GPX = `<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
  <trk><name>North road</name><trkseg>
    ${[0, 1, 2, 3, 4, 5]
      .map(
        (k) =>
          `<trkpt lat="${(45 + k * 0.0009).toFixed(4)}" lon="7.0000"><ele>${250 + 2 * k}</ele></trkpt>`,
      )
      .join("\n    ")}
  </trkseg></trk>
</gpx>`;

const HILL = [
  { lengthMeters: 100, gradePercent: 5 },
  { lengthMeters: 100, gradePercent: -5 },
];

describe("the tools and what they say about themselves", () => {
  it("are described for a model: three ways in, what each needs, that the server computes the numbers", async () => {
    const { tools } = await client.listTools();
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    const create = by.create_route;
    expect(create?.description).toMatch(/mode gpx/);
    expect(create?.description).toMatch(/mode waypoints/);
    expect(create?.description).toMatch(/mode profile/);
    expect(create?.description).toMatch(/LONGITUDE FIRST/);
    expect(create?.description).toMatch(/does not guess/);
    expect(create?.description).toMatch(/at most 2 MB/);
    expect(create?.description).toMatch(/200 km/);
    expect(create?.description).toMatch(/plus or minus 20%/);
    expect(create?.description).toMatch(/quote them, do not calculate them/);
    expect(create?.description).toMatch(/route_id/);
    const schema = (create?.inputSchema ?? {}) as {
      type?: string;
      required?: string[];
      properties?: Record<string, { description?: string }>;
    };
    expect(schema.type).toBe("object");
    expect(schema.required).toEqual(["mode", "name"]);
    // Each mode's field says which mode it belongs to.
    const props = schema.properties ?? {};
    expect(props.gpx?.description).toMatch(/^mode gpx/);
    expect(props.waypoints?.description).toMatch(/^mode waypoints/);
    expect(props.segments?.description).toMatch(/^mode profile/);
    expect(by.list_routes?.annotations?.readOnlyHint).toBe(true);
    expect(by.create_route?.annotations?.readOnlyHint).toBe(false);
    expect(by.delete_route?.annotations?.destructiveHint).toBe(true);
    expect(by.delete_route?.description).toMatch(/Rides already recorded on it are kept/);
    expect(by.list_routes?.description).toMatch(/route_id/);
  });
});

describe("create_route, mode profile", () => {
  it("makes the points of a hill, with the numbers worked out by code", async () => {
    const result = await call("create_route", {
      mode: "profile",
      name: "  Rolling hill  ",
      segments: HILL,
      startElevationMeters: 100,
    });
    expect(result.isError, result.text).toBe(false);
    const created = result.json as Created;
    expect(created).toMatchObject({
      created: true,
      name: "Rolling hill",
      source: "profile",
      distanceMeters: 200,
      ascentMeters: 4,
      hasCoordinates: false,
      createdAt: "2026-11-19T09:00:00.000Z",
      pointCount: 11,
      pointSpacingMeters: 20,
    });
    expect(created.id).toMatch(/^route-[0-9a-f]{10}$/);
    expect(created.next).toContain(`route_id "${created.id}"`);
    expect(created.notes).toBeUndefined();

    const stored = (await get(`/v1/routes/${created.id}`)).json() as Route;
    expect(stored.points.map((p) => p.e)).toEqual([
      100, 101, 102, 103, 104, 104.3, 104, 103, 102, 101, 100,
    ]);
    expect(stored.points.every((p) => p.lat === undefined && p.lon === undefined)).toBe(true);
  });

  it("clamps a grade beyond 20% and says so", async () => {
    const result = await call("create_route", {
      mode: "profile",
      name: "A wall",
      segments: [
        { lengthMeters: 500, gradePercent: 45 },
        { lengthMeters: 500, gradePercent: 3 },
      ],
    });
    const created = result.json as Created;
    expect(created.notes).toEqual(["1 segment had a grade beyond 20% and was clamped to it"]);
    // 500 m at 20% is 100 m of climb, then 15 m: 115 m, less what the smoothing takes off the corner.
    expect(created.ascentMeters).toBeGreaterThanOrEqual(113);
    expect(created.ascentMeters).toBeLessThanOrEqual(115);
  });

  it("says a coarser step when the profile is long", async () => {
    const result = await call("create_route", {
      mode: "profile",
      name: "A long way",
      segments: [{ lengthMeters: 150_000, gradePercent: 0.5 }],
    });
    const created = result.json as Created;
    expect(created.pointSpacingMeters).toBe(31);
    expect(created.pointCount).toBeLessThanOrEqual(5000);
    expect(created.notes?.[0]).toMatch(/31 m apart instead of 20 m/);
    expect(created.distanceMeters).toBe(150_000);
  });

  it("refuses what is wrong in plain words and creates nothing", async () => {
    const refused = async (args: Record<string, unknown>) => {
      const result = await call("create_route", args);
      expect(result.isError, JSON.stringify(args)).toBe(true);
      return result.text;
    };
    expect(await refused({ mode: "profile", name: "x", segments: [] })).toMatch(
      /give at least one segment/,
    );
    expect(await refused({ mode: "profile", name: "x" })).toMatch(/segments/);
    expect(
      await refused({
        mode: "profile",
        name: "x",
        segments: [{ lengthMeters: 0, gradePercent: 1 }],
      }),
    ).toMatch(/segments\.0\.lengthMeters/);
    expect(
      await refused({
        mode: "profile",
        name: "x",
        segments: [{ lengthMeters: 250_000, gradePercent: 0 }],
      }),
    ).toMatch(/longer than 200 km/);
    expect(
      await refused({
        mode: "profile",
        name: "x",
        segments: [{ lengthMeters: 50, gradePercent: 0 }],
      }),
    ).toMatch(/at least 100 m/);
    expect(
      await refused({
        mode: "profile",
        name: "x",
        segments: [{ lengthMeters: 5000, gradePercent: -20 }],
        startElevationMeters: 0,
      }),
    ).toMatch(/not plausible/);
    expect(await refused({ mode: "profile", name: "   ", segments: HILL })).toMatch(/name/);
    expect(await refused({ mode: "profile", name: "n".repeat(101), segments: HILL })).toMatch(
      /name/,
    );
    expect(await refused({ mode: "bike", name: "x" })).toMatch(/mode/);
    expect((await call("list_routes")).json).toMatchObject({ count: 0 });
  });
});

describe("create_route, mode gpx", () => {
  it("makes a route with coordinates from a GPX file", async () => {
    const result = await call("create_route", { mode: "gpx", name: "North road", gpx: GPX });
    expect(result.isError, result.text).toBe(false);
    const created = result.json as Created;
    // Five legs of 100.0756 m: 500.378 m, rounded to 500; the points every 20 m and the end at 500.4.
    expect(created).toMatchObject({
      source: "gpx",
      distanceMeters: 500,
      hasCoordinates: true,
      pointCount: 26,
      pointSpacingMeters: 20,
    });
    // A straight climb of 10 m in 500 m.
    expect(created.ascentMeters).toBe(10);

    const route = (await get(`/v1/routes/${created.id}`)).json() as Route;
    expect(validateRoute(route)).toMatchObject({ ok: true });
    expect(route.points[0]).toEqual({ d: 0, e: 250, lat: 45, lon: 7 });
    expect(route.points[1]).toMatchObject({ d: 20, e: 250.4 });
    expect(route.points[1]?.lat).toBeCloseTo(45 + 20 / 111_195.0802, 5);
    expect(route.points[25]).toMatchObject({ d: 500.4, e: 260, lat: 45.0045, lon: 7 });
  });

  it("refuses a GPX with a point that has no elevation, and a hostile one, in plain words", async () => {
    const noEle = GPX.replace("<ele>254</ele>", "");
    const missing = await call("create_route", { mode: "gpx", name: "x", gpx: noEle });
    expect(missing.isError).toBe(true);
    expect(missing.text).toMatch(
      /1 of 6 points of the GPX have no <ele> elevation \(the first is point 3\)/,
    );
    const bomb = await call("create_route", {
      mode: "gpx",
      name: "x",
      gpx: `<!DOCTYPE x [<!ENTITY a "aaaa">]>${GPX}`,
    });
    expect(bomb.isError).toBe(true);
    expect(bomb.text).toMatch(/DOCTYPE/);
    expect((await call("create_route", { mode: "gpx", name: "x" })).text).toMatch(/gpx/);
    expect((await call("create_route", { mode: "gpx", name: "x", gpx: "" })).text).toMatch(
      /gpx is empty/,
    );
    expect((await call("list_routes")).json).toMatchObject({ count: 0 });
  });

  it("takes a GPX of a little under 2 MB through the whole path, MCP body included", async () => {
    const points: string[] = [];
    for (let i = 0; i < 30_000; i++) {
      points.push(
        `<trkpt lat="${(45 + i * 0.00002).toFixed(6)}" lon="7.000000"><ele>${200 + (i % 30)}</ele></trkpt>`,
      );
    }
    const big = `<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>${points.join("\n")}</trkseg></trk></gpx>`;
    expect(Buffer.byteLength(big)).toBeGreaterThan(1_400_000);
    expect(Buffer.byteLength(big)).toBeLessThan(2 * 1024 * 1024);
    const result = await call("create_route", { mode: "gpx", name: "Long straight", gpx: big });
    expect(result.isError, result.text).toBe(false);
    // 30,000 points 2.22 m apart: 66.7 km.
    expect((result.json as Created).distanceMeters).toBeGreaterThan(66_000);
  });
});

describe("create_route, mode waypoints", () => {
  const WAYPOINTS = [
    { lon: 7.0, lat: 45.0 },
    { lon: 7.01, lat: 45.01 },
  ];

  it("turns waypoints into a road route with the routing engine and writes it like any other", async () => {
    const result = await call("create_route", {
      mode: "waypoints",
      name: "Valley loop",
      waypoints: WAYPOINTS,
      routingProfile: "fastbike",
    });
    expect(result.isError, result.text).toBe(false);
    const created = result.json as Created;
    expect(created).toMatchObject({ source: "waypoints", hasCoordinates: true });
    expect(created.distanceMeters).toBeGreaterThan(1200);
    expect(created.pointSpacingMeters).toBe(20);

    expect(brouter.requests).toHaveLength(1);
    expect(brouter.requests[0]?.query).toEqual({
      lonlats: "7.000000,45.000000|7.010000,45.010000",
      profile: "fastbike",
      format: "geojson",
    });
    const route = (await get(`/v1/routes/${created.id}`)).json() as Route;
    expect(validateRoute(route)).toMatchObject({ ok: true });
    expect(route.points[0]).toMatchObject({ lat: 45, lon: 7 });
    const last = route.points[route.points.length - 1];
    expect(last?.lat).toBeCloseTo(45.01, 5);
    expect(last?.lon).toBeCloseTo(7.01, 5);
  });

  it("defaults to the trekking profile", async () => {
    await call("create_route", { mode: "waypoints", name: "Default", waypoints: WAYPOINTS });
    expect(brouter.requests[0]?.query.profile).toBe("trekking");
  });

  it("says plainly that routing is off when there is no BROUTER_URL", async () => {
    const result = await callTool(offClient, "create_route", {
      mode: "waypoints",
      name: "x",
      waypoints: WAYPOINTS,
    });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/road routing is off: this server has no BROUTER_URL/);
    expect(result.text).toMatch(
      /GPX file \(mode gpx\) or as a synthetic elevation profile \(mode profile\)/,
    );
    expect((await callTool(offClient, "list_routes")).json).toMatchObject({ routingOn: false });
    expect((await call("list_routes")).json).toMatchObject({ routingOn: true });
  });

  it("maps the engine's failures to plain words", async () => {
    const outside = await call("create_route", {
      mode: "waypoints",
      name: "x",
      waypoints: [{ lon: 2.0, lat: 45.0 }, WAYPOINTS[1]],
    });
    expect(outside.isError).toBe(true);
    expect(outside.text).toMatch(/there is no map data for this area/);
    expect(outside.text).toMatch(/E0_N45\.rd5/);

    brouter.failNext(503);
    const down = await call("create_route", { mode: "waypoints", name: "x", waypoints: WAYPOINTS });
    expect(down.isError).toBe(true);
    expect(down.text).toMatch(/the routing service is unavailable/);

    brouter.profiles.clear();
    const bad = await call("create_route", { mode: "waypoints", name: "x", waypoints: WAYPOINTS });
    expect(bad.isError).toBe(true);
    expect(bad.text).toMatch(/could not use the request/);
    expect((await call("list_routes")).json).toMatchObject({ count: 0 });
  });

  it("refuses too few or too many waypoints, a place that is not on Earth and an unknown profile", async () => {
    const refused = async (args: Record<string, unknown>) => {
      const result = await call("create_route", { mode: "waypoints", name: "x", ...args });
      expect(result.isError, JSON.stringify(args)).toBe(true);
      return result.text;
    };
    expect(await refused({ waypoints: [WAYPOINTS[0]] })).toMatch(/give at least 2 waypoints/);
    expect(
      await refused({
        waypoints: Array.from({ length: 26 }, (_v, i) => ({ lon: 7 + i * 0.001, lat: 45 })),
      }),
    ).toMatch(/give at most 25 waypoints/);
    expect(await refused({ waypoints: [{ lon: 45, lat: 190 }, WAYPOINTS[1]] })).toMatch(
      /waypoints\.0\.lat/,
    );
    expect(await refused({ waypoints: WAYPOINTS, routingProfile: "mtb" })).toMatch(
      /routingProfile|Invalid option/i,
    );
    expect(await refused({})).toMatch(/waypoints/);
    expect(brouter.requests).toHaveLength(0);
    // 25 waypoints are fine.
    const ok = await call("create_route", {
      mode: "waypoints",
      name: "Twenty-five",
      waypoints: Array.from({ length: 25 }, (_v, i) => ({
        lon: 7 + i * 0.0005,
        lat: 45 + i * 0.0005,
      })),
    });
    expect(ok.isError, ok.text).toBe(false);
  });
});

describe("list_routes and delete_route", () => {
  it("lists newest first, without the points, and deletes", async () => {
    const first = (await call("create_route", { mode: "profile", name: "First", segments: HILL }))
      .json as Created;
    h.clock.advance(60_000);
    const second = (await call("create_route", { mode: "gpx", name: "Second", gpx: GPX }))
      .json as Created;

    const listed = (await call("list_routes")).json as {
      count: number;
      routes: (RouteSummary & { points?: unknown })[];
    };
    expect(listed.count).toBe(2);
    expect(listed.routes.map((r) => r.name)).toEqual(["Second", "First"]);
    expect(listed.routes[0]).toEqual({
      id: second.id,
      name: "Second",
      source: "gpx",
      distanceMeters: 500,
      ascentMeters: 10,
      hasCoordinates: true,
      createdAt: "2026-11-19T09:01:00.000Z",
    });
    expect(listed.routes[0]?.points).toBeUndefined();

    const deleted = await call("delete_route", { id: first.id });
    expect(deleted.json).toEqual({ id: first.id, deleted: true, workoutsChanged: [] });
    expect((await call("list_routes")).json).toMatchObject({ count: 1 });
    const again = await call("delete_route", { id: first.id });
    expect(again.isError).toBe(true);
    expect(again.text).toMatch(/there is no route/);
    expect((await get(`/v1/routes/${first.id}`)).statusCode).toBe(404);
  });

  it("keeps at most 200 routes and says so", async () => {
    await db.exec(`
      INSERT INTO routes (id, name, source, distance_m, ascent_m, has_coordinates, point_count, points, points_hash, created_at)
      SELECT 'route-bulk-' || n, 'Bulk ' || n, 'profile', 200, 0, false, 2, '[[0,0],[200,0]]'::jsonb, 'x', now()
        FROM generate_series(1, 200) AS n`);
    const result = await call("create_route", {
      mode: "profile",
      name: "One too many",
      segments: HILL,
    });
    expect(result.isError).toBe(true);
    expect(result.text).toMatch(/already 200 routes: delete some with delete_route first/);
  });
});

describe("GET /v1/routes and GET /v1/routes/{id}", () => {
  it("need a device token", async () => {
    expect((await h.app.inject({ method: "GET", url: "/v1/routes" })).statusCode).toBe(401);
    expect((await h.app.inject({ method: "GET", url: "/v1/routes/route-x" })).statusCode).toBe(401);
    expect((await get("/v1/routes", bearer(COMPANION))).statusCode).toBe(200);
  });

  it("list the routes in the contract's shape, newest first, and are not cached", async () => {
    await call("create_route", { mode: "profile", name: "Older", segments: HILL });
    h.clock.advance(1000);
    await call("create_route", { mode: "gpx", name: "Newer", gpx: GPX });
    const response = await get("/v1/routes");
    expect(response.statusCode).toBe(200);
    expect(response.headers["cache-control"]).toBe("no-store");
    const body = response.json() as RoutesResponse;
    expect(validateRoutesResponse(body)).toMatchObject({ ok: true });
    expect(body.generatedAt).toBe("2026-11-19T09:00:01.000Z");
    expect(body.routes.map((r) => r.name)).toEqual(["Newer", "Older"]);
    expect(Object.keys(body.routes[0] as object).sort()).toEqual([
      "ascentMeters",
      "createdAt",
      "distanceMeters",
      "hasCoordinates",
      "id",
      "name",
      "source",
    ]);
  });

  it("answer an empty list", async () => {
    const body = (await get("/v1/routes")).json() as RoutesResponse;
    expect(body.routes).toEqual([]);
    expect(validateRoutesResponse(body)).toMatchObject({ ok: true });
  });

  it("give one route with its points, and the grade is the tablet's to work out", async () => {
    const created = (await call("create_route", { mode: "gpx", name: "North road", gpx: GPX }))
      .json as Created;
    const response = await get(`/v1/routes/${created.id}`);
    expect(response.statusCode).toBe(200);
    expect(response.headers["cache-control"]).toBe("private, max-age=300");
    const route = response.json() as Route;
    expect(validateRoute(route)).toMatchObject({ ok: true });
    expect(route.id).toBe(created.id);
    expect(route.points).toHaveLength(26);
    expect(Math.abs(route.ascentMeters - routeAscentMeters(route.points))).toBeLessThanOrEqual(1);
    // No grade anywhere in the answer.
    expect(JSON.stringify(route)).not.toMatch(/grade/i);
    expect(Object.keys(route.points[0] as object).sort()).toEqual(["d", "e", "lat", "lon"]);
  });

  it("answer 404 not_found in the error shape for a route that does not exist or an id that is not one", async () => {
    for (const id of ["route-nothere", "..%2Fetc", "a".repeat(80), "bad id"]) {
      const response = await get(`/v1/routes/${id}`);
      expect(response.statusCode, id).toBe(404);
      const body = response.json();
      expect(validateApiError(body)).toMatchObject({ ok: true });
      expect((body as { error: { code: string } }).error.code).toBe("not_found");
    }
  });

  it("store the points compactly and give back exactly what was made", async () => {
    const created = (await call("create_route", { mode: "gpx", name: "North road", gpx: GPX }))
      .json as Created;
    const row = await db.query<{ points: unknown; point_count: number; points_hash: string }>(
      "SELECT points, point_count, points_hash FROM routes WHERE id = $1",
      [created.id],
    );
    expect(row.rows[0]?.point_count).toBe(26);
    expect(row.rows[0]?.points).toEqual(expect.arrayContaining([[0, 250, 45, 7]]));
    expect(row.rows[0]?.points_hash).toMatch(/^[0-9a-f]{16}$/);
  });
});
