import { readdirSync, readFileSync } from "node:fs";
import { validateWorkout, type Workout } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import type { IntervalsEvent } from "../src/intervals/client.ts";
import { convertIntervalsEvent, workoutToEventPayload } from "../src/intervals/workout-convert.ts";
import {
  cueOf,
  formatDuration,
  parseWorkoutText,
  renderWorkoutText,
} from "../src/intervals/workout-text.ts";

const fixtures = new URL("./fixtures/intervals/", import.meta.url);
const contractFixtures = new URL("../../contracts/fixtures/workouts/valid/", import.meta.url);

function load<T>(dir: string): [string, T][] {
  const base = new URL(`${dir}/`, fixtures);
  return readdirSync(base)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(new URL(f, base), "utf8")) as T]);
}

const convertible = load<{ event: IntervalsEvent; native: Workout }>("convertible");
const unsupported = load<{ event: IntervalsEvent; reason: string }>("unsupported");

const idOf = (event: IntervalsEvent): string => `icu-${event.id}`;

describe("convertIntervalsEvent: workouts it can convert", () => {
  it("has fixtures", () => {
    expect(convertible.length).toBeGreaterThanOrEqual(7);
  });

  it.each(convertible)("%s becomes the native workout in the fixture", (_name, fixture) => {
    const result = convertIntervalsEvent(fixture.event, idOf(fixture.event));
    expect(result.ok, JSON.stringify(result)).toBe(true);
    if (!result.ok) return;
    expect(result.workout).toEqual(fixture.native);
    // And it is a valid native workout, by the schema of the contract.
    expect(validateWorkout(result.workout)).toMatchObject({ ok: true });
  });

  it("says what it simplified", () => {
    const band = convertible.find(([name]) => name === "band-and-watts.json")?.[1];
    const result = convertIntervalsEvent(band?.event as IntervalsEvent, "icu-1004");
    expect(result.ok && result.notes).toEqual(["a target of 80 to 90 became its middle"]);
    const free = convertible.find(([name]) => name === "duration-only.json")?.[1];
    const second = convertIntervalsEvent(free?.event as IntervalsEvent, "icu-1007");
    expect(second.ok && second.notes[0]).toContain("planned duration");
  });

  it("takes the parsed steps over the text when both are there", () => {
    const result = convertIntervalsEvent(
      {
        id: 5,
        start_date_local: "2026-11-20T00:00:00",
        name: "Both",
        description: "- 10m 99%",
        workout_doc: { steps: [{ duration: 600, power: { value: 50, units: "%ftp" } }] },
      },
      "icu-5",
    );
    expect(result.ok && result.workout.steps).toEqual([
      { type: "steady", duration: 600, target: 0.5 },
    ]);
  });

  it("falls back to the text when the parsed steps are empty", () => {
    const result = convertIntervalsEvent(
      {
        id: 5,
        start_date_local: "2026-11-20T00:00:00",
        name: "Text",
        description: "- 10m 60%",
        workout_doc: { steps: [] },
      },
      "icu-5",
    );
    expect(result.ok && result.workout.steps).toEqual([
      { type: "steady", duration: 600, target: 0.6 },
    ]);
  });

  it("rounds durations to whole seconds and fractions to three decimals", () => {
    const result = convertIntervalsEvent(
      {
        id: 6,
        start_date_local: "2026-11-20T00:00:00",
        workout_doc: { ftp: 300, steps: [{ duration: 600.4, power: { value: 250, units: "w" } }] },
      },
      "icu-6",
    );
    expect(result.ok && result.workout.steps).toEqual([
      { type: "steady", duration: 600, target: 0.833 },
    ]);
  });
});

