import {
  type LiveCue,
  validateApiError,
  validateLiveCue,
  validateLiveSessionEndResponse,
  validateLiveSessionStartResponse,
  validateLiveStreamEnd,
} from "@overdrive/contracts";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION, expectError, TABLET } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import {
  flush,
  heard,
  LIVE_RIDE_ID,
  type LiveEnv,
  type LiveHarness,
  openStream,
  startLive,
  startLiveEnv,
  stopLiveEnv,
} from "./helpers/live.ts";

/**
 * The live coach's routes (spec §12.1, §8.3, ADR 0038): the device token, the bodies and what is said of a bad one, the 404 of a session
 * that is not the live one, the end and the report that a cue was played, and the event stream as the tablet reads it: its headers and its
 * first lines, the id of every cue, the resume with `Last-Event-ID`, the heartbeat, the last `end` event and the connection that closes.
 */

let db: Db;
let env: LiveEnv;
const open: LiveHarness[] = [];

beforeAll(async () => {
  db = await openMigratedTestDb();
  env = await startLiveEnv();
});
afterAll(async () => {
  await stopLiveEnv(env);
  await db.close();
});
beforeEach(async () => {
  env.llm.reset();
  env.tts.reset();
  await db.exec("TRUNCATE live_cues, planned_workouts, planned_workout_extras, profile");
});
afterEach(async () => {
  for (const h of open.splice(0)) await h.close();
});

async function boot(config: Parameters<typeof startLive>[2] = {}): Promise<LiveHarness> {
  const h = await startLive(db, env, config);
  open.push(h);
  await h.seed();
  await h.begin();
  return h;
}

/** A service that can make two cues in a few snapshots: the step-start of step 1 and the two-minute warning of the same step. */
const quick = { config: { LIVE_COACH_MIN_GAP_SECONDS: 0, LIVE_COACH_TRIGGER_COOLDOWN_SECONDS: 0 } };

async function twoCues(h: LiveHarness): Promise<void> {
  await h.sendAll([{ step: 0, left: 5 }]);
  await h.sendAll([{ step: 1, left: 125 }]);
}

describe("the device token", () => {
  it("is needed on every route of the live coach", async () => {
    const h = await boot();
    const id = h.sessionId;
    const calls: [string, string, unknown][] = [
      ["POST", "/v1/live/sessions", { rideId: LIVE_RIDE_ID, clock: 1 }],
      ["POST", `/v1/live/sessions/${id}/snapshots`, {}],
      ["GET", `/v1/live/sessions/${id}/cues`, undefined],
      ["POST", `/v1/live/sessions/${id}/end`, {}],
      ["POST", `/v1/live/sessions/${id}/cues/1/played`, { played: true }],
    ];
    for (const [method, url, body] of calls) {
      const none = await h.app.inject({
        method: method as "GET" | "POST",
        url,
        ...(body === undefined ? {} : { payload: body as object }),
      });
      expectError(none, "unauthorized");
      expect(none.headers["www-authenticate"]).toContain("Bearer");
      const wrong = await h.app.inject({
        method: method as "GET" | "POST",
        url,
        headers: { authorization: "Bearer not-the-token-0123456789abcdefghijklmnopqrstuvwxyz" },
        ...(body === undefined ? {} : { payload: body as object }),
      });
      expectError(wrong, "unauthorized");
    }
    // The stream refuses a connection without the token before anything is sent.
    const stream = await openStream(h, { token: null });
    expect(stream.status).toBe(401);
    // And nothing was asked of anyone.
    expect(env.llm.calls).toHaveLength(0);
  });

  it("is any device's: the tablet's and the companion's are both accepted", async () => {
    const h = await boot();
    expect((await h.post(`/sessions/${h.sessionId}/end`, {}, TABLET)).statusCode).toBe(200);
    const answer = await h.post("/sessions", { rideId: LIVE_RIDE_ID, clock: 1 }, COMPANION);
    expect(answer.statusCode).toBe(201);
  });
});

