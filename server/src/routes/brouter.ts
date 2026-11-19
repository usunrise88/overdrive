import type { TrackPoint } from "./geometry.ts";

/**
 * The client of the BRouter routing server (spec §11.4, ADR 0036, docs/deploy.md section 20): waypoints in, a road route with
 * elevation out. BRouter answers a GET `/brouter?lonlats=lon,lat|lon,lat&profile=<name>&format=geojson` with a GeoJSON feature whose
 * geometry is a `LineString` of `[lon, lat, ele]` and whose numeric properties (`track-length`, `filtered ascend`, `total-time`) are
 * **strings**. A failure is plain text or an empty body, so the body of a non-2xx answer is never parsed as JSON:
 *
 * - `400` with `datafile E5_N45.rd5 not found`: the waypoint is outside the map tiles the server holds (said as such);
 * - `500` with an empty body: BRouter could not use the request (an unknown profile, a point it cannot parse);
 * - another `400`: BRouter's own words about why it found no route (the point is not near a road, an island, …);
 * - anything else non-2xx, a network error or a timeout: the routing service is unavailable.
 *
 * Written from the findings of a probe against BRouter 1.7.10 (`docs/deploy.md` section 20); the tests run it against a fake server.
 */

/** The profiles the service offers: the road, the faster road bike and the gravel profile of BRouter's standard set. */
export const BROUTER_PROFILES = ["trekking", "fastbike", "gravel"] as const;
export type BrouterProfile = (typeof BROUTER_PROFILES)[number];

export type BrouterErrorKind =
  /** BRouter is not configured (`BROUTER_URL` is empty). */
  | "off"
  /** The waypoints are outside the map tiles the routing server holds. */
  | "no-map-data"
  /** BRouter refused the request or found no route between the points: the caller can change the waypoints or the profile. */
  | "bad-request"
  /** The routing service did not answer, timed out or answered something unusable. */
  | "unavailable";

/** A failure with a plain-words message, safe to hand to the agent. */
export class BrouterError extends Error {
  readonly kind: BrouterErrorKind;
  readonly status: number | null;

  constructor(kind: BrouterErrorKind, message: string, status: number | null = null) {
    super(message);
    this.name = "BrouterError";
    this.kind = kind;
    this.status = status;
  }
}

export interface Waypoint {
  lon: number;
  lat: number;
}

export interface BrouterRoute {
  /** The line BRouter found, with elevation. */
  track: TrackPoint[];
  /** BRouter's own `track-length` in metres, when it said one (the service computes its own from the points). */
  engineLengthMeters: number | null;
}

export interface BrouterClient {
  route(waypoints: readonly Waypoint[], profile: BrouterProfile): Promise<BrouterRoute>;
}

export interface BrouterClientOptions {
  /** `http://brouter:17777`, no trailing slash. */
  baseUrl: string;
  /** How long a request may take (default 30 s). */
  timeoutMs?: number;
  fetch?: typeof fetch;
}

const DEFAULT_TIMEOUT_MS = 30_000;
/** A routing answer larger than this is not read: 500 km of road is a few megabytes. */
const MAX_ANSWER_CHARS = 64 * 1024 * 1024;
const MAX_DETAIL = 120;

const oneLine = (text: string): string => text.replace(/\s+/g, " ").trim().slice(0, MAX_DETAIL);

function describeFailure(cause: unknown): string {
  if (cause instanceof Error) {
    if (cause.name === "TimeoutError" || cause.name === "AbortError") return "timed out";
    const code = (cause.cause as { code?: unknown } | undefined)?.code;
    return typeof code === "string" ? code : "the connection failed";
  }
  return "the connection failed";
}

/** The tile BRouter says it is missing (`E5_N45.rd5`), or null. */
function missingTile(body: string): string | null {
  const match = /datafile\s+(\S+\.rd5)\s+not found/i.exec(body);
  return match === null ? null : (match[1] as string);
}

function numberOfProperty(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && /^\d+(\.\d+)?$/.test(value)) return Number(value);
  return null;
}

/** The track of BRouter's GeoJSON answer, or null when it is not the shape described above. */
function readAnswer(text: string): BrouterRoute | null {
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return null;
  }
  const features = (body as { features?: unknown } | null)?.features;
  if (!Array.isArray(features)) return null;
  const feature = features[0] as
    | { geometry?: { type?: unknown; coordinates?: unknown }; properties?: Record<string, unknown> }
    | undefined;
  const coordinates = feature?.geometry?.coordinates;
  if (feature?.geometry?.type !== "LineString" || !Array.isArray(coordinates)) return null;
  const track: TrackPoint[] = [];
  for (const coordinate of coordinates) {
    if (!Array.isArray(coordinate)) return null;
    const [lon, lat, ele] = coordinate as unknown[];
    if (
      typeof lon !== "number" ||
      typeof lat !== "number" ||
      typeof ele !== "number" ||
      !Number.isFinite(lon + lat + ele)
    ) {
      return null;
    }
    track.push({ lon, lat, ele });
  }
  if (track.length < 2) return null;
  return { track, engineLengthMeters: numberOfProperty(feature.properties?.["track-length"]) };
}

export function createBrouterClient(options: BrouterClientOptions): BrouterClient {
  const baseUrl = options.baseUrl.replace(/\/+$/, "");
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return {
    async route(waypoints, profile) {
      const query = new URLSearchParams({
        lonlats: waypoints.map((w) => `${w.lon.toFixed(6)},${w.lat.toFixed(6)}`).join("|"),
        profile,
        format: "geojson",
      });
      let response: Response;
      try {
        response = await doFetch(`${baseUrl}/brouter?${query.toString()}`, {
          method: "GET",
          headers: { accept: "application/json" },
          redirect: "error",
          signal: AbortSignal.timeout(timeoutMs),
        });
      } catch (cause) {
        throw new BrouterError(
          "unavailable",
          `the routing service is unavailable (it did not answer: ${describeFailure(cause)}); try again later or create the route from a GPX file or a profile`,
        );
      }
      const text = await response.text().catch(() => "");
      const status = response.status;

      if (status >= 200 && status < 300) {
        if (text.length > MAX_ANSWER_CHARS) {
          throw new BrouterError(
            "unavailable",
            "the routing service answered with a route far too large to use; use fewer or closer waypoints",
            status,
          );
        }
        const route = readAnswer(text);
        if (route === null) {
          throw new BrouterError(
            "unavailable",
            "the routing service is unavailable (it answered something that is not a route)",
            status,
          );
        }
        return route;
      }

      const tile = status === 400 ? missingTile(text) : null;
      if (tile !== null) {
        throw new BrouterError(
          "no-map-data",
          `there is no map data for this area: the routing server does not have the tile ${tile}. Use waypoints inside the downloaded area, or ask the owner to download the tile (docs/deploy.md, section 20); a GPX file or a profile needs no map data`,
          status,
        );
      }
      if (status === 500 && text.trim() === "") {
        throw new BrouterError(
          "bad-request",
          "the routing service could not use the request (it answered 500 with no message: check the waypoints and that the profile is trekking, fastbike or gravel)",
          status,
        );
      }
      if (status === 400 && text.trim() !== "") {
        throw new BrouterError(
          "bad-request",
          `the routing service could not route these waypoints (it said: ${oneLine(text)}); move them onto roads or closer together`,
          status,
        );
      }
      throw new BrouterError(
        "unavailable",
        `the routing service is unavailable (it answered ${status})`,
        status,
      );
    },
  };
}
