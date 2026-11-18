import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { clearPlan, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  type FakeTelegram,
  OWNER_CHAT,
  STRANGER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";
import {
  clearIntegrations,
  type IntegrationHarness,
  startIntegrationHarness,
} from "./helpers/integrations.ts";
import { hourRide, OUTDOOR_START } from "./helpers/outdoor.ts";

/**
 * Path A through the bot (spec §11.8, ADR 0026): the owner sends a FIT document to the Telegram bot, which downloads it
 * (`getFile`, owner-only chat, at most 16 MB here and 20 MB for Telegram), imports it through the same service function as the
 * companion's upload, and answers with a short summary, or in plain words why it did not. Against the fake Telegram.
 */

let db: Db;
let intervals: FakeIntervals;
let telegram: FakeTelegram;
const started: IntegrationHarness[] = [];

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 5000, interval: 20 });

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  telegram = await startFakeTelegram();
});
afterAll(async () => {
  await telegram.close();
  await intervals.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  await clearIntegrations(db);
  await clearWellness(db);
  await db.exec("TRUNCATE ride_destinations, rides, profile");
  intervals.reset();
  telegram.reset();
});
afterEach(async () => {
  await Promise.all(started.splice(0).map((h) => h.app.close()));
});

async function boot(): Promise<IntegrationHarness> {
  const h = startIntegrationHarness(db, { intervals, telegram, now: "2027-03-14T12:00:00Z" });
  started.push(h);
  await h.services.profile.update({ maxHeartRate: 190, thresholdHeartRate: 170 });
  h.start();
  await h.services.refresher?.refresh();
  return h;
}

const said = (): string[] => telegram.sentTo(OWNER_CHAT).map((m) => m.text);

/** Sends a document as the owner and waits for the bot's answer. */
async function send(file: Parameters<FakeTelegram["pushDocument"]>[1]): Promise<string> {
  const before = telegram.sentTo(OWNER_CHAT).length;
  telegram.pushDocument(OWNER_CHAT, file);
  await until(() => expect(telegram.sentTo(OWNER_CHAT)).toHaveLength(before + 1));
  return telegram.sentTo(OWNER_CHAT)[before]?.text ?? "";
}

