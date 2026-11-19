import {
  ROUTE_MAX_POINTS,
  type RoutePoint,
  routeAscentMeters,
  validateRoute,
} from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildRoute,
  haversineMeters,
  profileToRaw,
  RouteError,
  smoothElevations,
  smoothingWindow,
  trackToRaw,
} from "../src/routes/geometry.ts";

/**
 * The geometry of a route (spec §11.4, ADR 0036): resampling to one point every step, the smoothing of the elevation and the numbers
 * worked out by code. The expected numbers are worked out by hand in the comments.
 */

const flat = (lengthMeters: number, startElevation = 100) =>
  profileToRaw([{ lengthMeters, gradePercent: 0 }], startElevation).raw;

describe("the great-circle distance", () => {
  it("is the radius of the sphere times the angle: one degree of latitude is 111195.08 m", () => {
    // R = 6371008.8 m; R * pi / 180 = 111195.0802 m.
    expect(haversineMeters(45, 7, 46, 7)).toBeCloseTo(111_195.08, 1);
    expect(haversineMeters(0, 0, 0, 1)).toBeCloseTo(111_195.08, 1);
    expect(haversineMeters(45, 7, 45, 7)).toBe(0);
  });

  it("shortens a degree of longitude with the latitude", () => {
    // At 60 degrees north a degree of longitude is half of one at the equator: cos 60 = 0.5.
    expect(haversineMeters(60, 0, 60, 1)).toBeCloseTo(111_195.08 / 2, -1);
  });
});

describe("the smoothing of the elevation", () => {
  it("is an average over three points at the default step and keeps the ends", () => {
    // [0, 0, 60, 0, 0, 0, 0] with a window of 3: the ends are kept (the window is 1 there), the others are the mean of three.
    //   i=1: (0+0+60)/3 = 20, i=2: (0+60+0)/3 = 20, i=3: (60+0+0)/3 = 20, i=4..5: 0.
    expect(smoothElevations([0, 0, 60, 0, 0, 0, 0], 3)).toEqual([0, 20, 20, 20, 0, 0, 0]);
  });

  it("keeps the first and the last elevation whatever the window, and shrinks the window symmetrically near them", () => {
    const e = [10, 50, 20, 20, 20, 90, 30];
    const out = smoothElevations(e, 5);
    expect(out[0]).toBe(10);
    expect(out[6]).toBe(30);
    // i=1 has one point each side (the window shrinks to 3): (10+50+20)/3.
    expect(out[1]).toBeCloseTo(80 / 3, 10);
    // i=2 has two each side: (10+50+20+20+20)/5.
    expect(out[2]).toBe(24);
    // i=5 has one each side: (20+90+30)/3.
    expect(out[5]).toBeCloseTo(140 / 3, 10);
  });

  it("is about 60 m wide at any step, odd and at least 3 points", () => {
    expect(smoothingWindow(20)).toBe(3);
    expect(smoothingWindow(10)).toBe(7); // 6 points, made odd
    expect(smoothingWindow(5)).toBe(13); // 12 points, made odd
    expect(smoothingWindow(30)).toBe(3); // 2 points, but never fewer than 3
    expect(smoothingWindow(200)).toBe(3);
  });

  it("changes nothing in a straight climb (the average of a line is the line)", () => {
    const climb = Array.from({ length: 9 }, (_v, i) => 100 + i * 2);
    expect(smoothElevations(climb, 3)).toEqual(climb);
  });
});

