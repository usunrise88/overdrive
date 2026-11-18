import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createTelegramClient } from "../src/telegram/client.ts";
import {
  FAKE_BOT_TOKEN,
  type FakeTelegram,
  OWNER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";

/** The bot's client sends the monospace spans of a message as `entities`, with no parse mode, and drops one the clip cut off. */

let telegram: FakeTelegram;

beforeAll(async () => {
  telegram = await startFakeTelegram();
});
afterAll(async () => {
  await telegram.close();
});
beforeEach(() => {
  telegram.reset();
});

const client = () => createTelegramClient({ baseUrl: telegram.url, token: FAKE_BOT_TOKEN });

describe("sendMessage with entities", () => {
  it("sends the spans as they are and asks for no parse mode", async () => {
    await client().sendMessage(OWNER_CHAT, "Ask:\nWhy did it stop?", undefined, [
      { type: "pre", offset: 5, length: 16 },
    ]);
    expect(telegram.sentTo(OWNER_CHAT)[0]?.entities).toEqual([
      { type: "pre", offset: 5, length: 16 },
    ]);
    expect(telegram.callsTo("sendMessage")[0]?.body).not.toHaveProperty("parse_mode");
  });

  it("sends no entities key for a plain message", async () => {
    await client().sendMessage(OWNER_CHAT, "Plain.");
    expect(telegram.callsTo("sendMessage")[0]?.body).not.toHaveProperty("entities");
  });

  it("drops a span that the clip of a very long message cut off, and keeps one before it", async () => {
    const text = `${"a".repeat(3990)}\n${"b".repeat(100)}`;
    await client().sendMessage(OWNER_CHAT, text, undefined, [
      { type: "pre", offset: 0, length: 10 },
      { type: "pre", offset: 3991, length: 100 },
    ]);
    const [sent] = telegram.sentTo(OWNER_CHAT);
    expect(sent?.text.length).toBe(4000);
    expect(sent?.entities).toEqual([{ type: "pre", offset: 0, length: 10 }]);
  });
});
