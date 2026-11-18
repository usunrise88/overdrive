import { readdirSync, readFileSync } from "node:fs";
import {
  defaultProfile,
  type Profile,
  validateProfile,
  withHeartRateKeys,
} from "@overdrive/contracts";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import type { ProfileChangedEvent } from "../src/events.ts";
import { bearer, expectError, startApp, type TestApp } from "./helpers/app.ts";
import { openMigratedTestDb } from "./helpers/db.ts";

let db: Db;
let t: TestApp;
let changes: ProfileChangedEvent[];

beforeAll(async () => {
  db = await openMigratedTestDb();
  t = startApp(db);
  t.app.services.bus.on("profile-changed", (event) => {
    changes.push(event);
  });
});

afterAll(async () => {
  await t.app.close();
  await db.close();
});

beforeEach(async () => {
  await db.exec("TRUNCATE profile");
  changes = [];
});

const get = () => t.app.inject({ method: "GET", url: "/v1/profile", headers: bearer() });
const patch = (body: unknown, contentType = "application/json") =>
  t.app.inject({
    method: "PATCH",
    url: "/v1/profile",
    headers: { "content-type": contentType, ...bearer() },
    payload: typeof body === "string" ? body : JSON.stringify(body),
  });

describe("GET /v1/profile", () => {
  it("needs a device token", async () => {
    const res = await t.app.inject({ method: "GET", url: "/v1/profile" });
    expectError(res, "unauthorized");
    const patched = await t.app.inject({
      method: "PATCH",
      url: "/v1/profile",
      headers: { "content-type": "application/json" },
      payload: "{}",
    });
    expectError(patched, "unauthorized");
  });

  it("answers the defaults while nothing has been set, in the contract's shape", async () => {
    const res = await get();
    expect(res.statusCode).toBe(200);
    expect(res.headers["cache-control"]).toBe("no-store");
    expect(validateProfile(res.json())).toMatchObject({ ok: true });
    expect(res.json()).toEqual(defaultProfile());
    expect(res.json().ftp).toBeNull();
    expect(res.json().updatedAt).toBeNull();
  });
});

