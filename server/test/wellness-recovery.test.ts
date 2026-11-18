import type { RecoveryRule, RecoveryState, WellnessMetric } from "@overdrive/contracts";
import { validateRecoveryResponse } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { DEFAULT_WELLNESS_SETTINGS } from "../src/wellness/metrics.ts";
import { evaluateRecovery, GUIDANCE, rollingHrv } from "../src/wellness/recovery.ts";
import type { DailyTable } from "../src/wellness/series.ts";
import {
  alternating,
  constant,
  makeTable,
  only,
  planted,
  type STEADY,
  steadyWith,
  TODAY,
} from "./helpers/wellness.ts";

const settings = DEFAULT_WELLNESS_SETTINGS;

const evaluate = (table: DailyTable, today = TODAY) => evaluateRecovery(table, today, settings);

/** The steady history has these spreads: resting heart rate 2.0243 bpm (mean 52), sleep 1822 s (mean 27000),
 * bedtime the floor of 60 minutes (mean -45), respiratory rate 0.4048 /min (mean 14.2), oxygen 1.012 % (mean 96),
 * heart rate in sleep 2.0243 bpm (mean 48). A value `z` spreads from the mean is `mean + z x spread`. */

interface Row {
  name: string;
  /** Overrides on days before today. */
  plant: Partial<Record<keyof typeof STEADY, Record<number, number | undefined>>>;
  rule: RecoveryRule;
  state: RecoveryState;
  reason: string;
  /** The metrics of the facts, in order, as far as the rule decides them. */
  leading: WellnessMetric[];
}

/** The rule table of ADR 0023, one row per rule, each with a morning that makes exactly that rule fire. */
const RULE_TABLE: Row[] = [
  {
    name: "OK: nothing outside the usual range",
    plant: {},
    rule: "OK",
    state: "ready",
    reason: "All six signals are in your usual range.",
    leading: [],
  },
  {
    name: "C2: one metric beyond 2 spreads (resting heart rate 56.5, z 2.2)",
    plant: { restingHr: { 0: 56.5 } },
    rule: "C2",
    state: "caution",
    reason: "Resting heart rate is well above your usual range.",
    leading: ["restingHr"],
  },
  {
    name: "C1: two metrics beyond 1 spread, none beyond 1.5 (resting heart rate 55 and respiratory rate 14.8, both z 1.48)",
    plant: { restingHr: { 0: 55 }, respiratoryRate: { 0: 14.8 } },
    rule: "C1",
    state: "caution",
    reason: "Two signals are outside your usual range: resting heart rate and respiratory rate.",
    leading: ["restingHr", "respiratoryRate"],
  },
  {
    name: "C3: one metric beyond 1 spread two mornings running (resting heart rate 55 and 55)",
    plant: { restingHr: { 1: 55, 0: 55 } },
    rule: "C3",
    state: "caution",
    reason: "Resting heart rate is above your usual range for the second morning.",
    leading: ["restingHr"],
  },
  {
    name: "C3: the fourth morning",
    plant: { restingHr: { 3: 55, 2: 55, 1: 55, 0: 55 } },
    rule: "C3",
    state: "caution",
    reason: "Resting heart rate is above your usual range for the fourth morning.",
    leading: ["restingHr"],
  },
  {
    name: "R1: three metrics beyond 1.5 spreads (resting heart rate 57, sleep 6 h, respiratory rate 15.2)",
    plant: { restingHr: { 0: 57 }, sleepSeconds: { 0: 21600 }, respiratoryRate: { 0: 15.2 } },
    rule: "R1",
    state: "rest",
    reason:
      "Three signals are well outside your usual range: sleep, resting heart rate and respiratory rate.",
    leading: ["sleepSeconds", "restingHr", "respiratoryRate"],
  },
  {
    name: "R2: one metric beyond 3 spreads two mornings running (resting heart rate 62 and 62)",
    plant: { restingHr: { 1: 62, 0: 62 } },
    rule: "R2",
    state: "rest",
    reason: "Resting heart rate has been far outside your usual range for two mornings.",
    leading: ["restingHr"],
  },
  {
    name: "R2: two metrics beyond 3 spreads two mornings running",
    plant: { restingHr: { 1: 62, 0: 62 }, spo2: { 1: 91, 0: 91 } },
    rule: "R2",
    state: "rest",
    reason:
      "Resting heart rate and oxygen saturation have been far outside your usual range for two mornings.",
    leading: ["restingHr", "spo2"],
  },
];

