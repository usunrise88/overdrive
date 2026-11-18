import type { Workout } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import { interpretReply, retryDelayMs } from "../src/strava/uploads.ts";
import { rideFixture } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeStrava, startFakeStrava } from "./helpers/fake-strava.ts";
import {
  clearStrava,
  destination,
  expectNoSecrets,
  fitOf,
  HOUR,
  MINUTE,
  type StravaHarness,
  startStravaHarness,
  storeRide,
  stravaUpload,
} from "./helpers/strava-harness.ts";

let db: Db;
let fake: FakeStrava;
let h: StravaHarness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeStrava();
});
afterAll(async () => {
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await clearStrava(db);
  fake.reset();
  h = startStravaHarness(db, fake); // 2026-11-18 08:00 UTC, rider in Paris
});

const run = () => h.uploads.runOnce();
const uploadPosts = () => fake.requestsTo("POST", "/api/v3/uploads");
const uploadPolls = () => fake.requests.filter((r) => /^\/api\/v3\/uploads\/\d+$/.test(r.path));
const nextAfter = (ms: number): string => new Date(h.clock.now().getTime() + ms).toISOString();
const errorLines = () => h.logging.lines.filter((line) => line.level === "error");

describe("a ride is posted to Strava as a virtual ride", () => {
  it("is queued with its FIT file, and posted with its name, description and the flags of an indoor ride", async () => {
    const fit = await storeRide(h, "ride-1", 1);
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "pending",
      attempts: 0,
      next: null,
    });
    expect(fake.requests).toEqual([]);

    expect(await run()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });

    expect(fake.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      "POST /oauth/token",
      "POST /api/v3/uploads",
      "GET /api/v3/uploads/7000001",
    ]);
    const post = fake.requests[1];
    expect(post?.authorization).toBe("Bearer fake-access-token-1");
    expect(post?.query).toEqual({}); // nothing in the address
    expect(post?.form).toEqual({
      file: { filename: "ride-1.fit", bytes: fit.length, sha256: sha256Hex(fit) },
      data_type: "fit",
      name: "Virtual ride 2026-11-18",
      description: "Free ride.\nDuration 3 s.\nAverage power 155 W.\nWork 0.5 kJ.\nFTP 220 W.",
      sport_type: "VirtualRide",
      trainer: "1",
      commute: "0",
      external_id: "ride-1",
    });

    const [activity] = fake.activities;
    expect(activity).toMatchObject({
      sportType: "VirtualRide",
      trainer: "1",
      commute: "0",
      externalId: "ride-1",
    });
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "done",
      attempts: 1,
      last_error: null,
      external_id: activity?.id,
    });
    expect(await stravaUpload(db, "ride-1")).toEqual({ upload_id: activity?.uploadId, polls: 1 });
    // Strava asks for at least a second between status checks: the worker waited before checking.
    expect(h.sleeps).toEqual([2000]);
    expect(h.logging.lines.filter((l) => l.message === "ride sent to Strava")).toHaveLength(1);
  });

  it("names a planned ride for its workout and describes the plan against the ride", async () => {
    const synthetic: Workout = {
      schema: 1,
      id: "synthetic-steps",
      name: "Synthetic steps",
      steps: [
        { type: "steady", duration: 60, target: 0.48 },
        { type: "steady", duration: 90, target: 0.88 },
        { type: "steady", duration: 60, target: 0.5 },
        { type: "free", duration: 90 },
      ],
    };
    await h.services.plan.upsert({
      id: "synthetic-steps",
      source: "intervals.icu",
      externalId: "1",
      date: "2026-11-18",
      name: "Synthetic steps",
      workout: synthetic,
      unsupportedReason: null,
    });
    await storeRide(h, "planned-1", 1, {
      record: rideFixture("synthetic-5min.json"),
      workoutId: "synthetic-steps",
    });
    await run();
    const [activity] = fake.activities;
    expect(activity?.name).toBe("Synthetic steps");
    expect(activity?.description).toBe(
      [
        "Synthetic steps: 1 min at 48% FTP, 1 min 30 s at 88% FTP, 1 min at 50% FTP, 1 min 30 s free.",
        "Duration 5 min.",
        "Average power 162 W, normalised power 176 W.",
        "Average heart rate 131 bpm.",
        "Work 46 kJ, load 3.9.",
        "Planned 5 min, ridden 4 min 45 s; 2 of 4 steps completed. Target power planned 164 W, ridden 163 W (-1%).",
        "FTP 250 W.",
      ].join("\n"),
    );
  });

  it("is posted as a free ride when its workout can no longer be found, and never held back for want of a description", async () => {
    await storeRide(h, "ride-1", 1, { workoutId: "icu-99" });
    await run();
    expect(fake.activities[0]?.name).toBe("Virtual ride 2026-11-18");
    expect(fake.activities[0]?.description?.startsWith("Free ride.")).toBe(true);
    expect(await destination(db, "ride-1")).toMatchObject({ state: "done" });
  });

  it("is only posted when its FIT is stored: a record alone queues nothing", async () => {
    expect(await run()).toEqual({ claimed: 0, done: 0, retry: 0, failed: 0 });
    expect(fake.requests).toEqual([]);
  });
});

