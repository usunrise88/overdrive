import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { createEventBus, type NotebookChangedEvent } from "../src/events.ts";
import {
  createNotebookService,
  type EditOutcome,
  methodologyChangedText,
  NotebookError,
  type NotebookService,
} from "../src/notebook/service.ts";
import { createNotebookStore } from "../src/notebook/store.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { collectingLogger, type TestClock, testClock } from "./helpers/support.ts";

/**
 * The coach notebook's rules (spec §11.6, §12.4, ADR 0035) at the service, on a real database: versions that are only ever added,
 * rollback as a new version, the caps and the answer that asks the agent to condense, the reason the methodology needs, its
 * announcement, and the game master's notebook kept apart.
 */

let db: Db;
let clock: TestClock;
let events: NotebookChangedEvent[];
let logging: ReturnType<typeof collectingLogger>;

beforeAll(async () => {
  db = await openMigratedTestDb();
});
afterAll(async () => {
  await db.close();
});
beforeEach(async () => {
  await db.exec("TRUNCATE notebook_versions");
  clock = testClock("2026-11-18T08:00:00Z");
  events = [];
  logging = collectingLogger();
});

function make(
  options: { maxChars?: number; logMaxChars?: number; canAnnounce?: boolean } = {},
): NotebookService {
  const bus = createEventBus();
  bus.on("notebook-changed", (event) => {
    events.push(event);
  });
  return createNotebookService({
    store: createNotebookStore(db),
    bus,
    timezone: "Europe/Paris",
    now: clock.now,
    limits: {
      ...(options.maxChars === undefined ? {} : { maxChars: options.maxChars }),
      ...(options.logMaxChars === undefined ? {} : { logMaxChars: options.logMaxChars }),
    },
    canAnnounce: options.canAnnounce ?? true,
    log: logging.log,
  });
}

const AGENT = "static:coach-test";
const saved = (outcome: EditOutcome) => {
  if (!outcome.saved) throw new Error(`not saved: ${outcome.message}`);
  return outcome;
};
const refused = (outcome: EditOutcome) => {
  if (outcome.saved) throw new Error("it was saved");
  return outcome;
};

describe("the document", () => {
  it("has the seven fixed sections of the coach notebook, all empty at version 0", async () => {
    const view = await make().get("coach");
    expect(view.sections.map((s) => s.key)).toEqual([
      "profile",
      "what_works",
      "what_does_not",
      "injuries",
      "methodology",
      "open_questions",
      "log",
    ]);
    for (const section of view.sections) {
      expect(section).toMatchObject({ text: "", version: 0, updatedAt: null, reason: null });
    }
    expect(view.totalChars).toBe(0);
    expect(view.limits).toEqual({ maxChars: 12000, logMaxChars: 3000 });
  });

  it("refuses a section the notebook does not have, naming the ones it has", async () => {
    const notebook = make();
    await expect(
      notebook.update("coach", { section: "game_plans", text: "x", author: AGENT }),
    ).rejects.toThrow(/there is no section "game_plans".*profile, what_works/);
    await expect(notebook.versions("coach", "nope", { limit: 5, withText: false })).rejects.toThrow(
      NotebookError,
    );
  });
});