describe("the recovery rule table", () => {
  it.each(RULE_TABLE)("$name", (row) => {
    const outcome = evaluate(steadyWith(row.plant));
    expect(outcome.rule).toBe(row.rule);
    expect(outcome.state).toBe(row.state);
    expect(outcome.reason).toBe(row.reason);
    expect(outcome.guidance).toBe(GUIDANCE[row.state as "ready" | "caution" | "rest"]);
    expect(outcome.date).toBe(TODAY);
    expect(outcome.nightsLogged).toBe(42);
    expect(outcome.facts.length).toBeGreaterThanOrEqual(3);
    expect(outcome.facts.length).toBeLessThanOrEqual(4);
    expect(outcome.facts.slice(0, row.leading.length).map((f) => f.metric)).toEqual(row.leading);
  });

  it("covers every rule of the table", () => {
    expect(new Set(RULE_TABLE.map((row) => row.rule))).toEqual(
      new Set(["OK", "C1", "C2", "C3", "R1", "R2"]),
    );
  });

  it("answers in the shape of the contract", () => {
    for (const row of RULE_TABLE) {
      const outcome = evaluate(steadyWith(row.plant));
      const body = {
        ...outcome,
        nightsNeeded: settings.nightsNeeded,
        generatedAt: "2026-11-18T07:00:00Z",
        stale: false,
      };
      expect(validateRecoveryResponse(body), row.name).toMatchObject({ ok: true });
    }
  });
});