describe("checking the upload", () => {
  it("asks again until Strava says the activity is ready, and records that activity's id", async () => {
    fake.pollsBeforeReady = 2;
    await storeRide(h, "ride-1", 1);
    await run();
    expect(uploadPolls()).toHaveLength(3);
    expect(h.sleeps).toEqual([2000, 2000, 2000]);
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "done",
      external_id: fake.activities[0]?.id,
    });
    expect(await stravaUpload(db, "ride-1")).toMatchObject({ polls: 3 });
  });

  it("asks a bounded number of times, persists the count and the wait, and never posts the ride again", async () => {
    fake.pollsBeforeReady = Number.POSITIVE_INFINITY;
    await storeRide(h, "ride-1", 1);

    // Four checks in a row, then a wait of 30 s, 60 s, 2 min, 4 min between rounds.
    const waits = [30_000, 60_000, 120_000, 240_000];
    for (const [round, wait] of waits.entries()) {
      expect(await run()).toEqual({ claimed: 1, done: 0, retry: 1, failed: 0 });
      expect(await stravaUpload(db, "ride-1")).toMatchObject({ polls: (round + 1) * 4 });
      expect(await destination(db, "ride-1")).toMatchObject({
        state: "pending",
        attempts: 0, // waiting for Strava is no one's failure
        last_error: null,
        next: nextAfter(wait),
      });
      // Not before it is due.
      h.clock.advance(wait - 1);
      expect(await run()).toMatchObject({ claimed: 0 });
      h.clock.advance(1);
    }

    // The twentieth check is the last: the upload is given up as stuck, and still not posted again.
    expect(await run()).toEqual({ claimed: 1, done: 0, retry: 0, failed: 1 });
    expect(uploadPolls()).toHaveLength(20);
    expect(uploadPosts()).toHaveLength(1);
    const row = await destination(db, "ride-1");
    expect(row?.state).toBe("failed");
    expect(row?.last_error).toContain("still processing upload 7000001 after 20 checks");
    expect(row?.last_error).toContain("not posted again");
    expect(errorLines()).toHaveLength(1);

    h.clock.advance(24 * HOUR);
    expect(await run()).toMatchObject({ claimed: 0 });
    expect(uploadPosts()).toHaveLength(1);
  });

  it("goes on checking after a restart without posting again", async () => {
    fake.pollsBeforeReady = Number.POSITIVE_INFINITY;
    await storeRide(h, "ride-1", 1);
    await run();
    expect(await stravaUpload(db, "ride-1")).toMatchObject({ polls: 4 });

    fake.pollsBeforeReady = 0;
    const restarted = startStravaHarness(db, fake, { clock: h.clock });
    h.clock.advance(30_000);
    expect(await restarted.uploads.runOnce()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });
    expect(uploadPosts()).toHaveLength(1);
    expect(fake.activities).toHaveLength(1);
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "done",
      external_id: fake.activities[0]?.id,
    });
  });

  it("does not post a ride the table says was posted, even when its queue row was lost track of", async () => {
    await storeRide(h, "ride-1", 1);
    // Strava took the upload, the process died before the answer was recorded as done: only strava_uploads knows.
    await db.query(
      "INSERT INTO strava_uploads (ride_id, upload_id, polls, posted_at) VALUES ('ride-1', '7000001', 0, now())",
    );
    fake.activities.push({
      id: "9000001",
      uploadId: "7000001",
      externalId: "ride-1",
      filename: "ride-1.fit",
      sha256: "x",
      name: null,
      description: null,
      sportType: null,
      trainer: null,
      commute: null,
    });
    // The fake knows no such upload: whatever it answers, no POST is made.
    await run();
    expect(uploadPosts()).toEqual([]);
    expect(uploadPolls()).toHaveLength(1);
  });
});

