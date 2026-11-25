import type { FastifyReply, FastifyRequest } from "fastify";
import { sendError } from "./errors.ts";
import { sha256Hex } from "./hash.ts";
import type { TenantRuntimeDirectory } from "./tenancy/context.ts";

declare module "fastify" {
  interface FastifyRequest {
    /** The name of the device whose token authenticated this request; null before authentication. */
    device: string | null;
  }
}

/**
 * Bearer-token authentication for the `/v1` API (ADR 0014 section 4, multi-tenancy ADR 0044): `Authorization: Bearer <token>`,
 * one token per device. The token's SHA-256 digest is looked up in the directory, which compares it with every known
 * digest using `timingSafeEqual` (equal length whatever the token's, no early exit on the first match), so the time taken
 * does not tell a guess how close it was. An unknown or revoked token, and the token of a disabled user, are all the same
 * 401; with no token known every request is rejected. On success `request.tenant` is the caller's user with their
 * services and database, and `request.device` the token's name.
 */
export function tenantAuth(directory: TenantRuntimeDirectory) {
  return async function authenticate(
    request: FastifyRequest,
    reply: FastifyReply,
  ): Promise<FastifyReply | undefined> {
    const header = request.headers.authorization;
    const match = header === undefined ? null : /^Bearer +([^ ]+)$/i.exec(header);
    // The lookup is made (and takes the same time) even without a usable header.
    const hit = await directory.byToken(sha256Hex(match?.[1] ?? ""), "device");
    if (hit === null || match === null) {
      reply.header("www-authenticate", 'Bearer realm="overdrive"');
      return sendError(reply, "unauthorized", "Missing or invalid bearer token");
    }
    const runtime = directory.runtime(hit.tenant.userId);
    if (runtime === null || runtime.state === "disabled") {
      reply.header("www-authenticate", 'Bearer realm="overdrive"');
      return sendError(reply, "unauthorized", "Missing or invalid bearer token");
    }
    if (runtime.state !== "ready" || runtime.services === null || runtime.db === null) {
      request.log.error(
        { userId: hit.tenant.userId },
        "a request for a tenant that failed to start",
      );
      return sendError(
        reply,
        "internal_error",
        "This account is not available: ask the owner of the service",
      );
    }
    request.device = hit.tokenName;
    request.tenant = {
      userId: hit.tenant.userId,
      handle: hit.tenant.handle,
      device: hit.tokenName,
      services: runtime.services,
      db: runtime.db,
    };
    return undefined;
  };
}
