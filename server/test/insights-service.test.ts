import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { templateText } from "../src/insights/template.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { FAKE_LLM_KEY, type FakeLlm, startFakeLlm } from "./helpers/fake-llm.ts";
import { type FakeTelegram, OWNER_CHAT, startFakeTelegram } from "./helpers/fake-telegram.ts";
import {
  at,
  boot,
  clearAll,
  getInsights,
  type InsightsEnv,
  notes,
  notifications,
  pass,
  planDays,
  restart,
  rows,
  STEADY,
  said,
  sweetSpotRide,
  uploadMorning,
  uploadNights,
  uploadRide,
  withRestingHr,
} from "./helpers/insights-harness.ts";
import type { IntegrationHarness } from "./helpers/integrations.ts";

/**
 * The triggers, with the real services over PGlite and fake plan, Telegram and language-model servers (ADR 0025): the morning note
 * at 07:00 when there is a verdict or a plan, the note after a ride, exactly once across ticks and restarts, the model's text
 * checked and the template used when it is not there.
 */

let db: Db;
let env: InsightsEnv;
let intervals: FakeIntervals;
let telegram: FakeTelegram;
let llm: FakeLlm;

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  telegram = await startFakeTelegram();
  llm = await startFakeLlm(
    (facts) => `From the model. ${facts === null ? "No facts." : templateText(facts as never)}`,
  );
  env = { db, intervals, telegram, llm, started: [] };
});
afterAll(async () => {
  await telegram.close();
  await intervals.close();
  await llm.close();
  await db.close();
});
beforeEach(async () => {
  await clearAll(db);
  intervals.reset();
  telegram.reset();
  llm.reset();
});
afterEach(async () => {
  await Promise.all(env.started.splice(0).map((h) => h.app.close()));
});

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 5000, interval: 20 });

const MORNING = "2026-11-18T07:00:00Z";

