import { describe, expect, it } from "vitest";
import { efficiencyOf, rideProgressStats, steadyStretches } from "../src/progress/steady.ts";
import { syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * Steady stretches and the efficiency factor (spec §6.3 "Progress over time", ADR 0043). Rides are synthetic: a ride is described by
 * functions of the second (1, 2, 3, …), so each expected number is worked out by hand in the comments. The sample of second `s` is the
 * index `s - 1`, and a stretch's `startSec` is an index.
 */

const START = Date.parse("2026-11-10T07:00:00Z");

function ride(spec: {
  seconds: number;
  power: (second: number) => number | null;
  heartRate?: (second: number) => number | null;
  paused?: (second: number) => boolean;
  ftp?: number;
}) {
  return syntheticRide({
    id: "ride-test",
    startEpochMillis: START,
    ftp: spec.ftp ?? 250,
    seconds: spec.seconds,
    power: spec.power,
    heartRate: spec.heartRate ?? (() => 150),
    ...(spec.paused === undefined ? {} : { paused: spec.paused }),
  });
}

describe("steady stretches", () => {
  it("splits a long steady effort at a sprint in the middle, and leaves out the first minute", () => {
    // 90 W for 2 minutes (under 40% of the 250 W FTP: 100 W), 180 W up to second 1500, a sprint of 10 s at 600 W, 180 W to the end.
    const record = ride({
      seconds: 2400,
      power: (s) => (s <= 120 ? 90 : s <= 1500 ? 180 : s <= 1510 ? 600 : 180),
      heartRate: () => 140,
    });
    const found = steadyStretches(record.samples, 250);
    // First: from index 120 (the first 120 s are under the floor). Extended while the 30 s means stay within 10% of the stretch's
    // mean: the first sprint second (index 1500) lifts the last mean to 194 W, inside 1.1 x 180.3 = 198.3 W; the second one (208 W)
    // does not. So it ends after index 1500: 1381 s, mean (1380 x 180 + 600) / 1381 = 180.3 W.
    // Second: a start inside the sprint's 9 remaining seconds cannot hold (the means fall to 180 W, more than 10% under the stretch's
    // mean); the first that can is index 1509 (one sprint second left): 2400 - 1509 = 891 s, mean (600 + 890 x 180) / 891 = 180.5 W.
    expect(found).toEqual([
      {
        startSec: 120,
        seconds: 1381,
        meanPower: 180.3,
        meanHeartRate: 140,
        heartRateSeconds: 1381,
      },
      { startSec: 1509, seconds: 891, meanPower: 180.5, meanHeartRate: 140, heartRateSeconds: 891 },
    ]);
    expect(found.every((s) => s.seconds >= 300)).toBe(true);
  });

  it("starts after the ride's first 60 seconds", () => {
    const found = steadyStretches(ride({ seconds: 900, power: () => 200 }).samples, 250);
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ startSec: 60, seconds: 840, meanPower: 200 });
  });

  it("needs a heart rate in 90% of the seconds, and means the seconds that have one", () => {
    // 200 W from second 1 to 1000: the stretch is index 60 to 999, 940 s. A reading is missing every 20th second (5%).
    const sparse = ride({
      seconds: 1000,
      power: () => 200,
      heartRate: (s) => (s % 20 === 0 ? null : 150),
    });
    // Seconds 80, 100, ... 1000 are multiples of 20 inside the stretch (seconds 61 to 1000): 47 of them; 940 - 47 = 893 readings.
    expect(steadyStretches(sparse.samples, 250)).toEqual([
      { startSec: 60, seconds: 940, meanPower: 200, meanHeartRate: 150, heartRateSeconds: 893 },
    ]);
    // A reading missing every 6th second (16.7%) is too few: the stretch is dropped, not measured badly.
    const gaps = ride({
      seconds: 1000,
      power: () => 200,
      heartRate: (s) => (s % 6 === 0 ? null : 150),
    });
    expect(steadyStretches(gaps.samples, 250)).toEqual([]);
    // No heart rate at all: nothing.
    expect(
      steadyStretches(
        ride({ seconds: 1000, power: () => 200, heartRate: () => null }).samples,
        250,
      ),
    ).toEqual([]);
  });

  it("ignores a heart rate outside the plausible range", () => {
    // 20 bpm and 260 bpm are sensor faults: every second is one of them, so there is no heart rate at all.
    const record = ride({
      seconds: 900,
      power: () => 200,
      heartRate: (s) => (s % 2 === 0 ? 20 : 260),
    });
    expect(steadyStretches(record.samples, 250)).toEqual([]);
  });

  it("a pause ends a stretch, and each side is a stretch when it is long enough", () => {
    // Paused seconds 701 to 710 are the indexes 700 to 709: 60..699 is 640 s, 710..1499 is 790 s.
    const record = ride({
      seconds: 1500,
      power: () => 200,
      paused: (s) => s >= 701 && s <= 710,
    });
    expect(steadyStretches(record.samples, 250).map((s) => [s.startSec, s.seconds])).toEqual([
      [60, 640],
      [710, 790],
    ]);
  });

  it("a gap in the power readings ends a stretch too", () => {
    const record = ride({ seconds: 1500, power: (s) => (s === 800 ? null : 200) });
    expect(steadyStretches(record.samples, 250).map((s) => [s.startSec, s.seconds])).toEqual([
      [60, 739],
      [800, 700],
    ]);
  });

  it("is not a stretch under 5 minutes, or at under 40% of the ride's FTP", () => {
    // 60 s of warm-up, then 299 s steady.
    expect(steadyStretches(ride({ seconds: 359, power: () => 200 }).samples, 250)).toEqual([]);
    expect(steadyStretches(ride({ seconds: 360, power: () => 200 }).samples, 250)).toHaveLength(1);
    // 99 W is under 100 W (40% of 250 W); 100 W counts. The floor follows the FTP the ride was recorded with.
    expect(steadyStretches(ride({ seconds: 1000, power: () => 99 }).samples, 250)).toEqual([]);
    expect(steadyStretches(ride({ seconds: 1000, power: () => 100 }).samples, 250)).toHaveLength(1);
    expect(steadyStretches(ride({ seconds: 1000, power: () => 99 }).samples, 200)).toHaveLength(1);
  });

  it("is not steady when the power moves by more than the band", () => {
    // 60 s at 150 W and 60 s at 250 W: the 30 s means reach 150 W and 250 W, 25% from the mean of 200 W.
    const blocks = ride({
      seconds: 1300,
      power: (s) => (Math.floor((s - 1) / 60) % 2 === 0 ? 150 : 250),
    });
    expect(steadyStretches(blocks.samples, 250)).toEqual([]);
    // A gentle wave (+-6 W around 200 W over 5 minutes) is steady: its 30 s means stay within 3% of the mean.
    const wave = ride({
      seconds: 1260,
      power: (s) => Math.round(200 + 6 * Math.sin((2 * Math.PI * s) / 300)),
    });
    const found = steadyStretches(wave.samples, 250);
    expect(found).toHaveLength(1);
    expect(found[0]?.startSec).toBe(60);
    expect(found[0]?.seconds).toBe(1200);
    expect(found[0]?.meanPower).toBeCloseTo(200, 0);
  });

  it("has none for a ride with no FTP, or shorter than a stretch and a warm-up", () => {
    expect(steadyStretches(ride({ seconds: 1000, power: () => 200 }).samples, 0)).toEqual([]);
    expect(steadyStretches([], 250)).toEqual([]);
  });
});

