import type { RecoveryFact } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  buildDeviationFacts,
  buildDriftFacts,
  buildMorningFacts,
  buildRideFacts,
  buildWeekFacts,
  type DriftInput,
  type Facts,
} from "../src/insights/facts.ts";
import { READINGS } from "../src/insights/readings.ts";
import { templateText } from "../src/insights/template.ts";
import { createValidator } from "../src/insights/validator.ts";
import {
  deviationInput,
  driftInput,
  fact,
  morningInput,
  RECOVERY,
  rideInput,
  weekInput,
} from "./helpers/insights.ts";

/**
 * The template is what is stored when no model is configured or its text is rejected, so it must pass the very check a model's text
 * must pass, for every facts document it can be given: a sweep over the situations the builders have inputs for (ADR 0025).
 */

const validator = createValidator();

function expectClean(label: string, facts: Facts): void {
  const text = templateText(facts);
  const check = validator.check(text, facts);
  expect(check.violations, `${label}: ${text}`).toEqual([]);
  expect(text.length, label).toBeGreaterThan(10);
  expect(text, label).not.toMatch(/\bundefined\b|\bnull\b|NaN|\[object/);
}

describe("every template text passes the validator", () => {
  it("for the morning, in every state, with and without a plan, a form and a last ride", () => {
    let count = 0;
    for (const state of Object.keys(RECOVERY) as (keyof typeof RECOVERY)[]) {
      for (const planned of ["sweet-spot", "done", "none"] as const) {
        for (const withForm of [true, false]) {
          for (const withLastRide of [true, false]) {
            const input = morningInput(state, { planned, withForm });
            expectClean(
              `morning ${state} ${planned} form=${withForm}`,
              buildMorningFacts(withLastRide ? input : { ...input, lastRide: null }),
            );
            count++;
          }
        }
      }
    }
    // The wellness data could not be read at all.
    expectClean(
      "morning without recovery",
      buildMorningFacts({ ...morningInput("ready"), recovery: null }),
    );
    expect(count).toBe(72);
  });

  it("for a ride of every length and effort, with every extra", () => {
    let count = 0;
    for (const seconds of [90, 900, 1500, 2100, 2700, 3000, 3600]) {
      for (const watts of [150, 190, 220, 235, 250, 300]) {
        for (const free of [false, true]) {
          for (const withRecords of [false, true]) {
            for (const drift of [false, true]) {
              for (const wPrimeLow of [false, true]) {
                expectClean(
                  `ride ${seconds}s ${watts}W free=${free} records=${withRecords} drift=${drift} w'=${wPrimeLow}`,
                  buildRideFacts(
                    rideInput({ seconds, watts, free, withRecords, drift, wPrimeLow }),
                  ),
                );
                count++;
              }
            }
          }
        }
      }
    }
    expect(count).toBe(7 * 6 * 2 * 2 * 2 * 2);
  });

  it("for a ride with no form, no power curve and no averages", () => {
    const base = rideInput();
    expectClean(
      "bare ride",
      buildRideFacts({
        ...base,
        form: null,
        curve: [],
        priorBests: [],
        averagePower: null,
        normalizedPower: null,
        intensityFactor: null,
        load: null,
        wPrime: null,
      }),
    );
  });

  it("for the week, busy and quiet, up and down, with and without a previous week and a form", () => {
    for (const quiet of [false, true]) {
      for (const previousLoad of [null, 100, 212.4, 400]) {
        for (const form of [true, false]) {
          for (const rest of [0, 1, 3]) {
            const base = weekInput({ quiet });
            expectClean(
              `week quiet=${quiet} previous=${previousLoad} form=${form} rest=${rest}`,
              buildWeekFacts({
                ...base,
                previousLoad,
                form: form ? base.form : null,
                recoveryDays: { ready: 7 - rest, caution: 0, rest, noVerdict: 0 },
                missed: rest === 1 ? [] : base.missed,
                newBests: rest === 3 ? [] : base.newBests,
              }),
            );
          }
        }
      }
    }
  });

  it("for a deviation alert on each scored metric and on all of them together", () => {
    const metrics: RecoveryFact["metric"][] = [
      "restingHr",
      "sleepSeconds",
      "bedtimeMinutes",
      "respiratoryRate",
      "spo2",
      "sleepingHr",
      "hrvRmssd",
    ];
    const rising: Record<string, Partial<RecoveryFact>> = {
      restingHr: { value: 59, deviation: "above", streak: 3 },
      sleepSeconds: { value: 21000, deviation: "below", streak: 4 },
      bedtimeMinutes: { value: 70, deviation: "above", streak: 3 },
      respiratoryRate: { value: 15.7, deviation: "above", streak: 5 },
      spo2: { value: 93.4, deviation: "below", streak: 3 },
      sleepingHr: { value: 54, deviation: "above", streak: 3 },
      hrvRmssd: { value: 41, deviation: "below", streak: 6 },
    };
    const signal = (metric: RecoveryFact["metric"]) => ({
      fact: fact(metric, rising[metric] ?? {}),
      reading: READINGS[metric].usually,
      trainingHint: READINGS[metric].hint,
    });
    for (const metric of metrics) {
      for (const planned of [true, false]) {
        const base = deviationInput();
        expectClean(
          `deviation ${metric} planned=${planned}`,
          buildDeviationFacts({
            ...base,
            signals: [signal(metric)],
            planned: planned ? base.planned : [],
          }),
        );
      }
    }
    expectClean(
      "deviation all",
      buildDeviationFacts({ ...deviationInput(), signals: metrics.map(signal) }),
    );
  });

  it("for plan drift, whichever reasons it names", () => {
    const reasons: DriftInput["reasons"][] = [
      ["missed"],
      ["load-above"],
      ["load-below"],
      ["missed", "load-below"],
      ["missed", "load-above"],
    ];
    for (const set of reasons)
      expectClean(`drift ${set.join("+")}`, buildDriftFacts(driftInput({ reasons: set })));
  });

  it("and the readings of the alerts name no cause, no condition and no number", () => {
    for (const [metric, reading] of Object.entries(READINGS)) {
      const text = `${reading.usually} ${reading.hint}`;
      expect(text, metric).not.toMatch(/\d/);
      expect(
        validator
          .check(text, buildDeviationFacts(deviationInput()))
          .violations.filter((v) => v.kind === "deny"),
        metric,
      ).toEqual([]);
    }
  });
});