describe("convertIntervalsEvent: workouts it cannot convert", () => {
  it("has fixtures", () => {
    expect(unsupported.length).toBeGreaterThanOrEqual(10);
  });

  it.each(unsupported)("%s is unsupported, with a reason, and does not throw", (_name, fixture) => {
    const result = convertIntervalsEvent(fixture.event, idOf(fixture.event));
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain(fixture.reason);
    expect(result.reason).not.toMatch(/unexpected/);
  });

  it.each([
    ["null", null],
    ["a number", 7],
    ["steps that are a string", { steps: "10m 50%" }],
    [
      "deep nonsense",
      {
        steps: [
          {
            steps: [
              {
                steps: [
                  { steps: [{ steps: [{ steps: [{ steps: [{ steps: [{ steps: [{}] }] }] }] }] }] },
                ],
              },
            ],
          },
        ],
      },
    ],
  ])("never throws on a workout_doc that is %s", (_label, doc) => {
    const result = convertIntervalsEvent(
      { id: 9, start_date_local: "2026-11-20T00:00:00", name: "x", workout_doc: doc },
      "icu-9",
    );
    expect(result).toHaveProperty("ok");
  });
});

describe("parseWorkoutText", () => {
  it.each([
    ["1h2m30s", 3750],
    ["5m30s", 330],
    ["10m", 600],
    ["90s", 90],
    ["1h", 3600],
    ["1m30", 90],
    ["5'", 300],
    ['30"', 30],
    ["1'30\"", 90],
  ])("reads the duration %s as %i seconds", (token, seconds) => {
    expect(parseWorkoutText(`- ${token} 50%`)).toEqual([
      { duration: seconds, power: { value: 50, units: "%ftp" } },
    ]);
  });

  it("reads targets: percent, band, ramp in both spellings, watts, zones, heart rate, pace", () => {
    const steps = parseWorkoutText(
      [
        "- 5m 75%",
        "- 5m 80-90%",
        "- 10m ramp 50-75%",
        "- 10m ramp 50%-75%",
        "- 5m 220w",
        "- 5m Z2",
        "- 5m Z3-Z4",
        "- 5m 70% HR",
        "- 5m 95% LTHR",
        "- 5m Z2 HR",
        "- 5m 5:00/km Pace",
        "- 5m 60% Pace",
        "- 5m 60% MMP 5m",
      ].join("\n"),
    );
    expect(steps.map((s) => s.power ?? s.hr ?? s.pace)).toEqual([
      { value: 75, units: "%ftp" },
      { start: 80, end: 90, units: "%ftp" },
      { start: 50, end: 75, units: "%ftp" },
      { start: 50, end: 75, units: "%ftp" },
      { value: 220, units: "w" },
      { value: 2, units: "power_zone" },
      { start: 3, end: 4, units: "power_zone" },
      { value: 70, units: "%hr" },
      { value: 95, units: "%lthr" },
      { value: 2, units: "hr_zone" },
      { value: 0, units: "pace" },
      { value: 60, units: "%pace" },
      { value: 60, units: "%mmp" },
    ]);
    expect(steps[2]?.ramp).toBe(true);
    expect(steps[3]?.ramp).toBe(true);
  });

  it("reads cadence in both spellings, cues before the target, and ignores text after it", () => {
    expect(parseWorkoutText("- Recovery 30s 50%")).toEqual([
      { text: "Recovery", duration: 30, power: { value: 50, units: "%ftp" } },
    ]);
    expect(parseWorkoutText("- 20m 60% 90-100rpm")[0]?.cadence).toEqual({
      start: 90,
      end: 100,
      units: "rpm",
    });
    expect(
      parseWorkoutText("- 4m 100% 40-50 rpm, power is less important than getting the torque"),
    ).toEqual([
      {
        duration: 240,
        power: { value: 100, units: "%ftp" },
        cadence: { start: 40, end: 50, units: "rpm" },
      },
    ]);
    expect(parseWorkoutText("- 5m recovery at 40%")).toEqual([
      { duration: 300, power: { value: 40, units: "%ftp" } },
    ]);
  });

  it("opens a repeat on a line ending in Nx and closes it at a blank line or another heading", () => {
    const steps = parseWorkoutText(
      [
        "Warmup",
        "- 10m 55%",
        "",
        "Main set 6x",
        "- 4m 100%",
        "- 5m 40%",
        "Cooldown",
        "- 10m 50%",
        "",
        "2x",
        "- 1m 120%",
        "- 1m 50%",
        "",
        "- 5m 40%",
      ].join("\n"),
    );
    expect(steps.map((s) => s.reps ?? 1)).toEqual([1, 6, 1, 2, 1]);
    expect(steps[1]).toMatchObject({ reps: 6, text: "Main set" });
    expect(steps[1]?.steps).toHaveLength(2);
    expect(steps[3]?.text).toBeUndefined();
  });

  it("ignores headings and notes, and returns no steps for a description with none", () => {
    expect(parseWorkoutText("Warmup\nSome notes about the day.\n\n# A markdown heading")).toEqual(
      [],
    );
    expect(parseWorkoutText("")).toEqual([]);
  });

  it("reads Windows line endings", () => {
    expect(parseWorkoutText("- 5m 50%\r\n- 5m 60%\r\n")).toHaveLength(2);
  });
});

