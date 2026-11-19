import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: the coach notebook (spec §11.6, §12.4, §8.3, ADR 0035) -----------------------------
// Hand-written mirror of schema/notebook-response.schema.json, which is the source of truth; the fixtures test keeps the
// two honest. The notebook is written by the coach agent over MCP; the companion only reads it (`GET /v1/notebook`).
//
// THE NOTEBOOK IS PRIVATE: no error message built here quotes the text of a section, only paths and rules.

/**
 * The fixed sections of the coach notebook, in the order they are shown: the rider's profile and constraints, what works, what
 * does not, injuries and niggles, the methodology (the current plan rationale, spec §12.4), open questions, and the dated log of
 * decisions.
 */
export const NOTEBOOK_SECTIONS = [
  "profile",
  "what_works",
  "what_does_not",
  "injuries",
  "methodology",
  "open_questions",
  "log",
] as const;
export type NotebookSection = (typeof NOTEBOOK_SECTIONS)[number];

/** The title each section is shown under. */
export const NOTEBOOK_SECTION_TITLES: Readonly<Record<NotebookSection, string>> = {
  profile: "Rider profile and constraints",
  what_works: "What works",
  what_does_not: "What does not work",
  injuries: "Injuries and niggles",
  methodology: "Methodology",
  open_questions: "Open questions",
  log: "Decisions log",
};

/** The section whose change needs a written reason and is announced to the rider (spec §12.4). */
export const METHODOLOGY_SECTION: NotebookSection = "methodology";

/** One section of the notebook as the companion reads it. */
export interface NotebookSectionView {
  key: NotebookSection;
  title: string;
  /** Empty for a section never written. */
  text: string;
  /** The number of the current text; 0 for a section never written. */
  version: number;
  /** UTC instant of the current version; null for version 0. */
  updatedAt: string | null;
  /** The reason given for the current version; null when none was given and for version 0. */
  reason: string | null;
}

/** The answer to `GET /v1/notebook`. */
export interface NotebookResponse {
  scope: "coach";
  /** UTC instant. */
  generatedAt: string;
  /** The server's caps, in characters (UTF-16 code units). */
  limits: { maxChars: number; logMaxChars: number };
  /** The sum of the sections' text lengths. */
  totalChars: number;
  /** All seven sections, in the order of {@link NOTEBOOK_SECTIONS}. */
  sections: NotebookSectionView[];
}

// ---- Validation -----------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkNotebookResponse = ajv.compile<NotebookResponse>(load("notebook-response.schema.json"));

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/**
 * What the schema cannot say about a notebook answer (see its description): the sections are the seven keys in order with their
 * titles, version 0 (never written) has empty text, no reason and no updatedAt while every written version has an updatedAt, the
 * instants are real, and totalChars is the sum of the text lengths.
 */
export function notebookResponseConsistencyErrors(response: NotebookResponse): string[] {
  const errors: string[] = [];
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  let total = 0;
  for (const [index, key] of NOTEBOOK_SECTIONS.entries()) {
    const section = response.sections[index];
    const at = `/sections/${index}`;
    if (section === undefined) continue;
    if (section.key !== key) errors.push(`${at}/key is ${section.key}, expected ${key}`);
    if (section.title !== NOTEBOOK_SECTION_TITLES[key]) {
      errors.push(`${at}/title is not "${NOTEBOOK_SECTION_TITLES[key]}"`);
    }
    total += section.text.length;
    if (section.version === 0) {
      if (section.text !== "" || section.reason !== null || section.updatedAt !== null) {
        errors.push(`${at}: version 0 (never written) has empty text, no reason and no updatedAt`);
      }
    } else if (section.updatedAt === null) {
      errors.push(`${at}/updatedAt is null, but version ${section.version} was written at a time`);
    }
    if (section.updatedAt !== null && parseInstant(section.updatedAt) === null) {
      errors.push(`${at}/updatedAt is not a real date and time`);
    }
  }
  if (response.totalChars !== total) {
    errors.push(`/totalChars is ${response.totalChars}, the texts add up to ${total}`);
  }
  return errors;
}

export function validateNotebookResponse(input: unknown): Validation<NotebookResponse> {
  if (!checkNotebookResponse(input)) {
    return { ok: false, errors: plain(checkNotebookResponse.errors) };
  }
  const errors = notebookResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
