import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { Db, Queryable } from "../src/db.ts";
import { createServices } from "../src/services.ts";
import { COMPANION, TABLET } from "./helpers/app.ts";
import { clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import {
  FAKE_API_KEY,
  FAKE_ATHLETE_ID,
  type FakeIntervals,
  startFakeIntervals,
} from "./helpers/fake-intervals.ts";
import { collectingLogger, recordedSleep, testClock } from "./helpers/support.ts";
import {
  batch,
  exercise,
  get,
  heartRate,
  oxygen,
  post,
  respiratory,
  resting,
  sleep,
} from "./helpers/wellness-data.ts";

/**
 * HEALTH DATA IS PRIVATE: no measured value in any log line and none in an error body. Distinctive figures go in through every
 * door and everything the service says (its HTTP log, its own log, its answers) is searched for them.
 */

let db: Db;
let fake: FakeIntervals;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});

afterAll(async () => {
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await clearWellness(db);
  fake.reset();
});

// Numbers that appear nowhere else in a log line: a timestamp or an id cannot be mistaken for them.
const RATE = 17.31415;
const SPO2 = 94.71828;
const HRV = 57.27182;
const BPM = 137;
const TITLE = "Secret Workout Title";
const WRONG_BPM = 7313917;
const WRONG_RATE = 80_004.5;
const SECRETS = [
  String(RATE),
  String(SPO2),
  String(HRV),
  TITLE,
  String(WRONG_BPM),
  String(WRONG_RATE),
];

interface Rig {
  app: ReturnType<typeof buildApp>;
  http: string[];
  service: ReturnType<typeof collectingLogger>;
  everything: () => string;
}

/** The whole service with every log line kept: the HTTP server's, at the most talkative level, and the modules'. */
function rig(wrap?: (db: Db) => Db): Rig {
  const http: string[] = [];
  const service = collectingLogger();
  const clock = testClock("2026-11-18T08:00:00Z");
  const useDb = wrap === undefined ? db : wrap(db);
  const services = createServices({
    config: {
      INTERVALS_API_KEY: FAKE_API_KEY,
      INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
      INTERVALS_BASE_URL: fake.url,
      TIMEZONE: "UTC",
    },
    db: useDb,
    log: service.log,
    now: clock.now,
    sleep: recordedSleep().sleep,
  });
  const app = buildApp({
    config: { LOG_LEVEL: "trace", DEVICE_TOKENS: [TABLET, COMPANION] },
    db: useDb,
    services,
    logStream: { write: (line) => http.push(line) },
  });
  return { app, http, service, everything: () => `${http.join("")}\n${service.text()}` };
}

const noSecrets = (text: string, extra: string[] = []) => {
  for (const secret of [...SECRETS, ...extra]) expect(text, secret).not.toContain(secret);
};

