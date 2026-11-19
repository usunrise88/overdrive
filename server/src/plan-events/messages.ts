import type { PlanEventKind } from "@overdrive/contracts";
import { shortDate } from "../zoned.ts";

/**
 * What a plan event says in words (spec §12.2, ADR 0035): to the coach agent (`describeEvent`, the `summary` the MCP tools add) and to
 * the rider in Telegram when the agent has resolved the event (`resolutionText`): what the event was, what became of it, and why.
 * Written by code from the event's fields, never by a model; the "why" is the agent's own resolution note, quoted as it was written.
 * A missed session that was dropped is said plainly to have been dropped.
 */

/** What the sentences are written from: an event's kind, day and detail (a stored event or one on the wire). */
interface Described {
  kind: PlanEventKind;
  day: string;
  detail: Record<string, unknown>;
}

interface Resolved extends Described {
  resolutionNote: string | null;
  dropped: boolean | null;
}

/** To whom a sentence is written: the agent reads about "the rider", the rider is told "you". */
export type Voice = "agent" | "rider";

const text = (value: unknown): string | null =>
  typeof value === "string" && value.trim() !== "" ? value.trim() : null;

const dayOf = (value: unknown): string | null =>
  typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) ? shortDate(value) : null;

/** What happened, in one sentence. */
export function describeEvent(event: Described, voice: Voice = "agent"): string {
  const who = voice === "rider" ? "You" : "The rider";
  const are = voice === "rider" ? "you are" : "they are";
  const name = text(event.detail.name) ?? "A planned session";
  const planned = shortDate(event.day);
  switch (event.kind) {
    case "missed":
      return `${name} on ${planned} was not ridden.`;
    case "moved": {
      const to = dayOf(event.detail.to);
      return to === null
        ? `${who} moved ${name} from ${planned}.`
        : `${who} moved ${name} from ${planned} to ${to}.`;
    }
    case "deleted":
      return `${who} deleted ${name} on ${planned} from the calendar.`;
    case "variant":
      return `${who} rode the easier version of ${name} on ${planned}.`;
    case "illness": {
      const until = dayOf(event.detail.until);
      return until === null
        ? `${who} said ${are} ill, from ${planned}.`
        : `${who} said ${are} ill, from ${planned} to ${until}.`;
    }
    case "travel": {
      const until = dayOf(event.detail.until);
      return until === null
        ? `${who} said ${are} travelling, from ${planned}.`
        : `${who} said ${are} travelling, from ${planned} to ${until}.`;
    }
  }
}

/** What became of it, in one sentence: a dropped session is said to be dropped. */
function whatBecameOfIt(event: Resolved): string {
  if (event.dropped === true) {
    return event.kind === "illness" || event.kind === "travel"
      ? "The sessions in that time were dropped, not moved."
      : "It was dropped and not rescheduled.";
  }
  return event.kind === "variant"
    ? "The plan has been brought in line with what you rode."
    : "The plan has been adjusted.";
}

/** The message sent to the rider once an event is resolved. */
export function resolutionText(event: Resolved): string {
  const why = text(event.resolutionNote);
  return [
    "Plan update",
    `${describeEvent(event, "rider")} ${whatBecameOfIt(event)}`,
    ...(why === null ? [] : [`Why: ${why}`]),
  ].join("\n");
}

/** The answer to `/ill` and `/travel`. */
export function declaredText(
  kind: "illness" | "travel",
  today: string,
  until: string | null,
  repeated: boolean,
): string {
  const what = kind === "illness" ? "ill" : "travelling";
  const span = until === null ? `from ${shortDate(today)}` : `until ${shortDate(until)}`;
  return repeated
    ? `Already noted: ${what} ${span}. Your coach has it.`
    : `Noted: ${what} ${span}. Your coach will rebuild the days ahead and tell you what changed.`;
}