describe("a synthetic profile", () => {
  it("makes the points of a hill, by hand", () => {
    // 100 m at +5% then 100 m at -5%, from 100 m: the raw line is 100 at d=0, 105 at d=100, 100 at d=200.
    // At 20 m: 100 101 102 103 104 105 104 103 102 101 100, smoothed over three points: only the peak changes,
    // (104+105+104)/3 = 104.333, rounded to a tenth 104.3; every other point is on a straight stretch.
    const { raw, clamped } = profileToRaw(
      [
        { lengthMeters: 100, gradePercent: 5 },
        { lengthMeters: 100, gradePercent: -5 },
      ],
      100,
    );
    expect(clamped).toBe(0);
    const built = buildRoute(raw, { stepMeters: 20 });
    expect(built.points).toEqual(
      [100, 101, 102, 103, 104, 104.3, 104, 103, 102, 101, 100].map((e, i) => ({ d: i * 20, e })),
    );
    // The rises: 100 to 104 is 4, 104 to 104.3 is 0.3, the descent counts for nothing: 4.3, which is 4 m.
    expect(built.ascentMeters).toBe(4);
    expect(built.distanceMeters).toBe(200);
    expect(built.hasCoordinates).toBe(false);
    expect(built.stepMeters).toBe(20);
  });

  it("clamps a grade beyond 20% and says how many segments it clamped", () => {
    const { raw, clamped } = profileToRaw(
      [
        { lengthMeters: 100, gradePercent: 35 },
        { lengthMeters: 100, gradePercent: -50 },
        { lengthMeters: 100, gradePercent: 20 },
      ],
      0,
    );
    expect(clamped).toBe(2);
    // +20 m, then -20 m, then +20 m.
    expect(raw.map((p) => p.e)).toEqual([0, 20, 0, 20]);
  });

  it("refuses an empty profile, a segment with no length or grade, and more than 200 km", () => {
    expect(() => profileToRaw([], 0)).toThrow(/no segments/);
    expect(() => profileToRaw([{ lengthMeters: 0, gradePercent: 1 }], 0)).toThrow(
      /segment 1 has no length/,
    );
    expect(() => profileToRaw([{ lengthMeters: 100, gradePercent: Number.NaN }], 0)).toThrow(
      /segment 1 has no grade/,
    );
    expect(() => profileToRaw([{ lengthMeters: 200_001, gradePercent: 0 }], 0)).toThrow(
      /longer than 200 km/,
    );
    expect(profileToRaw([{ lengthMeters: 200_000, gradePercent: 0 }], 0).raw).toHaveLength(2);
  });
});

describe("resampling", () => {
  it("puts a point every step and the last one at the end, even when the last gap is short", () => {
    // 130 m: 0 20 40 60 80 100 120 and the end at 130 (a last gap of 10 m).
    const built = buildRoute(flat(130), { stepMeters: 20 });
    expect(built.points.map((p) => p.d)).toEqual([0, 20, 40, 60, 80, 100, 120, 130]);
    expect(built.distanceMeters).toBe(130);
  });

  it("folds a last gap under a metre into the point before it, so no two points are equal after rounding", () => {
    const built = buildRoute(flat(120.5), { stepMeters: 20 });
    expect(built.points.map((p) => p.d)).toEqual([0, 20, 40, 60, 80, 100, 120.5]);
    const exact = buildRoute(flat(120), { stepMeters: 20 });
    expect(exact.points.map((p) => p.d)).toEqual([0, 20, 40, 60, 80, 100, 120]);
  });

  it("uses a coarser step for a route that would pass 5000 points, and says which", () => {
    // 99.96 km at 20 m is 4999 intervals: 5000 points would be the limit; 4999 + 1 fits and the step stays 20.
    const near = buildRoute(flat(99_960), { stepMeters: 20 });
    expect(near.stepMeters).toBe(20);
    expect(near.points).toHaveLength(4999);
    // 100 km would be 5001 points: the smallest whole-metre step that fits is 21 m.
    const hundred = buildRoute(flat(100_000), { stepMeters: 20 });
    expect(hundred.stepMeters).toBe(21);
    expect(hundred.points.length).toBeLessThanOrEqual(ROUTE_MAX_POINTS);
    // 200 km: 41 m; every point still increases and the route is valid.
    const far = buildRoute(flat(200_000), { stepMeters: 20 });
    expect(far.stepMeters).toBe(41);
    expect(far.points.length).toBeLessThanOrEqual(ROUTE_MAX_POINTS);
    for (let i = 1; i < far.points.length; i++) {
      expect((far.points[i] as RoutePoint).d).toBeGreaterThan((far.points[i - 1] as RoutePoint).d);
    }
    expect(far.distanceMeters).toBe(200_000);
  });

  it("honours a coarser setting and never goes finer than asked", () => {
    const built = buildRoute(flat(1000), { stepMeters: 50 });
    expect(built.stepMeters).toBe(50);
    expect(built.points).toHaveLength(21);
  });

  it("makes routes that the contract accepts, with or without coordinates", () => {
    const profile = buildRoute(flat(5000), { stepMeters: 20 });
    expect(
      validateRoute({
        id: "route-test",
        name: "Flat",
        source: "profile",
        distanceMeters: profile.distanceMeters,
        ascentMeters: profile.ascentMeters,
        hasCoordinates: profile.hasCoordinates,
        createdAt: "2026-11-19T10:00:00Z",
        points: profile.points,
      }),
    ).toMatchObject({ ok: true });
  });

  it("interpolates the coordinates and the elevation along a track", () => {
    // A track north along one meridian, five points 0.0009 degrees apart (100.075 m each), rising 10 m a point.
    const track = Array.from({ length: 5 }, (_v, k) => ({
      lat: 45 + k * 0.0009,
      lon: 7,
      ele: 200 + k * 10,
    }));
    const raw = trackToRaw(track);
    expect(raw[4]?.d).toBeCloseTo(4 * 100.0756, 1);
    const built = buildRoute(raw, { stepMeters: 20 });
    expect(built.hasCoordinates).toBe(true);
    const first = built.points[0] as RoutePoint;
    const last = built.points[built.points.length - 1] as RoutePoint;
    expect(first).toMatchObject({ d: 0, e: 200, lat: 45, lon: 7 });
    expect(last.lat).toBeCloseTo(45.0036, 6);
    expect(last.e).toBe(240);
    // The meridian stays a meridian, and latitude rises with the distance: d / 111194.93 degrees from the start.
    for (const p of built.points) {
      expect(p.lon).toBe(7);
      expect(p.lat as number).toBeCloseTo(45 + p.d / 111_195.0802, 5);
    }
    // A straight climb of 10 m per 100 m: the smoothing leaves the line, to a tenth of a metre.
    expect(built.points[5]?.e).toBeCloseTo(200 + (10 * (5 * 20)) / 100.0756, 1);
  });

  it("drops points that are the same place, so a stopped GPS adds no distance", () => {
    const track = [
      { lat: 45, lon: 7, ele: 100 },
      { lat: 45, lon: 7, ele: 100 },
      { lat: 45.002, lon: 7, ele: 101 },
      { lat: 45.002, lon: 7, ele: 101 },
    ];
    const raw = trackToRaw(track);
    expect(raw).toHaveLength(2);
    expect(raw[1]?.d).toBeCloseTo(222.39, 1);
  });
});