describe("the morning note", () => {
  it("is written at 07:00 when there is a verdict, not before, and sent to Telegram once, across ticks and a restart", async () => {
    const h = await boot(env);
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, "2026-11-18T06:59:00Z");
    expect(await rows(db)).toEqual([]);
    expect(said(env)).toEqual([]);

    await at(h, MORNING);
    const [morning] = await rows(db);
    expect(morning).toMatchObject({
      kind: "morning",
      key: "2026-11-18",
      source: "template",
      prompt_version: "template.v1",
      ride_id: null,
    });
    expect(morning?.text).toMatch(/^Ready this morning\./);
    expect(morning?.text).toContain("Planned today: Sweet spot, 40 min");
    await until(() => expect(notes(env).map((m) => m.text)).toEqual([morning?.text]));

    // Again and again, and after a restart: nothing new, nothing sent twice.
    await at(h, "2026-11-18T07:05:00Z");
    await at(h, "2026-11-18T09:00:00Z");
    const h2 = await restart(env, h);
    await at(h2, "2026-11-18T09:05:00Z");
    expect(await rows(db)).toHaveLength(1);
    expect(notes(env)).toHaveLength(1);
    expect(await notifications(db)).toEqual([
      { kind: "insight-morning", key: "2026-11-18", state: "sent" },
    ]);
  });

  it("is written on the minute 07:00 comes by the injectable clock, with no real waiting", async () => {
    const h = await boot(env, { now: "2026-11-18T06:58:00Z" });
    await uploadNights(h);
    expect(h.timers.pending.some((t) => t.kind === "every" && t.ms === 60_000)).toBe(true);
    h.clock.set(MORNING);
    h.timers.tick();
    await h.services.insights.idle();
    expect(await rows(db)).toHaveLength(1);
  });

  it("is not written at all with no verdict and no plan, and is written when the verdict arrives", async () => {
    const h = await boot(env, { now: MORNING });
    // Nothing is known: no nights, no plan.
    await pass(h);
    expect(await rows(db)).toEqual([]);
    // The history is there but not this morning's night: yesterday's verdict is not today's.
    await uploadAll60Until(h, 1);
    await pass(h);
    expect(await rows(db)).toEqual([]);
    // This morning's data arrives (the upload makes the service look again by itself).
    await uploadMorning(h, STEADY);
    await until(async () => expect(await rows(db)).toHaveLength(1));
    expect((await rows(db))[0]?.text).toMatch(/^Ready this morning\./);
  });

  it("is written from the plan alone once two hours have passed without this morning's data, and says so", async () => {
    const h = await boot(env, { now: MORNING });
    await planDays(env, h, ["2026-11-18"]);
    await pass(h);
    expect(await rows(db)).toEqual([]);
    await at(h, "2026-11-18T08:59:00Z");
    expect(await rows(db)).toEqual([]);
    await at(h, "2026-11-18T09:00:00Z");
    const [morning] = await rows(db);
    expect(morning?.text).toMatch(
      /^There is no watch data from this morning yet, so recovery is not rated\./,
    );
    expect(morning?.text).toContain("Sweet spot");
  });

  it("is not written after its window: six hours past the time", async () => {
    const h = await boot(env, { now: "2026-11-18T13:01:00Z" });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await pass(h);
    expect(await rows(db)).toEqual([]);
  });

  it("adjusts the wording for caution and for rest, and never changes the plan", async () => {
    const h = await boot(env, { now: "2026-11-18T05:30:00Z" });
    // Resting heart rate 58 two mornings in a row is caution by the rule table (C3).
    await uploadNights(h, withRestingHr({ 0: 58, 1: 58 }));
    await planDays(env, h, ["2026-11-18"]);
    const before = await h.services.plan.entries({ from: "2026-11-18", to: "2026-11-18" });
    await at(h, MORNING);
    const [morning] = await rows(db);
    expect(morning?.text).toMatch(/^Caution this morning\./);
    expect(morning?.text).toContain("keep the hard parts short");
    expect(morning?.text).toContain("The plan itself stays as it is");
    expect(await h.services.plan.entries({ from: "2026-11-18", to: "2026-11-18" })).toEqual(before);
    expect(intervals.requests.filter((r) => r.method !== "GET")).toEqual([]);
  });

  it("follows INSIGHT_MORNING_TIME, and is off with off", async () => {
    const h = await boot(env, {
      now: "2026-11-18T08:59:00Z",
      config: { INSIGHT_MORNING_TIME: "09:00" },
    });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await pass(h);
    expect(await rows(db)).toEqual([]);
    await at(h, "2026-11-18T09:00:00Z");
    expect(await rows(db)).toHaveLength(1);

    await clearAll(db);
    const off = await boot(env, {
      now: "2026-11-18T09:00:00Z",
      config: { INSIGHT_MORNING_TIME: "off" },
    });
    await uploadNights(off);
    await pass(off);
    expect((await rows(db)).filter((r) => r.kind === "morning")).toEqual([]);
  });

  it("is not written when the insights are switched off, and nothing is sent", async () => {
    const h = await boot(env, { now: MORNING, config: { INSIGHTS_ENABLED: false } });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await pass(h);
    expect(await rows(db)).toEqual([]);
    expect(h.timers.pending.some((t) => t.ms === 60_000)).toBe(false);
  });
});

async function uploadAll60Until(h: IntegrationHarness, firstBack: number): Promise<void> {
  const { history, uploadAll } = await import("./helpers/wellness-data.ts");
  await uploadAll(h.app, history(STEADY, 60, firstBack));
}

describe("the language model", () => {
  it("writes the note when one is configured: its text is stored as the model's, with the prompt that asked for it", async () => {
    const h = await boot(env, { llm: true });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, MORNING);
    const [morning] = await rows(db);
    expect(morning).toMatchObject({
      kind: "morning",
      source: "llm",
      prompt_version: "morning.v1+rules.v1",
    });
    expect(morning?.text).toMatch(/^From the model\. Ready this morning\./);
    expect(llm.calls).toHaveLength(1);
    expect(llm.calls[0]?.authorization).toBe(`Bearer ${FAKE_LLM_KEY}`);
    // The Telegram message is that text and nothing else: no facts, no key.
    await until(() => expect(notes(env).map((m) => m.text)).toEqual([morning?.text]));
  });

  it("sends health data to the model's endpoint only, and to no endpoint at all without one", async () => {
    const h = await boot(env, { llm: false });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, MORNING);
    expect(llm.calls).toEqual([]);
    expect((await rows(db))[0]?.source).toBe("template");
    expect(h.services.insights.usesModel).toBe(false);
    // Half a setting is off, with the reason in the log and no value in it.
    const half = await boot(env, { llm: false, config: { LLM_BASE_URL: llm.url } });
    expect(half.services.insights.usesModel).toBe(false);
    expect(
      half.logging.lines.some(
        (l) => l.level === "warn" && /LLM_MODEL is not set/.test(l.message ?? ""),
      ),
    ).toBe(true);
    expect(half.logging.text()).not.toContain(llm.url);
  });

  it("stores the template, marked as such, when the model's text is rejected twice", async () => {
    llm.queue({ text: "Resting heart rate is 71 bpm." }, { text: "It may be an infection." });
    const h = await boot(env, { llm: true });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, MORNING);
    const [morning] = await rows(db);
    expect(morning).toMatchObject({ source: "template", prompt_version: "template.v1" });
    expect(morning?.text).not.toMatch(/infection|71/);
    expect(llm.calls).toHaveLength(2);
  });

  it("stores the model's second text when the first one broke a rule", async () => {
    llm.queue({ text: "Resting heart rate is 71 bpm." });
    const h = await boot(env, { llm: true });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, MORNING);
    expect((await rows(db))[0]).toMatchObject({ source: "llm" });
    expect(llm.calls).toHaveLength(2);
  });

  it("stores the template and still sends it when the endpoint is down", async () => {
    llm.fallback = () => ({ status: 500 });
    const h = await boot(env, { llm: true });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    await at(h, MORNING);
    expect((await rows(db))[0]).toMatchObject({ source: "template" });
    await until(() => expect(notes(env)).toHaveLength(1));
    // Three tries, the client's own.
    expect(llm.calls).toHaveLength(3);
  });
});

