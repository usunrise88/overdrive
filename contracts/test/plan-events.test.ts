import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  DECLARED_PLAN_EVENT_KINDS,
  PLAN_EVENT_KINDS,
  PLAN_EVENT_STATUSES,
  type PlanEvent,
  validatePlanEvent,
  validatePlanEventDeclaration,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/plan-events/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("plan event", () => {
  const valid = load("plan-event", "valid");
  const invalid = load("plan-event", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(9);
    expect(invalid.length).toBeGreaterThanOrEqual(25);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validatePlanEvent(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validatePlanEvent(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("has an event of every kind and of both statuses among the valid fixtures", () => {
    const kinds = new Set<string>();
    const statuses = new Set<string>();
    for (const [, body] of valid) {
      kinds.add((body as PlanEvent).kind);
      statuses.add((body as PlanEvent).status);
    }
    expect([...kinds].sort()).toEqual([...PLAN_EVENT_KINDS].sort());
    expect([...statuses].sort()).toEqual([...PLAN_EVENT_STATUSES].sort());
  });

  it("has a resolved event that dropped the session and one that rescheduled it", () => {
    const outcomes = new Set(
      valid
        .map(([, body]) => body as PlanEvent)
        .filter((event) => event.status === "resolved")
        .map((event) => event.dropped),
    );
    expect([...outcomes].sort()).toEqual([false, true]);
  });

  it("lets the rider declare illness and travel and nothing else", () => {
    expect(DECLARED_PLAN_EVENT_KINDS).toEqual(["illness", "travel"]);
    for (const kind of DECLARED_PLAN_EVENT_KINDS) expect(PLAN_EVENT_KINDS).toContain(kind);
  });
});

describe("plan event declaration", () => {
  const valid = load("declaration", "valid");
  const invalid = load("declaration", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(4);
    expect(invalid.length).toBeGreaterThanOrEqual(10);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validatePlanEventDeclaration(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    expect(validatePlanEventDeclaration(body).ok).toBe(false);
  });
});
