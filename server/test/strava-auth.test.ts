import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { sha256Hex } from "../src/hash.ts";
import {
  createRefreshTokenStore,
  createTokenManager,
  type RefreshTokenStore,
  type TokenManager,
} from "../src/strava/auth.ts";
import {
  AuthorizeError,
  authorizeUrl,
  exchangeCode,
  parseRedirect,
  REDIRECT_URI,
} from "../src/strava/authorize.ts";
import { StravaError } from "../src/strava/errors.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import {
  FAKE_AUTH_CODE,
  FAKE_CLIENT_ID,
  FAKE_CLIENT_SECRET,
  FAKE_REFRESH_TOKEN,
  type FakeStrava,
  startFakeStrava,
} from "./helpers/fake-strava.ts";
import { collectingLogger, testClock } from "./helpers/support.ts";

let db: Db;
let fake: FakeStrava;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeStrava();
});
afterAll(async () => {
  await fake.close();
  await db.close();
});
beforeEach(async () => {
  await db.exec("TRUNCATE strava_auth");
  fake.reset();
});

const HOUR = 3_600_000;

function manager(options: {
  seed?: string;
  store?: RefreshTokenStore;
  clock?: ReturnType<typeof testClock>;
  logging?: ReturnType<typeof collectingLogger>;
}): TokenManager {
  const clock = options.clock ?? testClock("2026-11-18T08:00:00Z");
  fake.now = () => clock.now().getTime();
  return createTokenManager({
    store: options.store ?? createRefreshTokenStore(db),
    clientId: FAKE_CLIENT_ID,
    clientSecret: FAKE_CLIENT_SECRET,
    seedRefreshToken: options.seed ?? FAKE_REFRESH_TOKEN,
    baseUrl: fake.url,
    now: clock.now,
    log: (options.logging ?? collectingLogger()).log,
  });
}

const stored = async () =>
  (await createRefreshTokenStore(db).load()) ?? { refreshToken: "", seedSha256: "" };

