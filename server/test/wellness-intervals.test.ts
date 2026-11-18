import { addDaysToIsoDate, type WellnessResponse } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import { describeConfigError, loadConfig } from "../src/config.ts";
import type { Db } from "../src/db.ts";
import { createServices, type Services, type ServicesConfig } from "../src/services.ts";
import { PULL_DAYS } from "../src/wellness/puller.ts";
import {
  DEFAULT_SOURCE_ORDER,
  observationFromIntervals,
  parseSourceOrder,
} from "../src/wellness/sources.ts";
import { COMPANION, TABLET } from "./helpers/app.ts";
import { clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import {
  FAKE_API_KEY,
  FAKE_ATHLETE_ID,
  type FakeIntervals,
  startFakeIntervals,
} from "./helpers/fake-intervals.ts";
import { collectingLogger, manualTimers, recordedSleep, testClock } from "./helpers/support.ts";
import { batch, get, post, resting, sleep } from "./helpers/wellness-data.ts";

let db: Db;
let fake: FakeIntervals;
let app: FastifyInstance | null = null;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});

afterAll(async () => {
  await app?.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await app?.close();
  app = null;
  await clearWellness(db);
  fake.reset();
});

const NOW = "2026-11-18T08:00:00Z";

interface Booted {
  app: FastifyInstance;
  services: Services;
  logging: ReturnType<typeof collectingLogger>;
  timers: ReturnType<typeof manualTimers>;
  clock: ReturnType<typeof testClock>;
}

/** The service against the fake intervals.icu with the wellness settings given. */
function boot(config: Partial<ServicesConfig> = {}, keyed = true): Booted {
  const logging = collectingLogger();
  const clock = testClock(NOW);
  const timers = manualTimers();
  const services = createServices({
    config: {
      ...(keyed ? { INTERVALS_API_KEY: FAKE_API_KEY } : {}),
      INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
      INTERVALS_BASE_URL: fake.url,
      TIMEZONE: "UTC",
      ...config,
    },
    db,
    log: logging.log,
    now: clock.now,
    sleep: recordedSleep().sleep,
    timers,
  });
  app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET, COMPANION] },
    db,
    services,
  });
  return { app, services, logging, timers, clock };
}

async function wellnessOf(
  a: FastifyInstance,
  date: string,
): Promise<WellnessResponse["days"][number]> {
  const res = await get(a, `/v1/wellness?from=${date}&to=${date}`);
  expect(res.statusCode, res.body).toBe(200);
  const [only] = (res.json() as WellnessResponse).days;
  if (only === undefined) throw new Error("no day");
  return only;
}

const wellnessRequests = () => fake.requestsTo("GET", "/wellness");

