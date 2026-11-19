import { createHash, randomBytes } from "node:crypto";
import { UnauthorizedError } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { InvalidGrantError } from "@modelcontextprotocol/sdk/server/auth/errors.js";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Db } from "../src/db.ts";
import { MAX_PENDING_CLIENTS } from "../src/oauth/store.ts";
import { openMigratedTestDb } from "./helpers/db.ts";
import { type FakeIntervals, startFakeIntervals } from "./helpers/fake-intervals.ts";
import {
  authorizeQuery,
  callTool,
  codeOf,
  LOOPBACK_REDIRECT,
  type McpHarness,
  MemoryOAuthProvider,
  newClient,
  obtainTokens,
  PASSPHRASE,
  pkce,
  register,
  startMcpHarness,
  submitAuthorization,
  type Tokens,
  tokenRequest,
} from "./helpers/mcp.ts";

let db: Db;
let fake: FakeIntervals;
let h: McpHarness;

beforeAll(async () => {
  db = await openMigratedTestDb();
  fake = await startFakeIntervals();
});

afterAll(async () => {
  await fake.close();
  await db.close();
});

beforeEach(async () => {
  await db.exec("TRUNCATE oauth_tokens, oauth_codes, oauth_clients");
  h = await startMcpHarness(db, fake);
  return async () => {
    await h.close();
  };
});

const mcpPost = (token?: string, body: unknown = { jsonrpc: "2.0", id: 1, method: "ping" }) =>
  fetch(h.mcpUrl, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
      ...(token === undefined ? {} : { authorization: `Bearer ${token}` }),
    },
    body: JSON.stringify(body),
  });

const minutes = (n: number) => n * 60_000;
const days = (n: number) => n * 24 * 3_600_000;

describe("discovery", () => {
  it("serves the protected resource's metadata at both well-known addresses", async () => {
    for (const path of [
      "/.well-known/oauth-protected-resource",
      "/.well-known/oauth-protected-resource/mcp",
    ]) {
      const res = await fetch(`${h.baseUrl}${path}`);
      expect(res.status).toBe(200);
      expect(res.headers.get("access-control-allow-origin")).toBe("*");
      expect(await res.json()).toEqual({
        resource: `${h.baseUrl}/mcp`,
        authorization_servers: [h.baseUrl],
        scopes_supported: ["mcp"],
        bearer_methods_supported: ["header"],
        resource_name: "Overdrive",
      });
    }
  });

  it("serves the authorization server's metadata: S256 only, registration, revocation", async () => {
    const res = await fetch(`${h.baseUrl}/.well-known/oauth-authorization-server`);
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({
      issuer: h.baseUrl,
      authorization_endpoint: `${h.baseUrl}/oauth/authorize`,
      token_endpoint: `${h.baseUrl}/oauth/token`,
      registration_endpoint: `${h.baseUrl}/oauth/register`,
      revocation_endpoint: `${h.baseUrl}/oauth/revoke`,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code", "refresh_token"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["none", "client_secret_basic", "client_secret_post"],
      authorization_response_iss_parameter_supported: true,
      client_id_metadata_document_supported: false,
    });
  });

  it("answers an unauthenticated /mcp with 401 and the way to the metadata", async () => {
    const res = await mcpPost();
    expect(res.status).toBe(401);
    expect(res.headers.get("www-authenticate")).toBe(
      `Bearer resource_metadata="${h.baseUrl}/.well-known/oauth-protected-resource/mcp", scope="mcp"`,
    );
    expect(((await res.json()) as { error: { code: string } }).error.code).toBe("unauthorized");
    for (const method of ["GET", "DELETE"]) {
      expect((await fetch(h.mcpUrl, { method })).status).toBe(401);
    }
  });

  it("says invalid_token when a token is sent and is wrong", async () => {
    const res = await mcpPost("odat_not-a-token-this-server-issued-0123456789abcdef");
    expect(res.status).toBe(401);
    expect(res.headers.get("www-authenticate")).toContain('error="invalid_token"');
  });
});

