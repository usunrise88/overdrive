import {
  ROUTE_MAX_POINTS,
  ROUTE_MIN_DISTANCE_METERS,
  ROUTE_PROFILE_MAX_GRADE_PERCENT,
  ROUTE_PROFILE_MAX_METERS,
  ROUTE_SMOOTHING_METERS,
  type RoutePoint,
  routeAscentMeters,
} from "@overdrive/contracts";

/**
 * The geometry of a route (spec §11.4, ADR 0036), pure: raw points (a GPX track, a routing engine's line, a synthetic profile) are
 * resampled to one point every `stepMeters` along the route, their elevation lightly smoothed, and the distance and the ascent
 * worked out by code from the result. The tablet computes the grade from the points; nothing here makes one.
 */

/** A mistake in the input that the caller can understand and fix; the message is plain words. */
export class RouteError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RouteError";
  }
}

/** The mean radius of the Earth in metres (IUGG): distances along a route are great-circle distances on this sphere. */
const EARTH_RADIUS_METERS = 6_371_008.8;

/** The longest route taken from a GPX file or the routing engine, in metres. */
export const ROUTE_MAX_METERS = 500_000;
/** Elevations outside this range are not metres above sea level on Earth. */
const MIN_ELEVATION = -500;
const MAX_ELEVATION = 9000;
/** Consecutive raw points closer than this are one point (a GPS that stood still). */
const SAME_PLACE_METERS = 0.01;

const toRadians = (degrees: number): number => (degrees * Math.PI) / 180;

/** The great-circle distance in metres between two points given in degrees. */
export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** A raw point on its way in: `d` is the distance along the route (metres, not decreasing), `e` the elevation. */
export interface RawPoint {
  d: number;
  e: number;
  lat?: number;
  lon?: number;
}

/** A track point as a GPX file or the routing engine gives it. */
export interface TrackPoint {
  lat: number;
  lon: number;
  ele: number;
}

/** Raw points with distance, from a track: the distance is the sum of the great-circle distances between neighbours. */
export function trackToRaw(track: readonly TrackPoint[]): RawPoint[] {
  const raw: RawPoint[] = [];
  let distance = 0;
  let previous: TrackPoint | null = null;
  for (const point of track) {
    if (previous !== null) {
      const step = haversineMeters(previous.lat, previous.lon, point.lat, point.lon);
      if (step < SAME_PLACE_METERS) continue;
      distance += step;
    }
    raw.push({ d: distance, e: point.ele, lat: point.lat, lon: point.lon });
    previous = point;
  }
  return raw;
}

/** A segment of a synthetic profile: `lengthMeters` along the route at `gradePercent` (rise over run). */
export interface ProfileSegment {
  lengthMeters: number;
  gradePercent: number;
}

/**
 * Raw points of a synthetic profile: the elevation starts at `startElevation` and rises by `length × grade / 100` over each segment.
 * A grade beyond plus or minus 20% is clamped to it (the trainer's simulation cannot follow more, and a model's typing error should not
 * make a wall); `clamped` says how many segments were.
 */
export function profileToRaw(
  segments: readonly ProfileSegment[],
  startElevation: number,
): { raw: RawPoint[]; clamped: number } {
  if (segments.length === 0) throw new RouteError("the profile has no segments");
  let total = 0;
  let clamped = 0;
  const raw: RawPoint[] = [{ d: 0, e: startElevation }];
  let elevation = startElevation;
  for (const [index, segment] of segments.entries()) {
    if (!Number.isFinite(segment.lengthMeters) || segment.lengthMeters <= 0) {
      throw new RouteError(`segment ${index + 1} has no length: lengthMeters must be above 0`);
    }
    if (!Number.isFinite(segment.gradePercent)) {
      throw new RouteError(`segment ${index + 1} has no grade: gradePercent must be a number`);
    }
    const grade = Math.max(
      -ROUTE_PROFILE_MAX_GRADE_PERCENT,
      Math.min(ROUTE_PROFILE_MAX_GRADE_PERCENT, segment.gradePercent),
    );
    if (grade !== segment.gradePercent) clamped++;
    total += segment.lengthMeters;
    if (total > ROUTE_PROFILE_MAX_METERS) {
      throw new RouteError(
        `the profile is longer than ${ROUTE_PROFILE_MAX_METERS / 1000} km: shorten it (repeat the ride instead of making one route of it)`,
      );
    }
    elevation += (segment.lengthMeters * grade) / 100;
    raw.push({ d: total, e: elevation });
  }
  return { raw, clamped };
}

export interface BuiltRoute {
  points: RoutePoint[];
  distanceMeters: number;
  ascentMeters: number;
  hasCoordinates: boolean;
  /** The step actually used, in metres: the requested one, or a coarser one for a route that would pass the point limit. */
  stepMeters: number;
}

const round1 = (value: number): number => Math.round(value * 10) / 10;
const round6 = (value: number): number => Math.round(value * 1e6) / 1e6;

