import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  ROUTE_MAX_POINTS,
  type Route,
  type RoutePoint,
  routeAscentMeters,
  routeKey,
  routePointsHash,
  routeSessionHash,
  validateRideRecord,
  validateRoute,
  validateRoutesResponse,
  validateWorkout,
  validateWorkoutsResponse,
  variantStructureHash,
  type WorkoutsResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/routes/", import.meta.url).pathname;

function load(kind: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, kind, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

function valid(kind: string, name: string): unknown {
  return JSON.parse(readFileSync(join(fixtures, kind, "valid", name), "utf8"));
}

/** Every directory of fixtures that is a pair of valid/ and invalid/ with the validator that reads it. */
const KINDS: [string, (input: unknown) => { ok: boolean; errors?: string[] }][] = [
  ["route", validateRoute],
  ["routes-response", validateRoutesResponse],
  ["ride-record", validateRideRecord],
  ["workout", validateWorkout],
  ["workouts-response", validateWorkoutsResponse],
];

describe.each(KINDS)("route fixtures: %s", (kind, validate) => {
  it("has fixtures in both directories", () => {
    expect(load(kind, "valid").length).toBeGreaterThanOrEqual(2);
    expect(load(kind, "invalid").length).toBeGreaterThanOrEqual(5);
  });

  it.each(load(kind, "valid"))("accepts valid/%s", (_name, body) => {
    const result = validate(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load(kind, "invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validate(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors?.length).toBeGreaterThan(0);
  });
});

describe("route rules a schema cannot state", () => {
  const errors = (name: string): string => {
    const found = load("route", "invalid").find(([file]) => file === name);
    const result = validateRoute(found?.[1]);
    return result.ok ? "" : result.errors.join("; ");
  };

  it("says which rule a consistent-looking route breaks", () => {
    expect(errors("first-d-not-zero.json")).toMatch(/not 0/);
    expect(errors("d-not-increasing.json")).toMatch(/does not increase/);
    expect(errors("d-repeats.json")).toMatch(/does not increase/);
    expect(errors("distance-does-not-match.json")).toMatch(/\/distanceMeters is 120/);
    expect(errors("ascent-does-not-match.json")).toMatch(/\/ascentMeters is 7/);
    expect(errors("coordinates-on-some-points.json")).toMatch(
      /every point has lat and lon or none/,
    );
    expect(errors("has-coordinates-wrong.json")).toMatch(/hasCoordinates/);
    expect(errors("profile-with-coordinates.json")).toMatch(/false for source profile/);
    expect(errors("gpx-without-coordinates.json")).toMatch(/true for source gpx/);
    expect(errors("created-at-impossible.json")).toMatch(/real date/);
  });

  it("accepts the largest route and refuses one more point", () => {
    const base = valid("route", "profile.json") as Route;
    const points: RoutePoint[] = Array.from({ length: ROUTE_MAX_POINTS }, (_v, i) => ({
      d: i * 20,
      e: 100,
    }));
    const full: Route = {
      ...base,
      points,
      distanceMeters: (ROUTE_MAX_POINTS - 1) * 20,
      ascentMeters: 0,
    };
    expect(validateRoute(full)).toMatchObject({ ok: true });
    const over: Route = {
      ...full,
      points: [...points, { d: ROUTE_MAX_POINTS * 20, e: 100 }],
      distanceMeters: ROUTE_MAX_POINTS * 20,
    };
    expect(validateRoute(over).ok).toBe(false);
  });

  it("allows a tolerance of one metre in the ascent, not two", () => {
    const base = valid("route", "profile.json") as Route;
    expect(validateRoute({ ...base, ascentMeters: base.ascentMeters + 1 }).ok).toBe(true);
    expect(validateRoute({ ...base, ascentMeters: base.ascentMeters + 2 }).ok).toBe(false);
  });

  it("sums only the rises", () => {
    expect(routeAscentMeters([{ e: 10 }, { e: 12 }, { e: 11 }, { e: 15.5 }, { e: 15 }])).toBe(6.5);
    expect(routeAscentMeters([{ e: 5 }])).toBe(0);
    expect(routeAscentMeters([])).toBe(0);
  });
});

describe("the route key", () => {
  interface Golden {
    cases: {
      name: string;
      routeId: string;
      points: RoutePoint[];
      canonicalText: string;
      pointsHash: string;
      routeKey: string;
      sessionHash: string;
    }[];
  }
  const golden = JSON.parse(readFileSync(join(fixtures, "route-key.json"), "utf8")) as Golden;

  it.each(golden.cases)("$name has the golden hash and key", (c) => {
    expect(routePointsHash(c.points)).toBe(c.pointsHash);
    expect(routeKey(c.routeId, c.points)).toBe(c.routeKey);
    expect(routeSessionHash(c.routeKey)).toBe(c.sessionHash);
    expect(c.routeKey.length).toBeLessThanOrEqual(64);
    expect(c.canonicalText.startsWith("overdrive.route-points.v1\n")).toBe(true);
  });

  it("is the same for the same course and different for another", () => {
    const [first, second] = golden.cases;
    if (first === undefined || second === undefined) throw new Error("no cases");
    expect(routePointsHash(first.points)).toBe(routePointsHash(structuredClone(first.points)));
    expect(routePointsHash(first.points)).not.toBe(routePointsHash(second.points));
    // One decimetre of elevation is another course; a difference below the rounding is not.
    const nudged = first.points.map((p, i) => (i === 3 ? { ...p, e: p.e + 0.1 } : p));
    expect(routePointsHash(nudged)).not.toBe(routePointsHash(first.points));
    const noise = first.points.map((p, i) => (i === 3 ? { ...p, e: p.e + 0.01 } : p));
    expect(routePointsHash(noise)).toBe(routePointsHash(first.points));
  });
});

describe("the variant's hash", () => {
  it("is the hash of the primary workout with the variant's steps", () => {
    const response = valid("workouts-response", "with-variant.json") as WorkoutsResponse;
    const [entry] = response.workouts;
    if (entry?.variant === undefined) throw new Error("no variant in the fixture");
    expect(variantStructureHash(entry.workout, entry.variant.steps)).toBe(
      entry.variant.structureHash,
    );
    // The primary's own structure is another session.
    expect(variantStructureHash(entry.workout, entry.workout.steps)).not.toBe(
      entry.variant.structureHash,
    );
  });
});