describe("versions", () => {
  it("makes every edit a new version and keeps every old one", async () => {
    const notebook = make();
    const first = saved(
      await notebook.update("coach", {
        section: "profile",
        text: "Works full time.",
        reason: "First session.",
        author: AGENT,
      }),
    );
    expect(first).toMatchObject({ section: "profile", version: 1, previousVersion: 0, chars: 16 });
    clock.advance(60_000);
    const second = saved(
      await notebook.update("coach", {
        section: "profile",
        text: "Works full time, rides before work.",
        author: AGENT,
      }),
    );
    expect(second).toMatchObject({ version: 2, previousVersion: 1 });

    const listing = await notebook.versions("coach", "profile", { limit: 10, withText: true });
    expect(listing.current).toBe(2);
    expect(listing.versions.map((v) => [v.version, v.kind, v.reason, v.text])).toEqual([
      [2, "edit", null, "Works full time, rides before work."],
      [1, "edit", "First session.", "Works full time."],
    ]);
    expect(listing.versions[0]).toMatchObject({
      author: AGENT,
      createdAt: "2026-11-18T08:01:00.000Z",
    });
    const view = await notebook.get("coach");
    expect(view.sections[0]).toMatchObject({
      text: "Works full time, rides before work.",
      version: 2,
      updatedAt: "2026-11-18T08:01:00.000Z",
      reason: null,
    });
  });

  it("numbers the versions of each section on its own", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "profile", text: "a", author: AGENT });
    await notebook.update("coach", { section: "injuries", text: "b", author: AGENT });
    await notebook.update("coach", { section: "profile", text: "c", author: AGENT });
    const view = await notebook.get("coach");
    const byKey = Object.fromEntries(view.sections.map((s) => [s.key, s.version]));
    expect(byKey).toMatchObject({ profile: 2, injuries: 1, what_works: 0 });
  });

  it("writes nothing when the text is what the section already says", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "injuries", text: "Left knee.", author: AGENT });
    const again = refused(
      await notebook.update("coach", { section: "injuries", text: "Left knee.", author: AGENT }),
    );
    expect(again).toMatchObject({ saved: false, reason: "unchanged", currentVersion: 1 });
    expect(
      (await notebook.versions("coach", "injuries", { limit: 5, withText: false })).current,
    ).toBe(1);
    expect(events).toHaveLength(1);
  });

  it("lets an edit clear a section with an empty text, as a version of its own", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "injuries", text: "Left knee.", author: AGENT });
    const cleared = saved(
      await notebook.update("coach", {
        section: "injuries",
        text: "",
        reason: "Gone.",
        author: AGENT,
      }),
    );
    expect(cleared).toMatchObject({ version: 2, chars: 0 });
    expect((await notebook.get("coach")).sections[3]).toMatchObject({
      text: "",
      version: 2,
      reason: "Gone.",
    });
  });

  it("stops an edit made from a version the section has moved on from", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "what_works", text: "v1", author: AGENT });
    await notebook.update("coach", { section: "what_works", text: "v2", author: AGENT });
    const stale = refused(
      await notebook.update("coach", {
        section: "what_works",
        text: "from a stale read",
        expectedVersion: 1,
        author: AGENT,
      }),
    );
    expect(stale).toMatchObject({ reason: "version-conflict", currentVersion: 2 });
    expect(stale.message).toMatch(/another session changed it/);
    const ok = saved(
      await notebook.update("coach", {
        section: "what_works",
        text: "merged",
        expectedVersion: 2,
        author: AGENT,
      }),
    );
    expect(ok.version).toBe(3);
  });

  it("keeps every entry when sessions append to the log at the same moment", async () => {
    const notebook = make();
    const outcomes = await Promise.all(
      ["one", "two", "three", "four", "five"].map((entry) =>
        notebook.append("coach", { entry, author: AGENT }),
      ),
    );
    const versions = outcomes.map((o) => saved(o).version).sort();
    expect(versions).toEqual([1, 2, 3, 4, 5]);
    const log = (await notebook.get("coach")).sections[6]?.text ?? "";
    expect(log.split("\n").sort()).toEqual([
      "2026-11-18: five",
      "2026-11-18: four",
      "2026-11-18: one",
      "2026-11-18: three",
      "2026-11-18: two",
    ]);
  });
});

