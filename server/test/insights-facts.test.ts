import { describe, expect, it } from "vitest";
import { canonicalJson } from "../src/hash.ts";
import {
  buildDeviationFacts,
  buildDriftFacts,
  buildMorningFacts,
  buildRideFacts,
  buildWeekFacts,
  clockOf,
  type Facts,
  formWord,
} from "../src/insights/facts.ts";
import { templateText } from "../src/insights/template.ts";
import { createValidator, suppliedViolations } from "../src/insights/validator.ts";
import {
  deviationInput,
  driftInput,
  morningInput,
  rideInput,
  weekInput,
} from "./helpers/insights.ts";

/**
 * The facts builders are pure and deterministic: the same inputs give the same facts byte for byte, and the golden files under
 * `test/fixtures/insights-facts-*.json` are what they make of the synthetic inputs of `helpers/insights.ts` (ADR 0025). Every number in the facts is
 * listed as quotable, and the template's own text passes the validator a model's text must pass.
 */

const golden = (name: string, facts: Facts) =>
  expect(`${JSON.stringify(facts, null, 2)}\n`).toMatchFileSnapshot(
    `./fixtures/insights-facts-${name}.json`,
  );

const validator = createValidator();

/** Every number leaf of the facts except the schema version. */
function numbersOf(node: unknown, path = ""): { path: string; value: number }[] {
  if (typeof node === "number") return [{ path, value: node }];
  if (Array.isArray(node)) return node.flatMap((item, i) => numbersOf(item, `${path}[${i}]`));
  if (typeof node === "object" && node !== null) {
    return Object.entries(node).flatMap(([key, child]) =>
      key === "quotable" || key === "schema" ? [] : numbersOf(child, `${path}.${key}`),
    );
  }
  return [];
}

function cases(): [string, Facts][] {
  return [
    ["morning-ready", buildMorningFacts(morningInput("ready"))],
    ["morning-caution", buildMorningFacts(morningInput("caution"))],
    ["morning-rest", buildMorningFacts(morningInput("rest"))],
    ["morning-learning", buildMorningFacts(morningInput("learning"))],
    ["morning-no-data", buildMorningFacts(morningInput("noData", { planned: "none" }))],
    ["morning-yesterday-verdict", buildMorningFacts(morningInput("yesterday"))],
    ["morning-no-plan", buildMorningFacts(morningInput("ready", { planned: "none" }))],
    [
      "morning-done",
      buildMorningFacts(morningInput("caution", { planned: "done", withForm: false })),
    ],
    ["ride-planned", buildRideFacts(rideInput())],
    ["ride-cut-short", buildRideFacts(rideInput({ seconds: 2_100 }))],
    ["ride-above-target", buildRideFacts(rideInput({ watts: 245, drift: true }))],
    ["ride-records", buildRideFacts(rideInput({ withRecords: true, wPrimeLow: true }))],
    ["ride-free", buildRideFacts(rideInput({ free: true }))],
    ["week", buildWeekFacts(weekInput())],
    ["week-quiet", buildWeekFacts(weekInput({ quiet: true }))],
    ["deviation", buildDeviationFacts(deviationInput())],
    ["drift-missed-and-low", buildDriftFacts(driftInput())],
    ["drift-load-above", buildDriftFacts(driftInput({ reasons: ["load-above"] }))],
  ];
}

describe("golden facts", () => {
  for (const [name, facts] of cases()) {
    it(`${name}: matches its golden file`, async () => {
      await golden(name, facts);
    });
  }
});