describe("the start", () => {
  it("answers 201 with the contract's body and no cache", async () => {
    const h = await boot();
    const response = await h.post("/sessions", {
      rideId: LIVE_RIDE_ID,
      workoutId: "icu-7001",
      clock: 1_795_000_000_000,
    });
    expect(response.statusCode).toBe(201);
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(validateLiveSessionStartResponse(response.json())).toMatchObject({ ok: true });
  });

  it("refuses a body that is not a start, saying where and never what", async () => {
    const h = await boot();
    for (const body of [
      "not json at all",
      "[]",
      "null",
      {},
      { rideId: "ride 1795/xyz", clock: 5 },
      { rideId: LIVE_RIDE_ID },
      { rideId: LIVE_RIDE_ID, clock: -1 },
      { rideId: LIVE_RIDE_ID, clock: 1, structureHash: "SECRETSECRETSECR" },
      { rideId: LIVE_RIDE_ID, clock: 1, token: "x" },
    ]) {
      const response = await h.post("/sessions", body);
      const error = expectError(response, "invalid_json");
      expect(error.message.length).toBeLessThan(400);
      expect(error.message).not.toContain("SECRETSECRETSECR");
      expect(error.message).not.toContain("ride 1795");
    }
    // An empty body is not a start either.
    expectError(await h.post("/sessions"), "invalid_json");
  });

  it("refuses what is not JSON: plain text is not a start, and another type is not accepted at all", async () => {
    const h = await boot();
    const text = await h.app.inject({
      method: "POST",
      url: "/v1/live/sessions",
      headers: { ...bearer(), "content-type": "text/plain" },
      payload: "rideId=1",
    });
    expectError(text, "invalid_json");
    const xml = await h.app.inject({
      method: "POST",
      url: "/v1/live/sessions",
      headers: { ...bearer(), "content-type": "application/xml" },
      payload: "<start/>",
    });
    expectError(xml, "unsupported_media_type");
  });
});

describe("the snapshots", () => {
  it("answer 204 with no body", async () => {
    const h = await boot();
    const response = await h.send({ step: 0, left: 300 });
    expect(response.statusCode).toBe(204);
    expect(response.body).toBe("");
  });

  it("refuse a body that is not a snapshot, saying where and never the value of a reading", async () => {
    const h = await boot();
    const base = {
      seq: 5,
      clock: 1_795_000_100_000,
      elapsedSeconds: 100,
      paused: false,
      muted: false,
      step: { index: 0, type: "ramp", durationSeconds: 600, timeLeftSeconds: 500 },
      stepCount: 6,
    };
    const bad: unknown[] = [
      { ...base, power: 987654 },
      { ...base, heartRate: 1234567 },
      { ...base, step: { ...base.step, timeLeftSeconds: 601 } },
      { ...base, step: { ...base.step, index: 9 } },
      { ...base, gear: 4 },
      { ...base, muted: "yes" },
      {},
      [],
    ];
    for (const body of bad) {
      const response = await h.post(`/sessions/${h.sessionId}/snapshots`, body);
      const error = expectError(response, "invalid_json");
      expect(error.message).not.toMatch(/987654|1234567/);
    }
    // A snapshot that is too large is refused before it is read (a real one is about 400 bytes).
    const large = await h.post(
      `/sessions/${h.sessionId}/snapshots`,
      JSON.stringify({ ...base, pad: "x".repeat(20_000) }),
    );
    expectError(large, "payload_too_large");
  });

  it("are ignored when they are not newer than the last, with no word and no second look at the ride", async () => {
    const h = await boot();
    const first = await h.send({ step: 0, left: 5 });
    expect(first.statusCode).toBe(204);
    await h.idle();
    expect(env.llm.calls).toHaveLength(1);
    // The same snapshot again (a retry after a lost answer): taken as received, and it asks nobody anything.
    const again = await h.app.inject({
      method: "POST",
      url: `/v1/live/sessions/${h.sessionId}/snapshots`,
      headers: { ...bearer(), "content-type": "application/json" },
      payload: JSON.stringify({
        seq: 0,
        clock: h.clock.now().getTime(),
        elapsedSeconds: 5,
        paused: false,
        muted: false,
        step: {
          index: 0,
          type: "ramp",
          durationSeconds: 600,
          timeLeftSeconds: 5,
          targetWatts: 135,
        },
        stepCount: 6,
        power: 135,
      }),
    });
    expect(again.statusCode).toBe(204);
    await h.idle();
    expect(env.llm.calls).toHaveLength(1);
  });

  it("are a 404 for a session that is not the live one", async () => {
    const h = await boot();
    const response = await h.post(`/sessions/ls-0123456789abcdef/snapshots`, {
      seq: 0,
      clock: 1,
      elapsedSeconds: 0,
      paused: false,
      muted: false,
      step: { index: 0, type: "free", durationSeconds: 60, timeLeftSeconds: 60 },
      stepCount: 6,
    });
    const error = expectError(response, "not_found");
    expect(error.message).toMatch(/Start a new one/);
    // A path that is not even an id is the same 404.
    expectError(await h.post(`/sessions/${"x".repeat(80)}/snapshots`, {}), "not_found");
    expectError(await h.post("/sessions/has%20space/snapshots", {}), "not_found");
  });
});