describe("rollback", () => {
  it("restores an old text as a NEW version and never rewrites history", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "what_works", text: "first", author: AGENT });
    await notebook.update("coach", { section: "what_works", text: "second", author: AGENT });
    await notebook.update("coach", { section: "what_works", text: "third", author: AGENT });
    const before = await notebook.versions("coach", "what_works", { limit: 10, withText: true });

    clock.advance(1000);
    const back = saved(
      await notebook.rollback("coach", {
        section: "what_works",
        toVersion: 1,
        reason: "The second idea did not work.",
        author: AGENT,
      }),
    );
    expect(back).toMatchObject({ version: 4, previousVersion: 3 });

    const after = await notebook.versions("coach", "what_works", { limit: 10, withText: true });
    expect(after.current).toBe(4);
    // The new version carries the old text, says it is a rollback to version 1, and the three before it are exactly as they were.
    expect(after.versions[0]).toMatchObject({
      version: 4,
      kind: "rollback",
      rolledBackTo: 1,
      reason: "The second idea did not work.",
      text: "first",
    });
    expect(after.versions.slice(1)).toEqual(before.versions);
    expect((await notebook.get("coach")).sections[1]?.text).toBe("first");
  });

  it("can be undone by rolling back to the version it left", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "profile", text: "old", author: AGENT });
    await notebook.update("coach", { section: "profile", text: "new", author: AGENT });
    await notebook.rollback("coach", { section: "profile", toVersion: 1, author: AGENT });
    const forward = saved(
      await notebook.rollback("coach", { section: "profile", toVersion: 2, author: AGENT }),
    );
    expect(forward.version).toBe(4);
    expect((await notebook.get("coach")).sections[0]?.text).toBe("new");
  });

  it("records a reason of its own when none is given, and rejects a version that does not exist", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "profile", text: "a", author: AGENT });
    await notebook.update("coach", { section: "profile", text: "b", author: AGENT });
    await notebook.rollback("coach", { section: "profile", toVersion: 1, author: AGENT });
    const [newest] = (await notebook.versions("coach", "profile", { limit: 1, withText: false }))
      .versions;
    expect(newest?.reason).toBe("rolled back to version 1");
    await expect(
      notebook.rollback("coach", { section: "profile", toVersion: 9, author: AGENT }),
    ).rejects.toThrow(/there is no version 9 of "profile"/);
  });

  it("writes nothing when the section already has that text", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "profile", text: "a", author: AGENT });
    const same = refused(
      await notebook.rollback("coach", { section: "profile", toVersion: 1, author: AGENT }),
    );
    expect(same.reason).toBe("unchanged");
  });
});

describe("the size cap", () => {
  it("answers, without saving, an edit that would pass the cap, and says how to make room", async () => {
    const notebook = make({ maxChars: 100 });
    await notebook.update("coach", { section: "profile", text: "p".repeat(60), author: AGENT });
    const big = refused(
      await notebook.update("coach", {
        section: "what_works",
        text: "w".repeat(50),
        author: AGENT,
      }),
    );
    expect(big).toMatchObject({
      saved: false,
      reason: "document-too-large",
      condense: { cap: "document", chars: 60, maxChars: 100 },
    });
    expect(big.message).toMatch(
      /holds 60 characters and this edit would make it 110; the cap is 100/,
    );
    expect(big.message).toMatch(/shorten the sections/i);
    // Nothing was written.
    expect((await notebook.get("coach")).totalChars).toBe(60);
  });

  it("always accepts an edit that does not make the document longer, even over the cap", async () => {
    const roomy = make({ maxChars: 1000 });
    await roomy.update("coach", { section: "profile", text: "p".repeat(80), author: AGENT });
    await roomy.update("coach", { section: "injuries", text: "i".repeat(80), author: AGENT });
    // The cap is lowered (a setting) below what the notebook already holds.
    const notebook = make({ maxChars: 100 });
    expect((await notebook.get("coach")).totalChars).toBe(160);
    const longer = refused(
      await notebook.update("coach", { section: "profile", text: "p".repeat(81), author: AGENT }),
    );
    expect(longer.reason).toBe("document-too-large");
    const shorter = saved(
      await notebook.update("coach", { section: "profile", text: "p".repeat(40), author: AGENT }),
    );
    expect(shorter.totalChars).toBe(120);
  });

  it("counts a rollback to a longer text against the cap", async () => {
    const notebook = make({ maxChars: 100 });
    await notebook.update("coach", { section: "profile", text: "p".repeat(90), author: AGENT });
    await notebook.update("coach", { section: "profile", text: "short", author: AGENT });
    await notebook.update("coach", { section: "injuries", text: "i".repeat(50), author: AGENT });
    const back = refused(
      await notebook.rollback("coach", { section: "profile", toVersion: 1, author: AGENT }),
    );
    expect(back.reason).toBe("document-too-large");
  });
});

