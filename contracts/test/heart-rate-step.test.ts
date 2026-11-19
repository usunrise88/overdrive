import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  type RideRecord,
  rideRecordConsistencyErrors,
  validateRideRecord,
  validateWorkout,
  type Workout,
  workoutHash,
  workoutStructure,
} from "../src/index.ts";

// Stage A1b of v0.4.0: the heart-rate step (spec §11.2), the ride record's events and heart-rate target (spec §11.1), and
// the hash of a heart-rate step. The shared fixtures are read by Kotlin's tests too.

const fixtures = new URL("../fixtures/", import.meta.url).pathname;

function hrWorkout(extra: Record<string, unknown>) {
  return {
    schema: 1,
    id: "hr",
    name: "Heart rate",
    steps: [{ type: "hr", duration: 600, power_min: 80, power_max: 200, ...extra }],
  };
}

describe("heart-rate steps (spec §11.2)", () => {
  it("accepts a target in bpm or as a fraction of the threshold, and a band", () => {
    expect(validateWorkout(hrWorkout({ target_bpm: 135 })).ok).toBe(true);
    expect(validateWorkout(hrWorkout({ target_lthr: 0.85 })).ok).toBe(true);
    expect(validateWorkout(hrWorkout({ target_bpm: 135, band: 3 })).ok).toBe(true);
  });

  it("needs exactly one target", () => {
    expect(validateWorkout(hrWorkout({})).ok).toBe(false);
    expect(validateWorkout(hrWorkout({ target_bpm: 135, target_lthr: 0.85 })).ok).toBe(false);
  });

  it("needs power limits with the least below the most", () => {
    expect(validateWorkout(hrWorkout({ target_bpm: 135, power_min: 200, power_max: 200 })).ok).toBe(
      false,
    );
    const result = validateWorkout(hrWorkout({ target_bpm: 135, power_min: 220, power_max: 80 }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.join()).toMatch(/\/steps\/0 power_min must be below/);
  });

  it("checks the limits inside a repeat too, and bounds the band and the target", () => {
    const inRepeat = {
      schema: 1,
      id: "r",
      name: "Repeat",
      steps: [
        {
          type: "repeat",
          count: 2,
          steps: [{ type: "hr", duration: 60, target_bpm: 130, power_min: 90, power_max: 90 }],
        },
      ],
    };
    const result = validateWorkout(inRepeat);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.join()).toMatch(/\/steps\/0\/steps\/0 power_min/);
    expect(validateWorkout(hrWorkout({ target_bpm: 135, band: 0 })).ok).toBe(false);
    expect(validateWorkout(hrWorkout({ target_bpm: 135, band: 31 })).ok).toBe(false);
    expect(validateWorkout(hrWorkout({ target_bpm: 300 })).ok).toBe(false);
    expect(validateWorkout(hrWorkout({ target_lthr: 2 })).ok).toBe(false);
  });
});

function validRide(name: string): RideRecord {
  const result = validateRideRecord(
    JSON.parse(readFileSync(join(fixtures, "rides", "valid", name), "utf8")),
  );
  if (!result.ok) throw new Error(`${name}: ${result.errors.join("; ")}`);
  return result.value;
}

describe("events and the heart-rate target of a ride record (spec §11.1, §11.2)", () => {
  it("an old record without events is valid, and the new fixture carries them", () => {
    expect(validRide("minimal-free-ride.json").events).toBeUndefined();
    const ride = validRide("stall-guard-and-heart-rate-step.json");
    expect(ride.events).toEqual([{ type: "stall-guard", timeOffsetSeconds: 14, stepIndex: 0 }]);
    expect(ride.samples.filter((s) => s.targetHeartRate !== undefined)).toHaveLength(14);
    expect(rideRecordConsistencyErrors(ride)).toEqual([]);
  });

  it("reports an event after the end of the ride and events out of order", () => {
    const late = structuredClone(validRide("minimal-free-ride.json"));
    late.events = [{ type: "stall-guard", timeOffsetSeconds: 4 }];
    expect(rideRecordConsistencyErrors(late)[0]).toMatch(/\/events\/0 is at second 4/);
    const unordered = structuredClone(validRide("minimal-free-ride.json"));
    unordered.events = [
      { type: "stall-guard", timeOffsetSeconds: 3 },
      { type: "stall-guard", timeOffsetSeconds: 2 },
    ];
    expect(rideRecordConsistencyErrors(unordered)[0]).toMatch(/\/events\/1 is earlier/);
  });
});

interface HashCase {
  name: string;
  workout: Workout;
  structure: string;
  hash: string;
}

describe("the hash of a heart-rate step (spec §11.7)", () => {
  const golden = JSON.parse(
    readFileSync(join(fixtures, "repeats", "workout-hash.json"), "utf8"),
  ) as { cases: HashCase[] };
  const of = (name: string): HashCase => {
    const found = golden.cases.find((c) => c.name === name);
    if (found === undefined) throw new Error(`no case ${name}`);
    return found;
  };

  it("has a token of its own: length and target, not band, limits, label or cadence", () => {
    expect(workoutStructure(of("zone-2-by-heart-rate").workout)).toBe("h2400@135,s300@450");
    expect(workoutStructure(of("tempo-by-threshold-fraction").workout)).toBe(
      "r600@450-650,h900@l880,f180,h900@l880,f180",
    );
    expect(workoutHash(of("zone-2-by-heart-rate").workout)).toBe(
      workoutHash(of("zone-2-by-heart-rate-other-band-limits-and-labels").workout),
    );
    expect(workoutHash(of("zone-2-by-heart-rate").workout)).toBe("574f429f3132a081");
  });

  it("leaves the numbers of the older steps exactly as they were", () => {
    expect(of("sweet-spot").hash).toBe("b1add004e12d9617");
    expect(of("zone-2-hour").hash).toBe("4d491be812dc3f98");
    expect(of("free-ride-hour").hash).toBe("da2b6fdd397b6f36");
  });
});
