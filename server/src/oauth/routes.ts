import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import { authorizePage, messagePage } from "./pages.ts";
import { isLoopbackHost, redirectUriMatches, redirectUriProblem } from "./redirect.ts";
import {
  type ClientAuthMethod,
  type IssuedTokens,
  type OAuthClient,
  type OAuthStore,
  TooManyClientsError,
} from "./store.ts";
import type { LoginLockout, RateLimiter } from "./throttle.ts";

/** The one scope: everything the MCP tools can do. */
export const MCP_SCOPE = "mcp";
const SUPPORTED_SCOPES = [MCP_SCOPE];
const AUTH_METHODS: ClientAuthMethod[] = ["none", "client_secret_basic", "client_secret_post"];
const MAX_REDIRECT_URIS = 5;
const MAX_CLIENT_NAME = 100;
const CHALLENGE = /^[A-Za-z0-9_-]{43}$/;
const VERIFIER = /^[A-Za-z0-9\-._~]{43,128}$/;

export interface OAuthRoutesOptions {
  /** `https://overdrive.llmto.day`: the issuer, with no trailing slash. */
  baseUrl: string;
  store: OAuthStore;
  /** Whether a typed passphrase is the owner's; one at a time (see `serialVerifier`). */
  verifyPassphrase: (passphrase: string) => Promise<boolean>;
  lockout: LoginLockout;
  registrations: RateLimiter;
  now: () => Date;
  log: Logger;
}

type Params = URLSearchParams;

/** The address of the MCP endpoint, which is what an access token is issued for. */
export const mcpResourceOf = (baseUrl: string): string => `${baseUrl}/mcp`;

/** The address of the resource's metadata, which a 401 points clients at (RFC 9728). */
export const resourceMetadataUrlOf = (baseUrl: string): string =>
  `${baseUrl}/.well-known/oauth-protected-resource/mcp`;

function digest(value: string): Buffer {
  return createHash("sha256").update(value).digest();
}

/** The base64url of the SHA-256 of a code verifier: the S256 code challenge (RFC 7636). */
export function s256Challenge(verifier: string): string {
  return createHash("sha256").update(verifier).digest("base64url");
}

/**
 * The OAuth 2.1 authorization server of the MCP endpoint (ADR 0018), at the root of the service, not
 * under `/v1`: discovery documents, dynamic client registration (RFC 7591), the authorization
 * endpoint with the owner's passphrase page, the token endpoint (authorization code with PKCE S256,
 * refresh tokens that rotate) and revocation (RFC 7009).
 */
