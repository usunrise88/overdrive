import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { coachConfigShape } from "../src/config-coach.ts";
import { insightsConfigShape } from "../src/config-insights.ts";
import { integrationsShape } from "../src/config-integrations.ts";
import { liveCoachConfigShape } from "../src/config-live-coach.ts";
import { routesConfigShape } from "../src/config-routes.ts";
import { stravaConfigShape } from "../src/config-strava.ts";
import { wellnessConfigShape } from "../src/config-wellness.ts";
import {
  IMPORT_ONLY_KEYS,
  INSTALLATION_KEYS,
  keyKind,
  NOT_IN_CONFIG_YET,
  PER_USER_KEYS,
} from "../src/tenancy/keys.ts";

const SRC = fileURLToPath(new URL("../src/", import.meta.url));

/** The keys of the configuration code: the zod shapes of the config-*.ts files, and the schema written out in config.ts. */
function configKeys(): string[] {
  const fromShapes = [
    stravaConfigShape,
    wellnessConfigShape,
    insightsConfigShape,
    coachConfigShape,
    routesConfigShape,
    liveCoachConfigShape,
    integrationsShape,
  ].flatMap((shape) => Object.keys(shape));
  const source = readFileSync(join(SRC, "config.ts"), "utf8");
  const start = source.indexOf("const schema = z.object({");
  const end = source.indexOf("\n});", start);
  const body = source.slice(start, end);
  const own = [...body.matchAll(/^ {2}([A-Z][A-Z0-9_]*): /gm)].map((m) => m[1] as string);
  return [...fromShapes, ...own];
}

describe("the environment keys (T5)", () => {
  it("finds the configuration's keys", () => {
    const keys = configKeys();
    expect(keys.length).toBeGreaterThan(70);
    for (const known of ["PORT", "DATABASE_URL", "TIMEZONE", "STRAVA_CLIENT_ID", "TTS_VOICE"]) {
      expect(keys).toContain(known);
    }
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("puts every key of the configuration code in exactly one list", () => {
    const loose: string[] = [];
    for (const key of configKeys()) {
      const lists = [INSTALLATION_KEYS, PER_USER_KEYS, IMPORT_ONLY_KEYS].filter((l) =>
        l.includes(key),
      );
      if (lists.length !== 1) loose.push(`${key}: in ${lists.length} lists`);
    }
    expect(loose).toEqual([]);
  });

  it("lists no key twice and no key that the code does not know, except the two that are not read yet", () => {
    const all = [...INSTALLATION_KEYS, ...PER_USER_KEYS, ...IMPORT_ONLY_KEYS];
    expect(new Set(all).size).toBe(all.length);
    const known = new Set(configKeys());
    expect(all.filter((k) => !known.has(k)).sort()).toEqual([...NOT_IN_CONFIG_YET].sort());
  });

  it("agrees with the brief on the owner's secrets", () => {
    for (const key of [
      "INTERVALS_API_KEY",
      "GOOGLE_REFRESH_TOKEN",
      "STRAVA_CLIENT_SECRET",
      "STRAVA_REFRESH_TOKEN",
      "TIMEZONE",
    ]) {
      expect(keyKind(key)).toBe("per-user");
    }
    for (const key of ["LLM_API_KEY", "TELEGRAM_BOT_TOKEN", "DATABASE_URL", "TTS_API_KEY"]) {
      expect(keyKind(key)).toBe("installation");
    }
    for (const key of ["DEVICE_TOKENS", "MCP_STATIC_TOKENS", "OWNER_PASSPHRASE_HASH"]) {
      expect(keyKind(key)).toBe("import-only");
    }
    expect(keyKind("NO_SUCH_KEY")).toBeNull();
  });

  it("covers every environment key that code outside the configuration reads", () => {
    // The two command-line tools read a few keys of their own; those are not the service's.
    const tools = new Set(["GOOGLE_AUTH_PORT", "OWNER_PASSPHRASE"]);
    const found = new Set<string>();
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) {
          if (name !== "tenancy") walk(path);
        } else if (name.endsWith(".ts")) {
          for (const m of readFileSync(path, "utf8").matchAll(
            /\b(?:env|process\.env)\.([A-Z][A-Z0-9_]{3,})/g,
          )) {
            found.add(m[1] as string);
          }
        }
      }
    };
    walk(SRC);
    expect([...found].filter((k) => keyKind(k) === null && !tools.has(k))).toEqual([]);
  });
});