describe("pulling the wellness from intervals.icu", () => {
  it("reads the last 90 days with the API key, and keeps what it finds as daily figures with their source", async () => {
    fake.wellness["2026-11-17"] = {
      restingHR: 52,
      hrv: 61.5,
      hrvSDNN: 70.2,
      sleepSecs: 27000,
      spO2: 96.4,
      respiration: 14.1,
      steps: 8123,
      avgSleepingHR: 47.5,
      comments: "ignored",
    };
    const { app, services } = boot();
    const pulled = await services.wellness.refresh();
    expect(pulled).toEqual({ ok: true, days: 1 });

    const requests = wellnessRequests();
    expect(requests).toHaveLength(1);
    expect(requests[0]?.query).toEqual({
      oldest: addDaysToIsoDate("2026-11-18", -(PULL_DAYS - 1)),
      newest: "2026-11-18",
    });
    expect(requests[0]?.authorization).toBe(
      `Basic ${Buffer.from(`API_KEY:${FAKE_API_KEY}`).toString("base64")}`,
    );

    const day = await wellnessOf(app, "2026-11-17");
    expect(day.restingHr).toMatchObject({ value: 52, source: "intervals" });
    expect(day.sleepSeconds).toMatchObject({ value: 27000, source: "intervals" });
    expect(day.respiratoryRate.value).toBe(14.1);
    expect(day.spo2.value).toBe(96.4);
    expect(day.sleepingHr.value).toBe(47.5);
    expect(day.hrvRmssd).toMatchObject({ value: 61.5, source: "intervals" });
    expect(day.hrvSdnn).toBe(70.2);
    expect(day.steps).toBe(8123);
    // intervals.icu has no bedtime: that comes from the watch only.
    expect(day.bedtimeMinutes.value).toBeNull();
  });

  it("ignores a field that is absent, null or not plausible, and a day with nothing usable", async () => {
    fake.wellness["2026-11-17"] = {
      restingHR: 12,
      spO2: 40,
      sleepSecs: 100,
      respiration: null,
      hrv: 0,
      steps: 0,
    };
    fake.wellness["2026-11-16"] = { restingHR: 51, sleepSecs: null };
    const { services, app } = boot();
    expect(await services.wellness.refresh()).toEqual({ ok: true, days: 1 });
    const day = await wellnessOf(app, "2026-11-17");
    expect([
      day.restingHr.value,
      day.spo2.value,
      day.sleepSeconds.value,
      day.hrvRmssd.value,
      day.steps,
    ]).toEqual([null, null, null, null, null]);
    expect((await wellnessOf(app, "2026-11-16")).restingHr.value).toBe(51);
  });

  it("replaces what it knew: a day that is no longer there goes, a changed one changes", async () => {
    fake.wellness["2026-11-16"] = { restingHR: 50 };
    fake.wellness["2026-11-17"] = { restingHR: 52 };
    const { services, app } = boot();
    await services.wellness.refresh();
    delete fake.wellness["2026-11-16"];
    fake.wellness["2026-11-17"] = { restingHR: 55 };
    await services.wellness.refresh();
    expect((await wellnessOf(app, "2026-11-16")).restingHr.value).toBeNull();
    expect((await wellnessOf(app, "2026-11-17")).restingHr.value).toBe(55);
  });

  it("is on demand through ?refresh=true, and off without a key", async () => {
    fake.wellness["2026-11-17"] = { restingHR: 52 };
    const { app } = boot();
    expect((await wellnessOf(app, "2026-11-17")).restingHr.value).toBeNull();
    const res = await get(app, "/v1/wellness?from=2026-11-17&to=2026-11-17&refresh=true");
    expect((res.json() as WellnessResponse).days[0]?.restingHr.value).toBe(52);
    expect((await get(app, "/v1/recovery?refresh=true")).statusCode).toBe(200);
    expect(wellnessRequests()).toHaveLength(2);

    await app.close();
    fake.requests.length = 0;
    const off = boot({}, false);
    expect(off.services.wellness.puller).toBeNull();
    expect(off.services.wellness.refresh()).toBeNull();
    expect(
      (await get(off.app, "/v1/wellness?from=2026-11-17&to=2026-11-17&refresh=true")).statusCode,
    ).toBe(200);
    expect(fake.requests).toHaveLength(0);
  });

  it("pulls at start and again every WELLNESS_PULL_HOURS, until the app closes", async () => {
    fake.wellness["2026-11-17"] = { restingHR: 52 };
    const { services, timers, app } = boot({ WELLNESS_PULL_HOURS: 4 });
    services.start();
    await services.wellness.refresh(); // joins the first pull, which start() began
    expect(wellnessRequests()).toHaveLength(1);
    await expect
      .poll(() => timers.pending.filter((t) => t.kind === "after" && t.ms === 4 * 3_600_000))
      .toHaveLength(1);
    timers.tick();
    await services.wellness.refresh();
    expect(wellnessRequests().length).toBeGreaterThanOrEqual(2);
    await app.close();
    expect(timers.pending).toEqual([]);
    const before = wellnessRequests().length;
    timers.tick();
    expect(wellnessRequests()).toHaveLength(before);
  });

  it("is stale while the last pull failed, keeps the last figures, and says so only in words, never in values", async () => {
    fake.wellness["2026-11-17"] = { restingHR: 57.3141, sleepSecs: 26543 };
    const { services, app, logging } = boot();
    // Nothing pulled yet: stale.
    expect(
      ((await get(app, "/v1/wellness?from=2026-11-17&to=2026-11-17")).json() as WellnessResponse)
        .stale,
    ).toBe(true);
    await services.wellness.refresh();
    expect(
      ((await get(app, "/v1/wellness?from=2026-11-17&to=2026-11-17")).json() as WellnessResponse)
        .stale,
    ).toBe(false);
    expect(((await get(app, "/v1/recovery")).json() as { stale: boolean }).stale).toBe(false);

    fake.failAll(401);
    expect(await services.wellness.refresh()).toEqual({
      ok: false,
      error: "intervals.icu did not accept the API key (HTTP 401)",
    });
    const stale = (
      await get(app, "/v1/wellness?from=2026-11-17&to=2026-11-17")
    ).json() as WellnessResponse;
    expect(stale.stale).toBe(true);
    expect(stale.days[0]?.restingHr.value).toBe(57.3);
    expect(((await get(app, "/v1/recovery")).json() as { stale: boolean }).stale).toBe(true);

    fake.failAll(0);
    await services.wellness.refresh();
    expect(((await get(app, "/v1/recovery")).json() as { stale: boolean }).stale).toBe(false);
    // The log has counts and reasons, never a figure.
    expect(logging.text()).not.toContain("26543");
    expect(logging.text()).not.toContain("57.31");
  });

  it("is not stale when nothing is configured to pull from", async () => {
    const { app } = boot({}, false);
    expect(((await get(app, "/v1/recovery")).json() as { stale: boolean }).stale).toBe(false);
  });

  it("maps one intervals.icu day to the figures it can use", () => {
    expect(
      observationFromIntervals({
        id: "2026-11-17",
        restingHR: 51,
        hrv: 60,
        hrvSDNN: 75,
        sleepSecs: 25000,
        spO2: 95,
        respiration: 13.5,
        steps: 5000,
        avgSleepingHR: 46,
      }),
    ).toEqual({
      date: "2026-11-17",
      values: {
        restingHr: 51,
        hrvRmssd: 60,
        hrvSdnn: 75,
        sleepSeconds: 25000,
        spo2: 95,
        respiratoryRate: 13.5,
        steps: 5000,
        sleepingHr: 46,
      },
    });
    expect(observationFromIntervals({ id: "2026-11-17" }).values).toEqual({});
  });
});

