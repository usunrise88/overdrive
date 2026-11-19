import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { parseInstant } from "./wellness.ts";

// ---- Routes with elevation (spec §11.4, §8.3, ADR 0036) -------------------------------------------------------------
// Hand-written mirrors of schema/route.schema.json (one route with its points) and schema/routes-response.schema.json
// (the list), which are the source of truth; the fixtures test keeps the two honest. The agent creates routes over MCP
// (`create_route`); the tablet reads them (`GET /v1/routes`, `GET /v1/routes/{id}`) and computes the grade from the points
// itself: the service sends no grade.

/** How a route came in: a GPX file, waypoints routed over roads, or a synthetic elevation profile. */
export const ROUTE_SOURCES = ["gpx", "waypoints", "profile"] as const;
export type RouteSource = (typeof ROUTE_SOURCES)[number];

/** A route id as a free step's `route_id` and a ride's `routeId` carry it: 1 to 64 characters, starting with a letter or digit. */
export const ROUTE_ID_PATTERN: RegExp = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export function isValidRouteId(id: string): boolean {
  return ROUTE_ID_PATTERN.test(id);
}

/** The service resamples a route to one point every this many metres, unless a setting says otherwise. */
export const ROUTE_DEFAULT_STEP_METERS = 20;
/** The elevation is smoothed with a moving average over about this many metres. */
export const ROUTE_SMOOTHING_METERS = 60;
/** A route has at most this many points: a longer one is resampled with a coarser step. */
export const ROUTE_MAX_POINTS = 5000;
/** The shortest route the service keeps, in metres. */
export const ROUTE_MIN_DISTANCE_METERS = 100;
/** The grade of a synthetic profile's segment is clamped to plus and minus this many percent. */
export const ROUTE_PROFILE_MAX_GRADE_PERCENT = 20;
/** The longest synthetic profile, in metres. */
export const ROUTE_PROFILE_MAX_METERS = 200_000;

/** One resampled point. `lat` and `lon` come together, and on all points of a route or on none. */
export interface RoutePoint {
  /** Metres along the route from its start; the first is 0. */
  d: number;
  /** Elevation in metres above sea level, smoothed. */
  e: number;
  lat?: number;
  lon?: number;
}

/** A route without its points: a row of `GET /v1/routes`. */
export interface RouteSummary {
  id: string;
  name: string;
  source: RouteSource;
  /** The last point's `d`, rounded to a whole metre. */
  distanceMeters: number;
  /** The sum of the rises between consecutive points, rounded to a whole metre. */
  ascentMeters: number;
  /** true when every point has `lat` and `lon`: the route follows real roads and can write positions into a FIT file. */
  hasCoordinates: boolean;
  /** UTC instant. */
  createdAt: string;
}

/** One route with its points: `GET /v1/routes/{id}`. */
export interface Route extends RouteSummary {
  points: RoutePoint[];
}

/** The answer to `GET /v1/routes`. */
export interface RoutesResponse {
  generatedAt: string;
  /** Newest first, then by id. */
  routes: RouteSummary[];
}

// ---- Numbers the service and a checker both compute -----------------------------------------------------------------

/** The sum of the rises between consecutive points, in metres (not rounded): descents count for nothing. */
export function routeAscentMeters(points: readonly Pick<RoutePoint, "e">[]): number {
  let ascent = 0;
  for (let i = 1; i < points.length; i++) {
    const rise = (points[i] as RoutePoint).e - (points[i - 1] as RoutePoint).e;
    if (rise > 0) ascent += rise;
  }
  return ascent;
}

/** `d` and `e` to a tenth of a metre, `lat` and `lon` to a millionth of a degree (about 0.1 m): the text a point is hashed as. */
function pointText(point: RoutePoint): string {
  const base = `${point.d.toFixed(1)} ${point.e.toFixed(1)}`;
  return point.lat === undefined || point.lon === undefined
    ? base
    : `${base} ${point.lat.toFixed(6)} ${point.lon.toFixed(6)}`;
}

/** The version of the definition of {@link routePointsHash}; it is part of what is hashed. */
export const ROUTE_HASH_VERSION = 1;

/**
 * The hash of a route's points: the first 16 hex characters of the SHA-256 of the line `overdrive.route-points.v1`, a line feed
 * and one line per point (`d e` or `d e lat lon`, see {@link pointText}). It says whether two copies of a route are the same
 * course. Golden numbers are in `fixtures/routes/route-key.json`.
 */
export function routePointsHash(points: readonly RoutePoint[]): string {
  const text = `overdrive.route-points.v${ROUTE_HASH_VERSION}\n${points.map(pointText).join("\n")}`;
  return createHash("sha256").update(text).digest("hex").slice(0, 16);
}

