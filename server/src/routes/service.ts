import { randomBytes } from "node:crypto";
import {
  ROUTE_PROFILE_MAX_METERS,
  type Route,
  type RouteSummary,
  type RoutesResponse,
  routeKey,
  routePointsHash,
  routeSessionHash,
  validateRoute,
} from "@overdrive/contracts";
import type { RoutesSettings } from "../config-routes.ts";
import type { Db } from "../db.ts";
import type { Logger } from "../log.ts";
import {
  BROUTER_PROFILES,
  type BrouterClient,
  BrouterError,
  type BrouterProfile,
  createBrouterClient,
  type Waypoint,
} from "./brouter.ts";
import {
  buildRoute,
  type ProfileSegment,
  profileToRaw,
  RouteError,
  trackToRaw,
} from "./geometry.ts";
import { parseGpx } from "./gpx.ts";
import { createRouteStore, type RouteStore } from "./store.ts";

/**
 * Routes with elevation (spec §11.4, ADR 0036): the agent creates them in one of three ways (a GPX file, waypoints routed over roads by
 * BRouter, a synthetic elevation profile), the tablet reads them. Every route goes through the same geometry: resampled to one point
 * every step, elevation smoothed, distance and ascent worked out by code. Plain-words failures are {@link RouteError} and
 * {@link BrouterError}.
 */

export type CreateRouteInput =
  | { mode: "gpx"; name: string; gpx: string }
  | {
      mode: "waypoints";
      name: string;
      waypoints: Waypoint[];
      routingProfile?: BrouterProfile | undefined;
    }
  | {
      mode: "profile";
      name: string;
      segments: ProfileSegment[];
      startElevationMeters?: number | undefined;
    };

export interface CreatedRoute {
  route: RouteSummary;
  pointCount: number;
  /** The distance between points actually used: the requested step, or a coarser one for a route that would pass 5000 points. */
  stepMeters: number;
  /** Things the agent should know about how the route was built (a grade that was clamped, a coarser step). */
  notes: string[];
}

/** What `ride_routes` says about a ride, once the key is worked out. */
export interface RideRouteKey {
  routeId: string;
  routeName: string;
  /** The route id and the hash of its points as they were when the ride was stored. */
  routeKey: string;
  /** The 16 hex characters two rides on the same route share (spec §11.7). */
  sessionHash: string;
}

export interface RoutesService {
  create(input: CreateRouteInput): Promise<CreatedRoute>;
  list(): Promise<RouteSummary[]>;
  /** `GET /v1/routes`: the list with the time it was made. */
  listResponse(): Promise<RoutesResponse>;
  get(id: string): Promise<Route | null>;
  /** Deletes a route; false when there was none. Rides keep their id and name. */
  delete(id: string): Promise<boolean>;
  /** The ids among these that are not routes (for validating a workout's `route_id`s). */
  missing(ids: readonly string[]): Promise<string[]>;
  /** Whether road routing is available (BROUTER_URL is set). */
  readonly routingOn: boolean;
  /** Records the route a stored ride followed with its key (idempotent); the key, or null when the ride names no route. */
  noteRide(ride: {
    id: string;
    routeId?: string | null;
    routeName?: string | null;
  }): Promise<RideRouteKey | null>;
  /** Records the rides stored with a route that have not been recorded yet; how many. */
  ensureRideRoutes(): Promise<number>;
  /** The route of a stored ride, as recorded. */
  rideRoute(rideId: string): Promise<RideRouteKey | null>;
  readonly store: RouteStore;
}

export interface RoutesOptions {
  db: Db;
  settings: RoutesSettings;
  now: () => Date;
  log: Logger;
  /** For the tests: a routing client instead of the one made from `BROUTER_URL`, and the id generator. */
  brouter?: BrouterClient | null;
  newId?: () => string;
  fetch?: typeof fetch;
}

/** At most this many routes are kept: more is a sign of a runaway agent, and each is up to a few hundred kilobytes. */
export const MAX_ROUTES = 200;
const MAX_NAME = 100;
export const MAX_WAYPOINTS = 25;
const BATCH = 100;

const defaultId = (): string => `route-${randomBytes(5).toString("hex")}`;