describe("dynamic client registration", () => {
  it("registers a public client", async () => {
    const { status, body } = await register(h.baseUrl);
    expect(status).toBe(201);
    expect(body).toMatchObject({
      client_name: "Test client",
      redirect_uris: [LOOPBACK_REDIRECT],
      token_endpoint_auth_method: "none",
      response_types: ["code"],
      scope: "mcp",
    });
    expect(String(body.client_id)).toMatch(/^odc_[A-Za-z0-9_-]{43}$/);
    expect(body.client_secret).toBeUndefined();
    expect(typeof body.client_id_issued_at).toBe("number");
  });

  it("gives a client that does not say otherwise a secret, as RFC 7591 defaults to client_secret_basic", async () => {
    const res = await fetch(`${h.baseUrl}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ redirect_uris: ["https://claude.ai/api/mcp/auth_callback"] }),
    });
    expect(res.status).toBe(201);
    const body = (await res.json()) as Record<string, unknown>;
    expect(body.token_endpoint_auth_method).toBe("client_secret_basic");
    expect(String(body.client_secret)).toMatch(/^ods_[A-Za-z0-9_-]{43}$/);
    expect(body.client_secret_expires_at).toBe(0);
    expect(body.client_name).toBe("MCP client");
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("stores only the digest of the secret", async () => {
    const { body } = await register(h.baseUrl, {
      token_endpoint_auth_method: "client_secret_post",
    });
    const stored = await db.query<{ client_secret_sha256: string }>(
      "SELECT client_secret_sha256 FROM oauth_clients WHERE client_id = $1",
      [body.client_id],
    );
    const secret = String(body.client_secret);
    expect(stored.rows[0]?.client_secret_sha256).toBe(
      createHash("sha256").update(secret).digest("hex"),
    );
    expect(JSON.stringify(stored.rows)).not.toContain(secret);
  });

  it("cleans the name of a client, which is shown to the owner", async () => {
    const { body } = await register(h.baseUrl, {
      client_name: `  Evil\u0000\u001b[31m ${"x".repeat(300)}  `,
    });
    const name = String(body.client_name);
    expect(name).toMatch(/^Evil\[31m x+$/);
    expect(name.length).toBeLessThanOrEqual(100);
  });

  it.each([
    ["no redirect addresses", { redirect_uris: undefined }, "invalid_redirect_uri"],
    ["an empty list of them", { redirect_uris: [] }, "invalid_redirect_uri"],
    [
      "more than five",
      { redirect_uris: Array.from({ length: 6 }, (_, i) => `https://a.example/${i}`) },
      "invalid_redirect_uri",
    ],
    [
      "plain http off this machine",
      { redirect_uris: ["http://a.example/cb"] },
      "invalid_redirect_uri",
    ],
    ["a custom scheme", { redirect_uris: ["myapp://callback"] }, "invalid_redirect_uri"],
    ["a fragment", { redirect_uris: ["https://a.example/cb#frag"] }, "invalid_redirect_uri"],
    [
      "an address that is text, not a list",
      { redirect_uris: "https://a.example/cb" },
      "invalid_redirect_uri",
    ],
    [
      "an unknown authentication method",
      { token_endpoint_auth_method: "private_key_jwt" },
      "invalid_client_metadata",
    ],
    ["a grant type it does not do", { grant_types: ["implicit"] }, "invalid_client_metadata"],
    ["the password grant", { grant_types: ["password"] }, "invalid_client_metadata"],
    ["a response type it does not do", { response_types: ["token"] }, "invalid_client_metadata"],
    ["a scope it does not have", { scope: "mcp admin" }, "invalid_client_metadata"],
    ["a name that is not text", { client_name: 7 }, "invalid_client_metadata"],
  ])("refuses %s", async (_what, metadata, code) => {
    const { status, body } = await register(h.baseUrl, metadata);
    expect(status).toBe(400);
    expect(body.error).toBe(code);
    expect(String(body.error_description).length).toBeGreaterThan(0);
  });

  it("refuses a body that is not an object, or not JSON, in OAuth's shape", async () => {
    const list = await fetch(`${h.baseUrl}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "[]",
    });
    expect(list.status).toBe(400);
    expect(((await list.json()) as { error: string }).error).toBe("invalid_client_metadata");
    const junk = await fetch(`${h.baseUrl}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{nope",
    });
    expect(junk.status).toBe(400);
    expect(((await junk.json()) as { error: string }).error).toBe("invalid_request");
  });

  it("allows thirty registrations an hour and then answers 429 until the hour has passed", async () => {
    for (let i = 0; i < 30; i++) expect((await register(h.baseUrl)).status).toBe(201);
    const refused = await fetch(`${h.baseUrl}/oauth/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ redirect_uris: [LOOPBACK_REDIRECT] }),
    });
    expect(refused.status).toBe(429);
    expect(refused.headers.get("retry-after")).toBe("3600");
    expect(((await refused.json()) as { error: string }).error).toBe("temporarily_unavailable");
    h.clock.advance(minutes(61));
    expect((await register(h.baseUrl)).status).toBe(201);
  });

  it("refuses more than a hundred clients that nobody has used, and forgets them after a day", async () => {
    await db.query(
      `INSERT INTO oauth_clients (client_id, client_name, redirect_uris, token_endpoint_auth_method, created_at)
       SELECT 'odc_filler' || n, 'filler', '["https://a.example/cb"]'::jsonb, 'none', $1::timestamptz
         FROM generate_series(1, $2::int) AS n`,
      [h.clock.now().toISOString(), MAX_PENDING_CLIENTS],
    );
    const refused = await register(h.baseUrl);
    expect(refused.status).toBe(429);
    h.clock.advance(days(1) + minutes(1));
    expect((await register(h.baseUrl)).status).toBe(201);
    const left = await db.query<{ n: string }>("SELECT count(*)::text AS n FROM oauth_clients");
    expect(left.rows[0]?.n).toBe("1");
  });
});

describe("the authorization page", () => {
  async function clientId(metadata: Record<string, unknown> = {}): Promise<string> {
    return String((await register(h.baseUrl, metadata)).body.client_id);
  }

  it("asks the owner for the passphrase, naming the client and where it goes back to", async () => {
    const id = await clientId({
      client_name: "My <b>assistant</b>",
      redirect_uris: ["https://claude.ai/api/mcp/auth_callback"],
    });
    const { challenge } = pkce();
    const query = authorizeQuery(h.baseUrl, {
      clientId: id,
      challenge,
      redirectUri: "https://claude.ai/api/mcp/auth_callback",
    });
    const res = await fetch(`${h.baseUrl}/oauth/authorize?${query}`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toMatch(/^text\/html/);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("x-frame-options")).toBe("DENY");
    expect(res.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    expect(res.headers.get("referrer-policy")).toBe("no-referrer");
    const html = await res.text();
    // The name is text, not markup.
    expect(html).toContain("My &lt;b&gt;assistant&lt;/b&gt;");
    expect(html).not.toContain("<b>assistant</b>");
    expect(html).toContain("claude.ai");
    expect(html).toContain('type="password"');
    expect(html).toContain(`value="${challenge}"`);
    expect(html).not.toContain("<script");
  });

  it("calls a loopback address an app on this device", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, { clientId: id, challenge: pkce().challenge });
    const html = await (await fetch(`${h.baseUrl}/oauth/authorize?${query}`)).text();
    expect(html).toContain("an app on this device");
  });

  it("does not redirect when the client or the address cannot be trusted", async () => {
    const id = await clientId();
    const { challenge } = pkce();
    const cases: [string, URLSearchParams][] = [
      ["no client", new URLSearchParams({ response_type: "code" })],
      ["an unknown client", authorizeQuery(h.baseUrl, { clientId: "odc_nobody", challenge })],
      [
        "an address nobody registered",
        authorizeQuery(h.baseUrl, {
          clientId: id,
          challenge,
          redirectUri: "https://evil.example/cb",
        }),
      ],
      [
        "a registered host on another path",
        authorizeQuery(h.baseUrl, {
          clientId: id,
          challenge,
          redirectUri: "http://127.0.0.1:43111/other",
        }),
      ],
    ];
    for (const [what, query] of cases) {
      const res = await fetch(`${h.baseUrl}/oauth/authorize?${query}`, { redirect: "manual" });
      expect(res.status, what).toBe(400);
      expect(res.headers.get("location"), what).toBeNull();
      expect(res.headers.get("x-frame-options"), what).toBe("DENY");
    }
  });

  it("redirects the other mistakes back to the client, with its state and the issuer", async () => {
    const id = await clientId();
    const { challenge } = pkce();
    const cases: [string, Partial<Parameters<typeof authorizeQuery>[1]>, string][] = [
      ["a response type that is not code", { responseType: "token" }, "unsupported_response_type"],
      ["the plain challenge method", { method: "plain" }, "invalid_request"],
      ["a challenge that is not S256's", { challenge: "short" }, "invalid_request"],
      ["a scope that does not exist", { scope: "mcp admin" }, "invalid_scope"],
      [
        "a resource that is not this endpoint",
        { resource: "https://elsewhere.example/mcp" },
        "invalid_target",
      ],
    ];
    for (const [what, override, error] of cases) {
      const query = authorizeQuery(h.baseUrl, {
        clientId: id,
        challenge,
        state: "s-42",
        ...override,
      });
      const res = await fetch(`${h.baseUrl}/oauth/authorize?${query}`, { redirect: "manual" });
      expect(res.status, what).toBe(302);
      const location = new URL(res.headers.get("location") ?? "");
      expect(`${location.origin}${location.pathname}`, what).toBe(LOOPBACK_REDIRECT);
      expect(location.searchParams.get("error"), what).toBe(error);
      expect(location.searchParams.get("state"), what).toBe("s-42");
      expect(location.searchParams.get("iss"), what).toBe(h.baseUrl);
    }
  });

  it("refuses a request with no challenge at all", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, { clientId: id, challenge: "x" });
    query.delete("code_challenge");
    query.delete("code_challenge_method");
    const res = await fetch(`${h.baseUrl}/oauth/authorize?${query}`, { redirect: "manual" });
    expect(res.status).toBe(302);
    expect(new URL(res.headers.get("location") ?? "").searchParams.get("error")).toBe(
      "invalid_request",
    );
  });

  it("takes any port for a loopback address, and the only registered address when none is named", async () => {
    const id = await clientId();
    const { challenge } = pkce();
    const otherPort = authorizeQuery(h.baseUrl, {
      clientId: id,
      challenge,
      redirectUri: "http://127.0.0.1:55555/callback",
    });
    const approved = await submitAuthorization(h.baseUrl, otherPort, { passphrase: PASSPHRASE });
    expect(approved.headers.get("location")).toMatch(
      /^http:\/\/127\.0\.0\.1:55555\/callback\?code=/,
    );

    const none = authorizeQuery(h.baseUrl, { clientId: id, challenge });
    none.delete("redirect_uri");
    const defaulted = await submitAuthorization(h.baseUrl, none, { passphrase: PASSPHRASE });
    expect(defaulted.headers.get("location")).toMatch(
      /^http:\/\/127\.0\.0\.1:43111\/callback\?code=/,
    );
  });

  it("sends back a code, the state and the issuer when the passphrase is right", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, {
      clientId: id,
      challenge: pkce().challenge,
      state: "state-7",
    });
    const approved = await submitAuthorization(h.baseUrl, query, { passphrase: PASSPHRASE });
    const { code, state, iss } = codeOf(approved);
    expect(code).toMatch(/^odcode_[A-Za-z0-9_-]{43}$/);
    expect(state).toBe("state-7");
    expect(iss).toBe(h.baseUrl);
    // Only a digest of the code is kept.
    const stored = await db.query("SELECT code_sha256 FROM oauth_codes");
    expect(JSON.stringify(stored.rows)).not.toContain(code);
  });

  it("answers a wrong passphrase with the page again and counts the tries, without a code", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, { clientId: id, challenge: pkce().challenge });
    const wrong = await submitAuthorization(h.baseUrl, query, { passphrase: "zzz-guess-77" });
    expect(wrong.status).toBe(401);
    expect(wrong.headers.get("location")).toBeNull();
    const html = await wrong.text();
    expect(html).toContain("That is not the passphrase. 4 tries left.");
    // The form is back, and the passphrase that was typed is not.
    expect(html).toContain('type="password"');
    expect(html).not.toContain("zzz-guess-77");
    expect((await db.query("SELECT 1 FROM oauth_codes")).rows).toHaveLength(0);
  });

  it("asks for a passphrase when none was typed", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, { clientId: id, challenge: pkce().challenge });
    const res = await submitAuthorization(h.baseUrl, query, { passphrase: "" });
    expect(res.status).toBe(400);
    expect(await res.text()).toContain("Type your passphrase.");
  });

  it("sends the client back access_denied when the owner refuses, without asking for the passphrase", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, {
      clientId: id,
      challenge: pkce().challenge,
      state: "no-thanks",
    });
    const res = await submitAuthorization(h.baseUrl, query, { decision: "deny" });
    expect(res.status).toBe(302);
    const location = new URL(res.headers.get("location") ?? "");
    expect(location.searchParams.get("error")).toBe("access_denied");
    expect(location.searchParams.get("state")).toBe("no-thanks");
    expect(location.searchParams.get("code")).toBeNull();
  });

  it("locks after five wrong passphrases: the right one is not even tried until the lock ends", async () => {
    const id = await clientId();
    const query = authorizeQuery(h.baseUrl, { clientId: id, challenge: pkce().challenge });
    for (let i = 0; i < 4; i++) {
      expect(
        (await submitAuthorization(h.baseUrl, query, { passphrase: `guess ${i}` })).status,
      ).toBe(401);
    }
    const fifth = await submitAuthorization(h.baseUrl, query, { passphrase: "guess 5" });
    expect(fifth.status).toBe(429);
    expect(fifth.headers.get("retry-after")).toBe("900");
    expect(await fifth.text()).toContain("Too many wrong passphrases. Try again in 15 minutes.");

    // Locked: the owner's own passphrase gets the same answer, and no code.
    const locked = await submitAuthorization(h.baseUrl, query, { passphrase: PASSPHRASE });
    expect(locked.status).toBe(429);
    expect(locked.headers.get("location")).toBeNull();
    const page = await fetch(`${h.baseUrl}/oauth/authorize?${query}`);
    expect(await page.text()).toContain("Too many wrong passphrases");
    expect((await db.query("SELECT 1 FROM oauth_codes")).rows).toHaveLength(0);

    // The lock ends with time, and a right passphrase clears the count.
    h.clock.advance(minutes(15) + 1000);
    expect(
      codeOf(await submitAuthorization(h.baseUrl, query, { passphrase: PASSPHRASE })).code,
    ).toBeTruthy();
    expect(
      (await submitAuthorization(h.baseUrl, query, { passphrase: "wrong again" })).status,
    ).toBe(401);
    expect(await (await fetch(`${h.baseUrl}/oauth/authorize?${query}`)).text()).not.toContain(
      "Too many",
    );
  });

  it("never writes the passphrase, a code, a token or a secret to the log", async () => {
    const { clientId: id, tokens } = await obtainTokens(h.baseUrl, {
      registration: { token_endpoint_auth_method: "none" },
    });
    await submitAuthorization(
      h.baseUrl,
      authorizeQuery(h.baseUrl, { clientId: id, challenge: pkce().challenge }),
      {
        passphrase: "a wrong passphrase 123",
      },
    );
    await tokenRequest(h.baseUrl, {
      grant_type: "refresh_token",
      client_id: id,
      refresh_token: tokens.refresh_token,
    });
    const log = h.appLogs.join("\n");
    expect(log).toContain("oauth authorization approved");
    expect(log).toContain("wrong passphrase");
    for (const secret of [
      PASSPHRASE,
      "a wrong passphrase 123",
      tokens.access_token,
      tokens.refresh_token,
    ]) {
      expect(log).not.toContain(secret);
    }
    expect(log).not.toMatch(/odcode_|odat_|odrt_|ods_/);
  });
});

describe("the token endpoint", () => {
  async function approved(
    registration: Record<string, unknown> = {},
    redirectUri = LOOPBACK_REDIRECT,
  ): Promise<{ clientId: string; secret: string | undefined; code: string; verifier: string }> {
    const registered = await register(h.baseUrl, registration);
    const clientId = String(registered.body.client_id);
    const { verifier, challenge } = pkce();
    const res = await submitAuthorization(
      h.baseUrl,
      authorizeQuery(h.baseUrl, { clientId, challenge, redirectUri }),
      {
        passphrase: PASSPHRASE,
      },
    );
    return {
      clientId,
      secret:
        registered.body.client_secret === undefined
          ? undefined
          : String(registered.body.client_secret),
      code: codeOf(res).code,
      verifier,
    };
  }

  const exchange = (
    a: { clientId: string; code: string; verifier: string },
    extra: Record<string, string> = {},
  ) =>
    tokenRequest(h.baseUrl, {
      grant_type: "authorization_code",
      client_id: a.clientId,
      code: a.code,
      code_verifier: a.verifier,
      redirect_uri: LOOPBACK_REDIRECT,
      resource: `${h.baseUrl}/mcp`,
      ...extra,
    });

  it("exchanges the code for tokens, as the spec shows", async () => {
    const a = await approved();
    const res = await exchange(a);
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("pragma")).toBe("no-cache");
    expect(res.body).toMatchObject({ token_type: "Bearer", expires_in: 3600, scope: "mcp" });
    expect(String(res.body.access_token)).toMatch(/^odat_[A-Za-z0-9_-]{43}$/);
    expect(String(res.body.refresh_token)).toMatch(/^odrt_[A-Za-z0-9_-]{43}$/);
    // Only digests are stored.
    const stored = await db.query<{ token_sha256: string }>(
      "SELECT token_sha256 FROM oauth_tokens",
    );
    expect(stored.rows).toHaveLength(2);
    expect(JSON.stringify(stored.rows)).not.toContain(String(res.body.access_token));
    expect(JSON.stringify(stored.rows)).not.toContain(String(res.body.refresh_token));
    // And the access token opens /mcp.
    expect((await mcpPost(String(res.body.access_token))).status).toBe(200);
  });

  it("burns the code at a wrong verifier: the owner's own try after it fails too", async () => {
    const a = await approved();
    const wrong = await exchange(a, { code_verifier: pkce().verifier });
    expect(wrong.status).toBe(400);
    expect(wrong.body.error).toBe("invalid_grant");
    const right = await exchange(a);
    expect(right.status).toBe(400);
    expect(right.body.error).toBe("invalid_grant");
    expect((await db.query("SELECT 1 FROM oauth_tokens")).rows).toHaveLength(0);
  });

  it.each([
    ["no verifier", { code_verifier: "" }, "invalid_request"],
    ["a verifier that is too short", { code_verifier: "abc" }, "invalid_request"],
    [
      "a verifier with characters outside the syntax",
      { code_verifier: `${"a".repeat(50)}!` },
      "invalid_request",
    ],
  ])("refuses %s", async (_what, override, error) => {
    const a = await approved();
    const params: Record<string, string> = {
      grant_type: "authorization_code",
      client_id: a.clientId,
      code: a.code,
    };
    params.code_verifier = override.code_verifier ?? a.verifier;
    if (params.code_verifier === "") delete params.code_verifier;
    const res = await tokenRequest(h.baseUrl, params);
    expect(res.status).toBe(400);
    expect(res.body.error).toBe(error);
  });

  it("refuses a code twice and takes back the tokens the first use made", async () => {
    const a = await approved();
    const first = await exchange(a);
    expect(first.status).toBe(200);
    const second = await exchange(a);
    expect(second.status).toBe(400);
    expect(second.body.error).toBe("invalid_grant");
    // The family is revoked: neither token works any more.
    expect((await mcpPost(String(first.body.access_token))).status).toBe(401);
    const refreshed = await tokenRequest(h.baseUrl, {
      grant_type: "refresh_token",
      client_id: a.clientId,
      refresh_token: String(first.body.refresh_token),
    });
    expect(refreshed.status).toBe(400);
    expect(h.appLogs.join("\n")).toContain("oauth code used twice");
  });

  it("refuses a code that has expired, five minutes after it was made", async () => {
    const a = await approved();
    h.clock.advance(minutes(5) + 1000);
    const res = await exchange(a);
    expect(res.status).toBe(400);
    expect(res.body).toMatchObject({
      error: "invalid_grant",
      error_description: "The code has expired",
    });
  });

  it("accepts the code just before it expires", async () => {
    const a = await approved();
    h.clock.advance(minutes(5) - 1000);
    expect((await exchange(a)).status).toBe(200);
  });

  it("refuses a code that was made for another client, and does not burn it", async () => {
    const a = await approved();
    const other = await register(h.baseUrl);
    const stolen = await tokenRequest(h.baseUrl, {
      grant_type: "authorization_code",
      client_id: String(other.body.client_id),
      code: a.code,
      code_verifier: a.verifier,
    });
    expect(stolen.status).toBe(400);
    expect(stolen.body.error).toBe("invalid_grant");
    expect((await exchange(a)).status).toBe(200);
  });

  it("refuses another redirect address than the code was made for", async () => {
    const a = await approved();
    const res = await exchange(a, { redirect_uri: "http://127.0.0.1:43111/elsewhere" });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe("invalid_grant");
  });

  it("refuses a resource that is not this endpoint", async () => {
    const a = await approved();
    const res = await exchange(a, { resource: "https://elsewhere.example/mcp" });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe("invalid_target");
  });

  it("accepts the bare address of the service as the resource, and none at all", async () => {
    const withBare = await approved();
    expect((await exchange(withBare, { resource: `${h.baseUrl}/` })).status).toBe(200);
    const without = await approved();
    const params = {
      grant_type: "authorization_code",
      client_id: without.clientId,
      code: without.code,
      code_verifier: without.verifier,
    };
    expect((await tokenRequest(h.baseUrl, params)).status).toBe(200);
  });

  it("knows only the two grants, and a form", async () => {
    const a = await approved();
    const res = await tokenRequest(h.baseUrl, { grant_type: "password", client_id: a.clientId });
    expect(res.status).toBe(400);
    expect(res.body.error).toBe("unsupported_grant_type");
    const json = await fetch(`${h.baseUrl}/oauth/token`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ grant_type: "authorization_code" }),
    });
    expect(json.status).toBe(400);
    expect(((await json.json()) as { error: string }).error).toBe("invalid_request");
  });

  describe("client authentication", () => {
    it("rejects a client nobody registered", async () => {
      const a = await approved();
      const res = await exchange({ ...a, clientId: "odc_nobody" });
      expect(res.status).toBe(401);
      expect(res.body.error).toBe("invalid_client");
    });

    it("needs the secret of a confidential client, in the body or in Basic", async () => {
      const a = await approved({ token_endpoint_auth_method: "client_secret_post" });
      const secret = a.secret as string;
      const without = await exchange(a);
      expect(without.status).toBe(401);
      expect(without.body.error).toBe("invalid_client");
      const wrong = await exchange(a, { client_secret: `${secret}x` });
      expect(wrong.status).toBe(401);
      // The wrong tries did not burn the code: the right secret works.
      const right = await exchange(a, { client_secret: secret });
      expect(right.status).toBe(200);

      const b = await approved({ token_endpoint_auth_method: "client_secret_basic" });
      const basic = Buffer.from(
        `${encodeURIComponent(b.clientId)}:${encodeURIComponent(b.secret as string)}`,
      ).toString("base64");
      const viaBasic = await tokenRequest(
        h.baseUrl,
        { grant_type: "authorization_code", code: b.code, code_verifier: b.verifier },
        { authorization: `Basic ${basic}` },
      );
      expect(viaBasic.status).toBe(200);

      const c = await approved({ token_endpoint_auth_method: "client_secret_basic" });
      const badBasic = Buffer.from(`${c.clientId}:not-the-secret`).toString("base64");
      const refused = await tokenRequest(
        h.baseUrl,
        { grant_type: "authorization_code", code: c.code, code_verifier: c.verifier },
        { authorization: `Basic ${badBasic}` },
      );
      expect(refused.status).toBe(401);
      expect(refused.headers.get("www-authenticate")).toBe('Basic realm="overdrive"');
    });
  });

  describe("refresh tokens", () => {
    const refresh = (clientId: string, token: string, extra: Record<string, string> = {}) =>
      tokenRequest(h.baseUrl, {
        grant_type: "refresh_token",
        client_id: clientId,
        refresh_token: token,
        ...extra,
      });

    async function signedIn(): Promise<{ clientId: string; tokens: Tokens }> {
      return obtainTokens(h.baseUrl);
    }

    it("gives a new pair for the old refresh token, which is good once", async () => {
      const { clientId, tokens } = await signedIn();
      const next = await refresh(clientId, tokens.refresh_token);
      expect(next.status).toBe(200);
      expect(next.body.refresh_token).not.toBe(tokens.refresh_token);
      expect(next.body.access_token).not.toBe(tokens.access_token);
      expect(next.body).toMatchObject({ token_type: "Bearer", expires_in: 3600, scope: "mcp" });
      expect((await mcpPost(String(next.body.access_token))).status).toBe(200);
      // The first access token stays good until its own expiry.
      expect((await mcpPost(tokens.access_token)).status).toBe(200);
    });

    it("lets a request that raced the first refresh use the old token again, for a few seconds", async () => {
      const { clientId, tokens } = await signedIn();
      const first = await refresh(clientId, tokens.refresh_token);
      h.clock.advance(14_000);
      const second = await refresh(clientId, tokens.refresh_token);
      expect([first.status, second.status]).toEqual([200, 200]);
      expect(second.body.refresh_token).not.toBe(first.body.refresh_token);
      // Both pairs work, and nothing was revoked.
      expect((await mcpPost(String(first.body.access_token))).status).toBe(200);
      expect((await mcpPost(String(second.body.access_token))).status).toBe(200);
      expect((await refresh(clientId, String(first.body.refresh_token))).status).toBe(200);
      expect((await refresh(clientId, String(second.body.refresh_token))).status).toBe(200);
      expect(h.appLogs.join("\n")).not.toContain("oauth refresh token reused");
    });

    it("ends the whole family when an old refresh token is used again after that", async () => {
      const { clientId, tokens } = await signedIn();
      const next = await refresh(clientId, tokens.refresh_token);
      expect(next.status).toBe(200);
      h.clock.advance(16_000);
      const replay = await refresh(clientId, tokens.refresh_token);
      expect(replay.status).toBe(400);
      expect(replay.body).toMatchObject({
        error: "invalid_grant",
        error_description: "The refresh token was already used",
      });
      // What the first refresh made is gone too: whoever has it, the owner's client or a thief, signs in again.
      expect((await mcpPost(String(next.body.access_token))).status).toBe(401);
      expect((await refresh(clientId, String(next.body.refresh_token))).status).toBe(400);
      expect(h.appLogs.join("\n")).toContain("oauth refresh token reused");
    });

    it("lets a refresh token live thirty days from when it was made, and each refresh starts them again", async () => {
      const { clientId, tokens } = await signedIn();
      h.clock.advance(days(20));
      const next = await refresh(clientId, tokens.refresh_token);
      expect(next.status).toBe(200);
      h.clock.advance(days(20));
      // 40 days after the start, 20 after the refresh.
      const again = await refresh(clientId, String(next.body.refresh_token));
      expect(again.status).toBe(200);
      h.clock.advance(days(30) + 1000);
      const late = await refresh(clientId, String(again.body.refresh_token));
      expect(late.status).toBe(400);
      expect(late.body.error_description).toBe("The refresh token has expired");
    });

    it("lets an access token expire after an hour while the refresh token goes on", async () => {
      const { clientId, tokens } = await signedIn();
      h.clock.advance(minutes(60) - 1000);
      expect((await mcpPost(tokens.access_token)).status).toBe(200);
      h.clock.advance(2000);
      const expired = await mcpPost(tokens.access_token);
      expect(expired.status).toBe(401);
      expect(expired.headers.get("www-authenticate")).toContain('error="invalid_token"');
      const next = await refresh(clientId, tokens.refresh_token);
      expect(next.status).toBe(200);
      expect((await mcpPost(String(next.body.access_token))).status).toBe(200);
    });

    it("refuses another client's refresh token, and does not use it up", async () => {
      const { clientId, tokens } = await signedIn();
      const other = await register(h.baseUrl);
      const stolen = await refresh(String(other.body.client_id), tokens.refresh_token);
      expect(stolen.status).toBe(400);
      expect(stolen.body.error).toBe("invalid_grant");
      expect((await refresh(clientId, tokens.refresh_token)).status).toBe(200);
    });

    it("refuses a scope wider than the grant and a resource that is not this endpoint", async () => {
      const { clientId, tokens } = await signedIn();
      const wide = await refresh(clientId, tokens.refresh_token, { scope: "mcp admin" });
      expect(wide.status).toBe(400);
      expect(wide.body.error).toBe("invalid_scope");
      const elsewhere = await refresh(clientId, tokens.refresh_token, {
        resource: "https://elsewhere.example/mcp",
      });
      expect(elsewhere.status).toBe(400);
      expect(elsewhere.body.error).toBe("invalid_target");
      // Neither used the token up.
      expect((await refresh(clientId, tokens.refresh_token, { scope: "mcp" })).status).toBe(200);
    });

    it("refuses what is not a refresh token, an access token included", async () => {
      const { clientId, tokens } = await signedIn();
      expect((await refresh(clientId, tokens.access_token)).status).toBe(400);
      expect((await refresh(clientId, "odrt_nothing")).status).toBe(400);
      expect(
        (await tokenRequest(h.baseUrl, { grant_type: "refresh_token", client_id: clientId })).body
          .error,
      ).toBe("invalid_request");
    });
  });

  describe("revocation", () => {
    const revoke = (clientId: string, token: string, extra: Record<string, string> = {}) =>
      fetch(`${h.baseUrl}/oauth/revoke`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: clientId, token, ...extra }),
      });

    it("revokes an access token and leaves the refresh token", async () => {
      const { clientId, tokens } = await obtainTokens(h.baseUrl);
      expect(
        (await revoke(clientId, tokens.access_token, { token_type_hint: "access_token" })).status,
      ).toBe(200);
      expect((await mcpPost(tokens.access_token)).status).toBe(401);
      const next = await tokenRequest(h.baseUrl, {
        grant_type: "refresh_token",
        client_id: clientId,
        refresh_token: tokens.refresh_token,
      });
      expect(next.status).toBe(200);
    });

    it("revokes a refresh token and everything that came with it", async () => {
      const { clientId, tokens } = await obtainTokens(h.baseUrl);
      expect((await revoke(clientId, tokens.refresh_token)).status).toBe(200);
      expect((await mcpPost(tokens.access_token)).status).toBe(401);
      const next = await tokenRequest(h.baseUrl, {
        grant_type: "refresh_token",
        client_id: clientId,
        refresh_token: tokens.refresh_token,
      });
      expect(next.status).toBe(400);
    });

    it("answers 200 for a token it does not know, and does nothing for another client's", async () => {
      const { clientId, tokens } = await obtainTokens(h.baseUrl);
      expect((await revoke(clientId, "odat_unknown")).status).toBe(200);
      const other = await register(h.baseUrl);
      expect((await revoke(String(other.body.client_id), tokens.access_token)).status).toBe(200);
      expect((await mcpPost(tokens.access_token)).status).toBe(200);
    });

    it("needs a client that can be authenticated, and a token", async () => {
      expect((await revoke("odc_nobody", "odat_x")).status).toBe(401);
      const { clientId } = await obtainTokens(h.baseUrl);
      const res = await fetch(`${h.baseUrl}/oauth/revoke`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ client_id: clientId }),
      });
      expect(res.status).toBe(400);
    });
  });
});

describe("what /mcp accepts", () => {
  it("refuses a token issued for another resource, or without the scope", async () => {
    const { clientId } = await obtainTokens(h.baseUrl);
    const insert = async (resource: string, scope: string) => {
      const token = `odat_${randomBytes(32).toString("base64url")}`;
      await db.query(
        `INSERT INTO oauth_tokens (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at)
         VALUES ($1, 'access', 'f', $2, $3, $4, $5::timestamptz, $5::timestamptz)`,
        [
          createHash("sha256").update(token).digest("hex"),
          clientId,
          scope,
          resource,
          "2099-01-01T00:00:00Z",
        ],
      );
      return token;
    };
    expect((await mcpPost(await insert("https://elsewhere.example/mcp", "mcp"))).status).toBe(401);
    expect((await mcpPost(await insert(`${h.baseUrl}/mcp`, "other"))).status).toBe(401);
    expect((await mcpPost(await insert(`${h.baseUrl}/mcp`, "mcp"))).status).toBe(200);
  });

  it("refuses a refresh token, a code and a token of the shape of none of ours", async () => {
    const { tokens } = await obtainTokens(h.baseUrl);
    expect((await mcpPost(tokens.refresh_token)).status).toBe(401);
    expect((await mcpPost("odcode_x")).status).toBe(401);
    expect((await mcpPost("x".repeat(40))).status).toBe(401);
    expect((await mcpPost("")).status).toBe(401);
  });

  it("answers 405 to GET and DELETE once authenticated: there is no session and no stream", async () => {
    const { tokens } = await obtainTokens(h.baseUrl);
    for (const method of ["GET", "DELETE"]) {
      const res = await fetch(h.mcpUrl, {
        method,
        headers: { authorization: `Bearer ${tokens.access_token}` },
      });
      expect(res.status).toBe(405);
      expect(res.headers.get("allow")).toBe("POST");
    }
  });

  it("answers a body that is not JSON in JSON-RPC's words", async () => {
    const { tokens } = await obtainTokens(h.baseUrl);
    const res = await fetch(h.mcpUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${tokens.access_token}`,
        accept: "application/json, text/event-stream",
      },
      body: "{nope",
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ jsonrpc: "2.0", error: { code: -32700 } });
  });
});

