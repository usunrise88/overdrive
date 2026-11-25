import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createTelegramClient } from "../src/telegram/client.ts";
import { COMMANDS } from "../src/telegram/index.ts";
import { createTelegramPoller, type TelegramPoller } from "../src/telegram/poller.ts";
import {
  FAKE_BOT_TOKEN,
  type FakeTelegram,
  STRANGER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";
import { hourRide } from "./helpers/outdoor.ts";
import { collectingLogger } from "./helpers/support.ts";
import { openTenantRig, type RigUser, type TenantRig } from "./helpers/tenants.ts";

/**
 * One Telegram bot for several users (multi-tenancy, stage B2): a single poller reads `getUpdates` and hands each update to the
 * bot of the user whose chat it came from. Each user's own bot only answers and sends; it does not poll.
 */

let telegram: FakeTelegram;
let rig: TenantRig;
let ann: RigUser;
let ben: RigUser;
let sharedClient: ReturnType<typeof createTelegramClient>;
let poller: TelegramPoller | null = null;
const logging = collectingLogger();

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 10_000, interval: 20 });

beforeAll(async () => {
  telegram = await startFakeTelegram();
  const client = createTelegramClient({
    baseUrl: telegram.url,
    token: FAKE_BOT_TOKEN,
    log: logging.log,
  });
  rig = await openTenantRig(["ann", "ben"], {
    config: ({ chatId }) => ({
      TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
      TELEGRAM_BASE_URL: telegram.url,
      TELEGRAM_OWNER_CHAT_ID: String(chatId),
    }),
    services: { telegramClient: client },
  });
  ann = rig.users.ann as RigUser;
  ben = rig.users.ben as RigUser;
  sharedClient = client;
});

afterAll(async () => {
  await rig.close();
  await telegram.close();
});

beforeEach(async () => {
  telegram.reset();
  await rig.shared.exec("TRUNCATE bot_state");
  await ann.db.exec("TRUNCATE activities, activity_destinations CASCADE");
  await ben.db.exec("TRUNCATE activities, activity_destinations CASCADE");
});

afterEach(async () => {
  await poller?.stop();
  poller = null;
});

function startPoller(): TelegramPoller {
  const botOf = (userId: number) =>
    Object.values(rig.users).find((u) => u.userId === userId)?.services.telegram ?? null;
  poller = createTelegramPoller({
    client: sharedClient,
    shared: rig.shared,
    directory: rig.directory,
    botOf,
    log: logging.log,
    commandList: COMMANDS,
    pollSeconds: 1,
    sleep: () => new Promise((resolve) => setTimeout(resolve, 5)),
  });
  poller.start();
  return poller;
}

const activitiesOf = async (user: RigUser): Promise<number> =>
  (await user.db.query("SELECT 1 FROM activities")).rows.length;

