import { RouteError, type TrackPoint } from "./geometry.ts";

/**
 * A small, tolerant GPX reader of our own (ADR 0036): it needs only the points of a track (`trkpt`, or `rtept` when the file has no
 * track) with their `lat` and `lon` attributes and their `<ele>`, so there is no XML library to depend on. It is a scanner, not a
 * parser of XML: it never expands an entity, never fetches anything and refuses a DOCTYPE outright, so a hostile file can cost
 * time in proportion to its size (capped) and nothing else.
 *
 * Tolerated: any namespace prefix (`<gpx:trkpt>`), several tracks and segments (their points are joined in the order of the file),
 * comments, processing instructions, CDATA, other extensions inside a point, waypoints (ignored), attributes in either quote.
 * Refused, with a plain message: a point without `<ele>` (the server does not guess an elevation), a coordinate or elevation that is
 * not a number or out of range, a DOCTYPE, a file that is not GPX, a file over {@link GPX_MAX_BYTES} or with too many points.
 */

/** The largest GPX text accepted, in bytes (UTF-8). */
export const GPX_MAX_BYTES = 2 * 1024 * 1024;
/** More points than this cannot be in a GPX of {@link GPX_MAX_BYTES}; the cap keeps memory bounded whatever the bytes. */
const MAX_POINTS = 200_000;
/** A tag longer than this is not a GPX tag. */
const MAX_TAG_CHARS = 20_000;

export interface ParsedGpx {
  track: TrackPoint[];
  /** Which element the points were: a recorded track or a planned route. */
  from: "track" | "route";
  /** How many `trkseg` (or `rte`) the points came from. */
  segments: number;
}

interface Pending {
  lat: number | null;
  lon: number | null;
  ele: number | null;
  /** The point's own tag was malformed (a coordinate that is not a number). */
  problem: string | null;
}