describe("a real MCP client, the SDK's, from nothing to a tool call", () => {
  it("discovers, registers, is approved by the owner, and calls a tool", async () => {
    const provider = new MemoryOAuthProvider();
    const first = new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider });
    await expect(newClient().connect(first)).rejects.toBeInstanceOf(UnauthorizedError);
    expect(provider.registrations).toBe(1);
    const url = provider.authorizationUrl as URL;
    expect(url.origin).toBe(h.baseUrl);
    expect(url.searchParams.get("resource")).toBe(`${h.baseUrl}/mcp`);
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");

    const { code } = codeOf(
      await submitAuthorization(h.baseUrl, url.searchParams, { passphrase: PASSPHRASE }),
    );
    await first.finishAuth(code);

    const client = newClient();
    await client.connect(new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider }));
    const tools = await client.listTools();
    expect(tools.tools.map((t) => t.name).sort()).toEqual([
      "append_notebook_log",
      "create_workout",
      "delete_workout",
      "get_activity",
      "get_facts",
      "get_fitness",
      "get_notebook",
      "get_profile",
      "get_recovery",
      "get_repeat",
      "get_ride",
      "get_wellness",
      "list_activities",
      "list_discussion_topics",
      "list_insights",
      "list_notebook_versions",
      "list_plan_events",
      "list_planned_workouts",
      "list_repeats",
      "list_rides",
      "mark_discussion_topic",
      "resolve_plan_event",
      "rollback_notebook_section",
      "update_notebook_section",
      "update_profile",
      "update_workout",
    ]);
    expect((await callTool(client, "get_profile")).isError).toBe(false);
    await client.close();
  });

  it("refreshes an expired access token by itself, rotating the refresh token", async () => {
    const provider = new MemoryOAuthProvider();
    const first = new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider });
    await expect(newClient().connect(first)).rejects.toBeInstanceOf(UnauthorizedError);
    const { code } = codeOf(
      await submitAuthorization(h.baseUrl, (provider.authorizationUrl as URL).searchParams, {
        passphrase: PASSPHRASE,
      }),
    );
    await first.finishAuth(code);
    const client = newClient();
    await client.connect(new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider }));
    const before = provider.tokens();

    h.clock.advance(minutes(61));
    // The client's own call finds the token expired, refreshes and goes on.
    expect((await callTool(client, "get_profile")).isError).toBe(false);
    const after = provider.tokens();
    expect(after?.access_token).not.toBe(before?.access_token);
    expect(after?.refresh_token).not.toBe(before?.refresh_token);
    expect(provider.registrations).toBe(1);
    await client.close();
  });

  it("is told invalid_grant, which is its cue to ask the owner again, once its grant is revoked", async () => {
    const provider = new MemoryOAuthProvider();
    const first = new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider });
    await expect(newClient().connect(first)).rejects.toBeInstanceOf(UnauthorizedError);
    const { code } = codeOf(
      await submitAuthorization(h.baseUrl, (provider.authorizationUrl as URL).searchParams, {
        passphrase: PASSPHRASE,
      }),
    );
    await first.finishAuth(code);
    // The owner revokes everything (any refresh token of the family ends the family).
    await db.exec("UPDATE oauth_tokens SET revoked_at = now()");
    // The SDK's client lets the application decide what to do with a refresh token that is refused.
    await expect(
      newClient().connect(new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider })),
    ).rejects.toBeInstanceOf(InvalidGrantError);
  });

  it("works with a confidential client that takes its secret in the body", async () => {
    const provider = new MemoryOAuthProvider(LOOPBACK_REDIRECT, "client_secret_post");
    const first = new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider });
    await expect(newClient().connect(first)).rejects.toBeInstanceOf(UnauthorizedError);
    expect(provider.clientInformation()?.client_secret).toBeTruthy();
    const { code } = codeOf(
      await submitAuthorization(h.baseUrl, (provider.authorizationUrl as URL).searchParams, {
        passphrase: PASSPHRASE,
      }),
    );
    await first.finishAuth(code);
    const client = newClient();
    await client.connect(new StreamableHTTPClientTransport(h.mcpUrl, { authProvider: provider }));
    expect((await callTool(client, "get_profile")).isError).toBe(false);
    await client.close();
  });
});
