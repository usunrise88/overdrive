import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  applyProfilePatch,
  COGGAN_ZONE_UPPER_PERCENT,
  DEFAULT_PHYSICS,
  defaultProfile,
  type PowerZoneRange,
  type Profile,
  powerZoneOf,
  powerZoneRanges,
  validateApiError,
  validateProfile,
  validateProfilePatch,
  WEEKDAYS,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/profile/", import.meta.url).pathname;
const schemas = new URL("../schema/", import.meta.url).pathname;

function load(dir: string): [string, unknown][] {
  const path = join(fixtures, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

interface PatchCase {
  description?: string;
  before: Profile;
  patch: unknown;
  after?: Profile;
}

describe("profile schema", () => {
  it("has fixtures in both directories", () => {
    expect(load("profile/valid").length).toBeGreaterThanOrEqual(4);
    expect(load("profile/invalid").length).toBeGreaterThanOrEqual(30);
  });

  it.each(load("profile/valid"))("accepts valid/%s", (_name, body) => {
    const result = validateProfile(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(load("profile/invalid"))("rejects invalid/%s", (_name, body) => {
    const result = validateProfile(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("names the rule a well-shaped profile breaks", () => {
    const byName = new Map(load("profile/invalid"));
    const errors = (name: string): string => {
      const result = validateProfile(byName.get(name));
      return result.ok ? "" : result.errors.join("; ");
    };
    expect(errors("zones-not-increasing.json")).toMatch(/not above the bound before it/);
    expect(errors("zones-equal-bounds.json")).toMatch(/not above/);
    expect(errors("goals-blank.json")).toMatch(/blank/);
    expect(errors("threshold-not-below-max.json")).toMatch(/not below/);
  });

  it("accepts a profile cached before the heart-rate keys existed and gives it nulls", () => {
    const old = JSON.parse(JSON.stringify(defaultProfile())) as Record<string, unknown>;
    for (const key of [
      "maxHeartRate",
      "thresholdHeartRate",
      "estimatedMaxHeartRate",
      "estimatedThresholdHeartRate",
    ]) {
      delete old[key];
    }
    const result = validateProfile(old);
    expect(result).toMatchObject({ ok: true });
    if (result.ok) expect(result.value).toEqual(defaultProfile());
  });

  it("the defaults are the defaults fixture, Coggan's zones and the engine's physics", () => {
    expect(defaultProfile()).toEqual(
      load("profile/valid").find(([name]) => name === "defaults.json")?.[1],
    );
    expect(defaultProfile().zones.upperPercentOfFtp).toEqual([...COGGAN_ZONE_UPPER_PERCENT]);
    expect(DEFAULT_PHYSICS).toEqual({
      systemMassKg: 80,
      cdA: 0.35,
      crr: 0.004,
      airDensity: 1.225,
      drivetrainEfficiency: 1,
    });
    expect(WEEKDAYS).toEqual(["mon", "tue", "wed", "thu", "fri", "sat", "sun"]);
  });
});

describe("profile patch", () => {
  const valid = load("patch/valid") as [string, PatchCase][];
  const invalid = load("patch/invalid") as [string, PatchCase][];

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(12);
    expect(invalid.length).toBeGreaterThanOrEqual(18);
  });

  it.each(valid)("applies valid/%s to exactly the expected profile", (_name, fixture) => {
    expect(validateProfile(fixture.before)).toMatchObject({ ok: true });
    const result = applyProfilePatch(fixture.before, fixture.patch);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
    if (result.ok) {
      expect(result.value).toEqual(fixture.after);
      expect(validateProfile(result.value)).toMatchObject({ ok: true });
    }
  });

  it.each(invalid)("refuses invalid/%s and leaves the profile as it was", (_name, fixture) => {
    const before = structuredClone(fixture.before);
    const result = applyProfilePatch(fixture.before, fixture.patch);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
    expect(fixture.before).toEqual(before);
  });

  it("tells a badly shaped patch from one that makes a bad profile", () => {
    const byName = new Map(invalid);
    const shape = (name: string) => validateProfilePatch(byName.get(name)?.patch).ok;
    expect(shape("unknown-key.json")).toBe(false);
    expect(shape("zones-five-bounds.json")).toBe(false);
    // These have the right shape and fail only once applied.
    expect(shape("zones-not-increasing.json")).toBe(true);
    expect(shape("goals-blank.json")).toBe(true);
  });

  it("does not change the profile it is given", () => {
    const profile = defaultProfile();
    const result = applyProfilePatch(profile, { ftp: 300, goals: ["a"] });
    expect(result.ok).toBe(true);
    expect(profile).toEqual(defaultProfile());
  });
});

describe("zones in watts", () => {
  const file = JSON.parse(readFileSync(join(fixtures, "zone-ranges.json"), "utf8")) as {
    cases: { ftp: number; upperPercentOfFtp: number[]; ranges: PowerZoneRange[] }[];
  };

  it.each(file.cases.map((c) => [`${c.ftp} W, ${c.upperPercentOfFtp.join("/")}`, c] as const))(
    "gives the fixture's ranges for %s",
    (_name, c) => {
      expect(powerZoneRanges(c.ftp, c.upperPercentOfFtp)).toEqual(c.ranges);
    },
  );

  it("puts every whole-watt power in the zone whose range holds it", () => {
    for (const c of file.cases) {
      for (let watts = 0; watts <= 2500; watts++) {
        const zone = c.ranges.find(
          (r) => watts >= r.minWatts && (r.maxWatts === null || watts <= r.maxWatts),
        );
        expect(powerZoneOf(watts, c.ftp, c.upperPercentOfFtp)).toBe(zone?.zone);
      }
    }
  });

  it("reads negative power as zero", () => {
    expect(powerZoneOf(-40, 250, COGGAN_ZONE_UPPER_PERCENT)).toBe(1);
  });
});

describe("the invalid_profile error", () => {
  it("is a valid API error body", () => {
    const body = JSON.parse(
      readFileSync(
        new URL("../fixtures/sync/api-error/valid/invalid-profile.json", import.meta.url),
        "utf8",
      ),
    );
    expect(validateApiError(body)).toMatchObject({ ok: true });
  });

  it("is described by both schemas as a 422", () => {
    const schema = JSON.parse(readFileSync(join(schemas, "api-error.schema.json"), "utf8")) as {
      description: string;
    };
    expect(schema.description).toContain("422 invalid_record, invalid_profile");
  });
});
