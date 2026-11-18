import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { type InsightsResponse, validateInsightsResponse } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { buildRideFacts } from "../src/insights/facts.ts";
import type { NewInsight } from "../src/insights/store.ts";
import { bearer, COMPANION, expectError, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { rideInput } from "./helpers/insights.ts";
import { sweetSpotRide, uploadRide } from "./helpers/insights-harness.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/**
 * `GET /v1/insights` and the MCP tools `list_insights`, `get_facts`, `list_discussion_topics`, `mark_discussion_topic` (spec §6.5,
 * §8.3, §8.4, ADR 0025): ranges, ordering, local days, the topic of a ride note, the facts the writer saw, and privacy.
 */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    configured: false,
    timezone: "Europe/Paris",
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});
afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await db.exec(`TRUNCATE insights, discussion_topics, alert_streaks RESTART IDENTITY;
    TRUNCATE ride_destinations, rides, ride_metrics, ride_workouts;`);
  h.clock.set("2026-11-18T08:00:00Z");
});

const DAY = 86_400_000;
const mcp = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

const note = (
  kind: NewInsight["kind"],
  key: string,
  createdAt: string,
  text: string,
  rideId: string | null = null,
): NewInsight => ({
  kind,
  key,
  createdAt: new Date(createdAt),
  text,
  rideId,
  source: "template",
  promptVersion: "template.v1",
  factsHash: "0".repeat(64),
  facts: buildRideFacts(rideInput({ id: rideId ?? "ride-1" })),
});

const get = (url: string, device = COMPANION) =>
  h.app.inject({ method: "GET", url, headers: bearer(device) });

