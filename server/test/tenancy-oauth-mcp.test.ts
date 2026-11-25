import type { FastifyInstance } from "fastify";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.ts";
import type { McpTestOptions } from "../src/mcp/index.ts";
import { type PassphraseVerifier, verifyPassphrase } from "../src/oauth/passphrase.ts";
import { TABLET } from "./helpers/app.ts";
import {
  authorizeQuery,
  callTool,
  codeOf,
  connectWithToken,
  freePort,
  LOOPBACK_REDIRECT,
  pkce,
  register,
  submitAuthorization,
  type Tokens,
  tokenRequest,
} from "./helpers/mcp.ts";
import { openTenantRig, type RigUser, type TenantRig } from "./helpers/tenants.ts";

/**
 * Several users on one OAuth server and one MCP endpoint (multi-tenancy, stage B2): a user signs in with a handle and a
 * passphrase, a token belongs to the user that approved it, and a tool call acts on that user's schema only.
 */

let rig: TenantRig;
let alice: RigUser;
let bob: RigUser;
const apps: FastifyInstance[] = [];

beforeAll(async () => {
  rig = await openTenantRig(["alice", "bob"]);
  alice = rig.users.alice as RigUser;
  bob = rig.users.bob as RigUser;
});

afterAll(async () => {
  await rig.close();
});

afterEach(async () => {
  await Promise.all(apps.splice(0).map((a) => a.close()));
  await rig.shared.exec("TRUNCATE oauth_tokens, oauth_codes, oauth_clients, oauth_families");
});

interface Running {
  base: string;
  mcpUrl: URL;
}

async function start(test: McpTestOptions = {}): Promise<Running> {
  const port = await freePort();
  const base = `http://127.0.0.1:${port}`;
  const app = buildApp({
    config: { LOG_LEVEL: "silent", DEVICE_TOKENS: [TABLET], PUBLIC_BASE_URL: base },
    db: alice.db,
    services: alice.services,
    mcpTenancy: {
      shared: rig.shared,
      directory: rig.directory,
      servicesOf: rig.servicesOf,
      users: rig.loginUsers,
    },
    mcp: { now: rig.clock.now, ...test },
  });
  apps.push(app);
  await app.listen({ host: "127.0.0.1", port });
  return { base, mcpUrl: new URL(`${base}/mcp`) };
}

/** Registers a public client and posts the authorization form. */
async function authorize(
  run: Running,
  fields: { handle?: string; passphrase?: string },
): Promise<{ response: Response; clientId: string; verifier: string }> {
  const registered = await register(run.base);
  const clientId = String(registered.body.client_id);
  const { verifier, challenge } = pkce();
  const response = await submitAuthorization(
    run.base,
    authorizeQuery(run.base, { clientId, challenge }),
    fields,
  );
  return { response, clientId, verifier };
}

/** A whole sign-in: the tokens of the user whose handle and passphrase are given. */
async function signIn(run: Running, user: RigUser): Promise<{ clientId: string; tokens: Tokens }> {
  const { response, clientId, verifier } = await authorize(run, {
    handle: user.handle,
    passphrase: user.passphrase,
  });
  const exchanged = await tokenRequest(run.base, {
    grant_type: "authorization_code",
    client_id: clientId,
    code: codeOf(response).code,
    code_verifier: verifier,
    redirect_uri: LOOPBACK_REDIRECT,
    resource: `${run.base}/mcp`,
  });
  expect(exchanged.status, JSON.stringify(exchanged.body)).toBe(200);
  return { clientId, tokens: exchanged.body as unknown as Tokens };
}

async function ftpSeenWith(run: Running, token: string): Promise<number | null> {
  const client = await connectWithToken(run.mcpUrl, token);
  try {
    return ((await callTool(client, "get_profile")).json as { ftp: number | null }).ftp;
  } finally {
    await client.close();
  }
}

