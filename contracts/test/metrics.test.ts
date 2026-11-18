import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  addDaysToIsoDate,
  type MetricsResponse,
  POWER_CURVE_DURATIONS,
  type Validation,
  validateMetricsResponse,
  validateRideDetailResponse,
  validateRideMetricsResponse,
  validateRidesResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/metrics/", import.meta.url).pathname;
const schemas = new URL("../schema/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

const sets: [string, (input: unknown) => Validation<unknown>, number, number][] = [
  ["metrics-response", validateMetricsResponse, 6, 25],
  ["ride-metrics-response", validateRideMetricsResponse, 4, 14],
  ["rides-response", validateRidesResponse, 3, 12],
  ["ride-detail-response", validateRideDetailResponse, 3, 10],
];

describe.each(sets)("%s", (set, validate, valid, invalid) => {
  it("has fixtures in both directories", () => {
    expect(load(set, "valid").length).toBeGreaterThanOrEqual(valid);
    expect(load(set, "invalid").length).toBeGreaterThanOrEqual(invalid);
  });

  it.each(load(set, "valid"))("accepts valid/%s", (_name, body) => {
    const result = validate(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load(set, "invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validate(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });
});

describe("what a schema cannot say is named in the error", () => {
  const errors = (set: string, name: string, validate: (i: unknown) => Validation<unknown>) => {
    const body = load(set, "invalid").find(([file]) => file === `${name}.json`)?.[1];
    const result = validate(body);
    return result.ok ? "" : result.errors.join("; ");
  };

  it.each([
    ["daily-missing-a-day", /has 6 entries, the range has 7 days/],
    ["daily-out-of-order", /\/daily\/1\/date is 2026-11-11, expected 2026-11-10/],
    ["form-is-not-fitness-minus-fatigue", /tsb 5 is not ctl/],
    ["load-is-not-the-sum", /load 99.9 is not loadCycling plus loadOther/],
    ["week-does-not-start-on-a-monday", /is not a Monday/],
    ["a-week-is-missing", /touches 1 weeks/],
    ["curve-durations-not-ascending", /does not follow/],
    ["curve-power-rises-with-the-duration", /is above the 60 s power/],
    ["curve-date-outside-the-range", /is outside 2026-11-09 to 2026-11-15/],
    ["curve-window-days-differs", /windowDays is 30/],
    ["suggestion-difference-is-wrong", /differencePercent 9.9 is not 262 against 250/],
    ["suggestion-and-a-note-together", /must be null while a suggestion is pending/],
    ["no-suggestion-and-no-note", /must say why there is no suggestion/],
    ["suggestion-without-an-ftp-has-a-difference", /must be null without a currentFtp/],
    ["ftp-history-out-of-order", /is before the entry above it/],
    ["drift-outside-the-range", /is outside 2026-11-09 to 2026-11-15/],
    ["range-of-401-days", /spans 401 days, at most 400/],
    ["reversed-range", /is after \/to/],
    ["not-a-real-date", /real calendar dates/],
  ])("metrics: %s", (name, message) => {
    expect(errors("metrics-response", name, validateMetricsResponse)).toMatch(message);
  });

  it.each([
    ["moving-longer-than-the-ride", /is more than \/durationSec/],
    ["curve-power-rises-with-the-duration", /is above the 300 s power/],
    ["drift-available-without-numbers", /must all be present/],
    ["drift-available-with-a-reason", /must all be present and reason null/],
    ["drift-unavailable-with-numbers", /reason must be set and the numbers null/],
    ["drift-unavailable-without-a-reason", /reason must be set/],
    ["drift-ends-before-it-starts", /endSec must be after startSec/],
    ["w-prime-minimum-above-w-prime", /minBalanceKj is above wPrimeKj/],
    ["w-prime-depletion-does-not-follow", /does not follow from the minimum/],
    ["w-prime-series-too-long", /series has 9 points, a ride of 40 s has at most 8/],
  ])("ride metrics: %s", (name, message) => {
    expect(errors("ride-metrics-response", name, validateRideMetricsResponse)).toMatch(message);
  });

  it.each([
    ["has-more-is-wrong", /hasMore must be true/],
    ["more-rides-than-the-limit", /the limit is 1/],
    ["more-rides-than-are-left", /more entries than are left/],
    ["oldest-first", /starts after the ride above it/],
    ["the-same-ride-twice", /is listed twice/],
    ["reversed-dates", /is after \/to/],
  ])("rides: %s", (name, message) => {
    expect(errors("rides-response", name, validateRidesResponse)).toMatch(message);
  });

  it.each([
    ["laps-leave-a-gap", /starts at 1900, where lap 1 ends/],
    ["first-lap-starts-late", /not 0/],
    ["last-lap-stops-early", /the ride is 3600 s long/],
    ["series-too-short", /\/series\/power has 5 points, expected 6/],
  ])("ride detail: %s", (name, message) => {
    expect(errors("ride-detail-response", name, validateRideDetailResponse)).toMatch(message);
  });
});

describe("the metrics range", () => {
  const empty = load("metrics-response", "valid").find(
    ([file]) => file === "no-rides-yet.json",
  )?.[1] as MetricsResponse;

  /** The empty answer stretched to `days` days ending on 2026-11-18. */
  function over(days: number): MetricsResponse {
    const copy = structuredClone(empty);
    copy.from = addDaysToIsoDate("2026-11-18", 1 - days);
    copy.to = "2026-11-18";
    copy.daily = Array.from({ length: days }, (_, i) => ({
      ...(empty.daily[0] as MetricsResponse["daily"][number]),
      date: addDaysToIsoDate(copy.from, i),
    }));
    // Weeks: from the Monday of `from` to the Monday of 2026-11-16.
    const weeks = [];
    for (
      let monday = addDaysToIsoDate(
        copy.from,
        -((new Date(`${copy.from}T00:00:00Z`).getUTCDay() + 6) % 7),
      );
      monday <= "2026-11-16";
      monday = addDaysToIsoDate(monday, 7)
    ) {
      weeks.push({ weekStart: monday, load: 0, rides: 0, durationSec: 0 });
    }
    copy.weekly = weeks;
    copy.powerCurve.windowDays = days;
    return copy;
  }

  it("serves 400 days and refuses 401", () => {
    expect(validateMetricsResponse(over(400))).toMatchObject({ ok: true });
    expect(validateMetricsResponse(over(401)).ok).toBe(false);
    expect(validateMetricsResponse(over(1))).toMatchObject({ ok: true });
  });
});

describe("the power curve durations", () => {
  it("are the ones of the schemas", () => {
    for (const name of ["metrics-response", "ride-metrics-response"]) {
      const schema = JSON.parse(readFileSync(join(schemas, `${name}.schema.json`), "utf8")) as {
        $defs: { duration: { enum: number[] } };
      };
      expect(schema.$defs.duration.enum).toEqual([...POWER_CURVE_DURATIONS]);
    }
  });
});