describe("the dated log", () => {
  it("adds one dated line at a time, oldest first, dated today in the rider's time zone", async () => {
    // 23:30 UTC on the 18th is 00:30 on the 19th in Paris.
    clock.set("2026-11-18T23:30:00Z");
    const notebook = make();
    const first = saved(
      await notebook.append("coach", {
        entry: "Moved Thursday to Friday:\n  long day.",
        author: AGENT,
      }),
    );
    expect(first).toMatchObject({ section: "log", version: 1 });
    await notebook.append("coach", {
      entry: "Eased the weekend ride.",
      date: "2026-11-17",
      author: AGENT,
    });
    expect((await notebook.get("coach")).sections[6]?.text).toBe(
      "2026-11-19: Moved Thursday to Friday: long day.\n2026-11-17: Eased the weekend ride.",
    );
    const versions = await notebook.versions("coach", "log", { limit: 5, withText: false });
    expect(versions.versions.map((v) => [v.kind, v.reason])).toEqual([
      ["append", "log entry"],
      ["append", "log entry"],
    ]);
  });

  it("rejects an empty line, a line that is too long and a date that is not real", async () => {
    const notebook = make();
    await expect(notebook.append("coach", { entry: "  \n ", author: AGENT })).rejects.toThrow(
      /empty/,
    );
    await expect(
      notebook.append("coach", { entry: "x".repeat(301), author: AGENT }),
    ).rejects.toThrow(/at most 300/);
    await expect(
      notebook.append("coach", { entry: "x", date: "2026-02-30", author: AGENT }),
    ).rejects.toThrow(/not a real calendar date/);
  });

  it("saves the entry that passes the cap, asks for the log to be condensed and says how, then refuses more until it is", async () => {
    const notebook = make({ logMaxChars: 100 });
    saved(await notebook.append("coach", { entry: "a".repeat(60), author: AGENT }));
    // Each line is the date and a space (12 characters) and 60 more: the second entry passes the cap of 100.
    const passes = saved(await notebook.append("coach", { entry: "b".repeat(60), author: AGENT }));
    expect(passes.condense).toMatchObject({ cap: "log", maxChars: 100 });
    expect(passes.condense?.chars).toBe(145);
    expect(passes.condense?.how).toMatch(
      /move what still matters from the old entries into the sections/,
    );
    expect(passes.condense?.how).toMatch(/update_notebook_section/);
    expect(passes.condense?.how).toMatch(/list_notebook_versions/);
    expect(passes.note).toMatch(/no further entry is accepted until you condense it/);

    // It is an answer, not a failure, and the next entry waits.
    const waits = refused(await notebook.append("coach", { entry: "c", author: AGENT }));
    expect(waits).toMatchObject({ saved: false, reason: "log-full" });
    expect(waits.condense).toMatchObject({ cap: "log", chars: 145, maxChars: 100 });
    expect(waits.message).toMatch(/Then append this entry again/);

    // Condensing is an edit of the log that makes it shorter, so it is accepted; then the entry fits.
    saved(
      await notebook.update("coach", {
        section: "log",
        text: "2026-11-18: a and b, condensed.",
        reason: "condensed old entries into the sections",
        author: AGENT,
      }),
    );
    saved(await notebook.append("coach", { entry: "c", author: AGENT }));
    expect((await notebook.get("coach")).sections[6]?.text).toBe(
      "2026-11-18: a and b, condensed.\n2026-11-18: c",
    );
  });

  it("counts the log in the document's cap too", async () => {
    const notebook = make({ maxChars: 100, logMaxChars: 1000 });
    await notebook.update("coach", { section: "profile", text: "p".repeat(95), author: AGENT });
    const out = refused(await notebook.append("coach", { entry: "too much", author: AGENT }));
    expect(out.reason).toBe("document-too-large");
  });
});

