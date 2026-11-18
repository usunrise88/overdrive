import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  addDaysToIsoDate,
  INSIGHT_KINDS,
  isIsoDate,
  isValidRideId,
  MAX_INSIGHTS_RANGE_DAYS,
  rangeDays,
} from "@overdrive/contracts";
import { z } from "zod";
import { localDate } from "../../time.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const DEFAULT_DAYS = 7;
const MAX_TOPICS = 50;

/**
 * The tools of the written insights (spec §6.5, §12.3, §8.4, ADR 0025): `list_insights`, `get_facts`, `list_discussion_topics` and
 * `mark_discussion_topic`. The first three read; the last changes only the status of a topic. Nothing here can change the plan.
 */
export function registerInsightTools(server: McpServer, ctx: ToolContext): void {
  const { services, now } = ctx;
  const timezone = services.timezone;
  const { insights } = services;

  defineTool(
    server,
    ctx,
    {
      name: "list_insights",
      title: "List the written notes",
      kind: "read",
      description:
        "The notes the service wrote for the rider, newest first: kind morning (state, why, what to do with today's workout), ride (how a ride went against " +
        "its plan; rideId is set and it may carry a discussion topic), week (the weekly review), deviation (a metric outside the rider's own baseline for " +
        "several days in a row) and drift (missed sessions, or load far from the plan). Each has text (plain words, written from facts the server computed: " +
        "by a language model, checked so that it quotes only supplied numbers, or by a template when none is configured), source (llm or template) and, for " +
        "a ride note, its topic (id, text, status). The notes are what the rider has already been told: read them before advising so you do not repeat or " +
        "contradict them; use get_facts for the numbers behind a note. Dates are the rider's local days (YYYY-MM-DD) of the note's creation; without dates " +
        "the last 7 days; at most 366. kind limits to one kind. The notes are health-related: quote them to the rider, do not pass them on. A note never " +
        "changed the plan.",
      input: {
        from: dateField.optional().describe("First day. Default: 6 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
        kind: z.enum(INSIGHT_KINDS).optional().describe("Only notes of this kind."),
        detail: z
          .enum(["summary", "full"])
          .optional()
          .describe(
            "summary (default) is compact; full adds the prompt version and the hash of the facts the note was written from.",
          ),
      },
    },
    async ({ from, to, kind, detail }) => {
      const end = to ?? localDate(now(), timezone);
      if (!isIsoDate(end))
        throw new ToolError(`to ${end} is not a real calendar date, write YYYY-MM-DD`);
      const start = from ?? addDaysToIsoDate(end, -(DEFAULT_DAYS - 1));
      if (!isIsoDate(start))
        throw new ToolError(`from ${start} is not a real calendar date, write YYYY-MM-DD`);
      const days = rangeDays(start, end) ?? 0;
      if (days < 1) throw new ToolError(`to ${end} is before from ${start}`);
      if (days > MAX_INSIGHTS_RANGE_DAYS) {
        throw new ToolError(
          `the range spans ${days} days; at most ${MAX_INSIGHTS_RANGE_DAYS} are served`,
        );
      }
      const rows = await insights.listWithSource(start, end);
      const shown = kind === undefined ? rows : rows.filter((row) => row.insight.kind === kind);
      return {
        from: start,
        to: end,
        timezone,
        count: shown.length,
        insights: shown.map((row) => ({
          id: row.insight.id,
          kind: row.insight.kind,
          createdAt: row.insight.createdAt,
          text: row.insight.text,
          source: row.source,
          rideId: row.insight.rideId,
          topic:
            row.insight.topic === null
              ? null
              : {
                  id: row.insight.topic.id,
                  text: row.insight.topic.text,
                  status: row.insight.topic.status,
                },
          ...(detail === "full"
            ? { promptVersion: row.promptVersion, factsHash: row.factsHash }
            : {}),
        })),
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "get_facts",
      title: "Get the facts a note is written from",
      kind: "read",
      description:
        "The structured facts the service computed for a note and gave to the writer, so you can reason over exactly what the rider's note was " +
        "based on. kind morning (a date: recovery state with its reason and signals, today's planned workout with its length, planned load and intensity, form, " +
        "last ride), ride (a rideId: the ride, planned against ridden step by step, the best efforts against earlier bests, drift, W' and form), week (a Monday: " +
        "sessions planned against done, load against the week before, fitness and form, recovery mornings, wellness trends, new bests, the next 7 days), " +
        "deviation (a date: the metrics that have been outside the rider's baseline for the alert's number of days) or drift (a date: missed sessions and " +
        "load against plan over the last 7 days). Every number is computed by the server and listed in quotable with its unit: quote them, never recalculate or " +
        "round differently. When a note exists for that day, ride or week, the facts are the very ones stored with it (stored true, with the note's id and how it " +
        "was written); otherwise they are worked out now (stored false). Facts are health data and say nothing about cause; they are not medical advice.",
      input: {
        kind: z.enum(INSIGHT_KINDS).describe("Which facts."),
        date: dateField
          .optional()
          .describe("morning, deviation and drift: the day. Default: today."),
        rideId: z.string().optional().describe("ride: the ride's id (see list_activities)."),
        weekStart: dateField
          .optional()
          .describe("week: any day of the week; its Monday is used. Default: this week so far."),
      },
    },
    async ({ kind, date, rideId, weekStart }) => {
      for (const [name, value] of [
        ["date", date],
        ["weekStart", weekStart],
      ] as const) {
        if (value !== undefined && !isIsoDate(value)) {
          throw new ToolError(`${name} ${value} is not a real calendar date, write YYYY-MM-DD`);
        }
      }
      let answer: Awaited<ReturnType<typeof insights.facts>>;
      if (kind === "ride") {
        if (rideId === undefined || !isValidRideId(rideId)) {
          throw new ToolError("give rideId, the id of a ride (see list_activities)");
        }
        answer = await insights.facts({ kind: "ride", rideId });
        if (answer === null) throw new ToolError(`there is no ride ${rideId}`);
      } else if (kind === "week") {
        answer = await insights.facts({
          kind: "week",
          ...(weekStart === undefined ? {} : { weekStart }),
        });
      } else {
        answer = await insights.facts({ kind, ...(date === undefined ? {} : { date }) });
      }
      if (answer === null) throw new ToolError("there are no facts for that");
      return { timezone, stored: answer.stored, note: answer.note, facts: answer.facts };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "list_discussion_topics",
      title: "List the topics worth discussing",
      kind: "read",
      description:
        "Discussion topics: rides the post-ride analysis found worth a conversation with you, and why. A topic exists only for a real reason (a ride cut short " +
        "against its plan, a power record at a standard duration, load far from the plan, repeated plan drift), at most two a week. Each has text (one sentence on what " +
        "stood out), openingQuestion (what the rider would ask first, so you can start from it without the rider explaining), the ride (id, name, day), rule (which " +
        "reason found it) and status: open until you mark it discussed (mark_discussion_topic) or 14 days pass (expired). By default only the open ones, newest first: " +
        "when the rider opens a chat, start from the latest open topic, read the ride with get_activity and its facts with get_facts, and mark it discussed when " +
        "you have talked it through. status all lists every topic.",
      input: {
        status: z
          .enum(["open", "discussed", "expired", "all"])
          .optional()
          .describe("Default open."),
        limit: z
          .number()
          .int()
          .min(1)
          .max(MAX_TOPICS)
          .optional()
          .describe(`At most this many, default 10, at most ${MAX_TOPICS}.`),
      },
    },
    async ({ status, limit }) => {
      const wanted = status ?? "open";
      const topics = await insights.topics(wanted === "all" ? null : [wanted], limit ?? 10);
      return { count: topics.length, status: wanted, topics };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "mark_discussion_topic",
      title: "Mark a discussion topic as discussed",
      kind: "write",
      description:
        "Marks a discussion topic as discussed, so the next chat does not start from it again. Only this: it changes nothing in the plan or the profile. " +
        "Marking a topic that is already discussed changes nothing; an expired one stays expired. Use it once you have talked the topic through with the rider.",
      input: {
        id: z.number().int().min(1).describe("The topic's id (list_discussion_topics)."),
        status: z
          .enum(["discussed"])
          .optional()
          .describe("The only status that can be set: discussed."),
      },
    },
    async ({ id }) => {
      const topic = await insights.markTopicDiscussed(id);
      if (topic === null) throw new ToolError(`there is no discussion topic ${id}`);
      return { topic };
    },
  );
}
