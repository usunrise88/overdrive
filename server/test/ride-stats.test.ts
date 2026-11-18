import type { RideRecord, Workout } from "@overdrive/contracts";
import { COGGAN_ZONE_UPPER_PERCENT } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  flattenWorkout,
  MAX_SERIES_POINTS,
  rideCompliance,
  rideSeries,
  rideStatistics,
} from "../src/ride-stats.ts";
import { rideFixture, validRideFixtures } from "./helpers/app.ts";

const ZONES = COGGAN_ZONE_UPPER_PERCENT;

/** A ride of `seconds` seconds whose power is `watts(second)`; no pauses, no workout. */
function ride(seconds: number, watts: (second: number) => number | null, ftp = 250): RideRecord {
  const samples = Array.from({ length: seconds }, (_, i) => {
    const power = watts(i + 1);
    return {
      timeOffsetSeconds: i + 1,
      ...(power === null ? {} : { power }),
      heartRate: 100 + (i % 7),
      cadence: i % 10 === 0 ? 0 : 90,
      distanceMeters: (i + 1) * 8,
    };
  });
  return {
    schema: 1,
    ride: { id: "r", startEpochMillis: 1, ftp },
    samples,
    rrIntervals: [],
    laps: [{ startSeconds: 0, endSeconds: seconds }],
    summary: {
      durationSeconds: seconds,
      movingSeconds: seconds,
      distanceMeters: 0,
      workKilojoules: 0,
    },
  };
}

describe("rideStatistics", () => {
  it.each(validRideFixtures().filter(([, r]) => r.samples.length > 30))(
    "agrees with the summary the tablet recorded for %s",
    (_name, record) => {
      const stats = rideStatistics(record, ZONES);
      const { summary } = record;
      expect(stats.durationSeconds).toBe(summary.durationSeconds);
      expect(stats.movingSeconds).toBe(summary.movingSeconds);
      expect(stats.pausedSeconds).toBe(summary.durationSeconds - summary.movingSeconds);
      expect(stats.power.averageWatts).toBeCloseTo(summary.averagePower ?? Number.NaN, 1);
      expect(stats.power.normalizedWatts).toBeCloseTo(summary.normalizedPower ?? Number.NaN, 1);
      expect(stats.power.maxWatts).toBe(summary.maxPower);
      expect(stats.power.intensityFactor).toBeCloseTo(summary.intensityFactor ?? Number.NaN, 2);
      expect(stats.power.load).toBeCloseTo(summary.trainingStressScore ?? Number.NaN, 1);
      expect(stats.power.workKilojoules).toBeCloseTo(summary.workKilojoules, 1);
      expect(stats.heartRate.averageBpm).toBeCloseTo(summary.averageHeartRate ?? Number.NaN, 1);
      expect(stats.heartRate.maxBpm).toBe(summary.maxHeartRate);
    },
  );

  it("puts every powered second that was not paused in exactly one zone", () => {
    const record = rideFixture("synthetic-5min.json");
    const stats = rideStatistics(record, ZONES);
    const powered = record.samples.filter((s) => s.paused !== true && s.power != null).length;
    expect(stats.power.secondsInZone).toHaveLength(7);
    expect(stats.power.secondsInZone.reduce((a, b) => a + b, 0)).toBe(powered);
  });

  it("reads zones by the FTP of the ride and the bounds it is given", () => {
    // 150 W at FTP 250 is 60%: zone 2 with Coggan, zone 1 when zone 1 is allowed up to 65%.
    const record = ride(10, () => 150);
    expect(rideStatistics(record, ZONES).power.secondsInZone.slice(0, 3)).toEqual([0, 10, 0]);
    expect(
      rideStatistics(record, [65, 75, 90, 105, 120, 150]).power.secondsInZone.slice(0, 3),
    ).toEqual([10, 0, 0]);
  });

  it("finds the best efforts, counting gaps as zero and not reporting what the ride is too short for", () => {
    // 20 minutes at 100 W with five minutes at 300 W in the middle.
    const record = ride(1200, (s) => (s > 500 && s <= 800 ? 300 : 100));
    const best = Object.fromEntries(
      rideStatistics(record, ZONES).power.bestEfforts.map((e) => [e.seconds, e.watts]),
    );
    expect(best).toEqual({ 5: 300, 60: 300, 300: 300, 1200: 150 });
    expect(
      rideStatistics(
        ride(100, () => 200),
        ZONES,
      ).power.bestEfforts.map((e) => e.seconds),
    ).toEqual([5, 60]);
    // A missing reading counts as 0 W in the window.
    const gappy = ride(10, (s) => (s <= 5 ? 200 : null));
    expect(rideStatistics(gappy, ZONES).power.bestEfforts).toEqual([{ seconds: 5, watts: 200 }]);
  });

  it("makes steady power normalise to itself, and a surge make normalised power exceed the average", () => {
    const steady = rideStatistics(
      ride(120, () => 200),
      ZONES,
    ).power;
    expect(steady.averageWatts).toBe(200);
    expect(steady.normalizedWatts).toBe(200);
    expect(steady.variabilityIndex).toBe(1);
    const surgy = rideStatistics(
      ride(120, (s) => (Math.floor(s / 30) % 2 === 0 ? 100 : 300)),
      ZONES,
    ).power;
    expect(surgy.normalizedWatts as number).toBeGreaterThan(surgy.averageWatts as number);
    expect(surgy.variabilityIndex as number).toBeGreaterThan(1);
  });

  it("has no normalised power under 30 seconds, and no figures without power at all", () => {
    expect(
      rideStatistics(
        ride(20, () => 200),
        ZONES,
      ).power.normalizedWatts,
    ).toBeNull();
    const none = rideStatistics(
      ride(20, () => null),
      ZONES,
    );
    expect(none.power.averageWatts).toBeNull();
    expect(none.power.maxWatts).toBeNull();
    expect(none.power.load).toBeNull();
    expect(none.power.workKilojoules).toBe(0);
    expect(none.power.bestEfforts).toEqual([]);
  });

  it("averages cadence over the seconds spent pedalling, and reports heart rate and distance", () => {
    const stats = rideStatistics(
      ride(100, () => 150),
      ZONES,
    );
    expect(stats.cadence).toEqual({ averageRpm: 90, maxRpm: 90 });
    expect(stats.heartRate).toEqual({ averageBpm: 103, maxBpm: 106, minBpm: 100 });
    expect(stats.distanceKilometers).toBe(0.8);
  });

  it("copes with a ride that has no samples", () => {
    const empty = rideFixture("empty-ride.json");
    const stats = rideStatistics(empty, ZONES);
    expect(stats.durationSeconds).toBe(0);
    expect(stats.power.averageWatts).toBeNull();
    expect(stats.heartRate.averageBpm).toBeNull();
  });
});