describe("the note after a ride", () => {
  async function plannedRide(
    h: IntegrationHarness,
    seconds: number,
    watts: number,
    start = "2026-11-18T05:00:00Z",
  ) {
    const [planId] = await planDays(env, h, ["2026-11-18"]);
    const spec = sweetSpotRide(`ride-${Date.parse(start)}`, start, seconds, watts, planId ?? null);
    await uploadRide(h, spec);
    return { planId, rideId: spec.id };
  }

  it("is written when the ride is stored, against its plan, and sent after the ride's own summary", async () => {
    const h = await boot(env);
    const { rideId } = await plannedRide(h, 2400, 220);
    await until(async () =>
      expect((await rows(db)).filter((r) => r.kind === "ride")).toHaveLength(1),
    );
    const [note] = await rows(db);
    expect(note).toMatchObject({ kind: "ride", key: rideId, ride_id: rideId, source: "template" });
    expect(note?.text).toContain("Sweet spot");
    expect(note?.text).toContain("40 min planned");
    expect(note?.text).toContain("Targets were met: carry on as planned.");
    await until(() => expect(notes(env).map((m) => m.text)).toEqual([note?.text]));
    // The bot's own numbers came too, first.
    expect(said(env)[0]).toMatch(/^Ride saved: 40 min\./);
  });

  it("is written once: a second pass, the same event again and a restart write nothing", async () => {
    const h = await boot(env);
    const { rideId } = await plannedRide(h, 2400, 220);
    await until(async () => expect(await rows(db)).toHaveLength(1));
    h.services.bus.emit("ride-stored", { rideId, startEpochMillis: 1 });
    await pass(h);
    const h2 = await restart(env, h);
    await pass(h2);
    expect(await rows(db)).toHaveLength(1);
    await until(() => expect(notes(env)).toHaveLength(1));
    expect(await notifications(db)).toEqual([{ kind: "insight-ride", key: rideId, state: "sent" }]);
  });

  it("is written for a ride that was stored while the insights were off, if it is recent, and at the next start", async () => {
    const quiet = await boot(env, { config: { INSIGHTS_ENABLED: false } });
    const planId = (await planDays(env, quiet, ["2026-11-18"]))[0] ?? null;
    await uploadRide(quiet, sweetSpotRide("ride-1", "2026-11-18T04:00:00Z", 2400, 220, planId));
    await pass(quiet);
    expect(await rows(db)).toEqual([]);
    const again = await restart(env, quiet, { config: { INSIGHTS_ENABLED: true } });
    await pass(again);
    expect((await rows(db)).map((r) => r.kind)).toEqual(["ride"]);
  });

  it("makes no note for a ride under a minute, and one for a free ride too", async () => {
    const h = await boot(env);
    await uploadRide(h, {
      id: "ride-short",
      startEpochMillis: Date.parse("2026-11-18T04:00:00Z"),
      seconds: 40,
    });
    await uploadRide(h, {
      id: "ride-free",
      startEpochMillis: Date.parse("2026-11-18T04:30:00Z"),
      seconds: 1800,
      power: () => 160,
    });
    await until(async () => expect((await rows(db)).map((r) => r.key)).toEqual(["ride-free"]));
    await pass(h);
    expect((await rows(db)).map((r) => r.key)).toEqual(["ride-free"]);
    expect((await rows(db))[0]?.text).toContain("free ride");
  });

  it("attaches a discussion topic to a ride cut short, shows it in Telegram with the question as a block to copy, and in GET /v1/insights", async () => {
    const h = await boot(env);
    const { rideId } = await plannedRide(h, 1500, 220); // 25 of 40 minutes: 62%
    await until(() => expect(notes(env)).toHaveLength(1));
    const [message] = notes(env);
    const question =
      "I stopped Sweet spot after 25 of 40 minutes. Can we look at why, and at what to change for the rest of the week?";
    expect(message?.text).toContain(
      "Worth a chat with your coach: The ride stopped at 25 of the planned 40 minutes (63%).",
    );
    expect(message?.text.endsWith(question)).toBe(true);
    expect(message?.entities).toEqual([
      { type: "pre", offset: message?.text.indexOf(question), length: question.length },
    ]);

    const answer = await getInsights(h, "2026-11-18", "2026-11-18");
    expect(answer.insights).toHaveLength(1);
    expect(answer.insights[0]).toMatchObject({
      kind: "ride",
      rideId,
      topic: {
        text: "The ride stopped at 25 of the planned 40 minutes (63%).",
        openingQuestion: question,
        rideId,
        status: "open",
      },
    });
  });

  it("writes at most two topics in any 7 days and the note anyway", async () => {
    const h = await boot(env);
    const [planId] = await planDays(env, h, ["2026-11-18"]);
    for (const [i, hour] of ["01", "02", "03"].entries()) {
      await uploadRide(
        h,
        sweetSpotRide(
          `ride-${Date.parse(`2026-11-18T${hour}:00:00Z`)}`,
          `2026-11-18T${hour}:00:00Z`,
          1500,
          220,
          planId ?? null,
        ),
      );
      await until(async () =>
        expect((await rows(db)).filter((r) => r.kind === "ride")).toHaveLength(i + 1),
      );
    }
    const topics = await h.services.insights.topics(null, 10);
    expect(topics).toHaveLength(2);
    expect((await rows(db)).filter((r) => r.kind === "ride")).toHaveLength(3);
    await until(() => expect(notes(env)).toHaveLength(3));
    expect(notes(env).filter((m) => m.entities.length > 0)).toHaveLength(2);
  });
});

