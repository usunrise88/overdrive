import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:net";
import type { OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type {
  OAuthClientInformationMixed,
  OAuthClientMetadata,
  OAuthTokens,
} from "@modelcontextprotocol/sdk/shared/auth.js";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../../src/app.ts";
import type { Db } from "../../src/db.ts";
import type { McpTestOptions } from "../../src/mcp/index.ts";
import { hashPassphrase } from "../../src/oauth/passphrase.ts";
import { createServices, type Services } from "../../src/services.ts";
import { COMPANION, TABLET } from "./app.ts";
import type { FakeIntervals } from "./fake-intervals.ts";
import { FAKE_API_KEY, FAKE_ATHLETE_ID } from "./fake-intervals.ts";
import { collectingLogger, recordedSleep, type TestClock, testClock } from "./support.ts";

/** The owner's passphrase in the tests. Invented; the hash below is made from it with a cheap cost. */
export const PASSPHRASE = "correct horse battery staple";
export const OWNER_HASH: Promise<string> = hashPassphrase(PASSPHRASE, { N: 1024, r: 8, p: 1 });

/** A token of the static kind: at least 32 characters, invented. */
export const STATIC_TOKEN = "static-0123456789abcdefghijklmnopqrstuvwxyz";

/** A port nobody is listening on right now. */
export async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const probe = createServer();
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      const port = typeof address === "object" && address !== null ? address.port : 0;
      probe.close(() => resolve(port));
    });
  });
}

export interface McpHarness {
  app: FastifyInstance;
  services: Services;
  clock: TestClock;
  logging: ReturnType<typeof collectingLogger>;
  /** Every line the HTTP server logged. */
  appLogs: string[];
  /** `http://127.0.0.1:port`: the issuer, as PUBLIC_BASE_URL. */
  baseUrl: string;
  mcpUrl: URL;
  close(): Promise<void>;
}

export interface McpHarnessOptions {
  /** The test options of the OAuth server (lifetimes, lockout). */
  mcp?: Omit<McpTestOptions, "now">;
  /** false: no intervals.icu key. */
  configured?: boolean;
  staticTokens?: { name: string; token: string }[];
  /** Leave the MCP server unconfigured. */
  off?: boolean;
  now?: string;
  timezone?: string;
}

/**
 * The whole service with the MCP server on, listening on a local port, against the fake
 * intervals.icu: the real services over the given database, a clock the test controls.
 */
export async function startMcpHarness(
  db: Db,
  fake: FakeIntervals,
  options: McpHarnessOptions = {},
): Promise<McpHarness> {
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const clock = testClock(options.now ?? "2026-11-18T08:00:00Z");
  const logging = collectingLogger();
  const services = createServices({
    config: {
      ...(options.configured === false ? {} : { INTERVALS_API_KEY: FAKE_API_KEY }),
      INTERVALS_ATHLETE_ID: FAKE_ATHLETE_ID,
      INTERVALS_BASE_URL: fake.url,
      TIMEZONE: options.timezone ?? "Europe/Paris",
    },
    db,
    log: logging.log,
    now: clock.now,
    sleep: recordedSleep().sleep,
  });
  const appLogs: string[] = [];
  const app = buildApp({
    logStream: { write: (line) => appLogs.push(line) },
    config: {
      LOG_LEVEL: "info",
      DEVICE_TOKENS: [TABLET, COMPANION],
      ...(options.off === true
        ? {}
        : {
            PUBLIC_BASE_URL: baseUrl,
            OWNER_PASSPHRASE_HASH: await OWNER_HASH,
            MCP_STATIC_TOKENS: options.staticTokens ?? [],
          }),
    },
    db,
    services,
    mcp: { ...options.mcp, now: clock.now },
  });
  await app.listen({ host: "127.0.0.1", port });
  return {
    app,
    services,
    clock,
    logging,
    appLogs,
    baseUrl,
    mcpUrl: new URL(`${baseUrl}/mcp`),
    close: () => app.close(),
  };
}

// ---- PKCE and the authorization flow, by hand --------------------------------------------------

export function pkce(): { verifier: string; challenge: string } {
  const verifier = randomBytes(48).toString("base64url");
  return { verifier, challenge: createHash("sha256").update(verifier).digest("base64url") };
}

export const LOOPBACK_REDIRECT = "http://127.0.0.1:43111/callback";

export async function register(
  baseUrl: string,
  metadata: Record<string, unknown> = {},
): Promise<{ status: number; body: Record<string, unknown> }> {
  const response = await fetch(`${baseUrl}/oauth/register`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      client_name: "Test client",
      redirect_uris: [LOOPBACK_REDIRECT],
      token_endpoint_auth_method: "none",
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      ...metadata,
    }),
  });
  return { status: response.status, body: (await response.json()) as Record<string, unknown> };
}

export interface AuthorizeParams {
  clientId: string;
  redirectUri?: string;
  challenge: string;
  state?: string;
  scope?: string;
  resource?: string | null;
  method?: string;
  responseType?: string;
}

export function authorizeQuery(base: string, p: AuthorizeParams): URLSearchParams {
  const query = new URLSearchParams({
    response_type: p.responseType ?? "code",
    client_id: p.clientId,
    redirect_uri: p.redirectUri ?? LOOPBACK_REDIRECT,
    code_challenge: p.challenge,
    code_challenge_method: p.method ?? "S256",
    state: p.state ?? "xyz",
  });
  if (p.scope !== undefined) query.set("scope", p.scope);
  const resource = p.resource === undefined ? `${base}/mcp` : p.resource;
  if (resource !== null) query.set("resource", resource);
  return query;
}

