import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { fitFixture, rideFixture } from "./helpers/app.ts";
import { openTenancyTestDb, resetSchema, type TenancyTestDb } from "./helpers/db.ts";
import {
  FAKE_BOT_TOKEN,
  type FakeTelegram,
  STRANGER_CHAT,
  startFakeTelegram,
} from "./helpers/fake-telegram.ts";
import { callTool, connectWithToken } from "./helpers/mcp.ts";
import { type StartEnv, type Started, startEnv, startOn } from "./helpers/start.ts";

/**
 * Two users on one installation, started the way `main.ts` starts it (`start.ts`): the owner from a fresh database, two more
 * made through the admin API. Each has their own data, token and Telegram chat; the one bot serves both.
 */

let t: TenancyTestDb;
let telegram: FakeTelegram;
let config: StartEnv;
let started: Started;

const ANN_CHAT = 7101;
const BEN_CHAT = 7102;

beforeAll(async () => {
  t = await openTenancyTestDb();
  telegram = await startFakeTelegram();
});

afterAll(async () => {
  await telegram.close();
  await t.close();
});

beforeEach(async () => {
  await resetSchema(t.db);
  telegram.reset();
  config = await startEnv({
    TELEGRAM_BOT_TOKEN: FAKE_BOT_TOKEN,
    TELEGRAM_BASE_URL: telegram.url,
  });
  started = await startOn(t, config);
});

afterEach(async () => {
  await started.stop();
  // `stop()` has aborted the poller's last long poll; a round trip settles the fake server before the next `reset()`.
  await fetch(`${telegram.url}/bot${FAKE_BOT_TOKEN}/getMe`, { method: "POST" });
});

