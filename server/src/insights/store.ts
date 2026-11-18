import type { InsightKind, TopicStatus } from "@overdrive/contracts";
import type { Db, Queryable } from "../db.ts";
import type { Facts } from "./facts.ts";

/**
 * What the insights keep in PostgreSQL (migration 0008): the written notes (`insights`), the discussion topics
 * (`discussion_topics`) and the deviation alerts already sent (`alert_streaks`). Nothing here calculates or logs: a row is health
 * data (its text and facts), so errors that reach a log carry only a code (the callers see to that).
 */

export interface InsightRow {
  id: number;
  kind: InsightKind;
  /** The idempotence key: day, ride id, Monday, metrics and day, or Monday. */
  key: string;
  createdAt: Date;
  text: string;
  rideId: string | null;
  source: "llm" | "template";
  promptVersion: string;
  factsHash: string;
  facts: Facts;
}

export interface TopicRow {
  id: number;
  rideId: string;
  /** The row of the topic rule table that found it. */
  rule: string;
  text: string;
  openingQuestion: string;
  createdAt: Date;
  status: TopicStatus;
  decidedAt: Date | null;
}

export interface NewInsight {
  kind: InsightKind;
  key: string;
  createdAt: Date;
  text: string;
  rideId: string | null;
  source: "llm" | "template";
  promptVersion: string;
  factsHash: string;
  facts: Facts;
}

export interface NewTopic {
  rideId: string;
  rule: string;
  text: string;
  openingQuestion: string;
}

/** The limits a topic is written under: at most this many in the last `windowMs` (counted by their creation). */
export interface TopicLimit {
  max: number;
  windowMs: number;
}

export interface InsertResult {
  /** False: a note for this (kind, key) was already there and nothing was written. */
  inserted: boolean;
  insight: InsightRow | null;
  /** The topic written with the note, when one was and the limit allowed it. */
  topic: TopicRow | null;
  /** The topic was found by the rules but the limit stopped it. */
  topicSuppressed: boolean;
}

export interface WithTopic {
  insight: InsightRow;
  topic: TopicRow | null;
}

interface InsightSql {
  id: number;
  kind: InsightKind;
  key: string;
  created_at: Date | string;
  text: string;
  ride_id: string | null;
  source: "llm" | "template";
  prompt_version: string;
  facts_hash: string;
  facts: Facts | string;
}

interface TopicSql {
  id: number;
  ride_id: string;
  rule: string;
  text: string;
  opening_question: string;
  created_at: Date | string;
  status: TopicStatus;
  decided_at: Date | string | null;
}

const INSIGHT_COLUMNS =
  "id, kind, key, created_at, text, ride_id, source, prompt_version, facts_hash, facts";
const TOPIC_COLUMNS = "id, ride_id, rule, text, opening_question, created_at, status, decided_at";

const toInsight = (row: InsightSql): InsightRow => ({
  id: row.id,
  kind: row.kind,
  key: row.key,
  createdAt: new Date(row.created_at),
  text: row.text,
  rideId: row.ride_id,
  source: row.source,
  promptVersion: row.prompt_version,
  factsHash: row.facts_hash,
  facts: (typeof row.facts === "string" ? JSON.parse(row.facts) : row.facts) as Facts,
});

const toTopic = (row: TopicSql): TopicRow => ({
  id: row.id,
  rideId: row.ride_id,
  rule: row.rule,
  text: row.text,
  openingQuestion: row.opening_question,
  createdAt: new Date(row.created_at),
  status: row.status,
  decidedAt: row.decided_at === null ? null : new Date(row.decided_at),
});

