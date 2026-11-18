import { readdirSync, readFileSync } from "node:fs";
import type { Workout } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  describeSteps,
  formatDuration,
  workoutDurationSeconds,
} from "../src/plan/workout-summary.ts";
import {
  dayAndMonth,
  formatClock,
  isoWeekday,
  localParts,
  mondayOf,
  parseClock,
  shortDate,
  weekdayName,
  zonedInstant,
} from "../src/zoned.ts";

const fixtures = new URL("../../contracts/fixtures/workouts/valid/", import.meta.url);
const fixture = (name: string): Workout =>
  JSON.parse(readFileSync(new URL(name, fixtures), "utf8")) as Workout;

describe("zonedInstant", () => {
  it("reads a wall-clock time in winter and in summer", () => {
    expect(zonedInstant("2026-11-19", { hour: 7, minute: 0 }, "Europe/Paris").toISOString()).toBe(
      "2026-11-19T06:00:00.000Z",
    );
    expect(zonedInstant("2026-07-19", { hour: 7, minute: 30 }, "Europe/Paris").toISOString()).toBe(
      "2026-07-19T05:30:00.000Z",
    );
    expect(zonedInstant("2026-11-19", { hour: 7, minute: 0 }, "UTC").toISOString()).toBe(
      "2026-11-19T07:00:00.000Z",
    );
    expect(
      zonedInstant("2026-11-19", { hour: 7, minute: 0 }, "America/New_York").toISOString(),
    ).toBe("2026-11-19T12:00:00.000Z");
  });

  it("is right on the days the clocks change", () => {
    // Paris went forward on 2026-03-29 and back on 2026-10-25.
    expect(zonedInstant("2026-03-29", { hour: 7, minute: 0 }, "Europe/Paris").toISOString()).toBe(
      "2026-03-29T05:00:00.000Z",
    );
    expect(zonedInstant("2026-10-25", { hour: 7, minute: 0 }, "Europe/Paris").toISOString()).toBe(
      "2026-10-25T06:00:00.000Z",
    );
    expect(zonedInstant("2026-03-29", { hour: 0, minute: 0 }, "Europe/Paris").toISOString()).toBe(
      "2026-03-28T23:00:00.000Z",
    );
  });

  it("gives the date, time and weekday an instant has in a zone", () => {
    expect(localParts(Date.parse("2026-11-18T23:30:00Z"), "Europe/Paris")).toEqual({
      date: "2026-11-19",
      hour: 0,
      minute: 30,
      weekday: 4,
    });
    expect(localParts(Date.parse("2026-11-22T10:00:00Z"), "UTC").weekday).toBe(7);
  });
});

describe("calendar helpers", () => {
  it("knows the weekday, the Monday and the English names", () => {
    expect(isoWeekday("2026-11-18")).toBe(3); // Wednesday
    expect(isoWeekday("2026-11-22")).toBe(7);
    expect(mondayOf("2026-11-22")).toBe("2026-11-16");
    expect(mondayOf("2026-11-16")).toBe("2026-11-16");
    expect(weekdayName("2026-11-18")).toBe("Wed");
    expect(dayAndMonth("2026-11-05")).toBe("5 Nov");
    expect(shortDate("2026-11-18")).toBe("Wed 18 Nov");
  });

  it("parses and prints a time of day", () => {
    expect(parseClock("07:00")).toEqual({ hour: 7, minute: 0 });
    expect(parseClock("23:59")).toEqual({ hour: 23, minute: 59 });
    expect(parseClock("24:00")).toBeNull();
    expect(parseClock("7:00")).toBeNull();
    expect(formatClock({ hour: 7, minute: 5 })).toBe("07:05");
  });
});

describe("formatDuration", () => {
  it("says seconds, minutes and hours in plain words", () => {
    expect(formatDuration(45)).toBe("45 s");
    expect(formatDuration(600)).toBe("10 min");
    expect(formatDuration(90)).toBe("1 min 30 s");
    expect(formatDuration(3600)).toBe("1 h");
    expect(formatDuration(3720)).toBe("1 h 02 min");
    expect(formatDuration(3725)).toBe("1 h 02 min 5 s");
    expect(formatDuration(0)).toBe("0 s");
  });
});

describe("describeSteps and workoutDurationSeconds", () => {
  it("add up the sweet spot fixture, repeats counted", () => {
    const workout = fixture("sweet-spot-3x10.json");
    expect(workoutDurationSeconds(workout)).toBe(600 + 3 * (600 + 300) + 300 + 300);
    expect(describeSteps(workout)).toBe(
      "10 min warm-up (45% to 70% FTP), 3 x (10 min sweet spot at 88% FTP, 5 min recover at 50% FTP), 5 min free ride, 5 min cool-down (55% to 40% FTP)",
    );
  });

  it("writes a repeat of one step as `3 x 8 min at 95% FTP` and ramps without a label as ramps", () => {
    const workout: Workout = {
      schema: 1,
      id: "w",
      name: "W",
      steps: [
        { type: "ramp", duration: 600, from: 0.5, to: 0.75, label: "Warm-up" },
        { type: "repeat", count: 3, steps: [{ type: "steady", duration: 480, target: 0.95 }] },
        { type: "ramp", duration: 120, from: 0.6, to: 0.9 },
        { type: "steady", duration: 90, target: 0.875 },
        { type: "free", duration: 600, label: "Cool-down" },
      ],
    };
    expect(describeSteps(workout)).toBe(
      "10 min warm-up (50% to 75% FTP), 3 x 8 min at 95% FTP, 2 min ramp 60% to 90% FTP, 1 min 30 s at 87.5% FTP, 10 min cool-down",
    );
  });

  it("keeps a label that is not an ordinary word as it is, and cuts a very long description", () => {
    const workout: Workout = {
      schema: 1,
      id: "w",
      name: "W",
      steps: [{ type: "steady", duration: 600, target: 0.65, label: "Z2 endurance" }],
    };
    expect(describeSteps(workout)).toBe("10 min Z2 endurance at 65% FTP");
    const long: Workout = {
      schema: 1,
      id: "w",
      name: "W",
      steps: Array.from({ length: 80 }, () => ({
        type: "steady" as const,
        duration: 60,
        target: 0.6,
      })),
    };
    const text = describeSteps(long, 100);
    expect(text.length).toBeLessThanOrEqual(100);
    expect(text.endsWith("…")).toBe(true);
  });

  it("describes every valid workout fixture without throwing", () => {
    for (const name of readdirSync(fixtures).filter((f) => f.endsWith(".json"))) {
      const workout = fixture(name);
      expect(describeSteps(workout).length, name).toBeGreaterThan(0);
      expect(workoutDurationSeconds(workout), name).toBeGreaterThan(0);
    }
  });
});