describe("the methodology", () => {
  it("cannot be changed without a reason, however it is changed", async () => {
    const notebook = make();
    for (const reason of [undefined, "", "   \n"]) {
      await expect(
        notebook.update("coach", {
          section: "methodology",
          text: "Polarised.",
          reason,
          author: AGENT,
        }),
      ).rejects.toThrow(/changing the methodology needs a reason/);
    }
    await notebook.update("coach", {
      section: "methodology",
      text: "Polarised.",
      reason: "First season plan.",
      author: AGENT,
    });
    await expect(
      notebook.rollback("coach", { section: "methodology", toVersion: 1, author: AGENT }),
    ).rejects.toThrow(/needs a reason/);
    // Nothing was written by the refusals.
    expect(
      (await notebook.versions("coach", "methodology", { limit: 5, withText: false })).current,
    ).toBe(1);
  });

  it("does not take a reason that is too long to be sent in a message", async () => {
    await expect(
      make().update("coach", {
        section: "methodology",
        text: "x",
        reason: "r".repeat(501),
        author: AGENT,
      }),
    ).rejects.toThrow(/at most 500/);
  });

  it("is logged, versioned and announced to the rider: the section, the reason and the version", async () => {
    const notebook = make();
    const outcome = saved(
      await notebook.update("coach", {
        section: "methodology",
        text: "Sweet spot block, then threshold.",
        reason: "The FTP test came in lower, so the build moves back two weeks.",
        author: AGENT,
      }),
    );
    expect(outcome).toMatchObject({ version: 1, announcedToRider: true });
    expect(events).toEqual([{ scope: "coach", section: "methodology", version: 1 }]);

    const [candidate] = await notebook.notifications.candidates(clock.now().getTime());
    expect(candidate).toMatchObject({ kind: "notebook-methodology", key: "1" });
    expect(await candidate?.build()).toEqual({
      text:
        "Coach notebook: Methodology changed (version 1).\n" +
        "Why: The FTP test came in lower, so the build moves back two weeks.",
    });
  });

  it("announces a rollback as one, with its own version and reason", async () => {
    const notebook = make();
    await notebook.update("coach", {
      section: "methodology",
      text: "A",
      reason: "r1",
      author: AGENT,
    });
    await notebook.update("coach", {
      section: "methodology",
      text: "B",
      reason: "r2",
      author: AGENT,
    });
    await notebook.rollback("coach", {
      section: "methodology",
      toVersion: 1,
      reason: "B was a mistake.",
      author: AGENT,
    });
    const candidates = await notebook.notifications.candidates(clock.now().getTime());
    expect(candidates.map((c) => c.key)).toEqual(["1", "2", "3"]);
    expect((await candidates[2]?.build())?.text).toBe(
      "Coach notebook: Methodology went back to the text of version 1 (now version 3).\nWhy: B was a mistake.",
    );
  });

  it("is the only section announced, and only a day long", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "profile", text: "x", reason: "why", author: AGENT });
    await notebook.append("coach", { entry: "a decision", author: AGENT });
    expect(await notebook.notifications.candidates(clock.now().getTime())).toEqual([]);

    await notebook.update("coach", {
      section: "methodology",
      text: "M",
      reason: "r",
      author: AGENT,
    });
    expect(await notebook.notifications.candidates(clock.now().getTime())).toHaveLength(1);
    // The candidate says when it stops being worth sending: a day after the change.
    const [candidate] = await notebook.notifications.candidates(clock.now().getTime());
    expect(candidate?.expiresAt).toBe(clock.now().getTime() + 24 * 3_600_000);
    expect(await notebook.notifications.candidates(clock.now().getTime() + 25 * 3_600_000)).toEqual(
      [],
    );
  });

  it("says so, and tells the agent to tell the rider, when the bot is not set up", async () => {
    const notebook = make({ canAnnounce: false });
    const outcome = saved(
      await notebook.update("coach", {
        section: "methodology",
        text: "M",
        reason: "r",
        author: AGENT,
      }),
    );
    expect(outcome.announcedToRider).toBe(false);
    expect(outcome.note).toMatch(/NOT told in Telegram/);
  });

  it("is plain words by code: the text names no model and leaves out what was not given", () => {
    expect(
      methodologyChangedText({ version: 4, reason: null, kind: "edit", rolledBackTo: null }),
    ).toBe("Coach notebook: Methodology changed (version 4).\nWhy: no reason was recorded");
  });
});

