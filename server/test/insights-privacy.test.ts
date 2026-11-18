import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { templateText } from "../src/insights/template.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import { FAKE_LLM_KEY, type FakeLlm, startFakeLlm } from "./helpers/fake-llm.ts";
import { FAKE_BOT_TOKEN, type FakeTelegram, startFakeTelegram } from "./helpers/fake-telegram.ts";
import {
  at,
  boot,
  clearAll,
  type InsightsEnv,
  notes,
  planDays,
  rows,
  sweetSpotRide,
  uploadNights,
  uploadRide,
  withRestingHr,
} from "./helpers/insights-harness.ts";

/**
 * Health data is private (CLAUDE.md, ADR 0025): across a whole day of notes, alerts, a ride and a week, with a model that is up, one
 * that fails and a database that fails, no line of the log holds a measured value, a sentence of a note, a fact, the key or the
 * address of the endpoint. Only ids, kinds and counts.
 */

// These tests start the whole service over an in-process database: on a loaded machine they may take a few seconds, never because of a wait.
vi.setConfig({ testTimeout: 30_000, hookTimeout: 30_000 });

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
  vi.waitFor(check, { timeout: 15_000, interval: 20 });

describe("the log", () => {
  it("holds no note, fact, value, key or address after a morning, an alert, a ride and a week, with and without the model", async () => {
    const h = await boot(env, { now: "2026-11-15T05:30:00Z", llm: true });
    await uploadNights(h, withRestingHr({ 0: 59, 1: 58, 2: 58 }));
    await planDays(env, h, ["2026-11-15"]);
    // A model that is up, then one that fails and one that quotes a number it was not given.
    await at(h, "2026-11-15T07:00:00Z");
    await uploadRide(h, sweetSpotRide("ride-a", "2026-11-15T08:00:00Z", 1500, 220, null));
    llm.queue(
      { text: "Resting heart rate is 71 bpm, probably an infection." },
      { status: 500 },
      { status: 500 },
      { status: 500 },
    );
    await uploadRide(h, sweetSpotRide("ride-b", "2026-11-15T09:30:00Z", 1500, 221, null));
    await at(h, "2026-11-15T18:00:00Z");
    await until(async () => expect((await rows(db)).length).toBeGreaterThanOrEqual(4));
    await until(() => expect(notes(env).length).toBeGreaterThanOrEqual(4));

    const logged = h.logging.text();
    const stored = await db.query<{ text: string; facts: unknown }>(
      "SELECT text, facts FROM insights",
    );
    expect(stored.rows.length).toBeGreaterThanOrEqual(4);
    for (const row of stored.rows) {
      expect(logged).not.toContain(row.text.slice(0, 40));
      expect(logged).not.toContain(JSON.stringify(row.facts).slice(0, 60));
    }
    for (const secret of [
      FAKE_LLM_KEY,
      llm.url,
      "127.0.0.1",
      FAKE_BOT_TOKEN,
      "Resting heart rate",
      "resting heart rate",
      "bpm",
      "Sweet spot",
      "usual range",
      "mornings in a row",
      "infection",
      "From the model",
      "quotable",
    ]) {
      expect(logged, secret).not.toContain(secret);
    }
    // What it does hold: kinds, ids, counts.
    expect(logged).toContain("a note was stored");
    expect(logged).toContain('"source":"llm"');
    expect(logged).toContain('"source":"template"');
  });

  it("holds only an error code when the database fails on a note, never the row it quotes", async () => {
    const h = await boot(env, { now: "2026-11-18T05:30:00Z" });
    await uploadNights(h);
    await planDays(env, h, ["2026-11-18"]);
    const { store } = h.services.insights;
    store.insert = async () => {
      throw Object.assign(
        new Error(
          'new row for relation "insights" violates check constraint; Failing row contains (Distinctive health sentence 61 bpm)',
        ),
        { code: "23514" },
      );
    };
    await at(h, "2026-11-18T07:00:00Z");
    const logged = h.logging.text();
    expect(logged).toContain("23514");
    expect(logged).toContain("an insights step failed");
    expect(logged).not.toContain("Distinctive health sentence");
    expect(logged).not.toContain("61 bpm");
    expect(logged).not.toContain("violates check constraint");
  });

  it("holds no value when the Telegram message of a note fails, and sends nothing to a chat that is not the owner's", async () => {
    const h = await boot(env, { now: "2026-11-18T05:30:00Z" });
    await uploadNights(h, withRestingHr({ 0: 58, 1: 58 }));
    await planDays(env, h, ["2026-11-18"]);
    telegram.failNext(1, 400, {
      method: "sendMessage",
      description: "Bad Request: Distinctive health sentence",
    });
    await at(h, "2026-11-18T07:00:00Z");
    await until(() =>
      expect(h.logging.lines.some((l) => /Telegram message failed/.test(l.message ?? ""))).toBe(
        true,
      ),
    );
    const logged = h.logging.text();
    expect(logged).not.toMatch(/Caution this morning|Resting heart rate|bpm/);
    expect(telegram.sent.every((m) => m.chatId === 424242)).toBe(true);
  });
});