describe("a ride Strava already has", () => {
  it.each([
    ["on-poll", "reports it when the upload is checked"],
    ["in-post-reply", "reports it in the answer to the post"],
    ["post-400", "refuses the post as a repeat"],
  ] as const)("counts as sent when Strava %s", async (style, _words) => {
    const fit = await storeRide(h, "ride-1", 1);
    // The owner imported the same file by hand.
    fake.activities.push({
      id: "555",
      uploadId: "1",
      externalId: null,
      filename: "by-hand.fit",
      sha256: sha256Hex(fit),
      name: null,
      description: null,
      sportType: null,
      trainer: null,
      commute: null,
    });
    fake.duplicateStyle = style;
    expect(await run()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });
    expect(fake.activities).toHaveLength(1);
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "done",
      attempts: 1,
      last_error: null,
      external_id: "555",
    });
    expect(errorLines()).toEqual([]);
  });

  it("never posts a ride twice: done is final, and a repeated event or FIT changes nothing", async () => {
    await storeRide(h, "ride-1", 1);
    await run();
    expect(uploadPosts()).toHaveLength(1);

    h.clock.advance(48 * HOUR);
    expect(await run()).toMatchObject({ claimed: 0 });
    h.services.bus.emit("ride-stored", { rideId: "ride-1", startEpochMillis: 1 });
    await h.uploads.start();
    await h.uploads.idle();
    await h.uploads.stop();
    expect(uploadPosts()).toHaveLength(1);
  });

  it("recognises a repeat of a post whose answer was lost, by the duplicate Strava reports", async () => {
    await storeRide(h, "ride-1", 1);
    // The first try: Strava took the file, the answer never came back.
    fake.failNext(1, 503, { method: "POST", path: "/api/v3/uploads" });
    await run();
    expect(await destination(db, "ride-1")).toMatchObject({ state: "pending", attempts: 1 });
    // (The fake did not take it. Make it as if it had.)
    fake.activities.push({
      id: "777",
      uploadId: "1",
      externalId: "ride-1",
      filename: "ride-1.fit",
      sha256: "from-the-first-try",
      name: null,
      description: null,
      sportType: null,
      trainer: null,
      commute: null,
    });
    h.clock.advance(MINUTE);
    await run();
    expect(await destination(db, "ride-1")).toMatchObject({ state: "done", external_id: "777" });
    expect(fake.activities).toHaveLength(1);
  });
});

describe("the access token", () => {
  it("is renewed once after a 401 and the post is repeated once", async () => {
    await storeRide(h, "ride-1", 1);
    await run();
    fake.revokeAccessTokens();
    await storeRide(h, "ride-2", 2);
    fake.requests.length = 0;

    expect(await run()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });
    expect(fake.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      "POST /api/v3/uploads", // 401: the token was revoked
      "POST /oauth/token",
      "POST /api/v3/uploads",
      "GET /api/v3/uploads/7000002",
    ]);
    expect(fake.requests[0]?.authorization).toBe("Bearer fake-access-token-1");
    expect(fake.requests[2]?.authorization).toBe("Bearer fake-access-token-2");
    expect(fake.activities).toHaveLength(2);
    expect(await destination(db, "ride-2")).toMatchObject({ state: "done", attempts: 1 });
    expect(errorLines()).toEqual([]);
  });

  it("is stored rotated, and a restarted server goes on with it", async () => {
    await storeRide(h, "ride-1", 1);
    await run();
    const rotated = fake.validRefreshToken;
    const row = await db.query<{ refresh_token: string }>("SELECT refresh_token FROM strava_auth");
    expect(row.rows).toEqual([{ refresh_token: rotated }]);

    // A new process, with the same (now stale) value in its environment.
    const restarted = startStravaHarness(db, fake, { clock: h.clock });
    await storeRide(h, "ride-2", 2);
    expect(await restarted.uploads.runOnce()).toMatchObject({ claimed: 1, done: 1 });
    expect(errorLines()).toEqual([]);
  });

  it("a new refresh token in the environment replaces the stored one", async () => {
    await storeRide(h, "ride-1", 1);
    await run();
    fake.validRefreshToken = "fake-refresh-token-from-strava-auth";
    const restarted = startStravaHarness(db, fake, {
      clock: h.clock,
      refreshToken: "fake-refresh-token-from-strava-auth",
    });
    await storeRide(h, "ride-2", 2);
    fake.revokeAccessTokens();
    expect(await restarted.uploads.runOnce()).toMatchObject({ claimed: 1, done: 1 });
  });
});