describe("rule precedence and boundaries", () => {
  it("rest beats caution: three metrics beyond 1.5 is rest although two beyond 1 would be caution", () => {
    const outcome = evaluate(
      steadyWith({
        restingHr: { 0: 57 },
        sleepSeconds: { 0: 21600 },
        respiratoryRate: { 0: 15.2 },
      }),
    );
    expect(outcome.rule).toBe("R1");
  });

  it("two metrics beyond 1.5 are caution (C1), not rest", () => {
    const outcome = evaluate(steadyWith({ restingHr: { 0: 57 }, sleepSeconds: { 0: 21600 } }));
    expect(outcome.rule).toBe("C1");
    expect(outcome.state).toBe("caution");
  });

  it("two metrics on a run are caution by C1, which is checked before C3", () => {
    const outcome = evaluate(
      steadyWith({ restingHr: { 1: 55, 0: 55 }, sleepSeconds: { 1: 25000, 0: 25000 } }),
    );
    expect(outcome.rule).toBe("C1");
    expect(outcome.reason).toBe(
      "Two signals are outside your usual range: resting heart rate and sleep.",
    );
  });

  it("two metrics, one of them beyond 2 spreads, are caution by C1 first", () => {
    const outcome = evaluate(steadyWith({ restingHr: { 0: 57 }, respiratoryRate: { 0: 14.7 } }));
    expect(outcome.rule).toBe("C1");
  });

  it("a metric beyond 3 spreads on one morning only is caution (C2), not rest", () => {
    const outcome = evaluate(steadyWith({ restingHr: { 0: 62 } }));
    expect(outcome.rule).toBe("C2");
    // And the morning before it was ordinary: no run.
    expect(outcome.facts[0]?.streak).toBe(1);
  });

  it("a metric just inside one spread is ready; just outside is not", () => {
    // 54.0 is inside (the range is 52 +/- 2.0243); 54.1 is above.
    expect(evaluate(steadyWith({ restingHr: { 0: 54 } })).rule).toBe("OK");
    const outside = evaluate(steadyWith({ restingHr: { 1: 54.1, 0: 54.1 } }));
    expect(outside.rule).toBe("C3");
  });

  it("outside the range in the good direction is not adverse", () => {
    const outcome = evaluate(
      steadyWith({
        restingHr: { 1: 45, 0: 45 },
        sleepSeconds: { 1: 32400, 0: 32400 },
        spo2: { 1: 99, 0: 99 },
        respiratoryRate: { 1: 12, 0: 12 },
        sleepingHr: { 1: 40, 0: 40 },
        bedtimeMinutes: { 1: -150, 0: -150 },
      }),
    );
    expect(outcome.state).toBe("ready");
    // They are still outside the usual range, and the facts say so.
    const resting = outcome.facts.find((f) => f.metric === "restingHr");
    expect(resting).toMatchObject({ deviation: "below", streak: 2 });
  });

  it.each([
    ["restingHr", 56.5, "Resting heart rate is well above your usual range."],
    ["sleepSeconds", 22000, "Sleep was much shorter than usual."],
    ["respiratoryRate", 15.3, "Respiratory rate is well above your usual range."],
    ["spo2", 93.5, "Oxygen saturation is well below your usual range."],
    ["sleepingHr", 53, "Heart rate in sleep is well above your usual range."],
    // A bedtime 130 minutes later than the mean of -45: 85. The spread is an hour at least, so 2.17 spreads.
    ["bedtimeMinutes", 85, "Bedtime was much later than usual."],
  ] as const)("%s is adverse in its own direction: %s", (metric, value, reason) => {
    const outcome = evaluate(steadyWith({ [metric]: { 0: value } }));
    expect(outcome.rule).toBe("C2");
    expect(outcome.reason).toBe(reason);
    expect(outcome.facts[0]).toMatchObject({
      metric,
      deviation: expect.stringMatching(/above|below/),
    });
  });

  it("sleep timing counts only beyond an hour", () => {
    // 50 minutes later than usual (-45 + 50 = 5): inside the range, which is at least an hour either side.
    expect(evaluate(steadyWith({ bedtimeMinutes: { 0: 5 } })).state).toBe("ready");
    // 70 minutes later: outside, but one metric a little outside for one morning is not yet caution. The
    // sentence says so instead of claiming everything is in range, and the fact comes first.
    const late = evaluate(steadyWith({ bedtimeMinutes: { 0: 25 } }));
    expect(late.state).toBe("ready");
    expect(late.reason).toBe("Bedtime was later than usual, but nothing else is.");
    expect(late.facts[0]).toMatchObject({
      metric: "bedtimeMinutes",
      deviation: "above",
      streak: 1,
    });
  });

  it("says so when the only metric outside its range is outside on the good side", () => {
    const outcome = evaluate(steadyWith({ restingHr: { 0: 45 } }));
    expect(outcome.state).toBe("ready");
    expect(outcome.reason).toBe("No signal is outside your usual range in the wrong direction.");
    expect(outcome.facts[0]).toMatchObject({ metric: "restingHr", deviation: "below" });
  });

  it("the facts name the usual range and where the value stands", () => {
    const outcome = evaluate(steadyWith({ restingHr: { 1: 55, 0: 55 } }));
    expect(outcome.facts[0]).toEqual({
      metric: "restingHr",
      label: "Resting heart rate",
      value: 55,
      unit: "bpm",
      baselineLow: expect.any(Number),
      baselineHigh: expect.any(Number),
      deviation: "above",
      streak: 2,
    });
    expect(outcome.facts[0]?.baselineLow).toBeCloseTo(49.9, 0);
    expect(outcome.facts[0]?.baselineHigh).toBeCloseTo(54.1, 0);
  });
});

