import { execFileSync, spawnSync } from "node:child_process";
import { describe, expect, it } from "vitest";
import {
  DEFAULT_SCRYPT,
  hashPassphrase,
  parsePassphraseHash,
  serialVerifier,
  verifyPassphrase,
} from "../src/oauth/passphrase.ts";
import { redirectUriMatches, redirectUriProblem } from "../src/oauth/redirect.ts";
import { createLoginLockout, createRateLimiter, DEFAULT_LOCKOUT } from "../src/oauth/throttle.ts";

const CHEAP = { N: 1024, r: 8, p: 1 };

describe("the passphrase hash", () => {
  it("verifies the passphrase it was made from and no other", async () => {
    const stored = parsePassphraseHash(await hashPassphrase("correct horse battery staple", CHEAP));
    expect(await verifyPassphrase("correct horse battery staple", stored)).toBe(true);
    expect(await verifyPassphrase("correct horse battery stapl", stored)).toBe(false);
    expect(await verifyPassphrase("", stored)).toBe(false);
    expect(await verifyPassphrase("Correct horse battery staple", stored)).toBe(false);
  });

  it("is written with colons, never a dollar sign, which docker compose would read as a variable", async () => {
    const text = await hashPassphrase("correct horse battery staple", CHEAP);
    expect(text).toMatch(/^scrypt:1024:8:1:[A-Za-z0-9_-]{22}:[A-Za-z0-9_-]{43}$/);
    expect(text).not.toContain("$");
    expect(text).not.toContain("correct");
  });

  it("uses a new salt every time", async () => {
    const [a, b] = await Promise.all([
      hashPassphrase("same words, twice", CHEAP),
      hashPassphrase("same words, twice", CHEAP),
    ]);
    expect(a).not.toBe(b);
  });

  it("makes its default 32 MiB of work", async () => {
    expect(DEFAULT_SCRYPT).toEqual({ N: 32768, r: 8, p: 1 });
    expect(
      (await hashPassphrase("some long passphrase", DEFAULT_SCRYPT)).startsWith(
        "scrypt:32768:8:1:",
      ),
    ).toBe(true);
  });

  it("reads one passphrase typed another way as the same: NFKC", async () => {
    // U+FB01 is the ligature "fi"; NFKC folds it to the two letters.
    const stored = parsePassphraseHash(await hashPassphrase("ﬁrst of many words", CHEAP));
    expect(await verifyPassphrase("first of many words", stored)).toBe(true);
  });

  it("keeps the parameters with the hash, so the cost can be raised later", async () => {
    const stored = parsePassphraseHash(
      await hashPassphrase("a passphrase of words", { N: 2048, r: 8, p: 2 }),
    );
    expect(stored).toMatchObject({ N: 2048, r: 8, p: 2 });
    expect(await verifyPassphrase("a passphrase of words", stored)).toBe(true);
  });

  it.each([
    ["too few parts", "scrypt:1024:8:1:abcdefghijk"],
    ["another algorithm", `bcrypt:1024:8:1:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["a cost that is not a power of two", `scrypt:1000:8:1:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["a cost over the limit", `scrypt:${2 ** 21}:8:1:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["a cost under the floor", `scrypt:512:8:1:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["a block size of zero", `scrypt:1024:0:1:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["too much parallelism", `scrypt:1024:8:17:c2FsdHNhbHQ:${"A".repeat(43)}`],
    ["a salt that is too short", `scrypt:1024:8:1:AAA:${"A".repeat(43)}`],
    ["a hash of the wrong length", "scrypt:1024:8:1:c2FsdHNhbHQ:AAAA"],
  ])("refuses a stored hash with %s, in words that do not repeat it", (_what, text) => {
    let message = "";
    try {
      parsePassphraseHash(text);
    } catch (error) {
      message = String(error);
    }
    expect(message).not.toBe("");
    expect(message).not.toContain("c2FsdHNhbHQ");
  });

  it("verifies one passphrase at a time and still answers each correctly", async () => {
    const stored = parsePassphraseHash(await hashPassphrase("the right words", CHEAP));
    const verify = serialVerifier(stored);
    const answers = await Promise.all([
      verify("the right words"),
      verify("the wrong words"),
      verify("the right words"),
    ]);
    expect(answers).toEqual([true, false, true]);
  });
});

describe("hash-passphrase", () => {
  const script = new URL("../src/hash-passphrase.ts", import.meta.url).pathname;

  it("reads the passphrase from stdin and prints the line for .env, nothing else", async () => {
    const output = execFileSync(process.execPath, [script], {
      input: "a passphrase made of words\r\n",
      encoding: "utf8",
    });
    expect(output).toMatch(
      /^OWNER_PASSPHRASE_HASH=scrypt:32768:8:1:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+\n$/,
    );
    expect(output).not.toContain("passphrase made");
    const stored = parsePassphraseHash(output.trim().replace("OWNER_PASSPHRASE_HASH=", ""));
    // The trailing newline of the pipe is not part of the passphrase.
    expect(await verifyPassphrase("a passphrase made of words", stored)).toBe(true);
  });

  it("refuses a passphrase that is too short and prints no hash", () => {
    const result = spawnSync(process.execPath, [script], { input: "short\n", encoding: "utf8" });
    expect(result.status).toBe(1);
    expect(result.stdout).toBe("");
    expect(result.stderr).toMatch(/at least 12 characters/);
    expect(result.stderr).not.toContain("short\n");
  });
});

describe("redirect addresses", () => {
  it.each([
    "https://claude.ai/api/mcp/auth_callback",
    "https://example.com/cb?x=1",
    "http://localhost:3000/callback",
    "http://127.0.0.1:43111/callback",
    "http://[::1]:5000/cb",
  ])("accepts %s", (uri) => {
    expect(redirectUriProblem(uri)).toBeNull();
  });

  it.each([
    ["not an address", "callback"],
    ["plain http on the internet", "http://example.com/cb"],
    ["a custom scheme", "cursor://anysphere.cursor-mcp/oauth/callback"],
    ["javascript", "javascript:alert(1)"],
    ["a fragment", "https://example.com/cb#x"],
    ["credentials", "https://user:pw@example.com/cb"],
    ["something very long", `https://example.com/${"a".repeat(2100)}`],
  ])("refuses %s", (_what, uri) => {
    expect(redirectUriProblem(uri)).not.toBeNull();
  });

  it("matches exactly, except for the port of a loopback address", () => {
    expect(redirectUriMatches("https://a.example/cb", "https://a.example/cb")).toBe(true);
    expect(redirectUriMatches("https://a.example/cb", "https://a.example/cb2")).toBe(false);
    expect(redirectUriMatches("https://a.example/cb", "https://a.example:8443/cb")).toBe(false);
    expect(redirectUriMatches("http://127.0.0.1:1111/cb", "http://127.0.0.1:2222/cb")).toBe(true);
    expect(redirectUriMatches("http://localhost/cb", "http://localhost:5123/cb")).toBe(true);
    expect(redirectUriMatches("http://127.0.0.1:1111/cb", "http://127.0.0.1:2222/other")).toBe(
      false,
    );
    expect(redirectUriMatches("http://127.0.0.1:1111/cb", "http://localhost:1111/cb")).toBe(false);
    // The port is only free for loopback, not for any http address.
    expect(redirectUriMatches("https://a.example/cb", "http://a.example/cb")).toBe(false);
    expect(redirectUriMatches("https://a.example/cb?x=1", "https://a.example/cb?x=2")).toBe(false);
  });
});

describe("the passphrase lockout", () => {
  const at = (minutes: number) => new Date(Date.UTC(2026, 10, 18, 8, 0) + minutes * 60_000);

  it("locks after five wrong passphrases in a row and counts down the tries", () => {
    const lockout = createLoginLockout();
    expect(lockout.state(at(0))).toEqual({ locked: false, failuresLeft: 5 });
    expect(lockout.failed(at(0))).toEqual({ locked: false, failuresLeft: 4 });
    for (let i = 0; i < 3; i++) lockout.failed(at(0));
    expect(lockout.failed(at(0))).toEqual({ locked: true, retryAfterSeconds: 15 * 60 });
    expect(lockout.state(at(5))).toEqual({ locked: true, retryAfterSeconds: 10 * 60 });
    expect(lockout.state(at(15))).toEqual({ locked: false, failuresLeft: 5 });
  });

  it("clears at a right passphrase", () => {
    const lockout = createLoginLockout();
    for (let i = 0; i < 4; i++) lockout.failed(at(0));
    lockout.succeeded();
    expect(lockout.state(at(0))).toEqual({ locked: false, failuresLeft: 5 });
    for (let i = 0; i < 4; i++) lockout.failed(at(0));
    expect(lockout.state(at(0))).toEqual({ locked: false, failuresLeft: 1 });
  });

  it("doubles each lock and stops at a day", () => {
    const lockout = createLoginLockout({
      maxFailures: 1,
      firstLockMs: 60_000,
      maxLockMs: 5 * 60_000,
    });
    const seconds: number[] = [];
    let now = 0;
    for (let i = 0; i < 5; i++) {
      const state = lockout.failed(at(now));
      if (!state.locked) throw new Error("should be locked");
      seconds.push(state.retryAfterSeconds);
      now += state.retryAfterSeconds / 60;
    }
    expect(seconds).toEqual([60, 120, 240, 300, 300]);
    expect(DEFAULT_LOCKOUT).toEqual({
      maxFailures: 5,
      firstLockMs: 15 * 60_000,
      maxLockMs: 24 * 3_600_000,
    });
  });
});

describe("the rate limiter", () => {
  it("lets through the limit in a window and then the next window", () => {
    const limiter = createRateLimiter(3, 60_000);
    const t = (s: number) => new Date(s * 1000);
    expect([0, 1, 2, 3].map((s) => limiter.take(t(s)))).toEqual([true, true, true, false]);
    expect(limiter.take(t(59))).toBe(false);
    // The first one has aged out of the window.
    expect(limiter.take(t(60))).toBe(true);
    expect(limiter.take(t(60))).toBe(false);
  });
});