describe("rideSeries", () => {
  it("averages the samples in steps and keeps a missing step null", () => {
    const record = ride(10, (s) => (s <= 4 ? s * 100 : null));
    const series = rideSeries(record.samples, 5);
    expect(series.stepSeconds).toBe(5);
    expect(series.power).toEqual([250, null]);
    expect(series.heartRate).toHaveLength(2);
    expect(series.targetPower).toEqual([null, null]);
  });

  it("lengthens the step of a long ride so that no series is longer than the limit", () => {
    const record = ride(7200, () => 150);
    const series = rideSeries(record.samples, 1);
    expect(series.stepSeconds).toBe(6);
    expect(series.power).toHaveLength(MAX_SERIES_POINTS);
    expect(rideSeries(record.samples, 600).stepSeconds).toBe(600);
  });
});

const workout: Workout = {
  schema: 1,
  id: "synthetic-steps",
  name: "Synthetic steps",
  steps: [
    { type: "steady", duration: 60, target: 0.48, label: "Warm-up" },
    {
      type: "repeat",
      count: 2,
      label: "Efforts",
      steps: [
        { type: "steady", duration: 45, target: 0.88 },
        { type: "ramp", duration: 30, from: 0.5, to: 0.7, label: "Easy" },
      ],
    },
    { type: "free", duration: 90 },
  ],
};

