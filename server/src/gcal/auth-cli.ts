import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { pathToFileURL } from "node:url";

/**
 * The one-time helper that gets the Google refresh token (ADR 0019): `pnpm --filter @overdrive/server run google-auth`,
 * run by the owner in their own terminal. It is OAuth 2.0 for installed apps (the loopback flow with PKCE): it opens a
 * port on 127.0.0.1, prints an address for the owner to open in a browser, receives the code there (or from the address
 * the owner pastes back, when the browser is on another machine) and exchanges it for a refresh token, which it prints
 * to the terminal. Nothing is written to a file and nothing is sent anywhere but Google. Google's device flow cannot be
 * used: it does not allow the Calendar scope.
 */

export const DEFAULT_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
export const DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
/** Create, change and delete events on the calendars of the account; nothing else. */
export const CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events";

export interface AuthIo {
  print(text: string): void;
  /** Asks for a line; `secret` means it is not echoed. Rejects when `signal` aborts. */
  ask(prompt: string, options?: { secret?: boolean; signal?: AbortSignal }): Promise<string>;
}

export interface AuthOptions {
  env: Record<string, string | undefined>;
  io: AuthIo;
  fetch?: typeof fetch;
  authUrl?: string;
  tokenUrl?: string;
  /** The loopback port; 0 (default) lets the system pick one. */
  port?: number;
  /** How long to wait for the owner (default 10 minutes). */
  timeoutMs?: number;
}

function base64Url(bytes: Buffer): string {
  return bytes.toString("base64url");
}

/** Pulls `code` and `state` out of what the owner pasted: the address the browser ended on, or the bare code. */
export function parseRedirect(
  text: string,
): { code: string; state: string | null } | { error: string } {
  const trimmed = text.trim();
  if (trimmed === "") return { error: "nothing was pasted" };
  let url: URL | null = null;
  try {
    url = new URL(trimmed);
  } catch {
    url = null;
  }
  if (url === null) return { code: trimmed, state: null };
  const error = url.searchParams.get("error");
  if (error !== null) return { error: `Google answered: ${error}` };
  const code = url.searchParams.get("code");
  if (code === null || code === "") return { error: "the address has no code in it" };
  return { code, state: url.searchParams.get("state") };
}

/** Exchanges the code for tokens. Returns the refresh token, or says why there is none. Never repeats a secret. */
export async function exchangeCode(options: {
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  code: string;
  verifier: string;
  redirectUri: string;
  fetch?: typeof fetch;
}): Promise<{ refreshToken: string } | { error: string }> {
  const doFetch = options.fetch ?? fetch;
  let response: Response;
  try {
    response = await doFetch(options.tokenUrl, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
      body: new URLSearchParams({
        code: options.code,
        client_id: options.clientId,
        client_secret: options.clientSecret,
        code_verifier: options.verifier,
        redirect_uri: options.redirectUri,
        grant_type: "authorization_code",
      }).toString(),
      redirect: "error",
      signal: AbortSignal.timeout(30_000),
    });
  } catch {
    return { error: "Google's token endpoint could not be reached" };
  }
  const text = await response.text().catch(() => "");
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    body = undefined;
  }
  const fields = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
  if (response.status !== 200) {
    const reason = typeof fields.error === "string" ? fields.error : `HTTP ${response.status}`;
    const description =
      typeof fields.error_description === "string" ? `: ${fields.error_description}` : "";
    return { error: `Google refused the code (${reason}${description})` };
  }
  if (typeof fields.refresh_token !== "string" || fields.refresh_token === "") {
    return {
      error:
        "Google sent no refresh token. Remove this app's access at https://myaccount.google.com/permissions and run the helper again",
    };
  }
  return { refreshToken: fields.refresh_token };
}

