import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { Db } from "../src/db.ts";
import { createEventBus } from "../src/events.ts";
import { createPlanStore } from "../src/plan/store.ts";
import { createTelegram } from "../src/telegram/index.ts";
import { asRide, putFit, putRecord, rideFixture } from "./helpers/app.ts";
import { clearPlan, openMigratedTestDb, sharedDbOf } from "./helpers/db.ts";
import { type FakeGoogle, startFakeGoogle } from "./helpers/fake-google.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  FAKE_BOT_TOKEN,
  type FakeTelegram,
  OWNER_CHAT,
  STRANGER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";
import { buildFit } from "./helpers/fit.ts";
import {
  clearIntegrations,
  type IntegrationHarness,
  type IntegrationOptions,
  planWorkout,
  startIntegrationHarness,
} from "./helpers/integrations.ts";
import { collectingLogger, testClock } from "./helpers/support.ts";

let db: Db;
let intervals: FakeIntervals;
let telegram: FakeTelegram;
let google: FakeGoogle;
const started: IntegrationHarness[] = [];
const extraBots: { stop(): Promise<void> }[] = [];

/** Waits for a condition, polling quickly; the first assertion that holds ends it. */
const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 15_000, interval: 20 });

beforeAll(async () => {
  db = await openMigratedTestDb();
  intervals = await startFakeIntervals();
  telegram = await startFakeTelegram();
  google = await startFakeGoogle();
});
afterAll(async () => {
  await google.close();
  await telegram.close();
  await intervals.close();
  await db.close();
});
beforeEach(async () => {
  await clearPlan(db);
  await clearIntegrations(db);
  await db.exec("TRUNCATE ride_destinations, rides");
  intervals.reset();
  telegram.reset();
  google.reset();
});
afterEach(async () => {
  await Promise.all(extraBots.splice(0).map((bot) => bot.stop()));
  await Promise.all(started.splice(0).map((h) => h.app.close()));
});

/** The service with the bot on (and the plan source), started, its first plan read done. */
async function boot(options: IntegrationOptions = {}): Promise<IntegrationHarness> {
  const h = startIntegrationHarness(db, { intervals, telegram, ...options });
  started.push(h);
  h.start();
  await h.services.refresher?.refresh();
  return h;
}

/** What the bot has said to the owner so far. */
const said = (): string[] => telegram.sentTo(OWNER_CHAT).map((m) => m.text);

const waitForMessages = (count: number) =>
  until(() => expect(telegram.sentTo(OWNER_CHAT)).toHaveLength(count));

/** Sends a command as the owner and waits for the answer. */
async function ask(
  text: string,
): Promise<{ text: string; id: number; buttons: { text: string; data: string }[][] }> {
  const before = telegram.sentTo(OWNER_CHAT).length;
  telegram.pushText(OWNER_CHAT, text);
  await waitForMessages(before + 1);
  const last = telegram.sentTo(OWNER_CHAT)[before];
  if (last === undefined) throw new Error("no answer");
  return last;
}

/** Presses a button under message `messageId` and waits for the edit it causes. */
async function press(messageId: number, data: string) {
  const before = telegram.edits.length + telegram.sentTo(OWNER_CHAT).length;
  telegram.pushCallback(OWNER_CHAT, messageId, data);
  await until(() =>
    expect(telegram.edits.length + telegram.sentTo(OWNER_CHAT).length).toBeGreaterThan(before),
  );
  return telegram.edits.at(-1);
}

