import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  METHODOLOGY_SECTION,
  NOTEBOOK_SECTION_TITLES,
  NOTEBOOK_SECTIONS,
  validateNotebookResponse,
} from "../src/index.ts";

const fixtures = new URL("../fixtures/notebook/", import.meta.url).pathname;

function load(set: string, dir: "valid" | "invalid"): [string, unknown][] {
  const path = join(fixtures, set, dir);
  return readdirSync(path)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => [f, JSON.parse(readFileSync(join(path, f), "utf8"))]);
}

describe("notebook response", () => {
  const valid = load("notebook-response", "valid");
  const invalid = load("notebook-response", "invalid");

  it("has fixtures in both directories", () => {
    expect(valid.length).toBeGreaterThanOrEqual(5);
    expect(invalid.length).toBeGreaterThanOrEqual(25);
  });

  it.each(valid)("accepts valid/%s", (_name, body) => {
    const result = validateNotebookResponse(body);
    expect(result, JSON.stringify(result)).toMatchObject({ ok: true });
  });

  it.each(invalid)("rejects invalid/%s", (_name, body) => {
    const result = validateNotebookResponse(body);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.length).toBeGreaterThan(0);
  });

  it("names the seven sections, each with a title, and methodology is one of them", () => {
    expect(NOTEBOOK_SECTIONS).toEqual([
      "profile",
      "what_works",
      "what_does_not",
      "injuries",
      "methodology",
      "open_questions",
      "log",
    ]);
    for (const key of NOTEBOOK_SECTIONS) expect(NOTEBOOK_SECTION_TITLES[key]).toBeTruthy();
    expect(NOTEBOOK_SECTIONS).toContain(METHODOLOGY_SECTION);
  });

  it("serves the coach scope and never the game master's", () => {
    const [, body] = valid[0] as [string, { scope: string }];
    expect(body.scope).toBe("coach");
    const result = validateNotebookResponse({ ...body, scope: "game-master" });
    expect(result.ok).toBe(false);
  });

  it("never quotes the text of a section in an error", () => {
    const secret = "knee surgery in March, tell no one";
    const [, body] = valid.find(([name]) => name === "filled.json") as [string, never];
    const wrong = structuredClone(body) as {
      totalChars: number;
      sections: { text: string }[];
    };
    wrong.sections[0] = { ...(wrong.sections[0] as object), text: secret } as never;
    const result = validateNotebookResponse(wrong);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors.join("\n")).not.toContain("surgery");
  });
});
