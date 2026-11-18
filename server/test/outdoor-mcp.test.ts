import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { clearRides, clearWellness, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { expectImport, hourRide, postFit } from "./helpers/outdoor.ts";
import { constant, insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/** The tools that list and read activities cover the outdoor rides imported from FIT files (spec §11.8, ADR 0026). */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
    now: "2027-03-14T12:00:00Z",
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.services.metrics.whenIdle();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await h.services.metrics.whenIdle();
  await clearWellness(db);
  await clearRides(db);
  await db.exec("TRUNCATE ride_metrics, profile, ride_workouts");
  await h.services.profile.update({ maxHeartRate: 190, thresholdHeartRate: 170 });
});

const call = (name: string, args: Record<string, unknown> = {}) => callTool(client, name, args);

interface Listing {
  total: number;
  activities: {
    id: string;
    type: string;
    source: string;
    name: string;
    averageHeartRate?: number | null;
    load?: number | null;
    countsTowardLoad?: boolean;
  }[];
}

describe("list_activities and get_activity with an outdoor ride", () => {
  it("lists it with source fit and type cycling-outdoor, next to the tablet's rides, and filters by source", async () => {
    const answer = expectImport(await postFit(h.app, hourRide()), "imported");
    await insertRide(
      db,
      syntheticRide({
        id: "ride-1",
        startEpochMillis: Date.parse("2027-03-13T17:00:00Z"),
        seconds: 1200,
        power: constant(200),
      }),
    );

    const all = await call("list_activities", { from: "2027-03-12", to: "2027-03-14" });
    expect(all.isError).toBe(false);
    const listing = all.json as Listing;
    expect(listing.total).toBe(2);
    expect(listing.activities.map((a) => [a.id, a.source, a.type])).toEqual([
      [answer.id, "fit", "cycling-outdoor"],
      ["ride-1", "tablet", "cycling"],
    ]);
    expect(listing.activities[0]).toMatchObject({
      name: "Outdoor ride",
      averageHeartRate: 150,
      load: 60.9,
      countsTowardLoad: true,
    });

    const fit = (
      await call("list_activities", { from: "2027-03-12", to: "2027-03-14", source: "fit" })
    ).json as Listing;
    expect(fit.activities.map((a) => a.id)).toEqual([answer.id]);
    const tablet = (
      await call("list_activities", { from: "2027-03-12", to: "2027-03-14", source: "tablet" })
    ).json as Listing;
    expect(tablet.activities.map((a) => a.id)).toEqual(["ride-1"]);
    const watch = (
      await call("list_activities", { from: "2027-03-12", to: "2027-03-14", source: "watch" })
    ).json as Listing;
    expect(watch.activities).toEqual([]);
  });

  it("gets it by its id, with distance, climb, load and how dense the heart rate is, and the series on request", async () => {
    const answer = expectImport(await postFit(h.app, hourRide({ totalAscent: 250 })), "imported");
    const one = await call("get_activity", { id: answer.id });
    expect(one.isError).toBe(false);
    expect(one.json).toMatchObject({
      id: answer.id,
      source: "fit",
      type: "cycling-outdoor",
      distanceMeters: 27_000,
      elevationGainMeters: 250,
      load: 60.9,
      countsTowardLoad: true,
      heartRate: { samples: 3600, coverage: 1 },
    });
    expect((one.json as { series?: unknown }).series).toBeUndefined();
    const withSeries = await call("get_activity", { id: answer.id, series: true });
    expect(((withSeries.json as { series: unknown[] }).series ?? []).length).toBeGreaterThan(0);
    const missing = await call("get_activity", { id: "fit-0000000000000000" });
    expect(missing.isError).toBe(true);
    expect(missing.text).toMatch(/there is no activity fit-0000000000000000/);
  });

  it("says in its description what an outdoor ride counts for", async () => {
    const { tools } = await client.listTools();
    const text = tools.find((t) => t.name === "list_activities")?.description ?? "";
    expect(text).toMatch(/cycling-outdoor/);
    expect(text).toMatch(/never toward the power curve/);
  });
});
