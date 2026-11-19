import {
  type RideEvent,
  type RideRecord,
  type RideSample,
  validateWorkout,
  type Workout,
  workoutHash,
} from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { buildRideFacts } from "../src/insights/facts.ts";
import { plannedStats } from "../src/insights/planned.ts";
import {
  DEFAULT_TOPIC_SETTINGS,
  findTopic,
  TOPIC_RULES,
  type TopicContext,
} from "../src/insights/topics.ts";
import type { IntervalsEvent } from "../src/intervals/client.ts";
import { convertIntervalsEvent } from "../src/intervals/workout-convert.ts";
import { parseHrNotes, renderWorkoutText } from "../src/intervals/workout-text.ts";
import {
  NO_THRESHOLD_MESSAGE,
  resolveHeartRateTargets,
  thresholdHeartRate,
  usesThresholdFraction,
} from "../src/plan/heart-rate.ts";
import { plannedLoad, workoutOutline } from "../src/plan/summary.ts";
import { describeSteps } from "../src/plan/workout-summary.ts";
import { flattenWorkout, rideCompliance, stallGuardSummary } from "../src/ride-stats.ts";
import { sessionOutline } from "../src/strava/description.ts";
import { rideInput } from "./helpers/insights.ts";

// Stage A1b of v0.4.0: the heart-rate step (spec §11.2) and the stall guard's events (spec §11.1) on the service side. Every
// workout and ride here is synthetic.

const zone2: Workout = {
  schema: 1,
  id: "icu-7001",
  name: "Zone 2 by heart rate",
  steps: [
    { type: "ramp", duration: 600, from: 0.45, to: 0.6, label: "Warm-up" },
    {
      type: "hr",
      duration: 2400,
      target_bpm: 135,
      band: 4,
      power_min: 80,
      power_max: 220,
      label: "Zone 2 by heart rate",
    },
    { type: "steady", duration: 300, target: 0.4, label: "Cool-down" },
  ],
};

const tempo: Workout = {
  schema: 1,
  id: "icu-7002",
  name: "Tempo by heart rate",
  steps: [
    {
      type: "repeat",
      count: 2,
      steps: [
        { type: "hr", duration: 900, target_lthr: 0.88, power_min: 100, power_max: 260 },
        { type: "free", duration: 180, label: "Easy" },
      ],
    },
  ],
};

