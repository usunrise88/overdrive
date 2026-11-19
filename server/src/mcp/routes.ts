import { createHash, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";
import type { AuthInfo } from "@modelcontextprotocol/sdk/server/auth/types.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import type { DeviceToken } from "../config.ts";
import { MCP_SCOPE, mcpResourceOf, resourceMetadataUrlOf } from "../oauth/routes.ts";
import type { OAuthStore } from "../oauth/store.ts";
import { createMcpServer } from "./server.ts";
import type { ToolContext } from "./tool.ts";

export interface McpRoutesOptions {
  /** `https://overdrive.llmto.day`: where the discovery documents and `/mcp` are. */
  baseUrl: string;
  store: OAuthStore;
  /** Fixed tokens for clients that cannot do OAuth (`MCP_STATIC_TOKENS`). */
  staticTokens: readonly DeviceToken[];
  tools: ToolContext;
}

const digest = (secret: string): Buffer => createHash("sha256").update(secret).digest();

const OAUTH_ACCESS_TOKEN = /^odat_[A-Za-z0-9_-]{43}$/;

/** The largest request body: a workout is a few kilobytes. */
const BODY_LIMIT = 1024 * 1024;

const jsonRpcError = (code: number, message: string) => ({
  jsonrpc: "2.0" as const,
  error: { code, message },
  id: null,
});

/**
 * The MCP endpoint (spec §5.2, ADR 0018): Streamable HTTP at `POST /mcp`, stateless, each request
 * answered with one JSON document. It takes an OAuth access token issued for this endpoint (or a
 * configured static token) and nothing else; without one the answer is 401 with the address of the
 * resource metadata, which is how a client finds the authorization server. `GET` and `DELETE`
 * answer 405: there is no session and the server starts no stream of its own.
 */
export function mcpRoutes(app: FastifyInstance, options: McpRoutesOptions): void {
  const { baseUrl, store, tools } = options;
  const resource = mcpResourceOf(baseUrl);
  const challenge = `Bearer resource_metadata="${resourceMetadataUrlOf(baseUrl)}", scope="${MCP_SCOPE}"`;
  const staticTokens = options.staticTokens.map((t) => ({ name: t.name, digest: digest(t.token) }));

  // The body is read as text and parsed in the handler, so a body that is not JSON is answered in
  // JSON-RPC's own words.
  app.removeAllContentTypeParsers();
  app.addContentTypeParser(
    "application/json",
    { parseAs: "string", bodyLimit: BODY_LIMIT },
    (_request, body, done) => done(null, body),
  );

  /** Who the token belongs to, or null when it is none of ours or not for this endpoint. */
  async function identify(token: string): Promise<{ clientId: string; scopes: string[] } | null> {
    const presented = digest(token);
    let named: string | null = null;
    for (const entry of staticTokens) {
      if (timingSafeEqual(entry.digest, presented)) named = entry.name;
    }
    if (named !== null) return { clientId: `static:${named}`, scopes: [MCP_SCOPE] };
    if (!OAUTH_ACCESS_TOKEN.test(token)) return null;
    const grant = await store.findAccessToken(token, tools.now());
    // The token must have been issued for this endpoint and carry its scope (RFC 8707, MCP authorization).
    if (grant === null || grant.resource !== resource) return null;
    const scopes = grant.scope.split(" ");
    return scopes.includes(MCP_SCOPE) ? { clientId: grant.clientId, scopes } : null;
  }

  app.addHook("onRequest", async (request: FastifyRequest, reply: FastifyReply) => {
    const header = request.headers.authorization;
    const match = header === undefined ? null : /^Bearer +([^ ]+)$/i.exec(header);
    const who = match?.[1] === undefined ? null : await identify(match[1]);
    if (who === null) {
      const described = match === null ? "" : `, error="invalid_token"`;
      reply
        .code(401)
        .header("www-authenticate", `${challenge}${described}`)
        .send({
          error: {
            code: "unauthorized",
            message:
              match === null
                ? "This endpoint needs a bearer token"
                : "The token is not valid for this endpoint",
          },
        });
      return reply;
    }
    const auth: AuthInfo = { token: "", clientId: who.clientId, scopes: who.scopes };
    (request.raw as IncomingMessage & { auth?: AuthInfo }).auth = auth;
    return undefined;
  });

  app.post("/mcp", async (request, reply) => {
    let body: unknown;
    try {
      body = JSON.parse(request.body as string);
    } catch {
      return reply.code(400).send(jsonRpcError(-32700, "Parse error: the body is not JSON"));
    }
    // The tools are built for this token's scopes (the game master's notebook exists only for one that carries its scope).
    const auth = (request.raw as IncomingMessage & { auth?: AuthInfo }).auth;
    const server = createMcpServer(
      tools,
      auth === undefined ? undefined : { clientId: auth.clientId, scopes: auth.scopes },
    );
    // No session id generator means no session: every request stands alone.
    const transport = new StreamableHTTPServerTransport({ enableJsonResponse: true });
    reply.raw.on("close", () => {
      void transport.close();
      void server.close();
    });
    await server.connect(transport);
    // The transport writes the answer itself.
    reply.hijack();
    await transport.handleRequest(request.raw, reply.raw, body);
    return reply;
  });

  const notAllowed = (_request: FastifyRequest, reply: FastifyReply) =>
    reply
      .code(405)
      .header("allow", "POST")
      .send(jsonRpcError(-32000, "Method not allowed: use POST"));
  app.get("/mcp", notAllowed);
  app.delete("/mcp", notAllowed);
}
