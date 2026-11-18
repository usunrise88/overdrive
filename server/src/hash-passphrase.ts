/**
 * Makes `OWNER_PASSPHRASE_HASH` (ADR 0018): reads the owner's passphrase and prints the line to put
 * in `.env`. Run it on any machine, `pnpm --filter @overdrive/server run hash-passphrase`
 * (or `node dist/hash-passphrase.js` inside the image).
 *
 * On a terminal it asks twice and shows nothing as you type; given a pipe it reads all of stdin
 * and drops one trailing newline. The passphrase itself is never printed or stored.
 */
import { hashPassphrase, MIN_PASSPHRASE_LENGTH } from "./oauth/passphrase.ts";

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

async function readAll(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks)
    .toString("utf8")
    .replace(/\r?\n$/, "");
}

async function main(): Promise<void> {
  let passphrase: string;
  if (process.stdin.isTTY) {
    passphrase = await readHidden("Passphrase: ");
    if (passphrase.length >= MIN_PASSPHRASE_LENGTH) {
      const again = await readHidden("Again: ");
      if (again !== passphrase) throw new Error("the two passphrases differ");
    }
  } else {
    passphrase = await readAll();
  }
  if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
    throw new Error(
      `the passphrase must have at least ${MIN_PASSPHRASE_LENGTH} characters; a few random words make a good one`,
    );
  }
  process.stdout.write(`OWNER_PASSPHRASE_HASH=${await hashPassphrase(passphrase)}\n`);
}

main().catch((error: unknown) => {
  process.stderr.write(`hash-passphrase: ${error instanceof Error ? error.message : error}\n`);
  process.exit(1);
});