describe("who the bot answers", () => {
  it("answers the owner's chat and nobody else, and logs the stranger's chat id but not what was said", async () => {
    const h = await boot();
    telegram.pushText(STRANGER_CHAT, "/today please tell me the secret plan");
    telegram.pushCallback(STRANGER_CHAT, 55, "sk!:icu-1");
    const answer = await ask("/help");

    expect(answer.text).toContain("/today");
    // Only the owner's chat got anything, and the stranger's button changed nothing.
    expect(telegram.sent.every((m) => m.chatId === OWNER_CHAT)).toBe(true);
    expect(telegram.edits).toHaveLength(0);
    expect(telegram.answered).toHaveLength(0);
    expect(telegram.callsTo("deleteEvent")).toHaveLength(0);

    const ignored = h.logging.lines.filter((l) => l.message?.includes("another chat ignored"));
    expect(ignored.map((l) => l.fields)).toEqual([
      expect.objectContaining({ chatId: STRANGER_CHAT }),
      expect.objectContaining({ chatId: STRANGER_CHAT }),
    ]);
    const log = h.logging.text();
    expect(log).not.toContain("secret plan");
    expect(log).not.toContain("sk!:icu-1");
  });

  it("is in setup mode without TELEGRAM_OWNER_CHAT_ID: it listens, logs the chat id to use, and says nothing", async () => {
    const h = await boot({ owner: false });
    telegram.pushText(OWNER_CHAT, "/today");
    await until(() =>
      expect(
        h.logging.lines.some((l) => l.message?.includes("TELEGRAM_OWNER_CHAT_ID is not set")),
      ).toBe(true),
    );
    const line = h.logging.lines.find((l) =>
      l.message?.includes("TELEGRAM_OWNER_CHAT_ID is not set"),
    );
    expect(line?.fields).toMatchObject({ chatId: OWNER_CHAT });
    expect(telegram.sent).toHaveLength(0);
    expect(telegram.commands).toHaveLength(0);
  });

  it("is off without a token, and also when only the chat id is set", async () => {
    const off = startIntegrationHarness(db, { intervals });
    expect(off.services.telegram).toBeNull();
    expect(off.logging.text()).toContain("Telegram is not configured");
    const half = startIntegrationHarness(db, {
      intervals,
      config: { TELEGRAM_OWNER_CHAT_ID: String(OWNER_CHAT) },
    });
    expect(half.services.telegram).toBeNull();
    expect(half.logging.text()).toContain(
      "TELEGRAM_OWNER_CHAT_ID is set but TELEGRAM_BOT_TOKEN is not",
    );
    off.start();
    half.start();
    await off.app.close();
    await half.app.close();
    // A bot that was on would have dropped the webhook and set its menu at start. (Not "no calls at all": the
    // poll of the previous test's bot can still land on the fake a moment after its reset on a loaded machine.)
    expect(telegram.webhookDeleted).toBe(0);
    expect(telegram.commands).toHaveLength(0);
  });

  it("drops a webhook and sets the command menu when it starts", async () => {
    await boot();
    await until(() => expect(telegram.commands.length).toBeGreaterThan(0));
    expect(telegram.webhookDeleted).toBe(1);
    expect(telegram.commands.map((c) => c.command)).toEqual([
      "today",
      "week",
      "skip",
      "move",
      "ill",
      "travel",
      "help",
    ]);
  });
});

