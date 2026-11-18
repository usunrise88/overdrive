import { readdirSync, readFileSync } from "node:fs";
import { validateWorkout, type Workout } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { plannedLoad, workoutDurationSeconds, workoutOutline } from "../src/plan/summary.ts";
import { dayStartMillis, localDate } from "../src/time.ts";

const fixtureDir = new URL("../../contracts/fixtures/workouts/valid/", import.meta.url);
const fixture = (name: string): Workout => {
  const checked = validateWorkout(JSON.parse(readFileSync(new URL(name, fixtureDir), "utf8")));
  if (!checked.ok) throw new Error(checked.errors.join("; "));
  return checked.value;
};

describe("workout summaries", () => {
  it("adds up the length of a workout with repeats", () => {
    const sweetSpot = fixture("sweet-spot-3x10.json");
    const expected = sweetSpot.steps.reduce(
      (sum, step) =>
        sum +
        (step.type === "repeat"
          ? step.count * step.steps.reduce((a, s) => a + s.duration, 0)
          : step.duration),
      0,
    );
    expect(workoutDurationSeconds(sweetSpot)).toBe(expected);
    expect(expected).toBeGreaterThan(3000);
  });

  it("estimates the load from the targets: an hour at FTP is 100, a ramp counts as its mean square", () => {
    const hourAtFtp: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [{ type: "steady", duration: 3600, target: 1 }],
    };
    expect(plannedLoad(hourAtFtp)).toEqual({ estimatedTss: 100, freeSeconds: 0 });
    const halfAtHalf: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [{ type: "steady", duration: 3600, target: 0.5 }],
    };
    expect(plannedLoad(halfAtHalf).estimatedTss).toBe(25);
    // A ramp from 0.4 to 0.8: mean square (0.16 + 0.32 + 0.64) / 3, for 3600 s.
    const ramp: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [{ type: "ramp", duration: 3600, from: 0.4, to: 0.8 }],
    };
    expect(plannedLoad(ramp).estimatedTss).toBe(Math.round(((0.16 + 0.32 + 0.64) / 3) * 100));
  });

  it("counts repeats, and leaves free steps out of the load but reports their time", () => {
    const workout: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [
        {
          type: "repeat",
          count: 3,
          steps: [
            { type: "steady", duration: 600, target: 1 },
            { type: "free", duration: 300 },
          ],
        },
        { type: "free", duration: 600 },
      ],
    };
    expect(plannedLoad(workout)).toEqual({ estimatedTss: 50, freeSeconds: 1500 });
    expect(workoutDurationSeconds(workout)).toBe(3 * 900 + 600);
  });

  it("describes the shape in one line", () => {
    expect(workoutOutline(fixture("sweet-spot-3x10.json"))).toMatch(/3×\(10m 88% \+ 5m 50%\)/);
    const mixed: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [
        { type: "ramp", duration: 600, from: 0.45, to: 0.7 },
        { type: "steady", duration: 90, target: 0.875 },
        { type: "free", duration: 300 },
      ],
    };
    expect(workoutOutline(mixed)).toBe("10m 45→70%, 1m30s 87.5%, 5m free");
  });

  it("summarises every valid workout fixture without a surprise", () => {
    for (const file of readdirSync(fixtureDir)) {
      const workout = fixture(file);
      expect(workoutDurationSeconds(workout), file).toBeGreaterThan(0);
      expect(workoutOutline(workout), file).not.toBe("");
      const load = plannedLoad(workout);
      expect(load.estimatedTss, file).toBeGreaterThanOrEqual(0);
    }
  });
});

describe("dayStartMillis", () => {
  it("is local midnight in the rider's zone", () => {
    expect(new Date(dayStartMillis("2026-11-18", "UTC")).toISOString()).toBe(
      "2026-11-18T00:00:00.000Z",
    );
    // Paris is an hour ahead of UTC in November and two in July.
    expect(new Date(dayStartMillis("2026-11-18", "Europe/Paris")).toISOString()).toBe(
      "2026-11-17T23:00:00.000Z",
    );
    expect(new Date(dayStartMillis("2026-07-14", "Europe/Paris")).toISOString()).toBe(
      "2026-07-13T22:00:00.000Z",
    );
    expect(new Date(dayStartMillis("2026-11-18", "America/New_York")).toISOString()).toBe(
      "2026-11-18T05:00:00.000Z",
    );
    expect(new Date(dayStartMillis("2026-11-18", "Asia/Kolkata")).toISOString()).toBe(
      "2026-11-17T18:30:00.000Z",
    );
  });

  it("takes a day with a clock change as the day it is", () => {
    // Paris moved to summer time at 02:00 on 2026-03-29: that day is 23 hours long.
    const start = dayStartMillis("2026-03-29", "Europe/Paris");
    const next = dayStartMillis("2026-03-30", "Europe/Paris");
    expect(new Date(start).toISOString()).toBe("2026-03-28T23:00:00.000Z");
    expect((next - start) / 3_600_000).toBe(23);
    // and 25 hours when it moves back, on 2026-10-25
    expect(
      (dayStartMillis("2026-10-26", "Europe/Paris") -
        dayStartMillis("2026-10-25", "Europe/Paris")) /
        3_600_000,
    ).toBe(25);
  });

  it("starts the day an instant belongs to, whatever the zone", () => {
    for (const zone of ["Europe/Paris", "America/Los_Angeles", "Pacific/Auckland", "UTC"]) {
      for (const instant of [
        Date.UTC(2026, 10, 18, 0, 30),
        Date.UTC(2026, 10, 18, 23, 30),
        Date.UTC(2026, 2, 29, 1, 0),
      ]) {
        const day = localDate(instant, zone);
        const start = dayStartMillis(day, zone);
        expect(start).toBeLessThanOrEqual(instant);
        expect(localDate(start, zone)).toBe(day);
        expect(localDate(start - 1, zone) < day).toBe(true);
      }
    }
  });
});