describe("the access token", () => {
  it("is fetched with the seed refresh token, sent in the body and never in the address, and the rotated token is kept", async () => {
    const tokens = manager({});
    const access = await tokens.accessToken();
    expect(access).toBe("fake-access-token-1");

    expect(fake.requests).toHaveLength(1);
    const [request] = fake.requests;
    expect(request).toMatchObject({ method: "POST", path: "/oauth/token", query: {} });
    expect(request?.form).toEqual({
      client_id: FAKE_CLIENT_ID,
      client_secret: FAKE_CLIENT_SECRET,
      grant_type: "refresh_token",
      refresh_token: FAKE_REFRESH_TOKEN,
    });
    expect(request?.authorization).toBeUndefined();

    // Strava rotated it: the new one is in the table, with a note of which environment value seeded it.
    expect(fake.validRefreshToken).not.toBe(FAKE_REFRESH_TOKEN);
    expect(await stored()).toEqual({
      refreshToken: fake.validRefreshToken,
      seedSha256: sha256Hex(FAKE_REFRESH_TOKEN),
    });
  });

  it("is kept in memory until shortly before it expires, then renewed with the newest refresh token", async () => {
    const clock = testClock("2026-11-18T08:00:00Z");
    const tokens = manager({ clock });
    expect(await tokens.accessToken()).toBe("fake-access-token-1");
    clock.advance(5 * HOUR);
    expect(await tokens.accessToken()).toBe("fake-access-token-1");
    expect(fake.requests).toHaveLength(1);

    // Six hours from the first, less the five-minute margin.
    clock.advance(55 * 60_000 + 1);
    const rotatedOnce = fake.validRefreshToken;
    expect(await tokens.accessToken()).toBe("fake-access-token-2");
    expect(fake.requests[1]?.form.refresh_token).toBe(rotatedOnce);
    expect((await stored()).refreshToken).toBe(fake.validRefreshToken);
    expect(fake.validRefreshToken).not.toBe(rotatedOnce);
  });

  it("is renewed on demand, and calls at the same moment share one request", async () => {
    const tokens = manager({});
    await tokens.accessToken();
    const [a, b] = await Promise.all([tokens.refresh(), tokens.refresh()]);
    expect(a).toBe(b);
    expect(fake.requests).toHaveLength(2);
  });

  it("a restart goes on with the rotated token in the table, not the stale one in the environment", async () => {
    await manager({}).accessToken();
    // The environment still has the seed, which Strava no longer accepts.
    expect(fake.validRefreshToken).not.toBe(FAKE_REFRESH_TOKEN);
    const second = manager({});
    expect(await second.accessToken()).toBe("fake-access-token-2");
    expect(fake.requests[1]?.form.refresh_token).not.toBe(FAKE_REFRESH_TOKEN);
  });

  it("a new value in the environment replaces the table's, which is how the owner recovers", async () => {
    await manager({}).accessToken();
    // The owner ran strava-auth again and put the new token in .env.
    fake.validRefreshToken = "fake-refresh-token-from-strava-auth";
    const logging = collectingLogger();
    const second = manager({ seed: "fake-refresh-token-from-strava-auth", logging });
    expect(await second.accessToken()).toBe("fake-access-token-2");
    expect(fake.requests[1]?.form.refresh_token).toBe("fake-refresh-token-from-strava-auth");
    expect((await stored()).seedSha256).toBe(sha256Hex("fake-refresh-token-from-strava-auth"));
    expect(logging.lines.some((l) => /differs/.test(l.message ?? ""))).toBe(true);
  });

  it("works when Strava does not rotate the token, and writes it down all the same", async () => {
    fake.rotateRefreshTokens = false;
    await manager({}).accessToken();
    expect(await stored()).toEqual({
      refreshToken: FAKE_REFRESH_TOKEN,
      seedSha256: sha256Hex(FAKE_REFRESH_TOKEN),
    });
  });

  it("a refused refresh token is a configuration error that says what to do, without any secret", async () => {
    const logging = collectingLogger();
    const tokens = manager({ seed: "fake-refresh-token-revoked", logging });
    const error = await tokens.accessToken().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(StravaError);
    expect(error).toMatchObject({ kind: "auth-failed", status: 400 });
    expect((error as StravaError).configuration).toBe(true);
    const message = (error as StravaError).message;
    expect(message).toContain("strava-auth");
    expect(message).toContain("STRAVA_REFRESH_TOKEN");
    for (const secret of ["fake-refresh-token-revoked", FAKE_CLIENT_SECRET, FAKE_REFRESH_TOKEN]) {
      expect(message).not.toContain(secret);
      expect(logging.text()).not.toContain(secret);
    }
  });

  it("classifies a 429 and a 5xx of the token endpoint as waits, not as a wrong configuration", async () => {
    fake.failNext(1, 429, { retryAfter: 90, path: "/oauth/token" });
    const limited = await manager({})
      .accessToken()
      .catch((e: unknown) => e);
    expect(limited).toMatchObject({ kind: "rate-limited", retryAfter: "90" });
    fake.failNext(1, 503, { path: "/oauth/token" });
    const failed = await manager({})
      .accessToken()
      .catch((e: unknown) => e);
    expect(failed).toMatchObject({ kind: "server" });
    expect((failed as StravaError).configuration).toBe(false);
    fake.setUnreachable(true);
    expect(
      await manager({})
        .accessToken()
        .catch((e: unknown) => e),
    ).toMatchObject({ kind: "unreachable" });
  });

  it("keeps working from memory when the new token cannot be stored, and logs no token", async () => {
    const real = createRefreshTokenStore(db);
    const failing: RefreshTokenStore = {
      load: () => real.load(),
      // A database error can quote the row it refused: that must not reach the log.
      save: (token) =>
        Promise.reject(new Error(`null value, failing row contains (${token.refreshToken})`)),
    };
    const logging = collectingLogger();
    const tokens = manager({ store: failing, logging });
    expect(await tokens.accessToken()).toBe("fake-access-token-1");
    expect(logging.lines.filter((l) => l.level === "error")).toHaveLength(1);
    expect(logging.text()).not.toContain(fake.validRefreshToken);
    expect(logging.text()).not.toContain("fake-refresh-token");
  });
});