/** Runs the whole flow; returns the exit code (0 on success). */
export async function runGoogleAuth(options: AuthOptions): Promise<number> {
  const { io, env } = options;
  const authUrl = options.authUrl ?? DEFAULT_AUTH_URL;
  const tokenUrl = options.tokenUrl ?? DEFAULT_TOKEN_URL;

  const clientId =
    env.GOOGLE_CLIENT_ID?.trim() || (await io.ask("Google OAuth client ID: ")).trim();
  const clientSecret =
    env.GOOGLE_CLIENT_SECRET?.trim() ||
    (await io.ask("Google OAuth client secret (not shown): ", { secret: true })).trim();
  if (clientId === "" || clientSecret === "") {
    io.print("A client ID and a client secret are needed (docs/deploy.md, section 13).\n");
    return 1;
  }

  const verifier = base64Url(randomBytes(48));
  const challenge = base64Url(createHash("sha256").update(verifier).digest());
  const state = base64Url(randomBytes(24));

  const abort = new AbortController();
  let deliver: (answer: ReturnType<typeof parseRedirect>) => void = () => {};
  const arrived = new Promise<ReturnType<typeof parseRedirect>>((resolve) => {
    deliver = resolve;
  });

  const server = createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (url.searchParams.has("code") || url.searchParams.has("error")) {
      response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
      response.end("Done. You can close this tab and go back to the terminal.\n");
      deliver(parseRedirect(url.toString()));
    } else {
      response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
      response.end("Not found\n");
    }
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(options.port ?? 0, "127.0.0.1", resolve);
  }).catch((error: unknown) => {
    const code = (error as { code?: unknown }).code;
    io.print(
      `Could not listen on 127.0.0.1${options.port ? `:${options.port}` : ""} (${typeof code === "string" ? code : "error"}).\n`,
    );
    throw error;
  });
  const port = (server.address() as AddressInfo).port;
  const redirectUri = `http://127.0.0.1:${port}`;

  const url = new URL(authUrl);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", CALENDAR_SCOPE);
  // offline + consent: Google then always sends a refresh token, even if the account has said yes before.
  url.searchParams.set("access_type", "offline");
  url.searchParams.set("prompt", "consent");
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "S256");

  io.print(
    [
      "",
      "1. Open this address in a browser and sign in with the Google account that owns the calendar:",
      "",
      url.toString(),
      "",
      `2. Allow access. The browser is then sent to ${redirectUri}.`,
      "   If this terminal and the browser are on the same computer, this program picks the answer up by itself.",
      "   If the page cannot be reached (the browser is on another computer), copy the whole address from the",
      "   browser's address bar and paste it here.",
      "",
    ].join("\n"),
  );

  const pasted = io
    .ask("Pasted address (or wait): ", { signal: abort.signal })
    .then((line) => parseRedirect(line))
    .catch(() => null);
  const timeout = new Promise<null>((resolve) => {
    const timer = setTimeout(() => resolve(null), options.timeoutMs ?? 10 * 60_000);
    timer.unref();
    abort.signal.addEventListener("abort", () => clearTimeout(timer), { once: true });
  });

  const answer = await Promise.race([arrived, pasted, timeout]);
  abort.abort();
  server.close();
  server.closeAllConnections();

  if (answer === null) {
    io.print("Nothing came back in time. Run the helper again.\n");
    return 1;
  }
  if ("error" in answer) {
    io.print(`${answer.error}.\n`);
    return 1;
  }
  if (answer.state !== null && answer.state !== state) {
    io.print("The answer did not come from this run (the state differs). Run the helper again.\n");
    return 1;
  }

  const result = await exchangeCode({
    tokenUrl,
    clientId,
    clientSecret,
    code: answer.code,
    verifier,
    redirectUri,
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
  if ("error" in result) {
    io.print(`${result.error}.\n`);
    return 1;
  }

  io.print(
    [
      "",
      "Done. Put this line in the server's .env (it is a secret: do not paste it into a chat, an issue or a commit):",
      "",
      `GOOGLE_REFRESH_TOKEN=${result.refreshToken}`,
      "",
      "Nothing was written to a file. Then: docker compose up -d server",
      "",
    ].join("\n"),
  );
  return 0;
}

/** The real terminal. */
async function terminalIo(): Promise<AuthIo & { close(): void }> {
  const { createInterface } = await import("node:readline/promises");
  const { Writable } = await import("node:stream");
  let muted = false;
  const output = new Writable({
    write(chunk, encoding, callback) {
      if (!muted) process.stdout.write(chunk, encoding);
      callback();
    },
  });
  const rl = createInterface({
    input: process.stdin,
    output,
    terminal: process.stdin.isTTY === true,
  });
  return {
    print: (text) => {
      process.stdout.write(text);
    },
    async ask(prompt, askOptions = {}) {
      process.stdout.write(prompt);
      muted = askOptions.secret === true;
      try {
        return await rl.question(
          "",
          askOptions.signal === undefined ? {} : { signal: askOptions.signal },
        );
      } finally {
        muted = false;
        if (askOptions.secret === true) process.stdout.write("\n");
      }
    },
    close: () => rl.close(),
  };
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.includes("--help") || args.includes("-h")) {
    process.stdout.write(
      [
        "Usage: pnpm --filter @overdrive/server run google-auth [-- --port 8085]",
        "",
        "Gets the refresh token for Google Calendar and prints it to this terminal only.",
        "Reads GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET from the environment, or asks for them.",
        "--port N (or GOOGLE_AUTH_PORT) fixes the loopback port, for an SSH tunnel; default: any free port.",
        "",
      ].join("\n"),
    );
    return;
  }
  const portIndex = args.indexOf("--port");
  const portText = portIndex >= 0 ? args[portIndex + 1] : process.env.GOOGLE_AUTH_PORT;
  const port = portText === undefined || portText === "" ? 0 : Number(portText);
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    process.stderr.write("The port must be a whole number from 0 to 65535.\n");
    process.exitCode = 2;
    return;
  }
  const io = await terminalIo();
  try {
    // GOOGLE_TOKEN_URL is the server's own setting for the same endpoint; unset, it is Google's.
    const tokenUrl = process.env.GOOGLE_TOKEN_URL?.trim();
    process.exitCode = await runGoogleAuth({
      env: process.env,
      io,
      port,
      ...(tokenUrl === undefined || tokenUrl === "" ? {} : { tokenUrl }),
    });
  } finally {
    io.close();
  }
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error: unknown) => {
    process.stderr.write(`${error instanceof Error ? error.message : "The helper failed"}\n`);
    process.exitCode = 1;
  });
}