describe("which source a figure comes from", () => {
  const seed = async (a: FastifyInstance) => {
    // The watch has the resting heart rate and a night; intervals.icu has other values for the same day and a sleep length.
    await post(
      a,
      batch(
        resting("rhr", "2026-11-17T06:30:00Z", 50),
        sleep("night", "2026-11-16T22:45:00Z", "2026-11-17T05:45:00Z"),
      ),
    );
    fake.wellness["2026-11-17"] = { restingHR: 58, sleepSecs: 30000, spO2: 97 };
  };

  it("takes the watch first by default: each figure from the first source that has it", async () => {
    const { app, services } = boot();
    await seed(app);
    await services.wellness.refresh();
    const day = await wellnessOf(app, "2026-11-17");
    expect(day.restingHr).toMatchObject({ value: 50, source: "healthconnect" });
    expect(day.sleepSeconds).toMatchObject({ value: 7 * 3600, source: "healthconnect" });
    // Only intervals.icu has an oxygen saturation.
    expect(day.spo2).toMatchObject({ value: 97, source: "intervals" });
  });

  it("takes intervals.icu first when told to, and the watch for what it lacks", async () => {
    const { app, services } = boot({ WELLNESS_SOURCE_ORDER: "intervals,healthconnect" });
    await seed(app);
    await services.wellness.refresh();
    const day = await wellnessOf(app, "2026-11-17");
    expect(day.restingHr).toMatchObject({ value: 58, source: "intervals" });
    expect(day.sleepSeconds).toMatchObject({ value: 30000, source: "intervals" });
    // Bedtime only comes from the watch.
    expect(day.bedtimeMinutes).toMatchObject({ value: -75, source: "healthconnect" });
  });

  it("can prefer a source for one metric only, and leave a source out of a metric", async () => {
    const { app, services } = boot({
      WELLNESS_SOURCE_ORDER:
        "healthconnect,intervals;restingHr=intervals;sleepSeconds=healthconnect",
    });
    await seed(app);
    await services.wellness.refresh();
    const day = await wellnessOf(app, "2026-11-17");
    expect(day.restingHr).toMatchObject({ value: 58, source: "intervals" });
    // intervals.icu is not used for sleep at all.
    expect(day.sleepSeconds).toMatchObject({ value: 7 * 3600, source: "healthconnect" });
    // Whatever the watch has stays in the table: the other source's value comes back when the order changes.
    const rows = await db.query<{ source: string; value: number }>(
      "SELECT source, value FROM wellness_daily WHERE metric = 'restingHr' ORDER BY source",
    );
    expect(rows.rows).toEqual([
      { source: "healthconnect", value: 50 },
      { source: "intervals", value: 58 },
    ]);
  });
});