describe("what is logged", () => {
  it("is what changed, by scope, section, version and kind, and never what it said or why", async () => {
    const notebook = make();
    await notebook.update("coach", {
      section: "methodology",
      text: "SECRET-TEXT",
      reason: "SECRET-REASON",
      author: AGENT,
    });
    await notebook.append("coach", { entry: "SECRET-ENTRY", author: AGENT });
    await notebook.update("game-master", { section: "notes", text: "SECRET-GM", author: AGENT });
    expect(logging.lines.map((l) => [l.level, l.message, l.fields])).toEqual([
      [
        "info",
        "notebook changed",
        { scope: "coach", section: "methodology", version: 1, kind: "edit" },
      ],
      ["info", "notebook changed", { scope: "coach", section: "log", version: 1, kind: "append" }],
      [
        "info",
        "notebook changed",
        { scope: "game-master", section: "notes", version: 1, kind: "edit" },
      ],
    ]);
    expect(logging.text()).not.toMatch(/SECRET/);
  });
});

describe("the game master's notebook", () => {
  it("is a second notebook with its own sections, versions and log", async () => {
    const notebook = make();
    await notebook.update("coach", { section: "log", text: "coach log", author: AGENT });
    saved(
      await notebook.update("game-master", {
        section: "game_plans",
        text: "The surprise in week six.",
        author: AGENT,
      }),
    );
    saved(await notebook.append("game-master", { entry: "Planned the surprise.", author: AGENT }));

    const master = await notebook.get("game-master");
    expect(master.sections.map((s) => s.key)).toEqual(["game_plans", "notes", "log"]);
    expect(master.sections[0]).toMatchObject({ text: "The surprise in week six.", version: 1 });
    expect(master.sections[2]?.text).toBe("2026-11-18: Planned the surprise.");
    // The coach notebook is untouched by it, and has its own log at version 1.
    const coach = await notebook.get("coach");
    expect(coach.sections.find((s) => s.key === "log")).toMatchObject({
      text: "coach log",
      version: 1,
    });
    await expect(
      notebook.update("game-master", { section: "profile", text: "x", author: AGENT }),
    ).rejects.toThrow(/there is no section "profile"/);
  });

  it("is never in what the companion is served, nor announced", async () => {
    const notebook = make();
    await notebook.update("game-master", {
      section: "game_plans",
      text: "SECRET-GAME-PLAN",
      author: AGENT,
    });
    await notebook.update("game-master", { section: "notes", text: "SECRET-NOTE", author: AGENT });
    const served = await notebook.coachResponse();
    expect(served.scope).toBe("coach");
    expect(JSON.stringify(served)).not.toMatch(/SECRET|game/i);
    expect(served.sections.map((s) => s.key)).not.toContain("game_plans");
    expect(await notebook.notifications.candidates(clock.now().getTime())).toEqual([]);
  });

  it("does not announce, and needs no reason for, a section called methodology that is not the coach's", async () => {
    // The game master has no methodology section; the rule is the coach notebook's alone.
    await expect(
      make().update("game-master", { section: "methodology", text: "x", author: AGENT }),
    ).rejects.toThrow(/there is no section "methodology"/);
  });
});

describe("what the companion is served", () => {
  it("is the coach notebook in the contract's shape, with the caps and the total", async () => {
    const notebook = make({ maxChars: 5000, logMaxChars: 700 });
    await notebook.update("coach", {
      section: "methodology",
      text: "Base then build.",
      reason: "Season start.",
      author: AGENT,
    });
    clock.advance(5000);
    const served = await notebook.coachResponse();
    expect(served).toMatchObject({
      scope: "coach",
      generatedAt: "2026-11-18T08:00:05.000Z",
      limits: { maxChars: 5000, logMaxChars: 700 },
      totalChars: 16,
    });
    expect(served.sections).toHaveLength(7);
    expect(served.sections[4]).toEqual({
      key: "methodology",
      title: "Methodology",
      text: "Base then build.",
      version: 1,
      updatedAt: "2026-11-18T08:00:00.000Z",
      reason: "Season start.",
    });
  });
});