export interface InsightStore {
  /**
   * Writes a note once: a second attempt for the same (kind, key) writes nothing. With a topic, the topic is written in the same
   * transaction unless `limit` says there are enough already; a ride has one topic at most.
   */
  insert(
    note: NewInsight,
    topic?: { topic: NewTopic; limit: TopicLimit; now: Date } | null,
  ): Promise<InsertResult>;
  /** A note and the deviation streaks it reports, in one transaction; nothing is written if the note was already there. */
  insertAlert(
    note: NewInsight,
    streaks: { metric: string; streakStart: string }[],
  ): Promise<InsightRow | null>;
  find(kind: InsightKind, key: string): Promise<InsightRow | null>;
  get(id: number): Promise<InsightRow | null>;
  /** The notes created in `[fromMs, toMs)`, newest first, each with its ride's topic. */
  between(fromMs: number, toMs: number): Promise<WithTopic[]>;
  /** The notes created at or after `sinceMs`, oldest first. */
  since(sinceMs: number): Promise<InsightRow[]>;
  /** Which of these rides have a ride note. */
  rideNotes(rideIds: readonly string[]): Promise<Set<string>>;
  /** How many notes of a kind were created at or after `sinceMs`. */
  countSince(kind: InsightKind, sinceMs: number): Promise<number>;
  /** The deviation streaks already reported, as `metric@startDate`. */
  reportedStreaks(): Promise<Set<string>>;
  topicForRide(rideId: string): Promise<TopicRow | null>;
  topic(id: number): Promise<TopicRow | null>;
  /** Topics newest first; `statuses` limits to those. */
  topics(statuses: readonly TopicStatus[] | null, limit: number): Promise<TopicRow[]>;
  /** Marks a topic discussed (already discussed: unchanged); null when there is no such topic. */
  markDiscussed(id: number, at: Date): Promise<TopicRow | null>;
  /** Closes the open topics created at or before `createdBeforeMs`; how many were closed. */
  expireTopics(createdBeforeMs: number, at: Date): Promise<number>;
}