describe("a wrong credential, scope or subscription", () => {
  it("is one clear error: said once in the log, on every waiting ride, and not asked again for six hours", async () => {
    fake.scope = "read";
    await storeRide(h, "ride-1", 1);
    expect(await run()).toEqual({ claimed: 1, done: 0, retry: 1, failed: 0 });

    const reason = (await destination(db, "ride-1"))?.last_error ?? "";
    expect(reason).toContain("HTTP 401");
    expect(reason).toContain("activity:write");
    expect(reason).toContain("strava-auth");
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "pending",
      attempts: 0,
      next: nextAfter(6 * HOUR),
    });
    expect(errorLines()).toHaveLength(1);
    expect(errorLines()[0]?.message).toContain("needs the owner");
    // The post, refused; the token renewed; the post again, refused: that is all.
    expect(uploadPosts()).toHaveLength(2);

    // A ride stored meanwhile shows the reason too, and nothing is asked in the next hours.
    await storeRide(h, "ride-2", 2);
    for (let i = 0; i < 5; i++) {
      h.clock.advance(HOUR);
      await run();
    }
    expect((await destination(db, "ride-2"))?.last_error).toBe(reason);
    expect(uploadPosts()).toHaveLength(2);
    expect(errorLines()).toHaveLength(1);

    // After six hours one probe goes out; it fails the same way and is not announced again.
    h.clock.advance(2 * HOUR);
    await run();
    expect(uploadPosts()).toHaveLength(4);
    expect(errorLines()).toHaveLength(1);
    expect(await destination(db, "ride-1")).toMatchObject({ state: "pending", attempts: 0 });

    // The owner fixes it and restarts: everything goes out at once.
    fake.scope = "activity:write";
    const restarted = startStravaHarness(db, fake, { clock: h.clock });
    await restarted.uploads.start();
    await restarted.uploads.idle();
    await restarted.uploads.stop();
    expect(await destination(db, "ride-1")).toMatchObject({ state: "done", last_error: null });
    expect(await destination(db, "ride-2")).toMatchObject({ state: "done", last_error: null });
    expect(fake.activities).toHaveLength(2);
    await expectNoSecrets(db, fake, h.logging);
  });

  it("names the subscription when Strava answers 403, and does not loop", async () => {
    fake.uploadsForbidden = true;
    await storeRide(h, "ride-1", 1);
    await run();
    const row = await destination(db, "ride-1");
    expect(row).toMatchObject({ state: "pending", attempts: 0 });
    expect(row?.last_error).toContain("HTTP 403");
    expect(row?.last_error).toContain("subscription");
    expect(uploadPosts()).toHaveLength(1);
    for (let i = 0; i < 20; i++) {
      h.clock.advance(15 * MINUTE);
      h.uploads.kick();
      await h.uploads.idle();
    }
    // Five hours of ticking: still the one request.
    expect(uploadPosts()).toHaveLength(1);
    expect(errorLines()).toHaveLength(1);
    await expectNoSecrets(db, fake, h.logging);
  });

  it("is reported when the refresh token itself is refused", async () => {
    await storeRide(h, "ride-1", 1);
    fake.validRefreshToken = "something-else-entirely";
    await run();
    const row = await destination(db, "ride-1");
    expect(row).toMatchObject({ state: "pending", attempts: 0 });
    expect(row?.last_error).toContain("refresh token");
    expect(row?.last_error).toContain("STRAVA_REFRESH_TOKEN");
    expect(fake.requests.map((r) => r.path)).toEqual(["/oauth/token"]);
    expect(errorLines()).toHaveLength(1);
    await expectNoSecrets(db, fake, h.logging);
  });
});