describe("WELLNESS_SOURCE_ORDER and the other wellness settings", () => {
  it("parses a default order and per-metric orders", () => {
    const order = parseSourceOrder(
      "healthconnect,intervals;restingHr=intervals,healthconnect;steps=intervals",
    );
    expect(order("sleepSeconds")).toEqual(["healthconnect", "intervals"]);
    expect(order("restingHr")).toEqual(["intervals", "healthconnect"]);
    expect(order("steps")).toEqual(["intervals"]);
    expect(parseSourceOrder(DEFAULT_SOURCE_ORDER)("spo2")).toEqual(["healthconnect", "intervals"]);
    expect(parseSourceOrder(" intervals ; ")("spo2")).toEqual(["intervals"]);
  });

  it.each([
    ["", /at least one source/],
    ["healthconnect,garmin", /"garmin" is not a source/],
    ["healthconnect,healthconnect", /listed twice/],
    ["healthconnect;weight=intervals", /"weight" is not a metric/],
    ["healthconnect;restingHr", /metric=source/],
    ["healthconnect;restingHr=", /needs at least one source/],
  ])("refuses %j", (text, message) => {
    expect(() => parseSourceOrder(text)).toThrow(message);
  });

  const env = (extra: Record<string, string> = {}) => ({ DATABASE_URL: "postgres://x", ...extra });

  it("has defaults: healthconnect before intervals, a pull every 6 hours, 42 days, 14 nights", () => {
    const config = loadConfig(env());
    expect(config).toMatchObject({
      WELLNESS_SOURCE_ORDER: "healthconnect,intervals",
      WELLNESS_PULL_HOURS: 6,
      WELLNESS_BASELINE_DAYS: 42,
      WELLNESS_NIGHTS_NEEDED: 14,
    });
    // Empty values mean the defaults (compose passes ${VAR:-}).
    expect(
      loadConfig(
        env({
          WELLNESS_SOURCE_ORDER: "",
          WELLNESS_PULL_HOURS: "",
          WELLNESS_BASELINE_DAYS: "",
          WELLNESS_NIGHTS_NEEDED: "",
        }),
      ).WELLNESS_BASELINE_DAYS,
    ).toBe(42);
  });

  it("reads the owner's values", () => {
    const config = loadConfig(
      env({
        WELLNESS_SOURCE_ORDER: "intervals,healthconnect",
        WELLNESS_PULL_HOURS: "12",
        WELLNESS_BASELINE_DAYS: "28",
        WELLNESS_NIGHTS_NEEDED: "21",
      }),
    );
    expect(config).toMatchObject({
      WELLNESS_SOURCE_ORDER: "intervals,healthconnect",
      WELLNESS_PULL_HOURS: 12,
      WELLNESS_BASELINE_DAYS: 28,
      WELLNESS_NIGHTS_NEEDED: 21,
    });
  });

  it.each([
    [{ WELLNESS_SOURCE_ORDER: "garmin" }, "WELLNESS_SOURCE_ORDER"],
    [{ WELLNESS_PULL_HOURS: "0" }, "WELLNESS_PULL_HOURS must be at least 1"],
    [{ WELLNESS_PULL_HOURS: "200" }, "WELLNESS_PULL_HOURS must be at most 168"],
    [{ WELLNESS_BASELINE_DAYS: "27" }, "WELLNESS_BASELINE_DAYS must be at least 28"],
    [{ WELLNESS_BASELINE_DAYS: "61" }, "WELLNESS_BASELINE_DAYS must be at most 60"],
    [{ WELLNESS_NIGHTS_NEEDED: "6" }, "WELLNESS_NIGHTS_NEEDED must be at least 7"],
    [{ WELLNESS_NIGHTS_NEEDED: "abc" }, "WELLNESS_NIGHTS_NEEDED must be a whole number"],
  ])("refuses %j and names the variable", (extra, message) => {
    let described = "";
    try {
      loadConfig(env(extra));
    } catch (error) {
      described = describeConfigError(error);
    }
    expect(described).toContain(message);
  });

  it("uses the baseline window and the nights the owner asks for", async () => {
    const { app } = boot({ WELLNESS_BASELINE_DAYS: 28, WELLNESS_NIGHTS_NEEDED: 7 }, false);
    const res = await get(app, "/v1/wellness?from=2026-11-18&to=2026-11-18");
    expect(res.json()).toMatchObject({ baselineDays: 28, nightsNeeded: 7 });
    expect((await get(app, "/v1/recovery")).json()).toMatchObject({ nightsNeeded: 7 });
  });
});