describe("the end of a session", () => {
  it("answers with how many cues it made, with no body, with {} or with a reason", async () => {
    const h = await boot();
    await h.sendAll([{ step: 0, left: 5 }]);
    const response = await h.post(`/sessions/${h.sessionId}/end`, {
      reason: "finished",
      clock: 1_795_003_600_000,
    });
    expect(response.statusCode).toBe(200);
    expect(validateLiveSessionEndResponse(response.json())).toMatchObject({ ok: true });
    expect(response.json()).toEqual({ sessionId: h.sessionId, status: "ended", cues: 1 });
    // Again, and with no body, and with {}: it had ended already.
    const again = await h.post(`/sessions/${h.sessionId}/end`);
    expect(again.statusCode).toBe(200);
    expect(again.json()).toEqual({ sessionId: h.sessionId, status: "already-ended", cues: 1 });
    expect((await h.post(`/sessions/${h.sessionId}/end`, {})).json().status).toBe("already-ended");
  });

  it("answers an end with no body at all for a live session", async () => {
    const h = await boot();
    const response = await h.post(`/sessions/${h.sessionId}/end`);
    expect(response.json()).toMatchObject({ status: "ended", cues: 0 });
  });

  it("refuses a body that is not an end, and a session nobody knows", async () => {
    const h = await boot();
    expectError(
      await h.post(`/sessions/${h.sessionId}/end`, { reason: "crashed" }),
      "invalid_json",
    );
    expectError(
      await h.post(`/sessions/${h.sessionId}/end`, { reason: "finished", cues: 4 }),
      "invalid_json",
    );
    // The session is still live after a refused end.
    expect((await h.send({ step: 0, left: 300 })).statusCode).toBe(204);
    expectError(await h.post("/sessions/ls-0123456789abcdef/end", {}), "not_found");
  });

  it("ends the snapshots: the session is gone", async () => {
    const h = await boot();
    const id = h.sessionId;
    await h.post(`/sessions/${id}/end`, {});
    expect((await h.send({ step: 0, left: 300 })).statusCode).toBe(404);
  });
});

describe("a cue played, or not", () => {
  it("is stored with the cue, whichever way the tablet says it", async () => {
    const h = await boot(quick);
    await twoCues(h);
    expect(
      (await h.post(`/sessions/${h.sessionId}/cues/1/played`, { played: true })).statusCode,
    ).toBe(204);
    expect(
      (await h.post(`/sessions/${h.sessionId}/cues/2/played`, { played: false, clock: 1 }))
        .statusCode,
    ).toBe(204);
    const rows = await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID);
    expect(rows.map((r) => r.played)).toEqual([true, false]);
    // Said again later, the last word counts.
    await h.post(`/sessions/${h.sessionId}/cues/2/played`, { played: true });
    expect((await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID))[1]?.played).toBe(true);
  });

  it("is still taken after the session has ended: the tablet reports when it can", async () => {
    const h = await boot();
    await h.sendAll([{ step: 0, left: 5 }]);
    const id = h.sessionId;
    await h.post(`/sessions/${id}/end`, {});
    expect((await h.post(`/sessions/${id}/cues/1/played`, { played: true })).statusCode).toBe(204);
    expect((await h.services.liveCoach.cuesOfRide(LIVE_RIDE_ID))[0]?.played).toBe(true);
  });

  it("is a 404 for a cue the session never made, a session nobody knows and an id that is not a number", async () => {
    const h = await boot();
    await h.sendAll([{ step: 0, left: 5 }]);
    expectError(
      await h.post(`/sessions/${h.sessionId}/cues/9/played`, { played: true }),
      "not_found",
    );
    expectError(
      await h.post(`/sessions/${h.sessionId}/cues/0/played`, { played: true }),
      "not_found",
    );
    expectError(
      await h.post(`/sessions/${h.sessionId}/cues/one/played`, { played: true }),
      "not_found",
    );
    expectError(
      await h.post("/sessions/ls-0123456789abcdef/cues/1/played", { played: true }),
      "not_found",
    );
  });

  it("wants to be told whether it was played", async () => {
    const h = await boot();
    await h.sendAll([{ step: 0, left: 5 }]);
    expectError(await h.post(`/sessions/${h.sessionId}/cues/1/played`, {}), "invalid_json");
    expectError(await h.post(`/sessions/${h.sessionId}/cues/1/played`), "invalid_json");
    expectError(
      await h.post(`/sessions/${h.sessionId}/cues/1/played`, { played: "yes" }),
      "invalid_json",
    );
  });
});

