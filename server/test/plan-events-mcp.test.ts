import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { type PlanEvent, validatePlanEvent } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { clearPlan, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { plannedEvent, steady } from "./helpers/harness.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";

/**
 * `list_plan_events` and `resolve_plan_event` (spec §12.2, §8.4, ADR 0035): the queue the coach agent works from and how it says it is
 * done. The service only detects and queues; resolving changes nothing in the plan, the profile or the notebook.
 */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, { staticTokens: [{ name: "coach", token: STATIC_TOKEN }] });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});
afterAll(async () => {
  await client.close();
  await h.close();
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  await db.exec("TRUNCATE plan_events, plan_event_cursor, plan_history");
  fake.reset();
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

type Listed = PlanEvent & { summary: string };
type Listing = { status: string; total: number; count: number; events: Listed[] };

const missed = (day: string, id: string, name = "Sweet spot 3x10") =>
  h.services.planEvents.emit({
    kind: "missed",
    day,
    workoutId: id,
    detail: { name, plannedSeconds: 3600, plannedLoad: 62 },
  });

describe("the tools and what they say about themselves", () => {
  it("are described for a model: start every session by checking them, nothing moves by itself", async () => {
    const { tools } = await client.listTools();
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    expect(by.list_plan_events?.annotations?.readOnlyHint).toBe(true);
    expect(by.resolve_plan_event?.annotations?.readOnlyHint).toBe(false);
    expect(by.resolve_plan_event?.annotations?.destructiveHint).toBe(false);
    const list = by.list_plan_events?.description ?? "";
    expect(list).toMatch(/START EVERY SESSION BY CHECKING THEM/);
    for (const kind of ["missed", "moved", "deleted", "variant", "illness", "travel"]) {
      expect(list, kind).toContain(kind);
    }
    expect(list).toMatch(/The service moves nothing by itself/);
    expect(list).toMatch(/methodology section/);
    const resolve = by.resolve_plan_event?.description ?? "";
    expect(resolve).toMatch(/dropped is required and explicit/);
    expect(resolve).toMatch(/a valid outcome for a missed session/);
    expect(resolve).toMatch(/sent to the rider in Telegram/);
    expect(by.resolve_plan_event?.inputSchema.required).toEqual([
      "id",
      "resolutionNote",
      "dropped",
    ]);
    const resolveProperties = (by.resolve_plan_event?.inputSchema.properties ?? {}) as Record<
      string,
      { type?: string }
    >;
    expect(resolveProperties.dropped?.type).toBe("boolean");
    const listProperties = (by.list_plan_events?.inputSchema.properties ?? {}) as Record<
      string,
      { enum?: string[] }
    >;
    expect(listProperties.kind?.enum).toEqual([
      "missed",
      "moved",
      "deleted",
      "variant",
      "illness",
      "travel",
    ]);
  });
});

describe("list_plan_events", () => {
  it("is empty when nothing needs attention", async () => {
    const { json, isError } = await call("list_plan_events");
    expect(isError).toBe(false);
    expect(json).toEqual({ status: "open", total: 0, count: 0, events: [] });
  });

  it("lists the open events oldest first, each in the contract's shape with a plain summary", async () => {
    await missed("2026-11-16", "icu-1", "Endurance");
    h.clock.set("2026-11-18T09:00:00Z");
    await h.services.planEvents.declare("illness", "2026-11-20");
    await missed("2026-11-17", "icu-2");
    const { json } = await call("list_plan_events");
    const body = json as Listing;
    expect(body).toMatchObject({ status: "open", total: 3, count: 3 });
    expect(body.events.map((e) => [e.kind, e.day])).toEqual([
      ["missed", "2026-11-16"],
      ["illness", "2026-11-18"],
      ["missed", "2026-11-17"],
    ]);
    expect(body.events.map((e) => e.summary)).toEqual([
      "Endurance on Mon 16 Nov was not ridden.",
      "The rider said they are ill, from Wed 18 Nov to Fri 20 Nov.",
      "Sweet spot 3x10 on Tue 17 Nov was not ridden.",
    ]);
    for (const { summary: _summary, ...event } of body.events) {
      expect(validatePlanEvent(event), JSON.stringify(event)).toMatchObject({ ok: true });
    }
  });

  it("filters by status and kind, and limits, newest first once resolved", async () => {
    const a = await missed("2026-11-15", "icu-1", "A");
    const b = await missed("2026-11-16", "icu-2", "B");
    await missed("2026-11-17", "icu-3", "C");
    await h.services.planEvents.declare("travel", null);
    h.clock.set("2026-11-18T10:00:00Z");
    await call("resolve_plan_event", {
      id: a.event.id,
      resolutionNote: "Dropped A.",
      dropped: true,
    });
    h.clock.set("2026-11-18T11:00:00Z");
    await call("resolve_plan_event", {
      id: b.event.id,
      resolutionNote: "Moved B.",
      dropped: false,
    });

    const open = (await call("list_plan_events")).json as Listing;
    expect(open.events.map((e) => e.detail.name ?? e.kind)).toEqual(["C", "travel"]);
    const resolved = (await call("list_plan_events", { status: "resolved" })).json as Listing;
    expect(resolved.events.map((e) => [e.detail.name, e.dropped, e.resolutionNote])).toEqual([
      ["B", false, "Moved B."],
      ["A", true, "Dropped A."],
    ]);
    const all = (await call("list_plan_events", { status: "all" })).json as Listing;
    expect(all.total).toBe(4);
    const missedOnly = (await call("list_plan_events", { status: "all", kind: "missed" }))
      .json as Listing;
    expect(missedOnly.events.map((e) => e.detail.name)).toEqual(["C", "B", "A"]);
    const limited = (await call("list_plan_events", { status: "all", limit: 2 })).json as Listing;
    expect(limited).toMatchObject({ total: 4, count: 2 });
    const bad = await call("list_plan_events", { limit: 500 });
    expect(bad.isError).toBe(true);
  });
});

describe("resolve_plan_event", () => {
  it("resolves with the agent's note and an explicit dropped, and says the rider was not told when there is no bot", async () => {
    const { event } = await missed("2026-11-17", "icu-2");
    h.clock.set("2026-11-18T10:30:00Z");
    const { json, isError } = await call("resolve_plan_event", {
      id: event.id,
      resolutionNote: "  Dropped it; Thursday stays as it is.  ",
      dropped: true,
    });
    expect(isError).toBe(false);
    const body = json as { resolved: boolean; toldToRider: boolean; note: string; event: Listed };
    expect(body).toMatchObject({
      resolved: true,
      toldToRider: false,
      event: {
        id: event.id,
        status: "resolved",
        dropped: true,
        resolutionNote: "Dropped it; Thursday stays as it is.",
        resolvedAt: "2026-11-18T10:30:00.000Z",
      },
    });
    expect(body.note).toMatch(/NOT told in Telegram/);
    const { summary: _summary, ...plain } = body.event;
    expect(validatePlanEvent(plain), JSON.stringify(plain)).toMatchObject({ ok: true });
    expect(((await call("list_plan_events")).json as Listing).total).toBe(0);
  });

  it("says the rider is told when the bot is set up", async () => {
    const withBot = await startMcpHarness(db, fake, {
      staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
      config: {
        TELEGRAM_BOT_TOKEN: "1:AAFakeTokenForTestsOnly_0123456789abcdefgh",
        TELEGRAM_OWNER_CHAT_ID: "42",
      },
    });
    try {
      const other = await connectWithToken(withBot.mcpUrl, STATIC_TOKEN);
      const { event } = await missed("2026-11-17", "icu-2");
      const result = await callTool(other, "resolve_plan_event", {
        id: event.id,
        resolutionNote: "Fine.",
        dropped: false,
      });
      expect(result.json).toMatchObject({ resolved: true, toldToRider: true });
      expect(result.json).not.toHaveProperty("note");
      await other.close();
    } finally {
      await withBot.close();
    }
  });

  it("changes nothing when the event is already resolved, and says so", async () => {
    const { event } = await missed("2026-11-17", "icu-2");
    await call("resolve_plan_event", { id: event.id, resolutionNote: "First.", dropped: true });
    const again = await call("resolve_plan_event", {
      id: event.id,
      resolutionNote: "Second.",
      dropped: false,
    });
    expect(again.isError).toBe(false);
    expect(again.json).toMatchObject({
      resolved: false,
      alreadyResolved: true,
      event: { dropped: true, resolutionNote: "First." },
    });
  });

  it("fails in words for what the caller can fix", async () => {
    const { event } = await missed("2026-11-17", "icu-2");
    const cases: [Record<string, unknown>, RegExp][] = [
      [{ id: 9999, resolutionNote: "x", dropped: false }, /there is no plan event 9999/],
      [{ id: event.id, resolutionNote: "   ", dropped: false }, /resolutionNote is empty/],
      [{ id: event.id, resolutionNote: "n".repeat(700), dropped: false }, /at most 600/],
      [{ id: event.id, resolutionNote: "x" }, /dropped|required|expected boolean/i],
      [{ id: event.id, resolutionNote: "x", dropped: "yes" }, /dropped|expected boolean/i],
      [{ id: 0, resolutionNote: "x", dropped: true }, /id|too small|>=1/i],
    ];
    for (const [args, message] of cases) {
      const result = await call("resolve_plan_event", args);
      expect(result.isError, JSON.stringify(args)).toBe(true);
      expect(result.text).toMatch(message);
    }
    // Nothing was resolved by any of them.
    expect(((await call("list_plan_events")).json as Listing).total).toBe(1);
  });

  it("moves nothing in the plan, the profile or the notebook", async () => {
    fake.addEvent(plannedEvent("2026-11-20", "Threshold", [steady(600, 60), steady(1200, 95)]));
    await h.services.refresher?.refresh();
    const { event } = await missed("2026-11-17", "icu-2");
    const before = fake.requests.length;
    await call("resolve_plan_event", { id: event.id, resolutionNote: "Dropped.", dropped: true });
    expect(fake.requests.length).toBe(before);
    expect((await db.query("SELECT count(*)::int AS n FROM notebook_versions")).rows).toEqual([
      { n: 0 },
    ]);
    expect(((await call("get_profile")).json as { ftp: number | null }).ftp).toBeNull();
  });
});

describe("the plan events and the agent's own planning tools", () => {
  it("make an event of what the rider does in the calendar, never of what the tools do", async () => {
    const keep = fake.addEvent(
      plannedEvent("2026-11-20", "Threshold", [steady(600, 60), steady(1200, 95)]),
    );
    const move = fake.addEvent(plannedEvent("2026-11-21", "Endurance", [steady(1800, 65)]));
    const drop = fake.addEvent(plannedEvent("2026-11-22", "Sweet spot", [steady(1200, 88)]));
    const rider = fake.addEvent(plannedEvent("2026-11-23", "Recovery", [steady(1200, 50)]));
    await h.services.refresher?.refresh();

    // The agent plans through the tools.
    expect(
      (await call("update_workout", { id: `icu-${move.id}`, date: "2026-11-24" })).isError,
    ).toBe(false);
    expect((await call("delete_workout", { id: `icu-${drop.id}` })).isError).toBe(false);
    await h.services.refresher?.refresh();
    expect(((await call("list_plan_events")).json as Listing).total).toBe(0);

    // The rider moves one and deletes another in intervals.icu itself.
    keep.start_date_local = "2026-11-25T00:00:00";
    fake.events.splice(fake.events.indexOf(rider), 1);
    await h.services.refresher?.refresh();
    const body = (await call("list_plan_events")).json as Listing;
    expect(body.events.map((e) => [e.kind, e.day, e.detail.name]).sort()).toEqual([
      ["deleted", "2026-11-23", "Recovery"],
      ["moved", "2026-11-20", "Threshold"],
    ]);
    expect(body.events.find((e) => e.kind === "moved")?.summary).toBe(
      "The rider moved Threshold from Fri 20 Nov to Wed 25 Nov.",
    );
  });
});
