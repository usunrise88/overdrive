import { describe, expect, it } from "vitest";
import { decodeFit } from "../src/fit-decode.ts";
import { climbOf, ImportError, readOutdoorRide, thinSamples } from "../src/outdoor/fit-ride.ts";
import { FitBuilder, fitSeconds, syntheticRide } from "./helpers/fit-encoder.ts";

const START = Date.UTC(2027, 2, 14, 9, 0, 0);

const read = (spec: Parameters<typeof syntheticRide>[0]) =>
  readOutdoorRide(decodeFit(syntheticRide(spec)));

function refusal(spec: Parameters<typeof syntheticRide>[0]): ImportError {
  try {
    read(spec);
  } catch (error) {
    if (error instanceof ImportError) return error;
    throw error;
  }
  throw new Error("expected the file to be refused");
}

describe("the outdoor ride in a FIT file", () => {
  it("is the cycling session: when it started and ended, how far, the heart rate and whether there was power", () => {
    const ride = read({
      startMs: START,
      seconds: 601,
      heartRate: (s) => 120 + (s % 10),
      power: () => 250,
      speed: () => 8,
    });
    expect(ride.startMs).toBe(START + 1000);
    // The session says it lasted 601 s from its first record.
    expect(ride.endMs).toBe(START + 602_000);
    expect(ride.endMs - ride.startMs).toBe(601_000);
    expect(ride.records).toBe(601);
    expect(ride.samples).toHaveLength(601);
    expect(ride.distanceMeters).toBeCloseTo(4808, 0);
    expect(ride.hasPower).toBe(true);
  });

  it("takes the climb from the session when it says it, else from the altitudes with a dead band", () => {
    expect(read({ startMs: START, seconds: 30, totalAscent: 412 }).elevationGainMeters).toBe(412);
    // A staircase of 5 m every 10 s over 60 s climbs 30 m: 6 steps.
    const stairs = read({
      startMs: START,
      seconds: 61,
      altitude: (s) => 100 + 5 * Math.floor((s - 1) / 10),
    });
    expect(stairs.elevationGainMeters).toBe(30);
    // Noise of +-1 m around a flat road climbs nothing.
    const flat = read({
      startMs: START,
      seconds: 61,
      altitude: (s) => 100 + (s % 2 === 0 ? 1 : -1),
    });
    expect(flat.elevationGainMeters).toBe(0);
    // No altitude at all: not known.
    expect(
      read({ startMs: START, seconds: 30, altitude: () => null }).elevationGainMeters,
    ).toBeNull();
  });

  it("climbOf counts rises beyond the dead band and ignores the rest", () => {
    expect(climbOf([])).toBeNull();
    expect(climbOf([100, 101, 99, 102, 100])).toBe(0);
    expect(climbOf([100, 104, 108, 104, 100, 108])).toBe(16);
  });

  it("ignores heart rates that cannot be readings: zero, below 30 and above 250", () => {
    const ride = read({
      startMs: START,
      seconds: 10,
      heartRate: (s) => [0, 29, 251, 255, 130, 131, 132, 133, 134, 135][s - 1] ?? null,
    });
    expect(ride.samples.map((s) => s.bpm)).toEqual([130, 131, 132, 133, 134, 135]);
  });

  it("counts a file without heart rate as a ride with no samples", () => {
    const ride = read({ startMs: START, seconds: 30 });
    expect(ride.samples).toEqual([]);
    expect(ride.sessionAverageHeartRate).toBeNull();
  });

  it("falls back on the odometer for the distance when the session has none", () => {
    const builder = new FitBuilder()
      .define(0, 0, [{ num: 0, type: "enum" }])
      .data(0, [4])
      .define(1, 12, [{ num: 0, type: "enum" }])
      .data(1, [2])
      .define(2, 20, [
        { num: 253, type: "uint32" },
        { num: 5, type: "uint32" },
      ])
      .data(2, [fitSeconds(START), 1000])
      .data(2, [fitSeconds(START) + 60, 1000 + 50_000]);
    const ride = readOutdoorRide(decodeFit(builder.build()));
    expect(ride.distanceMeters).toBe(500);
    expect(ride.startMs).toBe(START);
    expect(ride.endMs).toBe(START + 60_000);
  });

  it("reads e-biking as cycling", () => {
    expect(read({ startMs: START, seconds: 30, sport: 21 }).records).toBe(30);
  });
});