describe("the event stream", () => {
  it("is text/event-stream that no proxy may hold back, and opens with the delay to reconnect and a comment", async () => {
    const h = await boot();
    const stream = await openStream(h);
    expect(stream.status).toBe(200);
    expect(stream.headers.get("content-type")).toBe("text/event-stream; charset=utf-8");
    expect(stream.headers.get("cache-control")).toBe("no-cache, no-transform");
    expect(stream.headers.get("x-accel-buffering")).toBe("no");
    expect(await stream.next()).toEqual({ retry: "3000" });
    expect(await stream.next()).toEqual({ comment: "open" });
  });

  it("carries each cue as an event with its number as the SSE id, valid against the contract", async () => {
    const h = await boot(quick);
    const stream = await openStream(h);
    await twoCues(h);
    const cues = await heard(h, stream);
    expect(cues.map((c) => c.id)).toEqual([1, 2]);
    const frames = stream.frames.filter((f) => f.event === "cue");
    expect(frames.map((f) => f.id)).toEqual(["1", "2"]);
    for (const cue of cues) expect(validateLiveCue(cue)).toMatchObject({ ok: true });
    // One line of data: the audio is inline, in base64, and nothing else is.
    for (const frame of frames) expect(frame.data).not.toContain("\n");
  });

  it("resumes with Last-Event-ID: the cues after it that are still worth playing", async () => {
    const h = await boot(quick);
    const first = await openStream(h);
    await twoCues(h);
    await heard(h, first);
    first.abort();
    // The tablet came back having seen cue 1: it is sent cue 2, and no other.
    const back = await openStream(h, { lastEventId: 1 });
    expect(await back.next()).toEqual({ retry: "3000" });
    expect(await back.next()).toEqual({ comment: "open" });
    const { cue, frame } = await back.nextCue();
    expect([cue.id, frame.id]).toEqual([2, "2"]);
    await flush(h, back);
    expect(back.cues().map((c) => c.id)).toEqual([2]);
    // Having seen both: nothing to resume.
    const caught = await openStream(h, { lastEventId: 2 });
    await flush(h, caught);
    expect(caught.cues()).toEqual([]);
    // Having seen none (no Last-Event-ID): both, in order.
    const fresh = await openStream(h);
    await flush(h, fresh);
    expect(fresh.cues().map((c) => c.id)).toEqual([1, 2]);
  });

  it("does not resume a cue that has expired: a late cue about a moment that has passed is worse than none", async () => {
    const h = await boot(quick);
    const first = await openStream(h);
    await twoCues(h);
    await heard(h, first);
    first.abort();
    // 8 s after cue 2 was ready it is no longer worth playing; cue 1 was ready 5 s earlier still.
    h.clock.advance(8000);
    const back = await openStream(h, { lastEventId: 0 });
    await flush(h, back);
    expect(back.cues()).toEqual([]);
  });

  it("replays a cue made while the tablet was away, and only that one", async () => {
    const h = await boot(quick);
    const first = await openStream(h);
    await h.sendAll([{ step: 0, left: 5 }]);
    await first.nextCue();
    first.abort();
    // The tablet is off the network: the next cue is made, nobody listens.
    await h.sendAll([{ step: 1, left: 125 }]);
    const back = await openStream(h, { lastEventId: 1 });
    const { cue } = await back.nextCue();
    expect(cue).toMatchObject({ id: 2, trigger: "two-minutes" });
    expect(validateLiveCue(cue)).toMatchObject({ ok: true });
  });

  it("writes a comment every time the loop ticks, so that a quiet connection is not closed on the way", async () => {
    const h = await boot();
    const stream = await openStream(h);
    // The tick is a timer of the service: 15 s, an interval, started with the session.
    expect(h.timers.pending).toContainEqual({ kind: "every", ms: 15_000 });
    await flush(h, stream);
    await flush(h, stream);
    await flush(h, stream);
    expect(stream.frames.filter((f) => f.comment === "hb")).toHaveLength(3);
  });

  it("ends with an `end` event when the app ends the session, and closes", async () => {
    const h = await boot();
    const stream = await openStream(h);
    await h.post(`/sessions/${h.sessionId}/end`, { reason: "finished" });
    const end = await stream.nextEvent("end");
    expect(validateLiveStreamEnd(JSON.parse(end.data ?? "null"))).toMatchObject({ ok: true });
    expect(JSON.parse(end.data ?? "null")).toEqual({ reason: "ended" });
    // The end carries no id: it is not a cue to resume from.
    expect(end.id).toBeUndefined();
    await stream.whenClosed;
    expect(stream.closed).toBe(true);
    // The session is gone: a new stream for it is a 404.
    expect((await openStream(h)).status).toBe(404);
  });

  it("tells every listener, and a listener that went away does not disturb the others", async () => {
    const h = await boot(quick);
    const a = await openStream(h);
    const b = await openStream(h);
    const c = await openStream(h);
    await a.next();
    c.abort();
    await h.sendAll([{ step: 0, left: 5 }]);
    expect((await a.nextCue()).cue.id).toBe(1);
    expect((await b.nextCue()).cue.id).toBe(1);
    await h.sendAll([{ step: 1, left: 125 }]);
    expect((await a.nextCue()).cue.id).toBe(2);
    expect((await b.nextCue()).cue.id).toBe(2);
    await h.post(`/sessions/${h.sessionId}/end`, {});
    await a.nextEvent("end");
    await b.nextEvent("end");
    await Promise.all([a.whenClosed, b.whenClosed]);
  });

  it("is a 404 for a session that is not the live one, and for an id that is not an id, before any event", async () => {
    const h = await boot();
    const unknown = await openStream(h, { sessionId: "ls-0123456789abcdef" });
    expect(unknown.status).toBe(404);
    expect(unknown.headers.get("content-type")).toMatch(/^application\/json/);
    const bad = await openStream(h, { sessionId: "not%20an%20id" });
    expect(bad.status).toBe(404);
    const response = await h.app.inject({
      method: "GET",
      url: "/v1/live/sessions/ls-0123456789abcdef/cues",
      headers: bearer(),
    });
    const body = expectError(response, "not_found");
    expect(validateApiError({ error: body })).toMatchObject({ ok: true });
  });

  it("is ended with `end` (shutdown) when the service stops, and the service stops: an open stream does not hold it", async () => {
    const h = await boot();
    const stream = await openStream(h);
    await stream.next();
    await h.close();
    open.splice(open.indexOf(h), 1);
    const end = await stream.nextEvent("end");
    expect(JSON.parse(end.data ?? "null")).toEqual({ reason: "shutdown" });
    await stream.whenClosed;
  });

  it("is ended with `end` (superseded) when a new session starts", async () => {
    const h = await boot();
    const stream = await openStream(h);
    await h.begin();
    const end = await stream.nextEvent("end");
    expect(JSON.parse(end.data ?? "null")).toEqual({ reason: "superseded" });
  });
});

describe("what the stream never carries", () => {
  it("is audio and the line and nothing of the ride: no reading, no id of the ride, no step", async () => {
    const h = await boot();
    const stream = await openStream(h);
    await h.sendAll([{ step: 0, left: 5, power: 133, hr: 171, cad: 93 }]);
    const [cue] = await heard(h, stream);
    const keys = Object.keys(cue as LiveCue).sort();
    expect(keys).toEqual(["audio", "createdAt", "expiresAt", "id", "text", "trigger", "ttlMs"]);
    const wire = stream.frames.map((f) => f.data ?? "").join("");
    expect(wire).not.toContain(LIVE_RIDE_ID);
    expect(wire).not.toMatch(/"(power|heartRate|cadence)"/);
  });
});
