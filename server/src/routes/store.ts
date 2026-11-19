import type { Route, RoutePoint, RouteSource, RouteSummary } from "@overdrive/contracts";
import type { Db } from "../db.ts";

/**
 * What the routes keep in PostgreSQL (migration 0011): the `routes` table (a route, its points as compact JSON) and `ride_routes` (the
 * route a stored ride followed, with the route key worked out when the ride was stored). A route is only created and deleted.
 */

/** A point as stored: `[d, e]` or `[d, e, lat, lon]`. */
type StoredPoint = [number, number] | [number, number, number, number];

interface RouteRow {
  id: string;
  name: string;
  source: RouteSource;
  distance_m: number;
  ascent_m: number;
  has_coordinates: boolean;
  created_at: Date | string;
}

interface RouteRowWithPoints extends RouteRow {
  points: StoredPoint[];
  points_hash: string;
}

const SUMMARY_COLUMNS = "id, name, source, distance_m, ascent_m, has_coordinates, created_at";

function toSummary(row: RouteRow): RouteSummary {
  return {
    id: row.id,
    name: row.name,
    source: row.source,
    distanceMeters: row.distance_m,
    ascentMeters: row.ascent_m,
    hasCoordinates: row.has_coordinates,
    createdAt: new Date(row.created_at).toISOString(),
  };
}

export function toStoredPoints(points: readonly RoutePoint[]): StoredPoint[] {
  return points.map((p) =>
    p.lat !== undefined && p.lon !== undefined ? [p.d, p.e, p.lat, p.lon] : [p.d, p.e],
  );
}

export function fromStoredPoints(points: readonly StoredPoint[]): RoutePoint[] {
  return points.map((p) =>
    p.length === 4 ? { d: p[0], e: p[1], lat: p[2], lon: p[3] } : { d: p[0], e: p[1] },
  );
}

/** A ride's route as kept when the ride was stored. */
export interface RideRouteRow {
  rideId: string;
  routeId: string;
  routeName: string;
  routeKey: string;
  sessionHash: string;
}

export interface RouteStore {
  insert(route: Route, pointsHash: string): Promise<void>;
  /** Newest first, then by id. */
  list(): Promise<RouteSummary[]>;
  count(): Promise<number>;
  get(id: string): Promise<(Route & { pointsHash: string }) | null>;
  /** Which of these ids exist. */
  existing(ids: readonly string[]): Promise<Set<string>>;
  /** Deletes the route; true if it was there. A ride that followed it keeps its row in `ride_routes`. */
  delete(id: string): Promise<boolean>;
  saveRideRoute(row: RideRouteRow, at: Date): Promise<void>;
  rideRoute(rideId: string): Promise<RideRouteRow | null>;
  /** Stored rides whose record names a route and that have no `ride_routes` row yet, oldest first. */
  ridesWithoutRouteRow(
    limit: number,
  ): Promise<{ rideId: string; routeId: string; routeName: string | null }[]>;
}

export function createRouteStore(db: Db): RouteStore {
  return {
    async insert(route, pointsHash) {
      await db.query(
        `INSERT INTO routes (id, name, source, distance_m, ascent_m, has_coordinates, point_count, points, points_hash, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9, $10::timestamptz)`,
        [
          route.id,
          route.name,
          route.source,
          route.distanceMeters,
          route.ascentMeters,
          route.hasCoordinates,
          route.points.length,
          JSON.stringify(toStoredPoints(route.points)),
          pointsHash,
          route.createdAt,
        ],
      );
    },

    async list() {
      const result = await db.query<RouteRow>(
        `SELECT ${SUMMARY_COLUMNS} FROM routes ORDER BY created_at DESC, id`,
      );
      return result.rows.map(toSummary);
    },

    async count() {
      const result = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM routes");
      return Number(result.rows[0]?.n ?? 0);
    },

    async get(id) {
      const result = await db.query<RouteRowWithPoints>(
        `SELECT ${SUMMARY_COLUMNS}, points, points_hash FROM routes WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      if (row === undefined) return null;
      return {
        ...toSummary(row),
        points: fromStoredPoints(row.points),
        pointsHash: row.points_hash,
      };
    },

    async existing(ids) {
      if (ids.length === 0) return new Set();
      const result = await db.query<{ id: string }>(
        "SELECT id FROM routes WHERE id = ANY($1::text[])",
        [[...ids]],
      );
      return new Set(result.rows.map((row) => row.id));
    },

    async delete(id) {
      const result = await db.query("DELETE FROM routes WHERE id = $1 RETURNING id", [id]);
      return result.rows.length > 0;
    },

    async saveRideRoute(row, at) {
      await db.query(
        `INSERT INTO ride_routes (ride_id, route_id, route_name, route_key, session_hash, created_at)
         VALUES ($1, $2, $3, $4, $5, $6::timestamptz) ON CONFLICT (ride_id) DO NOTHING`,
        [row.rideId, row.routeId, row.routeName, row.routeKey, row.sessionHash, at.toISOString()],
      );
    },

    async rideRoute(rideId) {
      const result = await db.query<{
        ride_id: string;
        route_id: string;
        route_name: string;
        route_key: string;
        session_hash: string;
      }>(
        "SELECT ride_id, route_id, route_name, route_key, session_hash FROM ride_routes WHERE ride_id = $1",
        [rideId],
      );
      const row = result.rows[0];
      return row === undefined
        ? null
        : {
            rideId: row.ride_id,
            routeId: row.route_id,
            routeName: row.route_name,
            routeKey: row.route_key,
            sessionHash: row.session_hash,
          };
    },

    async ridesWithoutRouteRow(limit) {
      const result = await db.query<{ id: string; route_id: string; route_name: string | null }>(
        `SELECT r.id, r.record->'ride'->>'routeId' AS route_id, r.record->'ride'->>'routeName' AS route_name
           FROM rides r LEFT JOIN ride_routes rr ON rr.ride_id = r.id
          WHERE r.record->'ride'->>'routeId' IS NOT NULL AND rr.ride_id IS NULL
          ORDER BY r.start_epoch_millis, r.id LIMIT $1`,
        [limit],
      );
      return result.rows.map((row) => ({
        rideId: row.id,
        routeId: row.route_id,
        routeName: row.route_name,
      }));
    },
  };
}
