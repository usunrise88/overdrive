import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { buildRideFacts, type RideFacts } from "../src/insights/facts.ts";
import { createInsightStore, type InsightStore, type NewInsight } from "../src/insights/store.ts";
import {
  DEFAULT_TOPIC_SETTINGS,
  findTopic,
  TOPIC_OPEN_DAYS,
  TOPIC_OPEN_MS,
  TOPIC_RULES,
  TOPIC_WINDOW_MS,
  TOPICS_PER_WEEK,
  type TopicContext,
} from "../src/insights/topics.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { rideInput } from "./helpers/insights.ts";

/**
 * Discussion topics (spec §12.3, ADR 0025): the rule table, and the limits (a ride has one, at most two in any 7 days, closed by
 * the coach or after 14 days).
 */

const DAY = 24 * 3_600_000;

const context = (facts: RideFacts, extra: Partial<TopicContext> = {}): TopicContext => ({
  ride: facts,
  driftAlertsLast28Days: 0,
  settings: DEFAULT_TOPIC_SETTINGS,
  ...extra,
});

describe("the rule table", () => {
  it("finds nothing for an ordinary ride, which is what most rides are", () => {
    expect(findTopic(context(buildRideFacts(rideInput())))).toBeNull();
    expect(findTopic(context(buildRideFacts(rideInput({ free: true }))))).toBeNull();
  });

  it("finds a ride cut short against its plan, from 75% of the planned length down", () => {
    // 3600 s planned. 2100 s ridden is 58%.
    const found = findTopic(context(buildRideFacts(rideInput({ seconds: 2_100 }))));
    expect(found?.rule).toBe("cut-short");
    expect(found?.text).toBe("The ride stopped at 35 of the planned 60 minutes (58%).");
    expect(found?.openingQuestion).toBe(
      "I stopped Sweet spot 3x10 after 35 of 60 minutes. Can we look at why, and at what to change for the rest of the week?",
    );
  });

  // The rule reads the percentage the note shows (whole percent), so a ride never "stopped at 75%" and still counts as under 75.
  it.each([
    [2_700, false],
    [2_699, false], // 74.97%, shown as 75%
    [2_650, true], // 73.6%, shown as 74%
    [3_000, false],
  ])("cuts short at under 75 percent: %i s of 3600 -> %s", (seconds, cut) => {
    const found = findTopic(context(buildRideFacts(rideInput({ seconds }))));
    expect(found?.rule === "cut-short").toBe(cut);
  });

  it("does not judge a short planned workout on its length", () => {
    const facts = buildRideFacts(rideInput({ seconds: 2_100 }));
    const short = {
      ...facts,
      plan: facts.plan === null ? null : { ...facts.plan, plannedMinutes: 8 },
    };
    expect(findTopic(context(short))?.rule).not.toBe("cut-short");
  });

  it("finds a power record at a standard duration, the longest first, and quotes only numbers of the facts", () => {
    const facts = buildRideFacts(rideInput({ withRecords: true }));
    const found = findTopic(context(facts));
    expect(found?.rule).toBe("power-record");
    expect(found?.text).toBe("A new best for 20 min: 241 W, up 6 W on 235 W.");
    expect(found?.openingQuestion).toBe(
      "I rode 241 W for 20 min, my best so far (235 W before). Does that change my FTP or my plan?",
    );
    const allowed = new Set(facts.quotable.map((q) => q.value));
    for (const n of `${found?.text} ${found?.openingQuestion}`.match(/\d+(\.\d+)?/g) ?? []) {
      expect(allowed.has(Number(n)), n).toBe(true);
    }
  });

  it("wants enough earlier rides and a real improvement before it calls a record", () => {
    const facts = buildRideFacts(rideInput({ withRecords: true }));
    const fewRides = { ...facts, efforts: facts.efforts.map((e) => ({ ...e, priorRides: 2 })) };
    expect(findTopic(context(fewRides))).toBeNull();
    expect(
      findTopic(
        context(fewRides, { settings: { ...DEFAULT_TOPIC_SETTINGS, recordMinPriorRides: 2 } }),
      )?.rule,
    ).toBe("power-record");
    // 6 W on 235 is 2.6%, 11 W on 290 is 3.8%: a 5% bar clears both.
    expect(
      findTopic(
        context(facts, { settings: { ...DEFAULT_TOPIC_SETTINGS, recordMinImprovementPercent: 5 } }),
      ),
    ).toBeNull();
    // The 5 s record is not at a standard duration for topics.
    const sprint = { ...facts, efforts: facts.efforts.filter((e) => e.durationSec === 5) };
    expect(findTopic(context(sprint))).toBeNull();
  });

  it("finds a load far from the plan, above (+30%) and below (-40%), and not on the edge", () => {
    const above = findTopic(context(buildRideFacts(rideInput({ watts: 245 }))));
    expect(above?.rule).toBe("load-off-plan");
    expect(above?.text).toBe("The ride's load was 71 against 51 planned, 39% above the plan.");
    const facts = buildRideFacts(rideInput());
    const withLoad = (percent: number): RideFacts => ({
      ...facts,
      plan: facts.plan === null ? null : { ...facts.plan, loadVsPlanPercent: percent },
    });
    expect(findTopic(context(withLoad(30)))?.rule).toBe("load-off-plan");
    expect(findTopic(context(withLoad(29)))).toBeNull();
    expect(findTopic(context(withLoad(-40)))?.rule).toBe("load-off-plan");
    expect(findTopic(context(withLoad(-39)))).toBeNull();
    const tiny = {
      ...withLoad(80),
      plan: { ...(withLoad(80).plan as NonNullable<RideFacts["plan"]>), plannedLoad: 20 },
    };
    expect(findTopic(context(tiny))).toBeNull();
  });

  it("finds repeated plan drift: the second drift alert within 28 days", () => {
    const facts = buildRideFacts(rideInput());
    expect(findTopic(context(facts, { driftAlertsLast28Days: 1 }))).toBeNull();
    const found = findTopic(context(facts, { driftAlertsLast28Days: 2 }));
    expect(found?.rule).toBe("repeated-drift");
    expect(found?.text).toContain("drifted more than once");
  });

  it("tries the rows in order: a cut-short ride that is also a record is the cut", () => {
    const facts = buildRideFacts(rideInput({ seconds: 2_100, withRecords: true }));
    expect(findTopic(context(facts, { driftAlertsLast28Days: 3 }))?.rule).toBe("cut-short");
  });

  it("lists the rules of later stages as named hooks that are not available, and never evaluates them", () => {
    const hooks = TOPIC_RULES.filter((rule) => !rule.available).map((rule) => rule.id);
    expect(hooks).toEqual(["effort-rating", "tag-streak", "repeat-trend"]);
    for (const rule of TOPIC_RULES)
      if (!rule.available) expect(rule.needs.length).toBeGreaterThan(10);
    // A table of only hooks finds nothing, and adding an available row is all it takes to switch a signal on.
    expect(
      findTopic(
        context(buildRideFacts(rideInput({ seconds: 2_100 }))),
        TOPIC_RULES.filter((r) => !r.available),
      ),
    ).toBeNull();
    const own = {
      id: "mine",
      available: true as const,
      evaluate: () => ({ text: "t", openingQuestion: "q" }),
    };
    expect(findTopic(context(buildRideFacts(rideInput())), [own])).toEqual({
      text: "t",
      openingQuestion: "q",
      rule: "mine",
    });
  });

  it("states the limits: two a week, open for 14 days", () => {
    expect(TOPICS_PER_WEEK).toBe(2);
    expect(TOPIC_WINDOW_MS).toBe(7 * DAY);
    expect(TOPIC_OPEN_DAYS).toBe(14);
    expect(TOPIC_OPEN_MS).toBe(14 * DAY);
  });
});