describe("flattenWorkout", () => {
  it("expands repeats in order, names steps and says what each asks for", () => {
    const flat = flattenWorkout(workout);
    expect(flat.map((s) => s.index)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(flat.map((s) => s.label)).toEqual([
      "Warm-up",
      "Efforts",
      "Easy",
      "Efforts",
      "Easy",
      "Free ride",
    ]);
    expect(flat.map((s) => s.type)).toEqual(["steady", "steady", "ramp", "steady", "ramp", "free"]);
    expect(flat[2]).toMatchObject({ fromFraction: 0.5, toFraction: 0.7, durationSeconds: 30 });
    expect(flat[5]).toMatchObject({ fromFraction: null, toFraction: null });
  });
});

describe("rideCompliance", () => {
  const record = rideFixture("synthetic-5min.json");
  // The synthetic ride follows four steps: 60 s at 120 W, 90 s at 220 W, 60 s at 125 W, then 90 s free.
  const played: Workout = {
    schema: 1,
    id: "synthetic-steps",
    name: "Synthetic steps",
    steps: [
      { type: "steady", duration: 60, target: 0.48 },
      { type: "steady", duration: 90, target: 0.88 },
      { type: "steady", duration: 60, target: 0.5 },
      { type: "free", duration: 90 },
    ],
  };

  it("compares each step's planned length and power with what was ridden", () => {
    const result = rideCompliance(record, played);
    if ("problem" in result) throw new Error(result.problem);
    expect(result.workout).toEqual({ id: "synthetic-steps", name: "Synthetic steps" });
    expect(result.stepsPlanned).toBe(4);
    expect(result.plannedSeconds).toBe(300);
    // Pauses are not counted: 80 of the 90 seconds of step 2 and 55 of the 60 of step 3.
    expect(result.steps.map((s) => s.actualSeconds)).toEqual([60, 80, 55, 90]);
    expect(result.actualSeconds).toBe(285);
    expect(result.stepsCompleted).toBe(2);

    const second = result.steps[1];
    expect(second).toMatchObject({ step: 2, plannedSeconds: 90, plannedWatts: 220 });
    const ridden = record.samples.filter((s) => s.stepIndex === 1 && s.paused !== true);
    const expected = ridden.reduce((a, s) => a + (s.power ?? 0), 0) / ridden.length;
    expect(second?.actualWatts).toBeCloseTo(expected, 1);
    expect(second?.deltaWatts).toBeCloseTo(expected - 220, 1);
    expect(second?.deltaPercent).toBeCloseTo(((expected - 220) / 220) * 100, 1);

    // A free step has nothing to compare power with.
    expect(result.steps[3]).toMatchObject({
      type: "free",
      plannedWatts: null,
      deltaWatts: null,
      deltaPercent: null,
    });
    expect(result.steps[3]?.actualWatts).not.toBeNull();
  });

  it("totals the steps that have a power target, weighted by their length", () => {
    const result = rideCompliance(record, played);
    if ("problem" in result) throw new Error(result.problem);
    // (60 s at 120 W, 90 s at 220 W, 60 s at 125 W) over 210 s
    expect(result.targetSteps.plannedWatts).toBeCloseTo((60 * 120 + 90 * 220 + 60 * 125) / 210, 1);
    const targeted = record.samples.filter(
      (s) => s.stepIndex !== 3 && s.paused !== true && s.power != null,
    );
    const actual = targeted.reduce((a, s) => a + (s.power as number), 0) / targeted.length;
    expect(result.targetSteps.actualWatts).toBeCloseTo(actual, 1);
    expect(result.targetSteps.deltaPercent).not.toBeNull();
  });

  it("uses the ride's own FTP for the planned watts", () => {
    const result = rideCompliance({ ...record, ride: { ...record.ride, ftp: 300 } }, played);
    if ("problem" in result) throw new Error(result.problem);
    expect(result.steps[1]?.plannedWatts).toBe(264);
  });

  it("says why when the ride and the workout cannot be compared", () => {
    const short: Workout = { ...played, steps: played.steps.slice(0, 2) };
    expect(rideCompliance(record, short)).toEqual({
      problem: "the ride has a step 3 and the workout has only 2",
    });
    const free = rideFixture("minimal-free-ride.json");
    expect(rideCompliance(free, played)).toEqual({ problem: "the ride recorded no workout step" });
  });
});
