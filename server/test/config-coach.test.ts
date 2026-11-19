import { describe, expect, it } from "vitest";
import { describeConfigError, loadConfig } from "../src/config.ts";

const DATABASE_URL = "postgres://overdrive:secret@db:5432/overdrive";
const load = (env: Record<string, string>) => loadConfig({ DATABASE_URL, ...env });

function problem(env: Record<string, string>): string {
  try {
    load(env);
  } catch (error) {
    return describeConfigError(error);
  }
  throw new Error("the configuration was accepted");
}

describe("the coach notebook and plan event settings (ADR 0035)", () => {
  it("are all optional, with a 12000-character notebook, a 3000-character log and three hours of grace", () => {
    expect(load({})).toMatchObject({
      NOTEBOOK_MAX_CHARS: 12000,
      NOTEBOOK_LOG_MAX_CHARS: 3000,
      PLAN_EVENT_DAY_END_GRACE_MINUTES: 180,
    });
  });

  it("treat empty values as not set (compose passes an empty VAR)", () => {
    expect(
      load({
        NOTEBOOK_MAX_CHARS: "",
        NOTEBOOK_LOG_MAX_CHARS: " ",
        PLAN_EVENT_DAY_END_GRACE_MINUTES: "",
      }),
    ).toMatchObject({
      NOTEBOOK_MAX_CHARS: 12000,
      NOTEBOOK_LOG_MAX_CHARS: 3000,
      PLAN_EVENT_DAY_END_GRACE_MINUTES: 180,
    });
  });

  it("take whole numbers within bounds", () => {
    expect(
      load({
        NOTEBOOK_MAX_CHARS: "20000",
        NOTEBOOK_LOG_MAX_CHARS: "5000",
        PLAN_EVENT_DAY_END_GRACE_MINUTES: "0",
      }),
    ).toMatchObject({
      NOTEBOOK_MAX_CHARS: 20000,
      NOTEBOOK_LOG_MAX_CHARS: 5000,
      PLAN_EVENT_DAY_END_GRACE_MINUTES: 0,
    });
  });

  it.each([
    ["NOTEBOOK_MAX_CHARS", "1999"],
    ["NOTEBOOK_MAX_CHARS", "100001"],
    ["NOTEBOOK_MAX_CHARS", "a lot"],
    ["NOTEBOOK_MAX_CHARS", "12000.5"],
    ["NOTEBOOK_LOG_MAX_CHARS", "499"],
    ["NOTEBOOK_LOG_MAX_CHARS", "50001"],
    ["PLAN_EVENT_DAY_END_GRACE_MINUTES", "-1"],
    ["PLAN_EVENT_DAY_END_GRACE_MINUTES", "721"],
  ])("refuse %s=%s with a message that names it", (name, value) => {
    expect(problem({ [name]: value })).toContain(name);
  });
});