describe("a heart-rate step in intervals.icu's text", () => {
  it("is written as a step with its power limits as a range, and a note that carries the target", () => {
    expect(renderWorkoutText(zone2)).toBe(
      [
        "- Warm-up 10m ramp 45-60%",
        "- Zone by heart rate 40m 80-220w",
        "- Cool-down 5m 40%",
        "",
        "Overdrive heart-rate step 2: 135 bpm, band 4, 80-220 W, label Zone 2 by heart rate",
      ].join("\n"),
    );
  });

  it("numbers the notes by the step lines, a repeat's body once, and writes a threshold fraction as a percent", () => {
    expect(renderWorkoutText(tempo)).toBe(
      [
        "2x",
        "- 15m 100-260w",
        "- Easy 3m freeride",
        "",
        "Overdrive heart-rate step 1: 88% of threshold heart rate, 100-260 W",
      ].join("\n"),
    );
  });

  it("reads the notes back, and leaves the lines it does not know alone", () => {
    const notes = parseHrNotes(
      [
        "A plain note about the day.",
        "- 15m 100-260w",
        "Overdrive heart-rate step 3: 120 bpm, band 6, 90-200 W, label Z2, then easy",
        "Overdrive heart-rate step 4: 90% of threshold heart rate, 90-200 W",
        "Overdrive heart-rate step x: nonsense",
        "Overdrive heart-rate step 3: 150 bpm, 90-200 W",
      ].join("\n"),
    );
    expect([...notes.keys()]).toEqual([3, 4]);
    expect(notes.get(3)).toEqual({
      step: 3,
      targetBpm: 120,
      targetLthr: null,
      band: 6,
      powerMin: 90,
      powerMax: 200,
      label: "Z2, then easy",
    });
    expect(notes.get(4)).toMatchObject({
      targetBpm: null,
      targetLthr: 0.9,
      band: null,
      label: null,
    });
    expect(parseHrNotes(null).size).toBe(0);
  });

  const roundTrip = (workout: Workout, extra: Partial<IntervalsEvent> = {}) =>
    convertIntervalsEvent(
      {
        id: 9001,
        start_date_local: "2026-12-01T00:00:00",
        category: "WORKOUT",
        type: "Ride",
        name: workout.name,
        description: renderWorkoutText(workout),
        ...extra,
      } as IntervalsEvent,
      workout.id,
    );

  it("comes back from the calendar as the same workout, label, band and limits included", () => {
    for (const workout of [zone2, tempo]) {
      const back = roundTrip(workout);
      expect(back).toMatchObject({ ok: true });
      if (back.ok) expect(back.workout.steps).toEqual(workout.steps);
    }
  });

  it("does not use what intervals.icu parsed when the notes are there: it cannot hold the step", () => {
    const back = roundTrip(zone2, {
      workout_doc: {
        steps: [
          { duration: 600, power: { start: 45, end: 60, units: "%ftp" }, ramp: true },
          { duration: 2400, power: { start: 80, end: 220, units: "w" } },
          { duration: 300, power: { value: 40, units: "%ftp" } },
        ],
      },
    } as Partial<IntervalsEvent>);
    expect(back).toMatchObject({ ok: true });
    if (back.ok) expect(back.workout.steps[1]).toMatchObject({ type: "hr", target_bpm: 135 });
  });

  it("reads a step that was edited in the calendar as the step it now is, and says so", () => {
    // Someone changed the power range in intervals.icu: the note no longer describes this step.
    const text = renderWorkoutText(zone2).replace("80-220w", "100-150w");
    const back = convertIntervalsEvent(
      {
        id: 9002,
        start_date_local: "2026-12-01T00:00:00",
        category: "WORKOUT",
        type: "Ride",
        name: "Edited",
        description: text,
        icu_ftp: 250,
      } as IntervalsEvent,
      "icu-9002",
    );
    expect(back).toMatchObject({ ok: true });
    if (back.ok) {
      expect(back.workout.steps[1]?.type).toBe("steady");
      expect(back.notes.join(" ")).toMatch(/heart-rate note for step 2 does not match/);
    }
  });

  it("is valid in the contract after the way back", () => {
    const back = roundTrip(tempo);
    expect(back.ok && validateWorkout(back.workout).ok).toBe(true);
  });
});

describe("words for a heart-rate step", () => {
  it("is said in plain words in a sentence, an outline and a Strava description", () => {
    expect(describeSteps(zone2)).toBe(
      "10 min warm-up (45% to 60% FTP), 40 min zone 2 by heart rate by heart rate at 135 bpm (80 to 220 W), 5 min cool-down at 40% FTP",
    );
    expect(describeSteps(tempo)).toBe(
      "2 x (15 min by heart rate at 88% of threshold heart rate (100 to 260 W), 3 min easy)",
    );
    expect(workoutOutline(zone2)).toBe("10m 45→60%, 40m hr 135bpm (80-220W), 5m 40%");
    expect(workoutOutline(tempo)).toBe("2×(15m hr 88%LTHR (100-260W) + 3m free)");
    expect(sessionOutline(zone2)).toContain("40 min by heart rate at 135 bpm, 80 to 220 W");
  });

  it("has no power to plan: the load leaves the step out and says how long it is", () => {
    const load = plannedLoad(zone2);
    expect(load.heartRateSeconds).toBe(2400);
    expect(load.freeSeconds).toBe(0);
    const stats = plannedStats(zone2);
    expect(stats.heartRateSeconds).toBe(2400);
    expect(stats.seconds).toBe(3300);
    expect(stats.intensityFactor).not.toBeNull();
    expect(
      plannedLoad({ ...zone2, steps: [zone2.steps[0] as never] }).heartRateSeconds,
    ).toBeUndefined();
  });
});