/** Posts the authorization form the way a browser would and returns the response without following the redirect. */
export async function submitAuthorization(
  base: string,
  query: URLSearchParams,
  fields: { passphrase?: string; decision?: "approve" | "deny" },
): Promise<Response> {
  const body = new URLSearchParams(query);
  if (fields.passphrase !== undefined) body.set("passphrase", fields.passphrase);
  body.set("decision", fields.decision ?? "approve");
  return fetch(`${base}/oauth/authorize`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
    redirect: "manual",
  });
}

/** The code an approved authorization redirected back with. */
export function codeOf(response: Response): {
  code: string;
  state: string | null;
  iss: string | null;
} {
  if (response.status !== 302) throw new Error(`expected a redirect, got ${response.status}`);
  const location = new URL(response.headers.get("location") ?? "");
  const code = location.searchParams.get("code");
  if (code === null) throw new Error(`no code in ${location.search}`);
  return { code, state: location.searchParams.get("state"), iss: location.searchParams.get("iss") };
}

export async function tokenRequest(
  base: string,
  form: Record<string, string>,
  headers: Record<string, string> = {},
): Promise<{ status: number; body: Record<string, unknown>; headers: Headers }> {
  const response = await fetch(`${base}/oauth/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded", ...headers },
    body: new URLSearchParams(form),
  });
  return {
    status: response.status,
    body: (await response.json()) as Record<string, unknown>,
    headers: response.headers,
  };
}

export interface Tokens {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  token_type: string;
  scope: string;
}

/** A complete authorization with a public client, by hand: registers, approves, exchanges. */
export async function obtainTokens(
  base: string,
  options: { registration?: Record<string, unknown> } = {},
): Promise<{ clientId: string; tokens: Tokens }> {
  const registered = await register(base, options.registration);
  const clientId = String(registered.body.client_id);
  const { verifier, challenge } = pkce();
  const approved = await submitAuthorization(base, authorizeQuery(base, { clientId, challenge }), {
    passphrase: PASSPHRASE,
  });
  const { code } = codeOf(approved);
  const exchanged = await tokenRequest(base, {
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    code_verifier: verifier,
    redirect_uri: LOOPBACK_REDIRECT,
    resource: `${base}/mcp`,
  });
  if (exchanged.status !== 200)
    throw new Error(`token request failed: ${JSON.stringify(exchanged.body)}`);
  return { clientId, tokens: exchanged.body as unknown as Tokens };
}

// ---- The SDK's own client ----------------------------------------------------------------------

/**
 * The OAuth side of an MCP client, in memory, for the SDK's `StreamableHTTPClientTransport`: it
 * registers, and "redirects to the browser" by keeping the URL for the test to open.
 */
export class MemoryOAuthProvider implements OAuthClientProvider {
  authorizationUrl: URL | null = null;
  registrations = 0;
  private info: OAuthClientInformationMixed | undefined;
  private saved: OAuthTokens | undefined;
  private verifier = "";
  readonly redirectUrl: string;
  private readonly method: OAuthClientMetadata["token_endpoint_auth_method"];

  constructor(
    redirectUrl: string = LOOPBACK_REDIRECT,
    method: OAuthClientMetadata["token_endpoint_auth_method"] = "none",
  ) {
    this.redirectUrl = redirectUrl;
    this.method = method;
  }

  get clientMetadata(): OAuthClientMetadata {
    return {
      client_name: "SDK test client",
      redirect_uris: [this.redirectUrl],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: this.method,
    };
  }

  clientInformation(): OAuthClientInformationMixed | undefined {
    return this.info;
  }

  saveClientInformation(info: OAuthClientInformationMixed): void {
    this.registrations++;
    this.info = info;
  }

  tokens(): OAuthTokens | undefined {
    return this.saved;
  }

  saveTokens(tokens: OAuthTokens): void {
    this.saved = tokens;
  }

  redirectToAuthorization(url: URL): void {
    this.authorizationUrl = url;
  }

  saveCodeVerifier(verifier: string): void {
    this.verifier = verifier;
  }

  codeVerifier(): string {
    return this.verifier;
  }

  /** Forgets the access token, as when it has expired on the client's side. */
  forgetAccessToken(): void {
    if (this.saved !== undefined) this.saved = { ...this.saved, access_token: "expired" };
  }
}

export function newClient(): Client {
  return new Client({ name: "overdrive-test", version: "1.0.0" });
}

/** An SDK client connected with a fixed bearer token (the static kind, or one obtained by hand). */
export async function connectWithToken(mcpUrl: URL, token: string): Promise<Client> {
  const client = newClient();
  await client.connect(
    new StreamableHTTPClientTransport(mcpUrl, {
      requestInit: { headers: { authorization: `Bearer ${token}` } },
    }),
  );
  return client;
}

/** Calls a tool and returns its JSON result, or the error text when the tool failed. */
export async function callTool(
  client: Client,
  name: string,
  args: Record<string, unknown> = {},
): Promise<{ isError: boolean; json: unknown; text: string }> {
  const result = await client.callTool({ name, arguments: args });
  const first = (result.content as { type: string; text?: string }[])[0];
  const text = first?.text ?? "";
  let json: unknown = null;
  if (result.isError !== true) json = JSON.parse(text);
  return { isError: result.isError === true, json, text };
}