describe("polling", () => {
  it("keeps its place across a restart: a command is answered once", async () => {
    const first = await boot();
    await ask("/help");
    // The next poll asks for what comes after the answered update; on a real database that
    // follows the saved offset by a moment, so wait for it rather than look once.
    await until(() => expect(telegram.offsets.at(-1)).toBeGreaterThanOrEqual(1));
    await first.app.close();
    started.pop();

    await boot();
    await until(() => expect(telegram.offsets.at(-1)).toBe(2));
    // The first run confirmed update 1; the second asks for what comes after it.
    expect(said()).toHaveLength(1);
    const stored = await sharedDbOf(db).query<{ value: string }>(
      "SELECT value FROM bot_state WHERE key = 'telegram.offset'",
    );
    expect(stored.rows[0]?.value).toBe("2");
  });

  it("survives Telegram being unreachable: logs the first failure, keeps trying, and works again", async () => {
    const logging = collectingLogger();
    const bot = createTelegram({
      config: {
        TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
        TELEGRAM_OWNER_CHAT_ID: String(OWNER_CHAT),
        TELEGRAM_BASE_URL: telegram.url,
      },
      db,
      stateDb: sharedDbOf(db),
      plan: createPlanStore(db, createEventBus()),
      bus: createEventBus(),
      intervals: null,
      refresher: null,
      log: logging.log,
      timezone: "Europe/Paris",
      now: testClock("2026-11-18T08:00:00Z").now,
      sleep: async () => {},
      botSleep: () => new Promise((resolve) => setTimeout(resolve, 5)),
    });
    if (bot === null) throw new Error("no bot");
    extraBots.push(bot);
    telegram.setUnreachable(true);
    bot.start();
    await until(() =>
      expect(logging.lines.some((l) => l.message?.includes("polling failed"))).toBe(true),
    );
    telegram.setUnreachable(false);
    telegram.pushText(OWNER_CHAT, "/today");
    await waitForMessages(1);
    // No plan source: the answer says so in plain words.
    expect(said()[0]).toContain("INTERVALS_API_KEY is not set");
    await until(() =>
      expect(logging.lines.some((l) => l.message?.includes("works again"))).toBe(true),
    );
    expect(
      logging.lines.filter((l) => l.message?.includes("polling failed")).length,
    ).toBeLessThanOrEqual(2);
    await bot.stop();
  });

  it("waits a long time after a refused token and never prints it", async () => {
    const logging = collectingLogger();
    const waits: number[] = [];
    const bot = createTelegram({
      config: {
        TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
        TELEGRAM_OWNER_CHAT_ID: String(OWNER_CHAT),
        TELEGRAM_BASE_URL: telegram.url,
      },
      db,
      stateDb: sharedDbOf(db),
      plan: createPlanStore(db, createEventBus()),
      bus: createEventBus(),
      intervals: null,
      refresher: null,
      log: logging.log,
      timezone: "Europe/Paris",
      sleep: async () => {},
      botSleep: async (ms) => {
        waits.push(ms);
        await new Promise((resolve) => setTimeout(resolve, 5));
      },
    });
    if (bot === null) throw new Error("no bot");
    extraBots.push(bot);
    telegram.failNext(1, 401, {
      method: "getUpdates",
      description: `Unauthorized ${FAKE_BOT_TOKEN}`,
    });
    bot.start();
    await until(() => expect(waits.length).toBeGreaterThan(0));
    expect(waits[0]).toBe(5 * 60_000);
    await bot.stop();
    expect(logging.text()).toContain("TELEGRAM_BOT_TOKEN");
    expect(logging.text()).not.toContain(FAKE_BOT_TOKEN);
    expect(logging.text()).not.toContain(FAKE_BOT_TOKEN.split(":")[1] ?? "x");
  });
});

describe("/help and what it does not understand", () => {
  it("lists the commands, also as /start and with the bot's name", async () => {
    await boot();
    const help = await ask("/help");
    for (const command of ["/today", "/week", "/skip", "/move", "/help"]) {
      expect(help.text).toContain(command);
    }
    expect((await ask("/start@OverdriveBot")).text).toBe(help.text);
  });

  it("says plainly when it does not know", async () => {
    await boot();
    expect((await ask("/nonsense")).text).toContain("/help");
    expect((await ask("hello there")).text).toContain("/help");
  });
});