describe("no measured value in any log line", () => {
  it("through a whole upload, the figures, the recovery, the activities and the pull", async () => {
    const r = rig();
    try {
      fake.wellness["2026-11-17"] = { restingHR: 71.8318, sleepSecs: 24681, hrv: 66.6313 };
      const upload = await post(
        r.app,
        batch(
          resting("rhr", "2026-11-17T06:30:00Z", BPM),
          respiratory("resp", "2026-11-17T03:00:00Z", RATE),
          oxygen("spo2", "2026-11-17T03:00:00Z", SPO2),
          { kind: "heartRateVariabilityRmssd", id: "hrv", time: "2026-11-17T03:30:00Z", ms: HRV },
          sleep("night", "2026-11-16T22:45:00Z", "2026-11-17T06:15:00Z"),
          heartRate("hr", "2026-11-16T22:45:00Z", "2026-11-17T06:15:00Z", 10, () => BPM),
          exercise("ex", "other_workout", "2026-11-17T19:05:00Z", "2026-11-17T19:47:00Z", {
            title: TITLE,
            heartRateSamples: [{ t: "2026-11-17T19:05:05Z", bpm: BPM }],
          }),
        ),
      );
      expect(upload.statusCode, upload.body).toBe(200);
      // Counts only in the answer.
      noSecrets(upload.body);
      expect(Object.keys(upload.json() as object).sort()).toEqual([
        "accepted",
        "byType",
        "duplicates",
        "generatedAt",
        "rejected",
      ]);

      await r.app.services.wellness.refresh();
      await r.app.services.wellness.forwarder?.runOnce();
      for (const url of [
        "/v1/wellness?from=2026-11-10&to=2026-11-18",
        "/v1/recovery",
        "/v1/activities/watch-ex",
        "/v1/profile",
      ]) {
        expect((await get(r.app, url)).statusCode, url).toBe(200);
      }
      const log = r.everything();
      noSecrets(log, ["71.8318", "24681", "66.6313"]);
      // The log has said what was done: counts, ids, devices.
      expect(r.http.join("")).toContain("wellness batch");
      expect(r.http.join("")).not.toContain('"bpm"');
      expect(r.service.text()).toContain("wellness pulled");
    } finally {
      await r.app.close();
    }
  });

  it("when a batch is refused, in the answer and in the log", async () => {
    const r = rig();
    try {
      const res = await post(
        r.app,
        batch(
          resting("bad", "2026-11-17T06:30:00Z", WRONG_BPM),
          respiratory("worse", "2026-11-17T03:00:00Z", WRONG_RATE),
          {
            kind: "oxygenSaturation",
            id: "title",
            time: "2026-11-17T03:00:00Z",
            percent: SPO2,
            [TITLE]: 1,
          } as never,
        ),
      );
      expect(res.statusCode).toBe(422);
      expect(res.body).toContain("/records/0/bpm");
      noSecrets(res.body);
      noSecrets(r.everything());
    } finally {
      await r.app.close();
    }
  });

  it("when the body is not JSON or not even an object", async () => {
    const r = rig();
    try {
      const garbage = await post(
        r.app,
        `{"source":"healthconnect","records":[{"bpm":${WRONG_BPM}, "title":"${TITLE}" oops`,
      );
      expect(garbage.statusCode).toBe(400);
      noSecrets(garbage.body);
      const text = await post(r.app, JSON.stringify(`${TITLE} ${WRONG_BPM}`));
      expect(text.statusCode).toBe(422);
      noSecrets(text.body);
      // A body over the limit is refused before it is read.
      const huge = await post(r.app, `{"x":"${TITLE}"}${" ".repeat(4 * 1024 * 1024)}`);
      expect(huge.statusCode).toBe(413);
      noSecrets(huge.body);
      noSecrets(r.everything());
    } finally {
      await r.app.close();
    }
  });

  it("when the database fails and its message quotes the row", async () => {
    // A driver error of the kind PostgreSQL makes: "Failing row contains (...)" with the values in it.
    const quote = `Failing row contains (healthconnect, rhr, resting, ${WRONG_RATE}, ${TITLE})`;
    const failing = (inner: Queryable): Queryable => ({
      ...inner,
      query: async (sql, params) => {
        if (/INSERT INTO wellness_records/.test(sql)) {
          throw Object.assign(new Error(`new row violates check constraint. ${quote}`), {
            code: "23514",
            detail: quote,
          });
        }
        return inner.query(sql, params);
      },
    });
    const r = rig((real) => ({
      ...failing(real),
      transaction: (work) => real.transaction((tx) => work(failing(tx))),
      close: () => real.close(),
    }));
    try {
      const res = await post(r.app, batch(resting("rhr", "2026-11-17T06:30:00Z", BPM)));
      expect(res.statusCode).toBe(500);
      expect(res.json()).toEqual({
        error: { code: "internal_error", message: "Something went wrong on the server" },
      });
      const log = r.everything();
      noSecrets(log);
      expect(log).not.toContain("Failing row");
      // The code survives, which is all there is to know.
      expect(log).toContain("wellness storage failed (23514)");
    } finally {
      await r.app.close();
    }
  });

  it("when intervals.icu refuses with a body that quotes what was sent, or answers with something unreadable", async () => {
    const r = rig();
    try {
      await post(
        r.app,
        batch(
          exercise("ex", "other_workout", "2026-11-17T19:05:00Z", "2026-11-17T19:47:00Z", {
            title: TITLE,
            heartRateSamples: [{ t: "2026-11-17T19:05:05Z", bpm: BPM }],
          }),
        ),
      );
      fake.failNext(1, 422, {
        path: "/activities/manual",
        body: JSON.stringify({ error: `bad ${TITLE} ${BPM} ${RATE}` }),
      });
      await r.app.services.wellness.forwarder?.runOnce();
      fake.failNext(1, 200, {
        path: "/wellness",
        body: `[{"id":"2026-11-17","restingHR":${WRONG_RATE},"junk":`,
      });
      await r.app.services.wellness.refresh();
      noSecrets(r.everything(), [`${String(BPM)} `]);
      expect(r.service.text()).not.toContain(`${RATE}`);
    } finally {
      await r.app.close();
    }
  });
});

describe("what the answers say about a figure", () => {
  it("an unknown activity or a bad range names no value", async () => {
    const r = rig();
    try {
      for (const url of [
        "/v1/activities/watch-nope",
        "/v1/wellness?from=2026-13-40&to=2026-11-18",
      ]) {
        const res = await get(r.app, url);
        expect(res.statusCode).toBeGreaterThanOrEqual(400);
        noSecrets(res.body);
      }
      noSecrets(r.everything());
    } finally {
      await r.app.close();
    }
  });
});
