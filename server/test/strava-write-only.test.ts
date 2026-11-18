import { readdirSync, readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeStrava, startFakeStrava } from "./helpers/fake-strava.ts";
import {
  clearStrava,
  MINUTE,
  type StravaHarness,
  startStravaHarness,
  storeRide,
} from "./helpers/strava-harness.ts";

/**
 * Strava is write-only here (spec §5.5, CLAUDE.md): its API terms forbid using its data in AI models,
 * so the service posts a ride and reads only what the upload protocol answers about that upload.
 * These tests hold the module to it, from two sides: what the fake Strava actually received over a
 * run that exercises every path, and what the source could ever ask for.
 */

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
  h = startStravaHarness(db, fake);
});

const ALLOWED = [
  /^POST \/oauth\/token$/,
  /^POST \/api\/v3\/uploads$/,
  /^GET \/api\/v3\/uploads\/\d+$/, // the status of an upload we made, the only GET
];

describe("the service reads nothing from Strava", () => {
  it("sends only the token request, the upload and the status of its own upload, whatever happens", async () => {
    // A success with polling, a repeat, a refused token renewed, a 429, a 5xx, a refusal, a forbidden account.
    fake.pollsBeforeReady = 1;
    const fit = await storeRide(h, "ride-1", 1);
    await h.uploads.runOnce();

    fake.activities.push({
      id: "555",
      uploadId: "1",
      externalId: null,
      filename: "by-hand.fit",
      sha256: sha256Hex(await storeRide(h, "ride-2", 2)),
      name: null,
      description: null,
      sportType: null,
      trainer: null,
      commute: null,
    });
    await h.uploads.runOnce();

    fake.revokeAccessTokens();
    await storeRide(h, "ride-3", 3);
    fake.failNext(1, 429, { retryAfter: 60, method: "POST" });
    await h.uploads.runOnce();
    h.clock.advance(2 * MINUTE);
    await h.uploads.runOnce();

    await storeRide(h, "ride-4", 4);
    fake.failNext(1, 503, { method: "POST", path: "/api/v3/uploads" });
    await h.uploads.runOnce();
    h.clock.advance(2 * MINUTE);
    fake.nextUploadError = "The file is malformed";
    await h.uploads.runOnce();

    await storeRide(h, "ride-5", 5);
    fake.uploadsForbidden = true;
    await h.uploads.runOnce();

    // Strava answered the token request with an athlete; it was never asked for anything but the token.
    expect(fit.length).toBeGreaterThan(0);
    expect(fake.requests.length).toBeGreaterThan(10);
    for (const request of fake.requests) {
      const line = `${request.method} ${request.path}`;
      expect(
        ALLOWED.some((pattern) => pattern.test(line)),
        line,
      ).toBe(true);
      expect(request.query, line).toEqual({});
    }
    // Every GET is the status of an upload the fake itself had issued.
    const gets = fake.requests.filter((r) => r.method === "GET");
    expect(gets.length).toBeGreaterThan(0);
    for (const get of gets) expect(get.path).toMatch(/^\/api\/v3\/uploads\/\d+$/);
    // And it is the only family of paths the fake saw at all.
    expect(new Set(fake.requests.map((r) => r.path.replace(/\d+$/, "N")))).toEqual(
      new Set(["/oauth/token", "/api/v3/uploads", "/api/v3/uploads/N"]),
    );
  });

  it("sends only the token request when the credentials are wrong, and nothing at all when Strava is off", async () => {
    fake.validRefreshToken = "another";
    await storeRide(h, "ride-1", 1);
    await h.uploads.runOnce();
    expect(fake.requests.map((r) => `${r.method} ${r.path}`)).toEqual(["POST /oauth/token"]);

    fake.reset();
    const off = startStravaHarness(db, fake, { configured: false });
    await storeRide(off, "ride-2", 2);
    off.services.start();
    await off.services.stop();
    expect(fake.requests).toEqual([]);
  });
});

describe("the source of the module", () => {
  const dirs = [new URL("../src/strava/", import.meta.url)];
  const files = [
    ...dirs.flatMap((dir) =>
      readdirSync(dir)
        .filter((name) => name.endsWith(".ts"))
        .map((name) => ({ name: `strava/${name}`, url: new URL(name, dir) })),
    ),
    { name: "strava-auth.ts", url: new URL("../src/strava-auth.ts", import.meta.url) },
    { name: "config-strava.ts", url: new URL("../src/config-strava.ts", import.meta.url) },
  ].map((file) => ({ ...file, text: readFileSync(file.url, "utf8") }));

  /** The code without its comments, so that prose can say what the module does not do. */
  const code = (text: string): string =>
    text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");

  it("knows no Strava endpoint but the token, the authorisation page and the upload and its status", () => {
    const found = new Set<string>();
    for (const file of files) {
      for (const match of code(file.text).matchAll(
        /\/(?:api\/v3|oauth)\/[a-z]+(?:\/\$\{[^}]*\})?/g,
      )) {
        found.add(match[0].replace(/\$\{[^}]*\}/g, "{id}"));
      }
    }
    expect([...found].sort()).toEqual([
      "/api/v3/uploads",
      "/api/v3/uploads/{id}",
      "/oauth/authorize",
      "/oauth/token",
    ]);
  });

  it("sends only GET and POST, and only two kinds of GET: the status of an upload and the authorisation page the owner opens", () => {
    const methods = new Set<string>();
    for (const file of files) {
      for (const match of code(file.text).matchAll(/method:\s*["']([A-Z]+)["']/g)) {
        methods.add(match[1] as string);
      }
    }
    expect([...methods].sort()).toEqual(["GET", "POST"]);
    // The one GET in the worker's path is the status check; the authorisation address is only printed.
    const client = files.find((f) => f.name === "strava/client.ts");
    expect([...code(client?.text ?? "").matchAll(/method:\s*"GET"\s*,/g)]).toHaveLength(1);
    expect(code(client?.text ?? "")).toContain(
      "/api/v3/uploads/$" + "{encodeURIComponent(uploadId)}",
    );
  });

  it("never reads an athlete, an activity, a stream or a segment, and asks for the upload permission only", () => {
    for (const file of files) {
      const text = code(file.text);
      expect(text, file.name).not.toMatch(
        /\/athlete|\/activities|\/segments|\/streams|\/routes|\/clubs/,
      );
      expect(text, file.name).not.toMatch(/activity:read|read_all|profile:read|"read"/);
    }
    const authorize = files.find((f) => f.name === "strava/authorize.ts");
    expect(authorize?.text).toContain('STRAVA_SCOPE = "activity:write"');
  });

  it("follows no redirect, which could carry a token somewhere else", () => {
    for (const file of files) {
      const text = code(file.text);
      const fetches = [...text.matchAll(/\b(?:doFetch|fetch)\(/g)].length;
      const refusals = [...text.matchAll(/redirect:\s*"error"/g)].length;
      expect(refusals, file.name).toBeGreaterThanOrEqual(fetches > 0 ? 1 : 0);
    }
  });
});