describe("a FIT file sent to the bot", () => {
  it("is downloaded and imported, and the answer is a short summary: date, duration, distance, heart rate and load", async () => {
    const h = await boot();
    const text = await send({
      bytes: hourRide(),
      fileName: "2027-03-14-morning-ride.fit",
      mimeType: "application/octet-stream",
    });
    expect(text.split("\n")).toEqual([
      "Outdoor ride added: Sun 14 Mar, 1 h, 27.0 km.",
      "Heart rate: 150 bpm average.",
      "Load: 61 (approximate, from heart rate).",
    ]);
    expect(telegram.downloads).toHaveLength(1);
    expect(telegram.callsTo("getFile")).toHaveLength(1);

    const stored = await db.query<{ id: string; source: string }>(
      "SELECT id, source FROM activities",
    );
    expect(stored.rows).toHaveLength(1);
    expect(stored.rows[0]?.source).toBe("fit");
    // The ride goes on to intervals.icu like one from the companion: the file, as it came (the forwarder runs in this harness).
    await until(() => expect(intervals.activities).toHaveLength(1));
    expect(intervals.activities[0]).toMatchObject({
      external_id: `overdrive-${stored.rows[0]?.id}`,
      bytes: hourRide().length,
    });
    await until(async () => {
      const queue = await db.query<{ state: string }>("SELECT state FROM activity_destinations");
      expect(queue.rows).toEqual([{ state: "done" }]);
    });
    expect(h.logging.text()).not.toMatch(/"bpm"/);
  });

  it("is 'already imported' the second time, whichever door the first came through", async () => {
    const h = await boot();
    const bytes = hourRide();
    // The companion's door first.
    const first = await h.app.inject({
      method: "POST",
      url: "/v1/activities/fit",
      headers: {
        "content-type": "application/vnd.ant.fit",
        authorization: `Bearer ${(await import("./helpers/app.ts")).COMPANION.token}`,
      },
      payload: bytes,
    });
    expect(first.statusCode).toBe(201);
    const text = await send({ bytes, fileName: "ride.fit" });
    expect(text.split("\n")).toEqual(["Already imported.", "Sun 14 Mar, 1 h, 27.0 km."]);
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
    expect(count.rows[0]?.n).toBe("1");
  });

  it("says the ride is already there when another file of the same ride arrives", async () => {
    await boot();
    await send({ bytes: hourRide(), fileName: "a.fit" });
    const text = await send({ bytes: hourRide({ heartRate: () => 151 }), fileName: "b.fit" });
    expect(text.split("\n")[0]).toBe(
      "That ride is already in your history (same start and length).",
    );
  });

  it("says there is no load yet when no maximum and threshold heart rate are known", async () => {
    const h = await boot();
    await h.services.profile.update({ maxHeartRate: null, thresholdHeartRate: null });
    const text = await send({ bytes: hourRide(), fileName: "ride.fit" });
    expect(text).toContain("Load: not worked out yet");
    expect(text).toContain("Outdoor ride added");
  });

  it.each([
    [
      "a document that is not a FIT file",
      { bytes: Buffer.from("%PDF-1.4"), fileName: "notes.pdf", mimeType: "application/pdf" },
      /^That is not a FIT file\./,
    ],
    [
      "a .fit file whose content is not FIT",
      { bytes: Buffer.from("hello, this is not a fit file"), fileName: "x.fit" },
      /^Not imported: that is not a valid FIT file \(/,
    ],
    [
      "a FIT file that is a run",
      { bytes: hourRide({ sport: 1 }), fileName: "run.fit" },
      /^Not imported: This FIT file is not a cycling ride \(its sport is running\); only cycling is imported\.$/,
    ],
    [
      "a FIT file with no ride in it",
      { bytes: hourRide({ noRecords: true }), fileName: "empty.fit" },
      /^Not imported: This FIT file holds no ride/,
    ],
  ])("answers in plain words for %s", async (_name, file, pattern) => {
    await boot();
    const text = await send(file);
    expect(text).toMatch(pattern);
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
    expect(count.rows[0]?.n).toBe("0");
  });

  it("does not even download a file that is over the limit, and says what the limit is", async () => {
    await boot();
    const text = await send({
      bytes: hourRide(),
      fileName: "big.fit",
      size: 17 * 1024 * 1024,
    });
    expect(text).toBe(
      "That file is too large: I take FIT files up to 16 MB (Telegram lets bots download up to 20 MB).",
    );
    expect(telegram.downloads).toHaveLength(0);
    expect(telegram.callsTo("getFile")).toHaveLength(0);
  });

  it("says it could not download a file that Telegram will not give", async () => {
    await boot();
    telegram.failNext(1, 400, { method: "getFile", description: "Bad Request: file is too big" });
    const text = await send({ bytes: hourRide(), fileName: "ride.fit" });
    expect(text).toBe("I could not download that file from Telegram. Try sending it again.");
  });

  it("answers only the owner: a stranger's document is ignored without a word and nothing is downloaded", async () => {
    const h = await boot();
    telegram.pushDocument(STRANGER_CHAT, { bytes: hourRide(), fileName: "ride.fit" });
    // The owner's help is the next thing the bot answers, so by then the stranger's update has been handled.
    telegram.pushText(OWNER_CHAT, "/help");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toContain("/today");
    expect(telegram.sent.every((m) => m.chatId === OWNER_CHAT)).toBe(true);
    expect(telegram.downloads).toHaveLength(0);
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM activities");
    expect(count.rows[0]?.n).toBe("0");
    expect(
      h.logging.lines.some(
        (l) => l.message?.includes("another chat ignored") && l.fields.chatId === STRANGER_CHAT,
      ),
    ).toBe(true);
  });

  it("is mentioned in /help", async () => {
    await boot();
    telegram.pushText(OWNER_CHAT, "/help");
    await until(() => expect(said()).toHaveLength(1));
    expect(said()[0]).toContain("Send me a .fit file from your bike computer (up to 16 MB)");
  });

  it("never writes a measured value or the file's name in the log", async () => {
    const h = await boot();
    await send({ bytes: hourRide({ heartRate: () => 233 }), fileName: "secret-name-2027.fit" });
    const log = h.logging.text();
    expect(log).not.toContain("secret-name");
    expect(log).not.toMatch(/(?<![0-9a-f])233(?![0-9a-f])/);
    expect(OUTDOOR_START).toBeGreaterThan(0);
  });
});