describe("renderWorkoutText", () => {
  it("formats durations compactly", () => {
    expect(formatDuration(90)).toBe("1m30s");
    expect(formatDuration(600)).toBe("10m");
    expect(formatDuration(3600)).toBe("1h");
    expect(formatDuration(3661)).toBe("1h1m1s");
    expect(formatDuration(45)).toBe("45s");
  });

  it("writes the sweet spot workout as text intervals.icu reads", () => {
    const workout = JSON.parse(
      readFileSync(new URL("sweet-spot-3x10.json", contractFixtures), "utf8"),
    ) as Workout;
    expect(renderWorkoutText(workout)).toBe(
      [
        "Three 10-minute efforts with 5-minute recoveries.",
        "",
        "- Warm-up 10m ramp 45-70%",
        "",
        "3x",
        "- Sweet spot 10m 88%",
        "- Recover 5m 50%",
        "",
        "- Free ride 5m freeride",
        "- Cool-down 5m ramp 55-40%",
      ].join("\n"),
    );
  });

  it("keeps a cue from looking like a target, and a note from opening a repeat", () => {
    expect(cueOf("Sweet spot 88% 10m")).toBe("Sweet spot");
    expect(cueOf("Z2 endurance ramp")).toBe("endurance");
    expect(cueOf("-- Hard ^ now")).toBe("Hard now");
    expect(cueOf(undefined)).toBe("");
    const workout: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      description: "- Do 3x",
      steps: [{ type: "steady", duration: 60, target: 0.5 }],
    };
    expect(renderWorkoutText(workout).split("\n")[0]).toBe("Do 3x.");
  });

  it.each(
    readdirSync(contractFixtures)
      .filter((f) => f.endsWith(".json"))
      .sort(),
  )("round trip: %s survives native, text, and back", (file) => {
    const original = JSON.parse(readFileSync(new URL(file, contractFixtures), "utf8")) as Workout;
    const text = renderWorkoutText(original);
    const back = convertIntervalsEvent(
      { id: 1, start_date_local: "2026-11-20T00:00:00", name: original.name, description: text },
      original.id,
    );
    expect(back.ok, JSON.stringify(back)).toBe(true);
    if (!back.ok) return;
    // The description is notes: it is written but not read back.
    const { description: _description, ...expected } = original;
    expect(back.workout).toEqual(expected);
  });
});

describe("workoutToEventPayload", () => {
  it("makes the calendar event for a native workout on a day", () => {
    const workout = JSON.parse(
      readFileSync(new URL("minimal-free-ride.json", contractFixtures), "utf8"),
    ) as Workout;
    expect(workoutToEventPayload(workout, "2026-11-20")).toEqual({
      category: "WORKOUT",
      start_date_local: "2026-11-20T00:00:00",
      type: "Ride",
      name: "Free ride, 60 minutes",
      description: "- 1h freeride",
      indoor: true,
    });
  });

  it("refuses a day that is not a date", () => {
    const workout: Workout = {
      schema: 1,
      id: "x",
      name: "x",
      steps: [{ type: "free", duration: 60 }],
    };
    expect(() => workoutToEventPayload(workout, "2026-02-30")).toThrow(/calendar date/);
  });
});
