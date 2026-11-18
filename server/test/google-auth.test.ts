import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  type AuthIo,
  CALENDAR_SCOPE,
  exchangeCode,
  parseRedirect,
  runGoogleAuth,
} from "../src/gcal/auth-cli.ts";
import { FAKE_CLIENT_ID, FAKE_CLIENT_SECRET } from "./helpers/fake-google.ts";

const REFRESH_TOKEN = "1//0fake-refresh-token-for-the-auth-helper-0123456789";

interface Harness {
  io: AuthIo;
  output: string[];
  /** The address the helper asked the owner to open. */
  authUrl: () => URL;
  asked: { prompt: string; secret: boolean }[];
}

/** A terminal the test drives. `answers` answers the questions in order; the last one is the pasted address. */
function terminal(options: {
  answers?: (string | ((authUrl: URL) => string) | "never")[];
}): Harness {
  const output: string[] = [];
  const asked: Harness["asked"] = [];
  const answers = [...(options.answers ?? [])];
  const authUrl = (): URL => {
    const line = output
      .join("")
      .split("\n")
      .find((l) => l.startsWith("https://"));
    if (line === undefined) throw new Error("no address was printed");
    return new URL(line);
  };
  const io: AuthIo = {
    print: (text) => {
      output.push(text);
    },
    ask(prompt, askOptions = {}) {
      asked.push({ prompt, secret: askOptions.secret === true });
      const next = answers.shift() ?? "never";
      if (next === "never") {
        return new Promise((_, reject) => {
          askOptions.signal?.addEventListener("abort", () => reject(new Error("aborted")), {
            once: true,
          });
        });
      }
      return Promise.resolve(typeof next === "function" ? next(authUrl()) : next);
    },
  };
  return { io, output, authUrl, asked };
}

const env = { GOOGLE_CLIENT_ID: FAKE_CLIENT_ID, GOOGLE_CLIENT_SECRET: FAKE_CLIENT_SECRET };

/** A token endpoint that records what it was sent and answers as given. */
function tokenEndpoint(reply: { status?: number; body: unknown }) {
  const requests: { url: string; form: Record<string, string> }[] = [];
  const fetchStub = (async (url: string | URL | Request, init?: RequestInit) => {
    requests.push({
      url: String(url),
      form: Object.fromEntries(new URLSearchParams(String(init?.body ?? ""))),
    });
    return new Response(JSON.stringify(reply.body), { status: reply.status ?? 200 });
  }) as typeof fetch;
  return { requests, fetch: fetchStub };
}

const good = { body: { access_token: "a", refresh_token: REFRESH_TOKEN, expires_in: 3600 } };