describe("the sign-in page of several users", () => {
  it("asks for a handle and keeps what was typed when the passphrase is wrong", async () => {
    const run = await start();
    const { response } = await authorize(run, { handle: "Alice", passphrase: "nope" });
    expect(response.status).toBe(401);
    const html = await response.text();
    expect(html).toContain('name="handle"');
    expect(html).toContain('value="alice"');
    expect(html).not.toContain("nope");
  });

  it("never signs in user B with the passphrase of user A, and each passphrase signs in its own user", async () => {
    const run = await start();
    const crossed = await authorize(run, { handle: "bob", passphrase: alice.passphrase });
    expect(crossed.response.status).toBe(401);
    expect(crossed.response.headers.get("location")).toBeNull();
    const other = await authorize(run, { handle: "alice", passphrase: bob.passphrase });
    expect(other.response.status).toBe(401);

    const first = await authorize(run, { handle: "alice", passphrase: alice.passphrase });
    expect(first.response.status).toBe(302);
    const second = await authorize(run, { handle: "bob", passphrase: bob.passphrase });
    expect(second.response.status).toBe(302);
    // Each family is its user's.
    const families = await rig.shared.query<{ user_id: string }>(
      "SELECT user_id::text AS user_id FROM oauth_families ORDER BY user_id",
    );
    expect(families.rows.map((r) => r.user_id)).toEqual([String(alice.userId), String(bob.userId)]);
  });

  it("answers an unknown handle exactly like a wrong passphrase, with one scrypt run each", async () => {
    let runs = 0;
    const counting: PassphraseVerifier = (passphrase, stored) => {
      runs++;
      return verifyPassphrase(passphrase, stored);
    };
    const run = await start({ verifier: counting });
    const wrong = await authorize(run, { handle: "alice", passphrase: "not the passphrase" });
    const wrongRuns = runs;
    const unknown = await authorize(run, { handle: "nobody", passphrase: "not the passphrase" });
    const unknownRuns = runs - wrongRuns;
    expect(wrongRuns).toBe(1);
    expect(unknownRuns).toBe(1);
    expect(unknown.response.status).toBe(wrong.response.status);
    // The pages differ only in the client's id and the handle typed.
    const scrub = (html: string, handle: string, clientId: string) =>
      html
        .replaceAll(handle, "HANDLE")
        .replaceAll(clientId, "CLIENT")
        .replace(/(name="code_challenge" value=")[^"]*/, "$1CHALLENGE");
    expect(scrub(await unknown.response.text(), "nobody", unknown.clientId)).toBe(
      scrub(await wrong.response.text(), "alice", wrong.clientId),
    );
  });

  it("treats a user who has no passphrase yet like an unknown handle", async () => {
    await rig.shared.exec("INSERT INTO users (handle) VALUES ('fresh')");
    let runs = 0;
    const run = await start({
      verifier: (passphrase, stored) => {
        runs++;
        return verifyPassphrase(passphrase, stored);
      },
    });
    const { response } = await authorize(run, { handle: "fresh", passphrase: "anything at all" });
    expect(response.status).toBe(401);
    expect(runs).toBe(1);
    await rig.shared.exec("DELETE FROM users WHERE handle = 'fresh'");
  });

  it("does not sign in a disabled user, and stops that user's tokens at once", async () => {
    const run = await start();
    const { tokens } = await signIn(run, bob);
    expect(await ftpSeenWith(run, tokens.access_token)).toBeNull();
    await rig.shared.exec("UPDATE users SET disabled_at = now() WHERE handle = 'bob'");
    try {
      const refused = await authorize(run, { handle: "bob", passphrase: bob.passphrase });
      expect(refused.response.status).toBe(401);
      const client = connectWithToken(run.mcpUrl, tokens.access_token);
      await expect(client).rejects.toThrow();
      const refresh = await tokenRequest(run.base, {
        grant_type: "refresh_token",
        refresh_token: tokens.refresh_token,
        client_id:
          (
            await rig.shared.query<{ client_id: string }>(
              "SELECT client_id FROM oauth_clients ORDER BY created_at DESC LIMIT 1",
            )
          ).rows[0]?.client_id ?? "",
      });
      expect(refresh.status).toBe(400);
    } finally {
      await rig.shared.exec("UPDATE users SET disabled_at = NULL WHERE handle = 'bob'");
    }
  });

  it("requires a handle", async () => {
    const run = await start();
    const { response } = await authorize(run, { passphrase: alice.passphrase });
    expect(response.status).toBe(400);
    expect(await response.text()).toContain("Type your handle.");
  });
});

describe("the lockout", () => {
  it("is per handle: five wrong passphrases lock that handle, and the right one is not tried; another handle signs in", async () => {
    const run = await start();
    for (let i = 0; i < 4; i++) {
      expect((await authorize(run, { handle: "alice", passphrase: "wrong" })).response.status).toBe(
        401,
      );
    }
    const fifth = await authorize(run, { handle: "alice", passphrase: "wrong" });
    expect(fifth.response.status).toBe(429);
    expect(Number(fifth.response.headers.get("retry-after"))).toBe(15 * 60);
    // The right passphrase is not even tried while the handle is locked.
    const locked = await authorize(run, { handle: "alice", passphrase: alice.passphrase });
    expect(locked.response.status).toBe(429);
    expect(locked.response.headers.get("location")).toBeNull();
    // Bob is not affected by Alice's lock.
    const fine = await authorize(run, { handle: "bob", passphrase: bob.passphrase });
    expect(fine.response.status).toBe(302);
    // The lock ends with time.
    rig.clock.advance(15 * 60_000 + 1000);
    const again = await authorize(run, { handle: "alice", passphrase: alice.passphrase });
    expect(again.response.status).toBe(302);
  });

  it("locks an unknown handle the same way, so the lock does not show who exists", async () => {
    const run = await start();
    for (let i = 0; i < 4; i++) await authorize(run, { handle: "ghost", passphrase: "wrong" });
    const fifth = await authorize(run, { handle: "ghost", passphrase: "wrong" });
    expect(fifth.response.status).toBe(429);
  });

  it("has a cap on the wrong passphrases of all handles together, which closes the page for everyone until they age out", async () => {
    const run = await start({ globalFailures: { limit: 6, windowMs: 60 * 60_000 } });
    // Six handles, one wrong try each: none is anywhere near its own lock.
    for (const handle of ["a1", "a2", "a3", "a4", "a5"]) {
      expect((await authorize(run, { handle, passphrase: "wrong" })).response.status).toBe(401);
    }
    // The sixth failure fills the cap.
    expect((await authorize(run, { handle: "a6", passphrase: "wrong" })).response.status).toBe(429);
    const everyone = await authorize(run, { handle: "bob", passphrase: bob.passphrase });
    expect(everyone.response.status).toBe(429);
    rig.clock.advance(61 * 60_000);
    expect(
      (await authorize(run, { handle: "bob", passphrase: bob.passphrase })).response.status,
    ).toBe(302);
  });

  it("is not filled by one locked handle: retrying a locked handle adds no failures", async () => {
    const run = await start({ globalFailures: { limit: 8, windowMs: 60 * 60_000 } });
    for (let i = 0; i < 5; i++) await authorize(run, { handle: "alice", passphrase: "wrong" });
    for (let i = 0; i < 20; i++) {
      expect((await authorize(run, { handle: "alice", passphrase: "wrong" })).response.status).toBe(
        429,
      );
    }
    const fine = await authorize(run, { handle: "bob", passphrase: bob.passphrase });
    expect(fine.response.status).toBe(302);
  });
});

