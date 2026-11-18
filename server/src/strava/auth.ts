import { z } from "zod";
import type { Db } from "../db.ts";
import { sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import { describeBody, describeFailure, StravaError } from "./errors.ts";
import { readRateInfo } from "./rate-limit.ts";

/**
 * Strava's OAuth for a single owner (ADR 0020): the refresh token is exchanged for an access token
 * that lives six hours and is kept in memory only. Strava rotates the refresh token (a refresh may
 * answer with a new one, and the old one stops working at once), so the newest is kept in the
 * `strava_auth` table; the environment's `STRAVA_REFRESH_TOKEN` seeds the first run, and a changed
 * value there (the owner ran `strava-auth` again) replaces the table's. No token, secret or code
 * reaches a log line, an error message or a URL: the credentials travel in a form body.
 */

export interface StoredRefreshToken {
  refreshToken: string;
  /** SHA-256 of the environment value this row was seeded from. */
  seedSha256: string;
}

export interface RefreshTokenStore {
  load(): Promise<StoredRefreshToken | null>;
  save(token: StoredRefreshToken, at: Date): Promise<void>;
}

export function createRefreshTokenStore(db: Db): RefreshTokenStore {
  return {
    async load() {
      const result = await db.query<{ refresh_token: string; seed_sha256: string }>(
        "SELECT refresh_token, seed_sha256 FROM strava_auth WHERE id",
      );
      const row = result.rows[0];
      return row === undefined
        ? null
        : { refreshToken: row.refresh_token, seedSha256: row.seed_sha256 };
    },
    async save(token, at) {
      await db.query(
        `INSERT INTO strava_auth (id, refresh_token, seed_sha256, updated_at)
         VALUES (true, $1, $2, $3::timestamptz)
         ON CONFLICT (id) DO UPDATE
           SET refresh_token = EXCLUDED.refresh_token,
               seed_sha256 = EXCLUDED.seed_sha256,
               updated_at = EXCLUDED.updated_at`,
        [token.refreshToken, token.seedSha256, at.toISOString()],
      );
    },
  };
}

export interface TokenManager {
  /** A usable access token: the one in memory while it has time left, otherwise a fresh one. */
  accessToken(): Promise<string>;
  /** Gets a new access token whatever the one in memory says (after a 401). Calls at once share one request. */
  refresh(): Promise<string>;
}

export interface TokenManagerOptions {
  store: RefreshTokenStore;
  clientId: string;
  clientSecret: string;
  /** The environment's `STRAVA_REFRESH_TOKEN`. */
  seedRefreshToken: string;
  /** `https://www.strava.com` or the fake of the tests. */
  baseUrl: string;
  now?: () => Date;
  fetch?: typeof fetch;
  timeoutMs?: number;
  log: Logger;
}

/** An access token is renewed this long before it expires. */
const EXPIRY_MARGIN_MS = 5 * 60_000;
const DEFAULT_TIMEOUT_MS = 20_000;

const tokenResponse = z.looseObject({
  access_token: z.string().min(1),
  refresh_token: z.string().min(1),
  // Epoch seconds. The `athlete` object that Strava may add to a token response is not in this
  // schema: it is dropped unread (Strava is write-only here, ADR 0020).
  expires_at: z.number().optional(),
  expires_in: z.number().optional(),
});

export function createTokenManager(options: TokenManagerOptions): TokenManager {
  const { store, clientId, clientSecret, seedRefreshToken, log } = options;
  const now = options.now ?? (() => new Date());
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const endpoint = `${options.baseUrl.replace(/\/+$/, "")}/oauth/token`;
  const seedSha256 = sha256Hex(seedRefreshToken);

  let cached: { token: string; expiresAtMs: number } | null = null;
  /** The newest refresh token; null until the first use reads the table. */
  let refreshToken: string | null = null;
  /** The refresh token the table holds, as far as this process knows. */
  let persisted: string | null = null;
  let inflight: Promise<string> | null = null;

  async function currentRefreshToken(): Promise<string> {
    if (refreshToken !== null) return refreshToken;
    const row = await store.load();
    if (row !== null && row.seedSha256 === seedSha256) {
      // The usual case after the first run: the rotated token is the one that works.
      refreshToken = row.refreshToken;
      persisted = row.refreshToken;
    } else {
      // The first run, or the owner has put another value in the environment: it wins.
      if (row !== null) {
        log.info(
          {},
          "STRAVA_REFRESH_TOKEN differs from the one this database was seeded with: using the environment's value",
        );
      }
      refreshToken = seedRefreshToken;
    }
    return refreshToken;
  }

  async function requestTokens(token: string): Promise<z.infer<typeof tokenResponse>> {
    let response: Response;
    try {
      response = await doFetch(endpoint, {
        method: "POST",
        headers: {
          accept: "application/json",
          "content-type": "application/x-www-form-urlencoded",
        },
        body: new URLSearchParams({
          client_id: clientId,
          client_secret: clientSecret,
          grant_type: "refresh_token",
          refresh_token: token,
        }),
        redirect: "error",
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (cause) {
      throw new StravaError("unreachable", `Strava did not answer (${describeFailure(cause)})`);
    }
    const text = await response.text().catch(() => "");
    const status = response.status;
    const rate = readRateInfo(response.headers);
    if (status >= 200 && status < 300) {
      let body: unknown;
      try {
        body = JSON.parse(text);
      } catch {
        body = null;
      }
      const parsed = tokenResponse.safeParse(body);
      if (!parsed.success) {
        throw new StravaError(
          "bad-response",
          "Strava answered the token request with no usable token",
          {
            status,
          },
        );
      }
      return parsed.data;
    }
    const detail = describeBody(text);
    if (status === 429) {
      throw new StravaError("rate-limited", "Strava is limiting requests (HTTP 429)", {
        status,
        detail,
        retryAfter: response.headers.get("retry-after"),
        rate,
      });
    }
    if (status >= 500 || status === 408) {
      throw new StravaError(
        "server",
        `Strava failed to answer the token request (HTTP ${status})`,
        {
          status,
          detail,
          rate,
        },
      );
    }
    if (status === 400 || status === 401 || status === 403) {
      throw new StravaError(
        "auth-failed",
        `Strava refused the refresh token or the client id and secret (HTTP ${status}). Run strava-auth again, put the new STRAVA_REFRESH_TOKEN in .env and restart the server`,
        { status, detail },
      );
    }
    throw new StravaError(
      "rejected",
      `Strava refused the token request (HTTP ${status}${detail === "" ? "" : `: ${detail}`})`,
      { status, detail },
    );
  }

  async function renew(): Promise<string> {
    const token = await currentRefreshToken();
    const issued = await requestTokens(token);
    const at = now();
    const expiresAtMs =
      issued.expires_at !== undefined
        ? issued.expires_at * 1000
        : at.getTime() + (issued.expires_in ?? 6 * 3600) * 1000;
    cached = { token: issued.access_token, expiresAtMs };
    // The old refresh token may be dead already: keep the new one in memory first, then try to keep it.
    refreshToken = issued.refresh_token;
    if (persisted !== issued.refresh_token) {
      try {
        await store.save({ refreshToken: issued.refresh_token, seedSha256 }, at);
        persisted = issued.refresh_token;
      } catch (error) {
        // Not fatal now (the token works from memory) but a restart would use a dead one: say so, every time.
        // The error object is not logged: a database error can quote the row it refused, token included.
        const code = (error as { code?: unknown }).code;
        log.error(
          { databaseError: typeof code === "string" ? code : "unknown" },
          "could not store the rotated Strava refresh token; if the server restarts before the next refresh, run strava-auth again",
        );
      }
    }
    return issued.access_token;
  }

  function refresh(): Promise<string> {
    inflight ??= renew().finally(() => {
      inflight = null;
    });
    return inflight;
  }

  return {
    async accessToken() {
      if (cached !== null && cached.expiresAtMs - now().getTime() > EXPIRY_MARGIN_MS) {
        return cached.token;
      }
      return refresh();
    },
    refresh() {
      cached = null;
      return refresh();
    },
  };
}
