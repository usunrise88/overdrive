import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import type { DeviceToken } from "./config.ts";
import { sendError } from "./errors.ts";

declare module "fastify" {
  interface FastifyRequest {
    /** The name of the device whose token authenticated this request; null before authentication. */
    device: string | null;
  }
}

const digest = (secret: string): Buffer => createHash("sha256").update(secret).digest();

/**
 * Bearer-token authentication for the `/v1` API (ADR 0014 section 4): `Authorization: Bearer <token>`,
 * one token per device. The tokens are compared as SHA-256 digests with `timingSafeEqual` (equal
 * length whatever the token's, no early exit on the first match), so the time taken does not tell
 * a guess how close it was. With no token configured every request is rejected.
 */
export function bearerAuth(devices: readonly DeviceToken[]) {
  const known = devices.map((d) => ({ name: d.name, digest: digest(d.token) }));

  return async function authenticate(
    request: FastifyRequest,
    reply: FastifyReply,
  ): Promise<FastifyReply | undefined> {
    const header = request.headers.authorization;
    const match = header === undefined ? null : /^Bearer +([^ ]+)$/i.exec(header);
    const presented = digest(match?.[1] ?? "");
    let name: string | null = null;
    for (const device of known) {
      if (timingSafeEqual(device.digest, presented) && match !== null) name = device.name;
    }
    if (name === null) {
      reply.header("www-authenticate", 'Bearer realm="overdrive"');
      return sendError(reply, "unauthorized", "Missing or invalid bearer token");
    }
    request.device = name;
    return undefined;
  };
}