describe("the weekly review", () => {
  it("is written at the weekly summary's time, once, and sent", async () => {
    const h = await boot(env, { now: "2026-11-15T17:59:00Z" }); // Sunday
    await uploadRide(
      h,
      sweetSpotRide(
        `ride-${Date.parse("2026-11-10T06:00:00Z")}`,
        "2026-11-10T06:00:00Z",
        2400,
        220,
        null,
      ),
    );
    await until(async () =>
      expect((await rows(db)).filter((r) => r.kind === "ride")).toHaveLength(1),
    );
    await at(h, "2026-11-15T17:59:00Z");
    expect((await rows(db)).filter((r) => r.kind === "week")).toEqual([]);
    await at(h, "2026-11-15T18:00:00Z");
    const week = (await rows(db)).filter((r) => r.kind === "week");
    expect(week).toHaveLength(1);
    expect(week[0]).toMatchObject({ key: "2026-11-09", source: "template" });
    expect(week[0]?.text).toMatch(/^Week of 9 Nov to 15 Nov: /);
    await until(() => expect(notes(env).map((m) => m.text)).toContain(week[0]?.text));
    await at(h, "2026-11-15T19:00:00Z");
    const h2 = await restart(env, h);
    await pass(h2);
    expect((await rows(db)).filter((r) => r.kind === "week")).toHaveLength(1);
    expect((await notifications(db)).filter((n) => n.kind === "insight-week")).toEqual([
      { kind: "insight-week", key: "2026-11-09", state: "sent" },
    ]);
  });

  it("is not written for a week with nothing planned and no ride, and not when the weekly summary is off", async () => {
    const h = await boot(env, { now: "2026-11-15T18:00:00Z" });
    await pass(h);
    expect((await rows(db)).filter((r) => r.kind === "week")).toEqual([]);
    const off = await boot(env, {
      now: "2026-11-15T18:00:00Z",
      config: { WEEKLY_SUMMARY_TIME: "off" },
    });
    await uploadRide(
      off,
      sweetSpotRide(
        `ride-${Date.parse("2026-11-10T06:00:00Z")}`,
        "2026-11-10T06:00:00Z",
        2400,
        220,
        null,
      ),
    );
    await pass(off);
    expect((await rows(db)).filter((r) => r.kind === "week")).toEqual([]);
  });

  it("is still written late on the same day when the service was down at the time", async () => {
    const h = await boot(env, { now: "2026-11-15T22:00:00Z" });
    await uploadRide(
      h,
      sweetSpotRide(
        `ride-${Date.parse("2026-11-10T06:00:00Z")}`,
        "2026-11-10T06:00:00Z",
        2400,
        220,
        null,
      ),
    );
    await pass(h);
    expect((await rows(db)).filter((r) => r.kind === "week")).toHaveLength(1);
    // A day later it is too late.
    await clearAll(db);
    const later = await boot(env, { now: "2026-11-16T19:00:00Z" });
    await uploadRide(
      later,
      sweetSpotRide(
        `ride-${Date.parse("2026-11-10T06:00:00Z")}`,
        "2026-11-10T06:00:00Z",
        2400,
        220,
        null,
      ),
    );
    await pass(later);
    expect((await rows(db)).filter((r) => r.kind === "week")).toEqual([]);
  });
});