describe("learning and no data", () => {
  it("rates nothing before 14 nights: learning, with the readings and no range", () => {
    const table = makeTable({
      restingHr: only(alternating(50, 54), 0, 9),
      sleepSeconds: only(constant(27000), 0, 9),
    });
    const outcome = evaluate(table);
    expect(outcome).toMatchObject({
      date: TODAY,
      state: "learning",
      rule: null,
      guidance: null,
      // Today is not part of the nights behind the baseline: 9 of the 10 days.
      nightsLogged: 9,
      reason: "Overdrive rates recovery after 14 nights with the watch. 9 nights so far.",
    });
    expect(outcome.facts.map((f) => f.metric)).toEqual(["restingHr", "sleepSeconds"]);
    expect(outcome.facts[0]).toMatchObject({
      value: 50,
      baselineLow: null,
      baselineHigh: null,
      deviation: null,
      streak: 0,
    });
  });

  it("says one night in the singular", () => {
    const outcome = evaluate(makeTable({ restingHr: only(constant(52), 0, 1) }));
    expect(outcome.reason).toBe(
      "Overdrive rates recovery after 14 nights with the watch. 1 night so far.",
    );
  });

  it("becomes a verdict on the first morning with 14 nights behind it", () => {
    const thirteen = evaluate(makeTable({ restingHr: only(alternating(50, 54), 0, 13) }));
    expect(thirteen.state).toBe("learning");
    const fourteen = evaluate(makeTable({ restingHr: only(alternating(50, 54), 0, 14) }));
    expect(fourteen.state).toBe("ready");
    expect(fourteen.nightsLogged).toBe(14);
  });

  it("is learning, not a verdict, when nights are enough but no metric has a range of its own", () => {
    // Fourteen nights, but each metric has a reading on only some of them: none reaches 14 readings.
    const table = makeTable({
      restingHr: only((back) => (back % 2 === 0 ? 52 : undefined), 0, 27),
      sleepSeconds: only((back) => (back % 2 === 1 ? 27000 : undefined), 1, 28),
    });
    const outcome = evaluate(table);
    expect(outcome.nightsLogged).toBeGreaterThanOrEqual(14);
    expect(outcome.state).toBe("learning");
    expect(outcome.reason).toBe(
      "Overdrive is still working out your usual range from the readings it has.",
    );
  });

  it("uses the nights the owner asks for", () => {
    const table = makeTable({ restingHr: only(alternating(50, 54), 0, 8) });
    expect(evaluateRecovery(table, TODAY, { baselineDays: 42, nightsNeeded: 7 }).state).toBe(
      "ready",
    );
    expect(evaluateRecovery(table, TODAY, { baselineDays: 42, nightsNeeded: 8 }).state).toBe(
      "ready",
    );
    expect(evaluateRecovery(table, TODAY, { baselineDays: 42, nightsNeeded: 9 }).state).toBe(
      "learning",
    );
    expect(evaluateRecovery(table, TODAY, { baselineDays: 42, nightsNeeded: 9 }).reason).toBe(
      "Overdrive rates recovery after 9 nights with the watch. 8 nights so far.",
    );
  });

  it("is noData with nothing in the last two days", () => {
    const table = steadyWith({});
    // Readings only up to three days ago.
    const old: DailyTable = {};
    for (const [metric, map] of Object.entries(table)) {
      old[metric as keyof DailyTable] = new Map(
        [...(map ?? [])].filter(([date]) => date < "2026-11-16"),
      );
    }
    const outcome = evaluate(old);
    expect(outcome).toMatchObject({
      date: TODAY,
      state: "noData",
      rule: null,
      guidance: null,
      facts: [],
      reason:
        "No reading from last night. Wear the watch to sleep, then open Overdrive in the morning.",
    });
    expect(evaluate({}).state).toBe("noData");
  });

  it("rates yesterday while this morning has not arrived", () => {
    const table = steadyWith({
      restingHr: { 0: undefined, 1: 56.5 },
      sleepSeconds: { 0: undefined },
      bedtimeMinutes: { 0: undefined },
      respiratoryRate: { 0: undefined },
      spo2: { 0: undefined },
      sleepingHr: { 0: undefined },
    });
    const outcome = evaluate(table);
    expect(outcome.date).toBe("2026-11-17");
    expect(outcome.rule).toBe("C2");
    expect(outcome.nightsLogged).toBe(42);
  });

  it("is noData for the day before yesterday, with the date of today", () => {
    const table = steadyWith({
      restingHr: { 0: undefined, 1: undefined },
      sleepSeconds: { 0: undefined, 1: undefined },
      bedtimeMinutes: { 0: undefined, 1: undefined },
      respiratoryRate: { 0: undefined, 1: undefined },
      spo2: { 0: undefined, 1: undefined },
      sleepingHr: { 0: undefined, 1: undefined },
    });
    expect(evaluate(table)).toMatchObject({ state: "noData", date: TODAY });
  });
});