describe("Strava's rate limits", () => {
  it("honours Retry-After on a 429: nothing else is asked, and the wait is not held against the ride", async () => {
    await storeRide(h, "ride-1", 1);
    await storeRide(h, "ride-2", 2);
    fake.failNext(1, 429, { retryAfter: 120, method: "POST", path: "/api/v3/uploads" });

    expect(await run()).toEqual({ claimed: 1, done: 0, retry: 1, failed: 0 });
    expect(uploadPosts()).toHaveLength(1);
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "pending",
      attempts: 0,
      next: nextAfter(120_000),
    });
    expect((await destination(db, "ride-1"))?.last_error).toContain("HTTP 429");
    // The other ride was not even taken.
    expect(await destination(db, "ride-2")).toMatchObject({ attempts: 0, next: null });

    expect(await run()).toMatchObject({ claimed: 0 });
    h.clock.advance(119_000);
    expect(await run()).toMatchObject({ claimed: 0 });
    expect(uploadPosts()).toHaveLength(1);

    h.clock.advance(2_000);
    expect(await run()).toEqual({ claimed: 2, done: 2, retry: 0, failed: 0 });
    expect(fake.activities).toHaveLength(2);
  });

  it("waits for the end of the 15-minute window when a 429 says nothing else", async () => {
    h.clock.set("2026-11-18T08:07:00Z");
    await storeRide(h, "ride-1", 1);
    fake.failNext(1, 429, { method: "POST", path: "/api/v3/uploads" });
    await run();
    // Windows end at :00, :15, :30, :45 (UTC); a few seconds of slack after.
    expect((await destination(db, "ride-1"))?.next).toBe("2026-11-18T08:15:05.000Z");
    h.clock.set("2026-11-18T08:15:04Z");
    expect(await run()).toMatchObject({ claimed: 0 });
    h.clock.set("2026-11-18T08:15:06Z");
    expect(await run()).toMatchObject({ claimed: 1, done: 1 });
  });

  it("waits until midnight UTC when the headers say the daily limit is used up", async () => {
    await storeRide(h, "ride-1", 1);
    fake.usage = { usage15: 10, usageDay: 2000 };
    fake.failNext(1, 429, { method: "POST", path: "/api/v3/uploads" });
    await run();
    expect((await destination(db, "ride-1"))?.next).toBe("2026-11-19T00:00:05.000Z");
  });

  it("waits longer each time a 429 follows a 429", async () => {
    await storeRide(h, "ride-1", 1);
    const waits: number[] = [];
    for (let i = 0; i < 4; i++) {
      fake.failNext(1, 429, { method: "POST", path: "/api/v3/uploads" });
      await run();
      const next = Date.parse((await destination(db, "ride-1"))?.next ?? "");
      waits.push(next - h.clock.now().getTime());
      h.clock.set(next + 1);
    }
    // At least 15 minutes, 30, 60 … (the window's end may add up to a quarter hour).
    expect(waits[1]).toBeGreaterThanOrEqual(15 * MINUTE);
    expect(waits[2]).toBeGreaterThanOrEqual(30 * MINUTE);
    expect(waits[3]).toBeGreaterThanOrEqual(60 * MINUTE);
    expect(await destination(db, "ride-1")).toMatchObject({ attempts: 0 });
  });

  it("stops at the limit the headers report instead of finding it out with a 429", async () => {
    fake.usage = { usage15: 200, usageDay: 10 };
    await storeRide(h, "ride-1", 1);
    await storeRide(h, "ride-2", 2);
    expect(await run()).toMatchObject({ claimed: 1, done: 0, retry: 1 });
    // The post went through; its status is checked once the window has ended. The second ride waits.
    expect(uploadPosts()).toHaveLength(1);
    expect(uploadPolls()).toHaveLength(0);
    expect(await destination(db, "ride-1")).toMatchObject({ state: "pending", attempts: 0 });
    expect(h.logging.lines.some((l) => /rate limit reached/.test(l.message ?? ""))).toBe(true);
    expect(await run()).toMatchObject({ claimed: 0 });

    fake.usage = null;
    h.clock.set("2026-11-18T08:15:06Z");
    expect(await run()).toEqual({ claimed: 2, done: 2, retry: 0, failed: 0 });
    expect(uploadPosts()).toHaveLength(2); // ride-1 was not posted again
  });

  it("backs off 1, 2, 4 … minutes on a 5xx, persists the wait and counts the failures", async () => {
    await storeRide(h, "ride-1", 1);
    fake.failNext(2, 503, { method: "POST", path: "/api/v3/uploads" });

    expect(await run()).toMatchObject({ retry: 1 });
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "pending",
      attempts: 1,
      next: nextAfter(MINUTE),
    });
    expect((await destination(db, "ride-1"))?.last_error).toContain("HTTP 503");

    h.clock.advance(MINUTE - 1);
    expect(await run()).toMatchObject({ claimed: 0 });
    h.clock.advance(1);
    expect(await run()).toMatchObject({ retry: 1 });
    expect(await destination(db, "ride-1")).toMatchObject({
      attempts: 2,
      next: nextAfter(2 * MINUTE),
    });

    h.clock.advance(2 * MINUTE);
    expect(await run()).toEqual({ claimed: 1, done: 1, retry: 0, failed: 0 });
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "done",
      attempts: 3,
      last_error: null,
    });
    expect(fake.activities).toHaveLength(1);
    expect(errorLines()).toEqual([]);
  });

  it("tries again when Strava cannot be reached, and gives up after twenty failures", async () => {
    await storeRide(h, "ride-1", 1);
    fake.setUnreachable(true);
    await run();
    expect(await destination(db, "ride-1")).toMatchObject({ state: "pending", attempts: 1 });
    expect((await destination(db, "ride-1"))?.last_error).toContain("did not answer");
    for (let attempts = 2; attempts <= 19; attempts++) {
      h.clock.advance(7 * HOUR);
      await run();
      expect(await destination(db, "ride-1")).toMatchObject({ state: "pending", attempts });
    }
    h.clock.advance(7 * HOUR);
    await run();
    expect(await destination(db, "ride-1")).toMatchObject({ state: "failed", attempts: 20 });
    expect(errorLines()).toHaveLength(1);
    h.clock.advance(48 * HOUR);
    expect(await run()).toMatchObject({ claimed: 0 });
  });
});

