import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isIsoDate } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Plan events and replanning (spec §12.2, §8.3, ADR 0035) --------------------------------------
// Hand-written mirrors of schema/plan-event.schema.json and schema/plan-event-declaration.schema.json, which are the source of
// truth; the fixtures test keeps the two honest. The service only detects and queues the events; the coach agent reads them
// over MCP (`list_plan_events`) and resolves them (`resolve_plan_event`); the rider declares illness or travel with
// `POST /v1/plan-events`.

/**
 * What can make the plan need rebuilding: a planned day ended with no matching activity (`missed`), the rider moved or deleted a
 * planned session (`moved`, `deleted`), the easier variant was ridden (`variant`), the rider declared illness or travel.
 */
export const PLAN_EVENT_KINDS = [
  "missed",
  "moved",
  "deleted",
  "variant",
  "illness",
  "travel",
] as const;
export type PlanEventKind = (typeof PLAN_EVENT_KINDS)[number];

/** The kinds the rider can declare: the rest are detected. */
export const DECLARED_PLAN_EVENT_KINDS = ["illness", "travel"] as const;
export type DeclaredPlanEventKind = (typeof DECLARED_PLAN_EVENT_KINDS)[number];

export const PLAN_EVENT_STATUSES = ["open", "resolved"] as const;
export type PlanEventStatus = (typeof PLAN_EVENT_STATUSES)[number];

/** One plan event. */
export interface PlanEvent {
  id: number;
  kind: PlanEventKind;
  /** The rider's local day the event is about, `YYYY-MM-DD`. */
  day: string;
  /** The planned workout (`icu-123`) it is about; null exactly for illness and travel. */
  workoutId: string | null;
  /** What the kind knows; see the schema's description. */
  detail: Record<string, unknown>;
  status: PlanEventStatus;
  /** UTC instant. */
  createdAt: string;
  /** UTC instant; null while open. */
  resolvedAt: string | null;
  /** What changed and why, in the agent's words; null while open. */
  resolutionNote: string | null;
  /** True when the session was dropped and not rescheduled; null while open. */
  dropped: boolean | null;
}

/** The body of `POST /v1/plan-events`. */
export interface PlanEventDeclaration {
  kind: DeclaredPlanEventKind;
  /** The last day it lasts; absent or null for no end given. */
  until?: string | null;
}

// ---- Validation -----------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkPlanEvent = ajv.compile<PlanEvent>(load("plan-event.schema.json"));
const checkDeclaration = ajv.compile<PlanEventDeclaration>(
  load("plan-event-declaration.schema.json"),
);

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** What the schema cannot say about a plan event (see its description). */
export function planEventConsistencyErrors(event: PlanEvent): string[] {
  const errors: string[] = [];
  if (!isIsoDate(event.day)) errors.push("/day is not a real calendar date");
  const created = parseInstant(event.createdAt);
  if (created === null) errors.push("/createdAt is not a real date and time");
  const declared = event.kind === "illness" || event.kind === "travel";
  if (declared !== (event.workoutId === null)) {
    errors.push("/workoutId is null exactly for kind illness and travel");
  }
  if (declared) {
    const until = event.detail.until;
    if (until !== null && (typeof until !== "string" || !isIsoDate(until))) {
      errors.push("/detail/until must be a real calendar date or null");
    } else if (typeof until === "string" && until < event.day) {
      errors.push("/detail/until is before the day");
    }
  }
  if (event.status === "open") {
    if (event.resolvedAt !== null || event.resolutionNote !== null || event.dropped !== null) {
      errors.push("/resolvedAt, /resolutionNote and /dropped must be null while open");
    }
  } else {
    if (event.resolvedAt === null || event.resolutionNote === null || event.dropped === null) {
      errors.push("/resolvedAt, /resolutionNote and /dropped must be set when resolved");
    } else {
      const resolved = parseInstant(event.resolvedAt);
      if (resolved === null) errors.push("/resolvedAt is not a real date and time");
      else if (created !== null && resolved < created) {
        errors.push("/resolvedAt is before /createdAt");
      }
    }
  }
  return errors;
}

export function validatePlanEvent(input: unknown): Validation<PlanEvent> {
  if (!checkPlanEvent(input)) return { ok: false, errors: plain(checkPlanEvent.errors) };
  const errors = planEventConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/** Validates the body of `POST /v1/plan-events`: the schema, and an `until` that is a real date. */
export function validatePlanEventDeclaration(input: unknown): Validation<PlanEventDeclaration> {
  if (!checkDeclaration(input)) return { ok: false, errors: plain(checkDeclaration.errors) };
  if (typeof input.until === "string" && !isIsoDate(input.until)) {
    return { ok: false, errors: ["/until is not a real calendar date"] };
  }
  return { ok: true, value: input };
}