describe("/today and /week", () => {
  it("say what is planned, with the steps in words and what is done", async () => {
    planWorkout(intervals, "2026-11-18", "Sweet spot");
    planWorkout(intervals, "2026-11-20", "Endurance");
    await boot();

    const today = await ask("/today");
    expect(today.text).toBe(
      [
        "Today, Wed 18 Nov, from 07:00",
        "- Sweet spot, 40 min.",
        "  10 min at 60% FTP, 20 min at 88% FTP, 10 min at 50% FTP.",
      ].join("\n"),
    );

    const week = await ask("/week");
    expect(week.text.split("\n")).toEqual([
      "The next 7 days",
      "Wed 18 Nov: Sweet spot (40 min)",
      "Thu 19 Nov: nothing planned",
      "Fri 20 Nov: Endurance (40 min)",
      "Sat 21 Nov: nothing planned",
      "Sun 22 Nov: nothing planned",
      "Mon 23 Nov: nothing planned",
      "Tue 24 Nov: nothing planned",
    ]);
  });

  it("marks a workout as done once a ride is stored for it", async () => {
    const id = planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot();
    const record = asRide(
      rideFixture("synthetic-5min.json"),
      "ride-done",
      Date.parse("2026-11-18T06:30:00Z"),
    );
    record.ride.workoutId = `icu-${id}`;
    expect((await putRecord(h.app, "ride-done", record)).statusCode).toBe(201);
    expect((await putFit(h.app, "ride-done", buildFit())).statusCode).toBe(201);
    await waitForMessages(1); // the summary of the ride

    const today = await ask("/today");
    expect(today.text).toContain("- Sweet spot, 40 min. Done: ridden 5 min.");
    expect((await ask("/week")).text).toContain("Wed 18 Nov: Sweet spot (40 min), done");
  });

  it("says nothing is planned, and tells the owner when the plan is the last one known", async () => {
    const h = await boot();
    expect((await ask("/today")).text).toBe("Nothing is planned for today.");
    intervals.failAll(500);
    await h.services.refresher?.refresh();
    expect((await ask("/today")).text).toContain("could not be reached at the last refresh");
  });

  it("says the plan is not connected when there is no plan source", async () => {
    const h = startIntegrationHarness(db, { telegram });
    started.push(h);
    h.start();
    for (const command of ["/today", "/week", "/skip", "/move"]) {
      const answer = await ask(command);
      expect(answer.text, command).toContain("INTERVALS_API_KEY is not set");
    }
  });
});