/** The number of points in the moving average: about {@link ROUTE_SMOOTHING_METERS} metres, odd, at least 3. */
export function smoothingWindow(stepMeters: number): number {
  const points = Math.max(3, Math.round(ROUTE_SMOOTHING_METERS / stepMeters));
  return points % 2 === 0 ? points + 1 : points;
}

/**
 * The moving average of the elevations over `window` points (odd), centred. The window shrinks symmetrically towards the ends so that
 * the first and the last elevation are kept as they are.
 */
export function smoothElevations(elevations: readonly number[], window: number): number[] {
  const half = (window - 1) / 2;
  const n = elevations.length;
  return elevations.map((_value, i) => {
    const reach = Math.min(half, i, n - 1 - i);
    let sum = 0;
    for (let j = i - reach; j <= i + reach; j++) sum += elevations[j] as number;
    return sum / (2 * reach + 1);
  });
}

/**
 * Resamples raw points to one every `stepMeters` (coarser when the route would need more than {@link ROUTE_MAX_POINTS}: the smallest
 * whole-metre step that fits), interpolating linearly along the route, smooths the elevation, rounds (distance and elevation to a
 * tenth of a metre, coordinates to a millionth of a degree) and works out the distance and the ascent from the points that result.
 * Coordinates must be on every raw point or on none.
 */
export function buildRoute(
  raw: readonly RawPoint[],
  options: { stepMeters: number; maxMeters?: number },
): BuiltRoute {
  const maxMeters = options.maxMeters ?? ROUTE_MAX_METERS;
  const first = raw[0];
  const last = raw[raw.length - 1];
  if (first === undefined || last === undefined || raw.length < 2) {
    throw new RouteError("the route needs at least two points");
  }
  const withCoordinates = raw.filter((p) => p.lat !== undefined && p.lon !== undefined).length;
  if (withCoordinates !== 0 && withCoordinates !== raw.length) {
    throw new RouteError("some points have coordinates and some do not");
  }
  const hasCoordinates = withCoordinates === raw.length;
  for (const point of raw) {
    if (point.e < MIN_ELEVATION || point.e > MAX_ELEVATION) {
      throw new RouteError(
        `an elevation of ${Math.round(point.e)} m at ${(point.d / 1000).toFixed(1)} km is not plausible: elevations must be metres above sea level, between ${MIN_ELEVATION} and ${MAX_ELEVATION}`,
      );
    }
  }
  const total = last.d - first.d;
  if (total < ROUTE_MIN_DISTANCE_METERS) {
    throw new RouteError(
      `the route is ${Math.round(total)} m long; it must be at least ${ROUTE_MIN_DISTANCE_METERS} m`,
    );
  }
  if (total > maxMeters) {
    throw new RouteError(
      `the route is ${(total / 1000).toFixed(0)} km long; at most ${maxMeters / 1000} km are kept`,
    );
  }

  const step = Math.max(options.stepMeters, Math.ceil(total / (ROUTE_MAX_POINTS - 2)));
  const distances: number[] = [];
  const whole = Math.floor(total / step);
  for (let i = 0; i <= whole; i++) distances.push(i * step);
  const remainder = total - whole * step;
  // A last gap under a metre is folded into the point before it, so that rounding can never make two points equal.
  if (remainder >= 1) distances.push(total);
  else distances[distances.length - 1] = total;

  const base = first.d;
  const interpolated: { d: number; e: number; lat?: number; lon?: number }[] = [];
  let segment = 0;
  for (const d of distances) {
    const at = base + d;
    while (segment < raw.length - 2 && (raw[segment + 1] as RawPoint).d < at) segment++;
    const from = raw[segment] as RawPoint;
    const to = raw[segment + 1] as RawPoint;
    const span = to.d - from.d;
    const t = span <= 0 ? 0 : Math.min(1, Math.max(0, (at - from.d) / span));
    const point: { d: number; e: number; lat?: number; lon?: number } = {
      d,
      e: from.e + (to.e - from.e) * t,
    };
    if (hasCoordinates) {
      point.lat = (from.lat as number) + ((to.lat as number) - (from.lat as number)) * t;
      point.lon = (from.lon as number) + ((to.lon as number) - (from.lon as number)) * t;
    }
    interpolated.push(point);
  }

  const smoothed = smoothElevations(
    interpolated.map((p) => p.e),
    smoothingWindow(step),
  );
  const points: RoutePoint[] = interpolated.map((p, i) => {
    const point: RoutePoint = { d: round1(p.d), e: round1(smoothed[i] as number) };
    if (p.lat !== undefined && p.lon !== undefined) {
      point.lat = round6(p.lat);
      point.lon = round6(p.lon);
    }
    return point;
  });
  const lastPoint = points[points.length - 1] as RoutePoint;
  return {
    points,
    distanceMeters: Math.round(lastPoint.d),
    ascentMeters: Math.round(routeAscentMeters(points)),
    hasCoordinates,
    stepMeters: step,
  };
}
