import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { startApp } from "./helpers/app.ts";
import { createPgliteDb } from "./helpers/db.ts";

let db: Db;

beforeAll(async () => {
  db = await createPgliteDb();
});

afterAll(async () => {
  await db.close();
});

describe("GET /healthz", () => {
  it("reports ok without a token, and without any configured", async () => {
    for (const devices of [undefined, []]) {
      const { app } = startApp(db, devices === undefined ? {} : { devices });
      const res = await app.inject({ method: "GET", url: "/healthz" });
      expect(res.statusCode).toBe(200);
      expect(res.json()).toEqual({ status: "ok" });
      await app.close();
    }
  });
});
