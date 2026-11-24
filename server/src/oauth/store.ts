import { randomBytes } from "node:crypto";
import type { Db, Queryable } from "../db.ts";
import { sha256Hex } from "../hash.ts";

/**
 * The OAuth server's storage (ADR 0018): registered clients, authorization codes and tokens in PostgreSQL,
 * in the `shared` schema (the store takes the `shared` Db). `oauth_families` says which user a family belongs to. Codes, tokens and client secrets are 256-bit random values, kept only as
 * SHA-256 digests; the plain value exists once, in the answer that issues it. Every comparison with
 * time takes `now` from the caller, so the tests move the clock instead of waiting.
 */

export type ClientAuthMethod = "none" | "client_secret_basic" | "client_secret_post";

export interface OAuthClient {
  clientId: string;
  /** Digest of the secret; null for a public client. */
  secretSha256: string | null;
  clientName: string;
  redirectUris: string[];
  authMethod: ClientAuthMethod;
}

export interface NewClient {
  clientName: string;
  redirectUris: string[];
  authMethod: ClientAuthMethod;
}

export interface CodeRecord {
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  scope: string;
  resource: string;
  familyId: string;
  expired: boolean;
  consumed: boolean;
}

export interface TokenRecord {
  kind: "access" | "refresh";
  familyId: string;
  clientId: string;
  scope: string;
  resource: string;
  expired: boolean;
  /** The user the token's family belongs to (an enabled one: the lookup joins `oauth_families` and `users`). */
  userId: number;
  /** The refresh token has been exchanged for the next pair. */
  used: boolean;
  /** It was exchanged only moments ago: a second request racing the first, not a replay. */
  usedJustNow: boolean;
  revoked: boolean;
}

export interface IssuedTokens {
  accessToken: string;
  refreshToken: string;
  /** Seconds the access token lives. */
  expiresIn: number;
  scope: string;
  resource: string;
  clientId: string;
  familyId: string;
}

/** What the access token means to the MCP endpoint. */
export interface AccessGrant {
  /** The user the grant was approved by. */
  userId: number;
  clientId: string;
  scope: string;
  resource: string;
}

export interface Lifetimes {
  codeSeconds: number;
  accessSeconds: number;
  refreshSeconds: number;
  /**
   * A refresh token that was just exchanged can be exchanged again for this long. A client that
   * finds its access token expired often has several requests in flight, and each one refreshes
   * with the same token: they must not be taken for a replay (the MCP SDK's client does this).
   */
  refreshReuseGraceSeconds: number;
}

export const DEFAULT_LIFETIMES: Lifetimes = {
  codeSeconds: 5 * 60,
  accessSeconds: 60 * 60,
  refreshSeconds: 30 * 24 * 3600,
  refreshReuseGraceSeconds: 15,
};

/** Clients that registered and were never used are dropped after this long. */
const UNUSED_CLIENT_SECONDS = 24 * 3600;
/** At most this many registered clients wait for the owner to authorise them. */
export const MAX_PENDING_CLIENTS = 100;

export class TooManyClientsError extends Error {
  constructor() {
    super("too many clients are waiting to be authorised");
    this.name = "TooManyClientsError";
  }
}

const random = (prefix: string): string => `${prefix}${randomBytes(32).toString("base64url")}`;
const iso = (date: Date): string => date.toISOString();
const later = (now: Date, seconds: number): string => iso(new Date(now.getTime() + seconds * 1000));

interface ClientRow {
  client_id: string;
  client_secret_sha256: string | null;
  client_name: string;
  redirect_uris: string[];
  token_endpoint_auth_method: ClientAuthMethod;
}

const toClient = (row: ClientRow): OAuthClient => ({
  clientId: row.client_id,
  secretSha256: row.client_secret_sha256,
  clientName: row.client_name,
  redirectUris: row.redirect_uris,
  authMethod: row.token_endpoint_auth_method,
});