describe("the efficiency factor of a ride", () => {
  it("is normalised power over average heart rate of the steady parts, from 20 minutes of them", () => {
    // 1560 s at 200 W and 150 bpm: the stretch is 1500 s; the normalised power of a constant 200 W is 200 W; 200 / 150 = 1.333.
    const record = ride({ seconds: 1560, power: () => 200, heartRate: () => 150 });
    const stats = rideProgressStats(record);
    expect(stats.stretches).toHaveLength(1);
    expect(stats.efficiency).toEqual({
      value: 1.333,
      normalizedPower: 200,
      averageHeartRate: 150,
      seconds: 1500,
    });
  });

  it("is null under 20 minutes of steady parts, although the stretch exists", () => {
    const stats = rideProgressStats(ride({ seconds: 960, power: () => 200 }));
    expect(stats.stretches).toHaveLength(1);
    expect(stats.stretches[0]?.seconds).toBe(900);
    expect(stats.efficiency).toBeNull();
  });

  it("adds up the steady parts of a ride that has two, and leaves out what is not steady", () => {
    // 60 s warm-up; 12 min at 180 W, 150 bpm; 5 min of hard intervals (not steady); 12 min at 180 W, 150 bpm.
    const record = ride({
      seconds: 60 + 720 + 300 + 720,
      power: (s) => {
        if (s <= 60) return 90;
        if (s <= 780) return 180;
        if (s <= 1080) return Math.floor((s - 781) / 30) % 2 === 0 ? 120 : 300;
        return 180;
      },
      heartRate: (s) => (s <= 780 ? 150 : s <= 1080 ? 170 : 150),
    });
    const stats = rideProgressStats(record);
    // Each block is found whole; the 30 s means let a stretch run on for a few seconds into the hard part (a 10% band), no more.
    const long = stats.stretches.filter((s) => s.seconds >= 700);
    expect(long).toHaveLength(2);
    for (const stretch of long) expect(stretch.seconds).toBeLessThan(740);
    // Nearly every second of the steady parts is 180 W at 150 bpm, so EF = 180 / 150 = 1.2.
    expect(stats.efficiency?.value).toBeCloseTo(1.2, 1);
    expect(stats.efficiency?.seconds).toBeGreaterThanOrEqual(1440);
  });

  it("is null for no stretch at all", () => {
    expect(efficiencyOf([], [])).toBeNull();
  });
});
