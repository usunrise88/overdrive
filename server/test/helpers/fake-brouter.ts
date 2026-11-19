import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

/**
 * A fake BRouter for the tests: a Node HTTP server on a free local port that answers `GET /brouter?lonlats=…&profile=…&format=geojson`
 * the way the real BRouter 1.7.10 did in the probe of ADR 0036 (docs/deploy.md section 20): a GeoJSON feature collection whose first
 * feature is a `LineString` of `[lon, lat, ele]`, numeric properties as strings, and failures as plain text or an empty body:
 *
 * - a waypoint in a tile the server does not hold: `400` `datafile E5_N45.rd5 not found`;
 * - an unknown profile: `500` with an empty body;
 * - a missing `lonlats`: `404` with an empty body.
 *
 * The route it draws is invented: a straight line between the waypoints with a point about every 11 m and a smooth made-up elevation.
 */

export interface FakeBrouterRequest {
  path: string;
  /** The query as the client sent it, decoded. */
  query: Record<string, string>;
  /** The raw query string, to see how the pipe was encoded. */
  rawQuery: string;
}

export interface FakeBrouter {
  /** `http://127.0.0.1:port` */
  url: string;
  requests: FakeBrouterRequest[];
  /** The 5-degree tiles it holds, `E5_N45`. Default: only that one, so lon 5 to 10 and lat 45 to 50. */
  tiles: Set<string>;
  /** The next request is answered with this status and body. */
  failNext(status: number, body?: string): void;
  /** Every request is accepted and never answered until reset. */
  setHanging(hanging: boolean): void;
  /** The next 200 answer is not a route. */
  garbageNext(): void;
  /** The profiles it knows. */
  profiles: Set<string>;
  reset(): void;
  close(): Promise<void>;
}

const tileOf = (lon: number, lat: number): string =>
  `${lon < 0 ? "W" : "E"}${Math.abs(Math.floor(lon / 5) * 5)}_${lat < 0 ? "S" : "N"}${Math.abs(Math.floor(lat / 5) * 5)}`;

export async function startFakeBrouter(): Promise<FakeBrouter> {
  let queued: { status: number; body: string } | null = null;
  let hanging = false;
  let garbage = false;
  const sockets = new Set<import("node:net").Socket>();

  const state: FakeBrouter = {
    url: "",
    requests: [],
    tiles: new Set(["E5_N45"]),
    profiles: new Set(["trekking", "fastbike", "gravel"]),
    failNext(status, body = "") {
      queued = { status, body };
    },
    setHanging(value) {
      hanging = value;
    },
    garbageNext() {
      garbage = true;
    },
    reset() {
      state.requests.length = 0;
      state.tiles = new Set(["E5_N45"]);
      state.profiles = new Set(["trekking", "fastbike", "gravel"]);
      queued = null;
      hanging = false;
      garbage = false;
    },
    close: () =>
      new Promise((resolve) => {
        for (const socket of sockets) socket.destroy();
        server.close(() => resolve());
      }),
  };

  const text = (response: ServerResponse, status: number, body: string): void => {
    response.writeHead(status, { "content-type": "text/plain" });
    response.end(body);
  };

  const server: Server = createServer((request: IncomingMessage, response: ServerResponse) => {
    const url = new URL(request.url ?? "/", "http://fake");
    state.requests.push({
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      rawQuery: url.search,
    });
    if (hanging) return;
    if (queued !== null) {
      const { status, body } = queued;
      queued = null;
      return text(response, status, body);
    }
    if (url.pathname !== "/brouter") return text(response, 404, "");
    const lonlats = url.searchParams.get("lonlats");
    if (lonlats === null || lonlats === "") return text(response, 404, "");
    const waypoints = lonlats.split("|").map((pair) => pair.split(",").map(Number));
    if (waypoints.some((w) => w.length !== 2 || w.some((n) => !Number.isFinite(n)))) {
      return text(response, 500, "");
    }
    if (!state.profiles.has(url.searchParams.get("profile") ?? "")) return text(response, 500, "");
    for (const [lon, lat] of waypoints as [number, number][]) {
      const tile = tileOf(lon, lat);
      if (!state.tiles.has(tile)) return text(response, 400, `datafile ${tile}.rd5 not found`);
    }
    if (garbage) {
      garbage = false;
      return text(response, 200, "this is not a route");
    }

    // A straight line between the waypoints, a point every 0.0001 degrees of the longer axis (about 11 m), with a made-up elevation.
    const coordinates: [number, number, number][] = [];
    let length = 0;
    let ascend = 0;
    for (let leg = 0; leg + 1 < waypoints.length; leg++) {
      const [lon1, lat1] = waypoints[leg] as [number, number];
      const [lon2, lat2] = waypoints[leg + 1] as [number, number];
      const steps = Math.max(
        2,
        Math.ceil(Math.max(Math.abs(lon2 - lon1), Math.abs(lat2 - lat1)) / 0.0001),
      );
      for (let i = leg === 0 ? 0 : 1; i <= steps; i++) {
        const t = i / steps;
        const lon = lon1 + (lon2 - lon1) * t;
        const lat = lat1 + (lat2 - lat1) * t;
        const ele = Math.round((300 + 40 * Math.sin(coordinates.length / 25)) * 4) / 4;
        const last = coordinates[coordinates.length - 1];
        if (last !== undefined) {
          length += Math.hypot((lon - last[0]) * 78_000, (lat - last[1]) * 111_000);
          if (ele > last[2]) ascend += ele - last[2];
        }
        coordinates.push([Number(lon.toFixed(6)), Number(lat.toFixed(6)), ele]);
      }
    }
    const body = {
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          properties: {
            creator: "BRouter-1.7.10",
            name: `brouter_${url.searchParams.get("profile")}_0`,
            "track-length": String(Math.round(length)),
            "filtered ascend": String(Math.round(ascend)),
            "plain-ascend": String(Math.round(ascend)),
            "total-time": String(Math.round(length / 5)),
            messages: [["Longitude", "Latitude", "Elevation", "Distance"]],
          },
          geometry: { type: "LineString", coordinates },
        },
      ],
    };
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify(body, null, 2));
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  state.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return state;
}