describe("what it refuses, in plain words", () => {
  it("a route that is too short or too long", () => {
    expect(() => buildRoute(flat(99), { stepMeters: 20 })).toThrow(RouteError);
    expect(() => buildRoute(flat(99), { stepMeters: 20 })).toThrow(/at least 100 m/);
    const long = [
      { d: 0, e: 100 },
      { d: 500_001, e: 100 },
    ];
    expect(() => buildRoute(long, { stepMeters: 20, maxMeters: 500_000 })).toThrow(
      /at most 500 km/,
    );
  });

  it("an elevation that is not metres above sea level", () => {
    const raw = [
      { d: 0, e: 100 },
      { d: 200, e: 9500 },
    ];
    expect(() => buildRoute(raw, { stepMeters: 20 })).toThrow(/not plausible/);
  });

  it("a route with coordinates on some points only", () => {
    const raw = [
      { d: 0, e: 100, lat: 45, lon: 7 },
      { d: 200, e: 100 },
    ];
    expect(() => buildRoute(raw, { stepMeters: 20 })).toThrow(/some points have coordinates/);
  });

  it("a single point", () => {
    expect(() => buildRoute([{ d: 0, e: 100 }], { stepMeters: 20 })).toThrow(/two points/);
  });
});

describe("the ascent", () => {
  it("is computed from the rounded points, so the contract's check agrees", () => {
    const built = buildRoute(
      profileToRaw(
        [
          { lengthMeters: 1000, gradePercent: 4 },
          { lengthMeters: 500, gradePercent: -2 },
          { lengthMeters: 700, gradePercent: 8 },
        ],
        50,
      ).raw,
      { stepMeters: 20 },
    );
    expect(Math.abs(built.ascentMeters - routeAscentMeters(built.points))).toBeLessThan(0.5 + 1e-9);
    // 40 m + 56 m = 96 m of climbing, less what the smoothing takes off the two corners (about a metre each at most).
    expect(built.ascentMeters).toBeGreaterThanOrEqual(94);
    expect(built.ascentMeters).toBeLessThanOrEqual(96);
  });
});