export interface OAuthStore {
  /** Registers a client; the secret (for a confidential one) is returned once. */
  registerClient(
    input: NewClient,
    now: Date,
  ): Promise<{ client: OAuthClient; clientSecret: string | null }>;
  getClient(clientId: string): Promise<OAuthClient | null>;
  markAuthorized(clientId: string, now: Date): Promise<void>;
  /** Makes a single-use code for an authorisation the user just approved; its family belongs to that user. */
  createCode(
    input: {
      userId: number;
      clientId: string;
      redirectUri: string;
      codeChallenge: string;
      scope: string;
      resource: string;
    },
    now: Date,
  ): Promise<string>;
  findCode(code: string, now: Date): Promise<CodeRecord | null>;
  /** Uses the code up without issuing anything (it failed a check): it cannot be tried again. */
  burnCode(code: string, now: Date): Promise<void>;
  /** Uses the code up and issues the first tokens of its family, in one step; null if another request was first. */
  exchangeCode(code: string, now: Date): Promise<IssuedTokens | null>;
  findRefreshToken(token: string, now: Date): Promise<TokenRecord | null>;
  /** Marks the refresh token used and issues the next pair of its family, in one step; null if another request was first. */
  rotateRefreshToken(token: string, now: Date): Promise<IssuedTokens | null>;
  /** The grant behind an access token, or null when it is unknown, expired or revoked. */
  findAccessToken(token: string, now: Date): Promise<AccessGrant | null>;
  revokeFamily(familyId: string, now: Date): Promise<void>;
  /** Revokes a token the client holds (a refresh token takes its family with it). Quiet when it is not found. */
  revokeToken(token: string, clientId: string, now: Date): Promise<void>;
  /** Forgets what has expired for good. */
  purge(now: Date): Promise<void>;
}