export function createInsightStore(db: Db): InsightStore {
  async function insertNote(tx: Queryable, note: NewInsight): Promise<InsightRow | null> {
    const result = await tx.query<InsightSql>(
      `INSERT INTO insights (kind, key, created_at, text, ride_id, source, prompt_version, facts_hash, facts)
       VALUES ($1, $2, $3::timestamptz, $4, $5, $6, $7, $8, $9::jsonb)
       ON CONFLICT (kind, key) DO NOTHING
       RETURNING ${INSIGHT_COLUMNS}`,
      [
        note.kind,
        note.key,
        note.createdAt.toISOString(),
        note.text,
        note.rideId,
        note.source,
        note.promptVersion,
        note.factsHash,
        JSON.stringify(note.facts),
      ],
    );
    const row = result.rows[0];
    return row === undefined ? null : toInsight(row);
  }

  return {
    async insert(note, withTopic) {
      return db.transaction(async (tx) => {
        const insight = await insertNote(tx, note);
        if (insight === null) {
          return { inserted: false, insight: null, topic: null, topicSuppressed: false };
        }
        if (withTopic === undefined || withTopic === null) {
          return { inserted: true, insight, topic: null, topicSuppressed: false };
        }
        const { topic, limit, now } = withTopic;
        const recent = await tx.query<{ n: string }>(
          "SELECT count(*)::text AS n FROM discussion_topics WHERE created_at > $1::timestamptz",
          [new Date(now.getTime() - limit.windowMs).toISOString()],
        );
        if (Number(recent.rows[0]?.n ?? 0) >= limit.max) {
          return { inserted: true, insight, topic: null, topicSuppressed: true };
        }
        const written = await tx.query<TopicSql>(
          `INSERT INTO discussion_topics (ride_id, rule, text, opening_question, created_at)
           VALUES ($1, $2, $3, $4, $5::timestamptz)
           ON CONFLICT (ride_id) DO NOTHING
           RETURNING ${TOPIC_COLUMNS}`,
          [topic.rideId, topic.rule, topic.text, topic.openingQuestion, now.toISOString()],
        );
        const row = written.rows[0];
        return {
          inserted: true,
          insight,
          topic: row === undefined ? null : toTopic(row),
          topicSuppressed: false,
        };
      });
    },

    async insertAlert(note, streaks) {
      return db.transaction(async (tx) => {
        const insight = await insertNote(tx, note);
        if (insight === null) return null;
        for (const streak of streaks) {
          await tx.query(
            `INSERT INTO alert_streaks (metric, streak_start, insight_id) VALUES ($1, $2::date, $3)
             ON CONFLICT (metric, streak_start) DO NOTHING`,
            [streak.metric, streak.streakStart, insight.id],
          );
        }
        return insight;
      });
    },

    async find(kind, key) {
      const result = await db.query<InsightSql>(
        `SELECT ${INSIGHT_COLUMNS} FROM insights WHERE kind = $1 AND key = $2`,
        [kind, key],
      );
      const row = result.rows[0];
      return row === undefined ? null : toInsight(row);
    },

    async get(id) {
      const result = await db.query<InsightSql>(
        `SELECT ${INSIGHT_COLUMNS} FROM insights WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toInsight(row);
    },

    async between(fromMs, toMs) {
      const notes = await db.query<InsightSql>(
        `SELECT ${INSIGHT_COLUMNS} FROM insights
          WHERE created_at >= $1::timestamptz AND created_at < $2::timestamptz
          ORDER BY created_at DESC, id DESC`,
        [new Date(fromMs).toISOString(), new Date(toMs).toISOString()],
      );
      const rows = notes.rows.map(toInsight);
      const rideIds = rows.flatMap((row) => (row.rideId === null ? [] : [row.rideId]));
      const topics = new Map<string, TopicRow>();
      if (rideIds.length > 0) {
        const found = await db.query<TopicSql>(
          `SELECT ${TOPIC_COLUMNS} FROM discussion_topics WHERE ride_id = ANY($1::text[])`,
          [rideIds],
        );
        for (const row of found.rows) topics.set(row.ride_id, toTopic(row));
      }
      return rows.map((insight) => ({
        insight,
        topic: insight.rideId === null ? null : (topics.get(insight.rideId) ?? null),
      }));
    },

    async since(sinceMs) {
      const result = await db.query<InsightSql>(
        `SELECT ${INSIGHT_COLUMNS} FROM insights WHERE created_at >= $1::timestamptz
          ORDER BY created_at, id`,
        [new Date(sinceMs).toISOString()],
      );
      return result.rows.map(toInsight);
    },

    async rideNotes(rideIds) {
      if (rideIds.length === 0) return new Set();
      const result = await db.query<{ key: string }>(
        "SELECT key FROM insights WHERE kind = 'ride' AND key = ANY($1::text[])",
        [rideIds],
      );
      return new Set(result.rows.map((row) => row.key));
    },

    async countSince(kind, sinceMs) {
      const result = await db.query<{ n: string }>(
        "SELECT count(*)::text AS n FROM insights WHERE kind = $1 AND created_at >= $2::timestamptz",
        [kind, new Date(sinceMs).toISOString()],
      );
      return Number(result.rows[0]?.n ?? 0);
    },

    async reportedStreaks() {
      const result = await db.query<{ metric: string; start: string }>(
        "SELECT metric, streak_start::text AS start FROM alert_streaks",
      );
      return new Set(result.rows.map((row) => `${row.metric}@${row.start}`));
    },

    async topicForRide(rideId) {
      const result = await db.query<TopicSql>(
        `SELECT ${TOPIC_COLUMNS} FROM discussion_topics WHERE ride_id = $1`,
        [rideId],
      );
      const row = result.rows[0];
      return row === undefined ? null : toTopic(row);
    },

    async topic(id) {
      const result = await db.query<TopicSql>(
        `SELECT ${TOPIC_COLUMNS} FROM discussion_topics WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toTopic(row);
    },

    async topics(statuses, limit) {
      const result =
        statuses === null
          ? await db.query<TopicSql>(
              `SELECT ${TOPIC_COLUMNS} FROM discussion_topics ORDER BY created_at DESC, id DESC LIMIT $1`,
              [limit],
            )
          : await db.query<TopicSql>(
              `SELECT ${TOPIC_COLUMNS} FROM discussion_topics WHERE status = ANY($2::text[])
                ORDER BY created_at DESC, id DESC LIMIT $1`,
              [limit, [...statuses]],
            );
      return result.rows.map(toTopic);
    },

    async markDiscussed(id, at) {
      await db.query(
        `UPDATE discussion_topics SET status = 'discussed', decided_at = $2::timestamptz
          WHERE id = $1 AND status = 'open'`,
        [id, at.toISOString()],
      );
      const result = await db.query<TopicSql>(
        `SELECT ${TOPIC_COLUMNS} FROM discussion_topics WHERE id = $1`,
        [id],
      );
      const row = result.rows[0];
      return row === undefined ? null : toTopic(row);
    },

    async expireTopics(createdBeforeMs, at) {
      const result = await db.query<{ id: number }>(
        `UPDATE discussion_topics SET status = 'expired', decided_at = $2::timestamptz
          WHERE status = 'open' AND created_at <= $1::timestamptz RETURNING id`,
        [new Date(createdBeforeMs).toISOString(), at.toISOString()],
      );
      return result.rows.length;
    },
  };
}