describe("strava-auth", () => {
  it("asks for the upload permission and nothing else, and carries no secret in the address", () => {
    const url = new URL(authorizeUrl("424242"));
    expect(url.origin + url.pathname).toBe("https://www.strava.com/oauth/authorize");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: "424242",
      response_type: "code",
      redirect_uri: REDIRECT_URI,
      approval_prompt: "force",
      scope: "activity:write",
    });
    expect(new URL(REDIRECT_URI).hostname).toBe("localhost");
  });

  it("reads the code from the whole address, from its query or from the bare code", () => {
    const code = "0123456789abcdef0123456789abcdef01234567";
    expect(
      parseRedirect(`http://localhost/overdrive-strava?state=&code=${code}&scope=activity:write`),
    ).toEqual({
      code,
      scope: "activity:write",
    });
    expect(parseRedirect(`?code=${code}&scope=read,activity:write`)).toEqual({
      code,
      scope: "read,activity:write",
    });
    expect(parseRedirect(`  ${code}\n`)).toEqual({ code, scope: null });
  });

  it("refuses a denial, an address without a code and a grant that lacks the upload permission, never repeating the code", () => {
    const code = "0123456789abcdef0123456789abcdef01234567";
    const refused = [
      "http://localhost/overdrive-strava?error=access_denied",
      "http://localhost/overdrive-strava?state=1",
      `http://localhost/overdrive-strava?code=${code}&scope=read`,
      "",
      "not an address at all !",
    ];
    for (const pasted of refused) {
      let error: unknown;
      try {
        parseRedirect(pasted);
      } catch (caught) {
        error = caught;
      }
      expect(error, pasted).toBeInstanceOf(AuthorizeError);
      expect((error as Error).message).not.toContain(code);
    }
  });

  it("exchanges the code for a refresh token and drops the athlete unread", async () => {
    const { refreshToken } = await exchangeCode({
      clientId: FAKE_CLIENT_ID,
      clientSecret: FAKE_CLIENT_SECRET,
      code: FAKE_AUTH_CODE,
      baseUrl: fake.url,
    });
    expect(refreshToken).toBe(fake.validRefreshToken);
    expect(Object.keys(fake.requests[0]?.form ?? {}).sort()).toEqual([
      "client_id",
      "client_secret",
      "code",
      "grant_type",
    ]);
    expect(fake.requests[0]?.form.grant_type).toBe("authorization_code");
    // The result has the token and nothing of the athlete the answer carried.
    expect(JSON.stringify({ refreshToken })).not.toContain("Invented");
  });

  it("explains a refused code without repeating it or the secret", async () => {
    const error = await exchangeCode({
      clientId: FAKE_CLIENT_ID,
      clientSecret: FAKE_CLIENT_SECRET,
      code: "an-old-code",
      baseUrl: fake.url,
    }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(AuthorizeError);
    expect((error as Error).message).toContain("HTTP 400");
    expect((error as Error).message).not.toContain("an-old-code");
    expect((error as Error).message).not.toContain(FAKE_CLIENT_SECRET);

    const wrongSecret = await exchangeCode({
      clientId: FAKE_CLIENT_ID,
      clientSecret: "wrong-secret",
      code: FAKE_AUTH_CODE,
      baseUrl: fake.url,
    }).catch((e: unknown) => e);
    expect((wrongSecret as Error).message).toContain("HTTP 401");
    expect((wrongSecret as Error).message).not.toContain("wrong-secret");
  });

  it("refuses a token that was not granted the upload permission", async () => {
    fake.scope = "read";
    const error = await exchangeCode({
      clientId: FAKE_CLIENT_ID,
      clientSecret: FAKE_CLIENT_SECRET,
      code: FAKE_AUTH_CODE,
      baseUrl: fake.url,
    }).catch((e: unknown) => e);
    expect((error as Error).message).toContain("activity:write");
  });
});
