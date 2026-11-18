import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  INSIGHT_KINDS,
  MAX_INSIGHTS_RANGE_DAYS,
  TOPIC_STATUSES,
  validateDiscussionTopic,
  validateInsightsResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/insights/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("insights response", () => {
  const valid = load("insights-response", "valid");
  const invalid = load("insights-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(8);
    expect(invalid.length).toBeGreaterThanOrEqual(60);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateInsightsResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateInsightsResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("has a note of every kind and a topic of every status among the valid fixtures", () => {
    const kinds = new Set<string>();
    const statuses = new Set<string>();
    for (const [, body] of valid) {
      for (const note of (
        body as { insights: { kind: string; topic: { status: string } | null }[] }
      ).insights) {
        kinds.add(note.kind);
        if (note.topic !== null) statuses.add(note.topic.status);
      }
    }
    expect([...kinds].sort()).toEqual([...INSIGHT_KINDS].sort());
    expect([...statuses].sort()).toEqual([...TOPIC_STATUSES].sort());
  });

  it(`serves at most ${MAX_INSIGHTS_RANGE_DAYS} days`, () => {
    expect(MAX_INSIGHTS_RANGE_DAYS).toBe(366);
  });

  it("never quotes the text of a note in an error", () => {
    const secret = "my resting heart rate is 99 and I feel awful";
    const body = {
      from: "2026-11-09",
      to: "2026-11-18",
      generatedAt: "2026-11-18T08:00:00Z",
      stale: false,
      insights: [
        {
          id: 1,
          kind: "alert",
          createdAt: "2026-11-18T06:00:00Z",
          text: secret,
          rideId: null,
          topic: null,
        },
      ],
    };
    const result = validateInsightsResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.join("\n")).not.toContain("awful");
  });
});

describe("discussion topic", () => {
  const valid = load("discussion-topic", "valid");
  const invalid = load("discussion-topic", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(5);
    expect(invalid.length).toBeGreaterThanOrEqual(18);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateDiscussionTopic(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateDiscussionTopic(body);
    expect(result.ok).toBe(false);
  });
});
