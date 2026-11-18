/**
 * The one-time Strava authorisation (ADR 0020), for the owner's own terminal:
 *
 *   pnpm --filter @overdrive/server run strava-auth
 *   (or on the host: docker compose run --rm --no-deps -it server node dist/strava-auth.js)
 *
 * It asks for the application's Client ID and Client Secret (the secret is not shown as you type;
 * or set STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET for the one command), prints an address to open
 * in a browser, and reads the address the browser lands on after you approve (the page does not
 * load: copy the address from the bar). It then prints one line, `STRAVA_REFRESH_TOKEN=…`, to put
 * in the server's `.env`. The only permission asked for is `activity:write`.
 *
 * The token is printed to the terminal and nowhere else: not to a file, and the script refuses to
 * run when its output is not a terminal, so a redirect cannot save it by accident. Never paste it
 * into a chat, an issue or a commit.
 */
import { createInterface } from "node:readline/promises";
import { DEFAULT_STRAVA_BASE_URL } from "./config-strava.ts";
import {
  AuthorizeError,
  authorizeUrl,
  exchangeCode,
  parseRedirect,
  STRAVA_SCOPE,
} from "./strava/authorize.ts";

function readHidden(prompt: string): Promise<string> {
  const stdin = process.stdin;
  process.stderr.write(prompt);
  stdin.setRawMode(true);
  stdin.resume();
  stdin.setEncoding("utf8");
  return new Promise((resolve, reject) => {
    let value = "";
    const finish = (done: () => void): void => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.off("data", onData);
      process.stderr.write("\n");
      done();
    };
    const onData = (chunk: string): void => {
      for (const char of chunk) {
        if (char === "\r" || char === "\n" || char === "\u0004") {
          finish(() => resolve(value));
          return;
        }
        if (char === "\u0003") {
          finish(() => reject(new Error("cancelled")));
          return;
        }
        if (char === "\u007f" || char === "\b") value = Array.from(value).slice(0, -1).join("");
        else value += char;
      }
    };
    stdin.on("data", onData);
  });
}

async function readLine(prompt: string): Promise<string> {
  const lines = createInterface({ input: process.stdin, output: process.stderr });
  try {
    return await lines.question(prompt);
  } finally {
    lines.close();
  }
}

async function main(): Promise<void> {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error(
      "run this in a terminal, not through a pipe or a redirect: it prints a secret, which must not end up in a file",
    );
  }
  const baseUrl = (process.env.STRAVA_BASE_URL ?? "").trim() || DEFAULT_STRAVA_BASE_URL;
  const clientId =
    (process.env.STRAVA_CLIENT_ID ?? "").trim() || (await readLine("Strava Client ID: ")).trim();
  if (!/^[0-9]{1,12}$/.test(clientId)) {
    throw new Error("the Client ID is the whole number on strava.com/settings/api");
  }
  const clientSecret =
    (process.env.STRAVA_CLIENT_SECRET ?? "").trim() ||
    (await readHidden("Strava Client Secret: ")).trim();
  if (clientSecret === "") throw new Error("the Client Secret is empty");

  process.stderr.write(
    [
      "",
      `1. In strava.com/settings/api the "Authorization Callback Domain" must be: localhost`,
      "2. Open this address in a browser where you are signed in to Strava, and press Authorize:",
      "",
      `   ${authorizeUrl(clientId, baseUrl)}`,
      "",
      `   It asks only for permission to upload (${STRAVA_SCOPE}); it cannot read your activities.`,
      "3. The browser then goes to a page on localhost that does not load. That is expected:",
      "   copy the whole address from the address bar and paste it here.",
      "",
    ].join("\n"),
  );
  const pasted = await readLine("Address (or just the code): ");
  const { code } = parseRedirect(pasted);
  const { refreshToken } = await exchangeCode({ clientId, clientSecret, code, baseUrl });

  process.stderr.write("\nDone. Put this line in the server's .env, then restart the server:\n\n");
  process.stdout.write(`STRAVA_REFRESH_TOKEN=${refreshToken}\n`);
  process.stderr.write(
    "\nThe .env also needs STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET. Clear this terminal afterwards; do not paste the token anywhere else.\n",
  );
}

main().catch((error: unknown) => {
  const message =
    error instanceof AuthorizeError || error instanceof Error ? error.message : String(error);
  process.stderr.write(`strava-auth: ${message}\n`);
  process.exit(1);
});