describe("/skip", () => {
  it("asks first, then deletes the event in intervals.icu, the cache and the calendar, and counts it as skipped", async () => {
    const id = planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot({ google });
    await h.services.calendar?.runOnce();
    expect(google.live()).toHaveLength(1);

    const question = await ask("/skip");
    expect(question.text).toBe(
      "Skip Sweet spot on Wed 18 Nov? It is deleted from the calendar in intervals.icu.",
    );
    expect(question.buttons).toEqual([
      [
        { text: "Yes, skip it", data: `sk!:icu-${id}` },
        { text: "Keep it", data: "no" },
      ],
    ]);
    // Nothing has changed yet.
    expect(intervals.events).toHaveLength(1);

    const edit = await press(question.id, `sk!:icu-${id}`);
    expect(edit?.text).toBe("Skipped Sweet spot on Wed 18 Nov.");
    expect(edit?.buttons).toEqual([]);
    expect(telegram.answered).toHaveLength(1);
    expect(intervals.events).toHaveLength(0);
    expect(await h.services.plan.get(`icu-${id}`)).toBeNull();
    const history = await db.query<{ status: string }>(
      "SELECT status FROM plan_history WHERE plan_id = $1",
      [`icu-${id}`],
    );
    expect(history.rows).toEqual([{ status: "skipped" }]);

    // The plan changed: the calendar follows.
    await until(async () => {
      await h.services.calendar?.runOnce();
      expect(google.live()).toHaveLength(0);
    });
    expect((await ask("/today")).text).toBe("Nothing is planned for today.");
  });

  it("leaves it alone when the owner says keep it", async () => {
    const id = planWorkout(intervals, "2026-11-18");
    await boot();
    const question = await ask("/skip");
    const edit = await press(question.id, "no");
    expect(edit?.text).toBe("Left as it is.");
    expect(intervals.events).toHaveLength(1);
    expect(
      await db.query("SELECT 1 FROM planned_workouts WHERE id = $1", [`icu-${id}`]),
    ).toMatchObject({
      rows: [expect.anything()],
    });
  });

  it("offers each workout when there are several that day, and skips only the chosen one", async () => {
    const first = planWorkout(intervals, "2026-11-18", "Morning spin");
    const second = planWorkout(intervals, "2026-11-18", "Evening intervals");
    await boot();
    const choice = await ask("/skip");
    expect(choice.text).toBe("Which workout do you want to skip?");
    expect(choice.buttons.map((row) => row.map((b) => b.text))).toEqual([
      ["Morning spin (Wed 18 Nov)"],
      ["Evening intervals (Wed 18 Nov)"],
      ["Cancel"],
    ]);
    const confirm = await press(choice.id, `sk:icu-${second}`);
    expect(confirm?.text).toContain("Skip Evening intervals on Wed 18 Nov?");
    await press(choice.id, `sk!:icu-${second}`);
    expect(intervals.events.map((e) => e.id)).toEqual([first]);
  });

  it("goes to the next day when today's workout is already ridden", async () => {
    const today = planWorkout(intervals, "2026-11-18", "Today's");
    const tomorrow = planWorkout(intervals, "2026-11-19", "Tomorrow's");
    const h = await boot();
    const record = asRide(
      rideFixture("synthetic-5min.json"),
      "ride-done",
      Date.parse("2026-11-18T06:30:00Z"),
    );
    record.ride.workoutId = `icu-${today}`;
    await putRecord(h.app, "ride-done", record);
    await putFit(h.app, "ride-done", buildFit());
    await waitForMessages(1); // the summary of the ride
    const question = await ask("/skip");
    expect(question.text).toContain("Skip Tomorrow's on Thu 19 Nov?");
    expect(question.buttons[0]?.[0]?.data).toBe(`sk!:icu-${tomorrow}`);
  });

  it("says so when there is nothing to skip", async () => {
    await boot();
    expect((await ask("/skip")).text).toContain("nothing to skip");
  });

  it("says nothing was changed when intervals.icu cannot be reached", async () => {
    const id = planWorkout(intervals, "2026-11-18");
    const h = await boot();
    const question = await ask("/skip");
    intervals.failAll(503);
    const edit = await press(question.id, `sk!:icu-${id}`);
    expect(edit?.text).toMatch(/^Could not skip it: .*Nothing was changed\.$/);
    intervals.failAll(0);
    expect(intervals.events).toHaveLength(1);
    expect(await h.services.plan.get(`icu-${id}`)).not.toBeNull();
    const history = await db.query("SELECT 1 FROM plan_history WHERE status = 'skipped'");
    expect(history.rows).toHaveLength(0);
  });

  it("says a workout that has gone from the plan has gone, and refuses odd button data", async () => {
    const id = planWorkout(intervals, "2026-11-18");
    await boot();
    const question = await ask("/skip");
    intervals.events.length = 0;
    await db.exec("TRUNCATE planned_workouts");
    expect((await press(question.id, `sk!:icu-${id}`))?.text).toBe(
      "That workout is no longer in the plan.",
    );
    expect((await press(question.id, "sk!:../../etc"))?.text).toContain("do not know");
    expect((await press(question.id, "whatever"))?.text).toContain("do not know");
  });

  it("falls back to a new message when the old one cannot be edited", async () => {
    planWorkout(intervals, "2026-11-18");
    await boot();
    const question = await ask("/skip");
    telegram.failNext(1, 400, {
      method: "editMessageText",
      description: "Bad Request: message to edit not found",
    });
    telegram.pushCallback(OWNER_CHAT, question.id, "no");
    await waitForMessages(2);
    expect(said()[1]).toBe("Left as it is.");
  });
});