export function createOAuthStore(db: Db, lifetimes: Lifetimes = DEFAULT_LIFETIMES): OAuthStore {
  async function insertTokens(
    tx: Queryable,
    grant: { clientId: string; scope: string; resource: string; familyId: string },
    now: Date,
  ): Promise<IssuedTokens> {
    const accessToken = random("odat_");
    const refreshToken = random("odrt_");
    const insert = `INSERT INTO oauth_tokens
      (token_sha256, kind, family_id, client_id, scope, resource, expires_at, created_at)
      VALUES ($1, $2, $3, $4, $5, $6, $7::timestamptz, $8::timestamptz)`;
    const common = [grant.familyId, grant.clientId, grant.scope, grant.resource] as const;
    await tx.query(insert, [
      sha256Hex(accessToken),
      "access",
      ...common,
      later(now, lifetimes.accessSeconds),
      iso(now),
    ]);
    await tx.query(insert, [
      sha256Hex(refreshToken),
      "refresh",
      ...common,
      later(now, lifetimes.refreshSeconds),
      iso(now),
    ]);
    return {
      accessToken,
      refreshToken,
      expiresIn: lifetimes.accessSeconds,
      scope: grant.scope,
      resource: grant.resource,
      clientId: grant.clientId,
      familyId: grant.familyId,
    };
  }

  async function revokeFamily(familyId: string, now: Date): Promise<void> {
    await db.query(
      `UPDATE oauth_tokens SET revoked_at = $2::timestamptz
        WHERE family_id = $1 AND revoked_at IS NULL`,
      [familyId, iso(now)],
    );
  }

  return {
    async registerClient(input, now) {
      // Open registration invites clutter: forget clients nobody used, and refuse a flood.
      await db.query(
        `DELETE FROM oauth_clients
          WHERE last_authorized_at IS NULL AND created_at < $1::timestamptz`,
        [later(now, -UNUSED_CLIENT_SECONDS)],
      );
      const pending = await db.query<{ n: string }>(
        "SELECT count(*)::text AS n FROM oauth_clients WHERE last_authorized_at IS NULL",
      );
      if (Number(pending.rows[0]?.n ?? 0) >= MAX_PENDING_CLIENTS) throw new TooManyClientsError();

      const clientId = random("odc_");
      const clientSecret = input.authMethod === "none" ? null : random("ods_");
      await db.query(
        `INSERT INTO oauth_clients
           (client_id, client_secret_sha256, client_name, redirect_uris, token_endpoint_auth_method, created_at)
         VALUES ($1, $2, $3, $4::jsonb, $5, $6::timestamptz)`,
        [
          clientId,
          clientSecret === null ? null : sha256Hex(clientSecret),
          input.clientName,
          JSON.stringify(input.redirectUris),
          input.authMethod,
          iso(now),
        ],
      );
      return {
        client: {
          clientId,
          secretSha256: clientSecret === null ? null : sha256Hex(clientSecret),
          clientName: input.clientName,
          redirectUris: input.redirectUris,
          authMethod: input.authMethod,
        },
        clientSecret,
      };
    },

    async getClient(clientId) {
      const result = await db.query<ClientRow>(
        `SELECT client_id, client_secret_sha256, client_name, redirect_uris, token_endpoint_auth_method
           FROM oauth_clients WHERE client_id = $1`,
        [clientId],
      );
      const row = result.rows[0];
      return row === undefined ? null : toClient(row);
    },

    async markAuthorized(clientId, now) {
      await db.query(
        "UPDATE oauth_clients SET last_authorized_at = $2::timestamptz WHERE client_id = $1",
        [clientId, iso(now)],
      );
    },

    async createCode(input, now) {
      const code = random("odcode_");
      const familyId = randomBytes(16).toString("hex");
      await db.transaction(async (tx) => {
        await tx.query("INSERT INTO oauth_families (family_id, user_id) VALUES ($1, $2)", [
          familyId,
          input.userId,
        ]);
        await tx.query(
          `INSERT INTO oauth_codes
             (code_sha256, client_id, redirect_uri, code_challenge, scope, resource, family_id, expires_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8::timestamptz)`,
          [
            sha256Hex(code),
            input.clientId,
            input.redirectUri,
            input.codeChallenge,
            input.scope,
            input.resource,
            familyId,
            later(now, lifetimes.codeSeconds),
          ],
        );
      });
      return code;
    },

    async findCode(code, now) {
      const result = await db.query<{
        client_id: string;
        redirect_uri: string;
        code_challenge: string;
        scope: string;
        resource: string;
        family_id: string;
        expired: boolean;
        consumed: boolean;
      }>(
        `SELECT client_id, redirect_uri, code_challenge, scope, resource, family_id,
                (expires_at <= $2::timestamptz) AS expired, (consumed_at IS NOT NULL) AS consumed
           FROM oauth_codes WHERE code_sha256 = $1`,
        [sha256Hex(code), iso(now)],
      );
      const row = result.rows[0];
      return row === undefined
        ? null
        : {
            clientId: row.client_id,
            redirectUri: row.redirect_uri,
            codeChallenge: row.code_challenge,
            scope: row.scope,
            resource: row.resource,
            familyId: row.family_id,
            expired: row.expired,
            consumed: row.consumed,
          };
    },

    async burnCode(code, now) {
      await db.query(
        "UPDATE oauth_codes SET consumed_at = $2::timestamptz WHERE code_sha256 = $1 AND consumed_at IS NULL",
        [sha256Hex(code), iso(now)],
      );
    },

    async exchangeCode(code, now) {
      return db.transaction(async (tx) => {
        const used = await tx.query<{
          client_id: string;
          scope: string;
          resource: string;
          family_id: string;
        }>(
          `UPDATE oauth_codes SET consumed_at = $2::timestamptz
            WHERE code_sha256 = $1 AND consumed_at IS NULL AND expires_at > $2::timestamptz
            RETURNING client_id, scope, resource, family_id`,
          [sha256Hex(code), iso(now)],
        );
        const row = used.rows[0];
        if (row === undefined) return null;
        return insertTokens(
          tx,
          {
            clientId: row.client_id,
            scope: row.scope,
            resource: row.resource,
            familyId: row.family_id,
          },
          now,
        );
      });
    },

    async findRefreshToken(token, now) {
      return findToken(db, token, "refresh", now, lifetimes.refreshReuseGraceSeconds);
    },

    async rotateRefreshToken(token, now) {
      return db.transaction(async (tx) => {
        const used = await tx.query<{
          client_id: string;
          scope: string;
          resource: string;
          family_id: string;
        }>(
          `UPDATE oauth_tokens SET used_at = COALESCE(used_at, $2::timestamptz)
            WHERE token_sha256 = $1 AND kind = 'refresh'
              AND (used_at IS NULL OR used_at > $3::timestamptz)
              AND revoked_at IS NULL AND expires_at > $2::timestamptz
            RETURNING client_id, scope, resource, family_id`,
          [sha256Hex(token), iso(now), later(now, -lifetimes.refreshReuseGraceSeconds)],
        );
        const row = used.rows[0];
        if (row === undefined) return null;
        return insertTokens(
          tx,
          {
            clientId: row.client_id,
            scope: row.scope,
            resource: row.resource,
            familyId: row.family_id,
          },
          now,
        );
      });
    },

    async findAccessToken(token, now) {
      const found = await findToken(db, token, "access", now, 0);
      if (found === null || found.expired || found.revoked) return null;
      return {
        userId: found.userId,
        clientId: found.clientId,
        scope: found.scope,
        resource: found.resource,
      };
    },

    revokeFamily,

    async revokeToken(token, clientId, now) {
      const sha = sha256Hex(token);
      const found = await db.query<{ kind: string; family_id: string }>(
        "SELECT kind, family_id FROM oauth_tokens WHERE token_sha256 = $1 AND client_id = $2",
        [sha, clientId],
      );
      const row = found.rows[0];
      if (row === undefined) return;
      if (row.kind === "refresh") {
        await revokeFamily(row.family_id, now);
      } else {
        await db.query(
          "UPDATE oauth_tokens SET revoked_at = $2::timestamptz WHERE token_sha256 = $1 AND revoked_at IS NULL",
          [sha, iso(now)],
        );
      }
    },

    async purge(now) {
      await db.query("DELETE FROM oauth_codes WHERE expires_at < $1::timestamptz", [
        later(now, -3600),
      ]);
      // A week after it expired a token tells nothing more (a used refresh token is kept until then
      // so that its reuse is still noticed).
      await db.query("DELETE FROM oauth_tokens WHERE expires_at < $1::timestamptz", [
        later(now, -7 * 24 * 3600),
      ]);
      // A family nothing refers to any more is forgotten with its last code or token.
      await db.query(
        `DELETE FROM oauth_families f
          WHERE NOT EXISTS (SELECT 1 FROM oauth_codes c WHERE c.family_id = f.family_id)
            AND NOT EXISTS (SELECT 1 FROM oauth_tokens t WHERE t.family_id = f.family_id)`,
      );
    },
  };
}

