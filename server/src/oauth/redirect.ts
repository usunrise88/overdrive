/**
 * Which redirect addresses a client may register and be sent back to (ADR 0018). The MCP
 * authorization spec says they must be `localhost` or https, and be matched exactly against what
 * was registered. The one exception is the port of a loopback address (RFC 8252 section 7.3): an
 * app on the same machine picks a free port each time, so it cannot register one in advance.
 */

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
const MAX_LENGTH = 2000;

export function isLoopbackHost(hostname: string): boolean {
  return LOOPBACK_HOSTS.has(hostname);
}

/** Why `uri` cannot be a redirect address, or null when it can. */
export function redirectUriProblem(uri: string): string | null {
  if (uri.length > MAX_LENGTH) return `is longer than ${MAX_LENGTH} characters`;
  let url: URL;
  try {
    url = new URL(uri);
  } catch {
    return "is not an absolute address";
  }
  if (url.hash !== "") return "must not have a fragment";
  if (url.username !== "" || url.password !== "") return "must not contain credentials";
  if (url.protocol === "https:") return null;
  if (url.protocol === "http:" && isLoopbackHost(url.hostname)) return null;
  return "must be https, or http on localhost";
}

/**
 * Whether the address a client asks to be sent back to is one it registered: the same text, or for a
 * loopback address the same apart from the port.
 */
export function redirectUriMatches(registered: string, requested: string): boolean {
  if (registered === requested) return true;
  let a: URL;
  let b: URL;
  try {
    a = new URL(registered);
    b = new URL(requested);
  } catch {
    return false;
  }
  return (
    a.protocol === "http:" &&
    b.protocol === "http:" &&
    isLoopbackHost(a.hostname) &&
    a.hostname === b.hostname &&
    a.pathname === b.pathname &&
    a.search === b.search
  );
}