describe("how many metrics there are", () => {
  it("rates with the metrics it has", () => {
    const only2 = makeTable({ restingHr: alternating(50, 54), spo2: alternating(95, 97) });
    expect(evaluateRecovery(only2, TODAY, settings).reason).toBe(
      "Both signals are in your usual range.",
    );
    const one = makeTable({ restingHr: alternating(50, 54) });
    expect(evaluateRecovery(one, TODAY, settings).reason).toBe(
      "Your one signal is in your usual range.",
    );
    // R1 needs three metrics: two metrics far outside are caution at most.
    const two = makeTable({
      restingHr: planted(alternating(50, 54), { 0: 60 }),
      spo2: planted(alternating(95, 97), { 0: 92 }),
    });
    expect(evaluateRecovery(two, TODAY, settings).rule).toBe("C1");
  });

  it("shows up to four facts, those that decided first, and a metric without a reading with its usual range", () => {
    // Oxygen saturation has a history but no reading this morning.
    const outcome = evaluate(steadyWith({ restingHr: { 0: 56.5 }, spo2: { 0: undefined } }));
    expect(outcome.facts).toHaveLength(4);
    expect(outcome.facts[0]?.metric).toBe("restingHr");
    // No reading: it comes after those with one.
    const none = steadyWith({ spo2: { 0: undefined } });
    const facts = evaluate(none).facts;
    expect(facts.map((f) => f.metric)).toEqual([
      "restingHr",
      "sleepSeconds",
      "respiratoryRate",
      "sleepingHr",
    ]);
    const six = evaluate(steadyWith({ restingHr: { 0: 56.5 }, sleepingHr: { 0: undefined } }));
    expect(six.facts.map((f) => f.metric)).toEqual([
      "restingHr",
      "sleepSeconds",
      "respiratoryRate",
      "spo2",
    ]);
  });
});

describe("heart-rate variability", () => {
  /** rMSSD of 60 ms most days. */
  const hrvHistory = constant(60);

  it("is the 7-day geometric mean, with at least four readings in the seven days", () => {
    const raw = makeTable({
      hrvRmssd: (back) => (back <= 3 ? 40 + back * 10 : undefined),
    }).hrvRmssd;
    const rolling = rollingHrv(raw);
    // Today (back 0): readings 40, 50, 60, 70 on days 0..3 are 4 readings: exp(mean ln).
    expect(rolling?.get(TODAY)?.value).toBeCloseTo(
      Math.exp((Math.log(40) + Math.log(50) + Math.log(60) + Math.log(70)) / 4),
      9,
    );
    // Three days ago has one reading in its week: no mean yet.
    expect(rolling?.has("2026-11-15")).toBe(false);
    expect(rollingHrv(undefined)).toBeUndefined();
  });

  it("is one more metric in the table, never required", () => {
    // Without it, the steady history is ready; with a steady HRV as well, still ready with seven signals.
    const base = steadyWith({}, { hrvRmssd: hrvHistory });
    const outcome = evaluate(base);
    expect(outcome.state).toBe("ready");
    expect(outcome.reason).toBe("All seven signals are in your usual range.");
  });

  it("a fall in the 7-day mean adds to the count: with one other metric outside, caution by C1", () => {
    // Five mornings at 40 ms after a steady 60 ms: the 7-day mean falls far below its baseline.
    const hrv = planted(hrvHistory, { 4: 40, 3: 40, 2: 40, 1: 40, 0: 40 });
    const outcome = evaluate(steadyWith({ restingHr: { 0: 55 } }, { hrvRmssd: hrv }));
    expect(outcome.rule).toBe("C1");
    expect(outcome.reason).toContain("heart rate variability");
    expect(outcome.facts.map((f) => f.metric)).toContain("hrvRmssd");
    const fact = outcome.facts.find((f) => f.metric === "hrvRmssd");
    expect(fact?.unit).toBe("ms");
    expect(fact?.deviation).toBe("below");
    // The value is the 7-day geometric mean: five at 40 and two at 60.
    expect(fact?.value).toBeCloseTo(Math.exp((5 * Math.log(40) + 2 * Math.log(60)) / 7), 1);
  });

  it("alone it can make caution, but never rest", () => {
    const hrv = planted(hrvHistory, {
      9: 20,
      8: 20,
      7: 20,
      6: 20,
      5: 20,
      4: 20,
      3: 20,
      2: 20,
      1: 20,
      0: 20,
    });
    const outcome = evaluate(steadyWith({}, { hrvRmssd: hrv }));
    expect(outcome.state).toBe("caution");
    expect(["C2", "C3"]).toContain(outcome.rule);
    expect(outcome.state).not.toBe("rest");
  });
});

describe("what the state never says", () => {
  it("uses no medical word in any sentence it can write", () => {
    const sentences = RULE_TABLE.map((row) => evaluate(steadyWith(row.plant)).reason);
    sentences.push(...Object.values(GUIDANCE));
    for (const sentence of sentences) {
      expect(sentence).not.toMatch(
        /ill|sick|infect|disease|diagnos|fever|cause|syndrome|overtrain|doctor|medical/i,
      );
    }
  });
});