describe("PATCH /v1/profile", () => {
  it("sets the FTP, stamps the change and serves it afterwards", async () => {
    const res = await patch({ ftp: 255 });
    expect(res.statusCode, res.body).toBe(200);
    const profile = res.json() as Profile;
    expect(validateProfile(profile)).toMatchObject({ ok: true });
    expect(profile.ftp).toBe(255);
    expect(profile.updatedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect((await get()).json()).toEqual(profile);
  });

  it("changes only what it names, and the rest survives across patches", async () => {
    await patch({ ftp: 250, goals: ["Base miles"] });
    await patch({ weeklyAvailability: { tue: 60, thu: 90 } });
    await patch({ physics: { systemMassKg: 77.5 } });
    const profile = (await get()).json() as Profile;
    expect(profile.ftp).toBe(250);
    expect(profile.goals).toEqual(["Base miles"]);
    expect(profile.weeklyAvailability).toMatchObject({ mon: null, tue: 60, wed: null, thu: 90 });
    expect(profile.physics).toEqual({ ...defaultProfile().physics, systemMassKg: 77.5 });
  });

  it("overrides the zones and puts Coggan's back with null", async () => {
    await patch({ zones: { upperPercentOfFtp: [50, 70, 85, 100, 115, 140] } });
    expect(((await get()).json() as Profile).zones.upperPercentOfFtp).toEqual([
      50, 70, 85, 100, 115, 140,
    ]);
    await patch({ zones: null });
    expect(((await get()).json() as Profile).zones).toEqual(defaultProfile().zones);
  });

  it("clears the FTP and forgets a weekday with null", async () => {
    await patch({ ftp: 250, weeklyAvailability: { sat: 120 } });
    await patch({ ftp: null, weeklyAvailability: { sat: null } });
    const profile = (await get()).json() as Profile;
    expect(profile.ftp).toBeNull();
    expect(profile.weeklyAvailability.sat).toBeNull();
  });

  it("announces what changed, once, and says nothing for a patch that changes nothing", async () => {
    await patch({ ftp: 250, goals: ["a"] });
    expect(changes).toEqual([{ fields: ["ftp", "goals"] }]);
    const before = (await get()).json() as Profile;
    expect((await patch({ ftp: 250 })).statusCode).toBe(200);
    expect((await patch({})).statusCode).toBe(200);
    expect(changes).toHaveLength(1);
    // Nothing was stored, so the stamp is the first one's.
    expect(((await get()).json() as Profile).updatedAt).toBe(before.updatedAt);
  });

  it("accepts application/merge-patch+json", async () => {
    const res = await patch({ ftp: 240 }, "application/merge-patch+json");
    expect(res.statusCode, res.body).toBe(200);
    expect((res.json() as Profile).ftp).toBe(240);
    expectError(await patch("{nope", "application/merge-patch+json"), "invalid_json");
  });

  it("applies two patches at once without losing either", async () => {
    await patch({ ftp: 250 });
    const [a, b] = await Promise.all([
      patch({ goals: ["one"] }),
      patch({ weeklyAvailability: { mon: 30 } }),
    ]);
    expect([a.statusCode, b.statusCode]).toEqual([200, 200]);
    const profile = (await get()).json() as Profile;
    expect(profile.goals).toEqual(["one"]);
    expect(profile.weeklyAvailability.mon).toBe(30);
    expect(profile.ftp).toBe(250);
  });

  it("refuses a body that is not JSON", async () => {
    expectError(await patch("{nope"), "invalid_json");
    expectError(await patch(""), "invalid_json");
    const res = await t.app.inject({
      method: "PATCH",
      url: "/v1/profile",
      headers: { "content-type": "text/plain", ...bearer() },
      payload: "ftp=250",
    });
    expectError(res, "unsupported_media_type");
  });

  it.each([
    ["an FTP below 30", { ftp: 10 }, /ftp/],
    ["an FTP that is text", { ftp: "250" }, /ftp/],
    ["a key that is not part of the profile", { weight: 70 }, /additional properties/],
    ["updatedAt", { updatedAt: "2026-11-18T10:00:00Z" }, /additional properties/],
    [
      "zones that do not increase",
      { zones: { upperPercentOfFtp: [55, 75, 70, 105, 120, 150] } },
      /not above/,
    ],
    ["a blank goal", { goals: ["fine", "  "] }, /blank/],
    ["more than ten goals", { goals: Array.from({ length: 11 }, (_, i) => `g${i}`) }, /goals/],
    ["a minute count above a day", { weeklyAvailability: { mon: 1500 } }, /mon/],
    ["a physics constant of null", { physics: { cdA: null } }, /cdA/],
    ["an efficiency above one", { physics: { drivetrainEfficiency: 1.2 } }, /drivetrainEfficiency/],
    ["a body that is a list", [], /object/],
    ["a body that is null", "null", /object/],
  ])("refuses %s with 422 invalid_profile and changes nothing", async (_what, body, reason) => {
    await patch({ ftp: 250 });
    const before = (await get()).json();
    changes = [];
    const error = expectError(await patch(body), "invalid_profile");
    expect(error.message).toMatch(reason);
    expect((await get()).json()).toEqual(before);
    expect(changes).toEqual([]);
  });

  it("does not serve a stored profile that is not valid", async () => {
    await db.query("INSERT INTO profile (id, data, updated_at) VALUES (true, $1::jsonb, now())", [
      JSON.stringify({ ftp: 5 }),
    ]);
    expectError(await get(), "internal_error");
  });
});

// The same fixtures the TypeScript contract tests and Kotlin's ProfileContractTest run, through HTTP.
describe("the contract's patch fixtures", () => {
  const dir = new URL("../../contracts/fixtures/profile/patch/", import.meta.url);
  const load = (kind: "valid" | "invalid") =>
    readdirSync(new URL(`${kind}/`, dir))
      .sort()
      .map(
        (file) =>
          [
            file,
            JSON.parse(readFileSync(new URL(`${kind}/${file}`, dir), "utf8")) as {
              before: Profile;
              patch: unknown;
              after: Profile;
            },
          ] as const,
      );

  /**
   * What the service answers for a fixture's profile: the four heart-rate keys are always written (the old fixtures lack them), and
   * the estimates are the stored rides' (none here), never what a fixture says.
   */
  const shown = (profile: Profile): Profile => ({
    ...withHeartRateKeys(profile),
    estimatedMaxHeartRate: null,
    estimatedThresholdHeartRate: null,
  });

  const seed = async (profile: Profile) => {
    const { updatedAt, ...data } = profile;
    await db.query(
      "INSERT INTO profile (id, data, updated_at) VALUES (true, $1::jsonb, $2::timestamptz)",
      [JSON.stringify(data), updatedAt ?? "2026-01-01T00:00:00Z"],
    );
  };

  it.each(load("valid"))("valid/%s makes the expected profile", async (_name, fixture) => {
    await seed(fixture.before);
    const res = await patch(fixture.patch);
    expect(res.statusCode, res.body).toBe(200);
    const { updatedAt, ...made } = res.json() as Profile;
    const { updatedAt: expectedStamp, ...expected } = shown(fixture.after);
    expect(made).toEqual(expected);
    // A patch that changed nothing keeps the old stamp; one that changed something has a new one.
    if (
      JSON.stringify(made) ===
      JSON.stringify((({ updatedAt: _u, ...rest }) => rest)(shown(fixture.before)))
    ) {
      expect(updatedAt).toBe(fixture.before.updatedAt ?? updatedAt);
    } else {
      expect(updatedAt).not.toBe(expectedStamp);
    }
  });

  it.each(load("invalid"))("invalid/%s is refused with 422", async (_name, fixture) => {
    await seed(fixture.before);
    const error = expectError(await patch(JSON.stringify(fixture.patch)), "invalid_profile");
    expect(error.message.length).toBeGreaterThan(0);
  });
});
