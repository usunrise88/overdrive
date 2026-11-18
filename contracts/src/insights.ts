import { readFileSync } from "node:fs";
import { Ajv2020, type ValidateFunction } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isIsoDate, rangeDays } from "./plan.ts";
import { parseInstant } from "./wellness.ts";

// ---- Sync API: written insights and discussion topics (spec §6.5, §6.6, §12.3, §8.3, ADR 0025) ----
// Hand-written mirrors of schema/insights-response.schema.json and schema/discussion-topic.schema.json, which are the
// source of truth; the fixtures test keeps the two honest. Kotlin's `InsightsApiJson` reads and writes the same bodies
// against the same fixtures.
//
// HEALTH DATA IS PRIVATE: no error message built here quotes the text of a note, only paths and rules.

/** The longest range `GET /v1/insights` serves, in days, both ends included. */
export const MAX_INSIGHTS_RANGE_DAYS = 366;

/**
 * The kinds of note: `morning`, `ride`, `week` are the notes of spec §6.5; `deviation` and `drift` are the alerts of
 * spec §6.6 (a metric outside the rider's own baseline for several days; missed sessions or load far from the plan).
 */
export const INSIGHT_KINDS = ["morning", "ride", "week", "deviation", "drift"] as const;
export type InsightKind = (typeof INSIGHT_KINDS)[number];

export const TOPIC_STATUSES = ["open", "discussed", "expired"] as const;
export type TopicStatus = (typeof TOPIC_STATUSES)[number];

/** A discussion topic (spec §12.3): why a ride is worth a conversation with the coach, and how to open it. */
export interface DiscussionTopic {
  id: number;
  /** One sentence on what stood out. */
  text: string;
  /** A question the rider can paste as the first message of a chat with the coach. */
  openingQuestion: string;
  rideId: string;
  /** UTC instant. */
  createdAt: string;
  status: TopicStatus;
}

/** One written note. */
export interface Insight {
  id: number;
  kind: InsightKind;
  /** UTC instant. */
  createdAt: string;
  text: string;
  /** The ride a `ride` note is about; null for every other kind. */
  rideId: string | null;
  /** Only a `ride` note can carry one. */
  topic: DiscussionTopic | null;
}

/** The answer to `GET /v1/insights?from&to`. */
export interface InsightsResponse {
  from: string;
  to: string;
  generatedAt: string;
  /** Always false: the notes are the service's own rows. */
  stale: boolean;
  /** Newest first. */
  insights: Insight[];
}

// ---- Validation -----------------------------------------------------------------------------------

const MAX_REPORTED = 20;

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

const checkInsightsResponse = ajv.compile<InsightsResponse>(load("insights-response.schema.json"));
const checkDiscussionTopic = ajv.compile<DiscussionTopic>(load("discussion-topic.schema.json"));

function plain(errors: ValidateFunction["errors"]): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/** What the schema cannot say about a topic (see its description). */
export function discussionTopicConsistencyErrors(topic: DiscussionTopic): string[] {
  return parseInstant(topic.createdAt) === null ? ["/createdAt is not a real date and time"] : [];
}

export function validateDiscussionTopic(input: unknown): Validation<DiscussionTopic> {
  if (!checkDiscussionTopic(input))
    return { ok: false, errors: plain(checkDiscussionTopic.errors) };
  const errors = discussionTopicConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}

/**
 * What the schema cannot say about an insights answer (see its description): a real range of at most
 * {@link MAX_INSIGHTS_RANGE_DAYS} days, real instants, unique ids, newest first, the ride id set exactly for ride notes,
 * and a topic only on a ride note that names the same ride.
 */
export function insightsResponseConsistencyErrors(response: InsightsResponse): string[] {
  const days = rangeDays(response.from, response.to);
  if (days === null) return ["/from and /to must be real calendar dates"];
  const errors: string[] = [];
  if (days < 1) errors.push(`/from ${response.from} is after /to ${response.to}`);
  if (days > MAX_INSIGHTS_RANGE_DAYS) {
    errors.push(`The range spans ${days} days, at most ${MAX_INSIGHTS_RANGE_DAYS} are served`);
  }
  if (!isIsoDate(response.from) || !isIsoDate(response.to)) {
    errors.push("/from and /to must be real calendar dates");
  }
  if (parseInstant(response.generatedAt) === null) {
    errors.push("/generatedAt is not a real date and time");
  }
  if (response.stale) errors.push("/stale must be false");
  const seen = new Set<number>();
  let before: { at: number; id: number } | null = null;
  for (const [index, insight] of response.insights.entries()) {
    const at = `/insights/${index}`;
    const created = parseInstant(insight.createdAt);
    if (created === null) errors.push(`${at}/createdAt is not a real date and time`);
    if (seen.has(insight.id)) errors.push(`${at}/id is used twice`);
    seen.add(insight.id);
    if (created !== null) {
      if (
        before !== null &&
        (created > before.at || (created === before.at && insight.id > before.id))
      ) {
        errors.push(`${at} is newer than the note before it: newest first`);
      }
      before = { at: created, id: insight.id };
    }
    if ((insight.kind === "ride") !== (insight.rideId !== null)) {
      errors.push(`${at}/rideId is set exactly for kind ride`);
    }
    if (insight.topic !== null) {
      if (insight.kind !== "ride") errors.push(`${at}/topic must be null unless kind is ride`);
      else if (insight.topic.rideId !== insight.rideId) {
        errors.push(`${at}/topic/rideId is not the note's rideId`);
      }
      for (const error of discussionTopicConsistencyErrors(insight.topic)) {
        errors.push(`${at}/topic${error}`);
      }
    }
  }
  return errors.slice(0, MAX_REPORTED);
}

export function validateInsightsResponse(input: unknown): Validation<InsightsResponse> {
  if (!checkInsightsResponse(input))
    return { ok: false, errors: plain(checkInsightsResponse.errors) };
  const errors = insightsResponseConsistencyErrors(input);
  return errors.length === 0 ? { ok: true, value: input } : { ok: false, errors };
}