describe("files that are not an outdoor cycling ride", () => {
  it("refuses another sport with unsupported_sport, in plain words", () => {
    const error = refusal({ startMs: START, seconds: 30, sport: 1 });
    expect(error.code).toBe("unsupported_sport");
    expect(error.message).toMatch(/not a cycling ride \(its sport is running\)/);
    expect(refusal({ startMs: START, seconds: 30, sport: 5 }).message).toMatch(/swimming/);
    expect(refusal({ startMs: START, seconds: 30, sport: 99 }).message).toMatch(/number 99/);
  });

  it("refuses a file that is not an activity (a workout or a course) with invalid_fit", () => {
    const error = refusal({ startMs: START, seconds: 30, fileType: 5 });
    expect(error.code).toBe("invalid_fit");
    expect(error.message).toMatch(/not an activity file/);
  });

  it("refuses an activity with no records, and one that lasts under a second, with empty_activity", () => {
    expect(refusal({ startMs: START, seconds: 30, noRecords: true }).code).toBe("empty_activity");
    const builder = new FitBuilder().define(0, 0, [{ num: 0, type: "enum" }]).data(0, [4]);
    expect(() => readOutdoorRide(decodeFit(builder.build()))).toThrowError(/no sport|does not say/);
    const one = new FitBuilder()
      .define(0, 0, [{ num: 0, type: "enum" }])
      .data(0, [4])
      .define(1, 12, [{ num: 0, type: "enum" }])
      .data(1, [2])
      .define(2, 20, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint8" },
      ])
      .data(2, [fitSeconds(START), 120]);
    const error = (() => {
      try {
        readOutdoorRide(decodeFit(one.build()));
      } catch (e) {
        return e as ImportError;
      }
      throw new Error("expected a refusal");
    })();
    expect(error.code).toBe("empty_activity");
  });

  it("refuses a file with no session and no sport message: it does not say it is cycling", () => {
    const builder = new FitBuilder()
      .define(0, 20, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint8" },
      ])
      .data(0, [fitSeconds(START), 120])
      .data(0, [fitSeconds(START) + 60, 121]);
    try {
      readOutdoorRide(decodeFit(builder.build()));
      throw new Error("expected a refusal");
    } catch (error) {
      expect((error as ImportError).code).toBe("unsupported_sport");
    }
  });

  it("never quotes a value in a refusal", () => {
    const error = refusal({ startMs: START, seconds: 30, sport: 1, heartRate: () => 187 });
    expect(error.message).not.toContain("187");
  });
});

describe("a file with several sessions", () => {
  it("takes the cycling sessions only: a run in the middle is not part of the ride", () => {
    const ride = read({
      startMs: START,
      seconds: 601,
      heartRate: () => 130,
      more: [
        { seconds: 300, sport: 1, gapSeconds: 60 },
        { seconds: 601, sport: 2, gapSeconds: 60 },
      ],
    });
    // The first cycling session starts at its first record and the second ends at its last: the run is between them, and its
    // records are not read (they are outside both windows).
    expect(ride.records).toBe(601 + 601);
    expect(ride.startMs).toBe(START + 1000);
    expect(ride.endMs).toBe(START + (1 + 601 + 60 + 300 + 60 + 601) * 1000);
  });

  it("is a ride of two cycling sessions when the device wrote two: one activity from the first start to the last end", () => {
    const ride = read({
      startMs: START,
      seconds: 301,
      more: [{ seconds: 301, gapSeconds: 600 }],
      heartRate: () => 140,
    });
    expect(ride.records).toBe(602);
    expect(ride.endMs - ride.startMs).toBe((301 + 600 + 301) * 1000);
  });

  it("refuses a file whose every session is another sport", () => {
    expect(
      refusal({ startMs: START, seconds: 100, sport: 1, more: [{ seconds: 100, sport: 11 }] }).code,
    ).toBe("unsupported_sport");
  });
});

describe("thinning the heart rate for storage", () => {
  it("keeps at most one reading per slot: the mean of the slot, placed where the slot's first reading was", () => {
    const samples = Array.from({ length: 12 }, (_, i) => ({ tMs: START + i * 1000, bpm: 100 + i }));
    const thinned = thinSamples(samples, START, 5);
    expect(thinned).toEqual([
      [0, 102],
      [5000, 107],
      [10_000, 110.5],
    ]);
  });

  it("keeps the offsets from the start of the ride", () => {
    const thinned = thinSamples([{ tMs: START + 7000, bpm: 120 }], START, 5);
    expect(thinned).toEqual([[7000, 120]]);
  });
});