describe("the threshold heart rate", () => {
  it("is the profile's own, else the estimate from the rides, else unknown", () => {
    expect(thresholdHeartRate({ thresholdHeartRate: 168, estimatedThresholdHeartRate: 160 })).toBe(
      168,
    );
    expect(thresholdHeartRate({ thresholdHeartRate: null, estimatedThresholdHeartRate: 160 })).toBe(
      160,
    );
    expect(
      thresholdHeartRate({ thresholdHeartRate: null, estimatedThresholdHeartRate: null }),
    ).toBeNull();
    expect(NO_THRESHOLD_MESSAGE).toMatch(/threshold heart rate/);
  });

  it("is resolved to beats for the tablet, in a repeat too, and the original is not touched", () => {
    expect(usesThresholdFraction(tempo)).toBe(true);
    expect(usesThresholdFraction(zone2)).toBe(false);
    const resolved = resolveHeartRateTargets(tempo, 170);
    const body = resolved.steps[0];
    expect(body?.type === "repeat" && body.steps[0]).toMatchObject({ type: "hr", target_bpm: 150 });
    expect(JSON.stringify(resolved)).not.toContain("target_lthr");
    expect(usesThresholdFraction(tempo)).toBe(true);
    expect(validateWorkout(resolved).ok).toBe(true);
  });

  it("leaves a workout alone when nothing needs resolving or no threshold is known", () => {
    expect(resolveHeartRateTargets(zone2, 170)).toBe(zone2);
    expect(resolveHeartRateTargets(tempo, null)).toBe(tempo);
  });

  it("keeps a resolved target inside what the contract accepts", () => {
    const high = resolveHeartRateTargets(tempo, 230);
    const body = high.steps[0];
    expect(body?.type === "repeat" && body.steps[0]).toMatchObject({ target_bpm: 202 });
    expect(validateWorkout(resolveHeartRateTargets(tempo, 40)).ok).toBe(true);
  });
});

describe("the hash of a session with a heart-rate step", () => {
  it("does not change with the band, the limits or the label, and does with the target", () => {
    const other: Workout = {
      ...zone2,
      steps: zone2.steps.map((step) =>
        step.type === "hr"
          ? { ...step, band: 7, power_min: 100, power_max: 300, label: "Base" }
          : step,
      ),
    };
    expect(workoutHash(other)).toBe(workoutHash(zone2));
    const harder: Workout = {
      ...zone2,
      steps: zone2.steps.map((step) => (step.type === "hr" ? { ...step, target_bpm: 140 } : step)),
    };
    expect(workoutHash(harder)).not.toBe(workoutHash(zone2));
  });
});

// ---- Compliance and the stall guard's events ---------------------------------------------------------------------

/** A ride of the zone 2 workout: step 0 (warm-up) 60 s, then the heart-rate step, whose first `ramp` seconds are the start ramp. */
function hrRide(options: {
  seconds: number;
  heartRate: (second: number) => number | null;
  ramp?: number;
  target?: (second: number) => number;
  paused?: (second: number) => boolean;
  events?: RideEvent[];
}): RideRecord {
  const ramp = options.ramp ?? 0;
  const samples: RideSample[] = Array.from({ length: options.seconds }, (_, i) => {
    const second = i + 1;
    const heartRate = options.heartRate(second);
    const sample: RideSample = {
      timeOffsetSeconds: second,
      power: 150,
      cadence: 90,
      stepIndex: 1,
      distanceMeters: second * 8,
    };
    if (heartRate !== null) sample.heartRate = heartRate;
    if (second > ramp) sample.targetHeartRate = options.target?.(second) ?? 135;
    if (options.paused?.(second) === true) sample.paused = true;
    return sample;
  });
  return {
    schema: 1,
    ride: { id: "ride-1", startEpochMillis: 1_794_996_000_000, ftp: 250, workoutId: "icu-7001" },
    samples,
    rrIntervals: [],
    laps: [{ stepIndex: 1, startSeconds: 0, endSeconds: options.seconds }],
    summary: {
      durationSeconds: options.seconds,
      movingSeconds: options.seconds,
      distanceMeters: 0,
      workKilojoules: 0,
    },
    ...(options.events === undefined ? {} : { events: options.events }),
  };
}

