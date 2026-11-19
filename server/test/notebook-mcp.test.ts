import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { validateNotebookResponse } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION, expectError, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  authorizeQuery,
  callTool,
  codeOf,
  connectWithToken,
  LOOPBACK_REDIRECT,
  type McpHarness,
  PASSPHRASE,
  pkce,
  register,
  STATIC_TOKEN,
  startMcpHarness,
  submitAuthorization,
  tokenRequest,
} from "./helpers/mcp.ts";

/**
 * The coach notebook over MCP and REST (spec §11.6, §12.4, §8.3, §8.4, ADR 0035): the tools and what they say about themselves, the
 * cap and the answer that asks the agent to condense, the methodology's reason, `GET /v1/notebook`, and the game master's notebook,
 * which only a session whose token carries the `game-master` scope can see and which the REST route never serves.
 */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let coach: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
    config: { NOTEBOOK_MAX_CHARS: 2000, NOTEBOOK_LOG_MAX_CHARS: 500 },
  });
  coach = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});
afterAll(async () => {
  await coach.close();
  await h.close();
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await db.exec("TRUNCATE notebook_versions");
  h.clock.set("2026-11-18T08:00:00Z");
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(coach, name, args);

type Section = {
  key: string;
  title: string;
  text: string;
  version: number;
  updatedAt: string | null;
  reason: string | null;
  chars: number;
};

const NOTEBOOK_TOOLS = [
  "get_notebook",
  "update_notebook_section",
  "append_notebook_log",
  "list_notebook_versions",
  "rollback_notebook_section",
];

describe("the tools and what they say about themselves", () => {
  it("lists the five notebook tools, described for a model, with reads and writes marked", async () => {
    const { tools } = await coach.listTools();
    const by = Object.fromEntries(tools.map((t) => [t.name, t]));
    for (const name of NOTEBOOK_TOOLS) {
      expect(by[name], name).toBeDefined();
      expect(by[name]?.description?.length, name).toBeGreaterThan(300);
      expect(by[name]?.title).toBeTruthy();
    }
    for (const name of ["get_notebook", "list_notebook_versions"]) {
      expect(by[name]?.annotations?.readOnlyHint, name).toBe(true);
    }
    // Every write keeps the old text, so none is destructive.
    for (const name of [
      "update_notebook_section",
      "append_notebook_log",
      "rollback_notebook_section",
    ]) {
      expect(by[name]?.annotations?.readOnlyHint, name).toBe(false);
      expect(by[name]?.annotations?.destructiveHint, name).toBe(false);
    }
    expect(by.get_notebook?.description).toMatch(/START EVERY SESSION BY READING IT/);
    expect(by.get_notebook?.description).toMatch(/methodology/);
    expect(by.update_notebook_section?.description).toMatch(
      /CHANGING THE METHODOLOGY IS DELIBERATE/,
    );
    expect(by.update_notebook_section?.description).toMatch(/tells the rider in Telegram/);
    expect(by.update_notebook_section?.description).toMatch(/every earlier version is kept/);
    expect(by.append_notebook_log?.description).toMatch(/RECORD THE REASON FOR EVERY PLAN CHANGE/);
    expect(by.append_notebook_log?.description).toMatch(/condense/);
    expect(by.append_notebook_log?.description).toMatch(/This is an answer, not an error/);
    expect(by.rollback_notebook_section?.description).toMatch(/NEW version/);
  });

  it("takes the caps from the settings and names them in the descriptions", async () => {
    const { tools } = await coach.listTools();
    expect(tools.find((t) => t.name === "update_notebook_section")?.description).toMatch(
      /2000 characters in all, 500 for the log/,
    );
  });

  it("lists the sections as the only values a section can have", async () => {
    const { tools } = await coach.listTools();
    const get = tools.find((t) => t.name === "get_notebook");
    const properties = get?.inputSchema.properties as Record<string, { enum?: string[] }>;
    expect(properties.section?.enum).toEqual([
      "profile",
      "what_works",
      "what_does_not",
      "injuries",
      "methodology",
      "open_questions",
      "log",
    ]);
    const update = tools.find((t) => t.name === "update_notebook_section");
    expect(update?.inputSchema.required).toEqual(["section", "text"]);
    const rollback = tools.find((t) => t.name === "rollback_notebook_section");
    expect(rollback?.inputSchema.required).toEqual(["section", "version"]);
  });

  it("tells every session in the server instructions to read the notebook first, record reasons and check the methodology", async () => {
    const text = coach.getInstructions() ?? "";
    expect(text).toMatch(/EVERY SESSION STARTS BY READING IT \(get_notebook/);
    expect(text).toMatch(/list_plan_events/);
    expect(text).toMatch(/Check EVERY plan change against the methodology section/);
    expect(text).toMatch(/RECORD THE REASON FOR ANY PLAN CHANGE with append_notebook_log/);
    expect(text).toMatch(
      /update_notebook_section on methodology with a written reason, and the rider is told in Telegram/,
    );
    expect(text).toMatch(/resolve_plan_event/);
    expect(text).toMatch(/dropping a missed session is a valid outcome/);
  });
});

describe("reading and writing", () => {
  it("says an empty notebook is empty, with every section at version 0", async () => {
    const { json, isError } = await call("get_notebook");
    expect(isError).toBe(false);
    const body = json as {
      scope: string;
      limits: unknown;
      totalChars: number;
      note: string;
      sections: Section[];
      log: unknown;
    };
    expect(body.scope).toBe("coach");
    expect(body.limits).toEqual({ maxChars: 2000, logMaxChars: 500 });
    expect(body.totalChars).toBe(0);
    expect(body.note).toMatch(/first session/);
    expect(body.sections.map((s) => [s.key, s.version])).toEqual([
      ["profile", 0],
      ["what_works", 0],
      ["what_does_not", 0],
      ["injuries", 0],
      ["methodology", 0],
      ["open_questions", 0],
      ["log", 0],
    ]);
    expect(body.log).toEqual({ chars: 0, maxChars: 500, overCap: false });
  });

  it("writes a section, reads it back whole and alone, with version numbers", async () => {
    const written = await call("update_notebook_section", {
      section: "injuries",
      text: "Left knee: avoid cadence below 80.",
      reason: "The rider said so.",
    });
    expect(written.isError).toBe(false);
    expect(written.json).toMatchObject({
      saved: true,
      section: "injuries",
      version: 1,
      previousVersion: 0,
      chars: 34,
      totalChars: 34,
    });
    expect(written.json).not.toHaveProperty("announcedToRider");

    const whole = (await call("get_notebook")).json as { sections: Section[]; note?: string };
    expect(whole.note).toBeUndefined();
    expect(whole.sections.find((s) => s.key === "injuries")).toMatchObject({
      text: "Left knee: avoid cadence below 80.",
      version: 1,
      reason: "The rider said so.",
      updatedAt: "2026-11-18T08:00:00.000Z",
    });
    const one = (await call("get_notebook", { section: "injuries" })).json as {
      section: Section;
      sections?: unknown;
    };
    expect(one.section).toMatchObject({ key: "injuries", version: 1 });
    expect(one.sections).toBeUndefined();
  });

  it("appends dated lines and lists the versions with their kind, reason and a preview", async () => {
    await call("append_notebook_log", { entry: "Moved Thursday to Friday: a long day." });
    h.clock.set("2026-11-19T08:00:00Z");
    await call("append_notebook_log", {
      entry: "Dropped the missed sweet spot.",
      date: "2026-11-18",
    });
    const log = ((await call("get_notebook", { section: "log" })).json as { section: Section })
      .section;
    expect(log.text).toBe(
      "2026-11-18: Moved Thursday to Friday: a long day.\n2026-11-18: Dropped the missed sweet spot.",
    );
    const listing = (await call("list_notebook_versions", { section: "log" })).json as {
      current: number;
      versions: { version: number; kind: string; reason: string; preview: string; text?: string }[];
    };
    expect(listing.current).toBe(2);
    expect(listing.versions.map((v) => [v.version, v.kind, v.reason])).toEqual([
      [2, "append", "log entry"],
      [1, "append", "log entry"],
    ]);
    expect(listing.versions[1]?.preview).toBe("2026-11-18: Moved Thursday to Friday: a long day.");
    expect(listing.versions[1]?.text).toBeUndefined();
    const withText = (
      await call("list_notebook_versions", { section: "log", includeText: true, limit: 1 })
    ).json as { versions: { text: string }[] };
    expect(withText.versions).toHaveLength(1);
    expect(withText.versions[0]?.text).toContain("Dropped the missed sweet spot.");
  });

  it("rolls a section back to an earlier version as a new one", async () => {
    await call("update_notebook_section", { section: "what_works", text: "Morning rides." });
    await call("update_notebook_section", { section: "what_works", text: "Evening rides." });
    const back = await call("rollback_notebook_section", {
      section: "what_works",
      version: 1,
      reason: "Evenings did not work.",
    });
    expect(back.json).toMatchObject({ saved: true, version: 3, previousVersion: 2 });
    const listing = (
      await call("list_notebook_versions", { section: "what_works", includeText: true })
    ).json as {
      versions: { version: number; kind: string; rolledBackTo: number | null; text: string }[];
    };
    expect(listing.versions.map((v) => [v.version, v.kind, v.rolledBackTo, v.text])).toEqual([
      [3, "rollback", 1, "Morning rides."],
      [2, "edit", null, "Evening rides."],
      [1, "edit", null, "Morning rides."],
    ]);
  });

  it("answers a stale edit with the current version instead of overwriting it", async () => {
    await call("update_notebook_section", { section: "profile", text: "one" });
    await call("update_notebook_section", { section: "profile", text: "two" });
    const stale = await call("update_notebook_section", {
      section: "profile",
      text: "three",
      expectedVersion: 1,
    });
    expect(stale.isError).toBe(false);
    expect(stale.json).toMatchObject({
      saved: false,
      reason: "version-conflict",
      currentVersion: 2,
    });
  });

  it("fails in words, as a tool error, for what a caller can fix", async () => {
    const cases: [string, Record<string, unknown>, RegExp][] = [
      ["get_notebook", { section: "plans" }, /Invalid option|invalid/i],
      [
        "rollback_notebook_section",
        { section: "profile", version: 7 },
        /there is no version 7 of "profile"/,
      ],
      ["append_notebook_log", { entry: "x", date: "2026-02-30" }, /not a real calendar date/],
      ["append_notebook_log", { entry: "x".repeat(400) }, /a log line is at most 300/],
      ["update_notebook_section", { section: "profile" }, /text|required|invalid/i],
    ];
    for (const [tool, args, message] of cases) {
      const result = await call(tool, args);
      expect(result.isError, `${tool} ${JSON.stringify(args)}`).toBe(true);
      expect(result.text, tool).toMatch(message);
    }
  });
});

describe("the methodology", () => {
  it("is refused without a reason, in words that say why", async () => {
    for (const args of [
      { section: "methodology", text: "Polarised." },
      { section: "methodology", text: "Polarised.", reason: "" },
      { section: "methodology", text: "Polarised.", reason: "  " },
    ]) {
      const result = await call("update_notebook_section", args);
      expect(result.isError).toBe(true);
      expect(result.text).toMatch(/changing the methodology needs a reason/);
    }
    expect(
      ((await call("get_notebook", { section: "methodology" })).json as { section: Section })
        .section.version,
    ).toBe(0);
  });

  it("is saved with its reason, versioned, and the answer says whether the rider is told (here: no bot)", async () => {
    const result = await call("update_notebook_section", {
      section: "methodology",
      text: "Polarised: 80% easy.",
      reason: "The rider has four mornings a week.",
    });
    expect(result.isError).toBe(false);
    expect(result.json).toMatchObject({
      saved: true,
      version: 1,
      announcedToRider: false,
    });
    expect((result.json as { note: string }).note).toMatch(/NOT told in Telegram/);
    const listing = (await call("list_notebook_versions", { section: "methodology" })).json as {
      versions: { reason: string }[];
    };
    expect(listing.versions[0]?.reason).toBe("The rider has four mornings a week.");
  });

  it("is told to the rider through the bot when it is set up", async () => {
    const withBot = await startMcpHarness(db, fake, {
      staticTokens: [{ name: "coach", token: STATIC_TOKEN }],
      config: {
        TELEGRAM_BOT_TOKEN: "1:AAFakeTokenForTestsOnly_0123456789abcdefgh",
        TELEGRAM_OWNER_CHAT_ID: "42",
      },
    });
    try {
      const client = await connectWithToken(withBot.mcpUrl, STATIC_TOKEN);
      const result = await callTool(client, "update_notebook_section", {
        section: "methodology",
        text: "Sweet spot.",
        reason: "A new block.",
      });
      expect(result.json).toMatchObject({ saved: true, announcedToRider: true });
      await client.close();
    } finally {
      await withBot.close();
    }
  });
});

describe("the size caps", () => {
  it("answers an edit that would pass the cap, saves nothing and says how to make room", async () => {
    await call("update_notebook_section", { section: "profile", text: "p".repeat(1500) });
    const result = await call("update_notebook_section", {
      section: "what_works",
      text: "w".repeat(600),
    });
    expect(result.isError).toBe(false);
    expect(result.json).toMatchObject({
      saved: false,
      reason: "document-too-large",
      condense: { cap: "document", chars: 1500, maxChars: 2000 },
    });
    expect((result.json as { message: string }).message).toMatch(/Make room first/);
    expect(((await call("get_notebook")).json as { totalChars: number }).totalChars).toBe(1500);
  });

  it("asks for the log to be condensed when an entry passes its cap, and says how, as an answer and not an error", async () => {
    const entry = "x".repeat(250);
    const first = await call("append_notebook_log", { entry });
    expect(first.json).not.toHaveProperty("condense");
    const second = await call("append_notebook_log", { entry });
    expect(second.isError).toBe(false);
    const body = second.json as {
      saved: boolean;
      condense: { cap: string; chars: number; maxChars: number; how: string };
      note: string;
    };
    expect(body.saved).toBe(true);
    expect(body.condense).toMatchObject({ cap: "log", maxChars: 500 });
    expect(body.condense.how).toMatch(
      /move what still matters from the old entries into the sections/,
    );
    expect(body.condense.how).toMatch(/rewrite the log/);
    expect(body.note).toMatch(/no further entry is accepted until you condense it/);

    const refused = await call("append_notebook_log", { entry: "more" });
    expect(refused.isError).toBe(false);
    expect(refused.json).toMatchObject({ saved: false, reason: "log-full" });

    const get = (await call("get_notebook")).json as { log: { overCap: boolean } };
    expect(get.log.overCap).toBe(true);

    // The agent condenses; the log accepts entries again.
    const condensed = await call("update_notebook_section", {
      section: "log",
      text: "2026-11-18: two long entries, condensed.",
      reason: "condensed old entries into the sections",
    });
    expect(condensed.json).toMatchObject({ saved: true });
    expect((await call("append_notebook_log", { entry: "more" })).json).toMatchObject({
      saved: true,
    });
  });
});

describe("GET /v1/notebook (the companion's read-only view)", () => {
  const get = (token = COMPANION, url = "/v1/notebook") =>
    h.app.inject({ method: "GET", url, headers: bearer(token) });

  it("needs a device token", async () => {
    const res = await h.app.inject({ method: "GET", url: "/v1/notebook" });
    expectError(res, "unauthorized");
  });

  it("serves the empty notebook in the contract's shape", async () => {
    const res = await get();
    expect(res.statusCode).toBe(200);
    expect(res.headers["cache-control"]).toBe("no-store");
    expect(validateNotebookResponse(res.json()), JSON.stringify(res.json())).toMatchObject({
      ok: true,
    });
    expect(res.json()).toMatchObject({
      scope: "coach",
      totalChars: 0,
      limits: { maxChars: 2000, logMaxChars: 500 },
    });
  });

  it("serves what the agent wrote, with versions and reasons, to the tablet and the companion alike", async () => {
    await call("update_notebook_section", { section: "profile", text: "Rides before work." });
    await call("update_notebook_section", {
      section: "methodology",
      text: "Base, then build.",
      reason: "Start of the season.",
    });
    await call("append_notebook_log", { entry: "Planned the first block." });
    for (const device of [COMPANION, TABLET]) {
      const res = await get(device);
      const body = res.json() as { sections: Section[]; totalChars: number };
      expect(validateNotebookResponse(body), JSON.stringify(body)).toMatchObject({ ok: true });
      expect(body.sections.map((s) => [s.key, s.version])).toEqual([
        ["profile", 1],
        ["what_works", 0],
        ["what_does_not", 0],
        ["injuries", 0],
        ["methodology", 1],
        ["open_questions", 0],
        ["log", 1],
      ]);
      expect(body.sections[4]).toMatchObject({
        text: "Base, then build.",
        reason: "Start of the season.",
        updatedAt: "2026-11-18T08:00:00.000Z",
      });
      expect(body.totalChars).toBe(18 + 17 + "2026-11-18: Planned the first block.".length);
    }
  });

  it("is read-only: nothing writes through it", async () => {
    for (const method of ["POST", "PUT", "PATCH", "DELETE"] as const) {
      const res = await h.app.inject(
        method === "DELETE"
          ? { method, url: "/v1/notebook", headers: bearer(COMPANION) }
          : {
              method,
              url: "/v1/notebook",
              headers: { ...bearer(COMPANION), "content-type": "application/json" },
              payload: JSON.stringify({ section: "profile", text: "x" }),
            },
      );
      expect([404, 405], method).toContain(res.statusCode);
    }
    expect(
      ((await call("get_notebook", { section: "profile" })).json as { section: Section }).section
        .version,
    ).toBe(0);
  });
});

// ---- The game master's notebook ---------------------------------------------------------------

/** A full OAuth authorization asking for these scopes, by hand; the token it ends with, and what it was granted. */
async function tokenWithScope(scope: string): Promise<{ token: string; granted: string }> {
  const registered = await register(h.baseUrl);
  const clientId = String(registered.body.client_id);
  const { verifier, challenge } = pkce();
  const approved = await submitAuthorization(
    h.baseUrl,
    authorizeQuery(h.baseUrl, { clientId, challenge, scope }),
    { passphrase: PASSPHRASE },
  );
  const { code } = codeOf(approved);
  const exchanged = await tokenRequest(h.baseUrl, {
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    code_verifier: verifier,
    redirect_uri: LOOPBACK_REDIRECT,
    resource: `${h.baseUrl}/mcp`,
  });
  expect(exchanged.status, JSON.stringify(exchanged.body)).toBe(200);
  return { token: String(exchanged.body.access_token), granted: String(exchanged.body.scope) };
}

describe("the game master's notebook", () => {
  const SECRET = "SECRET: the rider's surprise ride is in week six";
  let master: Client;

  beforeAll(async () => {
    await db.exec("TRUNCATE oauth_tokens, oauth_codes, oauth_clients");
    const { token, granted } = await tokenWithScope("mcp game-master");
    expect(granted).toBe("mcp game-master");
    master = await connectWithToken(h.mcpUrl, token);
  });
  afterAll(async () => {
    await master.close();
  });

  const asMaster = (name: string, args: Record<string, unknown> = {}) =>
    callTool(master, name, args);

  it("exists only for a token that carries the scope: the other session's tools have no scope argument and never mention it", async () => {
    const mine = (await coach.listTools()).tools.filter((t) => NOTEBOOK_TOOLS.includes(t.name));
    expect(mine).toHaveLength(5);
    for (const tool of mine) {
      expect(JSON.stringify(tool), tool.name).not.toMatch(/game[- ]master|game_plans/i);
      expect(Object.keys(tool.inputSchema.properties ?? {}), tool.name).not.toContain("scope");
    }
    expect(coach.getInstructions() ?? "").not.toMatch(/game[- ]master/i);

    const theirs = (await master.listTools()).tools.filter((t) => NOTEBOOK_TOOLS.includes(t.name));
    expect(theirs).toHaveLength(5);
    for (const tool of theirs) {
      const properties = tool.inputSchema.properties as Record<string, { enum?: string[] }>;
      expect(properties.scope?.enum, tool.name).toEqual(["coach", "game-master"]);
      expect(tool.description, tool.name).toMatch(/game master/i);
    }
    expect(master.getInstructions() ?? "").toMatch(/THIS SESSION ALSO HOLDS THE GAME MASTER SCOPE/);
  });

  it("is written and read by that session, and by no other", async () => {
    const written = await asMaster("update_notebook_section", {
      scope: "game-master",
      section: "game_plans",
      text: SECRET,
      reason: "Planning ahead.",
    });
    expect(written.json).toMatchObject({ saved: true, section: "game_plans", version: 1 });
    await asMaster("append_notebook_log", { scope: "game-master", entry: "Planned the surprise." });

    const read = (await asMaster("get_notebook", { scope: "game-master" })).json as {
      scope: string;
      sections: Section[];
    };
    expect(read.scope).toBe("game-master");
    expect(read.sections.map((s) => s.key)).toEqual(["game_plans", "notes", "log"]);
    expect(read.sections[0]?.text).toBe(SECRET);
    // Without the scope argument the same session gets the coach notebook, which has none of it.
    const own = (await asMaster("get_notebook")).json as { scope: string; sections: Section[] };
    expect(own.scope).toBe("coach");
    expect(JSON.stringify(own)).not.toContain("SECRET");
    const versions = (
      await asMaster("list_notebook_versions", { scope: "game-master", section: "game_plans" })
    ).json as { versions: unknown[] };
    expect(versions.versions).toHaveLength(1);

    // The coach session cannot name it: the argument is not there to be passed, and passing it reads the coach notebook.
    const asked = await call("get_notebook", { scope: "game-master" });
    expect(asked.isError).toBe(false);
    expect((asked.json as { scope: string }).scope).toBe("coach");
    expect(asked.text).not.toContain("SECRET");
    const sectionAsked = await call("get_notebook", { section: "game_plans" });
    expect(sectionAsked.isError).toBe(true);
    expect(sectionAsked.text).not.toContain("SECRET");
    const sneaky = await call("update_notebook_section", {
      scope: "game-master",
      section: "log",
      text: "from the coach session",
    });
    expect(sneaky.json).toMatchObject({ saved: true, section: "log", version: 1 });
    // ... and that went to the coach notebook's log, not the game master's.
    const gmLog = (await asMaster("get_notebook", { scope: "game-master", section: "log" }))
      .json as {
      section: Section;
    };
    expect(gmLog.section.text).toBe("2026-11-18: Planned the surprise.");
  });

  it("is never served by GET /v1/notebook, whatever the query says", async () => {
    await asMaster("update_notebook_section", {
      scope: "game-master",
      section: "game_plans",
      text: SECRET,
    });
    for (const url of [
      "/v1/notebook",
      "/v1/notebook?scope=game-master",
      "/v1/notebook?scope=game-master&section=game_plans",
    ]) {
      for (const device of [TABLET, COMPANION]) {
        const res = await h.app.inject({ method: "GET", url, headers: bearer(device) });
        expect(res.statusCode, url).toBe(200);
        expect(res.body, url).not.toContain("SECRET");
        expect(res.body, url).not.toMatch(/game[-_ ]?(master|plans)/i);
        expect((res.json() as { scope: string }).scope).toBe("coach");
      }
    }
  });

  it("is not announced to the rider, and writing it leaves the coach notebook as it was", async () => {
    await asMaster("update_notebook_section", {
      scope: "game-master",
      section: "notes",
      text: SECRET,
    });
    const candidates = await h.services.notebook.notifications.candidates(h.clock.now().getTime());
    expect(candidates).toEqual([]);
    const coachView = await h.services.notebook.coachResponse();
    expect(JSON.stringify(coachView)).not.toContain("SECRET");
  });
});

describe("the scope and the authorization server", () => {
  it("is not advertised, and a client that asks for it alone gets nothing", async () => {
    const metadata = (await (
      await fetch(`${h.baseUrl}/.well-known/oauth-authorization-server`)
    ).json()) as {
      scopes_supported: string[];
    };
    expect(metadata.scopes_supported).toEqual(["mcp"]);
    const resource = (await (
      await fetch(`${h.baseUrl}/.well-known/oauth-protected-resource`)
    ).json()) as {
      scopes_supported: string[];
    };
    expect(resource.scopes_supported).toEqual(["mcp"]);

    const registered = await register(h.baseUrl);
    const { challenge } = pkce();
    const query = authorizeQuery(h.baseUrl, {
      clientId: String(registered.body.client_id),
      challenge,
      scope: "game-master",
    });
    const res = await fetch(`${h.baseUrl}/oauth/authorize?${query}`, { redirect: "manual" });
    expect(res.status).toBe(302);
    const location = new URL(res.headers.get("location") ?? "");
    expect(location.searchParams.get("error")).toBe("invalid_scope");
    expect(location.searchParams.get("error_description")).toMatch(/together with mcp/);
  });

  it("is granted only after the owner's passphrase, and the page says what it is asking for", async () => {
    const registered = await register(h.baseUrl);
    const { challenge } = pkce();
    const query = authorizeQuery(h.baseUrl, {
      clientId: String(registered.body.client_id),
      challenge,
      scope: "mcp game-master",
    });
    const page = await (await fetch(`${h.baseUrl}/oauth/authorize?${query}`)).text();
    expect(page).toMatch(/It also asks for the game master&#39;s notebook|game master's notebook/);
    const plain = authorizeQuery(h.baseUrl, {
      clientId: String(registered.body.client_id),
      challenge,
    });
    expect(await (await fetch(`${h.baseUrl}/oauth/authorize?${plain}`)).text()).not.toMatch(
      /game master/i,
    );

    const wrong = await submitAuthorization(h.baseUrl, query, { passphrase: "not the passphrase" });
    expect(wrong.status).toBe(401);
  });

  it("refuses a scope that does not exist at registration, as before", async () => {
    const res = await register(h.baseUrl, { scope: "mcp admin" });
    expect(res.status).toBe(400);
    const ok = await register(h.baseUrl, { scope: "mcp game-master" });
    expect(ok.status).toBe(201);
  });
});

describe("what the server logs", () => {
  it("keeps what was written in the notebook out of the log", async () => {
    await call("update_notebook_section", {
      section: "injuries",
      text: "SECRET-TEXT-IN-A-SECTION",
      reason: "SECRET-REASON",
    });
    await call("append_notebook_log", { entry: "SECRET-LOG-ENTRY" });
    await call("update_notebook_section", {
      section: "methodology",
      text: "SECRET-METHOD",
      reason: "SECRET-WHY",
    });
    const everything = `${h.logging.text()}\n${h.appLogs.join("\n")}`;
    expect(everything).not.toMatch(/SECRET/);
  });
});