async function findToken(
  db: Queryable,
  token: string,
  kind: "access" | "refresh",
  now: Date,
  graceSeconds: number,
): Promise<TokenRecord | null> {
  const result = await db.query<{
    family_id: string;
    user_id: string;
    client_id: string;
    scope: string;
    resource: string;
    expired: boolean;
    used: boolean;
    just_now: boolean;
    revoked: boolean;
  }>(
    // The family's user must exist and be enabled: a disabled user's grants stop working at once.
    `SELECT t.family_id, f.user_id::text AS user_id, t.client_id, t.scope, t.resource,
            (t.expires_at <= $3::timestamptz) AS expired, (t.used_at IS NOT NULL) AS used,
            (t.used_at IS NOT NULL AND t.used_at > $4::timestamptz) AS just_now,
            (t.revoked_at IS NOT NULL) AS revoked
       FROM oauth_tokens t
       JOIN oauth_families f ON f.family_id = t.family_id
       JOIN users u ON u.id = f.user_id AND u.disabled_at IS NULL
      WHERE t.token_sha256 = $1 AND t.kind = $2`,
    [sha256Hex(token), kind, iso(now), later(now, -graceSeconds)],
  );
  const row = result.rows[0];
  return row === undefined
    ? null
    : {
        kind,
        familyId: row.family_id,
        userId: Number(row.user_id),
        clientId: row.client_id,
        scope: row.scope,
        resource: row.resource,
        expired: row.expired,
        used: row.used,
        usedJustNow: row.just_now,
        revoked: row.revoked,
      };
}
