import { readFileSync } from "node:fs";
import { validateWorkout, type Workout } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { describeRide, sessionOutline, spokenDuration } from "../src/strava/description.ts";
import { rideFixture, validRideFixtures } from "./helpers/app.ts";

const workoutFixture = (name: string): Workout => {
  const url = new URL(`../../contracts/fixtures/workouts/valid/${name}`, import.meta.url);
  const checked = validateWorkout(JSON.parse(readFileSync(url, "utf8")));
  if (!checked.ok) throw new Error(checked.errors.join("; "));
  return checked.value;
};

// The synthetic ride follows four steps: 60 s at 120 W, 90 s at 220 W, 60 s at 125 W, then 90 s free.
const synthetic: Workout = {
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

const describeFree = (name: string, timezone = "Europe/Paris") =>
  describeRide({ record: rideFixture(name), workout: null, timezone });

describe("the text of a free ride", () => {
  it("names it Virtual ride and its date in the rider's time zone", () => {
    // 1794996000000 is 2026-11-18 10:00 UTC.
    expect(describeFree("minimal-free-ride.json").name).toBe("Virtual ride 2026-11-18");
    const late = rideFixture("minimal-free-ride.json");
    late.ride.startEpochMillis = Date.parse("2026-11-18T23:30:00Z");
    // 00:30 the next day in Paris, still the 18th in UTC.
    expect(describeRide({ record: late, workout: null, timezone: "Europe/Paris" }).name).toBe(
      "Virtual ride 2026-11-19",
    );
    expect(describeRide({ record: late, workout: null, timezone: "UTC" }).name).toBe(
      "Virtual ride 2026-11-18",
    );
  });

  it.each([
    [
      "minimal-free-ride.json",
      "Free ride.\nDuration 3 s.\nAverage power 155 W.\nWork 0.5 kJ.\nFTP 220 W.",
    ],
    [
      "explicit-nulls.json",
      "Free ride.\nDuration 3 s.\nAverage power 155 W.\nWork 0.5 kJ.\nFTP 220 W.",
    ],
    [
      "fit-golden-3s.json",
      "Free ride.\nDuration 3 s.\nAverage power 210 W.\nAverage heart rate 121 bpm.\nWork 0.6 kJ.\nFTP 250 W.",
    ],
    [
      "synthetic-5min.json",
      "Free ride.\nDuration 5 min.\nAverage power 162 W, normalised power 176 W.\nAverage heart rate 131 bpm.\nWork 46 kJ, load 3.9.\nFTP 250 W.",
    ],
    ["empty-ride.json", "Free ride.\nDuration 0 s.\nFTP 200 W."],
  ])("%s", (file, expected) => {
    expect(describeFree(file).description).toBe(expected);
  });

  it("describes every valid fixture, in plain words: no emoji, no markup, short", () => {
    for (const [file, record] of validRideFixtures()) {
      const { name, description } = describeRide({ record, workout: null, timezone: "UTC" });
      expect(description.length, file).toBeLessThan(400);
      expect(name.length, file).toBeLessThanOrEqual(100);
      expect(description, file).not.toMatch(/\p{Extended_Pictographic}/u);
      expect(description, file).not.toMatch(/[<>]|NaN|undefined|null/);
    }
  });

  it("says the moving time only when the ride was paused for a minute or more", () => {
    const record = rideFixture("synthetic-5min.json");
    expect(describeRide({ record, workout: null, timezone: "UTC" }).description).not.toContain(
      "moving",
    );
    record.summary.movingSeconds = 200;
    expect(describeRide({ record, workout: null, timezone: "UTC" }).description).toContain(
      "Duration 5 min, moving 3 min.",
    );
  });
});

describe("the text of a planned ride", () => {
  it("is named for the planned workout, with the session in one line and the plan against the ride", () => {
    const text = describeRide({
      record: rideFixture("synthetic-5min.json"),
      workout: synthetic,
      timezone: "Europe/Paris",
    });
    expect(text.name).toBe("Synthetic steps");
    expect(text.description).toBe(
      [
        "Synthetic steps: 1 min at 48% FTP, 1 min 30 s at 88% FTP, 1 min at 50% FTP, 1 min 30 s free.",
        "Duration 5 min.",
        "Average power 162 W, normalised power 176 W.",
        "Average heart rate 131 bpm.",
        "Work 46 kJ, load 3.9.",
        "Planned 5 min, ridden 4 min 45 s; 2 of 4 steps completed. Target power planned 164 W, ridden 163 W (-1%).",
        "FTP 250 W.",
      ].join("\n"),
    );
  });

  it("writes a repeat and a ramp as the rider would say them", () => {
    const sweetSpot = workoutFixture("sweet-spot-3x10.json");
    expect(sessionOutline(sweetSpot)).toBe(
      "10 min ramp 45 to 70% FTP, 3 x (10 min at 88% FTP + 5 min at 50% FTP), 5 min free, 5 min ramp 55 to 40% FTP",
    );
    const text = describeRide({
      record: rideFixture("minimal-free-ride.json"),
      workout: sweetSpot,
      timezone: "UTC",
    });
    expect(text.name).toBe("Sweet spot 3x10");
    expect(text.description.split("\n")[0]).toBe(
      "Sweet spot 3x10: 10 min ramp 45 to 70% FTP, 3 x (10 min at 88% FTP + 5 min at 50% FTP), 5 min free, 5 min ramp 55 to 40% FTP.",
    );
  });

  it("leaves the comparison out when the ride recorded no steps, and says a very long workout by its size", () => {
    const text = describeRide({
      record: rideFixture("minimal-free-ride.json"),
      workout: synthetic,
      timezone: "UTC",
    });
    expect(text.description).not.toContain("Planned");

    const long: Workout = {
      schema: 1,
      id: "long",
      name: "Long one",
      steps: Array.from({ length: 12 }, (_, i) => ({
        type: "steady" as const,
        duration: 600 + i * 60,
        target: 0.6,
      })),
    };
    const summary = describeRide({
      record: rideFixture("minimal-free-ride.json"),
      workout: long,
      timezone: "UTC",
    }).description.split("\n")[0];
    expect(summary).toBe("Long one: 12 steps, 3 h 06 min.");
  });

  it("falls back to the free ride's name when the workout has none", () => {
    const unnamed: Workout = { ...synthetic, name: "  " };
    expect(
      describeRide({
        record: rideFixture("minimal-free-ride.json"),
        workout: unnamed,
        timezone: "UTC",
      }).name,
    ).toBe("Virtual ride 2026-11-18");
  });
});

describe("spokenDuration", () => {
  it.each([
    [0, "0 s"],
    [45, "45 s"],
    [60, "1 min"],
    [750, "12 min 30 s"],
    [3600, "1 h"],
    [3900, "1 h 05 min"],
    [7199, "2 h"],
  ])("%d s is %s", (seconds, text) => {
    expect(spokenDuration(seconds)).toBe(text);
  });
});
