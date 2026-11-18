import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createIntervalsClient, IntervalsError } from "../src/intervals/client.ts";
import {
  FAKE_API_KEY,
  FAKE_ATHLETE_ID,
  type FakeIntervals,
  startFakeIntervals,
} from "./helpers/fake-intervals.ts";
import { buildFit } from "./helpers/fit.ts";
import { collectingLogger, recordedSleep } from "./helpers/support.ts";

let fake: FakeIntervals;

beforeAll(async () => {
  fake = await startFakeIntervals();
});
afterAll(async () => {
  await fake.close();
});
beforeEach(() => {
  fake.reset();
});

function client(overrides: Partial<Parameters<typeof createIntervalsClient>[0]> = {}) {
  const sleeping = recordedSleep();
  const logging = collectingLogger();
  const instance = createIntervalsClient({
    baseUrl: fake.url,
    athleteId: FAKE_ATHLETE_ID,
    apiKey: FAKE_API_KEY,
    sleep: sleeping.sleep,
    log: logging.log,
    ...overrides,
  });
  return { client: instance, waits: sleeping.waits, logging };
}

async function failure(work: Promise<unknown>): Promise<IntervalsError> {
  try {
    await work;
  } catch (error) {
    expect(error).toBeInstanceOf(IntervalsError);
    return error as IntervalsError;
  }
  throw new Error("expected the call to fail");
}

