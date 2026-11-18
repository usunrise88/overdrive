import type { RideRecord } from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { fitCrc16 } from "../src/fit.ts";
import {
  asRide,
  expectError,
  expectUpload,
  fitFixture,
  putFit,
  putRecord,
  rideFixture,
  startApp,
  validRideFixtures,
} from "./helpers/app.ts";
import { clearRides, openMigratedTestDb } from "./helpers/db.ts";
import { buildFit } from "./helpers/fit.ts";

let db: Db;

beforeAll(async () => {
  db = await openMigratedTestDb();
});

afterAll(async () => {
  await db.close();
});

beforeEach(async () => {
  await clearRides(db);
});

describe("the shared fixtures through the API", () => {
  const rides = validRideFixtures();

  it("has the ride fixtures", () => {
    expect(rides.length).toBeGreaterThanOrEqual(5);
  });

  it("stores every valid ride record, once each, and answers 200 to a repeat", async () => {
    const { app } = startApp(db);
    // The fixtures share one start time, which the API would refuse for a second ride.
    for (const [index, [name, record]] of rides.entries()) {
      const id = `fixture-${name.replace(/\.json$/, "")}`;
      const stored = asRide(record, id, 1_800_000_000_000 + index * 86_400_000);
      expectUpload(await putRecord(app, id, stored), id, "stored");
      expectUpload(await putRecord(app, id, stored), id, "already-stored");
    }
    const count = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM rides");
    expect(count.rows[0]?.n).toBe(String(rides.length));
    await app.close();
  });

  it("refuses the fixtures that are only the same ride again, as the contract says", async () => {
    const { app } = startApp(db);
    // Every fixture has the start time 1794996000000, so the second one under any id collides.
    const [first, second] = rides;
    if (first === undefined || second === undefined) throw new Error("fixtures missing");
    const a = asRide(first[1], "same-start-a", 1_794_996_000_000);
    const b = asRide(second[1], "same-start-b", 1_794_996_000_000);
    expectUpload(await putRecord(app, "same-start-a", a), "same-start-a", "stored");
    expectError(await putRecord(app, "same-start-b", b), "start_time_conflict");
    await app.close();
  });

  it("stores the FIT file generated from the golden ride beside its record", async () => {
    const { app } = startApp(db);
    const golden: RideRecord = rideFixture("fit-golden-3s.json");
    const id = golden.ride.id;
    // The golden ride's own id and start time, as the tablet would send them.
    expectUpload(await putRecord(app, id, golden), id, "stored");
    expectUpload(await putFit(app, id, fitFixture("fit-golden-3s.fit")), id, "stored");
    const row = await db.query<{ fit_bytes: number }>(
      "SELECT length(fit)::int AS fit_bytes FROM rides WHERE id = $1",
      [id],
    );
    expect(row.rows[0]?.fit_bytes).toBe(376);
    await app.close();
  });

  it("takes both committed FIT fixtures, and refuses one with a damaged CRC", async () => {
    const { app } = startApp(db);
    const record = rideFixture("minimal-free-ride.json");
    await putRecord(app, record.ride.id, record);
    const damaged = Buffer.from(fitFixture("minimal.fit"));
    damaged.writeUInt16LE(fitCrc16(damaged, 0, damaged.length - 2) ^ 1, damaged.length - 2);
    expectError(await putFit(app, record.ride.id, damaged), "invalid_fit");
    expectUpload(
      await putFit(app, record.ride.id, fitFixture("minimal.fit")),
      record.ride.id,
      "stored",
    );
    expect(
      fitFixture("minimal.fit").equals(
        buildFit({ headerSize: 12, data: Uint8Array.from([0, 1, 2, 3]) }),
      ),
    ).toBe(true);
    await app.close();
  });
});