describe("/move", () => {
  it("offers the next seven days as buttons and moves the event in intervals.icu, the cache and the calendar", async () => {
    const id = planWorkout(intervals, "2026-11-18", "Sweet spot");
    const h = await boot({ google });
    await h.services.calendar?.runOnce();

    const question = await ask("/move");
    expect(question.text).toBe("Move Sweet spot (now Wed 18 Nov) to which day?");
    const days = question.buttons.flat().filter((b) => b.data.startsWith("md:"));
    expect(days.map((b) => b.text)).toEqual([
      "Thu 19",
      "Fri 20",
      "Sat 21",
      "Sun 22",
      "Mon 23",
      "Tue 24",
      "Wed 25",
    ]);
    expect(question.buttons.at(-1)).toEqual([{ text: "Cancel", data: "no" }]);

    const edit = await press(question.id, `md:icu-${id}:2026-11-20`);
    expect(edit?.text).toBe("Moved Sweet spot from Wed 18 Nov to Fri 20 Nov.");
    const put = intervals.requestsTo("PUT", `/events/${id}`);
    expect(put).toHaveLength(1);
    expect(put[0]?.json).toEqual({ start_date_local: "2026-11-20T00:00:00" });
    expect(await h.services.plan.get(`icu-${id}`)).toMatchObject({ date: "2026-11-20" });

    await until(async () => {
      await h.services.calendar?.runOnce();
      expect(google.live()[0]?.start?.dateTime).toBe("2026-11-20T06:00:00.000Z");
    });
    expect(google.live()).toHaveLength(1);
    expect((await ask("/week")).text).toContain("Fri 20 Nov: Sweet spot");
  });

  it("does not offer the day the workout is already on, and refuses a day out of reach", async () => {
    const id = planWorkout(intervals, "2026-11-19", "Sweet spot");
    await boot();
    const question = await ask("/move");
    const days = question.buttons.flat().filter((b) => b.data.startsWith("md:"));
    expect(days.map((b) => b.text)).not.toContain("Thu 19");
    expect(days).toHaveLength(6);
    expect((await press(question.id, `md:icu-${id}:2027-01-01`))?.text).toBe(
      "I cannot move it to that day.",
    );
    expect((await press(question.id, `md:icu-${id}:2026-11-17`))?.text).toBe(
      "I cannot move it to that day.",
    );
    expect((await press(question.id, `md:icu-${id}:2026-11-19`))?.text).toBe(
      "Sweet spot is already on Thu 19 Nov.",
    );
    expect(intervals.requestsTo("PUT", `/events/${id}`)).toHaveLength(0);
  });

  it("asks which workout when there are several, and says nothing was changed when intervals.icu fails", async () => {
    planWorkout(intervals, "2026-11-18", "One");
    const two = planWorkout(intervals, "2026-11-18", "Two");
    await boot();
    const choice = await ask("/move");
    expect(choice.text).toBe("Which workout do you want to move?");
    const days = await press(choice.id, `mv:icu-${two}`);
    expect(days?.text).toBe("Move Two (now Wed 18 Nov) to which day?");
    intervals.failAll(500);
    const edit = await press(choice.id, `md:icu-${two}:2026-11-21`);
    expect(edit?.text).toMatch(/^Could not move it: .*Nothing was changed\.$/);
    intervals.failAll(0);
    expect(intervals.events.find((e) => e.id === two)?.start_date_local).toBe(
      "2026-11-18T00:00:00",
    );
  });
});

describe("secrets", () => {
  it("the bot token never reaches the log or the database, even when Telegram echoes it", async () => {
    const id = planWorkout(intervals, "2026-11-18");
    const h = await boot();
    telegram.failNext(1, 400, {
      method: "sendMessage",
      description: `Bad Request: ${FAKE_BOT_TOKEN}`,
    });
    telegram.pushText(OWNER_CHAT, "/today"); // its answer fails
    await until(() => expect(telegram.callsTo("sendMessage").length).toBeGreaterThanOrEqual(1));
    telegram.failNext(5, 500);
    telegram.pushText(OWNER_CHAT, "/today");
    telegram.pushText(OWNER_CHAT, "/help");
    await until(() => expect(said().length).toBeGreaterThanOrEqual(1));
    expect(id).toBeGreaterThan(0);

    const secret = FAKE_BOT_TOKEN.split(":")[1] ?? "x";
    const log = h.logging.text();
    expect(log.length).toBeGreaterThan(50);
    expect(log).not.toContain(FAKE_BOT_TOKEN);
    expect(log).not.toContain(secret);
    const stored = JSON.stringify(
      (await db.query("SELECT * FROM bot_notifications")).rows.concat(
        (await sharedDbOf(db).query("SELECT * FROM bot_state")).rows,
      ),
    );
    expect(stored).not.toContain(secret);
  });
});