describe("authentication and addressing", () => {
  it("sends HTTP Basic with the user name API_KEY and the key as password, and nothing else secret", async () => {
    const { client: c } = client();
    await c.listEvents("2026-11-18", "2026-11-19");
    const [request] = fake.requests;
    expect(request?.authorization).toBe(
      `Basic ${Buffer.from(`API_KEY:${FAKE_API_KEY}`).toString("base64")}`,
    );
    // The key is in the header only, not in the URL.
    expect(request?.path).not.toContain(FAKE_API_KEY);
    expect(JSON.stringify(request?.query)).not.toContain(FAKE_API_KEY);
  });

  it("addresses the athlete from the configuration, and `0` means the key's own", async () => {
    await client().client.listEvents("2026-11-18", "2026-11-18");
    expect(fake.requests[0]?.path).toBe(`/api/v1/athlete/${FAKE_ATHLETE_ID}/events`);
    await client({ athleteId: "0" }).client.listEvents("2026-11-18", "2026-11-18");
    expect(fake.requests[1]?.path).toBe("/api/v1/athlete/0/events");
  });

  it("a wrong key is `unauthorized`, is not retried, and its message does not contain the key", async () => {
    const wrong = "wrong-key-that-must-not-leak-9999";
    const { client: c, waits } = client({ apiKey: wrong });
    const error = await failure(c.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("unauthorized");
    expect(error.status).toBe(401);
    expect(error.configuration).toBe(true);
    expect(error.message).not.toContain(wrong);
    expect(error.detail).not.toContain(wrong);
    expect(fake.requests).toHaveLength(1);
    expect(waits).toEqual([]);
  });

  it("a wrong athlete id is `not-found` and says what to check", async () => {
    const error = await failure(
      client({ athleteId: "i1" }).client.listEvents("2026-11-18", "2026-11-18"),
    );
    expect(error.kind).toBe("not-found");
    expect(error.message).toContain("INTERVALS_ATHLETE_ID");
  });
});

describe("uploadActivity", () => {
  const fit = buildFit();

  it("posts the file as multipart with the ride id as external id, and returns the activity id", async () => {
    const { client: c } = client();
    const outcome = await c.uploadActivity({
      fit,
      filename: "ride-1.fit",
      externalId: "ride-1",
      pairedEventId: 5001,
    });
    expect(outcome).toEqual({ status: "uploaded", activityId: "i1000001" });
    const [request] = fake.requestsTo("POST", "/activities");
    expect(request?.contentType).toMatch(/^multipart\/form-data; boundary=/);
    expect(request?.query).toEqual({ external_id: "ride-1", paired_event_id: "5001" });
    expect(fake.activities).toMatchObject([
      { external_id: "ride-1", filename: "ride-1.fit", bytes: fit.length, paired_event_id: 5001 },
    ]);
  });

  it("understands a plain 200 as well as a 201", async () => {
    fake.uploadStatus = 200;
    const outcome = await client().client.uploadActivity({
      fit,
      filename: "a.fit",
      externalId: "a",
    });
    expect(outcome).toEqual({ status: "uploaded", activityId: "i1000001" });
  });

  it.each([
    ["a 200 that created nothing", "empty-200"],
    ["a 409", "conflict-409"],
    ["a 422 that says the activity already exists", "message-422"],
  ] as const)("treats %s as `already exists`, a success", async (_label, style) => {
    const { client: c } = client();
    await c.uploadActivity({ fit, filename: "a.fit", externalId: "a" });
    fake.duplicateStyle = style;
    const again = await c.uploadActivity({ fit, filename: "a.fit", externalId: "a" });
    expect(again).toEqual({ status: "duplicate", activityId: null });
    expect(fake.activities).toHaveLength(1);
  });

  it("does not take any other refusal for a duplicate", async () => {
    fake.failNext(1, 422, {
      body: JSON.stringify({ status: 422, error: "Unsupported file type" }),
    });
    const error = await failure(
      client().client.uploadActivity({ fit, filename: "a.fit", externalId: "a" }),
    );
    expect(error.kind).toBe("rejected");
    expect(error.permanent).toBe(true);
    expect(error.message).toContain("Unsupported file type");
  });

  it("finds an existing activity by external id, or by start time within a minute", async () => {
    fake.activities.push(
      {
        id: "i7",
        external_id: "other",
        start_date: "2026-11-18T08:00:30Z",
        name: null,
        paired_event_id: null,
        sha256: "x",
        filename: "x.fit",
        bytes: 1,
      },
      {
        id: "i8",
        external_id: "ride-9",
        start_date: "2026-11-18T10:00:00Z",
        name: null,
        paired_event_id: null,
        sha256: "y",
        filename: "y.fit",
        bytes: 1,
      },
    );
    const { client: c } = client();
    const search = { oldest: "2026-11-17", newest: "2026-11-19" };
    expect(await c.findActivityId({ ...search, externalId: "ride-9", startEpochMillis: 0 })).toBe(
      "i8",
    );
    expect(
      await c.findActivityId({
        ...search,
        externalId: "ride-new",
        startEpochMillis: Date.parse("2026-11-18T08:00:00Z"),
      }),
    ).toBe("i7");
    expect(
      await c.findActivityId({
        ...search,
        externalId: "ride-new",
        startEpochMillis: Date.parse("2026-11-18T09:00:00Z"),
      }),
    ).toBeNull();
  });
});

describe("events", () => {
  it("lists WORKOUT events between two local dates, resolved, keeping what it needs", async () => {
    fake.addEvent({
      category: "WORKOUT",
      start_date_local: "2026-11-20T00:00:00",
      name: "A",
      type: "Ride",
    });
    fake.addEvent({ category: "NOTE", start_date_local: "2026-11-20T00:00:00", name: "Note" });
    fake.addEvent({ category: "WORKOUT", start_date_local: "2026-11-30T00:00:00", name: "Later" });
    const events = await client().client.listEvents("2026-11-18", "2026-11-25");
    expect(events.map((e) => e.name)).toEqual(["A"]);
    expect(fake.requests[0]?.query).toEqual({
      oldest: "2026-11-18",
      newest: "2026-11-25",
      category: "WORKOUT",
      resolve: "true",
    });
  });

  it("ignores events it cannot read, and says so", async () => {
    fake.failNext(1, 200, {
      body: JSON.stringify([
        { id: 1, start_date_local: "2026-11-20T00:00:00", category: "WORKOUT" },
        { id: "x", start_date_local: "2026-11-20T00:00:00" },
        { id: 3, category: "WORKOUT" },
      ]),
    });
    const { client: c, logging } = client();
    expect((await c.listEvents("2026-11-18", "2026-11-25")).map((e) => e.id)).toEqual([1]);
    expect(logging.lines.some((l) => l.level === "warn" && l.fields.dropped === 2)).toBe(true);
  });

  it("creates, updates and deletes an event", async () => {
    const { client: c } = client();
    const created = await c.createEvent({
      category: "WORKOUT",
      start_date_local: "2026-11-20T00:00:00",
      type: "Ride",
      name: "Sweet spot",
      description: "- 10m 88%",
    });
    expect(created.id).toBeGreaterThan(0);
    expect(fake.events).toHaveLength(1);

    const updated = await c.updateEvent(created.id, { name: "Sweet spot 2" });
    expect(updated.name).toBe("Sweet spot 2");
    expect(fake.events[0]).toMatchObject({ name: "Sweet spot 2", description: "- 10m 88%" });

    await c.deleteEvent(created.id);
    expect(fake.events).toHaveLength(0);
    // Already gone is fine.
    await expect(c.deleteEvent(created.id)).resolves.toBeUndefined();
  });

  it("an update of an event that does not exist is `not-found`", async () => {
    const error = await failure(client().client.updateEvent(999, { name: "x" }));
    expect(error.kind).toBe("not-found");
  });
});

describe("timeouts and retries", () => {
  it("retries a 503 with growing waits and then succeeds", async () => {
    fake.failNext(2, 503);
    const { client: c, waits } = client();
    await expect(c.listEvents("2026-11-18", "2026-11-18")).resolves.toEqual([]);
    expect(fake.requests).toHaveLength(3);
    expect(waits).toEqual([500, 1000]);
  });

  it("gives up after four attempts on a 5xx and says `server`", async () => {
    fake.failAll(502);
    const { client: c, waits } = client();
    const error = await failure(c.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("server");
    expect(error.status).toBe(502);
    expect(fake.requests).toHaveLength(4);
    expect(waits).toEqual([500, 1000, 2000]);
  });

  it("waits as long as a 429 asks (up to the cap), and gives up as `rate-limited`", async () => {
    fake.failNext(1, 429, { retryAfter: 7 });
    const first = client();
    await first.client.listEvents("2026-11-18", "2026-11-18");
    expect(first.waits).toEqual([7000]);

    fake.failAll(429);
    const second = client({ maxBackoffMs: 5000 });
    const error = await failure(second.client.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("rate-limited");
    expect(second.waits).toEqual([500, 1000, 2000]);
  });

  it("an unreachable host is `unreachable`, retried for a repeatable request", async () => {
    fake.setUnreachable(true);
    const { client: c } = client();
    const error = await failure(c.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("unreachable");
    expect(error.status).toBeNull();
    expect(fake.requests).toHaveLength(0);
  });

  it("a silent host times out", async () => {
    fake.setHanging(true);
    const { client: c } = client({ timeoutMs: 80, maxAttempts: 2 });
    const error = await failure(c.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("unreachable");
    expect(error.message).toContain("timed out");
    expect(fake.requests).toHaveLength(2);
  });

  it("creating an event is not repeated after a 5xx or a lost connection (it could make two), only after a 429", async () => {
    const payload = {
      category: "WORKOUT",
      start_date_local: "2026-11-20T00:00:00",
      type: "Ride",
      name: "x",
      description: "- 10m 50%",
    } as const;
    fake.failNext(1, 500);
    const { client: c } = client();
    expect((await failure(c.createEvent(payload))).kind).toBe("server");
    expect(fake.requests).toHaveLength(1);

    fake.failNext(1, 429, { retryAfter: 1 });
    await c.createEvent(payload);
    expect(fake.events).toHaveLength(1);
  });

  it("an upload is repeated after a 5xx (intervals.icu recognises a file it has seen)", async () => {
    fake.failNext(1, 504);
    const { client: c } = client();
    const outcome = await c.uploadActivity({ fit: buildFit(), filename: "a.fit", externalId: "a" });
    expect(outcome.status).toBe("uploaded");
    expect(fake.requestsTo("POST", "/activities")).toHaveLength(2);
  });

  it("an answer that is not JSON is `bad-response`", async () => {
    fake.failNext(1, 200, { body: "<html>maintenance</html>" });
    const error = await failure(client().client.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("bad-response");
  });

  it("an answer that is not a list of events is `bad-response`", async () => {
    fake.failNext(1, 200, { body: JSON.stringify({ events: [] }) });
    const error = await failure(client().client.listEvents("2026-11-18", "2026-11-18"));
    expect(error.kind).toBe("bad-response");
  });
});

describe("secrets", () => {
  it("the key appears in no error and no log line, whatever fails", async () => {
    const { client: c, logging } = client();
    const errors: IntervalsError[] = [];
    for (const status of [401, 404, 422, 429, 500]) {
      fake.failAll(status);
      errors.push(await failure(c.listEvents("2026-11-18", "2026-11-18")));
    }
    fake.failAll(0);
    fake.setUnreachable(true);
    errors.push(await failure(c.listEvents("2026-11-18", "2026-11-18")));
    for (const error of errors) {
      expect(`${error.message} ${error.detail} ${error.stack}`).not.toContain(FAKE_API_KEY);
    }
    expect(logging.text()).not.toContain(FAKE_API_KEY);
  });
});