describe("a ride Strava cannot use", () => {
  it("is given up with Strava's reason when processing fails, and never retried", async () => {
    fake.nextUploadError = "The file is malformed";
    await storeRide(h, "ride-1", 1);
    expect(await run()).toEqual({ claimed: 1, done: 0, retry: 0, failed: 1 });
    expect(await destination(db, "ride-1")).toMatchObject({
      state: "failed",
      last_error: "Strava could not make an activity from the file: The file is malformed",
    });
    expect(errorLines()).toHaveLength(1);
    h.clock.advance(48 * HOUR);
    expect(await run()).toMatchObject({ claimed: 0 });
    expect(uploadPosts()).toHaveLength(1);
  });

  it("is given up when Strava refuses the post itself, with its markup removed from the reason", async () => {
    await storeRide(h, "ride-1", 1);
    fake.failNext(1, 400, {
      method: "POST",
      path: "/api/v3/uploads",
      body: "<html><body><h1>Bad Request</h1><p>Could not read the <b>file</b>.</p></body></html>",
    });
    expect(await run()).toMatchObject({ failed: 1 });
    const row = await destination(db, "ride-1");
    expect(row?.state).toBe("failed");
    expect(row?.last_error).toContain("HTTP 400");
    expect(row?.last_error).toContain("Could not read the file");
    expect(row?.last_error).not.toMatch(/[<>]/);
  });
});

describe("what leaves the building", () => {
  it("carries no secret into the log, the table or an error, whatever goes wrong", async () => {
    await storeRide(h, "ride-1", 1);
    await storeRide(h, "ride-2", 2);
    await storeRide(h, "ride-3", 3);
    await storeRide(h, "ride-4", 4);
    fake.failNext(1, 503, { method: "POST", path: "/api/v3/uploads" });
    fake.failNext(1, 429, { retryAfter: 5, method: "POST", path: "/api/v3/uploads" });
    fake.nextUploadError = "The file is malformed";
    for (let i = 0; i < 6; i++) {
      await run();
      h.clock.advance(20 * MINUTE);
    }
    fake.revokeAccessTokens();
    fake.uploadsForbidden = true;
    h.clock.advance(HOUR);
    await run();
    await expectNoSecrets(db, fake, h.logging);
    expect(h.logging.text()).not.toMatch(/authorization/i);
  });
});

