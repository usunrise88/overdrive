import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { type ProgressResponse, validateProgressResponse } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { bearer, COMPANION } from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  callTool,
  connectWithToken,
  type McpHarness,
  STATIC_TOKEN,
  startMcpHarness,
} from "./helpers/mcp.ts";
import { insertRide, syntheticRide } from "./helpers/synthetic-rides.ts";

/** The MCP side of the Progress screen (spec §6.3, §8.4, ADR 0043): `get_progress`. */

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;
let client: Client;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
  h = await startMcpHarness(db, fake, {
    staticTokens: [{ name: "test", token: STATIC_TOKEN }],
    now: "2026-11-18T08:00:00Z",
  });
  client = await connectWithToken(h.mcpUrl, STATIC_TOKEN);
});

afterAll(async () => {
  await client.close();
  await h.services.metrics.whenIdle();
  await h.services.progress.whenIdle();
  await h.close();
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await h.services.metrics.whenIdle();
  await clearRides(db);
  await db.exec("TRUNCATE ride_metrics, ride_progress_stats, ftp_history, profile, wellness_daily");
});

const call = (args: Record<string, unknown> = {}) => callTool(client, "get_progress", args);

describe("get_progress", () => {
  it("is a read tool described for a model: quote the numbers and the sentences, trust a trend, 'fitter' only for heart rate", async () => {
    const { tools } = await client.listTools();
    const tool = tools.find((t) => t.name === "get_progress");
    expect(tool?.annotations?.readOnlyHint).toBe(true);
    expect(tool?.description).toMatch(/never by a model/);
    expect(tool?.description).toMatch(/do not recalculate/);
    expect(tool?.description).toMatch(/verdictText/);
    expect(tool?.description).toMatch(/heat, sleep and caffeine/);
    expect(tool?.description).toMatch(/'fitter' is said only for heart rate against power/);
    expect(tool?.description).toMatch(/unknown/);
  });

  it("gives the contract's answer: the same body as GET /v1/progress", async () => {
    await db.query(
      "INSERT INTO ftp_history (changed_at, ftp) VALUES ('2026-01-10T10:00:00Z', 250)",
    );
    for (let i = 0; i < 6; i++) {
      await insertRide(
        db,
        syntheticRide({
          id: `ride-${i + 1}`,
          startEpochMillis: Date.parse(`2026-10-${String(1 + i * 5).padStart(2, "0")}T07:00:00Z`),
          ftp: 250,
          seconds: 1500,
          power: (s) => (s <= 120 ? 90 : 180),
          heartRate: () => 150 - i,
        }),
      );
    }
    const { json, isError } = await call({ from: "2026-09-01", to: "2026-11-18" });
    expect(isError).toBe(false);
    const body = json as ProgressResponse;
    expect(validateProgressResponse(body)).toMatchObject({ ok: true });
    expect(body.range).toEqual({ from: "2026-09-01", to: "2026-11-18" });
    expect(body.efficiencyFactor.rides).toHaveLength(6);
    expect(body.efficiencyFactor.trend.verdictText).toMatch(
      /^Efficiency factor is trending up: fitter$/,
    );
    // The same numbers as the REST answer.
    const rest = await h.app.inject({
      method: "GET",
      url: "/v1/progress?from=2026-09-01&to=2026-11-18",
      headers: bearer(COMPANION),
    });
    expect(rest.statusCode).toBe(200);
    expect(rest.json()).toEqual(body);
  });

  it("defaults to the last six months and is valid with nothing recorded", async () => {
    const { json, isError } = await call();
    expect(isError).toBe(false);
    const body = json as ProgressResponse;
    expect(validateProgressResponse(body)).toMatchObject({ ok: true });
    expect(body.range).toEqual({ from: "2026-05-18", to: "2026-11-18" });
    expect(body.headline).toBeNull();
  });

  it("says what is wrong with a range, in words a model can act on", async () => {
    const reversed = await call({ from: "2026-11-03", to: "2026-11-01" });
    expect(reversed.isError).toBe(true);
    expect(reversed.text).toMatch(/from must not be after to/);
    const long = await call({ from: "2019-01-01" });
    expect(long.isError).toBe(true);
    expect(long.text).toMatch(/more than 5 years/);
    const bad = await call({ from: "June" });
    expect(bad.isError).toBe(true);
    expect(bad.text).toMatch(/from must be a calendar date/);
  });
});
