import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { Db } from "../src/db.ts";
import { createServices } from "../src/services.ts";
import { COMPANION, TABLET } from "./helpers/app.ts";
import { clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import {
  FAKE_API_KEY,
  FAKE_ATHLETE_ID,
  type FakeIntervals,
  startFakeIntervals,
} from "./helpers/fake-intervals.ts";
import { hourRide, postFit } from "./helpers/outdoor.ts";
import { collectingLogger, recordedSleep, testClock } from "./helpers/support.ts";

/**
 * HEALTH DATA IS PRIVATE (ADR 0026): no heart rate, distance, position or any other measured value of an imported ride in a log line
 * or an error body. Distinctive figures go in through the door (an import, a duplicate, a refusal, a forwarding that fails) and
 * everything the service says is searched for them.
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
  await db.exec("TRUNCATE ride_destinations, rides, profile");
  fake.reset();
});

// Numbers that appear nowhere else in a log line: a timestamp or an id cannot be mistaken for them (the search is for whole numbers,
// not inside a hex id).
const BPM = 229;
const SPEED = 13.37; // m/s: 48 132 m in the hour
const DISTANCE = "48132";
const SECRETS = [String(BPM), DISTANCE, "13.37", "latitude", "longitude", "position"];
const standalone = (text: string, secret: string): boolean =>
  new RegExp(`(?<![0-9a-zA-Z.])${secret.replace(/\./g, "\\.")}(?![0-9a-zA-Z])`).test(text);

function rig() {
  const http: string[] = [];
  const service = collectingLogger();
  const clock = testClock("2027-03-14T12:00:00Z");
  const services = createServices({
    config: {
      INSIGHTS_ENABLED: false,
      INTERVALS_API_KEY: FAKE_API_KEY,
      INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
      INTERVALS_BASE_URL: fake.url,
      TIMEZONE: "UTC",
    },
    db,
    log: service.log,
    now: clock.now,
    sleep: recordedSleep().sleep,
  });
  const app = buildApp({
    config: { LOG_LEVEL: "debug", DEVICE_TOKENS: [TABLET, COMPANION] },
    db,
    services,
    logStream: { write: (line) => http.push(line) },
  });
  return {
    app,
    services,
    http,
    service,
    everything: () => `${http.join("\n")}\n${service.text()}`,
  };
}

describe("what the import says about a ride", () => {
  it("writes no measured value in any log line, for an import, a duplicate, every refusal and a forwarding that fails", async () => {
    const r = rig();
    await r.services.profile.update({ maxHeartRate: 230, thresholdHeartRate: 200 });
    const bytes = hourRide({ heartRate: () => BPM, speed: () => SPEED, power: () => 399 });
    const answers = [
      await postFit(r.app, bytes), // imported
      await postFit(r.app, bytes), // duplicate
      await postFit(r.app, hourRide({ heartRate: () => BPM, speed: () => SPEED, sport: 1 })), // unsupported_sport
      await postFit(r.app, hourRide({ heartRate: () => BPM, noRecords: true })), // empty_activity
      await postFit(r.app, Buffer.concat([bytes.subarray(0, 200), Buffer.from([1, 2, 3])])), // invalid_fit
      await postFit(r.app, bytes.subarray(0, bytes.length - 3)), // truncated
    ];
    expect(answers.map((a) => a.statusCode)).toEqual([201, 200, 422, 422, 422, 422]);

    // A forwarding that fails: intervals.icu refuses and quotes what it was sent.
    fake.failNext(8, 500, {
      path: "/activities",
      body: JSON.stringify({ error: `rejected a ride with heart rate ${BPM} and ${DISTANCE} m` }),
    });
    await r.services.wellness.forwarder?.runOnce();

    for (const secret of SECRETS) {
      expect(standalone(r.everything(), secret), `${secret} in the logs`).toBe(false);
      for (const answer of answers) {
        // An answer may carry the figures it is about (the summary), but an error body never does.
        if (answer.statusCode >= 400) {
          expect(standalone(answer.body, secret), `${secret} in an error body`).toBe(false);
        }
      }
    }
    // What is logged is the status, the id and sizes.
    const lines = r.service.lines.filter((l) => l.message === "outdoor ride import");
    expect(lines.map((l) => l.fields.status)).toEqual(["imported", "duplicate"]);
    expect(Object.keys(lines[0]?.fields ?? {}).sort()).toEqual([
      "bytes",
      "device",
      "id",
      "samples",
      "status",
    ]);
    await r.app.close();
  });

  it("does not put positions anywhere but the raw file: the row has none, and the answers none", async () => {
    const r = rig();
    const answer = await postFit(r.app, hourRide());
    expect(answer.statusCode).toBe(201);
    expect(answer.body).not.toMatch(/lat|lon|position/i);
    const columns = await db.query<{ column_name: string }>(
      "SELECT column_name FROM information_schema.columns WHERE table_name = 'activities'",
    );
    expect(columns.rows.map((c) => c.column_name).filter((c) => /lat|lon|pos/i.test(c))).toEqual(
      [],
    );
    await r.app.close();
  });
});