describe("turning Strava on", () => {
  it("is off without the credentials: no queue row, no request, and it says so", async () => {
    const off = startStravaHarness(db, fake, { configured: false });
    expect(off.services.strava).toBeNull();
    expect(off.services.destinations).toEqual(["intervals.icu"]);
    await storeRide(off, "ride-1", 1);
    expect(await destination(db, "ride-1")).toBeUndefined();
    expect(fake.requests).toEqual([]);
    expect(off.logging.lines.some((l) => /Strava upload is off/.test(l.message ?? ""))).toBe(true);
  });

  it("warns, naming the variables and no value, when only some of the credentials are set", async () => {
    const partial = startStravaHarness(db, fake, {
      configured: false,
      config: { STRAVA_CLIENT_ID: "424242", STRAVA_CLIENT_SECRET: "a-secret-value" },
    });
    expect(partial.services.strava).toBeNull();
    const warning = partial.logging.lines.find((l) => l.level === "warn");
    expect(warning?.message).toBe("Strava upload is off: STRAVA_REFRESH_TOKEN is not set");
    expect(partial.logging.text()).not.toContain("a-secret-value");
  });

  it("queues new rides but not the history, unless STRAVA_BACKFILL says so", async () => {
    const before = startStravaHarness(db, fake, { configured: false });
    await storeRide(before, "old-1", 1);
    await storeRide(before, "old-2", 2);

    // Turned on, without backfill: the two old rides are left alone.
    const on = startStravaHarness(db, fake, { clock: h.clock });
    expect(on.services.destinations).toEqual(["intervals.icu", "strava"]);
    await on.uploads.start();
    await on.uploads.idle();
    expect(fake.requests).toEqual([]);
    expect(await destination(db, "old-1")).toBeUndefined();

    await storeRide(on, "new-1", 3);
    await on.uploads.idle(); // the ride-stored event woke the worker
    await on.uploads.stop();
    expect(fake.activities.map((a) => a.externalId)).toEqual(["new-1"]);
    expect(await destination(db, "old-1")).toBeUndefined();

    // Turned on with backfill: the old rides follow, oldest first, and the new one is not posted again.
    const backfill = startStravaHarness(db, fake, { clock: h.clock, backfill: true });
    await backfill.uploads.start();
    await backfill.uploads.idle();
    await backfill.uploads.stop();
    expect(fake.activities.map((a) => a.externalId)).toEqual(["new-1", "old-1", "old-2"]);
    expect(await destination(db, "old-2")).toMatchObject({ state: "done" });
    expect(
      backfill.logging.lines.some(
        (l) => l.message?.startsWith("STRAVA_BACKFILL") && l.fields.rides === 2,
      ),
    ).toBe(true);
    expect(uploadPosts()).toHaveLength(3);
  });

  it("posts a ride when its FIT arrives, without anyone calling the worker: the event wakes it", async () => {
    await h.uploads.start();
    await storeRide(h, "ride-1", 1);
    // Wait for the condition, not for a time.
    await expect
      .poll(async () => (await destination(db, "ride-1"))?.state, { timeout: 5000 })
      .toBe("done");
    await h.uploads.stop();
    expect(fake.activities).toHaveLength(1);
  });
});

describe("helpers", () => {
  it("reads what an upload's answer means", () => {
    const base = {
      activityId: null,
      error: null,
      status: "Your activity is still being processed.",
    };
    expect(interpretReply(base)).toEqual({ kind: "processing" });
    expect(
      interpretReply({ ...base, activityId: "42", status: "Your activity is ready." }),
    ).toEqual({
      kind: "ready",
      activityId: "42",
    });
    expect(interpretReply({ ...base, error: "x.fit duplicate of activity 21234316" })).toEqual({
      kind: "duplicate",
      activityId: "21234316",
    });
    expect(interpretReply({ ...base, error: "Duplicate activity" })).toEqual({
      kind: "duplicate",
      activityId: null,
    });
    expect(interpretReply({ ...base, error: "The file has no records" })).toEqual({
      kind: "refused",
      reason: "The file has no records",
    });
    expect(interpretReply({ ...base, status: "The created activity has been deleted." })).toEqual({
      kind: "deleted",
    });
  });

  it("backs off 1, 2, 4 … minutes up to six hours", () => {
    expect([1, 2, 3, 4].map(retryDelayMs)).toEqual([MINUTE, 2 * MINUTE, 4 * MINUTE, 8 * MINUTE]);
    expect(retryDelayMs(30)).toBe(6 * HOUR);
  });

  it("distinct FIT files differ, so the fake can tell duplicates by content", () => {
    expect(fitOf(1)).not.toEqual(fitOf(2));
  });
});
