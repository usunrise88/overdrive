import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  PROGRESS_MEASURES,
  PROGRESS_MIN_POINTS,
  type ProgressResponse,
  progressDirection,
  validateProgressResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/progress/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("progress response", () => {
  const valid = load("progress-response", "valid");
  const invalid = load("progress-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(5);
    expect(invalid.length).toBeGreaterThanOrEqual(60);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateProgressResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateProgressResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("covers every direction, the unknown one and a headline of each kind", () => {
    const directions = new Set<string>();
    const headlines = new Set<string>();
    for (const [, body] of valid) {
      const answer = body as ProgressResponse;
      const trends = [
        answer.aerobicEfficiency.trend,
        answer.aerobicEfficiency.powerTrend,
        answer.efficiencyFactor.trend,
        answer.decoupling.trend,
        answer.restingHeartRate.trend,
        answer.weight.trend,
        ...Object.values(answer.bestPower.trends),
      ];
      for (const trend of trends) directions.add(trend.direction);
      headlines.add(answer.headline === null ? "none" : answer.headline.direction);
    }
    expect([...directions].sort()).toEqual(["improving", "steady", "unknown", "worsening"]);
    expect([...headlines].sort()).toEqual(["improving", "none", "worsening"]);
  });

  it("says 'fitter' only for heart rate against power", () => {
    for (const [, body] of valid) {
      const answer = body as ProgressResponse;
      const fitter = (text: string): boolean => text.includes("fitter");
      expect(fitter(answer.bestPower.trend.verdictText)).toBe(false);
      expect(fitter(answer.decoupling.trend.verdictText)).toBe(false);
      expect(fitter(answer.restingHeartRate.trend.verdictText)).toBe(false);
      expect(fitter(answer.weight.trend.verdictText)).toBe(false);
      for (const trend of [answer.aerobicEfficiency.trend, answer.efficiencyFactor.trend]) {
        // A worsening or steady trend carries no judging word.
        if (trend.direction !== "improving") expect(fitter(trend.verdictText)).toBe(false);
      }
    }
  });

  it("states the minimum points of the design's table", () => {
    expect(PROGRESS_MIN_POINTS).toEqual({
      bestPower: 3,
      aerobicEfficiency: 4,
      efficiencyFactor: 5,
      decoupling: 5,
      restingHeartRate: 4,
      weight: 4,
    });
    expect(new Set(PROGRESS_MEASURES).size).toBe(6);
    for (const [, body] of valid) {
      const answer = body as ProgressResponse;
      expect(answer.efficiencyFactor.trend.minPoints).toBe(PROGRESS_MIN_POINTS.efficiencyFactor);
      expect(answer.decoupling.trend.minPoints).toBe(PROGRESS_MIN_POINTS.decoupling);
      expect(answer.restingHeartRate.trend.minPoints).toBe(PROGRESS_MIN_POINTS.restingHeartRate);
      expect(answer.weight.trend.minPoints).toBe(PROGRESS_MIN_POINTS.weight);
      expect(answer.aerobicEfficiency.trend.minPoints).toBe(PROGRESS_MIN_POINTS.aerobicEfficiency);
      expect(answer.bestPower.trends.d300.minPoints).toBe(PROGRESS_MIN_POINTS.bestPower);
    }
  });
});

describe("progressDirection", () => {
  it("is steady inside the band, else by which way is better", () => {
    expect(progressDirection(1.9, 2, "lower")).toBe("steady");
    expect(progressDirection(-1.9, 2, "lower")).toBe("steady");
    expect(progressDirection(-2, 2, "lower")).toBe("improving");
    expect(progressDirection(2, 2, "lower")).toBe("worsening");
    expect(progressDirection(2, 2, "higher")).toBe("improving");
    expect(progressDirection(-2, 2, "higher")).toBe("worsening");
  });
});