describe("every case", () => {
  for (const [name, facts] of cases()) {
    describe(name, () => {
      it("is deterministic and JSON all the way down", () => {
        const again = cases().find(([n]) => n === name)?.[1];
        expect(canonicalJson(again)).toBe(canonicalJson(facts));
        expect(JSON.parse(JSON.stringify(facts))).toEqual(facts);
      });

      it("lists every number it holds as quotable, with a label and a unit", () => {
        const allowed = new Set(facts.quotable.map((q) => q.value));
        for (const found of numbersOf(facts)) {
          expect(allowed.has(found.value), `${found.path} = ${found.value}`).toBe(true);
        }
        for (const q of facts.quotable) {
          expect(q.label.length).toBeGreaterThan(0);
          expect(q.unit.length).toBeGreaterThan(0);
          expect(Number.isFinite(q.value)).toBe(true);
        }
      });

      it("has a template text that passes the validator and is not empty", () => {
        const text = templateText(facts);
        expect(text.length).toBeGreaterThan(20);
        expect(text.length).toBeLessThanOrEqual(1500);
        const check = validator.check(text, facts);
        expect(check.violations, text).toEqual([]);
      });

      it("has a template text with no markdown and no exclamation mark", () => {
        const text = templateText(facts);
        expect(text).not.toMatch(/[*_`#]|!/);
      });
    });
  }
});

describe("what the morning facts say", () => {
  it("adjusts the workout only when this morning's state is caution or rest", () => {
    expect(buildMorningFacts(morningInput("ready")).adjustment).toBe("none");
    expect(buildMorningFacts(morningInput("caution")).adjustment).toBe("keep-hard-parts-short");
    expect(buildMorningFacts(morningInput("rest")).adjustment).toBe("easy-or-off");
    // A verdict from yesterday is not this morning's.
    expect(buildMorningFacts(morningInput("yesterday")).adjustment).toBe("none");
    expect(buildMorningFacts(morningInput("learning")).adjustment).toBe("none");
  });

  it("converts sleep to hours and the bedtime to a clock time, and keeps the rest in the unit it has", () => {
    const facts = buildMorningFacts(morningInput("rest"));
    const signals = facts.recovery?.signals ?? [];
    const sleep = signals.find((s) => s.metric === "sleepSeconds");
    expect(sleep).toMatchObject({
      value: 5.7,
      unit: "h",
      usualLow: 7,
      usualHigh: 8,
      position: "below",
    });
    const bedtime = signals.find((s) => s.metric === "bedtimeMinutes");
    expect(bedtime).toMatchObject({ value: null, clock: "00:40", usualClock: "22:45 to 23:45" });
    expect(signals.find((s) => s.metric === "respiratoryRate")).toMatchObject({
      value: 15.9,
      unit: "/min",
    });
  });

  it("computes the planned workout's length, load and intensity from its steps, at the current FTP", () => {
    const planned = buildMorningFacts(morningInput("ready")).today.planned[0];
    // 10 min at 60%, 3 x (10 min at 88%, 5 min at 50%), 5 min free: 60 min; load = sum(seconds x fraction^2) / 36 = 51.
    expect(planned).toMatchObject({
      minutes: 60,
      plannedLoad: 51,
      intensityFactor: 0.75,
      topStepWatts: 220,
      done: false,
    });
  });

  it("names the form word at the design's thresholds", () => {
    expect(formWord(5.1)).toBe("fresh");
    expect(formWord(5)).toBe("neutral");
    expect(formWord(-10)).toBe("neutral");
    expect(formWord(-10.1)).toBe("training hard");
    expect(formWord(-30)).toBe("training hard");
    expect(formWord(-30.1)).toBe("very fatigued");
  });

  it("writes the change in fitness from the rounded figures, so the reader can check the subtraction", () => {
    const facts = buildMorningFacts(morningInput("ready"));
    expect(facts.form).toMatchObject({ fitness: 62, fatigue: 70, form: -9, fitnessChange7Days: 3 });
  });

  it("writes a time of day for a clock position before and after midnight", () => {
    expect(clockOf(-45)).toBe("23:15");
    expect(clockOf(40)).toBe("00:40");
    expect(clockOf(-720)).toBe("12:00");
  });
});

describe("what the ride facts say", () => {
  it("compares the ride with the plan step by step, from the ride's own FTP", () => {
    const facts = buildRideFacts(rideInput({ watts: 245 }));
    expect(facts.plan).toMatchObject({
      workoutName: "Sweet spot 3x10",
      plannedMinutes: 60,
      completionPercent: 100,
      stepsCompared: 7,
      stepsOnTarget: 4,
      stepsAboveTarget: 3,
      stepsBelowTarget: 0,
      onTargetTolerancePercent: 5,
    });
    const steps = facts.plan?.steps ?? [];
    expect(steps.filter((s) => s.plannedWatts === 220).map((s) => s.actualWatts)).toEqual([
      245, 245, 245,
    ]);
  });

  it("calls a record only against earlier rides, and never on a duration nothing came before", () => {
    const facts = buildRideFacts(rideInput({ withRecords: true }));
    const byDuration = Object.fromEntries(facts.efforts.map((e) => [e.duration, e]));
    expect(byDuration["5 s"]).toMatchObject({
      power: 655,
      previousBest: 640,
      newBest: true,
      improvementWatts: 15,
    });
    expect(byDuration["1 min"]).toMatchObject({
      power: 405,
      previousBest: 410,
      newBest: false,
      improvementWatts: null,
    });
    expect(byDuration["20 min"]).toMatchObject({
      newBest: true,
      improvementWatts: 6,
      priorRides: 8,
    });
    // No 60-minute power in the ride: nothing is said about it.
    expect(byDuration["60 min"]).toBeUndefined();
  });

  it("says a free ride has no plan, and the aerobic drift only when there is one", () => {
    const free = buildRideFacts(rideInput({ free: true }));
    expect(free.plan).toBeNull();
    expect(free.drift).toBeNull();
    expect(buildRideFacts(rideInput({ drift: true })).drift).toEqual({ percent: 3.5 });
  });

  it("calls W' low below 30% of its capacity", () => {
    expect(buildRideFacts(rideInput({ wPrimeLow: true })).wPrime).toMatchObject({
      capacityKj: 20,
      lowestKj: 4.2,
      lowestPercentOfCapacity: 21,
      atMinute: 31,
      low: true,
    });
    expect(buildRideFacts(rideInput()).wPrime?.low).toBe(false);
  });
});

describe("what the other facts say", () => {
  it("counts the week against the week before and keeps the changes as differences of the rounded figures", () => {
    const facts = buildWeekFacts(weekInput());
    expect(facts.load).toMatchObject({
      week: 212,
      previousWeek: 189,
      changeVsPreviousWeek: 23,
      changePercent: 12,
      planned: 240,
    });
    expect(facts.fitnessChangeOverWeek).toBe(3);
    expect(facts.sessions).toMatchObject({ planned: 4, done: 3, minutes: 299 });
    expect(facts.recoveryDays).toEqual({ ready: 4, caution: 2, rest: 0, noVerdict: 1 });
  });

  it("names the metrics of a deviation alert, longest streak first, each with what it usually means", () => {
    const facts = buildDeviationFacts(deviationInput());
    expect(facts.signals.map((s) => [s.metric, s.daysOutsideInARow])).toEqual([
      ["restingHr", 3],
      ["sleepSeconds", 4],
    ]);
    expect(facts.signals[0]?.reading).toMatch(/usually goes with/);
  });

  it("gives the drift's reasons and the load against the plan in percent", () => {
    const facts = buildDriftFacts(driftInput());
    expect(facts.reasons).toEqual(["missed", "load-below"]);
    expect(facts.load).toEqual({ planned: 240, ridden: 119, differencePercent: -51 });
    expect(facts.missed?.count).toBe(2);
  });
});

describe("the quotable numbers", () => {
  it("allow the number as written and its rounding, and nothing it was not given", () => {
    const facts = buildMorningFacts(morningInput("caution"));
    expect(suppliedViolations("Resting heart rate was 58 bpm.", facts)).toEqual([]);
    expect(suppliedViolations("Resting heart rate was 57 bpm.", facts)).toEqual([
      { kind: "number", token: "57" },
    ]);
    expect(suppliedViolations("Planned load 77 for 60 minutes, or 1 h 00 min.", facts)).toEqual([
      { kind: "number", token: "77" },
    ]);
  });
});