describe("the whole path", () => {
  it("ride stored -> note -> Telegram -> GET /v1/insights, the owner's chat only", async () => {
    const h = await boot(env, { llm: true });
    const [planId] = await planDays(env, h, ["2026-11-18"]);
    const spec = sweetSpotRide(
      `ride-${Date.parse("2026-11-18T05:00:00Z")}`,
      "2026-11-18T05:00:00Z",
      2400,
      220,
      planId ?? null,
    );
    await uploadRide(h, spec);
    await until(() => expect(notes(env)).toHaveLength(1));
    const answer = await getInsights(h, "2026-11-18", "2026-11-18");
    expect(answer.insights.map((i) => [i.kind, i.rideId])).toEqual([["ride", spec.id]]);
    expect(answer.insights[0]?.text).toBe(notes(env)[0]?.text);
    expect(answer.insights[0]?.text).toMatch(/^From the model\. /);
    expect(telegram.sent.every((m) => m.chatId === OWNER_CHAT)).toBe(true);
    // The facts the model saw are the ones stored with the note.
    const sent = llm.calls[0]?.body.messages[1]?.content ?? "";
    const stored = await db.query<{ facts: unknown }>(
      "SELECT facts FROM insights WHERE kind = 'ride'",
    );
    expect(JSON.parse(sent.split("Facts (JSON):\n")[1] ?? "null")).toEqual(stored.rows[0]?.facts);
  });
});

describe("details of the messages", () => {
  it("marks the opening question as a block by its position in UTF-16 units, whatever is in the workout's name", async () => {
    const h = await boot(env);
    const [planId] = await planDays(env, h, ["2026-11-18"], "Tempo é🚴 3x10");
    await uploadRide(
      h,
      sweetSpotRide("ride-accents", "2026-11-18T05:00:00Z", 1500, 220, planId ?? null),
    );
    await until(() => expect(notes(env)).toHaveLength(1));
    const [message] = notes(env);
    const question =
      "I stopped Tempo é🚴 3x10 after 25 of 40 minutes. Can we look at why, and at what to change for the rest of the week?";
    expect(message?.text.endsWith(question)).toBe(true);
    // JavaScript counts UTF-16 units, as Telegram does: the emoji is two, and the block starts after everything that came before it.
    expect(message?.entities).toEqual([
      {
        type: "pre",
        offset: (message?.text.length ?? 0) - question.length,
        length: question.length,
      },
    ]);
    expect(question.length).toBe([...question].length + 1);
  });

  it("counts the mornings of the week by their state in the weekly review", async () => {
    const h = await boot(env, { now: "2026-11-15T17:00:00Z" });
    await uploadNights(h);
    await uploadRide(
      h,
      sweetSpotRide(
        `ride-${Date.parse("2026-11-10T06:00:00Z")}`,
        "2026-11-10T06:00:00Z",
        2400,
        220,
        null,
      ),
    );
    await at(h, "2026-11-15T18:00:00Z");
    const [week] = (await rows(db)).filter((r) => r.kind === "week");
    expect(week?.text).toContain("Recovery: 7 ready, 0 caution, 0 rest mornings.");
  });
});
