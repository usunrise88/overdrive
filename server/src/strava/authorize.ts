import { z } from "zod";
import { DEFAULT_STRAVA_BASE_URL } from "../config-strava.ts";
import { describeBody, describeFailure } from "./errors.ts";

/**
 * The one-time authorisation of the owner's Strava application (ADR 0020), as functions the
 * `strava-auth` script calls: the address to open, the reading of what the browser lands on, and
 * the exchange of the code for a refresh token. The only scope asked for is `activity:write`
 * (create uploads), which cannot read anything. The token response also carries a summary of the
 * athlete: it is dropped unread, like everything else Strava could tell us about the rider.
 */

export const STRAVA_SCOPE = "activity:write";

/**
 * Where Strava sends the browser after the owner approves. It need not exist: the page does not
 * load and the address bar holds the code, which is pasted into the terminal. `localhost` is
 * allowed by Strava as an Authorization Callback Domain.
 */
export const REDIRECT_URI = "http://localhost/overdrive-strava";

/** The address the owner opens in a browser to approve the application. */
export function authorizeUrl(clientId: string, baseUrl = DEFAULT_STRAVA_BASE_URL): string {
  const query = new URLSearchParams({
    client_id: clientId,
    response_type: "code",
    redirect_uri: REDIRECT_URI,
    approval_prompt: "force",
    scope: STRAVA_SCOPE,
  });
  return `${baseUrl.replace(/\/+$/, "")}/oauth/authorize?${query.toString()}`;
}

export class AuthorizeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthorizeError";
  }
}

/**
 * What the owner pasted: the whole address the browser ended on (`http://localhost/overdrive-strava?code=…&scope=…`),
 * just its query, or only the code. Refuses a denial and a grant that lacks `activity:write` (Strava lets
 * the owner untick scopes on its page).
 */
export function parseRedirect(pasted: string): { code: string; scope: string | null } {
  const text = pasted.trim();
  if (text === "") throw new AuthorizeError("nothing was pasted");
  let params: URLSearchParams;
  if (/^[\w-]+$/.test(text)) {
    return { code: text, scope: null };
  }
  try {
    params = text.includes("://")
      ? new URL(text).searchParams
      : new URLSearchParams(text.replace(/^\?/, ""));
  } catch {
    throw new AuthorizeError("that is not an address or a code");
  }
  const denied = params.get("error");
  if (denied !== null) {
    throw new AuthorizeError(
      denied === "access_denied"
        ? "the application was not approved (access_denied)"
        : `Strava answered with an error (${denied})`,
    );
  }
  const code = params.get("code");
  if (code === null || code === "") throw new AuthorizeError("the address has no code= in it");
  const scope = params.get("scope");
  if (scope !== null && !scope.split(/[\s,]+/).includes(STRAVA_SCOPE)) {
    throw new AuthorizeError(
      `Strava granted "${scope}", not ${STRAVA_SCOPE}: approve again and leave the upload permission ticked`,
    );
  }
  return { code, scope };
}

const exchangeResponse = z.looseObject({
  refresh_token: z.string().min(1),
  // Only to check the grant; `athlete` and everything else are not read.
  scope: z.string().optional(),
});

/** Exchanges the code for tokens and returns the refresh token. Throws {@link AuthorizeError} with a message fit to print. */
export async function exchangeCode(options: {
  clientId: string;
  clientSecret: string;
  code: string;
  baseUrl?: string;
  fetch?: typeof fetch;
}): Promise<{ refreshToken: string }> {
  const doFetch = options.fetch ?? fetch;
  const endpoint = `${(options.baseUrl ?? DEFAULT_STRAVA_BASE_URL).replace(/\/+$/, "")}/oauth/token`;
  let response: Response;
  try {
    response = await doFetch(endpoint, {
      method: "POST",
      headers: { accept: "application/json", "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: options.clientId,
        client_secret: options.clientSecret,
        code: options.code,
        grant_type: "authorization_code",
      }),
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
  } catch (cause) {
    throw new AuthorizeError(`Strava did not answer (${describeFailure(cause)})`);
  }
  const text = await response.text().catch(() => "");
  if (!response.ok) {
    const detail = describeBody(text);
    throw new AuthorizeError(
      `Strava refused the code (HTTP ${response.status}${detail === "" ? "" : `: ${detail}`}). A code works once and for a few minutes: start again, and check the client id and secret`,
    );
  }
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  const parsed = exchangeResponse.safeParse(body);
  if (!parsed.success) throw new AuthorizeError("Strava answered with no refresh token");
  const { scope } = parsed.data;
  if (scope !== undefined && !scope.split(/[\s,]+/).includes(STRAVA_SCOPE)) {
    throw new AuthorizeError(
      `Strava granted "${scope}", not ${STRAVA_SCOPE}: approve again and leave the upload permission ticked`,
    );
  }
  return { refreshToken: parsed.data.refresh_token };
}