describe("compliance of a heart-rate step: the time inside the band", () => {
  it("counts the judged seconds within the band either side of the target, edges included", () => {
    // 100 s: 10 s of start ramp (not judged); then 45 s at 135, 20 s at 139 (edge of band 4), 25 s at 140.
    const record = hrRide({
      seconds: 100,
      ramp: 10,
      heartRate: (s) => (s <= 55 ? 135 : s <= 75 ? 139 : 140),
    });
    const result = rideCompliance(record, zone2);
    expect("problem" in result).toBe(false);
    if ("problem" in result) return;
    const step = result.steps[1];
    expect(step?.type).toBe("hr");
    expect(step?.plannedWatts).toBeNull();
    expect(step?.heartRate).toMatchObject({
      targetBpm: 135,
      plannedTargetBpm: 135,
      band: 4,
      powerMin: 80,
      powerMax: 220,
      judgedSeconds: 90,
      secondsInBand: 65,
      inBandPercent: 72.2,
    });
    expect(step?.heartRate?.averageHeartRate).toBeCloseTo(137.3, 1);
    // The workout's power totals do not include the heart-rate step, and do not turn into NaN.
    expect(result.targetSteps.plannedWatts).not.toBeNaN();
    expect(result.steps[0]?.heartRate).toBeNull();
  });

  it("does not judge a paused second, one without a heart rate or one in the start ramp", () => {
    const record = hrRide({
      seconds: 60,
      ramp: 20,
      heartRate: (s) => (s > 50 ? null : 135),
      paused: (s) => s > 30 && s <= 40,
    });
    const step = rideCompliance(record, zone2);
    if ("problem" in step) throw new Error(step.problem);
    // seconds 21..30 (10) and 41..50 (10) are judged; 31..40 are paused, 51..60 have no heart rate.
    expect(step.steps[1]?.heartRate).toMatchObject({
      judgedSeconds: 20,
      secondsInBand: 20,
      inBandPercent: 100,
    });
    expect(step.steps[1]?.actualSeconds).toBe(50);
  });

  it("follows the target the rider moved with the gear buttons", () => {
    // The planned target is 135; from second 31 the rider asked for 129 and rode at 130.
    const record = hrRide({
      seconds: 60,
      heartRate: (s) => (s <= 30 ? 136 : 130),
      target: (s) => (s <= 30 ? 135 : 129),
    });
    const step = rideCompliance(record, zone2);
    if ("problem" in step) throw new Error(step.problem);
    expect(step.steps[1]?.heartRate).toMatchObject({
      targetBpm: 129,
      plannedTargetBpm: 135,
      judgedSeconds: 60,
      secondsInBand: 60,
    });
  });

  it("says nothing was judged when the whole step was start ramp or strap loss", () => {
    const record = hrRide({ seconds: 30, ramp: 30, heartRate: () => 120 });
    const step = rideCompliance(record, zone2);
    if ("problem" in step) throw new Error(step.problem);
    expect(step.steps[1]?.heartRate).toMatchObject({
      targetBpm: null,
      judgedSeconds: 0,
      secondsInBand: 0,
      inBandPercent: null,
      averageHeartRate: null,
    });
  });

  it("flattens a heart-rate step with its numbers, and the band defaults to 5", () => {
    const flat = flattenWorkout(tempo);
    expect(flat.map((s) => s.type)).toEqual(["hr", "free", "hr", "free"]);
    expect(flat[0]?.heartRate).toEqual({
      targetBpm: null,
      targetLthr: 0.88,
      band: 5,
      powerMin: 100,
      powerMax: 260,
    });
    expect(flat[0]?.fromFraction).toBeNull();
    expect(flat[0]?.label).toBe("Heart rate");
  });
});

describe("what the record's events say about the stall guard", () => {
  const events: RideEvent[] = [
    { type: "stall-guard", timeOffsetSeconds: 30, stepIndex: 1 },
    { type: "stall-guard", timeOffsetSeconds: 75, stepIndex: 1 },
    { type: "stall-guard", timeOffsetSeconds: 120, stepIndex: 2 },
  ];

  it("counts the activations, by step and with the label, and gives the minutes", () => {
    const record = hrRide({ seconds: 150, heartRate: () => 135, events });
    expect(stallGuardSummary(record, zone2)).toEqual({
      activations: 3,
      steps: [
        { step: 2, label: "Zone 2 by heart rate", activations: 2 },
        { step: 3, label: "Cool-down", activations: 1 },
      ],
      minutes: [0.5, 1.3, 2],
    });
    expect(stallGuardSummary(record, null).steps[0]?.label).toBeNull();
  });

  it("is zero for a record without events, which is every record from before they existed", () => {
    const record = hrRide({ seconds: 20, heartRate: () => 135 });
    expect(stallGuardSummary(record, zone2)).toEqual({ activations: 0, steps: [], minutes: [] });
  });
});