// ---- The limits, in the store ---------------------------------------------------------------------

let db: Db;
let store: InsightStore;

beforeAll(async () => {
  db = await openMigratedTestDb();
  store = createInsightStore(db);
});
afterAll(async () => {
  await db.close();
});
beforeEach(async () => {
  await db.exec("TRUNCATE insights, discussion_topics, alert_streaks RESTART IDENTITY");
});

const note = (rideId: string, at: Date): NewInsight => ({
  kind: "ride",
  key: rideId,
  createdAt: at,
  text: "A note.",
  rideId,
  source: "template",
  promptVersion: "template.v1",
  factsHash: "0".repeat(64),
  facts: buildRideFacts(rideInput({ id: rideId })),
});

const topic = (rideId: string) => ({
  topic: { rideId, rule: "cut-short", text: "It stopped early.", openingQuestion: "Why?" },
  limit: { max: TOPICS_PER_WEEK, windowMs: TOPIC_WINDOW_MS },
});

const T0 = new Date("2026-11-02T18:00:00Z");
const at = (days: number): Date => new Date(T0.getTime() + days * DAY);

describe("topics in the store", () => {
  it("writes a topic with its note, at most two in any 7 days, and says when the limit stopped one", async () => {
    const first = await store.insert(note("r1", at(0)), { ...topic("r1"), now: at(0) });
    const second = await store.insert(note("r2", at(2)), { ...topic("r2"), now: at(2) });
    const third = await store.insert(note("r3", at(4)), { ...topic("r3"), now: at(4) });
    expect([first.topic?.rideId, second.topic?.rideId, third.topic]).toEqual(["r1", "r2", null]);
    expect(third).toMatchObject({ inserted: true, topicSuppressed: true });
    // The note is written whatever the limit says: only the topic is held back.
    expect((await store.find("ride", "r3"))?.id).toBe(third.insight?.id);
    expect(await store.topicForRide("r3")).toBeNull();
  });

  it("counts a rolling 7 days: the first topic leaves the window after a week, and a new one fits", async () => {
    await store.insert(note("r1", at(0)), { ...topic("r1"), now: at(0) });
    await store.insert(note("r2", at(3)), { ...topic("r2"), now: at(3) });
    // Day 6.9: both still inside the window of 7 days.
    expect(
      (await store.insert(note("r3", at(6.9)), { ...topic("r3"), now: at(6.9) })).topic,
    ).toBeNull();
    // Day 7.1: the first has left it, so one fits; the second has not.
    expect(
      (await store.insert(note("r4", at(7.1)), { ...topic("r4"), now: at(7.1) })).topic?.rideId,
    ).toBe("r4");
    expect(
      (await store.insert(note("r5", at(7.2)), { ...topic("r5"), now: at(7.2) })).topic,
    ).toBeNull();
  });

  it("gives a ride one topic and one note, whatever is repeated", async () => {
    await store.insert(note("r1", at(0)), { ...topic("r1"), now: at(0) });
    const again = await store.insert(note("r1", at(1)), { ...topic("r1"), now: at(1) });
    expect(again).toMatchObject({ inserted: false, insight: null, topic: null });
    expect(await store.topics(null, 10)).toHaveLength(1);
    const rows = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM insights");
    expect(rows.rows[0]?.n).toBe("1");
  });

  it("closes a topic when it is marked discussed, and marking it again changes nothing", async () => {
    const written = await store.insert(note("r1", at(0)), { ...topic("r1"), now: at(0) });
    const id = written.topic?.id ?? 0;
    const done = await store.markDiscussed(id, at(1));
    expect(done).toMatchObject({ status: "discussed", decidedAt: at(1) });
    const twice = await store.markDiscussed(id, at(5));
    expect(twice?.decidedAt).toEqual(at(1));
    expect(await store.markDiscussed(999, at(5))).toBeNull();
  });

  it("closes an open topic after 14 days and leaves a discussed one as it is", async () => {
    const a = await store.insert(note("r1", at(0)), { ...topic("r1"), now: at(0) });
    const b = await store.insert(note("r2", at(5)), { ...topic("r2"), now: at(5) });
    await store.markDiscussed(b.topic?.id ?? 0, at(6));
    // Day 13.9: nothing is 14 days old.
    expect(await store.expireTopics(at(13.9).getTime() - TOPIC_OPEN_MS, at(13.9))).toBe(0);
    // Day 14: the first is.
    expect(await store.expireTopics(at(14).getTime() - TOPIC_OPEN_MS, at(14))).toBe(1);
    expect((await store.topic(a.topic?.id ?? 0))?.status).toBe("expired");
    // Day 30: the discussed one stays discussed.
    expect(await store.expireTopics(at(30).getTime() - TOPIC_OPEN_MS, at(30))).toBe(0);
    expect((await store.topic(b.topic?.id ?? 0))?.status).toBe("discussed");
    // An expired topic is not marked discussed afterwards: it stays what it was.
    expect((await store.markDiscussed(a.topic?.id ?? 0, at(31)))?.status).toBe("expired");
  });

  it("lists by status, newest first", async () => {
    for (const [i, id] of ["r1", "r2", "r3"].entries()) {
      await store.insert(note(id, at(i)), {
        ...topic(id),
        now: at(i),
        limit: { max: 10, windowMs: TOPIC_WINDOW_MS },
      });
    }
    await store.markDiscussed((await store.topicForRide("r2"))?.id ?? 0, at(4));
    expect((await store.topics(["open"], 10)).map((t) => t.rideId)).toEqual(["r3", "r1"]);
    expect((await store.topics(["discussed"], 10)).map((t) => t.rideId)).toEqual(["r2"]);
    expect((await store.topics(null, 2)).map((t) => t.rideId)).toEqual(["r3", "r2"]);
  });
});
