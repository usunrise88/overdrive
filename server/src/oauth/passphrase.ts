import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";

/**
 * The owner's passphrase (ADR 0018). The server never holds it: only a scrypt hash, in
 * `OWNER_PASSPHRASE_HASH`, written as `scrypt:N:r:p:<salt>:<hash>` (the last two base64url). The
 * separator is a colon because a dollar sign in a `.env` file is read by docker compose as a
 * variable. The parameters travel with the hash, so a later, stronger setting still verifies the
 * old hashes. The passphrase is normalised (NFKC) before it is hashed, so the same words typed
 * on another keyboard are the same passphrase.
 */

export interface ScryptParams {
  /** CPU and memory cost; a power of two. */
  N: number;
  r: number;
  p: number;
}

/** What `hash-passphrase` makes: 32 MiB and about 100 ms on a small server. */
export const DEFAULT_SCRYPT: ScryptParams = { N: 2 ** 15, r: 8, p: 1 };

const KEY_LENGTH = 32;
const SALT_LENGTH = 16;
/** A configured hash may not ask for more than this, so a mistyped value cannot exhaust the memory. */
const MAX_N = 2 ** 20;
const MIN_N = 2 ** 10;

/** A parsed `OWNER_PASSPHRASE_HASH`. */
export interface PassphraseHash extends ScryptParams {
  salt: Buffer;
  hash: Buffer;
}

export const MIN_PASSPHRASE_LENGTH = 12;

function derive(passphrase: string, salt: Buffer, params: ScryptParams): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(
      passphrase.normalize("NFKC"),
      salt,
      KEY_LENGTH,
      { N: params.N, r: params.r, p: params.p, maxmem: 256 * params.N * params.r },
      (error, key) => (error === null ? resolve(key) : reject(error)),
    );
  });
}

/** `scrypt:32768:8:1:<salt>:<hash>` for a new random salt. */
export async function hashPassphrase(
  passphrase: string,
  params: ScryptParams = DEFAULT_SCRYPT,
): Promise<string> {
  const salt = randomBytes(SALT_LENGTH);
  const key = await derive(passphrase, salt, params);
  return `scrypt:${params.N}:${params.r}:${params.p}:${salt.toString("base64url")}:${key.toString("base64url")}`;
}

/** Reads a stored hash. The error says what is wrong with it and never repeats it. */
export function parsePassphraseHash(text: string): PassphraseHash {
  const parts = text.split(":");
  if (parts.length !== 6 || parts[0] !== "scrypt") {
    throw new Error("is not a passphrase hash: make one with `pnpm run hash-passphrase`");
  }
  const [, n, r, p, salt, hash] = parts as [string, string, string, string, string, string];
  const params = { N: Number(n), r: Number(r), p: Number(p) };
  const power = Number.isInteger(params.N) && params.N > 0 && (params.N & (params.N - 1)) === 0;
  if (!power || params.N < MIN_N || params.N > MAX_N) {
    throw new Error("has a scrypt cost (N) that is not a power of two between 2^10 and 2^20");
  }
  if (!Number.isInteger(params.r) || params.r < 1 || params.r > 32) {
    throw new Error("has a scrypt block size (r) outside 1 to 32");
  }
  if (!Number.isInteger(params.p) || params.p < 1 || params.p > 16) {
    throw new Error("has a scrypt parallelism (p) outside 1 to 16");
  }
  const saltBytes = Buffer.from(salt, "base64url");
  const hashBytes = Buffer.from(hash, "base64url");
  if (saltBytes.length < 8) throw new Error("has a salt that is too short");
  if (hashBytes.length !== KEY_LENGTH) throw new Error("has a hash of the wrong length");
  return { ...params, salt: saltBytes, hash: hashBytes };
}

/** Whether `passphrase` is the one `stored` was made from. Constant time in the comparison. */
export async function verifyPassphrase(
  passphrase: string,
  stored: PassphraseHash,
): Promise<boolean> {
  const key = await derive(passphrase, stored.salt, stored);
  return timingSafeEqual(key, stored.hash);
}

/**
 * Runs one verification at a time. Each costs tens of megabytes, and the page that asks for the
 * passphrase is open to the internet.
 */
export function serialVerifier(stored: PassphraseHash): (passphrase: string) => Promise<boolean> {
  let tail: Promise<unknown> = Promise.resolve();
  return (passphrase) => {
    const run = tail.then(() => verifyPassphrase(passphrase, stored));
    tail = run.catch(() => {});
    return run;
  };
}