describe("the ride facts and the topic for a stall guard that fires repeatedly", () => {
  const guard = (activations: number) => ({
    activations,
    steps: [{ step: 2, label: "Zone 2 by heart rate", activations }],
    minutes: Array.from({ length: activations }, (_, i) => 10 + i * 5),
  });

  const context = (activations: number): TopicContext => ({
    ride: buildRideFacts({ ...rideInput(), stallGuard: guard(activations) }),
    driftAlertsLast28Days: 0,
    settings: DEFAULT_TOPIC_SETTINGS,
  });

  it("puts the guard in the facts, every number quotable, and only when it fired", () => {
    const quiet = buildRideFacts(rideInput());
    expect(quiet.stallGuard).toBeUndefined();
    expect("stallGuard" in quiet).toBe(false);
    const withGuard = buildRideFacts({ ...rideInput(), stallGuard: guard(4) });
    expect(withGuard.stallGuard).toEqual({
      activations: 4,
      steps: [{ step: 2, label: "Zone 2 by heart rate", activations: 4 }],
      atMinutes: [10, 15, 20, 25],
    });
    expect(withGuard.quotable.some((q) => q.label.includes("stall guard") && q.value === 4)).toBe(
      true,
    );
    expect(buildRideFacts({ ...rideInput(), stallGuard: guard(0) }).stallGuard).toBeUndefined();
  });

  it("is a topic from 3 activations in one ride, with a question the rider can paste", () => {
    expect(findTopic(context(2))).toBeNull();
    const found = findTopic(context(3));
    expect(found?.rule).toBe("stall-guard");
    expect(found?.text).toBe(
      "The ERG stall guard cut the target 3 times in this ride: cadence fell below its floor and the trainer was held at 70% until you were pedalling again.",
    );
    expect(found?.openingQuestion).toBe(
      "The stall guard cut my target 3 times in Sweet spot 3x10. Is the target too high for me right now, or is something else going on?",
    );
  });

  it("comes after the rows that were there before it, and is no longer a hook", () => {
    expect(TOPIC_RULES.find((rule) => rule.id === "stall-guard")?.available).toBe(true);
    // A ride cut short that also fired the guard is the cut: first match wins.
    const cut = buildRideFacts({ ...rideInput({ seconds: 2_100 }), stallGuard: guard(5) });
    expect(
      findTopic({ ride: cut, driftAlertsLast28Days: 0, settings: DEFAULT_TOPIC_SETTINGS })?.rule,
    ).toBe("cut-short");
  });

  it("holds a heart-rate step's compliance in the facts, and no planned load for a workout that has one", () => {
    const record = hrRide({
      seconds: 3300,
      ramp: 180,
      heartRate: (s) => (s % 10 === 0 ? 141 : 135),
    });
    const compliance = rideCompliance(record, zone2);
    const facts = buildRideFacts({
      ...rideInput(),
      name: zone2.name,
      plan: { workout: zone2, compliance },
    });
    expect(facts.plan?.heartRateSteps).toHaveLength(1);
    expect(facts.plan?.heartRateSteps?.[0]).toMatchObject({
      step: 2,
      targetBpm: 135,
      band: 4,
      powerMin: 80,
      powerMax: 220,
      inBandPercent: 90,
    });
    // No fixed power, so the step is not among those compared against a power target, and the plan has no load to compare with.
    expect(facts.plan?.steps.map((s) => s.step)).not.toContain(2);
    expect(facts.plan?.plannedLoad).toBeNull();
    expect(facts.plan?.loadVsPlanPercent).toBeNull();
    const allowed = new Set(facts.quotable.map((q) => q.value));
    expect(allowed.has(90)).toBe(true);
  });
});