describe("tokens belong to the user who approved them", () => {
  it("lets an OAuth token act on its own user's data only, and a refresh keeps the user", async () => {
    await alice.services.profile.update({ ftp: 211 });
    await bob.services.profile.update({ ftp: 322 });
    const run = await start();
    const a = await signIn(run, alice);
    const b = await signIn(run, bob);
    expect(await ftpSeenWith(run, a.tokens.access_token)).toBe(211);
    expect(await ftpSeenWith(run, b.tokens.access_token)).toBe(322);

    // Refresh: the new pair is still Bob's, and so is the family.
    const refreshed = await tokenRequest(run.base, {
      grant_type: "refresh_token",
      client_id: b.clientId,
      refresh_token: b.tokens.refresh_token,
    });
    expect(refreshed.status, JSON.stringify(refreshed.body)).toBe(200);
    const next = refreshed.body as unknown as Tokens;
    expect(next.access_token).not.toBe(b.tokens.access_token);
    expect(await ftpSeenWith(run, next.access_token)).toBe(322);
    const families = await rig.shared.query<{ user_id: string; n: string }>(
      `SELECT f.user_id::text AS user_id, count(DISTINCT t.token_sha256)::text AS n
         FROM oauth_families f JOIN oauth_tokens t ON t.family_id = f.family_id
        GROUP BY f.user_id ORDER BY f.user_id`,
    );
    expect(families.rows).toEqual([
      { user_id: String(alice.userId), n: "2" },
      { user_id: String(bob.userId), n: "4" },
    ]);
  });

  it("writes through a token to its own user's schema, not another's", async () => {
    const run = await start();
    const aliceBefore = (await alice.services.profile.get()).ftp;
    const b = await signIn(run, bob);
    const client = await connectWithToken(run.mcpUrl, b.tokens.access_token);
    try {
      expect((await callTool(client, "update_profile", { ftp: 301 })).isError).toBe(false);
    } finally {
      await client.close();
    }
    expect((await alice.services.profile.get()).ftp).toBe(aliceBefore);
    expect((await bob.services.profile.get()).ftp).toBe(301);
  });

  it("lets a static MCP token of user B call a tool and see B's data only", async () => {
    await alice.services.profile.update({ ftp: 190 });
    await bob.services.profile.update({ ftp: 280 });
    const run = await start();
    expect(await ftpSeenWith(run, bob.mcpToken)).toBe(280);
    expect(await ftpSeenWith(run, alice.mcpToken)).toBe(190);
    // A token nobody has, and a user's token revoked, open nothing.
    const stranger = connectWithToken(run.mcpUrl, "mcp-not-a-token-of-anybody-0123456789");
    await expect(stranger).rejects.toThrow();
    await rig.shared.exec("UPDATE user_tokens SET revoked_at = now() WHERE name = 'claude-bob'");
    try {
      await expect(connectWithToken(run.mcpUrl, bob.mcpToken)).rejects.toThrow();
    } finally {
      await rig.shared.exec("UPDATE user_tokens SET revoked_at = NULL WHERE name = 'claude-bob'");
    }
  });

  it("does not read a device token of the same user as an MCP token", async () => {
    const run = await start();
    // The tablet's static device token in `user_tokens` kind 'device' is not an MCP credential.
    const { sha256Hex } = await import("../src/hash.ts");
    await rig.shared.query(
      "INSERT INTO user_tokens (token_sha256, kind, name, user_id) VALUES ($1, 'device', 'tablet', $2)",
      [sha256Hex("device-token-for-the-test-0123456789"), bob.userId],
    );
    await expect(
      connectWithToken(run.mcpUrl, "device-token-for-the-test-0123456789"),
    ).rejects.toThrow();
  });
});