describe("the shared poller", () => {
  it("sets up once, and sends an update from a user's chat to that user's bot only", async () => {
    startPoller();
    telegram.pushText(ben.chatId, "/help");
    await until(() => expect(telegram.sentTo(ben.chatId)).toHaveLength(1));
    expect(telegram.sentTo(ben.chatId)[0]?.text).toContain("/today");
    expect(telegram.sentTo(ann.chatId)).toHaveLength(0);

    telegram.pushText(ann.chatId, "/help");
    await until(() => expect(telegram.sentTo(ann.chatId)).toHaveLength(1));
    expect(telegram.sentTo(ben.chatId)).toHaveLength(1);

    // One webhook deletion and one command menu, however many updates came.
    expect(telegram.webhookDeleted).toBe(1);
    expect(telegram.callsTo("setMyCommands")).toHaveLength(1);
    expect(telegram.commands.map((c) => c.command)).toEqual(COMMANDS.map((c) => c.command));
  });

  it("keeps its offset in shared.bot_state and resumes from it", async () => {
    startPoller();
    const id = telegram.pushText(ann.chatId, "/help");
    await until(() => expect(telegram.sentTo(ann.chatId)).toHaveLength(1));
    await until(async () => {
      const stored = await rig.shared.query<{ value: string }>(
        "SELECT value FROM bot_state WHERE key = 'telegram.offset'",
      );
      expect(stored.rows[0]?.value).toBe(String(id + 1));
    });
    await poller?.stop();
    telegram.offsets.length = 0;
    startPoller();
    await until(() => expect(telegram.offsets.length).toBeGreaterThan(0));
    expect(telegram.offsets[0]).toBe(id + 1);
    // Nothing was said twice.
    expect(telegram.sentTo(ann.chatId)).toHaveLength(1);
  });

  it("ignores a chat no user has, logging its id and never its content", async () => {
    startPoller();
    telegram.pushText(STRANGER_CHAT, "my secret message text");
    telegram.pushDocument(STRANGER_CHAT, { bytes: hourRide(), fileName: "x.fit" });
    // A known user's message after it still gets through.
    telegram.pushText(ben.chatId, "/help");
    await until(() => expect(telegram.sentTo(ben.chatId)).toHaveLength(1));
    expect(telegram.sent.filter((m) => m.chatId === STRANGER_CHAT)).toHaveLength(0);
    expect(telegram.downloads).toHaveLength(0);
    const line = logging.lines.find((l) => JSON.stringify(l).includes(String(STRANGER_CHAT)));
    expect(line).toBeDefined();
    expect(JSON.stringify(logging.lines)).not.toContain("my secret message text");
  });

  it("imports a FIT document from a user's chat into that user's schema only", async () => {
    startPoller();
    telegram.pushDocument(ben.chatId, { bytes: hourRide(), fileName: "ride.fit" });
    await until(() => expect(telegram.sentTo(ben.chatId)).toHaveLength(1));
    expect(telegram.sentTo(ben.chatId)[0]?.text).toMatch(/^Outdoor ride added/);
    expect(await activitiesOf(ben)).toBe(1);
    expect(await activitiesOf(ann)).toBe(0);
    expect(telegram.downloads).toHaveLength(1);
    // And not in `shared` either: it has no such table.
    const inShared = await rig.shared.query(
      "SELECT 1 FROM information_schema.tables WHERE table_schema = 'shared' AND table_name = 'activities'",
    );
    expect(inShared.rows).toHaveLength(0);
  });

  it("does not let a user's chat reach another user's bot: a message from a disabled user's chat is ignored", async () => {
    await rig.shared.exec("UPDATE users SET disabled_at = now() WHERE handle = 'ann'");
    try {
      startPoller();
      telegram.pushText(ann.chatId, "/help");
      telegram.pushText(ben.chatId, "/help");
      await until(() => expect(telegram.sentTo(ben.chatId)).toHaveLength(1));
      expect(telegram.sentTo(ann.chatId)).toHaveLength(0);
    } finally {
      await rig.shared.exec("UPDATE users SET disabled_at = NULL WHERE handle = 'ann'");
    }
  });
});

describe("a user's own bot", () => {
  it("does not poll: starting it asks Telegram for nothing", async () => {
    ann.services.telegram?.start();
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(telegram.offsets).toHaveLength(0);
    expect(telegram.webhookDeleted).toBe(0);
    await ann.services.telegram?.stop();
  });

  it("still answers only its own chat when an update is handed to it", async () => {
    const bot = ann.services.telegram;
    if (bot === null || bot === undefined) throw new Error("no bot");
    await bot.handleUpdate({
      updateId: 1,
      chatId: ben.chatId,
      message: { text: "/help", document: null },
      callback: null,
    });
    expect(telegram.sent).toHaveLength(0);
    await bot.handleUpdate({
      updateId: 2,
      chatId: ann.chatId,
      message: { text: "/help", document: null },
      callback: null,
    });
    expect(telegram.sentTo(ann.chatId)).toHaveLength(1);
  });
});