export function createRoutes(options: RoutesOptions): RoutesService {
  const { db, settings, now, log } = options;
  const store = createRouteStore(db);
  const newId = options.newId ?? defaultId;
  const stepMeters = settings.ROUTE_STEP_METERS ?? 20;
  const brouter: BrouterClient | null =
    options.brouter !== undefined
      ? options.brouter
      : settings.BROUTER_URL === undefined
        ? null
        : createBrouterClient({
            baseUrl: settings.BROUTER_URL,
            timeoutMs: (settings.BROUTER_TIMEOUT_SECONDS ?? 30) * 1000,
            ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
          });

  async function create(input: CreateRouteInput): Promise<CreatedRoute> {
    const name = input.name.trim();
    if (name === "") throw new RouteError("the route needs a name");
    if (name.length > MAX_NAME) {
      throw new RouteError(`the name is ${name.length} characters; at most ${MAX_NAME}`);
    }
    if ((await store.count()) >= MAX_ROUTES) {
      throw new RouteError(
        `there are already ${MAX_ROUTES} routes: delete some with delete_route first`,
      );
    }

    const notes: string[] = [];
    let built: ReturnType<typeof buildRoute>;
    let source: Route["source"];
    switch (input.mode) {
      case "gpx": {
        const parsed = parseGpx(input.gpx);
        built = buildRoute(trackToRaw(parsed.track), { stepMeters });
        source = "gpx";
        if (parsed.segments > 1) {
          notes.push(
            `the GPX had ${parsed.segments} segments: they were joined in the order of the file, a gap between two of them counts as a straight line`,
          );
        }
        break;
      }
      case "waypoints": {
        if (brouter === null) {
          throw new RouteError(
            "road routing is off: this server has no BROUTER_URL, so a route cannot be made from waypoints. Create the route from a GPX file (mode gpx) or as a synthetic elevation profile (mode profile), or ask the owner to turn routing on (docs/deploy.md, section 20)",
          );
        }
        const count = input.waypoints.length;
        if (count < 2 || count > MAX_WAYPOINTS) {
          throw new RouteError(`give 2 to ${MAX_WAYPOINTS} waypoints (got ${count})`);
        }
        for (const [index, w] of input.waypoints.entries()) {
          if (!(w.lat >= -90 && w.lat <= 90 && w.lon >= -180 && w.lon <= 180)) {
            throw new RouteError(
              `waypoint ${index + 1} is not a place on Earth: lon is -180 to 180, lat -90 to 90 (in that order)`,
            );
          }
        }
        const profile = input.routingProfile ?? "trekking";
        if (!BROUTER_PROFILES.includes(profile)) {
          throw new RouteError(`routingProfile must be one of ${BROUTER_PROFILES.join(", ")}`);
        }
        const answer = await brouter.route(input.waypoints, profile);
        built = buildRoute(trackToRaw(answer.track), { stepMeters });
        source = "waypoints";
        break;
      }
      case "profile": {
        const { raw, clamped } = profileToRaw(input.segments, input.startElevationMeters ?? 0);
        built = buildRoute(raw, { stepMeters, maxMeters: ROUTE_PROFILE_MAX_METERS });
        source = "profile";
        if (clamped > 0) {
          notes.push(
            `${clamped} segment${clamped === 1 ? "" : "s"} had a grade beyond 20% and ${clamped === 1 ? "was" : "were"} clamped to it`,
          );
        }
        break;
      }
    }
    if (built.stepMeters > stepMeters) {
      notes.push(
        `the route is long, so its points are ${built.stepMeters} m apart instead of ${stepMeters} m (at most 5000 points are kept)`,
      );
    }

    const route: Route = {
      id: newId(),
      name,
      source,
      distanceMeters: built.distanceMeters,
      ascentMeters: built.ascentMeters,
      hasCoordinates: built.hasCoordinates,
      createdAt: now().toISOString(),
      points: built.points,
    };
    const valid = validateRoute(route);
    if (!valid.ok) {
      // A bug in the geometry, not a mistake of the caller: it is logged here and answered without detail.
      log.error(
        { errors: valid.errors.slice(0, 3), mode: input.mode },
        "a built route does not validate",
      );
      throw new Error("the built route does not validate");
    }
    await store.insert(route, routePointsHash(route.points));
    log.info({ routeId: route.id, mode: input.mode, points: route.points.length }, "route created");
    const { points, ...summary } = route;
    return { route: summary, pointCount: points.length, stepMeters: built.stepMeters, notes };
  }

  async function keyOf(routeId: string): Promise<{ key: string; name: string } | null> {
    const route = await store.get(routeId);
    return route === null ? null : { key: routeKey(route.id, route.points), name: route.name };
  }

  async function noteRide(ride: {
    id: string;
    routeId?: string | null;
    routeName?: string | null;
  }): Promise<RideRouteKey | null> {
    const routeId = ride.routeId;
    if (routeId === undefined || routeId === null) return null;
    const known = await store.rideRoute(ride.id);
    if (known !== null) return known;
    const found = await keyOf(routeId);
    // A route deleted before its ride was stored has no points to hash: the ride is keyed by the id alone.
    const key = found === null ? `${routeId}:deleted` : found.key;
    const row = {
      rideId: ride.id,
      routeId,
      routeName: found?.name ?? ride.routeName ?? routeId,
      routeKey: key,
      sessionHash: routeSessionHash(key),
    };
    await store.saveRideRoute(row, now());
    return {
      routeId: row.routeId,
      routeName: row.routeName,
      routeKey: row.routeKey,
      sessionHash: row.sessionHash,
    };
  }

  return {
    create,
    list: () => store.list(),
    async listResponse() {
      return { generatedAt: now().toISOString(), routes: await store.list() };
    },
    async get(id) {
      const found = await store.get(id);
      if (found === null) return null;
      const { pointsHash: _hash, ...route } = found;
      return route;
    },
    delete: (id) => store.delete(id),
    async missing(ids) {
      const unique = [...new Set(ids)];
      const present = await store.existing(unique);
      return unique.filter((id) => !present.has(id));
    },
    routingOn: brouter !== null,
    noteRide,
    async ensureRideRoutes() {
      let done = 0;
      for (;;) {
        const pending = await store.ridesWithoutRouteRow(BATCH);
        if (pending.length === 0) return done;
        for (const ride of pending) {
          await noteRide({ id: ride.rideId, routeId: ride.routeId, routeName: ride.routeName });
          done++;
        }
      }
    },
    async rideRoute(rideId) {
      const row = await store.rideRoute(rideId);
      return row === null
        ? null
        : {
            routeId: row.routeId,
            routeName: row.routeName,
            routeKey: row.routeKey,
            sessionHash: row.sessionHash,
          };
    },
    store,
  };
}