/**
 * What identifies the course two rides share (spec §11.7): the route id and the hash of its points, `route-3f9a2c:0123456789abcdef`.
 * 1 to 64 characters, so it fits the `routeKey` of the repeats contract. Two rides with the same key rode the same route.
 */
export function routeKey(routeId: string, points: readonly RoutePoint[]): string {
  return `${routeId}:${routePointsHash(points)}`;
}

/** The version of the definition of {@link routeSessionHash}. */
export const ROUTE_SESSION_HASH_VERSION = 1;

/**
 * The 16 hex characters that identify the session two rides on the same route share (`GET /v1/repeats` calls it `hash`): the first 16
 * hex characters of the SHA-256 of the line `overdrive.route-session.v1`, a line feed and the {@link routeKey}. It cannot collide with
 * a workout's hash, which is taken over another text.
 */
export function routeSessionHash(key: string): string {
  return createHash("sha256")
    .update(`overdrive.route-session.v${ROUTE_SESSION_HASH_VERSION}\n${key}`)
    .digest("hex")
    .slice(0, 16);
}

// ---- Validation ----------------------------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

// The list's schema points at the route's by its file name.
ajv.addSchema(load("route.schema.json"), "route.schema.json");
const checkRoute = ajv.compile<Route>(load("route.schema.json"));
const checkRoutes = ajv.compile<RoutesResponse>(load("routes-response.schema.json"));

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

const MAX_REPORTED = 5;

/** What the schema cannot say about a route (see its description). Returns the violations, or an empty list. */
export function routeConsistencyErrors(route: Route): string[] {
  const errors: string[] = [];
  const { points } = route;
  if (parseInstant(route.createdAt) === null) errors.push("/createdAt is not a real date and time");

  const first = points[0];
  const last = points[points.length - 1];
  if (first !== undefined && first.d !== 0) errors.push(`/points/0/d is ${first.d}, not 0`);
  for (let i = 1; i < points.length; i++) {
    const before = (points[i - 1] as RoutePoint).d;
    const here = (points[i] as RoutePoint).d;
    if (here <= before) {
      errors.push(`/points/${i}/d ${here} does not increase from ${before}`);
      break;
    }
  }
  if (last !== undefined && route.distanceMeters !== Math.round(last.d)) {
    errors.push(`/distanceMeters is ${route.distanceMeters}, the last point is at ${last.d}`);
  }
  const ascent = routeAscentMeters(points);
  if (Math.abs(route.ascentMeters - ascent) > 1) {
    errors.push(
      `/ascentMeters is ${route.ascentMeters}, the points rise ${Math.round(ascent * 10) / 10}`,
    );
  }

  const withCoordinates = points.filter((p) => p.lat !== undefined && p.lon !== undefined).length;
  if (withCoordinates !== 0 && withCoordinates !== points.length) {
    errors.push("/points: either every point has lat and lon or none does");
  } else if (route.hasCoordinates !== (withCoordinates === points.length && points.length > 0)) {
    errors.push(
      `/hasCoordinates is ${route.hasCoordinates} but ${withCoordinates} of ${points.length} points have coordinates`,
    );
  }
  if (route.source === "profile" && route.hasCoordinates) {
    errors.push("/hasCoordinates must be false for source profile");
  }
  if (route.source !== "profile" && !route.hasCoordinates) {
    errors.push(`/hasCoordinates must be true for source ${route.source}`);
  }
  return errors.slice(0, MAX_REPORTED);
}

/** Validates a route: the schema, then the rules a schema cannot state. */
export function validateRoute(input: unknown): Validation<Route> {
  if (!checkRoute(input)) return { ok: false, errors: plain(checkRoute.errors) };
  const errors = routeConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** What the schema cannot say about the list: unique ids, newest first, real instants. */
export function routesResponseConsistencyErrors(response: RoutesResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  const seen = new Set<string>();
  let previous: RouteSummary | null = null;
  for (const [index, route] of response.routes.entries()) {
    const at = `/routes/${index}`;
    if (seen.has(route.id)) errors.push(`${at}/id ${route.id} is used twice`);
    seen.add(route.id);
    const created = parseInstant(route.createdAt);
    if (created === null) errors.push(`${at}/createdAt is not a real date and time`);
    if (previous !== null && created !== null) {
      const before = parseInstant(previous.createdAt);
      if (before !== null && (created > before || (created === before && route.id < previous.id))) {
        errors.push(`${at} is out of order: newest first, then by id`);
      }
    }
    previous = route;
    if (route.source === "profile" && route.hasCoordinates) {
      errors.push(`${at}/hasCoordinates must be false for source profile`);
    }
    if (route.source !== "profile" && !route.hasCoordinates) {
      errors.push(`${at}/hasCoordinates must be true for source ${route.source}`);
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateRoutesResponse(input: unknown): Validation<RoutesResponse> {
  if (!checkRoutes(input)) return { ok: false, errors: plain(checkRoutes.errors) };
  const errors = routesResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