const NUMBER = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const ATTRIBUTE = /([A-Za-z_][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;

/** The index of the `>` that ends the tag starting at `from` (a `<`), skipping any inside quoted attribute values; -1 if none. */
function tagEnd(text: string, from: number): number {
  let quote: string | null = null;
  const limit = Math.min(text.length, from + MAX_TAG_CHARS);
  for (let i = from + 1; i < limit; i++) {
    const ch = text[i];
    if (quote !== null) {
      if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === ">") {
      return i;
    }
  }
  return -1;
}

function numberOf(text: string): number | null {
  const trimmed = text.trim();
  if (!NUMBER.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** The local name of a tag (`gpx:trkpt` is `trkpt`), lower-case, or null for a malformed one. */
function localName(tag: string): { name: string; closing: boolean; selfClosing: boolean } | null {
  const closing = tag.startsWith("/");
  const body = closing ? tag.slice(1) : tag;
  const match = /^\s*([^\s/>]+)/.exec(body);
  if (match === null) return null;
  const full = match[1] as string;
  const name = full.slice(full.lastIndexOf(":") + 1).toLowerCase();
  return { name, closing, selfClosing: !closing && /\/\s*$/.test(tag) };
}

function attributes(tag: string): Map<string, string> {
  const found = new Map<string, string>();
  ATTRIBUTE.lastIndex = 0;
  for (let match = ATTRIBUTE.exec(tag); match !== null; match = ATTRIBUTE.exec(tag)) {
    const key = (match[1] as string).slice((match[1] as string).lastIndexOf(":") + 1).toLowerCase();
    found.set(key, (match[2] ?? match[3]) as string);
  }
  return found;
}

function startPoint(tag: string): Pending {
  const attrs = attributes(tag);
  const lat = attrs.has("lat") ? numberOf(attrs.get("lat") as string) : null;
  const lon = attrs.has("lon") ? numberOf(attrs.get("lon") as string) : null;
  let problem: string | null = null;
  if (lat === null || lon === null) problem = "has no usable lat and lon";
  else if (lat < -90 || lat > 90) problem = "has a latitude outside -90 to 90";
  else if (lon < -180 || lon > 180) problem = "has a longitude outside -180 to 180";
  return { lat, lon, ele: null, problem };
}

/** Reads the GPX text; throws {@link RouteError} with a plain message for anything it cannot use. */
export function parseGpx(input: string): ParsedGpx {
  if (Buffer.byteLength(input, "utf8") > GPX_MAX_BYTES) {
    throw new RouteError(
      `the GPX is larger than ${GPX_MAX_BYTES / (1024 * 1024)} MB: simplify the track or cut it into routes`,
    );
  }
  const text = input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;

  const track: Pending[] = [];
  const planned: Pending[] = [];
  const segmentStarts = { track: 0, route: 0 };
  let sawGpx = false;
  let current: { point: Pending; into: Pending[] } | null = null;

  let at = 0;
  for (;;) {
    const lt = text.indexOf("<", at);
    if (lt < 0) break;
    if (text.startsWith("<!--", lt)) {
      const end = text.indexOf("-->", lt + 4);
      if (end < 0) throw new RouteError("the GPX has a comment that is never closed");
      at = end + 3;
      continue;
    }
    if (text.startsWith("<![CDATA[", lt)) {
      const end = text.indexOf("]]>", lt + 9);
      if (end < 0) throw new RouteError("the GPX has a CDATA section that is never closed");
      at = end + 3;
      continue;
    }
    if (text.startsWith("<?", lt)) {
      const end = text.indexOf("?>", lt + 2);
      if (end < 0)
        throw new RouteError("the GPX has a processing instruction that is never closed");
      at = end + 2;
      continue;
    }
    if (text.startsWith("<!", lt)) {
      throw new RouteError(
        "the GPX has a DOCTYPE or entity declaration, which the server does not read; export it again as plain GPX",
      );
    }
    const gt = tagEnd(text, lt);
    if (gt < 0)
      throw new RouteError("the GPX has a tag that is never closed: it is cut off or not GPX");
    const tag = text.slice(lt + 1, gt);
    at = gt + 1;
    const parsed = localName(tag);
    if (parsed === null) throw new RouteError("the GPX has an empty tag: it is not GPX");
    const { name, closing, selfClosing } = parsed;

    if (!closing && name === "gpx") sawGpx = true;
    if (!closing && name === "trkseg") segmentStarts.track++;
    if (!closing && name === "rte") segmentStarts.route++;

    if (name === "trkpt" || name === "rtept") {
      const into = name === "trkpt" ? track : planned;
      if (closing) {
        if (current !== null) into.push(current.point);
        current = null;
      } else {
        if (track.length + planned.length >= MAX_POINTS) {
          throw new RouteError(`the GPX has more than ${MAX_POINTS} points: simplify the track`);
        }
        // A point whose end tag is missing ends where the next one starts.
        if (current !== null) current.into.push(current.point);
        const point = startPoint(tag);
        if (selfClosing) {
          into.push(point);
          current = null;
        } else {
          current = { point, into };
        }
      }
      continue;
    }
    if (
      name === "ele" &&
      !closing &&
      !selfClosing &&
      current !== null &&
      current.point.ele === null
    ) {
      const next = text.indexOf("<", at);
      const content = text.slice(at, next < 0 ? text.length : next);
      current.point.ele = numberOf(content);
      if (current.point.ele === null && current.point.problem === null) {
        current.point.problem = "has an <ele> that is not a number";
      }
    }
  }

  if (!sawGpx)
    throw new RouteError("this does not look like a GPX file: there is no <gpx> element");
  const useTrack = track.length > 0;
  const points = useTrack ? track : planned;
  if (points.length === 0) {
    throw new RouteError(
      "the GPX has no track or route points (only waypoints?): the route needs <trk><trkseg><trkpt> points",
    );
  }
  if (points.length < 2)
    throw new RouteError("the GPX has a single point: a route needs at least two");

  const broken = points.findIndex((p) => p.problem !== null);
  if (broken >= 0) {
    throw new RouteError(`point ${broken + 1} of the GPX ${(points[broken] as Pending).problem}`);
  }
  const missing = points.flatMap((p, i) => (p.ele === null ? [i] : []));
  if (missing.length > 0) {
    throw new RouteError(
      `${missing.length} of ${points.length} points of the GPX have no <ele> elevation (the first is point ${(missing[0] as number) + 1}). The server does not guess an elevation: export the GPX with elevation, or create the route from waypoints (mode waypoints), which takes elevation from the map data`,
    );
  }
  return {
    track: points.map((p) => ({
      lat: p.lat as number,
      lon: p.lon as number,
      ele: p.ele as number,
    })),
    from: useTrack ? "track" : "route",
    segments: Math.max(1, useTrack ? segmentStarts.track : segmentStarts.route),
  };
}