const admin = (method: string, path: string, body?: unknown) =>
  fetch(`${config.adminUrl}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${config.adminToken}`,
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

interface Made {
  id: number;
  handle: string;
  deviceToken: string;
  mcpToken: string;
}

async function makeUser(handle: string, chat: number): Promise<Made> {
  const created = await admin("POST", "/admin/users", { handle, telegramChatId: chat });
  expect(created.status).toBe(201);
  const user = (await created.json()) as { id: number; deviceToken: string };
  const mcp = await admin("POST", `/admin/users/${user.id}/tokens`, {
    kind: "mcp",
    name: "claude",
  });
  expect(mcp.status).toBe(201);
  const { token } = (await mcp.json()) as { token: string };
  return { id: user.id, handle, deviceToken: user.deviceToken, mcpToken: token };
}

const api = (token: string, path: string, init: RequestInit = {}) =>
  fetch(`${config.baseUrl}/v1${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, ...(init.headers as object) },
  });

const record = JSON.stringify(rideFixture("fit-golden-3s.json"));
const fit = fitFixture("fit-golden-3s.fit");

async function upload(token: string): Promise<number[]> {
  const put = await api(token, "/rides/fit-golden", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: record,
  });
  const putFit = await api(token, "/rides/fit-golden/fit", {
    method: "PUT",
    headers: { "content-type": "application/vnd.ant.fit" },
    body: new Uint8Array(fit),
  });
  return [put.status, putFit.status];
}

async function rideIds(user: Made): Promise<string[]> {
  const client = await connectWithToken(new URL(`${config.baseUrl}/mcp`), user.mcpToken);
  try {
    const result = await callTool(client, "list_rides", {
      from: "2000-01-01",
      to: "2099-12-31",
    });
    expect(result.isError, result.text).toBe(false);
    return (result.json as { rides: { id: string }[] }).rides.map((r) => r.id);
  } finally {
    await client.close();
  }
}

const until = (check: () => void | Promise<void>) =>
  vi.waitFor(check, { timeout: 10_000, interval: 20 });

describe("two users on one installation", () => {
  it("keeps each user's rides, profile and tokens apart, though both upload the same ride id and file", async () => {
    const ann = await makeUser("ann", ANN_CHAT);
    const ben = await makeUser("ben", BEN_CHAT);
    expect([ann.id, ben.id]).toEqual([2, 3]);

    // The same id and the same bytes: each lands in its own schema.
    expect(await upload(ann.deviceToken)).toEqual([201, 201]);
    expect(await upload(ben.deviceToken)).toEqual([201, 201]);
    // A repeat by the same user is still recognised as one.
    expect(await upload(ann.deviceToken)).toEqual([200, 200]);
    for (const id of [ann.id, ben.id]) {
      const own = await t.db.query<{ n: string }>(`SELECT count(*)::text AS n FROM t_${id}.rides`);
      expect(own.rows[0]?.n).toBe("1");
    }
    expect(await rideIds(ann)).toEqual(["fit-golden"]);
    expect(await rideIds(ben)).toEqual(["fit-golden"]);
    // The owner has none.
    const owner = await api(config.deviceToken, "/me");
    expect(await owner.json()).toMatchObject({ userId: 1 });
    expect((await t.db.query("SELECT 1 FROM t_1.rides")).rows).toHaveLength(0);

    // A profile change reaches only its user.
    const patch = await api(ann.deviceToken, "/profile", {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ftp: 280 }),
    });
    expect(patch.status).toBe(200);
    const annProfile = (await (await api(ann.deviceToken, "/profile")).json()) as { ftp: number };
    const benProfile = (await (await api(ben.deviceToken, "/profile")).json()) as { ftp: number };
    expect(annProfile.ftp).toBe(280);
    expect(benProfile.ftp).not.toBe(280);

    // Each token names its own user, and a token of one kind does not work as the other.
    expect(await (await api(ann.deviceToken, "/me")).json()).toMatchObject({ handle: "ann" });
    expect(await (await api(ben.deviceToken, "/me")).json()).toMatchObject({ handle: "ben" });
    expect(
      (
        await fetch(`${config.baseUrl}/mcp`, {
          method: "POST",
          headers: {
            authorization: `Bearer ${ann.deviceToken}`,
            "content-type": "application/json",
          },
          body: "{}",
        })
      ).status,
    ).toBe(401);
  });

  it("hands a Telegram update to the user whose chat it came from, and ignores an unknown chat", async () => {
    await makeUser("ann", ANN_CHAT);
    await makeUser("ben", BEN_CHAT);
    await until(() => expect(telegram.webhookDeleted).toBe(1));

    telegram.pushText(BEN_CHAT, "/help");
    await until(() => expect(telegram.sentTo(BEN_CHAT)).toHaveLength(1));
    expect(telegram.sentTo(ANN_CHAT)).toHaveLength(0);

    telegram.pushText(ANN_CHAT, "/help");
    await until(() => expect(telegram.sentTo(ANN_CHAT)).toHaveLength(1));
    expect(telegram.sentTo(BEN_CHAT)).toHaveLength(1);

    // An unknown chat gets no answer; its id is logged so it can be entered, its text is not.
    const before = telegram.sent.length;
    telegram.pushText(STRANGER_CHAT, "secret words /today");
    await until(() =>
      expect(
        started.logging.lines.some((l) => l.fields.chatId === STRANGER_CHAT && l.level === "warn"),
      ).toBe(true),
    );
    expect(telegram.sent).toHaveLength(before);
    expect(started.logging.text()).not.toContain("secret words");
    // One webhook deletion and one menu, for the one bot.
    expect(telegram.webhookDeleted).toBe(1);
    expect(telegram.callsTo("setMyCommands")).toHaveLength(1);
  });

  it("deletes a user with their handle as confirmation: schema, tokens and chat go; the owner needs allow-owner", async () => {
    const ann = await makeUser("ann", ANN_CHAT);
    const ben = await makeUser("ben", BEN_CHAT);
    await upload(ben.deviceToken);
    const schema = `t_${ben.id}`;
    const exists = async (name: string): Promise<boolean> =>
      (await t.db.query("SELECT 1 FROM information_schema.schemata WHERE schema_name = $1", [name]))
        .rows.length > 0;
    expect(await exists(schema)).toBe(true);
    expect((await api(ben.deviceToken, "/me")).status).toBe(200);

    expect((await admin("DELETE", `/admin/users/${ben.id}`, { confirm: "ann" })).status).toBe(400);
    expect((await admin("DELETE", `/admin/users/${ben.id}`, { confirm: "ben" })).status).toBe(204);

    expect(await exists(schema)).toBe(false);
    expect((await api(ben.deviceToken, "/me")).status).toBe(401);
    const client = connectWithToken(new URL(`${config.baseUrl}/mcp`), ben.mcpToken);
    await expect(client).rejects.toThrow();
    // Ann is untouched.
    expect((await api(ann.deviceToken, "/me")).status).toBe(200);
    expect(await exists(`t_${ann.id}`)).toBe(true);

    // Ben's chat is no longer anyone's.
    telegram.pushText(BEN_CHAT, "/help");
    await until(() =>
      expect(started.logging.lines.some((l) => l.fields.chatId === BEN_CHAT)).toBe(true),
    );
    expect(telegram.sentTo(BEN_CHAT)).toHaveLength(0);

    // The owner is user 1.
    expect((await admin("DELETE", "/admin/users/1", { confirm: "owner" })).status).toBe(409);
    expect(await exists("t_1")).toBe(true);
    expect((await api(config.deviceToken, "/me")).status).toBe(200);
    expect((await admin("DELETE", "/admin/users/1", { confirm: "owner allow-owner" })).status).toBe(
      204,
    );
    expect(await exists("t_1")).toBe(false);
    expect((await api(config.deviceToken, "/me")).status).toBe(401);
  });
});