export function oauthRoutes(app: FastifyInstance, options: OAuthRoutesOptions): void {
  const { baseUrl, store, lockout, log } = options;
  const resource = mcpResourceOf(baseUrl);

  // The token endpoint and the authorization page take forms.
  app.addContentTypeParser(
    "application/x-www-form-urlencoded",
    { parseAs: "string", bodyLimit: 32 * 1024 },
    (_request, body, done) => done(null, new URLSearchParams(body as string)),
  );

  // Failures the framework raises (a body that is not JSON, one that is too large) answer in OAuth's
  // shape here, the way the rest of the service answers in its own.
  app.setErrorHandler((error: Error & { statusCode?: number }, request, reply) => {
    const status = error.statusCode ?? 500;
    if (status >= 500) {
      request.log.error({ err: error }, "oauth request failed");
      return reply.code(500).send({ error: "server_error" });
    }
    return reply
      .code(status)
      .send({ error: "invalid_request", error_description: "The request could not be read" });
  });

  const oauthError = (
    reply: FastifyReply,
    status: number,
    error: string,
    description: string,
  ): FastifyReply =>
    reply
      .code(status)
      .header("cache-control", "no-store")
      .header("pragma", "no-cache")
      .send({ error, error_description: description });

  /** The canonical form of a resource indicator; ours is the MCP endpoint (or the bare address). */
  const resourceIsOurs = (value: string): boolean => {
    let url: URL;
    try {
      url = new URL(value);
    } catch {
      return false;
    }
    if (url.hash !== "" || url.search !== "") return false;
    const canonical = `${url.origin}${url.pathname.replace(/\/+$/, "")}`;
    return canonical === baseUrl || canonical === resource;
  };

  // ---- Discovery -------------------------------------------------------------------------------

  const discovery = (reply: FastifyReply, body: object): FastifyReply =>
    reply
      .header("access-control-allow-origin", "*")
      .header("cache-control", "public, max-age=300")
      .send(body);

  const protectedResource = {
    resource,
    authorization_servers: [baseUrl],
    scopes_supported: SUPPORTED_SCOPES,
    bearer_methods_supported: ["header"],
    resource_name: "Overdrive",
  };
  app.get("/.well-known/oauth-protected-resource", (_request, reply) =>
    discovery(reply, protectedResource),
  );
  app.get("/.well-known/oauth-protected-resource/mcp", (_request, reply) =>
    discovery(reply, protectedResource),
  );

  app.get("/.well-known/oauth-authorization-server", (_request, reply) =>
    discovery(reply, {
      issuer: baseUrl,
      authorization_endpoint: `${baseUrl}/oauth/authorize`,
      token_endpoint: `${baseUrl}/oauth/token`,
      registration_endpoint: `${baseUrl}/oauth/register`,
      revocation_endpoint: `${baseUrl}/oauth/revoke`,
      scopes_supported: SUPPORTED_SCOPES,
      response_types_supported: ["code"],
      response_modes_supported: ["query"],
      grant_types_supported: ["authorization_code", "refresh_token"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: AUTH_METHODS,
      revocation_endpoint_auth_methods_supported: AUTH_METHODS,
      authorization_response_iss_parameter_supported: true,
      client_id_metadata_document_supported: false,
    }),
  );

  // ---- Dynamic client registration (RFC 7591) --------------------------------------------------

  app.post("/oauth/register", { bodyLimit: 16 * 1024 }, async (request, reply) => {
    const invalid = (error: string, description: string) =>
      oauthError(reply, 400, error, description);
    if (!options.registrations.take(options.now())) {
      reply.header("retry-after", "3600");
      return oauthError(reply, 429, "temporarily_unavailable", "Too many registrations; try later");
    }
    const body = request.body;
    if (typeof body !== "object" || body === null || Array.isArray(body)) {
      return invalid("invalid_client_metadata", "The body must be a JSON object");
    }
    const meta = body as Record<string, unknown>;

    const uris = meta.redirect_uris;
    if (
      !Array.isArray(uris) ||
      uris.length < 1 ||
      uris.length > MAX_REDIRECT_URIS ||
      !uris.every((uri): uri is string => typeof uri === "string")
    ) {
      return invalid(
        "invalid_redirect_uri",
        `redirect_uris must list 1 to ${MAX_REDIRECT_URIS} addresses`,
      );
    }
    for (const uri of uris) {
      const problem = redirectUriProblem(uri);
      if (problem !== null) return invalid("invalid_redirect_uri", `A redirect address ${problem}`);
    }

    let authMethod: ClientAuthMethod = "client_secret_basic";
    if (meta.token_endpoint_auth_method !== undefined) {
      if (!AUTH_METHODS.includes(meta.token_endpoint_auth_method as ClientAuthMethod)) {
        return invalid(
          "invalid_client_metadata",
          `token_endpoint_auth_method must be one of ${AUTH_METHODS.join(", ")}`,
        );
      }
      authMethod = meta.token_endpoint_auth_method as ClientAuthMethod;
    }
    const grants = meta.grant_types;
    if (
      grants !== undefined &&
      !(
        Array.isArray(grants) &&
        grants.every((g) => g === "authorization_code" || g === "refresh_token")
      )
    ) {
      return invalid(
        "invalid_client_metadata",
        "grant_types may only be authorization_code and refresh_token",
      );
    }
    const responseTypes = meta.response_types;
    if (
      responseTypes !== undefined &&
      !(Array.isArray(responseTypes) && responseTypes.every((t) => t === "code"))
    ) {
      return invalid("invalid_client_metadata", "response_types may only be code");
    }
    const scopeText = meta.scope;
    if (scopeText !== undefined) {
      if (
        typeof scopeText !== "string" ||
        !scopeText.split(" ").every((s) => s === "" || SUPPORTED_SCOPES.includes(s))
      ) {
        return invalid(
          "invalid_client_metadata",
          `scope may only name: ${SUPPORTED_SCOPES.join(" ")}`,
        );
      }
    }
    let clientName = "MCP client";
    if (meta.client_name !== undefined) {
      if (typeof meta.client_name !== "string") {
        return invalid("invalid_client_metadata", "client_name must be text");
      }
      // The name is shown on the passphrase page: no control characters, a sensible length.
      const cleaned = Array.from(meta.client_name)
        .filter((char) => char >= " " && char !== "\u007f")
        .join("")
        .trim()
        .slice(0, MAX_CLIENT_NAME);
      if (cleaned !== "") clientName = cleaned;
    }

    let registered: Awaited<ReturnType<OAuthStore["registerClient"]>>;
    try {
      registered = await store.registerClient(
        { clientName, redirectUris: uris, authMethod },
        options.now(),
      );
    } catch (error) {
      if (error instanceof TooManyClientsError) {
        reply.header("retry-after", "3600");
        return oauthError(reply, 429, "temporarily_unavailable", "Too many clients are waiting");
      }
      throw error;
    }
    log.info(
      { clientId: registered.client.clientId, clientName, method: authMethod },
      "oauth client registered",
    );
    return reply
      .code(201)
      .header("cache-control", "no-store")
      .send({
        client_id: registered.client.clientId,
        client_id_issued_at: Math.floor(options.now().getTime() / 1000),
        ...(registered.clientSecret === null
          ? {}
          : { client_secret: registered.clientSecret, client_secret_expires_at: 0 }),
        redirect_uris: uris,
        client_name: clientName,
        grant_types: grants ?? ["authorization_code", "refresh_token"],
        response_types: ["code"],
        token_endpoint_auth_method: authMethod,
        scope: MCP_SCOPE,
      });
  });

  // ---- The authorization endpoint --------------------------------------------------------------

  /** What an authorization request asks for, once checked. */
  interface Approval {
    client: OAuthClient;
    redirectUri: string;
    state: string | undefined;
    codeChallenge: string;
    scope: string;
  }

  type Parsed =
    | { ok: true; request: Approval }
    /** The client or its redirect address cannot be trusted: tell the owner, do not redirect. */
    | { ok: false; page: string }
    /** The client is known and the address is registered: send the error back to it. */
    | { ok: false; redirect: string };

  const redirectWith = (uri: string, query: Record<string, string | undefined>): string => {
    const url = new URL(uri);
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, value);
    }
    return url.toString();
  };

  async function parseAuthorize(params: Params): Promise<Parsed> {
    const refuse = (message: string): Parsed => ({
      ok: false,
      page: messagePage("This request cannot be answered", message),
    });
    const clientId = params.get("client_id");
    if (clientId === null || clientId === "") return refuse("The request names no client.");
    const client = await store.getClient(clientId);
    if (client === null) return refuse("The client is not registered.");

    const asked = params.get("redirect_uri");
    let redirectUri: string;
    if (asked === null || asked === "") {
      const only = client.redirectUris.length === 1 ? client.redirectUris[0] : undefined;
      if (only === undefined) return refuse("The request names no address to go back to.");
      redirectUri = only;
    } else if (client.redirectUris.some((registered) => redirectUriMatches(registered, asked))) {
      redirectUri = asked;
    } else {
      return refuse("The address to go back to is not one this client registered.");
    }

    const state = params.get("state") ?? undefined;
    const fail = (error: string, description: string): Parsed => ({
      ok: false,
      redirect: redirectWith(redirectUri, {
        error,
        error_description: description,
        state,
        iss: baseUrl,
      }),
    });
    if (params.get("response_type") !== "code") {
      return fail("unsupported_response_type", "response_type must be code");
    }
    const challenge = params.get("code_challenge");
    if (challenge === null || !CHALLENGE.test(challenge)) {
      return fail("invalid_request", "code_challenge is missing or is not an S256 challenge");
    }
    if (params.get("code_challenge_method") !== "S256") {
      return fail("invalid_request", "code_challenge_method must be S256");
    }
    const scopes = (params.get("scope") ?? MCP_SCOPE).split(" ").filter((s) => s !== "");
    if (scopes.length === 0 || !scopes.every((s) => SUPPORTED_SCOPES.includes(s))) {
      return fail("invalid_scope", `scope may only name: ${SUPPORTED_SCOPES.join(" ")}`);
    }
    const resources = params.getAll("resource");
    if (resources.length > 1 || (resources[0] !== undefined && !resourceIsOurs(resources[0]))) {
      return fail("invalid_target", `resource must be ${resource}`);
    }
    return {
      ok: true,
      request: { client, redirectUri, state, codeChallenge: challenge, scope: scopes.join(" ") },
    };
  }

  const pageHeaders = (reply: FastifyReply): FastifyReply =>
    reply
      .header("cache-control", "no-store")
      .header("content-type", "text/html; charset=utf-8")
      .header("referrer-policy", "no-referrer")
      .header("x-frame-options", "DENY")
      .header(
        "content-security-policy",
        "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
      );

  /** The form again, with everything the request carried, and a line about what went wrong. */
  const formPage = (approval: Approval, params: Params, problem?: string): string => {
    const redirect = new URL(approval.redirectUri);
    const hidden: Record<string, string> = {
      client_id: approval.client.clientId,
      redirect_uri: approval.redirectUri,
      response_type: "code",
      code_challenge: approval.codeChallenge,
      code_challenge_method: "S256",
      scope: approval.scope,
    };
    const resourceParam = params.get("resource");
    if (resourceParam !== null) hidden.resource = resourceParam;
    if (approval.state !== undefined) hidden.state = approval.state;
    return authorizePage({
      clientName: approval.client.clientName,
      redirectHost: redirect.host,
      redirectIsLocal: redirect.protocol === "http:" && isLoopbackHost(redirect.hostname),
      hidden,
      ...(problem === undefined ? {} : { problem }),
    });
  };

  app.get("/oauth/authorize", async (request, reply) => {
    const params = new URL(request.url, "http://localhost").searchParams;
    const parsed = await parseAuthorize(params);
    if (!parsed.ok) {
      if ("redirect" in parsed) return reply.redirect(parsed.redirect);
      return pageHeaders(reply).code(400).send(parsed.page);
    }
    const state = lockout.state(options.now());
    const problem = state.locked ? lockedMessage(state.retryAfterSeconds) : undefined;
    return pageHeaders(reply).send(formPage(parsed.request, params, problem));
  });

  const lockedMessage = (seconds: number): string =>
    `Too many wrong passphrases. Try again in ${Math.max(1, Math.ceil(seconds / 60))} minutes.`;

  app.post("/oauth/authorize", { bodyLimit: 16 * 1024 }, async (request, reply) => {
    const params = request.body instanceof URLSearchParams ? request.body : null;
    if (params === null) {
      return pageHeaders(reply)
        .code(400)
        .send(messagePage("This request cannot be answered", "The form was not understood."));
    }
    const parsed = await parseAuthorize(params);
    if (!parsed.ok) {
      if ("redirect" in parsed) return reply.redirect(parsed.redirect);
      return pageHeaders(reply).code(400).send(parsed.page);
    }
    const approval = parsed.request;

    if (params.get("decision") === "deny") {
      log.info({ clientId: approval.client.clientId }, "oauth authorization denied by the owner");
      return reply.redirect(
        redirectWith(approval.redirectUri, {
          error: "access_denied",
          error_description: "The owner refused",
          state: approval.state,
          iss: baseUrl,
        }),
      );
    }

    const now = options.now();
    const before = lockout.state(now);
    if (before.locked) {
      reply.header("retry-after", String(before.retryAfterSeconds));
      return pageHeaders(reply)
        .code(429)
        .send(formPage(approval, params, lockedMessage(before.retryAfterSeconds)));
    }
    const passphrase = params.get("passphrase") ?? "";
    if (passphrase === "") {
      return pageHeaders(reply)
        .code(400)
        .send(formPage(approval, params, "Type your passphrase."));
    }
    if (!(await options.verifyPassphrase(passphrase))) {
      const after = lockout.failed(options.now());
      log.warn({ clientId: approval.client.clientId, locked: after.locked }, "wrong passphrase");
      const problem = after.locked
        ? lockedMessage(after.retryAfterSeconds)
        : `That is not the passphrase. ${after.failuresLeft} ${after.failuresLeft === 1 ? "try" : "tries"} left.`;
      if (after.locked) reply.header("retry-after", String(after.retryAfterSeconds));
      return pageHeaders(reply)
        .code(after.locked ? 429 : 401)
        .send(formPage(approval, params, problem));
    }

    lockout.succeeded();
    const issuedAt = options.now();
    await store.markAuthorized(approval.client.clientId, issuedAt);
    const code = await store.createCode(
      {
        clientId: approval.client.clientId,
        redirectUri: approval.redirectUri,
        codeChallenge: approval.codeChallenge,
        scope: approval.scope,
        resource,
      },
      issuedAt,
    );
    log.info(
      { clientId: approval.client.clientId, clientName: approval.client.clientName },
      "oauth authorization approved by the owner",
    );
    void store.purge(issuedAt).catch((err: unknown) => log.error({ err }, "oauth purge failed"));
    return reply.redirect(
      redirectWith(approval.redirectUri, { code, state: approval.state, iss: baseUrl }),
    );
  });

  // ---- The token endpoint ----------------------------------------------------------------------

  /** Who is calling the token or revocation endpoint: a client proven by its secret, or a public one by its id. */
  async function authenticateClient(
    request: FastifyRequest,
    reply: FastifyReply,
    params: Params,
  ): Promise<OAuthClient | null> {
    let clientId = params.get("client_id");
    let secret = params.get("client_secret");
    let viaBasic = false;
    const header = request.headers.authorization;
    const basic = header === undefined ? null : /^Basic +([A-Za-z0-9+/=_-]+)$/i.exec(header);
    if (basic?.[1] !== undefined) {
      const text = Buffer.from(basic[1], "base64").toString("utf8");
      const colon = text.indexOf(":");
      if (colon > 0) {
        // The id and the secret are form-encoded before they are joined (RFC 6749 section 2.3.1).
        try {
          clientId = decodeURIComponent(text.slice(0, colon).replace(/\+/g, " "));
          secret = decodeURIComponent(text.slice(colon + 1).replace(/\+/g, " "));
          viaBasic = true;
        } catch {
          // Not encoded text: treated as no credentials.
        }
      }
    }
    const refuse = (): null => {
      if (viaBasic) reply.header("www-authenticate", 'Basic realm="overdrive"');
      oauthError(reply, 401, "invalid_client", "The client could not be authenticated");
      return null;
    };
    if (clientId === null || clientId === "") return refuse();
    const client = await store.getClient(clientId);
    if (client === null) return refuse();
    if (client.secretSha256 !== null) {
      if (
        secret === null ||
        !timingSafeEqual(digest(secret), Buffer.from(client.secretSha256, "hex"))
      ) {
        return refuse();
      }
    }
    return client;
  }

  const tokenResponse = (reply: FastifyReply, tokens: IssuedTokens): FastifyReply =>
    reply.header("cache-control", "no-store").header("pragma", "no-cache").send({
      access_token: tokens.accessToken,
      token_type: "Bearer",
      expires_in: tokens.expiresIn,
      refresh_token: tokens.refreshToken,
      scope: tokens.scope,
    });

  app.post("/oauth/token", { bodyLimit: 32 * 1024 }, async (request, reply) => {
    const params = request.body instanceof URLSearchParams ? request.body : null;
    if (params === null) {
      return oauthError(reply, 400, "invalid_request", "The body must be form-encoded");
    }
    const client = await authenticateClient(request, reply, params);
    if (client === null) return reply;

    const grantType = params.get("grant_type");
    const resources = params.getAll("resource");
    const badResource =
      resources.length > 1 || (resources[0] !== undefined && !resourceIsOurs(resources[0]));

    if (grantType === "authorization_code") {
      const code = params.get("code");
      const verifier = params.get("code_verifier");
      if (code === null || verifier === null) {
        return oauthError(reply, 400, "invalid_request", "code and code_verifier are required");
      }
      if (!VERIFIER.test(verifier)) {
        return oauthError(
          reply,
          400,
          "invalid_request",
          "code_verifier must be 43 to 128 characters from A-Z a-z 0-9 - . _ ~",
        );
      }
      const now = options.now();
      const record = await store.findCode(code, now);
      const grantFailed = (why: string) => oauthError(reply, 400, "invalid_grant", why);
      if (record === null || record.clientId !== client.clientId) {
        return grantFailed("The code is not valid");
      }
      if (record.consumed) {
        // A code seen twice: whoever has the tokens it made may not be the owner's client.
        await store.revokeFamily(record.familyId, now);
        log.warn({ clientId: client.clientId }, "oauth code used twice: its tokens were revoked");
        return grantFailed("The code was already used");
      }
      if (record.expired) return grantFailed("The code has expired");
      // From here a failed check uses the code up: a guess gets one try.
      const redirectUri = params.get("redirect_uri");
      if (redirectUri !== null && redirectUri !== record.redirectUri) {
        await store.burnCode(code, now);
        return grantFailed("redirect_uri is not the one the code was issued for");
      }
      const challenge = Buffer.from(s256Challenge(verifier));
      const expected = Buffer.from(record.codeChallenge);
      if (challenge.length !== expected.length || !timingSafeEqual(challenge, expected)) {
        await store.burnCode(code, now);
        return grantFailed("code_verifier does not match the code challenge");
      }
      if (badResource) {
        await store.burnCode(code, now);
        return oauthError(reply, 400, "invalid_target", `resource must be ${resource}`);
      }
      const tokens = await store.exchangeCode(code, now);
      if (tokens === null) {
        await store.revokeFamily(record.familyId, now);
        return grantFailed("The code was already used");
      }
      return tokenResponse(reply, tokens);
    }

    if (grantType === "refresh_token") {
      const token = params.get("refresh_token");
      if (token === null) {
        return oauthError(reply, 400, "invalid_request", "refresh_token is required");
      }
      const now = options.now();
      const record = await store.findRefreshToken(token, now);
      const grantFailed = (why: string) => oauthError(reply, 400, "invalid_grant", why);
      if (record === null || record.clientId !== client.clientId || record.revoked) {
        return grantFailed("The refresh token is not valid");
      }
      if (record.used && !record.usedJustNow) {
        // A refresh token is good once (apart from the moments after it was used, for requests that
        // raced). Its reuse later means two parties hold it: end the whole family.
        await store.revokeFamily(record.familyId, now);
        log.warn(
          { clientId: client.clientId },
          "oauth refresh token reused: its family was revoked",
        );
        return grantFailed("The refresh token was already used");
      }
      if (record.expired) return grantFailed("The refresh token has expired");
      const asked = params.get("scope");
      if (
        asked !== null &&
        !asked.split(" ").every((s) => s === "" || record.scope.split(" ").includes(s))
      ) {
        return oauthError(reply, 400, "invalid_scope", "scope is wider than the original grant");
      }
      if (badResource) {
        return oauthError(reply, 400, "invalid_target", `resource must be ${resource}`);
      }
      const tokens = await store.rotateRefreshToken(token, now);
      if (tokens === null) {
        await store.revokeFamily(record.familyId, now);
        return grantFailed("The refresh token was already used");
      }
      return tokenResponse(reply, tokens);
    }

    return oauthError(
      reply,
      400,
      "unsupported_grant_type",
      "grant_type must be authorization_code or refresh_token",
    );
  });

  // ---- Revocation (RFC 7009) -------------------------------------------------------------------

  app.post("/oauth/revoke", { bodyLimit: 32 * 1024 }, async (request, reply) => {
    const params = request.body instanceof URLSearchParams ? request.body : null;
    if (params === null) {
      return oauthError(reply, 400, "invalid_request", "The body must be form-encoded");
    }
    const client = await authenticateClient(request, reply, params);
    if (client === null) return reply;
    const token = params.get("token");
    if (token === null || token === "") {
      return oauthError(reply, 400, "invalid_request", "token is required");
    }
    // Whatever the token is, even unknown or someone else's, the answer is the same (RFC 7009 section 2.2).
    await store.revokeToken(token, client.clientId, options.now());
    log.info(
      { clientId: client.clientId, hash: sha256Hex(token).slice(0, 8) },
      "oauth token revoked",
    );
    return reply.header("cache-control", "no-store").send({});
  });
}
