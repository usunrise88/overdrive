import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  addDaysToIsoDate,
  type MetricsResponse,
  POWER_CURVE_DURATIONS,
  type Validation,
  validateFtpSuggestionDismissRequest,
  validateFtpSuggestionDismissResponse,
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
  ["metrics-response", validateMetricsResponse, 8, 35],
  ["ride-metrics-response", validateRideMetricsResponse, 4, 17],
  ["rides-response", validateRidesResponse, 3, 20],
  ["ride-detail-response", validateRideDetailResponse, 5, 20],
  ["ftp-suggestion-dismiss-request", validateFtpSuggestionDismissRequest, 1, 5],
  ["ftp-suggestion-dismiss-response", validateFtpSuggestionDismissResponse, 3, 3],
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
    ["week-load-is-not-the-sum", /is not rideLoad plus otherLoad/],
    ["curve-durations-not-ascending", /does not follow/],
    ["curve-power-rises-with-the-duration", /is above the 60 s power/],
    ["curve-date-outside-the-range", /is outside the range/],
    ["curve-better-than-all-time", /no allTime effort of 60 s at least as good/],
    ["ftp-set-at-without-an-ftp", /must be null exactly when/],
    ["ftp-without-a-set-at", /must be null exactly when/],
    ["ftp-history-out-of-order", /is before the entry above it/],
    ["estimate-outside-the-range", /is outside 2026-11-09 to 2026-11-15/],
    ["drift-outside-the-range", /is outside 2026-11-09 to 2026-11-15/],
    ["range-of-367-days", /spans 367 days, at most 366/],
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
    ["has-more-without-a-next-before", /nextBefore must be the start of the last item/],
    ["next-before-is-not-the-last-start", /nextBefore must be the start of the last item/],
    ["next-before-without-more", /must be null when \/hasMore is false/],
    ["more-items-than-the-limit", /the limit is 1/],
    ["oldest-first", /does not start before the item above it/],
    ["not-before-the-cursor", /does not start before \/before/],
    ["the-same-ride-twice", /is listed twice/],
    ["ride-with-a-sport", /is a ride: no sport/],
    ["ride-with-an-approximate-load", /is a ride: no sport/],
    ["activity-with-a-power", /is an activity: no averagePower/],
    ["activity-with-an-exact-load", /is an activity: no averagePower/],
  ])("rides: %s", (name, message) => {
    expect(errors("rides-response", name, validateRidesResponse)).toMatch(message);
  });

  it.each([
    ["laps-leave-a-gap", /starts at 1900, where lap 1 ends/],
    ["first-lap-starts-late", /not 0/],
    ["last-lap-stops-early", /the ride is 3900 s long/],
    ["effort-range-without-a-target", /must be null exactly when effortTarget is/],
    ["effort-range-reversed", /minWatts is above maxWatts/],
    ["more-steps-ridden-than-planned", /stepsRidden 7 is more than stepsPlanned 6/],
    ["series-times-not-ascending", /must ascend/],
    ["series-past-the-end-of-the-ride", /must ascend and stay within the 3900 s/],
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
    const weeks = [];
    for (
      let monday = addDaysToIsoDate(
        copy.from,
        -((new Date(`${copy.from}T00:00:00Z`).getUTCDay() + 6) % 7),
      );
      monday <= "2026-11-16";
      monday = addDaysToIsoDate(monday, 7)
    ) {
      weeks.push({
        weekStart: monday,
        load: 0,
        rideLoad: 0,
        otherLoad: 0,
        rides: 0,
        activities: 0,
      });
    }
    copy.weekly = weeks;
    return copy;
  }

  it("serves 366 days (at most 54 weeks) and refuses 367", () => {
    expect(validateMetricsResponse(over(366))).toMatchObject({ ok: true });
    expect(over(366).weekly.length).toBeLessThanOrEqual(54);
    expect(validateMetricsResponse(over(367)).ok).toBe(false);
    expect(validateMetricsResponse(over(1))).toMatchObject({ ok: true });
  });
});

describe("the power curve durations", () => {
  it("are the 25 of the companion's design, in the schemas and in the code", () => {
    expect(POWER_CURVE_DURATIONS).toHaveLength(25);
    const schema = JSON.parse(
      readFileSync(join(schemas, "metrics-response.schema.json"), "utf8"),
    ) as { $defs: { duration: { enum: number[] } } };
    expect(schema.$defs.duration.enum).toEqual([...POWER_CURVE_DURATIONS]);
    // 1, 2, 3, 5, 8, 10, 15, 20, 30, 45 s; 1, 1.5, 2, 3, 4, 5, 7, 10, 15, 20, 30, 40, 60, 90, 120 min.
    expect([...POWER_CURVE_DURATIONS]).toEqual([
      1,
      2,
      3,
      5,
      8,
      10,
      15,
      20,
      30,
      45,
      ...[1, 1.5, 2, 3, 4, 5, 7, 10, 15, 20, 30, 40, 60, 90, 120].map((minutes) => minutes * 60),
    ]);
  });
});