async function insights(from: string, to: string): Promise<InsightsResponse> {
  const res = await get(`/v1/insights?from=${from}&to=${to}`);
  expect(res.statusCode, res.body).toBe(200);
  const body: unknown = res.json();
  expect(validateInsightsResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
  return body as InsightsResponse;
}

describe("GET /v1/insights", () => {
  it("needs a device token", async () => {
    const res = await h.app.inject({
      method: "GET",
      url: "/v1/insights?from=2026-11-18&to=2026-11-18",
    });
    expect(res.statusCode).toBe(401);
    expect((await get("/v1/insights?from=2026-11-18&to=2026-11-18", TABLET)).statusCode).toBe(200);
  });

  it("answers an empty list for a range with no notes, as the contract says", async () => {
    const answer = await insights("2026-11-01", "2026-11-18");
    expect(answer).toEqual({
      from: "2026-11-01",
      to: "2026-11-18",
      generatedAt: "2026-11-18T08:00:00.000Z",
      stale: false,
      insights: [],
    });
  });

  it("lists the notes newest first, each as {id, kind, createdAt, text, rideId, topic}", async () => {
    const { store } = h.services.insights;
    await store.insert(note("morning", "2026-11-16", "2026-11-16T06:00:10Z", "Monday note."));
    await store.insert(note("deviation", "2026-11-17:abc", "2026-11-17T06:00:20Z", "Alert."));
    await store.insert(note("morning", "2026-11-17", "2026-11-17T06:00:10Z", "Tuesday note."));
    await store.insert(note("ride", "ride-1", "2026-11-17T18:00:00Z", "Ride note.", "ride-1"));
    const answer = await insights("2026-11-16", "2026-11-18");
    expect(answer.insights.map((i) => [i.id, i.kind, i.text])).toEqual([
      [4, "ride", "Ride note."],
      [2, "deviation", "Alert."],
      [3, "morning", "Tuesday note."],
      [1, "morning", "Monday note."],
    ]);
    expect(answer.insights[0]).toEqual({
      id: 4,
      kind: "ride",
      createdAt: "2026-11-17T18:00:00.000Z",
      text: "Ride note.",
      rideId: "ride-1",
      topic: null,
    });
    expect(Object.keys(answer.insights[1] ?? {}).sort()).toEqual([
      "createdAt",
      "id",
      "kind",
      "rideId",
      "text",
      "topic",
    ]);
  });

  it("takes the day of a note in the rider's time zone, both ends included", async () => {
    const { store } = h.services.insights;
    // Paris is UTC+1 in November: 23:30 UTC on the 17th is 00:30 on the 18th; 22:59 UTC on the 18th is 23:59 on the 18th.
    await store.insert(note("morning", "a", "2026-11-17T22:59:59Z", "Before."));
    await store.insert(note("morning", "b", "2026-11-17T23:00:00Z", "Start of the 18th."));
    await store.insert(note("morning", "c", "2026-11-18T22:59:59Z", "End of the 18th."));
    await store.insert(note("morning", "d", "2026-11-18T23:00:00Z", "After."));
    expect((await insights("2026-11-18", "2026-11-18")).insights.map((i) => i.text)).toEqual([
      "End of the 18th.",
      "Start of the 18th.",
    ]);
    expect((await insights("2026-11-17", "2026-11-17")).insights.map((i) => i.text)).toEqual([
      "Before.",
    ]);
    expect((await insights("2026-11-19", "2026-11-19")).insights.map((i) => i.text)).toEqual([
      "After.",
    ]);
  });

  it("gives a ride note its topic, with the status it has now, and no other note one", async () => {
    const { store } = h.services.insights;
    await store.insert(note("ride", "ride-1", "2026-11-17T18:00:00Z", "Ride note.", "ride-1"), {
      topic: {
        rideId: "ride-1",
        rule: "cut-short",
        text: "It stopped early.",
        openingQuestion: "Why did it stop?",
      },
      limit: { max: 2, windowMs: 7 * DAY },
      now: new Date("2026-11-17T18:00:00Z"),
    });
    await store.insert(note("morning", "2026-11-17", "2026-11-17T06:00:00Z", "Morning."));
    let answer = await insights("2026-11-17", "2026-11-17");
    expect(answer.insights.map((i) => i.topic?.status ?? null)).toEqual(["open", null]);
    expect(answer.insights[0]?.topic).toEqual({
      id: 1,
      text: "It stopped early.",
      openingQuestion: "Why did it stop?",
      rideId: "ride-1",
      createdAt: "2026-11-17T18:00:00.000Z",
      status: "open",
    });
    await mcp("mark_discussion_topic", { id: 1 });
    answer = await insights("2026-11-17", "2026-11-17");
    expect(answer.insights[0]?.topic?.status).toBe("discussed");
  });

  it("shows an open topic as expired once it is 14 days old", async () => {
    const { store } = h.services.insights;
    await store.insert(note("ride", "ride-1", "2026-11-17T18:00:00Z", "Ride note.", "ride-1"), {
      topic: { rideId: "ride-1", rule: "cut-short", text: "t", openingQuestion: "q" },
      limit: { max: 2, windowMs: 7 * DAY },
      now: new Date("2026-11-17T18:00:00Z"),
    });
    h.clock.set("2026-12-01T17:59:59Z");
    expect((await insights("2026-11-17", "2026-11-17")).insights[0]?.topic?.status).toBe("open");
    h.clock.set("2026-12-01T18:00:00Z");
    expect((await insights("2026-11-17", "2026-11-17")).insights[0]?.topic?.status).toBe("expired");
  });

  it.each([
    ["", "invalid_range"],
    ["?from=2026-11-18", "invalid_range"],
    ["?to=2026-11-18", "invalid_range"],
    ["?from=2026-11-19&to=2026-11-18", "invalid_range"],
    ["?from=2026-02-30&to=2026-03-01", "invalid_range"],
    ["?from=yesterday&to=today", "invalid_range"],
    ["?from=2025-11-17&to=2026-11-18", "invalid_range"],
  ] as const)("refuses the range %j as %s", async (query, code) => {
    expectError(await get(`/v1/insights${query}`), code);
  });

  it("serves a year at most: 366 days", async () => {
    expect((await get("/v1/insights?from=2025-11-18&to=2026-11-18")).statusCode).toBe(200);
  });

  it("is not cached", async () => {
    expect((await get("/v1/insights?from=2026-11-18&to=2026-11-18")).headers["cache-control"]).toBe(
      "no-store",
    );
  });
});

// ---- MCP ------------------------------------------------------------------------------------------

describe("list_insights", () => {
  it("lists the last 7 days by default, with how each note was written", async () => {
    const { store } = h.services.insights;
    await store.insert(note("morning", "2026-11-18", "2026-11-18T06:00:00Z", "Today."));
    await store.insert(note("week", "2026-11-09", "2026-11-15T17:00:00Z", "Week."));
    await store.insert(note("morning", "2026-11-01", "2026-11-01T06:00:00Z", "Old."));
    const { json, isError } = await mcp("list_insights");
    expect(isError).toBe(false);
    expect((json as { insights: { text: string }[] }).insights.map((i) => i.text)).toEqual([
      "Today.",
      "Week.",
    ]);
    expect((json as { insights: { source: string }[] }).insights[0]?.source).toBe("template");
    expect(JSON.stringify(json)).not.toContain("factsHash");
  });

  it("filters by kind, takes a range, and adds the prompt version and the facts hash on request", async () => {
    const { store } = h.services.insights;
    await store.insert(note("morning", "2026-11-18", "2026-11-18T06:00:00Z", "Today."));
    await store.insert(note("drift", "2026-11-16", "2026-11-18T06:00:05Z", "Drift."));
    const only = await mcp("list_insights", { kind: "drift", detail: "full" });
    expect(
      (only.json as { insights: { text: string; promptVersion: string; factsHash: string }[] })
        .insights,
    ).toEqual([
      expect.objectContaining({
        text: "Drift.",
        promptVersion: "template.v1",
        factsHash: "0".repeat(64),
      }),
    ]);
    expect(
      (
        (await mcp("list_insights", { from: "2026-11-18", to: "2026-11-18" })).json as {
          count: number;
        }
      ).count,
    ).toBe(2);
  });

  it.each([
    [{ from: "2026-02-30" }, /not a real calendar date/],
    [{ from: "2026-11-19", to: "2026-11-18" }, /before from/],
    [{ from: "2025-01-01", to: "2026-11-18" }, /at most 366/],
  ])("says what is wrong with %j", async (args, message) => {
    const { text, isError } = await mcp("list_insights", args);
    expect(isError).toBe(true);
    expect(text).toMatch(message);
  });
});

describe("the topics", () => {
  async function topics() {
    const { store } = h.services.insights;
    for (const [i, id] of ["ride-1", "ride-2", "ride-3"].entries()) {
      await store.insert(note("ride", id, `2026-11-1${5 + i}T18:00:00Z`, `Note ${id}.`, id), {
        topic: {
          rideId: id,
          rule: i === 0 ? "cut-short" : "power-record",
          text: `Topic ${id}.`,
          openingQuestion: `Question ${id}?`,
        },
        limit: { max: 10, windowMs: 7 * DAY },
        now: new Date(`2026-11-1${5 + i}T18:00:00Z`),
      });
    }
  }

  it("lists the open ones newest first by default, with the ride they are about and the reason that found them", async () => {
    await topics();
    const { json } = await mcp("list_discussion_topics");
    const found = (
      json as {
        topics: {
          id: number;
          rideId: string;
          text: string;
          openingQuestion: string;
          rule: string;
          status: string;
          rideName: string | null;
        }[];
      }
    ).topics;
    expect(found.map((t) => [t.id, t.rideId, t.rule, t.status])).toEqual([
      [3, "ride-3", "power-record", "open"],
      [2, "ride-2", "power-record", "open"],
      [1, "ride-1", "cut-short", "open"],
    ]);
    expect(found[0]).toMatchObject({
      text: "Topic ride-3.",
      openingQuestion: "Question ride-3?",
      rideName: "Sweet spot 3x10",
    });
  });

  it("marks one discussed, and then lists by status", async () => {
    await topics();
    const marked = await mcp("mark_discussion_topic", { id: 2 });
    expect(marked.isError).toBe(false);
    expect((marked.json as { topic: { status: string; decidedAt: string } }).topic).toMatchObject({
      status: "discussed",
      decidedAt: "2026-11-18T08:00:00.000Z",
    });
    expect(
      ((await mcp("list_discussion_topics")).json as { topics: { id: number }[] }).topics.map(
        (t) => t.id,
      ),
    ).toEqual([3, 1]);
    expect(
      (
        (await mcp("list_discussion_topics", { status: "discussed" })).json as {
          topics: { id: number }[];
        }
      ).topics.map((t) => t.id),
    ).toEqual([2]);
    expect(
      (
        (await mcp("list_discussion_topics", { status: "all", limit: 2 })).json as {
          topics: { id: number }[];
        }
      ).topics.map((t) => t.id),
    ).toEqual([3, 2]);
    // Again: nothing changes.
    const again = await mcp("mark_discussion_topic", { id: 2 });
    expect((again.json as { topic: { decidedAt: string } }).topic.decidedAt).toBe(
      "2026-11-18T08:00:00.000Z",
    );
  });

  it("says there is no such topic, and refuses any status but discussed", async () => {
    const missing = await mcp("mark_discussion_topic", { id: 99 });
    expect(missing.isError).toBe(true);
    expect(missing.text).toMatch(/no discussion topic 99/);
    const refused = await mcp("mark_discussion_topic", { id: 1, status: "open" });
    expect(refused.isError).toBe(true);
  });

  it("closes the old ones: after 14 days a topic is expired and cannot be marked discussed", async () => {
    await topics();
    // The topics were written on the 15th, 16th and 17th at 18:00: on 1 December at 00:00 the first two are 14 days old.
    h.clock.set("2026-12-01T00:00:00Z");
    const expired = (await mcp("list_discussion_topics", { status: "expired" })).json as {
      topics: { id: number }[];
    };
    expect(expired.topics.map((t) => t.id)).toEqual([2, 1]);
    expect(
      ((await mcp("list_discussion_topics")).json as { topics: { id: number }[] }).topics.map(
        (t) => t.id,
      ),
    ).toEqual([3]);
    const late = await mcp("mark_discussion_topic", { id: 1 });
    expect((late.json as { topic: { status: string } }).topic.status).toBe("expired");
  });
});

describe("get_facts", () => {
  async function storeRide(): Promise<string> {
    const planned = sweetSpotRide("ride-1794984000000", "2026-11-17T17:00:00Z", 1500, 220, null);
    await uploadRide(h, planned);
    return planned.id;
  }

  it("works the facts of a ride out now when no note exists, and says so", async () => {
    const id = await storeRide();
    const { json, isError } = await mcp("get_facts", { kind: "ride", rideId: id });
    expect(isError).toBe(false);
    const answer = json as {
      stored: boolean;
      note: unknown;
      facts: {
        kind: string;
        ride: { id: string };
        quotable: { value: number; unit: string; label: string }[];
      };
    };
    expect(answer.stored).toBe(false);
    expect(answer.note).toBeNull();
    expect(answer.facts).toMatchObject({ schema: 1, kind: "ride", ride: { id } });
    expect(answer.facts.quotable.length).toBeGreaterThan(5);
    expect(answer.facts.quotable[0]).toEqual({
      label: expect.any(String),
      value: expect.any(Number),
      unit: expect.any(String),
    });
  });

  it("returns the very facts a note was written from once there is one", async () => {
    const id = await storeRide();
    await h.services.insights.tick();
    const stored = await db.query<{ id: number; facts: unknown; facts_hash: string }>(
      "SELECT id, facts, facts_hash FROM insights WHERE kind = 'ride'",
    );
    expect(stored.rows).toHaveLength(1);
    const { json } = await mcp("get_facts", { kind: "ride", rideId: id });
    const answer = json as {
      stored: boolean;
      note: { id: number; source: string; factsHash: string; promptVersion: string };
      facts: unknown;
    };
    expect(answer.stored).toBe(true);
    expect(answer.note).toMatchObject({
      id: stored.rows[0]?.id,
      source: "template",
      factsHash: stored.rows[0]?.facts_hash,
      promptVersion: "template.v1",
    });
    expect(answer.facts).toEqual(stored.rows[0]?.facts);
  });

  it("works out the morning, the week and the alerts' facts for a day", async () => {
    for (const args of [
      { kind: "morning", date: "2026-11-18" },
      { kind: "week", weekStart: "2026-11-11" },
      { kind: "deviation" },
      { kind: "drift", date: "2026-11-18" },
    ]) {
      const { json, isError } = await mcp("get_facts", args);
      expect(isError, JSON.stringify(args)).toBe(false);
      const facts = (json as { facts: { kind: string; quotable: unknown[] } }).facts;
      expect(facts.kind).toBe(args.kind);
      expect(Array.isArray(facts.quotable)).toBe(true);
    }
    // A week is named by its Monday, whichever day is given.
    const week = (await mcp("get_facts", { kind: "week", weekStart: "2026-11-11" })).json as {
      facts: { week: { from: string } };
    };
    expect(week.facts.week.from).toBe("2026-11-09");
  });

  it("says what is wrong: a ride that is not there, a ride id that is not one, a date that is not real", async () => {
    expect((await mcp("get_facts", { kind: "ride", rideId: "ride-nope" })).text).toMatch(
      /no ride ride-nope/,
    );
    expect((await mcp("get_facts", { kind: "ride" })).text).toMatch(/give rideId/);
    expect((await mcp("get_facts", { kind: "ride", rideId: "no/slash" })).text).toMatch(
      /give rideId/,
    );
    expect((await mcp("get_facts", { kind: "morning", date: "2026-02-30" })).text).toMatch(
      /not a real calendar date/,
    );
  });
});

describe("the tools describe themselves", () => {
  it("say what they are for, that numbers are computed by the server, and that no note changes the plan", async () => {
    const { tools } = await client.listTools();
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    expect(by.list_insights?.description).toMatch(/never\s+changed the plan/);
    expect(by.get_facts?.description).toMatch(/quotable/);
    expect(by.get_facts?.description).toMatch(/never recalculate/);
    expect(by.list_discussion_topics?.description).toMatch(/mark_discussion_topic/);
    expect(by.mark_discussion_topic?.description).toMatch(/changes nothing in the plan/);
    expect(JSON.stringify(by.get_facts?.inputSchema)).toContain("morning");
  });
});

describe("what the server logs", () => {
  it("never a note's text, a fact or a topic", async () => {
    const { store } = h.services.insights;
    await store.insert(
      note(
        "morning",
        "2026-11-18",
        "2026-11-18T06:00:00Z",
        "Distinctive sentence about resting heart rate 61.",
      ),
    );
    await get("/v1/insights?from=2026-11-18&to=2026-11-18");
    await mcp("list_insights");
    await mcp("get_facts", { kind: "morning" });
    await mcp("mark_discussion_topic", { id: 1 });
    const logged = `${h.appLogs.join("\n")}\n${h.logging.text()}`;
    expect(logged).not.toContain("Distinctive sentence");
    expect(logged).not.toContain("resting heart rate 61");
  });
});
