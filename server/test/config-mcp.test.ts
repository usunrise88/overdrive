import { describe, expect, it } from "vitest";
import { describeConfigError, loadConfig, MIN_TOKEN_LENGTH } from "../src/config.ts";

const DATABASE_URL = "postgres://overdrive:secret@db:5432/overdrive";
const token = (seed: string) => seed.repeat(MIN_TOKEN_LENGTH).slice(0, MIN_TOKEN_LENGTH);
const hash = `scrypt:1024:8:1:c2FsdHNhbHQ:${Buffer.alloc(32, 7).toString("base64url")}`;

describe("the MCP settings (ADR 0018)", () => {
  it("leaves the MCP server off by default, and blank values count as unset", () => {
    const config = loadConfig({ DATABASE_URL });
    expect(config.PUBLIC_BASE_URL).toBeUndefined();
    expect(config.OWNER_PASSPHRASE_HASH).toBeUndefined();
    expect(config.MCP_STATIC_TOKENS).toEqual([]);
    // compose passes `${VAR:-}`
    const blank = loadConfig({
      DATABASE_URL,
      PUBLIC_BASE_URL: "",
      OWNER_PASSPHRASE_HASH: "  ",
      MCP_STATIC_TOKENS: "",
    });
    expect(blank.PUBLIC_BASE_URL).toBeUndefined();
    expect(blank.OWNER_PASSPHRASE_HASH).toBeUndefined();
  });

  it("reads the address as an origin and keeps the hash as written", () => {
    const config = loadConfig({
      DATABASE_URL,
      PUBLIC_BASE_URL: " https://overdrive.example.com/ ",
      OWNER_PASSPHRASE_HASH: hash,
      MCP_STATIC_TOKENS: `claude:${token("m")}`,
    });
    expect(config.PUBLIC_BASE_URL).toBe("https://overdrive.example.com");
    expect(config.OWNER_PASSPHRASE_HASH).toBe(hash);
    expect(config.MCP_STATIC_TOKENS).toEqual([{ name: "claude", token: token("m") }]);
  });

  it("allows http only on this machine", () => {
    expect(
      loadConfig({ DATABASE_URL, PUBLIC_BASE_URL: "http://127.0.0.1:8080" }).PUBLIC_BASE_URL,
    ).toBe("http://127.0.0.1:8080");
    expect(
      loadConfig({ DATABASE_URL, PUBLIC_BASE_URL: "http://localhost:8080" }).PUBLIC_BASE_URL,
    ).toBe("http://localhost:8080");
  });

  it.each([
    ["no scheme", "overdrive.example.com"],
    ["plain http on the internet", "http://overdrive.example.com"],
    ["a path", "https://overdrive.example.com/overdrive"],
    ["credentials", "https://user:hunter2@overdrive.example.com"],
    ["a query", "https://overdrive.example.com/?a=b"],
    ["a fragment", "https://overdrive.example.com/#x"],
  ])("rejects a PUBLIC_BASE_URL with %s, without repeating it", (_what, value) => {
    let text = "";
    try {
      loadConfig({ DATABASE_URL, PUBLIC_BASE_URL: value });
    } catch (err) {
      text = describeConfigError(err);
    }
    expect(text).toContain("PUBLIC_BASE_URL");
    expect(text).not.toContain("hunter2");
  });

  it.each([
    ["not a hash at all", "my-passphrase-in-plain-text"],
    ["a hash of another kind", "bcrypt:10:abc:def:ghi:jkl"],
    [
      "a cost that is not a power of two",
      `scrypt:1000:8:1:c2FsdHNhbHQ:${Buffer.alloc(32).toString("base64url")}`,
    ],
    [
      "a cost that would exhaust the memory",
      `scrypt:${2 ** 25}:8:1:c2FsdHNhbHQ:${Buffer.alloc(32).toString("base64url")}`,
    ],
    ["a hash of the wrong length", "scrypt:1024:8:1:c2FsdHNhbHQ:AAAA"],
  ])("rejects an OWNER_PASSPHRASE_HASH that is %s, never printing it", (_what, value) => {
    let text = "";
    try {
      loadConfig({ DATABASE_URL, OWNER_PASSPHRASE_HASH: value });
    } catch (err) {
      text = describeConfigError(err);
    }
    expect(text).toContain("OWNER_PASSPHRASE_HASH");
    expect(text).not.toContain(value);
  });

  it("names MCP_STATIC_TOKENS, not DEVICE_TOKENS, when one of them is short", () => {
    let text = "";
    try {
      loadConfig({ DATABASE_URL, MCP_STATIC_TOKENS: "claude:short" });
    } catch (err) {
      text = describeConfigError(err);
    }
    expect(text).toMatch(/MCP_STATIC_TOKENS entry 1 \(claude\)/);
    expect(text).not.toContain("DEVICE_TOKENS");
  });
});