describe("google-auth", () => {
  it("receives the code on the loopback port, exchanges it with PKCE, and prints the refresh token to the terminal", async () => {
    const term = terminal({});
    const token = tokenEndpoint(good);
    const done = runGoogleAuth({ env, io: term.io, fetch: token.fetch, timeoutMs: 10_000 });

    // The helper prints the address; the browser then comes back to the loopback port.
    await expect.poll(() => term.output.join("").includes("https://")).toBe(true);
    const url = term.authUrl();
    expect(url.origin + url.pathname).toBe("https://accounts.google.com/o/oauth2/v2/auth");
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      client_id: FAKE_CLIENT_ID,
      response_type: "code",
      scope: CALENDAR_SCOPE,
      access_type: "offline",
      prompt: "consent",
      code_challenge_method: "S256",
    });
    const redirect = url.searchParams.get("redirect_uri") ?? "";
    expect(redirect).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);
    const state = url.searchParams.get("state") ?? "";
    const answer = await fetch(`${redirect}/?code=AUTH-CODE&state=${state}&scope=x`);
    expect(answer.status).toBe(200);
    expect(await answer.text()).not.toContain("AUTH-CODE");

    expect(await done).toBe(0);
    expect(token.requests).toHaveLength(1);
    const form = token.requests[0]?.form;
    expect(token.requests[0]?.url).toBe("https://oauth2.googleapis.com/token");
    expect(form).toMatchObject({
      code: "AUTH-CODE",
      client_id: FAKE_CLIENT_ID,
      client_secret: FAKE_CLIENT_SECRET,
      grant_type: "authorization_code",
      redirect_uri: redirect,
    });
    // PKCE: the challenge in the address is the hash of the verifier sent to the token endpoint.
    const challenge = createHash("sha256")
      .update(form?.code_verifier ?? "")
      .digest("base64url");
    expect(url.searchParams.get("code_challenge")).toBe(challenge);

    const text = term.output.join("");
    expect(text).toContain(`GOOGLE_REFRESH_TOKEN=${REFRESH_TOKEN}`);
    expect(text).not.toContain(FAKE_CLIENT_SECRET);
    expect(term.asked.map((a) => a.prompt)).toEqual(["Pasted address (or wait): "]);
  });

  it("takes the address the owner pastes when the browser is on another computer", async () => {
    const token = tokenEndpoint(good);
    const term = terminal({
      answers: [
        (url) =>
          `http://127.0.0.1:1/?state=${url.searchParams.get("state")}&code=PASTED-CODE&scope=calendar`,
      ],
    });
    expect(await runGoogleAuth({ env, io: term.io, fetch: token.fetch })).toBe(0);
    expect(token.requests[0]?.form.code).toBe("PASTED-CODE");
    // The redirect URI sent to Google is the one in the address the owner opened, not the pasted one.
    expect(token.requests[0]?.form.redirect_uri).toBe(
      term.authUrl().searchParams.get("redirect_uri"),
    );
    expect(term.output.join("")).toContain(`GOOGLE_REFRESH_TOKEN=${REFRESH_TOKEN}`);
  });

  it("asks for the client id and secret when the environment has none, without showing the secret", async () => {
    const token = tokenEndpoint(good);
    const term = terminal({
      answers: [
        FAKE_CLIENT_ID,
        FAKE_CLIENT_SECRET,
        (url) => `http://127.0.0.1:1/?state=${url.searchParams.get("state")}&code=C`,
      ],
    });
    expect(await runGoogleAuth({ env: {}, io: term.io, fetch: token.fetch })).toBe(0);
    expect(term.asked.map((a) => a.secret)).toEqual([false, true, false]);
    expect(token.requests[0]?.form).toMatchObject({
      client_id: FAKE_CLIENT_ID,
      client_secret: FAKE_CLIENT_SECRET,
    });
    expect(term.output.join("")).not.toContain(FAKE_CLIENT_SECRET);
  });

  it("refuses an answer from another run, and Google's refusal, without calling the token endpoint", async () => {
    const token = tokenEndpoint(good);
    const wrongState = terminal({ answers: ["http://127.0.0.1:1/?state=forged&code=C"] });
    expect(await runGoogleAuth({ env, io: wrongState.io, fetch: token.fetch })).toBe(1);
    expect(wrongState.output.join("")).toContain("did not come from this run");

    const denied = terminal({ answers: ["http://127.0.0.1:1/?error=access_denied"] });
    expect(await runGoogleAuth({ env, io: denied.io, fetch: token.fetch })).toBe(1);
    expect(denied.output.join("")).toContain("access_denied");
    expect(token.requests).toHaveLength(0);
  });

  it("explains an answer with no refresh token, and a code Google refuses, without printing a secret", async () => {
    const paste = (url: URL) => `http://127.0.0.1:1/?state=${url.searchParams.get("state")}&code=C`;
    const none = terminal({ answers: [paste] });
    expect(
      await runGoogleAuth({
        env,
        io: none.io,
        fetch: tokenEndpoint({ body: { access_token: "a" } }).fetch,
      }),
    ).toBe(1);
    expect(none.output.join("")).toContain("no refresh token");

    const refused = terminal({ answers: [paste] });
    expect(
      await runGoogleAuth({
        env,
        io: refused.io,
        fetch: tokenEndpoint({
          status: 400,
          body: { error: "invalid_grant", error_description: "Bad Request" },
        }).fetch,
      }),
    ).toBe(1);
    const text = refused.output.join("");
    expect(text).toContain("invalid_grant");
    expect(text).not.toContain(FAKE_CLIENT_SECRET);
    expect(text).not.toContain("GOOGLE_REFRESH_TOKEN=");
  });

  it("gives up when nothing comes back in time", async () => {
    const term = terminal({});
    expect(await runGoogleAuth({ env, io: term.io, timeoutMs: 30 })).toBe(1);
    expect(term.output.join("")).toContain("Nothing came back in time");
  });

  it("asks for nothing it cannot use: a missing client id or secret ends the run", async () => {
    const term = terminal({ answers: ["", ""] });
    expect(await runGoogleAuth({ env: {}, io: term.io })).toBe(1);
    expect(term.output.join("")).toContain("client ID and a client secret");
  });

  it("writes no file: the token goes to the terminal only", () => {
    const source = readFileSync(new URL("../src/gcal/auth-cli.ts", import.meta.url), "utf8");
    expect(source).not.toMatch(/node:fs|writeFile|appendFile|createWriteStream/);
  });
});

describe("parseRedirect", () => {
  it("reads the code and state from an address, a bare code, and refusals", () => {
    expect(parseRedirect("http://127.0.0.1:8085/?code=abc&state=xyz&scope=s")).toEqual({
      code: "abc",
      state: "xyz",
    });
    expect(parseRedirect("  4/0Aabc-def  ")).toEqual({ code: "4/0Aabc-def", state: null });
    expect(parseRedirect("http://127.0.0.1:8085/?error=access_denied")).toEqual({
      error: "Google answered: access_denied",
    });
    expect(parseRedirect("http://127.0.0.1:8085/")).toEqual({
      error: "the address has no code in it",
    });
    expect(parseRedirect("   ")).toEqual({ error: "nothing was pasted" });
  });
});

describe("exchangeCode", () => {
  it("says the token endpoint could not be reached without naming the address", async () => {
    const result = await exchangeCode({
      tokenUrl: "http://127.0.0.1:1/token",
      clientId: "id",
      clientSecret: "SECRET-VALUE",
      code: "c",
      verifier: "v",
      redirectUri: "http://127.0.0.1:2",
      fetch: (async () => {
        throw new Error("connect ECONNREFUSED SECRET-VALUE");
      }) as typeof fetch,
    });
    expect(result).toEqual({ error: "Google's token endpoint could not be reached" });
  });
});
